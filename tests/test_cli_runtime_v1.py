"""Run the installed CLI, then validate its real output against the canonical v1 schema."""
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = Draft202012Validator(
    json.loads((ROOT / "contracts/v1/session-v1.schema.json").read_text()),
    format_checker=FormatChecker(),
)
CLI = ROOT / "build/install/recursive-coder/bin/recursive-coder"


def command(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, text=True, capture_output=True, timeout=30)


class CliContractTest(unittest.TestCase):
    def request(self, temp):
        workspace = temp / "workspace"
        workspace.mkdir()
        for args in (
            ("git", "init", "-b", "dev"),
            ("git", "config", "user.name", "Fake Test"),
            ("git", "config", "user.email", "fake@example.invalid"),
        ):
            cp = command(*args, cwd=workspace)
            self.assertEqual(cp.returncode, 0, cp.stderr)
        task = workspace / "task.md"
        task.write_text("TOP_SECRET_FIXTURE_DATA: inspect this task.")
        for args in (("git", "add", "task.md"), ("git", "commit", "-m", "fixture")):
            cp = command(*args, cwd=workspace)
            self.assertEqual(cp.returncode, 0, cp.stderr)
        head = command("git", "rev-parse", "HEAD", cwd=workspace).stdout.strip()
        out = temp / "out"
        return {
            "kind": "session-request", "version": "1.0",
            "sessionId": "session-cli-4", "requestId": "request-cli-4", "traceId": "trace-cli-4",
            "invocation": {"source": "cli", "invocationId": "invocation-cli-4", "attempt": 1},
            "workspace": {"workspaceId": "workspace-cli-4", "rootPath": str(workspace),
                          "baselineSha": head, "headSha": head, "branch": "dev"},
            "task": {"stage": "sample", "role": "reader", "taskId": "task-cli-4",
                     "taskInputRef": {"path": str(task),
                                      "sha256": hashlib.sha256(task.read_bytes()).hexdigest()},
                     "parentAgentId": None},
            "model": {"provider": "fake", "modelId": "fake-v1",
                      "baseUrl": "http://fake.invalid/v1", "credentialRef": "env:FAKE_TOKEN",
                      "contextTokens": 8192, "maxOutputTokens": 128, "features": ["tool_calls"]},
            "toolPolicy": {"mode": "read-only", "allowedTools": ["read"],
                           "deniedTools": ["safe_edit", "git_push"],
                           "protectedPaths": [".git/**"], "writablePaths": [],
                           "policySha256": "b" * 64},
            "budgets": {"deadlineAt": (datetime.datetime.now(datetime.timezone.utc)
                        + datetime.timedelta(seconds=20)).isoformat(),
                        "maxWallTimeMs": 12000, "maxTotalTokens": 10000,
                        "maxToolCalls": 1, "maxModelRequests": 2,
                        "maxChildAgents": 0, "maxDepth": 0},
            "outputs": {"rootDir": str(out), "resultPath": str(out / "result.json"),
                        "eventsPath": str(out / "events.jsonl"),
                        "checkpointDir": str(out / "checkpoints"),
                        "diagnosticsPath": str(out / "diagnostics.json")},
            "resume": {"mode": "fresh", "fenceGeneration": 1, "checkpoint": None},
            "requiredCapabilities": ["session-events-v1", "fake-model-v1", "read-only-tools-v1"],
        }

    def test_real_cli_produces_canonical_result_and_events(self):
        self.assertTrue(CLI.exists(), "Build the CLI distribution first")
        with tempfile.TemporaryDirectory() as dirname:
            temp = Path(dirname)
            request = self.request(temp)
            VALIDATOR.validate(request)
            source = temp / "request.json"
            source.write_text(json.dumps(request))
            run = command(str(CLI), "run", str(source))
            self.assertEqual(run.returncode, 0, run.stderr + run.stdout)
            result = json.loads(run.stdout)
            VALIDATOR.validate(result)
            self.assertEqual(result["status"], "succeeded")
            self.assertEqual(result, json.loads(Path(request["outputs"]["resultPath"]).read_text()))
            events = [json.loads(line) for line in Path(request["outputs"]["eventsPath"]).read_text().splitlines()]
            self.assertGreaterEqual(len(events), 9)
            for event in events:
                VALIDATOR.validate(event)
                self.assertEqual(event["kind"], "session-event")
            self.assertEqual([e["sequence"] for e in events], list(range(1, len(events) + 1)))
            self.assertEqual(events[-1]["transition"]["to"], "succeeded")
            self.assertEqual(result["lastEventSeq"], len(events))
            self.assertEqual(sum(e["type"] == "tool.completed" for e in events), 1)
            self.assertNotIn("TOP_SECRET_FIXTURE_DATA", Path(request["outputs"]["eventsPath"]).read_text())
            self.assertNotIn("FAKE_TOKEN", Path(request["outputs"]["eventsPath"]).read_text())

    def test_unknown_minor_version_is_rejected_without_model(self):
        with tempfile.TemporaryDirectory() as dirname:
            temp = Path(dirname)
            request = self.request(temp)
            request["version"] = "1.1"
            source = temp / "request.json"
            source.write_text(json.dumps(request))
            run = command(str(CLI), "run", str(source))
            self.assertEqual(run.returncode, 2, run.stderr)
            result = json.loads(run.stdout)
            VALIDATOR.validate(result)
            self.assertEqual(result["status"], "rejected")
            for line in Path(request["outputs"]["eventsPath"]).read_text().splitlines():
                event = json.loads(line)
                VALIDATOR.validate(event)
                self.assertNotEqual(event["type"], "llm.request")


if __name__ == "__main__":
    unittest.main()
