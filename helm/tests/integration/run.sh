#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════════
# Bosca Helm Integration Test
#
# Prerequisites:
#   - BOSCA_IMAGE_REGISTRY to test images from a registry other than
#     ghcr.io/bosca-io/bosca (log Docker into it first when it is private)
#   - crane installed (brew install crane)
#   - kind, helm, kubectl, kubeconform
#
# Deploys bosca-infra + bosca-services into a kind cluster with a
# local registry sidecar. Uses crane to copy images cross-platform.
#
# Usage:
#   helm/tests/integration/run.sh
#   KEEP_CLUSTER=true helm/tests/integration/run.sh
# ═══════════════════════════════════════════════════════════════════

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$REPO_ROOT"

CLUSTER_NAME="${CLUSTER_NAME:-bosca-test}"
NAMESPACE="${NAMESPACE:-bosca}"
TIMEOUT="${TIMEOUT:-600s}"
# Registry (and path) the tested Bosca images are copied from.
REGISTRY="${BOSCA_IMAGE_REGISTRY:-ghcr.io/bosca-io/bosca}"
REGISTRY="${REGISTRY%/}"
LOCAL_REGISTRY_HOST="localhost:5111"
LOCAL_REGISTRY_KIND="kind-registry:5000"
LOCAL_REGISTRY_NAME="kind-registry"

# Per-image versions. Bosca services version independently, so each image
# has its own override. Defaults are the latest tagged release at the time
# of script update — bump these (or pass env vars) to test newer images.
BOSCA_SERVER_VERSION="${BOSCA_SERVER_VERSION:-5.2.6}"
BOSCA_RUNNER_VERSION="${BOSCA_RUNNER_VERSION:-5.3.0}"
BOSCA_STUDIO_VERSION="${BOSCA_STUDIO_VERSION:-5.3.1}"
BOSCA_K8S_CONTROLLER_VERSION="${BOSCA_K8S_CONTROLLER_VERSION:-5.3.1}"
BOSCA_COLLECTOR_VERSION="${BOSCA_COLLECTOR_VERSION:-5.3.1}"
BOSCA_GIT_SERVER_VERSION="${BOSCA_GIT_SERVER_VERSION:-5.1.26}"
FAILED=0

pass() { echo "  PASS: $1"; }
fail() { echo "  FAIL: $1"; FAILED=$((FAILED + 1)); }

cleanup() {
  if [ "${KEEP_CLUSTER:-}" != "true" ]; then
    echo ""
    echo "Cleaning up..."
    kind delete cluster --name "$CLUSTER_NAME" 2>/dev/null || true
    docker rm -f "$LOCAL_REGISTRY_NAME" 2>/dev/null || true
  else
    echo ""
    echo "Cluster preserved: kubectl --context kind-$CLUSTER_NAME -n $NAMESPACE"
  fi
}
trap cleanup EXIT

echo "=== Bosca Helm Integration Test ==="
echo ""

# ── Step 1: Pre-flight ───────────────────────────────────────────
echo "[1/8] Pre-flight checks..."

for cmd in helm kubectl kind docker crane kubeconform; do
  command -v "$cmd" &>/dev/null && pass "$cmd" || { fail "$cmd not found"; }
done
[ $FAILED -gt 0 ] && { echo "Missing tools. Aborting."; exit 1; }

# ── Step 2: Static validation ────────────────────────────────────
echo ""
echo "[2/8] Static validation..."

for chart in helm/bosca-common helm/bosca-server helm/bosca-runner helm/bosca-studio \
  helm/bosca-kubernetes-controller helm/bosca-artifacts-server helm/bosca-git-server \
  helm/bosca-collector helm/bosca-io \
  helm/bosca-gateway helm/bml-message-server helm/notifications-web helm/profiles-web \
  helm/text-embeddings-inference helm/bosca-infra helm/bosca-services; do
  helm dependency update "$chart" >/dev/null 2>&1 || true
  if helm lint "$chart" --set global.domain=test.local --set appConfig.domain=test.local \
    --set appConfig.databaseUrl=jdbc:postgresql://x:5432/x \
    --set appConfig.natsHost=nats --set appConfig.meilisearchHost=meilisearch \
    --set appConfig.redisHost=redis --set appConfig.publicUrl=https://messages.test.local >/dev/null 2>&1; then
    pass "lint $(basename $chart)"
  else
    fail "lint $(basename $chart)"
  fi
done

helm unittest helm/bosca-server helm/bosca-runner helm/bosca-studio \
  helm/bosca-kubernetes-controller helm/bosca-artifacts-server \
  helm/bosca-git-server helm/bosca-collector \
  helm/bosca-io helm/bosca-gateway helm/bml-message-server \
  helm/notifications-web helm/profiles-web helm/text-embeddings-inference \
  helm/bosca-services helm/bosca-infra >/dev/null 2>&1 \
  && pass "unit tests" || fail "unit tests"

helm/scripts/validate.sh >/dev/null 2>&1 && pass "kubeconform" || fail "kubeconform"

# Verify the umbrella renders ≤1 Secret (the bosca-gateway chart-internal one).
# Bosca's K8s Secrets are NOT helm-managed — they're applied via `kubectl apply
# -f secrets.yaml` before installs. Anything more here means a subchart's
# `secrets.X.create` is leaking back to true.
UMBRELLA_SECRETS=$(helm template umbrella-test helm/bosca-services \
  --set global.domain=test.local \
  --set bosca-gateway.enabled=true \
  --set bosca-artifacts-server.enabled=true \
  --set bosca-git-server.enabled=true 2>/dev/null \
  | grep -c "^kind: Secret$" || true)
if [ "$UMBRELLA_SECRETS" -le 1 ]; then
  pass "umbrella: only bosca-gateway internal Secret rendered ($UMBRELLA_SECRETS)"
else
  fail "umbrella: unexpected Secret rendering ($UMBRELLA_SECRETS, expected ≤1)"
fi

# ── Step 3: Template verification ────────────────────────────────
echo ""
echo "[3/8] Template verification..."

SERVER_YAML=$(helm template test helm/bosca-server \
  --set global.domain=test.local \
  --set appConfig.databaseUrl=jdbc:postgresql://postgres-bosca-rw:5432/bosca \
  -s templates/configmap-appconfig.yaml 2>&1)

for kw in 'nats://' '\$JWT_SECRET' '\$DATABASE_USER' 'core-migrations'; do
  echo "$SERVER_YAML" | grep -q "$kw" && pass "server config: $kw" || fail "server config missing: $kw"
done

for memGi in 1 4 8; do
  expected=$((memGi * 1024 / 4))
  actual=$(helm template t helm/bosca-infra --set global.domain=x --set postgres.memoryGi=$memGi \
    -s templates/postgres-cluster.yaml 2>&1 | grep shared_buffers | grep -o '[0-9]*MB' | head -1)
  [ "$actual" = "${expected}MB" ] && pass "pg ${memGi}Gi → ${expected}MB" || fail "pg ${memGi}Gi: got $actual"
done

# ── Step 4: Create kind cluster with local registry ──────────────
echo ""
echo "[4/8] Creating kind cluster with local registry..."

# Start local registry
docker rm -f "$LOCAL_REGISTRY_NAME" 2>/dev/null || true
docker run -d --restart=always -p 5111:5000 --name "$LOCAL_REGISTRY_NAME" registry:2 >/dev/null

# Create kind cluster configured to use it
kind delete cluster --name "$CLUSTER_NAME" 2>/dev/null || true
kind create cluster --name "$CLUSTER_NAME" --wait 120s

# Connect registry to kind network
docker network connect kind "$LOCAL_REGISTRY_NAME" 2>/dev/null || true

# Configure containerd to use the local registry (kind v0.20+)
REGISTRY_DIR="/etc/containerd/certs.d/${LOCAL_REGISTRY_KIND}"
HOSTS_TOML='[host."http://kind-registry:5000"]
  capabilities = ["pull", "resolve"]'
for node in $(kind get nodes --name "$CLUSTER_NAME"); do
  docker exec "$node" mkdir -p "$REGISTRY_DIR"
  echo "$HOSTS_TOML" | docker exec -i "$node" sh -c "cat > $REGISTRY_DIR/hosts.toml"
done

# Document the registry for kind tooling
kubectl apply -f - <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "${LOCAL_REGISTRY_HOST}"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
EOF

pass "kind cluster + local registry"

# ── Step 5: Copy images to local registry ────────────────────────
echo ""
echo "[5/8] Copying images to local registry..."

IMAGES_TO_COPY=(
  "$REGISTRY/bosca-server:$BOSCA_SERVER_VERSION|bosca-server:$BOSCA_SERVER_VERSION"
  "$REGISTRY/bosca-runner:$BOSCA_RUNNER_VERSION|bosca-runner:$BOSCA_RUNNER_VERSION"
  "$REGISTRY/bosca-studio:$BOSCA_STUDIO_VERSION|bosca-studio:$BOSCA_STUDIO_VERSION"
  "$REGISTRY/kubernetes-controller:$BOSCA_K8S_CONTROLLER_VERSION|kubernetes-controller:$BOSCA_K8S_CONTROLLER_VERSION"
  "$REGISTRY/analytics-collector:$BOSCA_COLLECTOR_VERSION|analytics-collector:$BOSCA_COLLECTOR_VERSION"
  "$REGISTRY/git-server:$BOSCA_GIT_SERVER_VERSION|git-server:$BOSCA_GIT_SERVER_VERSION"
)
for entry in "${IMAGES_TO_COPY[@]}"; do
  SRC="${entry%%|*}"
  DST="${entry##*|}"
  crane copy "$SRC" "$LOCAL_REGISTRY_HOST/$DST" --platform linux/amd64 2>/dev/null \
    && pass "$DST" || fail "copy $DST"
done

# ── Step 6: Deploy K8s Secrets + bosca-infra ────────────────────
echo ""
echo "[6/8] Deploying K8s Secrets + bosca-infra..."

helm repo add cnpg https://cloudnative-pg.github.io/charts 2>/dev/null || true
helm repo update cnpg >/dev/null 2>&1
helm upgrade --install cnpg cnpg/cloudnative-pg \
  --namespace cnpg-system --create-namespace --wait --timeout "$TIMEOUT" >/dev/null 2>&1 && pass "CNPG operator" || fail "CNPG operator"

kubectl apply -f https://raw.githubusercontent.com/dragonflydb/dragonfly-operator/main/manifests/dragonfly-operator.yaml >/dev/null 2>&1 || true
kubectl wait --for=condition=Available deployment/dragonfly-operator-controller-manager \
  -n dragonfly-operator-system --timeout=120s >/dev/null 2>&1 && pass "Dragonfly operator" || fail "Dragonfly operator"

kubectl create namespace "$NAMESPACE" 2>/dev/null || true

# K8s Secrets are NOT helm-managed (avoids plaintext in helm release storage).
# In production, operators apply a pre-rendered secrets.yaml. For this test we
# create dummy values inline. Must land BEFORE bosca-infra — CNPG Cluster
# references the pg-creds-* secrets when bootstrapping Postgres roles.
PG_TEST_PW="test-pw-$(openssl rand -hex 8)"
for role in bosca-server bosca-runner bosca-kubernetes-controller bosca-artifacts-server bosca-git-server bosca-warehouse; do
  kubectl create secret generic "pg-creds-${role}" -n "$NAMESPACE" \
    --from-literal=username="$role" --from-literal=password="$PG_TEST_PW" \
    --type=kubernetes.io/basic-auth \
    --dry-run=client -o yaml | kubectl label --local -f - cnpg.io/reload=true --dry-run=client -o yaml | kubectl apply -f - >/dev/null 2>&1 || true
done
# Application-shared secrets (random values; real prod values come from secrets.yaml)
for entry in \
  "jwt:secret" "nats:token" "cfg:secret" "url:secret" \
  "openai-token:token" "hubspot:token" "gemini-api-key:key" \
  "pipeline:secret_key" "initialization:admin_password"; do
  name="${entry%%:*}"; key="${entry##*:}"
  kubectl create secret generic "$name" -n "$NAMESPACE" \
    --from-literal="$key=$(openssl rand -hex 16)" --dry-run=client -o yaml \
    | kubectl apply -f - >/dev/null 2>&1
done
# Multi-key secrets
kubectl create secret generic s3 -n "$NAMESPACE" \
  --from-literal=ACCESS_KEY_ID=test --from-literal=ACCESS_SECRET_KEY=test \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null 2>&1
kubectl create secret generic scrypt-key -n "$NAMESPACE" \
  --from-literal=key=test --from-literal=salt=test \
  --from-literal=mem_cost=8 --from-literal=rounds=4 \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null 2>&1
kubectl create secret generic google -n "$NAMESPACE" \
  --from-literal=client_id=test --from-literal=client_secret=test \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null 2>&1
kubectl create secret generic google-account -n "$NAMESPACE" \
  --from-literal=account='{}' --dry-run=client -o yaml | kubectl apply -f - >/dev/null 2>&1
kubectl create secret generic facebook -n "$NAMESPACE" \
  --from-literal=app_id=test --from-literal=app_secret=test \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null 2>&1
kubectl create secret generic license -n "$NAMESPACE" \
  --from-literal=public_key=test --from-literal=internal_auth_token=test \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null 2>&1
kubectl create secret generic license-signing -n "$NAMESPACE" \
  --from-literal=signing_key=test --dry-run=client -o yaml | kubectl apply -f - >/dev/null 2>&1
pass "K8s Secrets (kubectl-applied, not helm-managed)"

helm upgrade --install bosca-infra helm/bosca-infra \
  --namespace "$NAMESPACE" \
  --set global.domain=test.bosca.local \
  --set postgres.instances=1 \
  --set postgres.storage.size=1Gi \
  --set postgres.memoryGi=1 \
  --set postgres.resources.requests.cpu=100m \
  --set postgres.resources.requests.memory=256Mi \
  --set postgres.resources.limits.cpu=500m \
  --set postgres.resources.limits.memory=512Mi \
  --set redis.resources.requests.cpu=100m \
  --set redis.resources.requests.memory=512Mi \
  --set redis.resources.limits.cpu=500m \
  --set redis.resources.limits.memory=512Mi \
  --set s3proxy.enabled=true \
  --set s3proxy.storageClass="" \
  --timeout "$TIMEOUT" >/dev/null 2>&1 && pass "bosca-infra" || fail "bosca-infra"

# ── Step 7: Wait for infra, deploy services ──────────────────────
echo ""
echo "[7/8] Infrastructure + services..."

check_ready() {
  local label="$1" desc="$2"
  kubectl wait --for=condition=Ready pod -l "$label" -n "$NAMESPACE" --timeout="$TIMEOUT" >/dev/null 2>&1 \
    && pass "$desc" || { fail "$desc"; kubectl get pods -l "$label" -n "$NAMESPACE" --no-headers 2>/dev/null; }
}

check_ready "app=localpv-provisioner" "OpenEBS"
check_ready "app=s3proxy" "S3Proxy"
check_ready "app.kubernetes.io/name=nats" "NATS"
check_ready "app.kubernetes.io/name=meilisearch" "Meilisearch"
check_ready "app=redis" "Redis"

kubectl wait --for=condition=Ready cluster/postgres-bosca -n "$NAMESPACE" --timeout="$TIMEOUT" >/dev/null 2>&1 \
  && pass "PostgreSQL" || { fail "PostgreSQL"; kubectl get pods -l cnpg.io/cluster=postgres-bosca -n "$NAMESPACE" --no-headers 2>/dev/null; }

# Deploy services using local registry images
DB_URL="jdbc:postgresql://postgres-bosca-rw.${NAMESPACE}.svc.cluster.local:5432/bosca"

helm upgrade --install bosca helm/bosca-services \
  --namespace "$NAMESPACE" \
  --set global.domain=test.bosca.local \
  --set bosca-server.enabled=true \
  --set bosca-server.replicaCount=1 \
  --set bosca-server.autoscaling.enabled=false \
  --set bosca-server.podDisruptionBudget.enabled=false \
  --set bosca-server.resources.requests.cpu=100m \
  --set bosca-server.resources.requests.memory=256Mi \
  --set bosca-server.resources.limits.cpu=2 \
  --set bosca-server.resources.limits.memory=1Gi \
  --set bosca-server.startupProbe.failureThreshold=60 \
  --set bosca-server.image.registry="$LOCAL_REGISTRY_KIND" \
  --set bosca-server.image.tag="$BOSCA_SERVER_VERSION" \
  --set bosca-server.image.pullPolicy=IfNotPresent \
  --set bosca-server.appConfig.databaseUrl="$DB_URL" \
  --set bosca-server.podSecurityContext=null \
  --set bosca-server.securityContext=null \
  --set bosca-studio.enabled=true \
  --set bosca-studio.replicaCount=1 \
  --set bosca-studio.autoscaling.enabled=false \
  --set bosca-studio.podDisruptionBudget.enabled=false \
  --set bosca-studio.image.registry="$LOCAL_REGISTRY_KIND" \
  --set bosca-studio.image.tag="$BOSCA_STUDIO_VERSION" \
  --set bosca-studio.image.pullPolicy=IfNotPresent \
  --set bosca-studio.podSecurityContext=null \
  --set bosca-studio.securityContext=null \
  --set bosca-runner.enabled=true \
  --set bosca-runner.workers.default.replicas=1 \
  --set bosca-runner.workers.default.resourceProfile=small \
  --set bosca-runner.image.registry="$LOCAL_REGISTRY_KIND" \
  --set bosca-runner.image.tag="$BOSCA_RUNNER_VERSION" \
  --set bosca-runner.image.pullPolicy=IfNotPresent \
  --set bosca-runner.appConfig.databaseUrl="$DB_URL" \
  --set bosca-kubernetes-controller.enabled=true \
  --set bosca-kubernetes-controller.replicaCount=1 \
  --set bosca-kubernetes-controller.image.registry="$LOCAL_REGISTRY_KIND" \
  --set bosca-kubernetes-controller.image.tag="$BOSCA_K8S_CONTROLLER_VERSION" \
  --set bosca-kubernetes-controller.image.pullPolicy=IfNotPresent \
  --set bosca-kubernetes-controller.appConfig.databaseUrl="$DB_URL" \
  --set bosca-kubernetes-controller.rbac.create=false \
  --set bosca-git-server.enabled=true \
  --set bosca-git-server.replicaCount=1 \
  --set bosca-git-server.image.registry="$LOCAL_REGISTRY_KIND" \
  --set bosca-git-server.image.tag="$BOSCA_GIT_SERVER_VERSION" \
  --set bosca-git-server.image.pullPolicy=IfNotPresent \
  --set bosca-git-server.appConfig.databaseUrl="$DB_URL" \
  --set bosca-collector.enabled=true \
  --set bosca-collector.image.registry="$LOCAL_REGISTRY_KIND" \
  --set bosca-collector.image.tag="$BOSCA_COLLECTOR_VERSION" \
  --set bosca-collector.image.pullPolicy=IfNotPresent \
  --set bosca-collector.appConfig.databaseUrl="$DB_URL" \
  --timeout "$TIMEOUT" >/dev/null 2>&1 && pass "bosca-services" || fail "bosca-services"

check_ready "app.kubernetes.io/name=bosca-collector" "bosca-collector"
check_ready "app.kubernetes.io/name=bosca-server" "bosca-server"
check_ready "app.kubernetes.io/name=bosca-studio" "bosca-studio"
check_ready "app.kubernetes.io/name=bosca-runner" "bosca-runner"
check_ready "app.kubernetes.io/name=bosca-kubernetes-controller" "bosca-kubernetes-controller"
check_ready "app.kubernetes.io/name=bosca-git-server" "bosca-git-server"

# ── Step 8: Health check ─────────────────────────────────────────
echo ""
echo "[8/8] Health checks..."

# Port-forward and curl (GraalVM native images don't have wget/curl)
kubectl port-forward -n "$NAMESPACE" svc/bosca-bosca-server 18080:8080 &>/dev/null &
PF_PID=$!
sleep 3
HEALTH=$(curl -sf http://localhost:18080/api/v1/health 2>/dev/null || echo "")
kill $PF_PID 2>/dev/null || true
wait $PF_PID 2>/dev/null || true
echo "$HEALTH" | grep -q '"ok":true' && pass "server /api/v1/health → ok" || fail "server health"

# ── Summary ──────────────────────────────────────────────────────
echo ""
kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null | awk '{printf "  %-55s %s\n", $1, $3}'
echo ""
[ $FAILED -eq 0 ] && echo "=== ALL TESTS PASSED ===" || echo "=== $FAILED TEST(S) FAILED ==="
exit $FAILED
