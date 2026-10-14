# Studio Git UI inventory

The Studio Git area combines repository browsing and editing, pull requests, CI, and repository administration. The proposed BML application must preserve these workflows while correcting the editor layout, Markdown asset handling, owner selection, and repository list defaults.

The target UI belongs inside the existing `:git:git-server` project and process. Git implementation and API ownership must leave `:server:bosca-server`; this is a server separation as well as a UI migration.

This is the initial Studio source inventory from 9 October 2026. Subsequent BML implementation and measured checks are recorded in [parity validation](parity-validation.md). This inventory describes the workspace source at the initial review. The reported deployed symptoms have not been reproduced in an authenticated browser: no browser was available and the supplied Studio URLs were inaccessible through the web reader. The specific repository's Markdown and failed image request remain to be inspected. Source findings below do not establish which revision is deployed.

## Reported issues and source findings

| Issue | Source evidence | Assessment |
| --- | --- | --- |
| Editor does not fill the page | Repository detail computes `fileViewerHeight` from line count, capped at 800px. Its `CodeEditor` calls pass `height`, but the shared editor has no height prop and renders a textarea with a default of 10 rows. The code tab and file viewer do not stretch through the available height. | Confirmed source mismatch. Even a working line-count height would leave short files occupying little of the available workspace. |
| Nested rounded editor box | `.file-viewer` supplies a rounded border. The textarea supplies another border and radius. The page tries to remove these with `.file-viewer-editor :deep(.editor)`, but that class does not exist in the shared component. | Confirmed ineffective CSS override. |
| No line numbers or editor features | `@bosca/ui/nuxt` registers the shared `CodeEditor`. That component is a styled textarea; it implements two-space Tab insertion, but no CodeMirror editor, line gutter, highlighting, folding, or search. Studio already declares CodeMirror dependencies. | Confirmed missing implementation, despite comments in the repository page assuming a richer editor. |
| Markdown image missing | Root README selection and loading work through `RepositoryReadme`. It passes Markdown text and a cache key to `CommonMarkdown`, which has no repository, ref, or document-path context. Relative links and image URLs are not rewritten to Git files. Raw HTML is deliberately disabled. | Confirmed repository asset resolution gap. A relative image would resolve against the Studio URL. A raw HTML image could also be suppressed; the supplied repository's exact cause is unverified. |
| Owner dropdown shrinks when opened | The shared `Select` replaces its closed label with an input using `size="1"`. Its minimum width measurement considers supplied options and the placeholder, but excludes transient search results and the picked-option cache. The repository list supplies initial options rather than every selected search result. | Plausible source cause when a searched owner's label is wider than initial options. Requires real browser geometry checks. |
| Same-name owners cannot be distinguished | Repository list calls `useProfileSearch()` with the default name-only label. Search already retrieves slug, but the default label discards it when name exists. The current and remembered owners also use name-first labels. | Confirmed ambiguity. These are Profile IDs used as repository owners, not Principal IDs. |
| Archived repositories shown by default | Repository list initializes `activeTab` to `All`, fetches with `includeArchived: true`, and excludes only deleted rows in All. Backend `GitQuery.repositories` defaults omitted `includeArchived` to false. | Confirmed UI choice, not a backend default. |

Primary evidence: [repository list](../../web/projects/studio/app/pages/git/repositories/index.vue), [repository detail](../../web/projects/studio/app/pages/git/repositories/[id].vue), [editor implementation](../../web/packages/ui/src/components/CodeEditor.vue), [Nuxt component registration](../../web/packages/ui/src/module.ts), [README component](../../web/projects/studio/app/components/git/RepositoryReadme.vue), [Markdown renderer](../../web/projects/studio/app/components/common/CommonMarkdown.vue), [owner search](../../web/projects/studio/app/composables/useProfileSearch.ts), [select implementation](../../web/packages/ui/src/components/Select.vue), and [backend query defaults](../../git/git/src/main/kotlin/bosca/git/graphql/GitQuery.kt).

## Current application surfaces

| Studio surface | Current workflows to preserve |
| --- | --- |
| `/git/repositories` | Owner selection and remembered owner; All, Active and Archived lists; create and import; archive, restore and soft-delete; visibility, content type, size and update metadata. |
| `/git/repositories/{id}` Code | Empty repository setup and clone URL; ref selection; path breadcrumbs; directory/file listing; root README; text viewing; edit and commit; create file and folder; rename and delete; commit author display; dirty-buffer navigation and unload protection. |
| Repository Commits, Branches and Tags | Paged commit history; branch listing, creation and deletion; tag listing and deletion; default branch and protection constraints delegated to the backend. |
| Repository Pull Requests and `/git/pulls` | Repository and cross-repository lists, status filters and paging; PR creation with source/target branches and draft state. |
| `/git/pulls/{id}` | Markdown description; changed-file tree, diff hunks and line limits; reviews and inline comments; thread resolution; assignees; dependencies and merge plan; merge strategies and dependency merge; close, reopen and mark ready; conflicts and required checks. Detail loading uses repository ID and PR number from URL context. |
| `/git/search` and `/git/compare` | Repository/ref selection; content and path search; file navigation from results; base/head comparison and file diffs. |
| Repository Pipelines and `/git/pipelines` | Pipeline catalog, latest runs, manual ref/input selection and start, with repository Execute capability checks. |
| `/git/pipelines/{id}` | Run/job/step status, requirements and failures; cancel and rerun controls; failed-job rerun; build-anyway actions; run deletion; artifact downloads; agent links; historical and live logs. Active run status polls every 2 seconds; live step logs use `graphql-transport-ws`. |
| Repository Statuses and `/git/statuses` | Commit selection and commit status/check details. |
| Repository Secrets | Repository pipeline secret names, add/replace and delete. Values are write-only. GitHub pairing uses the separate Pipeline secret store through its existing panels. |
| Repository Settings | Fixed repository context; webhooks; branch protection; group permissions including Execute; garbage collection and repair; administrator-only GitHub pairing, branch filters, synchronization history, explicit transfers, reconciliation and reviewed conflict resolution. Panel selection is URL-driven. |
| Repository metadata actions | Name, description, visibility, content type and default branch changes; slug rename and clone URL implications. |
| `/git/ci-agents` and `/git/ci-agents/{id}` | Agent registration with one-time token, status filters and deregistration; name/labels; orchestrator configuration; heartbeat, current/recent jobs and parent agent; active detail polls every 5 seconds. |
| `/git/settings/github` | Administrator-only shared GitHub user mappings. These select Bosca Principals and confer no permissions. |

Sources: [Git routes](../../web/projects/studio/app/pages/git), [Git components](../../web/projects/studio/app/components/git), [Git SDL](../../git/git/src/main/resources/graphql/git.graphqls), [GitHub SDL](../../git/git/src/main/resources/graphql/github.graphqls), and [Git operations guidance](../../git/README.md).

## Other source gaps affecting the rebuild

- Direct file viewing sends every text file, including Markdown, to the editor; only the root README gets rendered prose. There is no general Markdown Preview and Source switch.
- Binary viewing says “Binary file — cannot display.” `readBlob` also marks files above its 1MiB inline threshold as binary. The UI cannot reliably distinguish a true binary from oversized text using that flag alone.
- Root README selection recognizes conventional Markdown names and extensionless README, with deterministic priority. Nested READMEs are not selected by that helper.
- Owner search catches failures and returns an empty result. Global pipeline loading drops failed repository queries and can appear empty. The rebuild needs visible errors and partial-failure states.
- The backend has capabilities without matching controls in the inspected routes, including fork, owner transfer, blame, PR title/description update, webhook update and CI job approval. These are candidates for a separate product decision, not assumed feature-parity obligations.

Sources: [README selection](../../web/projects/studio/app/utils/gitReadme.ts), [blob reading](../../git/git/src/main/kotlin/bosca/git/service/RepositoryBrowseServiceImpl.kt), [global pipelines](../../web/projects/studio/app/pages/git/pipelines/index.vue), and [Git SDL](../../git/git/src/main/resources/graphql/git.graphqls).

## Existing BML foundation

`profiles-web` supplies an application example with generated GraphQL operations, server-rendered BML components, declarative server actions, browser auth, branding configuration and native packaging. BML exposes `requireAuth`, caller-token GraphQL loading, same-origin `/graphql`, live island actions, typed contracts, and private response handling.

BML does not currently install a repository raw-file proxy, CI artifact download proxy or a GraphQL WebSocket proxy. These capabilities should not be assumed to arrive automatically by moving pages. Studio also relies on `/ci/**` proxying for run artifact downloads. The existing Git raw route serves streamed bytes at `/{owner}/{repo}/raw/{ref}/{path}` and verifies repository View permission. Commit SHAs can avoid ambiguous branch names containing slashes. Git LFS has its own transport and must not be confused with an ordinary image blob.

Sources: [Profiles app](../../apps/profiles-web/README.md), [BML server](../../bml/bml-server/src/main/kotlin/bosca/bml/server/BmlServer.kt), [BML islands](../../bml/docs/islands.md), [raw files](../../git/git/src/main/kotlin/bosca/git/transport/GitRawFileRoute.kt), and [live logs](../../web/projects/studio/app/composables/usePipelineLogs.ts).

## Current server composition

| Composition root | Current source state | Migration consequence |
| --- | --- | --- |
| `:git:git-server` | Installs authentication, Git and CI transport routes, and Git/core/CI/jobs/supporting provider registrars. No GraphQL module or BML UI is installed by its application entry point. | Integrate BML compilation/assets into this project and install the UI into its existing `BoscaApplication`. Add Git GraphQL schema, namespace controllers, dispatchers and subscriptions. Keep one engine/process. |
| `:server:bosca-server` | Directly depends on Git core/CI and implementation/jobs modules. Registers their providers, Git schema/dispatchers and Git/GitHub query/mutation accessors. Owns Git queue configuration and Git email pipeline installer composition. | Remove embedded Git implementation/API ownership after client replacements and execution/installer ownership are validated. Removing only the Studio pages does not achieve the requested separation. |
| `:server:bosca-runner` | Loads Git/CI/jobs providers and participates in source synchronization and CI requirement processing. | Audit queue/listener ownership and registrar dependencies during separation. The user's main-server requirement does not by itself request removing all Git from this runner. |
| BML and GraphQL routing | `BmlServer.install()` registers a forwarding `/graphql` handler. `GraphQLModule` installs native `/graphql` and `/graphqlws` routes. | Embedded hosting requires deliberate endpoint ownership and caller-auth binding; installing both unchanged risks a conflicting handler or forwarding loop. Existing raw/CI routes can serve the UI directly without a second-server proxy. |

Domain consumers include Pipeline and AI repository browse/write services, script/query source synchronization, and WorkOps release/deployment orchestration using Git repository and CI contracts. These need authenticated remote operations or Git-owned API operations before local Git providers can be removed from the main server. Account/profile and other platform data needed by the BML UI remain owned by their existing services.

Sources: [Git server startup](../../git/git-server/src/main/kotlin/bosca/git/server/Application.kt), [Git server build](../../git/git-server/build.gradle.kts), [main server build](../../server/bosca-server/build.gradle.kts), [main server startup](../../server/bosca-server/src/main/kotlin/bosca/server/Application.kt), [main server GraphQL](../../server/bosca-server/src/main/kotlin/bosca/server/graphql/BoscaGraphQLService.kt), [runner startup](../../server/bosca-runner/src/main/kotlin/bosca/server/Application.kt), [GraphQL routes](../../bosca-core/core/src/main/kotlin/bosca/configuration/GraphQLModule.kt), [Pipeline Git consumers](../../pipelines/pipelines/src/main/kotlin/bosca/pipelines/git/PipelineGitSyncServiceImpl.kt), [AI Git consumers](../../ai/ai/src/main/kotlin/bosca/ai/agents/git/AgentGitSyncServiceImpl.kt), and [WorkOps release consumers](../../workops/workops/src/main/kotlin/bosca/workops/service/ReleasePipelineServiceImpl.kt).

## Cutover dependencies

Studio's Git subsystem navigation and command palette link into the current routes. AI, analytics and pipeline features use repository/file selectors and source links; these remain in their owning Studio features. Release run details and pipeline failure recovery also link into Git.

The Git server's clone-URL browser redirect explicitly builds `app.url + /git/repositories/{id}`. Message samples carry Studio repository, PR, synchronization, permission and mapping URLs. The cutover must also trace actual notification URL producers and installer-backed templates, including already stored customization, rather than changing samples alone.

The main server's `DefaultGitEmailPipelinesInstaller` is an actual URL producer: it seeds repository, PR, synchronization, permission and mapping links from the platform app URL. Its composition and origin configuration need to move with Git ownership while preserving edited installed pipelines.

Sources: [subsystem navigation](../../web/projects/studio/app/composables/useSubsystems.ts), [command palette](../../web/projects/studio/app/components/shell/CommandPalette.vue), [release links](../../web/projects/studio/app/components/releases/ReleasePipelineSection.vue), [failure recovery links](../../web/projects/studio/app/components/pipelines/PipelineRunDetailModal.vue), [Git browser redirect](../../git/git/src/main/kotlin/bosca/git/transport/GitRepositoryRedirectRoute.kt), and [message samples](../../apps/bosca-messages/src/main/kotlin/bosca/messages/Samples.kt).

## Validation coverage and remaining checks

Existing editor unit tests explicitly assert textarea behavior. README tests stub MDC and verify loading/ref changes and safe parser configuration; they do not fetch an image. Profile search tests assert the default name-only label. Select unit tests check interactions and async selected-label retention, not browser layout. These tests describe current behavior but do not establish the requested UX quality.

No application tests or builds were run for this documentation-only inventory. This checkout has no `web/node_modules`. Before implementation, deliberately inspect the supplied repository read-only: identify the Markdown file and ref, its exact image syntax, the resolved URL, response status, content type and auth behavior. Record dropdown bounding boxes closed/open and editor viewport geometry. Use isolated fixtures for subsequent mutation tests.

The [BML migration specification](bml-git-application-spec.md) defines the target behavior and acceptance checks.
