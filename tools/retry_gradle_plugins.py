#!/usr/bin/env python3
"""Retry only a CI Gradle plugin-resolution preflight, never product tasks."""
from __future__ import annotations

import argparse
import re
import subprocess
import time

DELAYS = (60, 180)
STATUS = re.compile(r"Received status code ([0-9]{3})\b", re.I)
REGISTRY_REQUEST = re.compile(
    r"Could not (?:GET|HEAD) "
    r"'https://(?:repo\.maven\.apache\.org|plugins\.gradle\.org|"
    r"plugins-artifacts\.gradle\.org)/[^'\r\n]+'\.",
    re.I,
)
REGISTRY_INLINE = re.compile(
    REGISTRY_REQUEST.pattern + r" Received status code (429|5[0-9]{2})\b.*",
    re.I,
)
CAUSE = re.compile(r"^([ ]*)> (.+)$")
RESOLUTION_PARENT = re.compile(
    r"(?:Could not resolve |Could not get resource |"
    r"Could not download |Error resolving plugin ).+"
)
VERIFICATION = re.compile(
    r"verification failed|verification failure|checksum|verification metadata|"
    r"signature|not trusted|untrusted artifact",
    re.I,
)


def command(standalone: bool) -> list[str]:
    args = [
        "./gradlew", "--dependency-verification", "strict", "--no-daemon",
        "--no-build-cache", "--max-workers=1",
        "-Dorg.gradle.jvmargs=-Xmx1g", "--console=plain",
    ]
    if standalone:
        args += ["-p", "build-logic"]
    return [*args, "help"]


def transient_failure(output: str) -> bool:
    # Accept only a single resolution failure tree whose every leaf is
    # an allowlisted registry response. A mixed or unfamiliar failure is
    # not a reason to rerun Gradle.
    if (output.count("FAILURE:") != 1
            or "FAILURE: Build failed with an exception." not in output
            or VERIFICATION.search(output)
            or output.count("* What went wrong:") != 1):
        return False
    section = output.split("* What went wrong:", 1)[1]
    section = re.split(r"(?m)^\* ", section, maxsplit=1)[0]
    nodes = []
    required_indent = None
    for line in section.splitlines():
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        match = CAUSE.fullmatch(line)
        if match:
            required_indent = None
            nodes.append((indent, match.group(2)))
        elif re.fullmatch(r"A problem occurred (?:configuring|evaluating) .+\.", line):
            if nodes:
                return False
        elif line.strip() == "Required by:" and nodes:
            required_indent = indent
        elif required_indent is not None and indent > required_indent:
            continue
        else:
            return False
    if not nodes:
        return False
    statuses = []
    for index, (indent, message) in enumerate(nodes):
        child = nodes[index + 1] if index + 1 < len(nodes) else None
        has_child = child is not None and child[0] > indent
        inline = REGISTRY_INLINE.fullmatch(message)
        if inline:
            if has_child:
                return False
            statuses.append(inline[1])
        elif REGISTRY_REQUEST.fullmatch(message):
            if not has_child:
                return False
            status = STATUS.match(child[1])
            if status is None or not re.fullmatch(r"429|5[0-9]{2}", status[1]):
                return False
        elif STATUS.match(message):
            if (has_child or index == 0 or nodes[index - 1][0] >= indent
                    or not REGISTRY_REQUEST.fullmatch(nodes[index - 1][1])):
                return False
            statuses.append(STATUS.match(message)[1])
        elif not RESOLUTION_PARENT.fullmatch(message) or not has_child:
            return False
    return bool(statuses) and statuses == STATUS.findall(output)


def run_preflight(standalone: bool) -> int:
    args = command(standalone)
    for attempt, delay in enumerate((*DELAYS, None), 1):
        result = subprocess.run(
            args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, errors="replace", check=False,
        )
        print(result.stdout, end="", flush=True)
        print(f"Gradle plugin preflight attempt {attempt}: exit={result.returncode}",
              flush=True)
        if result.returncode == 0:
            return 0
        if result.returncode < 0:
            return 1
        if delay is None or not transient_failure(result.stdout):
            return result.returncode
        print(f"Transient registry response; retrying in {delay}s", flush=True)
        time.sleep(delay)
    raise AssertionError("Unreachable retry state")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--standalone", action="store_true")
    raise SystemExit(run_preflight(parser.parse_args().standalone))
