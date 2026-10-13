# Contributing to Recursive Coder

## Language policy

**English is the sole language for repository-maintained documentation.** Write and review README files, architecture RFCs, design notes, configuration documentation, release notes, examples, issue descriptions, PR descriptions, and documentation comments in English.

Use English for new commit titles and descriptions. Keep this rule for generated documentation, diagrams containing text, and future website or help content. Existing source identifiers and standardized protocol literals must not be translated because wire compatibility takes precedence.

Before proposing a change, verify that edited documentation contains no non-English prose, that internal links still resolve, and that examples describe the implementation accurately. The CI checks for Cyrillic characters in maintained Markdown documentation as a mechanical regression guard; editorial review remains necessary for English-only quality.

## Independent product boundary

Keep task execution, session state, authorization, tool runtime, checkpoints, and evaluation owned by Recursive Coder. A third-party API, git host, IDE, or model endpoint may be configured as an optional integration, never as a mandatory orchestration dependency.

Documentation describing planned behavior must explicitly distinguish it from working functionality.

## JVM test tiers

Every JUnit Jupiter test must have an effective `@Tag("unit")`, `@Tag("integration")` or `@Tag("e2e")`. Apply it to the test class for a uniform suite, or to individual methods when appropriate. Pure `unit` tests use only in-memory inputs and fakes; anything invoking a file, Git, HTTP, socket, subprocess or Koog belongs to a higher tier. A class tagged `integration` may give one method `e2e`; the focused integration suite excludes that method.

Before opening a PR, run `./gradlew checkTestTiers unitTest` and the guard's independent regression cases with `python3 -m unittest discover -s tests -p 'test_test_tiers.py' -v`. The original `./gradlew test installDist` remains unfiltered. See [test tier policy](docs/architecture/TEST_SUITES.md) for the supported JUnit annotations, diagnostics and static-check limitations.
