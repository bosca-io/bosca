# bosca-experimentation

Personalization domain for the Bosca platform -- A/B testing, feature flags, recommendations, and user segmentation. Each vertical follows a core/implementation split: `core-*` modules define contracts (interfaces, models, annotations) while implementation modules provide PostgreSQL-backed services, GraphQL controllers, and background jobs.

## Modules

| Module | Description |
|--------|-------------|
| `core-experimentation` | Contracts: experiment and flag models, interfaces, annotations |
| `experimentation` | Implementation: repositories, GraphQL controllers, migrations |
| `core-recommendations` | Contracts: recommendation strategy and placement models |
| `recommendations` | Implementation: recommendation services and jobs |
| `core-segmentation` | Contracts: segment and campaign models |
| `segmentation` | Implementation: segmentation services and jobs |

## Prerequisites

- JDK 25 (via Gradle toolchains)

## Build

Run these commands from the **workspace root**. `experimentation` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :experimentation:test  # Test experimentation modules
./gradlew :experimentation:experimentation:test  # Test the implementation
```

## Architecture

Each domain (experimentation, recommendations, segmentation) follows a core/implementation split. Core modules define contracts; implementation modules wire together repositories, GraphQL controllers, and background jobs. The experimentation module exposes a GraphQL API. PostgreSQL persistence is managed through Flyway migrations, and KSP code generation handles DI registration through the `services-di` and `bosca-core` processors.

## Dependencies

- `io.bosca:di`, `io.bosca:service` -- from `services-di`
- `io.bosca:core`, `io.bosca:core-security`, etc. -- from bosca-core
