"""Hermetic real Gradle classpath failure; no external registry is configured."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import retry_gradle_plugins as retry


class RegistryFailureFixture(unittest.TestCase):
    def test_real_gradle_429_output(self):
        gradle = os.environ.get("RECURSIVE_VERIFIED_GRADLE")
        self.assertTrue(gradle and Path(gradle).is_file(),
                        "Run with the SHA-256-verified CI Gradle executable")
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def reject(self):
                requests.append(self.path)
                self.send_response(429, "Too Many Requests")
                self.send_header("Content-Length", "0")
                self.end_headers()

            do_GET = reject
            do_HEAD = reject

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    url = f"http://127.0.0.1:{server.server_port}"
                    (root / "settings.gradle").write_text(
                        "pluginManagement { includeBuild('build-logic') }\n"
                        "rootProject.name = 'registry-fixture'\n", encoding="utf-8")
                    (root / "build.gradle").write_text(
                        "plugins { id 'fixture.convention' }\n", encoding="utf-8")
                    included = root / "build-logic"
                    included.mkdir()
                    (included / "settings.gradle").write_text(
                        "rootProject.name = 'build-logic'\n", encoding="utf-8")
                    (included / "build.gradle").write_text(
                        "buildscript {\n"
                        "  repositories { maven {\n"
                        "    name = 'Fixture Registry'\n"
                        f"    url = uri('{url}')\n"
                        "    allowInsecureProtocol = true\n"
                        "  } }\n"
                        "  dependencies {\n"
                        + "".join(f"    classpath 'fixture:absent-plugin-{n}:1.0'\n"
                                  for n in range(6))
                        + "  }\n"
                        "}\n"
                        "plugins { id 'java-gradle-plugin' }\n"
                        "gradlePlugin { plugins { convention {\n"
                        "  id = 'fixture.convention'\n"
                        "  implementationClass = 'fixture.ConventionPlugin'\n"
                        "} } }\n", encoding="utf-8")
                    result = subprocess.run(
                        [gradle, "--dependency-verification", "strict",
                         "--no-daemon", "--no-build-cache", "--max-workers=1",
                         "-Dorg.gradle.jvmargs=-Xmx1g", "--console=plain",
                         "--gradle-user-home", str(root / "home"), "help"],
                        cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, errors="replace", timeout=180, check=False,
                    )
            finally:
                server.shutdown()
                thread.join()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertTrue(requests, result.stdout)
        self.assertIn("Received status code 429", result.stdout)
        self.assertIn("Error resolving plugin [id: 'fixture.convention']", result.stdout)
        self.assertIn("A problem occurred configuring project ':build-logic'", result.stdout)
        # Gradle can emit separate sibling resolution failures instead of a
        # disabled-repository parent and identical-cause summary (incident #213).
        for n in range(6):
            self.assertIn(f"Could not resolve fixture:absent-plugin-{n}:1.0.", result.stdout)
        self.assertIn("Required by:", result.stdout)
        self.assertFalse(retry.transient_failure(result.stdout), result.stdout)
        normalized = result.stdout.replace(url + "/", "https://repo.maven.apache.org/")
        self.assertNotEqual(normalized, result.stdout)
        self.assertTrue(retry.transient_failure(normalized), result.stdout)
