#!/usr/bin/env bash
#
# Sends the tree that `publishAllPublicationsToReleaseRepository` wrote to the
# Central Portal as one bundle, then waits for the Portal's verdict. The tree is
# checked here first because a Portal rejection costs a release attempt.
#
# Usage: upload-to-central.sh <release-directory>
# Needs MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD, a Portal user token.
# Version and artifact list come from the build; nothing here is per release.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tree="${1:?usage: upload-to-central.sh <release-directory>}"
portal="${CENTRAL_PORTAL_URL:-https://central.sonatype.com}"
poll_seconds="${CENTRAL_POLL_SECONDS:-15}"
poll_limit="${CENTRAL_POLL_LIMIT:-120}"
version="$(sed -n 's/^version=//p' "$root/gradle.properties")"
artifacts="fingerly-server fingerly-server-jakarta fingerly-server-javax fingerly-server-webflux"

fail() {
  echo "error: $*" >&2
  exit 1
}

: "${MAVEN_CENTRAL_USERNAME:?MAVEN_CENTRAL_USERNAME is not set}"
: "${MAVEN_CENTRAL_PASSWORD:?MAVEN_CENTRAL_PASSWORD is not set}"
[ -d "$tree/io/fingerly" ] || fail "$tree holds no io/fingerly tree"

# What the Portal does not read. Gradle writes checksums of the signatures and
# per-artifact metadata for a directory repository, neither part of a bundle.
find "$tree" \( -name 'maven-metadata.xml*' -o -name '*.asc.md5' -o -name '*.asc.sha1' \
  -o -name '*.asc.sha256' -o -name '*.asc.sha512' \) -delete

for artifact in $artifacts; do
  dir="$tree/io/fingerly/$artifact/$version"
  for file in "$artifact-$version.jar" "$artifact-$version-sources.jar" \
    "$artifact-$version-javadoc.jar" "$artifact-$version.pom"; do
    for suffix in "" .asc .md5 .sha1; do
      [ -s "$dir/$file$suffix" ] || fail "missing $artifact/$version/$file$suffix"
    done
  done
done

bundle="$(mktemp -d)/fingerly-java-$version.zip"
jar --create --no-manifest --file "$bundle" -C "$tree" io

# Passed through a file descriptor so the token is not on a command line.
token="$(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')"
auth_header() {
  printf 'Authorization: Bearer %s\n' "$token"
}

# No retry on the upload: a timeout may still have created a deployment, and an
# automatic deployment that was sent twice is the second one's failure to explain.
deployment="$(curl --silent --show-error --fail-with-body --max-time 600 \
  --header @<(auth_header) \
  --form "bundle=@$bundle;type=application/octet-stream" \
  "$portal/api/v1/publisher/upload?publishingType=AUTOMATIC&name=fingerly-java-$version")"
[[ "$deployment" =~ ^[0-9a-zA-Z-]+$ ]] || fail "unexpected upload response: $deployment"
echo "uploaded deployment $deployment"

for ((attempt = 0; attempt < poll_limit; attempt++)); do
  # A failed poll says nothing about the deployment, so it only costs a turn.
  body="$(curl --silent --show-error --fail-with-body --max-time 30 --request POST \
    --header @<(auth_header) "$portal/api/v1/publisher/status?id=$deployment")" || body=""
  state="$(jq -r '.deploymentState // empty' <<< "$body" 2> /dev/null || true)"
  echo "state: ${state:-unknown}"
  case "$state" in
    PUBLISHING | PUBLISHED)
      echo "Portal accepted $version; Central shows it once indexing finishes"
      exit 0
      ;;
    FAILED)
      jq -r '.errors // .' <<< "$body" >&2
      fail "the Portal rejected deployment $deployment"
      ;;
    VALIDATED)
      fail "deployment $deployment is waiting for a manual release in the Portal"
      ;;
  esac
  sleep "$poll_seconds"
done

fail "deployment $deployment had no verdict after $poll_limit polls; check the Portal before re-running"
