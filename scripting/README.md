# bosca-scripting

Script execution engine for the Bosca platform, providing Kotlin scripting capabilities with both local (in-process) and remote (out-of-process) execution modes. Supports sandboxed script evaluation with restricted class loading, trigger-based execution, and ephemeral script lifecycle management.

## Modules

| Module | Description |
|---|---|
| `core-scripting` | Contracts -- interfaces, models, and annotations for scripting |
| `scripting` | Implementation -- engine, services, jobs, with local-engine and remote-engine source sets |
| `scripting-engine` | Script engine implementation |

## Prerequisites

- Java 25 (resolved via Gradle toolchains)

## Build

Run these commands from the **workspace root**. `scripting` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :scripting:test  # Test scripting modules
./gradlew :scripting:scripting:test  # Test the implementation
./gradlew :scripting:scripting:koverHtmlReport  # Coverage report
```

## Architecture

The modules follow a core/implementation split. `core-scripting` defines interfaces and models; `scripting` and `scripting-engine` provide implementations. Consumers depend on the contracts in core. The implementation offers local and remote engine modes. Scripts can be bound to platform triggers and executed as background jobs via the shared queue.

## Dependencies

- `io.bosca:core`, `io.bosca:core-security` -- from bosca-core
- `io.bosca:di`, `io.bosca:service` -- from `services-di`

## Use in this workspace

Depend on the root project path for the modules you need. For example:

```kotlin
dependencies {
    implementation(project(":scripting:core-scripting"))
    implementation(project(":scripting:scripting"))
}
```

Published `io.bosca:*` coordinates used by existing catalogs also resolve to these local projects in the root build.
