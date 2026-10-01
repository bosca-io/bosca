# bosca-artifacts

Artifact storage server for the Bosca platform, providing Docker registry, Maven repository, and npm registry capabilities in a unified service. The modular design separates protocol-specific handling from shared artifact storage infrastructure, with a standalone server application supporting GraalVM native image compilation.

## Modules

| Module | Description |
|---|---|
| `core-artifacts` | Contracts: artifact interfaces, digest utilities, models |
| `artifacts-base` | Shared artifact storage infrastructure and configuration |
| `artifacts-docker` | Docker registry protocol implementation |
| `artifacts-helm` | Helm artifact handling |
| `artifacts-maven` | Maven repository protocol implementation |
| `artifacts-npm` | npm registry protocol implementation |
| `artifacts-raw` | Raw artifact handling |
| `artifacts-ml` | Machine learning artifact handling |
| `artifacts-admin` | GraphQL admin interface for artifacts |
| `artifacts-server` | Standalone server application |

## Prerequisites

- JDK 25

## Build

Run these commands from the **workspace root**. `artifacts` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :artifacts:test  # Test artifact modules
./gradlew :artifacts:artifacts-server:run  # Run the artifact server
./gradlew :artifacts:artifacts-server:nativeCompile  # Build a native image
```

## Architecture

Each artifact protocol (Docker, Maven, npm) is isolated in its own module with protocol-specific logic. These protocol modules share common storage infrastructure provided by `artifacts-base`. The `core-artifacts` module defines only interfaces and models. The `artifacts-server` module composes everything into a standalone application with GraalVM native image support. The `artifacts-admin` module provides a separate GraphQL admin interface for managing artifacts.

## Dependencies

- `io.bosca:core` -- Bosca Core (platform framework)
- `io.bosca:core-security` -- Bosca Core Security (authentication)
- `io.bosca:core-storage` -- Bosca Core Storage (object storage abstraction)
- `io.bosca:core-graalvm` -- Bosca Core GraalVM (native image support)
- `io.bosca:analytics-server-client` -- Bosca Analytics (event reporting)
