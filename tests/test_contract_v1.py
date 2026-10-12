"""Full JSON Schema 2020-12 compatibility checks for the canonical v1 wire protocol."""

import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "contracts" / "v1"
SCHEMA = json.loads((CONTRACTS / "session-v1.schema.json").read_text(encoding="utf-8"))
EXAMPLES = json.loads((CONTRACTS / "session-v1.examples.json").read_text(encoding="utf-8"))


class SessionV1ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        Draft202012Validator.check_schema(SCHEMA)
        cls.validator = Draft202012Validator(SCHEMA, format_checker=FormatChecker())

    def assert_contract_valid(self, value):
        self.assertEqual(
            list(self.validator.iter_errors(value)), [],
            "Expected wire envelope to satisfy the draft 2020-12 schema",
        )

    def assert_contract_rejected(self, value):
        self.assertNotEqual(
            list(self.validator.iter_errors(value)), [],
            "Unsafe/incompatible wire envelope unexpectedly validated",
        )

    def test_all_seven_positive_wire_fixtures(self):
        for name, value in EXAMPLES.items():
            with self.subTest(name=name):
                self.assert_contract_valid(value)
        self.assertEqual(len(EXAMPLES), 7)

    def test_forward_minor_extensions_are_namespaced(self):
        forward = copy.deepcopy(EXAMPLES["forwardMinorRequest"])
        self.assert_contract_valid(forward)
        forward["newRootField"] = True
        self.assert_contract_rejected(forward)
        del forward["newRootField"]
        forward["extensions"] = {"unsafe": {"enabled": True}}
        self.assert_contract_rejected(forward)

    def test_major_and_patch_versions_are_rejected(self):
        for version in ["2.0", "1.0.0", "1", "latest"]:
            request = copy.deepcopy(EXAMPLES["freshRequest"])
            request["version"] = version
            with self.subTest(version=version):
                self.assert_contract_rejected(request)

    def test_resume_requires_checkpoint_and_fencing(self):
        request = copy.deepcopy(EXAMPLES["resumeRequest"])
        request["resume"]["checkpoint"] = None
        self.assert_contract_rejected(request)
        request = copy.deepcopy(EXAMPLES["freshRequest"])
        request["resume"]["checkpoint"] = EXAMPLES["resumeRequest"]["resume"]["checkpoint"]
        self.assert_contract_rejected(request)
        self.assertGreater(
            EXAMPLES["resumeRequest"]["resume"]["fenceGeneration"],
            EXAMPLES["checkpoint"]["fenceGeneration"],
        )

    def test_error_and_credential_shapes(self):
        result = copy.deepcopy(EXAMPLES["successResult"])
        result["error"] = EXAMPLES["failureResult"]["error"]
        self.assert_contract_rejected(result)
        result = copy.deepcopy(EXAMPLES["failureResult"])
        result["error"] = None
        self.assert_contract_rejected(result)
        request = copy.deepcopy(EXAMPLES["freshRequest"])
        request["model"]["credentialRef"] = "PATH"
        self.assert_contract_rejected(request)

    def test_path_traversal_and_required_baseline(self):
        request = copy.deepcopy(EXAMPLES["freshRequest"])
        request["outputs"]["resultPath"] = "/tmp/../private"
        self.assert_contract_rejected(request)
        request = copy.deepcopy(EXAMPLES["freshRequest"])
        del request["workspace"]["baselineSha"]
        self.assert_contract_rejected(request)

    def test_native_invocation_is_not_tied_to_external_orchestration(self):
        request = copy.deepcopy(EXAMPLES["freshRequest"])
        self.assertEqual(request["invocation"]["source"], "cli")
        request["invocation"]["source"] = "unknown"
        self.assert_contract_rejected(request)

    def test_canonical_schema_source(self):
        self.assertEqual(
            SCHEMA["$id"],
            "https://raw.githubusercontent.com/YuriiSokolenko/recursive-coder/dev/contracts/v1/session-v1.schema.json",
        )


if __name__ == "__main__":
    unittest.main()
