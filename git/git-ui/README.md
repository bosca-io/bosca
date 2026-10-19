# Git UI

BML UI library hosted by `:git:git-ui-server`. This project owns the pages, generated GraphQL operations and packaged browser assets; the host module owns its HTTP routes and proxies. `git-server` starts the UI and API listeners on different ports in one process.

The library provides authentication, repository browsing and writes, CodeMirror editing, commit-pinned Markdown assets, search/compare/statuses, pull requests and reviews, CI runs and live logs, repository administration, agents and GitHub synchronization. Repository lists default to Active. Owner choices include the profile slug or ID so duplicate names remain distinguishable. The shell and controls follow Studio’s Git subsystem: its glass layout tokens and ambient background, sidebar, page header with full-width tabs, glass tables, section cards, badges and status pills. Dialogs, menus and popovers keep Studio’s opaque base surfaces. Geist and Geist Mono are packaged and served from the Git origin. Generic controls and Git components are kept separate. Agent configuration, foundational controls, icons, icon actions, tooltips, clipboard controls, popovers, dialogs and selection/search controls use the compiled Bosca Design Library. The remaining controls are local.

Repository Pull requests, Pipelines, Statuses, Secrets and Settings stay inside the repository tabs. Global pipelines are grouped by repository for the selected owner. Manual runs open a ref/input dialog; ref changes load their definitions automatically through a BML server action. Pull-request creation includes repository and branch selection and retains the draft when those selections change. App settings collapse during browsing. Each hand-written top-level Kotlin type has its own file.

Pull requests use Studio’s lifecycle colours and layout. The overview shows the description, recent reviews, merge conflicts and status checks in the main column, with Details, Assignees, Dependencies and Actions cards alongside. Actions choose the merge method and open a confirmation with the merge plan; the dialog reopens for each new plan. Creation groups target and source choices in a responsive dialog. Auxiliary actions use tooltipped icons; merge, close, reopen and review actions have explicit labels. Diff comments open beside the selected line, with compact keyboard-accessible actions. Files Changed and Compare show each change with three lines of context; gutter expanders reveal 20 hidden lines up or down (or a small gap at once), and file actions show or hide every unchanged line. Revealed lines accept comments, and current discussions on unchanged lines reopen beside their code. File headers stay visible while scrolling and can copy the full path; files collapse individually or together and stay collapsed across updates. The changed-file tree keeps names on one line with horizontal scrolling, highlights the file in view and resizes from the visible divider or the keyboard, remembering its width; a Top control returns from deep in the diff. Drafts must be ready before review submission. The API retains merge requirements and all authorization checks.

The **Commits** tab pages through the PR's commits, newest first. Open a commit to see its own changes in **Files Changed**, or choose **All changes** to return to the combined diff. Commit links retain their SHA and list page in the URL. File links and expanded context use that commit. Line discussions remain attached to the combined PR comparison and appear separately below a commit diff; add line comments from All changes. Merged PRs use the recorded merge result and original target, so history survives branch deletion. A squash merge exposes its single resulting commit. Deploy the API's `GitPullRequest.commits` field and optional `diff(commitSha:)` argument with or before this UI.

Pull-request detail pages resolve `/pulls/<id>` directly. Repository and request
number come from the authorized API result; URL query parameters are not needed
to identify the request. The optional `view` parameter selects its detail tab.
Overview and Reviews do not compute or serialize the diff; **View changes** opens
Files Changed when needed. PR pages resolve only their source and target branch
heads, without calculating ahead/behind history for every branch. Dependency
choices and PR lists omit unused mergeability checks; the current PR and its merge
confirmation retain their checks. Dependency choices and status checks load only
on Overview.

The create and edit dialogs include **Delete source branch after merge**. Creation starts with the target repository's default, and editing saves the PR's own choice. Deploy the API and registered `V50__pull_request_branch_cleanup.sql` migration before the updated UI.

The Git server hosts the GraphQL operations used by this application, including authentication and profile identity. BML loaders and server actions use its HTTP API. Browser authentication and subscriptions use same-origin proxies on the UI server; the API validates credentials and permissions. Studio and the main server composition are unchanged. Full replacement acceptance, backend separation and cutover are tracked in the specifications, with measured results in [parity validation](../../specs/git/parity-validation.md).

Independent page data requests run concurrently. Repository tabs reuse their parent’s metadata, and profile and multi-repository pipeline lookups run in groups of at most four. History-based repository statistics load in a private deferred island with retry support. Data pages restore browser tokens without fetching the same principal and profiles again; live-log reconnects use the same token-only path. Pipeline catalogs initially request one run; repository views keep its job indicators and load older runs and paginated history on demand. Selected-repository pages skip the unused owner repository listing. Agent detail and polling select one authorized agent. Backend statistics reuse an immutable history summary while reading ref counts and pack size fresh. Manual-run islands carry only their selected pipeline and inputs. The repository-wide [GraphQL page audit](../../bml/docs/graphql-page-audit.json) records every BML route, its loading strategy and remaining dependencies.

Pages own routes and load their data, then compose local BML components. `src/main/bml/components/ui/` retains the local layout, navigation, feedback and editor adapters that still await library adoption or cleanup. `components/repositories/` and `components/auth/` own Git views and authentication behavior. Global document and theme CSS stays in `src/main/client/git-ui.css`. The module declares the Bosca Design Library once in `src/main/bml/imports.bml`, without an alias. Agent settings use its unprefixed `json-editor` for runner profiles and alert sinks. Existing native tags that collide with library exports use `html:` explicitly. Git owns configuration validation, actions, navigation and the editor palette in its own CSS. Buttons, inputs, fields, cards, switches, alerts, badges, spinners, loading states, icons, icon buttons, icon links, tooltips, copy controls, copy fields, popovers, dialogs, confirmation dialogs, selects, search inputs and segmented controls use the short library exports throughout pages and local components. Controls that forward additional attributes use the compiler-provided `{...attrs}`. Migrated component copies and the old clipboard, popover, tooltip, dialog and select helpers have been removed. Repository menus and discussion links compose the library tooltip; Git retains its menu disclosure/navigation behavior. The owner picker marks its application-owned All choice with `data-persistent`; async search types come from the library’s public `@bosca/bml-design/select` declaration. Select and dynamic-option styles are library-owned; Git retains its expanded border color through `--select-active-border`. The unused local checkbox component was removed. The remaining `ui-*` controls and unused JSON adapter await migration or cleanup.

Search follows Studio's query-first layout with compact Content/Paths, repository and branch/ref controls. Typing debounces a read-only BML action; Enter submits immediately, and the query and caret survive updates. Criteria persist in the URL. Content search uses indexed branches; Paths accepts tags and commits and pins file/folder links to the resolved commit. Results show highlighted safe snippets or path metadata; line links open Markdown Source. Search shows API and transport failures and permits retry while retaining the query.

The Code tab follows Studio: statistics tiles, a path bar with file actions, a file list, the README and the repository description. Files open in a file card with Rename, Delete and Edit; Markdown files offer Preview and Source. File rows are native links that cover the row. Browser checks open README with a real pointer click and nested files with Enter. Clone controls use a selectable URL and inline copy icon, show stable check-icon feedback, and offer a manual-copy fallback when clipboard access fails. The popover follows its trigger and fits the viewport.

The repository's Tags tab offers **New Tag** to editors of active repositories with commits. Enter a tag name, a target branch/tag/commit SHA, and an optional annotation. Creation uses the current profile's author metadata and returns to the Tags tab. Existing tags are not replaced, and failures retain the draft. Tag lists and searchable dropdowns load 25 tags per page, newest first by Git tagger date. Lightweight tags use their target commit’s committer date; undated tags appear last and names break timestamp ties. JGit refs and objects remain authoritative. Each request enumerates current refs, retains the requested offset/limit window while sorting, and constructs tag results only for that page. A bounded cache of immutable object dates reduces parsing on subsequent requests; a cold search parses only matching objects. Deploy the API’s tag pagination arguments and exact tag lookup with or before this UI.

The **Branches** tab requests ahead/behind counts and shows each branch's nonzero ahead (`+N`) and behind (`-N`) commit counts relative to the default branch, even when another ref is selected. Other repository tabs and ref selectors use `includeAheadBehind: false` to avoid branch history comparisons. Deploy the API's optional branch argument with or before this UI. API callers that omit it continue receiving ahead/behind counts.

Client imports use the project alias, for example `import { dismissibleDisclosure } from "@client/disclosure"`. `tsconfig.json` maps `@client/*` to `src/main/client/*` for type checking and esbuild, including generated BML scripts. The bundle task tracks that configuration so alias changes rebuild the assets.

Client behavior uses `<script client scoped>` at component scope, outside islands. BML initializes each component on its DOM root and manages cleanup. Islands retain the live server-state views used by declarative actions; authentication and other client-only controls use their component roots directly. `src/main/client/auth.ts` shares SDK operations and return navigation.

Pipeline run status refreshes every two seconds while active. Expanded step logs stream through the same-origin WebSocket subscription and retain their viewer across run refreshes. History loads when a panel opens, with backfill on subscription connection, resume and completion, or through the log controls. Run refreshes skip history queries for the retained panel; a newly expanded failed step still loads its history.

From the workspace root:

```bash
./gradlew :git:git-ui:check :git:git-ui-server:test :git:git-server:test
./gradlew :git:git-server:run
```

Git UI defaults to `http://localhost:8093` and proxies over loopback HTTP to the API’s bound port (8091 by default). Both listeners run in the Git server process. Browser requests use `/graphql`; subscriptions use `/_git/subscriptions`, relayed to the API’s `/graphqlws`. BML pages, actions, fragments and contracts use the existing HTTP client. Configure auth app origins and provider callbacks as described in the [UI server README](../git-ui-server/README.md). The UI host needs no database or domain providers; the API retains its infrastructure requirements.

On a fresh database, the existing `security-initial` installer bootstraps the administrator and service account. Use its logged initial password or configured initialization password, following the platform's normal installation flow. Existing group and repository/profile permissions still apply; signup does not grant repository-creation permissions.

File writes resolve the displayed and committed author from the current primary profile, then its slug, using Studio's existing neutral fallbacks (`Studio User` and `studio@bosca.io`) for missing fields. The initial administrator can create a README without adding a profile email. File writes retain repository permissions, archive checks and branch protection.

Public authentication routes are `/login`, `/auth/signup`, `/auth/forgot`, `/auth/reset-password`, `/auth/verify` and `/auth/link/confirm`. Email landing routes match the platform's existing email links. Return destinations survive signup, verification, recovery and OAuth; account-link controls use the proof methods offered by the backend. Configure the Git UI origin in the Git server authentication app-origin allowlist so emailed links return to this application. Google starts through BML’s existing OAuth proxy; the configured provider callback is on Git server, and the browser SDK finishes on the UI origin.

`check` includes client flow tests, strict TypeScript checking, schema contract checks, and real HTTP/WebSocket tests of compiled BML pages, actions, assets and proxies. UI tests use an isolated upstream fixture. Git server integration tests start one production JVM process with both UI and API listeners against isolated PostgreSQL and NATS. They exercise authentication, pages, actions, raw files, subscriptions, custom cookie prefixes and GraphQL credential isolation. These tests do not send emails or complete an external Google exchange.

The client schema contains the fields used by the UI. Its contract test compares fields, arguments, inputs and enums with the owning domain schemas; update the subset alongside new operations. Local npm file dependencies use the workspace BML runtime and framework-free auth client. Gradle builds the production page bundles into the library jar so the UI server can serve them in JVM and native packages.

The production integration test can also exercise the combined native executable:

```bash
./gradlew :git:git-server:nativeCompile --build-args=-Ob --no-configuration-cache
GIT_SERVER_TEST_BINARY="$PWD/git/git-server/build/native/nativeCompile/bosca-git-server" \
  ./gradlew :git:git-server:test --tests 'bosca.git.server.GitServerIntegrationTest' --rerun --no-build-cache --no-configuration-cache
```

It creates isolated PostgreSQL/NATS resources and shuts down both listeners in that process. `-Ob` is quick optimization; release optimization is a separate check.

See the [application specification](../../specs/git/bml-git-application-spec.md) and [Studio inventory](../../specs/git/studio-ui-inventory.md). Studio and the main server are unchanged in this step.

Browser checks use a separate Chrome profile and isolated API records. Run the fixture in one terminal:

```bash
./gradlew :git:git-ui:runBrowserFixture
```

Use its printed URL in another terminal (Node 22 or newer and Chrome are required):

```bash
cd git/git-ui
GIT_UI_BASE_URL=http://localhost:<printed-port> node src/test/browser/run.mjs
```

`./gradlew :git:git-ui:probePageLoading` runs the 38 page/view request matrix against an isolated HTTP fixture at zero and 50 ms upstream latency, including search actions and repeated concurrent page bursts. It reports initial-HTML timing, operation counts and connection reuse.

`GIT_UI_CHROME_BINARY` selects another Chrome executable. `GIT_UI_ARTIFACT_DIR` selects the screenshot/report directory. The runner closes its own browser; stop the fixture after the checks. The screen matrix checks 55 route/view combinations at 1920, 1440, 1024 and 390px (220 cases), including populated and empty pull-request lists, empty repositories, settings and filters. It measures full-width panels and tab dividers, Markdown toolbar/content alignment, consistent header spacing and select chevron geometry, and checks menu rotation and native select presentation with JavaScript disabled. Held-request checks inspect file, search, async-choice and action progress, reduced motion, failure/retry visibility and stable button dimensions/accessibility. It then verifies live-log behavior, polling with an open job dialog and retained reason, and agent drafts with both JSON editors mounted.

The PR interaction module also checks Open, Draft, Merged, Closed and conflicting requests at all four widths, including badge colors, title spacing, grouped checks, merge-method confirmation, a real line-comment click, keyboard-focus tooltips and cancellation beside the diff. To run only that module, set `GIT_UI_BROWSER_TESTS=pulls.mjs`; comma-separated selections may include `auth.mjs`, `repository.mjs`, `statistics.mjs`, `pipelines.mjs`, `pulls.mjs`, `review.mjs`, `search.mjs`, `tags.mjs` and `screens.mjs`. The review checks cover hidden-line expanders, discussion context, collapse persistence across updates, sticky file headers, the Top control, horizontal tree scrolling and pointer/keyboard resizing, at desktop and phone widths. The statistics checks hold and fail requests, retry them, and verify the loaded summary at desktop, tablet and phone widths. The focused pipeline checks exercise on-demand history and pagination at desktop and phone widths, manual runs, ref changes, live logs, reconnect and polling independently of the broad screen geometry matrix.

Search interaction checks cover debounce, latest-query coalescing, preserved focus/caret, URL restoration, mode/repository/ref changes, safe highlighted snippets, path metadata, visible API/transport failures, retry, clear, Enter submission and Markdown source-line navigation. Interaction checks also cover actual file-link clicks and keyboard navigation, clone URL selection, clipboard success/failure, copy feedback geometry, popover positioning/dismissal, icon tooltips on pointer hover and keyboard focus, Markdown view tabs, select keyboard navigation, async search recovery, webhook multi-selection and actual form submission, mapping creation/removal with readable identities, repository actions, pull-request merge plans and strategy changes, first-field/modal focus and dismissal, failed drafts, responsive tabs, compact command snippets, shared control heights and text/icon alignment, loaded design fonts, dropdown dimensions and stable menu width. Screenshots include each initial view plus open forms and populated/empty mapping states. These isolated checks do not establish real provider exchange, deployed repository data, native-server auth, or external synchronization acceptance.

The [BML component specification](../../specs/git/bml-ui-components-spec.md) records the Nuxt source references, reusable control contracts and remaining extraction gaps.

The API and UI changes ship together: deploy the API with or before the UI, which uses the new optional `id` selectors on `pipelines` and `pipelineAgents`.


The focused `json-editor.mjs` browser module checks the compiled library identity, independent
runner-profile and alert-sink values, exact invalid drafts, server validation and upstream rejection,
immediate submission while text changes are pending, local mode/focus retention, successful
configuration readback, consumer CSS overrides, native fallback forms and four viewport widths.
Its isolated API fixture stores submitted configurations in memory and returns them through the
ordinary configuration query. It never creates a VM or changes a real agent.
