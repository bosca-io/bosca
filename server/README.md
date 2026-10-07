# bosca-server

Main server assembly and background job runner for the Bosca platform. This is the composition root that wires all domain verticals (content, social, search, AI, workops, experimentation, and more) into a runnable GraphQL server and background runner, with GraalVM native-image support.

## Modules

| Module | Description |
|---|---|
| `bosca-server` | Primary server application -- GraphQL API via Bosca Server (Netty), DI wiring, schema registration |
| `bosca-runner` | Background job runner application -- processes async jobs using shared domain libraries |
| `messages-pages` | Message page rendering (JTE templates) |

## Prerequisites

- Java 25 (resolved via Gradle toolchains)
- Docker (for local infrastructure)

## Build

Run these commands from the **workspace root**. `server` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :server:test  # Test server modules
./gradlew :server:bosca-server:run  # Run the GraphQL API
./gradlew :server:bosca-runner:run  # Run background jobs
./gradlew -Pbosca.scripting.engine=false :server:bosca-server:nativeCompile  # Build a native image
```

Native server builds exclude the local Kotlin scripting engine and delegate script execution to
the JVM runner. `scripts/release/build-image.sh bosca-server <version>` selects this variant
automatically. JVM server builds retain local scripting by default.

## Local Infrastructure

From the workspace root:

```bash
cd server
docker compose up -d    # Start all services
docker compose down     # Stop all services
```

| Service | Port | Credentials |
|---|---|---|
| PostgreSQL 18 | 5433 | user: `bosca`, password: `bosca`, db: `bosca` |
| Dragonfly (Redis-compatible) | 6380 | -- |
| NATS | 4222 | -- |

## Architecture

The server itself contains minimal domain logic -- it is a composition root that wires domain modules together via KSP-generated DI providers. Two applications share the same domain libraries: `bosca-server` serves the GraphQL API (queries, mutations, subscriptions via Bosca Server), and `bosca-runner` processes background jobs. GraalVM native-image is supported for production deployments with fast startup and low memory footprint. Fat JAR packaging uses Shadow with ZIP64 support.

## Dependencies

- All `bosca-*` domain libraries (content, social, search, AI, workops, etc.)
- `io.bosca:sharedqueue` -- from bosca-sharedqueue
- `io.bosca:core` from `bosca-core`, and `io.bosca:di` / `io.bosca:service` from `services-di`
- Integration libraries (HubSpot, Meilisearch, Mux)
