# Workspace Manager and read-only tool runtime (issue #5)

This implementation is the trusted local workspace boundary for the offline fake-agent
session. It is independent of any GitHub runner, external project or model provider.

## Workspace admission and ownership

- Only an existing, non-symlink local Git working tree with a normal `.git`
  directory and a named branch is admitted. Linked worktrees, bare repositories,
  submodule roots, non-Git directories and write-capable modes are unsupported.
- The trusted host compares the actual top-level root, Git directory, branch and
  HEAD against the SessionRequest's declared root, branch, baseline and head.
  The immutable snapshot includes a canonical real path, workspace ID, baseline
  commit, branch, *computed effective* policy SHA-256 and a Git tree allowlist.
- The engine holds an exclusive OS file lock at
  `~/.recursive-coder/locks/<canonical-root-digest>.lock`, outside the project,
  until the session closes. Another session targeting the same root is rejected.
  The runtime uses its own lock directory; model calls cannot select this path.
- An incoming `toolPolicy.policySha256` remains a protocol declaration, **not**
  an authorization signature. The runtime independently hashes its effective
  policy, and the request's allow/deny fields are enforced on every tool call.
  Host-signed policy admission and durable leases are future work.

## Tool interfaces

The trusted runtime exposes typed `WorkspaceTool` calls: `read`, `list`,
`find`, `search`, `git_status` and `git_diff`. The model supplies only
relative paths or literal queries, never executable Git arguments, tool IDs,
environment variables, lock paths or absolute path authorities.

Every call is checked independently:

1. Denials take precedence over allowed tool names. Unknown granted tools and
   write-capable workspace modes are refused before execution.
2. Readable files must be regular tracked blobs at the original `HEAD`, not
   merely files present in the directory or newly staged into the index.
   Symlinks and submodule gitlinks are never read.
3. Components `.`, `..`, empty segments, absolute paths and control characters
   are rejected. `.git`, credential-bearing names, secret keys and configured
   protected globs are denied, including ancestor components.
4. A Java `SecureDirectoryStream` and `NOFOLLOW_LINKS` check every traversal
   component and descriptor open. If the platform cannot provide this
   race-resistant primitive, the workspace fails admission with
   `SANDBOX_UNAVAILABLE`. These tools do **not** expose arbitrary subprocesses.
5. Before each call, the manager checks the held lease, root/Git-directory
   identity, branch and baseline HEAD. Stale workspaces fail closed.
6. Reads are limited to 32 KiB of valid UTF-8; each tool result is limited to
   48 KiB, aggregate returned payloads to 256 KiB, list/find/search results
   to 100 entries, and search to 256 tracked files / 1 MiB examined. The
   session's `maxToolCalls` applies to **attempts**, including denials.
7. Git inspection calls are fixed verbs, no shell, no external diff/textconv,
   with a sanitized environment, bounded output and a three-second deadline.
   Executable Git filters, config includes and command-capable diff drivers
   cause admission refusal. Changes to the local Git configuration during the
   session are detected before tools proceed; repositories relying on Git LFS
   filter drivers are not yet supported. Git status excludes untracked files
   and filters all returned names through the tracked/protected-path policy.

Each attempted call receives an engine-generated `tool-<sessionId>-<sequence>`
receipt ID. Claimed execution IDs are rejected. The journal records metadata
for **both permitted and denied** calls: tool name, receipt, outcome, byte count,
timing and bounded denial code; never the tool arguments, secret values,
file contents or search matches. Durability of receipts is deferred to #10.

## Non-goals and security boundaries

This milestone does not provide production file writes, shell execution,
sandboxed tests, a multi-user security boundary, protected-branch enforcement,
remote Git operations, or durable event-store recovery. The runtime runs as the
current OS user; it is not a full operating-system container or protection
against another process with the same account intentionally mutating inode
metadata concurrently. Future write-capable tools require separate sandbox
design, change manifest validation and independent check receipts.

The initial task file is verified through the same trusted descriptor-based
boundary before the fake model runs. The fake agent's subsequent read now
goes through `WorkspaceManager.execute(WorkspaceTool.Read(...))`; there is
no direct `Files.readAllBytes` model-tool bypass.

## Verification

`WorkspaceManagerTest` tests allowed file operations, metadata, status/diff,
deny precedence, path traversal and secrets, untracked files, symlinked
directory escapes, tracked symlink denial, submodule gitlinks, forged IDs,
quotas, stale baselines, exclusive leases, oversized files and unsupported
modes. Existing fake-model/CLI protocol tests exercise the integrated
read path and canonical redacted session events.

Run `./gradlew test` and the independent repository CI suites.
