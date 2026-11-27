#!/usr/bin/env python3
"""Preparation-specific registrations and pending profiles."""
import sys

from check_adapter_conformance import validate as validate_contract, main as run

PACKAGE = "ai/yuriilab/recursivecoder/"
HOST = PACKAGE + "PreparationStageHost"
CONTRACT = PACKAGE + "PreparationStageHostContract"
REGISTRATION = PACKAGE + "PreparationHostConformance"
PENDING_PROFILE = "L" + PACKAGE + "PreparationPendingProfile;"
REFUSED_ADAPTER = PACKAGE + "LinuxFiveRoleWorkflowHost"
TEST = "org/junit/jupiter/api/Test"
REQUIRED_SUITES = {
    PACKAGE + "RunPreparationStageIntegrationTest":
        PACKAGE + "RunPreparationStageIntegrationTest$FakeHost",
    PACKAGE + "RunDeveloperStageIntegrationTest":
        PACKAGE + "RunDeveloperStageIntegrationTest$FakeHost",
    PACKAGE + "LinuxFiveRoleWorkflowHostTest":
        PACKAGE + "LinuxFiveRoleWorkflowHost",
}

def pending_profile(suite, adapter, values):
    profile = values.get("pending", (PENDING_PROFILE, "FULL"))
    expected = "REFUSED" if adapter == REFUSED_ADAPTER else "FULL"
    if profile != (PENDING_PROFILE, expected):
        return [f"{suite}: invalid pending conformance profile {profile}; expected {expected}"]
    return []


def validate(class_dirs, report_dirs, required_suites=REQUIRED_SUITES):
    return validate_contract(class_dirs, report_dirs, host=HOST, contract=CONTRACT,
                             registration=REGISTRATION, required_suites=required_suites,
                             label="preparation", profile_validator=pending_profile)


if __name__ == "__main__":
    sys.exit(run(validate, "Preparation host"))
