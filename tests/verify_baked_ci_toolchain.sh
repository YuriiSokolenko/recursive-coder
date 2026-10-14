#!/usr/bin/env bash
# Called by each isolated GitHub Actions job; no network/install fallback.
set -euo pipefail
base=/opt/recursive-coder-kvm
manifest="$base/toolchain-v2.manifest"
expected='schema=2 gradle=9.8.1 gradle_sha256=dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f node=22.21.1 node_sha256=680d3f30b24a7ff24b98db5e96f294c0070f8f9078df658da1bce1b9c9873c88 python=3.12 jsonschema=4.25.1 source=3028a05e602a9572ba41bc1f3daa28d0fdb762c0'
[[ -f "$manifest" && "$(cat "$manifest")" = "$expected" ]] || {
  echo "::error::CI Golden Image v2 is required; baked toolchain manifest is absent or mismatched" >&2
  exit 1
}
[[ "\${GRADLE_USER_HOME:-}" = "$base/gradle-home" ]] || {
  echo "::error::GRADLE_USER_HOME must point to the prewarmed cache" >&2
  exit 1
}
[[ -d "$GRADLE_USER_HOME/caches" ]] || {
  echo "::error::Prewarmed Gradle cache is missing" >&2
  exit 1
}
[[ "$(command -v gradle)" = "$base/gradle-9.8.1/bin/gradle" ]]
[[ "$(command -v node)" = "$base/node-v22.21.1-linux-x64/bin/node" ]]
[[ "$(command -v python)" = "$base/python/bin/python" ]]
java -version 2>&1 | grep -Eq 'version "21|openjdk version "21'
gradle --version | grep -Fq 'Gradle 9.8.1'
[[ "$(node --version)" = v22.21.1 ]]
python -c 'import sys; from importlib.metadata import version; assert sys.version_info[:2] == (3,12) and version("jsonschema") == "4.25.1"'
echo "Validated baked Gradle, Java 21, Node 22, Python 3.12 and offline-ready Gradle cache."
