# Studio System port inventory

The source of scope is `web/projects/studio/app/pages/system`, including components and
composables those pages use. A route or passing schema check alone does not establish parity.
Complete each workflow, then verify it through the composed Atlas API and browser controls.
The existing Vue pages remain in place; this port supplies the BML application in Atlas.

## Required workflows

| Studio source (under `pages/system`) | Atlas route | Required workflows | Current evidence / remaining work |
| --- | --- | --- | --- |
| `security/groups/index.vue` | `/system/security/groups` | Search, offset pages, create/type selection, copy ID, confirmed delete | Implemented; group creation/replay rejection verified by `AtlasSystemIntegrationTest` |
| `security/principals/index.vue` | `/system/security/principals` | Offset pages, identifier/ID search, include-deleted filter, identifier/profile/group/status summary, open details | Implemented; `AtlasSecurityIntegrationTest` and isolated native-control browser checks at 1920/1024/768/390px |
| `security/principals/[id].vue` | `/system/security/principals/{id}` | Credentials/originators, linked profiles/primary/deleted badges, groups, dates, IDs, mark/restore/permanent deletion | Implemented; `AtlasSecurityIntegrationTest` and isolated native-control browser checks at 1920/1024/768/390px |
| `security/personas/index.vue` | `/system/security/personas` | List/status/subsystems, create with subsystem picker and select-all, open details, confirmed delete | Implemented; `AtlasSecurityIntegrationTest` and isolated native-control browser checks at 1920/1024/768/390px |
| `security/personas/[id].vue` | `/system/security/personas/{id}` | Edit name/description/subsystems/enabled state, select-all, assignment by profile ID, confirmed delete | Implemented; `AtlasSecurityIntegrationTest` and isolated native-control browser checks at 1920/1024/768/390px |
| `security/tokens/index.vue` | `/system/security/tokens` | Self/other-account ownership filter and profile search, offset pages, name/description/expiration, scope catalog/custom/artifact rules, one-time raw token/copy, revoke/delete | Implemented; indexed owner search/filtering verified in both hosts; browser creation retains drafts while targets load, selects repository type/path/version/actions, persists a scoped token and clears restrictions; creation/edit/revoke/delete and one-time display verified |
| `security/tokens/[id].vue` | `/system/security/tokens/{id}` | Details/IDs/dates/IP/status/allowed groups/scopes, owner metadata/scope editing, revoke/delete | Implemented; `AtlasSecurityIntegrationTest` and isolated native-control browser checks at 1920/1024/768/390px |
| `security/passkeys.vue` | `/system/security/passkeys` | List transports/dates, real WebAuthn registration through same-origin proxy, confirmed deletion | Implemented; `AtlasPasskeyIntegrationTest` verifies both hosts, real browser registration, cancellation, account isolation, deletion/session reset and layouts at four widths |
| `config/index.vue` | `/system/config` | Search, create/edit structured JSON, public flag, preserve permissions, confirmed delete | Implemented; `AtlasSystemConfigurationIntegrationTest` verifies both hosts, JSON persistence, permission retention, denied replay and delegated group access; browser verification covers JSON editing, invalid-input recovery, filtering and deletion at four widths |
| `storage.vue` | `/system/storage` | List/types/configuration/embedding model selection, create/edit/delete, full or per-system reindex options | Implemented; production-host persistence/authorization/index-worker checks and browser model add/remove/edit/delete, scoped controls and responsive form acceptance |
| `search/index.vue` | `/system/search` | Configuration read/write, reindex/delete-first, metadata context preview | Implemented; real metadata preview/reindex acceptance, ACL-preserving edits and browser draft/confirmation checks |
| `backups/index.vue` | `/system/backups` | Offset pages, create/include-files, restore/conflict strategy, confirmed delete | Implemented; both production hosts, dedicated worker, archive contents and all conflict strategies verified by `AtlasBackupsIntegrationTest`; native browser controls and four layouts at four widths verified |
| `artifacts/index.vue` | `/system/artifacts` | Permanent redirect to `/artifacts/namespaces` | Implemented; permanent redirect verified in both Atlas hosts |
| `utilities/index.vue` | `/system/utilities` | Clear cache/CDN cache, expire jobs, clear locks with appropriate confirmation | Implemented; production-host and native-control browser checks verify all four operations, confirmation, replay rejection and visible unconfigured-CDN failure at four widths |
| `integrations/index.vue` | `/system/integrations` | Integration status and navigation with authorization | Status cards, refresh and navigation verified in both hosts; HubSpot browser acceptance includes the eight-provider index at four widths |
| `integrations/email.vue` | `/system/integrations/email` | Provider selection, deployment default and provider configuration links | Implemented; both production hosts, delegated group edits, deployment-default null value and native selector/browser checks |
| `integrations/smtp.vue` | `/system/integrations/smtp` | Host/port/security, authenticated or unauthenticated relay, validation, secret and ACL retention | Implemented; both production hosts, password preservation/clearing, delegated access, invalid-input recovery and browser checks at four widths |
| `integrations/mailgun.vue` | `/system/integrations/mailgun` | Domain/base URL/API key, URL validation, secret and ACL retention | Implemented; both production hosts, stored-key retention, delegated access, invalid-input recovery and browser checks at four widths |
| `integrations/sendgrid.vue` | `/system/integrations/sendgrid` | API and webhook verification keys, secret and ACL retention, webhook path | Implemented; both production hosts, credential replacement/clearing, delegated access and browser checks at four widths |
| `integrations/hubspot.vue` | `/system/integrations/hubspot` | Configuration, all/single-profile sync and third-party context preview | Implemented; both hosts, real person/company sync pipelines, separate profile worker, indexed profile search, private settings and delegated access; six HubSpot/index browser views at four widths |
| `integrations/mux.vue` | `/system/integrations/mux` | Credentials, playback policy, processing wait, quality/resolution, MP4, subtitle language and test mode | Implemented; both hosts, fresh-secret and ACL retention, default overrides, delegated access and all form sections at four widths |
| `integrations/push.vue` | `/system/integrations/push` | Delivery/provider toggles, FCM JSON/default credentials, APNs identifiers/key/sandbox and incomplete-credential warning | Implemented; both hosts, provider removal, secret/ACL retention, JSON validation and toggle-draft recovery; collapsed/expanded forms and APNs fields at four widths |
| `integrations/federation.vue` | `/system/integrations/federation` | Active peers/count/refresh, peer URLs/date, validated registration, shared-secret rotation/clearing, confirmed deactivation | Implemented; both production hosts, private secret persistence and denied replay; list and all three dialogs at four widths |
| `integrations/bible.vue` | `/system/integrations/bible` | Provider credentials, catalog browsing, text/audio source registration and allowed-use controls | Both production hosts: catalog paging/search, compatible audio targets, text/audio registration, permitted uses, refresh, version checks and confirmed removal/purge; 56 responsive browser measurements |
| `jobs.vue` | `/system/jobs` | Status/source filters, offset pages, running/pending counts, expandable full execution details and cancellation | Implemented; both hosts verify filters, current-page counts, expandable execution details, offset pages and confirmed cancellation; browser layouts at four widths |
| `scheduler/index.vue` | `/system/scheduler` | List, edit/create navigation, enable/disable, immediate run and confirmed deletion | Implemented; both production hosts and native browser controls verify enable/disable, navigation, confirmed execution and deletion at four widths |
| `scheduler/[id].vue` | `/system/scheduler/{id}` | All schedule fields, registered parameter schema assistance, cron validation/preview, execution history, principal state/actions, run/delete | Implemented; manual/timed creation/editing, definition metadata/schema display, cron previews, history paging/filtering, principal assignment/clearing, confirmed execution/deletion and a separate common worker; ten browser views at four widths |
| `packages/index.vue` | `/system/packages` | Package/version/installer status and execution, history, summary counts | Implemented; real root/Bible installer and package execution, authoritative summaries/history, confirmation and replay rejection in both hosts; 32 responsive browser measurements |
| `postgres.vue` | `/system/postgres` | Overview/tables/indexes/queries/settings tabs, all diagnostic fields, table detail, query detail, filters, maintenance controls | Implemented; all seven tabs, schema/bloat/I/O/settings filters, table/index/full-query details, maintenance confirmations and automatic refresh verified in both hosts; 144 responsive browser measurements |
| `nats.vue` | `/system/nats` | Overview/streams/KV/connections/routes, filtered consumers/messages/KV detail, JSON/text values, mutations/confirmations | Implemented; all five tabs, stream/consumer/message/KV details, filters, JSON/text values, writes and confirmed maintenance verified in both hosts; 168 responsive browser measurements |
| `meilisearch.vue` | `/system/meilisearch` | Health/version/stats, nodes/indexes/tasks/keys, node and storage forms, documents offset pages, settings/embedders, mutations | All 17 source operations plus named embedder removal; both-host browser workflows verify the typed tabs, paging, REST credentials, asynchronous failures/cancellation and confirmations; 216 responsive measurements, with populated node, drawer/settings and embedder captures reviewed |

## Visual review status

The presentation review uses Studio's actual components/styles as the reference: grouped navigation
and System accent, consumer-owned
palettes, fixed headings/tabs, metric tiles, section gutters, action placement and each page's layout.
`appearance.mjs` captures all 27 top-level/form routes in both themes and both hosts for direct review.
The latest pass records 216 captures at desktop and phone widths. Phone captures for every route,
desktop captures, and populated detail/dialog captures were inspected alongside the source. The
workflow-specific browser suites cover the additional detail routes, editors, confirmations, drawers
and short phone viewports. Overflow measurements alone do not establish visual parity.

The source review compares each page's fields, defaults, actions and linked destinations with its
BML consumer. The latest corrections restore groups' Description/Type columns, storage's untitled
table and compact model rows, Search's header Save action and tree editor, the separate token scope
dialog and 90-day default, principal-to-profile navigation, persona header actions and assignment
dialog, and scheduler cron/status/run-time columns. Long credential identifiers wrap inside their
cards. Persona assignment retains both the unsaved detail form and a rejected profile ID.

Controls are normalized to the BML design library: subsystem selection uses its searchable multiple
select, structured JSON uses its JSON editor, and resource names have separate action controls.
Action success uses the library toast; persistent cron validity and errors stay next to their inputs.
The multiline input's native `rows` controls its height, including two-row group/persona descriptions.
The catalog checks the actual height difference between two and four rows. System browser checks
measure inner editor/credential bounds and nonempty card rectangles, rather than relying only on
document overflow.

## Native process acceptance

The full `:server:atlas-server:nativeHostTest` run passes 19 tests across 16 integration classes
using rebuilt Atlas backend and standalone UI executables. This includes System routes in both
hosts, security, configuration, storage and real profile/metadata indexing, backups with a separate
worker, infrastructure diagnostics and maintenance, integrations, passkeys, scheduler/packages,
and the existing Git/artifact composition. JSONata's method-based builtin lookup has explicit
native reachability metadata, matching the existing server and CLI registration.

HubSpot and Bible transport checks use special JVM fixture entrypoints that intercept their
upstream clients, so they run separately from production-native executables. Their page rendering
is included in native System smoke coverage. Native success does not establish visual parity.

The final acceptance run on 2026-11-03 rebuilt both native executables and passed all 19 native
tests across those 16 classes. The System module passed 94 tests and its TypeScript check. The
packaged design-library browser suite passed in development and production modes, including JSON
state/compact sizing and multiline row sizing. The latest security browser run also passed scope
dialog dismissal, assignment error recovery, draft preservation, principal navigation/wrapping,
copy spacing, paging and all 16 Search toast/layout cases. Scheduler/package browser workflows and
the 216-capture whole-page appearance matrix passed before the final persona-only recovery change.
Git's JSON/editor/source/profile browser checks preserve all 22 original code grammars in both
themes, editing commands, search/replace, completion, folding and keyboard exit.

The subsequent BML convention audit passed 95 System model tests, 296 runtime tests and
TypeScript checks. Focused production-host and browser checks cover modal toast dismissal,
declarative dialog dismissal, token-scope draft retention, backups, federation, Meilisearch,
PostgreSQL disclosure/scroll retention and configuration editing with VIEW/LIST/EDIT access
without MANAGE. One Bible credential test passed its assertions but timed out during Atlas
shutdown; the isolated rerun passed, including shutdown. The native-host suite above predates
these final shared modal and runtime changes and has not been rerun against them.

## Invariants and completion checks

- Use the design library controls, overlays, editors, shell, tables, profile menu and interaction behavior.
  Consumers render `<profile-menu />` directly, with no application menu wrapper or manual mount.
- Keep client calls on the existing same-origin authentication/GraphQL/proxy paths. Typed operations
  retain the caller; real API authorization is the boundary, including replay of another user's state.
- Preserve principal/profile IDs as distinct concepts, group-based permissions, secrets that remain
  unchanged when blank, one-time token disclosure, and two-stage principal deletion.
- Preserve offset pagination, filters, drafts on failures, cancellation propagation and visible errors.
  Multiple native select values are submitted as JSON array text, consistent with primitive live-action arguments.
- Wire each Atlas namespace through SDL, accessors, field dispatchers and providers. Verify embedded,
  standalone and worker composition where dependencies change. Do not add unrelated SDK/backend stubs.
- Source-level parity review must include every page's actions, fields, defaults and linked components.
- Run focused model/runtime tests and schema checks; add real production-host integration for wiring.
- Browser checks must exercise meaningful workflows and layout at desktop/tablet/phone widths in an
  isolated fixture. Green test counts are not coverage or proof of untested workflows.
- Inspect native resource/reachability changes and run applicable native validation before claiming it.
- Review Git diff/status without committing or altering unrelated changes.

Redis has no existing Studio System page or administration GraphQL namespace. Building Redis
administration is new functionality rather than an item in this source port inventory.

## Security verification

`AtlasSecurityIntegrationTest` covers both UI hosts, authorization replay rejection, persona CRUD and
profile assignment, self/other-account token creation, metadata/scopes edits, explicit clearing of
restrictions, one-time raw-token disclosure, revocation/deletion, and principal mark/restore/permanent
deletion. It also verifies the legacy `/system/artifacts` redirect. The optional
`-Patlas.security.browser=true` check drives actual design-library controls, exercises the lifecycle
forms, and records thirteen views at four widths under `build/reports/security-browser`. The latest check
has 52 measurements and no document overflow. Token details use stacked Details/Scopes cards and a
520px edit modal; browser checks verify its bounds, visible actions and focus return after dismissal. The owner picker uses the actual Admin Search Index
and maps each selected profile to its principal, verified through both hosts. Browser acceptance also
loads registry targets during creation without losing the draft, filters repository types, selects a
real path/version, persists a `pull,admin` scope and verifies that clearing all selected scopes returns
the token to unrestricted access.

The API adds optional `clearScopes: Boolean! = false` to token editing. Omitted/null scopes keep
existing restrictions; this flag explicitly returns a token to unrestricted access. The UI uses it
when an owner clears the scope selection. Service/controller tests cover preservation, explicit
clearing, conflicting input and rejection of non-interactive credential mutation.

`AtlasPasskeyIntegrationTest` exercises both production UI hosts and the WebAuthn REST proxy,
including anonymous/API-token rejection, principal-bound one-time challenges and invalid
attestation rejection. With `-Patlas.passkeys.browser=true`, an isolated Chrome virtual
authenticator completes registration through the real SDK and backend verifier. Browser checks
cover cancellation/reopening, transport/date display, denied cross-account deletion, confirmed
removal and sign-in after session revocation. Sixteen list/dialog measurements at
1920/1024/768/390px have no document overflow; screenshots are under
`build/reports/passkeys-browser` in this module. The shared auth tags own registration and local
session clearing. The SDK unit coverage run measures 100% lines (624/624) and branches (327/327).

## Configuration and utilities verification

`AtlasSystemConfigurationIntegrationTest` uses both production UI hosts and the composed Atlas API.
It verifies JSON/public state, retention of current group permissions, rejected replay without ACL
access, delegated configuration editing by a regular principal with group permissions, and all four
utility mutations. Configuration access follows the existing API's ACLs; creation and global recovery
operations retain their existing administrator checks. The browser check exercises the shared JSON
editor, malformed input recovery, filtering, deletion and utility confirmation controls, including the
expanded configuration form at phone/tablet/desktop widths. Native resource patterns include the new
configuration SDL; the native process suite verifies these operations.

## Storage and search verification

`AtlasStorageIntegrationTest` covers both hosts, storage/model persistence, existing catalog resolvers,
real metadata-context preview and denied replay, and observes a document in the fixture-prefixed
Meilisearch index after the index worker runs. Fixture source metadata is seeded into the disposable
database before read-only preview checks; browser mutations use the API. Storage model snake_case
columns and model/configuration field resolvers are explicitly mapped. Search installer matching
only uses configured SEARCH systems, leaving supplementary/vector systems intact.

`StorageActionsTest` verifies draft retention across association changes, invalid model JSON,
explicit reindex scopes and confirmation, and search-save metadata preservation. Browser acceptance
uses the actual enhanced model selector and JSON editors, verifies an unsaved model draft survives
add/remove actions, and checks list/editor/search layouts at four widths. BML dispatches
`bml:form-serialize` before resolving form arguments; editors flush in document capture before
repeated form fields are collected. Runtime tests cover click and submit actions.

Configuration permissions are optional on writes: omitted/null preserves the current ACL and an
explicit empty list clears it. Production-host tests exercise a group with VIEW/EDIT and no MANAGE
using Search settings. The final configuration workflow check uses VIEW/LIST/EDIT without MANAGE.

## Backup verification

`AtlasBackupsIntegrationTest` checks both production UI hosts, anonymous gating and rejected
cross-account create/restore/delete actions. An API process with workers disabled enqueues a backup;
a separate process consuming only `backup` creates the archive. Tests inspect ZIP manifests,
database records and a known binary file to distinguish file-inclusive from data-only backups.
SKIP retains changed configuration, OVERWRITE restores its archived value, and FAIL rejects a
conflict. Deletion removes both the tracking record and stored archive.

`-Patlas.backups.browser=true` drives the design-library forms in both hosts, including file
inclusion, completion refresh, the conflict-strategy selector, restoration and confirmed deletion.
It records the list and three dialogs at 1920/1024/768/390px: 32 measurements with no document
overflow under this module's `build/reports/backups-browser`. Model tests verify offset pagination,
confirmation, failed-draft retention and a fresh completed-backup check before restoration.

Atlas composes the existing Backup providers, schema, field accessors, dispatchers and dedicated
queue. The existing backup table catalog spans optional modules; export selects installed relations
inside its snapshot. Restore uses PostgreSQL record conversion to retain database column types and
fails on insertion errors. Native resource patterns include backup SDL; the native process suite verifies the archive and worker workflows.

## Email settings verification

`AtlasEmailIntegrationTest` covers Email, SMTP, Mailgun and SendGrid in both production UI hosts.
It verifies anonymous gating, rejected live-state replay, and successful edits by a regular
principal with VIEW/EDIT group permissions and no MANAGE permission. Writes preserve the existing
ACL and keep provider settings private. Ordinary blank credential fields retain their current
authorized server values; SMTP and SendGrid expose explicit clearing controls. Stored secrets are
not copied into the serialized UI model. Unit tests also cover fresh-secret reads, failed drafts,
invalid ports/security/origins, deployment defaults and password whitespace.

`-Patlas.email.browser=true` exercises the real provider/security selectors, save controls, SMTP
and Mailgun validation recovery, password/key retention, and explicit webhook-key clearing.
Thirty-two measurements cover all four pages in both hosts at 1920/1024/768/390px, with no document
overflow. Screenshots are under this module's `build/reports/email-browser`. These checks configure
providers without sending email. The remaining integration pages and integration-status index
are tracked separately above.

## Mux and push settings verification

`AtlasProviderSettingsIntegrationTest` checks both production hosts, anonymous gating, denied
live-state replay and successful regular-user edits with VIEW/EDIT group permissions. Mux covers
all settings from Studio, credential retention/replacement/clearing, default encoding overrides,
and preservation of additional stored settings. Push covers delivery/provider toggles, FCM JSON
replacement, Application Default Credentials, APNs identifiers/private key/sandbox, explicit
clearing and removal of disabled providers. Incomplete APNs configuration remains saveable, with
a warning; it does not introduce a server-startup validation gate. Stored credentials stay out of
the UI model, and ordinary saves retain the existing configuration ACL.

`-Patlas.providers.browser=true` drives design-library selects, switches, the JSON editor and
multiline input in both hosts. It verifies malformed-JSON recovery and retention of unsaved FCM
and APNs drafts while toggling providers. Forty measurements cover Mux credentials/quality,
collapsed push settings, expanded FCM and APNs fields at 1920/1024/768/390px, with no document
overflow. Screenshots are under this module's `build/reports/providers-browser`. The email browser
suite also passes after the shared input extension. These checks configure settings without
contacting Mux, Firebase or APNs. Native process acceptance also verifies settings persistence.

## Federation administration verification

`AtlasFederationIntegrationTest` covers both production hosts and the owning federation service.
It verifies anonymous gating, administrator-only reads and rejected replay for all three mutations.
Peer URLs/dates persist, stored secrets stay out of the page model, rotation replaces the private
configuration value, blank input clears it, and deactivation removes the peer from the active list
and clears its secret. Registration accepts absolute NATS URLs and HTTP/HTTPS API URLs, including
local hostnames. Failed registration retains the complete draft.

Atlas selects the existing federation repository/service and Chat/Collaboration migrations.
The service refreshes subscriptions when an inbound listener provider is present, allowing
administration hosts to manage settings independently. GraphQL has explicit peer/channel field
resolvers, and the peer channel rollup uses the existing service rather than an empty placeholder.
Domain tests cover listener presence and channel filtering; Atlas security and backup regression
checks pass with the new migrations.

`-Patlas.federation.browser=true` exercises registration validation/recovery, refresh, secret
rotation/clearing and confirmed deactivation through the design-library controls in both hosts.
Thirty-two measurements cover the list and registration/secret/deactivation dialogs at
1920/1024/768/390px, with no document overflow. Screenshots are under this module's
`build/reports/federation-browser`. These checks cover administration; cross-instance message
transport and native-image execution are separate from this browser acceptance.

## Bible providers verification

`AtlasBibleProvidersIntegrationTest` exercises both production UI hosts, administrator authorization
and rejected live-state replay. It verifies private saved credentials and blank-key retention, catalog
paging/search, text registration with no granted uses, compatible audio targets and playback permission,
source refresh and stale-version rejection, independent recording removal and explicit configuration
purging. Atlas installs the existing root/Bibles collection packages needed by registration.

The catalog fixture launches the production bootstrap and uses the real API.Bible adapter with a
test-only HTTP interceptor. Domain services, GraphQL dispatchers, PostgreSQL persistence and same-origin
UI proxies remain real. It does not contact the external provider.

`-Patlas.bible.browser=true` drives configuration, disabled-configuration connection testing, automatic
catalog/target loading, catalog paging/search, text/audio registration and use selection, source
refresh/removal and confirmed purging in both hosts. Seven views at four widths produce 56 responsive
measurements with no document overflow under `build/reports/bible-browser`. The design tags own their
client initialization; catalog and target requests use declarative server actions. Cross-area metadata
links retain Studio's `/cms/metadata/{id}` destination; CMS hosting is outside this System module. Native
page rendering passes; the upstream-intercepted provider transport checks remain JVM-only.

## Scheduler, job history and package verification

`AtlasOperationsIntegrationTest` verifies both production UI hosts and the owning services.
Manual schedules persist a null cron and no automatic next run; timed edits populate the next run,
and clearing cron returns a job to manual operation. All execution flags, catch-up limits and JSON
parameters persist. The check rejects non-administrator live-state replay, verifies principal
assignment/clearing, creates enough real executions for offset paging and filtered counts, and
checks the existing history-cancellation action and confirmed deletion. A separate `common` worker completes the queued
maintenance jobs. No scheduler/queue services are mocked.

The existing SDL and database already permit manual schedules. Core scheduler input/model/service
and runner handling now preserve that nullable contract, including enablement and catch-up paths.
Git cron synchronization removes an entry whose cron was cleared and retains its declared pipeline
schedules. Owning scheduler and Git CI tests pass.

The job-definition API currently returns null parameter schemas. The port displays any returned
schema through the shared JSON editor, with model and compiled-render checks for supplied and
absent schemas; it does not claim the current catalog supplies schemas. Definition changes and cron
preview preserve the full editor draft.

`-Patlas.operations.browser=true` exercises native design controls for cron validation/preview, JSON
validation recovery, manual job creation, repeated confirmed execution, cancellation, scheduling
flags, principal assignment/clearing, global job details/filtering/pages and confirmed deletion.
Eighty measurements cover ten scheduler/job views in both hosts at 1920/1024/768/390px with
no document overflow, under `build/reports/operations-scheduler-browser`.

Package acceptance starts with the existing root/Bible packages uninstalled, executes their real
installer/package actions, verifies authoritative installed versions and persisted history, rejects
unauthorized replay and recognizes an already installed individual installer. Browser checks include
required confirmations, package summaries, refresh and the history tab in both hosts: 32 measurements
with no document overflow under `build/reports/operations-packages-browser`. The shared
`confirm-dialog` tag supports declarative completion and starts a fresh attempt when reopened.
The native process suite also verifies these operations.

The scheduler's existing cancellation API changes recorded status and does not stop underlying
queue work. This port uses that contract. Extending queue cancellation is a separate scope question
raised with the user; no new queue-cancellation behavior is claimed here. Browser checks also verify
that rejected execution requests remain visible in their confirmation dialog and retain unsaved
schedule text/JSON. Principal-assignment failures retain their own draft.

## HubSpot and integration-index verification

`AtlasHubSpotIntegrationTest` uses the real production bootstrap and existing HubSpot adapter,
with upstream HTTP intercepted only in test fixtures. It verifies private configuration, blank-token
retention, explicit clearing/replacement, additional stored fields, ACL retention, delegated VIEW/EDIT
access, rejected replay, real person/organization contexts and contact/company pipeline execution.
An isolated organization is seeded before interaction; subsequent changes use the API. A separate
`profile,index` worker completes bulk synchronization and indexes actual profiles for the Admin Search
Index. Configured production startup is checked without contacting HubSpot.

The optional client is selected at startup before GraphQL resolver/job construction; changing the
stored token requires an API/profile-worker restart. Atlas installs the existing HubSpot attribute
and pipeline packages. The shared code-editor tag owns its CodeMirror lifecycle and form value.
Git's editable file workspace consumes that same tag and supplies its own preview and commit adapter.
Local JSONata evaluation consumes only the server-authorized context and current editor drafts;
search, context loading and sync use typed server actions.

`-Patlas.hubspot.browser=true` records 48 responsive measurements across settings, expressions,
person/company previews, error recovery and integration-index navigation in both UI hosts. There
is no document overflow or browser exception. Context/search/sync updates preserve unsaved settings.
The shared editor's development/packaged checks cover form submission, draft preservation, supplied
replacement, reset and disabled/read-only behavior. The packaged catalog export-contract and existing
interaction suite pass. Native route rendering passes; upstream-intercepted HubSpot transport tests remain JVM-only.

## PostgreSQL verification

`AtlasPostgresIntegrationTest` starts a dedicated pgvector PostgreSQL instance with
`pg_stat_statements` preloaded, rather than altering the shared test server. It checks both real
Atlas hosts, all seven tabs, table/schema filters, bloat and I/O diagnostics, all/unused/suggested
indexes, total/mean query ordering, settings search, details and denied cross-principal replay.
ANALYZE updates the real scratch table's maintenance statistics; reset changes the server's actual
statement-statistics reset timestamp. Cancel/terminate actions target only owned, read-only sessions,
whose resulting SQL states are verified. The scratch table is seeded before interaction, and all
maintenance actions use the composed API.

The owning pool-statistics service follows the existing provider `exists` contract, matching core
health reporting. Unconfigured optional pools do not get constructed, while failures from configured
providers still propagate. Owning tests cover both cases. Existing infrastructure/API/schema
regressions pass with the revised PostgreSQL view model.

`-Patlas.postgres.browser=true` drives all tabs and resource controls, table/index/full-SQL details,
confirmations, filters, ordering and maintenance actions in both hosts. Eighteen views at four widths
produce 144 measurements with no overflow or browser exception under `build/reports/postgres-browser`.
Ten-second overview refresh preserves unsaved filters and SQL viewer identity/focus. Detail dialogs
remain dismissed through subsequent actions and restore focus to the selected resource. Their bodies
scroll above fixed actions. Model/render tests also exercise PgBouncer, replica, slot, vacuum and
optional/null fields that are not present on a single-node fixture. Native process checks also verify these diagnostics and maintenance operations.

## NATS verification

`AtlasNatsIntegrationTest` starts a separate monitoring-enabled NATS broker and uses owned streams,
consumers and key-value buckets. Both production UI hosts exercise all ten source operations, real
headers/payloads, consumer state, subject/sequence/payload and KV wildcard/value filters, empty
values, rejected writes, key deletion, both tombstone paths and stream purging. Unauthorized replay
cannot mutate the fixtures. Stream sequences begin at 9007199254740993, and browser actions carry
exact decimal strings to the typed GraphQL Long boundary.

`-Patlas.nats.browser=true` verifies the five Studio tabs, automatic refresh, stream/consumer/message
details, JSON/text/empty payloads, KV inspection and text/JSON writing, all confirmations, rejected
writes and a server-rejected purge. Drafts survive filter/expand/format/error updates. Dialog actions
stay visible above their scrolling bodies, dismissed dialogs stay closed and focus returns to the
resource control. Twenty-one views at 1920/1024/768/390px produce 168 measurements with no document
overflow or browser exception under `build/reports/nats-browser`.

Model/render tests cover cluster routes and optional fields absent on the standalone broker,
independent consumer/message failures, validation before I/O, false mutation results, the KV
tombstone sentinel, completion followed by a failed refresh, and cancellation propagation. Existing
infrastructure and composed-schema checks pass. Native process checks also verify these operations.
