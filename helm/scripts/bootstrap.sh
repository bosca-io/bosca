#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════════
# Bosca cluster bootstrap — installs cluster-scoped infrastructure
# (operators + storage provisioner) that bosca-infra depends on but
# cannot bundle (cluster-scoped CRDs, one-per-cluster ownership).
#
# Idempotent: safe to re-run. Existing installs are upgraded in place.
# Run once per cluster, not per release/namespace.
#
# Prerequisites:
#   - kubectl pointing at the target cluster
#   - helm 3.x
#
# Usage:
#   ./helm/scripts/bootstrap.sh
#
# Environment overrides:
#   CNPG_NAMESPACE          (default: cnpg-system)
#   CNPG_VERSION            (default: empty → latest stable from chart repo)
#   DRAGONFLY_NAMESPACE     (default: dragonfly-operator-system)
#   DRAGONFLY_MANIFEST_URL  (default: dragonflydb/dragonfly-operator main)
#   OPENEBS_NAMESPACE       (default: openebs)
#   OPENEBS_VERSION         (default: 4.4.0)
#   CERT_MANAGER_NAMESPACE  (default: cert-manager)
#   CERT_MANAGER_VERSION    (default: v1.16.2)
#   WAIT_TIMEOUT            (default: 300s)
# ═══════════════════════════════════════════════════════════════════

CNPG_NAMESPACE="${CNPG_NAMESPACE:-cnpg-system}"
CNPG_VERSION="${CNPG_VERSION:-}"
DRAGONFLY_NAMESPACE="${DRAGONFLY_NAMESPACE:-dragonfly-operator-system}"
DRAGONFLY_MANIFEST_URL="${DRAGONFLY_MANIFEST_URL:-https://raw.githubusercontent.com/dragonflydb/dragonfly-operator/main/manifests/dragonfly-operator.yaml}"
OPENEBS_NAMESPACE="${OPENEBS_NAMESPACE:-openebs}"
OPENEBS_VERSION="${OPENEBS_VERSION:-4.4.0}"
CERT_MANAGER_NAMESPACE="${CERT_MANAGER_NAMESPACE:-cert-manager}"
CERT_MANAGER_VERSION="${CERT_MANAGER_VERSION:-v1.20.2}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-300s}"

FAILED=0
pass() { echo "  PASS: $1"; }
fail() { echo "  FAIL: $1"; FAILED=$((FAILED + 1)); }

echo "=== Bosca cluster bootstrap ==="
echo "Target cluster: $(kubectl config current-context 2>/dev/null || echo '<unset>')"
echo ""

# ── Pre-flight ───────────────────────────────────────────────────
echo "[1/5] Pre-flight..."
for cmd in kubectl helm; do
  if command -v "$cmd" >/dev/null 2>&1; then
    pass "$cmd"
  else
    fail "$cmd not found"
  fi
done
if ! kubectl cluster-info >/dev/null 2>&1; then
  fail "kubectl can't reach the cluster"
fi
[ $FAILED -gt 0 ] && { echo "Pre-flight failed. Aborting."; exit 1; }

# ── CloudNativePG operator (helm chart) ──────────────────────────
echo ""
echo "[2/5] CloudNativePG operator (namespace: $CNPG_NAMESPACE)..."

helm repo add cnpg https://cloudnative-pg.github.io/charts >/dev/null 2>&1 || true
helm repo update cnpg >/dev/null 2>&1 && pass "cnpg repo refreshed" || fail "cnpg repo refresh"

if helm upgrade --install cnpg cnpg/cloudnative-pg \
  --namespace "$CNPG_NAMESPACE" --create-namespace \
  --wait --timeout "$WAIT_TIMEOUT" >/dev/null 2>&1; then
  pass "CNPG operator installed/upgraded"
else
  fail "CNPG operator install"
fi

if kubectl wait --for=condition=Available deployment \
  -l app.kubernetes.io/name=cloudnative-pg \
  -n "$CNPG_NAMESPACE" --timeout="$WAIT_TIMEOUT" >/dev/null 2>&1; then
  pass "CNPG operator deployment Available"
else
  fail "CNPG operator did not become Available"
fi

# ── Dragonfly operator (raw manifest, no helm chart upstream) ────
echo ""
echo "[3/5] Dragonfly operator (namespace: $DRAGONFLY_NAMESPACE)..."

# kubectl apply is idempotent. Sets server-side annotations on each resource;
# repeat applies just no-op against unchanged manifests.
if kubectl apply -f "$DRAGONFLY_MANIFEST_URL" >/dev/null 2>&1; then
  pass "Dragonfly manifests applied"
else
  fail "Dragonfly manifest apply"
fi

if kubectl wait --for=condition=Available \
  deployment/dragonfly-operator-controller-manager \
  -n "$DRAGONFLY_NAMESPACE" --timeout="$WAIT_TIMEOUT" >/dev/null 2>&1; then
  pass "Dragonfly operator Available"
else
  fail "Dragonfly operator did not become Available"
fi

# ── OpenEBS storage (helm chart) ─────────────────────────────────
# Bosca-infra StorageClasses default to `openebs-hostpath`. We disable
# the bundled volumesnapshot* CRDs (openebs-crds.csi.volumeSnapshots)
# because DOKS (and most managed Kubernetes distros) ship them already
# via the platform's CSI snapshotter. Hostpath storage doesn't need
# the CSI volume-snapshot subsystem anyway.
echo ""
echo "[4/5] OpenEBS storage (namespace: $OPENEBS_NAMESPACE)..."

helm repo add openebs https://openebs.github.io/openebs >/dev/null 2>&1 || true
helm repo update openebs >/dev/null 2>&1 && pass "openebs repo refreshed" || fail "openebs repo refresh"

if helm upgrade --install openebs openebs/openebs \
  --namespace "$OPENEBS_NAMESPACE" --create-namespace \
  --version "$OPENEBS_VERSION" \
  --set openebs-crds.csi.volumeSnapshots.enabled=false \
  --set engines.replicated.mayastor.enabled=false \
  --set engines.local.lvm.enabled=false \
  --set engines.local.zfs.enabled=false \
  --set zfs-localpv.enabled=false \
  --set lvm-localpv.enabled=false \
  --set rawfile-localpv.enabled=false \
  --set mayastor.enabled=false \
  --set loki.enabled=false \
  --set alloy.enabled=false \
  --wait --timeout "$WAIT_TIMEOUT" >/dev/null 2>&1; then
  pass "OpenEBS installed/upgraded"
else
  fail "OpenEBS install"
fi

if kubectl wait --for=condition=Available deployment \
  -l app=localpv-provisioner \
  -n "$OPENEBS_NAMESPACE" --timeout="$WAIT_TIMEOUT" >/dev/null 2>&1; then
  pass "OpenEBS localpv-provisioner Available"
else
  fail "OpenEBS localpv-provisioner did not become Available"
fi

# ── cert-manager (helm chart) ─────────────────────────────────────
# Installs the cert-manager operator and its CRDs cluster-wide. The
# DNS01-Cloudflare ClusterIssuer and its API-token Secret are NOT
# created here — they're rendered by the bosca-infra chart (Issuer is
# config; this script only installs operators).
echo ""
echo "[5/5] cert-manager (namespace: $CERT_MANAGER_NAMESPACE)..."

kubectl create namespace "$CERT_MANAGER_NAMESPACE" >/dev/null 2>&1 || true

helm repo add jetstack https://charts.jetstack.io >/dev/null 2>&1 || true
helm repo update jetstack >/dev/null 2>&1 && pass "jetstack repo refreshed" || fail "jetstack repo refresh"

# config.enableGatewayAPI=true wires up the cert-manager Gateway-API shim:
# it watches Gateway resources, reads the cert-manager.io/cluster-issuer
# annotation, and auto-creates a Certificate for each listener's
# tls.certificateRefs target. Stable in cert-manager v1.20+.
#
# webhook.timeoutSeconds=29 keeps both the validating and mutating webhook
# configurations under the 30s ceiling that DOKS/GKE upgrade pre-checks
# enforce — chart default of 30 trips "block upgrade" insights.
if helm upgrade --install cert-manager jetstack/cert-manager \
  --namespace "$CERT_MANAGER_NAMESPACE" \
  --version "$CERT_MANAGER_VERSION" \
  --set crds.enabled=true \
  --set config.enableGatewayAPI=true \
  --set webhook.timeoutSeconds=29 \
  --wait --timeout "$WAIT_TIMEOUT" >/dev/null 2>&1; then
  pass "cert-manager installed/upgraded"
else
  fail "cert-manager install"
fi

if kubectl wait --for=condition=Available deployment \
  -l app.kubernetes.io/instance=cert-manager \
  -n "$CERT_MANAGER_NAMESPACE" --timeout="$WAIT_TIMEOUT" >/dev/null 2>&1; then
  pass "cert-manager deployments Available"
else
  fail "cert-manager did not become Available"
fi

# ── Summary ──────────────────────────────────────────────────────
echo ""
if [ $FAILED -eq 0 ]; then
  echo "=== Bootstrap complete ==="
  echo ""
  echo "Next steps:"
  echo "  kubectl create namespace bosca"
  echo "  kubectl apply -f /path/to/secrets.yaml"
  echo "  helm install bosca-infra helm/bosca-infra -n bosca -f /path/to/infra-values.yaml"
  echo "  helm install bosca       helm/bosca-services -n bosca -f /path/to/values.yaml"
  echo ""
  echo "Note: secrets.yaml contains entries for both the bosca and"
  echo "      $CERT_MANAGER_NAMESPACE namespaces — explicit metadata.namespace"
  echo "      on each Secret routes them correctly without the -n flag."
else
  echo "=== $FAILED step(s) failed — see output above ==="
  exit 1
fi
