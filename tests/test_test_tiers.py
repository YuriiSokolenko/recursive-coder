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

    def compile(self, source, extra_sources=None, additional_module=None,
                include_additional_module=False):
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
        modules = {"fixture": [classes]}
        if additional_module is not None:
            # Compile a real Java test in a new JVM module, but optionally omit its
            # outputs from Gradle's --class-dir arguments to reproduce a blind spot.
            new_source = root / "new-jvm-module/src/test/java/example/Fresh.java"
            new_source.parent.mkdir(parents=True, exist_ok=True)
            new_source.write_text("package example; " + textwrap.dedent(additional_module),
                                  encoding="utf-8")
            new_classes = root / "new-jvm-module/build/classes/java/test"
            new_classes.mkdir(parents=True)
            subprocess.run(["javac", "-encoding", "UTF-8", "-cp", str(classes),
                            "-d", str(new_classes), str(new_source)],
                           check=True, capture_output=True, text=True)
            if include_additional_module:
                modules["new-jvm-module"] = [new_classes]
        return guard.validate(modules, root)

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

    def test_nio_channels_socket_address_and_tls_apis_are_rejected(self):
        samples = (
            ("file-channel", "java.nio.channels.FileChannel.class.getName()",
             "java.nio.channels.FileChannel"),
            ("socket-channel", "java.nio.channels.SocketChannel.class.getName()",
             "java.nio.channels.SocketChannel"),
            ("async-channel", "java.nio.channels.AsynchronousFileChannel.class.getName()",
             "java.nio.channels.AsynchronousFileChannel"),
            ("socket-address", "java.net.SocketAddress.class.getName()",
             "java.net.SocketAddress"),
            ("tls", "javax.net.SocketFactory.getDefault()", "javax.net.SocketFactory"),
            ("datagram-packet", "java.net.DatagramPacket.class.getName()",
             "java.net.DatagramPacket"),
            ("network-interface", "java.net.NetworkInterface.class.getName()",
             "java.net.NetworkInterface"),
            ("proxy", "java.net.Proxy.class.getName()", "java.net.Proxy"),
        )
        for label, expression, expected in samples:
            with self.subTest(api=label):
                count, problems = self.compile(f'''
                    @org.junit.jupiter.api.Tag("unit")
                    public class Suite {{
                      @org.junit.jupiter.api.Test void pure() {{
                        Object reference = {expression};
                        if (reference == null) throw new AssertionError();
                      }}
                    }}
                ''')
                self.assertEqual(1, count)
                self.assertEqual(1, len(problems), problems)
                self.assertIn(expected, problems[0])

    def test_in_memory_kotlin_closeable_use_and_jdk_values_pass(self):
        count, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            public class Suite {
              @org.junit.jupiter.api.Test void inMemoryUse() throws Exception {
                kotlin.io.CloseableKt.use(new java.io.ByteArrayInputStream(new byte[0]));
                java.net.URI uri = java.net.URI.create("https://example.test/no-request");
                java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(new byte[1]);
                if (uri == null || bytes.remaining() != 1) throw new AssertionError();
              }
            }
        ''', extra_sources={
            "kotlin/io/CloseableKt.java": """
                package kotlin.io;
                public final class CloseableKt {
                  public static void use(java.io.Closeable value) throws Exception {
                    try (value) {}
                  }
                }
            """,
        })
        self.assertEqual(1, count)
        self.assertEqual([], problems)

    def test_kotlin_text_streams_and_descriptor_only_url_are_rejected(self):
        _, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            public class Suite {
              @org.junit.jupiter.api.Test void net() throws Exception {
                kotlin.io.TextStreamsKt.readBytes(java.net.URI.create("https://example.test").toURL());
              }
            }
        ''', extra_sources={"kotlin/io/TextStreamsKt.java": """
            package kotlin.io;
            public class TextStreamsKt {
                public static byte[] readBytes(java.net.URL url) { return new byte[0]; }
            }
        """})
        self.assertEqual(1, len(problems), problems)
        self.assertIn("kotlin.io.TextStreamsKt", problems[0])
        self.assertIn("java.net.URL", problems[0])

    def test_descriptor_only_method_field_and_array_types_are_rejected(self):
        for body, expected in (
            ('Helper.files();', 'java.nio.file.Files'),
            ('Object value = Helper.file;', 'java.io.File'),
            ('Helper.urls();', 'java.net.URL'),
        ):
            with self.subTest(body=body):
                _, problems = self.compile(f'''
                    @org.junit.jupiter.api.Tag("unit")
                    public class Suite {{
                      @org.junit.jupiter.api.Test void impure() {{ {body} }}
                    }}
                ''', extra_sources={"example/Helper.java": """
                    package example;
                    public class Helper {
                        public static java.io.File file;
                        public static java.nio.file.Files files() { return null; }
                        public static java.net.URL[] urls() { return null; }
                    }
                """})
                self.assertEqual(1, len(problems), problems)
                self.assertIn(expected, problems[0])

    def test_path_in_descriptor_alone_is_allowed_but_direct_access_is_not(self):
        for body, forbidden in (
            ("Helper.path();", False),
            ("java.nio.file.Path.of(\"task.txt\");", True),
            ("java.nio.file.Files.exists(Helper.path());", True),
        ):
            with self.subTest(body=body):
                count, problems = self.compile(f'''
                    @org.junit.jupiter.api.Tag("unit")
                    public class Suite {{
                      @org.junit.jupiter.api.Test void test() {{ {body} }}
                    }}
                ''', extra_sources={"example/Helper.java": """
                    package example;
                    public class Helper {
                        public static java.nio.file.Path path() { return null; }
                    }
                """})
                self.assertEqual(1, count)
                self.assertEqual(forbidden, bool(problems), problems)

    def test_descriptor_references_do_not_change_shared_class_references(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Suite.java"
            source.write_text("class Suite { java.net.URL field; }", encoding="utf-8")
            subprocess.run(["javac", "-d", str(root), str(source)],
                           check=True, capture_output=True, text=True)
            info = guard.parse_class(root / "Suite.class", "fixture")
            self.assertIn("java/net/URL", info.descriptor_references)
            self.assertNotIn("java/net/URL", info.references)

    def test_root_test_source_module_has_normalized_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "src/test/java/Suite.java"
            source.parent.mkdir(parents=True)
            source.write_text("class Suite {}", encoding="utf-8")
            self.assertEqual({""}, guard.test_source_modules(root))
            _, problems = guard.validate({}, root)
            self.assertTrue(any(p.startswith(": (root): test sources excluded") for p in problems))
            _, problems = guard.validate({"": []}, root)
            self.assertTrue(any(p.startswith(": (root): no compiled test classes") for p in problems))
            self.assertEqual(":core:contracts", guard.module_label("core/contracts"))

    def test_kotlin_file_io_entry_point_is_still_rejected(self):
        _, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            public class Suite {
              @org.junit.jupiter.api.Test void impure() { kotlin.io.FilesKt.read(); }
            }
        ''', extra_sources={
            "kotlin/io/FilesKt.java": """
                package kotlin.io;
                public final class FilesKt { public static void read() {} }
            """,
        })
        self.assertEqual(1, len(problems), problems)
        self.assertIn("kotlin.io.FilesKt", problems[0])

    def test_compiled_lambda_companion_and_default_impls_helpers_are_scanned(self):
        cases = (
            ("lambda", '''
                @org.junit.jupiter.api.Tag("unit")
                public class Suite {
                  @org.junit.jupiter.api.Test void generated() {
                    Runnable closure = () -> javax.net.SocketFactory.getDefault();
                    if (closure == null) throw new AssertionError();
                  }
                }
            ''', {}, "javax.net.SocketFactory"),
            ("companion", '''
                @org.junit.jupiter.api.Tag("unit")
                public class Suite {
                  static final class Companion {
                    static void connect() { new java.net.Socket(); }
                  }
                  @org.junit.jupiter.api.Test void generated() {}
                }
            ''', {}, "java.net.Socket"),
            ("default-impls", '''
                @org.junit.jupiter.api.Tag("unit")
                public class Suite implements InterfaceContract {
                  @org.junit.jupiter.api.Test void generated() {}
                }
            ''', {
                "example/InterfaceContract.java":
                    "package example; public interface InterfaceContract {}",
                "example/InterfaceContract$DefaultImpls.java": """
                    package example;
                    public final class InterfaceContract$DefaultImpls {
                      public static void connect() { javax.net.SocketFactory.getDefault(); }
                    }
                """,
            }, "javax.net.SocketFactory"),
        )
        for label, suite, stubs, expected in cases:
            with self.subTest(helper=label):
                count, problems = self.compile(suite, extra_sources=stubs)
                self.assertEqual(1, count)
                self.assertEqual(1, len(problems), problems)
                self.assertIn(expected, problems[0])

    def test_compiled_pure_nested_helpers_are_allowed(self):
        count, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            public class Suite {
              static final class Companion { int value() { return 42; } }
              @org.junit.jupiter.api.Test void generated() {
                java.util.function.IntSupplier lambda = () -> new Companion().value();
                if (lambda.getAsInt() != 42) throw new AssertionError();
              }
            }
        ''')
        self.assertEqual(1, count)
        self.assertEqual([], problems)

    def test_new_jvm_module_omitted_from_class_dirs_fails_closed(self):
        count, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            public class Suite {
              @org.junit.jupiter.api.Test void existing() {}
            }
        ''', additional_module='''
            public class Fresh {
              @org.junit.jupiter.api.Test void untagged() {}
            }
        ''')
        self.assertEqual(1, count)
        self.assertEqual(1, len(problems), problems)
        self.assertIn(":new-jvm-module:", problems[0])
        self.assertIn("excluded from checkTestTiers discovery", problems[0])

    def test_new_jvm_module_included_still_rejects_untagged_test(self):
        count, problems = self.compile('''
            @org.junit.jupiter.api.Tag("unit")
            public class Suite {
              @org.junit.jupiter.api.Test void existing() {}
            }
        ''', additional_module='''
            public class Fresh {
              @org.junit.jupiter.api.Test void untagged() {}
            }
        ''', include_additional_module=True)
        self.assertEqual(2, count)
        self.assertEqual(1, len(problems), problems)
        self.assertIn("new-jvm-module", problems[0])
        self.assertIn("Fresh#untagged", problems[0])
        self.assertIn("missing JUnit tier", problems[0])

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
