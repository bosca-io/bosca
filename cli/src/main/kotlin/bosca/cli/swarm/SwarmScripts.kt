package bosca.cli.swarm

// Scripts shipped to the Swarm as Swarm configs. Kept as Kotlin strings so the native CLI needs no
// resource registration; `$` is escaped for Kotlin's raw strings.

/**
 * TensorFlow Serving start script, copied verbatim from the tf-serving Helm chart
 * (`helm/tf-serving/files/serve.sh`) so both deployments serve models the same way.
 */
internal val TF_SERVING_SERVE_SCRIPT = """#!/usr/bin/env bash
#
# Discover only complete, selected model directories. Each model loads one exact version.
# A changed selection stops the serving process before starting its replacement, avoiding
# the extra memory required by overlapping generations. Stored versions remain on disk.
#
# Optional environment: MODELS_ROOT (/models), MODEL_NAMES (global model names; empty disables them),
# CONFIG_PATH (/tmp/models.config), REST_PORT (8501), GRPC_PORT (8500), POLL_WAIT_SECONDS (30).

set -uo pipefail

MODELS_ROOT="${'$'}{MODELS_ROOT:-/models}"
CONFIG_PATH="${'$'}{CONFIG_PATH:-/tmp/models.config}"   # /tmp so it works even when the models mount is read-only
REST_PORT="${'$'}{REST_PORT:-8501}"
GRPC_PORT="${'$'}{GRPC_PORT:-8500}"
MODEL_NAMES="${'$'}{MODEL_NAMES-recommender-content recommender-personalized}"
POLL_WAIT_SECONDS="${'$'}{POLL_WAIT_SECONDS:-30}"

# Best-effort: create the base paths so an empty one still exists. No-op (and non-fatal) when the models
# volume is mounted read-only — a read-write init step is expected to have created them in that case.
for m in ${'$'}MODEL_NAMES; do mkdir -p "${'$'}MODELS_ROOT/${'$'}m" 2>/dev/null || true; done

# Context exports are discovered from loader-owned selections, never from an unselected latest version.
selected_model_names() {
  printf '%s\n' ${'$'}MODEL_NAMES
  for selection in "${'$'}MODELS_ROOT"/recommender-*-content/.served-versions "${'$'}MODELS_ROOT"/recommender-*-personalized/.served-versions; do
    [ -s "${'$'}selection" ] || continue
    local directory="${'$'}{selection%/.served-versions}"
    local name="${'$'}{directory##*/}"
    [[ "${'$'}name" =~ ^recommender-[a-f0-9-]{36}-(content|personalized)${'$'} ]] || continue
    printf '%s\n' "${'$'}name"
  done
}

generate_config() {
  CONFIG_CHANGED=0
  local desired
  desired="${'$'}(
    echo 'model_config_list {'
    for m in ${'$'}(selected_model_names | sort -u); do
      if ls -d "${'$'}MODELS_ROOT/${'$'}m"/[0-9]* >/dev/null 2>&1; then
        local selection_file="${'$'}MODELS_ROOT/${'$'}m/.served-versions"
        local newest_version=0
        for directory in "${'$'}MODELS_ROOT/${'$'}m"/[0-9]*; do
          [ -d "${'$'}directory" ] || continue
          local version="${'$'}{directory##*/}"
          case "${'$'}version" in (''|*[!0-9]*) continue ;; esac
          if [ "${'$'}version" -gt "${'$'}newest_version" ]; then newest_version="${'$'}version"; fi
        done
        [ "${'$'}newest_version" -gt 0 ] || continue
        local version_policy="specific { versions: ${'$'}newest_version }"
        if [ -s "${'$'}selection_file" ]; then
          local selected_version=0
          while IFS= read -r version; do
            case "${'$'}version" in
              (''|*[!0-9]*) continue ;;
            esac
            if [ "${'$'}version" -gt "${'$'}selected_version" ]; then selected_version="${'$'}version"; fi
          done < "${'$'}selection_file"
          if [ "${'$'}selected_version" -gt 0 ]; then
            version_policy="specific { versions: ${'$'}selected_version }"
          fi
        fi
        printf '  config { name: "%s" base_path: "%s/%s" model_platform: "tensorflow" model_version_policy { %s } }\n' \
          "${'$'}m" "${'$'}MODELS_ROOT" "${'$'}m" "${'$'}version_policy"
      fi
    done
    echo '}'
  )"
  # Command substitution strips trailing newlines on both sides, so the comparison is stable across runs.
  if [ "${'$'}desired" != "${'$'}(cat "${'$'}CONFIG_PATH" 2>/dev/null)" ]; then
    printf '%s\n' "${'$'}desired" > "${'$'}{CONFIG_PATH}.tmp"
    mv "${'$'}{CONFIG_PATH}.tmp" "${'$'}CONFIG_PATH"   # atomic swap, only on an actual change
    CONFIG_CHANGED=1
    echo "serve.sh: served model set changed -> ${'$'}(printf '%s' "${'$'}desired" | grep -o 'name: "[^"]*"' | tr '\n' ' ')"
  fi
}

generate_config

# The stock server keeps old and new versions resident during hot reload. Stop it fully before
# starting a changed selection so replacement never needs memory for both generations.
start_server() {
  tensorflow_model_server \
    --port="${'$'}GRPC_PORT" \
    --rest_api_port="${'$'}REST_PORT" \
    --model_config_file="${'$'}CONFIG_PATH" \
    --model_config_file_poll_wait_seconds=0 \
    --file_system_poll_wait_seconds=0 \
    --num_load_threads=1 &
  server_pid=${'$'}!
}

stop_server() {
  kill -TERM "${'$'}server_pid" 2>/dev/null || true
  wait "${'$'}server_pid" 2>/dev/null || true
}

shutdown() {
  if [ -n "${'$'}{poll_pid:-}" ]; then kill "${'$'}poll_pid" 2>/dev/null || true; fi
  stop_server
  exit 0
}

trap shutdown TERM INT
start_server
while kill -0 "${'$'}server_pid" 2>/dev/null; do
  sleep "${'$'}POLL_WAIT_SECONDS" &
  poll_pid=${'$'}!
  wait "${'$'}poll_pid" || true
  poll_pid=''
  kill -0 "${'$'}server_pid" 2>/dev/null || break
  generate_config
  if [ "${'$'}CONFIG_CHANGED" -eq 1 ]; then
    echo "serve.sh: replacing model selection; stopping the previous server before loading"
    stop_server
    start_server
  fi
done
# Preserve unexpected server failures so the container runtime can report and restart them.
wait "${'$'}server_pid"
exit ${'$'}?
"""

/** Nightly `pg_dump` of every site database; see [backupServices]. */
internal val BACKUP_DUMP_SCRIPT = """#!/bin/sh
# Nightly logical backup of every site database, written atomically into /backups/postgres.
# Usage: backup-dump.sh [once]. Environment: PGHOST, PGUSER, PGPASSWORD, BACKUP_DATABASES, BACKUP_HOUR.
set -u

dump_all() {
  mkdir -p /backups/postgres
  status=0
  for database in ${'$'}BACKUP_DATABASES; do
    if pg_dump --format=custom --file="/backups/postgres/${'$'}database.dump.tmp" "${'$'}database"; then
      mv "/backups/postgres/${'$'}database.dump.tmp" "/backups/postgres/${'$'}database.dump"
      echo "backup-dump: dumped ${'$'}database"
    else
      rm -f "/backups/postgres/${'$'}database.dump.tmp"
      echo "backup-dump: FAILED to dump ${'$'}database" >&2
      status=1
    fi
  done
  if pg_dumpall --globals-only --file=/backups/postgres/globals.sql.tmp; then
    mv /backups/postgres/globals.sql.tmp /backups/postgres/globals.sql
  else
    rm -f /backups/postgres/globals.sql.tmp
    echo "backup-dump: FAILED to dump roles" >&2
    status=1
  fi
  return "${'$'}status"
}

# Seconds until the next UTC time ${'$'}1:${'$'}2.
seconds_until() {
  now=${'$'}(date -u +%s)
  target=${'$'}(date -u -d "${'$'}(date -u +%Y-%m-%d) ${'$'}1:${'$'}2:00" +%s)
  if [ "${'$'}target" -le "${'$'}now" ]; then target=${'$'}((target + 86400)); fi
  echo ${'$'}((target - now))
}

if [ "${'$'}{1:-}" = once ]; then
  dump_all
  exit ${'$'}?
fi
while true; do
  sleep "${'$'}(seconds_until "${'$'}BACKUP_HOUR" 00)"
  dump_all || echo "backup-dump: nightly dump incomplete" >&2
done
"""

/** Nightly restic snapshot and retention of the dumps and other stateful data; see [backupServices]. */
internal val BACKUP_SNAPSHOT_SCRIPT = """#!/bin/sh
# Nightly restic snapshot of everything mounted under /data, then retention pruning. Records the outcome
# in /state (last-success, last-failure) for `bosca swarm status`. A missing or day-old success runs a
# snapshot immediately at start-up so a restarted service catches up.
# Usage: backup.sh [once]. Environment: RESTIC_REPOSITORY, RESTIC_PASSWORD, AWS_ACCESS_KEY_ID,
# AWS_SECRET_ACCESS_KEY, BACKUP_HOUR, KEEP_DAILY, KEEP_WEEKLY, KEEP_MONTHLY.
set -u

snapshot() {
  if ! restic cat config >/dev/null 2>&1; then
    restic init || return 1
  fi
  # A fixed host name keeps every snapshot in one retention series across container restarts.
  restic backup --host bosca-swarm --tag bosca --exclude '*.tmp' /data || return 1
  restic forget --host bosca-swarm --tag bosca --keep-daily "${'$'}KEEP_DAILY" --keep-weekly "${'$'}KEEP_WEEKLY" \
    --keep-monthly "${'$'}KEEP_MONTHLY" --prune || return 1
}

run() {
  if snapshot; then
    date -u +%Y-%m-%dT%H:%M:%SZ > /state/last-success
    rm -f /state/last-failure
    echo "backup: snapshot complete"
  else
    date -u +%Y-%m-%dT%H:%M:%SZ > /state/last-failure
    echo "backup: snapshot FAILED" >&2
    return 1
  fi
}

seconds_until() {
  now=${'$'}(date -u +%s)
  target=${'$'}(date -u -d "${'$'}(date -u +%Y-%m-%d) ${'$'}1:${'$'}2:00" +%s)
  if [ "${'$'}target" -le "${'$'}now" ]; then target=${'$'}((target + 86400)); fi
  echo ${'$'}((target - now))
}

if [ "${'$'}{1:-}" = once ]; then
  run
  exit ${'$'}?
fi
last=0
if [ -s /state/last-success ]; then
  last=${'$'}(date -u -d "${'$'}(sed 's/T/ /; s/Z//' /state/last-success)" +%s 2>/dev/null || echo 0)
fi
if [ ${'$'}(( ${'$'}(date -u +%s) - last )) -gt 86400 ]; then
  run || true
fi
while true; do
  sleep "${'$'}(seconds_until "${'$'}BACKUP_HOUR" 30)"
  run || true
done
"""
