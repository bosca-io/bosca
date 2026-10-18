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

`core-storage` contains `StorageConfiguration` and the `ObjectStorageServiceFactory` contract. The factory creates independent filesystem, S3-compatible or no-op stores. Callers of `create` own and close their stores; `withStorage` closes them after work, failure or cancellation. Stores and open streams stay within the callback. `ContentConfiguration` passes the application storage settings to the injected factory and closes its singleton store at application shutdown. Bucket creation is optional.

`ObjectStorageService.getObjectInfo(path)` reads an object's byte length and optional modification time, entity tag and provider version without reading its body. Filesystem storage reads regular-file attributes, S3 uses `HeadObject`, and Google storage reads blob metadata. Missing objects raise `ObjectNotFoundException`; access/backend errors and known S3 missing-bucket errors propagate. Blocking lookups use `StorageDispatcher`. Entity tags are opaque values, not SHA-256 digests. These observations do not pin revisions, guarantee unchanged content or establish backup recovery coverage.

`listObjects(prefix, offset, limit)` enumerates literal keys and their `ObjectStorageInfo` metadata without asset bodies or per-object lookups. Public pagination uses nonnegative Long offsets and positive limits; provider continuation tokens stay internal. Filesystem enumeration sorts regular-file keys, follows directory aliases, prunes unrelated prefixes and retains at most the requested offset/limit window of metadata. Selected link/traversal failures and missing roots fail explicitly. S3 listing has no immutable version IDs; Google includes available generations. Source changes can shift page positions, so enumeration alone is not a closed snapshot.

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
