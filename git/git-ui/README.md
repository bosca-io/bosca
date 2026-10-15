# Git UI

BML UI library hosted by `:git:git-ui-server`. This project owns the pages, generated GraphQL operations and packaged browser assets; the host module owns its HTTP routes and proxies. `git-server` starts the UI and API listeners on different ports in one process.

The library provides authentication, repository browsing and writes, CodeMirror editing, commit-pinned Markdown assets, search/compare/statuses, pull requests and reviews, CI runs and live logs, repository administration, agents and GitHub synchronization. Repository lists default to Active. Owner choices include the profile slug or ID so duplicate names remain distinguishable. The shell and controls follow Studio’s Git subsystem: its glass layout tokens and ambient background, sidebar, page header with full-width tabs, glass tables, section cards, badges and status pills. Dialogs, menus and popovers keep Studio’s opaque base surfaces. Geist and Geist Mono are packaged and served from the Git origin. Local controls and Git components are kept separate for later extraction into a shared BML UI library.

Repository Pull requests, Pipelines, Statuses, Secrets and Settings stay inside the repository tabs. Global pipelines are grouped by repository for the selected owner. Manual runs open a ref/input dialog; ref changes load their definitions automatically through a BML server action. Pull-request creation includes repository and branch selection and retains the draft when those selections change. App settings collapse during browsing. Each hand-written top-level Kotlin type has its own file.

Pull requests use Studio’s lifecycle colours and layout. The overview shows the description, recent reviews, merge conflicts and status checks in the main column, with Details, Assignees, Dependencies and Actions cards alongside. Actions choose the merge method and open a confirmation with the merge plan; the dialog reopens for each new plan. Creation groups target and source choices in a responsive dialog. Auxiliary actions use tooltipped icons; merge, close, reopen and review actions have explicit labels. Diff comments open beside the selected line, with compact keyboard-accessible actions. Drafts must be ready before review submission. The API retains merge requirements and all authorization checks.

Pull-request detail pages resolve `/pulls/<id>` directly. Repository and request
number come from the authorized API result; URL query parameters are not needed
to identify the request. The optional `view` parameter selects its detail tab.

The Git server hosts the GraphQL operations used by this application, including authentication and profile identity. BML loaders and server actions use its HTTP API. Browser authentication and subscriptions use same-origin proxies on the UI server; the API validates credentials and permissions. Studio and the main server composition are unchanged. Full replacement acceptance, backend separation and cutover are tracked in the specifications, with measured results in [parity validation](../../specs/git/parity-validation.md).

Independent page data requests run concurrently. Repository tabs reuse their parent’s metadata, and profile and multi-repository pipeline lookups run in groups of at most four. History-based repository statistics load in a private deferred island with retry support. Data pages restore browser tokens without fetching the same principal and profiles again; live-log reconnects use the same token-only path. Pipeline catalogs initially request one run; repository views keep its job indicators and load older runs and paginated history on demand. Selected-repository pages skip the unused owner repository listing. Agent detail and polling select one authorized agent. Backend statistics reuse an immutable history summary while reading ref counts and pack size fresh. Manual-run islands carry only their selected pipeline and inputs. The repository-wide [GraphQL page audit](../../bml/docs/graphql-page-audit.json) records every BML route, its loading strategy and remaining dependencies.

Pages own routes and load their data, then compose local BML components. `src/main/bml/components/ui/` contains generic layout, cards, dialogs, rich selects, search inputs, segmented controls, sections, popovers, timestamps, form controls, tabs, switches, hover/focus tooltips, keyboard file trees, Lucide icon actions, badges, alerts and text viewing, with component-scoped styles. `components/repositories/` and `components/auth/` own Git views and authentication behavior. Global document and theme CSS stays in `src/main/client/git-ui.css`. These boundaries support later extraction of shared controls into a Bosca BML UI library; that library is deferred while Git functionality is developed.

Search follows Studio's query-first layout with compact Content/Paths, repository and branch/ref controls. Typing debounces a read-only BML action; Enter submits immediately, and the query and caret survive updates. Criteria persist in the URL. Content search uses indexed branches; Paths accepts tags and commits and pins file/folder links to the resolved commit. Results show highlighted safe snippets or path metadata; line links open Markdown Source. Search shows API and transport failures and permits retry while retaining the query.

The Code tab follows Studio: statistics tiles, a path bar with file actions, a file list, the README and the repository description. Files open in a file card with Rename, Delete and Edit; Markdown files offer Preview and Source. File rows are native links that cover the row. Browser checks open README with a real pointer click and nested files with Enter. Clone controls use a selectable URL and inline copy icon, show stable check-icon feedback, and offer a manual-copy fallback when clipboard access fails. The popover follows its trigger and fits the viewport.

Client imports use the project alias, for example `import { attachTooltip } from "@client/tooltip"`. `tsconfig.json` maps `@client/*` to `src/main/client/*` for type checking and esbuild, including generated BML scripts. The bundle task tracks that configuration so alias changes rebuild the assets.

Client behavior uses `<script client scoped>` at component scope, outside islands. BML initializes each component on its DOM root and manages cleanup. Islands retain the live server-state views used by declarative actions; authentication and other client-only controls use their component roots directly. `src/main/client/auth.ts` shares SDK operations and return navigation.

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

The PR interaction module also checks Open, Draft, Merged, Closed and conflicting requests at all four widths, including badge colors, title spacing, grouped checks, merge-method confirmation, a real line-comment click, keyboard-focus tooltips and cancellation beside the diff. To run only that module, set `GIT_UI_BROWSER_TESTS=pulls.mjs`; comma-separated selections may include `auth.mjs`, `repository.mjs`, `statistics.mjs`, `pipelines.mjs`, `pulls.mjs`, `search.mjs` and `screens.mjs`. The statistics checks hold and fail requests, retry them, and verify the loaded summary at desktop, tablet and phone widths. The focused pipeline checks exercise on-demand history and pagination at desktop and phone widths, manual runs, ref changes, live logs, reconnect and polling independently of the broad screen geometry matrix.

Search interaction checks cover debounce, latest-query coalescing, preserved focus/caret, URL restoration, mode/repository/ref changes, safe highlighted snippets, path metadata, visible API/transport failures, retry, clear, Enter submission and Markdown source-line navigation. Interaction checks also cover actual file-link clicks and keyboard navigation, clone URL selection, clipboard success/failure, copy feedback geometry, popover positioning/dismissal, icon tooltips on pointer hover and keyboard focus, Markdown view tabs, select keyboard navigation, async search recovery, webhook multi-selection and actual form submission, mapping creation/removal with readable identities, repository actions, pull-request merge plans and strategy changes, first-field/modal focus and dismissal, failed drafts, responsive tabs, compact command snippets, shared control heights and text/icon alignment, loaded design fonts, dropdown dimensions and stable menu width. Screenshots include each initial view plus open forms and populated/empty mapping states. These isolated checks do not establish real provider exchange, deployed repository data, native-server auth, or external synchronization acceptance.

The [BML component specification](../../specs/git/bml-ui-components-spec.md) records the Nuxt source references, reusable control contracts and remaining extraction gaps.

The API and UI changes ship together: deploy the API with or before the UI, which uses the new optional `id` selectors on `pipelines` and `pipelineAgents`.
