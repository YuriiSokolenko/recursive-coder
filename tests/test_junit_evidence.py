"""Regression fixtures for the JUnit evidence gate and scoped import check."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("check_junit_evidence", ROOT / "tools/check_junit_evidence.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


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
