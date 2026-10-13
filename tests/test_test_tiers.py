"""Bytecode-level regression fixtures for checkTestTiers (no JUnit download needed)."""

import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("check_test_tiers", ROOT / "tools/check_test_tiers.py")
guard = importlib.util.module_from_spec(spec)
import sys
sys.modules[spec.name] = guard
spec.loader.exec_module(guard)

ANNOTATIONS = {
    "org/junit/jupiter/api/Test.java": """
        package org.junit.jupiter.api;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        public @interface Test {}
    """,
    "org/junit/jupiter/api/Tag.java": """
        package org.junit.jupiter.api;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
        @Repeatable(Tags.class)
        public @interface Tag { String value(); }
    """,
    "org/junit/jupiter/api/Tags.java": """
        package org.junit.jupiter.api;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
        public @interface Tags { Tag[] value(); }
    """,
    "org/junit/jupiter/api/Nested.java": """
        package org.junit.jupiter.api;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Nested {}
    """,
    "org/junit/jupiter/api/TestFactory.java": """
        package org.junit.jupiter.api;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        public @interface TestFactory {}
    """,
    "org/junit/jupiter/api/RepeatedTest.java": """
        package org.junit.jupiter.api;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        public @interface RepeatedTest { int value(); }
    """,
    "org/junit/jupiter/params/ParameterizedTest.java": """
        package org.junit.jupiter.params;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        public @interface ParameterizedTest {}
    """,
    "org/junit/jupiter/api/io/TempDir.java": """
        package org.junit.jupiter.api.io;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.FIELD, ElementType.PARAMETER})
        public @interface TempDir {}
    """,
}


@unittest.skipUnless(shutil.which("javac"), "JDK javac is required")
class TestTierGuardTest(unittest.TestCase):
    def compile(self, source):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        entries = {**ANNOTATIONS, "example/Suite.java": "package example; " + source}
        files = []
        for name, text in entries.items():
            path = root / "fixture" / "src/test/java" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(textwrap.dedent(text), encoding="utf-8")
            files.append(str(path))
        classes = root / "fixture" / "build" / "classes" / "java" / "test"
        classes.mkdir(parents=True)
        subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(classes), *files],
                       check=True, capture_output=True, text=True)
        # Compiled annotation stubs are in the same class directory, like custom annotations.
        return guard.validate({"fixture": [classes]}, root)

    def test_unclassified_test_is_rejected_with_location_and_method(self):
        _, problems = self.compile('public class Suite { @org.junit.jupiter.api.Test void untagged() {} }')
        self.assertEqual(1, len(problems))
        self.assertIn("Suite#untagged", problems[0])
        self.assertIn("missing JUnit tier", problems[0])
        self.assertIn("Suite.java", problems[0])

    def test_pure_unit_with_in_memory_fake_is_allowed(self):
        count, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            public class Suite {
              static class Fake { int read() { return 42; } }
              @org.junit.jupiter.api.Test void inMemory() {
                if (new Fake().read() != 42) throw new AssertionError();
              }
            }
        ''')
        self.assertEqual(1, count)
        self.assertEqual([], problems)

    def test_mistagged_io_and_subprocess_are_rejected(self):
        _, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            public class Suite {
              @org.junit.jupiter.api.Test void files() throws Exception {
                java.nio.file.Files.readString(java.nio.file.Path.of("x"));
              }
              @org.junit.jupiter.api.Test void process() {
                new ProcessBuilder("git", "status");
              }
            }
        ''')
        self.assertEqual(2, len(problems))
        self.assertTrue(all("impure unit fixture" in problem for problem in problems))
        self.assertIn("java.nio.file.Files", problems[0] + problems[1])
        self.assertIn("java.lang.ProcessBuilder", problems[0] + problems[1])

    def test_temp_dir_field_is_not_permitted_in_unit_suite(self):
        _, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            public class Suite {
              @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
              @org.junit.jupiter.api.Test void temporary() {}
            }
        ''')
        self.assertEqual(1, len(problems))
        self.assertIn("TempDir", problems[0])

    def test_ambiguous_unit_and_integration_is_rejected(self):
        _, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            public class Suite {
              @org.junit.jupiter.api.Tag("integration")
              @org.junit.jupiter.api.Test void ambiguous() {}
            }
        ''')
        self.assertEqual(1, len(problems))
        self.assertIn("incompatible JUnit tiers", problems[0])

    def test_e2e_overrides_integration_for_suite_selection(self):
        count, problems = self.compile('''
            @org.junit.jupiter.api.Tag("integration")
            public class Suite {
              @org.junit.jupiter.api.Test void local() {}
              @org.junit.jupiter.api.Tag("e2e")
              @org.junit.jupiter.api.Test void sandbox() {}
            }
        ''')
        self.assertEqual(2, count)
        self.assertEqual([], problems)
        self.assertTrue({"integration", "e2e"} & guard.TIERS)

    def test_inherited_class_tier_covers_superclass_test(self):
        count, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            abstract class Base {
              @org.junit.jupiter.api.Test void inherited() {}
            }
            public class Suite extends Base {}
        ''')
        self.assertEqual(1, count)
        self.assertEqual([], problems)

    def test_composed_test_and_tag_and_parameterized_test(self):
        count, problems = self.compile('''
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
            @org.junit.jupiter.api.Test
            @org.junit.jupiter.api.Tag("unit")
            @interface FastTest {}
            public class Suite {
              @FastTest void composed() {}
              @org.junit.jupiter.api.Tag("unit")
              @org.junit.jupiter.params.ParameterizedTest void withArguments(String value) {}
              @org.junit.jupiter.api.Tag("unit")
              @org.junit.jupiter.api.TestFactory Object factory() { return null; }
            }
        ''')
        self.assertEqual(3, count)
        self.assertEqual([], problems)

    def test_nested_test_inherits_outer_integration_tag(self):
        count, problems = self.compile('''
            @org.junit.jupiter.api.Tag("integration")
            public class Suite {
              @org.junit.jupiter.api.Nested
              class Child {
                @org.junit.jupiter.api.Test void nested() {}
              }
            }
        ''')
        self.assertEqual(1, count)
        self.assertEqual([], problems)


if __name__ == "__main__":
    unittest.main()
