# bosca-calendar

Calendar and event management for the Bosca platform, built on iCal4j (RFC 5545). Supports calendar CRUD, event scheduling (content publish events, job events), participant tracking, attachments, occurrence expansion, and synthetic event generation, all exposed via GraphQL controllers.

## Modules

| Module | Description |
|--------|-------------|
| `core-calendar` | Contracts: calendar/event interfaces, models, service definitions |
| `calendar` | Implementation: PostgreSQL repositories, GraphQL controllers, DB migrations |

## Prerequisites

- Java 25

## Build

Run these commands from the **workspace root**. `calendar` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :calendar:test  # Test both calendar modules
./gradlew :calendar:calendar:test  # Test the implementation
```

## Architecture

The project follows a core/impl split where `core-calendar` defines the contract (interfaces, models) and `calendar` provides the PostgreSQL-backed implementation with GraphQL exposure. iCal4j provides RFC 5545 compliance for calendar/event modeling, recurrence rules, and occurrence expansion. Specialized repository types handle content-publish events, job events, and synthetic events. All calendar operations are exposed through typed GraphQL controllers using the platform's `@TypeController` annotation pattern.

## Dependencies

- `io.bosca:core`, `io.bosca:core-security`, `io.bosca:core-scheduler` -- from bosca-core
- `io.bosca:di`, `io.bosca:service` -- from `services-di`
