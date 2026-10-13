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
        val paths = listOf(
            "specs/Next.md", "tests/acceptance/Next.md", "src/test/NextTest.kt",
            "tests/unit/Next.kt", "src/main/Next.kt"
        )
        val denied = "ROLE_NOT_AUTHORIZED"
        // Literal expected matrix: each column corresponds to one path above.
        // Never derive the expected outcomes from the implementation's scope expressions.
        val expected: List<Pair<EngineeringRole?, List<String?>>> = listOf(
            EngineeringRole.SDD to listOf(null, denied, denied, denied, denied),
            EngineeringRole.ATDD to listOf(denied, null, denied, denied, denied),
            EngineeringRole.TDD to listOf(denied, denied, null, null, denied),
            EngineeringRole.DEVELOPER to listOf(denied, denied, denied, denied, null),
            EngineeringRole.REVIEWER to listOf(denied, denied, denied, denied, denied),
            null to listOf(denied, denied, denied, denied, denied)
        )
        return expected.flatMap { (role, outcomes) ->
            WriteOperationKind.entries.flatMap { kind ->
                paths.mapIndexed { index, path ->
                    DynamicTest.dynamicTest("$role $kind $path") {
                        assertEquals(outcomes[index], code(ToolAuthorizationPolicy.authorizeWrite(
                            WriteAuthorizationFacts(role, kind, path))))
                    }
                }
            }
        }
    }

    /**
     * BEFORE/AFTER GOLDEN FIXTURES.
     * The expected denial codes below were transcribed from the inline authorized(path)
     * implementation at dev@04018999bafa9cba4cc49c1d72b0d4cd8ec03057, before #30.
     * These are fixed outcomes, NOT recomputed from the current ToolAuthorizationPolicy.
     * A null expected code means the old implementation reached permit. The old function
     * ignored operation kind; vary it here to detect accidental per-kind broadening.
     */
    private data class LegacyWriteFixture(
        val label: String,
        val role: EngineeringRole,
        val path: String,
        val expectedCode: String?,
        val gitMode: String? = null,
        val insideSubmodule: Boolean = false,
        val immutable: Boolean = false,
        val reviewed: Boolean = false
    )

    @TestFactory
    fun preservesLegacyDevWriteDecisionFixtures(): List<DynamicTest> {
        val before = listOf(
            LegacyWriteFixture("SDD owns specs", EngineeringRole.SDD, "specs/Feature.md", null),
            LegacyWriteFixture("ATDD owns acceptance", EngineeringRole.ATDD, "tests/acceptance/Feature.md", null),
            LegacyWriteFixture("TDD owns src tests", EngineeringRole.TDD, "src/test/FeatureTest.kt", null),
            LegacyWriteFixture("TDD owns unit tests", EngineeringRole.TDD, "tests/unit/Rules.kt", null),
            LegacyWriteFixture("Developer owns main", EngineeringRole.DEVELOPER, "src/main/Feature.kt", null),
            LegacyWriteFixture("Reviewer cannot write", EngineeringRole.REVIEWER, "src/main/Feature.kt", "ROLE_NOT_AUTHORIZED"),
            LegacyWriteFixture("Developer cannot write specs", EngineeringRole.DEVELOPER, "specs/Feature.md", "ROLE_NOT_AUTHORIZED"),
            LegacyWriteFixture("Developer cannot write unit tests", EngineeringRole.DEVELOPER, "tests/unit/Rules.kt", "ROLE_NOT_AUTHORIZED"),
            LegacyWriteFixture("Developer cannot write acceptance tests", EngineeringRole.DEVELOPER, "tests/acceptance/Feature.md", "ROLE_NOT_AUTHORIZED"),
            LegacyWriteFixture("TDD cannot write main", EngineeringRole.TDD, "src/main/Feature.kt", "ROLE_NOT_AUTHORIZED"),
            LegacyWriteFixture("ATDD cannot write unit tests", EngineeringRole.ATDD, "tests/unit/Rules.kt", "ROLE_NOT_AUTHORIZED"),
            LegacyWriteFixture("SDD protected env beats role", EngineeringRole.SDD, "specs/.env", "PROTECTED_PATH"),
            LegacyWriteFixture("ATDD protected env beats role", EngineeringRole.ATDD, "tests/acceptance/.env", "PROTECTED_PATH"),
            LegacyWriteFixture("TDD protected env beats role", EngineeringRole.TDD, "src/test/.env", "PROTECTED_PATH"),
            LegacyWriteFixture("Reviewer protected path beats role", EngineeringRole.REVIEWER, "specs/.env", "PROTECTED_PATH"),
            LegacyWriteFixture("Developer protected config beats role", EngineeringRole.DEVELOPER, "src/main/.github/config", "PROTECTED_PATH"),
            LegacyWriteFixture("Developer test suffix", EngineeringRole.DEVELOPER, "src/main/FeatureTest.kt", "PROTECTED_PATH"),
            LegacyWriteFixture("Developer mixed-case test suffix", EngineeringRole.DEVELOPER, "src/main/FooTEST.kt", "PROTECTED_PATH"),
            LegacyWriteFixture("Developer mixed-case spec suffix", EngineeringRole.DEVELOPER, "src/main/FooSPEC.MD", "PROTECTED_PATH"),
            LegacyWriteFixture("Developer ordinary file", EngineeringRole.DEVELOPER, "src/main/Feature.kt", null, "100644"),
            LegacyWriteFixture("Executable regular Git file", EngineeringRole.DEVELOPER, "src/main/Runner.kt", null, "100755"),
            LegacyWriteFixture("Symlink Git entry", EngineeringRole.DEVELOPER, "src/main/Alias.kt", "NONREGULAR_GIT_ENTRY", "120000"),
            LegacyWriteFixture("Submodule Git entry", EngineeringRole.SDD, "specs/Link", "NONREGULAR_GIT_ENTRY", "160000"),
            LegacyWriteFixture("Submodule child", EngineeringRole.DEVELOPER, "src/main/submodule/Feature.kt", "SUBMODULE_BOUNDARY", insideSubmodule = true),
            LegacyWriteFixture("Submodule wins over Git mode", EngineeringRole.DEVELOPER, "src/main/submodule/Feature.kt", "SUBMODULE_BOUNDARY", "160000", insideSubmodule = true),
            LegacyWriteFixture("Protected wins over submodule", EngineeringRole.SDD, "specs/.env", "PROTECTED_PATH", insideSubmodule = true),
            LegacyWriteFixture("Role wins over submodule", EngineeringRole.ATDD, "src/main/submodule/Feature.kt", "ROLE_NOT_AUTHORIZED", insideSubmodule = true),
            LegacyWriteFixture("Invalid traversal", EngineeringRole.DEVELOPER, "src/main/../outside.kt", "INVALID_TOOL_PATH"),
            LegacyWriteFixture("Invalid traversal beats protected", EngineeringRole.DEVELOPER, "src/main/../.env", "INVALID_TOOL_PATH"),
            LegacyWriteFixture("Invalid absolute path", EngineeringRole.SDD, "/etc/passwd", "INVALID_TOOL_PATH"),
            LegacyWriteFixture("Invalid backslash", EngineeringRole.DEVELOPER, "src/main\\Alias.kt", "INVALID_TOOL_PATH"),
            LegacyWriteFixture("Invalid empty segment", EngineeringRole.DEVELOPER, "src/main//Alias.kt", "INVALID_TOOL_PATH"),
            LegacyWriteFixture("Frozen approved test", EngineeringRole.TDD, "src/test/FrozenTest.kt", "APPROVED_ARTIFACT_IMMUTABLE", immutable = true),
            LegacyWriteFixture("Frozen approved spec", EngineeringRole.SDD, "specs/Frozen.md", "APPROVED_ARTIFACT_IMMUTABLE", immutable = true),
            LegacyWriteFixture("Approved test with independent review", EngineeringRole.TDD, "src/test/FrozenTest.kt", null, immutable = true, reviewed = true),
            LegacyWriteFixture("Review cannot widen scope", EngineeringRole.DEVELOPER, "src/test/FrozenTest.kt", "ROLE_NOT_AUTHORIZED", immutable = true, reviewed = true),
            LegacyWriteFixture("Review cannot bypass nonregular Git entry", EngineeringRole.TDD, "src/test/FrozenTest.kt", "NONREGULAR_GIT_ENTRY", "160000", immutable = true, reviewed = true)
        )
        return before.flatMap { fixture ->
            WriteOperationKind.entries.map { kind ->
                DynamicTest.dynamicTest("dev-before: " + fixture.label + " (" + kind + ")") {
                    val immutablePaths = if (fixture.immutable) setOf(fixture.path) else emptySet()
                    val reviewedPaths = if (fixture.reviewed) setOf(fixture.path) else emptySet()
                    val after = ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                        actorRole = fixture.role,
                        operationKind = kind,
                        declaredPath = fixture.path,
                        trackedGitMode = fixture.gitMode,
                        withinSubmodule = fixture.insideSubmodule,
                        immutableArtifactPaths = immutablePaths,
                        independentlyReviewedPaths = reviewedPaths
                    ))
                    assertEquals(fixture.expectedCode, code(after), fixture.toString())
                }
            }
        }
    }

    @Test
    fun sharedHostIdentifierGrammarIsConsistentWithReviewValidation() {
        val good = listOf("a", "issue-30.session:1", "Z".repeat(160))
        val bad = listOf("", "_leading", "bad id", "x".repeat(161), "id/with/slash")
        for (id in good) {
            assertTrue(ToolAuthorizationPolicy.validHostIdentifier(id), id)
            assertEquals(null, code(ToolAuthorizationPolicy.validateIndependentReview(
                ReviewedChangeFacts(id, "author", "reviewer",
                    EngineeringRole.TDD, EngineeringRole.TDD,
                    setOf("src/test/FrozenTest.kt"), setOf("src/test/FrozenTest.kt")))))
        }
        for (id in bad) {
            assertFalse(ToolAuthorizationPolicy.validHostIdentifier(id), id)
            assertEquals("INVALID_REVIEW_AUTHORIZATION",
                code(ToolAuthorizationPolicy.validateIndependentReview(
                    ReviewedChangeFacts(id, "author", "reviewer",
                        EngineeringRole.TDD, EngineeringRole.TDD,
                        setOf("src/test/FrozenTest.kt"), setOf("src/test/FrozenTest.kt")))))
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
    fun adversarialPrecedenceAndImmutableReviewDenials() {
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
