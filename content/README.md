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

## Architecture

The project follows a core/impl split where `core-*` modules define contracts (interfaces, models, annotations) and implementation modules provide PostgreSQL-backed repositories with GraphQL exposure. Content items move through states via a workflow engine with permission-gated transitions and job history tracking. Collections and metadata support supplementary attachments with source tracking. All content is language-tagged with localization as a first-class concern. The Bible compiler is a specialized module for parsing and compiling Bible text into the platform's content model.

## Dependencies

- `io.bosca:core`, `io.bosca:core-security`, `io.bosca:core-storage`, `io.bosca:core-scheduler` -- from bosca-core
- `io.bosca:di`, `io.bosca:service` -- from `services-di`
