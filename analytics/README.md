# bosca-analytics

Analytics collection, processing, and AI-powered analysis for the Bosca platform. Includes a collector server that ingests analytics events, a processor that transforms and stores them, an AI analysis layer, and shared models and server client libraries. Both the collector and processor are standalone applications with GraalVM native image support.

## Modules

| Module | Description |
|---|---|
| `core-analytics` | Contracts: analytics interfaces, configuration, models |
| `analytics` | Implementation: analytics services and configuration |
| `analytics-ai` | AI-powered analytics analysis |
| `analytics-models` | Shared analytics data models |
| `analytics-server-client` | Client library for analytics server API |
| `analytics-collector` | Standalone collector server (ingests events) |
| `analytics-processor` | Standalone processor server (transforms/stores events) |

## Prerequisites

- JDK 25
- Docker (for local infrastructure)

## Build

Run these commands from the **workspace root**. `analytics` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :analytics:test  # Test analytics modules
./gradlew :analytics:analytics-collector:run  # Run the collector
./gradlew :analytics:analytics-processor:run  # Run the processor
./gradlew :analytics:analytics-collector:nativeCompile  # Build a native image
```

## Local Infrastructure

From the workspace root:

```bash
cd analytics
docker compose up -d    # Start services
docker compose down     # Stop services
```

| Service | Port | Notes |
|---|---|---|
| PostgreSQL | 5433 | Database |
| Dragonfly (Redis) | 6380 | Cache |
| NATS | 4222 | Messaging |

## Architecture

The project separates contracts (`core-analytics`) from implementations (`analytics`). Two standalone servers handle the event pipeline: the collector ingests events and the processor transforms and stores them. Both servers support GraalVM native image compilation and fat JAR packaging via the Shadow plugin. The `analytics-ai` module adds AI-powered analysis on top of the core analytics contracts. `analytics-server-client` provides a client library for other services to interact with the analytics API.

## Dependencies

- `io.bosca:core` -- Bosca Core (platform framework)
- `io.bosca:core-security` -- Bosca Core Security (authentication)
- `io.bosca:core-graalvm` -- Bosca Core GraalVM (native image support)

## Use in this workspace

Depend on the root project path for the modules you need. For example:

```kotlin
dependencies {
    implementation(project(":analytics:core-analytics"))
    implementation(project(":analytics:analytics-server-client"))
}
```

Published `io.bosca:*` coordinates used by existing catalogs also resolve to these local projects in the root build.
