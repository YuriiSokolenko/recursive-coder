"""Offline fail-closed regressions for issue #144 CI supply-chain trust."""
from __future__ import annotations

import hashlib
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import check_ci_bootstrap as bootstrap


class CiBootstrapTrustTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

    def test_reviewed_action_pins_and_six_ci_jobs(self):
        bootstrap.audit_repository()

    def test_gradlew_without_tracked_executable_bit_rejected(self):
        tracked_sha = "a" * 40
        for entry in (
            f"100644 {tracked_sha} 0\tgradlew\n",
            "",
            f"100755 {tracked_sha} 0\tgradlew\n100755 {tracked_sha} 0\tother\n",
        ):
            with self.subTest(index_entry=entry), mock.patch.object(
                bootstrap.subprocess, "run",
                return_value=subprocess.CompletedProcess(
                    args=["git", "ls-files"], returncode=0, stdout=entry,
                ),
            ) as check_git:
                with self.assertRaisesRegex(AssertionError, "Git mode 100755"):
                    bootstrap.audit_gradlew_executable_mode()
                check_git.assert_called_once()

    def test_gradlew_with_tracked_executable_bit_accepted(self):
        with mock.patch.object(
            bootstrap.subprocess, "run",
            return_value=subprocess.CompletedProcess(
                args=["git", "ls-files"], returncode=0,
                stdout="100755 " + "a" * 40 + " 0\tgradlew\n",
            ),
        ):
            bootstrap.audit_gradlew_executable_mode()

    def test_changed_action_commit_rejected(self):
        mutated = self.workflow.replace(
            "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1",
            "actions/checkout@" + "0" * 40, 1,
        )
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "Changed GitHub Actions pin"):
            bootstrap.audit_workflow(mutated)

    def test_mutable_action_tag_rejected(self):
        mutated = self.workflow.replace(
            "actions/setup-java@d1374f8ac74474200d16ef8ecee22c7ae7a51880 # v6",
            "actions/setup-java@v6", 1,
        )
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "Unpinned"):
            bootstrap.audit_workflow(mutated)

    def test_unknown_action_rejected(self):
        mutated = self.workflow.replace(
            "      - name: Set up Node.js 22",
            "      - uses: unknown/action@" + "0" * 40 + " # v1\n"
            "      - name: Set up Node.js 22", 1,
        )
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "Unreviewed"):
            bootstrap.audit_workflow(mutated)

    def test_checkout_without_disabled_credentials_rejected(self):
        mutated = self.workflow.replace(
            "          persist-credentials: false\n",
            "",
            1,
        )
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "persist-credentials: false"):
            bootstrap.audit_workflow(mutated)

    def test_checkout_with_credential_persistence_enabled_rejected(self):
        mutated = self.workflow.replace(
            "          persist-credentials: false",
            "          persist-credentials: true", 1,
        )
        with self.assertRaisesRegex(AssertionError, "persist-credentials: false"):
            bootstrap.audit_workflow(mutated)

    def test_flow_style_action_reference_rejected(self):
        mutated = self.workflow.replace(
            "      - name: Set up Node.js 22",
            "      - {uses: unknown/action@v1}\n      - name: Set up Node.js 22", 1,
        )
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "Unreviewed GitHub Actions uses syntax"):
            bootstrap.audit_workflow(mutated)

    def test_removed_gradle_bootstrap_rejected(self):
        mutated = self.workflow.replace(
            "python3 -I tools/check_ci_bootstrap.py --install-gradle", "true", 1,
        )
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "authenticated Gradle"):
            bootstrap.audit_workflow(mutated)

    def test_reviewed_official_gradle_checksum(self):
        self.assertEqual(bootstrap.VERSION, "9.8.2")
        self.assertEqual(
            bootstrap.GRADLE_SHA256,
            "21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607",
        )
        self.assertEqual(
            bootstrap.GRADLE_URL,
            "https://services.gradle.org/distributions/gradle-9.8.2-bin.zip",
        )

    def test_ci_jobs_and_verifier_reference_same_reviewed_gradle(self):
        self.assertEqual(
            self.workflow.count("Install SHA-256-verified Gradle 9.8.2"),
            len(bootstrap.GRADLE_JOBS),
        )
        self.assertIn("'Gradle 9.8.2'", self.workflow)
        self.assertIn("Generate trusted Gradle 9.8.2 launcher templates", self.workflow)
        self.assertEqual(
            set(re.findall(r"\bGradle (\d+\.\d+\.\d+)\b", self.workflow)),
            {bootstrap.VERSION},
        )

    def test_bad_download_is_deleted_before_extraction_or_path_publication(self):
        with tempfile.TemporaryDirectory() as tmp:
            temp = Path(tmp)
            env_file = temp / "github-env"
            path_file = temp / "github-path"

            def fake_download(argv, **kwargs):
                self.assertEqual(argv[0], "curl")
                archive = Path(argv[argv.index("--output") + 1])
                archive.write_bytes(b"malicious Gradle ZIP")
                return subprocess.CompletedProcess(argv, 0)

            with mock.patch.dict(os.environ, {
                "CI": "true",
                "RUNNER_TEMP": tmp,
                "GITHUB_ENV": str(env_file),
                "GITHUB_PATH": str(path_file),
            }), mock.patch.object(
                bootstrap.subprocess, "run", side_effect=fake_download
            ) as commands:
                with self.assertRaisesRegex(AssertionError, "SHA-256 mismatch"):
                    bootstrap.install_gradle()

            self.assertEqual(commands.call_count, 1)
            self.assertEqual(commands.call_args.args[0][0], "curl")
            self.assertEqual(list(temp.rglob("gradle-9.8.2-bin.zip")), [])
            self.assertFalse(env_file.exists())
            self.assertFalse(path_file.exists())

    def test_altered_gradle_archive_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "gradle.zip"
            archive.write_bytes(b"tampered Gradle distribution")
            with self.assertRaisesRegex(AssertionError, "SHA-256 mismatch"):
                bootstrap.require_archive_sha256(archive)

    def test_altered_gradle_checksum_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "archive.zip"
            archive.write_bytes(b"known local fixture")
            correct = hashlib.sha256(archive.read_bytes()).hexdigest()
            bootstrap.require_archive_sha256(archive, expected=correct)
            with self.assertRaisesRegex(AssertionError, "SHA-256 mismatch"):
                bootstrap.require_archive_sha256(archive, expected="0" * 64)

    def test_ci_launcher_rejects_local_gradle_path_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake_gradle = Path(tmp) / "gradle"
            fake_gradle.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            fake_gradle.chmod(0o755)
            env = os.environ.copy()
            env["CI"] = "true"
            env["GITHUB_ACTIONS"] = "true"
            env["PATH"] = f"{tmp}:{env.get('PATH', '/usr/bin:/bin')}"
            env.pop("RECURSIVE_VERIFIED_GRADLE", None)
            result = subprocess.run(
                ["/bin/sh", str(ROOT / "gradlew"), "--version"],
                env=env, capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 127)
            self.assertIn("refusing PATH fallback", result.stderr)

    def test_ci_launcher_rejects_missing_executable(self):
        env = os.environ.copy()
        env["GITHUB_ACTIONS"] = "true"
        env.pop("CI", None)
        env["RECURSIVE_VERIFIED_GRADLE"] = "/not-a-reviewed-gradle"
        result = subprocess.run(
            ["/bin/sh", str(ROOT / "gradlew"), "--version"],
            env=env, capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 127)

    def test_ci_false_uses_local_launcher_without_verified_gradle(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake_gradle = Path(tmp) / "gradle"
            fake_gradle.write_text("#!/bin/sh\nprintf 'local fallback works\\n'\n", encoding="utf-8")
            fake_gradle.chmod(0o755)
            env = os.environ.copy()
            env["CI"] = "false"
            env.pop("GITHUB_ACTIONS", None)
            env.pop("RECURSIVE_VERIFIED_GRADLE", None)
            env["PATH"] = f"{tmp}:{env.get('PATH', '/usr/bin:/bin')}"
            result = subprocess.run(
                ["/bin/sh", str(ROOT / "gradlew"), "--version"],
                env=env, capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("local fallback works", result.stdout)

    def test_missing_gradle_archive_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(FileNotFoundError):
                bootstrap.require_archive_sha256(Path(tmp) / "missing.zip")


if __name__ == "__main__":
    unittest.main()
