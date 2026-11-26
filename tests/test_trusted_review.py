import contextlib
import copy
import io
import json
import os
from pathlib import Path
import runpy
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
            with self.subTest(invalid=invalid), self.assertRaisesRegex(ValueError, "Expected full lowercase commit SHA"):
                trusted_review.sha(invalid)
        base, head = "a" * 40, "b" * 40
        pr = {"state": "open", "base": {"ref": "dev", "sha": base,
              "repo": {"full_name": trusted_review.REPO}},
              "head": {"ref": "feature", "sha": head,
                       "repo": {"full_name": "fork/example"}}}
        with mock.patch.object(trusted_review, "api", return_value=pr):
            self.assertEqual(trusted_review.pr_pair("1", base, head), "dev")
            pr["base"]["ref"] = "main"
            pr["head"] = {"ref": "dev", "sha": head,
                          "repo": {"full_name": trusted_review.REPO}}
            self.assertEqual(trusted_review.pr_pair("1", base, head), "main")

    def test_each_pr_metadata_gate(self):
        base, head = "a" * 40, "b" * 40
        valid = {"state": "open", "base": {"ref": "main", "sha": base,
                 "repo": {"full_name": "YuriiSokolenko/recursive-coder"}},
                 "head": {"ref": "dev", "sha": head,
                          "repo": {"full_name": "YuriiSokolenko/recursive-coder"}}}
        for keys, value in (
            (("state",), "closed"),
            (("base", "repo", "full_name"), "fork/example"),
            (("base", "ref"), "feature"),
            (("head", "ref"), "feature"),
            (("head", "repo", "full_name"), "fork/example"),
            (("base", "sha"), "c" * 40),
            (("head", "sha"), "c" * 40),
        ):
            with self.subTest(keys=keys):
                pr = copy.deepcopy(valid)
                target = pr
                for key in keys[:-1]:
                    target = target[key]
                target[keys[-1]] = value
                with mock.patch.object(trusted_review, "api", return_value=pr):
                    with self.assertRaisesRegex(ValueError, "PR metadata differs"):
                        trusted_review.pr_pair("1", base, head)

    def test_invalid_pair_and_pr_number_stop_before_api(self):
        base, head = "a" * 40, "b" * 40
        for pair in ((base, base, "1"), (base, head, "-1"),
                     (base, head, "01"), (base, head, "1e2"),
                     (base, head, "12345678901"), (base, head, "")):
            with self.subTest(pair=pair), mock.patch.object(trusted_review, "api") as api:
                with self.assertRaisesRegex(ValueError, "Expected distinct commits and PR number"):
                    trusted_review.collect(*pair, base)
                api.assert_not_called()

    def test_unprotected_branch_rejected(self):
        base, head = "a" * 40, "b" * 40
        for protected in (False, None, 1):
            with self.subTest(protected=protected):
                with mock.patch.object(trusted_review, "api", return_value={"protected": protected}):
                    with self.assertRaisesRegex(ValueError, "Trusted branch is not protected"):
                        trusted_review.collect(base, head, "0", base)

    def exercise(self, *, missing=False, wrong_head=False, helper_failure=False,
                 candidate=False, stale=False, fault=None):
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
                if fault == "symlink" and name == "c3":
                    dest.symlink_to("not-a-helper")
                    continue
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
            off_target_base = head
            if fault == "base_off_target":
                git("commit", "--allow-empty", "-m", "second candidate commit")
                head = git("rev-parse", "HEAD")
            git("update-ref", "refs/pull/1/head", head)
            if fault == "non_descendant":
                git("checkout", "dev")
                git("commit", "--allow-empty", "-m", "advanced base")
                base = git("rev-parse", "HEAD")
            requested_base = off_target_base if fault == "base_off_target" else base
            workflow = head if fault == "workflow_off_dev" else base

            def local_run(args, **kwargs):
                # The production runner forbids file transports. Only this
                # hermetic fixture substitutes the local Git remote.
                args = ["protocol.file.allow=always" if a == "protocol.file.allow=never"
                        else a for a in args]
                result = real_run(args, **kwargs)
                if (fault in ("workflow_off_dev", "base_off_target")
                        and args[-1] == "refs/heads/dev:refs/remotes/origin/dev"):
                    # Ensure the off-branch commit is present so the ancestry
                    # check fails with status 1, not an unknown-object error.
                    real_run([*args[:-1],
                              "refs/heads/candidate:refs/remotes/origin/fixture-candidate"],
                             **kwargs)
                if fault == "blob_mismatch" and "show" in args:
                    return subprocess.CompletedProcess(args, 0, result.stdout + b"tampered", b"")
                if fault == "dirty_base" and "status" in args:
                    return subprocess.CompletedProcess(args, 0, b"?? unexpected\n", b"")
                if fault == "wrong_checkout" and args[-2:] == ["rev-parse", "HEAD"]:
                    return subprocess.CompletedProcess(args, 0, b"0" * 40 + b"\n", b"")
                return result

            pr = {"state": "open", "base": {"ref": "dev", "sha": requested_base,
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
                if fault == "target_moved" and calls == 2:
                    return {**pr, "base": {**pr["base"], "ref": "main"},
                            "head": {**pr["head"], "ref": "dev"}}
                return pr

            output = io.StringIO()
            with mock.patch.object(trusted_review, "REMOTE", str(source)), \
                    mock.patch.object(trusted_review, "api", side_effect=local_api), \
                    mock.patch.object(trusted_review.subprocess, "run", side_effect=local_run), \
                    mock.patch.dict(os.environ, {"REVIEW_TOKEN": "not-for-helpers"}), \
                    contextlib.redirect_stdout(output):
                args = (requested_base, pr["head"]["sha"], "0" if candidate else "1", workflow)
                expected = ("Missing or non-regular trusted helper: c3" if missing else
                            "Fetched head does not match requested SHA" if wrong_head else
                            "Trusted helper failed: c3" if helper_failure else
                            "PR metadata differs" if stale else
                            {"symlink": "Missing or non-regular trusted helper: c3",
                             "blob_mismatch": "Trusted helper blob mismatch: policy",
                             "dirty_base": "Base checkout is not clean and exact",
                             "wrong_checkout": "Base checkout is not clean and exact",
                             "target_moved": "PR target changed during collection"}.get(fault))
                if expected:
                    with self.assertRaisesRegex(ValueError, expected):
                        trusted_review.collect(*args)
                elif fault in ("workflow_off_dev", "base_off_target", "non_descendant"):
                    with self.assertRaises(subprocess.CalledProcessError) as caught:
                        trusted_review.collect(*args)
                    ancestry = {"workflow_off_dev": (workflow, "refs/remotes/origin/dev"),
                                "base_off_target": (requested_base, "refs/remotes/origin/dev"),
                                "non_descendant": (base, head)}
                    self.assertEqual(caught.exception.returncode, 1)
                    self.assertEqual(caught.exception.cmd[-4:],
                                     ["merge-base", "--is-ancestor", *ancestry[fault]])
                else:
                    trusted_review.collect(*args)
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

    def test_pr_target_movement_with_unchanged_pair(self):
        rows = self.exercise(fault="target_moved")
        self.assertEqual(sum("helper" in row for row in rows), 3)
        self.assertFalse(any(row.get("result") == "complete" for row in rows))

    def test_provenance_and_helper_integrity_failures(self):
        for fault in ("workflow_off_dev", "base_off_target", "non_descendant",
                      "symlink", "blob_mismatch", "dirty_base", "wrong_checkout"):
            with self.subTest(fault=fault):
                rows = self.exercise(candidate=True, fault=fault)
                self.assertFalse(any("helper" in row or row.get("result") == "complete"
                                     for row in rows))

    def test_each_workflow_startup_gate(self):
        valid = {"GITHUB_EVENT_NAME": "workflow_dispatch",
                 "GITHUB_REPOSITORY": "YuriiSokolenko/recursive-coder",
                 "GITHUB_REF": "refs/heads/dev", "GITHUB_SHA": "a" * 40,
                 "GITHUB_WORKFLOW_SHA": "a" * 40, "REVIEW_BASE": "b" * 40,
                 "REVIEW_HEAD": "c" * 40, "REVIEW_PR": "1"}
        with mock.patch.dict(os.environ, valid, clear=True), \
                mock.patch.object(trusted_review, "collect") as collect:
            trusted_review.main()
            collect.assert_called_once_with("b" * 40, "c" * 40, "1", "a" * 40)
        for key, value in (("GITHUB_EVENT_NAME", "pull_request"),
                           ("GITHUB_REPOSITORY", "fork/example"),
                           ("GITHUB_REF", "refs/heads/candidate"),
                           ("GITHUB_SHA", "d" * 40)):
            with self.subTest(key=key), mock.patch.dict(os.environ, {**valid, key: value}, clear=True), \
                    mock.patch.object(trusted_review, "collect") as collect:
                with self.assertRaisesRegex(ValueError, "Evidence must run"):
                    trusted_review.main()
                collect.assert_not_called()

    def test_null_api_response_emits_failed_json(self):
        env = {"GITHUB_EVENT_NAME": "workflow_dispatch",
               "GITHUB_REPOSITORY": "YuriiSokolenko/recursive-coder",
               "GITHUB_REF": "refs/heads/dev", "GITHUB_SHA": "a" * 40,
               "GITHUB_WORKFLOW_SHA": "a" * 40, "REVIEW_BASE": "b" * 40,
               "REVIEW_HEAD": "c" * 40, "REVIEW_PR": "1",
               "REVIEW_TOKEN": "fixture"}
        response = mock.MagicMock()
        response.__enter__.return_value = io.BytesIO(b"null")
        output = io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), \
                mock.patch("urllib.request.urlopen", return_value=response), \
                contextlib.redirect_stdout(output):
            with self.assertRaises(SystemExit) as caught:
                runpy.run_path(trusted_review.__file__, run_name="__main__")
        self.assertEqual(caught.exception.code, 1)
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["result"], "failed")
        self.assertIn("NoneType", rows[0]["error"])
