"""Retry policy regressions, including real subprocess stdout/stderr capture."""
import contextlib
import io
import signal
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import retry_gradle_plugins as retry

TRANSIENT = (
    "FAILURE: Build failed with an exception.\n"
    "\n* What went wrong:\n"
    "A problem occurred configuring root project 'fixture'.\n"
    "> Could not resolve all artifacts for configuration ':classpath'.\n"
    "   > Could not GET 'https://repo.maven.apache.org/maven2/example.pom'.\n"
    "      > Received status code 429 from server: Too Many Requests\n"
)


class GradlePluginRetryTest(unittest.TestCase):
    def test_exact_commands(self):
        self.assertEqual(retry.command(False), [
            "./gradlew", "--dependency-verification", "strict", "--no-daemon",
            "--no-build-cache", "--max-workers=1",
            "-Dorg.gradle.jvmargs=-Xmx1g", "--console=plain", "help",
        ])
        self.assertEqual(retry.command(True)[-3:], ["-p", "build-logic", "help"])

    def execute(self, outcomes, standalone=False):
        real_run = subprocess.run
        values = iter(outcomes)

        def child(args, **kwargs):
            expected = ["./gradlew", "--dependency-verification", "strict", "--no-daemon",
                        "--no-build-cache", "--max-workers=1",
                        "-Dorg.gradle.jvmargs=-Xmx1g", "--console=plain"]
            if standalone:
                expected += ["-p", "build-logic"]
            self.assertEqual(args, expected + ["help"])
            status, stdout, stderr = next(values)
            if status < 0:
                return real_run([sys.executable, "-c",
                                 "import os, signal; os.kill(os.getpid(), signal.SIGTERM)"],
                                **kwargs)
            return real_run(
                [sys.executable, "-c",
                 "import sys; print(sys.argv[1]); print(sys.argv[2], file=sys.stderr); "
                 "sys.exit(int(sys.argv[3]))",
                 stdout, stderr, str(status)], **kwargs,
            )

        output = io.StringIO()
        with mock.patch.object(retry.subprocess, "run", side_effect=child) as run, \
                mock.patch.object(retry.time, "sleep") as sleep, \
                contextlib.redirect_stdout(output):
            result = retry.run_preflight(standalone)
        return result, run.call_count, sleep.call_args_list, output.getvalue()

    def test_transient_then_success(self):
        code, calls, delays, output = self.execute([
            (1, "", TRANSIENT), (0, "BUILD SUCCESSFUL", ""),
        ])
        self.assertEqual((code, calls, delays), (0, 2, [mock.call(60)]))
        self.assertIn("Too Many Requests", output)
        self.assertIn("BUILD SUCCESSFUL", output)

    def test_transient_exhaustion(self):
        code, calls, delays, _ = self.execute([(1, TRANSIENT, "")] * 3)
        self.assertEqual((code, calls, delays), (1, 3, [mock.call(60), mock.call(180)]))

    def test_no_retry_for_non_transient_or_verification_failure(self):
        for failure in (
            "unknown failure",
            TRANSIENT + "Dependency verification failed\n",
            TRANSIENT.replace("429", "403"),
            TRANSIENT.replace("repo.maven.apache.org", "unapproved.example"),
            TRANSIENT + "Received status code 404\n",
            TRANSIENT + "FAILURE: Another failure\n",
            TRANSIENT + "   > Could not resolve missing:module:1.0.\n",
            TRANSIENT + "> Compilation failed unexpectedly.\n",
            TRANSIENT.replace("A problem occurred configuring root project 'fixture'.",
                              "Execution failed for task ':compileKotlin'."),
            "Received status code 429\n",
        ):
            with self.subTest(failure=failure):
                code, calls, delays, _ = self.execute([(1, "", failure)])
                self.assertEqual((code, calls, delays), (1, 1, []))

    def test_signal_is_not_retried(self):
        code, calls, delays, output = self.execute([(-signal.SIGTERM, "", "")])
        self.assertEqual((code, calls, delays), (1, 1, []))
        self.assertIn("exit=-15", output)

    def test_standalone_runs_exact_command(self):
        code, calls, delays, _ = self.execute([
            (1, "", TRANSIENT), (0, "BUILD SUCCESSFUL", ""),
        ], standalone=True)
        self.assertEqual((code, calls, delays), (0, 2, [mock.call(60)]))

    def test_incident_213_real_log(self):
        incident = (Path(__file__).parent / "fixtures/gradle_retry/incident_213.txt").read_text()
        self.assertTrue(retry.transient_failure(incident), incident)
        code, calls, delays, output = self.execute([
            (1, "", incident), (0, "BUILD SUCCESSFUL", ""),
        ])
        self.assertEqual((code, calls, delays), (0, 2, [mock.call(60)]))
        self.assertIn("attempt 2: exit=0 elapsed=", output)
        for mutated in (
            incident.replace("status code 429", "status code 403"),
            incident.replace("status code 429", "status code 404"),
            incident.replace("repo.maven.apache.org", "unapproved.example"),
            incident.replace("> There are 4 more failures", "> Compilation failed.\n> There are 4 more failures"),
            incident.replace("There are 4 more failures with identical causes.",
                             "Dependency verification failed."),
            incident.replace("Required by:", "Unknown diagnostic:"),
            incident.replace("buildscript of project ':build-logic'\n",
                             "unexpected failure\n", 1),
        ):
            with self.subTest(mutated=mutated):
                self.assertNotEqual(mutated, incident)
                self.assertFalse(retry.transient_failure(mutated))
                self.assertEqual(self.execute([(1, "", mutated)])[:3], (1, 1, []))

    def test_full_included_build_output_from_ci(self):
        # Full real console output from run 46008211118, job 140536685099.
        captured = (Path(__file__).parent / "fixtures/gradle_retry/included_build_429.txt").read_text()
        loopback = "http://127.0.0.1:44081/"
        self.assertEqual(captured.count("Received status code 429"), 6)
        self.assertFalse(retry.transient_failure(captured), captured)
        normalized = captured.replace(loopback, "https://repo.maven.apache.org/")
        self.assertNotEqual(normalized, captured)
        self.assertTrue(retry.transient_failure(normalized), normalized)
        self.assertEqual(self.execute([(1, "", normalized), (0, "BUILD SUCCESSFUL", "")])[:3],
                         (0, 2, [mock.call(60)]))
        for mixed in (
            normalized.replace("Received status code 429", "Received status code 403", 1),
            normalized.replace("Could not GET 'https://repo.maven.apache.org/",
                               "Could not GET 'https://unapproved.example/", 1),
            normalized.replace("BUILD FAILED in 7s", "Dependency verification failed"),
        ):
            with self.subTest(mixed=mixed):
                self.assertNotEqual(mixed, normalized)
                self.assertFalse(retry.transient_failure(mixed), mixed)

    def test_inline_registry_status(self):
        inline = TRANSIENT.replace(".\n      > Received", ". Received")
        self.assertNotEqual(inline, TRANSIENT)
        self.assertTrue(retry.transient_failure(inline))
        self.assertFalse(retry.transient_failure(inline.replace("429", "401")))
        self.assertFalse(retry.transient_failure(
            inline.replace("repo.maven.apache.org", "repo.maven.apache.org.example.org")))

    def test_recognized_5xx(self):
        self.assertTrue(retry.transient_failure(TRANSIENT.replace("429", "503")))
