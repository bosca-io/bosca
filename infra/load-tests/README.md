# Load Tests

[k6](https://k6.io/) load test scripts for the Bosca server.

## Prerequisites

Install k6: `brew install k6` (macOS) or see [k6 installation docs](https://grafana.com/docs/k6/latest/set-up/install-k6/).

## Usage

From the workspace root:

```bash
cd infra/load-tests
./run.sh <test> [k6-options...]
```

### Available Tests

| Test | Description |
|------|-------------|
| `http-throughput` | HTTP layer throughput (ready, server now, health) |
| `graphql` | GraphQL queries and mutations |
| `auth` | Authentication lifecycle |
| `content-operations` | Content CRUD (collections, metadata) |
| `file-upload` | Large file uploads |
| `flag-experiment` | Feature flag bucketing and experiment flows |
| `all` | Run all tests sequentially |
| `smoke` | Quick 1-VU run of each test (GraalVM native image validation) |

### Examples

```bash
./run.sh http-throughput
./run.sh graphql --vus 50 --duration 30s
./run.sh smoke
BASE_URL=http://prod:8080 ./run.sh all
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `BASE_URL` | `http://localhost:8080` | Server URL |
| `TEST_USER` | `admin` | Login email |
| `TEST_PASS` | `password` | Login password |
