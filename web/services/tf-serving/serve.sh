#!/usr/bin/env bash
#
# Discover only complete, selected model directories. Each model loads one exact version.
# A changed selection stops the serving process before starting its replacement, avoiding
# the extra memory required by overlapping generations. Stored versions remain on disk.
#
# Optional environment: MODELS_ROOT (/models), MODEL_NAMES (global model names; empty disables them),
# CONFIG_PATH (/tmp/models.config), REST_PORT (8501), GRPC_PORT (8500), POLL_WAIT_SECONDS (30).

set -uo pipefail

MODELS_ROOT="${MODELS_ROOT:-/models}"
CONFIG_PATH="${CONFIG_PATH:-/tmp/models.config}"   # /tmp so it works even when the models mount is read-only
REST_PORT="${REST_PORT:-8501}"
GRPC_PORT="${GRPC_PORT:-8500}"
MODEL_NAMES="${MODEL_NAMES-recommender-content recommender-personalized}"
POLL_WAIT_SECONDS="${POLL_WAIT_SECONDS:-30}"

# Best-effort: create the base paths so an empty one still exists. No-op (and non-fatal) when the models
# volume is mounted read-only — a read-write init step is expected to have created them in that case.
for m in $MODEL_NAMES; do mkdir -p "$MODELS_ROOT/$m" 2>/dev/null || true; done

# Context exports are discovered from loader-owned selections, never from an unselected latest version.
selected_model_names() {
  printf '%s\n' $MODEL_NAMES
  for selection in "$MODELS_ROOT"/recommender-*-content/.served-versions "$MODELS_ROOT"/recommender-*-personalized/.served-versions; do
    [ -s "$selection" ] || continue
    local directory="${selection%/.served-versions}"
    local name="${directory##*/}"
    [[ "$name" =~ ^recommender-[a-f0-9-]{36}-(content|personalized)$ ]] || continue
    printf '%s\n' "$name"
  done
}

generate_config() {
  CONFIG_CHANGED=0
  local desired
  desired="$(
    echo 'model_config_list {'
    for m in $(selected_model_names | sort -u); do
      if ls -d "$MODELS_ROOT/$m"/[0-9]* >/dev/null 2>&1; then
        local selection_file="$MODELS_ROOT/$m/.served-versions"
        local newest_version=0
        for directory in "$MODELS_ROOT/$m"/[0-9]*; do
          [ -d "$directory" ] || continue
          local version="${directory##*/}"
          case "$version" in (''|*[!0-9]*) continue ;; esac
          if [ "$version" -gt "$newest_version" ]; then newest_version="$version"; fi
        done
        [ "$newest_version" -gt 0 ] || continue
        local version_policy="specific { versions: $newest_version }"
        if [ -s "$selection_file" ]; then
          local selected_version=0
          while IFS= read -r version; do
            case "$version" in
              (''|*[!0-9]*) continue ;;
            esac
            if [ "$version" -gt "$selected_version" ]; then selected_version="$version"; fi
          done < "$selection_file"
          if [ "$selected_version" -gt 0 ]; then
            version_policy="specific { versions: $selected_version }"
          fi
        fi
        printf '  config { name: "%s" base_path: "%s/%s" model_platform: "tensorflow" model_version_policy { %s } }\n' \
          "$m" "$MODELS_ROOT" "$m" "$version_policy"
      fi
    done
    echo '}'
  )"
  # Command substitution strips trailing newlines on both sides, so the comparison is stable across runs.
  if [ "$desired" != "$(cat "$CONFIG_PATH" 2>/dev/null)" ]; then
    printf '%s\n' "$desired" > "${CONFIG_PATH}.tmp"
    mv "${CONFIG_PATH}.tmp" "$CONFIG_PATH"   # atomic swap, only on an actual change
    CONFIG_CHANGED=1
    echo "serve.sh: served model set changed -> $(printf '%s' "$desired" | grep -o 'name: "[^"]*"' | tr '\n' ' ')"
  fi
}

generate_config

# The stock server keeps old and new versions resident during hot reload. Stop it fully before
# starting a changed selection so replacement never needs memory for both generations.
start_server() {
  tensorflow_model_server \
    --port="$GRPC_PORT" \
    --rest_api_port="$REST_PORT" \
    --model_config_file="$CONFIG_PATH" \
    --model_config_file_poll_wait_seconds=0 \
    --file_system_poll_wait_seconds=0 \
    --num_load_threads=1 &
  server_pid=$!
}

stop_server() {
  kill -TERM "$server_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
}

shutdown() {
  if [ -n "${poll_pid:-}" ]; then kill "$poll_pid" 2>/dev/null || true; fi
  stop_server
  exit 0
}

trap shutdown TERM INT
start_server
while kill -0 "$server_pid" 2>/dev/null; do
  sleep "$POLL_WAIT_SECONDS" &
  poll_pid=$!
  wait "$poll_pid" || true
  poll_pid=''
  kill -0 "$server_pid" 2>/dev/null || break
  generate_config
  if [ "$CONFIG_CHANGED" -eq 1 ]; then
    echo "serve.sh: replacing model selection; stopping the previous server before loading"
    stop_server
    start_server
  fi
done
# Preserve unexpected server failures so the container runtime can report and restart them.
wait "$server_pid"
exit $?
