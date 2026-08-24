from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import socket
import sys
import time
from datetime import datetime, timezone
import xml.etree.ElementTree as ET


MICRO_BATCH = 1
RESULT_SCHEMA = "pathlab.model-worker-result/1"
PROGRESS_SCHEMA = "pathlab.model-worker-progress/1"
CHECKPOINT_SCHEMA = "pathlab.model-worker-checkpoint/1"
COHORT_SCHEMA = "pathlab.cell-qualification-cohort/1"
ORGANS = {"lung", "kidney", "breast", "prostate"}


def fail(message: str) -> None:
    raise RuntimeError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8-sig") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        fail(f"JSON object required: {path.name}")
    return value


def write_json_atomic(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + ".partial")
    with partial.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, sort_keys=True, separators=(",", ":"), allow_nan=False)
        stream.write("\n")
    os.replace(partial, path)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def require_offline(flag: bool) -> None:
    if not flag or os.environ.get("PATHLAB_ANALYSIS_NETWORK") != "disabled":
        fail("offline analysis contract was not enforced")
    if os.environ.get("HF_HUB_OFFLINE") != "1" or os.environ.get("TRANSFORMERS_OFFLINE") != "1":
        fail("model library offline flags were not enforced")


def disable_network() -> None:
    def blocked_socket(*_args, **_kwargs):
        raise OSError("network disabled for PathLab analysis")

    socket.socket = blocked_socket
    socket.create_connection = blocked_socket


def validate_manifest(install_root: Path, manifest_name: str, schema: str, content_name: str) -> None:
    manifest = load_json(install_root / manifest_name)
    root = (install_root / content_name).resolve()
    if not root.is_relative_to(install_root.resolve()) or not root.is_dir():
        fail(f"{manifest_name} content root is invalid")
    if manifest.get("schema") != schema or not isinstance(manifest.get("files"), list):
        fail(f"{manifest_name} is invalid")
    for item in manifest["files"]:
        if not isinstance(item, dict) or set(item) != {"path", "bytes", "sha256"}:
            fail(f"{manifest_name} file ledger is invalid")
        relative = Path(str(item["path"]))
        path = (root / relative).resolve()
        if relative.is_absolute() or not path.is_relative_to(root.resolve()) or not path.is_file():
            fail(f"{manifest_name} path is invalid")
        if path.stat().st_size != item["bytes"] or sha256(path) != item["sha256"]:
            fail(f"{manifest_name} checksum changed")


def validate_references(install_root: Path) -> tuple[Path, Path]:
    reference = load_json(install_root / "runtime-reference.json")
    required = {
        "schema", "sharedRuntimePack", "sharedRuntimeVersion", "sharedRuntimeRoot",
        "sharedRuntimeManifestSha256", "pythonRelativePath", "candidateId",
        "candidateVersion", "candidateRoot", "candidateLedgerSha256", "weightFile",
        "weightSha256", "runtimeCopiedIntoCandidate", "analysisNetwork",
    }
    if set(reference) != required or reference.get("schema") != "pathlab.model-runtime-reference/1":
        fail("runtime reference contract is invalid")
    if reference.get("runtimeCopiedIntoCandidate") is not False or reference.get("analysisNetwork") != "disabled":
        fail("runtime reference isolation is invalid")
    runtime_root = Path(str(reference["sharedRuntimeRoot"])).resolve()
    candidate_root = Path(str(reference["candidateRoot"])).resolve()
    models_root = install_root.parent.parent.resolve()
    if not runtime_root.is_relative_to(models_root) or not candidate_root.is_relative_to(models_root):
        fail("runtime or candidate reference escaped the model root")
    if runtime_root.name != str(reference["sharedRuntimeVersion"]):
        fail("shared runtime version binding is invalid")
    runtime_manifest = runtime_root / "runtime-manifest.json"
    candidate_ledger = candidate_root / "candidate-ledger.json"
    weight = candidate_root / str(reference["weightFile"])
    if not runtime_manifest.is_file() or sha256(runtime_manifest) != reference["sharedRuntimeManifestSha256"]:
        fail("shared runtime manifest checksum changed")
    if not candidate_ledger.is_file() or sha256(candidate_ledger) != reference["candidateLedgerSha256"]:
        fail("candidate ledger checksum changed")
    if not weight.is_file() or sha256(weight) != reference["weightSha256"]:
        fail("HoVer-Net weight checksum changed")
    python = (runtime_root / str(reference["pythonRelativePath"])).resolve()
    if not python.is_relative_to(runtime_root) or not python.is_file():
        fail("shared Python executable is unavailable")
    return python, weight


def working_set_mib() -> float:
    class Counters(ctypes.Structure):
        _fields_ = [
            ("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong),
            ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
            ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
            ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t),
        ]
    counters = Counters()
    counters.cb = ctypes.sizeof(counters)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    kernel32.GetCurrentProcess.restype = ctypes.c_void_p
    psapi.GetProcessMemoryInfo.argtypes = [ctypes.c_void_p, ctypes.POINTER(Counters), ctypes.c_ulong]
    psapi.GetProcessMemoryInfo.restype = ctypes.c_int
    if not psapi.GetProcessMemoryInfo(kernel32.GetCurrentProcess(), ctypes.byref(counters), counters.cb):
        fail("unable to read worker memory")
    return counters.PeakWorkingSetSize / (1024 * 1024)


def starts(size: int, output_size: int) -> list[int]:
    if size <= output_size:
        return [0]
    values = list(range(0, size - output_size + 1, output_size))
    if values[-1] != size - output_size:
        values.append(size - output_size)
    return values


def normalized(value):
    import numpy as np
    low = float(np.min(value))
    high = float(np.max(value))
    if high - low <= 1.0e-8:
        return np.zeros_like(value, dtype=np.float32)
    return ((value - low) / (high - low)).astype(np.float32)


def postprocess(probability, horizontal, vertical):
    import numpy as np
    from scipy import ndimage

    foreground = probability >= 0.5
    labels, _ = ndimage.label(foreground)
    sizes = np.bincount(labels.ravel())
    keep = sizes >= 10
    keep[0] = False
    foreground = keep[labels]
    h_sobel = 1.0 - normalized(ndimage.sobel(normalized(horizontal), axis=1))
    v_sobel = 1.0 - normalized(ndimage.sobel(normalized(vertical), axis=0))
    boundary = np.maximum(h_sobel, v_sobel)
    boundary = np.maximum(boundary - (~foreground), 0.0)
    distance = -ndimage.gaussian_filter((1.0 - boundary) * foreground, sigma=0.5)
    marker = foreground & ~(boundary >= 0.4)
    marker = ndimage.binary_fill_holes(marker)
    marker = ndimage.binary_opening(marker, structure=np.ones((3, 3), dtype=bool))
    markers, _ = ndimage.label(marker)
    marker_sizes = np.bincount(markers.ravel())
    valid = marker_sizes >= 10
    valid[0] = False
    markers = markers * valid[markers]
    markers, _ = ndimage.label(markers > 0)
    if int(markers.max()) == 0:
        return np.zeros_like(markers, dtype=np.int32)
    scaled = normalized(distance)
    terrain = np.asarray(np.rint(scaled * 255.0), dtype=np.uint8)
    instances = ndimage.watershed_ift(terrain, markers.astype(np.int32))
    instances[~foreground] = 0
    return instances.astype(np.int32)


def load_model(weight_path: Path):
    import torch
    from models.hovernet.net_desc import create_model

    torch.manual_seed(0)
    torch.cuda.manual_seed_all(0)
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.backends.cudnn.benchmark = False
    torch.use_deterministic_algorithms(True)
    model = create_model(mode="fast", nr_types=5)
    checkpoint = torch.load(weight_path, map_location="cpu", weights_only=True)
    model.load_state_dict(checkpoint["desc"], strict=True)
    return model.eval().to("cuda")


def infer(image_path: Path, model):
    import numpy as np
    from PIL import Image
    import torch

    if not torch.cuda.is_available() or torch.cuda.get_device_capability(0) != (6, 1):
        fail("CUDA sm_61 host is unavailable")
    if torch.version.cuda != "12.6" or "sm_61" not in torch.cuda.get_arch_list():
        fail("runtime does not contain the pinned Pascal CUDA target")
    max_vram = int(os.environ.get("PATHLAB_MAX_VRAM_MIB", "0"))
    max_ram = int(os.environ.get("PATHLAB_MAX_RAM_MIB", "0"))
    if not 0 < max_vram <= 4608 or not 0 < max_ram <= 16384:
        fail("resource envelope is invalid")

    with Image.open(image_path) as opened:
        image = np.asarray(opened.convert("RGB"), dtype=np.uint8)
    if image.shape[0] > 2048 or image.shape[1] > 2048 or image.shape[0] < 64 or image.shape[1] < 64:
        fail("probe image geometry is invalid")

    output_size = 164
    border = 46
    height, width = image.shape[:2]
    padded = np.pad(image, ((border, border), (border, border), (0, 0)), mode="reflect")
    probability = np.zeros((height, width), dtype=np.float32)
    horizontal = np.zeros((height, width), dtype=np.float32)
    vertical = np.zeros((height, width), dtype=np.float32)
    type_scores = np.zeros((height, width, 5), dtype=np.float32)
    counts = np.zeros((height, width), dtype=np.float32)
    positions = [(y, x) for y in starts(height, output_size) for x in starts(width, output_size)]
    started = time.perf_counter()
    with torch.inference_mode():
        for offset in range(0, len(positions), MICRO_BATCH):
            y, x = positions[offset]
            tile = np.ascontiguousarray(padded[y:y + 256, x:x + 256].transpose(2, 0, 1))
            tensor = torch.from_numpy(tile).unsqueeze(0).float().to("cuda")
            output = model(tensor)
            np_values = torch.softmax(output["np"], dim=1)[0, 1].cpu().numpy()
            hv_values = output["hv"][0].cpu().numpy()
            tp_values = torch.softmax(output["tp"], dim=1)[0].permute(1, 2, 0).cpu().numpy()
            y2 = min(y + output_size, height)
            x2 = min(x + output_size, width)
            oh, ow = y2 - y, x2 - x
            probability[y:y2, x:x2] += np_values[:oh, :ow]
            horizontal[y:y2, x:x2] += hv_values[0, :oh, :ow]
            vertical[y:y2, x:x2] += hv_values[1, :oh, :ow]
            type_scores[y:y2, x:x2] += tp_values[:oh, :ow]
            counts[y:y2, x:x2] += 1.0
            del tensor, output
    counts = np.maximum(counts, 1.0)
    instances = postprocess(probability / counts, horizontal / counts, vertical / counts)
    types = np.argmax(type_scores / counts[:, :, None], axis=2)
    research_counts = {str(index): 0 for index in range(1, 5)}
    for instance_id in range(1, int(instances.max()) + 1):
        values = types[instances == instance_id]
        if values.size:
            selected = int(np.bincount(values, minlength=5).argmax())
            if selected > 0:
                research_counts[str(selected)] += 1
    torch.cuda.synchronize()
    runtime = {
        "device": torch.cuda.get_device_name(0), "cuda": torch.version.cuda,
        "architecture": "sm_61", "microBatch": MICRO_BATCH, "tiles": len(positions),
        "elapsedSeconds": round(time.perf_counter() - started, 6),
        "peakVramMiB": round(torch.cuda.max_memory_reserved() / (1024 * 1024), 3),
        "peakRamMiB": round(working_set_mib(), 3), "analysisNetwork": "disabled",
    }
    if runtime["peakVramMiB"] > max_vram or runtime["peakRamMiB"] > max_ram:
        fail("worker exceeded its declared resource envelope")
    return instances, research_counts, runtime


def required_text(value: dict, name: str) -> str:
    result = value.get(name)
    if not isinstance(result, str) or not result:
        fail(f"cell qualification field is invalid: {name}")
    return result


def relative_file(root: Path, relative_value: str) -> Path:
    relative = Path(relative_value)
    path = (root / relative).resolve()
    if relative.is_absolute() or not path.is_relative_to(root.resolve()) or not path.is_file():
        fail("cell qualification sample path is invalid")
    return path


def ground_truth_labels(annotation_path: Path, width: int, height: int):
    import numpy as np
    from PIL import Image, ImageDraw

    try:
        tree = ET.parse(annotation_path)
    except ET.ParseError as error:
        raise RuntimeError("cell qualification annotation is invalid") from error
    labels = Image.new("I", (width, height), 0)
    draw = ImageDraw.Draw(labels)
    instance_id = 0
    for region in tree.getroot().iter("Region"):
        vertices = []
        for vertex in region.iter("Vertex"):
            try:
                vertices.append((round(float(vertex.attrib["X"])), round(float(vertex.attrib["Y"]))))
            except (KeyError, ValueError) as error:
                raise RuntimeError("cell qualification annotation is invalid") from error
        if len(vertices) >= 3:
            instance_id += 1
            draw.polygon(vertices, fill=instance_id)
    if instance_id == 0:
        fail("cell qualification annotation contains no instances")
    return np.asarray(labels, dtype=np.int32)


def perimeters(labels, count: int):
    import numpy as np
    from scipy import ndimage

    result = np.zeros(count + 1, dtype=np.float64)
    for instance_id in range(1, count + 1):
        mask = labels == instance_id
        if not np.any(mask):
            continue
        eroded = ndimage.binary_erosion(mask, structure=np.ones((3, 3), dtype=bool), border_value=0)
        result[instance_id] = float(np.count_nonzero(mask & ~eroded))
    return result


def compare_instances(organ: str, truth, predicted: object) -> dict:
    import numpy as np

    truth_count = int(truth.max())
    predicted_count = int(predicted.max())
    truth_area = np.bincount(truth.ravel(), minlength=truth_count + 1)
    predicted_area = np.bincount(predicted.ravel(), minlength=predicted_count + 1)
    factor = predicted_count + 1
    overlap_pixels = (truth > 0) & (predicted > 0)
    encoded = truth[overlap_pixels].astype(np.int64) * factor + predicted[overlap_pixels].astype(np.int64)
    pairs = []
    if encoded.size:
        keys, intersections = np.unique(encoded, return_counts=True)
        for key, intersection in zip(keys.tolist(), intersections.tolist()):
            truth_id = key // factor
            predicted_id = key % factor
            union = int(truth_area[truth_id] + predicted_area[predicted_id] - intersection)
            iou = intersection / union
            if iou >= 0.5:
                pairs.append((iou, truth_id, predicted_id, intersection))
    pairs.sort(key=lambda item: (-item[0], item[1], item[2]))
    used_truth = set()
    used_predicted = set()
    matches = []
    for pair in pairs:
        if pair[1] not in used_truth and pair[2] not in used_predicted:
            matches.append(pair)
            used_truth.add(pair[1])
            used_predicted.add(pair[2])
    denominator = len(matches) + 0.5 * (predicted_count - len(matches)) + 0.5 * (truth_count - len(matches))
    pq = sum(pair[0] for pair in matches) / denominator if denominator else 0.0
    dice_denominator = max(truth_count, predicted_count)
    dice = (sum(2.0 * pair[3] / (truth_area[pair[1]] + predicted_area[pair[2]])
                for pair in matches) / dice_denominator if dice_denominator else 0.0)
    truth_perimeters = perimeters(truth, truth_count)
    predicted_perimeters = perimeters(predicted, predicted_count)
    biases = []
    for _, truth_id, predicted_id, _ in matches:
        area_bias = abs(int(predicted_area[predicted_id]) - int(truth_area[truth_id])) / max(1, int(truth_area[truth_id]))
        perimeter_bias = abs(predicted_perimeters[predicted_id] - truth_perimeters[truth_id]) / max(1.0, truth_perimeters[truth_id])
        biases.append((area_bias + perimeter_bias) / 2.0)
    return {
        "organ": organ, "pq": pq, "dice": dice,
        "countError": abs(predicted_count - truth_count) / max(1, truth_count),
        "morphometryBias": float(np.median(biases)) if biases else 1.0,
    }


def validate_cohort(path: Path, expected_hash: str) -> tuple[dict, list[dict]]:
    if not path.is_file() or sha256(path) != expected_hash:
        fail("cell qualification cohort checksum does not match")
    cohort = load_json(path)
    samples = cohort.get("samples")
    if (cohort.get("schema") != COHORT_SCHEMA or not isinstance(samples, list)
            or len(samples) < 4 or cohort.get("sampleCount") != len(samples)):
        fail("cell qualification cohort contract is invalid")
    return cohort, samples


def checkpoint(output_path: Path, job_id: str, pack_hash: str, request_hash: str,
               cohort_hash: str, rows: list[dict], failures: list[dict], organs: set[str],
               repeatable: bool, total: int) -> None:
    completed = len(rows) + len(failures)
    checkpoint_path = output_path.parent / f"checkpoint-{completed:06d}.json"
    value = {
        "schema": CHECKPOINT_SCHEMA, "jobId": job_id, "packManifestSha256": pack_hash,
        "requestSha256": request_hash, "cohortManifestSha256": cohort_hash,
        "completedUnits": completed, "totalUnits": total, "rows": rows,
        "sampleFailures": failures, "observedOrgans": sorted(organs),
        "deterministicRepeat": repeatable, "updatedAt": utc_now(),
    }
    write_json_atomic(checkpoint_path, value)
    write_json_atomic(output_path.parent / "progress.json", {
        "schema": PROGRESS_SCHEMA, "jobId": job_id, "packManifestSha256": pack_hash,
        "completedUnits": completed, "totalUnits": total,
        "checkpointPath": str(checkpoint_path), "checkpointSha256": sha256(checkpoint_path),
        "updatedAt": utc_now(),
    })


def restore_checkpoint(path_value: str | None, job_id: str, pack_hash: str,
                       request_hash: str, cohort_hash: str, total: int):
    if not path_value:
        return [], [], set(), True
    path = Path(path_value).resolve()
    value = load_json(path)
    if (value.get("schema") != CHECKPOINT_SCHEMA or value.get("jobId") != job_id
            or value.get("packManifestSha256") != pack_hash
            or value.get("requestSha256") != request_hash
            or value.get("cohortManifestSha256") != cohort_hash
            or value.get("totalUnits") != total):
        fail("model worker checkpoint identity changed")
    rows = value.get("rows")
    failures = value.get("sampleFailures")
    completed = value.get("completedUnits")
    if (not isinstance(rows, list) or not isinstance(failures, list)
            or completed != len(rows) + len(failures) or completed < 0 or completed > total):
        fail("model worker checkpoint counts are invalid")
    organs = value.get("observedOrgans")
    if not isinstance(organs, list) or not set(organs).issubset(ORGANS):
        fail("model worker checkpoint tissues are invalid")
    return rows, failures, set(organs), bool(value.get("deterministicRepeat", False))


def bounded_failure(sample: dict, sample_index: int, error: Exception) -> dict:
    sample_id = sample.get("sampleId")
    if not isinstance(sample_id, str) or not sample_id or len(sample_id) > 120:
        sample_id = f"sample-{sample_index:03d}"
    organ = sample.get("organ") if sample.get("organ") in ORGANS else "unknown"
    message = str(error).lower()
    if "rights or split" in message:
        code = "SAMPLE_RIGHTS_OR_SPLIT_INVALID"
    elif "checksum changed" in message or "path is invalid" in message:
        code = "SAMPLE_CHECKSUM_CHANGED"
    elif "geometry is invalid" in message:
        code = "SAMPLE_GEOMETRY_INVALID"
    elif "annotation" in message:
        code = "SAMPLE_ANNOTATION_INVALID"
    elif any(value in message for value in ("cuda", "tensor", "shape", "memory", "model")):
        code = "MODEL_INFERENCE_FAILED"
    else:
        code = "SAMPLE_EVALUATION_FAILED"
    return {"sampleId": sample_id, "organ": organ, "code": code}


def cohort_qualification(request: dict, request_path: Path, output_path: Path,
                         pack_hash: str, weight: Path, resume_path: str | None):
    import numpy as np
    import torch

    cohort_path = Path(required_text(request, "qualificationCohortManifest")).resolve()
    cohort_hash = required_text(request, "qualificationCohortManifestSha256")
    cohort, samples = validate_cohort(cohort_path, cohort_hash)
    job_id = output_path.parent.name
    request_hash = sha256(request_path)
    rows, sample_failures, observed_organs, repeatable = restore_checkpoint(
        resume_path, job_id, pack_hash, request_hash, cohort_hash, len(samples))
    completed = len(rows) + len(sample_failures)
    started = time.perf_counter()
    torch.cuda.reset_peak_memory_stats()
    model = load_model(weight)
    root = cohort_path.parent
    for sample_index, sample in enumerate(samples[completed:], start=completed):
        try:
            organ = required_text(sample, "organ")
            if (organ not in ORGANS or required_text(sample, "split") != "qualification-held-out-test"
                    or bool(sample.get("patientOverlapWithTraining", True))
                    or required_text(sample, "license") != "CC-BY-NC-SA-4.0"
                    or required_text(sample, "permittedUse") != "private-research-restricted"):
                fail("cell qualification sample rights or split is invalid")
            image_path = relative_file(root, required_text(sample, "imagePath"))
            annotation_path = relative_file(root, required_text(sample, "annotationPath"))
            if sha256(image_path) != required_text(sample, "imageSha256") or sha256(annotation_path) != required_text(sample, "annotationSha256"):
                fail("cell qualification sample checksum changed")
            width = int(sample.get("width", 0))
            height = int(sample.get("height", 0))
            if width < 64 or height < 64 or width * height > 4_194_304:
                fail("cell qualification image geometry is invalid")
            truth = ground_truth_labels(annotation_path, width, height)
            first, _, _ = infer(image_path, model)
            second, _, _ = infer(image_path, model)
            repeatable = repeatable and np.array_equal(first, second)
            rows.append(compare_instances(organ, truth, first))
            observed_organs.add(organ)
        except (OSError, RuntimeError, ValueError, KeyError) as error:
            sample_failures.append(bounded_failure(sample, sample_index, error))
        checkpoint(output_path, job_id, pack_hash, request_hash, cohort_hash,
                   rows, sample_failures, observed_organs, repeatable, len(samples))
    elapsed = time.perf_counter() - started
    peak_ram = working_set_mib()
    peak_vram = torch.cuda.max_memory_reserved() / (1024 * 1024)
    if not rows:
        fail("no cell qualification region was evaluable")
    gates = cohort.get("gates", {})
    required_gates = ["minimumMacroPq", "minimumInstanceDice", "maximumCountError",
                      "maximumMorphometryBias", "maximumFailedRegionRate"]
    if any(not isinstance(gates.get(name), (int, float)) for name in required_gates):
        fail("cell qualification gates are invalid")
    per_organ = {}
    for organ in sorted(ORGANS):
        organ_rows = [row for row in rows if row["organ"] == organ]
        per_organ[organ] = {
            "sampleCount": len(organ_rows),
            "macroPq": sum(row["pq"] for row in organ_rows) / len(organ_rows) if organ_rows else 0.0,
            "instanceDice": sum(row["dice"] for row in organ_rows) / len(organ_rows) if organ_rows else 0.0,
            "countError": sum(row["countError"] for row in organ_rows) / len(organ_rows) if organ_rows else 1.0,
        }
    integrity_failure_codes = {"SAMPLE_RIGHTS_OR_SPLIT_INVALID", "SAMPLE_CHECKSUM_CHANGED",
                               "SAMPLE_GEOMETRY_INVALID", "SAMPLE_ANNOTATION_INVALID"}
    rights_passed = not any(item["code"] in integrity_failure_codes for item in sample_failures)
    cross_tissue = observed_organs == ORGANS
    resource_compliant = (peak_ram <= int(os.environ["PATHLAB_MAX_RAM_MIB"])
                          and peak_vram <= int(os.environ["PATHLAB_MAX_VRAM_MIB"]))
    reasons = []
    if not rights_passed:
        reasons.append("CELL_COHORT_RIGHTS_OR_INTEGRITY_FAILED")
    if not cross_tissue:
        reasons.append("CELL_CROSS_TISSUE_COVERAGE_INCOMPLETE")
    if not resource_compliant:
        reasons.append("CELL_RESOURCE_ENVELOPE_EXCEEDED")
    metrics = {
        "schema": "pathlab.cell-instance-metrics/1", "cohortManifestSha256": cohort_hash,
        "sampleCount": len(samples), "evaluatedSampleCount": len(rows),
        "macroPq": sum(row["pq"] for row in rows) / len(rows),
        "instanceDice": sum(row["dice"] for row in rows) / len(rows),
        "countError": sum(row["countError"] for row in rows) / len(rows),
        "morphometryBias": float(np.median([row["morphometryBias"] for row in rows])),
        "failedRegionRate": len(sample_failures) / len(samples), "deterministicRepeat": repeatable,
        "crossTissuePerformance": cross_tissue, "rightsAndIntegrityPassed": rights_passed,
        "resourceCompliant": resource_compliant, "elapsedSeconds": elapsed,
        "peakHeapMiB": peak_ram, "minimumMacroPq": float(gates["minimumMacroPq"]),
        "minimumInstanceDice": float(gates["minimumInstanceDice"]),
        "maximumCountError": float(gates["maximumCountError"]),
        "maximumMorphometryBias": float(gates["maximumMorphometryBias"]),
        "maximumFailedRegionRate": float(gates["maximumFailedRegionRate"]),
        "sourceIntegrity": str(cohort.get("source", {}).get("integrity", "local-first-acquisition-sha256")),
        "upstreamChecksumAvailable": bool(cohort.get("source", {}).get("upstreamChecksumAvailable", False)),
        "perOrgan": per_organ, "sampleFailures": sample_failures,
        "notEvaluableReasons": reasons,
    }
    runtime = {"device": torch.cuda.get_device_name(0), "cuda": torch.version.cuda,
               "architecture": "sm_61", "microBatch": MICRO_BATCH, "samples": len(samples),
               "elapsedSeconds": round(elapsed, 6), "peakVramMiB": round(peak_vram, 3),
               "peakRamMiB": round(peak_ram, 3), "analysisNetwork": "disabled"}
    return metrics, runtime


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--request", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--resume-checkpoint")
    arguments = parser.parse_args()
    require_offline(arguments.offline)
    install_root = Path(__file__).resolve().parent
    _, weight = validate_references(install_root)
    validate_manifest(install_root, "source-manifest.json", "pathlab.model-source-manifest/1", "upstream")
    validate_manifest(install_root, "overlay-manifest.json", "pathlab.model-overlay-manifest/1", "overlay")
    sys.path.insert(0, str(install_root / "overlay"))
    sys.path.insert(0, str(install_root / "upstream"))
    import torch  # noqa: F401 - fully load local binary dependencies before socket replacement
    from scipy import ndimage  # noqa: F401
    from models.hovernet.net_desc import create_model  # noqa: F401
    disable_network()
    request_path = Path(arguments.request).resolve()
    request = load_json(request_path)
    output = Path(arguments.output).resolve()
    if request.get("schema") == "pathlab.hovernet-runtime-probe/1":
        import torch
        image_path = Path(str(request.get("imagePath", ""))).resolve()
        if not image_path.is_file() or sha256(image_path) != request.get("imageSha256"):
            fail("probe image checksum changed")
        torch.cuda.reset_peak_memory_stats()
        model = load_model(weight)
        instances, type_counts, runtime = infer(image_path, model)
        result = {
            "schema": "pathlab.hovernet-runtime-probe-result/1",
            "status": "completed", "scope": "runtime-probe-only-not-qualification",
            "instanceCount": int(instances.max()), "researchTypeCounts": type_counts,
            "runtime": runtime, "activationEligible": False,
        }
    elif request.get("schema") in {"pathlab.evidence-job/1", "pathlab.evidence-job/2"}:
        pack_path = Path(required_text(request, "packManifest")).resolve()
        if not pack_path.is_file():
            fail("pack manifest is unavailable")
        pack_hash = sha256(pack_path)
        metrics, runtime = cohort_qualification(
            request, request_path, output, pack_hash, weight, arguments.resume_checkpoint)
        result = {"schema": RESULT_SCHEMA, "status": "completed",
                  "packManifestSha256": pack_hash, "regions": [],
                  "runtime": runtime, "qualificationMetrics": metrics}
    else:
        fail("HoVer-Net request schema is unsupported")
    write_json_atomic(output, result)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"PathLab model worker failed closed: {error}", file=sys.stderr)
        raise SystemExit(2)
