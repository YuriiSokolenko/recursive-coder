"""Negative discovery/execution fixtures using actual JVM bytecode and JUnit XML."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from check_effect_authority_conformance import validate, PACKAGE, REQUIRED_METHODS
from check_test_tiers import parse_class


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
        self.root = root
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
        self.compiled = compiled
        self.source = source
        subprocess.run(["javac", "-d", str(compiled), *map(str, files)],
                       check=True, capture_output=True, text=True)
        for name in sources:
            output = self.main if name in ("Adapter", "HostEffectAntiRollbackAuthority") else self.test
            destination = output / PACKAGE / (name + ".class")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(compiled / PACKAGE / (name + ".class"), destination)
        for _, tier, _ in self.reports:
            self.report(tier)

    def report(self, tier, omit=None, skip=None, failure=None, duplicate=None):
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
            if failure and method == failure[0]:
                ET.SubElement(case, failure[1])
            if method == duplicate:
                ET.SubElement(root, "testcase", dict(case.attrib))
        ET.ElementTree(root).write(directory / "TEST-Suite.xml")

    def problems(self, required_methods=frozenset({"read", "cas"})):
        return validate(self.outputs, self.reports,
                        required={PACKAGE + "Suite": PACKAGE + "Adapter"},
                        required_methods=required_methods)

    def assert_problems(self, expected, actual=None):
        self.assertEqual(sorted(expected), self.problems() if actual is None else actual)

    def unregistered(self):
        return f"Concrete production adapter without conformance suite: {PACKAGE}Adapter"

    def execution(self, tier, method):
        return (f"module:{tier}: {PACKAGE}Suite#{method} must execute and pass exactly once "
                "(no skips); ensure the suite is tagged integration")

    def recompile(self, name, body, output):
        path = self.source / (name + ".java")
        package = PACKAGE.replace("/", ".").rstrip(".")
        path.write_text(f"package {package};\n{body}", encoding="utf-8")
        subprocess.run(["javac", "-cp", str(self.compiled), "-d", str(output), str(path)],
                       check=True, capture_output=True, text=True)

    def test_all_real_adapter_methods_execute(self):
        self.assert_problems([])

    def test_missing_suite_rejected(self):
        (self.test / PACKAGE / "Suite.class").unlink()
        self.assert_problems([self.unregistered(),
                             f"Required effect authority suite not discovered: {PACKAGE}Suite"])

    def test_missing_production_adapter_rejected(self):
        (self.main / PACKAGE / "Adapter.class").unlink()
        self.assert_problems(["No concrete production effect authority hosts discovered",
                             f"{PACKAGE}Suite: invalid or nonlocal adapter "
                             + PACKAGE.replace("/", ".") + "Adapter"])

    def test_unexecuted_and_skipped_contract_methods_rejected(self):
        self.report("integrationTest", omit="cas")
        self.report("test", skip="read")
        self.assert_problems([self.execution("integrationTest", "cas"), self.execution("test", "read")])

    def test_failed_error_and_duplicate_testcase_rejected(self):
        for tag in ("failure", "error"):
            with self.subTest(tag=tag):
                self.report("integrationTest", failure=("cas", tag))
                self.report("test", duplicate="read")
                self.assert_problems([self.execution("integrationTest", "cas"),
                                      self.execution("test", "read")])

    def test_contract_cannot_delete_required_methods(self):
        expected = sorted(REQUIRED_METHODS - {"read", "cas"})
        self.assert_problems([f"Missing required shared effect authority tests: {expected}"],
                             self.problems(REQUIRED_METHODS))

    def test_nonfinal_and_shadowed_contract_method_rejected(self):
        self.recompile("EffectAuthorityAdapterContract", """
            @org.junit.jupiter.api.Tag("integration")
            public abstract class EffectAuthorityAdapterContract {
                @org.junit.jupiter.api.Test public void read() {}
                @org.junit.jupiter.api.Test public final void cas() {}
            }
        """, self.test)
        # Compile the subclass against the modified contract first.
        shutil.copyfile(self.test / PACKAGE / "EffectAuthorityAdapterContract.class",
                        self.compiled / PACKAGE / "EffectAuthorityAdapterContract.class")
        self.recompile("Suite", '@EffectAuthorityConformance(adapter="'
                       + PACKAGE.replace("/", ".") + 'Adapter") '
                       "public class Suite extends EffectAuthorityAdapterContract { "
                       "public void read() {} }", self.test)
        self.assert_problems(["Shared contract test must be final: read",
                             f"{PACKAGE}Suite: shared contract method shadowed by {PACKAGE}Suite"])

    def test_nonintegration_tier_rejected(self):
        self.recompile("EffectAuthorityAdapterContract", """
            @org.junit.jupiter.api.Tag("unit")
            public abstract class EffectAuthorityAdapterContract {
                @org.junit.jupiter.api.Test public final void read() {}
                @org.junit.jupiter.api.Test public final void cas() {}
            }
        """, self.test)
        self.assert_problems([f"{PACKAGE}Suite: conformance must be integration tier"])

    def test_missing_registration_rejected(self):
        self.recompile("Suite", "public class Suite extends EffectAuthorityAdapterContract {}", self.test)
        self.assert_problems([self.unregistered(), f"{PACKAGE}Suite: missing exact adapter registration"])

    def test_duplicate_registration_rejected(self):
        # javac forbids duplicate non-repeatable annotations; emulate malformed
        # compiled annotation metadata after the real class parser.
        def duplicate(path, module):
            info = parse_class(path, module)
            if info.name == PACKAGE + "Suite":
                info.annotations += info.annotations
            return info
        with patch("check_adapter_conformance.parse_class", side_effect=duplicate):
            self.assert_problems([self.unregistered(), f"{PACKAGE}Suite: missing exact adapter registration"])

    def test_non_adapter_registration_rejected(self):
        self.recompile("Suite", '@EffectAuthorityConformance(adapter="'
                       + PACKAGE.replace("/", ".") + 'EffectAuthorityAdapterContract") '
                       "public class Suite extends EffectAuthorityAdapterContract {}", self.test)
        self.assert_problems([self.unregistered(), f"{PACKAGE}Suite: invalid or nonlocal adapter "
                             + PACKAGE.replace("/", ".") + "EffectAuthorityAdapterContract"])

    def test_nonlocal_registration_rejected(self):
        outputs = [("foreign" if kind == "test" else module, kind, directory)
                   for module, kind, directory in self.outputs]
        actual = validate(outputs, self.reports, required={PACKAGE + "Suite": PACKAGE + "Adapter"},
                          required_methods={"read", "cas"})
        self.assert_problems([self.unregistered(), f"{PACKAGE}Suite: invalid or nonlocal adapter "
                             + PACKAGE.replace("/", ".") + "Adapter"], actual)

    def test_required_adapter_mismatch_rejected(self):
        actual = validate(self.outputs, self.reports, required={PACKAGE + "Suite": PACKAGE + "Other"},
                          required_methods={"read", "cas"})
        self.assert_problems([f"{PACKAGE}Suite: expected adapter {PACKAGE}Other, got {PACKAGE}Adapter"], actual)

    def test_missing_required_types_rejected(self):
        for name, output in (("HostEffectAntiRollbackAuthority", self.main),
                             ("EffectAuthorityAdapterContract", self.test),
                             ("EffectAuthorityConformance", self.test)):
            with self.subTest(name=name):
                file = output / PACKAGE / (name + ".class")
                data = file.read_bytes()
                file.unlink()
                try:
                    self.assert_problems([f"Missing compiled conformance type {PACKAGE}{name}"])
                finally:
                    file.write_bytes(data)

    def test_duplicate_class_rejected(self):
        path = self.test / PACKAGE / "Adapter.class"
        shutil.copyfile(self.main / PACKAGE / "Adapter.class", path)
        with self.assertRaises(ValueError) as error:
            self.problems()
        self.assertEqual(f"Duplicate compiled class {PACKAGE}Adapter: {path}", str(error.exception))
