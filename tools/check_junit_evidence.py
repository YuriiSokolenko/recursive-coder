"""Check module-level JUnit execution evidence after the full JVM CI build."""

import argparse
from collections import Counter
import importlib.util
from pathlib import Path
import struct
import sys
from xml.etree import ElementTree as ET


# Keep this baseline in sync with the product-module allowlist in checkArchitecture.
# Source discovery also catches new conventional test source sets.
MODULES = (
    "agent/runtime", "app/cli", "core/contracts", "core/session", "core/tasks",
    "infra/journal", "infra/model", "infra/task-store", "infra/workspace",
)
IMPORT_FILES = (
    "app/cli/SessionRuntimeTest.kt", "app/cli/TaskCliTest.kt",
    "app/cli/WorkspaceManagerTest.kt", "core/contracts/SessionProtocolTest.kt",
    "core/session/SessionControllerUnitTest.kt", "infra/model/ModelGatewayTest.kt",
    "infra/workspace/TrustedWriteRuntimeTest.kt",
    "infra/workspace/WorkspaceBoundaryUnitTest.kt",
)
TARGETS = ("test", "integrationTest", "e2eTest")


def load_tier_classifier():
    # Reuse the existing compiled annotation classifier rather than guessing
    # tiers from test filenames or source text. Isolated Python needs an explicit
    # sibling module import.
    spec = importlib.util.spec_from_file_location(
        "junit_evidence_tiers", Path(__file__).with_name("check_test_tiers.py"))
    guard = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = guard
    spec.loader.exec_module(guard)
    return guard


def expected_targets(root):
    guard = load_tier_classifier()
    result = {}
    modules = set(MODULES) | guard.test_source_modules(root)
    for module in sorted(modules):
        classes = {}
        for language in ("kotlin", "java"):
            directory = root / module / "build/classes" / language / "test"
            for path in sorted(directory.rglob("*.class")):
                info = guard.parse_class(path, module)
                if info.name in classes:
                    raise ValueError(f"{module}: duplicate compiled class {info.name}")
                classes[info.name] = info
        targets = set()
        for info in classes.values():
            if info.flags & 0x0600:
                continue
            for method in guard.test_methods(info, classes):
                tags = guard.class_tiers(info, classes) | guard.tier_tags(method.annotations, classes)
                if tags not in ({"unit"}, {"integration"}, {"e2e"}, {"integration", "e2e"}):
                    raise ValueError(f"{module}: invalid test tiers {tags} for {info.name}#{method.name}")
                targets.add("test")
                if "e2e" in tags:
                    targets.add("e2eTest")
                elif "integration" in tags:
                    targets.add("integrationTest")
        if not targets:
            raise ValueError(f"{module}: no compiled runnable JUnit methods")
        result[module] = targets
    return result


def read_cases(directory):
    reports = sorted(directory.glob("TEST-*.xml"))
    cases = Counter()
    passed = skipped = 0
    for report in reports:
        for case in ET.parse(report).iter("testcase"):
            identity = (case.get("classname"), case.get("name"))
            if not all(identity):
                raise ValueError(f"{report}: missing testcase identity")
            if case.find("failure") is not None or case.find("error") is not None:
                raise ValueError(f"{report}: failed/error testcase {identity}")
            cases[identity] += 1
            if case.find("skipped") is not None:
                skipped += 1
            else:
                passed += 1
    return cases, passed, skipped, len(reports)


def verify(root, expected):
    # Fail closed if classifier output loses a whole focused tier globally.
    if not expected or set().union(*expected.values()) != set(TARGETS):
        raise ValueError("Expected inventory must cover all three JVM targets")
    for module, targets in sorted(expected.items()):
        if "test" not in targets:
            raise ValueError(f"{module}: missing expected unfiltered target")
        unfiltered = None
        for target in TARGETS:
            directory = root / module / "build/test-results" / target
            cases, passed, skipped, reports = read_cases(directory)
            if target in targets and not passed:
                raise ValueError(f"{module}:{target}: no passing testcase ({reports} reports, {skipped} skipped)")
            if target == "test":
                unfiltered = cases
            elif cases - unfiltered:
                raise ValueError(f"{module}:{target}: cases absent from unfiltered test: {cases - unfiltered}")
            print(f"{module}:{target}: {passed} passed, {skipped} skipped, {reports} reports")


def verify_imports(root):
    for entry in IMPORT_FILES:
        module, filename = entry.rsplit("/", 1)
        path = root / module / "src/test/kotlin/ai/yuriilab/recursivecoder" / filename
        lines = path.read_text(encoding="utf-8").splitlines()
        positions = [i for i, line in enumerate(lines) if line.startswith("import ")]
        imports = [lines[i] for i in positions]
        if (not positions or positions != list(range(positions[0], positions[-1] + 1))
                or imports != sorted(set(imports))):
            raise ValueError(f"{path}: imports must be contiguous, unique and lexicographically sorted")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--imports-only", action="store_true")
    args = parser.parse_args()
    try:
        if args.imports_only:
            verify_imports(args.root)
        else:
            verify(args.root, expected_targets(args.root))
    except (OSError, ValueError, IndexError, struct.error, ET.ParseError) as error:
        print(f"JUnit evidence check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
