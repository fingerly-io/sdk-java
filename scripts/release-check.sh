#!/usr/bin/env bash
#
# Decides whether the version in gradle.properties still has to be released.
# Prints new=true or new=false, and appends the same plus version=X.Y.Z to
# $GITHUB_OUTPUT when run in a workflow. Any lookup that cannot answer fails the
# script, because an outage must never read as "not published yet".
#
# Usage: release-check.sh [group-path artifact version]
# The arguments exist so the logic can be tried on an artifact already on
# Central; the workflow passes none.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

group="${1:-io/fingerly}"
artifact="${2:-fingerly-server}"
version="${3:-$(sed -n 's/^version=//p' gradle.properties)}"
central="${CENTRAL_REPO_URL:-https://repo1.maven.org/maven2}"

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || {
  echo "error: '$version' is not a release version" >&2
  exit 1
}

finish() {
  echo "$2"
  echo "new=$1"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    { echo "new=$1"; echo "version=$version"; } >> "$GITHUB_OUTPUT"
  fi
  exit 0
}

# The tag comes first. It appears the moment a release finishes, while Central
# can take a while to show a new version, so it is what stops a quick re-run.
rc=0
git ls-remote --exit-code --tags origin "refs/tags/v$version" > /dev/null || rc=$?
case "$rc" in
  0) finish false "tag v$version exists" ;;
  2) ;;
  *) echo "error: git ls-remote failed with status $rc" >&2; exit 1 ;;
esac

# The direct path on repo1, not the search index, which lags further behind.
url="$central/$group/$artifact/$version/$artifact-$version.pom"
status="$(curl --silent --show-error --location --max-time 30 --retry 3 --retry-all-errors \
  --output /dev/null --write-out '%{http_code}' "$url")"
case "$status" in
  200) finish false "$artifact $version is on Maven Central" ;;
  404) finish true "$artifact $version is not on Maven Central" ;;
  *) echo "error: $url answered HTTP $status" >&2; exit 1 ;;
esac
