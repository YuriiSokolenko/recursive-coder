#!/usr/bin/env python3
"""Shared compiled-adapter and executed JUnit conformance verifier."""
import argparse
from collections import defaultdict
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

from check_test_tiers import parse_class, ancestors, class_tiers

TEST = "org/junit/jupiter/api/Test"
TIERS = ("test", "integrationTest")


def concrete(info):
    return not info.flags & 0x0600


def validate(class_dirs, report_dirs, *, host, contract, registration,
             required_suites, label, required_methods=frozenset(),
             strict=False, profile_validator=None):
    problems = []
    classes = {}
    origins = {}
    for module, source_set, directory in class_dirs:
        for path in sorted(directory.rglob("*.class")):
            info = parse_class(path, module)
            if info.name in classes:
                raise ValueError(f"Duplicate compiled class {info.name}: {path}")
            classes[info.name] = info
            origins[info.name] = (module, source_set)

    def extends(info, target):
        return any(parent.name == target for parent in ancestors(info, classes))

    for name in (host, contract, registration):
        if name not in classes:
            problems.append(f"Missing compiled conformance type {name}")
    if problems:
        return problems

    methods = {method.name for method in classes[contract].methods
               if any(a.name == TEST for a in method.annotations)}
    if not methods:
        problems.append(f"No compiled shared {label} contract tests discovered")
    if required_methods - methods:
        problems.append(f"Missing required shared {label} tests: {sorted(required_methods - methods)}")
    if strict:
        for method in classes[contract].methods:
            if method.name in methods and not method.flags & 0x0010:
                problems.append(f"Shared contract test must be final: {method.name}")
    adapters = {name for name, info in classes.items()
                if origins[name][1] == "main" and concrete(info) and extends(info, host)}
    if not adapters:
        problems.append(f"No concrete production {label} hosts discovered")
    suites = {name for name, info in classes.items()
              if origins[name][1] == "test" and concrete(info) and extends(info, contract)}
    for name in set(required_suites) - suites:
        problems.append(f"Required {label} suite not discovered: {name}")

    registered = defaultdict(list)
    cases = defaultdict(list)
    for module, tier, directory in report_dirs:
        for path in sorted(directory.glob("TEST-*.xml")):
            for case in ET.parse(path).iter("testcase"):
                owner = case.get("classname", "").replace(".", "/")
                if owner in suites:
                    cases[module, tier, owner].append(case)

    for suite in sorted(suites):
        info = classes[suite]
        module = origins[suite][0]
        annotations = [a for a in info.annotations if a.name == registration]
        if len(annotations) != 1:
            problems.append(f"{suite}: missing exact adapter registration")
            continue
        binary_name = annotations[0].values.get("adapter")
        adapter = binary_name.replace(".", "/") if isinstance(binary_name, str) else ""
        if (adapter not in classes or not concrete(classes[adapter])
                or not extends(classes[adapter], host)
                or origins[adapter][0] != module
                or (strict and adapter not in adapters)):
            problems.append(f"{suite}: invalid or nonlocal adapter {binary_name}")
            continue
        if suite in required_suites and required_suites[suite] != adapter:
            problems.append(f"{suite}: expected adapter {required_suites[suite]}, got {adapter}")
        if profile_validator:
            problems.extend(profile_validator(suite, adapter, annotations[0].values))
        if strict and class_tiers(info, classes) != {"integration"}:
            problems.append(f"{suite}: conformance must be integration tier")
        registered[adapter].append(suite)
        # Do not permit an intermediate class to shadow a shared test method.
        for ancestor in ancestors(info, classes):
            if ancestor.name != contract and any(m.name in methods for m in ancestor.methods):
                problems.append(f"{suite}: shared contract method shadowed by {ancestor.name}")
        for tier in TIERS:
            for method in sorted(methods):
                matched = [case for case in cases[module, tier, suite]
                           if case.get("name") in (method, method + "()")]
                if len(matched) != 1 or any(
                        case.find(tag) is not None for case in matched
                        for tag in ("skipped", "failure", "error")):
                    problems.append(f"{module}:{tier}: {suite}#{method} "
                                    "must execute and pass exactly once (no skips); "
                                    "ensure the suite is tagged integration")

    for adapter in sorted(adapters):
        if not registered[adapter]:
            problems.append(f"Concrete production adapter without conformance suite: {adapter}")
    return sorted(set(problems))


def main(validator, label):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", action="append", required=True)
    parser.add_argument("--reports", action="append", required=True)
    args = parser.parse_args()

    def specs(values):
        for value in values:
            module, kind, path = value.split("|", 2)
            yield module, kind, Path(path)

    try:
        problems = validator(list(specs(args.classes)), list(specs(args.reports)))
    except (OSError, ValueError, IndexError, ET.ParseError) as error:
        problems = [f"Cannot inspect {label} conformance: {error}"]
    if problems:
        print(f"{label} conformance failed:\n" + "\n".join(" - " + p for p in problems),
              file=sys.stderr)
        return 1
    print(f"{label} conformance: all concrete production adapters and shared tests passed.")
    return 0


