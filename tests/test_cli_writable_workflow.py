"""Installed, deterministic offline writable CLI acceptance. No model/network calls."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "build/install/recursive-coder/bin/recursive-coder"
OLD = "fun featureFull() = 1\n"
NEW = "fun featureFull() = 2\n"
SPEC = "Requirement: positive CLI integration\n"
ATDD = "Scenario: positive CLI integration\n"
TDD = "fun testHost() = Unit\n"


def sha(data):
    if isinstance(data, str):
        data = data.encode("utf-8")
    return hashlib.sha256(data).hexdigest()


class InstalledWritableWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="writable-cli-")
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.repo = self.base / "checkout"
        self.repo.mkdir()
        self.git("init", "-b", "dev")
        self.git("config", "user.name", "CLI Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        for directory in ("src/main", "specs", "tests/acceptance", "tests/unit"):
            (self.repo / directory).mkdir(parents=True)
        (self.repo / "README.md").write_text("Isolated test checkout\n")
        (self.repo / "src/main/FeatureFull.kt").write_text(OLD)
        self.git("add", "README.md", "src/main/FeatureFull.kt")
        self.git("commit", "-m", "fixture baseline")
        (self.repo / ".git/recursive-coder-isolated-workflow").write_text(
            "isolated-workflow-v1\n"
        )
        self.original_head = self.git("rev-parse", "HEAD")
        self.plan_path = self.base / "plan.json"
        self.approval_path = self.base / "approvals.json"

    def git(self, *args):
        env = os.environ.copy()
        env.update({
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_TERMINAL_PROMPT": "0",
        })
        process = subprocess.run(
            ["/usr/bin/git", *args], cwd=self.repo, env=env,
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20
        )
        self.assertEqual(process.returncode, 0, process.stderr)
        return process.stdout.strip()

    def fixture(self, *, verdict="ACCEPT", protected=False):
        manifest = (
            "recursive-coder implementation manifest v1\n"
            + "src/main/FeatureFull.kt\t" + sha(OLD) + "\t" + sha(NEW) + "\n"
        )
        proposal = json.dumps({
            "files": [{
                "path": ("tests/unit/FeatureFullTest.kt" if protected
                         else "src/main/FeatureFull.kt"),
                "content": NEW,
            }]
        }, separators=(",", ":"))
        plan = {
            "version": 1,
            "sessionId": "installed-fixture",
            "workspace": {
                "rootPath": str(self.repo),
                "headSha": self.original_head,
                "branch": "dev",
                "lockDirectory": str(self.base / "locks"),
            },
            "artifacts": {
                "SDD": {"id": "req-1", "path": "specs/FeatureFull.md"},
                "ATDD": {"id": "atdd-1", "path": "tests/acceptance/FeatureFull.feature"},
                "TDD": {"id": "unit-1", "path": "tests/unit/FeatureFullTest.kt"},
                "DEVELOP": {
                    "id": "impl-1",
                    "path": "src/main/FeatureFullManifest.xml",
                },
            },
            "instructions": {name: "Perform " + name for name in
                             ("SDD", "ATDD", "TDD", "DEVELOP", "REVIEW")},
            "developerTargets": [
                {"path": "src/main/FeatureFull.kt", "expectedSha256": sha(OLD)}
            ],
            "fakeProposals": {
                "SDD": SPEC, "ATDD": ATDD, "TDD": TDD, "DEVELOP": proposal,
            },
            "fakeReviewerVerdict": verdict,
            "check": {
                "executable": "/usr/bin/grep",
                "arguments": ["-q", "fun featureFull()", "src/main/FeatureFull.kt"],
            },
            "limits": {
                "maxTokens": 10000, "maxDurationMillis": 120000,
                "deadlineSeconds": 120,
            },
        }
        self.plan_path.write_text(json.dumps(plan, separators=(",", ":")))
        approvals = {
            "version": 1,
            "planSha256": sha(self.plan_path.read_bytes()),
            "approvedSha256": {
                "SDD": sha(SPEC),
                "ATDD": sha(ATDD),
                "TDD": sha(TDD),
                "DEVELOP": sha(manifest),
            },
        }
        self.approval_path.write_text(json.dumps(approvals))
        return plan, approvals

    def invoke(self, *args):
        self.assertTrue(CLI.is_file(), "Run installDist before installed acceptance")
        return subprocess.run(
            [str(CLI), "workflow", str(self.plan_path), *args],
            cwd=ROOT, text=True, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, timeout=150,
        )

    def authorized(self):
        return self.invoke("--approvals", str(self.approval_path),
                           "--enable-stage-writes")

    def test_installed_five_role_workflow_has_clean_baseline(self):
        self.fixture()
        result = self.authorized()
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertEqual(json.loads(result.stdout),
                         {"outcome": "completed", "code": "SUCCEEDED"})
        self.assertEqual((self.repo / "src/main/FeatureFull.kt").read_text(), NEW)
        self.assertEqual((self.repo / "specs/FeatureFull.md").read_text(), SPEC)
        self.assertEqual((self.repo / "tests/acceptance/FeatureFull.feature").read_text(), ATDD)
        self.assertEqual((self.repo / "tests/unit/FeatureFullTest.kt").read_text(), TDD)
        self.assertTrue((self.repo / "src/main/FeatureFullManifest.xml").is_file())
        self.assertNotEqual(self.original_head, self.git("rev-parse", "HEAD"))
        self.assertEqual("", self.git("status", "--porcelain=v1", "--ignored=matching"))

    def test_disabled_by_default_and_original_run_remains_read_only(self):
        self.fixture()
        without_optin = self.invoke("--approvals", str(self.approval_path))
        self.assertEqual(without_optin.returncode, 2)
        self.assertIn("--enable-stage-writes", without_optin.stderr)
        self.assertEqual(self.original_head, self.git("rev-parse", "HEAD"))
        self.assertEqual("", self.git("status", "--porcelain=v1"))
        old_run = subprocess.run(
            [str(CLI), "run", str(self.plan_path), "--enable-stage-writes"],
            cwd=ROOT, text=True, capture_output=True, timeout=20
        )
        self.assertEqual(old_run.returncode, 2)
        self.assertEqual(self.original_head, self.git("rev-parse", "HEAD"))

    def test_stale_approval_is_denied_without_mutation(self):
        _, approval = self.fixture()
        approval["planSha256"] = "0" * 64
        self.approval_path.write_text(json.dumps(approval))
        self.assertEqual(self.authorized().returncode, 2)
        self.assertEqual(self.original_head, self.git("rev-parse", "HEAD"))
        self.assertEqual("", self.git("status", "--porcelain=v1"))

    def test_dirty_checkout_denied_before_any_role(self):
        self.fixture()
        (self.repo / "src/main/FeatureFull.kt").write_text("tampered\n")
        result = self.authorized()
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertEqual(self.original_head, self.git("rev-parse", "HEAD"))
        self.assertFalse((self.repo / "specs/FeatureFull.md").exists())

    def test_reviewer_rejection_does_not_finish_success(self):
        self.fixture(verdict="REJECT")
        result = self.authorized()
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout)["code"], "REVIEWER_REJECTED")
        self.assertEqual("", self.git("status", "--porcelain=v1"))

    def test_developer_cannot_write_protected_tests(self):
        self.fixture(protected=True)
        result = self.authorized()
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout)["code"], "INVALID_DEVELOPER_PROPOSAL")
        self.assertEqual(TDD, (self.repo / "tests/unit/FeatureFullTest.kt").read_text())
        self.assertEqual(OLD, (self.repo / "src/main/FeatureFull.kt").read_text())
        self.assertEqual("", self.git("status", "--porcelain=v1"))


if __name__ == "__main__":
    unittest.main()
