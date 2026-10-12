# Git UI

BML UI library embedded in `:git:git-server`. The server owns the HTTP engine and configuration; this project owns the pages, generated GraphQL operations and packaged browser assets.

The library provides authentication, repository browsing and writes, CodeMirror editing, commit-pinned Markdown assets, search/compare/statuses, pull requests and reviews, CI runs and live logs, repository administration, agents and GitHub synchronization. Repository lists default to Active. Owner choices include the profile slug or ID so duplicate names remain distinguishable. The shell and controls use Studio’s design tokens, sidebar, compact headers and table treatment. Geist and Geist Mono are packaged and served from the Git origin. Local controls and Git components are kept separate for later extraction into a shared BML UI library.

Repository Pull requests, Pipelines, Statuses, Secrets and Settings stay inside the repository tabs. Global pipelines are grouped by repository for the selected owner. Manual runs open a ref/input dialog; ref changes load their definitions automatically through a BML server action. Pull-request creation includes repository and branch selection and retains the draft when those selections change. App settings collapse during browsing. Each hand-written top-level Kotlin type has its own file.

The Git server now hosts the GraphQL operations used by this application, including authentication and profile identity. BML loaders and server actions execute through that engine in process. Browser authentication and subscriptions use the same Git origin. Studio and the main server composition are unchanged. Full replacement acceptance, backend separation and cutover are tracked in the specifications, with measured results in [parity validation](../../specs/git/parity-validation.md).

Pages own routes and load their data, then compose local BML components. `src/main/bml/components/ui/` contains generic layout, cards, dialogs, rich selects, search inputs, segmented controls, sections, popovers, timestamps, form controls, tabs, switches, hover/focus tooltips, keyboard file trees, Lucide icon actions, badges, alerts and text viewing, with component-scoped styles. `components/repositories/` and `components/auth/` own Git views and authentication behavior. Global document and theme CSS stays in `src/main/client/git-ui.css`. These boundaries support later extraction of shared controls into a Bosca BML UI library; that library is deferred while Git functionality is developed.

Search follows Studio's query-first layout with compact Content/Paths, repository and branch/ref controls. Typing debounces a read-only BML action; Enter submits immediately, and the query and caret survive updates. Criteria persist in the URL. Content search uses indexed branches; Paths accepts tags and commits and pins file/folder links to the resolved commit. Results show highlighted safe snippets or path metadata; line links open Markdown Source. Search shows API and transport failures and permits retry while retaining the query.

File-tree rows keep native file links; only directories dispatch folder expansion. Browser checks open README with a real pointer click and nested files with Enter. Clone controls use a selectable URL and inline copy icon, show stable check-icon feedback, and offer a manual-copy fallback when clipboard access fails. The popover follows its trigger and fits the viewport.

Client imports use the project alias, for example `import { attachTooltip } from "@client/tooltip"`. `tsconfig.json` maps `@client/*` to `src/main/client/*` for type checking and esbuild, including generated BML scripts. The bundle task tracks that configuration so alias changes rebuild the assets.

Client behavior uses `<script client scoped>` at component scope, outside islands. BML initializes each component on its DOM root and manages cleanup. Islands retain the live server-state views used by declarative actions; authentication and other client-only controls use their component roots directly. `src/main/client/auth.ts` shares SDK operations and return navigation.

From the workspace root:

```bash
./gradlew :git:git-ui:check :git:git-server:test :git:git-server:installDist
./gradlew :git:git-server:run
```

Git server defaults to `http://localhost:8091` and uses its own GraphQL engine. No main API process or `BML_GRAPHQL_ENDPOINT` is needed. Browser requests use `/graphql`; subscriptions use `/_git/subscriptions`. BML pages, actions, fragments and contracts use the same engine directly, with the original request and verified credentials. `APP_URL`, `APP_ALLOWED_ORIGIN` and OAuth redirect/provider settings must match the Git application's public origin. For a custom browser cookie prefix, configure that prefix and Git host in `jwt.cookie-prefixes`; `BML_AUTH_COOKIE_PREFIX` selects the UI SDK cookie. PostgreSQL, NATS and the configured storage/search services remain required.

On a fresh database, the existing `security-initial` installer bootstraps the administrator and service account. Use its logged initial password or configured initialization password, following the platform's normal installation flow. Existing group and repository/profile permissions still apply; signup does not grant repository-creation permissions.

File writes resolve the displayed and committed author from the current primary profile, then its slug, using Studio's existing neutral fallbacks (`Studio User` and `studio@bosca.io`) for missing fields. The initial administrator can create a README without adding a profile email. File writes retain repository permissions, archive checks and branch protection.

Public authentication routes are `/login`, `/auth/signup`, `/auth/forgot`, `/auth/reset-password`, `/auth/verify` and `/auth/link/confirm`. Email landing routes match the platform's existing email links. Return destinations survive signup, verification, recovery and OAuth; account-link controls use the proof methods offered by the backend. Configure the Git UI origin in the Git server authentication app-origin allowlist so emailed links return to this application. Google uses the Git server's security routes and configured provider.

`check` includes client flow tests, strict TypeScript checking, schema contract checks, and real HTTP/WebSocket tests of compiled BML pages, actions, assets and proxies. UI tests use an isolated upstream fixture. Git-server integration tests additionally start the production JVM composition against isolated PostgreSQL and NATS, exercising local authentication, pages, actions and subscriptions. These tests do not send emails or complete an external Google exchange.

The client schema contains the fields used by the UI. Its contract test compares fields, arguments, inputs and enums with the owning domain schemas; update the subset alongside new operations. Local npm file dependencies use the workspace BML runtime and framework-free auth client. Gradle builds the production page bundles into the library jar so the Git server can serve them in JVM and native packages.

The production-composition integration test can also exercise the native executable:

```bash
./gradlew :git:git-server:nativeCompile --build-args=-Ob --no-configuration-cache
GIT_SERVER_TEST_BINARY="$PWD/git/git-server/build/native/nativeCompile/bosca-git-server" \
  ./gradlew :git:git-server:test --tests 'bosca.git.server.LocalGitGraphQLIntegrationTest' --rerun --no-build-cache --no-configuration-cache
```

It creates isolated PostgreSQL/NATS test resources and exits its own server process. `-Ob` is quick optimization; release optimization is a separate check.

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

`GIT_UI_CHROME_BINARY` selects another Chrome executable. `GIT_UI_ARTIFACT_DIR` selects the screenshot/report directory. The runner closes its own browser; stop the fixture after the checks. The screen matrix checks 55 route/view combinations at 1920, 1440, 1024 and 390px (220 cases), including populated and empty pull-request lists, empty repositories, settings and filters. It measures full-width panels and tab dividers, Markdown toolbar/content alignment, consistent header spacing and select chevron geometry, and checks menu rotation and native select presentation with JavaScript disabled. Held-request checks inspect file, search, async-choice and action progress, reduced motion, failure/retry visibility and stable button dimensions/accessibility. It then verifies live-log behavior, polling with an open job dialog and retained reason, and agent drafts with both JSON editors mounted.

Search interaction checks cover debounce, latest-query coalescing, preserved focus/caret, URL restoration, mode/repository/ref changes, safe highlighted snippets, path metadata, visible API/transport failures, retry, clear, Enter submission and Markdown source-line navigation. Interaction checks also cover actual file-link clicks and keyboard navigation, clone URL selection, clipboard success/failure, copy feedback geometry, popover positioning/dismissal, icon tooltips on pointer hover and keyboard focus, file-tree expansion/selection and keyboard navigation, Markdown view tabs, select keyboard navigation, async search recovery, webhook multi-selection and actual form submission, mapping creation/removal with readable identities, deferred file-tree expansion and failed-load retry, repository actions, pull-request merge plans and strategy changes, first-field/modal focus and dismissal, failed drafts, responsive tabs, compact command snippets, shared control heights and text/icon alignment, loaded design fonts, dropdown dimensions and stable menu width. Screenshots include each initial view plus open forms and populated/empty mapping states. These isolated checks do not establish real provider exchange, deployed repository data, native-server auth, or external synchronization acceptance.

The [BML component specification](../../specs/git/bml-ui-components-spec.md) records the Nuxt source references, reusable control contracts and remaining extraction gaps.
