"""Negative discovery/execution regressions using actual javac bytecode and JUnit XML."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from check_preparation_host_conformance import validate, PACKAGE


class PreparationConformanceGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.main = self.root / "main"
        self.fixture = self.root / "fixture"
        self.test = self.root / "test-classes"
        self.outputs = [("module", kind, path) for kind, path in
                        (("main", self.main), ("testFixtures", self.fixture), ("test", self.test))]
        self.reports = [("module", tier, self.root / tier) for tier in ("test", "integrationTest")]
        package = PACKAGE.replace("/", ".").rstrip(".")
        sources = {
            "PreparationStageHost": "public interface PreparationStageHost {}",
            "Indirect": "public interface Indirect extends PreparationStageHost {}",
            "Base": "public abstract class Base implements Indirect {}",
            "Adapter": "public class Adapter extends Base {}",
            "PreparationHostConformance": """
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface PreparationHostConformance { String adapter(); }
            """,
            "PreparationStageHostContract": """
                public abstract class PreparationStageHostContract {
                    @org.junit.jupiter.api.Test public final void read() {}
                    @org.junit.jupiter.api.Test public final void transaction() {}
                }
            """,
            "Suite": f'@PreparationHostConformance(adapter="{package}.Adapter") '
                     "public class Suite extends PreparationStageHostContract {}",
        }
        source = self.root / "source"
        source.mkdir()
        files = []
        for name, body in sources.items():
            path = source / (name + ".java")
            path.write_text(f"package {package};\n{body}", encoding="utf-8")
            files.append(path)
        marker = source / "Test.java"
        marker.write_text("package org.junit.jupiter.api; "
                          "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) "
                          "public @interface Test {}", encoding="utf-8")
        compiled = self.root / "compiled"
        subprocess.run(["javac", "-d", str(compiled), *map(str, files), str(marker)],
                       check=True, capture_output=True, text=True)
        for name in sources:
            kind = self.test if name == "Suite" else (
                self.fixture if name in ("PreparationHostConformance", "PreparationStageHostContract")
                else self.main)
            dest = kind / PACKAGE / (name + ".class")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(compiled / PACKAGE / (name + ".class"), dest)
        for _, tier, _ in self.reports:
            self.write_report(tier)

    def write_report(self, tier, omitted=None, skipped=None, duplicate=False):
        directory = self.root / tier
        directory.mkdir(exist_ok=True)
        root = ET.Element("testsuite")
        for method in ("read", "transaction"):
            if method == omitted:
                continue
            case = ET.SubElement(root, "testcase", classname=PACKAGE.replace("/", ".") + "Suite",
                                 name=method + "()")
            if method == skipped:
                ET.SubElement(case, "skipped")
            if duplicate:
                ET.SubElement(root, "testcase", dict(case.attrib))
        ET.ElementTree(root).write(directory / "TEST-Suite.xml")

    def problems(self):
        return validate(self.outputs, self.reports, required_suites=set())

    def test_indirect_concrete_adapter_passes_with_all_methods_executed(self):
        self.assertEqual([], self.problems())

    def test_concrete_adapter_without_suite_is_rejected(self):
        (self.test / PACKAGE / "Suite.class").unlink()
        self.assertTrue(any("Concrete production adapter without conformance suite" in p
                            and "Adapter" in p for p in self.problems()))

    def test_unexecuted_contract_method_is_rejected_even_with_registered_suite(self):
        self.write_report("integrationTest", omitted="transaction")
        self.assertTrue(any("integrationTest" in p and "#transaction" in p
                            for p in self.problems()))

    def test_missing_report_is_rejected(self):
        (self.root / "test" / "TEST-Suite.xml").unlink()
        self.assertTrue(any(":test:" in p and "#read" in p for p in self.problems()))

    def test_skipped_or_duplicate_cases_are_rejected(self):
        self.write_report("test", skipped="read")
        self.write_report("integrationTest", duplicate=True)
        problems = self.problems()
        self.assertTrue(any(":test:" in p and "#read" in p for p in problems))
        self.assertTrue(any(":integrationTest:" in p and "#transaction" in p for p in problems))
