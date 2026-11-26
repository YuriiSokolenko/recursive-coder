import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import run_trusted_review as trusted_review


class TrustedReviewEvidenceTest(unittest.TestCase):
    def test_exact_revision_and_pr_metadata(self):
        for invalid in ("dev", "a" * 39, "A" * 40, "a" * 40 + "\n"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                trusted_review.sha(invalid)
        base, head = "a" * 40, "b" * 40
        pr = {"state": "open", "base": {"ref": "dev", "sha": base,
              "repo": {"full_name": trusted_review.REPO}},
              "head": {"ref": "feature", "sha": head,
                       "repo": {"full_name": "fork/example"}}}
        with mock.patch.object(trusted_review, "api", return_value=pr):
            self.assertEqual(trusted_review.pr_pair("1", base, head), "dev")
            with self.assertRaises(ValueError):
                trusted_review.pr_pair("1", head, base)
            pr["base"]["ref"] = "main"
            with self.assertRaises(ValueError):
                trusted_review.pr_pair("1", base, head)

    def exercise(self, *, missing=False, wrong_head=False, helper_failure=False,
                 candidate=False, stale=False):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "source"
            source.mkdir()
            real_run = subprocess.run

            def git(*args):
                return real_run(["git", "-C", str(source), *args],
                                check=True, capture_output=True).stdout.decode().strip()

            git("init", "-b", "dev")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Fixture")
            for name, path in trusted_review.HELPERS:
                if missing and name == "c3":
                    continue
                dest = source / path
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text("import os, sys\n"
                                "assert 'REVIEW_TOKEN' not in os.environ\n"
                                "print('trusted base helper')\n"
                                + ("sys.exit(1)\n" if helper_failure and name == "c3" else ""))
            git("add", ".")
            git("commit", "-m", "trusted base")
            base = git("rev-parse", "HEAD")
            git("checkout", "-b", "candidate")
            path = source / trusted_review.HELPERS[0][1]
            path.write_text("raise RuntimeError('candidate helper must not execute')\n")
            git("add", ".")
            git("commit", "-m", "candidate")
            head = git("rev-parse", "HEAD")
            git("update-ref", "refs/pull/1/head", head)

            def local_run(args, **kwargs):
                # The production runner forbids file transports. Only this
                # hermetic fixture substitutes the local Git remote.
                args = ["protocol.file.allow=always" if a == "protocol.file.allow=never"
                        else a for a in args]
                return real_run(args, **kwargs)

            pr = {"state": "open", "base": {"ref": "dev", "sha": base,
                  "repo": {"full_name": trusted_review.REPO}},
                  "head": {"ref": "candidate", "sha": "0" * 40 if wrong_head else head,
                           "repo": {"full_name": trusted_review.REPO}}}
            calls = 0

            def local_api(path):
                nonlocal calls
                if path.startswith("/branches/"):
                    return {"protected": True}
                self.assertFalse(candidate, "Candidate mode must not query a PR")
                calls += 1
                if stale and calls == 2:
                    return {**pr, "head": {**pr["head"], "sha": "0" * 40}}
                return pr

            output = io.StringIO()
            with mock.patch.object(trusted_review, "REMOTE", str(source)), \
                    mock.patch.object(trusted_review, "api", side_effect=local_api), \
                    mock.patch.object(trusted_review.subprocess, "run", side_effect=local_run), \
                    mock.patch.dict(os.environ, {"REVIEW_TOKEN": "not-for-helpers"}), \
                    contextlib.redirect_stdout(output):
                if missing or wrong_head or helper_failure or stale:
                    with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                        trusted_review.collect(base, pr["head"]["sha"], "0" if candidate else "1", base)
                else:
                    trusted_review.collect(base, head, "0" if candidate else "1", base)
            return [json.loads(line) for line in output.getvalue().splitlines()]

    def test_base_only_execution_and_full_evidence(self):
        rows = self.exercise()
        self.assertEqual([row.get("helper") for row in rows[1:-1]],
                         ["policy", "c3", "vocabulary"])
        for row in rows[1:-1]:
            self.assertEqual(row["exit"], 0)
            self.assertEqual(row["stdout"], "trusted base helper\n")
            self.assertRegex(row["sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual(rows[-1]["result"], "complete")

    def test_missing_helper_wrong_ref_and_failed_helper_refuse(self):
        for kwargs in ({"missing": True}, {"wrong_head": True}, {"helper_failure": True}):
            with self.subTest(kwargs=kwargs):
                rows = self.exercise(**kwargs)
                self.assertFalse(any(row.get("result") == "complete" for row in rows))

    def test_candidate_mode_uses_exact_sha_without_pr_metadata(self):
        rows = self.exercise(candidate=True)
        self.assertEqual(rows[0]["mode"], "candidate")
        self.assertEqual(rows[0]["pr"], "0")
        self.assertEqual(rows[-1]["result"], "complete")

    def test_pr_movement_invalidates_evidence(self):
        rows = self.exercise(stale=True)
        self.assertEqual(sum("helper" in row for row in rows), 3)
        self.assertFalse(any(row.get("result") == "complete" for row in rows))

    def test_wrong_workflow_ref_rejected(self):
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REPOSITORY": trusted_review.REPO,
                "GITHUB_REF": "refs/heads/candidate"}, clear=True):
            with self.assertRaises(ValueError):
                trusted_review.main()
