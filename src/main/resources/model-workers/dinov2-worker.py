from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path
import socket
import sys
import time
from datetime import datetime, timezone


RESULT_SCHEMA = "pathlab.model-worker-result/1"
PROGRESS_SCHEMA = "pathlab.model-worker-progress/1"
CHECKPOINT_SCHEMA = "pathlab.model-worker-checkpoint/1"
MODEL_FILES = {
    "model": "model.safetensors",
    "config": "config.json",
    "preprocessor": "preprocessor_config.json",
    "workerSource": "worker.py",
    "runtime-manifest": "runtime-manifest.json",
}


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
    partial = path.with_name(path.name + ".partial")
    partial.parent.mkdir(parents=True, exist_ok=True)
    with partial.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, sort_keys=True, separators=(",", ":"), allow_nan=False)
        stream.write("\n")
    os.replace(partial, path)


def require_offline(offline: bool) -> None:
    if not offline or os.environ.get("PATHLAB_ANALYSIS_NETWORK") != "disabled":
        fail("offline analysis contract was not enforced")
    if os.environ.get("HF_HUB_OFFLINE") != "1" or os.environ.get("TRANSFORMERS_OFFLINE") != "1":
        fail("model library offline flags were not enforced")


def disable_network() -> None:
    def blocked_socket(*_args, **_kwargs):
        raise OSError("network disabled for PathLab analysis")

    socket.socket = blocked_socket
    socket.create_connection = blocked_socket


def validate_artifacts(install_root: Path, pack: dict) -> None:
    artifacts = pack.get("artifacts")
    if not isinstance(artifacts, list):
        fail("pack artifact ledger is missing")
    ledger = {item.get("name"): item.get("sha256") for item in artifacts if isinstance(item, dict)}
    for name, relative in MODEL_FILES.items():
        expected = ledger.get(name)
        path = (install_root / relative).resolve()
        if not isinstance(expected, str) or len(expected) != 64 or not path.is_file():
            fail(f"required artifact is unavailable: {name}")
        if sha256(path) != expected:
            fail(f"artifact checksum mismatch: {name}")

    runtime = load_json(install_root / "runtime-manifest.json")
    if runtime.get("schema") != "pathlab.model-runtime/1":
        fail("runtime manifest schema is invalid")
    for item in runtime.get("files", []):
        if not isinstance(item, dict):
            fail("runtime file ledger is invalid")
        path = (install_root / str(item.get("path", ""))).resolve()
        if install_root not in path.parents or not path.is_file() or sha256(path) != item.get("sha256"):
            fail(f"runtime checksum mismatch: {item.get('path', '')}")


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


def validate_tile_manifest(path: Path, expected_sha: str) -> tuple[Path, dict]:
    path = path.resolve()
    if not path.is_file() or sha256(path) != expected_sha:
        fail("tile-cache manifest checksum does not match")
    manifest = load_json(path)
    if manifest.get("schema") != "pathlab.tile-cache/1" or manifest.get("tilePixels") != 512:
        fail("tile-cache schema is unsupported")
    if manifest.get("encoding") != "png" or manifest.get("preprocessingInput") != "rgb-srgb-uint8":
        fail("tile-cache pixel contract is unsupported")
    tiles = manifest.get("tiles")
    if not isinstance(tiles, list) or len(tiles) != 1:
        fail("qualification samples must contain exactly one immutable tile")
    tile = tiles[0]
    relative = Path(str(tile.get("path", "")))
    image_path = (path.parent / relative).resolve()
    if relative.is_absolute() or not image_path.is_relative_to(path.parent.resolve()):
        fail("tile-cache tile path must be relative")
    if not image_path.is_file() or sha256(image_path) != tile.get("sha256"):
        fail("tile-cache tile checksum or path is invalid")
    return image_path, manifest


def load_single_request(request: dict):
    from PIL import Image
    path, manifest = validate_tile_manifest(
        Path(str(request.get("tileCacheManifest", ""))), request.get("tileCacheManifestSha256"))
    source = manifest.get("source", {})
    if source.get("sha256") != request.get("sourceSha256") or source.get("slideRevision") != request.get("slideRevision"):
        fail("tile cache is stale or belongs to another source revision")
    with Image.open(path) as opened:
        image = opened.convert("RGB")
        image.load()
    return [{"id": "request-tile", "image": image, "split": "query", "label": "unknown"}]


def load_cohort(request: dict):
    from PIL import Image
    cohort_path = Path(str(request.get("qualificationCohortManifest", ""))).resolve()
    expected = request.get("qualificationCohortManifestSha256")
    if not cohort_path.is_file() or sha256(cohort_path) != expected:
        fail("qualification cohort checksum does not match")
    cohort = load_json(cohort_path)
    samples = cohort.get("samples")
    if cohort.get("schema") != "pathlab.qualification-cohort/1" or not isinstance(samples, list):
        fail("qualification cohort schema is unsupported")
    if not 40 <= len(samples) <= 2000:
        fail("qualification cohort sample count is invalid")
    root = cohort_path.parent.resolve()
    loaded = []
    seen = set()
    for sample in samples:
        if not isinstance(sample, dict) or sample.get("id") in seen:
            fail("qualification cohort sample is invalid or duplicated")
        seen.add(sample.get("id"))
        relative = Path(str(sample.get("tileCacheManifest", "")))
        manifest_path = (root / relative).resolve()
        if relative.is_absolute() or not manifest_path.is_relative_to(root):
            fail("qualification cohort path escaped its frozen root")
        image_path, manifest = validate_tile_manifest(manifest_path, sample.get("tileCacheManifestSha256"))
        provenance_path = manifest_path.parent / str(manifest.get("source", {}).get("sampleManifest", ""))
        provenance = load_json(provenance_path)
        if sha256(provenance_path) != manifest.get("source", {}).get("sampleManifestSha256"):
            fail("qualification sample provenance checksum does not match")
        if provenance.get("permittedUse") != "private-research":
            fail("qualification sample rights are not approved")
        with Image.open(image_path) as opened:
            image = opened.convert("RGB")
            image.load()
        loaded.append({
            "id": sample.get("id"), "image": image, "split": sample.get("split"),
            "label": sample.get("phenotypeGroup"), "evaluationGroup": sample.get("evaluationGroup"),
        })
    return cohort_path, cohort, loaded


def bind_legacy_cohort_request(request: dict) -> None:
    if request.get("qualificationCohortManifest"):
        return
    marker = str(request.get("marker", ""))
    if not marker.startswith("cohort-") or len(marker) != 71:
        return
    expected = marker[7:]
    if any(character not in "0123456789abcdef" for character in expected):
        fail("qualification cohort binding is invalid")
    request_path = Path(str(request.get("_requestPath", ""))).resolve()
    if len(request_path.parents) < 4:
        fail("qualification request path is invalid")
    state_root = request_path.parents[3]
    cohort_path = state_root / "derived" / "he-dinov2-small-v1" / "nct-crc-gi-20-per-class-v1" / "cohort.json"
    if not cohort_path.is_file() or sha256(cohort_path) != expected:
        fail("qualification cohort checksum does not match")
    request["qualificationCohortManifest"] = str(cohort_path)
    request["qualificationCohortManifestSha256"] = expected


def histogram_features(samples):
    import numpy as np
    features = []
    for sample in samples:
        pixels = np.asarray(sample["image"], dtype=np.uint8)
        parts = [np.histogram(pixels[:, :, channel], bins=16, range=(0, 256))[0].astype(np.float32)
                 for channel in range(3)]
        vector = np.concatenate(parts)
        vector /= max(float(np.linalg.norm(vector)), 1e-12)
        features.append(vector)
    return np.stack(features)


def retrieval_metrics(features, samples):
    import numpy as np
    reference = [i for i, sample in enumerate(samples) if sample["split"] == "reference"]
    query = [i for i, sample in enumerate(samples) if sample["split"] == "query"]
    if not reference or not query:
        fail("qualification cohort reference/query split is incomplete")
    labels = sorted({samples[i]["label"] for i in query})
    recalls, ndcgs, rankings = [], [], []
    for label in labels:
        label_queries = [i for i in query if samples[i]["label"] == label]
        label_recalls, label_ndcgs = [], []
        for index in label_queries:
            scores = features[reference] @ features[index]
            order = sorted(range(len(reference)), key=lambda offset: (-float(scores[offset]), samples[reference[offset]]["id"]))
            ranked = [reference[offset] for offset in order]
            rankings.append([samples[item]["id"] for item in ranked])
            relevance = [1 if samples[item]["label"] == label else 0 for item in ranked]
            label_recalls.append(1.0 if any(relevance[:5]) else 0.0)
            dcg = sum(value / math.log2(rank + 2) for rank, value in enumerate(relevance[:10]))
            relevant_total = sum(1 for item in reference if samples[item]["label"] == label)
            ideal = sum(1.0 / math.log2(rank + 2) for rank in range(min(10, relevant_total)))
            label_ndcgs.append(dcg / ideal if ideal else 0.0)
        recalls.append(sum(label_recalls) / len(label_recalls))
        ndcgs.append(sum(label_ndcgs) / len(label_ndcgs))
    return sum(recalls) / len(recalls), sum(ndcgs) / len(ndcgs), rankings


def checkpoint(output_path: Path, job_id: str, pack_hash: str, cohort_hash: str,
               completed: int, total: int) -> None:
    checkpoint_path = output_path.parent / f"checkpoint-{completed:06d}.json"
    value = {
        "schema": CHECKPOINT_SCHEMA, "jobId": job_id, "packManifestSha256": pack_hash,
        "cohortManifestSha256": cohort_hash, "completedUnits": completed, "totalUnits": total,
        "containsEmbeddings": False, "updatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    write_json_atomic(checkpoint_path, value)
    progress = {
        "schema": PROGRESS_SCHEMA, "jobId": job_id, "packManifestSha256": pack_hash,
        "completedUnits": completed, "totalUnits": total,
        "checkpointPath": str(checkpoint_path), "checkpointSha256": sha256(checkpoint_path),
        "updatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    write_json_atomic(output_path.parent / "progress.json", progress)


def model_features(model, processor, samples, output_path, pack_hash, cohort_hash, pass_index):
    import numpy as np
    import torch
    vectors = []
    total = len(samples) * 2
    batch_size = 4
    with torch.inference_mode():
        for offset in range(0, len(samples), batch_size):
            batch_samples = samples[offset:offset + batch_size]
            pixels = processor(images=[item["image"] for item in batch_samples], return_tensors="pt")["pixel_values"].to("cuda")
            output = model(pixel_values=pixels).last_hidden_state[:, 0, :].float().cpu().numpy()
            vectors.append(output)
            del pixels
            completed = pass_index * len(samples) + min(offset + len(batch_samples), len(samples))
            if completed % 16 == 0 or completed == (pass_index + 1) * len(samples):
                checkpoint(output_path, output_path.parent.name, pack_hash, cohort_hash, completed, total)
    result = np.concatenate(vectors, axis=0)
    norms = np.linalg.norm(result, axis=1, keepdims=True)
    return result / np.maximum(norms, 1e-12)


def infer(install_root: Path, request: dict, output_path: Path, pack_hash: str) -> tuple[list[dict], dict, dict | None]:
    import torch
    from transformers import AutoImageProcessor, Dinov2Model
    disable_network()
    if not torch.cuda.is_available() or torch.cuda.get_device_capability(0) != (6, 1):
        fail("CUDA sm_61 host is unavailable")
    if "sm_61" not in torch.cuda.get_arch_list() or torch.version.cuda != "12.6":
        fail("runtime does not contain the pinned Pascal CUDA target")
    max_vram = int(os.environ.get("PATHLAB_MAX_VRAM_MIB", "0"))
    max_ram = int(os.environ.get("PATHLAB_MAX_RAM_MIB", "0"))
    if max_vram <= 0 or max_vram > 4608 or max_ram <= 0 or max_ram > 16384:
        fail("resource envelope is invalid")

    torch.manual_seed(0)
    torch.cuda.manual_seed_all(0)
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.backends.cudnn.benchmark = False
    torch.use_deterministic_algorithms(True)
    torch.cuda.reset_peak_memory_stats()
    processor = AutoImageProcessor.from_pretrained(install_root, local_files_only=True, use_fast=False)
    model = Dinov2Model.from_pretrained(install_root, local_files_only=True, use_safetensors=True).eval().to("cuda")
    started = time.perf_counter()

    qualification = None
    if request.get("qualificationCohortManifest"):
        cohort_path, cohort, samples = load_cohort(request)
        cohort_hash = sha256(cohort_path)
        baseline_recall, baseline_ndcg, _ = retrieval_metrics(histogram_features(samples), samples)
        first = model_features(model, processor, samples, output_path, pack_hash, cohort_hash, 0)
        model_recall, model_ndcg, first_rankings = retrieval_metrics(first, samples)
        second = model_features(model, processor, samples, output_path, pack_hash, cohort_hash, 1)
        _, _, second_rankings = retrieval_metrics(second, samples)
        criteria = cohort.get("acceptanceCriteria", {})
        reasons = list(cohort.get("qualificationReasons", []))
        qualification = {
            "schema": "pathlab.he-retrieval-metrics/1",
            "cohortId": cohort.get("cohortId"), "cohortManifestSha256": cohort_hash,
            "sampleCount": len(samples), "referenceCount": sum(s["split"] == "reference" for s in samples),
            "queryCount": sum(s["split"] == "query" for s in samples),
            "oodCount": sum(s["split"] == "ood" for s in samples),
            "evaluationGroups": sorted({s["evaluationGroup"] for s in samples}),
            "baselineId": criteria.get("baselineId"),
            "baselineMacroRecallAt5": round(baseline_recall, 9),
            "modelMacroRecallAt5": round(model_recall, 9),
            "macroRecallAt5Improvement": round(model_recall - baseline_recall, 9),
            "baselineMacroNdcgAt10": round(baseline_ndcg, 9),
            "modelMacroNdcgAt10": round(model_ndcg, 9),
            "macroNdcgAt10Improvement": round(model_ndcg - baseline_ndcg, 9),
            "minimumMacroRecallAt5Improvement": criteria.get("minimumMacroRecallAt5Improvement"),
            "minimumMacroNdcgAt10Improvement": criteria.get("minimumMacroNdcgAt10Improvement"),
            "minimumOodAuRoc": criteria.get("minimumOodAuRoc"), "oodAuRoc": None,
            "exactRankingRepeatability": first_rankings == second_rankings,
            "cohortStatus": cohort.get("qualificationStatus", "not_evaluable"),
            "notEvaluableReasons": reasons,
            "embeddingsExported": False,
        }
        regions = [{"id": "dinov2-cohort-support-1", "stage": "coarse", "kind": "support",
                    "x": 0, "y": 0, "width": 512, "height": 512, "score": 1.0}]
        del first, second
    else:
        samples = load_single_request(request)
        features = model_features(model, processor, samples, output_path, pack_hash, "0" * 64, 0)
        regions = [{"id": "dinov2-support-1", "stage": "coarse", "kind": "support",
                    "x": 0, "y": 0, "width": 512, "height": 512, "score": 1.0}]
        del features

    torch.cuda.synchronize()
    runtime = {
        "device": torch.cuda.get_device_name(0), "cuda": torch.version.cuda, "architecture": "sm_61",
        "tiles": len(samples), "batchSize": 4, "elapsedSeconds": round(time.perf_counter() - started, 6),
        "peakVramMiB": round(torch.cuda.max_memory_reserved() / (1024 * 1024), 3),
        "peakRamMiB": round(working_set_mib(), 3), "analysisNetwork": "disabled",
    }
    if runtime["peakVramMiB"] > max_vram or runtime["peakRamMiB"] > max_ram:
        fail("worker exceeded its declared resource envelope")
    del model
    torch.cuda.empty_cache()
    return regions, runtime, qualification


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--request", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--resume-checkpoint")
    arguments = parser.parse_args()
    require_offline(arguments.offline)
    install_root = Path(__file__).resolve().parent
    request = load_json(Path(arguments.request).resolve())
    request["_requestPath"] = str(Path(arguments.request).resolve())
    bind_legacy_cohort_request(request)
    output_path = Path(arguments.output).resolve()
    pack_path = Path(str(request.get("packManifest", ""))).resolve()
    if not pack_path.is_file():
        fail("pack manifest is unavailable")
    pack = load_json(pack_path)
    validate_artifacts(install_root, pack)
    pack_hash = sha256(pack_path)
    regions, runtime, qualification = infer(install_root, request, output_path, pack_hash)
    result = {"schema": RESULT_SCHEMA, "status": "completed", "packManifestSha256": pack_hash,
              "regions": regions, "runtime": runtime}
    if qualification is not None:
        result["qualificationMetrics"] = qualification
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, sort_keys=True, separators=(",", ":"), allow_nan=False)
        stream.write("\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"PathLab DINOv2 worker failed closed: {error}", file=sys.stderr)
        raise SystemExit(2)
