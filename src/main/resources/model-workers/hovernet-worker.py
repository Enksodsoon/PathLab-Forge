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


MICRO_BATCH = 1


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


def infer(image_path: Path, weight_path: Path):
    import numpy as np
    from PIL import Image
    import torch
    from models.hovernet.net_desc import create_model

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

    torch.manual_seed(0)
    torch.cuda.manual_seed_all(0)
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.backends.cudnn.benchmark = False
    torch.use_deterministic_algorithms(True)
    torch.cuda.reset_peak_memory_stats()
    model = create_model(mode="fast", nr_types=5)
    checkpoint = torch.load(weight_path, map_location="cpu", weights_only=True)
    model.load_state_dict(checkpoint["desc"], strict=True)
    model.eval().to("cuda")

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
    return int(instances.max()), research_counts, runtime


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--request", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--offline", action="store_true")
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
    if request.get("schema") != "pathlab.hovernet-runtime-probe/1":
        fail("HoVer-Net request schema is unsupported")
    image_path = Path(str(request.get("imagePath", ""))).resolve()
    if not image_path.is_file() or sha256(image_path) != request.get("imageSha256"):
        fail("probe image checksum changed")
    count, type_counts, runtime = infer(image_path, weight)
    result = {
        "schema": "pathlab.hovernet-runtime-probe-result/1",
        "status": "completed", "scope": "runtime-probe-only-not-qualification",
        "instanceCount": count, "researchTypeCounts": type_counts,
        "runtime": runtime, "activationEligible": False,
    }
    output = Path(arguments.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, sort_keys=True, separators=(",", ":"), allow_nan=False)
        stream.write("\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"PathLab model worker failed closed: {error}", file=sys.stderr)
        raise SystemExit(2)
