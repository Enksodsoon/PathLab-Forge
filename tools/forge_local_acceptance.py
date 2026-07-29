"""Drive the loopback-only Forge acceptance API without exposing session credentials."""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-log", required=True, type=Path)
    parser.add_argument("--session-file", type=Path)
    parser.add_argument(
        "action",
        choices=(
            "datasets",
            "full-vsi-1.5",
            "full-vsi-2",
            "qupath-vsi-crop",
            "ome-cancel-restart",
            "restart-current",
            "approve-current",
            "seed-vsi-annotations",
            "start-pairing",
            "exchange",
            "upload",
            "upload-status",
        ),
    )
    parser.add_argument("--dataset-id")
    parser.add_argument("--viewer-url", default="http://127.0.0.1:8010")
    args = parser.parse_args()

    launch_url = latest_launch_url(args.server_log)
    base = launch_url.split("/?", 1)[0]
    cookie_jar: http.cookiejar.FileCookieJar
    if args.session_file:
        cookie_jar = http.cookiejar.MozillaCookieJar(args.session_file)
        if args.session_file.is_file():
            cookie_jar.load(ignore_discard=True, ignore_expires=True)
    else:
        cookie_jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(cookie_jar)
    )
    try:
        session = opener.open(f"{base}/api/session")
    except urllib.error.HTTPError as error:
        if error.code != 401:
            raise
        with opener.open(launch_url) as launch_response:
            launch_response.read(1)
        session = opener.open(f"{base}/api/session")
        if args.session_file:
            args.session_file.parent.mkdir(parents=True, exist_ok=True)
            cookie_jar.save(ignore_discard=True, ignore_expires=True)
    csrf = session.headers["X-Forge-CSRF"]

    if args.action == "datasets":
        result = request_json(opener, f"{base}/api/datasets")
    elif args.action == "full-vsi-1.5":
        dataset_id = required_dataset(args.dataset_id)
        write(opener, csrf, f"{base}/api/datasets/{dataset_id}/inspect")
        query = urllib.parse.urlencode(
            {
                "series": 2,
                "downsample": 1.5,
                "x": 0,
                "y": 0,
                "width": 165845,
                "height": 90735,
            }
        )
        configured = write(
            opener, csrf, f"{base}/api/datasets/{dataset_id}/series?{query}"
        )
        started = write(opener, csrf, f"{base}/api/datasets/{dataset_id}/convert")
        result = {"configured": configured, "started": started}
    elif args.action in {"full-vsi-2", "qupath-vsi-crop"}:
        dataset_id = required_dataset(args.dataset_id)
        write(opener, csrf, f"{base}/api/datasets/{dataset_id}/inspect")
        values = (
            {
                "series": 2,
                "downsample": 2.0,
                "x": 0,
                "y": 0,
                "width": 165845,
                "height": 90735,
            }
            if args.action == "full-vsi-2"
            else {
                "series": 2,
                "downsample": 1.5,
                "x": 69790,
                "y": 23372,
                "width": 11336,
                "height": 11040,
            }
        )
        configured = write(
            opener,
            csrf,
            f"{base}/api/datasets/{dataset_id}/series?"
            + urllib.parse.urlencode(values),
        )
        started = write(opener, csrf, f"{base}/api/datasets/{dataset_id}/convert")
        result = {"configured": configured, "started": started}
    elif args.action == "ome-cancel-restart":
        dataset_id = required_dataset(args.dataset_id)
        write(opener, csrf, f"{base}/api/datasets/{dataset_id}/inspect")
        query = urllib.parse.urlencode(
            {
                "series": 0,
                "downsample": 1.5,
                "x": 1024,
                "y": 1024,
                "width": 4096,
                "height": 4096,
            }
        )
        configured = write(
            opener, csrf, f"{base}/api/datasets/{dataset_id}/series?{query}"
        )
        first = write(opener, csrf, f"{base}/api/datasets/{dataset_id}/convert")
        time.sleep(2)
        cancelled = write(opener, csrf, f"{base}/api/datasets/{dataset_id}/cancel")
        for _ in range(100):
            current = dataset(opener, base, dataset_id)
            if current["status"] == "CANCELLED":
                break
            time.sleep(0.1)
        restarted = write(opener, csrf, f"{base}/api/datasets/{dataset_id}/convert")
        result = {
            "configured": configured,
            "firstArtifact": first["currentArtifactRevision"],
            "cancelled": cancelled,
            "restarted": restarted,
        }
    elif args.action == "restart-current":
        dataset_id = required_dataset(args.dataset_id)
        result = write(opener, csrf, f"{base}/api/datasets/{dataset_id}/convert")
    elif args.action == "approve-current":
        dataset_id = required_dataset(args.dataset_id)
        artifacts = request_json(opener, f"{base}/api/datasets/{dataset_id}/artifacts")
        if isinstance(artifacts, dict) and "revisions" in artifacts:
            current_id = artifacts["currentRevision"]
            current = next(
                item for item in artifacts["revisions"] if item["id"] == current_id
            )
        else:
            artifact_items = (
                artifacts["artifacts"] if isinstance(artifacts, dict) else artifacts
            )
            current = next(
                item for item in artifact_items if not item["historical"]
            )
        result = write(
            opener,
            csrf,
            f"{base}/api/datasets/{dataset_id}/artifacts/{current['id']}/approve",
        )
    elif args.action == "seed-vsi-annotations":
        dataset_id = required_dataset(args.dataset_id)
        created = []
        for annotation_type, geometry, label in (
            ("point", "75000,30000", "Acceptance point"),
            ("ruler", "69790,23372;81126,34412", "QuPath crop diagonal"),
        ):
            query = urllib.parse.urlencode(
                {
                    "type": annotation_type,
                    "geometry": geometry,
                    "label": label,
                    "color": "#f3b33d",
                }
            )
            created.append(
                write(
                    opener,
                    csrf,
                    f"{base}/api/datasets/{dataset_id}/annotations?{query}",
                )
            )
        result = {"annotations": created}
    elif args.action == "start-pairing":
        result = write(
            opener,
            csrf,
            f"{base}/api/viewer/pairing/start?viewerUrl="
            + urllib.parse.quote(args.viewer_url, safe=""),
        )
    elif args.action == "exchange":
        result = write(opener, csrf, f"{base}/api/viewer/pairing/exchange")
    elif args.action == "upload-status":
        result = request_json(opener, f"{base}/api/viewer/upload")
    else:
        dataset_id = required_dataset(args.dataset_id)
        result = write(opener, csrf, f"{base}/api/datasets/{dataset_id}/upload")

    print(json.dumps(redact(result), indent=2))


def latest_launch_url(log: Path) -> str:
    matches = re.findall(r"Open this one-time local URL: (http://\S+)", log.read_text())
    if not matches:
        raise RuntimeError("Forge launch URL is not present in the server log")
    return matches[-1]


def required_dataset(dataset_id: str | None) -> str:
    if not dataset_id:
        raise ValueError("--dataset-id is required for this action")
    return urllib.parse.quote(dataset_id, safe="")


def write(
    opener: urllib.request.OpenerDirector, csrf: str, url: str
) -> dict[str, object]:
    parsed = urllib.parse.urlsplit(url)
    origin = f"{parsed.scheme}://{parsed.netloc}"
    request = urllib.request.Request(
        url,
        data=b"",
        method="POST",
        headers={"Origin": origin, "X-Forge-CSRF": csrf},
    )
    try:
        return json.loads(opener.open(request).read())
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{error.code} from {url}: {detail}") from error


def request_json(
    opener: urllib.request.OpenerDirector, url: str
) -> dict[str, object]:
    return json.loads(opener.open(url).read())


def dataset(
    opener: urllib.request.OpenerDirector, base: str, dataset_id: str
) -> dict[str, object]:
    listing = request_json(opener, f"{base}/api/datasets")
    return next(item for item in listing["datasets"] if item["id"] == dataset_id)


def redact(value: object) -> object:
    if isinstance(value, dict):
        return {
            key: ("[redacted]" if key in {"deviceSecret", "deviceCode"} else redact(item))
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


if __name__ == "__main__":
    main()
