#!/usr/bin/env python3
"""Verify that a review checkout's auto-loaded instructions equal the trusted base.

Usage:
    check_policy_checkout.py --base REV [--repo PATH]

For the supported default discovery names, Claude Code and Codex load
instructions from files on disk, at any depth of the working tree:
CLAUDE.md, CLAUDE.local.md, .claude/ and .mcp.json for Claude Code;
AGENTS.md, AGENTS.override.md, .codex/ and .agents/ for Codex.
This check walks the whole working tree (except the top-level .git) for
every such name, matched case-insensitively because some file systems are,
plus the root CONTRIBUTING.md. It never asks Git's index or status whether a
file changed, because index flags such as assume-unchanged or skip-worktree
can hide a modified file. It hashes the raw bytes of every matching file as
a Git blob, in the repository's object format, and compares them with the
matching entries of the whole base tree. It lists:

  differs     a matching path whose bytes differ from the base blob, whose
              type differs, or whose base or disk object is not a regular
              file;
  missing     a file the base has but the disk does not;
  extra       a file on disk that the base does not have (tracked, untracked
              or ignored: any of them could be auto-loaded), including
              sockets, FIFOs and devices;
  unreadable  a file or directory the check cannot read;
  symlink     any working-tree symbolic link not present in the matching
              base instruction inventory, including new instruction links.

Reject all symbolic links in the working tree, including unchanged policy
links present at the base and non-policy directory aliases. Never follow a
link or trust its target string as instruction content. Bytes are compared
without Git's clean filters or end-of-line conversion, so a checkout that
converts line endings fails the check.

This filesystem check covers only the listed default names inside the
repository, not provider configuration or instructions already loaded into a
session. Claude Code can also load CLAUDE.md from ancestor directories above
the repository root; those files, custom fallback names, additional imports,
external instructions and other discovery settings require separate
verification under the review policy.

Exit codes:
    0  every matching regular file on disk equals the base blob, no extra
       matching path exists, and no working-tree symlink was found.
    1  at least one path is listed: do not follow the loaded instructions.
       Start a fresh session from a clean checkout detached at the base.
    2  the base revision or the repository cannot be read.
"""
import argparse
import hashlib
import os
import stat
import subprocess
import sys

# File names a provider auto-loads, at any depth (compared case-insensitively).
INSTRUCTION_FILES = {"claude.md", "claude.local.md", "agents.md",
                     "agents.override.md", ".mcp.json"}
# Directories whose whole content a provider may load, at any depth.
INSTRUCTION_DIRS = {".claude", ".codex", ".agents"}
# Root-only files the review policy depends on.
ROOT_FILES = {"contributing.md"}
REGULAR_FILE_MODES = {"100644", "100755"}


def is_instruction_path(path):
    parts = path.casefold().split("/")
    return (parts[-1] in INSTRUCTION_FILES
            or any(part in INSTRUCTION_DIRS for part in parts[:-1])
            or parts[-1] in INSTRUCTION_DIRS
            or (len(parts) == 1 and parts[0] in ROOT_FILES))


def git(repo, *args):
    return subprocess.run(["git", "-C", repo, *args], check=True,
                          capture_output=True).stdout


def blob_id(algorithm, data):
    return hashlib.new(algorithm, b"blob %d\0" % len(data) + data).hexdigest()


def base_entries(repo, base):
    """Map path -> (mode, object type, object id) for matching base entries."""
    raw = git(repo, "ls-tree", "-r", "-z", "--full-tree", base)
    entries = {}
    for record in raw.split(b"\0"):
        if not record:
            continue
        meta, raw_path = record.split(b"\t", 1)
        path = os.fsdecode(raw_path)
        if is_instruction_path(path):
            mode, kind, oid = meta.decode().split()
            entries[path] = (mode, kind, oid)
    return entries


def disk_entries(root):
    """Map path -> (kind, bytes); kind is file, symlink, special or unreadable."""
    found = {}

    def visit(rel, inside_instruction_dir):
        full = os.path.join(root, rel) if rel else root
        try:
            info = os.lstat(full)
        except OSError:
            found[rel] = ("unreadable", None)
            return
        name = rel.rsplit("/", 1)[-1].casefold()
        matches = inside_instruction_dir or is_instruction_path(rel)
        if stat.S_ISDIR(info.st_mode):
            try:
                names = sorted(os.listdir(full))
            except OSError:
                found[rel or "."] = ("unreadable", None)
                return
            nested = inside_instruction_dir or name in INSTRUCTION_DIRS
            for child in names:
                if not rel and child == ".git":
                    continue
                visit(f"{rel}/{child}" if rel else child, nested)
        elif stat.S_ISLNK(info.st_mode):
            # Even an unchanged base link can resolve to different bytes.
            # Reject links anywhere, including non-policy directory aliases.
            found[rel] = ("symlink", None)
        elif not matches:
            return
        elif stat.S_ISREG(info.st_mode):
            try:
                with open(full, "rb") as handle:
                    found[rel] = ("file", handle.read())
            except OSError:
                found[rel] = ("unreadable", None)
        else:
            found[rel] = ("special", None)  # socket, FIFO or device

    visit("", False)
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=".")
    ap.add_argument("--base", required=True)
    a = ap.parse_args()
    try:
        base = git(a.repo, "rev-parse", "--verify", "--end-of-options",
                   f"{a.base}^{{commit}}").decode().strip()
        head = git(a.repo, "rev-parse", "--verify", "HEAD").decode().strip()
        root = git(a.repo, "rev-parse", "--show-toplevel").decode().strip()
        algorithm = git(a.repo, "rev-parse", "--show-object-format").decode().strip()
        expected = base_entries(a.repo, base)
    except (subprocess.CalledProcessError, ValueError) as error:
        detail = getattr(error, "stderr", b"") or b""
        print(f"ERROR: {detail.decode().strip() or error}")
        return 2

    actual = disk_entries(root)
    rows = []
    for path, (mode, kind, oid) in expected.items():
        if path not in actual:
            rows.append(("missing", path))
            continue
        disk_kind, data = actual[path]
        if disk_kind == "unreadable":
            continue  # reported below
        if (kind != "blob" or mode not in REGULAR_FILE_MODES
                or disk_kind != "file" or blob_id(algorithm, data) != oid):
            rows.append(("differs", path))
    for path, (disk_kind, _) in actual.items():
        if disk_kind == "unreadable":
            rows.append(("unreadable", path))
        elif disk_kind == "symlink" and path not in expected:
            rows.append(("symlink", path))
        elif path not in expected:
            rows.append(("extra", path))

    print(f"Base: {base}\nCheckout HEAD: {head}\nObject format: {algorithm}")
    instruction_count = sum(is_instruction_path(path) for path in actual)
    print(f"Compared {instruction_count} instruction paths on disk with "
          f"{len(expected)} base entries")
    if not rows:
        print("OK: auto-loaded instruction paths match the base")
        return 0
    for kind, path in sorted(rows, key=lambda row: (row[1], row[0])):
        print(f"{kind:<10} {path}")
    print("FAIL: loaded instructions are not the base's; do not follow them")
    return 1


if __name__ == "__main__":
    sys.exit(main())
