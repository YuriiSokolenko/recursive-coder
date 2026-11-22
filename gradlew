#!/bin/sh
# Pre-release launcher: local development uses an installed Gradle.
# CI executes only the independently SHA-256-authenticated 9.8.1 distribution.
set -eu
if [ "${CI:-}" = "true" ]; then
  if [ -z "${RECURSIVE_VERIFIED_GRADLE:-}" ] || [ ! -x "$RECURSIVE_VERIFIED_GRADLE" ]; then
    printf '%s\n' 'CI requires verified Gradle 9.8.1; refusing PATH fallback.' >&2
    exit 127
  fi
  exec "$RECURSIVE_VERIFIED_GRADLE" "$@"
fi
if ! command -v gradle >/dev/null 2>&1; then
  printf '%s\n' 'Gradle 9.8.1 is required for this pre-release launcher.' >&2
  exit 127
fi
exec gradle "$@"
