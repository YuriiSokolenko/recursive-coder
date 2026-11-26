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

    def test_repository_audit_requires_tracked_gradlew_executable_mode(self):
        """Exercise the repository entry point, not just the isolated mode guard."""
        tracked_sha = "a" * 40
        command = ["git", "ls-files", "--stage", "--", "gradlew"]
        for mode in ("100644", "100755"):
            with self.subTest(tracked_mode=mode), mock.patch.object(
                bootstrap.subprocess, "run",
                return_value=subprocess.CompletedProcess(
                    args=command, returncode=0,
                    stdout=f"{mode} {tracked_sha} 0\tgradlew\n",
                ),
            ) as inspect_index:
                if mode == "100644":
                    with self.assertRaisesRegex(AssertionError, "Git mode 100755"):
                        bootstrap.audit_repository()
                else:
                    bootstrap.audit_repository()
                inspect_index.assert_called_once_with(
                    command, cwd=ROOT, check=True, capture_output=True, text=True,
                )

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

    def test_independent_review_workflow_and_runner_drift_rejected(self):
        workflow = (ROOT / ".github/workflows/trusted-review.yml").read_bytes()
        runner = (ROOT / "tools/run_trusted_review.py").read_bytes()
        bootstrap.audit_trusted_review(workflow, runner)
        for pair in ((workflow + b"\n", runner), (workflow, runner + b"\n")):
            with self.assertRaisesRegex(AssertionError, "Changed independent trusted-review"):
                bootstrap.audit_trusted_review(*pair)

    def test_exact_workflow_inventory_rejects_missing_and_extra_entries(self):
        for names in (("ci.yml",), ("trusted-review.yml",),
                      ("ci.yml", "trusted-review.yml", "extra.yml"),
                      ("ci.yml", "trusted-review.yml", "extra.yaml")):
            with self.subTest(names=names), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                workflows = root / ".github/workflows"
                workflows.mkdir(parents=True)
                for name in names:
                    (workflows / name).touch()
                with mock.patch.object(bootstrap, "ROOT", root):
                    with self.assertRaisesRegex(AssertionError, "Unexpected additional/reusable workflow"):
                        bootstrap.audit_repository()

    def test_trusted_review_suite_is_explicit_in_contracts_job(self):
        command = "python3 -I -m unittest discover -s tests -p 'test_trusted_review.py' -v"
        contracts = re.search(r"^  contracts:\n.*?(?=^  \S|\Z)", self.workflow, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(contracts)
        self.assertIn(f"      - name: Test independent trusted review evidence\n        run: {command}\n",
                      contracts.group())
        self.assertEqual(self.workflow.count(command), 1)

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
                self.assertEqual(archive.name, "gradle-9.8.2-bin.zip")
                self.assertEqual(argv[-1],
                                 "https://services.gradle.org/distributions/gradle-9.8.2-bin.zip")
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



def workflow_step_script(workflow: str, name: str) -> str:
    """Return the dedented `run: |` block of the named workflow step."""
    lines = workflow.splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == f"- name: {name}")
    run_index = next(i for i in range(start + 1, len(lines))
                     if lines[i].strip() == "run: |")
    body_indent = len(lines[run_index + 1]) - len(lines[run_index + 1].lstrip())
    body = []
    for line in lines[run_index + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) < body_indent:
            break
        body.append(line[body_indent:])
    return "\n".join(body) + "\n"


def workflow_step_field(workflow: str, name: str, field: str) -> str | None:
    """Return a scalar key of the named step, or None when the step has no such key."""
    lines = workflow.splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == f"- name: {name}")
    step_indent = len(lines[start]) - len(lines[start].lstrip()) + 2
    for line in lines[start + 1:]:
        indent = len(line) - len(line.lstrip())
        if line.strip() and indent < step_indent:
            return None
        if indent == step_indent and line.strip().startswith(f"{field}:"):
            return line.split(":", 1)[1].strip()
    return None


# GitHub Actions runs `shell: bash` as `bash --noprofile --norc -eo pipefail {0}`.
ACTIONS_BASH = ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c"]

# A stub helper that checks its arguments against an oracle passed by the test
# through the environment, so a swapped, missing or extra argument fails.
STUB_HELPER = """import os, sys
expected = ["--repo", os.environ["EXPECT_REPO"], "--base", os.environ["EXPECT_BASE"],
            "--head", os.environ["EXPECT_HEAD"]]
if sys.argv[1:] != expected:
    print("ARGS MISMATCH", sys.argv[1:])
    sys.exit(99)
print("base helper ran with exact arguments")
sys.exit({code})
"""


class WorkflowShellParityTest(unittest.TestCase):
    def test_executed_steps_declare_bash_like_the_test_harness(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        for step in ("Review evidence (base helpers)",
                     "Enforce English-only repository documentation"):
            with self.subTest(step=step):
                self.assertEqual(workflow_step_field(workflow, step, "shell"), "bash")


class ReviewEvidenceStepTest(unittest.TestCase):
    """Execute the contracts job's review evidence step against temporary repositories."""

    PIN_HELPER = ".claude/skills/c3-pin-check/scripts/check_c3_pins.py"
    SCAN_HELPER = ".claude/skills/diagnostic-vocabulary-audit/scripts/scan_literals.py"

    @classmethod
    def setUpClass(cls):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        cls.script = workflow_step_script(workflow, "Review evidence (base helpers)")

    def git(self, repo: Path, *args: str) -> str:
        return subprocess.run(["git", "-C", str(repo), *args], check=True,
                              capture_output=True, text=True).stdout.strip()

    def make_repo(self, tmp: Path, base_helpers: tuple[str, ...],
                  pin_exit: int = 0, scan_exit: int = 0) -> tuple[Path, str, str]:
        repo = tmp / "repo"
        repo.mkdir()
        self.git(repo, "init", "-q", "-b", "main")
        self.git(repo, "config", "user.email", "test@example.invalid")
        self.git(repo, "config", "user.name", "Test")
        self.git(repo, "config", "commit.gpgsign", "false")
        (repo / "README.md").write_text("base\n")
        for helper in base_helpers:
            target = repo / helper
            target.parent.mkdir(parents=True, exist_ok=True)
            code = pin_exit if helper == self.PIN_HELPER else scan_exit
            target.write_text(STUB_HELPER.replace("{code}", str(code)))
        self.git(repo, "add", "-A")
        self.git(repo, "commit", "-q", "-m", "base")
        base = self.git(repo, "rev-parse", "HEAD")
        # The head adds both helpers; the step must never run head copies.
        for helper in (self.PIN_HELPER, self.SCAN_HELPER):
            target = repo / helper
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("import sys\nprint('HEAD HELPER RAN')\nsys.exit(0)\n")
        self.git(repo, "add", "-A")
        self.git(repo, "commit", "-q", "-m", "head")
        return repo, base, self.git(repo, "rev-parse", "HEAD")

    def run_step(self, repo: Path, base: str, head: str, tmp: Path,
                 expect: tuple[str, str] | None = None):
        env = os.environ.copy()
        expect_base, expect_head = expect or (base, head)
        env.update(REVIEW_BASE=base, REVIEW_HEAD=head,
                   RUNNER_TEMP=str(tmp), GITHUB_WORKSPACE=str(repo),
                   EXPECT_REPO=str(repo), EXPECT_BASE=expect_base, EXPECT_HEAD=expect_head)
        return subprocess.run([*ACTIONS_BASH, self.script],
                              cwd=repo, env=env, capture_output=True, text=True, check=False)

    def test_base_without_review_gate_reports_gap_and_passes(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            repo, base, head = self.make_repo(tmp, ())
            result = self.run_step(repo, base, head, tmp)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"Trusted base helpers unavailable at {base}", result.stdout)
        self.assertIn("No review evidence produced.", result.stdout)
        self.assertNotIn("HEAD HELPER RAN", result.stdout)

    def test_base_with_one_helper_reports_the_missing_one(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            repo, base, head = self.make_repo(tmp, (self.PIN_HELPER,))
            result = self.run_step(repo, base, head, tmp)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(self.SCAN_HELPER, result.stdout)
        self.assertNotIn("base helper ran", result.stdout)
        self.assertNotIn("HEAD HELPER RAN", result.stdout)

    def test_base_helpers_run_instead_of_head_copies(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            repo, base, head = self.make_repo(tmp, (self.PIN_HELPER, self.SCAN_HELPER))
            result = self.run_step(repo, base, head, tmp)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.count("base helper ran with exact arguments"), 2)
        self.assertNotIn("ARGS MISMATCH", result.stdout)
        self.assertNotIn("HEAD HELPER RAN", result.stdout)
        self.assertIn("C3 pin check exit: 0", result.stdout)

    def test_unrelated_histories_fail_before_any_helper_runs(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            repo, base, head = self.make_repo(tmp, (self.PIN_HELPER, self.SCAN_HELPER))
            # An orphan commit with the same trusted helpers but no shared history.
            self.git(repo, "checkout", "-q", "--orphan", "unrelated", base)
            self.git(repo, "commit", "-q", "-m", "unrelated base")
            unrelated = self.git(repo, "rev-parse", "HEAD")
            self.assertEqual(subprocess.run(["git", "-C", str(repo), "merge-base", unrelated, head],
                                            capture_output=True).returncode, 1)
            result = self.run_step(repo, unrelated, head, tmp)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("Review base:", result.stdout)
        self.assertNotIn("base helper ran", result.stdout)

    def test_helper_output_is_wrapped_in_a_fresh_stop_commands_token(self):
        tokens = []
        for _ in range(2):
            with tempfile.TemporaryDirectory() as raw:
                tmp = Path(raw)
                repo, base, head = self.make_repo(tmp, (self.PIN_HELPER, self.SCAN_HELPER))
                result = self.run_step(repo, base, head, tmp)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            lines = result.stdout.splitlines()
            starts = [i for i, line in enumerate(lines) if line.startswith("::stop-commands::")]
            self.assertEqual(len(starts), 1, result.stdout)
            token = lines[starts[0]].removeprefix("::stop-commands::")
            self.assertRegex(token, r"^[0-9a-f]{64}$")
            end = lines.index(f"::{token}::")
            helper_lines = [i for i, line in enumerate(lines) if "base helper ran" in line]
            self.assertEqual(len(helper_lines), 2)
            self.assertTrue(all(starts[0] < i < end for i in helper_lines), result.stdout)
            tokens.append(token)
        self.assertNotEqual(tokens[0], tokens[1])

    def test_failing_pin_helper_fails_the_step(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            repo, base, head = self.make_repo(tmp, (self.PIN_HELPER, self.SCAN_HELPER), pin_exit=1)
            result = self.run_step(repo, base, head, tmp)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("C3 pin check exit: 1", result.stdout)
        self.assertIn("Vocabulary scanner exit: 0", result.stdout)
        self.assertNotIn("HEAD HELPER RAN", result.stdout)

    def test_failing_scanner_alone_fails_the_step(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            repo, base, head = self.make_repo(tmp, (self.PIN_HELPER, self.SCAN_HELPER), scan_exit=1)
            result = self.run_step(repo, base, head, tmp)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("C3 pin check exit: 0", result.stdout)
        self.assertIn("Vocabulary scanner exit: 1", result.stdout)

    def test_helpers_receive_exact_base_and_head(self):
        # The oracle expects the pair swapped: the step passes the real pair,
        # so both helpers must report a mismatch and the step must fail.
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            repo, base, head = self.make_repo(tmp, (self.PIN_HELPER, self.SCAN_HELPER))
            result = self.run_step(repo, base, head, tmp, expect=(head, base))
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertEqual(result.stdout.count("ARGS MISMATCH"), 2)
        self.assertIn("C3 pin check exit: 99", result.stdout)

    def test_malformed_or_unresolved_revisions_fail_before_helpers_run(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            repo, base, head = self.make_repo(tmp, (self.PIN_HELPER, self.SCAN_HELPER))
            self.git(repo, "tag", "-a", "-m", "tag", "review-tag", head)
            tag_object = self.git(repo, "rev-parse", "review-tag")
            self.assertNotEqual(tag_object, head)
            cases = {
                "annotated tag object as head": (base, tag_object),
                "annotated tag object as base": (tag_object, head),
                "short base": (base[:12], head),
                "uppercase head": (base, head.upper()),
                "unknown base": ("0" * 40, head),
                "unknown head": (base, "f" * 40),
            }
            for label, (bad_base, bad_head) in cases.items():
                with self.subTest(label):
                    result = self.run_step(repo, bad_base, bad_head, tmp)
                    self.assertNotEqual(result.returncode, 0, result.stdout)
                    self.assertNotIn("base helper ran", result.stdout)
                    self.assertNotIn("Review base:", result.stdout)


class EnglishOnlyGuardStepTest(unittest.TestCase):
    """Execute the English-only documentation guard against temporary repositories."""

    @classmethod
    def setUpClass(cls):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        cls.script = workflow_step_script(workflow, "Enforce English-only repository documentation")

    def run_guard(self, files: dict[str, str], delete: tuple[str, ...] = (),
                  failing_listing: bool = False):
        with tempfile.TemporaryDirectory() as raw:
            repo = Path(raw) / "repo"
            repo.mkdir()
            env = os.environ.copy()
            if failing_listing:
                # A git wrapper whose ls-files prints one valid path, then fails.
                bin_dir = Path(raw) / "bin"
                bin_dir.mkdir()
                real_git = subprocess.run(["sh", "-c", "command -v git"], check=True,
                                          capture_output=True, text=True).stdout.strip()
                wrapper = bin_dir / "git"
                wrapper.write_text(
                    "#!/bin/sh\n"
                    'if [ "$1" = ls-files ]; then printf \'README.md\\0\'; exit 1; fi\n'
                    f'exec "{real_git}" "$@"\n')
                wrapper.chmod(0o755)
                env["PATH"] = f"{bin_dir}:{env.get('PATH', '/usr/bin:/bin')}"
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            for rel, text in files.items():
                target = repo / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(text, encoding="utf-8")
            subprocess.run(["git", "-C", str(repo), "add", "-A"], check=True)
            for rel in delete:
                (repo / rel).unlink()
            return subprocess.run([*ACTIONS_BASH, self.script],
                                  cwd=repo, env=env, capture_output=True, text=True, check=False)

    def test_english_markdown_in_any_tracked_path_passes(self):
        result = self.run_guard({"README.md": "English\n", "docs/deep/notes.md": "More\n",
                                 "src/notes.txt": "\u041f\u0440\u0438\u0432\u0435\u0442\n"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Verified 2 tracked Markdown files", result.stdout)

    def test_cyrillic_in_any_tracked_markdown_fails(self):
        result = self.run_guard({"README.md": "English\n",
                                 "docs/dependency-verification.md": "\u041f\u0440\u0438\u0432\u0435\u0442\n"})
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("grep exit 0", result.stdout)

    def test_unreadable_tracked_markdown_fails_closed(self):
        result = self.run_guard({"README.md": "English\n", "AGENTS.md": "English\n"},
                                delete=("AGENTS.md",))
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("grep exit 2", result.stdout)

    def test_failing_markdown_listing_fails_closed(self):
        result = self.run_guard({"README.md": "English\n",
                                 "docs/other.md": "\u041f\u0440\u0438\u0432\u0435\u0442\n"},
                                failing_listing=True)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("Verified", result.stdout)


if __name__ == "__main__":
    unittest.main()
