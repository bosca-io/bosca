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

## Per-index type exclusions

In Studio’s storage system settings, add exclusions to the search index’s configuration JSON
alongside its existing `indexName`, `filterable`, and other settings:

```json
{
  "indexName": "public",
  "excludeTypes": ["episode", "profile"],
  "excludeContentTypePrefix": ["image/", "video/"]
}
```

`excludeTypes` matches either the document’s custom `type` or its `_type` kind
(`metadata`, `collection`, or `profile`) exactly. `excludeContentTypePrefix` matches
prefixes of `contentType`. All matches are case-sensitive. Both lists default to empty.
These exclusions apply to writes to that index, including indexing jobs, pipeline document
writes, and partial updates. Both indexing and partial updates use the stored type fields when
they are omitted from the incoming document. Accepted indexing writes still replace the full document;
accepted partial updates preserve omitted fields. An excluded write removes the existing entry, if present,
and invalidates cached search results. Existing global transformation exclusions still apply.

Saving exclusions does not remove existing documents. Reindex that storage system with deletion
enabled for the affected document kinds to remove previously indexed entries. Explicit per-index
exclusions also apply to the administration index if configured there.
