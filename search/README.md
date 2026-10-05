# bosca-search

Search infrastructure for the Bosca platform, providing index management, query execution, and document indexing as a horizontal capability. Consumed by content, community, workops, profile, and other verticals through a unified search API backed by Meilisearch.

## Modules

| Module | Description |
|---|---|
| `core-search` | Contracts -- `SearchService`, query/filter/document models, index configuration |
| `search` | Implementation -- Meilisearch integration, transform pipeline, configuration |

## Prerequisites

- Java 25 (resolved via Gradle toolchains)

## Build

Run these commands from the **workspace root**. `search` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :search:test  # Test both search modules
./gradlew :search:core-search:test :search:search:test  # Focused tests
```

## Architecture

The repo follows a core/impl split. `core-search` defines `SearchService`, query/filter/document models, and index configuration. `search` provides the Meilisearch-backed implementation. Index initialization runs as a background job via `IndexInitializerJobFactory` to avoid blocking server startup. A `SearchTransformConfiguration` supports document transformation before indexing. Faceted search is supported via `SearchResultFacet` for faceted filtering across results.

## Dependencies

- `io.bosca:core`, `io.bosca:core-security` -- from bosca-core
- `io.bosca:di`, `io.bosca:service` -- from `services-di`

## Use in this workspace

Depend on the root project path for the modules you need. For example:

```kotlin
dependencies {
    implementation(project(":search:core-search"))
    implementation(project(":search:search"))
}
```

Published `io.bosca:*` coordinates used by existing catalogs also resolve to these local projects in the root build.
