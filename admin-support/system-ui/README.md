# System UI

BML system administration module composed into both embedded Atlas and the standalone
Atlas UI server. It uses the shared Atlas GraphQL endpoint, authentication session and
BML design library. `<profile-menu />` owns account actions directly; System has no
profile-menu wrapper or client adapter. Domain authorization remains
in the existing API controllers.

Action results use the design library's `toast` tag, with success or error tone. Explicit form
validation and diagnostic feedback can remain inline beside the relevant controls. Shared feedback retains a stable notification key and advances its sequence for each
notice, including repeated failures. Reads without messages stay quiet. Initial request failures
are announced on page load.
Search browser checks verify dismissal, repeated saves, no replay on preview/navigation and stable
card geometry in both themes at four widths in both UI hosts.
Search places Save in the configuration card header and uses the shared JSON editor's tree view;
the metadata preview input and action share one row.

BML pages own authentication and loading; feature components own their live models and declarative
actions. Controls, editors, modal behavior and clipboard feedback use design-library tags. The
application's client helpers cover provider catalog scheduling, expression preview, repeated model
field serialization and browser-only tab/polling behavior; API work stays in server actions or typed
contracts. Modal fields use padded `form-body` and fixed `form-actions` footers directly. Bible
modal errors use dialog-owned design-library toasts. Programmatic dialogs clear their model state
through declarative `@dialogdismiss` actions; the token scope editor uses the same event instead
of a custom close listener. Controls supply explicit accessible labels.

The component audit removed the unused generic console renderer and its models, plus field-only
wrappers for token fields, registry scope rules and reindex options. Shared metrics, persona fields,
the asynchronous owner picker and payload rendering remain reusable components. HubSpot settings
and preview keep independent islands so preview requests retain the unsaved settings editor.
Infrastructure disclosures retain only their native open state while their values refresh;
Meilisearch task rows use this instead of retaining stale server markup.

The [Studio inventory](docs/studio-ui-inventory.md) records the source workflows and
their validation evidence.

Implemented routes:

- `/system/backups`: offset pages, backup creation with optional binary files, completed-backup
  restoration with SKIP/OVERWRITE/FAIL conflict strategies, refresh and confirmed archive deletion.
- `/system/integrations/email`: outgoing provider selection and the deployment default.
- `/system/integrations/smtp`, `/mailgun`, `/sendgrid`: provider connection/credential settings,
  validation, stored-secret retention and existing group permissions. These forms use the shared
  configuration API and support delegated VIEW/EDIT access.
- `/system/integrations/hubspot`: private token/association/list/subscription settings, shared code
  editors for JSONata expressions, profile search/context preview and all/single-profile sync.
  Both hosts, real sync pipelines/profile worker, delegated settings access and browser controls
  are verified.
- `/system/integrations/mux`: API credentials, playback/quality/resolution, processing wait,
  subtitle language, MP4 support and test mode.
- `/system/integrations/push`: delivery and provider toggles, FCM service-account JSON or
  Application Default Credentials, and APNs identifiers/private key/sandbox settings. Unsaved
  credential drafts survive provider toggles; stored credentials remain hidden on reload.
- `/system/integrations`: provider status cards and configuration navigation.
- `/system/integrations/bible`: private provider credentials, catalog paging/search, text/audio
  registration, compatible variants, permitted uses, refresh and confirmed removal/purging.
- `/system/integrations/federation`: active peer list/refresh, URL/date display, validated
  registration, private shared-secret rotation/clearing and confirmed deactivation.

- `/system/storage`: storage type/configuration editing, model catalog selection, per-model JSON
  configuration, association add/remove, confirmed deletion and scoped/full reindexing.
- `/system/search`: JSON search settings, metadata indexing-context preview, and incremental or
  clean rebuild requests for Admin Search Index. Saves retain existing configuration permissions.

- `/system/config`: filter, create/edit JSON through the design-library editor, public visibility,
  permission retention, delegated group access and confirmed deletion.
- `/system/utilities`: clear server/CDN caches, expire jobs and release job locks. Job recovery
  operations require confirmation; unconfigured CDN and backend failures remain visible.

- `/system/security/principals` and `/{id}`: 25-row offset pages, case-insensitive login-identifier or
  principal-ID search, deleted-account filtering, credentials,
  profile/group details, and two-stage deletion/restoration.
  Linked profiles retain their existing audience destination; long credential identifiers wrap
  inside the detail card.
- `/system/security/personas` and `/{id}`: create/edit/delete, subsystem selection/select-all,
  enabled state and profile assignment.
  Detail actions use header controls and a design-library assignment dialog. Assignment errors
  retain the entered profile ID and unsaved persona fields.
- `/system/security/tokens` and `/{id}`: account filtering/search, scoped token creation,
  one-time copy, registry scope rules, detail editing, revocation and deletion. Scopes use a separate
  design-library modal; Done, Escape and the close button retain the token draft and return focus.
  New tokens default to 90-day expiration, which can be cleared.
- `/system/security/passkeys`: the current account's registered passkeys, transport/date display,
  WebAuthn registration through the shared authentication form and confirmed removal. Removing
  a credential revokes the account's sessions and returns the browser to sign-in.

- `/system/postgres`: live database, query, connection, vacuum, lock, replication and
  PgBouncer diagnostics; table/index/I/O/bloat statistics, index suggestions, slow queries,
  settings, query cancellation, backend termination, ANALYZE and statement-statistics reset.
- `/system/nats`: server/JetStream metrics, connections/routes/subscriptions, streams and
  consumers, filtered message inspection, filtered KV browsing, KV writes/deletes,
  stream/message purging and tombstone compaction.
- `/system/meilisearch`: health/version/statistics, indexes, paged documents, settings,
  embedders, tasks, keys, node management and storage assignments.

- `/system/security/groups`: search, system/principal type filtering, 25-row offset pages, group creation, ID copying,
  and confirmed deletion.
- `/system/jobs`: paged execution history with status/source filters, timing, errors,
  parent IDs, catch-up/delay details, and confirmed cancellation through the scheduler.
- `/system/scheduler`: paged schedules, enable/disable, confirmed immediate execution
  and deletion, with separate cron, enabled, last-run and next-run columns.
- `/system/scheduler/{id}` (including `new`): create/edit schedules, JSON parameters,
  registered definition labels/queues and optional schema display, cron validation and upcoming-run
  previews, concurrency/catch-up settings, execution history with offset pages and filters,
  execution-principal assignment/clearing, confirmed triggering and deletion.
- `/system/packages`: available package versions, installer status, confirmed package
  and individual installer execution, authoritative installed-version summaries, refresh and a
  separate installation-history tab.

Presentation was reviewed against the Studio source and design-library examples, including populated
details and dialogs. The behavior checks below do not establish visual parity by themselves. The port uses grouped navigation,
System palettes, fixed headings, metric density, section gutters and page-specific form/action layouts.
Groups use the source Description/Type columns and type colours. Their filter row uses matching
32px design-library controls, aligned labels and responsive spacing. Browser checks measure
control heights, alignment and corner radii in both hosts and themes. Storage has an untitled table frame
and a 600px design-library editor with compact JSON configuration fields and responsive model rows.
The storage browser matrix measures both wrapper and editor bounds in both themes and UI hosts;
JSON token contrast is checked against the rendered editor background.

The source review covers all 31 Studio System pages and their linked controls. Native process acceptance passes
across 16 integration classes (19 tests), including real profile and metadata indexing. Meilisearch
and token-picker browser acceptance also pass. Studio's existing pages
remain available. The job-definition API currently returns null parameter schemas; the schedule
editor displays a schema when one is supplied.

The whole-page appearance matrix captures 27 routes in both hosts and themes at 1440px and 390px
(216 captures). Run it with the monitoring-enabled NATS fixture:

```bash
./gradlew :server:atlas-server:test --tests bosca.atlas.server.AtlasNatsIntegrationTest -Patlas.appearance.browser=true
```

Captures are under `build/reports/appearance-browser`. Workflow-specific suites below also capture
populated details and dialogs at desktop, tablet and phone widths.

System content regions use BML scroll-only keys so refreshed server markup retains the current
scroll position. Console keys include the selected tab, allowing tab navigation to start at the top.
PostgreSQL browser checks verify automatic refresh and scrolling during a pending manual refresh
at desktop and phone widths in both hosts, while checking that the rendered metrics advance.
Measurements are recorded in `build/reports/postgres-browser/scroll-checks.json`.

Source lives under `src/main/bml`, `src/main/kotlin`, `src/main/graphql`, and
`src/main/client`. Generated BML and GraphQL code stays under `build/`.

Run from the workspace root:

```bash
./gradlew :admin-support:system-ui:check
./gradlew :server:atlas-server:test --tests bosca.atlas.server.AtlasGraphQLSchemaTest --tests bosca.atlas.server.AtlasSystemIntegrationTest
./gradlew :server:atlas-ui:test
```

The integration test starts production Atlas processes with isolated PostgreSQL, NATS,
Meilisearch and file storage. It checks embedded/standalone rendering, anonymous redirects,
regular-user gating, cross-user live-action replay rejection, and persisted group creation.
Browser interaction and native process acceptance have separate commands below.

## Infrastructure consoles

All 40 query and mutation operations used by the three Studio infrastructure pages are
included as typed client operations. PostgreSQL has the seven Studio tabs, typed metric/resource
views, table/index/query details, filtered diagnostics and resource-specific maintenance controls.
NATS has the five Studio tabs, filtered streams/buckets/connections, consumer diagnostics,
message and key-value detail, JSON/text payloads and resource-specific maintenance controls.
Resource names are plain text, including bloated PostgreSQL tables; separate design-library icon
buttons open details or editing. Active PostgreSQL queries use distinct icon-only cancel and
disconnect actions with confirmations. Query fields wrap into labelled records on narrow screens;
browser checks verify no horizontal overflow. Configuration keys follow the same pattern.
Meilisearch uses Nodes/Indexes/Tasks/Keys tabs, a right-side index drawer with Stats/Settings/Embedders/Documents,
node/storage dialogs and resource-specific confirmations. Dedicated browser acceptance covers both hosts;
populated node, settings-drawer and embedder captures were reviewed. Structured input uses the shared JSON editor. Destructive actions require confirmation, and API authorization is preserved even
when a live state payload is replayed by another principal.

The NATS monitoring URL is configured on the backend through `NATS_MONITORING_URL`
(default `http://localhost:8222`). JetStream/KV operations use the configured NATS connection.
Monitoring failures are displayed on the console. UI hosts continue to use the one same-origin
Atlas GraphQL proxy; no browser-to-infrastructure access is added.

Studio has no Redis-specific console or Redis administration GraphQL namespace to port.
No Redis administration surface is introduced here.

Infrastructure acceptance uses a separate monitoring-enabled NATS container and isolated
PostgreSQL/Meilisearch fixtures, covering both UI hosts, live PostgreSQL statistics, KV writes
and deletes, cross-principal authorization, and Meilisearch index creation/documents/deletion:

```bash
./gradlew :server:atlas-server:test --tests bosca.atlas.server.AtlasInfrastructureIntegrationTest
```

Security production-host and browser checks:

```bash
./gradlew :server:atlas-server:test --tests bosca.atlas.server.AtlasSecurityIntegrationTest -Patlas.security.browser=true
```

The check uses a fresh Chrome profile and isolated Atlas services. It exercises native design
controls and measures principal/persona/token list and detail pages, configuration list/editor, storage list/editor, search and utilities at desktop, tablet and phone widths. Browser screenshots and measurements stay under `build/reports/security-browser`.

System modals compose the design library's `dialog`/`confirm-dialog`, padded `form-body`, and
bordered `form-actions`. Its shared modal layout keeps the body scrollable above fixed actions.
Persona and group creation are checked in both hosts and themes at four widths, including a short
phone viewport. Security workflows also measure footer padding and action bounds. Persona
"Select all" remains inside the padded body and retains the open editor and entered draft through
its server update. Modal measurements are in `build/reports/security-browser/modal-layout-checks.json`.

Principal search filters the database before paging. Search and deleted-account filters survive
Previous/Next navigation; a new search starts at page one. The page indicator and unavailable
Previous/Next controls remain visible for single-page and empty results. The production-host and
browser checks use 26 matching principals, including one deleted account, in both UI hosts.

Token-owner choices use the design-library asynchronous select, backed by an authenticated
`SystemTokenOwnerSearch` contract. Both hosts verify profile-to-principal resolution against the
real Admin Search Index, denied non-administrator searches and automatic owner filtering. Creating
a token retains its name, expiration and scopes while registry targets load. The scope picker
filters repositories by type, loads actual versions, creates a `pull,admin` rule and persists it;
clearing all scopes then verifies the token is unrestricted. Details use stacked cards and a 520px
edit modal; desktop/tablet/phone checks verify viewport bounds, fixed actions and focus return.
Registry actions are `pull`, `push`
and `admin`, matching the owning permission evaluator.

Passkey production-host and browser checks:

```bash
./gradlew :bml:bml-auth-ui:check :admin-support:system-ui:check :server:atlas-server:test --tests bosca.atlas.server.AtlasPasskeyIntegrationTest --tests bosca.atlas.server.AtlasGraphQLSchemaTest -Patlas.passkeys.browser=true
```

The check completes real WebAuthn registration with an isolated Chrome virtual authenticator in
both hosts, exercises cancellation and rejected cross-account actions, then removes the credential
and signs in again after session revocation. List and registration-dialog screenshots at four
widths are recorded under `build/reports/passkeys-browser` in this module.

Configuration and utility production-host acceptance:

```bash
./gradlew :server:atlas-server:test --tests bosca.atlas.server.AtlasSystemConfigurationIntegrationTest
```

The check edits a configuration with existing group permissions, verifies JSON/public state and
permission retention, permits edits granted through group ACLs, rejects unauthorized replay,
exercises all four utility operations,
and verifies that an unconfigured CDN returns a visible failure. The security browser check also
exercises the configuration editor and utility controls using the isolated fixture.

Storage and search production-host acceptance:

```bash
./gradlew :server:atlas-server:test --tests bosca.atlas.server.AtlasStorageIntegrationTest
```

The check persists storage/model associations through the real API, previews disposable source
metadata, rejects unauthorized action replay, and observes a metadata document in Meilisearch after
an index worker processes the reindex request. The shared browser check covers model selection,
add/remove actions with unsaved JSON, edit/delete, metadata preview, reindex confirmation and layout.
Atlas composes the existing search index/transform installers, content transformations, Bible source
services and Mux video service required by metadata services. No provider credentials are needed to
preview ordinary metadata. The model catalog uses the existing AI module registrar. The configured
package list controls which packages are installed; it does not include the AI package.

Search setting saves omit `ConfigurationInput.permissions`. The API retains the current ACL when
this field is absent/null; an explicit empty list still clears permissions. This lets a group with
VIEW/EDIT update settings without requiring MANAGE to read the ACL.

Backup production-host and browser acceptance:

```bash
./gradlew :backup:backup:test :admin-support:system-ui:check :server:atlas-server:test --tests bosca.atlas.server.AtlasBackupsIntegrationTest --tests bosca.atlas.server.AtlasGraphQLSchemaTest --tests bosca.atlas.server.AtlasConfigurationTest -Patlas.backups.browser=true
```

The check uses a separate backup-only worker, inspects actual ZIP contents with and without files,
verifies all three restore strategies against changed configuration, rejects unauthorized actions,
and checks record/archive deletion. Browser acceptance drives both hosts' list/create/restore/delete
controls and records 32 responsive measurements under `build/reports/backups-browser` in this module.

Email settings production-host and browser acceptance:

```bash
./gradlew :admin-support:system-ui:check :server:atlas-server:test --tests bosca.atlas.server.AtlasEmailIntegrationTest --tests bosca.atlas.server.AtlasGraphQLSchemaTest -Patlas.email.browser=true
```

The check verifies private configuration, ACL retention, delegated edits, denied replay, credential
replacement and explicit clearing through both hosts. Stored secrets stay out of live UI state;
blank credential inputs retrieve and retain the current value under the caller's authorization.
The browser check configures the four pages without sending email, exercises validation recovery
and enhanced selectors, and records 32 responsive measurements under `build/reports/email-browser`.

Mux and push settings production-host and browser acceptance:

```bash
./gradlew :admin-support:system-ui:check :bml:bml-design-catalog:compileKotlin :server:atlas-server:test --tests bosca.atlas.server.AtlasProviderSettingsIntegrationTest --tests bosca.atlas.server.AtlasEmailIntegrationTest --tests bosca.atlas.server.AtlasGraphQLSchemaTest -Patlas.providers.browser=true -Patlas.email.browser=true
```

The check verifies complete persisted settings, fresh authorized credential reads, explicit
clearing, provider removal, delegated group access and retained ACLs. Browser checks cover all Mux
sections, collapsed/expanded push providers, JSON validation and unsaved draft retention. Forty
responsive measurements and screenshots are under `build/reports/providers-browser`; the existing
email browser suite supplies 32 additional measurements. These checks do not contact delivery or
video providers. `<input multiline>` supplies the design library's native multiline form control;
FCM credentials use the shared JSON editor.

Federation administration production-host and browser acceptance:

```bash
./gradlew :social:collaboration:test :admin-support:system-ui:check :server:atlas-server:test --tests bosca.atlas.server.AtlasFederationIntegrationTest --tests bosca.atlas.server.AtlasGraphQLSchemaTest -Patlas.federation.browser=true
```

The check verifies peer persistence and private secret creation/rotation/clearing, administrator
authorization and deactivation through both hosts. It records 32 responsive list/dialog measurements
under `build/reports/federation-browser`. Atlas uses the generated Chat and Collaboration registrars
for the existing federation service, its dependencies and migrations.
The API uses explicit field resolvers. Security and backup regression tests pass with these migrations.

Bible-provider production-host and browser acceptance:

```bash
./gradlew :admin-support:system-ui:check :server:atlas-server:test --tests bosca.atlas.server.AtlasBibleProvidersIntegrationTest --tests bosca.atlas.server.AtlasGraphQLSchemaTest -Patlas.bible.browser=true
```

The check verifies real persistence, source version checks, rejected replay and explicit purging.
The production bootstrap uses a test-only HTTP interceptor for the existing API.Bible adapter.
Browser acceptance exercises catalog/search/paging, private configuration, text/audio registration
and permitted uses in both hosts, with 64 measurements under `build/reports/bible-browser`.
The empty configuration selector is disabled and remains free of action errors. Rejected provider
credentials exercise repeated error toasts, dismissal and stable card geometry in both themes
at four widths per host, with 16 additional screenshots.

Scheduler, execution-history and package acceptance:

```bash
./gradlew :bosca-core:core-scheduler:test :bosca-core:scheduler:test :admin-support:system-ui:check :git:git-ci:test :server:atlas-server:test --tests bosca.atlas.server.AtlasOperationsIntegrationTest --tests bosca.atlas.server.AtlasGraphQLSchemaTest -Patlas.operations.browser=true
```

The check verifies manual/timed jobs, persisted parameters/flags, authorization, principal state,
filtered/paged execution history and a separate worker completing real maintenance jobs. Package
checks execute the existing root/Bible installers and verify authoritative status and history.
Browser acceptance drives both hosts and records 80 scheduler/job and 32 package measurements
under `build/reports/operations-scheduler-browser` and `build/reports/operations-packages-browser`.

Cancellation on these pages uses the existing scheduler history API: it changes the recorded
status and does not stop underlying queued or running work. Queue cancellation is a separate scope
question. Failed confirmation actions show their error inside the open dialog, and execution/history
updates retain unsaved schedule fields.

HubSpot and integration-index production-host/browser acceptance:

```bash
./gradlew :admin-support:system-ui:check :server:atlas-server:test --tests bosca.atlas.server.AtlasHubSpotIntegrationTest --tests bosca.atlas.server.AtlasBibleProvidersIntegrationTest --tests bosca.atlas.server.AtlasSystemIntegrationTest --tests bosca.atlas.server.AtlasGraphQLSchemaTest --tests bosca.atlas.server.AtlasConfigurationTest -Patlas.hubspot.browser=true
./gradlew :bml:bml-design-catalog:test --tests "bosca.design.catalog.CatalogApplicationTest.code editor submits native values and retains drafts options and reset" --tests "bosca.design.catalog.CatalogApplicationTest.packaged catalog serves isolated examples assets and real browser actions"
```

The check verifies private configuration, ordinary blank-token retention, explicit clearing/replacement,
extra fields, retained ACLs, delegated edits and administrator-only context/sync actions. Real contact
and company pipelines use the existing HubSpot adapter with test-only upstream HTTP responses. A
separate `profile,index` worker completes bulk synchronization and indexes actual profiles for search.
The production optional client is configured before GraphQL resolvers and workers capture its provider.
Changing the token requires restarting Atlas API and profile-worker processes.

Browser acceptance drives settings, CodeMirror expressions, person/company context, local JSONata
preview/error recovery, sync controls and integration-index navigation in both hosts. Six views at four
widths produce 48 measurements with no document overflow under `build/reports/hubspot-browser`.
Profile/context updates retain unsaved settings and expressions. The design library's `code-editor`
tag owns setup, native form text, reset, pending/disabled/read-only state and cleanup; its development
and packaged browser checks and the packaged export-contract suite pass. Native process acceptance includes this production composition; the intercepted upstream transport
checks remain JVM-only.
Git's file editor uses this same tag, with file-path grammar selection, wrapping and fill-height
layout. Its application adapter retains Markdown preview, dirty-navigation protection and commits.

PostgreSQL production-host and browser acceptance:

```bash
./gradlew :admin-support:postgres-admin:test :admin-support:system-ui:check :server:atlas-server:test --tests bosca.atlas.server.AtlasPostgresIntegrationTest --tests bosca.atlas.server.AtlasInfrastructureIntegrationTest --tests bosca.atlas.server.AtlasGraphQLSchemaTest -Patlas.postgres.browser=true
```

The dedicated pgvector fixture preloads `pg_stat_statements`, leaving the shared test PostgreSQL
server unchanged. Both hosts verify schema/bloat/I/O/settings filters, index diagnostics, total/mean
query ordering, full SQL details, administrator-only access, ANALYZE and statement-statistics reset.
Cancellation and termination target owned, read-only diagnostic sessions and verify their actual
PostgreSQL SQL states. No application connection is selected for those checks.

Browser acceptance covers 18 views at 1920/1024/768/390px in both hosts: 144 measurements with no
document overflow or browser exception under `build/reports/postgres-browser`. Automatic ten-second
overview refresh preserves filter drafts, the SQL viewer and keyboard focus. Detail dialogs stay
closed after dismissal, return focus to their resource control, and retain a scrolling body above
fixed actions. The native process suite also verifies these diagnostics and maintenance actions.
Tab loading uses a reserved spinner slot beside the tabs. Browser checks hold an actual tab request
open at all four widths in both hosts and verify unchanged tab, metric and diagnostic positions
before, during and after loading.

Pool statistics use the existing DI provider `exists` contract, matching the health endpoint:
unconfigured optional Trino pools are excluded; configured-pool resolution failures remain visible.

NATS production-host and browser acceptance:

```bash
./gradlew :admin-support:system-ui:check :server:atlas-server:test --tests bosca.atlas.server.AtlasNatsIntegrationTest --tests bosca.atlas.server.AtlasInfrastructureIntegrationTest --tests bosca.atlas.server.AtlasGraphQLSchemaTest -Patlas.nats.browser=true
```

The dedicated monitoring-enabled NATS fixture verifies all ten operations through both production
UI hosts, using owned streams, consumers and buckets. It checks wildcard/sequence/payload filters,
message headers, empty payloads, KV writes/deletes, both tombstone paths, stream purging and rejected
cross-principal replay. Real stream sequences start above JavaScript's safe integer range; browser
actions keep them as exact decimal strings until Kotlin parses the GraphQL Long.

Browser acceptance drives the five tabs, JSON/text inspection, KV text/JSON writing, empty values,
filters, confirmations, rejected writes and a server-rejected purge. Twenty-one views at four widths
produce 168 measurements with no document overflow or browser exception under
`build/reports/nats-browser`. Drafts survive filtering, expansion, format changes and errors; dialogs
keep their fixed actions visible, remain dismissed through later updates and restore focus. Model/render
tests cover cluster routes, optional fields, independent consumer/message failures, mutation results
and cancellation. The native process suite also verifies these operations.

## Meilisearch verification

The typed console replaces the generic operation forms with Studio's four primary tabs and four
index-detail tabs. Node editing keeps a blank API key, storage choices exclude current assignments,
documents use 20-row offset pages, settings use the declared GraphQL input, and embedder forms
retain drafts across provider changes. REST mappings use the design-library JSON editor and are
excluded from other providers' native form validation. Mutation notices report queued task IDs;
task details retain duration, timestamps and errors.

The existing update-embedders API patches the named configurations. The added `removeEmbedder`
operation removes one named provider without rewriting the others; removing the last provider uses
the existing reset operation. The client SDK extension is additive. Authorization stays in the
owning GraphQL controller.

```bash
./gradlew :admin-support:system-ui:check :server:atlas-server:test --tests bosca.atlas.server.AtlasInfrastructureIntegrationTest --tests bosca.atlas.server.AtlasGraphQLSchemaTest
```

The production-host integration renders the consoles in both hosts and drives embedded live actions
against isolated services. It verifies index creation/settings/deletion, two real user-provided
embedders, denied cross-principal removal, removal of one while retaining the other, and reset of the
last provider. Model/render tests cover tab/detail state, document paging, typed settings, node key
omission, assignment choices, JSON validation, confirmations, failure retention and cancellation.
`AtlasMeilisearchIntegrationTest` adds a disposable Meilisearch instance and owned REST embedder.
The browser workflow verifies node creation/editing with blank-key retention, storage assignment/removal,
index validation and asynchronous task failures, index deletion, both document pages, settings updates,
user-provided/REST embedder creation, malformed JSON recovery, provider switches, credential retention,
task cancellation, read-only key display, individual/last embedder removal and document deletion.
Its blocked REST request holds one real indexing task while the next queued task is canceled.
The provider verifies the retained credential on actual HTTP requests. Refreshes pause in dialogs;
navigation actions coalesce behind an active overview refresh.

The workflow captures 27 views at four widths in each host (216 measurements), using dark embedded
Atlas and light standalone Atlas. It checks viewport overflow, drawer bounds and fixed dialog actions.
Screenshots live in `build/reports/meilisearch-browser`.

```bash
./gradlew :server:atlas-server:test --tests bosca.atlas.server.AtlasMeilisearchIntegrationTest -Patlas.meilisearch.browser=true
```
