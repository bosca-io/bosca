# WorkOps

Kotlin/JVM projects for project management, task tracking, releases, and automation in the Bosca platform. They include a core domain model, services and repositories, background jobs, and store pipeline integration.

## Modules

| Module | Description |
|---|---|
| `core-workops` | Domain models, BQL query language, error types |
| `workops` | Service layer — controllers, services, repositories, migrations |
| `workops-jobs` | Background job processing |
| `store-pipelines` | Store pipeline integration |

## Prerequisites

- **JDK 25** (resolved via Gradle toolchains)
- **Docker** and **Docker Compose** (for local Postgres, NATS, Dragonfly)

## Build

Run these commands from the **workspace root**. `workops` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :workops:test  # Test WorkOps modules
./gradlew :workops:core-workops:test :workops:workops:test  # Focused tests
./gradlew :workops:workops:koverHtmlReport  # Coverage report
```

## Local Infrastructure

From the workspace root:

```bash
cd workops
docker compose up -d               # Start services
docker compose down                # Stop services
```

| Service | Port |
|---|---|
| PostgreSQL | 5433 |
| Dragonfly (Redis) | 6380 |
| NATS | 4222 |

## Architecture

`core-workops` contains domain models, including a custom query language (BQL) with its own tokenizer, parser, validator, and query planner for task filtering. The `workops` module adds controllers, services, repositories, and Flyway-managed database migrations. The `workops-jobs` module handles asynchronous background processing.

KSP code generation handles dependency injection and service wiring via Bosca DI, Service, and Core annotations. The core module is `:workops:core-workops` in the root Gradle build.

## Dependencies

- Bosca Platform libraries (DI, security, storage)
- Bosca Core (annotations, models)

## Editable workflow guidance

Studio → WorkOps → Settings → Guidance edits five guides: investigation, specification,
interface review, implementation, and verification. Select platform defaults or a project.
Projects inherit the current platform policy and any guides they have not overridden.
Saving replaces the selected scope's overrides; restoring defaults removes those overrides
and their AgentResources. Each save or reset requires the revision loaded by the editor.
Concurrent changes return a conflict before any writes. Use Reload and reconcile to retain
the draft, include other saved changes, and choose which version to keep for conflicting guides
or policy before saving against the refreshed revision.
Studio preserves drafts while switching scopes and typing during a save, and warns before
leaving or reloading with unsaved changes.
Undo and redo apply within the selected scope and guide; switching either starts a new editor history.
Platform writes require administrator authorization; project writes require project management
permission. Project reads require view permission.

Guide bodies are content-system ProseMirror documents with document-backed AgentResources.
Configuration stores their references and typed policy, not plain-text document bodies.
Guidance reads use the configuration service's standard cache.
Changed guides create document snapshots that preserve previous bodies. Identical saves retain
the existing documents, resources, and revision; hidden platform edits do not invalidate overridden
parts of a project's guidance. The bundled defaults are standalone
`SKILL.md` files under `workops/workops/src/main/resources/workops/guidance/`.

Import SKILL.md previews its name, description, and body in the selected guide's draft.
Save applies the draft; import never changes executable policy. Export saved SKILL.md
exports the current saved guide. The five guides can be exported independently; policy
is configured through the typed settings rather than skill prose or frontmatter.

Default policy requires current interface review, RED/GREEN/REFACTOR TDD evidence, a
behavioral test quality review, and measured coverage of at least 99% lines and 97% branches.
Repository instructions and stricter module coverage gates still apply. Policy changes
are explicit settings changes; editing prose alone cannot bypass the checks.

MCP `workops_spec_instructions` and `workops_plan action=instructions` accept optional
`projectId` or `projectKey`; omitting both returns platform guidance. Context and execution
recommendations load the spec's project guidance. Reviews and validation comments record
its revision, derived from effective content and persisted override state. Verification and diffs expose stale evidence after changes
to effective policy or guide content, including inherited changes. These tools record
caller evidence; they do not execute tests or independently audit coverage reports.

Transitions into a DONE status enforce the effective policy in the backend, including
direct GraphQL and MCP transitions. Completion requires current evidence for every
requirement, completed linked tasks and active child specifications, and finished or cancelled
generated tasks. Invalid or stale evidence leaves the specification and its history unchanged. An internal completion
revision advances atomically when contributing mutations commit and guards the final DONE write. Child checks and GraphQL child counts read current active children and status categories, independently of stored rollups. Document
checks bypass caches and reject soft-deleted metadata; a concurrently committed edit rejects completion and rolls it back. Requirement
ordering uses stored positions and stable creation/identity tie-breakers, so task progress does not
invalidate interface review. `V57__spec_completion_revision.sql` introduces the revision;
`V58__deferred_spec_completion.sql` defers contributing-row triggers until commit and includes
child hierarchy and status changes. This lets document and task writes finish before they acquire
specification completion locks. Requirement content replacements serialize on the requirement;
version conflicts, cancellation, and write failures roll back all changes.

Requirement input reserves the top-level Acceptance criteria section for the structured criteria
list. Parsed Markdown validation recognizes heading variants and inline formatting while allowing
examples inside code blocks and blockquotes. MCP uses the same validator as the backend.

The guidance GraphQL namespace exposes `effective`, `importSkill`, and `exportSkill`
queries, plus `save` and `reset` mutations under `workOps.guidance`. Both mutations require
`expectedRevision` from the last `effective` response. Reads have no seeding
side effects, and editing guidance does not require restarting MCP. Deploy the updated
backend together with the CLI before using these operations.
