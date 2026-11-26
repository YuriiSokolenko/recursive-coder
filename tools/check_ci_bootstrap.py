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
GRADLE_ARCHIVE_NAME = f"gradle-{VERSION}-bin.zip"
GRADLE_URL = f"https://services.gradle.org/distributions/{GRADLE_ARCHIVE_NAME}"
GRADLE_SHA256 = "21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607"

# Compared to the official GitHub git/ref/tags endpoints on 2026-11-22.
# The trailing versions are documentation only; commit SHAs are the authority.
APPROVED_ACTIONS = {
    "actions/checkout": ("3d3c42e5aac5ba805825da76410c181273ba90b1", "v7", 6),
    "actions/setup-java": ("d1374f8ac74474200d16ef8ecee22c7ae7a51880", "v6", 5),
    "actions/setup-python": ("ece7cb06caefa5fff74198d8649806c4678c61a1", "v6", 4),
    "actions/setup-node": ("249970729cb0ef3589644e2896645e5dc5ba9c38", "v6", 1),
    "actions/upload-artifact": ("cf430e030ddbb5b0abf93d22962f4752f3646cd9", "v7", 4),
    "actions/download-artifact": ("9000827ccba6bdab643e8b6fd33ac0654aef8333", "v8", 4),
    # Official actions/cache refs/tags/v5 verified on 2026-11-26.
    "actions/cache/restore": ("caa296126883cff596d87d8935842f9db880ef25", "v5", 1),
    "actions/cache/save": ("caa296126883cff596d87d8935842f9db880ef25", "v5", 1),
}
GRADLE_JOBS = ("architecture", "bytecode-build", "bytecode-verifier", "kotlin")
REQUIRED_JOBS = (*GRADLE_JOBS, "cli-e2e", "contracts")
USE_LINE = re.compile(r"^\s*(?:-\s*)?uses\s*:\s*(.*?)\s*$")
PINNED = re.compile(r"([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?)@([0-9a-f]{40})\s+#\s+(v[0-9]+(?:\.[0-9]+){0,2})")
JOB_LINE = re.compile(r"^  ([a-z][a-z0-9-]*):\s*$", re.MULTILINE)
INSTALL = "run: python3 -I tools/check_ci_bootstrap.py --install-gradle"


CACHE_PATH = "${{ runner.temp }}/recursive-gradle-module-cache"
CACHE_KEY = "gradle-module-cache-v3-${{ runner.os }}-${{ runner.arch }}-9.8.2-${{ hashFiles('gradle/verification-metadata.xml', 'build-logic/gradle/verification-metadata.xml', 'build-logic/gradle/libs.versions.toml', '**/*.gradle.kts', 'gradle.properties', 'tools/check_dependency_verification.py', 'tools/resolve_ci_dependencies.init.gradle', '.github/workflows/ci.yml') }}"
CACHE_SAVE_IF = "${{ success() && github.event_name == 'push' && (github.ref == 'refs/heads/dev' || github.ref == 'refs/heads/main') && steps.gradle-cache.outputs.cache-hit != 'true' }}"
GRADLE_OPTS_BLOCK = "env:\n  GRADLE_OPTS: -Dorg.gradle.caching=false\n"

CACHE_PREPARE_STEP = '      - name: Prepare fresh Gradle user home\n        run: |\n          mkdir "$RUNNER_TEMP/recursive-gradle-home"\n          echo "GRADLE_USER_HOME=$RUNNER_TEMP/recursive-gradle-home" >> "$GITHUB_ENV"\n'

CACHE_RESTORE_STEP = "      - name: Restore protected-branch Gradle module cache\n        id: gradle-cache\n        uses: actions/cache/restore@caa296126883cff596d87d8935842f9db880ef25 # v5\n        with:\n          path: ${{ runner.temp }}/recursive-gradle-module-cache\n          key: gradle-module-cache-v3-${{ runner.os }}-${{ runner.arch }}-9.8.2-${{ hashFiles('gradle/verification-metadata.xml', 'build-logic/gradle/verification-metadata.xml', 'build-logic/gradle/libs.versions.toml', '**/*.gradle.kts', 'gradle.properties', 'tools/check_dependency_verification.py', 'tools/resolve_ci_dependencies.init.gradle', '.github/workflows/ci.yml') }}\n"

CACHE_VERIFY_STEP = '      - name: Verify restored module cache before Gradle resolution\n        env:\n          CACHE_HIT: ${{ steps.gradle-cache.outputs.cache-hit }}\n        run: |\n          if [ "$CACHE_HIT" = true ]; then\n            python3 -I tools/check_dependency_verification.py --restore-module-cache \\\n              "$RUNNER_TEMP/recursive-gradle-module-cache" \\\n              "$GRADLE_USER_HOME/caches/modules-2"\n          else\n            echo "No exact dependency cache hit; resolve from official repositories"\n          fi\n          rm -rf "$RUNNER_TEMP/recursive-gradle-module-cache"\n'

CACHE_DOWNLOAD_STEP = '      - name: Download current-run Gradle module cache\n        uses: actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333 # v8\n        with:\n          name: gradle-module-cache\n          path: ${{ runner.temp }}/recursive-gradle-module-cache\n'

CACHE_VERIFY_DOWNLOAD_STEP = '      - name: Verify current-run module cache before Gradle resolution\n        run: |\n          python3 -I tools/check_dependency_verification.py --restore-module-cache \\\n            "$RUNNER_TEMP/recursive-gradle-module-cache" \\\n            "$GRADLE_USER_HOME/caches/modules-2"\n          rm -rf "$RUNNER_TEMP/recursive-gradle-module-cache"\n'

CACHE_PLUGIN_STEP = '      - name: Resolve Gradle plugins with bounded registry retry\n        run: python3 -I tools/run_ci_gradle.py --retry-resolution --label plugin-resolution -- ./gradlew --dependency-verification strict --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g --no-build-cache help\n'

CACHE_RESOLVE_STEP = '      - name: Resolve all CI build and test dependencies with bounded registry retry\n        run: |\n          python3 -I tools/run_ci_gradle.py --retry-resolution --label dependency-resolution -- ./gradlew --dependency-verification strict --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g --no-build-cache --no-configuration-cache --init-script "$GITHUB_WORKSPACE/tools/resolve_ci_dependencies.init.gradle" :resolveCiDependencies\n          python3 -I tools/run_ci_gradle.py --retry-resolution --label build-logic-dependency-resolution -- ./gradlew --dependency-verification strict --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g --no-build-cache --no-configuration-cache --init-script "$GITHUB_WORKSPACE/tools/resolve_ci_dependencies.init.gradle" -p build-logic :resolveCiDependencies\n'

CACHE_STAGE_STEP = '      - name: Stage pinned module files and resolution metadata\n        run: |\n          python3 -I tools/check_dependency_verification.py --stage-module-cache \\\n            "$GRADLE_USER_HOME/caches/modules-2" \\\n            "$RUNNER_TEMP/recursive-gradle-module-cache"\n'

CACHE_PROOF_STEP = '      - name: Prove staged dependencies resolve offline in a fresh Gradle home\n        run: |\n          probe_home="$(mktemp -d "$RUNNER_TEMP/recursive-gradle-offline-proof.XXXXXX")"\n          trap \'rm -rf "$probe_home"\' EXIT\n          python3 -I tools/check_dependency_verification.py --restore-module-cache \\\n            "$RUNNER_TEMP/recursive-gradle-module-cache" "$probe_home/caches/modules-2"\n          export GRADLE_USER_HOME="$probe_home"\n          ./gradlew --dependency-verification strict --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g --no-build-cache --offline --warning-mode=fail --no-configuration-cache --init-script "$GITHUB_WORKSPACE/tools/resolve_ci_dependencies.init.gradle" :resolveCiDependencies\n          ./gradlew --dependency-verification strict --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g --no-build-cache --offline --warning-mode=fail --no-configuration-cache --init-script "$GITHUB_WORKSPACE/tools/resolve_ci_dependencies.init.gradle" -p build-logic :resolveCiDependencies\n'

CACHE_PUBLISH_STEP = '      - name: Publish current-run Gradle module cache\n        uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7\n        with:\n          name: gradle-module-cache\n          path: ${{ runner.temp }}/recursive-gradle-module-cache\n          if-no-files-found: error\n          retention-days: 3\n          overwrite: true\n'

CACHE_SAVE_STEP = "      - name: Save protected-branch Gradle module cache\n        if: ${{ success() && github.event_name == 'push' && (github.ref == 'refs/heads/dev' || github.ref == 'refs/heads/main') && steps.gradle-cache.outputs.cache-hit != 'true' }}\n        uses: actions/cache/save@caa296126883cff596d87d8935842f9db880ef25 # v5\n        with:\n          path: ${{ runner.temp }}/recursive-gradle-module-cache\n          key: ${{ steps.gradle-cache.outputs.cache-primary-key }}\n"


DEPENDENCY_INPUT = re.compile(
    r"(?:^|/)(?:[^/]+\.gradle\.kts|[^/]+\.toml|verification-metadata\.xml|gradle\.properties)\Z"
    r"|^tools/[^/]+\.init\.gradle\Z"
)
CACHE_MARKERS = ("recursive-gradle-module-cache", "recursive-gradle-home", "actions/cache/",
                 "resolveCiDependencies", "run_ci_gradle.py")
PRODUCER_STEPS = (CACHE_PREPARE_STEP, CACHE_RESTORE_STEP, CACHE_VERIFY_STEP,
                  CACHE_PLUGIN_STEP, CACHE_RESOLVE_STEP, CACHE_STAGE_STEP,
                  CACHE_PROOF_STEP, CACHE_PUBLISH_STEP, CACHE_SAVE_STEP)
CONSUMER_STEPS = (CACHE_PREPARE_STEP, CACHE_DOWNLOAD_STEP, CACHE_VERIFY_DOWNLOAD_STEP)
CONSUMER_JOBS = ("architecture", "kotlin")


def cache_key_globs(key: str = CACHE_KEY) -> list[str]:
    match = re.search(r"hashFiles\((.*)\) \}\}\Z", key)
    if match is None:
        raise AssertionError("Dependency cache key must end with one hashFiles expression")
    return re.findall(r"'([^']+)'", match.group(1))


def glob_regex(pattern: str) -> re.Pattern[str]:
    """Translate the hashFiles globs used here: **/ spans directories, * does not."""
    parts = re.split(r"(\*\*/|\*)", pattern)
    translated = "".join(
        "(?:.*/)?" if part == "**/" else "[^/]*" if part == "*" else re.escape(part)
        for part in parts
    )
    return re.compile(translated + r"\Z")


def audit_cache_key_coverage(tracked: list[str], key: str = CACHE_KEY) -> None:
    """Every tracked dependency input must change the exact persistent cache key."""
    patterns = [glob_regex(glob) for glob in cache_key_globs(key)]
    missing = [path for path in tracked if DEPENDENCY_INPUT.search(path)
               and not any(pattern.match(path) for pattern in patterns)]
    if missing:
        raise AssertionError(f"Dependency inputs missing from the cache key: {missing}")


def audit_cache_policy(source: str) -> None:
    """Drift guard; trusted review still owns the workflow and cache boundary."""
    if source.split("\npermissions:", 1)[0] != (
        "name: Recursive Coder CI\n\non:\n"
        "  pull_request:\n    branches: [dev, main]\n"
        "  push:\n    branches: [dev, main]\n  workflow_dispatch:\n"
    ):
        raise AssertionError("Unexpected cache workflow trigger")
    if ("\n" + GRADLE_OPTS_BLOCK + "\nconcurrency:\n" not in source
            or source.count("GRADLE_OPTS") != 1):
        raise AssertionError("Workflow-wide GRADLE_OPTS must only disable the build cache")
    sections = re.split(
        r"(?=^  [a-z][a-z0-9-]*:\s*$)", source.split("\njobs:\n", 1)[1],
        flags=re.MULTILINE,
    )
    for section in sections:
        match = JOB_LINE.match(section)
        if not match:
            continue
        job = match.group(1)
        expected = PRODUCER_STEPS if job == "bytecode-build" else (
            CONSUMER_STEPS if job in CONSUMER_JOBS else ()
        )
        steps = [
            step.rstrip() + "\n"
            for step in re.split(r"(?=^      - )", section, flags=re.MULTILINE)
            if any(marker in step for marker in CACHE_MARKERS)
        ]
        if tuple(steps) != expected:
            raise AssertionError(f"Changed dependency cache policy in {job}")
        if not expected:
            continue
        if section.index(INSTALL) > section.index(CACHE_PREPARE_STEP):
            raise AssertionError(f"Unsafe dependency cache restore order in {job}")
        verify = CACHE_VERIFY_STEP if job == "bytecode-build" else CACHE_VERIFY_DOWNLOAD_STEP
        first_gradle = re.search(r"^(?!\s*#).*\./gradlew ", section, flags=re.MULTILINE)
        if first_gradle is None or section.index(verify) > first_gradle.start():
            raise AssertionError(f"Gradle resolution precedes cache verification in {job}")
        online = (CACHE_PLUGIN_STEP + CACHE_RESOLVE_STEP).splitlines() if job == "bytecode-build" else []
        for line in section.splitlines():
            if (re.match(r"\s*#", line) is None and "./gradlew " in line
                    and "--offline" not in line.split() and line not in online):
                raise AssertionError(f"Gradle may reach registries outside dependency resolution in {job}")
        if job == "bytecode-build" and section.index(CACHE_RESOLVE_STEP) > section.index(
                "      - name: Compile and install production artifacts without test execution\n"):
            raise AssertionError("Production compilation precedes dependency resolution")
        if job in CONSUMER_JOBS and "    needs: [bytecode-verifier, bytecode-build]\n" not in section:
            raise AssertionError(f"Missing current-run producer dependency in {job}")
    if "restore-keys:" in source or "pull_request_target:" in source:
        raise AssertionError("Unsafe dependency cache fallback or trigger")


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
    audit_cache_policy(source)


def audit_gradlew_executable_mode() -> None:
    """Require the tracked launcher to retain its Git executable bit.

    Inspect the Git index, not just the local filesystem: GitHub checkout
    restores tracked file modes from the committed tree.
    """
    result = subprocess.run(
        ["git", "ls-files", "--stage", "--", "gradlew"],
        cwd=ROOT, check=True, capture_output=True, text=True,
    )
    if re.fullmatch(r"100755 [0-9a-f]{40} 0\tgradlew\n?", result.stdout) is None:
        raise AssertionError(
            "gradlew must be tracked with Git mode 100755 (executable); "
            "restore its executable bit before running CI"
        )


def tracked_files() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True, text=True,
    )
    return [path for path in result.stdout.split("\0") if path]


def audit_repository() -> None:
    workflows = sorted((ROOT / ".github/workflows").glob("*.yml"))
    workflows += sorted((ROOT / ".github/workflows").glob("*.yaml"))
    if len(workflows) != 1 or workflows[0].name != "ci.yml":
        raise AssertionError("Unexpected additional/reusable workflow; review and pin before accepting")
    audit_gradlew_executable_mode()
    audit_workflow(workflows[0].read_text(encoding="utf-8"))
    audit_cache_key_coverage(tracked_files())
    count = sum(entry[2] for entry in APPROVED_ACTIONS.values())
    print(f"PASS: six hosted CI jobs and {count} immutable reviewed action references")


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
    archive = install_root / GRADLE_ARCHIVE_NAME
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
