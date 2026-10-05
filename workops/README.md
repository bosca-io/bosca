# WorkOps

Kotlin/JVM projects for project management, task tracking, releases, and automation in the Bosca platform. They include a core domain model, services and repositories, background jobs, and store pipeline integration.

## Modules

| Module | Description |
|---|---|
| `core-workops` | Domain models, BQL query language, error types |
| `workops` | Service layer — controllers, services, repositories, migrations |
| `workops-jobs` | Background job processing |
| `store-pipelines` | Store pipeline integration |

## Prerequisites

- **JDK 25** (resolved via Gradle toolchains)
- **Docker** and **Docker Compose** (for local Postgres, NATS, Dragonfly)

## Build

Run these commands from the **workspace root**. `workops` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :workops:test  # Test WorkOps modules
./gradlew :workops:core-workops:test :workops:workops:test  # Focused tests
./gradlew :workops:workops:koverHtmlReport  # Coverage report
```

## Local Infrastructure

From the workspace root:

```bash
cd workops
docker compose up -d               # Start services
docker compose down                # Stop services
```

| Service | Port |
|---|---|
| PostgreSQL | 5433 |
| Dragonfly (Redis) | 6380 |
| NATS | 4222 |

## Architecture

`core-workops` contains domain models, including a custom query language (BQL) with its own tokenizer, parser, validator, and query planner for task filtering. The `workops` module adds controllers, services, repositories, and Flyway-managed database migrations. The `workops-jobs` module handles asynchronous background processing.

KSP code generation handles dependency injection and service wiring via Bosca DI, Service, and Core annotations. The core module is `:workops:core-workops` in the root Gradle build.

## Dependencies

- Bosca Platform libraries (DI, security, storage)
- Bosca Core (annotations, models)
