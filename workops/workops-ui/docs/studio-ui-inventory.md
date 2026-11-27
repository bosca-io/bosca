# Studio WorkOps UI inventory

This inventory defines the BML port against the checked-out Studio sources. Source paths below
are relative to `web/projects/studio/app`. Atlas destinations retain the `/workops` prefix;
`[id]` becomes a BML `{id}` parameter, including existing key-based detail links. There are 34
source routes. None is accepted for retirement until its behavioral scenarios pass against Atlas.

Current behavioral evidence covers all 34 Studio routes, shared document controls and the Atlas
platform approval view. The message-provider/configuration correction passes actual JVM, staged
and release-optimized native SMTP delivery in both worker layouts. Current native checks pass all
30 selected production-compatible suites in both layouts and real Studio routing/document rollback.
Native image metadata completion passes in both Linux ARM64 worker layouts. The Core line and
branch coverage gates also pass. The follow-up review passes all 34 browser workflow suites in both
Atlas layouts and the concurrent work-log deletion cases. WorkOps UI Kotlin is now measured
separately: 98.8037% lines and 90.0675% branches, including model serialization code, passing its
new 90% branch gate. Deployment and Studio retirement are separate future work; target
deployment details are not implementation acceptance criteria.

The owning implementation remains in `workops/workops`. The UI uses typed HTTP GraphQL operations
under the caller's authentication. Controls do not grant permissions: GraphQL controllers retain
their existing entity/group authorization, and inaccessible reads remain filtered. A profile is
distinct from its authenticated principal. Versioned writes send the version that was inspected.

## Route and operation matrix

The API column names the existing `workOps` namespace unless another root is explicit. The last
column identifies the acceptance fixture below; it is a required scenario, not a passing result.
Every row additionally requires the shared shell, error, authorization and layout checks.

| Studio source under `pages/workops/` | Atlas destination | Operations and preserved behavior | Fixture |
| --- | --- | --- | --- |
| `tasks/index.vue` | `/workops/tasks` | `savedFilters.searchTasks/validateBql/mine`, `projects.all`; BQL, free text, saved filters, 25-row offset pages, keyboard selection, configurable new-task dialog | Tasks |
| `tasks/[id].vue` | `/workops/tasks/{id}` | `tasks.task/taskByKey/update/transition/createDocument`, links, watchers, comments and worklogs; stored HTML/Markdown fallback, independent summary/Markdown edits, assignment/priority/sprint, estimates/due date, affected projects, links, optional document, history | Tasks, Documents |
| `specs/index.vue` | `/workops/specs` | `savedFilters.searchSpecs/validateSpecBql/mine`, `projects.all`, `specs.create`; specification BQL, saved filters, 25-row pages, hierarchy/progress and new-spec dialog | Specifications |
| `specs/[id].vue` | `/workops/specs/{id}` | `specs.spec/specByKey/update/transition`, children, requirements, contexts, generations, Git push, comments/history; metadata title and document editing | Specifications, Documents |
| `requirements/[id].vue` | `/workops/requirements/{id}` | `requirements.requirement/requirementByKey/update`, linked `tasks.transition`, requirement comments and content metadata; title/body, parent, priority/assignee, lifecycle and history | Specifications, Documents |
| `filters.vue` | `/workops/filters` | `savedFilters.mine/create/update/delete/searchTasks`; name/description/BQL, query preview, run in task list, retained invalid draft and confirmed deletion | Tasks |
| `inbox.vue` | `/workops/inbox` | `notifications.unread/mine/markRead/markAllRead/preferences/updatePreferences`; Unread/All/Preferences views, 30-row offset pages, entity navigation and channel/event choices | Activity |
| `portfolios.vue` | `/workops/portfolios` | `portfolios.all/create/update/archive/unarchive`; Active/All/Archived, key/name/description/owner and counts | Planning |
| `programs.vue` | `/workops/programs` | `programs.all/create/update/archive/unarchive`, portfolio choices; hierarchy, ownership and dates, Active/All/Archived | Planning |
| `projects/index.vue` | `/workops/projects` | `projects.all/create/update/archive/unarchive/move`, programs and cross-project move; Active/All/Archived, ownership and confirmed archive (the source's Delete action calls archive) | Planning |
| `projects/[id].vue` | `/workops/projects/{id}` | Project overview, Board, Versions, Components, Dependencies, Labels, Repositories and Analytics views; related CRUD, move, deploy-config creation/preview and recent tasks | Planning, Boards, Delivery |
| `milestones.vue` | `/workops/milestones` | `milestones.byProgram/create/close`; program selection, target date/progress, overdue status and close | Planning |
| `boards/index.vue` | `/workops/boards` | `boards.byProject`, multi-project boards, `boards.create`, sprint choices and shared BoardView; project/board/sprint selection and multi-project create | Boards |
| `boards/[id].vue` | `/workops/boards/{id}` | `boards.board/update`, column add/update/delete/order/status mapping/WIP; Board/Sprints/Columns/Settings, project scope, sprint start/close and backlog commitment | Boards |
| `sprints/index.vue` | `/workops/sprints` | `boards.byProject`, `sprints.byBoard/create/start`, backlog search; board/state selection, dates, commitment and scope-change indicators | Boards |
| `sprints/[id].vue` | `/workops/sprints/{id}` | `sprints.sprint/start/close`, task/backlog lookup, `tasks.update`; task selection/search/add, commitment, future sprint choices and incomplete-task disposition | Boards |
| `releases/index.vue` | `/workops/releases` | Program release list, Git lifecycle-run status, release creation and version bundling | Delivery |
| `releases/[id].vue` | `/workops/releases/{id}` | Release/components/readiness, environments/drift, lifecycle runs; edit/bundle, pipeline start/promote/approve/reject/cancel/rerun/patch, rollback, delete, artifacts, notes and telemetry | Delivery, Telemetry |
| `releases/environments.vue` | `/workops/releases/environments` | Program environments, environment types and security groups; create/update/delete, slug/key, source chain and EXECUTE permission grants/revocation | Delivery, Administration |
| `releases/versions.vue` | `/workops/releases/versions` | `projects.all`, `versions.byProject/create/release/delete`; project filtering, description and confirmed deletion | Delivery |
| `settings/task-types/index.vue` | `/workops/settings/task-types` | `tasks.taskTypes`, `taskTypes.create/update/delete`; name, description, hierarchy, color and icon key | Administration |
| `settings/environment-types/index.vue` | `/workops/settings/environment-types` | Environment-type catalog create/update/delete; name, description and display order | Administration |
| `settings/statuses/index.vue` | `/workops/settings/statuses` | Status lookup/create/update/delete; name, description, category and color | Administration |
| `settings/priorities/index.vue` | `/workops/settings/priorities` | Priority lookup/create/update/delete; name, description, color, icon key and display order | Administration |
| `settings/labels/index.vue` | `/workops/settings/labels` | Global label lookup/create/update/delete; name, color and retained edit draft | Administration |
| `settings/workflows/index.vue` | `/workops/settings/workflows` | Workflow and scheme list/create/update/delete; detail states/transitions, scheme default/associations and confirmed deletion | Administration |
| `settings/automation/index.vue` | `/workops/settings/automation` | Automation rules create/update/enable/delete, execution logs; trigger/condition/action configuration, valid JSON, outcome and history | Administration |
| `settings/link-types/index.vue` | `/workops/settings/link-types` | Link-type lookup/create/update/delete; name, inward/outward labels and category | Administration |
| `settings/resolutions/index.vue` | `/workops/settings/resolutions` | Resolution lookup/create/update/delete; name, description and order | Administration |
| `settings/sla/index.vue` | `/workops/settings/sla` | SLA policies and working calendars; create policy/calendar, targets, durations, calendar JSON and validation | Administration |
| `settings/components/index.vue` | `/workops/settings/components` | Project components lookup/create/delete; project selection, description, lead and assignee mode | Administration |
| `settings/permissions/index.vue` | `/workops/settings/permissions` | Portfolio/program/project permission lookup/add/remove; entity and group selection, action, confirmed revocation | Administration |
| `settings/notifications/index.vue` | `/workops/settings/notifications` | Notification schemes and preference updates; per-event channel choices, independent scheme and personal preference values | Activity, Administration |
| `settings/milestones/index.vue` | `/workops/settings/milestones` | Program milestone lookup/create/close; name, date and confirmation | Planning, Administration |

## Transitive component inventory

| Studio source | WorkOps responsibility | Shared component responsibility |
| --- | --- | --- |
| `components/NewTaskModal.vue`, `web/packages/forms/src/` | Load the selected project's `taskCreationFormSchemaKey`, falling back to `workops.create-task`; WorkOps entity choices, preselected project and submission result navigation | Existing Forms JSON Schema/UI Schema rendering, field layouts, native values, required/default/validation feedback and pending/error state; reusable rendering belongs in `bml-design` |
| `components/workops/NewSpecModal.vue` | Project/parent context and create action | Dialog, form fields, asynchronous selects and actions |
| `components/workops/BoardView.vue` | Task cards, allowed transition-to-status mapping, sprint filtering, WIP, quick add and refresh | Neutral board/column geometry, accessible pointer/keyboard move requests, focus/scroll retention and pending/rejection lifecycle |
| `components/workops/SprintCloseModal.vue` | Completion counts, unfinished tasks and selected destination sprint/backlog | Dialog, list, select, confirmation and feedback |
| `components/workops/MoveProjectModal.vue` | Destination program, existing move operation and reload | Dialog, select and form states |
| `components/workops/SpecRequirements.vue`, `TaskRequirements.vue` | Requirement list/create/assignment/delete, names and task links | Table, profile, select, badges, progress, confirmation and feedback |
| `components/workops/SpecContexts.vue` | Resolve typed context targets, add/remove context and route mapping | Existing selects, links, disclosures and fields |
| `components/workops/SpecTaskGenerations.vue` | Generation request/history, generated task resolution and links | Tables, timestamps, badges and action feedback |
| `components/projects/ProjectDependenciesSection.vue` | Dependency declarations/removal and project labels | Dialog/select/table/confirmation |
| `components/document/DocumentEditor.vue`, `composables/useCollaborationAndAttributes.ts`, `utils/editor/ydoc.ts` and editor extensions | Content metadata/template/version identity; title, save, context, permission and dirty-state integration | Extend existing `document-editor` for reusable supported nodes/marks, authenticated mention integration, collaboration lifecycle, normalization, selection/undo and retained edits |
| `components/collaboration/MentionPicker.vue`, `composables/useProfileSearch.ts`, `utils/profileMentions.ts` | Authorized profile search and persisted mention identity | Reusable suggestion/selection interaction, keyboard and focus lifecycle |
| `components/NotificationPreferenceMatrix.vue`, `utils/workopsNotificationPreferences.ts` | WorkOps event/channel mapping, stored defaults and updates | Native switches/checkboxes and table presentation; general matrix interaction belongs in the library if needed |
| `components/releases/ReleaseCreateModal.vue`, `ReleaseEditModal.vue`, `ReleaseComponentPicker.vue` | Program, release details, staged project versions and bundle mutations | Form fields, dates, dialogs, selects and feedback |
| `components/releases/ReleasePipelineSection.vue` | Repository Edit versus Execute versus environment Execute; start/declared inputs, service-owned release/promotion refs, promotion/source readiness, approval/rejection, cancel/rerun, patch, rollback and run refresh | Controls, dialogs, status presentation, JSON/code inputs and progress; no duplicated pipeline editor |
| `components/releases/ReleaseArtifactsSection.vue`, `ReleaseNotesSection.vue` | Declared versus actual artifacts; notes generation/edit and API outcomes | Existing tables, badges, JSON editor and feedback |
| `components/releases/ReleaseTelemetryRail.vue` | Environment chain, deployment state and drift queries | Existing popover, status and timestamp controls |
| `components/releases/ReleaseHealthSection.vue`, `ReleaseAppHealthRow.vue`, `ReleaseServiceHealthRow.vue`, `ReleaseStoreHealthSection.vue` | Application/service/store query identities and data mapping | Existing chart component, compact layouts and readable data tables |
| `components/releases/ReleaseLiveSessionsSection.vue`, `composables/useReleaseLiveSessions.ts` | Linked project applications, per-version sources, authenticated live-session subscription, TTL, resubscribe and disposal | Existing map/chart rendering and dialog; transport remains application-owned |

## URLs, state and data contracts

- Task/spec BQL uses the `bql` query argument and current `workops-tasks-bql` /
  `workops-specs-bql` browser preference. An explicit URL takes precedence. Preserve query escaping,
  free-text terms, invalid query draft, validation positions/hints and offset reset when changing a
  filter. Keep offset pagination; the source list size is 25 and inbox size is 30.
- Preserve `new=1` create entry points from shell search and existing contextual creation. A project's
  configured form key remains authoritative. Changing projects must not discard unrelated draft input.
- Resolve task, spec and requirement UUID/key detail URLs through the authorized API result; never
  guess parent scope from query arguments. Use actual document titles for specs/requirements and
  keep internal tracking identifiers out of their product presentation.
- Project tabs share the resolved project. Load tab-specific data when needed. Do not request every
  release log, full document, telemetry series or Git diff while rendering an unrelated list.
- Keep task `descriptionMarkdown` independent from its optional content document. Spec/requirement
  rich bodies stay in Content as ProseMirror JSON. Preserve title, template/version and relationships;
  unsupported editor content must retain its original JSON and report the limitation.
- Existing content collaboration HTTP paths are under
  `/api/v1/content/metadata/{id}/document/collaboration?version={version}`; initial read, seed and
  update operations need the same-origin transport and existing authentication. API/schema presence
  alone does not prove document collaboration works.
- Reuse Atlas's same-origin GraphQL/auth and subscription transports. Release `liveSessions`
  subscriptions retain the selected app/version, per-app session identity, reconnect, 15-minute
  eviction and unmount cleanup. Active pipeline refresh also stops on terminal/unmount.
- Replace Studio `/git/...` destinations with the actual imported Atlas Git routes, preserving the
  correct repository/run identity. Distinguish Git CI runs from platform pipeline runs. Use the
  established artifact UI/file destinations rather than guessing protocol paths.
- Verify notification, command-palette and content-context destinations at cutover. Configure deployed
  origins in the existing host configuration; no deployment hostname belongs in client source.
- Platform-pipeline approval notifications retain their stored `/pipelines/runs?runId=...` values.
  The WorkOps destination adapter maps the approval event to `/workops/approvals/{id}` inside Atlas.
  That view preserves the platform run identity, existing administrator run-read checks and pipeline
  Execute decisions. Atlas Git CI routes retain their own identity and destination.

## Authorization and failure scenarios

Every fixture below runs with an administrator and an ordinary authenticated principal whose profile
has only the relevant group/entity grants. Include another inaccessible portfolio/program/project and
its tasks/documents/repositories. Confirm reads filter it and writes fail without leaking its content.

Reference-data mutations in `LookupMutationControllers.kt` require the existing `sa` or
`administrators` group. Other settings follow their owning controller's current checks rather than
a new blanket UI admin policy. Portfolio/program/project access uses their permission evaluators;
MANAGE controls grants/revocation. Content, profiles, Git, Forms, environments and releases retain
their own authorization. Environment Execute and repository Execute are independent of Edit.

For every write, preserve the draft after validation/network/authorization/version failure, show an
actionable inline error, prevent duplicate pending submission and propagate cancellation. Confirm
destructive operations and keep expected-version semantics. Refreshing a board, inbox or pipeline
must not erase an open editor, modal draft, current caret, selected tab or scroll position.

## Acceptance fixtures and scenarios

These fixture definitions remain the acceptance contract. Measured results and their scope are
recorded below. All database/worker checks use isolated infrastructure and all
browser mutations use isolated records created/restored through the API.

| Fixture | Seeded data | Required scenarios |
| --- | --- | --- |
| Tasks | Accessible and denied projects, 26+ matching tasks, assigned/unassigned/due/linked tasks, two saved filters, configurable task form | Search/page/filter by URL and browser preference; keyboard open; invalid BQL; custom form layout/fields/defaults/project choice and failed submit; edit each supported detail field; terminal resolution; watch, mention/comment, link, worklog add/delete; stale version; indexing and notification execution |
| Activity | 31+ notifications, read/unread states, per-event preferences and a notification scheme | Page Unread/All, mark one/all, follow resolved task/spec links, toggle/save/reload preferences, distinguish scheme/default values; API failure does not appear as an empty inbox |
| Specifications | Parent/child specs, task- and spec-parented requirements, comments/history, typed contexts and a linked Git repository | Search/create via ordinary profile; UUID/key open; owner/assignee/priority and transition/resolution; context add/remove and authorized name resolution; requirement create/delete and linked-task description; generation request/history/task links; Git push and failure; document title synchronization |
| Documents | Existing nonempty spec/requirement/task documents with templates, custom attributes, containers, mentions, images, relationships and two collaborating sessions | Initial/empty seed; no lossy round trip; normalization before ready; preserved server dirty state; save against inspected version; concurrent edit during save remains dirty; failed save/reconnect; two editors; route/unmount cleanup; focus/selection/undo/scroll through surrounding actions |
| Planning | Two portfolios/programs, active and archived projects, components/labels/versions/milestones, a project dependency and real Git repository associations | Active/All/Archived, create/edit/archive/unarchive/delete; move and return; dependencies add/remove; project tabs; versions/components/labels; repositories and generated deploy config preview; analytics app/service association; milestone close; denied entity and stale action |
| Boards | Project and multi-project boards, columns with multiple status mappings/WIP, allowed and forbidden transitions, backlog, active/future sprints | Select board/project/sprint, quick add; edit/order/remove columns; pointer and keyboard move only through allowed transitions; failed move recovery; retained focus/scroll; sprint creation/commitment/start/add/search/close and unfinished-task target |
| Delivery | Program release bundling multiple projects/versions, staged environments, gates, failed/active/completed Git runs, declared/actual artifacts | Create/edit/bundle/unbundle/version lifecycle; readiness/blockers; environment type and permission settings; deploy/promote/approve/reject/cancel/rerun/patch/rollback; source readiness and Edit/Execute distinction; artifacts/completion/publication; notes generation/edit; correct Atlas links; stale/rejected action |
| Telemetry | Linked and unlinked project applications/services, store data, multiple application versions and an isolated subscription publisher | Real empty versus loading versus query error; app/service/store health; per-project map colors; source/version selection; live-session update/reconnect/TTL/unmount; no mock operational values in production views |
| Administration | Ordinary and admin principals, all current lookup types, workflows/states/transitions/schemes, automation logs, SLA calendars and entity groups | Every exposed create/edit/delete/close/enable/grant/revoke operation; order and defaults; invalid structured JSON draft; workflow detail and scheme associations; automation execution history; SLA durations/calendar; repeat/reload and expected-version conflicts |

The shared acceptance matrix applies to every implemented route: anonymous redirect and login return,
direct deep links, empty/loading/error/readonly states, keyboard/focus access, light/dark appearance,
1920/1024/768/390px geometry, no unintended body overflow, no console errors and no leaked subscriptions.
Shared components additionally run in the Design Library catalog and independent consumer; test
Git, Artifacts and System consumers after changing shared behavior.

Atlas qualification must also prove combined and split API/UI/worker processes, API enqueue with
consumers disabled, real indexing/notification/synchronization completion under the initiating
principal, migration/installer reruns and packaged JVM/native assets outside the checkout.

## Explicit exclusions

`pages/analytics/workops.vue` is a placeholder with unpopulated KPIs/charts; a new dashboard is outside
this port. Backend-only OKR, roadmap, dashboard or import features are not silently added. Retain
the current auth model and offset pagination. This migration does not create a standalone WorkOps
server, change pipeline execution, move WorkOps ownership out of the main server, or authorize a
production deployment or Studio retirement.

## Confirmed scope decisions

- WorkOps BML serves collaboration; Content owns authenticated CRDT persistence. Shared editor
  lifecycle stays in the Design Library, and UI instances use the same configured pub/sub backend.
- Document mentions show their referenced content, collection or profile inside Atlas and retain
  the originating task/tab context.
- The Content media picker and upload flow are implemented. Reusable selection/upload presentation
  belongs in the Design Library; WorkOps supplies authorized domain operations.
- Workflow and scheme headers support name/description/default CRUD. Existing state/transition
  graphs and task-type overrides are preserved; state/transition details remain read-only.
- Notification API behavior is unchanged. Supported opt-outs, unknown event keys with supported
  channels and muted identities persist; the existing API still discards unknown channel values.
- Platform approvals use the new Atlas view with the existing read/decision authorization.

## Task, specification and document source comparison

The table records the compared Studio behavior and implemented ownership. Actual fixtures cover
both Atlas layouts, with runtime scope recorded in the validation evidence below.

| Source behavior | Atlas implementation and ownership | Behavioral evidence |
| --- | --- | --- |
| Task search/create, BQL diagnostics, preferences, shortcuts and configured task form | WorkOps search/create adapters compose shared query, schema form and profile controls; stored form/provider configuration and caller credentials remain authoritative | Task search/create and configured-provider fixtures cover ordinary/private identities, invalid drafts, offsets, project switching, real custom fields and stale forms |
| Task detail summary/description, workflow/resolution, assignment/priority/sprint, dates/estimates, watches, comments/mentions, work log, links and affected projects | WorkOps task model and typed actions retain versions and drafts; shared controls own presentation. Stored HTML precedes Markdown, the optional Content document stays independent, start dates remain read-only, and past UTC due-date days retain Studio's overdue cue | Actual task-detail, description and history fixtures pass against actual Atlas hosts, including two-editor persistence, stale writes, permission changes, retained HTML and stored history |
| Saved filters and Inbox | WorkOps owns caller queries, notification events/preferences and navigation. Shared editors/forms/tables/dialogs own interaction | Actual filter and Inbox fixtures cover caller isolation, validation, read operations, paging and preferences; notification behavior remains the existing API behavior |
| Specification search/create, hierarchy, ownership, requirements, comments/history, contexts, generations and Git sync | WorkOps models and components compose shared documents, profiles, controls and offset tables. Existing aggregate operations retain rich bodies and independent linked-task descriptions | Specification search/detail/delivery and shared requirement fixtures cover saved Content versions, real Git writes, exact identities, private references, stale/denied drafts and both parent kinds |
| Requirement UUID/key routes, Content name/body, priority/assignee and linked-task lifecycle | The requirement adapter keeps Content permission separate from WorkOps; names and history use readable product labels | Requirement fixtures cover both parent kinds, ordinary/private identities, independent body/description, transitions/resolutions, comments/history and save/reopen |
| Studio editor schema, toolbar/slash commands, containers, dragging, code/Mermaid, links, mentions, table widths and images | Design Library owns the reusable schema/editor/lifecycle and reference/media controls. WorkOps provides template options, authorized lookups, same-origin uploads and save actions | Library, catalog and independent consumer fixtures plus actual Atlas task/specification/requirement documents cover normalization, JSON round trips, authoring, references, uploads and readonly presentation |
| Collaboration, title/version, dirty/save revision, reconnect, access changes and editor retention | WorkOps serves the existing collaboration protocol and caller session; Content owns binary persistence and extracted entity links. Shared editor owns selection, history, lifecycle and save acknowledgement | Actual Content/Studio-provider/two-listener fixtures cover peer edits, large updates, current-token access, reconnect, persisted revision, normalization before readiness and drafts across failures/parent updates |

Studio's container node can display configured tool buttons, but the task, specification and
requirement metadata queries select only container id/name/description/type/filters. They do not
select tools or tool configuration, and the node does not load them independently. No reachable
WorkOps tool-execution flow was found in these source paths. Metadata attribute controls outside
these WorkOps pages remain outside this migration. The shared editor rejects unsupported stored
nodes, marks or attributes before parsing can discard them and retains the original form value.


## Validation evidence

Results apply to the measured source and named scope. Passing test counts do not replace coverage.
All passing process/browser results below have zero failures, errors and skips.

| Area | Measured evidence | Scope |
| --- | --- | --- |
| WorkOps backend | 1,303 cases and unchanged coverage gates pass | Full owning backend suite, including real PostgreSQL behavior |
| WorkOps UI | 339 cases, strict client checking, 15 HTTP cases and assembled schema check pass | Models, compiled routes/actions, authentication and imported UI operations |
| Shared controls | 23 Kotlin and 119 client cases, strict checking and export validation pass | Catalog and independent consumer cover shared forms, documents and other promoted controls |
| Library packaging | 299 runtime cases and TypeScript pass; 47 focused bundler/dependency cases pass | A before-failing published-library regression proves unused lazy CommonJS loaders are removed while called modules initialize once and authored effects remain |
| Actual configured forms | Both layouts pass, 43.692s/49.353s | Real API-script adapters through a dedicated JVM engine fixture |
| Current consumer regression | Git 113, Artifacts 13, System 98 and WorkOps HTTP 15 pass | Production assets, routes, navigation, schema and Markdown contracts |
| Git browsers | 220 screen cases at 1920/1440/1024/390, four native Clone fallbacks and all 22 editor grammars pass | Repository/JSON/file workspace, palettes, drafts, focus, logs and triggers; no overflow or browser exceptions |
| Previous staged JVM hosts | Both layouts pass all 30 compatible suites, 535.131s/550.014s; later task-date checks pass, 90.463s/74.873s | Before the message-provider/configuration correction: complete distributions run outside the checkout with unchanged 733-file manifests; a later six-suite refresh passes in 81.675s/90.162s |
| Refreshed staged JVM hosts | Five cases pass with zero failures/errors/skips | Message delivery 28.477s/38.198s, real routing rollback 101.081s, six affected workflow suites 91.998s/94.418s. Complete API/UI distributions run outside the checkout; all 736 manifest files remain unchanged |
| Native hosts before ImageIO packaging correction | Five cases pass with zero failures/errors/skips | Both 30-suite layouts, 550.525s/548.941s; installed-pipeline SMTP, 38.750s/37.235s; actual routing/document rollback, 117.915s. `-O3` backend and UI execute outside the checkout; image hashes remain unchanged. These cases exclude the new image-job assertion |
| Current JVM media/runtime refresh | Seven cases pass with zero failures/errors/skips | Media 33.073s/22.787s, SMTP 53.449s/54.561s, actual routing rollback 183.466s and affected task/document workflow 82.951s/82.464s. Separate runtime directories preserve each executable's native libraries; workers fetch signed content through the API |
| Native date presentation | 32 description captures per host, including overdue views in both palettes/four widths | Past/future/absent dates and unchanged stored fields; phone-light and desktop-dark captures reviewed for each host |
| Gateway and deployment preparation | 40 focused CLI, 19 Compose installer and two local Compose cases pass | Real Caddy/nginx combined/split HTTP and collaboration text/binary WebSocket forwarding; every imported page, caller/URI/cookie/upload preservation, disabled surfaces and Git namespaces. Standalone packaged configuration resolves Swarm NATS/Redis; all 16 Compose topologies render with the shared NATS settings where UI is separate |
| Isolated routing and document rollback | Current staged case passes in 101.081s; native Atlas case passes in 117.915s, with screenshots and API readback | Real production-built Studio and staged platform API remain separate from Atlas API/UI. Caddy switches Studio → Atlas → saved Studio configuration while preserving identity, document edits and four task-index/in-app notification completions. The 3,798-file Studio and 604-file platform manifests remain unchanged |

Git's CI editor closure measures 369,734 gzip bytes under its unchanged 380,000-byte static JS/CSS
budget; source/edit closures remain 200,382. Header and Clone assertions select the intended
controls with their original strict bounds. The Git path/action toolbar uses the existing small
control size consistently; shared defaults remain unchanged.

The corrected native API build retains release optimization, takes 28m50s and produces
1,142,131,392 bytes. The unchanged 426,233,352-byte UI image remains up-to-date; its earlier build took
5m3s. Both use a 48 GiB compiler heap, four threads and `-J-XX:NewRatio=9`. Compiler flags and binary
digests were verified separately and image hashes remain unchanged after runtime. These are local
ARM64 native results. Deployment origins and target release artifacts are future rollout inputs
and have not been qualified by these local checks.

Native scope is explicitly selected: task-create, task-detail, task-labels, requirements,
specifications, specification-detail, specification-delivery, planning, milestones, projects, boards,
sprints, versions, environments, global-catalogs, automation, workflows, sla, components, permissions,
notifications, approvals, task-descriptions, task-history, release-build, release-ci,
release-environments, task-search, filters and inbox. The release-content, release-health,
release-store and release-live fixtures replace external analytics/model/vendor execution through
separate JVM entry points. Their successful JVM behavior and real broker-backed live-session
publication/relay are separate evidence; they are not counted as native or staged production-service
execution. Configured API-script execution likewise uses its dedicated JVM engine fixture.

### Coverage and recorded findings

| Module | Lines covered / missed | Line coverage | Branches covered / missed | Branch coverage | Result |
| --- | --- | --- | --- | --- | --- |
| WorkOps | 11,590 / 88 | 99.2464% | 6,464 / 198 | 97.0279% | Existing 99% line / 97% branch gates pass |
| Content | 11,374 / 973 | 92.1195% | 6,252 / 2,257 | 73.4751% | Owning gates pass |
| Core | 8,097 / 342 | 95.9474% | 4,911 / 541 | 90.0770% | Existing 95% line / 90% branch gates pass |
| WorkOps UI Kotlin aggregate | 5,451 / 66 | 98.8037% | 10,401 / 1,147 | 90.0675% | New 90% branch gate passes |

The UI aggregate merges 527 passing module tests, 15 composed HTTP-host tests and 12 JVM process
reports from successful browser/action cases. All 34 workflow suites pass in both layouts
(619.250s/643.282s); the focused task workflow also passes concurrent API deletion, refused-deletion
feedback and reload (75.852s/87.196s). The focused process reports were regenerated after detecting
one truncated shutdown save; all 12 current reports are complete and unlocked, and merge without
coverage warnings. Empty or unfinished reports fail validation. Failed-run reports are kept out
of the aggregate. A refused
work-log deletion now reports failure instead of proceeding through the success path. Independent
drafts, unloaded-editor write guards, selected-tab pagination, configured choices and CI waiting
requirements have additional regressions. WorkOps's redundant descendant styling of shared buttons
was removed; group layout remains application-owned.

The report excludes only the generated BML/GraphQL packages. Model constructors and serialization
remain included. Explicit serialization contracts cover populated and omitted defaults, required
identities and independent drafts; action/read tests cover missing selections, failed providers,
reference fallbacks, pagination, permissions and malformed form values. BML's state encoder uses
`encodeDefaults = true`; alternative serialization paths remain in the raw report. The new
aggregate gate enforces 90% branches without changing production UI bytecode, the 11,548-branch
denominator, report filters or the existing owning gates. These Kotlin totals do not measure TypeScript or generated
BML rendering, which have separate behavioral checks. Earlier native results below retain their
original artifact scope; the current UI correction was qualified on JVM/browser hosts.

Core's controlled original-code baseline is 4,795 covered / 653 missed branches, 88.0140%.
Additional behavior tests raise the migration's Core result from 88.0227% to 90.0770%, with the
same 5,452 branches in scope. The full run passes 1,991 Core tests, two real-socket transport soaks
and both existing coverage gates in 6m3s. Tests cover request and WebSocket cleanup, upload buffer
handoffs, shared connection and lock ownership, remote-cache reply failures, authentication,
installer selection, configuration, watchdog lifecycle and management probes. Coverage filters and
thresholds are unchanged. The Core runtime jar still exactly matches the qualified native build.

Run the measured checks from the workspace root:

```bash
./gradlew :bosca-core:core:test :bosca-core:core:nettyTransportSoakTest :bosca-core:core:koverXmlReport :bosca-core:core:koverVerify --no-build-cache
```

The refreshed dependency audit exits nonzero with 45 affected package entries: two high, 40 moderate
and three low, across seven advisory IDs. All seven were present in the previous recorded baseline;
there are no new advisory IDs and the prior AJV advisory is no longer reported. These findings remain
tracked separately; this migration does not claim a clean dependency audit.

The isolated rehearsal exposed a message-pipeline composition gap. Both combined and separate
workers reproduced the missing `DeviceService` failure in the installed WorkOps email pipeline.
Atlas now registers the existing device-service providers and their migrations and supplies the
same mailer configuration contract as the platform server. Two actual JVM process cases pass
through the installed WorkOps pipeline to a real SMTP receiver, including sender, recipient,
payload identity, action URL and in-app delivery. The unchanged BML rendering service is represented
by its HTTP contract in these cases. Fresh device migrations and the unchanged API namespace guard
pass, alongside three Swarm configuration cases and 20 Compose installer cases. Matched staged
and release-optimized native delivery/routing cases pass. All five bootstrap search retries in the
fresh native routing rehearsal later complete.

Broad workflow fixtures assert in-app delivery without installing an email pipeline; their email
outbox retries do not qualify SMTP. Dedicated SMTP cases use the installed platform pipeline and
verify actual delivery. Normal platform installers also seed a Recommendations pipeline whose node
is outside Atlas's selected domain composition; the existing pipeline service logs/skips that record.
The fixtures do not run the analytics ingestion sidecar or qualify every platform bootstrap job.

Linux ARM64 native media qualification now passes explicit job and dimension assertions. The earlier
macOS native worker failed with `UnsatisfiedLinkError: Can't load library: awt` at
`ImageServiceImpl.getDimensions`, then retried with `NoClassDefFoundError` for `ImageIO`, despite
passing original-file and picker assertions. The new regression measures that background work.

The new actual-process media regression waits for image jobs and checks stored dimensions alongside
original-byte downloads. PNG, JPEG and GIF persist `32x24` in both JVM layouts; SVG jobs complete
without inventing an unsupported dimension value. The initial split fixture's health-only download
URL returned 404; pointing its storage URL at the API makes both cases pass. No Content persistence
or parsing implementation changed.

An isolated Linux ARM64 GraalVM 25.0.3 probe reproduces missing ImageIO JNI metadata and then passes
all three raster formats with traced metadata. The old Atlas Dockerfile reproduces missing AWT
libraries; retaining the complete generated runtime passes in the same Debian base without extra
OS packages. Atlas now includes the traced metadata, headless native configuration, complete
backend/UI native archives and matching Docker copies. Actual optimized Linux ARM64 builds pass:
backend 41m08s with peak RSS 51.40 GB and UI 10m42s with peak RSS 13.93 GB. The production Dockerfile
images retain the backend's 12 generated libraries and the UI's management library. Both actual
native media layouts pass with zero failures/errors/skips: split 37.682s and combined 18.176s.
PNG/JPEG/GIF persist `32x24`, SVG jobs complete without an unsupported size, every matching image
job completes, and original bytes remain exact. Runtime hashes remain unchanged. The fixture uses
empty rendition sizes, so external image-processor execution is outside this measured scope.
GraalVM 25 macOS AWT support is not claimed. Five further Linux native cases pass with zero
failures/errors/skips: both 30-suite workflow/collaboration layouts (597.048s/606.012s), real installed
SMTP in both layouts (29.662s/34.322s), and actual Studio routing/document rollback (110.615s).
Together with the two media cases, all seven native acceptance cases pass against the same verified
production images. All 15 native process image records, the five protected notification API files
and the retained platform distribution hashes remain exact. Each workflow host has 32 fresh
description captures; phone-light and desktop-dark overdue views and the three routing screenshots
were reviewed. Routing evidence retains the same identity and document, including Atlas edits read
back after restoring Studio.

The broad workflow fixtures leave the email pipeline uninstalled and qualify in-app delivery;
dedicated SMTP cases install and verify the real pipeline. Analytics ingestion remains outside the
fixture. Eight distributed-lock release timeout errors occur after shutdown begins; all process
termination assertions pass. These boundaries remain recorded without claiming clean runtime logs
or complete platform background processing.

The implementation includes portable destinations, gateway configuration and an isolated routing
and rollback rehearsal. The operational checklist in WorkOps records deployment origins, release
artifacts and target ingress/navigation changes for a future rollout. Those inputs and deployment
approval do not block implementation completion. Recorded dependency advisories remain documented
separately. Studio routes/navigation and other Studio consumers remain available; no deployment,
Studio retirement or database downgrade has been performed.
