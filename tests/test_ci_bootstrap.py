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
import check_dependency_verification as dependency
import run_ci_gradle as ci_gradle


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
                bootstrap, "tracked_files", return_value=["settings.gradle.kts"],
            ), mock.patch.object(
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

    def test_cache_save_without_protected_push_guard_rejected(self):
        mutated = self.workflow.replace(
            "      - name: Save protected-branch Gradle module cache\n"
            f"        if: {bootstrap.CACHE_SAVE_IF}\n",
            "      - name: Save protected-branch Gradle module cache\n", 1,
        )
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "Changed dependency cache policy"):
            bootstrap.audit_workflow(mutated)

    def test_cache_restore_broader_path_or_fallback_rejected(self):
        for replacement in (
            "          path: ~/.gradle",
            f"          path: {bootstrap.CACHE_PATH}\n          restore-keys: gradle-",
        ):
            with self.subTest(replacement=replacement):
                mutated = self.workflow.replace(
                    f"          path: {bootstrap.CACHE_PATH}", replacement, 1,
                )
                with self.assertRaisesRegex(AssertionError, "Changed dependency cache policy"):
                    bootstrap.audit_workflow(mutated)

    def test_cache_preflight_bypass_rejected(self):
        mutated = self.workflow.replace("--restore-module-cache", "--stage-module-cache", 1)
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "Changed dependency cache policy"):
            bootstrap.audit_workflow(mutated)

    def test_cache_save_recomputed_key_rejected(self):
        mutated = self.workflow.replace(
            "          key: ${{ steps.gradle-cache.outputs.cache-primary-key }}",
            f"          key: {bootstrap.CACHE_KEY}", 1,
        )
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "Changed dependency cache policy"):
            bootstrap.audit_workflow(mutated)

    def test_current_run_cache_cannot_enter_independent_verifier(self):
        original = bootstrap.CACHE_DOWNLOAD_STEP
        mutated = self.workflow.replace(original, "", 1).replace(
            "      - name: Generate trusted Gradle 9.8.2 launcher templates",
            original + "      - name: Generate trusted Gradle 9.8.2 launcher templates", 1,
        )
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "Changed dependency cache policy"):
            bootstrap.audit_workflow(mutated)

    def test_cache_resolution_and_offline_proof_are_mandatory(self):
        for step in (bootstrap.CACHE_RESOLVE_STEP, bootstrap.CACHE_PROOF_STEP):
            with self.subTest(step=step.splitlines()[0]):
                self.assertIn(step, self.workflow)
                mutated = self.workflow.replace(step, "", 1)
                with self.assertRaisesRegex(AssertionError, "Changed dependency cache policy"):
                    bootstrap.audit_workflow(mutated)

    def test_cache_resolution_cannot_be_skipped_on_hit(self):
        original = bootstrap.CACHE_RESOLVE_STEP
        guarded = original.replace("        run: |",
                                   "        if: false\n        run: |", 1)
        mutated = self.workflow.replace(original, guarded, 1)
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "Changed dependency cache policy"):
            bootstrap.audit_workflow(mutated)

    def test_cache_proof_cannot_access_registries_or_tolerate_deprecations(self):
        original = bootstrap.CACHE_PROOF_STEP
        for flag in ("--offline ", "--warning-mode=fail "):
            with self.subTest(flag=flag):
                mutated = self.workflow.replace(original, original.replace(flag, ""), 1)
                self.assertNotEqual(mutated, self.workflow)
                with self.assertRaisesRegex(AssertionError, "Changed dependency cache policy"):
                    bootstrap.audit_workflow(mutated)

    def test_cache_proof_cannot_be_moved_after_publication(self):
        proof = bootstrap.CACHE_PROOF_STEP
        publish = bootstrap.CACHE_PUBLISH_STEP
        mutated = self.workflow.replace(proof + publish, publish + proof, 1)
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "Changed dependency cache policy"):
            bootstrap.audit_workflow(mutated)

    def test_build_and_test_gradle_invocations_must_be_offline(self):
        offline_lines = [
            line for line in self.workflow.splitlines()
            if "./gradlew " in line and "--offline" in line.split()
            and "--warning-mode=fail" not in line
        ]
        self.assertGreaterEqual(len(offline_lines), 10)
        for line in offline_lines:
            with self.subTest(line=line.strip()[:90]):
                mutated = self.workflow.replace(line, line.replace(" --offline", "", 1), 1)
                self.assertNotEqual(mutated, self.workflow)
                with self.assertRaisesRegex(AssertionError, "reach registries"):
                    bootstrap.audit_workflow(mutated)

    def test_consumers_cannot_resolve_from_registries(self):
        for job in bootstrap.CONSUMER_JOBS:
            with self.subTest(job=job):
                section = self.workflow.split(f"\n  {job}:\n", 1)[1]
                anchor = bootstrap.CACHE_VERIFY_DOWNLOAD_STEP
                self.assertIn(anchor, section)
                mutated = self.workflow.replace(
                    f"\n  {job}:\n" + section.split(anchor, 1)[0] + anchor,
                    f"\n  {job}:\n" + section.split(anchor, 1)[0] + anchor
                    + bootstrap.CACHE_PLUGIN_STEP, 1,
                )
                self.assertNotEqual(mutated, self.workflow)
                with self.assertRaisesRegex(AssertionError, "Changed dependency cache policy"):
                    bootstrap.audit_workflow(mutated)

    def test_production_compilation_cannot_precede_dependency_resolution(self):
        compile_name = "      - name: Compile and install production artifacts without test execution\n"
        start = self.workflow.index(compile_name)
        compile_step = self.workflow[start:self.workflow.index("\n      - ", start + 1) + 1]
        mutated = self.workflow.replace(compile_step, "", 1).replace(
            bootstrap.CACHE_RESOLVE_STEP, compile_step + bootstrap.CACHE_RESOLVE_STEP, 1,
        )
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "precedes dependency resolution"):
            bootstrap.audit_workflow(mutated)

    def test_workflow_gradle_opts_are_fixed(self):
        for replacement in (
            "env:\n  GRADLE_OPTS: -Dorg.gradle.caching=true\n",
            "env:\n  GRADLE_OPTS: -Dorg.gradle.caching=false -Dhttps.proxyHost=evil\n",
            "",
        ):
            with self.subTest(replacement=replacement):
                mutated = self.workflow.replace(bootstrap.GRADLE_OPTS_BLOCK, replacement, 1)
                self.assertNotEqual(mutated, self.workflow)
                with self.assertRaisesRegex(AssertionError, "GRADLE_OPTS"):
                    bootstrap.audit_workflow(mutated)
        mutated = self.workflow.replace(
            "      - name: Install SHA-256-verified Gradle 9.8.2\n",
            "      - name: Install SHA-256-verified Gradle 9.8.2\n"
            "        env:\n          GRADLE_OPTS: -Dorg.gradle.caching=true\n", 1,
        )
        with self.assertRaisesRegex(AssertionError, "GRADLE_OPTS"):
            bootstrap.audit_workflow(mutated)

    def test_cache_key_covers_every_dependency_input(self):
        tracked = [
            "settings.gradle.kts", "app/cli/build.gradle.kts",
            "build-logic/src/main/kotlin/recursivecoder.jvm.gradle.kts",
            "build-logic/gradle/libs.versions.toml", "gradle/verification-metadata.xml",
            "build-logic/gradle/verification-metadata.xml", "gradle.properties",
            "tools/resolve_ci_dependencies.init.gradle", "README.md",
        ]
        bootstrap.audit_cache_key_coverage(tracked)
        # The previous key hashed only build.gradle.kts files and missed the
        # convention plugin that declares the JUnit runtime dependencies.
        legacy = bootstrap.CACHE_KEY.replace("'**/*.gradle.kts'",
                                             "'**/settings.gradle.kts', '**/build.gradle.kts'")
        self.assertNotEqual(legacy, bootstrap.CACHE_KEY)
        with self.assertRaisesRegex(AssertionError, "recursivecoder.jvm.gradle.kts"):
            bootstrap.audit_cache_key_coverage(tracked, legacy)
        for path in ("gradle/libs.versions.toml", "tools/extra.init.gradle",
                     "sub/gradle.properties"):
            with self.subTest(path=path):
                with self.assertRaisesRegex(AssertionError, "missing from the cache key"):
                    bootstrap.audit_cache_key_coverage([*tracked, path])

    def test_cache_key_glob_translation(self):
        pattern = bootstrap.glob_regex("**/*.gradle.kts")
        for path in ("settings.gradle.kts", "a/b/c.gradle.kts"):
            self.assertIsNotNone(pattern.match(path), path)
        for path in ("a/b.gradle.kts.bak", "a/b.gradle"):
            self.assertIsNone(pattern.match(path), path)
        self.assertIsNone(bootstrap.glob_regex("tools/*.py").match("tools/a/b.py"))

    def test_cache_action_pin_change_rejected(self):
        mutated = self.workflow.replace(
            "actions/cache/restore@caa296126883cff596d87d8935842f9db880ef25",
            "actions/cache/restore@" + "0" * 40, 1,
        )
        self.assertNotEqual(mutated, self.workflow)
        with self.assertRaisesRegex(AssertionError, "Changed GitHub Actions pin"):
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



def pin_for(path: Path, coordinate: tuple[str, str, str]) -> dict:
    return {(*coordinate, path.name): hashlib.sha256(path.read_bytes()).hexdigest()}


class ModuleFilesCacheTest(unittest.TestCase):
    def test_coordinate_pinned_bytes_only_and_corruption_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            source = root / "source"
            directory = source / "org.example" / "module" / "1.0" / ("a" * 40)
            directory.mkdir(parents=True)
            jar = directory / "module-1.0.jar"
            jar.write_bytes(b"pinned artifact")
            unknown = directory / "unknown.jar"
            unknown.write_bytes(b"unapproved")
            pins = pin_for(jar, ("org.example", "module", "1.0"))
            staged = root / "stage"
            with mock.patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(root / "summary.md")}):
                self.assertEqual(
                    dependency.copy_pinned_module_files(source, staged, pins, staging=True), 1,
                )
            self.assertEqual(list(staged.rglob("*.jar")), [staged / jar.relative_to(source)])
            self.assertIn("`org.example:module:1.0/unknown.jar`",
                          (root / "summary.md").read_text(encoding="utf-8"))
            self.assertEqual(
                dependency.copy_pinned_module_files(staged, root / "restore", pins), 1,
            )
            (staged / jar.relative_to(source)).write_bytes(b"corrupt artifact")
            for staging in (False, True):
                with self.subTest(staging=staging):
                    with self.assertRaisesRegex(AssertionError, "differ from their SHA-256 pin"):
                        dependency.copy_pinned_module_files(
                            staged, root / "rejected", pins, staging=staging,
                        )
                    self.assertFalse((root / "rejected").exists())

    def test_pinned_bytes_under_another_coordinate_are_not_transported(self):
        # A digest pinned for one coordinate does not authorize another path.
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            source = root / "source"
            pinned = source / "org.example/module/1.0" / ("a" * 40) / "module-1.0.jar"
            moved = source / "org.example/other/9.9" / ("a" * 40) / "other-9.9.jar"
            for path in (pinned, moved):
                path.parent.mkdir(parents=True)
                path.write_bytes(b"same bytes")
            pins = pin_for(pinned, ("org.example", "module", "1.0"))
            with self.assertRaisesRegex(AssertionError, "Unpinned restored module coordinate"):
                dependency.copy_pinned_module_files(source, root / "restore", pins)
            stage = root / "stage"
            self.assertEqual(
                dependency.copy_pinned_module_files(source, stage, pins, staging=True), 1,
            )
            self.assertFalse((stage / moved.relative_to(source)).exists())

    def test_gradle_sha1_directory_accepts_omitted_leading_zeroes(self):
        # The cache path is a layout check, not the SHA-256 trust authority.
        for name in ("0", "a", "9fdaf45aaba0a282247d79f8be67b0b4ac40127",
                     "a" * 39, "a" * 40):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as raw:
                root = Path(raw)
                source = root / "source"
                relative = Path("g/m/1") / name / "m.jar"
                jar = source / relative
                jar.parent.mkdir(parents=True)
                jar.write_bytes(b"pinned")
                pins = pin_for(jar, ("g", "m", "1"))
                stage = root / "stage"
                self.assertEqual(
                    dependency.copy_pinned_module_files(source, stage, pins, staging=True), 1,
                )
                restored = root / "restore"
                self.assertEqual(
                    dependency.copy_pinned_module_files(stage, restored, pins), 1,
                )
                self.assertEqual((restored / relative).read_bytes(), b"pinned")
                (stage / relative).write_bytes(b"tampered")
                with self.assertRaisesRegex(AssertionError, "differ from their SHA-256 pin"):
                    dependency.copy_pinned_module_files(stage, root / "rejected", pins)
                self.assertFalse((root / "rejected").exists())

    def test_gradle_sha1_directory_rejects_malformed_names(self):
        for name in ("a" * 41, "A" * 40, "g", "0x01", "-1", "1.2"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as raw:
                root = Path(raw)
                source = root / "source"
                jar = source / "g" / "m" / "1" / name / "m.jar"
                jar.parent.mkdir(parents=True)
                jar.write_bytes(b"pinned")
                pins = pin_for(jar, ("g", "m", "1"))
                for staging in (False, True):
                    with self.subTest(staging=staging):
                        with self.assertRaisesRegex(AssertionError, "Unsafe module cache entry"):
                            dependency.copy_pinned_module_files(
                                source, root / "rejected", pins, staging=staging,
                            )
                        self.assertFalse((root / "rejected").exists())

    def test_symlink_hardlink_and_unexpected_layout_rejected(self):
        for kind in ("symlink", "hardlink", "layout"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as raw:
                root = Path(raw)
                source = root / "source"
                source.mkdir()
                original = root / "original"
                original.write_bytes(b"pinned")
                entry = source / "g" / "m" / "1" / ("b" * 40) / "m.jar"
                entry.parent.mkdir(parents=True)
                if kind == "symlink":
                    entry.symlink_to(original)
                elif kind == "hardlink":
                    os.link(original, entry)
                else:
                    (source / "metadata.bin").write_bytes(original.read_bytes())
                pins = {("g", "m", "1", "m.jar"): hashlib.sha256(b"pinned").hexdigest()}
                with self.assertRaisesRegex(AssertionError, "Unsafe module cache entry"):
                    dependency.copy_pinned_module_files(source, root / "restore", pins)

    def test_module_cache_pins_come_from_both_manifests(self):
        pins = dependency.module_cache_pins()
        for path in dependency.MANIFESTS:
            for key, digest in dependency.manifest_pins(path).items():
                self.assertEqual(pins[key], digest)

    def test_module_cache_roundtrip_excludes_locks_and_keeps_metadata(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            source = root / "source"
            entry = source / "files-2.1/g/m/1" / ("a" * 40) / "m.jar"
            entry.parent.mkdir(parents=True)
            entry.write_bytes(b"pinned")
            metadata = source / "metadata-2.107"
            metadata.mkdir()
            (metadata / "module-metadata.bin").write_bytes(b"producer state")
            (metadata / "module-metadata.bin.lock").write_bytes(b"lock")
            (source / "modules-2.lock").write_bytes(b"lock")
            (source / "gc.properties").write_bytes(b"gc")
            pins = pin_for(entry, ("g", "m", "1"))
            stage = root / "stage"
            dependency.copy_module_cache(source, stage, pins, staging=True)
            self.assertEqual({p.name for p in stage.iterdir()}, {"files-2.1", "metadata-2.107"})
            self.assertEqual(list((stage / "metadata-2.107").iterdir()),
                             [stage / "metadata-2.107/module-metadata.bin"])
            restored = root / "restored"
            dependency.copy_module_cache(stage, restored, pins)
            self.assertEqual((restored / "metadata-2.107/module-metadata.bin").read_bytes(),
                             b"producer state")
            for unexpected in ("gc.properties", "init.gradle", "metadata-2.999"):
                with self.subTest(unexpected=unexpected):
                    invalid = stage / unexpected
                    invalid.write_bytes(b"unexpected")
                    with self.assertRaisesRegex(AssertionError, "Unexpected modules-2 entry"):
                        dependency.copy_module_cache(stage, root / "refused", pins)
                    self.assertFalse((root / "refused").exists())
                    invalid.unlink()
            metadata_entry = stage / "metadata-2.107/module-metadata.bin"
            metadata_entry.unlink()
            metadata_entry.symlink_to(entry)
            with self.assertRaisesRegex(AssertionError, "Unsafe module metadata file"):
                dependency.copy_module_cache(stage, root / "refused", pins)


class GradleResolutionRetryTest(unittest.TestCase):
    COMMAND = ["./gradlew", "--dependency-verification", "strict", "--no-build-cache", "help"]

    def test_transient_resolution_is_bounded(self):
        with mock.patch.object(ci_gradle, "run_once", return_value=(1, 4, True, False)) as run:
            with mock.patch.object(ci_gradle.time, "sleep") as sleep:
                self.assertEqual(ci_gradle.run(self.COMMAND, "fixture", retry_resolution=True), 1)
            self.assertEqual(run.call_count, 3)
            self.assertEqual(sleep.call_args_list, [mock.call(5), mock.call(15)])

    def test_verification_failure_or_nontransient_failure_never_retries(self):
        for transient, verification in ((True, True), (False, False)):
            with mock.patch.object(ci_gradle, "run_once",
                                   return_value=(1, 1, transient, verification)) as run:
                with mock.patch.object(ci_gradle.time, "sleep") as sleep:
                    self.assertEqual(ci_gradle.run(
                        self.COMMAND, "fixture", retry_resolution=True,
                    ), 1)
                run.assert_called_once()
                sleep.assert_not_called()

    def test_only_online_resolution_tasks_may_be_measured(self):
        base = ["./gradlew", "--dependency-verification", "strict", "--no-build-cache"]
        for task in ("help", ":resolveCiDependencies"):
            ci_gradle.validate([*base, task])
        for command in ([*base, "test"], [*base, "installDist"],
                        [*base, "--offline", "help"],
                        ["./gradlew", "--no-build-cache", "help"],
                        ["./gradlew", "--dependency-verification", "strict", "help"]):
            with self.subTest(command=command):
                with self.assertRaises(ValueError):
                    ci_gradle.validate(command)

    def test_debug_continuations_follow_their_event_visibility(self):
        lines = [
            "Starting a Gradle Daemon\n",
            "2026-11-26T11:02:18.009+0000 [ERROR] [org.gradle.Reporter] * What went wrong:\n",
            "Execution failed for task ':app:cli:compileKotlin'.\n",
            "\tat org.example.Frame.run(Frame.kt:1)\n",
            "2026-11-26T11:02:18.010+0000 [DEBUG] [org.gradle.Http] Request headers:\n",
            "Authorization: Bearer secret-token\n",
            "2026-11-26T11:02:18.011+0000 [INFO] [org.gradle.Info] details\n",
            "more details\n",
            "2026-11-26T11:02:18.012+0000 [LIFECYCLE] [org.gradle.Result] BUILD FAILED\n",
        ]
        visible = [line for line, shown in ci_gradle.visible_lines(lines) if shown]
        self.assertEqual(visible, [lines[0], lines[1], lines[2], lines[3], lines[8]])

    def test_transient_status_in_failure_continuation_triggers_retry(self):
        output = (
            "2026-11-26T11:02:18.009+0000 [ERROR] [org.gradle.Reporter] * What went wrong:\n"
            "> Could not GET 'https://repo.maven.apache.org/maven2/a.pom'. "
            "Received status code 429 from server: Too Many Requests\n"
        )
        fake = mock.MagicMock()
        fake.__enter__.return_value = fake
        fake.stdout = iter(output.splitlines(keepends=True))
        fake.wait.return_value = 1
        with mock.patch.object(ci_gradle.subprocess, "Popen", return_value=fake):
            with mock.patch("builtins.print"):
                self.assertEqual(ci_gradle.run_once(self.COMMAND), (1, 0, True, False))

    def test_registry_http_operation_and_status_patterns(self):
        self.assertIsNotNone(ci_gradle.HTTP_OPERATION.search(
            "Performing HTTP GET: https://repo.maven.apache.org/maven2/test.pom",
        ))
        self.assertIsNone(ci_gradle.HTTP_OPERATION.search(
            "Performing HTTP GET: https://example.org/test.pom",
        ))
        self.assertIsNotNone(ci_gradle.TRANSIENT.search("Received status code 429 from server"))
        self.assertIsNotNone(ci_gradle.TRANSIENT.search("Received status code 503 from server"))
        self.assertIsNone(ci_gradle.TRANSIENT.search("Received status code 404 from server"))


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
