#!/usr/bin/env bash
# Exercise packaging with Helm and publishing with isolated command fixtures.
set -euo pipefail
SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release.sh"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}';
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}
mkdir -p "$TEMP/bin" "$TEMP/packages"
for name in alpha omega; do
  printf '%s chart' "$name" > "$TEMP/packages/$name-0.1.0.tgz"
  printf '%s\t0.1.0\t%s-0.1.0.tgz\t%s\n' "$name" "$name" "$(sha256 "$TEMP/packages/$name-0.1.0.tgz")" >> "$TEMP/packages/release.tsv"
done
cat > "$TEMP/bin/helm" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  registry) cat > "$RELEASE_TEST_STATE/password" ;;
  push)
    [[ "${FAIL_OCI_PUSH:-false}" != true ]] || { echo 'Simulated OCI push failure' >&2; exit 1; }
    cp "$2" "$RELEASE_TEST_STATE/oci/${2##*/}"
    echo oci >> "$RELEASE_TEST_STATE/writes"
    ;;
  pull)
    [[ "${OCI_AUTH_FAILURE:-false}" != true ]] || { echo '401 Unauthorized' >&2; exit 1; }
    name="${2##*/}"; shift 2
    version=""; directory=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --version) version="$2"; shift ;;
        --destination) directory="$2"; shift ;;
      esac
      shift
    done
    archive="$name-$version.tgz"
    [[ -f "$RELEASE_TEST_STATE/oci/$archive" ]] || { echo '404 Not Found' >&2; exit 1; }
    cp "$RELEASE_TEST_STATE/oci/$archive" "$directory/$archive"
    ;;
  *) echo "Unexpected Helm command: $*" >&2; exit 1 ;;
esac
MOCK
cat > "$TEMP/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$RELEASE_TEST_STATE/curl-arguments"
method=""; output=""; archive=""; url=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --request) method="$2"; shift ;;
    --output) output="$2"; shift ;;
    --data-binary) archive="${2#@}"; shift ;;
    --max-time|--write-out|--header) shift ;;
    --*) ;;
    *) url="$1" ;;
  esac
  shift
done
if [[ "${HTTP_AUTH_FAILURE:-false}" == true ]]; then printf 401; exit; fi
if [[ "$method" == POST ]]; then
  if [[ "${HTTP_CONFLICT:-false}" == true ]]; then
    printf different > "$RELEASE_TEST_STATE/http/${archive##*/}"
    printf 409
  else
    cp "$archive" "$RELEASE_TEST_STATE/http/${archive##*/}"
    echo http >> "$RELEASE_TEST_STATE/writes"
    printf 201
  fi
else
  version="${url##*/}"; parent="${url%/*}"; name="${parent##*/}"
  if [[ -f "$RELEASE_TEST_STATE/http/$name-$version.tgz" ]]; then
    cp "$RELEASE_TEST_STATE/http/$name-$version.tgz" "$output"
    printf 200
  else
    printf 404
  fi
fi
MOCK
chmod +x "$TEMP/bin/helm" "$TEMP/bin/curl"
export RELEASE_TEST_STATE="$TEMP/state"
reset_registry() {
  rm -rf "$RELEASE_TEST_STATE"
  mkdir -p "$RELEASE_TEST_STATE/http" "$RELEASE_TEST_STATE/oci"
  : > "$RELEASE_TEST_STATE/writes"
}
publish() {
  PATH="$TEMP/bin:$PATH" HELM_REPO_URL=https://example.invalid/helm/bosca-charts \
    HELM_OCI_URL=oci://example.invalid/bosca-charts \
    bash "$SCRIPT" publish --output "$TEMP/packages"
}
expect_failure() {
  local pattern="$1"; shift
  if "$@" > "$TEMP/failure.log" 2>&1; then fail "Expected failure: $pattern"; fi
  grep -Fq "$pattern" "$TEMP/failure.log" || { cat "$TEMP/failure.log" >&2; fail "Missing failure: $pattern"; }
}

reset_registry
HELM_REPO_TOKEN=secret-http HELM_OCI_USERNAME=api_token HELM_OCI_TOKEN=secret-oci publish
[[ "$(cat "$RELEASE_TEST_STATE/password")" == secret-oci ]] || fail "OCI password was not sent over stdin"
! grep -q secret-http "$RELEASE_TEST_STATE/curl-arguments" || fail "HTTP token appeared in command arguments"
[[ "$(wc -l < "$RELEASE_TEST_STATE/writes")" -eq 4 ]] || fail "Both destinations were not populated"
for archive in "$TEMP/packages"/*.tgz; do
  cmp "$archive" "$RELEASE_TEST_STATE/http/${archive##*/}"
  cmp "$archive" "$RELEASE_TEST_STATE/oci/${archive##*/}"
done
publish
[[ "$(wc -l < "$RELEASE_TEST_STATE/writes")" -eq 4 ]] || fail "Retry uploaded existing archives"

reset_registry
FAIL_OCI_PUSH=true expect_failure 'Simulated OCI push failure' publish
[[ -f "$RELEASE_TEST_STATE/http/alpha-0.1.0.tgz" && ! -f "$RELEASE_TEST_STATE/oci/alpha-0.1.0.tgz" ]] || fail "Expected partial release"
publish
[[ "$(wc -l < "$RELEASE_TEST_STATE/writes")" -eq 4 ]] || fail "Partial release was not resumed"

for destination in http oci; do
  reset_registry
  printf conflicting > "$RELEASE_TEST_STATE/$destination/omega-0.1.0.tgz"
  expect_failure 'different contents' publish
  [[ ! -s "$RELEASE_TEST_STATE/writes" ]] || fail "A conflict uploaded an earlier chart"
done
reset_registry
HTTP_AUTH_FAILURE=true expect_failure '401' publish
OCI_AUTH_FAILURE=true expect_failure '401 Unauthorized' publish
[[ ! -s "$RELEASE_TEST_STATE/writes" ]] || fail "Authentication failure uploaded charts"
HTTP_CONFLICT=true expect_failure 'different contents' publish
[[ ! -s "$RELEASE_TEST_STATE/writes" ]] || fail "HTTP conflict was followed by an OCI push"
cp "$TEMP/packages/alpha-0.1.0.tgz" "$TEMP/original"
printf tampered > "$TEMP/packages/alpha-0.1.0.tgz"
expect_failure 'checksum mismatch' publish
mv "$TEMP/original" "$TEMP/packages/alpha-0.1.0.tgz"
echo 'PASS: publishing, identical bytes, retries, conflicts and credentials'

# Real Helm integration: retain versions, bundle local dependencies, preserve sources.
mkdir -p "$TEMP/charts/common" "$TEMP/charts/app"
cat > "$TEMP/charts/common/Chart.yaml" <<'CHART'
apiVersion: v2
name: common
version: 0.1.0
type: library
CHART
cat > "$TEMP/charts/app/Chart.yaml" <<'CHART'
apiVersion: v2
name: app
version: 0.1.0
appVersion: "6.9.0"
dependencies:
  - name: common
    version: ">=0.1.0"
    repository: "file://../common"
CHART
HELM_REPOSITORY_CONFIG="$TEMP/repositories.yaml" HELM_REPOSITORY_CACHE="$TEMP/cache" \
  helm dependency update "$TEMP/charts/app" --skip-refresh
cp -R "$TEMP/charts" "$TEMP/before"
bash "$SCRIPT" package --charts "$TEMP/charts" --output "$TEMP/packaged"
diff -r "$TEMP/before" "$TEMP/charts"
tar -tzf "$TEMP/packaged/app-0.1.0.tgz" > "$TEMP/archive-files"
grep -Fxq app/charts/common/Chart.yaml "$TEMP/archive-files" || fail "Dependency was not bundled"
helm show chart "$TEMP/packaged/app-0.1.0.tgz" > "$TEMP/chart-metadata"
grep -Fxq 'version: 0.1.0' "$TEMP/chart-metadata" || fail "Chart version changed"
grep -Fq 6.9.0 "$TEMP/chart-metadata" || fail "Application version changed"
expect_failure 'must be empty' bash "$SCRIPT" package --charts "$TEMP/charts" --output "$TEMP/packaged"
rm "$TEMP/charts/app/Chart.lock"
expect_failure 'Missing dependency lock' bash "$SCRIPT" package --charts "$TEMP/charts" --output "$TEMP/missing-lock"
echo 'PASS: real Helm packaging, dependency locks, chart versions and source preservation'
