"""Regression checks for architecture rules using temporary Gradle init scripts."""

from contextlib import redirect_stdout
import io
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parent.parent



def _stop_gradle_tree(process: subprocess.Popen[str], *, force: bool = False) -> None:
    """Stop Gradle and its descendants, including children holding stdout."""
    if os.name == "posix":
        try:
            os.killpg(process.pid, signal.SIGKILL if force else signal.SIGTERM)
        except ProcessLookupError:
            pass
    elif process.poll() is None:
        (process.kill if force else process.terminate)()


def run_logged_gradle(
    command: list[str],
    *,
    cwd: Path,
    scenario: str,
    phase: str,
    sequence: int,
    timeout: float,
) -> subprocess.CompletedProcess[str]:
    """Stream combined output now, retain an exact transcript for assertions."""
    label = f"[gradle #{sequence:02d}] {scenario} | {phase}"
    started = time.monotonic()
    print(f"{label} START timeout={timeout:g}s", flush=True)
    try:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            start_new_session=(os.name == "posix"),
        )
    except OSError as error:
        print(
            f"{label} END status=spawn-failed exit=n/a "
            f"elapsed={time.monotonic() - started:.1f}s error={error}",
            flush=True,
        )
        raise

    output_lines: list[str] = []

    def relay() -> None:
        assert process.stdout is not None
        for line in process.stdout:
            output_lines.append(line)
            print(line, end="", flush=True)

    reader = threading.Thread(target=relay, daemon=True)
    reader.start()
    timed_out = False
    try:
        deadline = started + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                timed_out = True
                break
            try:
                process.wait(timeout=min(15, remaining))
                break
            except subprocess.TimeoutExpired:
                if time.monotonic() >= deadline:
                    timed_out = True
                    break
                print(
                    f"{label} RUNNING elapsed={time.monotonic() - started:.1f}s",
                    flush=True,
                )
    finally:
        if timed_out:
            _stop_gradle_tree(process)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                _stop_gradle_tree(process, force=True)
                process.wait()
        reader.join(timeout=5)
        if reader.is_alive():
            # A descendant can outlive the parent and keep the pipe open.
            _stop_gradle_tree(process, force=True)
            reader.join(timeout=5)
        if process.poll() is None:
            _stop_gradle_tree(process, force=True)
        process.wait()
        assert process.stdout is not None
        process.stdout.close()

    transcript = "".join(output_lines)
    elapsed = time.monotonic() - started
    if timed_out:
        print(
            f"{label} END status=timeout exit={process.returncode} "
            f"elapsed={elapsed:.1f}s",
            flush=True,
        )
        raise AssertionError(
            f"{label} timed out after {timeout:g}s (exit={process.returncode}). "
            f"Captured output (last 8000 characters of {len(transcript)}):\n"
            f"{transcript[-8000:]}"
        )
    if "Reusing configuration cache." in transcript:
        cache = "reused"
    elif "Calculating task graph as configuration cache cannot be reused" in transcript:
        cache = "invalidated"
    elif "Configuration cache entry stored." in transcript:
        cache = "stored"
    else:
        cache = "not-reported"
    status = "passed" if process.returncode == 0 else "failed"
    print(
        f"{label} END status={status} exit={process.returncode} "
        f"elapsed={elapsed:.1f}s cache={cache}",
        flush=True,
    )
    return subprocess.CompletedProcess(command, process.returncode, transcript)


class ArchitectureBoundaryTest(unittest.TestCase):
    def run_with_edge(self, source: str, target: str, configuration: str) -> str:
        init_script = (
            "gradle.projectsEvaluated {\n"
            f"    if (gradle.rootProject.findProject('{source}') != null) {{\n"
            f"        def owner = gradle.rootProject.project('{source}')\n"
            "        owner.dependencies.add('PLACEHOLDER', "
            f"owner.dependencies.project(path: '{target}'))\n"
            "    }\n"
            "}\n"
        )
        init_script = init_script.replace("PLACEHOLDER", configuration)
        return self.run_with_init_script(
            init_script, scenario=f"{source}:{configuration} -> {target}"
        )

    def run_with_init_script(self, init_script: str, *, scenario: str = "") -> str:
        with tempfile.TemporaryDirectory() as directory:
            init_file = Path(directory) / "illegal-edge.gradle"
            init_file.write_text(init_script, encoding="utf-8")
            run = run_logged_gradle(
                [
                    "./gradlew",
                    "--no-daemon",
                    "--offline",
                    "--console=plain",
                    "--init-script",
                    str(init_file),
                    "checkArchitecture",
                ],
                cwd=ROOT,
                scenario=scenario or f"init-script {self._testMethodName}",
                phase="injected-architecture-check",
                sequence=1,
                timeout=180,
            )
        self.assertNotEqual(0, run.returncode, run.stdout)
        return run.stdout

    def test_forbidden_core_to_adapter_test_dependency(self):
        output = self.run_with_edge(":core:session", ":infra:model", "testImplementation")
        self.assertIn(
            "Forbidden project dependency :core:session:testImplementation -> :infra:model",
            output,
        )

    def test_test_only_edge_cannot_complete_a_cycle(self):
        output = self.run_with_edge(":core:contracts", ":core:session", "testImplementation")
        self.assertIn(
            "Project dependency cycle (including tests): "
            ":core:contracts -> :core:session -> :core:contracts",
            output,
        )

    def test_forbidden_production_dependency(self):
        output = self.run_with_edge(":core:tasks", ":infra:workspace", "implementation")
        self.assertIn(
            "Forbidden project dependency :core:tasks:implementation -> :infra:workspace",
            output,
        )

    def test_unclassified_new_module(self):
        output = self.run_with_init_script(
            "gradle.settingsEvaluated { settings ->\n"
            "    if (settings.rootProject.name == 'recursive-coder') {\n"
            "        settings.include(':unknown')\n"
            "        settings.project(':unknown').projectDir = new File(settings.rootDir, 'docs')\n"
            "    }\n"
            "}\n"
        )
        self.assertIn("Unclassified module :unknown", output)

    def test_forbidden_core_framework_in_test_configuration(self):
        output = self.run_with_init_script(
            "gradle.projectsEvaluated {\n"
            "    if (gradle.rootProject.findProject(':core:session') != null) {\n"
            "        def owner = gradle.rootProject.project(':core:session')\n"
            "        owner.dependencies.add('testImplementation', 'io.ktor:ktor-client:1.0')\n"
            "    }\n"
            "}\n"
        )
        self.assertIn(
            "Forbidden core framework dependency :core:session:testImplementation -> io.ktor:ktor-client",
            output,
        )


class ArchitectureConfigurationCacheRegressionTest(unittest.TestCase):
    """A previously valid cache entry must not conceal new forbidden edges."""

    @staticmethod
    def copy_fixture(destination: Path) -> None:
        # Discover product module build scripts instead of maintaining a fixed
        # list. The included build-logic build is copied separately below.
        # Exclude Gradle output directories and existing configuration caches.
        inputs = [ROOT / "settings.gradle.kts", ROOT / "gradle.properties"]
        inputs.extend(
            source
            for source in ROOT.rglob("build.gradle.kts")
            if not any(
                part in {"build-logic", ".gradle", "build"}
                for part in source.relative_to(ROOT).parts[:-1]
            )
        )
        for source in sorted(inputs):
            relative = source.relative_to(ROOT)
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        shutil.copytree(
            ROOT / "build-logic",
            destination / "build-logic",
            ignore=shutil.ignore_patterns(".gradle", "build", "__pycache__"),
        )

    def check_architecture(
        self, directory: Path, *, scenario: str, phase: str
    ) -> subprocess.CompletedProcess[str]:
        sequence = getattr(self, "_gradle_sequence", 0) + 1
        self._gradle_sequence = sequence
        return run_logged_gradle(
            [
                str(ROOT / "gradlew"),
                "--no-daemon",
                "--offline",
                "--console=plain",
                "--configuration-cache",
                "checkArchitecture",
            ],
            cwd=directory,
            scenario=scenario,
            phase=phase,
            sequence=sequence,
            timeout=240,
        )

    def assert_valid(self, result: subprocess.CompletedProcess[str]) -> None:
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertRegex(
            result.stdout, r"Architecture boundaries verified for [1-9]\d* modules\."
        )

    def assert_cache_hit(self, fixture: Path, *, scenario: str) -> None:
        warm = self.check_architecture(fixture, scenario=scenario, phase="cache-reuse")
        self.assert_valid(warm)
        self.assertIn("Reusing configuration cache.", warm.stdout)

    def assert_restored_cache_ready(self, fixture: Path, *, scenario: str) -> None:
        restored = self.check_architecture(fixture, scenario=scenario, phase="restored")
        self.assert_valid(restored)
        # The restored run itself may reuse an earlier valid cache entry.
        # Only perform an extra warm-up if Gradle had to reconfigure.
        if "Reusing configuration cache." not in restored.stdout:
            self.assert_cache_hit(fixture, scenario=scenario)

    def assert_cache_invalidated(
        self, fixture: Path, diagnostic: str, input_pattern: str, *, scenario: str
    ) -> None:
        rejected = self.check_architecture(fixture, scenario=scenario, phase="invalidated")
        self.assertNotEqual(0, rejected.returncode, rejected.stdout)
        self.assertIn(diagnostic, rejected.stdout)
        self.assertIn(
            "Calculating task graph as configuration cache cannot be reused",
            rejected.stdout,
        )
        self.assertNotIn("Reusing configuration cache.", rejected.stdout)
        # Name the changed input in the invalidation reason, rather than
        # matching an unrelated Gradle progress message.
        self.assertRegex(
            rejected.stdout.replace("\\", "/"),
            r"Calculating task graph as configuration cache cannot be reused because [^\n]*"
            + input_pattern,
        )

    def test_changed_build_script_invalidates_cache_for_production_and_test_edges(self):
        with tempfile.TemporaryDirectory(prefix="architecture-cache-") as temporary:
            fixture = Path(temporary)
            self.copy_fixture(fixture)

            cold = self.check_architecture(
                fixture, scenario="module build.gradle.kts baseline", phase="cold-warmup"
            )
            self.assert_valid(cold)
            self.assertIn("Configuration cache entry stored.", cold.stdout)

            warm = self.check_architecture(
                fixture, scenario="module build.gradle.kts baseline", phase="cache-reuse"
            )
            self.assert_valid(warm)
            self.assertIn("Reusing configuration cache.", warm.stdout)

            for module, configuration, target in (
                (":core:tasks", "implementation", ":infra:workspace"),
                (":core:session", "testImplementation", ":infra:model"),
            ):
                relative = Path(module.lstrip(":").replace(":", "/")) / "build.gradle.kts"
                build_file = fixture / relative
                original = build_file.read_text(encoding="utf-8")

                with self.subTest(module=module, configuration=configuration):
                    # Mutate a copied build input after a confirmed cache hit.
                    # The added dependency is declared, never resolved.
                    injection = (
                        f'\n\ndependencies {{ {configuration}(project("{target}")) }}\n'
                    )
                    build_file.write_text(original + injection, encoding="utf-8")
                    try:
                        rejected = self.check_architecture(
                            fixture,
                            scenario=f"{relative.as_posix()} ({configuration} -> {target})",
                            phase="invalidated",
                        )
                        self.assertNotEqual(0, rejected.returncode, rejected.stdout)
                        self.assertIn(
                            f"Forbidden project dependency {module}:{configuration} -> {target}",
                            rejected.stdout,
                        )
                        self.assertIn(
                            "Calculating task graph as configuration cache cannot be reused",
                            rejected.stdout,
                        )
                        self.assertIn("has changed", rejected.stdout)
                        self.assertIn(relative.as_posix(), rejected.stdout.replace("\\", "/"))
                        self.assertNotIn("Reusing configuration cache.", rejected.stdout)
                    finally:
                        build_file.write_text(original, encoding="utf-8")

                    # Restoring exactly the original build input must pass,
                    # regardless of whether Gradle reuses or rebuilds the entry.
                    restored = self.check_architecture(
                        fixture,
                        scenario=f"{relative.as_posix()} ({configuration} -> {target})",
                        phase="restored",
                    )
                    self.assert_valid(restored)
                    self.assertEqual(original, build_file.read_text(encoding="utf-8"))

    def test_non_module_inputs_invalidate_a_warmed_configuration_cache(self):
        with tempfile.TemporaryDirectory(prefix="architecture-input-cache-") as temporary:
            fixture = Path(temporary)
            self.copy_fixture(fixture)

            properties_file = fixture / "gradle.properties"
            tasks_build_file = fixture / "core/tasks/build.gradle.kts"
            rules_file = (
                fixture / "build-logic/src/main/kotlin/ai/yuriilab/"
                "recursivecoder/buildlogic/architecture/ArchitectureGraph.kt"
            )
            settings_file = fixture / "settings.gradle.kts"

            original_properties = properties_file.read_text(encoding="utf-8")
            original_tasks_build = tasks_build_file.read_text(encoding="utf-8")
            original_rules = rules_file.read_text(encoding="utf-8")
            original_settings = settings_file.read_text(encoding="utf-8")

            # Install a property-driven *declared* dependency only in this
            # temporary fixture. Resolving the provider during configuration
            # makes its value a tracked configuration-cache input.
            property_name = "architectureFixtureIllegalEdge"
            self.assertNotIn(property_name, original_properties)
            properties_with_disabled_edge = (
                original_properties + f"\n{property_name}=false\n"
            )
            properties_file.write_text(
                properties_with_disabled_edge, encoding="utf-8"
            )
            tasks_build_file.write_text(
                original_tasks_build
                + '\nif (providers.gradleProperty("architectureFixtureIllegalEdge").orNull == "true") {\n'
                + '    dependencies { implementation(project(":infra:workspace")) }\n'
                + '}\n',
                encoding="utf-8",
            )

            cold = self.check_architecture(
                fixture, scenario="non-module inputs baseline", phase="cold-warmup"
            )
            self.assert_valid(cold)
            self.assertIn("Configuration cache entry stored.", cold.stdout)
            self.assert_cache_hit(fixture, scenario="non-module inputs baseline")

            # 1. Change the compiled allowlist, not any module build script.
            # :core:tasks already declares :core:contracts; the new rule
            # must recompile/reconfigure and reject that existing edge.
            with self.subTest(input="build-logic rules"):
                allowed = '":core:tasks" to setOf(":core:contracts"),'
                self.assertEqual(1, original_rules.count(allowed))
                rules_file.write_text(
                    original_rules.replace(
                        allowed, '":core:tasks" to emptySet(),'
                    ),
                    encoding="utf-8",
                )
                try:
                    # Gradle 9.8.1 names the invalidated compile task input,
                    # rather than the Kotlin source file, in its cache reason.
                    self.assertIn(
                        '":core:tasks" to emptySet(),',
                        rules_file.read_text(encoding="utf-8"),
                    )
                    self.assert_cache_invalidated(
                        fixture,
                        "Forbidden project dependency "
                        ":core:tasks:implementation -> :core:contracts",
                        r"an input to task ':build-logic:compileKotlin' has changed",
                        scenario="build-logic ArchitectureGraph.kt rules",
                    )
                finally:
                    rules_file.write_text(original_rules, encoding="utf-8")
                self.assertEqual(
                    original_rules, rules_file.read_text(encoding="utf-8")
                )
                self.assert_restored_cache_ready(
                    fixture, scenario="build-logic ArchitectureGraph.kt rules"
                )

            # 2. Include a new fixture-local module through settings, rather
            # than injecting one with a Gradle init script.
            with self.subTest(input="settings.gradle.kts"):
                unknown_module = fixture / "architectureUnclassified"
                unknown_module.mkdir()
                (unknown_module / "build.gradle.kts").write_text(
                    "// Intentionally missing from the architecture allowlist.\n",
                    encoding="utf-8",
                )
                settings_file.write_text(
                    original_settings + '\ninclude(":architectureUnclassified")\n',
                    encoding="utf-8",
                )
                try:
                    self.assert_cache_invalidated(
                        fixture,
                        "Unclassified module :architectureUnclassified",
                        r"settings\.gradle\.kts",
                        scenario="settings.gradle.kts",
                    )
                finally:
                    settings_file.write_text(original_settings, encoding="utf-8")
                    shutil.rmtree(unknown_module)
                self.assertEqual(
                    original_settings, settings_file.read_text(encoding="utf-8")
                )
                self.assert_restored_cache_ready(fixture, scenario="settings.gradle.kts")

            # 3. Flip only the tracked fixture property. The build script and
            # Gradle daemon/JVM settings stay unchanged across the cache hit.
            with self.subTest(input="gradle.properties"):
                properties_file.write_text(
                    properties_with_disabled_edge.replace(
                        f"{property_name}=false", f"{property_name}=true"
                    ),
                    encoding="utf-8",
                )
                try:
                    self.assert_cache_invalidated(
                        fixture,
                        "Forbidden project dependency "
                        ":core:tasks:implementation -> :infra:workspace",
                        r"gradle\.properties|architectureFixtureIllegalEdge",
                        scenario="gradle.properties (architectureFixtureIllegalEdge)",
                    )
                finally:
                    properties_file.write_text(
                        properties_with_disabled_edge, encoding="utf-8"
                    )
                restored = self.check_architecture(
                    fixture,
                    scenario="gradle.properties (architectureFixtureIllegalEdge)",
                    phase="restored",
                )
                self.assert_valid(restored)
                self.assertEqual(
                    properties_with_disabled_edge,
                    properties_file.read_text(encoding="utf-8"),
                )


class LoggedGradleSubprocessTest(unittest.TestCase):
    """Fast runner checks that do not require Gradle."""

    def test_combined_stream_and_failure_marker(self):
        log = io.StringIO()
        with redirect_stdout(log):
            result = run_logged_gradle(
                [
                    sys.executable, "-u", "-c",
                    "import sys; print('out', flush=True); "
                    "print('err', file=sys.stderr, flush=True); sys.exit(7)",
                ],
                cwd=ROOT, scenario="module build.gradle.kts",
                phase="invalidated", sequence=3, timeout=5,
            )
        self.assertEqual(7, result.returncode)
        self.assertEqual("out\nerr\n", result.stdout)
        self.assertLess(log.getvalue().index("START"), log.getvalue().index("out\n"))
        self.assertLess(log.getvalue().index("err\n"), log.getvalue().index("END"))
        self.assertIn("status=failed exit=7", log.getvalue())

    def test_stream_reaches_log_before_child_exits(self):
        with tempfile.TemporaryDirectory(prefix="gradle-logging-") as directory:
            release = Path(directory) / "release"
            code = (
                "import pathlib, time\n"
                f"release = pathlib.Path({str(release)!r})\n"
                "print('first line', flush=True)\n"
                "while not release.exists():\n"
                "    time.sleep(0.02)\n"
                "print('finished', flush=True)\n"
            )
            log = io.StringIO()
            results = []
            failures = []

            def invoke() -> None:
                try:
                    results.append(
                        run_logged_gradle(
                            [sys.executable, "-u", "-c", code],
                            cwd=ROOT,
                            scenario="live fixture",
                            phase="cold-warmup",
                            sequence=1,
                            timeout=10,
                        )
                    )
                except BaseException as error:
                    failures.append(error)

            with redirect_stdout(log):
                worker = threading.Thread(target=invoke)
                worker.start()
                try:
                    deadline = time.monotonic() + 5
                    while "first line\n" not in log.getvalue() and time.monotonic() < deadline:
                        time.sleep(0.01)
                    self.assertIn("first line\n", log.getvalue())
                    self.assertTrue(worker.is_alive(), "Child exited before release")
                finally:
                    release.touch()
                    worker.join(timeout=5)
            self.assertFalse(worker.is_alive(), "Runner did not exit after release")
            self.assertEqual([], failures)
            self.assertEqual("first line\nfinished\n", results[0].stdout)

    def test_timeout_reports_scenario_and_partial_output(self):
        log = io.StringIO()
        with redirect_stdout(log):
            with self.assertRaises(AssertionError) as error:
                run_logged_gradle(
                    [
                        sys.executable, "-u", "-c",
                        "import time; print('diagnostic', flush=True); time.sleep(60)",
                    ],
                    cwd=ROOT, scenario="settings.gradle.kts",
                    phase="invalidated", sequence=2, timeout=1.5,
                )
        self.assertIn("settings.gradle.kts", str(error.exception))
        self.assertIn("diagnostic", str(error.exception))
        self.assertIn("END status=timeout", log.getvalue())


if __name__ == "__main__":
    unittest.main()
