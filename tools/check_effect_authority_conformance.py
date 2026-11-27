#!/usr/bin/env python3
"""Require real effect-authority adapters and executed inherited conformance tests."""
import argparse
from collections import defaultdict
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

from check_test_tiers import ancestors, class_tiers, parse_class

PACKAGE = "ai/yuriilab/recursivecoder/"
PORT = PACKAGE + "HostEffectAntiRollbackAuthority"
CONTRACT = PACKAGE + "EffectAuthorityAdapterContract"
REGISTRATION = PACKAGE + "EffectAuthorityConformance"
REQUIRED = {
    PACKAGE + "SshEffectAuthorityConformanceIntegrationTest":
        PACKAGE + "SshEffectAntiRollbackAuthority",
}
REQUIRED_METHODS = {
    "initializationFreshReadAndExactCas",
    "immutableHistoryAndOperationCheckpointHeadUniqueness",
    "successorGenerationCannotReuseHistoricalAnchorOrPath",
    "concurrentIndependentProcessesYieldExactlyOneDurableTransition",
    "failureBeforeAppendAndLostResponseNeverBecomeAcknowledgements",
    "crashBetweenDurabilityBoundariesQuarantinesWithoutRepair",
    "wrongCredentialsForeignIdentityAndUnavailableFailClosed",
    "truncatedAuthenticatedPrefixIsNotLatestAndCannotBootstrap",
    "corruptBothHistoriesAndResetAreNotProvisioning",
    "timeoutAndInterruptionQuarantineInvocation",
    "replayedAuthenticatedResponseIsNotFresh",
}
TIERS = ("test", "integrationTest")
TEST = "org/junit/jupiter/api/Test"


def validate(class_dirs, report_dirs, required=REQUIRED, required_methods=REQUIRED_METHODS):
    classes, origins, problems = {}, {}, []
    for module, kind, directory in class_dirs:
        for path in sorted(directory.rglob("*.class")):
            info = parse_class(path, module)
            if info.name in classes:
                raise ValueError(f"Duplicate class: {info.name}")
            classes[info.name] = info
            origins[info.name] = (module, kind)

    for name in (PORT, CONTRACT, REGISTRATION):
        if name not in classes:
            problems.append(f"Missing authority conformance type: {name}")
    if problems:
        return problems

    def concrete(info):
        return not info.flags & 0x0600

    def extends(info, target):
        return any(parent.name == target for parent in ancestors(info, classes))

    methods = {method.name for method in classes[CONTRACT].methods
               if any(a.name == TEST for a in method.annotations)}
    if not methods or required_methods - methods:
        problems.append(f"Missing shared authority tests: {sorted(required_methods - methods)}")
    for method in classes[CONTRACT].methods:
        if method.name in methods and not method.flags & 0x0010:
            problems.append(f"Shared authority test must be final: {method.name}")

    adapters = {name for name, info in classes.items()
                if origins[name][1] == "main" and concrete(info) and extends(info, PORT)}
    if not adapters:
        problems.append("No concrete production effect authority adapters")
    suites = {name for name, info in classes.items()
              if origins[name][1] == "test" and concrete(info) and extends(info, CONTRACT)}
    for name in set(required) - suites:
        problems.append(f"Required authority suite missing: {name}")

    reports = defaultdict(list)
    for module, tier, directory in report_dirs:
        for path in sorted(directory.glob("TEST-*.xml")):
            for case in ET.parse(path).iter("testcase"):
                owner = case.get("classname", "").replace(".", "/")
                if owner in suites:
                    reports[module, tier, owner].append(case)

    registered = defaultdict(list)
    for suite in sorted(suites):
        info = classes[suite]
        module = origins[suite][0]
        annotations = [a for a in info.annotations if a.name == REGISTRATION]
        if len(annotations) != 1:
            problems.append(f"{suite}: missing exact adapter registration")
            continue
        value = annotations[0].values.get("adapter")
        adapter = value.replace(".", "/") if isinstance(value, str) else ""
        if adapter not in adapters or origins[adapter][0] != module:
            problems.append(f"{suite}: registration does not name a local production adapter")
            continue
        if suite in required and required[suite] != adapter:
            problems.append(f"{suite}: expected {required[suite]}, got {adapter}")
        registered[adapter].append(suite)
        if class_tiers(info, classes) != {"integration"}:
            problems.append(f"{suite}: authority conformance must be integration tier")
        for parent in ancestors(info, classes):
            if parent.name != CONTRACT and any(m.name in methods for m in parent.methods):
                problems.append(f"{suite}: shared test shadowed by {parent.name}")
        for tier in TIERS:
            for method in sorted(methods):
                cases = [case for case in reports[module, tier, suite]
                         if case.get("name") in (method, method + "()")]
                if len(cases) != 1 or any(
                        case.find(tag) is not None for case in cases
                        for tag in ("skipped", "failure", "error")):
                    problems.append(f"{module}:{tier}: {suite}#{method} must pass exactly once")

    for adapter in sorted(adapters):
        if not registered[adapter]:
            problems.append(f"Concrete authority adapter without conformance: {adapter}")
    return sorted(set(problems))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", action="append", required=True)
    parser.add_argument("--reports", action="append", required=True)
    args = parser.parse_args()

    def specs(values):
        for value in values:
            module, kind, path = value.split("|", 2)
            yield module, kind, Path(path)

    try:
        problems = validate(list(specs(args.classes)), list(specs(args.reports)))
    except (OSError, ValueError, IndexError, ET.ParseError) as error:
        problems = [f"Cannot inspect effect authority conformance: {error}"]
    if problems:
        print("Effect authority conformance failed:\n" + "\n".join(" - " + p for p in problems),
              file=sys.stderr)
        return 1
    print("Effect authority conformance: all production adapters and shared tests passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
