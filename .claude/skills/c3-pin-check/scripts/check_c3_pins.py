#!/usr/bin/env python3
"""Verify the trusted C3 source pins and report protected files changed by a PR.

Usage:
    check_c3_pins.py [--repo PATH] [--base REV] [--head REV]

Without --base/--head, only the working-tree pins are checked.
With both, the pins are checked at HEAD's content (read via git), and every
CODEOWNERS-protected path changed in base...head is listed.
Exit code 1 means a pin mismatch or a missing pinned file.
"""
import argparse
import fnmatch
import hashlib
import re
import subprocess
import sys
from pathlib import Path

GRAPH = "build-logic/src/main/kotlin/ai/yuriilab/recursivecoder/buildlogic/architecture/ArchitectureGraph.kt"
PIN_RE = re.compile(r'"([^"]+\.kt)"\s+to\s+"([0-9a-f]{64})"')


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], check=True,
                          capture_output=True).stdout


def read(repo, rev, path):
    if rev is None:
        p = Path(repo, path)
        return p.read_bytes() if p.is_file() else None
    try:
        return git(repo, "show", f"{rev}:{path}")
    except subprocess.CalledProcessError:
        return None


def codeowners_patterns(repo, rev):
    raw = read(repo, rev, ".github/CODEOWNERS")
    if raw is None:
        return []
    patterns = []
    for line in raw.decode().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            patterns.append(line.split()[0])
    return patterns


def matches(path, pattern):
    pat = pattern.lstrip("/")
    if pat.endswith("/"):
        return path.startswith(pat)
    if "**/" in pat:
        return fnmatch.fnmatch(path, pat.replace("**/", "*")) or fnmatch.fnmatch(path, pat.replace("**/", ""))
    if "/" not in pattern.strip("/") and not pattern.startswith("/"):
        return fnmatch.fnmatch(Path(path).name, pat)
    return fnmatch.fnmatch(path, pat)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=".")
    ap.add_argument("--base")
    ap.add_argument("--head")
    a = ap.parse_args()
    rev = a.head

    graph = read(a.repo, rev, GRAPH)
    if graph is None:
        print(f"FAIL: {GRAPH} not found")
        return 1
    pins = PIN_RE.findall(graph.decode())
    if len(pins) != 6:
        print(f"WARN: expected 6 C3 pins, found {len(pins)}; the pin inventory itself changed")
    failed = False
    for path, expected in pins:
        data = read(a.repo, rev, path)
        if data is None:
            print(f"FAIL missing  {path}")
            failed = True
            continue
        actual = hashlib.sha256(data).hexdigest()
        status = "ok  " if actual == expected else "FAIL"
        failed |= actual != expected
        print(f"{status} {path}" + ("" if actual == expected else f"\n     expected {expected}\n     actual   {actual}"))

    if a.base and a.head:
        changed = git(a.repo, "diff", "--name-only", f"{a.base}...{a.head}").decode().split()
        patterns = codeowners_patterns(a.repo, a.head)
        protected = sorted({p for p in changed for pat in patterns if matches(p, pat)})
        pinned_changed = sorted({p for p in changed for p_, _ in pins if p == p_})
        graph_changed = GRAPH in changed
        print(f"\nChanged files: {len(changed)}; CODEOWNERS-protected among them: {len(protected)}")
        for p in protected:
            print(f"  protected  {p}")
        if pinned_changed or graph_changed:
            print("\nC3 CHANGE: pinned sources or the pin inventory changed. "
                  "Require the TRUSTED_C3_REVIEW.md record and owner review.")
            for p in pinned_changed:
                print(f"  pinned     {p}")
            if graph_changed:
                print(f"  inventory  {GRAPH}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
