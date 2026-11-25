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

Remote execution records the requested script version, caller identity, effective groups, and scoped credential restrictions in the job. Before running it, the worker checks that the script is still enabled and unchanged, the caller is active, and the submitted group membership remains valid. It also checks the credential's owner, expiry, revocation, and current scope and group restrictions. Later permission increases do not expand a queued job. Anonymous calls remain anonymous; callers needing a service identity must supply that identity when submitting the script. Raw tokens are never included in the job.

Deploy matching server and worker artifacts. Drain outstanding script execution jobs before upgrading from a version whose jobs do not contain the authority snapshot; those older payloads fail validation.

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

## Script API permissions and validation

GraphQL script reads use `scripts:view` and script group permissions. The public
API invocation flag does not grant access to source code. Editing, toggling and
deleting use the corresponding script permissions with `scripts:manage`;
execution uses `scripts:execute` and EXECUTE permission. Creating a script or
promoting it to TOOL also requires an administrator or service-account group.
TOOL registration and script persistence share a transaction. A missing
`kit.script` Agent leaves the registered tool available for later assignment.

`editScript` accepts `expectedVersion` to reject stale updates. `validateScript`
checks unsaved source and can compile it without invoking the result. A compiler
failure returns diagnostics; transport and infrastructure failures remain errors.
A server without the compiler sends validation to the scripting queue and polls
its private object-storage result for up to five minutes. The worker skips expired
requests, and the server deletes a consumed result. Validation is not a save or
an execution approval.
