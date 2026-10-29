# bosca-core

Backend infrastructure foundation for the Bosca platform. Provides HTTP/GraphQL server framework, database access, caching, security, storage, scheduling, forms, configuration, and device management. All other Bosca backend modules depend on the contracts defined here.

## Modules

| Module | Description |
|--------|-------------|
| `core` | Bosca Server (Netty), PostgreSQL, Redis/NATS, Caffeine cache, OpenTelemetry |
| `core-annotations` | `@TypeController`, `@Repository`, `@JobDefinition`, `@RouteController` |
| `core-ksp` | KSP processor that generates GraphQL dispatchers and repository impls |
| `core-security` | Security interfaces: authentication, authorization, permissions |
| `security` | Security implementation: JWT, WebAuthn, password hashing |
| `core-storage` | Storage interfaces: file/object storage abstraction |
| `storage` | Storage implementation: S3, GCS |
| `core-scheduler` | Scheduler interfaces: job scheduling |
| `scheduler` | Scheduler implementation: NATS-backed job execution |
| `core-events` | Event contracts |
| `events` | Event implementation |
| `core-forms` | Forms interfaces: form definitions and submissions |
| `forms` | Forms implementation |
| `core-configuration` | Configuration interfaces |
| `configuration` | Configuration implementation |
| `core-devices` | Device management interfaces: push notifications |
| `devices` | Device management implementation |
| `core-graalvm` | GraalVM native-image annotations |
| `test-support` | Shared test utilities |

## Prerequisites

- Java 25
- Docker (for tests via TestContainers)

## Build

Run these commands from the **workspace root**. `bosca-core` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :bosca-core:test  # Test Bosca Core modules
./gradlew :bosca-core:core:test :bosca-core:security:test  # Focused tests
```

## Local Infrastructure

From the workspace root:

```bash
cd bosca-core
docker compose up -d               # Start all services
docker compose down                # Stop all services
```

| Service | Port | Credentials |
|---------|------|-------------|
| PostgreSQL 18.1 | 5433 | bosca/bosca |
| Dragonfly (Redis) | 6380 | -- |
| NATS | 4222 | -- |

## Architecture

The project follows a core/implementation split where `core-*` modules define contracts (interfaces, models, annotations). Other workspace projects depend on these contracts through the root Gradle build. KSP code generation processes annotations in `core-annotations/` to generate GraphQL controllers, repository implementations, job executors, and route handlers. Database access uses the `@Repository` pattern with `@Query`-annotated methods. Each module manages its own Flyway migrations. DI uses the `di` and `service` projects in `services-di` with KSP-generated wiring.

`ObjectStorageServiceFactory` creates independently owned stores through the new
`ManagedObjectStorageService` contract. It adds inventory metadata and listing to
Bosca's existing storage operations. Filesystem and S3 adapters delegate reads and
writes to the existing implementations. The existing `ObjectStorageService`
contract and its implementations are unchanged.

Callers of `create` close the returned store; `withStorage` closes it after success,
failure or cancellation. `ContentConfiguration` passes application storage settings
to the injected factory and closes its shared store at shutdown. Bucket creation is
optional. Backup stores use filesystem or S3-compatible storage.

`getObjectInfo` observes length and available provider metadata without reading the
body. `listObjects(prefix, offset, limit)` returns ordered keys and metadata using
existing offset pagination. These observations detect changes; they do not pin
provider versions or make a listing atomic. Failures and cancellation propagate.

## Dependencies

- `io.bosca:di`, `io.bosca:service`, `io.bosca:di-ksp`, `io.bosca:service-ksp` -- from `services-di`

## Use in this workspace

Depend on the root project path for the modules you need. For example:

```kotlin
dependencies {
    implementation(project(":bosca-core:core"))
    ksp(project(":bosca-core:core-ksp"))
}
```

Published `io.bosca:*` coordinates used by existing catalogs also resolve to these local projects in the root build.
