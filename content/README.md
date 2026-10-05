# bosca-content

Content management, language, localization, slug generation, and Bible compilation modules for the Bosca platform. Manages metadata, collections, transitions, workflows, permissions, and supplementary content through a core/impl architecture with PostgreSQL-backed repositories and GraphQL controllers.

## Modules

| Module | Description |
|--------|-------------|
| `core-content` | Contracts: content models (metadata, collections, transitions, categories, sources) |
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
```

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

## Dependencies

- `io.bosca:core`, `io.bosca:core-security`, `io.bosca:core-storage`, `io.bosca:core-scheduler` -- from bosca-core
- `io.bosca:di`, `io.bosca:service` -- from `services-di`
