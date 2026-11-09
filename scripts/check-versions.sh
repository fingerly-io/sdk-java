#!/usr/bin/env bash
#
# Fails when a copy of the version disagrees with gradle.properties. Jars and
# POMs take it from the build; these are the copies written out by hand.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

version="$(sed -n 's/^version=//p' gradle.properties)"
[ -n "$version" ] || { echo "error: no version in gradle.properties" >&2; exit 1; }

status=0

require() {
  local file="$1" text="$2"
  if ! grep -qF -- "$text" "$file"; then
    echo "error: $file does not contain: $text" >&2
    status=1
  fi
}

require README.md "<version>$version</version>"
require README.md "io.fingerly:fingerly-server:$version"
require README.md "java/$version"
require CHANGELOG.md "## $version"

[ "$status" -eq 0 ] && echo "version $version is consistent"
exit "$status"
