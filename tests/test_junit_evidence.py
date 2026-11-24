"""Regression fixtures for the JUnit evidence gate and scoped import check."""

import importlib.util
from pathlib import Path
import tempfile
import struct
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("check_junit_evidence", ROOT / "tools/check_junit_evidence.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ExpectedTargetsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.classifier = Mock()
        self.classifier.test_source_modules.return_value = {"extra"}
        self.classifier.parse_class.side_effect = self.parse_class
        self.classifier.test_methods.side_effect = lambda info, classes: info.methods
        self.classifier.class_tiers.side_effect = lambda info, classes: info.tags
        self.classifier.tier_tags.side_effect = lambda annotations, classes: annotations
        self.infos = {}
        self.addCleanup(patch.stopall)
        patch.object(guard, "MODULES", ("unit", "mixed")).start()
        patch.object(guard, "load_tier_classifier", return_value=self.classifier).start()

    def parse_class(self, path, module):
        return self.infos[path]

    def fixture(self, module, name, tags, methods, language="kotlin", flags=0):
        path = self.root / module / "build/classes" / language / "test" / (name + ".class")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()
        self.infos[path] = SimpleNamespace(
            name=name, flags=flags, tags=set(tags),
            methods=[SimpleNamespace(name=f"case{i}", annotations=set(t))
                     for i, t in enumerate(methods)])

    def populate(self):
        self.fixture("unit", "Unit", {"unit"}, [set()])
        self.fixture("mixed", "Mixed", {"integration"}, [set(), {"e2e"}])
        self.fixture("mixed", "Abstract", set(), [set()], flags=0x0400)
        self.fixture("extra", "E2e", {"e2e"}, [set()], language="java")

    def test_expected_targets_by_module_and_effective_tags(self):
        self.populate()
        self.assertEqual(guard.expected_targets(self.root), {
            "unit": {"test"}, "mixed": {"test", "integrationTest", "e2eTest"},
            "extra": {"test", "e2eTest"},
        })
        self.assertEqual(self.classifier.parse_class.call_count, 4)

    def test_missing_compiled_module_fails_closed(self):
        self.populate()
        next((self.root / "unit").rglob("*.class")).unlink()
        with self.assertRaisesRegex(ValueError, "unit: no compiled runnable"):
            guard.expected_targets(self.root)

    def test_invalid_effective_tags_rejected(self):
        self.populate()
        self.fixture("mixed", "Invalid", {"unit"}, [{"e2e"}])
        with self.assertRaisesRegex(ValueError, "invalid test tiers"):
            guard.expected_targets(self.root)

    def test_duplicate_compiled_class_rejected(self):
        self.populate()
        self.fixture("unit", "Unit", {"unit"}, [set()], language="java")
        with self.assertRaisesRegex(ValueError, "duplicate compiled class"):
            guard.expected_targets(self.root)

    def test_main_reports_classifier_parse_errors(self):
        for error in (IndexError("truncated class"), struct.error("bad class")):
            with self.subTest(error=error), patch.object(
                    guard, "expected_targets", side_effect=error), patch(
                    "sys.argv", ["check_junit_evidence.py"]), patch("sys.stderr"):
                self.assertEqual(guard.main(), 1)


class JunitEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.expected = {"a": {"test", "integrationTest"}, "b": {"test", "e2eTest"}}
        for module, targets in self.expected.items():
            for target in targets:
                self.report(module, target)

    def report(self, module, target, body="", name="sample"):
        directory = self.root / module / "build/test-results" / target
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / "TEST-example.xml"
        path.write_text(f'<testsuite><testcase classname="Example" name="{name}">{body}</testcase></testsuite>',
                        encoding="utf-8")
        return path

    def test_pass_and_empty_nonapplicable_tier(self):
        guard.verify(self.root, self.expected)

    def test_missing_module_report(self):
        self.report("b", "test").unlink()
        with self.assertRaisesRegex(ValueError, "b:test: no passing"):
            guard.verify(self.root, self.expected)

    def test_missing_focused_report(self):
        self.report("a", "integrationTest").unlink()
        with self.assertRaisesRegex(ValueError, "a:integrationTest: no passing"):
            guard.verify(self.root, self.expected)

    def test_all_skipped(self):
        self.report("b", "e2eTest", "<skipped/>")
        with self.assertRaisesRegex(ValueError, "b:e2eTest: no passing"):
            guard.verify(self.root, self.expected)

    def test_failure_and_error(self):
        for tag in ("failure", "error"):
            with self.subTest(tag=tag):
                self.report("a", "test", f"<{tag}/>")
                with self.assertRaisesRegex(ValueError, "failed/error"):
                    guard.verify(self.root, self.expected)

    def test_focused_case_missing_from_unfiltered(self):
        self.report("a", "integrationTest", name="other")
        with self.assertRaisesRegex(ValueError, "absent from unfiltered"):
            guard.verify(self.root, self.expected)

    def test_empty_inventory(self):
        with self.assertRaisesRegex(ValueError, "all three JVM targets"):
            guard.verify(self.root, {})

    def test_import_order_and_contiguity(self):
        path = self.root / "a/src/test/kotlin/ai/yuriilab/recursivecoder/Example.kt"
        path.parent.mkdir(parents=True)
        with patch.object(guard, "IMPORT_FILES", ("a/Example.kt",)):
            path.write_text("package example\n\nimport a.A\nimport z.Z\n")
            guard.verify_imports(self.root)
            for imports in ("import z.Z\nimport a.A", "import a.A\n\nimport z.Z",
                            "import a.A\nimport a.A"):
                with self.subTest(imports=imports):
                    path.write_text("package example\n\n" + imports)
                    with self.assertRaisesRegex(ValueError, "imports must be"):
                        guard.verify_imports(self.root)


if __name__ == "__main__":
    unittest.main()
