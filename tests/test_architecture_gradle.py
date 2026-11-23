"""Regression checks for architecture rules using temporary Gradle init scripts."""

import atexit
import errno
from collections import defaultdict
from contextlib import redirect_stdout
import io
import os
import re
from pathlib import Path
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent

# Every fixture owns its build outputs and configuration cache. Gradle daemon
# reuse across fixtures assumes immutable build-logic static/classloader state:
# ArchitectureGraph and opt-in policies must not acquire mutable global caches.
# The suite also owns a separate daemon registry so deliberately hostile fixture
# scripts cannot run inside a developer's ordinary Gradle daemon.
_GRADLE_INVOCATIONS: list[tuple[str, str, str, float, float | None, str]] = []
_FIXTURE_COPIES: list[tuple[str, float]] = []
_SUITE_GRADLE_HOME: tempfile.TemporaryDirectory[str] | None = None
_DAEMON_IDLE_TIMEOUT_MS = 60_000
_DAEMON_STOP_TIMEOUT_S = 20
# Never duplicate an entire developer cache after a hardlink failure.
_MAX_ARTIFACT_COPY_BYTES = 128 * 1024 * 1024
_CACHE_CLEANUP_INIT = (
    "beforeSettings { settings ->\n"
    "    settings.caches { cleanup = Cleanup.DISABLED }\n"
    "    if (System.getProperty('architecture.cacheCleanupProbe') == 'true') {\n"
    "        println 'Architecture suite cache cleanup disabled'\n"
    "    }\n"
    "}\n"
)


def _same_filesystem(source: Path, destination: Path) -> bool:
    """Check both devices before traversing the content-addressed artifact tree."""
    return source.stat().st_dev == destination.stat().st_dev


def _private_suite_home(shared_home: Path):
    """Prefer the cache's own filesystem; report permission-related fallbacks."""
    locations = [("sibling", shared_home.parent)]
    if shared_home.is_dir():
        locations.append(("inside-shared", shared_home))
    locations.append(("system-temp", None))
    failures = []
    for mode, directory in locations:
        try:
            return (tempfile.TemporaryDirectory(
                prefix="architecture-gradle-home-", dir=directory,
            ), mode, failures)
        except OSError as error:
            failures.append(f"{mode}:{type(error).__name__}")
    raise OSError(f"No writable location for private Gradle home: {failures}")



def _link_shared_cache(source: Path, destination: Path) -> None:
    """Share trusted Gradle caches without merging daemon registries."""
    try:
        destination.symlink_to(source, target_is_directory=source.is_dir())
    except OSError:
        if os.name != "nt" or not source.is_dir():
            raise
        # A junction works for unprivileged Windows users.
        subprocess.run(
            ["cmd", "/c", "mklink", "/J", str(destination), str(source)],
            check=True, capture_output=True, text=True, timeout=10,
        )


def _create_suite_gradle_home(shared_home: Path) -> tempfile.TemporaryDirectory[str]:
    """Private daemons/init scripts, with vetted artifacts and cache metadata shared."""
    started = time.monotonic()
    shared = shared_home.expanduser().resolve()
    home, placement, failures = _private_suite_home(shared)
    hardlinks = hardlink_bytes = copies = copy_bytes = 0

    def link_artifact(source: str, destination: str) -> str:
        nonlocal hardlinks, hardlink_bytes, copies, copy_bytes
        size = os.stat(source).st_size
        try:
            os.link(source, destination)
        except OSError as error:
            if error.errno == errno.EXDEV:
                raise RuntimeError(
                    "Architecture artifact cache is cross-device; refusing "
                    "to copy it into the private Gradle home"
                ) from error
            if copy_bytes + size > _MAX_ARTIFACT_COPY_BYTES:
                raise RuntimeError(
                    "Architecture artifact cache hardlink failed and bounded "
                    f"copy fallback exceeds {_MAX_ARTIFACT_COPY_BYTES} bytes"
                ) from error
            # Small explicit same-filesystem fallback for unavailable hardlinks.
            shutil.copy2(source, destination)
            copies += 1
            copy_bytes += size
        else:
            hardlinks += 1
            hardlink_bytes += size
        return destination

    def report(status: str) -> None:
        print(
            f"[architecture-suite-home] status={status} placement={placement} "
            f"fallback={','.join(failures) if failures else 'none'} "
            f"hardlinks={hardlinks} hardlink-bytes={hardlink_bytes} "
            f"copies={copies} copy-bytes={copy_bytes} "
            f"elapsed={time.monotonic() - started:.2f}s",
            flush=True,
        )

    try:
        source = shared / "caches"
        if source.is_dir():
            dest = Path(home.name) / "caches"
            dest.mkdir()
            for entry in source.iterdir():
                target = dest / entry.name
                if entry.name != "modules-2" or not entry.is_dir():
                    _link_shared_cache(entry.resolve(), target)
                    continue
                target.mkdir()
                for module_entry in entry.iterdir():
                    module_dest = target / module_entry.name
                    if module_entry.name == "files-2.1" and module_entry.is_dir():
                        # The security guard checks canonical artifact paths
                        # beneath the private Gradle User Home, not symlinks.
                        if not _same_filesystem(module_entry, Path(home.name)):
                            raise RuntimeError(
                                "Architecture artifact cache is cross-device; "
                                "refusing a bulk copy into the private Gradle home. "
                                "Use a writable directory on the cache filesystem."
                            )
                        shutil.copytree(
                            module_entry, module_dest, copy_function=link_artifact,
                        )
                    else:
                        _link_shared_cache(module_entry.resolve(), module_dest)
        # Gradle 9 cleanup configuration belongs in the private init.d. Do
        # not execute ordinary developer init scripts or share daemon state.
        init_dir = Path(home.name) / "init.d"
        init_dir.mkdir()
        (init_dir / "disable-cache-cleanup.init.gradle").write_text(
            _CACHE_CLEANUP_INIT, encoding="utf-8",
        )
        (Path(home.name) / "gradle.properties").write_text(
            "org.gradle.welcome=never\n", encoding="utf-8",
        )
        report("ready")
        return home
    except BaseException:
        report("failed")
        home.cleanup()
        raise


def _suite_gradle_home() -> str:
    global _SUITE_GRADLE_HOME
    if _SUITE_GRADLE_HOME is None:
        shared = Path(os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle")
        _SUITE_GRADLE_HOME = _create_suite_gradle_home(shared)
    return _SUITE_GRADLE_HOME.name


def _stop_suite_daemons(reason: str) -> None:
    """Stop ONLY this suite's daemon registry, never the user's daemon registry."""
    if _SUITE_GRADLE_HOME is None:
        return
    print(f"[architecture-daemon] stopping isolated daemons: {reason}", flush=True)
    try:
        result = subprocess.run(
            [str(ROOT / "gradlew"), "--quiet", "--stop"],
            cwd=ROOT,
            env={**os.environ, "GRADLE_USER_HOME": _SUITE_GRADLE_HOME.name},
            capture_output=True,
            text=True,
            timeout=_DAEMON_STOP_TIMEOUT_S,
            check=False,
        )
        print(
            f"[architecture-daemon] stop exit={result.returncode}; "
            f"output={result.stdout[-1024:]!r} stderr={result.stderr[-1024:]!r}",
            flush=True,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"[architecture-daemon] WARNING stop failed: {error}", flush=True)


@atexit.register
def _cleanup_suite_gradle_home() -> None:
    global _SUITE_GRADLE_HOME
    if _SUITE_GRADLE_HOME is None:
        return
    try:
        _stop_suite_daemons("test suite finished")
    finally:
        _SUITE_GRADLE_HOME.cleanup()
        _SUITE_GRADLE_HOME = None


@atexit.register
def _print_architecture_timings() -> None:
    """Report timings without flaky elapsed-time pass/fail thresholds."""
    if not _GRADLE_INVOCATIONS:
        return
    phase_times: dict[str, list[float]] = defaultdict(list)
    compile_times: dict[str, list[float]] = defaultdict(list)
    for _, _, phase, elapsed, _, compile_state in _GRADLE_INVOCATIONS:
        phase_times[phase].append(elapsed)
        compile_times[compile_state].append(elapsed)
    print(
        f"[architecture-timing] Gradle invocations={len(_GRADLE_INVOCATIONS)} "
        f"elapsed-total={sum(x[3] for x in _GRADLE_INVOCATIONS):.1f}s "
        f"fixture-copies={len(_FIXTURE_COPIES)} "
        f"copy-total={sum(x[1] for x in _FIXTURE_COPIES):.1f}s",
        flush=True,
    )
    for group, values in sorted(phase_times.items()):
        print(
            f"[architecture-timing] phase={group} count={len(values)} "
            f"total={sum(values):.1f}s median={statistics.median(values):.1f}s",
            flush=True,
        )
    for group, values in sorted(compile_times.items()):
        print(
            f"[architecture-timing] build-logic={group} count={len(values)} "
            f"total={sum(values):.1f}s",
            flush=True,
        )


class _WindowsProcessJob:
    """Track descendants after the direct Gradle process exits on Windows."""

    def __init__(self, process: subprocess.Popen[str]) -> None:
        import ctypes
        from ctypes import wintypes

        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        kernel.CreateJobObjectW.restype = wintypes.HANDLE
        kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        kernel.AssignProcessToJobObject.restype = wintypes.BOOL
        kernel.TerminateJobObject.argtypes = [wintypes.HANDLE, wintypes.UINT]
        kernel.TerminateJobObject.restype = wintypes.BOOL
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel.CloseHandle.restype = wintypes.BOOL

        handle = kernel.CreateJobObjectW(None, None)
        if not handle:
            raise OSError(ctypes.get_last_error(), "CreateJobObjectW failed")
        if not kernel.AssignProcessToJobObject(handle, process._handle):
            error = ctypes.get_last_error()
            kernel.CloseHandle(handle)
            raise OSError(error, "AssignProcessToJobObject failed")
        self._kernel = kernel
        self._handle = handle

    def terminate(self) -> bool:
        return bool(self._kernel.TerminateJobObject(self._handle, 1))

    def close(self) -> None:
        self._kernel.CloseHandle(self._handle)


def _stop_gradle_tree(
    process: subprocess.Popen[str], *, force: bool = False,
    windows_job=None,
) -> None:
    """Stop the CLI process tree; detached Gradle daemons require separate stop."""
    if os.name == "posix":
        try:
            os.killpg(process.pid, signal.SIGKILL if force else signal.SIGTERM)
        except ProcessLookupError:
            pass
    elif os.name == "nt":
        if windows_job is not None and windows_job.terminate():
            return
        # Fallback when Windows Job Objects are unavailable.
        # taskkill /T terminates the process tree, not just the Gradle parent.
        # Windows does not support POSIX process-group signals.
        try:
            result = subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=10,
                check=False,
            )
            if result.returncode != 0 and process.poll() is None:
                process.kill()
        except (OSError, subprocess.TimeoutExpired):
            if process.poll() is None:
                process.kill()
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
    # All 93 Gradle invocations share a suite-only daemon pool. The 60s idle
    # limit avoids leaving large test daemons running on developer workstations.
    is_gradle = Path(command[0]).stem == "gradlew"  # also gradlew.bat on Windows
    gradle_env = (
        {**os.environ, "GRADLE_USER_HOME": _suite_gradle_home()}
        if is_gradle else None
    )
    if is_gradle:
        command = [
            command[0], f"-Dorg.gradle.daemon.idletimeout={_DAEMON_IDLE_TIMEOUT_MS}",
            *command[1:],
        ]
    started = time.monotonic()
    print(f"{label} START timeout={timeout:g}s", flush=True)
    try:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            env=gradle_env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
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

    windows_job = None
    if os.name == "nt":
        try:
            windows_job = _WindowsProcessJob(process)
        except OSError as error:
            print(
                f"{label} WARNING Job Object unavailable ({error}); "
                "falling back to taskkill",
                flush=True,
            )

    output_lines: list[str] = []
    first_task_elapsed: float | None = None

    def relay() -> None:
        nonlocal first_task_elapsed
        assert process.stdout is not None
        for line in process.stdout:
            if first_task_elapsed is None and line.startswith("> Task "):
                first_task_elapsed = time.monotonic() - started
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
        try:
            if timed_out:
                _stop_gradle_tree(process, windows_job=windows_job)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    _stop_gradle_tree(
                        process, force=True, windows_job=windows_job
                    )
                    process.wait()
                # A detached daemon is not a descendant of the Gradle CLI.
                if is_gradle:
                    _stop_suite_daemons(f"{label} timed out")
            reader.join(timeout=5)
            if reader.is_alive():
                if is_gradle:
                    _stop_suite_daemons(f"{label} stdout reader stuck")
                # Parent may exit while its descendants still hold stdout.
                _stop_gradle_tree(
                    process, force=True, windows_job=windows_job
                )
                reader.join(timeout=5)
            if process.poll() is None:
                _stop_gradle_tree(
                    process, force=True, windows_job=windows_job
                )
            process.wait()
            if reader.is_alive():
                # Closing an active reader's pipe may itself hang.
                status = "timeout" if timed_out else "output-reader-stuck"
                print(
                    f"{label} END status={status} exit={process.returncode} "
                    f"elapsed={time.monotonic() - started:.1f}s",
                    flush=True,
                )
                reason = (
                    f"timed out after {timeout:g}s; reader remains stuck"
                    if timed_out else "output reader did not exit"
                )
                raise AssertionError(
                    f"{label} {reason} after process-tree cleanup; "
                    "see streamed output above"
                )
            assert process.stdout is not None
            process.stdout.close()
        finally:
            if windows_job is not None:
                windows_job.close()

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
            f"Gradle output was streamed above; last 2048 characters:\n"
            f"{transcript[-2048:]}"
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
    compiled = re.findall(r"(?m)^> Task :build-logic:compileKotlin(?: ([^\r\n]+))?$", transcript)
    if not compiled:
        compile_state = "no-compile-task"
    elif any(item not in ("FROM-CACHE", "UP-TO-DATE", "SKIPPED", "NO-SOURCE")
             for item in compiled):
        compile_state = "executed"
    else:
        compile_state = compiled[-1].lower()
    if is_gradle:
        _GRADLE_INVOCATIONS.append(
            (cwd.name, scenario, phase, elapsed, first_task_elapsed, compile_state)
        )
    pre_task = f"{first_task_elapsed:.1f}s" if first_task_elapsed is not None else "n/a"
    print(
        f"{label} END status={status} exit={process.returncode} "
        f"elapsed={elapsed:.1f}s pre-task={pre_task} "
        f"build-logic={compile_state} cache={cache}",
        flush=True,
    )
    return subprocess.CompletedProcess(command, process.returncode, transcript)


class _GradleOutputAssertions(unittest.TestCase):
    """Keep unittest assertions from duplicating live Gradle output."""

    def assert_gradle_contains(self, expected: str, output: str) -> None:
        if expected not in output:
            self.fail(f"Expected {expected!r}; see streamed Gradle output above.")

    def assert_gradle_not_contains(self, forbidden: str, output: str) -> None:
        if forbidden in output:
            self.fail(f"Unexpected {forbidden!r}; see streamed Gradle output above.")

    def assert_gradle_regex(self, output: str, pattern: str) -> None:
        if re.search(pattern, output) is None:
            self.fail(f"Expected pattern {pattern!r}; see streamed Gradle output above.")


class ArchitectureBoundaryTest(_GradleOutputAssertions):
    def test_production_file_opt_in_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="trusted-running-optin-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            injected = fixture / "core/session/src/main/kotlin/ForbiddenRunningOptIn.kt"
            injected.parent.mkdir(parents=True, exist_ok=True)
            injected.write_text(
                "@file:OptIn(TrustedRunningBoundaryApi::class)\n",
                encoding="utf-8",
            )
            run = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture,
                scenario="production file-level trusted running opt-in",
                phase="architecture-source-rejection",
                sequence=1,
                timeout=180,
            )
            self.assertNotEqual(0, run.returncode)
            self.assert_gradle_contains(
                "Unauthorized TrustedRunningBoundaryApi reference in production Kotlin source",
                run.stdout,
            )

    def test_import_alias_typealias_and_multiline_comment_are_rejected(self):
        cases = {
            "import-alias": "import ai.yuriilab.recursivecoder.TrustedRunningBoundaryApi as T\n",
            "typealias": "typealias C3 = TrustedRunningBoundaryApi\n",
            "comment-before-marker": (
                "@OptIn(Other::class, /* ) */\n"
                "  TrustedRunningBoundaryApi::class)\n"
            ),
        }
        with tempfile.TemporaryDirectory(prefix="architecture-token-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            injected = fixture / "core/tasks/src/main/kotlin/Unreviewed.kt"
            injected.parent.mkdir(parents=True, exist_ok=True)
            for name, content in cases.items():
                with self.subTest(name=name):
                    injected.write_text(content, encoding="utf-8")
                    result = run_logged_gradle(
                        [str(ROOT / "gradlew"), "--daemon", "--offline",
                         "--console=plain", "checkArchitecture"],
                        cwd=fixture, scenario=name,
                        phase="token-negative", sequence=1, timeout=180,
                    )
                    self.assertNotEqual(0, result.returncode)
                    self.assert_gradle_contains(
                        "Unauthorized TrustedRunningBoundaryApi reference", result.stdout)

    def test_compiler_wide_module_opt_in_and_convention_plugin_are_rejected(self):
        marker = "ai.yuriilab.recursivecoder.TrustedRunningBoundaryApi"
        cases = (
            ("core/tasks/build.gradle.kts",
             'tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { '
             f'compilerOptions.optIn.add("{marker}") }}\n'),
            ("core/tasks/build.gradle.kts",
             'tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { '
             f'compilerOptions.freeCompilerArgs.add("-opt-in={marker}") }}\n'),
            ("core/tasks/build.gradle.kts",
             f'kotlin.sourceSets.all {{ languageSettings.optIn("{marker}") }}\n'),
            ("build-logic/src/main/kotlin/recursivecoder.jvm.gradle.kts",
             'tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach { '
             f'compilerOptions.optIn.add("{marker}") }}\n'),
        )
        with tempfile.TemporaryDirectory(prefix="architecture-gradle-optin-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            for index, (relative, injection) in enumerate(cases):
                path = fixture / relative
                original = path.read_text(encoding="utf-8")
                with self.subTest(relative=relative, index=index):
                    path.write_text(original + "\n" + injection, encoding="utf-8")
                    try:
                        result = run_logged_gradle(
                            [str(ROOT / "gradlew"), "--daemon", "--offline",
                             "--console=plain", "checkArchitecture"],
                            cwd=fixture, scenario=f"{relative} compiler opt-in",
                            phase="script-negative", sequence=index + 1, timeout=180,
                        )
                        self.assertNotEqual(0, result.returncode)
                        self.assert_gradle_contains(
                            "Forbidden compiler-wide TrustedRunningBoundaryApi opt-in",
                            result.stdout)
                    finally:
                        path.write_text(original, encoding="utf-8")

    def test_effective_source_roots_include_custom_generated_and_java(self):
        with tempfile.TemporaryDirectory(prefix="architecture-extra-roots-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            module = fixture / "core/tasks/build.gradle.kts"
            original = module.read_text(encoding="utf-8")
            cases = (
                ('kotlin.sourceSets.getByName("main").kotlin.srcDir("src/extra")',
                 "core/tasks/src/extra/Bad.kt",
                 "@file:OptIn(TrustedRunningBoundaryApi::class)\n",
                 "Unauthorized TrustedRunningBoundaryApi reference"),
                ('kotlin.sourceSets.getByName("main").kotlin.srcDir("src/generatedExtra")',
                 "core/tasks/src/generatedExtra/Bad.kt",
                 "@file:OptIn(TrustedRunningBoundaryApi::class)\n",
                 "Unauthorized TrustedRunningBoundaryApi reference"),
                ('sourceSets.getByName("main").java.srcDir("src/generatedJava")',
                 "core/tasks/src/generatedJava/Bad.java", "class Bad {}\n",
                 "Production Java source is forbidden"),
            )
            for n, (config, source_name, content, expected) in enumerate(cases):
                with self.subTest(source=source_name):
                    source = fixture / source_name
                    source.parent.mkdir(parents=True, exist_ok=True)
                    source.write_text(content, encoding="utf-8")
                    module.write_text(original + "\n" + config + "\n", encoding="utf-8")
                    try:
                        run = run_logged_gradle(
                            [str(ROOT / "gradlew"), "--daemon", "--offline",
                             "--console=plain", "checkArchitecture"],
                            cwd=fixture, scenario=f"source root {source_name}",
                            phase="negative", sequence=n + 1, timeout=180,
                        )
                        self.assertNotEqual(0, run.returncode)
                        self.assert_gradle_contains(expected, run.stdout)
                    finally:
                        source.unlink()
                        module.write_text(original, encoding="utf-8")

    def test_java_main_denied_and_java_test_permitted(self):
        with tempfile.TemporaryDirectory(prefix="architecture-java-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            test = fixture / "core/tasks/src/test/java/Allowed.java"
            test.parent.mkdir(parents=True, exist_ok=True)
            test.write_text("class Allowed {}\n", encoding="utf-8")
            good = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="Java test source", phase="positive",
                sequence=1, timeout=180)
            self.assertEqual(0, good.returncode)
            main = fixture / "infra/journal/src/main/java/Unreviewed.java"
            main.parent.mkdir(parents=True, exist_ok=True)
            main.write_text(
                "package ai.yuriilab.recursivecoder;\n"
                "public final class Unreviewed {\n"
                "  public static void publish(TrustedSessionRunningHost host,\n"
                "      SessionRequest request, Cancellation cancellation,\n"
                "      java.time.Instant deadline, java.time.Clock clock) {\n"
                "    host.publishAcceptedRunning(request, cancellation, deadline, clock);\n"
                "  }\n"
                "}\n", encoding="utf-8")
            bad = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="Java production source", phase="negative",
                sequence=2, timeout=180)
            self.assertNotEqual(0, bad.returncode)
            self.assert_gradle_contains("Production Java source is forbidden", bad.stdout)

    def test_semantic_split_marker_is_rejected_but_unrelated_opt_in_is_allowed(self):
        with tempfile.TemporaryDirectory(prefix="architecture-semantic-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            build = fixture / "core/tasks/build.gradle.kts"
            original = build.read_text(encoding="utf-8")
            injection = (
                'tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach { '
                'compilerOptions.optIn.add(listOf("ai.yuriilab.recursivecoder.", '
                '"TrustedRunningBoundaryApi").joinToString("")) }\n'
            )
            build.write_text(original + "\n" + injection, encoding="utf-8")
            denied = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="split marker compiler opt-in", phase="negative",
                sequence=1, timeout=180)
            self.assertNotEqual(0, denied.returncode)
            self.assert_gradle_contains("Forbidden compiler-wide TrustedRunningBoundaryApi opt-in", denied.stdout)
            build.write_text(original + "\n"
                '// compilerOptions.optIn.add("TrustedRunningBoundaryApi")\n'
                'tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach { '
                'compilerOptions.optIn.add("com.example.Unrelated") }\n', encoding="utf-8")
            allowed = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="comment and unrelated compiler opt-in", phase="positive",
                sequence=2, timeout=180)
            self.assertEqual(0, allowed.returncode)

    def test_c3_suppression_without_marker_is_rejected(self):
        cases = {
            "literal": '@Suppress("OPT_IN_USAGE_ERROR")\n',
            "unicode": r'@Suppress("OPT_IN_USAGE\u005FERROR")' + "\n",
            "concat": '@Suppress("OPT_IN_" + "USAGE_ERROR")\n',
            "external-constant": '@Suppress(OtherFile.C3_ERROR)\n',
            "import-alias": 'import kotlin.Suppress as Ignore\n@Ignore(OtherFile.C3_ERROR)\n',
            "java-suppress-warnings": '@java.lang.SuppressWarnings("OPT_IN_" + "USAGE_ERROR")\n',
            "suppress-warnings-alias": 'import kotlin.Suppress as SuppressWarnings\n@SuppressWarnings("OPT_IN_" + "USAGE_ERROR")\n',
        }
        with tempfile.TemporaryDirectory(prefix="architecture-suppression-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            source = fixture / "core/tasks/src/main/kotlin/Suppressed.kt"
            source.parent.mkdir(parents=True, exist_ok=True)
            for idx, (name, suppression) in enumerate(cases.items()):
                with self.subTest(name=name):
                    source.write_text(
                        suppression +
                        'fun bypass(runtime: DefaultRunSessionUseCase, request: SessionRequest, '
                        'cancellation: Cancellation, host: TrustedSessionRunningHost) =\n'
                        '    runtime.publishTrustedAcceptedRunning(request, cancellation, host)\n',
                        encoding="utf-8")
                    run = run_logged_gradle(
                        [str(ROOT / "gradlew"), "--daemon", "--offline",
                         "--console=plain", "checkArchitecture"],
                        cwd=fixture, scenario=f"production {name} suppression",
                        phase="negative", sequence=idx + 1, timeout=180)
                    self.assertNotEqual(0, run.returncode)
                    self.assert_gradle_contains(
                        "Forbidden Kotlin suppression in production source", run.stdout)

    def test_other_production_bytecode_and_dynamic_entrypoints_are_denied(self):
        mutations = {
            "groovy-compiler": ('apply(plugin = "groovy")\n',
                                "Unapproved production JVM compiler"),
            "scala-compiler": ('apply(plugin = "scala")\n',
                               "Unapproved production JVM compiler"),
            "direct-jar": ('dependencies { "implementation"(files("rogue.jar")) }\n',
                           "Unreviewed production file dependency"),
            "compiler-plugin-flags": (
                'tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>()'
                '.configureEach { compilerOptions.freeCompilerArgs.add("-Xplugin=rogue.jar") }\n',
                "Forbidden Kotlin compiler plugin argument"),
            "compiler-plugin-P": (
                'tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>()'
                '.configureEach { compilerOptions.freeCompilerArgs.addAll('
                'listOf("-P", "plugin:rogue:key=value")) }\n',
                "Forbidden Kotlin compiler plugin argument"),
            "compiler-plugin-classpath": (
                'tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>()'
                '.configureEach { pluginClasspath.from(files("rogue.jar")) }\n',
                "Kotlin compiler plugin classpath is forbidden"),
            "extra-output-dir": (
                'extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()'
                '.named("main") { output.dir(file("rogue-classes")) }\n',
                "Unreviewed production output.dir bytecode directory"),
        }
        with tempfile.TemporaryDirectory(prefix="architecture-bytecode-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            script = fixture / "core/tasks/build.gradle.kts"
            base_script = script.read_text(encoding="utf-8")
            (fixture / "core/tasks/rogue.jar").write_bytes(b"invalid-bytecode-placeholder")
            for idx, (name, (injection, diagnostic)) in enumerate(mutations.items()):
                with self.subTest(name=name):
                    script.write_text(base_script + "\n" + injection, encoding="utf-8")
                    run = run_logged_gradle(
                        [str(ROOT / "gradlew"), "--daemon", "--offline",
                         "--console=plain", "checkArchitecture"],
                        cwd=fixture, scenario=f"production bytecode {name}",
                        phase="negative", sequence=idx + 1, timeout=240)
                    self.assertNotEqual(0, run.returncode)
                    self.assert_gradle_contains(diagnostic, run.stdout)

    def test_production_precompiled_resources_are_denied(self):
        with tempfile.TemporaryDirectory(prefix="architecture-evil-resource-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            resource = (fixture / "core/tasks/src/main/resources" /
                        "ai/yuriilab/recursivecoder/Evil.class")
            resource.parent.mkdir(parents=True, exist_ok=True)
            resource.write_bytes(b"\xca\xfe\xba\xbe")
            result = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="precompiled resource class",
                phase="negative", sequence=1, timeout=240)
            self.assertNotEqual(0, result.returncode)
            self.assert_gradle_contains(
                "Precompiled JVM bytecode in production resources is forbidden", result.stdout)

    def test_dependency_verification_settings_guard_is_fail_closed(self):
        # These tests exercise the real root AND standalone build-logic settings,
        # rather than a mock of the guard. Assert the security diagnostic so a
        # failed plugin download or unrelated configuration error cannot pass.
        diagnostic = "Strict Gradle SHA-256 dependency verification is mandatory"
        with tempfile.TemporaryDirectory(prefix="gradle-verification-modes-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            sequence = 0

            def execute(mode: str, options: list[str], *, standalone: bool = False):
                nonlocal sequence
                sequence += 1
                args = [str(ROOT / "gradlew"), "--daemon", "--offline",
                        "--console=plain"]
                if standalone:
                    args.extend(["-p", "build-logic"])
                args.extend(options)
                args.append("help")
                return run_logged_gradle(
                    args, cwd=fixture, scenario=f"verification {mode}",
                    phase="negative", sequence=sequence, timeout=180,
                )

            for standalone in (False, True):
                scope = "standalone build-logic" if standalone else "root"
                for mode in ("off", "lenient"):
                    with self.subTest(scope=scope, cli=mode):
                        result = execute(
                            f"{scope}: --dependency-verification {mode}",
                            ["--dependency-verification", mode],
                            standalone=standalone,
                        )
                        self.assertNotEqual(0, result.returncode)
                        self.assert_gradle_contains(diagnostic, result.stdout)

                properties = fixture / ("build-logic/gradle.properties" if standalone
                                        else "gradle.properties")
                previous = properties.read_bytes() if properties.exists() else None
                try:
                    for mode in ("off", "lenient"):
                        with self.subTest(scope=scope, gradle_properties=mode):
                            properties.write_text(
                                (previous.decode("utf-8") if previous else "") +
                                f"\norg.gradle.dependency.verification={mode}\n",
                                encoding="utf-8",
                            )
                            result = execute(
                                f"{scope}: gradle.properties {mode}", [],
                                standalone=standalone,
                            )
                            self.assertNotEqual(0, result.returncode)
                            self.assert_gradle_contains(diagnostic, result.stdout)
                finally:
                    if previous is None:
                        properties.unlink(missing_ok=True)
                    else:
                        properties.write_bytes(previous)

                manifest = fixture / ("build-logic/gradle/verification-metadata.xml"
                                      if standalone else "gradle/verification-metadata.xml")
                saved = manifest.read_bytes()
                manifest.unlink()
                try:
                    with self.subTest(scope=scope, manifest="missing"):
                        result = execute(f"{scope}: missing manifest", [],
                                         standalone=standalone)
                        self.assertNotEqual(0, result.returncode)
                        self.assert_gradle_contains(
                            "Missing reviewed gradle/verification-metadata.xml",
                            result.stdout,
                        )
                finally:
                    manifest.write_bytes(saved)

    def test_verification_metadata_refresh_is_available_only_in_fixture(self):
        # The production manifests must not be rewritten as part of normal CI.
        # This checks the documented manual refresh route on disposable copies
        # with the actual new strict settings guards active.
        with tempfile.TemporaryDirectory(prefix="gradle-verify-refresh-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            for i, standalone in enumerate((True, False), 1):
                with self.subTest(standalone=standalone):
                    relative = ("build-logic/gradle/verification-metadata.xml"
                                if standalone else "gradle/verification-metadata.xml")
                    manifest = fixture / relative
                    before = manifest.read_bytes()
                    command = [str(ROOT / "gradlew"), "--daemon",
                               "--offline", "--console=plain"]
                    if standalone:
                        command.extend(["-p", "build-logic"])
                    command.extend(["--write-verification-metadata", "sha256", "help"])
                    result = run_logged_gradle(
                        command, cwd=fixture,
                        scenario=f"manual SHA-256 refresh: {relative}",
                        phase="positive", sequence=i, timeout=240,
                    )
                    self.assertEqual(0, result.returncode,
                                     "Manual SHA-256 refresh should be usable in fixture")
                    self.assertTrue(manifest.is_file())
                    self.assertIn(b"<sha256", manifest.read_bytes())
                    # At least verify this was an isolated fixture update, not
                    # a write to the security-controlled repository manifest.
                    self.assertEqual(before, (ROOT / relative).read_bytes())

    def test_local_settings_repositories_are_denied(self):
        variants = {
            "mavenLocal": 'dependencyResolutionManagement { repositories { mavenLocal() } }\n',
            "flatDir": 'dependencyResolutionManagement { repositories { flatDir { dirs("libs") } } }\n',
            "file-uri": 'dependencyResolutionManagement { repositories { maven { url = uri("file:/tmp/local") } } }\n',
        }
        with tempfile.TemporaryDirectory(prefix="architecture-local-repo-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            settings = fixture / "settings.gradle.kts"
            original = settings.read_text(encoding="utf-8")
            for idx, (name, addition) in enumerate(variants.items()):
                with self.subTest(name=name):
                    settings.write_text(original + addition, encoding="utf-8")
                    result = run_logged_gradle(
                        [str(ROOT / "gradlew"), "--daemon", "--offline",
                         "--console=plain", "checkArchitecture"],
                        cwd=fixture, scenario=f"settings repository {name}",
                        phase="negative", sequence=idx + 1, timeout=240)
                    self.assertNotEqual(0, result.returncode)
                    self.assert_gradle_contains("Unreviewed Gradle repository in settings",
                                                result.stdout)


    def test_artifact_urls_in_maven_central_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="architecture-artifact-urls-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            settings = fixture / "settings.gradle.kts"
            original = settings.read_text(encoding="utf-8")
            needle = "repositories { mavenCentral() }"
            self.assertIn(needle, original)
            settings.write_text(original.replace(
                needle,
                'repositories { mavenCentral { artifactUrls("https://example.invalid/m2") } }',
                1), encoding="utf-8")
            result = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="Maven Central artifactUrls injection",
                phase="negative", sequence=1, timeout=240)
            self.assertNotEqual(0, result.returncode)
            self.assert_gradle_contains("artifactUrls are forbidden", result.stdout)

    def test_settings_and_project_legacy_buildscript_are_rejected(self):
        variants = {
            "settings.gradle.kts":
                'buildscript { repositories { maven { url = uri("file:/tmp/injected") } } }\n',
            "core/tasks/build.gradle.kts":
                'buildscript { repositories { maven { url = uri("file:/tmp/injected") } } }\n',
        }
        with tempfile.TemporaryDirectory(prefix="architecture-buildscript-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            for idx, (name, injection) in enumerate(variants.items()):
                with self.subTest(name=name):
                    script = fixture / name
                    original = script.read_text(encoding="utf-8")
                    script.write_text(original + "\n" + injection, encoding="utf-8")
                    try:
                        result = run_logged_gradle(
                            [str(ROOT / "gradlew"), "--daemon", "--offline",
                             "--console=plain", "checkArchitecture"],
                            cwd=fixture, scenario=f"legacy buildscript {name}",
                            phase="negative", sequence=idx + 1, timeout=240)
                        self.assertNotEqual(0, result.returncode)
                        self.assert_gradle_contains("buildscript", result.stdout)
                    finally:
                        script.write_text(original, encoding="utf-8")

    def test_computed_project_buildscript_repository_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="architecture-buildscript-computed-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            script = fixture / "core/tasks/build.gradle.kts"
            with script.open("a", encoding="utf-8") as stream:
                stream.write(
                    '\nproject.buildscript.repositories.maven { '
                    'url = uri("https://example.invalid/plugin") }\n'
                )
            result = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="computed project buildscript repository",
                phase="negative", sequence=1, timeout=240)
            self.assertNotEqual(0, result.returncode)
            self.assert_gradle_contains("buildscript repository", result.stdout)

    def test_settings_apply_from_script_plugins_are_rejected(self):
        settings_paths = ("settings.gradle.kts", "build-logic/settings.gradle.kts")
        for sequence, relative in enumerate(settings_paths, start=1):
            with self.subTest(settings=relative):
                with tempfile.TemporaryDirectory(
                    prefix="architecture-settings-script-plugin-"
                ) as directory:
                    fixture = Path(directory)
                    ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
                    settings = fixture / relative
                    plugin = settings.parent / "unreviewed-fixture.gradle.kts"
                    plugin.write_text("// Benign fixture to test a forbidden apply(from)\n",
                                      encoding="utf-8")
                    settings.write_text(
                        settings.read_text(encoding="utf-8") +
                        '\napply(from = "unreviewed-fixture.gradle.kts")\n',
                        encoding="utf-8",
                    )
                    result = run_logged_gradle(
                        [str(ROOT / "gradlew"), "--daemon", "--offline",
                         "--console=plain", "checkArchitecture"],
                        cwd=fixture, scenario=f"settings apply(from) {relative}",
                        phase="negative", sequence=sequence, timeout=240,
                    )
                    self.assertNotEqual(0, result.returncode)
                    self.assert_gradle_contains(
                        "Unreviewed apply(from) script plugin in settings",
                        result.stdout,
                    )

    def test_project_apply_from_script_plugin_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="architecture-script-plugin-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            injected = fixture / "core/tasks/reviewed-fixture.gradle.kts"
            injected.write_text("// Harmless fixture: explicit script plugins are denied\n",
                                encoding="utf-8")
            script = fixture / "core/tasks/build.gradle.kts"
            script.write_text(
                script.read_text(encoding="utf-8") +
                '\napply(from = "reviewed-fixture.gradle.kts")\n', encoding="utf-8")
            result = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="apply(from) script plugin",
                phase="negative", sequence=1, timeout=240)
            self.assertNotEqual(0, result.returncode)
            self.assert_gradle_contains("unreviewed apply(from) script plugin",
                                        result.stdout)

    def test_inherited_test_named_file_dependency_reaches_production(self):
        with tempfile.TemporaryDirectory(prefix="architecture-inherited-file-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            script = fixture / "core/tasks/build.gradle.kts"
            script.write_text(script.read_text(encoding="utf-8") + """
val hiddenBridge = configurations.create("testHiddenBridge")
configurations.named("implementation") { extendsFrom(hiddenBridge) }
dependencies { add("testHiddenBridge", files("rogue.jar")) }
""", encoding="utf-8")
            (fixture / "core/tasks/rogue.jar").write_bytes(b"unreviewed")
            result = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="inherited test named production dependency",
                phase="negative", sequence=1, timeout=240)
            self.assertNotEqual(0, result.returncode)
            self.assert_gradle_contains(
                "Unreviewed production file dependency", result.stdout)

    def test_content_pinned_c3_file_mutation_is_denied(self):
        with tempfile.TemporaryDirectory(prefix="architecture-c3-sha-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            source = fixture / (
                "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/"
                "DurableAcceptedRunningHandoff.kt"
            )
            source.write_text(
                source.read_text(encoding="utf-8") + "\n// Safe-looking change\n",
                encoding="utf-8",
            )
            run = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="trusted C3 SHA-256 content pin",
                phase="negative", sequence=1, timeout=240)
            self.assertNotEqual(0, run.returncode)
            self.assert_gradle_contains(
                "Trusted C3 source content changed", run.stdout)

    def test_production_reflection_is_denied(self):
        with tempfile.TemporaryDirectory(prefix="architecture-reflection-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            source = fixture / "core/tasks/src/main/kotlin/Reflective.kt"
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(
                'fun bypass() = Class.forName("ai.yuriilab.recursivecoder.SomeHost")\n',
                encoding="utf-8",
            )
            run = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="reflective trusted C3 access",
                phase="negative", sequence=1, timeout=240)
            self.assertNotEqual(0, run.returncode)
            self.assert_gradle_contains(
                "Forbidden reflection or dynamic class loading", run.stdout)

    def test_compiler_argfile_is_denied_without_marker_in_script(self):
        with tempfile.TemporaryDirectory(prefix="architecture-argfile-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            script = fixture / "core/tasks/build.gradle.kts"
            script.write_text(script.read_text(encoding="utf-8") +
                '\n// Compiler arguments may hide dangerous flags.\n'
                'tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach { '
                'compilerOptions.freeCompilerArgs.add("@c3.args") }\n',
                encoding="utf-8")
            (fixture / "core/tasks/c3.args").write_text(
                "-opt-in=ai.yuriilab.recursivecoder.TrustedRunningBoundaryApi\n",
                encoding="utf-8")
            run = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="effective compiler @argfile", phase="negative",
                sequence=1, timeout=180)
            self.assertNotEqual(0, run.returncode)
            self.assert_gradle_contains(
                "Compiler @argfile is forbidden in production", run.stdout)

    def test_groovy_init_script_compiler_opt_in_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="architecture-groovy-optin-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            init = fixture / "c3-optin.gradle"
            init.write_text(
                "gradle.projectsEvaluated {\n"
                "  def source = gradle.rootProject.findProject(':core:tasks')\n"
                "  if (source != null) {\n"
                "  def marker = ['ai.yuriilab.recursivecoder.', 'TrustedRunningBoundaryApi'].join('')\n"
                "  source.tasks.named('compileKotlin').get().compilerOptions.optIn.add(marker)\n"
                "  }\n"
                "}\n", encoding="utf-8")
            result = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "--init-script", str(init), "checkArchitecture"],
                cwd=fixture, scenario="Groovy init script compiler opt-in",
                phase="negative", sequence=1, timeout=180)
            self.assertNotEqual(0, result.returncode)
            self.assert_gradle_contains(
                "Forbidden compiler-wide TrustedRunningBoundaryApi opt-in", result.stdout)
            self.assert_gradle_not_contains("could not be found in project", result.stdout)

    def test_reviewed_marker_declaration_is_allowed(self):
        with tempfile.TemporaryDirectory(prefix="architecture-allowed-c3-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            reviewed = fixture / (
                "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/"
                "TrustedRunningBoundaryPolicy.kt"
            )
            self.assertTrue(reviewed.is_file())  # Original, content-pinned declaration.
            result = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture, scenario="allowed marker declaration",
                phase="source-regression", sequence=1, timeout=180,
            )
            self.assertEqual(0, result.returncode, result.stdout)
            self.assert_gradle_contains("Architecture boundaries verified", result.stdout)

    def test_reviewed_c3_file_cannot_launder_an_opt_in(self):
        with tempfile.TemporaryDirectory(prefix="architecture-reviewed-c3-") as directory:
            fixture = Path(directory)
            ArchitectureConfigurationCacheRegressionTest.copy_fixture(fixture)
            reviewed = fixture / (
                "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/"
                "DurableSessionEventLedger.kt"
            )
            reviewed.parent.mkdir(parents=True, exist_ok=True)
            reviewed.write_text(
                "@TrustedRunningBoundaryApi\ninternal fun reviewedHost() {}\n"
                "@OptIn(TrustedRunningBoundaryApi::class)\n"
                "fun forbiddenWrapper() {}\n",
                encoding="utf-8",
            )
            run = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "checkArchitecture"],
                cwd=fixture,
                scenario="reviewed production file cannot launder opt-in",
                phase="architecture-source-rejection",
                sequence=1,
                timeout=180,
            )
            self.assertNotEqual(0, run.returncode)
            self.assert_gradle_contains(
                "Unauthorized TrustedRunningBoundaryApi reference in production Kotlin source",
                run.stdout,
            )

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
                    "--daemon",
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
        self.assertNotEqual(
            0, run.returncode,
            "Expected Gradle to reject the injected dependency; see streamed output.",
        )
        return run.stdout

    def test_forbidden_core_to_adapter_test_dependency(self):
        output = self.run_with_edge(":core:session", ":infra:model", "testImplementation")
        self.assert_gradle_contains(
            "Forbidden project dependency :core:session:testImplementation -> :infra:model",
            output,
        )

    def test_test_only_edge_cannot_complete_a_cycle(self):
        output = self.run_with_edge(":core:contracts", ":core:session", "testImplementation")
        self.assert_gradle_contains(
            "Project dependency cycle (including tests): "
            ":core:contracts -> :core:session -> :core:contracts",
            output,
        )

    def test_forbidden_production_dependency(self):
        output = self.run_with_edge(":core:tasks", ":infra:workspace", "implementation")
        self.assert_gradle_contains(
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
        self.assert_gradle_contains("Unclassified module :unknown", output)

    def test_forbidden_core_framework_in_test_configuration(self):
        output = self.run_with_init_script(
            "gradle.projectsEvaluated {\n"
            "    if (gradle.rootProject.findProject(':core:session') != null) {\n"
            "        def owner = gradle.rootProject.project(':core:session')\n"
            "        owner.dependencies.add('testImplementation', 'io.ktor:ktor-client:1.0')\n"
            "    }\n"
            "}\n"
        )
        self.assert_gradle_contains(
            "Forbidden core framework dependency :core:session:testImplementation -> io.ktor:ktor-client",
            output,
        )


class ArchitectureConfigurationCacheRegressionTest(_GradleOutputAssertions):
    """A previously valid cache entry must not conceal new forbidden edges."""

    @staticmethod
    def copy_fixture(destination: Path) -> None:
        copy_started = time.monotonic()
        # Discover product module build scripts instead of maintaining a fixed
        # list. The included build-logic build is copied separately below.
        # Exclude Gradle output directories and existing configuration caches.
        inputs = [
            ROOT / "settings.gradle.kts",
            ROOT / "gradle.properties",
            ROOT / "gradle/verification-metadata.xml",
        ]
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
        # The guard pins exact SHA-256 of these six reviewed C3 sources.
        # Temporary workspaces need their real bytes to verify unrelated edits.
        trusted = (
            "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/SessionRuntime.kt",
            "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedRunningBoundaryPolicy.kt",
            "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedSessionPreExecutionHost.kt",
            "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableAcceptedRunningHandoff.kt",
            "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableRuntimeAcceptedRunningHost.kt",
            "infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableSessionEventLedger.kt",
        )
        for relative in trusted:
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        elapsed = time.monotonic() - copy_started
        _FIXTURE_COPIES.append((destination.name, elapsed))
        print(
            f"[architecture-fixture] name={destination.name} "
            f"copy={elapsed:.2f}s; build outputs and configuration cache excluded",
            flush=True,
        )

    def check_architecture(
        self, directory: Path, *, scenario: str, phase: str
    ) -> subprocess.CompletedProcess[str]:
        sequence = getattr(self, "_gradle_sequence", 0) + 1
        self._gradle_sequence = sequence
        return run_logged_gradle(
            [
                str(ROOT / "gradlew"),
                "--daemon",
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
        self.assertEqual(
            0, result.returncode, "Gradle failed; see streamed output above."
        )
        self.assert_gradle_regex(
            result.stdout, r"Architecture boundaries verified for [1-9]\d* modules\."
        )

    def assert_cache_hit(self, fixture: Path, *, scenario: str) -> None:
        warm = self.check_architecture(fixture, scenario=scenario, phase="cache-reuse")
        self.assert_valid(warm)
        self.assert_gradle_contains("Reusing configuration cache.", warm.stdout)

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
        self.assertNotEqual(
            0, rejected.returncode, "Expected Gradle rejection; see streamed output."
        )
        self.assert_gradle_contains(diagnostic, rejected.stdout)
        self.assert_gradle_contains(
            "Calculating task graph as configuration cache cannot be reused",
            rejected.stdout,
        )
        self.assert_gradle_not_contains("Reusing configuration cache.", rejected.stdout)
        # Name the changed input in the invalidation reason, rather than
        # matching an unrelated Gradle progress message.
        self.assert_gradle_regex(
            rejected.stdout.replace("\\", "/"),
            r"Calculating task graph as configuration cache cannot be reused because [^\n]*"
            + input_pattern,
        )

    def test_generated_source_root_rechecked_after_configuration_cache_hit(self):
        with tempfile.TemporaryDirectory(prefix="architecture-generated-cache-") as directory:
            fixture = Path(directory)
            self.copy_fixture(fixture)
            build = fixture / "core/tasks/build.gradle.kts"
            build.write_text(build.read_text(encoding="utf-8") +
                '\nkotlin.sourceSets.getByName("main").kotlin.srcDir("src/generatedRoot")\n',
                encoding="utf-8")
            self.assert_valid(self.check_architecture(
                fixture, scenario="generated root baseline", phase="cold-warmup"))
            self.assert_cache_hit(fixture, scenario="generated root baseline")
            source = fixture / "core/tasks/src/generatedRoot/Generated.kt"
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(
                "@file:OptIn(TrustedRunningBoundaryApi::class)\n", encoding="utf-8")
            denied = self.check_architecture(
                fixture, scenario="generated source after cache hit", phase="negative")
            self.assertNotEqual(0, denied.returncode)
            self.assert_gradle_contains(
                "Unauthorized TrustedRunningBoundaryApi reference", denied.stdout)

    def test_running_marker_new_source_and_script_are_checked_after_cache_hit(self):
        with tempfile.TemporaryDirectory(prefix="running-optin-cache-") as directory:
            fixture = Path(directory)
            self.copy_fixture(fixture)
            first = self.check_architecture(
                fixture, scenario="running marker baseline", phase="cold-warmup")
            self.assert_valid(first)
            self.assert_cache_hit(fixture, scenario="running marker baseline")

            injected = fixture / "core/tasks/src/main/kotlin/NewRunningOptIn.kt"
            injected.parent.mkdir(parents=True, exist_ok=True)
            injected.write_text(
                "import ai.yuriilab.recursivecoder.TrustedRunningBoundaryApi as X\n",
                encoding="utf-8",
            )
            try:
                refusal = self.check_architecture(
                    fixture, scenario="new source after cache hit", phase="negative")
                self.assertNotEqual(0, refusal.returncode)
                self.assert_gradle_contains(
                    "Unauthorized TrustedRunningBoundaryApi reference", refusal.stdout)
            finally:
                injected.unlink()

            # An unapplied script comment is not an effective compiler option.
            script = fixture / "core/tasks/new-c3-guard.gradle.kts"
            script.write_text(
                '// compilerOptions.optIn.add("TrustedRunningBoundaryApi")\n',
                encoding="utf-8",
            )
            try:
                self.assert_valid(self.check_architecture(
                    fixture, scenario="unapplied Gradle script", phase="positive"))
            finally:
                script.unlink()
            self.assert_restored_cache_ready(fixture, scenario="running marker restored")

    def test_modified_gradle_script_invalidates_warm_cache_and_denies_marker(self):
        with tempfile.TemporaryDirectory(prefix="running-optin-script-cache-") as directory:
            fixture = Path(directory)
            self.copy_fixture(fixture)
            self.assert_valid(self.check_architecture(
                fixture, scenario="module opt-in", phase="cold-warmup"))
            self.assert_cache_hit(fixture, scenario="module opt-in")
            script = fixture / "core/tasks/build.gradle.kts"
            original = script.read_text(encoding="utf-8")
            try:
                script.write_text(
                    original + '\ntasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach { '
                    + 'compilerOptions.optIn.add("ai.yuriilab.recursivecoder.TrustedRunningBoundaryApi") }\n',
                    encoding="utf-8",
                )
                self.assert_cache_invalidated(
                    fixture,
                    "Forbidden compiler-wide TrustedRunningBoundaryApi opt-in",
                    r"core/tasks/build\\.gradle\\.kts|build.gradle.kts",
                    scenario="changed module compiler opt-in",
                )
            finally:
                script.write_text(original, encoding="utf-8")
            self.assert_restored_cache_ready(fixture, scenario="module opt-in restored")

    def test_changed_build_script_invalidates_cache_for_production_and_test_edges(self):
        with tempfile.TemporaryDirectory(prefix="architecture-cache-") as temporary:
            fixture = Path(temporary)
            self.copy_fixture(fixture)

            cold = self.check_architecture(
                fixture, scenario="module build.gradle.kts baseline", phase="cold-warmup"
            )
            self.assert_valid(cold)
            self.assert_gradle_contains("Configuration cache entry stored.", cold.stdout)

            warm = self.check_architecture(
                fixture, scenario="module build.gradle.kts baseline", phase="cache-reuse"
            )
            self.assert_valid(warm)
            self.assert_gradle_contains("Reusing configuration cache.", warm.stdout)

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
                        self.assertNotEqual(
                            0, rejected.returncode,
                            "Expected Gradle rejection; see streamed output.",
                        )
                        self.assert_gradle_contains(
                            f"Forbidden project dependency {module}:{configuration} -> {target}",
                            rejected.stdout,
                        )
                        self.assert_gradle_contains(
                            "Calculating task graph as configuration cache cannot be reused",
                            rejected.stdout,
                        )
                        self.assert_gradle_contains("has changed", rejected.stdout)
                        self.assert_gradle_contains(relative.as_posix(), rejected.stdout.replace("\\", "/"))
                        self.assert_gradle_not_contains("Reusing configuration cache.", rejected.stdout)
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
            self.assert_gradle_contains("Configuration cache entry stored.", cold.stdout)
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
                    # Gradle 9.8.2 names the invalidated compile task input,
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

    def test_suite_daemon_registry_is_private_but_offline_cache_is_shared(self):
        with tempfile.TemporaryDirectory(prefix="ordinary-gradle-home-") as source:
            original = Path(source)
            caches = original / "caches"
            caches.mkdir()
            (caches / "cached-module").write_text("present", encoding="utf-8")
            artifact = caches / "modules-2/files-2.1/org.test/library/1.0/abc/library.jar"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"verified artifact")
            (original / "daemon" / "9.8.2").mkdir(parents=True)
            with _create_suite_gradle_home(original) as isolated:
                suite = Path(isolated)
                self.assertNotEqual(original, suite)
                self.assertEqual(
                    "present", (suite / "caches" / "cached-module").read_text(encoding="utf-8")
                )
                copied = suite / artifact.relative_to(original)
                self.assertEqual(b"verified artifact", copied.read_bytes())
                self.assertNotEqual(copied.resolve(), artifact.resolve())
                self.assertTrue(copied.resolve().is_relative_to(suite))
                self.assertFalse((suite / "daemon").exists())
                self.assertTrue((original / "daemon" / "9.8.2").exists())

    def test_suite_setup_metrics_and_cleanup_policy_are_private(self):
        with tempfile.TemporaryDirectory(prefix="ordinary-gradle-home-") as directory:
            shared = Path(directory)
            (shared / "init.d").mkdir()
            (shared / "init.d" / "developer.init.gradle").write_text(
                "throw new RuntimeException('must not execute')", encoding="utf-8",
            )
            artifact = shared / "caches/modules-2/files-2.1/a/b/1/hash/lib.jar"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"private artifact")
            output = io.StringIO()
            with redirect_stdout(output):
                with _create_suite_gradle_home(shared) as isolated:
                    suite = Path(isolated)
                    self.assertEqual(
                        _CACHE_CLEANUP_INIT,
                        (suite / "init.d/disable-cache-cleanup.init.gradle")
                        .read_text(encoding="utf-8"),
                    )
                    self.assertEqual(
                        ["disable-cache-cleanup.init.gradle"],
                        sorted(p.name for p in (suite / "init.d").iterdir()),
                    )
                    self.assertFalse((suite / "daemon").exists())
                    self.assertEqual(
                        "org.gradle.welcome=never\n",
                        (suite / "gradle.properties").read_text(encoding="utf-8"),
                    )
            self.assertIn("status=ready placement=sibling fallback=none", output.getvalue())
            self.assertIn("hardlinks=1 hardlink-bytes=16 copies=0 copy-bytes=0", output.getvalue())
            self.assertTrue((shared / "init.d/developer.init.gradle").exists())

    def test_cross_device_cache_rejected_before_any_artifact_copy(self):
        with tempfile.TemporaryDirectory(prefix="ordinary-gradle-home-") as directory:
            shared = Path(directory)
            artifact = shared / "caches/modules-2/files-2.1/a/b/1/hash/lib.jar"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"do not copy")
            with mock.patch.object(
                sys.modules[__name__], "_same_filesystem", return_value=False,
            ), mock.patch.object(shutil, "copy2") as copy:
                with self.assertRaisesRegex(RuntimeError, "cross-device"):
                    _create_suite_gradle_home(shared)
            copy.assert_not_called()
            self.assertEqual(b"do not copy", artifact.read_bytes())

    def test_cross_device_hardlink_failure_never_falls_back_to_copy(self):
        with tempfile.TemporaryDirectory(prefix="ordinary-gradle-home-") as directory:
            shared = Path(directory)
            artifact = shared / "caches/modules-2/files-2.1/a/b/1/hash/lib.jar"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"do not copy")
            with mock.patch.object(os, "link", side_effect=OSError(errno.EXDEV, "cross-device")):
                with mock.patch.object(shutil, "copy2") as copy:
                    with self.assertRaisesRegex(RuntimeError, "cross-device"):
                        _create_suite_gradle_home(shared)
            copy.assert_not_called()

    def test_small_same_device_copy_fallback_is_measured_and_bounded(self):
        with tempfile.TemporaryDirectory(prefix="ordinary-gradle-home-") as directory:
            shared = Path(directory)
            artifact = shared / "caches/modules-2/files-2.1/a/b/1/hash/lib.jar"
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"small")
            output = io.StringIO()
            with mock.patch.object(os, "link", side_effect=OSError(errno.EPERM, "no links")):
                with redirect_stdout(output):
                    with _create_suite_gradle_home(shared) as isolated:
                        self.assertEqual(
                            b"small", (Path(isolated) / artifact.relative_to(shared)).read_bytes(),
                        )
            self.assertIn("hardlinks=0 hardlink-bytes=0 copies=1 copy-bytes=5", output.getvalue())
            with mock.patch.object(os, "link", side_effect=OSError(errno.EPERM, "no links")):
                with mock.patch.dict(globals(), {"_MAX_ARTIFACT_COPY_BYTES": 4}):
                    with self.assertRaisesRegex(RuntimeError, "bounded copy fallback"):
                        _create_suite_gradle_home(shared)

    def test_unwritable_sibling_uses_private_directory_inside_shared_home(self):
        with tempfile.TemporaryDirectory(prefix="ordinary-gradle-home-") as directory:
            shared = Path(directory)
            real_temporary_directory = tempfile.TemporaryDirectory

            def simulate_readonly_parent(*args, **kwargs):
                if kwargs.get("dir") == shared.parent:
                    raise PermissionError(errno.EACCES, "parent is read-only")
                return real_temporary_directory(*args, **kwargs)

            output = io.StringIO()
            with mock.patch.object(
                tempfile, "TemporaryDirectory", side_effect=simulate_readonly_parent,
            ), redirect_stdout(output):
                with _create_suite_gradle_home(shared) as isolated:
                    self.assertEqual(shared, Path(isolated).parent)
            self.assertIn(
                "placement=inside-shared fallback=sibling:PermissionError", output.getvalue(),
            )

    def test_gradle_honors_private_cleanup_policy(self):
        # A real Gradle settings evaluation proves init.d ran before the build.
        with tempfile.TemporaryDirectory(prefix="gradle-cleanup-probe-") as directory:
            project = Path(directory)
            (project / "settings.gradle").write_text(
                "rootProject.name = 'cache-cleanup-probe'\n", encoding="utf-8",
            )
            (project / "build.gradle").write_text("", encoding="utf-8")
            result = run_logged_gradle(
                [str(ROOT / "gradlew"), "--daemon", "--offline",
                 "--console=plain", "-Darchitecture.cacheCleanupProbe=true", "help"],
                cwd=project, scenario="Gradle User Home cleanup policy",
                phase="cache-policy", sequence=1, timeout=180,
            )
            self.assertEqual(0, result.returncode)
            self.assertIn("Architecture suite cache cleanup disabled", result.stdout)

    def test_daemon_stop_targets_only_private_suite_registry(self):
        with tempfile.TemporaryDirectory(prefix="architecture-daemon-unit-") as directory:
            test_home = mock.Mock()
            test_home.name = directory
            command_result = subprocess.CompletedProcess(
                [str(ROOT / "gradlew"), "--stop"], 0, "Stopped", ""
            )
            with mock.patch.dict(globals(), {"_SUITE_GRADLE_HOME": test_home}):
                with mock.patch.object(subprocess, "run", return_value=command_result) as invoke:
                    _stop_suite_daemons("unit timeout")
            invoke.assert_called_once()
            args, kwargs = invoke.call_args
            self.assertEqual([str(ROOT / "gradlew"), "--quiet", "--stop"], args[0])
            self.assertEqual(directory, kwargs["env"]["GRADLE_USER_HOME"])
            self.assertEqual(_DAEMON_STOP_TIMEOUT_S, kwargs["timeout"])
            self.assertFalse(kwargs["check"])

    @unittest.skipUnless(os.name == "posix", "POSIX process-group timeout regression")
    def test_gradle_timeout_stops_detached_daemon_via_private_registry(self):
        with tempfile.TemporaryDirectory(prefix="gradle-timeout-unit-") as directory:
            executable = Path(directory) / "gradlew"
            executable.write_text(
                "#!/bin/sh\nprintf 'synthetic fixture\\n'\nsleep 60\n",
                encoding="utf-8",
            )
            executable.chmod(0o755)
            output = io.StringIO()
            # One private home substitution; no redundant nested mock.patch.
            with tempfile.TemporaryDirectory(prefix="gradle-timeout-home-") as home_path:
                home = mock.Mock()
                home.name = home_path
                with mock.patch.dict(globals(), {"_SUITE_GRADLE_HOME": home}):
                    with mock.patch.object(
                        sys.modules[__name__], "_stop_suite_daemons"
                    ) as stop:
                        with redirect_stdout(output):
                            with self.assertRaisesRegex(AssertionError, "timed out"):
                                run_logged_gradle(
                                    [str(executable), "--daemon"],
                                    cwd=Path(directory),
                                    scenario="simulated detached daemon timeout",
                                    phase="cleanup", sequence=1, timeout=0.5,
                                )
                    stop.assert_called_once()
                    self.assertIn("timed out", stop.call_args.args[0])

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

    def test_invalid_utf8_is_replaced_without_stopping_reader(self):
        log = io.StringIO()
        with redirect_stdout(log):
            result = run_logged_gradle(
                [
                    sys.executable, "-u", "-c",
                    "import sys; sys.stdout.buffer.write(b'bad: \\xff\\n'); "
                    "sys.stdout.buffer.flush()",
                ],
                cwd=ROOT, scenario="invalid UTF-8",
                phase="warmup", sequence=4, timeout=10,
            )
        self.assertEqual(0, result.returncode)
        self.assertIn("bad: \ufffd", result.stdout)
        self.assertIn("status=passed", log.getvalue())

    def test_spawn_failure_has_end_marker(self):
        with tempfile.TemporaryDirectory(prefix="missing-gradle-") as directory:
            missing = str(Path(directory) / "does-not-exist")
            log = io.StringIO()
            with redirect_stdout(log):
                with self.assertRaises(OSError):
                    run_logged_gradle(
                        [missing],
                        cwd=ROOT, scenario="spawn failure",
                        phase="cold-warmup", sequence=5, timeout=10,
                    )
            self.assertIn("START", log.getvalue())
            self.assertIn("END status=spawn-failed exit=n/a", log.getvalue())

    @unittest.skipUnless(sys.platform.startswith("linux"), "Linux process groups")
    def test_descendant_holding_stdout_is_terminated_after_parent_exit(self):
        # Parent exits immediately, but its child inherits the stdout pipe.
        # The relay must close only after cleaning up the entire process group.
        script = (
            "import subprocess, sys\n"
            "child = subprocess.Popen([sys.executable, '-u', '-c', "
            "'import time; time.sleep(60)'])\n"
            "print(f'grandchild_pid={child.pid}', flush=True)\n"
        )
        log = io.StringIO()
        with redirect_stdout(log):
            result = run_logged_gradle(
                [sys.executable, "-u", "-c", script],
                cwd=ROOT, scenario="descendant keeps stdout",
                phase="cleanup", sequence=6, timeout=30,
            )
        self.assertEqual(0, result.returncode)
        self.assertIn("END status=passed", log.getvalue())
        # The grandchild sleeps for 60 seconds with stdout inherited.
        # Successful EOF/return before the 30-second runner deadline proves
        # that process-tree cleanup released the inherited pipe.
        self.assertIn("grandchild_pid=", result.stdout)

    def test_windows_cleanup_targets_process_tree(self):
        process = mock.Mock(pid=1234)
        with mock.patch.object(os, "name", "nt"), mock.patch.object(
            subprocess, "run"
        ) as command:
            command.return_value.returncode = 0
            _stop_gradle_tree(process, force=True)
        command.assert_called_once_with(
            ["taskkill", "/PID", "1234", "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=10,
            check=False,
        )
        process.kill.assert_not_called()

    def test_windows_job_terminates_orphans_after_parent_exit(self):
        process = mock.Mock(pid=1234)
        process.poll.return_value = 0
        job = mock.Mock()
        job.terminate.return_value = True
        with mock.patch.object(os, "name", "nt"):
            with mock.patch.object(subprocess, "run") as command:
                _stop_gradle_tree(process, force=True, windows_job=job)
        job.terminate.assert_called_once_with()
        command.assert_not_called()

    def test_windows_cleanup_falls_back_if_taskkill_fails(self):
        process = mock.Mock(pid=1234)
        process.poll.return_value = None
        with mock.patch.object(os, "name", "nt"), mock.patch.object(
            subprocess, "run"
        ) as command:
            command.return_value.returncode = 1
            _stop_gradle_tree(process, force=True)
        process.kill.assert_called_once_with()

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

            class NotifyingOutput(io.StringIO):
                def __init__(self) -> None:
                    super().__init__()
                    self.first_line = threading.Event()

                def write(self, value: str) -> int:
                    count = super().write(value)
                    if "first line" in value:
                        self.first_line.set()
                    return count

            log = NotifyingOutput()
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
                            timeout=45,
                        )
                    )
                except BaseException as error:
                    failures.append(error)

            with redirect_stdout(log):
                worker = threading.Thread(target=invoke)
                worker.start()
                try:
                    self.assertTrue(
                        log.first_line.wait(timeout=30),
                        "No live output from the subprocess",
                    )
                    self.assertTrue(worker.is_alive(), "Child exited before release")
                finally:
                    release.touch()
                    worker.join(timeout=30)
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
                    phase="invalidated", sequence=2, timeout=5,
                )
        self.assertIn("settings.gradle.kts", str(error.exception))
        self.assertIn("diagnostic", str(error.exception))
        self.assertIn("END status=timeout", log.getvalue())


if __name__ == "__main__":
    unittest.main()
