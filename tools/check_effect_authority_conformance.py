#!/usr/bin/env python3
"""Effect authority-specific registrations and required contract methods."""
import sys

from check_adapter_conformance import validate as validate_contract, main as run

PACKAGE = "ai/yuriilab/recursivecoder/"
PORT = PACKAGE + "HostEffectAntiRollbackAuthority"
CONTRACT = PACKAGE + "EffectAuthorityAdapterContract"
REGISTRATION = PACKAGE + "EffectAuthorityConformance"
REQUIRED = {
    PACKAGE + "SshEffectAuthorityConformanceIntegrationTest":
        PACKAGE + "SshEffectAntiRollbackAuthority",
}
REQUIRED_METHODS = {
    "initializationFreshReadAndExactCas",
    "immutableHistoryAndOperationCheckpointHeadUniqueness",
    "successorGenerationCannotReuseHistoricalAnchorOrPath",
    "concurrentIndependentProcessesYieldExactlyOneDurableTransition",
    "failureBeforeAppendAndLostResponseNeverBecomeAcknowledgements",
    "crashBeforeSecondForceDoesNotProduceAcknowledgement",
    "crashBetweenDurabilityBoundariesQuarantinesWithoutRepair",
    "wrongCredentialsForeignIdentityAndUnavailableFailClosed",
    "oneSidedTruncationIsUnavailableAndCannotBootstrap",
    "coherentAuthorityDomainRollbackIsOutsideThreatModel",
    "malformedAuthenticatedRepliesAndForeignRootsNeverAuthorize",
    "corruptBothHistoriesAndResetAreNotProvisioning",
    "timeoutAndInterruptionQuarantineInvocation",
    "replayedAuthenticatedResponseIsNotFresh",
}

def validate(class_dirs, report_dirs, required=REQUIRED, required_methods=REQUIRED_METHODS):
    return validate_contract(class_dirs, report_dirs, host=PORT, contract=CONTRACT,
                             registration=REGISTRATION, required_suites=required,
                             label="effect authority", required_methods=required_methods,
                             strict=True)


if __name__ == "__main__":
    sys.exit(run(validate, "Effect authority"))
