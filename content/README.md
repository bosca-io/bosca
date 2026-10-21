# bosca-content

Content management, language, localization, slug generation, and Bible compilation modules for the Bosca platform. Manages metadata, collections, transitions, workflows, permissions, and supplementary content through a core/impl architecture with PostgreSQL-backed repositories and GraphQL controllers.

## Modules

| Module | Description |
|--------|-------------|
| `core-content` | Contracts: content models (metadata, collections, transitions, categories, sources) |
| `core-bible-providers` | Integration contracts: Bible provider access, configuration, and source administration |
| `bible-providers` | Bible provider adapters, encrypted configuration, and source persistence using the shared Bible models |
| `content` | Implementation: repositories, GraphQL controllers, migrations |
| `core-languages` | Contracts: language model and service interfaces |
| `languages` | Implementation: language repository and controllers |
| `core-localization` | Contracts: localization model and service interfaces |
| `localization` | Implementation: localization repository and controllers |
| `slugs` | Slug generation utilities |
| `bible-compiler` | Bible text compiler (parsing, indexing) |
| `core-comments` | Comment contracts and models |
| `comments` | Comment services and repositories |

## Prerequisites

- Java 25

## Build

Run these commands from the **workspace root**. `content` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :content:test  # Test content modules
./gradlew :content:content:test :content:bible-compiler:test  # Focused tests
./gradlew :content:bible-providers:test  # Provider adapters and configuration/source/cache tests
```

API.Bible chapter reads enqueue edition-wide cache cleanup on the content queue after the
response is stored. The worker keeps the newest responses within 499 verses across
configurations. This limit is eventual while jobs are pending; the worker evicts whole
chapter text, including chapters larger than the cache budget. Reads return the full chapter.
Usage tokens stay on the existing chapter rows after text eviction so delayed FUMS jobs
can report usage; both text and usage metadata expire after 14 days.

The existing Bible `/api/v1/content/metadata/` routes retain their response structure.
Verse responses add attribution when available. The single-chapter `/api/v2/` route
returns the shared `BibleChapterContent` model; its `/api/v1/` counterpart continues
to return `BibleChapter`.

Administrator queries and mutations under `content.bibleProviders` manage saved provider
configurations, browse catalogs, and add, refresh, or remove text and audio sources.
Configuration responses expose whether a key is saved without returning the key itself.

Bible `audio` lists attached recordings; `audioChapter(audioId, usfm)` fetches a recording
on demand and returns its attribution, verse timing and absolute Bosca playback URL
using the configured `content.storage.urlPrefix`.
Playback URLs identify the exact recording described by the response. Replaced, evicted,
expired or missing recordings return not found rather than serving a replacement. Existing
requests without a recording identifier continue to read the current recording.
The audio route supports byte ranges and checks content and playback access on every request.
Recording metadata remains on existing chapter rows, and media uses `ObjectStorageService`.
Warm playback uses the chapter cache, which reads recording metadata from PostgreSQL on a miss.
Background jobs retain the newest 50 files per upstream recording across configurations
and delete evicted or expired media. Responses expire
after 14 days; private usage metadata remains on the chapter after media eviction until
that expiry. Evicted recordings are excluded from playback and the 50-file limit. Cache entries
are removed immediately, while media deletion has a five-minute grace period so requests
that already resolved a recording can open it.

The Bible compiler tests download KJV and ASV DBL bundles rather than storing them in Git.
Set `BIBLE_RAW_ARTIFACTS` to the raw artifact download base URL, including the namespace,
repository, and version (`https://<artifact-host>/raw/<namespace>/<repository>/<version>`).
The download step appends `/kjv.zip` and `/asv.zip`. These must be DBL ZIP archives
containing `metadata.xml`, styles, and USX files.
Run `./gradlew :content:bible-compiler:downloadBibleTestResources` to download them explicitly;
the test task also runs this step automatically. Bundles are retained in the ignored
`content/bible-compiler/.test-resources/` directory for subsequent offline runs and IDE tests
(add that directory to the test runtime classpath when using the IDE test runner).
Delete a cached ZIP to download it again. Downloads are excluded from build caching and
are added only to the test runtime classpath, so they are not packaged as project resources.
Locally, if the variable is unset or a download fails, the build prints a prominent warning
and tests requiring the missing bundles are skipped. Other compiler tests still run.
In CI (`CI=true`), missing bundle configuration or a failed download fails the build.

## Architecture

The project follows a core/impl split where `core-*` modules define contracts (interfaces, models, annotations) and implementation modules provide PostgreSQL-backed repositories with GraphQL exposure. Content items move through states via a workflow engine with permission-gated transitions and job history tracking. Collections and metadata support supplementary attachments with source tracking. All content is language-tagged with localization as a first-class concern. The Bible compiler is a specialized module for parsing and compiling Bible text into the platform's content model.

## Reading and editing labels

Metadata and collections expose their freeform labels through the `labels` field:

```graphql
query ContentLabels($metadataId: UUID!, $collectionId: UUID!) {
    content {
        metadata(id: $metadataId) { labels }
        collection(id: $collectionId) { labels }
    }
}
```

Use `setLabels` to replace labels without submitting a full content edit. Pass an
empty list to clear them. Both mutations require edit permission on the item.

```graphql
mutation SetContentLabels($metadataId: UUID!, $collectionId: UUID!, $labels: [String!]!) {
    content {
        metadata { setLabels(metadataId: $metadataId, labels: $labels) }
        collection { setLabels(id: $collectionId, labels: $labels) }
    }
}
```

## Finding a guide from a step

When a search result contains a step's metadata ID, query `Metadata.parentGuides`
to retrieve the guides' IDs, names, slugs, or attributes for navigation:

```graphql
query StepGuides($stepMetadataId: UUID!) {
    content {
        metadata(id: $stepMetadataId) {
            parentGuides {
                id
                name
                slug
            }
        }
    }
}
```

This returns accessible current guides that reference that metadata version as a step.
It returns an empty list when there are no accessible parents and includes each guide
once if it uses the same content more than once. Content can belong to multiple guides.
When already querying a `GuideStep`, use `guide { id name slug }` to retrieve its parent;
that field returns null if the parent is missing or inaccessible.

## Dependencies

- `io.bosca:core`, `io.bosca:core-security`, `io.bosca:core-storage`, `io.bosca:core-scheduler` -- from bosca-core
- `io.bosca:di`, `io.bosca:service` -- from `services-di`
