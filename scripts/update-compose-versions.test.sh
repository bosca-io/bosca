#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
test_tmp="$(mktemp -d "${TMPDIR:-/tmp}/bosca-versions-test.XXXXXX")"
trap 'rm -rf "$test_tmp"' EXIT
mkdir -p "$test_tmp/workspace/scripts" "$test_tmp/bin"
cp "$script_dir/update-compose-versions.sh" "$test_tmp/workspace/scripts/"
root="$test_tmp/workspace"
cat > "$root/docker-compose.yaml" <<'COMPOSE'
services:
  server:
    image: ${BOSCA_IMAGE_REGISTRY:-ghcr.io/bosca-io}/bosca-server:${BOSCA_VERSION:-0.0.8}
  runner:
    image: ${BOSCA_IMAGE_REGISTRY:-ghcr.io/bosca-io}/bosca-runner:${BOSCA_VERSION:-0.0.8}
  imageprocessor:
    image: ${BOSCA_IMAGE_REGISTRY:-ghcr.io/bosca-io}/imageprocessor:${BOSCA_IMAGEPROCESSOR_VERSION:-0.0.26}
  studio:
    environment:
      NUXT_PUBLIC_APP_VERSION: ${BOSCA_VERSION:-0.0.8}
  redis:
    image: redis:7.4.11-alpine
COMPOSE
printf 'BOSCA_VERSION=0.0.8\nBOSCA_IMAGEPROCESSOR_VERSION=0.0.26\nBOSCA_PORT=3000\n' > "$root/.env.example"
printf 'BOSCA_VERSION=custom\nINIT_ADMIN_PASSWORD=custom-test\n' > "$root/.env"
cp "$root/docker-compose.yaml" "$test_tmp/original-compose"
cp "$root/.env.example" "$test_tmp/original-env"
cp "$root/.env" "$test_tmp/private-env"

# Replace curl only in this test process; the updater still parses real JSON with jq.
cat > "$test_tmp/bin/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
output=''
headers=''
url=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) output="$2"; shift 2 ;;
    -D) headers="$2"; shift 2 ;;
    -H|--data-urlencode|--connect-timeout|--max-time) shift 2 ;;
    https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done
next=''
case "$url" in
  */token) body='{"token":"anonymous-pull-token"}' ;;
  */manifests/*)
    if [[ "${MOCK_MODE:-}" = manifest-failure && "$url" = */bosca-runner/* ]]; then exit 22; fi
    printf '%s\n' "$url" >> "$MANIFEST_LOG"
    body='{}' ;;
  */tags/list*)
    if [[ "${MOCK_MODE:-}" = tags-failure ]]; then exit 22; fi
    if [[ "$url" = */imageprocessor/* ]]; then
      body='{"tags":["0.0.26","v0.0.27","0.0.28-rc.1"]}'
    elif [[ "${MOCK_MODE:-}" = no-common && "$url" = */bosca-runner/* ]]; then
      body='{"tags":["0.0.1"]}'
    elif [[ "$url" = *last=* ]]; then
      body='{"tags":["0.0.10"]}'
    else
      body='{"tags":["0.0.8","0.0.9","latest","0.0.12-rc.1","abcdef"]}'
      if [[ "$url" = */bosca-server/* ]]; then body='{"tags":["0.0.8","0.0.9","0.0.11","latest","0.0.12-rc.1"]}'; fi
      next="${url#https://ghcr.io}&last=0.0.9"
    fi ;;
  *) printf 'Unexpected URL: %s\n' "$url" >&2; exit 1 ;;
esac
if [[ -n "$headers" ]]; then
  printf 'HTTP/2 200\r\n' > "$headers"
  if [[ -n "$next" ]]; then printf 'Link: <%s>; rel="next"\r\n' "$next" >> "$headers"; fi
fi
if [[ -n "$output" ]]; then printf '%s\n' "$body" > "$output"; else printf '%s\n' "$body"; fi
CURL
chmod +x "$test_tmp/bin/curl"
export PATH="$test_tmp/bin:$PATH"
export MANIFEST_LOG="$test_tmp/manifests"
updater="$root/scripts/update-compose-versions.sh"

unchanged() {
  cmp "$root/docker-compose.yaml" "$test_tmp/original-compose"
  cmp "$root/.env.example" "$test_tmp/original-env"
  cmp "$root/.env" "$test_tmp/private-env"
}

# Run outside the fixture root to exercise script-relative file resolution.
cd "$test_tmp"
bash "$updater" --dry-run > "$test_tmp/output"
unchanged
grep -q 'Would update: docker-compose.yaml' "$test_tmp/output"
[[ "$(wc -l < "$MANIFEST_LOG" | tr -d ' ')" = 3 ]]
printf 'PASS: dry run checks every manifest without editing files\n'

bash "$updater" > "$test_tmp/output"
sed -e 's/BOSCA_VERSION:-0.0.8/BOSCA_VERSION:-0.0.10/g' \
  -e 's/BOSCA_IMAGEPROCESSOR_VERSION:-0.0.26/BOSCA_IMAGEPROCESSOR_VERSION:-v0.0.27/g' \
  "$test_tmp/original-compose" > "$test_tmp/expected-compose"
cmp "$root/docker-compose.yaml" "$test_tmp/expected-compose"
printf 'BOSCA_VERSION=0.0.10\nBOSCA_IMAGEPROCESSOR_VERSION=v0.0.27\nBOSCA_PORT=3000\n' > "$test_tmp/expected-env"
cmp "$root/.env.example" "$test_tmp/expected-env"
cmp "$root/.env" "$test_tmp/private-env"
printf 'PASS: paginated numeric release selection, shared tags, and separate image processor version\n'

bash "$updater" > "$test_tmp/output"
grep -q 'Versions are already current.' "$test_tmp/output"
cmp "$root/docker-compose.yaml" "$test_tmp/expected-compose"
printf 'PASS: repeated updates preserve unrelated settings and are idempotent\n'

for mode in tags-failure no-common manifest-failure; do
  cp "$test_tmp/original-compose" "$root/docker-compose.yaml"
  cp "$test_tmp/original-env" "$root/.env.example"
  if MOCK_MODE="$mode" bash "$updater" > "$test_tmp/output" 2>&1; then
    printf 'Expected failure: %s\n' "$mode" >&2; exit 1
  fi
  unchanged
  grep -q 'Unable to update Compose versions:' "$test_tmp/output"
  printf 'PASS: %s leaves files untouched\n' "$mode"
done
