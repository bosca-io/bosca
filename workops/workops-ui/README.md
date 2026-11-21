# WorkOps UI

WorkOps BML pages and domain components imported by Atlas's shared UI host. Both embedded Atlas
and the standalone `atlas-ui-server` use the same compiled registries, packaged assets and session.
Loaders and actions use generated typed HTTP GraphQL operations under the caller's credentials.

Transient action successes use the Design Library toast through WorkOps feedback. Each feedback
instance keeps a stable notice identity and advances its sequence for every success, including repeated
messages. Navigation and unrelated updates do not replay old notices. Validation and failure details
remain inline; result links remain available after the toast closes.
The toast update passes 531 UI tests, 15 composed HTTP-host tests and both-host browser checks
for notice lifetime, repeated messages, dismissal, focus and navigation. Fresh JVM process coverage
merges with those tests at 98.8059% lines and 90.1175% branches, passing the 90% branch gate.

The first implemented route is `/workops/tasks`: validated BQL search, caller-owned saved filters,
25-row offset pagination, task links and explicit query/transport errors. `New task` and `new=1`
load the selected project's stored task form, use the shared schema renderer and supply WorkOps
entity choices. Configured profile, GraphQL and API-script choices use stored field configuration
and the caller's credentials. Static providers load once per form; filtering and offset pages use
that snapshot. Search providers use their configured variables or parameters. File/image fields
retain the existing Forms data-URL string/null values. Creation submits through Forms under the
caller's identity and retains a rejected draft; reloading a changed form also preserves the draft
and refreshes its providers. The module supplies
WorkOps navigation and theme identity; generic shell, controls, CodeMirror, profiles, badges,
timestamps and table/pagination presentation come from `bml-design`.
Project creation stores its configured task-form key. Switching projects in the new-task dialog
loads that project's stored form through the compiled BML action and retains the complete draft.
Database readback and both-host browser checks cover switching to a distinct form and back in
both palettes at four widths.
Task queries retain the existing browser preference, give explicit URL queries precedence and support
`/` to focus, Ctrl/Command+Enter and clearing the query. Draft BQL validates through the same
WorkOps API; stale replies cannot replace current feedback. `j`/`k` move through the current result
page and Enter follows its selected task link, while editors, dialogs and menus retain their keys.
Search and detail distinguish an assigned private profile from an unassigned task. Task edits retain
that identity, and task access without project VIEW permits editing other fields while keeping the
existing sprint and omitting inaccessible project choices.

`/workops/tasks/{id}` resolves UUID and task-key links. It provides versioned task edits,
workflow transitions with resolutions, watches, comments, task links, affected projects,
work logs and paginated activity. Editing retains the original version and a rejected draft.
Stored HTML descriptions use the Design Library's `html-content` component, with Markdown and
empty fallbacks. Editing the independent Markdown description retains stored HTML and the optional
Content document. Summary history uses readable labels for generated document names while retaining
authored names and the original stored history values. Both Atlas layouts verify these behaviors
through actual API readback, in both palettes at four widths.
Stored start dates use the shared date presentation and survive ordinary task edits.
Assignee search uses the existing authorized profile index through a generated server contract.
Task documents use the existing WorkOps create-document operation and Content load/save APIs.
The shared Design Library editor owns rich-text controls, CodeMirror blocks, collaboration and
save revisions. WorkOps supplies the caller's existing browser session and document identity.
Document creation, collaborative saves, reloads and Content access rules pass production-process
browser checks in both Atlas layouts. Native comments insert authorized profile handles; rich document
mentions retain content, collection and profile identities through collaborative saves. Activating a
document mention opens `/workops/references/{type}/{id}` inside Atlas and retains the originating task/tab.
These read-only views use the shared editor, profile, table and offset pagination components under the
current viewer's credentials. Actual browser checks cover collection paging, private profiles, access
revocation/restoration and anonymous gating. WorkOps loads the selected template's container choices
for the shared editor and keeps its template/version identity on save. Metadata and scripture containers use the Design Library's shared reference controls. WorkOps resolves
template-filtered metadata choices, visible Bible choices, human reference searches and selected
passages through generated same-origin contracts and existing Content/search APIs. Stored versions,
renderers and both scripture attribute representations stay in the document. Passage display includes
supplied styles and attribution; unavailable references retain their identities and offer reload.
The shared image picker supports existing Content images, upload progress/cancel/retry, descriptions,
titles and image replacement. WorkOps supplies generated authorized Content/search operations and
streams uploads and images through the existing Content routes on the UI origin. A retry retains the
prepared upload identity. Stored images resolve under the current viewer, and shared insertion points
follow Yjs identities across peer edits. Removing the target image cancels a delayed replacement with
retry feedback. Actual browser checks cover upload bytes, image-library paging, save/reopen,
readonly/private/missing images, permission changes and both palettes/widths. Raw SVG responses keep
inline presentation and disable active documents through the image response policy.

`/workops/requirements/{id}` resolves UUID and key links. It reuses the document integration and
provides Content name edits, priority/assignee changes, linked-task transitions with resolutions,
parent/task links and offset pages for comments/history. Name edits preserve the independent rich
body and lifecycle task description. History displays product names without generated requirement
tracking keys. Private assignees retain their identity when editing another field; Content access
remains independent from WorkOps access. Tasks that are not themselves linked requirements show a
requirements table with page-scoped progress, creation, assignment and confirmed deletion. Creation
uses the existing aggregate operation, seeding a rich heading/paragraph body and independent linked
task Markdown together. Requirement pages and task activity retain each other's tab/page state, and
creation offers a direct result link when the new row falls outside the visible page. Real browser
checks pass in embedded Atlas and separate API/UI/worker layouts, with both parent kinds, palettes
and four widths. Shared-library and independent-consumer checks also pass. Collaborative document
submission waits for persisted updates before saving; rapid typing followed by save/immediate reload
retains the full body and saved revision marker.

`WorkOpsUi.install` serves `/collaboration` inside the imported BML application in both Atlas hosts.
The Kotlin Netty/YKS server preserves Studio's Hocuspocus protocol and uses Content's permission and
binary persistence routes under each participant's current token. Content atomically seeds a new
room and merges concurrent writes; reconnect loads its CRDT history. Instance notifications use
Bosca's `PubSubService` for document invalidations and ephemeral presence. Document bytes stay in
Content, and periodic reconciliation repairs missed notifications. Real Studio-provider
and shared-editor browser fixtures cover simultaneous edits, persistence acknowledgements, awareness,
token refresh, permission revocation, version isolation, offline reconnect and unmount cleanup.
Two-listener fixtures pass cross-instance edits and presence, idle heartbeats and recovery without
a notification. The collaboration route has a 100 MiB message capacity; other routes retain their
own capacity. Both Atlas production layouts pass updates over one MiB, persistence and reopening
across separate UI processes. They also pass rich document saves between those processes;
edit propagation and save acknowledgement must arrive before the periodic recovery interval.
Authoritative access loss during an action removes an existing editor, while transport failures
retain its draft. Complete document adapter parity remains part of collaboration acceptance.

`/workops/filters` provides caller-owned saved queries with validated create/edit, task previews,
query links, confirmed deletion and offset pages. Invalid BQL retains its message, range, hint
and draft. BQL editors use the shared CodeMirror component.

`/workops/specs` provides specification BQL validation/search, caller-owned filters, 25-row offset
pages and aggregate creation with authorized title-based parent selection. Specification and task
queries keep separate catalogs and browser preferences while sharing the query controls. Creation
retains rejected drafts and creates its Content document through the existing aggregate operation.
Search/create passes real browser checks in both Atlas layouts, palettes and four widths.
Specification detail resolves UUID/key links and reuses the Content editor and the same requirements
component as tasks. Owner edits, workflow/resolutions, independent name/body, comments/history,
child paging and typed context selection/removal pass both-host checks. Contexts resolve names
through their owning APIs; Content, collections and profiles open in Atlas with their originating
specification state. Requirement changes refresh the specification count while preserving independent
component drafts and created-result links. Task generation uses the current saved document version
and resolves generated task names under the viewer's access. Git push uses the saved Content document,
preserves the JSON wrapper/template or renders Markdown for the bound path, and requires a human
author name/email. Binding changes and failed writes retain the draft. Unsaved editor changes remain
in the editor. These workflows pass the combined browser suite in both Atlas layouts.

`/workops/portfolios`, `/workops/programs` and `/workops/projects` provide Active/All/Archived
lists, create/edit, confirmed archive/unarchive and project moves. Edits retain the inspected version,
immutable key/parent, private owner identity, existing program dates and project task form/type scheme.
Parent names resolve through authorized roots. Project moves retain the project and its tasks and
permissions; the destination uses the existing MANAGE checks. Reloading choices preserves the draft
and selected owner label. A failed lookup cannot reopen the previous edited entity as a new form.
Studio's project Delete action calls archive; Atlas presents the existing behavior as Archive.

`/workops/milestones` and `/workops/settings/milestones` use the same program-owned component.
They provide program selection, create with an optional UTC target date, due/closed presentation,
confirmed versioned close and 25-row offset pages. An off-page result has a direct list link.
Existing program authorization also governs archived-program actions. Both planning and milestone
workflows pass actual browser checks in embedded and separate Atlas layouts, including ordinary
group grants, denied and stale drafts, private identities, palette/width geometry and reloads.
`/workops/projects/{id}` provides Overview, Board, Versions, Components, Dependencies, Labels,
Repositories and Analytics. Facts and recent-task status counts use the actual loaded result;
program and owner labels resolve under the viewer's access. Project moves retain the inspected
version and preserve task identity and permissions. The Board tab composes the same domain adapter
and Design Library movement controls as the standalone board, with independent header state.
Versions create/release, components and labels create/delete, dependency declarations and inverse
consumers, repository associations and telemetry identifiers use their owning APIs. Catalog and
relationship drafts survive rejected writes and choice reloads, with direct result links and offset
pages. Application/service editors keep separate drafts and page state. Deploy-config setup uses
the existing non-overwriting service, an explicit human author and the shared source viewer for the
actual committed file. Project MANAGE governs this workflow independently of Git-label visibility.
Actual browsers verify writes/readback, stale and denied retries, private references, Git author and
committer fields, embedded moves/quick add, eight tabs and dialogs in both palettes at four widths.

`/workops/releases/versions` reuses the project catalog with authorized project selection, including
archived projects. Versions sort by descending sequence and show their start/release dates and state.
Creation retains optional UTC dates through rejected writes and catalog reloads. Release uses the
inspected version; confirmed deletion uses the existing id-only API and reports when tasks still
reference a version. Successful creation retains a direct result link across offset pages or a failed
refresh. Both Atlas browser layouts verify these operations, permission changes and retries, project
catalog regressions, palettes and four widths.

`/workops/releases` provides program selection, 25-row offset pages, native run status and staged
release creation. The builder retains details, UTC dates and selected project versions across its
steps. Partial bundle retries retain the created release and acknowledged versions. Empty releases
remain editable. The detail workspace supplies authorized component labels, readiness, versioned
detail edits, independent bundle/unbundle and confirmed deletion. Summary refreshes preserve an
open editor's unsaved fields and focus.
Releases and Environments share a WorkOps program picker over the Design Library select. Choosing
a program navigates immediately through the existing GET route and starts at the first page.
The explicit submit control is available only when scripts are disabled.
Milestone, project, board, sprint, permission and notification-scheme filters use the Design Library
select’s opt-in `submitOnChange` behavior. Release project choices load their versions immediately
through a dedicated read action while retaining unsaved release details. Save and add actions remain explicit.

Release pipelines use the Design Library's readonly flow graph, schema form, controls and dialogs.
WorkOps supplies repository plans, run/job facts and existing release/Git actions: declared start and
promotion inputs, approvals and comments, run/job cancellation, failed-job rerun, selected-project
patching, attempt rollback and target-native environment rollback. Program Manage, repository Edit,
repository Execute and environment Execute stay independent; the API checks current access on each
write. Starts build the repository's default branch; promotions use the version tag and native CI's
source-stage and downgrade checks. Rejected actions retain drafts. Active-run refresh pauses for open
action dialogs and hidden documents, stops at terminal state and disposes with its island. Native
run links open the existing Atlas Git CI page. Both production-process Atlas layouts verify these
flows, both palettes and four widths; the inventory records overall release-route acceptance.

Release artifacts retain declared and registered publications, their project/version labels,
namespace, environment scope and actual status. Read failures retain explicitly stale rows;
successful empty reads describe the viewer's visible result. Localized notes generate from tagged
changes and edit the existing structured JSON variants through the shared JSON editor. Program
Manage governs writes. Invalid and rejected drafts retain their captured version, and accepted
writes close before independent readback. Background bundle/CI refreshes retain the open editor
and focus. User actions queue behind active reads, and completed runs discard deferred polls.
Both Atlas browser layouts verify publication and localization persistence, permission retries,
private labels, stale reads and the retained modal, in both palettes at four widths.

Release environment cards keep disjoint promotion families in promotion order and retain each family's
selected stop through refreshes and topology changes. Current targets, compatible projects and
version drift come from their owning WorkOps APIs. Failed reads retain explicitly stale facts and
cannot show an in-sync status. Project/version labels use authorized roots. Both Atlas layouts verify
actual deployment readback, denied reads and retries, and automatic selected-environment refresh
after native rollback, with both palettes and four widths.

Release application/service health loads in a deferred BML panel. Project application and service links
come from authorized roots. Session counters, exact open/fatal error-group totals and HTTP response
rates use the existing Analytics APIs and their manager/administrator permission boundary. Failed reads
retain explicitly stale values; unknown metrics stay unavailable. Native Design Library charts and
readable data tables retain actual timestamps, including the counter API's reported zero buckets.
Both Atlas layouts verify real counter/error-group persistence, permission changes, source replacement
during deferred loading, parent refresh, failed reads and retry in both palettes at four widths.

Store health has its own deferred panel over the existing release-scoped WorkOps API. It displays
Play track/build/rollout, App Store review/processing/phased state, TestFlight groups and crash
feedback, plus timestamped vitals and review histories. Histories match store, application and
version; missing or nonnumeric values remain unavailable. Failed reads retain one stale snapshot,
and parent bundle changes replace or clear the target cards. Both Atlas layouts verify Git/tag and
secret reads, production vendor adapters against disposable HTTP responses, permission changes,
failure/retry, escaped feedback, data tables and both palettes at four widths. Vendor credentials
and analytics history execution are isolated in those fixtures.

Live sessions resolve application links and release versions through authorized WorkOps roots, then use
the existing authenticated Atlas subscription relay. The default scope keeps exact release versions;
unknown versions pause those streams until the viewer explicitly selects all versions. The Design
Library supplies native world maps, readable point tables, map navigation and the expanded dialog.
WorkOps merges application/session identities, retains project colors, reconnects completed streams
and expires points 15 minutes after their latest received update. Server updates retain points across
compact/expanded views without duplicating streams. Both Atlas layouts verify retained and live
Analytics broker messages, version scope, source revocation/replacement, deferred failure/retry and
navigation cleanup. Client lifecycle checks cover expiry, late callbacks and browser-session changes.
The fixture publishes directly through the existing Analytics service; ingestion and geographic
enrichment are separate responsibilities.

`/workops/releases/environments` manages program deployment targets and explicit environment grants.
Creation uses the Design Library's shared slug input, retaining a manually edited key through choice
reloads. Existing keys, promotion sources and approval policy are read-only. Versioned edits preserve
the inspected policy and unsaved fields through grant changes or failed choices. The administrator-only
system-group catalog has independent feedback, so program managers can still edit an environment or
remove a visible explicit grant when group names are unavailable. Parent program grants stay inherited.
`/workops/settings/environment-types` supplies the administrator-managed global type catalog with
display-order edits and confirmed deletion that rejects types still used by environments. Both routes
retain rejected drafts, offset pages and direct saved-result links. Actual browsers verify API readback,
archived programs, stale and denied retries, distinct program/global permissions and both palettes at
four widths. The shared slug component also passes packaged catalog and independent consumer checks.

`/workops/boards` combines project and multi-project board choices and creates either scope through
the existing WorkOps API. Project labels come from authorized roots. Creation retains a rejected
draft and provides a result link within the created board's scope, including when the header project
was excluded or refreshing choices fails. The Design Library supplies board columns/cards, move
controls and dialogs; WorkOps supplies task bodies, workflow transitions, resolutions and writes.
Moves retain the rendered task version and update the view only after the API succeeds. Closing
moves await a resolution or cancellation. Stale and denied moves retain their destination and card.
The board offers sprint selection, exact authorized sprint membership, quick add and loaded-task
WIP warnings. An empty sprint displays no tasks. All tasks loads up to 200 rows and links to the
complete task search; counts and WIP warnings describe that loaded result. Creation and moves pass
actual browsers in both Atlas layouts, palettes and four widths.
Board scope uses membership IDs independently of project-label reads. Explicit task access in a
member project remains usable when that project's details are hidden; inaccessible tasks stay filtered
by their owning API.

`/workops/boards/{id}` composes that board view with column and settings views. Column editing
maps multiple statuses, retains the inspected payload and treats empty/zero WIP as no limit.
Adjacent ordering uses the existing temporary-order swap and reloads the actual columns after a
failed write. Unavailable reloads hide stale column actions. Settings retain their inspected board
version, existing private/archived project membership and stored swimlane strategy. Rejected drafts
survive a choice reload; resetting to the loaded board is explicit. Single-parent boards keep their
parent scope. Multi-project changes use explicit membership deltas. Actual browsers cover column
creation/edit/deletion, ordering collision/retry, stale drafts, ordinary-group permission changes,
membership readback and the shared board composition in both Atlas layouts.

`/workops/sprints`, `/workops/sprints/{id}` and the board detail Sprints view share the same
WorkOps lifecycle component. They provide project/board selection, planned/active/closed lists,
creation, commitment search and selection, dates, task additions, progress, completion and velocity.
Task searches use the caller's globally authorized results. Dates and selected tasks survive search
and offset pages; start/close retain the inspected sprint version, and adding a task retains its
inspected task version. The shared schema form supplies each unfinished task's destination.
Completed/cancelled tasks keep their assignment. Closing preserves the commitment/addition snapshot
while moving unfinished tasks to the backlog or a future sprint. Missing task details retain a
placeholder in that snapshot and are excluded from visible progress. Rejected actions retain their
draft and offer an explicit refresh path. Both-host browsers verify assignment readback, stale and
denied retries, human task history, hidden members, task/list paging and keyboard task navigation,
with both palettes at four widths. Project detail embeds the same board view and movement adapter.

`/workops/inbox` provides unread/all views, read actions and task navigation. Preferences use
the shared schema renderer for watch settings, event/channel choices, digest and quiet hours.
Explicit channel opt-outs and existing mute values survive saves; inbox refresh retains a draft.
Platform-pipeline approval notifications open `/workops/approvals/{id}` inside Atlas. This focused
view uses the existing platform run/decision APIs and retains their administrator read boundary.
Decisions refresh the exact awaited root/child gate before submitting, retain failed notes and
preserve a successful acknowledgement if status readback fails. Actual browsers verify notification
navigation, approval/rejection, nested gate identity, stale/access-loss recovery and keyboard focus
in both Atlas layouts, palettes and four widths. Stored notification links and Git CI routes remain
under their existing APIs.

The six global catalog routes manage task types, statuses, priorities, labels, link types and
resolutions through their existing typed APIs. They reuse the Design Library's color input and
retain inspected edit versions, rejected drafts, confirmed deletion targets and saved-result links.
Catalog colors respect their seven-character storage limit; optional label colors remain nullable.
Actual browsers verify CRUD/readback, stale edits, administrator changes and retries, referenced
deletion failure, link previews and offset pages in both Atlas layouts, palettes and four widths.
Real PostgreSQL cases also cover every hierarchy/status enum value through the owning repositories.

`/workops/settings/automation` manages global, portfolio, program and project rules. It reuses the
shared JSON editors and profile selection while retaining raw invalid drafts, inspected versions and
the stored scope. Existing private run-as references survive unrelated authorized edits. Versioned
enable actions, confirmed deletion and independent execution history use the owning APIs. Actual
browsers verify all four permission scopes, JSON persistence and recovery, paging, and execution
logs generated through the real task dispatcher in both Atlas layouts, palettes and four widths.

`/workops/settings/workflows` provides workflow and scheme lists, create/edit and confirmed deletion.
Workflow header edits retain the initial state and complete engine graph. Scheme edits retain stored
task-type overrides while changing the name, description or default workflow. New definitions start
empty. Existing administrator checks govern writes, and references block deletion. Detail/editor
views show ordered states, categories, initial markers and wildcard transitions through shared tables
and badges. Both Atlas browser layouts verify persisted fields/default swaps, stale and denied draft
recovery, referenced deletes, offset pages and both palettes at four widths. Real PostgreSQL checks
also verify override retention and graph preservation/deletion.

`/workops/settings/sla` provides policy/calendar lists and creation with read-only details. Shared
JSON editors retain invalid weekly-hours/holiday drafts and display stored calendar data; shared
source viewers show ordered goal conditions alongside duration targets and risk percentages.
Existing administrator checks govern reads and writes. Both Atlas browser layouts verify actual
structured persistence, invalid/time-zone and permission recovery, retained drafts, offset results
and detail views in both palettes at four widths.

`/workops/settings/components` reuses the project component catalog and project selector. It retains
four assignee modes, private lead/default-assignee identities, rejected drafts and settings-scoped
offset/result links. `/workops/settings/permissions` manages direct portfolio/program/project grants
through existing APIs. System-group discovery remains administrator-only; existing principal-group
grants resolve names through their permission relationship. Parent access stays independent from
direct rows, and an empty visible result does not claim that effective access is absent. Both Atlas
layouts verify actual writes, denied retries, inherited Manage access, discovery beyond 100 groups,
confirmed revocation and both palettes/four widths, alongside project/version regressions.

`/workops/settings/notifications` provides scheme selection and read-only event recipients, including
stored event names and unavailable recipient data. Caller preferences share the Inbox model and
the WorkOps fields component over the Design Library schema renderer. Saves retain supported
channel opt-outs, unknown event keys with supported channels and muted task/project identities.
The existing API continues to discard unrecognized channel values. Both Atlas layouts verify actual
preference persistence, invalid-time draft recovery, independent caller identities, Inbox parity,
scheme offset pages and both palettes at four widths. Notification APIs remain unchanged.

The [Studio inventory](docs/studio-ui-inventory.md) defines the complete migration and acceptance
scope. Specification, requirement and document source comparisons and combined browser checks pass.
The overdue due-date cue passed UI, packaged JVM and release-optimized native checks. After the
message-provider/configuration correction, both earlier macOS native layouts pass the 30 selected
production-compatible WorkOps suites. The five native cases also include actual SMTP delivery in
both worker layouts and real Studio → Atlas → restored Studio routing/document readback, with
zero failures, errors or skips. The corrected Linux ARM64 native API/UI/worker images now pass both
explicit media cases: 37.682s split and 18.176s combined, with zero failures, errors or skips.
PNG/JPEG/GIF dimensions persist as `32x24`, SVG jobs complete without an unsupported size, and
original bytes remain exact. Complete native archives and Dockerfiles retain generated libraries;
runtime hashes remain unchanged. Both JVM layouts also pass these assertions. External rendition
processing is outside the empty-size fixture scope. Five further Linux native cases pass: both
30-suite workflow/collaboration layouts (597.048s/606.012s), real SMTP in both layouts
(29.662s/34.322s), and retained Studio routing/document rollback (110.615s), with zero failures,
errors or skips. The seven aggregate native cases retain exact runtime and notification API hashes.
The Core coverage follow-up passes 1,991 tests, two real-socket transport soaks and its existing
95% line / 90% branch gates: 95.9474% lines and 90.0770% branches. Runtime code, coverage filters
and thresholds are unchanged. The follow-up review passes 527 UI tests, 15 composed HTTP-host tests,
all 34 browser workflow suites in both Atlas layouts and the concurrent work-log deletion cases.
The UI Kotlin aggregate passes its new 90% branch gate at 98.8037% lines and 90.0675% branches,
including model serialization. Dependency advisories remain documented
separately. Target-specific deployment and Studio retirement are future work.
The preceding Atlas API/worker build passed staged combined and split-host checks.
Swarm, local Caddy and Compose nginx now route the imported WorkOps pages and collaboration
WebSocket to the selected UI listener. Standalone deployment templates supply shared pub/sub
settings. Forty focused CLI, 20 installer and two local Compose cases pass, including real
Caddy/nginx forwarding and NATS/Redis configuration resolution. Target-specific routing and
rollback acceptance remains separate from these deployment preparation checks.
The real Studio → Atlas → restored Studio routing/document case passes against staged and native hosts,
retaining the platform API for Studio and preserving the saved document and authenticated identity.
The message-pipeline provider/configuration correction passes actual JVM, staged and native cases
through the installed WorkOps pipeline to real SMTP, preserving the in-app notification. The device
API namespace remains outside Atlas. All 736 staged API/UI manifest files and both native image hashes
remain unchanged after runtime checks. Broad workflow fixtures qualify in-app notifications; their
email pipeline is not installed. The dedicated SMTP cases use normal platform installers and an HTTP
contract fixture for the unchanged BML renderer. These fixtures do not qualify analytics ingestion or
every other platform pipeline.

Run focused checks from the workspace root:

```bash
./gradlew :workops:workops-ui:check
./gradlew :server:atlas-ui-server:test --tests 'bosca.atlas.ui.server.AtlasWorkOpsUiRoutesTest'
```

`check` also writes `build/reports/kover/report.xml` for handwritten WorkOps UI Kotlin. It excludes
the compiler-generated BML and GraphQL packages; model serialization and handwritten transports
remain included. This report measures the UI module separately from the WorkOps backend. The
complete host aggregate below enforces a 90% branch minimum; the existing backend, Content and
Core gates remain unchanged.

Collect the existing production-process browser/action coverage with a fresh output directory,
then merge it with the UI model and composed HTTP-host tests:

```bash
workops_coverage_dir="$(mktemp -d)"
workops_coverage_agent="$(pwd)/$(rg --files workops/workops-ui/build/kover -g 'kover-jvm-agent-*.jar')"
./gradlew :server:atlas-server:test --tests 'bosca.atlas.server.AtlasWorkOpsIntegrationTest' \
  -Patlas.workops.browser=true -Patlas.workops.coverage.agent="$workops_coverage_agent" \
  -Patlas.workops.coverage.directory="$workops_coverage_dir"
./gradlew :server:atlas-ui-server:koverXmlReport :server:atlas-ui-server:koverVerify \
  -Patlas.workops.coverage.directory="$workops_coverage_dir"
```

The aggregate XML is under `server/atlas-ui-server/build/reports/kover/`. Each JVM process writes
its own report; wait for the integration test to finish before merging. An explicitly supplied
directory must contain nonempty reports without unfinished agent locks. The fixture checks report
completion before removing its temporary files. When that directory property is supplied,
`:server:atlas-ui-server:check` also runs the 90% aggregate gate. Native fixtures do not support this JVM agent. These
Kotlin reports do not measure TypeScript or generated BML rendering; browser checks qualify those
interactions separately.

The HTTP suite exercises the actual composed UI with an isolated upstream fixture. Production
WorkOps service/job, browser and native-host acceptance remain separate requirements.

The focused production-process browser tests use isolated Atlas infrastructure:

```bash
./gradlew :server:atlas-server:test --tests 'bosca.atlas.server.AtlasWorkOpsIntegrationTest' -Patlas.workops.browser=true
```
