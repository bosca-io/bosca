# Feeds

`core-feeds` defines feed and subscription contracts. `feeds` fetches and parses RSS, Atom,
and JSON feeds, persists entries, and exposes them through Bosca services and GraphQL.
Feed serving reaches recommendations and other domains through their `core-*` contracts.

Both modules are projects in the workspace's root Gradle build. Run from the workspace root:

```bash
./gradlew :feeds:test
./gradlew :feeds:core-feeds:test :feeds:feeds:test
```

Some implementation tests require PostgreSQL test resources. Dependency versions are in
[the shared catalog](../gradle/libs.versions.toml).
