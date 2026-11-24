package ai.yuriilab.recursivecoder

/**
 * First-party, I/O-free tool authorization. All inputs are facts supplied by the trusted host,
 * not assertions accepted from an LLM. A permit is only a logical decision: workspace adapters
 * must still recheck the current Git tree, path descriptors, symlinks, lease and mutation receipts.
 */
enum class EngineeringRole { SDD, ATDD, TDD, DEVELOPER, REVIEWER }

/**
 * Host-approved write intent. All three known kinds share the same logical role/path policy.
 * The kind currently only gates unknown/missing intents; create/edit/delete preconditions,
 * optimistic hashes and actual effects are checked separately in trusted infrastructure.
 */
enum class WriteOperationKind { CREATE, EDIT, DELETE }

sealed interface ToolAuthorizationDecision {
    val deniedCode: String?

    data object Permit : ToolAuthorizationDecision {
        override val deniedCode: String? = null
    }

    data class Deny(override val deniedCode: String) : ToolAuthorizationDecision
}

/** Read-only session admission and per-call permission grants are deliberately separate. */
data class ReadPolicyFacts(
    val mode: String,
    val allowedTools: Set<String>,
    val writablePathsPresent: Boolean,
    val immutableInputsPresent: Boolean
)

data class ReadAuthorizationFacts(
    val requestedTool: String,
    val allowedTools: Set<String>,
    val deniedTools: Set<String>,
    val taskAdmission: Boolean = false
)

/**
 * Scope and immutable ownership are declared facts. trackedGitMode/withinSubmodule must come
 * from the host's inspected Git tree, and independentlyReviewedPaths from validated host review.
 * operationKind is deliberately not a per-operation permission matrix today; physical
 * create/edit/delete semantics and preconditions remain in TrustedWriteRuntime.
 */
data class WriteAuthorizationFacts(
    val actorRole: EngineeringRole?,
    val operationKind: WriteOperationKind?,
    val declaredPath: String,
    val trackedGitMode: String? = null,
    val withinSubmodule: Boolean = false,
    val immutableArtifactPaths: Set<String> = emptySet(),
    val independentlyReviewedPaths: Set<String> = emptySet()
)

data class ReviewedChangeFacts(
    val changeRequestId: String,
    val requestedBy: String,
    val reviewedBy: String,
    val actorRole: EngineeringRole?,
    val reviewedRole: EngineeringRole?,
    val paths: Set<String>,
    val approvedArtifactPaths: Set<String>
)

object ToolAuthorizationPolicy {
    val knownReadTools: Set<String> = setOf(WorkspaceToolNames.READ, WorkspaceToolNames.LIST, WorkspaceToolNames.FIND, WorkspaceToolNames.SEARCH, WorkspaceToolNames.GIT_STATUS, WorkspaceToolNames.GIT_DIFF)
    private val protectedSegments = setOf(".git", ".github", ".gradle", ".env", "secrets", "credentials")
    private val protectedFiles = setOf("gradle.properties", "local.properties", "build.gradle.kts",
        "settings.gradle.kts", "build.gradle", "settings.gradle", "pom.xml", "package.json",
        ".gitattributes", ".gitignore", ".gitmodules")
    private val developerTestSuffix = Regex(".*(test|spec)\\.[a-z0-9]+")

    /**
     * Files that configure *how tests execute* rather than what they assert. Main
     * resources are on the test runtime classpath of common JVM builds, so a
     * Developer could otherwise neutralize frozen tests without editing them
     * (for example `junit.platform.execution.dryRun.enabled=true`). This is a
     * defense-in-depth denylist, not a proof of test integrity (issue #152).
     */
    private val developerTestConfigFiles = setOf("junit-platform.properties", "testng.xml",
        "kotest.properties", "pytest.ini", "tox.ini", "setup.cfg", "pyproject.toml",
        "sitecustomize.py", "usercustomize.py", "phpunit.xml", "phpunit.xml.dist")
    private val developerTestConfigPrefixes = listOf("jest.config.", "vitest.config.",
        "karma.conf.", ".mocharc", "playwright.config.", "cypress.config.")

    private fun developerTestConfiguration(parts: List<String>): Boolean {
        val name = parts.last()
        // JVM service registrations under META-INF/services can auto-install
        // test-framework extensions.
        val serviceRegistration = parts.windowed(2).any { (a, b) -> a == "meta-inf" && b == "services" }
        return serviceRegistration || name in developerTestConfigFiles ||
            developerTestConfigPrefixes.any { name.startsWith(it) }
    }
    private val hostIdentifierPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")

    /** Shared stable grammar for host-issued session IDs, check IDs and review request IDs. */
    fun validHostIdentifier(id: String): Boolean = hostIdentifierPattern.matches(id)

    /** The same lexical syntax is used for host policy admission and each model-declared path. */
    fun validDeclaredPath(path: String): Boolean =
        path.isNotEmpty() && path.length <= 4096 &&
            !path.startsWith('/') && '\\' !in path && path.split('/').all { segment ->
                segment.isNotEmpty() && segment != "." && segment != ".." &&
                    segment.none { char -> char.code < 32 || char.code == 127 }
            }

    fun admitReadOnlyPolicy(facts: ReadPolicyFacts): ToolAuthorizationDecision =
        if (facts.mode != ToolPolicyModes.READ_ONLY || facts.allowedTools.any { it !in knownReadTools } ||
            facts.writablePathsPresent || facts.immutableInputsPresent)
            ToolAuthorizationDecision.Deny(ToolDenialCodes.UNSUPPORTED_TOOL_POLICY)
        else ToolAuthorizationDecision.Permit

    fun authorizeRead(facts: ReadAuthorizationFacts): ToolAuthorizationDecision {
        val code = if (facts.taskAdmission) "READ_NOT_AUTHORIZED" else "TOOL_NOT_AUTHORIZED"
        // An unknown tool never acquires authority, even if present in an allow set.
        if (facts.requestedTool !in knownReadTools || facts.requestedTool !in facts.allowedTools ||
            facts.requestedTool in facts.deniedTools)
            return ToolAuthorizationDecision.Deny(code)
        return ToolAuthorizationDecision.Permit
    }

    /**
     * Decision order is part of the existing denial contract. Physical path safety and
     * read-before-write/hash preconditions deliberately remain in TrustedWriteRuntime.
     */
    fun authorizeWrite(facts: WriteAuthorizationFacts): ToolAuthorizationDecision {
        val path = facts.declaredPath
        if (!validDeclaredPath(path))
            return ToolAuthorizationDecision.Deny(ToolDenialCodes.INVALID_TOOL_PATH)
        val parts = path.lowercase().split('/')
        val name = parts.last()
        if (parts.any { it in protectedSegments } ||
            name.startsWith(".env") || name.endsWith(".key") || name.endsWith(".pem") ||
            name.endsWith(".p12") || name.endsWith(".jks") || name in protectedFiles)
            return ToolAuthorizationDecision.Deny(ToolDenialCodes.PROTECTED_PATH)

        val allowed = when (facts.actorRole) {
            EngineeringRole.SDD -> path.startsWith("specs/")
            EngineeringRole.ATDD -> path.startsWith("tests/acceptance/")
            EngineeringRole.TDD -> path.startsWith("src/test/") || path.startsWith("tests/unit/")
            EngineeringRole.DEVELOPER -> path.startsWith("src/main/")
            EngineeringRole.REVIEWER, null -> false
        }
        if (!allowed || facts.operationKind == null)
            return ToolAuthorizationDecision.Deny(ToolDenialCodes.ROLE_NOT_AUTHORIZED)
        if (facts.actorRole == EngineeringRole.DEVELOPER &&
            (developerTestSuffix.matches(name) || developerTestConfiguration(parts)))
            return ToolAuthorizationDecision.Deny(ToolDenialCodes.PROTECTED_PATH)
        if (facts.withinSubmodule)
            return ToolAuthorizationDecision.Deny(ToolDenialCodes.SUBMODULE_BOUNDARY)
        if (facts.trackedGitMode != null && facts.trackedGitMode !in setOf("100644", "100755"))
            return ToolAuthorizationDecision.Deny("NONREGULAR_GIT_ENTRY")
        if (path in facts.immutableArtifactPaths && path !in facts.independentlyReviewedPaths)
            return ToolAuthorizationDecision.Deny("APPROVED_ARTIFACT_IMMUTABLE")
        return ToolAuthorizationDecision.Permit
    }

    /** Review validation never treats an agent's claimed approval as authority. */
    fun validateIndependentReview(facts: ReviewedChangeFacts): ToolAuthorizationDecision {
        if (!validHostIdentifier(facts.changeRequestId) ||
            facts.requestedBy.isBlank() || facts.reviewedBy.isBlank() ||
            facts.requestedBy == facts.reviewedBy || facts.reviewedRole != facts.actorRole ||
            facts.actorRole == null || facts.paths.isEmpty())
            return ToolAuthorizationDecision.Deny("INVALID_REVIEW_AUTHORIZATION")
        if (facts.paths.any { !validDeclaredPath(it) || it !in facts.approvedArtifactPaths })
            return ToolAuthorizationDecision.Deny(ToolDenialCodes.INVALID_WRITE_POLICY)
        return ToolAuthorizationDecision.Permit
    }

    /** Developers must have an independent test and the fixed Git diff check. */
    fun admitRequiredChecks(role: EngineeringRole?, requiredChecks: Set<String>): ToolAuthorizationDecision =
        if (role == null || (role == EngineeringRole.DEVELOPER &&
            (StageCheckIds.GIT_DIFF_CHECK !in requiredChecks ||
                requiredChecks.none { it != StageCheckIds.GIT_DIFF_CHECK })))
            ToolAuthorizationDecision.Deny("TEST_CHECK_REQUIRED")
        else ToolAuthorizationDecision.Permit

    fun authorizeCheck(requestedCheck: String, allowlistedChecks: Set<String>): ToolAuthorizationDecision =
        if (requestedCheck == StageCheckIds.GIT_DIFF_CHECK || requestedCheck in allowlistedChecks)
            ToolAuthorizationDecision.Permit
        else ToolAuthorizationDecision.Deny("CHECK_NOT_ALLOWLISTED")
}
