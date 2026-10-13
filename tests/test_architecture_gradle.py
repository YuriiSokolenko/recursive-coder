"""Regression checks for architecture rules using temporary Gradle init scripts."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class ArchitectureBoundaryTest(unittest.TestCase):
    def run_with_edge(self, source: str, target: str, configuration: str) -> str:
        init_script = (
            "gradle.projectsEvaluated {\n"
            f"    if (gradle.rootProject.findProject('{source}') != null) {{\n"
            f"        def owner = gradle.rootProject.project('{source}')\n"
            "        owner.dependencies.add('PLACEHOLDER', "
            f"owner.dependencies.project(path: '{target}'))\n"
            "    }\n"
            "}\n"
        )
        init_script = init_script.replace("PLACEHOLDER", configuration)
        return self.run_with_init_script(init_script)

    def run_with_init_script(self, init_script: str) -> str:
        with tempfile.TemporaryDirectory() as directory:
            init_file = Path(directory) / "illegal-edge.gradle"
            init_file.write_text(init_script, encoding="utf-8")
            run = subprocess.run(
                [
                    "./gradlew", "--no-daemon", "--offline", "--console=plain",
                    "--init-script", str(init_file), "checkArchitecture",
                ],
                cwd=ROOT,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=180,
                check=False,
            )
        self.assertNotEqual(0, run.returncode, run.stdout)
        return run.stdout

    def test_forbidden_core_to_adapter_test_dependency(self):
        output = self.run_with_edge(":core:session", ":infra:model", "testImplementation")
        self.assertIn(
            "Forbidden project dependency :core:session:testImplementation -> :infra:model",
            output,
        )

    def test_test_only_edge_cannot_complete_a_cycle(self):
        output = self.run_with_edge(":core:contracts", ":core:session", "testImplementation")
        self.assertIn(
            "Project dependency cycle (including tests): "
            ":core:contracts -> :core:session -> :core:contracts",
            output,
        )

    def test_forbidden_production_dependency(self):
        output = self.run_with_edge(":core:tasks", ":infra:workspace", "implementation")
        self.assertIn(
            "Forbidden project dependency :core:tasks:implementation -> :infra:workspace",
            output,
        )

    def test_unclassified_new_module(self):
        output = self.run_with_init_script(
            "gradle.settingsEvaluated { settings ->\n"
            "    if (settings.rootProject.name == 'recursive-coder') {\n"
            "        settings.include(':unknown')\n"
            "        settings.project(':unknown').projectDir = new File(settings.rootDir, 'docs')\n"
            "    }\n"
            "}\n"
        )
        self.assertIn("Unclassified module :unknown", output)

    def test_forbidden_core_framework_in_test_configuration(self):
        output = self.run_with_init_script(
            "gradle.projectsEvaluated {\n"
            "    if (gradle.rootProject.findProject(':core:session') != null) {\n"
            "        def owner = gradle.rootProject.project(':core:session')\n"
            "        owner.dependencies.add('testImplementation', 'io.ktor:ktor-client:1.0')\n"
            "    }\n"
            "}\n"
        )
        self.assertIn(
            "Forbidden core framework dependency :core:session:testImplementation -> io.ktor:ktor-client",
            output,
        )


class ArchitectureConfigurationCacheRegressionTest(unittest.TestCase):
    """A previously valid cache entry must not conceal new forbidden edges."""

    @staticmethod
    def copy_fixture(destination: Path) -> None:
        # Copy only tracked Gradle inputs and local build logic. No sources,
        # repository metadata or existing configuration cache enter the fixture.
        for relative in (
            "settings.gradle.kts",
            "build.gradle.kts",
            "gradle.properties",
            "app/cli/build.gradle.kts",
            "core/contracts/build.gradle.kts",
            "core/session/build.gradle.kts",
            "core/tasks/build.gradle.kts",
            "infra/model/build.gradle.kts",
            "infra/workspace/build.gradle.kts",
        ):
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        shutil.copytree(
            ROOT / "build-logic",
            destination / "build-logic",
            ignore=shutil.ignore_patterns(".gradle", "build", "__pycache__"),
        )

    @staticmethod
    def check_architecture(directory: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                str(ROOT / "gradlew"),
                "--no-daemon",
                "--offline",
                "--console=plain",
                "--configuration-cache",
                "checkArchitecture",
            ],
            cwd=directory,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=240,
            check=False,
        )

    def assert_valid(self, result: subprocess.CompletedProcess[str]) -> None:
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("Architecture boundaries verified for 6 modules.", result.stdout)

    def test_changed_build_script_invalidates_cache_for_production_and_test_edges(self):
        with tempfile.TemporaryDirectory(prefix="architecture-cache-") as temporary:
            fixture = Path(temporary)
            self.copy_fixture(fixture)

            cold = self.check_architecture(fixture)
            self.assert_valid(cold)
            self.assertIn("Configuration cache entry stored.", cold.stdout)

            warm = self.check_architecture(fixture)
            self.assert_valid(warm)
            self.assertIn("Reusing configuration cache.", warm.stdout)

            for module, configuration, target in (
                (":core:tasks", "implementation", ":infra:workspace"),
                (":core:session", "testImplementation", ":infra:model"),
            ):
                relative = Path(module.lstrip(":").replace(":", "/")) / "build.gradle.kts"
                build_file = fixture / relative
                original = build_file.read_text(encoding="utf-8")

                with self.subTest(module=module, configuration=configuration):
                    # Mutate a copied build input after a confirmed cache hit.
                    # The added dependency is declared, never resolved.
                    injection = (
                        f'\n\ndependencies {{ {configuration}(project("{target}")) }}\n'
                    )
                    build_file.write_text(original + injection, encoding="utf-8")
                    try:
                        rejected = self.check_architecture(fixture)
                        self.assertNotEqual(0, rejected.returncode, rejected.stdout)
                        self.assertIn(
                            f"Forbidden project dependency {module}:{configuration} -> {target}",
                            rejected.stdout,
                        )
                        self.assertIn(
                            "Calculating task graph as configuration cache cannot be reused",
                            rejected.stdout,
                        )
                        self.assertIn("has changed", rejected.stdout)
                        self.assertIn(relative.as_posix(), rejected.stdout.replace("\\", "/"))
                        self.assertNotIn("Reusing configuration cache.", rejected.stdout)
                    finally:
                        build_file.write_text(original, encoding="utf-8")

                    # Restoring exactly the original build input must pass,
                    # regardless of whether Gradle reuses or rebuilds the entry.
                    restored = self.check_architecture(fixture)
                    self.assert_valid(restored)
                    self.assertEqual(original, build_file.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
