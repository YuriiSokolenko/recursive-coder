#!/usr/bin/env python3
"""Find diagnostic-code literals a vocabulary PR left behind.

Usage:
    scan_literals.py --base REV --head REV [--repo PATH] [--min 2]

Reads production Kotlin files (src/main) at HEAD and reports:
  1. Codes that already have an owner constant (`const val X = "X"`) but still
     appear as raw literals in production code.
  2. UPPER_SNAKE literals repeated >= --min times inside files touched by the PR.
  3. Literals from (2) that also appear in other production files, with the
     modules involved (candidates for a shared or module-internal owner).
Pinned C3 files are marked [C3] and must not be edited outside a C3 change.
Each hit is a finding: migrate it, or record it in the inventory with a reason.
"""
import argparse
import re
import subprocess
import sys
from collections import Counter, defaultdict

CODE_RE = re.compile(r'"([A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+)"')
CONST_RE = re.compile(r'const\s+val\s+([A-Z0-9_]+)\s*=\s*"([A-Z][A-Z0-9_]+)"')
PIN_RE = re.compile(r'"([^"]+\.kt)"\s+to\s+"[0-9a-f]{64}"')
GRAPH = "build-logic/src/main/kotlin/ai/yuriilab/recursivecoder/buildlogic/architecture/ArchitectureGraph.kt"


def git(repo, *args):
    return subprocess.run(["git", "-C", repo, *args], check=True,
                          capture_output=True, text=True).stdout


def module(path):
    parts = path.split("/src/")[0]
    return ":" + parts.replace("/", ":")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=".")
    ap.add_argument("--base", required=True)
    ap.add_argument("--head", required=True)
    ap.add_argument("--min", type=int, default=2)
    a = ap.parse_args()

    files = [f for f in git(a.repo, "ls-tree", "-r", "--name-only", a.head).split()
             if "/src/main/" in f and f.endswith(".kt")]
    touched = set(git(a.repo, "diff", "--name-only", f"{a.base}...{a.head}").split())
    pinned = set(PIN_RE.findall(git(a.repo, "show", f"{a.head}:{GRAPH}")))

    text = {f: git(a.repo, "show", f"{a.head}:{f}") for f in files}
    owners = {}
    for f, t in text.items():
        for name, value in CONST_RE.findall(t):
            owners.setdefault(value, []).append(f"{f.rsplit('/', 1)[-1]}:{name}")

    usages = defaultdict(Counter)
    for f, t in text.items():
        for line in t.splitlines():
            if "const val" in line:
                continue
            for code in CODE_RE.findall(line):
                usages[code][f] += 1

    def tag(f):
        return "[C3] " if f in pinned else ""

    print("== 1. Owned codes still used as raw literals")
    n1 = 0
    for code in sorted(usages):
        if code in owners:
            for f, c in sorted(usages[code].items()):
                n1 += 1
                print(f"  {code} x{c}  {tag(f)}{f}   owner: {', '.join(owners[code])}")
    if not n1:
        print("  none")

    print(f"\n== 2. Literals repeated >= {a.min} times in touched files")
    n2 = 0
    rows = []
    for code, per_file in usages.items():
        if code in owners:
            continue
        for f, c in per_file.items():
            if f in touched and c >= a.min:
                rows.append((c, code, f))
    for c, code, f in sorted(rows, reverse=True):
        n2 += 1
        print(f"  {code} x{c}  {tag(f)}{f}")
    if not n2:
        print("  none")

    print("\n== 3. Codes used in a touched file and in other production files")
    n3 = 0
    for code, per_file in sorted(usages.items()):
        if code in owners or len(per_file) < 2 or not (set(per_file) & touched):
            continue
        n3 += 1
        mods = sorted({module(f) for f in per_file})
        print(f"  {code}  files={len(per_file)} modules={','.join(mods)}")
        for f, c in sorted(per_file.items()):
            print(f"      x{c}  {tag(f)}{f}{'  (touched)' if f in touched else ''}")
    if not n3:
        print("  none")

    print(f"\nSummary: owned-but-raw={n1} repeated-in-touched={n2} cross-file={n3}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
