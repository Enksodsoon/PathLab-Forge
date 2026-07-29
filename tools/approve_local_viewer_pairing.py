"""Approve a Forge device code in an isolated loopback Viewer acceptance instance."""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import urllib.request


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--viewer-url", default="http://127.0.0.1:8010")
    parser.add_argument("--username", default="forgeadmin")
    parser.add_argument("--user-code", required=True)
    args = parser.parse_args()
    password = os.environ.get("PATHLAB_ACCEPTANCE_ADMIN_PASSWORD", "")
    if not password:
        raise RuntimeError("PATHLAB_ACCEPTANCE_ADMIN_PASSWORD is required")

    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
    )
    login = json_request(
        opener,
        f"{args.viewer_url}/api/v1/auth/session",
        {"username": args.username, "password": password},
    )
    request = urllib.request.Request(
        f"{args.viewer_url}/api/v1/desktop/pairings/approve",
        data=json.dumps({"userCode": args.user_code}).encode(),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-CSRF-Token": str(login["csrfToken"]),
        },
    )
    with opener.open(request) as response:
        if response.status != 204:
            raise RuntimeError(f"Viewer returned HTTP {response.status}")
    print("Local Viewer pairing approved")


def json_request(
    opener: urllib.request.OpenerDirector, url: str, payload: dict[str, str]
) -> dict[str, object]:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    return json.loads(opener.open(request).read())


if __name__ == "__main__":
    main()
