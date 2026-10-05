#!/usr/bin/env bash
set -euo pipefail

# Validates rendered Helm templates against Kubernetes API schemas using kubeconform.
# Supports CRD schemas for CNPG, Dragonfly, and Gateway API.
#
# Usage: ./scripts/validate.sh [chart-dir ...]
# If no args, validates all charts.
#
# Self-locates so it works from any caller cwd: chart paths are resolved
# relative to the helm/ directory (the script's parent).
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.."

KUBERNETES_VERSION="${KUBERNETES_VERSION:-1.30.0}"
SCHEMA_LOCATION="${SCHEMA_LOCATION:-https://raw.githubusercontent.com/yannh/kubernetes-json-schema/master}"

# CRD schema sources. Keep schemas for Bosca-owned resources in-repository so
# validation does not depend on an external catalogue publishing them.
CRD_SCHEMAS=(
  "scripts/schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"
  "https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"
)

if ! command -v kubeconform &>/dev/null; then
  echo "ERROR: kubeconform not found. Install: https://github.com/yannh/kubeconform"
  exit 1
fi

CHARTS=("$@")
if [ ${#CHARTS[@]} -eq 0 ]; then
  CHARTS=(
    bosca-server
    bosca-runner
    bosca-studio
    bosca-kubernetes-controller
    bosca-artifacts-server
    bosca-git-server
    bosca-collector
    bosca-gateway
    bosca-io
    notifications-web
    profiles-web
    bml-message-server
    text-embeddings-inference
    bosca-infra
    bosca-services
  )
fi

SCHEMA_ARGS=""
for schema in "${CRD_SCHEMAS[@]}"; do
  SCHEMA_ARGS="${SCHEMA_ARGS} -schema-location ${schema}"
done

FAILED=0
for chart in "${CHARTS[@]}"; do
  name=$(basename "$chart")
  echo "=== Validating: $name ==="

  # Ensure dependencies are resolved (writes resolved .tgz into <chart>/charts/)
  helm dependency update "$chart"

  # Build common set flags for templating
  SET_FLAGS=(
    --set global.domain=validate.example.com
    --set appConfig.domain=validate.example.com
    --set appConfig.databaseUrl=jdbc:postgresql://postgres-bosca-rw:5432/bosca
    --set appConfig.natsHost=nats
    --set appConfig.meilisearchHost=meilisearch
    --set appConfig.redisHost=redis
    --set appConfig.publicUrl=https://messages.validate.example.com
  )

  # Template and validate (stderr passed through so failures are diagnosable)
  if helm template validate "$chart" "${SET_FLAGS[@]}" | \
    kubeconform \
      -kubernetes-version "$KUBERNETES_VERSION" \
      -schema-location "$SCHEMA_LOCATION/{{ .NormalizedKubernetesVersion }}-standalone{{ .StrictSuffix }}/{{ .ResourceKind }}{{ .KindSuffix }}.json" \
      $SCHEMA_ARGS \
      -strict \
      -summary \
      -skip CustomResourceDefinition,Cluster,Database,Pooler,Dragonfly,ScheduledBackup 2>&1; then
    echo "  PASS"
  else
    echo "  FAIL"
    FAILED=$((FAILED + 1))
  fi
  echo
done

if [ $FAILED -gt 0 ]; then
  echo "=== $FAILED chart(s) failed validation ==="
  exit 1
fi

echo "=== All charts validated successfully ==="
