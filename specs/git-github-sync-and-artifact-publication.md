# Bosca Git synchronization and artifact publication

This spec covers bidirectional synchronization between Bosca Git and GitHub, authorization to use Bosca build infrastructure, and remote publication owned by Bosca Artifacts. Developers can work in either paired repository. CI/CD remains on Bosca infrastructure.

Status: implementation in progress. Native Git CI build authorization, trigger retry handling, repository pairing and GitHub user mappings, verified GitHub delivery intake, event dispatch to configured pipelines, and bidirectional branch/tag synchronization are implemented. Pull request mirroring and remote artifact publication remain to be implemented. Existing Git hosting, pipeline execution, permissions, and artifact storage are foundations to reuse.

## Implementation progress

- [x] Native push/tag CI refreshes the current default-branch catalog independently of build permission. Trigger matching and jobs use the triggering commit without overwriting live catalog state. Removed files retain their pipeline IDs, history, and trigger occurrences through archival; run creation checks the initiating principal's current repository `EXECUTE` grant and preserves attribution.
- [x] Manual CI starts, reruns, build-anyway actions, and build approvals require repository `EXECUTE`; WorkOps release starts and promotions require repository write and execution grants.
- [x] Studio release and Git pipeline build controls expose the execution requirement.
- [x] Native CI reserves each durable trigger job/pipeline occurrence in PostgreSQL. Reservation, run, jobs, and initial commit statuses share a transaction; redelivery skips committed work, and rolled-back attempts can retry.
- [x] Administrator-controlled repository pairing and GitHub user mappings, raw-byte HMAC-SHA256 verification, and PostgreSQL delivery deduplication. Intake preserves originating principal attribution; unknown and bot users remain unattributed. Fork-origin pull request deliveries and unsupported event types are recorded as ignored.
- [x] Event dispatch to existing triggered pipelines. Eligible deliveries dispatch `GitHubDelivery` events carrying the webhook ID, original receipt time and principal attribution. Redelivery dispatches the stored occurrence again. Unknown and bot users remain unattributed in the event data. The pipeline engine uses its established execution identity and job-history hooks.
- [x] Branch/tag synchronization nodes and directional event pipelines, preserving original objects and principal attribution, with persisted common refs, echo detection, conflict handling, and hourly reconciliation through existing pipeline jobs.
- [x] Typed GitHub pull request API operations for current-state reads, paged listing, creation, metadata/lifecycle updates, and draft transitions, with local HTTP contract tests. Every operation verifies the paired repository's immutable ID before accessing the counterpart.
- [ ] Pull request counterpart mappings and bidirectional lifecycle synchronization, including merge propagation, conflict handling, and reconciliation.
- [ ] Artifacts-owned destination publication, verification, retries, and remote retention.
- [x] Verified event-to-pipeline integration tests with PostgreSQL migrations, generated event dispatch and catalog, pipeline matching and run jobs, the real executor, transaction rollback, redelivery and originating-user data.
- [x] Ref synchronization integration tests with real PostgreSQL DFS refs/packs and Git transports: cancellation and ledger rollback, write-lock/commit boundaries, concurrent intake and reconciliation, late verified attribution, and native echo deduplication.
- [ ] Pull request synchronization integration tests and isolated provider API checks.
- [x] Git server native-image compilation with GraalVM 25 on macOS arm64, using `--no-configuration-cache`.
- [ ] Native runtime validation of synchronization.

The native CI occurrence reservation identifies a durable queue job. GitHub intake preserves one occurrence under the `X-GitHub-Delivery` identifier; a repeated identifier with a different repository, event, or payload digest is rejected. Pairing uses the immutable GitHub repository ID, and credentials are references to the existing encrypted Pipeline secret store. Apply Git migrations `V44__github_intake.sql` and `V45__github_ref_synchronization.sql`. The GitHub package installs ordinary inbound and outbound ref pipelines, plus hourly ref reconciliation. The inbound pipeline has `triggered = true` and input type `bosca.git.model.GitHubDelivery`; standard pipeline execution applies. Ref synchronization records completed push deliveries and retains their originating principal. Anonymous recovery can later attribute CI to a verified push only while that exact imported ref write remains current. Pull request operation mapping and the retention choices below remain to be implemented or settled.

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

Merging must propagate the resulting history without independently generating a second merge on the other host. Finalize the supported merge strategies and the mapping of merged status, reviews, comments, authorship, and concurrent edits against the destination APIs before implementing those operations. Do not present mirrored integration actions as native approvals by another person.

## Authorization to build

Only identities authorized to execute builds may cause Bosca servers to build code. Trusted people are responsible for choosing safe work to execute.

The GitHub integration must map the originating GitHub user to a Bosca identity and evaluate build execution permission through Bosca's existing group-based permission system. Unknown or unauthorized users cannot start builds. Public repository access, forking, and opening a pull request confer no build permission.

Apply this rule to automatic triggers, manual starts, reruns, and replayed deliveries. Mirrored ref updates must not bypass it by appearing to originate from a privileged integration identity. Scheduled work uses an explicitly authorized execution identity.

This scope does not add a separate per-commit approval process. A trusted person may import a contribution into an authorized branch and start its build. The existing permission and secret mechanisms should be reused; the integration must not assume all existing start paths already use the same permission action.

## Outages and reconciliation

Each Git host remains usable when the other is unavailable. Synchronization resumes when connectivity returns. Scheduled reconciliation should invoke the same node operations to recover missed events and incomplete work.

Fast-forward changes can converge automatically. Concurrent branch histories, conflicting tag changes, and incompatible pull request edits must retain both sides' information and surface a conflict for resolution. The implementation must define deletion and force-push handling relative to the last synchronized state.

Source development on GitHub can continue while Bosca is unavailable; Bosca-hosted builds and deployments still require Bosca. This design does not add a second CI/CD system on GitHub.

## Remote artifact publication

Bosca Artifacts owns remote publication. CI publishes completed outputs to Bosca Artifacts, which pushes the same outputs to configured destinations such as GitHub or Maven Central. Per-pipeline publication scripts should give way to this ownership as destination support is implemented.

The artifacts server must track publication and verification separately for each destination. A remote failure leaves work pending or failed with a retry path, while the internal artifact remains available. Retrying publication reuses the existing bytes rather than rebuilding a version.

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
- [Bosca Artifacts](../artifacts/README.md) already provides storage and registry protocols. Existing [CLI](../.bosca/pipelines/release-cli.yaml) and [image](../.bosca/pipelines/release-image.yaml) pipelines configure GitHub publication through release scripts.

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
