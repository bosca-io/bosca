#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"

export BASE_URL

usage() {
  echo "Usage: $0 <test> [k6-options...]"
  echo ""
  echo "Tests:"
  echo "  http-throughput      HTTP layer throughput (ready, server now, health)"
  echo "  graphql              GraphQL queries and mutations"
  echo "  auth                 Authentication lifecycle"
  echo "  content-operations   Content CRUD (collections, metadata)"
  echo "  file-upload          Large file uploads"
  echo "  flag-experiment      Feature flag bucketing + A/B experiment e2e"
  echo "  all                  Run all tests sequentially"
  echo "  smoke                Quick 1-VU run of each test (GraalVM validation)"
  echo ""
  echo "Options:"
  echo "  Any additional arguments are passed directly to k6."
  echo ""
  echo "Environment:"
  echo "  BASE_URL    Server URL (default: http://localhost:8080)"
  echo "  TEST_USER   Login email (default: admin)"
  echo "  TEST_PASS   Login password (default: password)"
  echo ""
  echo "Examples:"
  echo "  $0 http-throughput"
  echo "  $0 graphql --vus 50 --duration 30s"
  echo "  $0 smoke"
  echo "  BASE_URL=http://prod:8080 $0 all"
}

run_test() {
  local test_name="$1"
  shift
  echo "=== Running ${test_name} ==="
  k6 run "$@" "${SCRIPT_DIR}/${test_name}.js"
  echo ""
}

run_smoke() {
  local test_name="$1"
  echo "=== Smoke: ${test_name} ==="
  k6 run -e SMOKE=true "${SCRIPT_DIR}/${test_name}.js"
  echo ""
}

if [ $# -lt 1 ]; then
  usage
  exit 1
fi

TEST="$1"
shift

case "$TEST" in
  http-throughput|graphql|auth-flow|content-operations|file-upload|flag-experiment)
    run_test "$TEST" "$@"
    ;;
  auth)
    run_test "auth-flow" "$@"
    ;;
  all)
    run_test "http-throughput" "$@"
    run_test "graphql" "$@"
    run_test "auth-flow" "$@"
    run_test "content-operations" "$@"
    run_test "file-upload" "$@"
    ;;
  smoke)
    run_smoke "http-throughput"
    run_smoke "graphql"
    run_smoke "auth-flow"
    run_smoke "content-operations"
    run_smoke "file-upload"
    echo "=== Smoke tests complete ==="
    ;;
  help|--help|-h)
    usage
    ;;
  *)
    echo "Unknown test: $TEST"
    usage
    exit 1
    ;;
esac
