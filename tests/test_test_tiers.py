"""Bytecode-level regression fixtures for checkTestTiers (no JUnit download needed)."""

import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from unittest import mock
import sys

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("check_test_tiers", ROOT / "tools/check_test_tiers.py")
guard = importlib.util.module_from_spec(spec)
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
    "org/junit/jupiter/api/BeforeEach.java": """
        package org.junit.jupiter.api;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public @interface BeforeEach {}
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


class TestTierGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("javac"):
            raise RuntimeError("javac is required to compile test-tier guard fixtures; install a JDK")

    def compile(self, source, extra_sources=None):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        entries = {**ANNOTATIONS, **(extra_sources or {}),
                   "example/Suite.java": "package example; " + source}
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

    def test_misspelled_tier_tag_is_rejected(self):
        _, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unti")
            public class Suite {
              @org.junit.jupiter.api.Test void mistyped() {}
            }
        ''')
        self.assertEqual(1, len(problems))
        self.assertIn("Suite#mistyped", problems[0])
        self.assertIn("missing JUnit tier", problems[0])

    def test_tags_container_is_recognized(self):
        count, problems = self.compile('''
            @org.junit.jupiter.api.Tags({@org.junit.jupiter.api.Tag("unit")})
            public class Suite {
              @org.junit.jupiter.api.Test void tagged() {}
            }
        ''')
        self.assertEqual(1, count)
        self.assertEqual([], problems)

    def test_tags_container_with_conflicting_tiers_is_rejected(self):
        _, problems = self.compile('''
            @org.junit.jupiter.api.Tags({@org.junit.jupiter.api.Tag("unit"),
                                          @org.junit.jupiter.api.Tag("integration")})
            public class Suite {
              @org.junit.jupiter.api.Test void conflicted() {}
            }
        ''')
        self.assertEqual(1, len(problems))
        self.assertIn("incompatible JUnit tiers", problems[0])

    def test_composed_annotation_with_name_ending_in_l_is_recognized(self):
        count, problems = self.compile('''
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
            @org.junit.jupiter.api.Test
            @org.junit.jupiter.api.Tag("unit")
            @interface FastUnitL {}
            public class Suite {
              @FastUnitL void composed() {}
            }
        ''')
        self.assertEqual(1, count)
        self.assertEqual([], problems)

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

    def test_git_http_socket_and_koog_references_are_rejected(self):
        samples = (
            ("git", "new org.eclipse.jgit.api.Git();", "org.eclipse.jgit.api.Git"),
            ("http", "java.net.http.HttpClient.newHttpClient();", "java.net.http.HttpClient"),
            ("socket", "new java.net.Socket();", "java.net.Socket"),
            ("koog", "new ai.koog.SampleModel();", "ai.koog.SampleModel"),
        )
        stubs = {
            "org/eclipse/jgit/api/Git.java":
                "package org.eclipse.jgit.api; public class Git {}",
            "ai/koog/SampleModel.java":
                "package ai.koog; public class SampleModel {}",
        }
        for name, statement, expected in samples:
            with self.subTest(api=name):
                _, problems = self.compile(f'''
                    @org.junit.jupiter.api.Tag("unit")
                    public class Suite {{
                      @org.junit.jupiter.api.Test void impure() {{ {statement} }}
                    }}
                ''', extra_sources=stubs)
                self.assertEqual(1, len(problems), problems)
                self.assertIn("impure unit fixture", problems[0])
                self.assertIn(expected, problems[0])

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

    def test_integration_class_with_e2e_method_is_supported(self):
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
        # CI independently verifies the Gradle include/exclude tag filters.

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

    def test_nested_pure_unit_rejects_outer_before_each_files(self):
        _, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            public class Suite {
              @org.junit.jupiter.api.BeforeEach void setup() throws Exception {
                java.nio.file.Files.readString(java.nio.file.Path.of("task.txt"));
              }
              @org.junit.jupiter.api.Nested class Child {
                @org.junit.jupiter.api.Nested class Grandchild {
                  @org.junit.jupiter.api.Test void nested() {}
                }
              }
            }
        ''')
        self.assertEqual(1, len(problems))
        self.assertIn("Suite$Child$Grandchild#nested", problems[0])
        self.assertIn("java.nio.file.Files", problems[0])

    def test_nested_pure_unit_rejects_outer_temp_dir_without_outer_tests(self):
        _, problems = self.compile('''
            public class Suite {
              @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
              @org.junit.jupiter.api.Nested
              @org.junit.jupiter.api.Tag("unit")
              class Child {
                @org.junit.jupiter.api.Test void nested() {}
              }
            }
        ''')
        self.assertEqual(1, len(problems))
        self.assertIn("Suite$Child#nested", problems[0])
        self.assertIn("TempDir", problems[0])

    def test_nested_pure_unit_with_pure_outer_fixture_is_allowed(self):
        count, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            public class Suite {
              int counter = 0;
              @org.junit.jupiter.api.BeforeEach void setup() { counter++; }
              @org.junit.jupiter.api.Nested class Child {
                @org.junit.jupiter.api.Test void nested() {}
              }
            }
        ''')
        self.assertEqual(1, count)
        self.assertEqual([], problems)

    def test_missing_javac_fails_instead_of_skipping(self):
        with mock.patch.object(shutil, "which", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "javac is required"):
                type(self).setUpClass()

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
