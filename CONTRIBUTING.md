# Contributing to Recursive Coder

## Language policy

**English is the sole language for repository-maintained documentation.** Write and review README files, architecture RFCs, design notes, configuration documentation, release notes, examples, issue descriptions, PR descriptions, and documentation comments in English.

Use English for new commit titles and descriptions. Keep this rule for generated documentation, diagrams containing text, and future website or help content. Existing source identifiers and standardized protocol literals must not be translated because wire compatibility takes precedence.

Before proposing a change, verify that edited documentation contains no non-English prose, that internal links still resolve, and that examples describe the implementation accurately. The CI checks for Cyrillic characters in maintained Markdown documentation as a mechanical regression guard; editorial review remains necessary for English-only quality.

## Independent product boundary

Keep task execution, session state, authorization, tool runtime, checkpoints, and evaluation owned by Recursive Coder. A third-party API, git host, IDE, or model endpoint may be configured as an optional integration, never as a mandatory orchestration dependency.

Documentation describing planned behavior must explicitly distinguish it from working functionality.
