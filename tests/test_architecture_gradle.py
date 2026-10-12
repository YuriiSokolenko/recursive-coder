"""Regression checks for architecture rules using temporary Gradle init scripts."""

from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class ArchitectureBoundaryTest(unittest.TestCase):
    def run_with_test_edge(self, source: str, target: str) -> str:
        init_script = (
            "gradle.projectsEvaluated {\n"
            f"    def owner = gradle.rootProject.project('{source}')\n"
            "    owner.dependencies.add('testImplementation', "
            f"owner.dependencies.project(path: '{target}'))\n"
            "}\n"
        )
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
        output = self.run_with_test_edge(":core:session", ":infra:model")
        self.assertIn(
            "Forbidden project dependency :core:session:testImplementation -> :infra:model",
            output,
        )

    def test_test_only_edge_cannot_complete_a_cycle(self):
        output = self.run_with_test_edge(":core:contracts", ":core:session")
        self.assertIn(
            "Project dependency cycle (including tests): "
            ":core:contracts -> :core:session -> :core:contracts",
            output,
        )


if __name__ == "__main__":
    unittest.main()
