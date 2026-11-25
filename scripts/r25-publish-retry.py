"""Wait for the installed R25 publisher's explicit pre-transaction lock refusal."""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path


BUSY = {"outcome": "REFUSED", "failure_class": "Refused", "reason": "shared_publication_busy"}


def is_shared_lease_busy(result):
    if result.returncode != 65 or result.stdout.strip():
        return False
    try:
        return json.loads(result.stderr) == BUSY
    except ValueError:
        return False


def run_with_lease_retry(command, wait_seconds, interval_seconds, *, check_release, invoke,
                         sleep=time.sleep, clock=time.monotonic, note=print):
    deadline = None
    while True:
        check_release()
        if deadline is not None and clock() >= deadline:
            return result
        result = invoke(command)
        if not is_shared_lease_busy(result):
            return result
        now = clock()
        if deadline is None:
            deadline = now + wait_seconds
        remaining = deadline - now
        if remaining <= 0:
            return result
        note("R25 shared publication lease busy; waiting for the current owner.")
        sleep(min(interval_seconds, remaining))


def require_current_release(commit):
    response = subprocess.run(
        ["git", "ls-remote", "--exit-code", "origin", "refs/heads/release"],
        capture_output=True, text=True, timeout=15, check=False,
    )
    if response.returncode or not response.stdout.strip():
        raise ValueError("RELEASE_REF_UNAVAILABLE")
    if response.stdout.strip() != f"{commit}\trefs/heads/release":
        raise ValueError("RELEASE_ADVANCED")


def verify_receipt(receipt, commit, manifest):
    expected = json.loads(manifest.read_text(encoding="utf-8"))
    outcome = receipt["outcome"]
    transaction = receipt["transaction_id"]
    release = receipt["release"]
    if not (
        release["commit_sha"] == expected["commit_sha"] == commit
        and release["artifact_sha256"] == expected["artifact"]["sha256"]
        and ((outcome == "COMMITTED" and isinstance(transaction, str)
              and re.fullmatch(r"[0-9a-f]{32}", transaction))
             or (outcome == "CURRENT_VERIFIED_NOOP" and transaction is None))
    ):
        raise ValueError("PUBLICATION_RECEIPT_MISMATCH")


def summary(message):
    print(message, flush=True)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as stream:
            print(message, file=stream)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--manifest", type=Path, help="Require a successful publish receipt for this candidate")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.commit) or len(args.command) < 2 or args.command[0] != "--":
        parser.error("the exact commit and a command after -- are required")

    try:
        result = run_with_lease_retry(
            args.command[1:], 180, 5,
            check_release=lambda: require_current_release(args.commit),
            invoke=lambda argv: subprocess.run(argv, capture_output=True, text=True, check=False),
        )
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        summary(f"R25 status {str(error) if type(error) is ValueError else 'RELEASE_CHECK_FAILED'}")
        return 1

    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    if is_shared_lease_busy(result):
        summary("R25 status SHARED_LEASE_WAIT_EXPIRED")
    elif result.returncode == 255 and args.manifest is None:
        summary("R25 status SSH_PREPARATION_FAILED. Publication has not started.")
    elif result.returncode in (124, 137, 255):
        summary("R25 status OUTCOME_UNKNOWN. Inspect the managed transaction before another attempt.")
    elif result.returncode:
        summary("R25 status REFUSED_OR_FAILED. See the publisher receipt for details.")
    elif args.manifest is not None:
        try:
            receipt = json.loads(result.stdout)
            verify_receipt(receipt, args.commit, args.manifest)
        except (KeyError, ValueError, TypeError, OSError):
            summary("R25 status OUTCOME_UNKNOWN. The publisher receipt could not be verified.")
            return 1
        summary(f"R25 receipt {receipt['outcome']} for {args.commit}")
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
