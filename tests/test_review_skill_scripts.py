"""Offline regression checks for the review gate's helper scripts in .claude/skills."""

import hashlib
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PIN_SCRIPT = ROOT / ".claude/skills/c3-pin-check/scripts/check_c3_pins.py"
SCAN_SCRIPT = ROOT / ".claude/skills/diagnostic-vocabulary-audit/scripts/scan_literals.py"
POLICY_SCRIPT = ROOT / ".claude/skills/strict-pr-review/scripts/check_policy_checkout.py"
GRAPH = ("build-logic/src/main/kotlin/ai/yuriilab/recursivecoder/buildlogic/"
         "architecture/ArchitectureGraph.kt")
PINNED = [f"core/session/src/main/kotlin/ai/yuriilab/recursivecoder/Pinned{i}.kt"
          for i in range(6)]


def run(args, cwd=None):
    return subprocess.run(args, cwd=cwd, capture_output=True, text=True)


class GitFixture:
    """Temporary Git repository with a fake six-pin ArchitectureGraph."""

    def __init__(self, object_format="sha1"):
        self._tmp = tempfile.TemporaryDirectory(prefix="review-skill-")
        self.path = Path(self._tmp.name)
        self.git("init", "-q", "-b", "dev", f"--object-format={object_format}")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test")
        self.git("config", "commit.gpgsign", "false")

    def close(self):
        self._tmp.cleanup()

    def git(self, *args):
        result = run(["git", "-C", str(self.path), *args])
        if result.returncode:
            raise AssertionError(result.stderr)
        return result.stdout.strip()

    def write(self, rel, text):
        target = self.path / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def write_pins(self, overrides=None):
        overrides = overrides or {}
        entries = []
        for path in PINNED:
            data = (self.path / path).read_bytes()
            digest = overrides.get(path, hashlib.sha256(data).hexdigest())
            entries.append(f'        "{path}" to\n            "{digest}",')
        self.write(GRAPH, "val approvedSourceSha256 = mapOf(\n" + "\n".join(entries) + "\n)\n")


class C3PinCheckTest(unittest.TestCase):
    def setUp(self):
        self.repo = GitFixture()
        for i, path in enumerate(PINNED):
            self.repo.write(path, f"class Pinned{i}\n")
        self.repo.write_pins()
        self.repo.write(".github/CODEOWNERS", "/core/ @owner\n/.claude/ @owner\n")
        self.repo.write("docs/notes.md", "notes\n")
        self.base = self.repo.commit("base")

    def tearDown(self):
        self.repo.close()

    def check(self, *extra):
        return run([sys.executable, "-I", str(PIN_SCRIPT), "--repo", str(self.repo.path), *extra])

    def test_matching_pins_pass(self):
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(result.stdout.count("ok  "), 6)
        self.assertNotIn("FAIL", result.stdout)

    def test_changed_pinned_source_fails_and_reports_c3_change(self):
        self.repo.write(PINNED[2], "class Pinned2 { val changed = true }\n")
        head = self.repo.commit("edit pinned")
        result = self.check("--base", self.base, "--head", head)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn(f"FAIL {PINNED[2]}", result.stdout)
        self.assertIn("C3 CHANGE", result.stdout)
        self.assertIn(f"pinned     {PINNED[2]}", result.stdout)

    def test_updated_pin_passes_but_still_reports_c3_change(self):
        self.repo.write(PINNED[0], "class Pinned0 { val reviewed = true }\n")
        self.repo.write_pins()
        head = self.repo.commit("reviewed C3 change")
        result = self.check("--base", self.base, "--head", head)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("C3 CHANGE", result.stdout)
        self.assertIn(f"inventory  {GRAPH}", result.stdout)

    def test_missing_pinned_file_fails(self):
        self.repo.git("rm", "-q", PINNED[5])
        head = self.repo.commit("delete pinned")
        result = self.check("--base", self.base, "--head", head)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn(f"FAIL missing  {PINNED[5]}", result.stdout)

    def test_protected_paths_are_listed_and_unprotected_are_not(self):
        self.repo.write("core/tasks/src/main/kotlin/Other.kt", "class Other\n")
        self.repo.write(".claude/skills/x/SKILL.md", "skill\n")
        self.repo.write("docs/notes.md", "changed\n")
        head = self.repo.commit("mixed change")
        result = self.check("--base", self.base, "--head", head)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("CODEOWNERS-protected among them: 2", result.stdout)
        self.assertIn("protected  .claude/skills/x/SKILL.md", result.stdout)
        self.assertIn("protected  core/tasks/src/main/kotlin/Other.kt", result.stdout)
        self.assertNotIn("protected  docs/notes.md", result.stdout)
        self.assertNotIn("C3 CHANGE", result.stdout)

    def test_removed_codeowners_rule_does_not_hide_protected_change(self):
        self.repo.write(".github/CODEOWNERS", "/.claude/ @owner\n")
        path = "core/tasks/src/main/kotlin/Other.kt"
        self.repo.write(path, "class Other\n")
        head = self.repo.commit("remove protection and change file")
        result = self.check("--base", self.base, "--head", head)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn(f"protected  {path}", result.stdout)

    def test_missing_pin_entry_fails(self):
        graph = self.repo.path / GRAPH
        lines = graph.read_text().splitlines(keepends=True)
        graph.write_text("".join(lines[:1] + lines[3:]))
        head = self.repo.commit("remove pin entry")
        result = self.check("--base", self.base, "--head", head)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("FAIL: expected 6 distinct C3 pins", result.stdout)

    def test_duplicate_pin_path_fails(self):
        graph = self.repo.path / GRAPH
        graph.write_text(graph.read_text().replace(PINNED[1], PINNED[0]))
        head = self.repo.commit("duplicate pin path")
        result = self.check("--base", self.base, "--head", head)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("FAIL: expected 6 distinct C3 pins", result.stdout)

    def test_single_revision_argument_rejected(self):
        result = self.check("--base", self.base)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--base and --head must be provided together", result.stderr)

    def test_head_revision_alone_rejected(self):
        result = self.check("--head", self.base)
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("--base and --head must be provided together", result.stderr)

    def test_base_without_graph_fails(self):
        repo = GitFixture()
        try:
            repo.write("docs/notes.md", "notes\n")
            base = repo.commit("no graph yet")
            for i, path in enumerate(PINNED):
                repo.write(path, f"class Pinned{i}\n")
            repo.write_pins()
            head = repo.commit("add graph")
            result = run([sys.executable, "-I", str(PIN_SCRIPT), "--repo", str(repo.path),
                          "--base", base, "--head", head])
        finally:
            repo.close()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn(f"FAIL: base {GRAPH} not found", result.stdout)
        self.assertEqual(result.stdout.count("ok  "), 6)

    def test_repointed_pin_still_reports_base_pinned_source(self):
        moved = "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/Replacement.kt"
        self.repo.write(moved, "class Replacement\n")
        self.repo.write(PINNED[2], "class Pinned2 { val unpinned = true }\n")
        graph = self.repo.path / GRAPH
        digest = hashlib.sha256((self.repo.path / moved).read_bytes()).hexdigest()
        lines = graph.read_text().splitlines(keepends=True)
        index = next(i for i, line in enumerate(lines) if PINNED[2] in line)
        lines[index] = f'        "{moved}" to\n'
        lines[index + 1] = f'            "{digest}",\n'
        graph.write_text("".join(lines))
        head = self.repo.commit("re-point pin and edit old source")
        result = self.check("--base", self.base, "--head", head)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("C3 CHANGE", result.stdout)
        self.assertIn(f"pinned     {PINNED[2]}", result.stdout)
        self.assertIn(f"pinned     {moved}", result.stdout)

    def test_renamed_pinned_file_lists_both_paths(self):
        moved = "core/session/src/main/kotlin/ai/yuriilab/recursivecoder/Moved4.kt"
        self.repo.git("mv", PINNED[4], moved)
        graph = self.repo.path / GRAPH
        graph.write_text(graph.read_text().replace(PINNED[4], moved))
        head = self.repo.commit("rename pinned file")
        result = self.check("--base", self.base, "--head", head)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn(f"pinned     {PINNED[4]}", result.stdout)
        self.assertIn(f"pinned     {moved}", result.stdout)

    def test_protected_path_with_space_is_listed(self):
        path = "core/tasks/src/main/kotlin/Task Policy.kt"
        self.repo.write(path, "class TaskPolicy\n")
        head = self.repo.commit("path with space")
        result = self.check("--base", self.base, "--head", head)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("CODEOWNERS-protected among them: 1", result.stdout)
        self.assertIn(f"protected  {path}", result.stdout)

    def test_head_content_is_checked_not_working_tree(self):
        self.repo.write(PINNED[1], "class Pinned1 { val committed = true }\n")
        head = self.repo.commit("edit pinned")
        self.repo.git("checkout", "-q", self.base)
        result = self.check("--base", self.base, "--head", head)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn(f"FAIL {PINNED[1]}", result.stdout)


class ScanLiteralsTest(unittest.TestCase):
    OWNER = "core/contracts/src/main/kotlin/StageDenialCodes.kt"
    USER = "core/tasks/src/main/kotlin/TaskPolicy.kt"
    OTHER = "infra/journal/src/main/kotlin/Journal.kt"
    UNTOUCHED_REPEAT = "infra/model/src/main/kotlin/Model.kt"
    UNTOUCHED_SHARED_A = "infra/workspace/src/main/kotlin/Workspace.kt"
    UNTOUCHED_SHARED_B = "app/cli/src/main/kotlin/Cli.kt"

    def setUp(self):
        self.repo = GitFixture()
        for i, path in enumerate(PINNED):
            self.repo.write(path, f"class Pinned{i}\n")
        self.repo.write_pins()
        self.repo.write(self.OWNER, 'object StageDenialCodes {\n'
                                    '    const val STAGE_DENIED = "STAGE_DENIED"\n}\n')
        self.repo.write(self.USER, "class TaskPolicy\n")
        self.repo.write(self.OTHER, 'val x = "TASK_MISSING"\n')
        # Untouched production files that would be reported if the touched-file
        # filters were dropped: a literal repeated in one file, and a literal
        # shared by two files.
        self.repo.write(self.UNTOUCHED_REPEAT, 'val a = "UNTOUCHED_REPEAT"\n'
                                               'val b = "UNTOUCHED_REPEAT"\n')
        self.repo.write(self.UNTOUCHED_SHARED_A, 'val a = "UNTOUCHED_SHARED"\n')
        self.repo.write(self.UNTOUCHED_SHARED_B, 'val b = "UNTOUCHED_SHARED"\n')
        self.repo.write("core/tasks/src/test/kotlin/TaskPolicyTest.kt",
                        'val oracle = "STAGE_DENIED" + "TASK_MISSING" + "TASK_MISSING"\n')
        self.base = self.repo.commit("base")

    def tearDown(self):
        self.repo.close()

    def scan(self, head):
        result = run([sys.executable, "-I", str(SCAN_SCRIPT), "--repo", str(self.repo.path),
                      "--base", self.base, "--head", head])
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def test_reports_owned_raw_repeated_and_cross_file_literals(self):
        self.repo.write(self.USER, 'class TaskPolicy {\n'
                                   '    val a = "STAGE_DENIED"\n'
                                   '    val b = "TASK_MISSING"\n'
                                   '    val c = "TASK_MISSING"\n}\n')
        output = self.scan(self.repo.commit("add literals"))
        section1, rest = output.split("== 2.", 1)
        section2, section3 = rest.split("== 3.", 1)
        self.assertIn(f"STAGE_DENIED x1  {self.USER}", section1)
        self.assertIn("owner: StageDenialCodes.kt:STAGE_DENIED", section1)
        self.assertIn(f"TASK_MISSING x2  {self.USER}", section2)
        self.assertIn("modules=:core:tasks,:infra:journal", section3)
        self.assertIn(f"{self.USER}  (touched)", section3)
        self.assertIn("Summary: owned-but-raw=1 repeated-in-touched=1 cross-file=1", output)

    def test_ignores_test_sources_and_untouched_files(self):
        self.repo.write("docs/notes.md", "changed\n")
        output = self.scan(self.repo.commit("docs only"))
        self.assertIn("Summary: owned-but-raw=0 repeated-in-touched=0 cross-file=0", output)
        self.assertNotIn("UNTOUCHED_REPEAT", output)
        self.assertNotIn("UNTOUCHED_SHARED", output)
        self.assertNotIn("TaskPolicyTest.kt", output)

    def test_untouched_literals_stay_out_when_another_file_is_touched(self):
        self.repo.write(self.USER, 'class TaskPolicy {\n    val a = "TASK_MISSING"\n}\n')
        output = self.scan(self.repo.commit("touch one file"))
        self.assertNotIn("UNTOUCHED_REPEAT", output)
        self.assertNotIn("UNTOUCHED_SHARED", output)
        self.assertIn("Summary: owned-but-raw=0 repeated-in-touched=0 cross-file=1", output)

    def test_touched_path_with_space_is_scanned(self):
        path = "core/tasks/src/main/kotlin/Task Rules.kt"
        self.repo.write(path, 'val a = "SPACED_CODE"\nval b = "SPACED_CODE"\n')
        output = self.scan(self.repo.commit("path with space"))
        self.assertIn(f"SPACED_CODE x2  {path}", output)
        self.assertIn("repeated-in-touched=1", output)

    def test_marks_pinned_c3_files(self):
        self.repo.write(PINNED[3], 'class Pinned3 {\n    val a = "STAGE_DENIED"\n}\n')
        self.repo.write_pins()
        output = self.scan(self.repo.commit("pinned literal"))
        self.assertIn(f"STAGE_DENIED x1  [C3] {PINNED[3]}", output)



class PolicyCheckoutTest(unittest.TestCase):
    """Instruction files on disk must equal the base blobs, whatever Git's index says."""

    SKILL = ".claude/skills/strict-pr-review/SKILL.md"

    def setUp(self):
        self.repo = GitFixture()
        self.repo.write(".gitignore", "*.local.json\n")
        self.repo.write("CONTRIBUTING.md", "rules\n")
        self.repo.write(self.SKILL, "base skill\n")
        self.repo.write("README.md", "readme\n")
        self.base = self.repo.commit("base without root entry points")

    def tearDown(self):
        self.repo.close()

    def check(self, base=None):
        return run([sys.executable, "-I", str(POLICY_SCRIPT), "--repo", str(self.repo.path),
                    "--base", base or self.base])

    def assert_fails_with(self, *lines):
        result = self.check()
        self.assertEqual(result.returncode, 1, result.stdout)
        for line in lines:
            self.assertIn(line, result.stdout)
        return result

    def test_clean_checkout_at_base_passes(self):
        self.repo.git("checkout", "-q", "--detach", self.base)
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("Compared 2 instruction paths on disk with 2 base entries", result.stdout)
        self.assertIn("OK: auto-loaded instruction paths match the base", result.stdout)

    def test_non_policy_changes_do_not_matter(self):
        self.repo.write("README.md", "changed\n")
        self.repo.commit("readme only")
        self.repo.write("notes.txt", "untracked\n")
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_entry_point_absent_at_base_fails(self):
        self.repo.write("AGENTS.md", "pr instructions\n")
        self.repo.commit("add AGENTS.md")
        self.assert_fails_with("extra      AGENTS.md")

    def test_committed_and_unstaged_changes_fail(self):
        self.repo.write(self.SKILL, "pr skill\n")
        self.repo.commit("change skill")
        self.repo.write("CONTRIBUTING.md", "unstaged\n")
        self.assert_fails_with(f"differs    {self.SKILL}", "differs    CONTRIBUTING.md")

    def test_deleted_policy_file_fails(self):
        (self.repo.path / "CONTRIBUTING.md").unlink()
        self.assert_fails_with("missing    CONTRIBUTING.md")

    def test_renamed_policy_file_lists_both_paths(self):
        self.repo.git("mv", self.SKILL, ".claude/skills/strict-pr-review/RENAMED.md")
        self.repo.commit("rename skill")
        self.assert_fails_with(f"missing    {self.SKILL}",
                               "extra      .claude/skills/strict-pr-review/RENAMED.md")

    def test_untracked_instruction_files_fail(self):
        self.repo.write("CLAUDE.md", "untracked instructions\n")
        self.repo.write(".claude/agents/extra agent.md", "untracked agent\n")
        self.assert_fails_with("extra      CLAUDE.md", "extra      .claude/agents/extra agent.md")

    def test_ignored_instruction_files_fail(self):
        self.repo.write(".claude/settings.local.json", "{}\n")
        self.assert_fails_with("extra      .claude/settings.local.json")

    def test_assume_unchanged_file_with_new_bytes_fails(self):
        # git diff and git status both report nothing for this file.
        self.repo.git("update-index", "--assume-unchanged", self.SKILL)
        self.repo.write(self.SKILL, "edited behind the index\n")
        self.assertEqual(self.repo.git("status", "--porcelain", "--", self.SKILL), "")
        self.assert_fails_with(f"differs    {self.SKILL}")

    def test_skip_worktree_file_with_new_bytes_fails(self):
        self.repo.git("update-index", "--skip-worktree", "CONTRIBUTING.md")
        self.repo.write("CONTRIBUTING.md", "edited behind the index\n")
        self.assertEqual(self.repo.git("status", "--porcelain", "--", "CONTRIBUTING.md"), "")
        self.assert_fails_with("differs    CONTRIBUTING.md")

    def test_symlink_in_place_of_file_fails(self):
        target = self.repo.path / "elsewhere.md"
        target.write_text("base skill\n")
        skill = self.repo.path / self.SKILL
        skill.unlink()
        skill.symlink_to(target)
        self.assert_fails_with(f"differs    {self.SKILL}")

    def test_new_instruction_symlink_is_reported_as_symlink(self):
        link = self.repo.path / "AGENTS.md"
        link.symlink_to("README.md")
        for tracked in (False, True):
            with self.subTest(tracked=tracked):
                if tracked:
                    self.repo.commit("add instruction symlink")
                result = self.assert_fails_with("symlink    AGENTS.md")
                self.assertNotIn("extra      AGENTS.md", result.stdout)
                self.assertIn(
                    "Compared 3 instruction paths on disk with 2 base entries",
                    result.stdout,
                )

    def test_type_change_with_identical_bytes_fails(self):
        # A symlink's blob is its target string, so a file holding that string
        # has the same blob ID; only the type differs, in either direction.
        link = self.repo.path / ".claude/link.md"
        link.symlink_to("notes.md")
        plain = self.repo.path / ".claude/plain.md"
        plain.write_text("notes.md")
        base = self.repo.commit("base with a symlink and a file")
        link.unlink()
        link.write_text("notes.md")
        plain.unlink()
        plain.symlink_to("notes.md")
        result = self.check(base)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("differs    .claude/link.md", result.stdout)
        self.assertIn("differs    .claude/plain.md", result.stdout)

    def test_unchanged_base_instruction_link_with_changed_target_fails(self):
        self.repo.write("README.md", "trusted instructions\n")
        link = self.repo.path / "AGENTS.md"
        link.symlink_to("README.md")
        base = self.repo.commit("base with instruction link")
        # Even a clean checkout with a policy symlink is unsupported.
        self.assertEqual(self.check(base).returncode, 1)
        self.repo.write("README.md", "changed instructions\n")
        self.assertEqual(os.readlink(link), "README.md")
        result = self.check(base)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("differs    AGENTS.md", result.stdout)

    def test_unchanged_base_instruction_link_with_missing_target_fails(self):
        self.repo.write("README.md", "trusted instructions\n")
        link = self.repo.path / "CLAUDE.md"
        link.symlink_to("README.md")
        base = self.repo.commit("base with instruction link")
        (self.repo.path / "README.md").unlink()
        self.assertEqual(os.readlink(link), "README.md")
        result = self.check(base)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("differs    CLAUDE.md", result.stdout)

    def test_unchanged_base_instruction_link_to_external_target_fails(self):
        with tempfile.TemporaryDirectory() as outside:
            target = Path(outside) / "instructions.md"
            target.write_text("external instructions\n")
            link = self.repo.path / "AGENTS.md"
            link.symlink_to(target)
            base = self.repo.commit("base with external instruction link")
            result = self.check(base)
            self.assertEqual(result.returncode, 1, result.stdout)
            self.assertIn("differs    AGENTS.md", result.stdout)

    def test_non_policy_file_symlink_is_rejected(self):
        docs = self.repo.path / "docs"
        docs.mkdir()
        link = docs / "notes.md"
        link.symlink_to("../README.md")
        for tracked in (False, True):
            with self.subTest(tracked=tracked):
                base = (self.repo.commit("base with non-policy file link")
                        if tracked else self.base)
                result = self.check(base)
                self.assertEqual(result.returncode, 1, result.stdout)
                self.assertIn("symlink    docs/notes.md", result.stdout)
                self.assertNotIn("extra      docs/notes.md", result.stdout)
                self.assertIn(
                    "Compared 2 instruction paths on disk with 2 base entries",
                    result.stdout,
                )

    def test_non_policy_directory_alias_is_rejected_without_following_it(self):
        with tempfile.TemporaryDirectory() as outside:
            target = Path(outside)
            (target / "AGENTS.md").write_text("external instructions\n")
            alias = self.repo.path / "alias"
            alias.symlink_to(target, target_is_directory=True)
            # Both an untracked alias and an identical committed alias fail.
            for tracked in (False, True):
                with self.subTest(tracked=tracked):
                    base = (self.repo.commit("base with directory alias")
                            if tracked else self.base)
                    result = self.check(base)
                    self.assertEqual(result.returncode, 1, result.stdout)
                    self.assertIn("symlink    alias", result.stdout)
                    self.assertNotIn("alias/AGENTS.md", result.stdout)
                    self.assertIn(
                        "Compared 2 instruction paths on disk with 2 base entries",
                        result.stdout,
                    )

    def test_every_auto_loaded_name_at_any_depth_fails_when_untracked(self):
        names = ["CLAUDE.local.md", "docs/CLAUDE.md", "sub/AGENTS.md", "AGENTS.override.md",
                 "deep/a/b/AGENTS.override.md", "deep/CLAUDE.local.md", ".mcp.json",
                 "tools/.mcp.json", "sub/.claude/skills/x/SKILL.md", ".codex/config.toml",
                 "sub/.codex/prompts/x.md", ".agents/skills/x/SKILL.md", "docs/claude.MD",
                 "Docs/Agents.Md", "sub/.Claude/agents/x.md"]
        for name in names:
            with self.subTest(name=name):
                self.repo.write(name, "untracked instructions\n")
                result = self.check()
                (self.repo.path / name).unlink()
                self.assertEqual(result.returncode, 1, result.stdout)
                self.assertIn(f"extra      {name}", result.stdout)
        self.assertEqual(self.check().returncode, 0)

    def test_nested_instruction_files_in_base_are_compared(self):
        self.repo.write("docs/AGENTS.md", "nested base instructions\n")
        self.repo.write("tools/.claude/agents/x.md", "nested base agent\n")
        base = self.repo.commit("base with nested instructions")
        self.assertEqual(self.check(base).returncode, 0)
        self.repo.write("docs/AGENTS.md", "changed\n")
        (self.repo.path / "tools/.claude/agents/x.md").unlink()
        result = self.check(base)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("differs    docs/AGENTS.md", result.stdout)
        self.assertIn("missing    tools/.claude/agents/x.md", result.stdout)

    def test_special_files_are_never_trusted(self):
        os.mkfifo(self.repo.path / ".claude/pipe")
        os.mkfifo(self.repo.path / "CLAUDE.md")
        self.assert_fails_with("extra      .claude/pipe", "extra      CLAUDE.md")

    def test_special_file_in_place_of_base_file_differs(self):
        (self.repo.path / "CONTRIBUTING.md").unlink()
        os.mkfifo(self.repo.path / "CONTRIBUTING.md")
        self.assert_fails_with("differs    CONTRIBUTING.md")

    def test_repo_argument_inside_a_subdirectory_checks_the_whole_tree(self):
        sub = self.repo.path / "docs"
        sub.mkdir()
        result = run([sys.executable, "-I", str(POLICY_SCRIPT), "--repo", str(sub),
                      "--base", self.base])
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("Compared 2 instruction paths on disk with 2 base entries", result.stdout)
        self.repo.write("CLAUDE.md", "root instructions\n")
        result = run([sys.executable, "-I", str(POLICY_SCRIPT), "--repo", str(sub),
                      "--base", self.base])
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("extra      CLAUDE.md", result.stdout)

    @unittest.skipIf(os.geteuid() == 0, "root bypasses file permissions")
    def test_unreadable_paths_fail(self):
        locked_dir = self.repo.path / "locked"
        locked_dir.mkdir()
        locked_file = self.repo.path / ".claude/skills/strict-pr-review/SKILL.md"
        locked_dir.chmod(0)
        locked_file.chmod(0)
        try:
            result = self.check()
        finally:
            locked_dir.chmod(0o755)
            locked_file.chmod(0o644)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("unreadable locked", result.stdout)
        self.assertIn(f"unreadable {self.SKILL}", result.stdout)

    def test_unresolvable_base_exits_2(self):
        result = self.check(base="0" * 40)
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("ERROR", result.stdout)

class PolicyCheckoutSha256Test(unittest.TestCase):
    """Blob IDs follow the repository's object format, not a fixed SHA-1."""

    def test_sha256_repository(self):
        repo = GitFixture(object_format="sha256")
        try:
            repo.write("CONTRIBUTING.md", "rules\n")
            repo.write(".claude/skills/x/SKILL.md", "skill\n")
            base = repo.commit("sha256 base")
            self.assertEqual(len(base), 64)
            clean = run([sys.executable, "-I", str(POLICY_SCRIPT), "--repo", str(repo.path),
                         "--base", base])
            repo.write(".claude/skills/x/SKILL.md", "changed\n")
            changed = run([sys.executable, "-I", str(POLICY_SCRIPT), "--repo", str(repo.path),
                           "--base", base])
        finally:
            repo.close()
        self.assertEqual(clean.returncode, 0, clean.stdout)
        self.assertIn("Object format: sha256", clean.stdout)
        self.assertEqual(changed.returncode, 1, changed.stdout)
        self.assertIn("differs    .claude/skills/x/SKILL.md", changed.stdout)


if __name__ == "__main__":
    unittest.main()
