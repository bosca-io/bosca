#!/bin/sh
set -eu
deployment_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$deployment_dir"

test -f .env || {
    echo 'Run install.sh --domain YOUR_DOMAIN --directory INSTALL_DIRECTORY first.' >&2
    exit 1
}

# Prepare every writable bind-mount source before invoking Docker.
mkdir -p data/postgres data/nats data/meilisearch data/storage data/server-tmp data/git-tmp data/message-cache

# Select this installation's configuration and registry login explicitly.
docker_bin=$(command -v docker)
exec "$docker_bin" --config "$deployment_dir/.docker" compose \
    --project-directory "$deployment_dir" --env-file "$deployment_dir/.env" \
    -f "$deployment_dir/compose.yaml" "$@"
