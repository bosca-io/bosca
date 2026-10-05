# bosca-social

Identity and interaction domain for the Bosca platform, covering profile management, community, collaboration (with Slack/Teams bridge and federation), and real-time chat. Each domain follows the core/implementation split pattern. Comment modules are in `content`.

## Modules

| Module | Description |
|---|---|
| `core-profile` | Contracts -- profile interfaces and models |
| `profile` | Implementation -- profile service, repository |
| `core-community` | Contracts -- community interfaces and models |
| `community` | Implementation -- community service, repository |
| `core-collaboration` | Contracts -- collaboration interfaces and models |
| `collaboration` | Implementation -- federation, bridge adapters (Slack, Teams), chat dispatch, slash commands, mention extraction |
| `core-chat` | Contracts -- chat interfaces and models |
| `chat` | Implementation -- chat service, repository |

## Prerequisites

- Java 25 (resolved via Gradle toolchains)

## Build

Run these commands from the **workspace root**. `social` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :social:test  # Test active social modules
./gradlew :social:collaboration:test :social:profile:test :social:community:test :social:chat:test  # Focused tests
```

## Architecture

Four domains each follow the core/implementation split: Profile, Community, Collaboration, and Chat. Each `core-*` module defines interfaces, models, and annotations, while implementation modules provide the concrete logic. Collaboration includes adapter-based bridging to external platforms (Slack, Teams) via `BridgeAdapterRegistry` and peer-to-peer federation with a handshake protocol. Chat dispatch handles real-time message routing with mention extraction and notification jobs. Data access uses the repository pattern with Flyway-managed database migrations.

## Dependencies

- `io.bosca:core`, `io.bosca:core-security` -- from bosca-core
- `io.bosca:di`, `io.bosca:service` -- from `services-di`

## Use in this workspace

Depend on the root project path for the modules you need. For example:

```kotlin
dependencies {
    implementation(project(":social:core-profile"))
    implementation(project(":social:profile"))
}
```

Published `io.bosca:*` coordinates used by existing catalogs also resolve to these local projects in the root build.
