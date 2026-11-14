"""Installed, deterministic offline writable CLI acceptance. No model/network calls."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
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

    def assert_denial(self, result, code):
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout),
                         {"outcome": "denied", "code": code})
        self.assertEqual(self.original_head, self.git("rev-parse", "HEAD"))

    def test_missing_approvals_reports_required_flag(self):
        self.fixture()
        result = self.invoke("--enable-stage-writes")
        self.assertEqual(result.returncode, 2)
        self.assertIn("--approvals is required", result.stderr)
        self.assertEqual(self.original_head, self.git("rev-parse", "HEAD"))

    def test_missing_or_invalid_isolation_marker(self):
        for value in (None, "bad-marker\\n"):
            with self.subTest(value=value):
                self.fixture()
                marker = self.repo / ".git/recursive-coder-isolated-workflow"
                if value is None:
                    marker.unlink()
                else:
                    marker.write_text(value)
                self.assert_denial(self.authorized(), "MARKER_MISSING")
                marker.write_text("isolated-workflow-v1\\n")

    def test_plan_via_symlinked_parent_into_checkout_is_rejected(self):
        self.fixture()
        inside = self.repo / "operator"
        inside.mkdir()
        (inside / "plan.json").write_bytes(self.plan_path.read_bytes())
        alias = self.base / "plan-alias"
        alias.symlink_to(self.repo, target_is_directory=True)
        self.plan_path = alias / "operator/plan.json"
        self.assert_denial(self.authorized(), "CONFIG_INSIDE_CHECKOUT")

    def test_approvals_via_symlinked_parent_into_checkout_are_rejected(self):
        self.fixture()
        inside = self.repo / "operator"
        inside.mkdir()
        (inside / "approvals.json").write_bytes(self.approval_path.read_bytes())
        alias = self.base / "approvals-alias"
        alias.symlink_to(self.repo, target_is_directory=True)
        self.approval_path = alias / "operator/approvals.json"
        self.assert_denial(self.authorized(), "CONFIG_INSIDE_CHECKOUT")

    def test_new_lock_path_via_symlinked_parent_into_checkout_is_rejected(self):
        plan, approval = self.fixture()
        inside = self.repo / "operator"
        inside.mkdir()
        alias = self.base / "locks-alias"
        alias.symlink_to(inside, target_is_directory=True)
        plan["workspace"]["lockDirectory"] = str(alias / "new-locks")
        self.plan_path.write_text(json.dumps(plan))
        approval["planSha256"] = sha(self.plan_path.read_bytes())
        self.approval_path.write_text(json.dumps(approval))
        self.assert_denial(self.authorized(), "LOCK_OVERLAPS_CHECKOUT")

    def test_cwd_below_checkout_is_rejected(self):
        self.fixture()
        result = subprocess.run(
            [str(CLI), "workflow", str(self.plan_path),
             "--approvals", str(self.approval_path), "--enable-stage-writes"],
            cwd=self.repo / "src/main", text=True, capture_output=True, timeout=20
        )
        self.assert_denial(result, "CWD_INSIDE_CHECKOUT")

    def test_head_and_branch_rejected_with_git_baseline_codes(self):
        for field, value, code in (
            ("branch", "another-branch", "STAGE_BRANCH_CHANGED"),
            ("headSha", "0" * 40, "STAGE_BASELINE_CHANGED"),
            ("branch", "../bad-ref", "INVALID_GIT_BASELINE"),
        ):
            with self.subTest(field=field, value=value):
                plan, approval = self.fixture()
                plan["workspace"][field] = value
                self.plan_path.write_text(json.dumps(plan))
                approval["planSha256"] = sha(self.plan_path.read_bytes())
                self.approval_path.write_text(json.dumps(approval))
                self.assert_denial(self.authorized(), code)

    def test_per_artifact_digest_mismatch_is_not_approved(self):
        _, approval = self.fixture()
        approval["approvedSha256"]["SDD"] = "0" * 64
        self.approval_path.write_text(json.dumps(approval))
        result = self.authorized()
        # The SDD write precedes the host's digest rejection. The host may
        # have uncommitted bytes, so denying it as safely stopped is incorrect.
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout),
                         {"outcome": "reconciliation_required",
                          "code": "HOST_STOP_SNAPSHOT_UNAVAILABLE"})
        self.assertEqual(self.original_head, self.git("rev-parse", "HEAD"))

    def test_true_executable_is_not_meaningful_check(self):
        plan, approval = self.fixture()
        plan["check"] = {"executable": "/usr/bin/true", "arguments": []}
        self.plan_path.write_text(json.dumps(plan))
        approval["planSha256"] = sha(self.plan_path.read_bytes())
        self.approval_path.write_text(json.dumps(approval))
        self.assert_denial(self.authorized(), "INVALID_WORKFLOW_CONFIGURATION")

    def test_needs_human_reviewer_does_not_claim_success(self):
        self.fixture(verdict="NEEDS_HUMAN")
        result = self.authorized()
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout),
                         {"outcome": "stopped", "code": "REVIEWER_NEEDS_HUMAN"})
        self.assertEqual("", self.git("status", "--porcelain=v1"))

    def test_post_write_failed_check_returns_reconciliation_required(self):
        plan, approval = self.fixture()
        # SDD, ATDD and TDD see OLD and pass. Developer writes NEW first;
        # the same check then fails and the host must not claim safe denial.
        plan["check"]["arguments"] = [
            "-q", "fun featureFull() = 1", "src/main/FeatureFull.kt"
        ]
        self.plan_path.write_text(json.dumps(plan))
        approval["planSha256"] = sha(self.plan_path.read_bytes())
        self.approval_path.write_text(json.dumps(approval))
        result = self.authorized()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        response = json.loads(result.stdout)
        self.assertEqual(response["outcome"], "reconciliation_required")
        self.assertNotEqual(response["code"], "SUCCEEDED")
        # SDD, ATDD and TDD are already committed clean Git baselines.
        # The failed Developer check leaves its write for reconciliation.
        self.assertNotEqual(self.original_head, self.git("rev-parse", "HEAD"))
        self.assertEqual(NEW, (self.repo / "src/main/FeatureFull.kt").read_text())


    def test_opt_in_session_events_are_correlated_and_redacted(self):
        plan, approvals = self.fixture()
        journal_path = self.base / "workflow-events.jsonl"
        plan["eventsPath"] = str(journal_path)
        self.plan_path.write_text(json.dumps(plan))
        approvals["planSha256"] = sha(self.plan_path.read_bytes())
        self.approval_path.write_text(json.dumps(approvals))
        result = self.authorized()
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        records = [json.loads(line) for line in journal_path.read_text().splitlines()]
        self.assertGreaterEqual(len(records), 10)
        self.assertEqual(list(range(1, len(records) + 1)),
                         [record["sequence"] for record in records])
        for index, record in enumerate(records):
            self.assertEqual(record["sessionId"], "installed-fixture")
            self.assertEqual(record["version"], "1.0")
            self.assertEqual(record["redaction"]["mode"], "metadata-only")
            self.assertEqual(record["causationId"],
                             None if index == 0 else records[index - 1]["eventId"])
            self.assertNotIn("Perform SDD", json.dumps(record))
            self.assertNotIn("rawToolOutput", record.get("metadata", {}))
        roles = {r["metadata"]["role"] for r in records
                 if "roleEvent" in r.get("metadata", {})}
        self.assertEqual(roles, {"SDD", "ATDD", "TDD", "DEVELOPER", "REVIEWER"})
        self.assertTrue(any(r["metadata"].get("verdict") == "ACCEPT"
                            for r in records))

    def with_journal(self, events_path, *, verdict="ACCEPT"):
        plan, approvals = self.fixture(verdict=verdict)
        plan["eventsPath"] = str(events_path)
        self.plan_path.write_text(json.dumps(plan))
        approvals["planSha256"] = sha(self.plan_path.read_bytes())
        self.approval_path.write_text(json.dumps(approvals))
        return events_path

    def test_journal_open_failure_reports_unavailable_before_any_write(self):
        path = self.with_journal(self.base / "missing-parent" / "events.jsonl")
        result = self.authorized()
        self.assert_denial(result, "EVENT_JOURNAL_UNAVAILABLE")
        self.assertFalse(path.exists())
        self.assertFalse((self.repo / "specs/FeatureFull.md").exists())
        self.assertEqual("", self.git("status", "--porcelain=v1"))

    def test_event_journal_path_denials_are_typed_and_precede_stage_writes(self):
        for path in (
            "relative-events.jsonl",
            str(self.repo / "events.jsonl"),
            str(self.base / "locks" / "events.jsonl"),
            str(self.base),  # parent of the lock scope
            "bad" + chr(0) + "events.jsonl",  # Path.of throws InvalidPathException
        ):
            with self.subTest(path=repr(path)):
                self.with_journal(path)
                result = self.authorized()
                self.assert_denial(result, "INVALID_EVENT_JOURNAL_PATH")
                self.assertFalse((self.repo / "specs/FeatureFull.md").exists())
                self.assertEqual("", self.git("status", "--porcelain=v1"))

    def test_lost_journal_after_started_events_requires_reconciliation(self):
        journal = self.with_journal(self.base / "lost-events.jsonl")
        self.assertTrue(CLI.is_file(), "Run installDist before installed acceptance")
        proc = subprocess.Popen(
            [str(CLI), "workflow", str(self.plan_path),
             "--approvals", str(self.approval_path), "--enable-stage-writes"],
            cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        try:
            # Wait for an acknowledged event before removing the journal pathname.
            # Linux keeps the writer's open fd valid, but finish() must fail
            # to verify the absent on-disk JSONL and return reconciliation_required.
            deadline = time.monotonic() + 50
            while time.monotonic() < deadline:
                if journal.exists() and journal.stat().st_size > 0:
                    break
                if proc.poll() is not None:
                    self.fail("Workflow completed before journal was observable")
                time.sleep(0.01)
            else:
                self.fail("Workflow did not create a journal with acknowledged events")
            journal.unlink()
            stdout, stderr = proc.communicate(timeout=150)
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.communicate(timeout=20)
        self.assertEqual(proc.returncode, 1, stdout + stderr)
        self.assertEqual(json.loads(stdout),
                         {"outcome": "reconciliation_required",
                          "code": "EVENT_JOURNAL_UNCONFIRMED"})
        self.assertFalse(journal.exists())
        self.assertNotEqual(self.original_head, self.git("rev-parse", "HEAD"))
        self.assertEqual(NEW, (self.repo / "src/main/FeatureFull.kt").read_text())

    def test_existing_event_journal_is_rejected_before_writing(self):
        plan, approvals = self.fixture()
        journal_path = self.base / "workflow-events.jsonl"
        journal_path.write_text("untouched\n")
        plan["eventsPath"] = str(journal_path)
        self.plan_path.write_text(json.dumps(plan))
        approvals["planSha256"] = sha(self.plan_path.read_bytes())
        self.approval_path.write_text(json.dumps(approvals))
        result = self.authorized()
        self.assert_denial(result, "INVALID_EVENT_JOURNAL_PATH")
        self.assertEqual(journal_path.read_text(), "untouched\n")


if __name__ == "__main__":
    unittest.main()
