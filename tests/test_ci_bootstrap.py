"""Offline fail-closed regressions for issue #144 CI supply-chain trust."""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import check_ci_bootstrap as bootstrap


class CiBootstrapTrustTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

    def test_reviewed_action_pins_and_six_ci_jobs(self):
        bootstrap.audit_repository()

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

    def test_removed_gradle_bootstrap_rejected(self):
        mutated = self.workflow.replace(
            "python3 -I tools/check_ci_bootstrap.py --install-gradle", "true", 1,
        )
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "authenticated Gradle"):
            bootstrap.audit_workflow(mutated)

    def test_reviewed_official_gradle_checksum(self):
        self.assertEqual(bootstrap.VERSION, "9.8.1")
        self.assertEqual(
            bootstrap.GRADLE_SHA256,
            "dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f",
        )
        self.assertEqual(
            bootstrap.GRADLE_URL,
            "https://services.gradle.org/distributions/gradle-9.8.1-bin.zip",
        )

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
            fake_gradle.write_text("#!/bin/sh\\nexit 0\\n", encoding="utf-8")
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

    def test_ci_launcher_rejects_unverified_absolute_path(self):
        env = os.environ.copy()
        env["GITHUB_ACTIONS"] = "true"
        env.pop("CI", None)
        env["RECURSIVE_VERIFIED_GRADLE"] = "/not-a-reviewed-gradle"
        result = subprocess.run(
            ["/bin/sh", str(ROOT / "gradlew"), "--version"],
            env=env, capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 127)

    def test_missing_gradle_archive_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(FileNotFoundError):
                bootstrap.require_archive_sha256(Path(tmp) / "missing.zip")


if __name__ == "__main__":
    unittest.main()
