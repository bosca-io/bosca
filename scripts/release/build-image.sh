#!/usr/bin/env bash
# Builds one Bosca image from this workspace and pushes it to the image registry.
#
# Usage:
#   scripts/release/build-image.sh <image> <version>
#
# Missing arguments are prompted for on a terminal. Log Docker into the registry
# before pushing.
#
# Environment:
#   BOSCA_IMAGE_REGISTRY  Registry and namespace images are pushed to. Defaults to
#                         <BOSCA_REGISTRY_URL host>/bosca when configured.
#                         Local builds without a registry use the bosca namespace.
#   PUSH                  Set to "false" to build the image without pushing it.
#   BOSCA_REGISTRY_URL,   Bosca Artifacts server and token. bml-message-server bundles
#   BOSCA_REGISTRY_TOKEN  the latest bosca-messages project from it, and
#                         notifications-web/profiles-web install @bosca npm packages
#                         from its /npm registry. CI's setup-registry step exports both.
#
# Native images are built for the machine running this script and published as
# linux/amd64, so they must be built on a Linux x86_64 host.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

IMAGES=(
  bosca-server bosca-runner analytics-collector artifacts-server git-server
  kubernetes-controller bml-message-server notifications-web profiles-web
  bosca-cli bosca-studio bosca-io imageprocessor bosca-gateway
  recommendation-trainer recommendation-model-loader
)

prompt() {
  local name="$1" message="$2" value="${!1:-}"
  if [[ -z "$value" && -t 0 ]]; then
    read -rp "$message: " value
  fi
  [[ -n "$value" ]] || { echo "Missing $name: $message" >&2; exit 1; }
  printf -v "$name" '%s' "$value"
}

IMAGE="${1:-}"
VERSION="${2:-}"
prompt IMAGE "Image to build (${IMAGES[*]})"
prompt VERSION "Version to publish (for example 6.31.0)"
[[ " ${IMAGES[*]} " == *" $IMAGE "* ]] || { echo "Unknown image: $IMAGE (expected one of: ${IMAGES[*]})" >&2; exit 1; }

if [[ -n "${BOSCA_REGISTRY_URL:-}" ]]; then
  registry_host="${BOSCA_REGISTRY_URL#http://}"
  registry_host="${registry_host#https://}"
  registry_host="${registry_host%/}"
  # Match setup-registry's Docker host when the agent's API endpoint is loopback.
  if [[ "$registry_host" == 127.0.0.1* ]]; then
    registry_host="host.docker.internal${registry_host#127.0.0.1}"
  fi
  REGISTRY="${BOSCA_IMAGE_REGISTRY:-$registry_host/bosca}"
else
  if [[ "${PUSH:-true}" != false && -z "${BOSCA_IMAGE_REGISTRY:-}" ]]; then
    echo "Set BOSCA_REGISTRY_URL or BOSCA_IMAGE_REGISTRY before publishing an image." >&2
    exit 1
  fi
  REGISTRY="${BOSCA_IMAGE_REGISTRY:-bosca}"
fi
REGISTRY="${REGISTRY%/}"
TAG="$REGISTRY/$IMAGE:$VERSION"
# Staging directory name the Dockerfiles read through the ARTIFACT_SHA build argument.
STAGE="release-$VERSION"
export RELEASE_VERSION="$VERSION"

gradle() {
  ./gradlew --no-daemon "$@"
}

require_linux_x86_64() {
  if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
    echo "$IMAGE is a linux/amd64 native image; build it on a Linux x86_64 host." >&2
    exit 1
  fi
}

# Builds <project>:nativeCompile and copies <binary> to <context>/artifacts/$STAGE/<staged>/.
stage_native() {
  local project="$1" project_dir="$2" binary="$3" context="$4" staged="$5"
  require_linux_x86_64
  gradle --no-configuration-cache "$project:nativeCompile"
  local destination="$context/artifacts/$STAGE/$staged"
  rm -rf "$destination"
  mkdir -p "$destination"
  cp "$project_dir/build/native/nativeCompile/$binary" "$destination/"
}

# Points the checked-in .npmrc of the BML sites at the Artifacts server's npm registry.
use_bosca_npm_registry() {
  : "${BOSCA_REGISTRY_URL:?Set BOSCA_REGISTRY_URL to the Artifacts server that serves @bosca npm packages}"
  local host="${BOSCA_REGISTRY_URL#http://}"
  host="${host#https://}"
  export BOSCA_NPM_REGISTRY="${BOSCA_NPM_REGISTRY:-${host%/}/npm}"
}

build_web_packages() {
  (cd web && pnpm install --frozen-lockfile && pnpm --filter './packages/*' build)
}

docker_build() {
  local context="$1" dockerfile="$2"
  shift 2
  DOCKER_BUILDKIT=1 docker build --platform linux/amd64 -f "$dockerfile" -t "$TAG" "$@" "$context"
}

case "$IMAGE" in
  bosca-server)
    stage_native :server:bosca-server server/bosca-server bosca-server server bosca-server-native
    docker_build server server/bosca-server/Dockerfile.graalvm --build-arg "ARTIFACT_SHA=$STAGE"
    ;;
  bosca-runner)
    gradle :server:bosca-runner:installDist
    rm -rf "server/artifacts/$STAGE/bosca-runner"
    mkdir -p "server/artifacts/$STAGE/bosca-runner"
    cp -R server/bosca-runner/build/install/bosca-runner/. "server/artifacts/$STAGE/bosca-runner/"
    docker_build server server/bosca-runner/Dockerfile --build-arg "ARTIFACT_SHA=$STAGE"
    ;;
  analytics-collector)
    stage_native :analytics:analytics-collector analytics/analytics-collector analytics-collector analytics analytics-collector-native
    docker_build analytics analytics/analytics-collector/Dockerfile.graalvm --build-arg "ARTIFACT_SHA=$STAGE"
    ;;
  artifacts-server)
    stage_native :artifacts:artifacts-server artifacts/artifacts-server artifacts-server artifacts artifacts-server-native
    docker_build artifacts artifacts/artifacts-server/Dockerfile.graalvm --build-arg "ARTIFACT_SHA=$STAGE"
    ;;
  git-server)
    stage_native :git:git-server git/git-server bosca-git-server git git-server-native
    docker_build git git/git-server/Dockerfile --build-arg "ARTIFACT_SHA=$STAGE"
    ;;
  kubernetes-controller)
    stage_native :kubernetes:kubernetes-controller kubernetes/kubernetes-controller bosca-kubernetes-controller kubernetes kubernetes-controller-native
    docker_build kubernetes kubernetes/kubernetes-controller/Dockerfile --build-arg "ARTIFACT_SHA=$STAGE"
    ;;
  bml-message-server)
    : "${BOSCA_REGISTRY_URL:?Set BOSCA_REGISTRY_URL to the Artifacts server that hosts bosca-messages}"
    stage_native :bml:bml-message-server bml/bml-message-server bml-message-server bml bml-message-server-native
    gradle :bml:bml-message-server:packageDefaultMessageProject
    docker_build bml bml/bml-message-server/Dockerfile.graalvm --build-arg "ARTIFACT_SHA=$STAGE"
    ;;
  notifications-web | profiles-web)
    require_linux_x86_64
    use_bosca_npm_registry
    (cd bml/bml-runtime && npm ci --no-audit --no-fund)
    gradle --no-configuration-cache ":$IMAGE:nativeCompile" ":$IMAGE:bmlBundleClient"
    docker_build "apps/$IMAGE" "apps/$IMAGE/Dockerfile"
    ;;
  bosca-cli)
    stage_native :cli cli bosca cli bosca-cli-native
    docker_build cli cli/Dockerfile --build-arg "ARTIFACT_SHA=$STAGE"
    ;;
  bosca-studio)
    build_web_packages
    (cd web && pnpm --filter @bosca/studio build)
    docker_build web web/projects/studio/Dockerfile
    ;;
  bosca-io)
    build_web_packages
    (cd web && pnpm --filter @bosca/bosca-io build)
    docker_build web web/projects/bosca.io/Dockerfile
    ;;
  imageprocessor)
    (cd web && pnpm install --frozen-lockfile)
    rm -rf web/projects/imageprocessor/lib
    (cd web && pnpm --filter @bosca/imageprocessor build)
    output="$(mktemp -d)"
    trap 'rm -rf "$output"' EXIT
    (cd web && pnpm --filter @bosca/imageprocessor deploy --legacy --prod "$output/app")
    # The package's .dockerignore excludes lib/ and node_modules/, which the deploy output needs.
    rm -f "$output/app/.dockerignore"
    docker_build "$output/app" web/projects/imageprocessor/Dockerfile
    ;;
  bosca-gateway)
    docker_build gateway/proxy gateway/proxy/Dockerfile
    ;;
  recommendation-trainer | recommendation-model-loader)
    docker_build "experimentation/ml/$IMAGE" "experimentation/ml/$IMAGE/Dockerfile"
    ;;
esac

if [[ "${PUSH:-true}" != false ]]; then
  docker push "$TAG"
fi
echo "Built $TAG"
