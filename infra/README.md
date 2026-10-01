# bosca-infra

Infrastructure configuration for the Bosca platform -- Docker Compose service definitions for local development, k6 load tests, and CI/build support tooling. This is not a Gradle project.

## Modules

| Module | Description |
|--------|-------------|
| `services/` | Docker Compose stack and service configs for local development |
| `load-tests/` | k6 load/stress test scripts (auth flows, GraphQL, HTTP throughput, feature flags) |
| `support/` | Java AOT cache tooling and the scripts host project |

> Recommendation trainer and model loader source lives under `experimentation/ml/`.
> This Compose stack references versioned images for those services.

## Prerequisites

- Docker and Docker Compose
- k6 (for load tests)

## Build

```bash
# Start local development infrastructure from the workspace root
(cd infra/services && docker compose up -d)

# Run load tests
(cd infra/load-tests && ./run.sh)
```

## Local Infrastructure

| Service | Port | Purpose |
|---------|------|---------|
| PostgreSQL (primary) | 5433 | Application database |
| PostgreSQL (warehouse) | 5434 | Analytics data warehouse |
| NATS | 4222 | Message broker |
| Meilisearch | 7701 | Full-text search |
| S3Proxy | 8000 | S3-compatible object storage |
| Trino | 8089 | Distributed SQL query engine |
| Dragonfly | 6380 | Redis-compatible cache |
| Jaeger | 16686 | Distributed tracing UI |
| Text Extractor | 8083 | Document text extraction |

## Architecture

All local dependencies run as Docker containers with health checks and named volumes on a shared `boscanet` network. Load testing uses k6 with JavaScript-based test scripts covering auth flows, GraphQL endpoints, file uploads, and feature flag evaluation. Trino provides federated analytics queries across PostgreSQL and S3/Iceberg data lakes. The recommendation TF Serving service runs here from the upstream `tensorflow/serving` image; the trainer and model loader that feed it are built from `experimentation/`.
