# Recursive Coder

**Recursive Coder** is a planned autonomous coding agent built with **Kotlin/JVM** and **JetBrains Koog**.

> **Status: independent Kotlin/JVM build, canonical session contract v1 and CI scaffold.** The coding agent, observable Koog session controller and GitHub adapter are **not implemented yet**.

## Goal

Build an observable, controllable and recoverable multi-agent coding system, instead of relying on an opaque third-party agent runtime.

The intended development pipeline is **SDD → ATDD → TDD → Develop → Independent Review**. Each stage is owned by a separate agent, and decomposition can recurse until tasks are atomic.

Approved specifications and acceptance/unit tests are protected: the Developer agent cannot modify artifacts authored by SDD/ATDD/TDD. Changes require a separate reviewed process and trusted tool authorization.

## Architecture boundary

- **GitHub Actions / [social-mcp](https://github.com/YuriiSokolenko/social-mcp)** own global scheduling, issues/PRs, CI, Merge Gate, Reconciler and N150 runners.
- **Recursive Coder / Koog** owns agent orchestration **inside one GitHub Actions job**, including its subagents, recursion, tools, budgets, checkpoints and trace.
- **HP Nano** provides the local LLM through an OpenAI-compatible API.
- Each job starts one logical coding session. A later job attempt may safely resume it when durable state and authorization permit.
- The existing Pi implementation remains an optional rollback backend throughout migration.

**Observable by Design:** record structured, correlated state transitions, model/tool interactions, policy decisions, errors and external side effects. Diagnostic replay uses recorded responses; it does not claim access to private model reasoning.

## Independent project and CI

This repository is independently built and tested. It does **not** use the `social-mcp` CI, N150 runner pool or HP Nano for pull-request checks.

- **Kotlin/JVM**: Kotlin `2.4.21`, JDK `21`, Gradle `9.8.1` and pinned Koog `1.3.0` dependency. Only a protocol-version admission stub exists; a functional agent is the next milestone.
- **[Recursive Coder CI](.github/workflows/ci.yml)**: GitHub-hosted PR/push checks for `dev` and `main` plus manual dispatch. Kotlin compilation/unit tests run separately from versioned contract validation.
- **[Canonical session protocol v1](contracts/README.md)**: JSON Schema, fixtures, independent conformance tests and exact ownership/admission guarantees. `social-mcp` has a historical snapshot and will eventually only consume a pinned version through an adapter.

Reproduce the first JVM checks locally with JDK 21 and Gradle 9.8.1: `gradle test`. For schema tests, see `contracts/README.md`.

No production model requests, GitHub PR publication, Checkpoint/resume or recursive agent execution are implied by a green build yet.

## Documentation

- **[Agent Runtime Architecture v0.2](docs/architecture/KOOG_AGENT_RUNTIME_ARCHITECTURE_v0.2.md)** — current architectural RFC, including Observability, Controllability and Reproducibility.
- [Agent Runtime Architecture v0.1](docs/architecture/KOOG_AGENT_RUNTIME_ARCHITECTURE_v0.1.md) — initial historical draft, preserved for context.

The architecture documents are currently written in Russian. They describe a proposed target design rather than implemented features.

## Roadmap and work tracking

The migration work is tracked in the existing project's **[Koog extraction Epic #783](https://github.com/YuriiSokolenko/social-mcp/issues/783)** and tasks [#784–#790](https://github.com/YuriiSokolenko/social-mcp/issues/783). The #784 boundary/contract milestone is merged; the next implementation milestone is #785 (standalone executable Koog session). This repository owns the ongoing contract source and CI.

The old JavaScript `agent-harness` extraction proposal is not a blueprint for mechanically copying all control-plane code here: GitHub-owned control-plane responsibilities remain in `social-mcp`.

---

Koog is a JetBrains framework used as a dependency; **Recursive Coder is an independent project** and is not presented as an official JetBrains product.
