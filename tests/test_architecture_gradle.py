"""Regression checks for architecture rules using temporary Gradle init scripts."""

from pathlib import Path
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


if __name__ == "__main__":
    unittest.main()
