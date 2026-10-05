# Bosca GraphQL

GraphQL language tooling, typed client generation, and server integration for the Bosca platform.
These modules are projects in the workspace's root Gradle build.

| Project | Purpose |
| --- | --- |
| `:bosca-graphql:bosca-graphql` | Multiplatform GraphQL lexer, AST, and parser |
| `:bosca-graphql:bosca-graphql-client` | Typed client runtime and transports |
| `:bosca-graphql:bosca-graphql-server` | Server integration |
| `:bosca-graphql:bosca-graphql-gradle` | `io.bosca.graphql` code generation plugin |

Run from the workspace root:

```bash
./gradlew :bosca-graphql:test
./gradlew :bosca-graphql:bosca-graphql-gradle:test
```

Dependency versions are in [the shared catalog](../gradle/libs.versions.toml).
Local `io.bosca:*` dependencies resolve to their source projects in this build.
