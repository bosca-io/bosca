# Git BML parity acceptance

Acceptance record for the [application specification](bml-git-application-spec.md), against the [Studio source inventory](studio-ui-inventory.md). Updated 12 October 2026.

## Acceptance status

The `:git:git-ui` BML library is hosted by the `:git:git-ui-server` module on a second listener in the `git-server` process. Git server owns its GraphQL and security routes. BML loaders/actions/contracts/fragments use the existing HTTP client; browser GraphQL/OAuth and subscriptions use same-origin proxies to Git server. Raw files and CI artifacts are streamed through the UI origin. Local GraphQL execution and its core BML auth hooks have been removed. Studio and the main server composition remain unchanged in this stage. Historical validations below refer to the composition in place when they were run; they do not establish the current combined host’s acceptance.

**Full Studio replacement parity is not accepted.** Fixture checks establish the behaviors listed below. They do not establish the supplied deployed repository's image behavior, external authentication/email delivery, real GitHub synchronization, production CI subscriptions, or full external-service acceptance. These remain acceptance work, and the old surfaces cannot be retired on the strength of this record.

## Screen and behavior evidence

| Surface | Implemented behavior and measured checks | Evidence boundary |
| --- | --- | --- |
| Authentication | Studio's browser auth SDK; password/Google signup and sign-in, password feedback, verification/resend cooldown, recovery/reset, offered account-link proofs, OAuth callback, return destinations and sign-out. Desktop/mobile screenshots reviewed. Browser checks drive the real SDK and compiled BML islands. | Browser interaction uses isolated API fixtures. Combined JVM and native production-composition tests verify real local signup/login, identity queries, invalid-token rejection and sign-out cookies against isolated PostgreSQL/NATS. No external Google account, real emailed link, or deployed session was exercised. |
| Repository list and owners | Active by default; explicit Archived/All; deleted records excluded; create/import/lifecycle actions; owner URL and remembered selection; same-name results show slug or full ID. Browser measurements confirm unchanged picker width when opened. | Test API data; failed actions and retained posted fields checked through compiled actions. |
| Code workspace | CodeMirror viewing/editing with line numbers, language support, search/folding/wrapping, keyboard focus, full available height and zero inner radius. Empty and 500-line files measured at 1440 and 1024px: editor 591.25px inside a 638.25px surface; long content scrolls within CodeMirror. At 390px, the file surface is at least 360px and at least 240px of code is visible after scrolling into view. Dirty buffers and failed commits preserve content/message; successful commit navigates. | Browser edits use isolated test records. Real JGit writes and branch-protection enforcement are covered separately below. |
| Files and Markdown | Root/nested README, Markdown Preview/Source, pinned SHA image/document links, headings/fragments, spaces/Unicode, binary/oversized/LFS states, raster preview, missing-image fallback, clone copy. Browser image fetch and preserved prose verified; HTML is escaped and unsafe URL schemes rejected. | Browser image response is a fixture. A separate real Git transport test verifies nested raw PNG delivery with signed cookies. The reported private production repository remains uninspected. |
| Commits and refs | Offset-paged history, branch/tag selection/create/delete, annotated tag message, detached SHA browsing and unborn initial branch writes. | Compiled HTTP/actions and isolated upstream data; backend suite exercises actual Git operations. |
| Search, comparison and statuses | Repository/ref selection, content/path results and line navigation, diff rendering, commit/check states and safe HTTP/S status links. | Compiled route rendering/action checks and browser screen matrix. |
| Pull requests | Global/repository lists, Open default, explicit All and offset paging; draft creation with retained source/target/title/description; Markdown, changed-file tree, diffs/inline comments, reviews/resolution, assignees, dependencies/plans, checks/conflicts, lifecycle and all four merge strategies. Prepare/merge re-read the source head and reject an advanced reviewed branch. | Compiled action regressions cover draft retention, review/plan contracts and stale-head rejection. Upstream operations are fixtures; real backend merge/review logic is exercised by the Git suite. |
| CI catalogs and execution | Global/repository catalogs with visible partial failures, ref-specific typed manual inputs, fresh definitions, separate View/Edit/Execute/Manage gating; run/job/step states and actions, requirements/build-anyway reason/confirmation, error/artifact/agent links. | Compiled actions verify argument/capability contracts. Browser checks verify polling preserves the reason and controls. Actual production scheduling and runner execution were not initiated. |
| CI logs | Historical paging, same-origin WebSocket relay, authenticated protocol initialization, deduplicated/out-of-order safe output, pause/resume, follow/scroll, reconnect/backfill, bounded buffers and terminal cleanup. Global log-grid/STDERR styles checked in the browser. | UI log interactions use a real Netty/JDK relay with an isolated protocol server. JVM and native production-composition tests additionally verify authenticated local repository pipeline events over NATS and reject invalid connection-init credentials. Production log streams and artifact storage remain to be tested. |
| CI artifacts | Generated upload/download routes round-trip names containing folders, spaces, `#`, `+` and Unicode; preserve bytes/MIME; reject anonymous, expired and wrongly signed cookies; enforce View/Edit and matching repository/run IDs. Downloads use private caching and attachment headers. | Actual generated routes, Netty decoding, signed JWT middleware and streaming; identity lookup, permission decision and artifact storage are fixtures. Production storage-backed delivery remains unverified. |
| Repository administration | Metadata, webhooks, branch protection, group permissions including Execute, GC/repair, write-only CI secrets. Settings require Manage; failed forms retain drafts; secret values are not echoed. | Compiled routes/actions compare against actual schema and API permission contracts. |
| CI agents | Administrator gating, registration with transient one-time token, confirmed deregistration, name/labels and orchestrator JSON editors, status/heartbeat/jobs/parent, polling. Browser checks mount both structured JSON editors and retain settings drafts across polling. | Agent API is a fixture; no real agent was registered or deregistered. |
| GitHub administration | Pair/version/filter/secret controls, paged history/ref/pull/deliveries, transfers/reconciliation, reviewed conflict SHAs, shared principal mappings with distinguishing identity. | Compiled actions and browser rendering; real external transfers and conflict resolution remain unverified. |
| Packaging | Production BML page bundles are served from the library jar. Git server hosts separate API and UI listeners in one process with loopback HTTP proxies. JVM distribution, GraalVM native compilation, isolated JVM/native auth/page/action/subscription flows and graceful shutdown checked. | Combined native compilation uses `-Ob`; release `-O3`, external provider exchange and production-service acceptance remain unverified for this composition. |

The current browser matrix visits 47 route/view combinations at 1440, 1024 and 390px (141 checks), including empty repositories and administration screens. It measures rendered headings, document fit, control dimensions/alignment and browser exceptions, exercises dialogs and deferred file browsing, then separately checks live logs and agent drafts. It is not a claim that every possible action or permission combination has been executed in a browser. Counts in earlier revision records below describe those earlier runs.

## Visual review — 11 October 2026

The earlier browser checks did not establish visual parity with Studio. The user rejected that UI. This review inspected the authenticated deployed Studio repository list in native Chrome, compared its actual header, navigation, background and table treatment with the BML screenshots, and read Studio's shared shell, auth and control sources. The deployed authentication route returned the existing signed-in session to the home page; its logged-out appearance was not inspected. The supplied repository detail did not produce a rendered reference during the initial review. The follow-up review successfully inspected the live Sowers Bosca repository workspace, including its header/ref controls, tabs, file list and README. The supplied repository and its reported image remain unaccepted.

The Git BML application now uses Studio's dark design tokens, slate Git accent, compact typography, 224px sidebar with active navigation, ambient background, header owner/actions, understated table separators, and tab indicators. Authentication uses Studio's 480px form column, 380px glass card, decorative brand rings/orbs and mobile layout. Pull requests use a main review/diff column and a details sidebar that stacks below it on smaller screens. Review composition opens on demand. Metadata and rename settings have separate panels; pipelines use the shared table treatment. File actions no longer add a padded rounded box inside the workspace. CodeMirror retains its flat full-height surface and uses the application palette.

Repository create/import disclosures have scrollable bounded forms, Cancel, Escape and outside-click dismissal with trigger focus restoration. Clone URLs open in a reusable popover with Escape/outside dismissal. Repository and file action forms have Cancel and the same dismissal helper. Mobile repository tabs no longer shrink away. These behaviors are asserted in the browser matrix. The runner now saves 24 representative screen screenshots plus create-form screenshots at all three widths, alongside the existing auth/editor/log/agent artifacts. Screenshots were visually inspected for repository lists, source/Markdown, authentication, pull requests, settings, pipelines and synchronization, including mobile lists, forms and code.

Final validation: `:git:git-ui:check` passed 37 JVM tests, 15 client tests, generated BML compilation and strict TypeScript checking. The isolated browser runner passed all 84 route/viewport checks, auth/editor/Markdown flows, menu dismissal/focus checks, mobile tab visibility, live logs and agent draft preservation, with no horizontal overflow or browser exceptions.

The user also rejected the first styling pass as still messy. The follow-up simplified the actual layouts: ref controls moved beside the repository title; clone details became expandable; file actions and source controls share one row; duplicated search navigation was removed; PR management moved beside the review/diff content; GitHub history precedes expandable pairing/secrets; agent deregistration is expandable; pipeline cards became table rows. Reusable section, popover and UTC timestamp components remain local to Git UI for later library extraction. Browser regressions cover the taller editor, popover focus, first-viewport changed files/history, expandable configuration, action cancellation and readable timestamp semantics. A later screenshot review exposed vertical character wrapping in mobile tables despite the earlier overflow checks passing. Shared tables now keep headings/actions/identifiers readable and scroll horizontally; browser checks measure their text height, not only document overflow.

This is a visual correction based on the evidence above, not acceptance of every screen against a live Studio counterpart. Full replacement acceptance remains open.

## Corrections found by the review

- Replaced the initial minimal authentication flow with the actual Studio SDK flows, proof selection and feedback; initialize the SDK on signed-in pages so token refresh remains active. Sign-out waits for its client handler to mount.
- Removed the authentication mount/view router. Shared helpers contain SDK operations and navigation. Client scripts use component scope outside islands, with BML managing setup and cleanup. Removed client-only island wrappers; live server-state views retain their islands. Expanded all 105 BML files and formatted the Kotlin, client, style and GraphQL source. Repeated compiled-page, strict TypeScript and browser checks after these changes, including failed sign-in control restoration, incomplete recovery links and OAuth failure feedback.
- Replaced textarea-style viewing with CodeMirror, preserved editor instances/drafts across failed actions, and made read-only content keyboard-focusable.
- Prevented mobile controls from clipping CodeMirror inside a short file surface. The surface uses viewport sizing; browser checks assert visible code area, bounded height and internal long-file scrolling.
- Fixed raw file routing to capture the remaining path, so nested Markdown assets can reach Git storage. Added private caching, MIME/active-document download controls and signed-cookie transport coverage.
- Fixed artifact upload/download routes to capture nested names after URI decoding; bind run IDs to their repository and serve private attachments. A real HTTP regression reproduced the routing failure before the correction.
- Matched settings/lifecycle controls to Manage and CI actions to their distinct Edit/Execute/Manage requirements. Added independent repository capability fields to the owning GraphQL type.
- Enforced existing branch-protection rules at direct GraphQL file-write boundaries; verified denied writes leave real repository refs unchanged.
- Rejected merge preparation/submission when the root PR's reviewed source branch advances.
- Compared the UI schema subset to actual domain SDL and corrected nullability/return-shape mismatches.
- Removed unsupported Vue CSS escape selectors from BML components; moved cross-component/client-widget rules into real CSS. Fixed literal empty field hints and missing heading descriptions. Browser assertions now check the resulting styles.
- Added repeatable browser fixtures and isolated Chrome profiles. Repeated runs create fresh scratch files; screen exceptions and overflow are assertions, not just printed diagnostics.

## Local GraphQL integration — 10 October 2026

The standalone composition exposes every GraphQL operation in the Git UI and shared browser auth SDK. It reuses domain controllers and authorization, with a focused profile/search identity schema instead of loading the entire main API. The original `ServerCall` reaches local GraphQL execution, preserving origin validation, credentials, session changes and BML installation/session identities. HTTP-backed BML remains available through the same client interface; explicit host backends leave browser/OAuth route ownership with the host and survive renderer reloads.

The production-composition test starts fresh isolated PostgreSQL and NATS, bootstraps security through the normal installer, signs up and signs in a real user, creates a repository through the administrator API and grants the owner's group View. It then renders compiled repository pages, archives the repository through a BML action, receives a real repository pipeline event over the local WebSocket/NATS path, rejects invalid HTTP and connection-init tokens, and checks sign-out cookie clearing. `BML_GRAPHQL_ENDPOINT` is deliberately unreachable. The same test passed on JVM and the quick-optimized native executable. Infrastructure and configured external services still use their normal network connections.

Real composition checks exposed ref names sent to the SHA-only commit API. Repository browsing, Markdown previews and pull-request head checks now resolve branches/tags before looking up a commit, including fresh-head checks before reviews and merges. The HTTP fixture rejects named refs at that API so the regression is observable.

A BML action fixture failed with EOF connections during concurrent native compilation; its isolated rerun passed. Its readiness probe now waits for a successful BML HTTP response rather than merely a TCP listener. The full 168-test BML server suite then passed both independently and during another uncached native compilation.

## BML control revision — 2026-10-11

The Nuxt component and Git page source now provide the explicit reference for reusable BML controls. Git forms use one enhanced select with async owner search, loading/empty/error feedback, keyboard navigation, viewport positioning and multi-select tags. Creation, review, assignment and dependency dialogs use native modal containment, explicit focus wrapping, fixed footers, dismissal/focus restoration and failed-action draft recovery. Repository filtering applies immediately. Pull-request Overview, Files Changed and Reviews are separate views, with the existing review/comment/resolve and merge actions retained. Creation matches Studio's README default and suggests slugs while preserving explicit edits. The [component specification](bml-ui-components-spec.md) records supported contracts and remaining Nuxt-package gaps.

Focused `:git:git-ui:check` passed: 37 JVM tests, 15 client-auth tests, strict TypeScript and BML compilation. `:git:git-server:installDist` packaged the revision successfully. The isolated browser suite passed all 90 route/view and viewport combinations, plus the targeted interaction checks, without horizontal overflow or runtime exceptions. Desktop and mobile dialog and pull-request screenshots were inspected; label alignment and form widths were corrected from that inspection. These measurements cover the local fixture and do not establish deployed external-flow acceptance or a complete port of the Nuxt component package.

## Validation commands and results

Run Gradle from the workspace root. `:git:git-ui:check` includes strict TypeScript checking, 15 client-auth tests, schema contract checks and 37 JVM tests covering compiled BML HTTP/actions, Markdown and WebSocket relay behavior. It and `:git:git-server:installDist` passed again after moving client scripts to component scope. The browser auth/editor/Markdown checks and 84-view screen matrix also passed again, with no runtime exceptions or horizontal overflow. Additional browser checks passed for the owner filter's All choice, clone-copy feedback, ref-loading GET submission and typed pipeline execution. The earlier Git-server suite had 2 provider tests; the local-composition validation adds schema, permission-filtering and real-server integration checks.

For the local GraphQL revision, focused validation passed **421 JVM tests**: 208 core BML, 168 BML server, 37 Git UI, 5 Git server, and 3 core GraphQL execution tests. The 15 client-auth tests, strict TypeScript check and Git-server JVM distribution also passed. The BML server suite was rerun without build-cache restoration after the readiness correction. These are behavior/contract checks, not a workspace-wide coverage figure.

```bash
./gradlew :bml:core-bml:test :bml:bml-server:test --rerun :git:git-ui:check :git:git-server:test :git:git-server:installDist :bosca-core:core:test --tests 'bosca.graphql.GraphQLServiceExecutionTest' --no-build-cache --no-configuration-cache
GIT_SERVER_TEST_BINARY="$PWD/git/git-server/build/native/nativeCompile/bosca-git-server" \
  ./gradlew :git:git-server:test --tests 'bosca.git.server.LocalGitGraphQLIntegrationTest' --rerun --no-build-cache --no-configuration-cache
```

The full Git suite passed with 1,043 tests. `:git:git:koverVerify` passed its 98% line and 85% branch gates; XML report measured **98.279409% line coverage** (5,255 covered / 92 missed) and **85.681875% branch coverage** (3,399 covered / 568 missed). These are Git-domain coverage figures, not UI or entire-workspace coverage.

The full Git CI suite passed with **571 tests**, including three artifact HTTP regressions. Focused transport checks use generated routes, signed-cookie middleware and real sockets, with isolated identity/permission/storage decisions. The raw-image test additionally pushes through stock Git into the real DFS/JGit storage path.

```bash
./gradlew :git:git-ui:check :git:git-server:test :git:git-server:installDist
./gradlew :git:git:test :git:git:koverVerify :git:git:koverXmlReport
./gradlew :git:git-ci:test
./gradlew :git:git-server:nativeCompile --build-args=-Ob --no-configuration-cache
```

Native compilation for the local GraphQL revision passed in quick optimization mode (`-Ob`) in 1m 43s on the final uncached run, and its executable passed the isolated production-composition integration test. Configuration cache was disabled after its metadata resolution failed with a Gradle project-lock error; this does not establish release-mode compilation. No generated source or build output is part of the change.

Browser reproduction instructions are in [Git UI README](../../git/git-ui/README.md). The isolated fixture serves compiled pages/assets and records API arguments. The runner uses its own temporary Chrome profile and closes it. Reports/screenshots are written to `git/git-ui/build/reports/browser/`.

## Empty repository and control sizing regressions — 2026-10-11

An unborn symbolic HEAD exists without an object ID. `RepositoryBrowseServiceImpl.getStats` now selects a non-null HEAD ID or an existing branch ID before walking commits. A truly empty repository returns zero commit/contributor counts and retains the pack-size query. The two new real-JGit regression cases reproduced null-object-ID failures before the fix, then passed with the other browse tests (56 total). Initialization still links empty repositories to their configured default branch.

The production JVM composition test now also creates an empty repository with default branch `trunk`, grants the user's group View, and renders its Code and Commits pages through local GraphQL against isolated PostgreSQL/NATS. Both return HTTP 200 with the empty setup and zero stats; initialized README browsing, actions and subscriptions remain covered. This expanded test passed. The new cases have not been rerun on the native executable.

The control pass uses explicit 32px regular / 30px compact heights, matching input/select padding and typography, 14px icons, consistent owner/field captions and 14px stacked-form gaps. Form margins are reset: browser measurements exposed a 6.5px offset between the branch controls and Clone before that reset. Grid actions now fit their labels. Authentication keeps its separate dimensions and border theme.

The isolated browser matrix passed all 90 route/view × viewport cases (1440, 1024 and 390px) with no horizontal overflow or runtime exceptions. Added checks measure visible control heights and top/bottom alignment within the same row; dialogs retain full-width, left-aligned controls. Owner-menu checks cover stable/matching widths, 30px search fields, compact options and viewport fit at all three widths. Desktop/mobile screenshots were inspected for creation, settings, comparison and owner menus. The UI check passed its 37 JVM tests, 15 client flow tests, strict TypeScript and compiled BML checks; the Git-server distribution was rebuilt.

Commands for this focused regression pass:

```bash
./gradlew :git:git:test --tests 'bosca.git.service.RepositoryBrowseServiceTest' \
  :git:git-ui:check \
  :git:git-server:test --tests 'bosca.git.server.LocalGitGraphQLIntegrationTest' \
  :git:git-server:installDist --no-configuration-cache
```

Browser execution uses the isolated fixture and runner described in the Git UI README. These measurements cover this sizing/crash correction; they do not establish full Studio parity or deployed-environment acceptance.

## Screen quality revision — 2026-10-11

This revision addresses the supplied settings, secrets, mappings, agents, owner menu, repository actions, empty repository and pipeline screenshots. The layouts now compose shared card headers, readable records, compact aligned controls and focused dialog forms. Confirmation checkboxes no longer occupy permission rows or lifecycle/maintenance toolbars. Empty states describe the next action. Settings retain metadata, rename, webhook events/signing secrets, every protection flag, group actions and write-only pipeline secrets.

The broader review also revised agent status/settings, pipeline run/job/artifact details, synchronization/pairing, search/comparison/statuses, and pull-request reviews and actions. Status polling remains separate from agent settings drafts. Merge preparation presents the reviewed strategy and dependency plan in a dialog; changing strategy retains that choice and modality. Comment failures remain on the file view, while failed review submissions retain their review form. Existing source-head and merge-plan revalidation remain enforced.

File browsing now uses a deferred sidebar with commit-pinned scalar inputs. Compiled HTTP tests verify that a file page renders without an eager tree query, then loads the tree under the original authentication. Folder expansion and failed-load retry are exercised in Chrome. The production JVM composition additionally verifies an authenticated deferred request, private/no-store caching and anonymous rejection with the network GraphQL endpoint deliberately unreachable. Unborn repositories have one setup view, no empty tree divider, and a clone command that uses the phone's available width.

Desktop and phone renders were visually reviewed for the supplied screens and their dialogs, the empty states, and the revised detail views. This caught a stale file-tree divider, cramped phone clone commands, oversized short snippets, and loss of native dialog containment during polling. Those issues were corrected and are asserted in the browser checks. Short command snippets stay within 120px; full file views retain the measured 591.25px CodeMirror height, line-number gutters and zero inner radius.

Final focused validation passed:

- **40 Git UI JVM tests**, **15 client-auth tests**, strict TypeScript and compiled BML checks.
- **114 screen/viewport cases**, plus auth/editor/Markdown, owner search, webhook submission, mapping creation/removal, merge-plan/strategy dialogs, deferred-tree recovery, live logs and preserved agent drafts. No document overflow or runtime exceptions were reported.
- The real **JVM `LocalGitGraphQLIntegrationTest`** against isolated PostgreSQL/NATS, including the deferred request and initialized/unborn repository rendering.
- Git-server `installDist` and `git diff --check`. A source audit found no script declarations inside islands.

```bash
./gradlew :git:git-ui:check :git:git-server:installDist --no-configuration-cache
./gradlew :git:git-server:test --tests bosca.git.server.LocalGitGraphQLIntegrationTest --no-configuration-cache
./gradlew :git:git-ui:runBrowserFixture --no-configuration-cache
```

The fixture and isolated browser runner are described in the [Git UI README](../../git/git-ui/README.md). This run saved its screenshots and matrix report in `/tmp/git-ui-quality-browser`. Restart Git server to load the rebuilt UI. This quality revision was validated on JVM; its final UI changes have not been rerun in a native executable or against the supplied deployed repository. The replacement gates below remain separate.

## Text alignment revision — 2026-10-11

The original compact button text was 0.75px above the control and icon center. A rendered-text regression check failed against that version. Buttons, button links and popover triggers now use normal font line boxes while retaining their control heights. Repository menu actions share the same left inset and typography; destructive button styling belongs to the scoped button component so its default styles cannot overwrite it.

The Studio source comparison also exposed that Git declared Geist without loading it. Git now packages the existing Bosca Geist and Geist Mono assets, including their Unicode subsets and license. Both fonts load from the Git origin. HTTP checks verify every WOFF2 file, MIME type and cache policy. The real Git-server JVM composition serves all 11 font subsets. Native resource metadata includes the font stylesheet, files and license; this revision was not rerun in a native executable.

Final validation passed **41 UI JVM tests**, **15 client-auth tests**, strict TypeScript, compiled BML, and **114 browser screen/viewport cases** with the fonts loaded. The matrix recorded **405 visible control measurements**: every text-range and icon center had a 0px vertical offset, and the largest combined horizontal centering deviation was below 0.01px. “New repository” retained its 30px height and had a 0px text/icon centering offset at 1440, 1024 and 390px. Open menu actions and destructive treatment were checked at each width. Auth, editing, Markdown, dialog, polling and other interaction checks also passed without document overflow or runtime exceptions.

The real JVM `LocalGitGraphQLIntegrationTest` against isolated PostgreSQL/NATS passed with the font-delivery assertions. Git-server `installDist` and `git diff --check` passed. Final screenshots and measurements are in `/tmp/git-ui-text-alignment-verified`; the header close-up is in `/tmp/git-ui-text-alignment-final-detail`. Restart Git server to load its rebuilt UI.

## Commit author revision — 2026-10-11

The BML file writer required a profile name and email, unlike Studio's `useGitCommitAuthor` resolver. The `security-initial` administrator profile has no email attribute. The shared BML author resolver now uses the primary profile, then the first profile, with name/slug and Studio's existing neutral fallbacks. The editor preview and each write action resolve the same metadata. Missing profiles and malformed email attributes use those fallbacks; archive, detached-ref, permissions and branch-protection checks remain in place.

Validation passed **47 UI JVM tests**, **15 client tests**, strict TypeScript and the Git-server distribution build. Compiled HTTP actions create a README and folder and delete a file with a profile lacking an email. The real JVM `LocalGitGraphQLIntegrationTest` uses the bootstrap administrator against isolated PostgreSQL/NATS, submits the compiled BML editor action, and reads back the README content, message and author from an actual initial JGit commit on the configured `trunk` branch. The browser clicks Create first file, edits and commits the README with a no-email profile, and opens its rendered Markdown. The complete browser suite passed **114 screen/viewport checks** with no overflow or runtime exceptions. Artifacts are in `/tmp/git-ui-commit-author-browser`. This revision was not rerun in a native executable or the user's running server; restart Git server to load it.

## Repository context and Studio behavior revision — 2026-10-11

This revision compared the actual repository, pipeline and pull-request sources in `web/projects/studio/app/pages/git/` before correcting the BML behavior. Repository Pull requests, Pipelines, Statuses, Secrets and Settings now render within the repository header and tabs. Their filtering, pagination and settings-panel links retain repository/ref/return context. Explicit repository selection is independent of remembered owner filtering; an archived repository's pull list stays scoped and disables creation.

The global New pull request dialog now contains its target repository picker, source repository, source/target branches, title, description and draft switch. Changing repositories reloads available branches through declarative server actions while retaining the draft and native modal. It works without a repository chosen elsewhere on the page. The initial focus lands on the visible target picker.

Global pipelines follow Studio's owner selection and compact repository groups, with latest-run status, ref, SHA, duration, time and a manual Run icon. The repository tab shows recent runs and job states; complete run history is expandable. Pipeline titles open the latest run. Manual execution stays in the selected repository until starting the run, preserves the owner filter, and reloads typed input definitions automatically when the ref changes. Compatible input values and modal focus survive the action patch. Fresh definition, input and Execute-permission validation remain enforced at submission.

Boolean preferences/configuration use the shared accessible switch. Canonical Lucide paths and their ISC attribution are packaged locally. Compact icon actions replace repeated short text actions, while form submission retains named buttons. Application settings collapse during browsing and expand on their routes. Repository synchronization is a secondary Metadata action. Dialogs focus the first visible enabled field; native-select enhancement transfers focus to its visible trigger. Shared controls, Git composition and their scoped styles remain separate for later BML-library extraction. Every handwritten top-level Kotlin type in Git UI remains in its own file.

Final validation passed:

- **51 Git UI JVM tests**, **5 Git-server tests**, **15 client tests**, strict TypeScript, schema contracts and compiled BML production assets.
- **141 browser screen/viewport cases** at 1440, 1024 and 390px, with no document overflow or runtime exceptions. Interaction checks cover scoped repository tabs, latest-run navigation, repository/branch draft retention, keyboard switches, initial/modal focus, automatic manual-input reload, execution, live logs and agent draft retention. Authentication, CodeMirror, Markdown images, owner menus and no-email bootstrap README creation also passed.
- The real JVM production composition against isolated PostgreSQL/NATS renders the Pull requests, Pipelines, Statuses, Secrets and Settings repository tabs through the local GraphQL engine; it retains its real bootstrap README write/readback, local auth, deferred-tree and subscription checks.
- Git-server `installDist`, `git diff --check` and a source review of the changed components. Desktop and phone screenshots were inspected for pipeline catalog/repository views, PR creation, manual execution, settings and controls. The matrix recorded **447 control measurements**, with 0px vertical text/icon centering offsets and less than 0.01px horizontal centering deviation.

```bash
./gradlew :git:git-ui:check :git:git-server:test :git:git-server:installDist --no-configuration-cache
./gradlew :git:git-ui:runBrowserFixture --no-configuration-cache
GIT_UI_BASE_URL=http://localhost:<fixture-port> \
  GIT_UI_ARTIFACT_DIR=/tmp/git-ui-studio-parity-browser \
  node git/git-ui/src/test/browser/run.mjs
```

The final screenshots and matrix report are in `/tmp/git-ui-studio-parity-browser`. The isolated fixture and browser were stopped after validation. Restart the running Git server to load the rebuilt UI. This revision was verified on JVM and isolated browser data; its native executable and the user's live external/provider/CI environment were not exercised. The remaining replacement gates below still apply.

## Tree and action controls revision — 2026-10-11

The supplied file-view screenshot prompted a control-level comparison against Nuxt's `Button.vue`, `Tooltip.vue`, `Tabs.vue` and Studio's `ChangedFileTreeNode.vue`. The [component specification](bml-ui-components-spec.md#source-mapping) links each BML concept to that source. Tree and disclosure controls have Studio domain references; the shared Nuxt package does not currently export generic Tree or Disclosure components.

File edit/rename, delete, download, raw view, new file/folder, clone, copy and line-wrapping actions now use labelled Lucide icons. Shared tooltip presentation appears on pointer hover and keyboard focus, uses the Bosca theme and 200ms delay, fits the viewport, links through `aria-describedby`, and dismisses on Escape. `ui-icon-button` composes the static button and tooltip controls; native links retain navigation/download semantics. Markdown Preview/Source use underline tabs beside the file actions in one toolbar. The redundant ref-submit arrow is removed. CodeMirror retains its 591.25px measured desktop viewing height inside a 638.25px file surface.

Repository browsing and changed-file views compose `ui-tree` and `ui-tree-item`. The Files heading is static. Directory controls occupy the full row, use familiar chevron/folder icons and show an open-folder state. The current file has one selected row; navigation expands its ancestors automatically. The tree has a single tab stop, ARIA level/selection/expansion, keyboard movement and type-ahead. Its controller stays outside the changing server island, so folder expansion can focus the first child and collapse restores the parent focus. Folder loading remains deferred, authenticated and commit-pinned. Diff trees use the same presentation with local expansion and native file anchors.

Expandable sections use a shared styled disclosure heading instead of browser-default triangles. Job headings have one chevron. Review actions use the shared icon-link control; PR identity/diff labels and repository breadcrumbs have explicit spacing. No shared BML library or Studio source was changed in this revision.

Final validation passed:

- **53 Git UI JVM tests**, **5 Git-server tests**, **15 client tests**, strict TypeScript, schema contracts, compiled BML and Git-server `installDist`.
- **141 browser screen/viewport cases** at 1440, 1024 and 390px with zero document overflow and zero runtime exceptions. Added checks exercise real pointer/focus tooltips, Escape dismissal, Markdown keyboard tabs, tree expansion/parent movement/Home/End, selected-file ancestor loading and one selected row. Visible disclosures have no native marker. Existing auth, editor, image, action, pipeline/log and agent-draft checks passed again.
- **426 control measurements**, with 0px vertical text/icon offsets and less than 0.01px horizontal centering deviation. Desktop/phone screenshots and tooltip captures were reviewed for the file workspace, tree, PR diff and shared disclosures.
- The Git-server tests retain real local GraphQL production-composition checks against isolated PostgreSQL/NATS. `git diff --check` passed; unrelated workspace changes were preserved.

```bash
./gradlew :git:git-ui:check :git:git-server:test :git:git-server:installDist --no-configuration-cache
./gradlew :git:git-ui:runBrowserFixture --no-configuration-cache
GIT_UI_BASE_URL=http://localhost:<fixture-port> \
  GIT_UI_ARTIFACT_DIR=/tmp/git-ui-tree-controls-browser \
  node git/git-ui/src/test/browser/run.mjs
```

Final artifacts are in `/tmp/git-ui-tree-controls-browser`, including `bosca-git-ui-file-action-tooltip-1440.png`, `bosca-git-ui-file-action-tooltip-390.png` and the matrix JSON. The isolated fixture/browser were stopped after validation. Restart Git server to load its rebuilt UI. This revision was verified on JVM and isolated browser records; native/deployed external-service acceptance remains outside this evidence.

## Search revision — 2026-10-11

Search now follows the actual Studio `git/search.vue` layout and the Nuxt search/input/select sources. A prominent query sits above aligned compact Content/Paths, repository and branch/ref controls. The old search branch was removed from the generic exploration loader and picker. The focused search model and page component compose generic search-input and segmented-control primitives; each handwritten Kotlin type remains in its own file. The [component specification](bml-ui-components-spec.md#search-composition) records the contracts and source mapping for later library extraction.

Typing debounces a read-only BML action by 300ms; Enter submits immediately. Actions coalesce to the latest query, retain input focus/caret, hide obsolete results, and persist repository/query/mode/ref in the URL. Changing repositories retains the query and resets the branch. Content search uses indexed branches; Paths supports tags/commits and pins result links to the resolved commit. Snippets remain safely escaped and highlight literal query matches. Paths show names, full paths, type icons, sizes and link/submodule labels. Result anchors support keyboard navigation, and Markdown line links open the numbered Source view. Empty repositories, unsupported content refs, no matches, API failures and transport failures have distinct feedback. A failed transport preserves the query and permits retry. Mobile navigation brings the active Search item into view.

Final validation passed:

- **57 Git UI JVM tests**, **5 Git-server tests**, **15 client tests**, strict TypeScript, schema contracts, compiled BML production assets and Git-server `installDist`.
- **13 browser search interaction checks**, exercising debounce, focus/caret, escaped highlighted snippets, slow-response coalescing, repository/ref changes, keyboard modes, URL restoration, path metadata, API/transport errors, retry, clear, Enter and Markdown source-line navigation. The deliberate blocked-action check recovered successfully after unblocking. There were no browser runtime exceptions.
- **159 screen/viewport cases** (53 routes/views at 1440, 1024 and 390px), including initial, Content, Paths/tag, no-match, error and empty-repository search states. No document overflow or runtime exceptions. The matrix recorded **537 control measurements**, including the new mode controls: 0px vertical text/icon offsets and a maximum horizontal centering deviation of 0.0078125px. Compact controls sharing a row align, and active Search navigation stays visible at every width. Existing auth, editor, Markdown image, tree, pipeline/log and agent-draft checks passed.
- The real JVM production-composition integration test against isolated PostgreSQL/NATS searches the README created by the bootstrap administrator through a compiled BML action, local GraphQL and actual JGit path search, then verifies the file link is commit-pinned.
- Desktop and phone screenshots were inspected for initial/results/error states, path metadata, mode/control alignment, visible navigation and Markdown line selection. `git diff --check` passed; unrelated workspace changes were preserved.

```bash
./gradlew :git:git-ui:check :git:git-server:test :git:git-server:installDist --no-configuration-cache
./gradlew :git:git-ui:runBrowserFixture --no-configuration-cache
GIT_UI_BASE_URL=http://localhost:<fixture-port> \
  GIT_UI_ARTIFACT_DIR=/tmp/git-ui-search-browser \
  node git/git-ui/src/test/browser/run.mjs
```

Artifacts are in `/tmp/git-ui-search-browser`, including `bosca-git-ui-search-content.png`, `bosca-git-ui-search-paths.png`, `bosca-git-ui-search-line.png`, phone/desktop state captures and the search/matrix JSON reports. The isolated fixture and browser were stopped after validation. Restart Git server to load the rebuilt UI. This revision verifies real JGit path search and fixture-backed content results; it does not establish acceptance of the deployed content index or a newly rebuilt native executable. The replacement gates below remain open.

## File navigation and clone revision — 2026-10-11

The supplied README error was reproduced with an actual pointer click in isolated Chrome before changing the code. The file row inherited the component-level folder action on its outer tree item. The recorded request was `git-sidebar-tree.model:repoabc`, method `expand`, arguments `["README.md"]`; the URL stayed on the repository root and the UI showed `Folder is no longer available.` Earlier browser checks expanded directories and opened file URLs directly, so they did not cover this transition.

The sidebar now binds expansion only in the directory branch. File rows retain native anchors without a folder action. The updated browser test opens README with a real pointer click, expands a folder through its server action, and opens a nested Markdown file using Enter. It verifies the URL, selected file and request sequence. Folder validation remains enforced; the error was not suppressed in the model.

The clone panel now composes a compact selectable read-only URL and its copy icon in one row, following Studio's clone field. Shared copy feedback changes to a check icon without changing the input width; clipboard failure selects the value and displays manual-copy guidance. The interactive popover follows its trigger in the browser top layer, repositions with viewport/content changes, and supports Escape/outside dismissal and focus recovery. Generic field/copy/popover controls remain local components for later BML-library extraction.

Validation passed:

- **59 Git UI JVM tests**, **5 Git-server tests**, **15 client tests**, strict TypeScript, schema contracts, compiled BML production assets and Git-server `installDist`.
- The compiled deferred-tree regression checks that directory rows have the expansion action and file rows do not. The real JVM production-composition test verifies the same boundary in an actual repository tree and renders its README through the local GraphQL engine under the viewer's credentials. Reader/editor tests verify the presence of the actual editor component rather than matching a generic DOM ref name.
- Browser checks exercise actual pointer and keyboard file navigation, correct folder-only requests, clone-field focus/selection, actual clipboard contents, stable success feedback, and a deliberate clipboard-denied fallback. Existing auth, bootstrap README, editing, Markdown images, search, dialogs, pipeline/log and agent checks pass.
- **159 screen/viewport cases** at 1440, 1024 and 390px, with zero document overflow and zero runtime exceptions. Open clone panels stay below 100px in height, their input and copy icon each measure 30px and align on one row, and they fit the viewport. The matrix records **537 control measurements**, with 0px vertical text/icon offsets and a maximum horizontal centering deviation of 0.0078125px. Desktop, tablet and phone clone captures and the README navigation capture were inspected.
- `git diff --check` passed. Unrelated workspace changes were preserved.

```bash
./gradlew :git:git-ui:check :git:git-server:test :git:git-server:installDist --no-configuration-cache
./gradlew :git:git-ui:runBrowserFixture --no-configuration-cache
GIT_UI_BASE_URL=http://localhost:<fixture-port> \
  GIT_UI_ARTIFACT_DIR=/tmp/git-ui-readme-clone-browser \
  node git/git-ui/src/test/browser/run.mjs
```

Before-fix evidence is in `/tmp/git-ui-readme-clone-before-browser/bosca-git-ui-file-navigation.json`. Final artifacts are in `/tmp/git-ui-readme-clone-browser`, including the file-navigation JSON, README capture and `bosca-git-ui-clone-1440.png`, `bosca-git-ui-clone-1024.png` and `bosca-git-ui-clone-390.png`. The isolated fixture/browser were stopped after validation. Restart Git server to load the rebuilt UI. The native executable and the user's running repository were not exercised in this revision; the replacement gates below remain open.

## 2026-10-11 tab dividers and dropdown chevrons

The shared route tabs now own a full-width divider and a trailing metadata slot. Pull-request counts occupy that slot instead of shrinking the tabs inside a separate flex toolbar. Repository and pull-request lists use the same 12px header-to-tabs gap. PR numbers follow Studio's compact badge and 6px title gap; the count uses singular/plural wording.

Select chevrons follow Nuxt's `Select.vue`: the Lucide glyph is 13px, muted with `--fg-4`, centered and inset 11px from the border. Enhanced selects rotate the chevron when open and highlight their trigger border. The native fallback suppresses the browser arrow and uses the same glyph. More menus use the 12px Nuxt chevron with open-state rotation. The generic controls and their client controllers remain local for later library extraction.

Final validation passed:

- `:git:git-ui:check` and Git-server `installDist`, including **59 JVM tests**, strict TypeScript, schema contracts and compiled BML assets. The existing 15 client tests passed in the initial check; subsequent style-only checks reused that task.
- **165 screen/viewport cases** across 55 routes/views at 1440, 1024 and 390px, with no document overflow or browser runtime exceptions. The matrix measured **114 tab dividers**, all filling the available content width with 0px edge deviation, and **105 select chevrons**, all centered with 0px vertical deviation. All measured repository/PR header gaps were 12px.
- Select and More open-state rotation, Escape dismissal and stable dropdown width passed. JavaScript-disabled captures at all three widths verified the native fallback's 13px glyph, 11px inset and 0px centering deviation. PR title gaps measured 6px and the populated count read `1 result`.
- **549 control measurements** retained 0px vertical text/icon deviation and at most 0.0078125px horizontal centering deviation. Existing authentication, real pointer/keyboard file navigation, clipboard success/failure, editor, Markdown image, search, pipeline navigation/trigger, live-log and agent-draft checks passed.
- Desktop and phone populated/empty PR, repository, open-owner and native-select captures were inspected. `git diff --check` passed; unrelated workspace changes were preserved.

An intermediate browser attempt exited with Node's unsettled top-level-await diagnostic during navigation and was not counted as a pass. The harness now explicitly rejects closed or timed-out debugger requests. A new divider assertion initially included the vertical scrollbar gutter; it now measures the parent's client width. The final run used a fresh isolated fixture and completed the entire suite.

```bash
./gradlew :git:git-ui:check :git:git-server:installDist --no-configuration-cache
./gradlew :git:git-ui:runBrowserFixture --no-configuration-cache
GIT_UI_BASE_URL=http://localhost:<fixture-port> \
  GIT_UI_ARTIFACT_DIR=/tmp/git-tabs-select-browser \
  node git/git-ui/src/test/browser/run.mjs
```

Artifacts and measured geometry are in `/tmp/git-tabs-select-browser`, including `bosca-git-ui-screen-matrix.json`, `bosca-git-ui-initial--pulls-1440.png`, `bosca-git-ui-initial--pulls-status-CLOSED-390.png`, and owner/native-select captures. The isolated fixture and browser were stopped. Restart Git server to load the rebuilt UI. This revision checks the packaged JVM UI against isolated data; it does not establish deployed or native replacement acceptance. The remaining gates below still apply.

## 2026-10-11 panel widths, Markdown alignment and loading

Shared section stacks no longer cap themselves at 1100px. Cards fill their parent allocation, so Compare, Statuses and repository panels reach the same content edge as their navigation. Compare commits use the shared record layout, with separate message, author and hash instead of concatenated text.

Markdown fills its file surface with Studio's README padding, keeping its title and view labels on the same left inset. View labels and icon actions share the toolbar's centerline; the active underline meets the divider. The centered 980px article and nested toolbar padding were removed.

`ui-spinner` and `ui-loading-state` provide shared progress presentation for deferred files, search, async choices and pending buttons. Buttons retain their dimensions and accessible names while displaying progress through BML's existing busy attributes. Closing a select clears its busy state. File loading hides Retry; failure replaces progress with an alert and aligned Retry action; successful retry replaces the fallback with files. The shared spinner respects reduced motion. Generic components remain local for later BML UI-library extraction.

Validation passed:

- `:git:git-ui:check` and Git-server `installDist`: **59 JVM tests**, **15 client tests**, strict TypeScript, schema contracts and compiled BML assets.
- **220 screen/viewport cases**, covering 55 routes/views at 1920, 1440, 1024 and 390px, with zero document overflow or browser runtime exceptions. **160 panel measurements** showed 0px edge deviation. Markdown title/view-label and text/action centers measured 0px deviation at every width; the article filled its toolbar width and its selected underline met the divider.
- **16 held-request loading states** across files, async owner search, search results and form actions. These checks measured spinner/text alignment, preserved button dimensions and the actual accessibility-tree action name, verified reduced motion, completed or dismissed each request, and checked transport recovery. Deliberately aborted form requests never reached the fixture's mutation handler. File-error captures verify hidden progress, visible aligned Retry and successful retry.
- **736 control measurements** retained 0px vertical text/icon deviation and at most 0.0078125px horizontal deviation. Existing authentication, file navigation, editor, Markdown images, clipboard, search, dialogs, pipelines/logs and agent checks passed.
- Wide desktop and phone Markdown, Compare, Statuses, loading and retry captures were inspected. The loading capture exposed an overridden hide rule on Retry; its selectors and error-state button layout were corrected before the final run. The initial JVM assertion still expected the old loading label and was updated. `git diff --check` passed; unrelated changes were preserved.

```bash
./gradlew :git:git-ui:check :git:git-server:installDist --no-configuration-cache
./gradlew :git:git-ui:runBrowserFixture --no-configuration-cache
GIT_UI_BASE_URL=http://localhost:<fixture-port> \
  GIT_UI_ARTIFACT_DIR=/tmp/git-layout-loading-browser \
  node git/git-ui/src/test/browser/run.mjs
```

Artifacts and measured geometry are in `/tmp/git-layout-loading-browser`, including the screen-matrix JSON, Markdown and wide-screen Compare/Statuses captures, and `bosca-git-ui-loading-<context>-<width>.png` captures. The isolated fixture/browser were stopped. Restart Git server to load the rebuilt UI. This revision checks the packaged JVM UI with isolated records; deployed/native replacement acceptance remains governed by the gates below.

## 2026-10-12 separate UI host and restored BML HTTP boundary

The application now runs in `:git:git-ui-server`, which consumes the existing `:git:git-ui` component library. Git server retains its GraphQL/security, Git transport and CI routes. The local backend, host-backend selection hooks and added `GraphQLService.execute(ServerCall, ...)` entry point are removed. Production BML/core GraphQL source affected by that adapter matches the pre-adapter implementation. The UI host uses BML's existing HTTP GraphQL/OAuth proxies and the existing WebSocket relay; it streams raw files and artifacts without validating credentials. API URL prefixes remain in the proxy configuration rather than raw UI links. Clone-URL browser visits redirect to the configured UI origin.

Measured validation passed:

- `:git:git-ui:check`: **60 JVM tests**, **15 client auth tests**, strict TypeScript, generated BML compilation and packaged browser assets.
- `:bml:bml-server:test`: **163 tests**; the focused core `GraphQLServiceExecutionTest`: **3 tests**. Git-server's remaining schema/provider/infrastructure checks: **5 tests**. Repository browser-redirect checks: **6 tests**. No failures or skips in these reports.
- `:git:git-ui-server:test`: **8 tests** across the JVM production flows and file-proxy checks, then rerun with both native server executables. Six HTTP proxy tests cover nested/encoded image and artifact paths, exact binary bytes, headers, API prefixes, explicit credential precedence, upstream denial, rejection of path normalization to another API route and delivery before the upstream finishes producing a download.
- The two production-process tests use isolated PostgreSQL/NATS and exercise default and custom cookie prefixes. They verify signup/login, profiles, fonts/CSS, repository reads and writes, empty repositories, deferred trees, repository tabs, search, raw README delivery, invalid credentials, sign-out cookies and authenticated pipeline-event relay through `/graphqlws`.
- Real API regressions verify that an anonymous shared BML shell cannot list a private repository despite incoming bearer/cookie credentials, and that a scoped API-token override cannot reuse a preceding unrestricted JWT's permissions. The default JWT remains usable on the following HTTP operation.
- Both JVM distributions and native binaries built successfully. API native compilation used **`-O3`**; UI native compilation used **`-Ob`**. The distribution audit found no BML/UI modules in Git server and no Git/security implementation modules in Git UI server. The UI library jar retains its page scripts, theme CSS and all 11 font assets.

The initial integration probe asked the anonymous principal resolver for an unseeded NIL principal and failed; the regression now queries permission-filtered private repository data. Another assertion expected 401/403 for raw access denial; source and real HTTP confirmed the API's existing route contract returns 400. The final test requires the proxy to preserve that status/body and verifies that private content is absent. Compilation initially lacked the test-only BML runtime dependency, which was added. One command had a malformed task selector and did not run tests. All final checks above completed successfully; failed attempts are not counted as passes.

Current test/run commands are in the [UI server README](../../git/git-ui-server/README.md). Historical commands earlier in this record refer to the former embedded host.

```bash
./gradlew :git:git-ui:check :git:git-ui-server:test :git:git-server:test
./gradlew :bml:bml-server:test :bosca-core:core:test --tests 'bosca.graphql.GraphQLServiceExecutionTest'
./gradlew :git:git:test --tests 'bosca.git.transport.GitRepositoryRedirectRouteTest'
./gradlew :git:git-server:installDist :git:git-ui-server:installDist
GIT_SERVER_TEST_BINARY="$PWD/git/git-server/build/native/nativeCompile/bosca-git-server" \
GIT_UI_SERVER_TEST_BINARY="$PWD/git/git-ui-server/build/native/nativeCompile/bosca-git-ui-server" \
  ./gradlew :git:git-ui-server:test --rerun --no-build-cache --no-configuration-cache
```

Native build/test logs are `/tmp/git-ui-server-packaging.log`, `/tmp/git-ui-server-final-validation.log`, `/tmp/git-ui-server-path-validation.log` and `/tmp/git-ui-server-native-final.log`. The UI presentation was retained; the browser screen matrix was not rerun for this server migration. External OAuth/email delivery, production storage-backed artifacts and live pipeline-step logs, the supplied deployed repository, UI release optimization and the later Studio/main-server cutover remain unverified. Test resources and server processes were stopped.

## Remaining replacement acceptance

1. Inspect the supplied Studio repository read-only with a real session: Markdown syntax/ref, exact image request, response/MIME and actual ACLs. Repeat against the Git application and record the result.
2. Complete real provider/email/link/refresh flows on the configured Git origin. Confirm the Git server app-origin allowlist and custom cookie-prefix/domain configuration. Password auth and sign-out through the separate UI/API processes are covered on JVM/native; deployed external flows remain open.
3. Exercise real Git CI subscriptions and artifact delivery, real agent behavior, and external GitHub synchronization using isolated records and normal APIs.
4. Extend isolated JVM/native startup coverage to storage-backed artifacts, real pipeline-step logs and Git transports through the HTTP proxy boundary. Initialization, password authentication, packaged pages, BML mutations and subscription relay are covered. UI release optimization remains a release-readiness check.
5. Complete the separately specified Git API/backend ownership move, remote domain consumers, notification origins and cutover. Do not remove Studio or main-server Git composition in this first stage.

Unknown behavior remains unaccepted until measured. These gates are part of the migration, rather than assumptions hidden behind passing fixture counts.


## 2026-10-12 one process with separate API and UI listeners

Commit `ff408e51` records the preceding separate-host migration and removal of local GraphQL execution. The subsequent composition change makes `:git:git-server` consume the `:git:git-ui-server` host module and start both listeners in one process. Run `:git:git-server:run`; API/transport defaults to 8091 and UI defaults to 8093 (corrected after the local embedding-service collision described below). `GIT_HTTP_PORT` and `GIT_UI_HTTP_PORT` configure the listeners. API transport configuration remains under `bosca.server`; UI transport configuration lives under `git.ui.server` in the same application configuration file.

BML remains an HTTP client/proxy. The embedded UI's GraphQL, OAuth, files and subscriptions use loopback HTTP/WebSocket connections to the API's actual bound port. `BML_GRAPHQL_ENDPOINT` applies only to the optional standalone UI entry point. The combined production test deliberately sets that variable to an unreachable endpoint and verifies that the embedded UI still targets its own API. Authentication, scopes and domain authorization remain in the API's fresh request contexts. No production BML or GraphQL execution/authentication source changed in this step.

`BoscaApplication` now accepts explicit service ownership. The existing one-argument constructor retains its registration/shutdown behavior and JVM signature. The UI application's non-owning constructor leaves the API's application and serializers registered in DI; UI shutdown closes its own hooks and scope without stopping process-wide services. The API is started before the UI. The host drains the UI before stopping the API, and a failed or closed listener shuts down the whole host without invoking the JVM-halting engine stop method.

Measured validation:

- Full `:bosca-core:core:test`: **1,915 tests**, no failures, errors or skips. The new ownership regression verifies that the UI cannot replace the API's application/JSON/Protobuf providers, runs its hooks in reverse order, cancels its scope and leaves shared services running until the owner shuts down.
- Full `:git:git-server:test`: **11 tests**, no failures, errors or skips. Four real-listener lifecycle cases cover API bind failure, UI bind failure, UI installation failure and sibling-listener shutdown, including released ports and application cleanup.
- The two production-composition tests now start **one process with both listeners**, against isolated PostgreSQL/NATS. Both passed on JVM and the rebuilt combined native executable with default and custom cookie prefixes. Existing signup/login, profile identity, repository reads/writes, initial README commits, deferred tree/actions/search/tabs, CSS/fonts/raw delivery, subscriptions, invalid credentials, sign-out cookies, anonymous shared shells and scoped-token override checks passed. Process shutdown is required to complete gracefully and release both ports.
- `:git:git-ui-server:test`: **6 file-proxy tests**, no failures, errors or skips.
- `:git:git-server:installDist` and `:git:git-server:nativeCompile --build-args=-Ob` passed. The combined JVM distribution contains the UI host and BML libraries, **23 page bundles** and **11 font assets**. Final native integration was rerun with `--rerun --no-build-cache`; four JVM listener-lifecycle cases passed alongside the two native production cases.

Moving the production test initially omitted its test-only BML server dependency; that compile failure was corrected. A subsequent full run caught a race in classifying a listener closed immediately after binding as a startup error. The host now treats a completed close as ordinary shutdown, preserves bind exceptions and stops the sibling. The full Git suite and final native/lifecycle run passed after that correction. The API's JMX worker count also follows its configured/default Netty worker count.

Commands used for final validation:

```bash
./gradlew :bosca-core:core:test
./gradlew :git:git-ui-server:test
./gradlew :git:git-server:test :git:git-server:installDist :git:git-server:nativeCompile --build-args=-Ob --no-configuration-cache
GIT_SERVER_TEST_BINARY="$PWD/git/git-server/build/native/nativeCompile/bosca-git-server" \
  ./gradlew :git:git-server:test --tests bosca.git.server.GitServerIntegrationTest --tests bosca.git.server.GitServerLifecycleTest --rerun --no-build-cache --no-configuration-cache
```

The application specification and component READMEs now describe one-command startup. External OAuth/email, deployed data/service acceptance, combined release `-O3`, and the earlier UI/cutover gates were not revalidated by this listener change. Core and Git test counts are behavior checks, not coverage measurements; no new Kover gate was run.


## 2026-10-12 local UI port collision

The first default UI port, 8092, conflicts with the workspace's text-embeddings service. The reported bind failure was confirmed with `lsof` and `docker ps`: `bosca-dependencies-text-embeddings-inference-1` publishes `8092:80`, as specified in `server/services/docker-compose.yaml` and the existing embedding run configuration.

The Git UI listener now defaults to **8093**, both in the combined server and the standalone UI configuration. The API's default app URL, origin allowlist, OAuth return allowlist and repository UI URL all use `http://localhost:8093`. Git transport remains on 8091. No embedding-service configuration or running container was changed.

`GitServerConfigurationTest` checks default API/UI separation, avoids the embedding service's configured port, and verifies the UI host, authentication/redirect origins and transport endpoints agree. It reads both packaged Git configurations and the actual workspace dependency configuration. This regression and both production combined-server integration cases passed (**3 tests**, no failures, errors or skips). `:git:git-server:installDist` also passed. These checks used JVM processes; the native executable was not rebuilt for this configuration-only correction.

```bash
./gradlew :git:git-server:test --tests bosca.git.server.GitServerConfigurationTest --tests bosca.git.server.GitServerIntegrationTest :git:git-server:installDist
```

Restart Git Server to load the updated resources and open `http://localhost:8093`. An explicit `GIT_UI_HTTP_PORT=8092` or custom configuration still overrides the default and must be changed if present.


Pre-push validation repeated `:git:git-ui:test` and the complete `:git:git-server:test` after the UI asset handlers moved resource reads to `Dispatchers.IO` and the standalone UI module became non-suspending. All **60 UI tests** and **12 server tests** passed, with no failures, errors or skips. This run includes the default-port configuration regression and both production combined-server cases. No native rebuild was run for those final edits.

## 2026-10-12 pull-request presentation and interaction revision

The PR list, creation dialog and three detail views were compared with Studio's actual PR source. A shared lifecycle component now applies green Open, purple Merged, red Closed and neutral Draft presentation across titles, rows, dependencies and merge plans. Review outcomes use readable labels and dismissed reviews remain neutral. Headers group title/number/status, author/source/target and timestamps; date labels have explicit spacing. Repository labels exclude deleted records; the list-name regression is covered over HTTP and in the browser.

Overview puts merge readiness and checks together in the main column, followed by recent reviews and changed files. Assignees and dependencies share a compact sidebar. Auxiliary actions use specifically labelled, tooltipped icons. Merge, close, reopen and review actions retain explicit text. Merge confirmation names the selected method and shows the ordered dependency plan. Known branch conflicts and blocked plans disable the respective merge controls; the API retains required-review/check enforcement. Drafts offer Ready for review rather than an unavailable review submission action.

Creation groups target and source repositories/branches side by side on desktop and stacks them on phones, retaining draft fields across selections. Diff files use framed headings, aligned line numbers and compact comment icons. Comment composition opens inside the selected diff; cancellation clears it. A real added-line click exposed a previous 500 response: nullable line numbers were sent through BML's primitive-only action decoder. Git now sends zero for an absent side and normalizes it inside its model, without changing core BML.

Measured validation:

- `:git:git-ui:check` and Git-server `installDist` passed: **63 JVM tests**, **15 client tests**, strict TypeScript, schema contracts and compiled BML assets. The three new HTTP/action regressions cover lifecycle controls, conflict/metadata controls and selecting/cancelling an added-line comment.
- PR browser interactions passed at **1920, 1440, 1024 and 390px**. Checks cover five lifecycle/conflict states, rendered badge colors, title and timestamp spacing, grouped checks/reviews, merge-method confirmation, a real line-comment click, its 24px control and keyboard-focus tooltip, and cancellation beside the diff. No PR browser runtime exceptions were observed.
- The complete isolated browser suite passed, including authentication, repository editing/navigation, Markdown assets, search, PR interactions, and **220 route/viewport cases** with no document overflow or browser runtime exceptions. Live logs, agent drafts and pipeline-trigger checks also passed. After the timestamp adjustment, the PR module and screen matrix were repeated against the rebuilt fixture.
- Desktop and phone PR overview, creation, merge confirmation, reviews, conflicts and inline-comment captures were inspected. `git diff --check` passed. The [component specification](bml-ui-components-spec.md) records the Nuxt mappings and the local control contracts.

The first inline-comment regression failed because the primitive decoder received null; both the compiled action test and real browser click pass after the Git-only argument correction. One combined browser run later timed out loading branches in the global creation dialog. Its field/action binding was inspected; the complete run passed against a freshly rebuilt fixture, with timeout diagnostics added to the runner. Earlier copy expectations for result counts and merge-method labels were updated to the new presentation. Failed attempts are not counted as passes.

```bash
./gradlew :git:git-ui:check :git:git-server:installDist
./gradlew :git:git-ui:runBrowserFixture
GIT_UI_BASE_URL=http://localhost:<fixture-port> \
  GIT_UI_ARTIFACT_DIR=/tmp/git-pr-complete-browser \
  node git/git-ui/src/test/browser/run.mjs
GIT_UI_BASE_URL=http://localhost:<fixture-port> \
  GIT_UI_BROWSER_TESTS=pulls.mjs,screens.mjs \
  GIT_UI_ARTIFACT_DIR=/tmp/git-pr-verified-browser \
  node git/git-ui/src/test/browser/run.mjs
```

Final build output is in `/tmp/git-pr-last-check.log`; full-suite and final PR/matrix logs and captures are in `/tmp/git-pr-complete-browser*` and `/tmp/git-pr-verified-browser*`. The repository-label correction was then verified with the same 63-test check and a focused four-width PR browser run in `/tmp/git-pr-label-browser*`. All isolated fixtures and browsers were stopped. This revision changes Git UI and its specifications only. No production BML, GraphQL, authentication or API code changed. Browser mutations use isolated fixtures; real Git/backend merge execution and native packaging were not rerun for this presentation change. Earlier replacement-acceptance gates remain open. Restart Git Server to load the rebuilt UI at `http://localhost:8093`.

## 2026-10-12 compact pull-request header

Commit `877bb4c2` records the PR-page improvements above. The subsequent header adjustment removes the separate desktop timestamp row. Author/branch context and dates share a centerline, with timestamps right-aligned beneath the header action. At phone width they wrap with a 6px gap. Titles and branch chips retain readable wrapping for longer content.

`:git:git-ui:check` and Git-server `installDist` passed, including all **63 JVM tests** with no failures, errors or skips, TypeScript checking, schema contracts and BML compilation. The focused PR browser module passed at **1920, 1440, 1024 and 390px**, including its existing lifecycle/merge/comment interactions and new header measurements. Regular desktop headers measured **85.546875px** high at all three desktop widths; context/date centers and timestamp/header right edges had **0px** deviation. Phone metadata measured the intended **6px** gap. Short and long title/branch captures were inspected, and the long-content check found no document/header overflow. No browser runtime exceptions were observed.

Build output is `/tmp/git-pr-header-check.log`; browser output, screenshots and `pull-header-layout.json` are in `/tmp/git-pr-header-browser*`. The browser used an isolated fixture. The full screen matrix and native/backend execution tests were not repeated for this scoped header change. The fixture and browser were stopped; `git diff --check` passed.

## 2026-10-12 compact repository header and workspace spacing

The repository header now lives in `git-repository-heading`, composing the shared heading, branch picker, PR creation and clone controls. Description and stats share a desktop row, with stats aligned beneath the controls. Phones stack that metadata. Count labels now distinguish one commit, branch, tag or contributor from plural counts.

Baseline browser measurements found the branch controls **11px above** the title center and a **16px** gap before the summary. The intended global compact-gap rule lost to the page component's scoped styles. The page now owns an explicit `compact` option; the heading owns an opt-in title-aligned action layout. Repository pages select both contracts. Other page/header defaults retain their layouts.

Measured validation passed:

- `:git:git-ui:check` and Git-server `installDist`: **63 JVM tests**, no failures/errors/skips, TypeScript, schema contracts and compiled BML assets. The repository route regression now checks singular and plural stat labels; its first run caught an incorrect branch plural, which was corrected with explicit forms before final validation.
- Desktop headers measured **79px**, down from **89px**, at 1920, 1440 and 1024px. Title/control centers now have **0px** deviation. Tabs start **18px higher** on desktop; header-to-tab spacing is **12px** and repository page gaps are **8px**. Description and stats share a centerline, with stats at the header's right edge. Desktop and phone captures were inspected.
- The complete isolated browser suite passed: authentication, repository file/keyboard navigation, ref and clone controls, editor writes/drafts, Markdown assets, PR interactions, search, and **220 route/viewport cases** with no document overflow or browser runtime exceptions. Repository-header checks run across the repository tabs and empty views. Pipeline triggers/live logs and agent drafts also passed.
- Empty and long files at 1440 and 1024px now have a **625.25px** CodeMirror editor inside a **672.25px** surface, **34px taller** than the preceding measured layout. Line gutters, internal scrolling, flat surfaces and viewport fit remain verified.

Build output is `/tmp/git-repository-header-check-final.log`; browser output and screen-matrix measurements are in `/tmp/git-repository-header-browser*`. Baseline and rebuilt header measurements/captures are in `/tmp/git-repository-header-before` and `/tmp/git-repository-header-after`. The isolated browser and fixture were stopped; `git diff --check` passed. API/authentication and production BML source did not change, and native/backend production tests were not rerun for this UI revision.

## 2026-10-12 Files Changed workspace and timestamp text alignment

The supplied empty-file screenshot exposed a poor arrangement: a repeated section heading and a full-width file tree separated from its diff. Files Changed now uses a compact count/action toolbar with a bounded 220px tree beside the diff, following Studio's review layout. Phones stack the tree above the diff. The new `git-pull-files` component owns only this layout; the existing review model, dialogs, line comments, thread resolution and pagination remain in their owning component. Empty text files show “Empty file added.” or the corresponding deletion message instead of an unhelpful zero-line hunk. Normal hunks retain standard spacing in their captions.

The timestamp containers had shared a centerline, but their text did not: the parent used 11.5px text while `ui-time` forced 12px. The labels and values now share inherited 12px/18px typography and a baseline. The earlier container-only measurements missed this defect; the new checks compare computed fonts and actual text-range bounds.

Measured validation passed:

- `:git:git-ui:check` and Git-server `installDist`, including **64 JVM tests** with no failures, errors or skips, strict TypeScript, schema contracts and BML compilation. The new compiled-route regression verifies the added empty file's notice and absence of a zero-line hunk or nonexistent line-comment controls.
- PR browser checks at **1920, 1440, 1024 and 390px**, covering desktop sidebar/diff alignment, mobile stacking, the compact toolbar, folder collapse/keyboard expansion, native file-fragment navigation and inline-comment editing/cancellation. All **16 timestamp pairs** across older and fresh “just now” cases have identical fonts and **0px** text-top/text-bottom deviation.
- The complete isolated browser suite, including authentication, repository navigation/editing, Markdown images, search, PR interactions and **220 route/viewport cases**, with no document overflow or runtime exceptions. Live logs, agent drafts and pipeline triggers also passed.
- Desktop and phone empty-file and regular-diff captures, including an open inline-comment form, were visually inspected. `git diff --check` passed.

Build output is `/tmp/git-pr-workspace-check.log`. Focused PR measurements and captures are in `/tmp/git-pr-workspace-browser`; full-suite output and captures are in `/tmp/git-pr-workspace-full-browser*`. Browser actions use isolated fixtures. Production BML, API and authentication source did not change; native packaging and real backend merge execution were not rerun for this presentation change. The component specification records the supported layout and typography contracts without claiming complete Nuxt review-feature parity. Restart Git Server to load the rebuilt UI at `http://localhost:8093`.

## 2026-10-12 pull-request editing and branch recovery

Every PR detail view now has a tooltipped pencil action for repository editors. The dialog changes the title, Markdown description and source/target branches, retains rejected drafts and returns to the current view. Open and draft requests allow branch changes. Closed requests require reopening before retargeting; merged requests keep metadata editing. Description clearing is supported. Repository associations and the PR number remain fixed.

The Git API validates changed branch refs, checks the current PR version and writes metadata, review dismissal and comment invalidation in one transaction. Metadata-only edits preserve review decisions and anchors. Previous approving/change-request decisions are dismissed after either branch changes, and comments remain in history as outdated. Edits, approvals, line comments and merges from stale BML pages are rejected. Dependency merges check the root version before starting and each planned PR version before merging. Diff pagination rejects a changed comparison. Existing optional GraphQL calls remain valid; the new version fields and arguments are additive.

PR metadata and diff loading are separate. A deleted source or target leaves the page and editor available with a clear comparison-unavailable state, without claiming there are no changes. The editor marks the current missing branch unavailable and offers live replacements. A real-server regression deletes the source, edits metadata while it is missing, and then repairs the PR by choosing a live branch; its diff becomes readable again. Reviews display “Branches changed” rather than the stored machine reason.

Measured validation passed:

- Full `:git:git:test`: **1,054 JVM tests**, no failures, errors or skips. `:git:git:koverXmlReport` and `:git:git:koverVerify` passed the module's 98% line / 85% branch gates. Coverage is **98.2966% lines** (5,309 covered, 92 missed) and **85.7461% branches** (3,465 covered, 576 missed), excluding only the configured generated code. This is module coverage, not complete path coverage.
- `:git:git-ui:check` and Git-server `installDist`: **70 JVM UI tests**, TypeScript, schema contracts and compiled BML/client assets. New HTTP/action cases cover editing, retained drafts, blank-title validation, permission-aware controls, fixed branches on completed PRs, stale diff paging and missing-source recovery.
- Both production `GitServerIntegrationTest` cases passed against isolated PostgreSQL/NATS, with the default and custom cookie prefixes. They create real branches/commits and a PR, approve/comment, edit through the compiled BML action, read back the saved description/branches/version/diff/mergeability, verify dismissal/outdated history, reject stale edits/reviews/comments/merges and read-only credentials, and recover from a deleted source.
- PR browser checks passed at **1920, 1440, 1024 and 390px**, including initial/title focus, Escape/focus restoration, draft and merged edit forms, keyboard branch selection, rejected-field retention, successful branch changes, description clearing, return to Files Changed and selection of a live replacement for a deleted branch. Desktop and phone captures were inspected.
- The complete isolated browser suite passed, including authentication, repository/editor/Markdown, PRs and search, plus **220 route/viewport cases** without document overflow or runtime exceptions. Live logs, agent drafts and pipeline triggers also passed. `git diff --check` passed.

The full Git run caught an existing synchronization workflow that stores rejected blank metadata; the API keeps that behavior while the editor validates nonblank titles. The browser's Escape regression exposed an external-return-focus target losing to a nested select trigger; the Git UI dialog now gives the explicit target precedence. Earlier stale empty-PR copy and authorization/test-fixture assumptions were corrected before the passing results. Failed attempts are excluded from the results above.

Core test/coverage output is `/tmp/git-pr-edit-coverage-final.log`; the final UI build is `/tmp/git-pr-edit-last-ui-check.log`. Production editing and deleted-branch recovery output is `/tmp/git-pr-edit-recovery-check.log`. Final browser logs and captures are `/tmp/git-pr-edit-acceptance-browser*`. Native compilation was not repeated. No production BML, authentication or server-composition source changed: BML still proxies to the Git API over HTTP, and the API owns permissions. Restart Git Server and refresh `http://localhost:8093` to load the rebuilt UI. Replacement/cutover gates outside this editing workflow remain open.

## 2026-10-12 review-action toasts

Review submission, line comments and thread resolution now use a reusable `ui-toast`, following Nuxt's `ToastContainer.vue` and `useToast.ts` surface, centered 75vh placement and four-second timeout. The notice sits outside document flow and uses the shared icon/tooltip controls. It fits phone widths, announces success politely, does not take focus, pauses expiration during hover/focus or a hidden document, and supports dismissal and reduced motion. Its preserved root and server-owned sequence display repeated successful messages without replaying them on unrelated island patches. This control presents the latest notice from its owning component; notification queues and progress toasts are not part of this change. Form/API failures remain inline and retain drafts.

Measured validation passed:

- `./gradlew :git:git-ui:check :git:git-server:installDist`: **71 JVM UI tests**, no failures, errors or skips; TypeScript, schema contracts, compiled BML/client assets and Git-server packaging passed. The compiled-action regression covers successful resolve/comment/review notices, increasing sequences for repeated successes, and inline failure feedback without advancing the sequence.
- Focused PR browser checks passed at **1920, 1440, 1024 and 390px**. Actual BML actions verify that success does not move the diff or add an inline banner, messages repeat after dismissal, unrelated comment-selection patches do not replay a notice, failed comments retain their draft inline, successful comments close the composer and review submission closes its modal. The notice stays within the viewport, retains its DOM across island replacement, exposes polite status semantics and restores keyboard focus on dismissal. Hover/focus pause, timeout, shared dismiss tooltip and reduced motion were checked.
- The complete isolated browser suite passed, including authentication, repository/editor/Markdown, PRs and search, plus **220 route/viewport cases** without overflow or runtime exceptions. Live logs, agent drafts and pipeline triggers also passed. Desktop and phone thread/review toast captures were visually inspected. `git diff --check` passed.

Build output is `/tmp/git-toast-check.log`; focused browser output/captures are `/tmp/git-toast-browser*`, and the complete run is `/tmp/git-toast-full-browser*`. These checks use isolated fixtures. Backend mutation execution and native compilation were not rerun for this presentation change. Restart Git Server and refresh the UI to load the packaged assets.

## 2026-10-12 compact line commenting

The line-comment editor now follows Studio's inline composition: one full-width three-row field directly under the selected diff line, aligned with the code after its desktop gutters and inset on phones. The nested card, redundant heading, review picker and duplicate close action are removed. Comments attach to the latest active review owned by the current user's profiles. The existing reviewed-head and PR-version checks remain. First-time reviewers get a compact Review changes action and keep their line draft through that dialog.

The reusable `ui-comment-editor` owns field sizing, hints, compact error feedback, native validation and Ctrl/Cmd+Enter submission. `git-pull-comment` supplies the selected-line context, while actions stay in the parent live review-model scope. Per-line drafts survive selection changes and unrelated patches. Cancel discards the selected draft; Cancel and success restore the line action's focus. Failed comments retain the field and focus it, with an 8px gap before the error. Saving shows the existing spinner, retains button dimensions, disables actions and makes the field read-only. A retry hides the previous rejection while pending. Encoded IDs preserve hint/error references for filenames containing spaces.

Measured validation passed:

- Final `./gradlew :git:git-ui:check :git:git-server:installDist`: **72 JVM UI tests**, no failures, errors or skips, with strict TypeScript, schema contracts, compiled BML/client assets and Git-server packaging. Compiled action cases verify the compact composition, automatic latest-owned-review association excluding dismissed/other reviews, rejected drafts, selected-line reset and encoded error references for a filename with spaces.
- Final PR browser checks at **1920, 1440, 1024 and 390px** verify automatic focus and file/line labelling, full diff width, desktop code alignment and an empty composer no taller than 160px. They cover whitespace-disabled submission, separate drafts on two lines, restoration after switching back, retention through thread resolution, bounded growth to 320px, cancellation/discard/focus return, failures inside the field, keyboard submission and first-review dialog draft/focus recovery. A held real BML request checks the read-only field, disabled actions, stable button width, visible spinner and hidden prior rejection. Existing review/toast/editing interactions also pass.
- The complete isolated browser suite passed after the layout, error spacing and review-dialog changes: authentication, repository/editor/Markdown, PRs, search and **220 route/viewport cases**, with no overflow or runtime exceptions. Live logs, agent drafts and pipeline triggers passed. The subsequent pending-action and encoded-ID refinements were covered by the final build and focused PR rerun above. Desktop and phone composer/error/prerequisite captures, plus the final saving state, were visually inspected. `git diff --check` passed.

The final build is `/tmp/git-comment-last-check.log`; final focused browser output and captures are `/tmp/git-comment-last-browser*`. The full run is `/tmp/git-comment-full-browser*`. Browser checks use isolated fixtures; production backend mutation execution and native compilation were not rerun. Restart Git Server and refresh `http://localhost:8093` to load the packaged UI.

## 2026-10-12 discussion replies

Files Changed and Reviews now provide Reply beneath each discussion. The shared compact editor focuses immediately, supports keyboard submission, keeps rejected drafts, retains drafts while switching threads and returns focus to Reply on Cancel or success. Its open state replaces the action row so there is one submit action. Responses display chronologically with each author's identity and time. Replies attach to the original review without requiring another review, retain its file/line/commit and outdated flag, and add an unresolved response to a resolved discussion. Outdated groups remain in Reviews with original-commit links and stay off the current diff.

The additive Git `replyReviewComment` mutation checks repository View access, the authenticated profile, comment membership in the PR and the inspected PR version. Restricted API tokens need `git:write` or `ci:edit`, with the established SA exemption. This check is specific to the new reply operation; existing discussion mutations retain their current authorization behavior. Ordinary browser sessions still participate through View access. Reply creation and its COMMENTED activity share the existing transaction and event path. The comment insert now persists the inherited outdated flag; no new database column or BML/authentication mechanism is introduced.

Measured validation passed:

- `./gradlew :git:git-ui:check :git:git-server:installDist :git:git:test :git:git:koverXmlReport :git:git:koverVerify :git:git-server:test --tests 'bosca.git.server.GitServerIntegrationTest'`: **1,058 Git JVM tests**, **73 JVM UI tests** and **two production-server integration cases**, no failures, errors or skips. Strict TypeScript, schema contracts, compiled BML/client assets and server packaging passed. Kover gates passed with **98.3063% lines** (5,340 covered, 92 missed) and **85.8093% branches** (3,483 covered, 576 missed), excluding only configured generated code. This is module coverage, not complete path coverage.
- Service/controller cases verify anchor inheritance, another author's response, resolved/outdated discussions across PR states, activity dispatch, missing/foreign comments, blank replies, stale versions, cancellation, repository access and token write scopes. The real PostgreSQL/NATS server cases, with default/custom cookie prefixes, retarget and resolve a discussion, post its reply through compiled BML, then read back the stored original review/commit/line, outdated flag and unresolved response. They reject read-only API credentials and stale reply submissions. The initial View-only implementation failed the read-only-token check; the new endpoint's scope check was corrected before these passing results.
- The complete isolated browser suite passed: authentication, repository/editor/Markdown, PRs, search and **220 route/viewport cases**, with no overflow or runtime exceptions. Live logs, agent drafts and pipeline triggers passed. Reply checks cover both views at **1920, 1440, 1024 and 390px**, no separate-review prerequisite, field focus, cancellation/discard/focus return, retained errors, safe text rendering, chronological authors, resolution/reopening, per-discussion drafts and original-location links for outdated replies. The subsequent action-row/icon refinements passed the final UI build and focused PR rerun at all four widths. Desktop and phone captures of the final editor were visually inspected. `git diff --check` passed.

Core, coverage and real-server results are in `/tmp/git-reply-final-validation-2.log`; the final UI build is `/tmp/git-reply-last-ui-check.log`. The complete browser run is `/tmp/git-reply-browser*`, and final focused captures/logs are `/tmp/git-reply-last-browser*`. Native compilation was not repeated. Restart Git Server and refresh the UI to load the rebuilt API and packaged assets.


## Review discussion layout and direct navigation — October 12, 2026

Reviews now uses a single review container with full-width discussion rows instead of nested comment cards. Reviewer/outcome/body and comment author/time metadata use consistent insets and typography. Each file/line location links to a stable discussion fragment in Files Changed. Navigation reveals a collapsed file, scrolls to the discussion, focuses it and highlights the target. Native links preserve history, keyboard activation and reload behavior. Later action patches do not take focus from an open reply editor.

Outdated, file-level and undisplayed-line discussions remain reachable in a separate section below the diff. Outdated discussions retain their original-commit source link and do not attach to current diff lines. File-level comments render once without a fabricated line number, support replies and omit the existing line-only resolution action.

Measured validation:

- `./gradlew :git:git-ui:check :git:git-server:installDist` passed on the final sources: **75 JVM UI tests**, no failures, errors or skips; strict TypeScript, schema contracts, compiled BML/client assets and Git server packaging passed. Regression cases cover stable unique anchors, current/deleted line sides, truncated/absent diff lines, outdated context and file-level labels/controls.
- The complete isolated browser suite passed, including authentication, repository/editor/Markdown, PRs, search and **220 screen/viewport cases**, with no overflow or runtime exceptions; live logs, agent drafts and pipeline triggers passed. The subsequent file-level refinements passed a final PR rerun at **1920, 1440, 1024 and 390px**. Checks cover aligned metadata, full-width flat rows, location tooltips, actual pointer/keyboard links, Back, reload, collapsed-file reveal, target focus/highlight, replying after navigation, unavailable fragments and separate outdated/file-level discussion navigation. The phone run initially clicked a reply toast covering the next link; the test now dismisses that notification before navigation. Final desktop and phone captures were visually inspected.
- `git diff --check` passed. The changes in this step are confined to Git UI and its specifications. Backend integration tests, coverage and native compilation were not rerun for this presentation/navigation step.

Final build output is `/tmp/git-discussion-final-validation.log`; the complete browser run is `/tmp/git-discussion-full-browser*`, and final PR captures/results are `/tmp/git-discussion-final-browser*`. The isolated fixture and browser were stopped. Restart Git Server and refresh the UI to load the packaged changes.


## Merged pull-request comparisons — October 13, 2026

The `GitPullRequest.diff` resolver compared live source/target refs with three-dot semantics. After a merge the source becomes an ancestor of the target, making that comparison empty. The local PR reported at repository `382b5ae6-f5f4-47b7-8216-fa76e707e588`, number 2, was confirmed as MERGED with strategy MERGE_COMMIT through a read-only query restricted to that PR and repository. No local records were modified.

Migration `V49__pull_request_merge_base.sql`, registered in `GitMigration`, adds the target tip immediately before merging. Every native merge strategy records it within the existing ref/PR transaction. The additive `mergeBaseSha` GraphQL field exposes that base; merge/squash parents can recover it when it was not recorded, including a two-parent merge with no strategy metadata. Completed diffs compare this immutable base with the merge result. Older rebase/fast-forward records without the base are explicitly unavailable; no guessed single-commit comparison is substituted.

BML uses the merge SHA and target repository for merged file context, skips live-branch prerequisites and preserves version checks during diff pagination. Original discussion source links retain their original source repository/commit. When that commit differs from the merge result, comments appear as Earlier revision discussions outside merged line positions; fast-forward comments remain inline when their commit matches. The multi-commit rebase regression exposed an unflushed intermediate commit; rebasing now flushes each inserted commit before the next merge reads it. BML processing, authentication and listener composition are unchanged.

Validation passed:

- `./gradlew :git:git-ui:check :git:git:test :git:git:koverXmlReport :git:git:koverVerify :git:git-server:installDist :git:git-server:test --tests 'bosca.git.server.GitServerIntegrationTest'`: **1,063 Git tests**, **76 JVM UI tests**, and **two real PostgreSQL/NATS server cases**, no failures, errors or skips. Strict TypeScript, schema contracts, compiled BML assets and packaging passed. Kover passed its gates at **98.3132% lines** (5,362 covered, 92 missed) and **85.9272% branches** (3,517 covered, 576 missed). This is module coverage, not complete path coverage.
- Both real server cases exercised all four merge strategies with two source commits. They read back the stored base, advanced the target with an unrelated change, checked that the historical diff contained both source files and excluded the later change, deleted both branches, and rendered the retained changes through BML. The subsequent server rerun also posts a source review/comment before each merge and verifies it stays inline for fast-forward or with its earlier revision for the other strategies. Unit cases cover existing commit-parent recovery, unavailable metadata, loud missing-object failures, cancellation and merged pagination. Four subset-migration fixtures were updated after they failed against the new model column; the rerun passed. A prior discussion assertion was updated to verify outdated replies remain outside current diff lines while reachable in their separate section.
- The isolated PR browser suite passed at **1920, 1440, 1024 and 390px**, including merged Files Changed with deleted branch names, earlier-revision discussion placement, unavailable-base presentation and the existing editing/review/reply/navigation interactions. Desktop and phone captures were visually inspected. `git diff --check` passed. Native compilation and the full 220-screen matrix were not repeated for this step.

Core build/integration/coverage output is `/tmp/git-merged-diff-final-validation.log`; the final UI/server rerun is `/tmp/git-merged-diff-discussion-check.log`, and final browser results/captures are `/tmp/git-merged-diff-final-browser*`. The isolated browser and fixture were stopped. Automatic approval review rejected binding the existing Chrome app because it could expose unrelated private content, and targeted browser surfaces were unavailable; the reported authenticated page itself was not inspected. The narrow local database read and isolated server/UI checks supplied the verification instead. Restart Git Server to load the code and apply V49 before refreshing the reported PR.
