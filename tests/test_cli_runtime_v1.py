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

    def test_clikt_help_is_read_only_and_successful(self):
        self.assertTrue(CLI.exists(), "Build the CLI distribution first")
        with tempfile.TemporaryDirectory() as dirname:
            store = Path(dirname) / "uncreated-store"
            for args, expected in (
                (("--help",), "tasks"),
                (("run", "--help"), "--fake-delay-ms"),
                (("tasks", "--help"), "enqueue"),
                (("tasks", "--store", str(store), "enqueue", "--help"), "--priority"),
                (("tasks", "--store", str(store), "run", "--help"), "--concurrency"),
            ):
                with self.subTest(args=args):
                    cp = command(str(CLI), *args)
                    self.assertEqual(cp.returncode, 0, cp.stderr)
                    self.assertIn(expected, cp.stdout)
                    self.assertEqual(cp.stderr, "")
                    self.assertFalse(store.exists())

    def test_clikt_rejects_bad_task_syntax_before_creating_store(self):
        self.assertTrue(CLI.exists(), "Build the CLI distribution first")
        with tempfile.TemporaryDirectory() as dirname:
            temp = Path(dirname)
            store = temp / "uncreated-store"
            request_path = temp / "request.json"
            request_path.write_text("{}")
            prefix = ("tasks", "--store", str(store))
            invalid = (
                ("enqueue",),
                ("enqueue", str(request_path), "--priority"),
                ("enqueue", str(request_path), "--priority", "abc"),
                ("enqueue", str(request_path), "--priority", "1.5"),
                ("enqueue", str(request_path), "--priority", "1", "--priority", "2"),
                ("enqueue", str(request_path), "--depends-on", "first,,second"),
                ("enqueue", str(request_path), "--depends-on", ""),
                ("enqueue", str(request_path), "--depends-on", "one", "--depends-on", "two"),
                ("run", "--concurrency"),
                ("run", "--concurrency", "two"),
                ("run", "--concurrency", "1", "--concurrency", "2"),
                ("cancel",),
                ("show", "one", "two"),
                ("list", "--unknown"),
                ("list", "--store", str(store)),
                ("does-not-exist",),
            )
            for args in invalid:
                with self.subTest(args=args):
                    cp = command(str(CLI), *prefix, *args)
                    self.assertEqual(cp.returncode, 2, cp.stdout + cp.stderr)
                    self.assertEqual(cp.stdout, "")
                    self.assertTrue(cp.stderr.strip())
                    self.assertFalse(store.exists(), "Parser created a store on invalid syntax")

    def test_clikt_rejects_bad_session_syntax_without_output(self):
        self.assertTrue(CLI.exists(), "Build the CLI distribution first")
        with tempfile.TemporaryDirectory() as dirname:
            temp = Path(dirname)
            request = self.request(temp)
            source = temp / "request.json"
            source.write_text(json.dumps(request))
            for args in (
                ("run",),
                ("run", str(source), "--unknown"),
                ("run", str(source), "--fake-delay-ms"),
                ("run", str(source), "--fake-delay-ms", "nan"),
                ("run", str(source), "--fake-delay-ms", "2", "--fake-delay-ms", "3"),
                ("run", str(source), "--fake-delay-ms", "60001"),
                ("run", str(source), "extra"),
                ("no-such-command",),
            ):
                with self.subTest(args=args):
                    cp = command(str(CLI), *args)
                    self.assertEqual(cp.returncode, 2, cp.stdout + cp.stderr)
                    self.assertEqual(cp.stdout, "")
                    self.assertTrue(cp.stderr.strip())
                    self.assertFalse(Path(request["outputs"]["rootDir"]).exists(),
                                     "Malformed CLI started a session")

    def test_clikt_enqueue_options_and_defaults(self):
        self.assertTrue(CLI.exists(), "Build the CLI distribution first")
        with tempfile.TemporaryDirectory() as dirname:
            temp = Path(dirname)
            request = self.request(temp)
            source = temp / "request.json"
            source.write_text(json.dumps(request))
            store = temp / "queue"
            prefix = ("tasks", "--store", str(store))
            first = command(str(CLI), *prefix, "enqueue", str(source), "--priority", "70")
            self.assertEqual(first.returncode, 0, first.stderr)
            task = json.loads(first.stdout)
            self.assertEqual(task["priority"], 70)
            self.assertEqual(task["dependencies"], [])
            # The second task depends on the existing native task ID.
            second = json.loads(source.read_text())
            second["task"]["taskId"] = "other-task"
            second["sessionId"] = "session-other-task"
            second["requestId"] = "request-other-task"
            second["traceId"] = "trace-other-task"
            second_source = temp / "second.json"
            second_source.write_text(json.dumps(second))
            cp = command(str(CLI), *prefix, "enqueue", str(second_source),
                         "--depends-on", task["taskId"])
            self.assertEqual(cp.returncode, 0, cp.stderr)
            self.assertEqual(json.loads(cp.stdout)["priority"], 50)
            self.assertEqual(json.loads(cp.stdout)["dependencies"], [task["taskId"]])
            listed = command(str(CLI), *prefix, "list")
            self.assertEqual(listed.returncode, 0, listed.stderr)
            self.assertEqual(len(json.loads(listed.stdout)), 2)

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
