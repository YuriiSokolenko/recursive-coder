# Build logic and tooling vocabulary audit (#205)

Parent: #194. This is a follow-up to the initial changes already in dev, not a
change to the test-tier, bytecode, dependency or CI security policy.

Audit baseline: dev at `5a31bae85341a2083f0cf982eb4b1f83db2d67cb`. The inventory
below records files found via repository code search and inspected for repeated
literal candidates, with targeted source inspection of the affected paths and
guard boundaries. This is not a proof that every literal in every test is unique.

## Ownership and disposition

| Reviewed files (paths relative to repository root) | Candidate and disposition |
| --- | --- |
| `build-logic/src/main/kotlin/recursivecoder.jvm.gradle.kts` | Existing local test source-set name and three JUnit tags are authoritative for the convention plugin. Keep independent Python tier expectations. Explicit Gradle task names remain externally visible and are not derived from tags. |
| `build.gradle.kts`, `core/contracts/build.gradle.kts` | Test source-set names, Gradle task names and Python CLI arguments are external integration identifiers. In particular, the independently discovered testClasses inventory must not be derived from the guard's Java-module selection. |
| `app/cli/build.gradle.kts` | Local application name now drives both the application configuration and the historical install path. Keep verifier and installed CLI test paths literal and independent. |
| `settings.gradle.kts`, `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`, `build-logic/gradle/libs.versions.toml` | Keep separate root and standalone bootstrap checks; repository URL expectations are not derived from effective repositories. Existing version catalog owns catalogued dependencies; changing dependency resolution or introducing a new BOM is out of scope. |
| `agent/runtime/build.gradle.kts`, `core/session/build.gradle.kts`, `core/tasks/build.gradle.kts`, `infra/model/build.gradle.kts`, `infra/workspace/build.gradle.kts`, `infra/task-store/build.gradle.kts`, `infra/journal/build.gradle.kts` | Explicit project edges, Gradle configuration names, and compiler/opt-in regression fixtures stay readable. Matching Gradle task names do not imply the same fixture or policy. |
| `build-logic/src/main/kotlin/recursivecoder.architecture.gradle.kts`, architecture package `ArchitectureGraph.kt`, `BuildScripts.kt`, `CheckArchitectureTask.kt`, and `build-logic/src/test/kotlin/ai/yuriilab/recursivecoder/buildlogic/architecture/ArchitectureGraphTest.kt` | Reviewed graph edges, approved source paths, SHA pins, compiler checks, opt-in fixtures and tests remain explicit. Do not derive expected architecture from the actual build or source pin inventory from discovered files. |
| `.github/workflows/ci.yml`, `tools/check_ci_bootstrap.py`, `tests/test_ci_bootstrap.py` | Existing local job sets retained. Bootstrap owns the authenticated archive basename, reused for URL and local download. Workflow action SHA pins, runner and job topology remain independently verified. Literal tests pin both URL and filename. |
| `tools/check_test_tiers.py`, `tests/test_test_tiers.py`, `tests/check_kotlin_tier_fixtures.py` | Classifier owns local JVM Tag, Tags and Nested identities as well as existing local tier labels. No changes to tag matching, nested traversal, purity checks, class parser, JVM flags or literal fixtures. Existing regressions cover repeatable tags, nested tests and misspelled tiers. |
| `tools/check_junit_evidence.py`, `tests/test_junit_evidence.py` | Existing independent target names and tier expectations retained, including the separate unfiltered target. Added explicit per-tier mapping and unknown-effective-tier rejection tests. Do not import the convention plugin or derive the expected target set from Gradle. |
| `tools/check_production_bytecode.py`, `tests/test_production_bytecode.py` | Verifier-local install root shared between launcher and library checks. Keep exact allowed entry names, C3 marker/caller/supertype expectations, JVM bootstrap and reflection rules explicit. Existing tests independently construct literal Unix and Windows launcher paths and reject unexpected files, symlinks and altered launchers. |
| `tools/check_dependency_verification.py` | Keep independent checksum and manifest expectations, negative fixture coordinates, namespaces and digest format. No change to verification metadata. |
| `tools/check_preparation_host_conformance.py`, `tests/test_preparation_host_conformance.py` | Keep adapter and registration identity, expected profile, required suites, execution-count and no-skip rules independent; existing local identifiers retained. |
| `tests/test_architecture_gradle.py` | Configuration-cache regression strings, task names, paths and compiler diagnostics are independent Gradle fixture inputs and expectations. Do not share them with the plugin or change fixture semantics; generic local strings and filesystem helpers need no new constants. |
| `tests/test_contract_v1.py`, `tests/test_cli_runtime_v1.py`, `tests/test_cli_writable_workflow.py`, `contracts/v1/session-v1.test.mjs` | Keep wire field names, fixture mutations, unknown values, CLI arguments, output names and expected installed CLI path literal as compatibility oracles. Ajv and Python continue to read the canonical schema independently of Kotlin. |

## Updated dev baseline

The initial audit was refreshed against dev at
`2eb9d2d05905a0c443a65a0bfc26edc0c55803ad`. The recursive Git tree lists
all Python, JavaScript, Gradle Kotlin DSL, TOML, YAML and properties files;
the new review tools and root Gradle properties are classified below. The
existing inventory link and archive-name assertion are applied on top of
this dev revision without replacing its diagnostic inventory or review tests.

| Reviewed files | Ownership and disposition |
| --- | --- |
| `gradle.properties` | Gradle and Kotlin configuration keys, cache toggle, JVM memory and encoding are external configuration, not shared domain vocabulary. |
| `.claude/skills/c3-pin-check/scripts/check_c3_pins.py`, `.claude/skills/diagnostic-vocabulary-audit/scripts/scan_literals.py` | Keep each trusted-base helper standalone. Their graph path and pin regular expressions are independently owned verifier inputs, not shared with the architecture implementation or each other. |
| `.claude/skills/strict-pr-review/scripts/check_policy_checkout.py` | Discovery names, root policy names, file modes and Git blob format belong to the checkout guard. Preserve its independent policy expectations and fail-closed behavior. |
| `tests/test_review_skill_scripts.py`, `.github/workflows/ci.yml`, `tests/test_ci_bootstrap.py` | Preserve literal fixture paths, pin counts, provider discovery names, trusted helper paths and CI evidence expectations independently of the helpers. The new review-evidence step and its security regressions remain unchanged. |

## Verification

No schema, wire format, product Kotlin code, module dependency, CI YAML, C3 pin
or trust policy is changed in this slice. Existing independent regression tests
remain in place. The additional Python fixtures verify literal distribution
archive spelling and all four admitted effective tier combinations, plus
rejection of an unknown effective tier. Full architecture, pure unit,
integration, installed CLI E2E, bytecode and protocol CI is required before
merging or marking #205 complete. Keep #194 open.
