#!/usr/bin/env bash
# =============================================================================
# Generate a Project Leyden AOT cache for bosca-server.
#
# Spins up dependencies, starts the server with AOT recording, runs k6 to
# exercise hot paths, then extracts the .aot file.
#
# Prerequisites: docker, k6
# Output: app.aot in this script's directory
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yaml"
OUTPUT_FILE="$SCRIPT_DIR/app.aot"
GRAPHQL_URL="http://localhost:8080/graphql"
MAX_WAIT=120

cleanup() {
    echo "--- Tearing down services ---"
    docker compose -f "$COMPOSE_FILE" down -v --remove-orphans 2>/dev/null || true
}
trap cleanup EXIT

echo "--- Starting dependencies ---"
docker compose -f "$COMPOSE_FILE" up -d postgres nats dragonfly meilisearch s3proxy mc

echo "--- Waiting for dependencies to be healthy ---"
docker compose -f "$COMPOSE_FILE" up -d --wait postgres meilisearch

echo "--- Starting bosca-server with AOT recording ---"
docker compose -f "$COMPOSE_FILE" up -d bosca-server

echo "--- Waiting for server readiness ---"
elapsed=0
until curl -sf "$GRAPHQL_URL" -H 'Content-Type: application/json' \
    -d '{"query":"{ server { now } }"}' > /dev/null 2>&1; do
    if [ "$elapsed" -ge "$MAX_WAIT" ]; then
        echo "ERROR: Server did not become ready within ${MAX_WAIT}s"
        docker compose -f "$COMPOSE_FILE" logs bosca-server
        exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
    echo "  waiting... (${elapsed}s)"
done
echo "Server ready after ${elapsed}s"

echo "--- Running k6 warm-up ---"
k6 run --env GRAPHQL_URL="$GRAPHQL_URL" "$SCRIPT_DIR/warmup.js"

echo "--- Stopping server (triggers AOT cache write) ---"
docker compose -f "$COMPOSE_FILE" stop -t 30 bosca-server

echo "--- Extracting AOT cache ---"
CONTAINER_ID=$(docker compose -f "$COMPOSE_FILE" ps -q bosca-server)
if [ -z "$CONTAINER_ID" ]; then
    echo "ERROR: Could not find bosca-server container"
    exit 1
fi

# Copy from the named volume via the stopped container
docker cp "$CONTAINER_ID:/aot/app.aot" "$OUTPUT_FILE" 2>/dev/null || {
    echo "WARNING: AOT cache file not found — the JVM may not have written it"
    exit 1
}

echo "--- AOT cache generated: $OUTPUT_FILE ($(du -h "$OUTPUT_FILE" | cut -f1)) ---"
