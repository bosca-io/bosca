# Bosca Git synchronization and artifact publication

This spec covers bidirectional synchronization between Bosca Git and GitHub, authorization to use Bosca build infrastructure, and remote publication owned by Bosca Artifacts. Developers can work in either paired repository. CI/CD remains on Bosca infrastructure.

Status: implementation in progress. Native Git CI build authorization, trigger retry handling, repository pairing and GitHub user mappings, verified GitHub delivery intake, event dispatch to configured pipelines, bidirectional branch/tag synchronization, pull request lifecycle synchronization, artifacts-owned GitHub release publication through ordinary pipelines, and CI artifact completion events are implemented. Native Git startup and synchronization transport checks passed. Isolated live provider checks, Studio configuration and verification, package publication adapters and retention remain. Existing Git hosting, pipeline execution, permissions, and artifact storage are foundations to reuse.

## Implementation progress

- [x] Native push/tag CI refreshes the current default-branch catalog independently of build permission. Trigger matching and jobs use the triggering commit without overwriting live catalog state. Removed files retain their pipeline IDs, history, and trigger occurrences through archival; run creation checks the initiating principal's current repository `EXECUTE` grant and preserves attribution.
- [x] Manual CI starts, reruns, build-anyway actions, and build approvals require repository `EXECUTE`; WorkOps release starts and promotions require repository write and execution grants.
- [x] Studio release and Git pipeline build controls expose the execution requirement.
- [x] Native CI reserves each durable trigger job/pipeline occurrence in PostgreSQL. Reservation, run, jobs, and initial commit statuses share a transaction; redelivery skips committed work, and rolled-back attempts can retry.
- [x] Administrator-controlled repository pairing and GitHub user mappings, raw-byte HMAC-SHA256 verification, and PostgreSQL delivery deduplication. Intake preserves originating principal attribution; unknown and bot users remain unattributed. Fork-origin pull request deliveries and unsupported event types are recorded as ignored.
- [x] Event dispatch to existing triggered pipelines. Eligible deliveries dispatch `GitHubDelivery` events carrying the webhook ID, original receipt time and principal attribution. Redelivery dispatches the stored occurrence again. Unknown and bot users remain unattributed in the event data. The pipeline engine uses its established execution identity and job-history hooks.
- [x] Branch/tag synchronization nodes and directional event pipelines, preserving original objects and principal attribution, with persisted common refs, echo detection, conflict handling, and hourly reconciliation through existing pipeline jobs.
- [x] Typed GitHub pull request API operations for current-state reads, paged listing, creation, metadata/lifecycle updates, and draft transitions, with local HTTP contract tests. Every operation verifies the paired repository's immutable ID before accessing the counterpart.
- [x] Pull request counterpart mappings and bidirectional title, description, branch, draft/open/closed status, and completed-merge synchronization through owning services. Common snapshots expose observed conflicts and native version comparisons retain racing Bosca edits. Metadata exports write only changed fields; GitHub provides no conditional PR writes to prevent simultaneous edits to the same written field. Committed creation reservations and outbound snapshots recover lost responses and partial provider updates. Rejected creation or metadata update input can refresh without replacing its counterpart identity, descriptions compare with consistent line endings, and completed native merges advance older lifecycle intentions. Existing pipeline jobs perform event-driven synchronization and hourly reconciliation.
- [x] Artifacts-owned GitHub release destinations for raw versions, fixed file manifests, references to the existing encrypted Pipeline secret store, an ordinary pipeline action, and independent publication/verification results. Publication requires an existing tag pointing to the requested commit and preserves existing uploaded assets.
- [x] Artifact declarations remain associated with their resolved CI jobs and are exposed through `GitPipelineJob.artifacts`. The job service verifies each producer's declared outputs before recording success and notifies the registry when all producers of the same type, namespace and coordinate in that run succeed. The artifacts service dispatches `ArtifactCompleted` with the stored version ID, producer job IDs, build commit and initiating principal through the existing event-to-pipeline path. Unrelated jobs do not delay completion.
- [x] Separate artifacts-owned **Get Artifact Publication Destinations** and **Publish GitHub Release** nodes select matching artifact/destination pairs and push one pair using ordinary pipeline execution and retries. The existing **For Each** node handles multiple destinations. CI continues uploading and reporting ordinary job completion, without a separate publication POST or YAML flag.
- [ ] Configure and verify GitHub synchronization and artifact publication through Studio.
- [ ] GitHub package registries and Maven Central adapters.
- [ ] Remote retention, pending the version count, stable/prerelease treatment and exemptions.
- [x] Raw publication validation: PostgreSQL migrations and generated repositories, transaction commit/rollback and concurrent producer reports, typed pipeline action execution, artifact push permission checks, lost upload responses, independent verification failures, fixed manifests, credential rotation and GraphQL configuration authorization.
- [x] Verified event-to-pipeline integration tests with PostgreSQL migrations, generated event dispatch and catalog, pipeline matching and run jobs, the real executor, transaction rollback, redelivery and originating-user data.
- [x] Ref synchronization integration tests with real PostgreSQL DFS refs/packs and Git transports: cancellation and ledger rollback, write-lock/commit boundaries, concurrent intake and reconciliation, late verified attribution, and native echo deduplication.
- [x] Pull request integration tests with PostgreSQL migrations, generated JDBC mapping, the native PR service, version races, lost responses, partial draft updates, original authorship, merge history, GraphQL authorization, and generated event-to-pipeline backing jobs. Native merge metadata and DFS target refs share a transaction; cancellation rolls both back, and the repository write lock remains held through commit.
- [ ] Isolated provider API checks for PR lifecycle and merge behavior.
- [x] Git server native-image compilation with GraalVM 25 on macOS arm64, using `--no-configuration-cache`.
- [x] Native runtime checks with GraalVM 25 on macOS arm64: the standalone server starts against isolated PostgreSQL/NATS, applies Git migrations through V46, passes liveness/readiness, and rejects an unpaired webhook. A separate entry point compiled through the production native task exercises the actual GitHub client against a loopback HTTP fixture and ref synchronization against temporary Git repositories: PR serialization, creation and metadata updates, draft/open/closed transitions, immutable repository checks, rejected requests, fetch/push, original commits and annotated tags, echo deduplication, protected-import rejection, and deferred CI attribution. Live provider behavior remains a separate check.

The native CI occurrence reservation identifies a durable queue job. GitHub intake preserves one occurrence under the `X-GitHub-Delivery` identifier; a repeated identifier with a different repository, event, or payload digest is rejected. Pairing uses the immutable GitHub repository ID, and credentials are references to the existing encrypted Pipeline secret store. Apply Git migrations `V44__github_intake.sql`, `V45__github_ref_synchronization.sql`, and `V46__github_pull_request_synchronization.sql`. GitHub package and installer version `1.1.0` install separate ordinary inbound/outbound ref and PR graphs, plus hourly reconciliation for each. Inbound graphs consume `bosca.git.model.GitHubDelivery`; outbound PR graphs consume `bosca.git.model.PullRequestEvent`. Existing operator-edited graphs are preserved. Ref synchronization records completed push deliveries and retains their originating principal. PR metadata editors do not authorize builds for newer commits observed during synchronization: PR ref changes require separately verified push evidence, and permitted PR merge transfers defer builds to their verified push occurrence. Later CI attribution requires that exact permitted imported ref write to remain current. GitHub release publication through ordinary pipelines and CI artifact completion events are implemented; Studio destination configuration and verification, package adapters and remote retention remain pending.

## Two synchronization pipelines

The two Bosca Pipelines represent sync direction. Their event sources determine which pipeline runs.

| Pipeline | Trigger | Direction |
| --- | --- | --- |
| Outbound synchronization | Bosca Git events | Bosca Git to GitHub |
| Inbound synchronization | GitHub webhooks | GitHub to Bosca Git |

Both use the existing Bosca Pipeline node graph and durable job execution in the `pipelines` component. They can share Pipeline Nodes, configured for the appropriate source and destination. These workflows are separate from the repository CI jobs defined in `.bosca/pipelines/`.

### Bosca Git to GitHub

1. A Bosca Git event starts the outbound pipeline.
2. Nodes resolve the paired repository and any corresponding pull request.
3. The pipeline compares the change with the last synchronized state.
4. Nodes transfer the relevant refs or apply the pull request changes to GitHub.
5. The pipeline records the result and counterpart identifiers for subsequent events and retries.

### GitHub to Bosca Git

1. A verified GitHub webhook starts the inbound pipeline.
2. Nodes resolve the configured repository pair, the corresponding pull request, and the originating user.
3. The pipeline compares the change with the last synchronized state.
4. Nodes transfer the relevant refs or apply pull request changes through Bosca's owning services.
5. The pipeline records the result. Any resulting build must satisfy the initiating person's execution permission.

Webhook authenticity and permission to start a build are separate checks. An authentic delivery does not make its originating user trusted to build. The integration must preserve that user through mirrored changes rather than allowing the synchronization service's credentials to authorize a build implicitly.

Both hosts remain authoritative for their own permissions and branch protections. Every inbound write requires the verified occurrence's mapped, active Bosca principal to have current repository `EDIT` permission. Ref imports enforce Bosca's required-PR, force-push, deletion, push-access and linear-history rules. PR authorization is bound to a signed observation matching the current provider state; reconciliation cannot invent an originating user. GitHub applies its ordinary Git and API authorization to exports, using a configured transport credential without branch-protection bypass rights. A rejection by either host stops the operation.

## Shared nodes and synchronization state

Reuse existing nodes and domain services where they provide the required operation. Add integration nodes for missing GitHub or Git synchronization operations. Shared operations may include counterpart lookup, state comparison, ref transfer, pull request synchronization, and recording synchronization results; destination-specific API behavior can remain in the relevant node implementation.

Keep workflow orchestration in the two pipelines. Reuse the existing pipeline engine for execution, run history, retries, and scheduling. Persistent synchronization state must support:

- Pairing Bosca and GitHub repository and pull request identifiers.
- Identifying processed deliveries and changes so retries do not duplicate pull requests or builds.
- Recording the last synchronized refs and relevant pull request state.
- Recognizing changes produced by synchronization so they do not echo indefinitely between systems.
- Detecting concurrent changes rather than silently overwriting one side.

The exact model and node boundaries must follow the owning components' existing conventions. No separate synchronization execution engine is required.

## Branches and pull requests

Branches and tags synchronize between the configured repositories. Opening a pull request in either paired repository creates or updates its counterpart. Subsequent branch changes and supported pull request lifecycle changes synchronize through the same directional workflows.

GitHub forks are outside the automatic synchronization workflow. Contributions from a fork are brought into a branch in Bosca, followed by a branch-to-main pull request. Importing into that branch does not mean merging directly into `main`. Development in branches of the configured GitHub repository is also supported. Bosca-hosted forks retain their existing behavior.

Merging propagates the resulting history without independently generating a second merge on the other host. GitHub-completed merges import existing refs and record the original SHA and timestamp through the Bosca PR service. Bosca-completed merges export existing refs. If GitHub recognizes the transferred history as an indirect merge, its merged status is retained; otherwise the counterpart is closed with a record of the exact Bosca merge SHA. This preserves squash/rebase history without asking GitHub to recreate it. Completed merges cannot be reverted or rewritten by metadata synchronization.

Importing a GitHub-completed merge first verifies the mapped merger's current Bosca write permission and the paired PR's version, open state, resolved dependencies, reviewed source SHA, active approvals and required status checks. Dismissed reviews confer no approval. Destination push restrictions and linear-history rules still apply to the transferred ref. A protection requirement that cannot be verified blocks import and is recorded as a synchronization problem.

Reviews, comments, approvals, and assignees remain on their original host in this first lifecycle implementation. Import waits for the original GitHub author to have a mapped, active Bosca principal and a primary profile; it does not invent an integration author. GitHub creates exported PRs under the configured token's identity, with original Bosca authorship recorded in a bounded body footer. User text outside that footer remains part of the description. Known descriptions distinguish complete managed footers from quoted or damaged blocks; known quotations and ambiguous blocks remain user content even when edited or when the managed footer is removed. Creation reservations retain their original content baseline independently of later observations, so recovering a lost response preserves subsequent edits and detects incompatible changes. Recovery checks complete references and the reserved source branch; multiple matching counterparts or reservations produce an administrator-visible problem. Observed incompatible changes on both sides since their common snapshot produce an administrator-visible problem rather than choosing a winner. Exports omit unchanged fields, including descriptions that do not need a merge record. GitHub's PR update API does not support conditional writes, so another edit to the same field between the final read and write can still be overwritten; a subsequent read cannot recover that overwritten value. GitHub does not allow changing an existing PR's head branch through the update API; such a change is surfaced for resolution. A Bosca PR already closed or merged before synchronization began tracking it is not exported, just as untracked closed or merged GitHub PRs are not imported, so neither host's history becomes counterparts or problems. A tracked PR that completes before its counterpart exists is surfaced once, rather than creating an open PR or new history merely to manufacture a counterpart. Provider mutation behavior still requires the isolated API checks listed above.

## Authorization to build

Only identities authorized to execute builds may cause Bosca servers to build code. Trusted people are responsible for choosing safe work to execute.

The GitHub integration must map the originating GitHub user to a Bosca identity and evaluate build execution permission through Bosca's existing group-based permission system. Unknown or unauthorized users cannot start builds. Public repository access, forking, and opening a pull request confer no build permission.

Apply this rule to automatic triggers, manual starts, reruns, and replayed deliveries. Mirrored ref updates must not bypass it by appearing to originate from a privileged integration identity. Scheduled work uses an explicitly authorized execution identity.

This scope does not add a separate per-commit approval process. A trusted person may import a contribution into an authorized branch and start its build. The existing permission and secret mechanisms should be reused; the integration must not assume all existing start paths already use the same permission action.

## Outages and reconciliation

Each Git host remains usable when the other is unavailable. Synchronization resumes when connectivity returns. Scheduled reconciliation should invoke the same node operations to recover missed events and incomplete work.

Inbound reconciliation waits for verified user attribution before changing refs or PR metadata. Missing webhooks produce observed divergence or a PR synchronization problem; the administrator running reconciliation does not become the originating writer. Accepted occurrences retry with their original principal and current permission checks.

Fast-forward changes can converge automatically. Concurrent branch histories, conflicting tag changes, and incompatible pull request edits must retain both sides' information and surface a conflict for resolution. The implementation must define deletion and force-push handling relative to the last synchronized state.

Source development on GitHub can continue while Bosca is unavailable; Bosca-hosted builds and deployments still require Bosca. This design does not add a second CI/CD system on GitHub.

## Remote artifact publication

Bosca Artifacts owns remote publication. CI publishes completed outputs to Bosca Artifacts, which pushes the same outputs to configured destinations such as GitHub or Maven Central. Per-pipeline publication scripts should give way to this ownership as destination support is implemented.

CI already stores resolved artifact declarations on each producing job. The job service verifies each producer's outputs before saving success. An artifact is ready when every job declaring the same type, namespace and coordinate within the run succeeds. A failed, cancelled or skipped producer blocks that artifact's completion; unrelated jobs can continue. The existing registry integration then calls `ArtifactRepositoryService.completeVersion`. The artifacts service emits `bosca.artifacts.model.ArtifactCompleted` with the stored version ID, producer job IDs, build commit, initiating principal and creation time. It uses ordinary generated event dispatch into configured pipelines, with no changes to pipeline execution.

Completion dispatch shares the successful producer's transaction through the existing generated event dispatcher. Pipeline execution owns publication retries. The registry's earlier `ArtifactVersionPublished` announcement remains an availability signal and does not indicate that every file upload has completed.

The completion event and separate destination lookup and publication nodes are implemented. Git sync and artifact destination configuration and verification belong in Studio, using the existing owning services and APIs. CI does not need to call a separate publication endpoint.

The artifacts server must track publication and verification separately for each destination. A remote failure leaves work pending or failed with a retry path, while the internal artifact remains available. Retrying publication reuses the existing bytes rather than rebuilding a version.

The first adapter publishes raw artifact versions as GitHub release assets. Administrators configure immutable repository targets and Pipeline secret references through the artifact admin API. Credentials remain in the existing encrypted Pipeline secret store managed in Studio. A triggered pipeline accepting `bosca.artifacts.model.ArtifactCompleted` uses **Get Artifact Publication Destinations** to return matching artifact/destination pairs without publication side effects. **For Each** runs a body pipeline containing **Publish GitHub Release** for each pair. The push node checks artifact push permission under its execution identity, fixes the version's file manifest and publishes only the selected destination. Publication and verification timestamps persist independently. Pipeline retries reuse the original input and stored bytes. See [artifact publication configuration](../artifacts/README.md#github-release-publication) for the migration, shared Pipeline secret configuration and pipeline setup.

The adapter verifies the immutable GitHub repository ID and existing tag's exact commit before publishing. Managed drafts recover incomplete uploads; matching uploaded assets are reused and conflicting bytes are rejected. It does not create tags or replace uploaded assets. PostgreSQL/NATS integration tests, generated pipeline action tests and GraphQL configuration authorization tests exercise the local workflow. Live provider checks remain separate.

GitHub destinations depend on artifact type: release assets for downloadable binaries and installers, and the appropriate package registry for packages and container images. Maven Central requires its own publication adapter. Each adapter must honor the destination's validation, authentication, and version immutability rules.

## Retention

Keep the latest configured N versions of each artifact on GitHub while retaining artifacts in Bosca under an independent internal policy. Define N per independently versioned artifact, not as one combined count across all products in a repository.

Use GitHub cleanup policies where they support the required behavior. Where they do not, use scheduled cleanup through the destination API, with execution remaining outside GitHub CI/CD. Validate platform deletion restrictions before promising a strict maximum count.

Intentional remote expiry must update the desired publication state so synchronization does not restore the deleted version. Cleanup must not delete the internal copy as a side effect.

Before implementation, settle the value of N, stable versus prerelease treatment, and whether supported or deployed versions receive retention exceptions.

## Existing implementation to reuse

- [Bosca Git](../git/README.md) owns repository and pull request services.
- [Pipeline jobs](../pipelines/pipelines/src/main/kotlin/bosca/pipelines/trigger/PipelineJobs.kt) provide event, on-demand, and scheduled execution through the existing job system.
- [Pipeline API execution](../pipelines/pipelines/src/main/kotlin/bosca/pipelines/routes/ExecutePipeline.kt) already accepts pipeline input. Its current JSON endpoint is not a GitHub webhook verifier; verified delivery intake and context propagation are integration work.
- [Scheduled CI execution](../git/git-ci/src/main/kotlin/bosca/git/ci/jobs/PipelineScheduleExecutor.kt) already validates an assigned execution identity and repository execution permission.
- [CI secret resolution](../git/git-ci/src/main/kotlin/bosca/git/ci/service/PipelineSecretServiceImpl.kt) already implements secret storage, environment scope, and initiating-principal checks for declared, scoped, or explicitly permissioned secrets.
- [Bosca Artifacts](../artifacts/README.md) already provides storage and registry protocols. Existing [CLI](../.bosca/pipelines/release-cli.yaml) and [server image](../.bosca/pipelines/release-image-bosca-server.yaml) pipelines publish to Bosca Artifacts; external forwarding belongs to the artifacts server.

These are source-level observations, not a claim of new runtime validation or a backlog to rebuild the existing systems.

## Acceptance checks

- A Bosca branch or pull request event produces the corresponding GitHub change through the outbound pipeline.
- A verified GitHub event produces the corresponding Bosca change through the inbound pipeline.
- Repeated deliveries, pipeline retries, and reflected events do not duplicate changes or builds.
- GitHub forks and unknown or unauthorized users cannot consume Bosca build infrastructure through the integration.
- A trusted person can start a build for code imported into an authorized branch; Bosca-hosted fork behavior is preserved.
- The original user remains attributable through synchronization, and integration credentials do not grant that user build permission.
- An outage can be followed by reconciliation using the same node operations; divergent changes are reported without discarding either side.
- Remote publication preserves artifact content, retries without rebuilding, and records each destination's result.
- Remote retention removes eligible older versions without deleting the internal copy or causing republication.

Validate these with focused behavioral tests and integration tests exercising actual event-to-pipeline execution and persistence. Provider API behavior, especially pull request merge handling and package cleanup, also needs deliberate checks against isolated test repositories and packages.

## Deferred hardening

- [ ] TODO: Harden CI/CD beyond trusting the person who starts the build. Review worker and internal-network isolation, host credentials, shared caches, job secret exposure, separation of build and publication/deployment credentials, and trusted pipeline configuration. This is deferred from the initial integration scope.
