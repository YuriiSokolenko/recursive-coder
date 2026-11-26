#!/usr/bin/env python3
"""Collect independent base-helper evidence, never executing candidate files."""
import json
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request

REPO = "YuriiSokolenko/recursive-coder"
REMOTE = f"https://github.com/{REPO}.git"
API = f"https://api.github.com/repos/{REPO}"
HELPERS = (
    ("policy", ".claude/skills/strict-pr-review/scripts/check_policy_checkout.py"),
    ("c3", ".claude/skills/c3-pin-check/scripts/check_c3_pins.py"),
    ("vocabulary", ".claude/skills/diagnostic-vocabulary-audit/scripts/scan_literals.py"),
)


def sha(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("Expected full lowercase commit SHA")
    return value


def api(path):
    request = urllib.request.Request(API + path, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": "Bearer " + os.environ["REVIEW_TOKEN"],
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "recursive-coder-trusted-review",
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def pr_pair(number, base, head):
    p = api(f"/pulls/{number}")
    target = p["base"]["ref"]
    if (p["state"] != "open" or p["base"]["repo"]["full_name"] != REPO
            or target not in {"dev", "main"}
            or (target == "main" and (p["head"]["ref"] != "dev"
                or p["head"]["repo"]["full_name"] != REPO))
            or (p["base"]["sha"], p["head"]["sha"]) != (base, head)):
        raise ValueError("PR metadata differs from requested pair or target policy")
    return target


def emit(**fields):
    # JSON escapes control characters and newlines in candidate-derived output.
    print(json.dumps(fields, ensure_ascii=True), flush=True)


def collect(base, head, number, workflow):
    base, head, workflow = map(sha, (base, head, workflow))
    if base == head or not re.fullmatch(r"(?:0|[1-9][0-9]{0,9})", number):
        raise ValueError("Expected distinct commits and PR number (0 for pre-push)")
    target = pr_pair(number, base, head) if number != "0" else "dev"
    for branch in {"dev", target}:
        if api(f"/branches/{branch}")["protected"] is not True:
            raise ValueError("Trusted branch is not protected")
    emit(mode="candidate" if number == "0" else "pr", pr=number,
         base=base, head=head, target=target, workflow=workflow)
    with tempfile.TemporaryDirectory(prefix="trusted-review-") as tmp:
        root = Path(tmp)
        repo, home, helpers = (root / name for name in ("repo", "home", "helpers"))
        for path in (repo, home, helpers):
            path.mkdir(mode=0o700)
        env = {"PATH": "/usr/bin:/bin", "HOME": str(home), "LANG": "C.UTF-8",
               "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null",
               "GIT_TERMINAL_PROMPT": "0", "GIT_NO_REPLACE_OBJECTS": "1"}

        def git(*args):
            return subprocess.run(
                ["git", "-C", str(repo), "-c", "core.hooksPath=/dev/null",
                 "-c", "protocol.file.allow=never", *args], env=env,
                capture_output=True, check=True).stdout

        git("init", "--template=")
        git("remote", "add", "origin", REMOTE)
        for branch in sorted({"dev", target}):
            git("fetch", "--no-tags", "origin",
                f"refs/heads/{branch}:refs/remotes/origin/{branch}")
        git("merge-base", "--is-ancestor", workflow, "refs/remotes/origin/dev")
        git("merge-base", "--is-ancestor", base, f"refs/remotes/origin/{target}")
        ref = head if number == "0" else f"refs/pull/{number}/head"
        git("fetch", "--no-tags", "origin", ref)
        if git("rev-parse", "FETCH_HEAD^{commit}").decode().strip() != head:
            raise ValueError("Fetched head does not match requested SHA")
        if number == "0":
            git("merge-base", "--is-ancestor", base, head)
        git("merge-base", base, head)
        git("checkout", "--detach", base)
        if git("rev-parse", "HEAD").decode().strip() != base or git(
                "status", "--porcelain", "--untracked-files=all"):
            raise ValueError("Base checkout is not clean and exact")
        # Extract only ordinary blobs, never follow a symlink or use head files.
        for name, path in HELPERS:
            entry = git("ls-tree", base, "--", path).decode().strip()
            if not re.fullmatch(r"100(?:644|755) blob [0-9a-f]{40}\t" + re.escape(path), entry):
                raise ValueError(f"Missing or non-regular trusted helper: {name}")
            data = git("show", f"{base}:{path}")
            oid = hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()
            if oid != entry.split()[2]:
                raise ValueError(f"Trusted helper blob mismatch: {name}")
            (helpers / name).write_bytes(data)
        for name, _ in HELPERS:
            script = helpers / name
            digest = hashlib.sha256(script.read_bytes()).hexdigest()
            args = ["/usr/bin/python3", "-I", str(script), "--repo", str(repo), "--base", base]
            if name != "policy":
                args += ["--head", head]
            result = subprocess.run(args, cwd=root, env=env, capture_output=True)
            emit(helper=name, sha256=digest, exit=result.returncode,
                 stdout=result.stdout.decode("utf-8", errors="backslashreplace"),
                 stderr=result.stderr.decode("utf-8", errors="backslashreplace"))
            if result.returncode:
                raise ValueError(f"Trusted helper failed: {name}")
        if number != "0" and pr_pair(number, base, head) != target:
            raise ValueError("PR target changed during collection")
        emit(result="complete", base=base, head=head, workflow=workflow)


def main():
    if (os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
            or os.environ.get("GITHUB_REPOSITORY") != REPO
            or os.environ.get("GITHUB_REF") != "refs/heads/dev"
            or os.environ.get("GITHUB_SHA") != os.environ.get("GITHUB_WORKFLOW_SHA")):
        raise ValueError("Evidence must run from the owner repository's dev workflow")
    collect(os.environ["REVIEW_BASE"], os.environ["REVIEW_HEAD"],
            os.environ["REVIEW_PR"], os.environ["GITHUB_WORKFLOW_SHA"])


if __name__ == "__main__":
    try:
        main()
    except (ValueError, TypeError, KeyError, OSError, subprocess.CalledProcessError) as error:
        emit(result="failed", error=str(error))
        raise SystemExit(1)
