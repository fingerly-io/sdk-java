#!/usr/bin/env bash
#
# Checks on an already built tree that `gradlew build` does not make itself.
# CI and the release job both run this.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# The published bytecode must stay readable by Java 11, whatever JDK built it.
# Major version 55 is Java 11.
major="$(javap -verbose -cp fingerly-server/build/classes/java/main \
  io.fingerly.server.FingerlyClient | awk '/major version/ {print $3}')"
if [ "$major" != "55" ]; then
  echo "error: bytecode is major $major, want 55" >&2
  exit 1
fi

# Jackson is the only runtime dependency the core is allowed to have.
deps="$(./gradlew -q :fingerly-server:dependencies --configuration runtimeClasspath)"
grep -q "jackson-databind" <<< "$deps" || {
  echo "error: jackson-databind is missing from the runtime classpath" >&2
  exit 1
}
if grep -E '^[+\\]--- ' <<< "$deps" | grep -qv 'com\.fasterxml'; then
  echo "error: an unexpected runtime dependency appeared:" >&2
  echo "$deps" >&2
  exit 1
fi

echo "bytecode and runtime dependencies are as expected"
