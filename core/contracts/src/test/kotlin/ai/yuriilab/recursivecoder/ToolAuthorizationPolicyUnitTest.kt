package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Value-only authorization fixtures: no workspace, Git, sandbox, model or trusted clock. */
@Tag("unit")
class ToolAuthorizationPolicyUnitTest {
    private fun code(decision: ToolAuthorizationDecision): String? = decision.deniedCode

    @TestFactory
    fun allRoleAndWriteOperationCombinations(): List<DynamicTest> {
        val owned = mapOf(
            EngineeringRole.SDD to "specs/Next.md",
            EngineeringRole.ATDD to "tests/acceptance/Next.md",
            EngineeringRole.TDD to "src/test/NextTest.kt",
            EngineeringRole.DEVELOPER to "src/main/Next.kt"
        )
        val samples = owned.values + "tests/unit/Next.kt"
        val roles: List<EngineeringRole?> = EngineeringRole.entries + listOf(null)
        return roles.flatMap { role ->
            WriteOperationKind.entries.flatMap { kind ->
                samples.map { path ->
                    DynamicTest.dynamicTest("$role $kind $path") {
                        val permitted = when (role) {
                            EngineeringRole.SDD -> path.startsWith("specs/")
                            EngineeringRole.ATDD -> path.startsWith("tests/acceptance/")
                            EngineeringRole.TDD -> path.startsWith("src/test/") || path.startsWith("tests/unit/")
                            EngineeringRole.DEVELOPER -> path.startsWith("src/main/")
                            EngineeringRole.REVIEWER, null -> false
                        }
                        assertEquals(if (permitted) null else "ROLE_NOT_AUTHORIZED",
                            code(ToolAuthorizationPolicy.authorizeWrite(
                                WriteAuthorizationFacts(role, kind, path))))
                    }
                }
            }
        }
    }

    @Test
    fun readAllowDenyMatrixUsesDenyPrecedenceAndFailClosedUnknownTools() {
        val known = ToolAuthorizationPolicy.knownReadTools
        assertEquals(6, known.size)
        assertEquals(null, code(ToolAuthorizationPolicy.admitReadOnlyPolicy(
            ReadPolicyFacts("read-only", known, false, false))))
        for (tool in known) {
            assertEquals(null, code(ToolAuthorizationPolicy.authorizeRead(
                ReadAuthorizationFacts(tool, known, emptySet()))))
            assertEquals("TOOL_NOT_AUTHORIZED", code(ToolAuthorizationPolicy.authorizeRead(
                ReadAuthorizationFacts(tool, known, setOf(tool)))))
            assertEquals("READ_NOT_AUTHORIZED", code(ToolAuthorizationPolicy.authorizeRead(
                ReadAuthorizationFacts(tool, known, setOf(tool), taskAdmission = true))))
        }
        for (tool in listOf("safe_edit", "write", "run_check", "", "READ")) {
            assertEquals("TOOL_NOT_AUTHORIZED", code(ToolAuthorizationPolicy.authorizeRead(
                ReadAuthorizationFacts(tool, known + tool, emptySet()))))
            assertEquals("UNSUPPORTED_TOOL_POLICY", code(ToolAuthorizationPolicy.admitReadOnlyPolicy(
                ReadPolicyFacts("read-only", known + tool, false, false))))
        }
        assertEquals("TOOL_NOT_AUTHORIZED", code(ToolAuthorizationPolicy.authorizeRead(
            ReadAuthorizationFacts("read", setOf("list"), emptySet()))))
        for (facts in listOf(
            ReadPolicyFacts("read-write", known, false, false),
            ReadPolicyFacts("read-only", known, true, false),
            ReadPolicyFacts("read-only", known, false, true)
        )) assertEquals("UNSUPPORTED_TOOL_POLICY", code(ToolAuthorizationPolicy.admitReadOnlyPolicy(facts)))
    }

    @Test
    fun precedenceAndImmutableReviewMatchLegacyWriteDenials() {
        val base = WriteAuthorizationFacts(EngineeringRole.DEVELOPER, WriteOperationKind.EDIT,
            "src/main/Feature.kt")
        val cases = listOf(
            base.copy(declaredPath = "src/main/../outside.kt") to "INVALID_TOOL_PATH",
            base.copy(declaredPath = "/absolute") to "INVALID_TOOL_PATH",
            base.copy(declaredPath = "src/main//Feature.kt") to "INVALID_TOOL_PATH",
            base.copy(declaredPath = "src/main\\Feature.kt") to "INVALID_TOOL_PATH",
            base.copy(declaredPath = "src/main/.github/config", withinSubmodule = true) to "PROTECTED_PATH",
            base.copy(declaredPath = "src/main/build.gradle.kts") to "PROTECTED_PATH",
            base.copy(declaredPath = "src/main/FeatureTest.kt") to "PROTECTED_PATH",
            base.copy(actorRole = EngineeringRole.SDD, withinSubmodule = true) to "ROLE_NOT_AUTHORIZED",
            base.copy(withinSubmodule = true, trackedGitMode = "120000") to "SUBMODULE_BOUNDARY",
            base.copy(trackedGitMode = "120000") to "NONREGULAR_GIT_ENTRY",
            base.copy(immutableArtifactPaths = setOf(base.declaredPath)) to "APPROVED_ARTIFACT_IMMUTABLE",
            base.copy(operationKind = null) to "ROLE_NOT_AUTHORIZED",
            base.copy(actorRole = null) to "ROLE_NOT_AUTHORIZED"
        )
        cases.forEach { (facts, expected) ->
            assertEquals(expected, code(ToolAuthorizationPolicy.authorizeWrite(facts)), facts.toString())
        }

        val frozen = WriteAuthorizationFacts(EngineeringRole.TDD, WriteOperationKind.DELETE,
            "src/test/FrozenTest.kt", trackedGitMode = "100644",
            immutableArtifactPaths = setOf("src/test/FrozenTest.kt"))
        assertEquals("APPROVED_ARTIFACT_IMMUTABLE",
            code(ToolAuthorizationPolicy.authorizeWrite(frozen)))
        assertEquals(null, code(ToolAuthorizationPolicy.authorizeWrite(
            frozen.copy(independentlyReviewedPaths = setOf(frozen.declaredPath)))))
        assertEquals("ROLE_NOT_AUTHORIZED", code(ToolAuthorizationPolicy.authorizeWrite(
            frozen.copy(actorRole = EngineeringRole.DEVELOPER,
                independentlyReviewedPaths = setOf(frozen.declaredPath)))))
        assertEquals(null, code(ToolAuthorizationPolicy.authorizeWrite(
            base.copy(declaredPath = "src/main/build/Module.kt", trackedGitMode = "100755"))))
    }

    @Test
    fun onlyIndependentScopedHostReviewCanRelaxAnImmutableArtifact() {
        val valid = ReviewedChangeFacts("change-2", "author", "independent-reviewer",
            EngineeringRole.TDD, EngineeringRole.TDD, setOf("src/test/FrozenTest.kt"),
            setOf("src/test/FrozenTest.kt", "specs/Approved.md"))
        assertEquals(null, code(ToolAuthorizationPolicy.validateIndependentReview(valid)))
        for (bad in listOf(
            valid.copy(requestedBy = "author", reviewedBy = "author"),
            valid.copy(changeRequestId = "bad id"),
            valid.copy(requestedBy = " "),
            valid.copy(reviewedRole = EngineeringRole.SDD),
            valid.copy(actorRole = null),
            valid.copy(paths = emptySet())
        )) assertEquals("INVALID_REVIEW_AUTHORIZATION",
            code(ToolAuthorizationPolicy.validateIndependentReview(bad)), bad.toString())
        for (bad in listOf(
            valid.copy(paths = setOf("src/test/../FrozenTest.kt")),
            valid.copy(paths = setOf("src/test/UnapprovedTest.kt"))
        )) assertEquals("INVALID_WRITE_POLICY",
            code(ToolAuthorizationPolicy.validateIndependentReview(bad)), bad.toString())
    }

    @Test
    fun checkCapabilitiesAreExplicitAndDeveloperTestsRemainMandatory() {
        val required = setOf("git_diff_check", "separate-test")
        assertEquals(null, code(ToolAuthorizationPolicy.admitRequiredChecks(
            EngineeringRole.DEVELOPER, required)))
        for (missing in listOf(emptySet(), setOf("git_diff_check"), setOf("separate-test"))) {
            assertEquals("TEST_CHECK_REQUIRED", code(ToolAuthorizationPolicy.admitRequiredChecks(
                EngineeringRole.DEVELOPER, missing)))
        }
        assertEquals("TEST_CHECK_REQUIRED", code(ToolAuthorizationPolicy.admitRequiredChecks(null, required)))
        for (role in EngineeringRole.entries.filter { it != EngineeringRole.DEVELOPER }) {
            assertEquals(null, code(ToolAuthorizationPolicy.admitRequiredChecks(role, setOf("git_diff_check"))))
        }
        assertEquals(null, code(ToolAuthorizationPolicy.authorizeCheck("git_diff_check", emptySet())))
        assertEquals(null, code(ToolAuthorizationPolicy.authorizeCheck("separate-test", setOf("separate-test"))))
        assertEquals("CHECK_NOT_ALLOWLISTED", code(ToolAuthorizationPolicy.authorizeCheck(
            "unknown", setOf("separate-test"))))
        assertFalse(ToolAuthorizationPolicy.validDeclaredPath("src/main/../../outside"))
        assertTrue(ToolAuthorizationPolicy.validDeclaredPath("src/main/Allowed.kt"))
    }
}
