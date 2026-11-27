"""Negative discovery/execution fixtures using actual JVM bytecode and JUnit XML."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from check_effect_authority_conformance import validate, PACKAGE, REQUIRED_METHODS


class EffectAuthorityConformanceGateTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.main = root / "main"
        self.test = root / "test"
        self.outputs = [("module", "main", self.main), ("module", "test", self.test)]
        self.reports = [("module", tier, root / tier) for tier in ("test", "integrationTest")]
        package = PACKAGE.replace("/", ".").rstrip(".")
        source = root / "source"
        source.mkdir()
        sources = {
            "HostEffectAntiRollbackAuthority": "public interface HostEffectAntiRollbackAuthority {}",
            "Adapter": "public class Adapter implements HostEffectAntiRollbackAuthority {}",
            "EffectAuthorityConformance": """
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface EffectAuthorityConformance { String adapter(); }
            """,
            "EffectAuthorityAdapterContract": """
                @org.junit.jupiter.api.Tag("integration")
                public abstract class EffectAuthorityAdapterContract {
                    @org.junit.jupiter.api.Test public final void read() {}
                    @org.junit.jupiter.api.Test public final void cas() {}
                }
            """,
            "Suite": f'@EffectAuthorityConformance(adapter="{package}.Adapter") '
                     "public class Suite extends EffectAuthorityAdapterContract {}",
        }
        files = []
        for name, body in sources.items():
            path = source / (name + ".java")
            path.write_text(f"package {package};\n{body}", encoding="utf-8")
            files.append(path)
        for annotation, body in (("Test", ""), ("Tag", "String value();")):
            path = source / (annotation + ".java")
            path.write_text("package org.junit.jupiter.api; "
                            "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) "
                            f"public @interface {annotation} {{ {body} }}", encoding="utf-8")
            files.append(path)
        compiled = root / "compiled"
        subprocess.run(["javac", "-d", str(compiled), *map(str, files)],
                       check=True, capture_output=True, text=True)
        for name in sources:
            output = self.main if name in ("Adapter", "HostEffectAntiRollbackAuthority") else self.test
            destination = output / PACKAGE / (name + ".class")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(compiled / PACKAGE / (name + ".class"), destination)
        for _, tier, _ in self.reports:
            self.report(tier)

    def report(self, tier, omit=None, skip=None):
        directory = next(path for _, name, path in self.reports if name == tier)
        directory.mkdir(exist_ok=True)
        root = ET.Element("testsuite")
        for method in ("read", "cas"):
            if method == omit:
                continue
            case = ET.SubElement(root, "testcase", classname=PACKAGE.replace("/", ".") + "Suite",
                                 name=method + "()")
            if method == skip:
                ET.SubElement(case, "skipped")
        ET.ElementTree(root).write(directory / "TEST-Suite.xml")

    def problems(self, required_methods=frozenset({"read", "cas"})):
        return validate(self.outputs, self.reports,
                        required={PACKAGE + "Suite": PACKAGE + "Adapter"},
                        required_methods=required_methods)

    def test_all_real_adapter_methods_execute(self):
        self.assertEqual([], self.problems())

    def test_missing_suite_rejected(self):
        (self.test / PACKAGE / "Suite.class").unlink()
        self.assertTrue(any("without conformance" in problem for problem in self.problems()))

    def test_missing_production_adapter_rejected(self):
        (self.main / PACKAGE / "Adapter.class").unlink()
        self.assertTrue(any("No concrete production" in problem for problem in self.problems()))

    def test_unexecuted_and_skipped_contract_methods_rejected(self):
        self.report("integrationTest", omit="cas")
        self.report("test", skip="read")
        problems = self.problems()
        self.assertTrue(any("integrationTest" in p and "#cas" in p for p in problems))
        self.assertTrue(any(":test:" in p and "#read" in p for p in problems))

    def test_contract_cannot_delete_required_methods(self):
        self.assertTrue(any("Missing shared authority tests" in p
                            for p in self.problems(REQUIRED_METHODS)))
