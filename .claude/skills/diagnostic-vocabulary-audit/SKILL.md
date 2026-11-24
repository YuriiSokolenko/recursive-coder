---
name: diagnostic-vocabulary-audit
description: Audit denial codes, diagnostic categories, protocol strings and constants in a recursive-coder PR - finds owned codes still written as raw literals, repeated literals in touched files, cross-module duplicates and missing schema oracles. Use for PRs under issues #194/#201-style vocabulary work or any PR that adds or moves constants.
---

# Diagnostic vocabulary audit

Rules come from `CONTRIBUTING.md` ("Semantic constants and wire vocabulary")
and `docs/architecture/SEMANTIC_VOCABULARY.md`.

## 1. Run the scanner

```bash
python3 -I .claude/skills/diagnostic-vocabulary-audit/scripts/scan_literals.py \
  --repo <clone> --base <base_sha> --head pr-<N>
```

It reads production Kotlin (`src/main`) at the PR head and prints three
sections:

1. **Owned codes still raw:** a `const val` owner exists, but the literal is
   still used. Each hit outside a `[C3]` file is a finding. `[C3]` hits must
   be listed in the inventory as excluded pinned literals.
2. **Repeated in touched files:** the same literal appears two or more times in
   a file the PR edits. Required action: a private or internal constant in that
   file or module, or an inventory entry that says why it stays literal.
3. **Cross-file:** the literal appears in a touched file and in other
   production files. When the meaning is the same, it needs one owner: a
   shared owner in `:core:contracts` for cross-module vocabulary, or an
   `internal` owner in the module. Confirm the meaning by reading every call
   site before you require consolidation. When meanings differ, the inventory
   must say so.

## 2. Judge, don't just count

- Same spelling is not the same meaning. Read each site.
- Same meaning across modules means one owner. A PR that creates an owner
  object but leaves its own domain's repeated codes raw is incomplete
  (Important when the PR or doc claims that ownership).
- Implementation-only codes belong in a private or internal constant in their
  module, not in `:core:contracts`.
- Avoid generic constants containers. Owners are named per domain
  (`StageDenialCodes`, `RecursiveDenialCodes`, `ResumeDenialCodes`, ...).
- Closed sets (schema enums, categories, statuses) should be typed values or
  enums. If changing the type is blocked (for example by C3-pinned callers),
  require a tracked issue linked from the inventory.

## 3. Tests and oracles

- Every new constant has a literal spelling assertion in a `@Tag("unit")`
  test, grouped per owner object.
- Kotlin vocabulary that mirrors a schema enum or field set is asserted
  against `contracts/v1/session-v1.schema.json` in
  `SessionVocabularySchemaIntegrationTest` (or an equivalent schema oracle).
  Pinning literals alone does not detect schema drift.
- Behavior tests and fixtures keep literal expected codes. They must not import
  the constants they verify.

## 4. Inventory accuracy

Compare `docs/architecture/SEMANTIC_VOCABULARY.md` (and any follow-up doc)
with the scanner output:

- Every "owns" or "migrated" statement must hold in the code.
- Every deliberately skipped repeated literal is listed with its reason.
- The audit base SHA is the PR's actual base.
