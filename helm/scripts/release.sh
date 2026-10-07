#!/usr/bin/env bash
# Package charts once, then publish those archives through HTTP Helm and OCI.
set -euo pipefail

CHART_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMMAND="${1:-}"
[[ $# -gt 0 ]] && shift
OUTPUT=""
REPO="${HELM_REPO_URL:-}"
OCI="${HELM_OCI_URL:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) OUTPUT="${2:?Missing output directory}" ;;
    --charts) CHART_ROOT="${2:?Missing chart directory}" ;;
    --repo) REPO="${2:?Missing HTTP repository URL}" ;;
    --oci) OCI="${2:?Missing OCI registry URL}" ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
  shift 2
done

fail() { echo "Release failed: $*" >&2; exit 1; }
[[ "$COMMAND" == package || "$COMMAND" == publish ]] || fail "Usage: bash release.sh package|publish --output DIRECTORY [--charts DIRECTORY] [--repo URL] [--oci URL]"
[[ -n "$OUTPUT" ]] || fail "Set --output"
command -v helm >/dev/null || fail "Helm is required"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT

sha256() {
  if command -v sha256sum >/dev/null; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# Read Helm's normalized metadata rather than parsing source YAML ourselves.
field() { awk -v key="$2" '$0 ~ "^" key ":" {print $2; exit}' "$1"; }
repositories() { awk '$1 == "repository:" {print $2}' "$1"; }

package_chart() {
  local chart="$1" key metadata repository child name version kind archive
  key="${chart##*/}"
  [[ ! -f "$TEMP/state/$key.done" ]] || return 0
  [[ ! -f "$TEMP/state/$key.visiting" ]] || fail "Cyclic dependency: $key"
  touch "$TEMP/state/$key.visiting"
  metadata="$TEMP/metadata/$key.yaml"
  while IFS= read -r repository; do
    if [[ "$repository" == file://* ]]; then
      child="$(cd "$chart/${repository#file://}" && pwd)"
      [[ "${child%/*}" == "$TEMP/charts" && -f "$child/Chart.yaml" ]] || fail "Local dependency outside chart staging: $repository"
      package_chart "$child"
    fi
  done < <(repositories "$metadata")
  if grep -q '^dependencies:' "$metadata"; then
    helm dependency build "$chart" --skip-refresh
  fi
  name="$(field "$metadata" name)"
  version="$(field "$metadata" version)"
  kind="$(field "$metadata" type)"
  local settings=(--set global.domain=validate.example.com --set appConfig.publicUrl=https://messages.validate.example.com)
  helm lint "$chart" --strict "${settings[@]}"
  if [[ "$kind" != library ]]; then
    helm template validate "$chart" "${settings[@]}" >/dev/null
  fi
  helm package "$chart" --destination "$OUTPUT"
  archive="$name-$version.tgz"
  printf '%s\t%s\t%s\t%s\n' "$name" "$version" "$archive" "$(sha256 "$OUTPUT/$archive")" >> "$TEMP/release.tsv"
  mv "$TEMP/state/$key.visiting" "$TEMP/state/$key.done"
}

package_charts() {
  mkdir -p "$OUTPUT"
  OUTPUT="$(cd "$OUTPUT" && pwd)"
  shopt -s nullglob dotglob
  local existing=("$OUTPUT"/*)
  [[ ${#existing[@]} -eq 0 ]] || fail "Output directory must be empty; use publish to retry existing packages"
  CHART_ROOT="$(cd "$CHART_ROOT" && pwd)"
  export HELM_REPOSITORY_CONFIG="$TEMP/repositories.yaml"
  export HELM_REPOSITORY_CACHE="$TEMP/cache"
  mkdir -p "$TEMP/charts" "$TEMP/metadata" "$TEMP/state"
  local chart_yaml key metadata repository index=0 count=0
  for chart_yaml in "$CHART_ROOT"/*/Chart.yaml; do
    key="${chart_yaml%/Chart.yaml}"
    key="${key##*/}"
    mkdir "$TEMP/charts/$key"
    cp -R "${chart_yaml%/Chart.yaml}/." "$TEMP/charts/$key/"
    rm -rf "$TEMP/charts/$key/charts"
    metadata="$TEMP/metadata/$key.yaml"
    helm show chart "$TEMP/charts/$key" > "$metadata"
    if grep -q '^dependencies:' "$metadata"; then
      [[ -f "$TEMP/charts/$key/Chart.lock" ]] || fail "Missing dependency lock: $key/Chart.lock"
    fi
    count=$((count + 1))
  done
  [[ "$count" -gt 0 ]] || fail "No charts found in $CHART_ROOT"
  for metadata in "$TEMP/metadata"/*.yaml; do
    repositories "$metadata"
  done | sort -u > "$TEMP/repositories"
  while IFS= read -r repository; do
    case "$repository" in
      http://*|https://*) helm repo add "release-$index" "$repository"; index=$((index + 1)) ;;
    esac
  done < "$TEMP/repositories"
  for chart_yaml in "$TEMP/charts"/*/Chart.yaml; do
    package_chart "${chart_yaml%/Chart.yaml}"
  done
  mv "$TEMP/release.tsv" "$OUTPUT/release.tsv"
  echo "Packaged $count charts in $OUTPUT"
}

http() {
  local method="$1" url="$2" destination="$3"
  local args=(--disable --silent --show-error --max-time 300 --request "$method"
              --output "$destination" --write-out '%{http_code}' --header "@$TEMP/auth-header")
  if [[ "$method" == POST ]]; then
    args+=(--header 'Content-Type: application/gzip' --data-binary "@$4")
  fi
  curl "${args[@]}" "$url"
}

helm_oci() {
  if [[ "${HELM_OCI_PLAIN_HTTP:-false}" == true ]]; then
    helm "$@" --plain-http
  else
    helm "$@"
  fi
}

check_oci() {
  local name="$1" version="$2" archive="$3" expected="$4" directory="$5"
  if helm_oci pull "$OCI/$name" --version "$version" --destination "$directory" > "$directory/oci.log" 2>&1; then
    [[ "$(sha256 "$directory/$archive")" == "$expected" ]] || fail "OCI chart version has different contents: $archive"
    echo present
  elif grep -Eiq 'not found|manifest_unknown' "$directory/oci.log"; then
    echo missing
  else
    cat "$directory/oci.log" >&2
    fail "Cannot check OCI chart: $archive"
  fi
}

verify_http() {
  local name="$1" version="$2" archive="$3" expected="$4" directory="$5" status
  status="$(http GET "$REPO/charts/$name/$version" "$directory/verify.tgz")"
  [[ "$status" == 200 ]] || fail "HTTP download returned $status: $archive"
  [[ "$(sha256 "$directory/verify.tgz")" == "$expected" ]] || fail "HTTP chart version has different contents: $archive"
}

publish_charts() {
  command -v curl >/dev/null || fail "curl is required"
  OUTPUT="$(cd "$OUTPUT" && pwd)"
  REPO="${REPO%/}"
  OCI="${OCI%/}"
  [[ "$REPO" == http://?* || "$REPO" == https://?* ]] || fail "Set HELM_REPO_URL to the HTTP Helm repository URL"
  [[ "$OCI" == oci://?* ]] || fail "Set HELM_OCI_URL to the OCI registry prefix without chart name or version"
  [[ -s "$OUTPUT/release.tsv" ]] || fail "Missing or empty release.tsv"
  local name version archive expected status oci_status directory count=0 host
  # Validate all archives before contacting either destination.
  while IFS=$'\t' read -r name version archive expected; do
    [[ "$name" =~ ^[a-zA-Z0-9._-]+$ && "$version" =~ ^[a-zA-Z0-9.+_-]+$ ]] || fail "Invalid chart coordinates in release.tsv"
    [[ "$archive" == "$name-$version.tgz" && "$expected" =~ ^[a-f0-9]{64}$ ]] || fail "Invalid archive entry in release.tsv"
    [[ -f "$OUTPUT/$archive" && "$(sha256 "$OUTPUT/$archive")" == "$expected" ]] || fail "Package checksum mismatch: $archive"
  done < "$OUTPUT/release.tsv"
  printf '' > "$TEMP/auth-header"
  local token="${HELM_REPO_TOKEN:-${BOSCA_REGISTRY_TOKEN:-}}"
  [[ -z "$token" ]] || printf 'Authorization: Bearer %s\n' "$token" > "$TEMP/auth-header"
  if [[ -n "${HELM_OCI_USERNAME:-}" || -n "${HELM_OCI_TOKEN:-}" ]]; then
    [[ -n "${HELM_OCI_USERNAME:-}" && -n "${HELM_OCI_TOKEN:-}" ]] || fail "Set both HELM_OCI_USERNAME and HELM_OCI_TOKEN"
    export HELM_REGISTRY_CONFIG="$TEMP/registry.json"
    host="${OCI#oci://}"
    host="${host%%/*}"
    printf '%s\n' "$HELM_OCI_TOKEN" | helm_oci registry login "$host" --username "$HELM_OCI_USERNAME" --password-stdin
  fi
  # Inspect every version before uploading any chart; preserve existing contents.
  while IFS=$'\t' read -r name version archive expected; do
    directory="$TEMP/check-$count"
    mkdir "$directory"
    status="$(http GET "$REPO/charts/$name/$version" "$directory/http.tgz")"
    case "$status" in
      200) [[ "$(sha256 "$directory/http.tgz")" == "$expected" ]] || fail "HTTP chart version has different contents: $archive" ;;
      404) ;;
      *) fail "HTTP chart check returned $status: $archive" ;;
    esac
    oci_status="$(check_oci "$name" "$version" "$archive" "$expected" "$directory")"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$version" "$archive" "$expected" "$status" "$oci_status" "$directory" >> "$TEMP/status.tsv"
    count=$((count + 1))
  done < "$OUTPUT/release.tsv"
  while IFS=$'\t' read -r name version archive expected status oci_status directory; do
    if [[ "$status" == 404 ]]; then
      status="$(http POST "$REPO/api/charts" "$directory/response" "$OUTPUT/$archive")"
      case "$status" in
        200|201|409) verify_http "$name" "$version" "$archive" "$expected" "$directory" ;;
        *) fail "HTTP upload returned $status: $archive" ;;
      esac
    fi
    if [[ "$oci_status" == missing ]]; then
      helm_oci push "$OUTPUT/$archive" "$OCI"
    fi
    verify_http "$name" "$version" "$archive" "$expected" "$directory"
    [[ "$(check_oci "$name" "$version" "$archive" "$expected" "$directory")" == present ]] || fail "OCI download verification failed: $archive"
    echo "Verified HTTP and OCI: $archive sha256:$expected"
  done < "$TEMP/status.tsv"
}

case "$COMMAND" in
  package) package_charts ;;
  publish) publish_charts ;;
esac
