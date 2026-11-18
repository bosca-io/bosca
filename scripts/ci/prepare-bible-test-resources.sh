#!/usr/bin/env bash
set -euo pipefail

: "${BOSCA_REGISTRY_TOKEN:?setup-registry must provide BOSCA_REGISTRY_TOKEN}"
if [[ -z "${BIBLE_RAW_ARTIFACTS:-}" ]]; then
    : "${BOSCA_REGISTRY_URL:?Set BOSCA_REGISTRY_URL or BIBLE_RAW_ARTIFACTS}"
    registry="${BOSCA_REGISTRY_URL%/}"
    case "$registry" in
        http://*|https://*) ;;
        *) registry="https://$registry" ;;
    esac
    BIBLE_RAW_ARTIFACTS="$registry/raw/bibles/bibles/0.0.1"
fi

workspace="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
destination="$workspace/content/bible-compiler/.test-resources"
mkdir -p "$destination"
temporary=""
trap 'if [[ -n "$temporary" ]]; then rm -f "$temporary"; fi' EXIT

for translation in kjv asv; do
    temporary="$(mktemp "$destination/$translation.zip.XXXXXX")"
    printf 'header = "Authorization: Bearer %s"\n' "$BOSCA_REGISTRY_TOKEN" |
        curl --config - --fail --silent --show-error --location \
            --connect-timeout 30 --max-time 120 \
            "${BIBLE_RAW_ARTIFACTS%/}/$translation.zip" --output "$temporary"
    if ! unzip -tqq "$temporary"; then
        printf 'Registry response for %s is not a valid ZIP bundle\n' "$translation" >&2
        exit 1
    fi
    mv "$temporary" "$destination/$translation.zip"
    temporary=""
done
