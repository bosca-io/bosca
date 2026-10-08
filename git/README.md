# bosca-git

Native git hosting platform with WorkOps integration, source ref management for scripts and queries, and a full GraphQL API. Implements the git smart HTTP protocol on top of a Distributed File System (DFS) backed by PostgreSQL and S3-compatible object storage, enabling multi-pod horizontal scaling.

## Modules

| Module | Description |
|---|---|
| `core-git` | Domain models and service interfaces (pure contracts) |
| `core-git-ci` | CI contracts |
| `git` | Full implementation — DFS, services, transport, GraphQL, repositories |
| `git-ci` | CI implementation |
| `git-jobs` | Background job workers (GC, purge, webhooks, maintenance) |
| `git-server` | Executable server — composes modules into a GraalVM native binary |

## Prerequisites

- **JDK 25** (resolved via Gradle toolchains)
- **Docker** and **Docker Compose** (for local Postgres, NATS, S3Proxy)
- **Git** and **Git LFS** on `PATH` for the end-to-end CLI tests. On macOS,
  install Git LFS with `brew install git-lfs`; verify it with `git lfs version`.

## Build

Run these commands from the **workspace root**. `git` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :git:test  # Test Git modules
./gradlew :git:git-server:run  # Run the Git server
./gradlew :git:git-server:nativeCompile  # Build a native image
```

## Local Infrastructure

From the workspace root:

```bash
cd git
docker compose up -d               # Start services
docker compose down                # Stop services
```

| Service | Port |
|---|---|
| PostgreSQL | 5433 |
| NATS | 4222 |
| S3Proxy | 8000 |

## Architecture

JGit operates against S3 object storage with PostgreSQL metadata instead of local filesystem, enabling stateless multi-pod deployments. GraphQL batch resolution uses Caffeine caching for permission evaluations and repository lookups. Webhooks, task key extraction, and source ref sync are event-driven via NATS pub/sub. Repositories support soft-delete with 30-day retention before purge jobs permanently remove them.

## Build execution permissions

Grant repository `EXECUTE` to the security groups whose members should run builds. Repository `EDIT` alone does not authorize manual starts, reruns, build-anyway actions, or build approvals. WorkOps release starts and promotions require both repository write and execution grants, alongside their existing program and environment permissions.

Accepted push/tag updates refresh the pipeline catalog from the current default branch independently of build execution permission. Trigger matching and job definitions use the triggering commit; historical snapshots do not overwrite live catalog metadata, schedules, or environments. Removed files are archived so pipeline IDs, run history, and trigger occurrences survive removal and reappearance. Automatic CI checks the initiating principal's current execution permission before creating runs. Missing, deleted, or unauthorized principals do not start builds. Scheduled CI retains its assigned execution identity and permission checks.

Apply migrations `V41__pipeline_trigger_occurrences.sql` and `V43__pipeline_catalog_archival.sql` through the registered Git migrations before running the updated CI worker. Each durable trigger job can create one run per pipeline; its reservation, jobs, and initial commit statuses commit together. A rolled-back attempt can retry, and redelivery skips completed creation. This does not deduplicate separately submitted events.

Deploy the updated GraphQL schema before the updated Studio: its build controls query `GitRepository.canExecute` and `WorkOpsReleaseRepositoryAccess.canExecute`.

CI stores resolved artifact declarations on each producing job; `GitPipelineJob.artifacts` exposes those associations. The job service verifies each producer's declared artifacts before recording success. When every job declaring the same type, namespace and coordinate within a run succeeds, the registry integration notifies the artifacts service that the stored version has completed. Artifacts dispatches `bosca.artifacts.model.ArtifactCompleted`, carrying the version ID, producer job IDs, build commit and initiating principal through the existing event-to-pipeline dispatcher. Unrelated jobs do not delay completion, while failed, cancelled and skipped producers block it. Destination selection and publication belong to separate artifacts pipeline nodes.

Completion dispatch shares the job's terminal-state transaction and uses normal generated event dispatch. Publication is a normal pipeline action in the artifacts module, with the pipeline's existing execution and retries. Studio destination configuration and verification remain pending.

The server compiles to a GraalVM native image for sub-100ms startup and low memory footprint, with a JVM fallback via Shadow JAR.

## GitHub pairing and delivery intake

Apply the registered Git migration `V44__github_intake.sql` before using intake. Administrators configure `savePair`, `mapUser`, and `unmapUser` under the `github` GraphQL mutation namespace. The `github` query namespace exposes `pair`, `users`, and `deliveries`. Repository pairing uses GitHub's immutable numeric repository ID; user mappings use immutable human user IDs and Bosca principal IDs. These mappings grant no permissions. Pairing and delivery history queries are administrator-only because accepted payloads can contain private repository content.

Credentials remain in the encrypted Pipeline secret store, managed through Pipelines → Secrets. A pair stores the webhook and token secret names. Pairing defaults to disabled; enabling it requires both secrets to exist. Configure the repository webhook to send JSON to `/api/webhooks/github/<Bosca repository UUID>` with the matching webhook secret.

Intake verifies `X-Hub-Signature-256` against the exact raw body bytes and checks the signed repository ID. `X-GitHub-Delivery` reserves one persisted occurrence, including across redelivery; changing the body, event, or repository under that identifier returns a conflict. Accepted push and paired-repository pull request deliveries retain the original user mapping. Unknown and bot users remain unattributed, and fork-origin pull requests and other event types are recorded as ignored.

Each host retains its own permission and branch-protection authority. Webhook imports require the verified occurrence's mapped, active Bosca principal to have current repository `EDIT` permission. Explicit pulls use the calling administrator's active Bosca principal and current repository `EDIT` permission instead. GitHub acceptance and repository pairing confer no Bosca write permission. Both import paths obey Bosca's required-PR, force-push, deletion, push-access and linear-history rules. Exports use GitHub's ordinary Git and API authorization; configure the transport credential without branch-protection bypass rights. Rejections remain failures.

Each eligible delivery dispatches a `GitHubDelivery` event through the standard event system. Configure an inbound pipeline with `triggered = true` and input type `bosca.git.model.GitHubDelivery`; matching uses the event type and does not require a particular pipeline key. The event carries the delivery ID, originating principal and original receipt time. Redelivery dispatches the stored occurrence again, preserving that data. Unknown and bot senders have no originating principal.

Event dispatch uses the existing pipeline jobs, execution identity, history, trigger configuration and admission limits. Synchronization nodes consume the delivery data and must preserve its originating principal when calling Git services so build authorization checks the initiating person. Those nodes must track completed synchronization operations so redelivery does not repeat them.

Apply `V45__github_ref_synchronization.sql` for branch/tag synchronization state. The GitHub package installs two event-triggered graphs: **GitHub: Import Refs** consumes verified push deliveries, and **GitHub: Export Refs** consumes ordinary `RefUpdateEvent` events. Their nodes use the existing durable pipeline backing jobs. Reinstalling preserves graphs already carrying those keys.

All six installed GitHub synchronization pipelines carry the **Git** tag. Package and installer version `1.2.0` adds it to existing pipelines while preserving their graphs, custom tags, schedules, execution limits, and endpoint settings. Pipelines already tagged Git are left unchanged.

Transfers preserve the original commits and annotated tag objects. Inbound changes use the ordinary ref notifier with the delivery's persisted originating principal. Completed push deliveries are recorded, so redelivery does not import again or trigger additional builds. Echoes whose refs already agree are no-ops; stale occurrences cannot replace a newer source ref. Fast-forwards converge. Force updates and deletions require an unchanged common target, or the matching old value on initial synchronization. Independent edits, including update-versus-delete races, retain both sides and appear in the administrator-only `github.refStates` query.

Inbound ref changes, synchronization state and notification registration share a PostgreSQL transaction. An interrupted import rolls back the ref and can retry with its original principal. The repository write lock remains held through commit.

**GitHub: Reconcile Refs** runs hourly through the existing pipeline scheduler. Verified, unprocessed pushes are redispatched as ordinary events; their original attribution and current permission checks are retained. Each ref commits before releasing its write lock, allowing native pushes between transfers. A failed pair does not prevent the remaining pairs from reconciling; the run reports accumulated failures afterward. Without a verified originating user, scheduled reconciliation observes inbound changes and waits for their webhook rather than changing Bosca refs. A GitHub-only advance remains awaiting synchronization rather than being labeled an independent-edit conflict. The administrator's explicit `github.reconcileRefs(repositoryId: ...)` action instead uses their current repository Edit permission, as described below. A later verified push may authorize CI for an already permitted PR merge only while that exact imported ref write remains current. A subsequent native write prevents this attribution, including when it restores the same SHA. Reconciliation preserves unresolved initial deletion conflicts and never independently merges divergent histories.

Apply `V46__github_pull_request_synchronization.sql` and GitHub package/installer version `1.2.0` for PR lifecycle synchronization. The package adds **GitHub: Import Pull Requests**, **GitHub: Export Pull Requests**, and hourly **GitHub: Reconcile Pull Requests** graphs. They use the existing events and durable pipeline jobs. Synchronization reads current provider/native state and mirrors titles, descriptions, branches, draft/open/closed status, and completed merges. Native PR version checks preserve racing edits; common snapshots expose incompatible changes through the administrator-only `github.pullRequestStates` query. Administrators can also call `github.reconcilePullRequests(repositoryId: ...)`.

GitHub authors must have an active mapped Bosca principal and a primary profile before import; missing mappings remain visible and are retried. Reviews, comments, approvals, and assignees stay on their original host. Exported PRs record original Bosca authorship in a bounded body footer. Footer parsing uses known descriptions to distinguish complete managed blocks from quoted or damaged blocks; known quotations and ambiguous blocks remain user content even when edited or when the managed footer is removed. Committed creation reservations retain their original content baseline when recovering a lost response, and committed outbound snapshots let partial REST/draft updates resume. Recovery checks complete references and the reserved source branch; multiple matching counterparts or reservations surface as problems. Completed merges transfer existing commits: synchronization never asks the destination to create another merge. If GitHub does not recognize a Bosca merge indirectly, the counterpart closes with the original merge SHA recorded. Untracked completed PRs on either host are ignored. A tracked PR that completes without a counterpart and unsupported head-branch changes surface as problems.

Metadata exports send only changed REST fields. Untouched descriptions, branches, and lifecycle state are preserved, and changes observed before or after a write surface as problems. Description comparisons normalize CRLF to LF without changing stored Bosca text. GitHub does not support conditional PR updates, so simultaneous edits to the same written field can still overwrite one another. A confirmed rejected creation or metadata update releases its obsolete input while retaining the counterpart identity. A completed native merge advances an earlier outbound intention and accepts GitHub's recognition of the identical merge without accepting unrelated metadata changes.

PR imports require a signed occurrence matching the current provider state and a currently authorized Bosca writer. Ref changes require their own verified pushes; a title/status editor cannot authorize newer commits observed while fetching a PR. A GitHub-completed merge requires a mapped, authorized merger and the paired Bosca PR's current version, open state, resolved dependencies, matching reviewed source, active approvals and required CI checks. The permitted merge transfers existing history without authorizing CI as the metadata editor. Unverifiable protection requirements block import and appear in PR synchronization state. Verified push deliveries retain build attribution through the ref pipeline.

Native runtime checks passed for standalone startup and migrations, webhook rejection, the GitHub HTTP client against a local fixture, and Git ref transfers with protection and attribution checks. Live GitHub API and protection checks remain outstanding. GitHub release publication is implemented in the artifacts module; Studio artifact destination configuration remains pending.

### Studio configuration

Open a repository and choose **Settings** to manage its GitHub synchronization, outgoing webhooks, branch protection, permissions, and storage utilities. Each panel uses the open repository; no second repository selection is needed. The selected panel is stored in the URL for direct links and browser navigation. Repository pipeline secrets remain on the repository's **Secrets** tab. CI agents and shared GitHub user mappings remain in Git's global Settings menu.

Administrators can use **Repository → Settings → GitHub Sync** to create or update its GitHub pairing and enable or disable synchronization. Add the token and webhook secrets directly beside their selectors, or select credentials already stored through **Pipelines → Secrets**. Saving a secret selects its name without resetting the pairing form; values are encrypted and cannot be read back. Replacing an existing name updates that shared secret wherever it is used. Bosca looks up the immutable GitHub repository ID using the owner, repository name and selected token on first save or when the owner/name changes; no manual ID entry is needed. A disabled pairing can be created before the webhook secret exists, but its token must be available for the lookup. An existing pairing with an unchanged owner/name can be disabled even if its credentials are missing. Reload pairing to pick up externally created secrets or the latest version after a concurrent edit. The immutable GitHub repository ID cannot be changed after the first save.

Configure GitHub's JSON webhook for push and pull request events at the displayed payload URL, using the referenced webhook secret. Studio builds this full URL from its `gitServerUrl` public runtime setting and the repository's webhook path, preserving any configured base path. Set `NUXT_PUBLIC_GIT_SERVER_URL` to the Git server's externally reachable address in deployment; `GIT_SERVER_URL` supplies its configured default, which is `http://localhost:8080` in local development. The existing GitHub package installs the import, export and hourly reconciliation pipelines; manage their execution configuration in **Pipelines**. Pairing alone does not replace pipeline configuration.

If repository lookup returns HTTP 404, check the entered GitHub owner and repository name and the selected token's access to that repository. GitHub also returns 404 for inaccessible private repositories. Organization token approval and SSO authorization may be required. The failed lookup does not save a pairing; correct the configuration and retry.

**Git → Settings → GitHub User Mappings** looks up a GitHub username and maps its immutable human-user ID to the selected Bosca user. Mappings do not grant permissions; repository Edit authorizes inbound changes and Execute authorizes builds. PR imports require the original author's primary profile. Removing a mapping preserves previously recorded delivery attribution.

**Synchronization history → Deliveries** shows the last import failure and current mapping or permission blockers, with links to user mappings and repository permissions. Pipeline active runs and run details show failed backing-work attempts while retries are still suspended. Grant repository Edit to a group the mapped user belongs to to allow an existing attributed delivery to retry. A delivery received without a mapping retains that original identity; configure the mapping for future deliveries and use **Pull from GitHub** to import existing branches and tags.

The registered `V47__github_delivery_problems.sql` migration stores delivery diagnostics and reserves one `GitHubSynchronizationFailed` event per verified delivery. A failed import rolls back its ref changes before recording the problem and dispatching the event; cancellation does not dispatch a failure. The default Git email package installs **Email Git Activity — GitHub Synchronization Failure**, an editable event-triggered pipeline that sends the repository owner the problem and recovery links using the `github-sync-failed` message template. Repeated job attempts do not dispatch duplicate failure events. Delivery diagnostics clear after synchronization succeeds.

Under **Synchronization history → Branches and tags**, **Pull from GitHub** imports current GitHub refs into Bosca and **Push to GitHub** exports current Bosca refs. These explicit operations require an enabled, available pairing and the calling administrator's repository Edit permission. Pull can import an existing repository without a webhook delivery or GitHub user mapping. It retains original commits and tags and sends ordinary ref notifications with the caller's principal; CI applies its normal Execute checks. Both directions preserve independent edits as conflicts. Source deletions propagate only for refs with a synchronized common value, subject to destination protections; untracked destination-only refs are preserved. These actions transfer branches and tags; pull request lifecycle recovery remains under **Pull requests → Reconcile**.

**Reconcile** on this same tab transfers current ref changes in either direction using the calling administrator's active repository Edit permission. It imports GitHub changes without waiting for a webhook or background pipeline and attributes ordinary ref/CI notifications to the caller. The common value selects the changed host; safe fast-forwards can converge when both hosts advanced along the same history. Independent edits remain conflicts, and destination protections still apply. Each ref commits separately, rechecking the pair version, availability and caller permission between commits. A later webhook echo does not repeat notifications for an already attributed import. Use Pull for an explicitly inbound operation when the GitHub token is read-only.

Synchronization history pages through branch/tag state, pull request counterpart mappings and snapshots, and accepted or ignored deliveries. A delivery's intake status does not claim that its synchronization completed. Conflicts show both hosts' observed state and the last common snapshot. For a conflicting branch or tag, **Resolve** opens a review dialog with **Keep GitHub** and **Keep Bosca** choices. The selected value replaces that ref on the other host; selecting an absent ref deletes its counterpart. Both reviewed values are checked against the fetched refs, and the destination write requires its reviewed value to remain current. A changed observation rejects the choice and refreshes history for another review. Destination branch protections still apply, and inbound changes notify the ordinary ref/CI path as the caller. To preserve changes from both histories, merge them in Git before pulling or pushing. Resolve pull request metadata conflicts on their owning host before reconciling. Pairing must be enabled for resolution and reconciliation. Transfers and reconciliation commit each ref separately; failures remain visible and refresh observations committed before the failure.

Local checks passed for the Studio controls, GraphQL operations, and an isolated mocked browser flow through Studio's login and proxy, including both explicit transfer directions, manual reconciliation of a GitHub advance, and conflict review. Repository settings checks cover fixed repository targeting, administrator visibility, branch-protection creation and editing, Execute permission grants and removal, outgoing webhook creation, utility confirmations, visible load failures, direct links, browser back navigation, and the shared mappings menu. All 41 focused Studio tests, focused lint and type checking passed, along with both browser checks. The earlier fresh full Git module run passed all 1,022 tests with no failures or skips, including real PostgreSQL/DFS/Git reconciliation without webhooks, background observation, conflict resolution, stale-review rejection, deletion choices, protection enforcement, cancellation rollback, and loopback GitHub API contracts. Kover gates passed at 98.34% line coverage and 85.50% branch coverage. These checks do not replace the outstanding isolated live GitHub API and branch-protection checks.

## Dependencies

- Bosca Framework libraries (auth, DI, navigation, storage, GraphQL)
- Bosca Core (annotations, models)
