#!/bin/sh
# Pre-release: delegate to Gradle provisioned by standalone CI.
# Fully self-contained Gradle wrapper packaging is tracked in issue #12.
set -eu
if ! command -v gradle >/dev/null 2>&1; then
  printf '%s\n' 'Gradle 9.8.1 is required for this pre-release launcher.' >&2
  exit 127
fi
exec gradle "$@"
