#!/usr/bin/env python3
"""Upload a release bundle to a Google Play track and commit the edit.

race-timer #81. Written rather than taken off the shelf, deliberately: this script is handed a
credential that can publish to the owner's Play account, and a third-party action holding that
credential is a larger trust decision than the automation is worth. It depends on `google-auth`
for RSA signing only — every HTTP call is plain urllib, so what happens to the credential is
readable in one file.

The Play Developer API works in *edits*: open one, change things inside it, validate, then commit.
Nothing an edit contains is visible to anyone until the commit, so a failure anywhere before that
point leaves the account exactly as it was. That is why this script does the upload and the track
assignment inside a single edit and commits once at the end.

Reads the service account JSON from PLAY_SERVICE_ACCOUNT_JSON (content, not a path — keeping it in
the environment means it never lands on the runner's disk).

Never prints the credential, the assertion, or the access token.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from google.auth import crypt, jwt

API = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def fail(msg: str) -> None:
    """Emit a GitHub Actions error annotation and stop."""
    print(f"::error::{msg}", flush=True)
    sys.exit(1)


def access_token(sa: dict) -> str:
    """Exchange a signed JWT assertion for an OAuth access token.

    google-auth's own Credentials class would do this, but its refresh path requires a `requests`
    transport. Signing the assertion directly keeps the dependency to the one thing Python cannot
    do on its own — RSA — and leaves the token exchange as an ordinary POST.
    """
    now = int(time.time())
    signer = crypt.RSASigner.from_service_account_info(sa)
    assertion = jwt.encode(
        signer,
        {
            "iss": sa["client_email"],
            "scope": SCOPE,
            "aud": sa["token_uri"],
            "iat": now,
            "exp": now + 3600,
        },
    )
    body = urllib.parse.urlencode(
        {
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": assertion.decode("ascii"),
        }
    ).encode()
    req = urllib.request.Request(sa["token_uri"], data=body, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            token = json.loads(resp.read())["access_token"]
    except urllib.error.HTTPError as exc:
        # The response body here can name the cause exactly (clock skew, disabled key, wrong
        # audience). It contains no secret — the assertion went the other way.
        fail(f"token exchange failed: HTTP {exc.code} {exc.read().decode('utf-8', 'replace')[:400]}")
    if not token:
        fail("token exchange returned no access_token")
    return token


def call(token: str, method: str, url: str, body=None, raw: bytes | None = None,
         content_type: str | None = None):
    """One JSON (or octet-stream) call. Returns (status, parsed-body)."""
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    if content_type:
        req.add_header("Content-Type", content_type)
    elif data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=900) as resp:
            payload = resp.read()
            return resp.status, (json.loads(payload) if payload else {})
    except urllib.error.HTTPError as exc:
        payload = exc.read()
        try:
            return exc.code, json.loads(payload)
        except Exception:
            return exc.code, {"raw": payload.decode("utf-8", "replace")[:400]}


def ok(status: int) -> bool:
    """Any 2xx. Not `status == 200`.

    Measured 2026-08-18 against the live API: `PUT edits/{id}/tracks/{track}` answers **204** when
    the track resource it would return is empty, and 200 otherwise — so an equality check on 200
    fails a release for a call that succeeded. Every endpoint here is treated the same way rather
    than special-casing the one that was caught, since the next one to do this would be silent in
    exactly the same way.
    """
    return 200 <= status < 300


def api_error(resp: dict) -> str:
    return resp.get("error", {}).get("message") or json.dumps(resp)[:300]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--package", required=True)
    ap.add_argument("--bundle", required=True)
    ap.add_argument("--track", default="internal")
    ap.add_argument("--status", default="draft", choices=["draft", "completed"])
    ap.add_argument("--expected-version-code", type=int, default=None,
                    help="Refuse to publish if Play reports a different versionCode than the "
                         "build produced. Catches uploading a stale artifact.")
    ap.add_argument("--release-name", default=None)
    ap.add_argument("--dry-run", action="store_true",
                    help="Open an edit, validate the upload, then DELETE the edit instead of "
                         "committing. Proves the whole path including credentials, and publishes "
                         "nothing.")
    args = ap.parse_args()

    if not os.path.isfile(args.bundle):
        fail(f"no bundle at {args.bundle}")

    raw_sa = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw_sa.strip():
        fail("PLAY_SERVICE_ACCOUNT_JSON is empty")
    try:
        sa = json.loads(raw_sa)
    except json.JSONDecodeError as exc:
        fail(f"PLAY_SERVICE_ACCOUNT_JSON is not valid JSON ({exc.msg})")
    for key in ("client_email", "private_key", "token_uri"):
        if not sa.get(key):
            fail(f"service account JSON is missing {key}")

    print(f"publishing as {sa['client_email']}")
    token = access_token(sa)
    print("access token acquired")

    base = f"{API}/applications/{args.package}"

    status, edit = call(token, "POST", f"{base}/edits")
    if not ok(status):
        # 401/403 here is the interesting one: it means the credential is valid but the Play
        # Console invitation is missing or carries the wrong permission.
        fail(f"could not open an edit: HTTP {status} — {api_error(edit)}")
    edit_id = edit["id"]
    print(f"edit {edit_id} opened")

    committed = False
    try:
        with open(args.bundle, "rb") as fh:
            blob = fh.read()
        print(f"uploading {os.path.basename(args.bundle)} ({len(blob):,} bytes)")
        status, uploaded = call(
            token, "POST",
            f"{UPLOAD}/applications/{args.package}/edits/{edit_id}/bundles?uploadType=media",
            raw=blob, content_type="application/octet-stream",
        )
        if not ok(status):
            fail(f"bundle upload rejected: HTTP {status} — {api_error(uploaded)}")

        version_code = int(uploaded["versionCode"])
        print(f"Play accepted the bundle as versionCode {version_code}")

        # The bundle on disk and the version this job believes it built are two different claims.
        # They diverge when a cached or stale artifact is picked up, and the symptom otherwise
        # appears much later as "the testers have the wrong build".
        if args.expected_version_code is not None and version_code != args.expected_version_code:
            fail(f"versionCode mismatch: built {args.expected_version_code}, "
                 f"Play read {version_code} from the uploaded bundle")

        release = {
            "versionCodes": [str(version_code)],
            "status": args.status,
        }
        if args.release_name:
            release["name"] = args.release_name

        status, _ = call(
            token, "PUT", f"{base}/edits/{edit_id}/tracks/{args.track}",
            body={"track": args.track, "releases": [release]},
        )
        if not ok(status):
            fail(f"could not assign to track {args.track}: HTTP {status}")
        print(f"assigned to track '{args.track}' with status '{args.status}'")

        status, validated = call(token, "POST", f"{base}/edits/{edit_id}:validate")
        if not ok(status):
            fail(f"Play rejected the edit at validation: HTTP {status} — {api_error(validated)}")
        print("edit validated by Play")

        if args.dry_run:
            print("dry run — deleting the edit instead of committing. Nothing is published.")
            return

        status, _ = call(token, "POST", f"{base}/edits/{edit_id}:commit")
        if not ok(status):
            fail(f"commit failed: HTTP {status}")
        committed = True
        print(f"::notice::Committed versionCode {version_code} to the {args.track} track "
              f"as '{args.status}'.")
    finally:
        # An uncommitted edit is inert, but leaving it open means the next run inherits an account
        # with stale edits lying around. Committing is what makes an edit permanent; deleting an
        # already-committed edit is not possible, hence the guard.
        if not committed:
            code, _ = call(token, "DELETE", f"{base}/edits/{edit_id}")
            print(f"edit {edit_id} discarded (HTTP {code}) — nothing was published")


if __name__ == "__main__":
    main()
