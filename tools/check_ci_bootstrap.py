#!/usr/bin/env python3
"""CI supply-chain audit and official SHA-256-verified Gradle bootstrap.

The distribution checksum below comes from https://gradle.org/release-checksums/ ;
the archive's downloaded checksum sidecar is deliberately not trusted.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "9.8.2"
GRADLE_URL = f"https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip"
GRADLE_SHA256 = "21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607"

# Compared to the official GitHub git/ref/tags endpoints on 2026-11-22.
# The trailing versions are documentation only; commit SHAs are the authority.
APPROVED_ACTIONS = {
    "actions/checkout": ("3d3c42e5aac5ba805825da76410c181273ba90b1", "v7", 6),
    "actions/setup-java": ("d1374f8ac74474200d16ef8ecee22c7ae7a51880", "v6", 5),
    "actions/setup-python": ("ece7cb06caefa5fff74198d8649806c4678c61a1", "v6", 4),
    "actions/setup-node": ("249970729cb0ef3589644e2896645e5dc5ba9c38", "v6", 1),
    "actions/upload-artifact": ("cf430e030ddbb5b0abf93d22962f4752f3646cd9", "v7", 3),
    "actions/download-artifact": ("9000827ccba6bdab643e8b6fd33ac0654aef8333", "v8", 2),
}
REQUIRED_JOBS = (
    "architecture", "bytecode-build", "bytecode-verifier",
    "kotlin", "cli-e2e", "contracts",
)
GRADLE_JOBS = ("architecture", "bytecode-build", "bytecode-verifier", "kotlin")
USE_LINE = re.compile(r"^\s*(?:-\s*)?uses\s*:\s*(.*?)\s*$")
PINNED = re.compile(r"([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)@([0-9a-f]{40})\s+#\s+(v[0-9]+(?:\.[0-9]+){0,2})")
JOB_LINE = re.compile(r"^  ([a-z][a-z0-9-]*):\s*$", re.MULTILINE)
INSTALL = "run: python3 -I tools/check_ci_bootstrap.py --install-gradle"


def audit_workflow(source: str) -> None:
    """Reject mutable, unreviewed, missing or additionally introduced actions."""
    counts = dict.fromkeys(APPROVED_ACTIONS, 0)
    lines = source.splitlines()
    for index, line in enumerate(lines):
        match = USE_LINE.match(line)
        # This policy deliberately rejects YAML flow-style action references;
        # every action must occupy its own "uses:" line to be reviewed.
        if re.search(r"\buses\s*:", line) and match is None and not line.lstrip().startswith("#"):
            raise AssertionError(f"Unreviewed GitHub Actions uses syntax: {line.strip()}")
        if match is None:
            continue
        pin = PINNED.fullmatch(match.group(1).strip())
        if pin is None:
            raise AssertionError(f"Unpinned/malformed GitHub Actions reference: {line.strip()}")
        action, sha, version = pin.groups()
        if action not in APPROVED_ACTIONS:
            raise AssertionError(f"Unreviewed GitHub Actions reference: {action}")
        approved_sha, approved_version, _ = APPROVED_ACTIONS[action]
        if (sha, version) != (approved_sha, approved_version):
            raise AssertionError(f"Changed GitHub Actions pin: {action}@{sha} # {version}")
        counts[action] += 1
        if action == "actions/checkout":
            if not line.startswith("      - uses: "):
                raise AssertionError("Checkout must be a directly visible job step")
            step_tail = []
            for subsequent in lines[index + 1:]:
                if subsequent.startswith("      - "):
                    break
                step_tail.append(subsequent)
            if (step_tail.count("        with:") != 1
                    or step_tail.count("          persist-credentials: false") != 1
                    or sum("persist-credentials" in entry for entry in step_tail) != 1):
                raise AssertionError("Each checkout must explicitly set persist-credentials: false")
    for action, (_, _, required_count) in APPROVED_ACTIONS.items():
        if counts[action] != required_count:
            raise AssertionError(
                f"Expected {required_count} reviewed uses of {action}, found {counts[action]}"
            )
    jobs_section = source.split("\njobs:\n", 1)
    if len(jobs_section) != 2:
        raise AssertionError("Missing jobs section")
    declared_jobs = tuple(JOB_LINE.findall(jobs_section[1]))
    if set(declared_jobs) != set(REQUIRED_JOBS) or len(declared_jobs) != len(REQUIRED_JOBS):
        raise AssertionError(f"Unexpected required CI jobs: {declared_jobs}")
    if source.count("runs-on: ubuntu-24.04") != len(REQUIRED_JOBS):
        raise AssertionError("Every CI job must use a fresh hosted ubuntu-24.04 runner")
    if source.count(INSTALL) != len(GRADLE_JOBS):
        raise AssertionError("All Gradle-consuming jobs must provision authenticated Gradle")
    if "gradle/actions/setup-gradle@" in source:
        raise AssertionError("Unverified setup-gradle executable provisioning is prohibited")
    if source.count("tools/check_ci_bootstrap.py --audit") != len(REQUIRED_JOBS):
        raise AssertionError("All CI jobs must audit pinned actions and Gradle bootstrap")


def audit_repository() -> None:
    workflows = sorted((ROOT / ".github/workflows").glob("*.yml"))
    workflows += sorted((ROOT / ".github/workflows").glob("*.yaml"))
    if len(workflows) != 1 or workflows[0].name != "ci.yml":
        raise AssertionError("Unexpected additional/reusable workflow; review and pin before accepting")
    audit_workflow(workflows[0].read_text(encoding="utf-8"))
    print("PASS: six hosted CI jobs and 21 immutable reviewed action references")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_archive_sha256(archive: Path, expected: str = GRADLE_SHA256) -> None:
    actual = sha256_file(archive)
    if re.fullmatch(r"[0-9a-f]{64}", expected) is None or actual != expected:
        raise AssertionError(f"Gradle distribution SHA-256 mismatch: got {actual}")


def install_gradle() -> None:
    """Download into fresh hosted-runner temp; verify BEFORE extracting or executing."""
    runner_temp = os.environ.get("RUNNER_TEMP")
    github_env = os.environ.get("GITHUB_ENV")
    github_path = os.environ.get("GITHUB_PATH")
    if os.environ.get("CI") != "true" or not all((runner_temp, github_env, github_path)):
        raise RuntimeError("Authenticated Gradle installation is supported only in GitHub CI")
    install_root = Path(tempfile.mkdtemp(prefix=f"verified-gradle-{VERSION}-", dir=runner_temp))
    archive = install_root / f"gradle-{VERSION}-bin.zip"
    subprocess.run([
        "curl", "--fail", "--location", "--silent", "--show-error",
        "--proto", "=https", "--proto-redir", "=https",
        "--retry", "3", "--retry-all-errors", "--connect-timeout", "20",
        "--max-time", "240", "--output", str(archive), GRADLE_URL,
    ], check=True)
    try:
        require_archive_sha256(archive)
    except Exception:
        archive.unlink(missing_ok=True)
        raise
    subprocess.run(["unzip", "-q", str(archive), "-d", str(install_root)], check=True)
    verified_bin = install_root / f"gradle-{VERSION}/bin/gradle"
    if not verified_bin.is_file() or not os.access(verified_bin, os.X_OK):
        raise RuntimeError("Verified archive did not contain an executable Gradle launcher")
    with open(github_env, "a", encoding="utf-8") as output:
        output.write(f"RECURSIVE_VERIFIED_GRADLE={verified_bin}\n")
    with open(github_path, "a", encoding="utf-8") as output:
        output.write(f"{verified_bin.parent}\n")
    print(f"PASS: Gradle {VERSION} archive SHA-256 {GRADLE_SHA256}")
    print(f"Verified executable: {verified_bin}")


def main() -> None:
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--audit", action="store_true")
    group.add_argument("--install-gradle", action="store_true")
    args = parser.parse_args()
    if args.audit:
        audit_repository()
    else:
        install_gradle()


if __name__ == "__main__":
    main()
