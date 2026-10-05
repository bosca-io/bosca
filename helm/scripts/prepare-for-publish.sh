#!/usr/bin/env bash
set -euo pipefail

HELM_REPO_URL="${HELM_REPO_URL:-}"
if [[ -z "$HELM_REPO_URL" && -t 0 ]]; then
  read -rp "Helm repository URL (for example https://artifacts.example.com/helm/bosca-helm): " HELM_REPO_URL
fi
[[ -n "$HELM_REPO_URL" ]] || { echo "Set HELM_REPO_URL to the Helm repository URL" >&2; exit 1; }

for chart_yaml in */Chart.yaml; do
  sed -i "s|repository: \"file://[^\"]*\"|repository: \"${HELM_REPO_URL}\"|g" "$chart_yaml"
done

echo "Rewrote all Chart.yaml repository references to: ${HELM_REPO_URL}"
