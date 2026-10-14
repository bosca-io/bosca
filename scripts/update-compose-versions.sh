#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

die() { printf 'Unable to update Compose versions: %s\n' "$*" >&2; exit 1; }

dry_run=false
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry_run=true ;;
    -h|--help)
      cat <<'HELP'
Usage: scripts/update-compose-versions.sh [--dry-run]

Requires Bash, curl, and jq. Queries public GHCR tags without credentials.
Updates docker-compose.yaml and .env.example with the newest stable release
available for every image sharing a version variable. Ignores prerelease tags.
The image processor is updated separately. Existing .env overrides are preserved.
Third-party infrastructure images are unchanged. --dry-run previews changes.
HELP
      exit 0 ;;
    *) die "Unknown argument: $arg. Use --help for usage." ;;
  esac
done

for tool in curl jq; do
  command -v "$tool" >/dev/null || die "Install $tool before running this script."
done

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_tmp="$(mktemp -d "${TMPDIR:-/tmp}/bosca-compose-versions.XXXXXX")"
trap 'rm -rf "$task_tmp"' EXIT
compose="$root/docker-compose.yaml"
env_example="$root/.env.example"
[[ -f "$compose" && -f "$env_example" ]] || die 'Missing docker-compose.yaml or .env.example.'

sed -nE 's@^[[:space:]]*image: \$\{BOSCA_IMAGE_REGISTRY:-ghcr\.io/bosca-io\}/([[:alnum:]_-]+):\$\{(BOSCA_[A-Z_]*VERSION):-([^}]+)\}[[:space:]]*$@\1 \2 \3@p' "$compose" > "$task_tmp/images"
[[ -s "$task_tmp/images" ]] || die 'No public Bosca image defaults found in docker-compose.yaml.'
awk '{print $2}' "$task_tmp/images" | sort -u > "$task_tmp/variables"
while read -r variable; do
  count="$(awk -v variable="$variable" '$0 ~ "^" variable "=" {count++} END {print count+0}' "$env_example")"
  [[ "$count" = 1 ]] || die "Expected exactly one $variable entry in .env.example."
done < "$task_tmp/variables"

curl_flags=(--fail --silent --show-error --connect-timeout 10 --max-time 30)
while read -r image variable current; do
  printf 'Checking ghcr.io/bosca-io/%s...\n' "$image"
  token="$(curl "${curl_flags[@]}" --get --data-urlencode service=ghcr.io \
    --data-urlencode "scope=repository:bosca-io/$image:pull" https://ghcr.io/token \
    | jq -er '(.token // .access_token) | select(type == "string" and length > 0)')" \
    || die "Cannot get an anonymous pull token for $image."
  printf '%s' "$token" > "$task_tmp/token-$image"
  url="https://ghcr.io/v2/bosca-io/$image/tags/list?n=100"
  : > "$task_tmp/tags-$image"
  while [[ -n "$url" ]]; do
    curl "${curl_flags[@]}" -H "Authorization: Bearer $token" \
      -D "$task_tmp/headers" -o "$task_tmp/page" "$url" || die "Cannot list tags for $image."
    jq -r 'if .tags == null then [] elif (.tags | type) == "array" then .tags else error("Invalid tag list") end
      | .[] | select(type == "string")
      | select(test("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$"))' \
      "$task_tmp/page" >> "$task_tmp/tags-$image" || die "Invalid tag list for $image."
    next="$(tr -d '\r' < "$task_tmp/headers" \
      | sed -nE 's/^[Ll][Ii][Nn][Kk]:.*<([^>]+)>;[[:space:]]*rel="next".*/\1/p')"
    case "$next" in
      '') url='' ;;
      https://*) url="$next" ;;
      /*) url="https://ghcr.io$next" ;;
      *) url="${url%/*}/$next" ;;
    esac
  done
  sort -u "$task_tmp/tags-$image" > "$task_tmp/sorted"
  mv "$task_tmp/sorted" "$task_tmp/tags-$image"
  if [[ -f "$task_tmp/common-$variable" ]]; then
    comm -12 "$task_tmp/common-$variable" "$task_tmp/tags-$image" > "$task_tmp/common-next"
    mv "$task_tmp/common-next" "$task_tmp/common-$variable"
  else
    cp "$task_tmp/tags-$image" "$task_tmp/common-$variable"
  fi
done < "$task_tmp/images"

while read -r variable; do
  latest="$(awk '{version=$0; sub(/^v/, "", version); split(version, n, "."); print n[1], n[2], n[3], $0}' \
    "$task_tmp/common-$variable" | sort -k1,1nr -k2,2nr -k3,3nr -k4,4 | awk 'NR == 1 {print $4}')"
  [[ -n "$latest" ]] || die "No stable release tag is available for every image using $variable."
  printf '%s' "$latest" > "$task_tmp/latest-$variable"
done < "$task_tmp/variables"

# Resolve every selected manifest before changing any files, including on a dry run.
manifest_types='application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json'
while read -r image variable current; do
  token="$(cat "$task_tmp/token-$image")"
  latest="$(cat "$task_tmp/latest-$variable")"
  curl "${curl_flags[@]}" -H "Authorization: Bearer $token" -H "Accept: $manifest_types" \
    -o /dev/null "https://ghcr.io/v2/bosca-io/$image/manifests/$latest" \
    || die "Cannot resolve $image:$latest. Files were not changed."
done < "$task_tmp/images"

cp "$compose" "$task_tmp/compose"
cp "$env_example" "$task_tmp/env"
while read -r variable; do
  latest="$(cat "$task_tmp/latest-$variable")"
  current="$(awk -v variable="$variable" '$2 == variable {print $3}' "$task_tmp/images" | sort -u | paste -sd ',' -)"
  printf '%s: %s -> %s\n' "$variable" "$current" "$latest"
  awk -v variable="$variable" -v version="$latest" \
    '{gsub("\\$\\{" variable ":-[^}]+\\}", "${" variable ":-" version "}"); print}' \
    "$task_tmp/compose" > "$task_tmp/compose-next"
  mv "$task_tmp/compose-next" "$task_tmp/compose"
  awk -v variable="$variable" -v version="$latest" \
    '$0 ~ "^" variable "=" {$0=variable "=" version} {print}' \
    "$task_tmp/env" > "$task_tmp/env-next"
  mv "$task_tmp/env-next" "$task_tmp/env"
done < "$task_tmp/variables"

changed=false
for file in compose env; do
  if [[ "$file" = compose ]]; then target="$compose"; else target="$env_example"; fi
  if ! cmp -s "$task_tmp/$file" "$target"; then
    changed=true
    if "$dry_run"; then
      printf 'Would update: %s\n' "${target##*/}"
    else
      cat "$task_tmp/$file" > "$target"
      printf 'Updated: %s\n' "${target##*/}"
    fi
  fi
done
if ! "$changed"; then printf 'Versions are already current.\n'; fi
