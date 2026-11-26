#!/usr/bin/env python3
"""Measure registry operations of CI dependency resolution and retry transient failures.

Only resolution-only Gradle invocations may use this wrapper. Compilation and
tests run directly with --offline, so they need no --debug instrumentation and
cannot reach a registry at all.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import time

HTTP_OPERATION = re.compile(
    r"Performing HTTP (?:GET|HEAD): https://"
    r"(?:repo\.maven\.apache\.org|plugins\.gradle\.org|plugins-artifacts\.gradle\.org)/"
)
TRANSIENT = re.compile(r"(?:Received status code|HTTP status code) (?:429|5[0-9]{2})\b", re.I)
VERIFICATION_FAILURE = re.compile(r"dependency verification failed|checksum verification failed", re.I)
# Gradle --debug prefixes the first line of every log event with a timestamp
# and level. Later lines of a multi-line event (cause chains, stack frames,
# failure details) carry no prefix and inherit the visibility of that event.
EVENT = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{4} \[([A-Z]+)\] ")
VISIBLE_LEVELS = frozenset({"LIFECYCLE", "QUIET", "WARN", "ERROR"})
RESOLUTION_TASKS = frozenset({"help", ":resolveCiDependencies"})
RETRY_DELAYS = (5, 15)


def visible_lines(lines):
    """Yield (line, visible): debug and info events stay hidden with their continuations."""
    visible = True  # Output before the first event is launcher text.
    for line in lines:
        event = EVENT.match(line)
        if event:
            visible = event.group(1) in VISIBLE_LEVELS
        yield line, visible


def run_once(command: list[str]) -> tuple[int, int, bool, bool]:
    requests = 0
    transient = verification_failed = False
    # Gradle debug logs can contain secrets. Never publish or persist raw logs.
    with subprocess.Popen(
        [*command, "--debug", "--console=plain"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        errors="replace",
    ) as process:
        assert process.stdout is not None
        for line, visible in visible_lines(process.stdout):
            requests += bool(HTTP_OPERATION.search(line))
            if visible:
                transient |= bool(TRANSIENT.search(line))
                verification_failed |= bool(VERIFICATION_FAILURE.search(line))
                print(line, end="", flush=True)
        return process.wait(), requests, transient, verification_failed


def validate(command: list[str]) -> None:
    if not command or command[-1] not in RESOLUTION_TASKS:
        raise ValueError("Only resolution-only Gradle tasks may run under --debug measurement")
    if "--offline" in command:
        raise ValueError("Offline Gradle invocations run directly, not through the wrapper")
    if "--no-build-cache" not in command or not any(
        command[i:i + 2] == ["--dependency-verification", "strict"]
        for i in range(len(command))
    ):
        raise ValueError("CI Gradle requires strict verification and no build cache")


def run(command: list[str], label: str, *, retry_resolution: bool) -> int:
    validate(command)
    total = 0
    delays = RETRY_DELAYS if retry_resolution else ()
    for attempt, delay in enumerate((*delays, None), 1):
        code, requests, transient, verification_failed = run_once(command)
        total += requests
        if code == 0 or not transient or verification_failed or delay is None:
            break
        print(f"Registry resolution failed with HTTP 429/5xx; retrying in {delay}s",
              flush=True)
        time.sleep(delay)
    summary = (f"Gradle HTTP GET/HEAD operations ({label}): {total}; "
               f"attempts={attempt}; exit={code}")
    print(summary, flush=True)
    if summary_file := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary_file).open("a", encoding="utf-8") as stream:
            stream.write(summary + "\n\n")
    return code


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--retry-resolution", action="store_true")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        parser.error("missing Gradle command")
    raise SystemExit(run(command, args.label, retry_resolution=args.retry_resolution))
