#!/usr/bin/env bash
# Publishes packaged CLI files as the GitHub release `cli-v<version>`.
#
# Usage:
#   scripts/release/publish-cli.sh <version> <directory>
#
# Every file in <directory> (named bosca-<version>-<platform>.<ext>) is uploaded
# together with a generated SHA256SUMS, which cli/install.sh verifies against. The
# release is created as a draft, filled, and then published, so installers never
# see a partially uploaded release. An existing release is never overwritten.
#
# Environment:
#   GITHUB_TOKEN          Token with permission to create releases (prompted for
#                         on a terminal when unset).
#   BOSCA_CLI_REPOSITORY  owner/name repository to publish to (default: bosca-io/bosca).
#   RELEASE_COMMIT        Commit to tag (default: the checked-out HEAD).
set -euo pipefail

prompt() {
  local name="$1" message="$2" secret="${3:-}" value="${!1:-}"
  if [[ -z "$value" && -t 0 ]]; then
    if [[ -n "$secret" ]]; then read -rsp "$message: " value; echo; else read -rp "$message: " value; fi
  fi
  [[ -n "$value" ]] || { echo "Missing $name: $message" >&2; exit 1; }
  printf -v "$name" '%s' "$value"
}

VERSION="${1:-}"
DIRECTORY="${2:-}"
prompt VERSION "CLI version to publish (for example 6.31.0)"
prompt DIRECTORY "Directory containing the packaged CLI files"
prompt GITHUB_TOKEN "GitHub token with permission to create releases" secret
VERSION="${VERSION#cli-v}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || { echo "Invalid version: $VERSION" >&2; exit 1; }

REPOSITORY="${BOSCA_CLI_REPOSITORY:-bosca-io/bosca}"
TAG="cli-v$VERSION"
COMMIT="${RELEASE_COMMIT:-$(git rev-parse HEAD)}"
API="https://api.github.com/repos/$REPOSITORY"

shopt -s nullglob
FILES=("$DIRECTORY"/bosca-"$VERSION"-*)
shopt -u nullglob
[[ ${#FILES[@]} -gt 0 ]] || { echo "No bosca-$VERSION-* files in $DIRECTORY" >&2; exit 1; }

github() {
  curl -fsSL -H "Accept: application/vnd.github+json" -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "X-GitHub-Api-Version: 2022-11-28" "$@"
}

json_field() {
  python3 -c 'import json, sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"
}

status="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $GITHUB_TOKEN" "$API/releases/tags/$TAG")"
if [[ "$status" != 404 ]]; then
  echo "Release $TAG already exists in $REPOSITORY (HTTP $status); refusing to overwrite it." >&2
  exit 1
fi

(cd "$DIRECTORY" && for file in "${FILES[@]}"; do basename "$file"; done | xargs shasum -a 256) > "$DIRECTORY/SHA256SUMS"

prerelease=false
[[ "$VERSION" == *-* ]] && prerelease=true
body="$(python3 -c 'import json, sys; print(json.dumps({"tag_name": sys.argv[1], "target_commitish": sys.argv[2], "name": "Bosca CLI " + sys.argv[3], "draft": True, "prerelease": sys.argv[4] == "true"}))' "$TAG" "$COMMIT" "$VERSION" "$prerelease")"
release="$(github -X POST "$API/releases" -d "$body")"
id="$(json_field id <<<"$release")"
echo "Created draft release $TAG ($id) at $COMMIT"

for file in "${FILES[@]}" "$DIRECTORY/SHA256SUMS"; do
  name="$(basename "$file")"
  github -X POST -H "Content-Type: application/octet-stream" --data-binary "@$file" \
    "https://uploads.github.com/repos/$REPOSITORY/releases/$id/assets?name=$name" >/dev/null
  echo "Uploaded $name"
done

github -X PATCH "$API/releases/$id" -d '{"draft": false}' >/dev/null
echo "Published https://github.com/$REPOSITORY/releases/tag/$TAG"
