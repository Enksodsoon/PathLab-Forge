"""Run the real local Forge process against a real isolated Viewer process."""

from __future__ import annotations

import argparse
import concurrent.futures
import contextlib
import hashlib
import http.cookiejar
import json
import os
import queue
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

import numpy as np
import tifffile


TIMEOUT_SECONDS = 180
PASSWORD = "ome-rc-local-test-password"


def free_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


class Client:
    def __init__(self, base: str) -> None:
        self.base = base.rstrip("/")
        self.cookie_jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookie_jar)
        )
        self.csrf = ""

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        *,
        headers: dict[str, str] | None = None,
        expected: tuple[int, ...] = (200,),
    ) -> tuple[Any, Any]:
        payload = None if body is None else json.dumps(body).encode()
        request_headers = dict(headers or {})
        if payload is not None:
            request_headers["Content-Type"] = "application/json"
        if method != "GET" and self.csrf:
            request_headers["X-Forge-CSRF"] = self.csrf
            request_headers["Origin"] = self.base
        request = urllib.request.Request(
            self.base + path,
            data=payload,
            headers=request_headers,
            method=method,
        )
        try:
            response = self.opener.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            content = error.read().decode(errors="replace")
            raise RuntimeError(f"{method} {path} failed ({error.code}): {content}") from error
        if response.status not in expected:
            raise RuntimeError(f"{method} {path} returned {response.status}")
        content = response.read()
        result = json.loads(content) if content else None
        return result, response

    def bytes(self, path: str, *, headers: dict[str, str] | None = None) -> bytes:
        request = urllib.request.Request(self.base + path, headers=headers or {})
        with self.opener.open(request, timeout=30) as response:
            return response.read()

    def cookie_header(self) -> str:
        return "; ".join(f"{cookie.name}={cookie.value}" for cookie in self.cookie_jar)


class Process:
    def __init__(
        self,
        command: list[str],
        *,
        cwd: Path,
        env: dict[str, str],
        label: str,
    ) -> None:
        self.label = label
        self.lines: queue.Queue[str] = queue.Queue()
        self.all_lines: list[str] = []
        self.process = subprocess.Popen(
            command,
            cwd=cwd,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        assert self.process.stdout is not None
        for line in self.process.stdout:
            clean = line.rstrip()
            self.all_lines.append(clean)
            self.lines.put(clean)

    def wait_for(self, text: str, timeout: int = 60) -> str:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError(
                    f"{self.label} exited before readiness:\n" + "\n".join(self.all_lines[-30:])
                )
            try:
                line = self.lines.get(timeout=0.25)
            except queue.Empty:
                continue
            if text in line:
                return line
        raise TimeoutError(f"{self.label} did not emit {text!r}")

    def close(self) -> None:
        if self.process.poll() is None:
            if os.name == "nt":
                subprocess.run(
                    ["taskkill", "/PID", str(self.process.pid), "/T", "/F"],
                    capture_output=True,
                    check=False,
                    text=True,
                )
            else:
                self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)


def fixture(path: Path) -> str:
    y, x = np.mgrid[:1024, :1024]
    full = np.stack(
        ((x % 256).astype(np.uint8), (y % 256).astype(np.uint8), ((x + y) % 256).astype(np.uint8)),
        axis=-1,
    )
    with tifffile.TiffWriter(path, ome=True, bigtiff=True) as writer:
        writer.write(
            full,
            metadata={"axes": "YXS", "PhysicalSizeX": 0.375, "PhysicalSizeY": 0.375},
            photometric="ycbcr",
            compression="jpeg",
            tile=(512, 512),
            subifds=1,
        )
        writer.write(
            full[::2, ::2],
            photometric="ycbcr",
            compression="jpeg",
            tile=(512, 512),
            subfiletype=1,
        )
    return hashlib.sha256(path.read_bytes()).hexdigest()


def wait_http(client: Client, path: str, timeout: int = 60) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            client.request("GET", path)
            return
        except Exception:
            time.sleep(0.25)
    raise TimeoutError(f"service did not become ready: {path}")


def initialize_viewer(python: Path, viewer: Path, env: dict[str, str]) -> None:
    subprocess.run(
        [str(python), "-m", "alembic", "upgrade", "head"],
        cwd=viewer,
        env=env,
        check=True,
        capture_output=True,
        text=True,
    )
    subprocess.run(
        [
            str(python),
            "-c",
            "from wsi_viewer.cli import main; main()",
            "create-admin",
            "--password-stdin",
        ],
        cwd=viewer,
        env=env,
        input=PASSWORD + "\n",
        check=True,
        capture_output=True,
        text=True,
    )


def viewer_login(client: Client) -> str:
    login, _ = client.request(
        "POST",
        "/api/v1/auth/session",
        {"username": "admin", "password": PASSWORD},
        expected=(201,),
    )
    return str(login["csrfToken"])


def authorize_forge(client: Client, launch_uri: str) -> None:
    client.bytes(launch_uri.removeprefix(client.base))
    _, response = client.request("GET", "/api/session")
    client.csrf = response.headers.get("X-Forge-CSRF", "")
    if not client.csrf:
        raise RuntimeError("Forge did not return its CSRF token")


def pair(forge: Client, viewer: Client, viewer_base: str) -> None:
    pairing, _ = forge.request(
        "POST",
        "/api/viewer/pairing/start?viewerUrl=" + urllib.parse.quote(viewer_base, safe=""),
        expected=(201,),
    )
    csrf = viewer_login(viewer)
    viewer.request(
        "POST",
        "/api/v1/desktop/pairings/approve",
        {"userCode": pairing["userCode"]},
        headers={"X-CSRF-Token": csrf},
        expected=(204,),
    )
    forge.request("POST", "/api/viewer/pairing/exchange")


def wait_dataset(forge: Client, dataset_id: str, wanted: set[str]) -> dict[str, Any]:
    deadline = time.monotonic() + TIMEOUT_SECONDS
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        result, _ = forge.request("GET", "/api/datasets")
        last = next(item for item in result["datasets"] if item["id"] == dataset_id)
        if last["status"] in wanted:
            return last
        if last["status"] == "FAILED":
            raise RuntimeError("Forge conversion failed: " + last["detail"])
        time.sleep(0.25)
    raise TimeoutError("Forge dataset did not reach " + ",".join(sorted(wanted)))


def convert(forge: Client, source: Path) -> tuple[str, dict[str, Any]]:
    imported, _ = forge.request(
        "POST", "/api/datasets/import?path=" + urllib.parse.quote(str(source), safe="")
    )
    dataset = imported["datasets"][0]
    dataset_id = str(dataset["id"])
    wait_dataset(
        forge,
        dataset_id,
        {"READY", "READER_REQUIRED", "READY_TO_CONVERT", "PACKAGE_READY"},
    )
    inspected, _ = forge.request("POST", f"/api/datasets/{dataset_id}/inspect")
    selected = inspected["series"][0]
    query = urllib.parse.urlencode(
        {
            "series": selected["index"],
            "downsample": 1,
            "x": 0,
            "y": 0,
            "width": selected["width"],
            "height": selected["height"],
        }
    )
    forge.request("POST", f"/api/datasets/{dataset_id}/series?{query}")
    forge.request("POST", f"/api/datasets/{dataset_id}/convert", expected=(202,))
    wait_dataset(forge, dataset_id, {"PACKAGE_READY"})
    artifacts, _ = forge.request("GET", f"/api/datasets/{dataset_id}/artifacts")
    current = next(item for item in artifacts["revisions"] if item["id"] == artifacts["currentRevision"])
    forge.request(
        "POST", f"/api/datasets/{dataset_id}/artifacts/{current['id']}/approve"
    )
    return dataset_id, current


def upload(forge: Client, dataset_id: str) -> dict[str, Any]:
    forge.request("POST", f"/api/datasets/{dataset_id}/upload", expected=(202,))
    deadline = time.monotonic() + TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        status, _ = forge.request("GET", "/api/viewer/upload")
        if status["state"] == "READY_PRIVATE":
            return status
        if status["state"] == "FAILED":
            raise RuntimeError("Viewer upload failed: " + status["detail"])
        time.sleep(0.25)
    raise TimeoutError("Viewer upload did not reach READY_PRIVATE")


def reconvert(forge: Client, dataset_id: str) -> dict[str, Any]:
    forge.request("POST", f"/api/datasets/{dataset_id}/convert", expected=(202,))
    wait_dataset(forge, dataset_id, {"PACKAGE_READY"})
    artifacts, _ = forge.request("GET", f"/api/datasets/{dataset_id}/artifacts")
    current = next(item for item in artifacts["revisions"] if item["id"] == artifacts["currentRevision"])
    forge.request("POST", f"/api/datasets/{dataset_id}/artifacts/{current['id']}/approve")
    return current


def concurrency_smoke(client: Client, path: str) -> dict[str, Any]:
    cookie = client.cookie_header()
    result: dict[str, Any] = {}
    for clients in (1, 10, 25, 50):
        durations: list[float] = []
        errors = 0

        def fetch(_: int) -> float:
            started = time.perf_counter()
            request = urllib.request.Request(client.base + path, headers={"Cookie": cookie})
            with urllib.request.urlopen(request, timeout=30) as response:
                if response.status != 200 or not response.read().startswith(b"\xff\xd8"):
                    raise RuntimeError("invalid concurrent tile response")
            return (time.perf_counter() - started) * 1000

        with concurrent.futures.ThreadPoolExecutor(max_workers=clients) as executor:
            futures = [executor.submit(fetch, index) for index in range(max(20, clients * 2))]
            for future in concurrent.futures.as_completed(futures):
                try:
                    durations.append(future.result())
                except Exception:
                    errors += 1
        durations.sort()

        def percentile(value: float) -> float:
            return durations[min(len(durations) - 1, int((len(durations) - 1) * value))]

        result[str(clients)] = {
            "requests": len(futures),
            "errors": errors,
            "p50Ms": round(statistics.median(durations), 3) if durations else None,
            "p95Ms": round(percentile(0.95), 3) if durations else None,
            "p99Ms": round(percentile(0.99), 3) if durations else None,
        }
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--viewer", type=Path, required=True)
    parser.add_argument("--viewer-python", type=Path, required=True)
    parser.add_argument("--forge-script", type=Path, required=True)
    parser.add_argument("--evidence", type=Path)
    arguments = parser.parse_args()

    root = Path(tempfile.mkdtemp(prefix="pathlab-ome-rc-"))
    viewer_port = free_port()
    forge_port = free_port()
    viewer_base = f"http://127.0.0.1:{viewer_port}"
    forge_base = f"http://127.0.0.1:{forge_port}"
    source = root / "fixture.ome.tif"
    fixture_sha = fixture(source)
    processes: list[Process] = []
    result: dict[str, Any] = {"fixtureSha256": fixture_sha}
    success = False
    try:
        viewer_env = os.environ.copy()
        viewer_env.update(
            {
                "PYTHONPATH": str(arguments.viewer / "server"),
                "PATHLAB_ENVIRONMENT": "test",
                "PATHLAB_DATABASE_URL": "sqlite:///" + str(root / "viewer.sqlite3").replace("\\", "/"),
                "PATHLAB_DATA_ROOT": str(root / "viewer-data"),
                "PATHLAB_TUS_INTERNAL_UPLOAD_DIR": str(root / "viewer-tus"),
                "PATHLAB_SECRET_KEY": "local-e2e-secret-key-that-is-long-enough",
                "PATHLAB_SECURE_COOKIES": "false",
                "PATHLAB_DESKTOP_OME_DYNAMIC_ENABLED": "true",
            }
        )
        initialize_viewer(arguments.viewer_python, arguments.viewer, viewer_env)
        viewer_process = Process(
            [
                str(arguments.viewer_python), "-m", "uvicorn", "wsi_viewer.main:app",
                "--host", "127.0.0.1", "--port", str(viewer_port),
            ],
            cwd=arguments.viewer,
            env=viewer_env,
            label="Viewer",
        )
        processes.append(viewer_process)
        viewer = Client(viewer_base)
        wait_http(viewer, "/livez")

        forge_env = os.environ.copy()
        forge_env["JAVA_OPTS"] = (
            f"-Dpathlab.forge.port={forge_port} "
            f"-Dpathlab.forge.viewerCredentialTarget=PathLab-Forge-OME-RC-{forge_port}"
        )
        forge_process = Process(
            [
                str(arguments.forge_script), "--serve", "--no-browser", "--data-root",
                str(root / "forge-data"),
            ],
            cwd=arguments.forge_script.parent,
            env=forge_env,
            label="Forge",
        )
        processes.append(forge_process)
        line = forge_process.wait_for("Authorize a new browser once with:")
        launch_uri = line.split(": ", 1)[1]
        forge = Client(forge_base)
        authorize_forge(forge, launch_uri)
        pair(forge, viewer, viewer_base)

        dataset_id, direct = convert(forge, source)
        if direct["format"] != "OME_DYNAMIC_V1" or direct["packageBytes"] != 0:
            raise RuntimeError("Forge did not select the direct-only artifact")
        direct_upload = upload(forge, dataset_id)
        if direct_upload["viewerSlideSha256"] != direct["omeSha256"]:
            raise RuntimeError("persisted direct OME SHA mismatch")
        slide_id = direct_upload["viewerSlideId"]
        tile = viewer.bytes(f"/api/v1/admin/slides/{slide_id}/preview/slide_files/10/0_0.jpg")
        if not tile.startswith(b"\xff\xd8"):
            raise RuntimeError("Viewer native tile was not a JPEG")
        originals = list((root / "viewer-data" / "originals" / slide_id).glob("*.ome.tif"))
        private_root = root / "viewer-data" / "private" / slide_id
        if len(originals) != 1 or private_root.exists():
            raise RuntimeError("direct Viewer storage contained an unexpected derivative")
        result["direct"] = {
            "format": direct["format"],
            "omeBytes": direct["omeBytes"],
            "packageBytes": direct["packageBytes"],
            "shaMatch": True,
            "tileBytes": len(tile),
            "storedOmeFiles": len(originals),
            "storedDziFiles": 0,
        }
        result["concurrency"] = concurrency_smoke(
            viewer, f"/api/v1/admin/slides/{slide_id}/preview/slide_files/10/0_0.jpg"
        )

        viewer_process.close()
        processes.remove(viewer_process)
        viewer_env["PATHLAB_DESKTOP_OME_DYNAMIC_ENABLED"] = "false"
        viewer_process = Process(
            [
                str(arguments.viewer_python), "-m", "uvicorn", "wsi_viewer.main:app",
                "--host", "127.0.0.1", "--port", str(viewer_port),
            ],
            cwd=arguments.viewer,
            env=viewer_env,
            label="Viewer fallback",
        )
        processes.append(viewer_process)
        viewer = Client(viewer_base)
        wait_http(viewer, "/livez")
        viewer_login(viewer)
        fallback = reconvert(forge, dataset_id)
        if fallback["format"] != "PREPARED_DZI_V2" or fallback["packageBytes"] <= 0:
            raise RuntimeError("Forge did not generate prepared-v2 after rollback")
        fallback_upload = upload(forge, dataset_id)
        if fallback_upload["viewerSlideSha256"] != fallback["packageSha256"]:
            raise RuntimeError("persisted prepared package SHA mismatch")
        fallback_slide = fallback_upload["viewerSlideId"]
        fallback_tile = viewer.bytes(
            f"/api/v1/admin/slides/{fallback_slide}/preview/slide_files/10/0_0.jpg"
        )
        if not fallback_tile.startswith(b"\xff\xd8"):
            raise RuntimeError("prepared fallback tile was not a JPEG")
        fallback_files = list((root / "viewer-data" / "private" / fallback_slide).rglob("*"))
        if not any(path.suffix.lower() == ".jpg" for path in fallback_files):
            raise RuntimeError("prepared fallback did not persist DZI tiles")
        result["fallback"] = {
            "format": fallback["format"],
            "omeBytes": fallback["omeBytes"],
            "packageBytes": fallback["packageBytes"],
            "shaMatch": True,
            "tileBytes": len(fallback_tile),
            "storedFiles": sum(path.is_file() for path in fallback_files),
        }
        success = True
    finally:
        for process in reversed(processes):
            process.close()
        if arguments.evidence:
            arguments.evidence.parent.mkdir(parents=True, exist_ok=True)
            arguments.evidence.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
        if success:
            shutil.rmtree(root, ignore_errors=True)
        else:
            print(f"Failure state retained at {root}", file=sys.stderr)
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
