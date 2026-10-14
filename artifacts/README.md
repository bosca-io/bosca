# bosca-artifacts

Artifact storage server for the Bosca platform, providing Docker registry, Maven repository, and npm registry capabilities in a unified service. The modular design separates protocol-specific handling from shared artifact storage infrastructure, with a standalone server application supporting GraalVM native image compilation.

## Modules

| Module | Description |
|---|---|
| `core-artifacts` | Contracts: artifact interfaces, digest utilities, models |
| `artifacts-base` | Shared artifact storage infrastructure and configuration |
| `artifacts-docker` | Docker registry protocol implementation |
| `artifacts-helm` | Helm artifact handling |
| `artifacts-maven` | Maven repository protocol implementation |
| `artifacts-npm` | npm registry protocol implementation |
| `artifacts-raw` | Raw artifact handling |
| `artifacts-ml` | Machine learning artifact handling |
| `artifacts-admin` | GraphQL admin interface for artifacts |
| `artifacts-server` | Standalone server application |

## Prerequisites

- JDK 25

## Build

Run these commands from the **workspace root**. `artifacts` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :artifacts:test  # Test artifact modules
./gradlew :artifacts:artifacts-server:run  # Run the artifact server
./gradlew :artifacts:artifacts-server:nativeCompile  # Build a native image
```

## Architecture

Each artifact protocol (Docker, Maven, npm) is isolated in its own module with protocol-specific logic. These protocol modules share common storage infrastructure provided by `artifacts-base`. The `core-artifacts` module defines only interfaces and models. The `artifacts-server` module composes everything into a standalone application with GraalVM native image support. The `artifacts-admin` module provides a separate GraphQL admin interface for managing artifacts.

## Dependencies

- `io.bosca:core` -- Bosca Core (platform framework)
- `io.bosca:core-security` -- Bosca Core Security (authentication)
- `io.bosca:core-storage` -- Bosca Core Storage (object storage abstraction)
- `io.bosca:core-graalvm` -- Bosca Core GraalVM (native image support)
- `io.bosca:analytics-server-client` -- Bosca Analytics (event reporting)

## Docker syncing to GHCR

Configure a Docker repository to copy each completed, tagged image to a GHCR image path. For example, a push to Bosca's `images/server:1.2.3` can publish the same image as `ghcr.io/bosca-io/bosca/server:1.2.3`. Source tags are preserved; image manifests, indexes, configs and layers retain their original bytes and digests. OCI images, multi-platform indexes and Docker schema-2 images are supported.

In Studio, open **Artifacts → Repositories**, select a Docker image, open **Settings**, and use **GitHub Container Registry sync → Add destination**. Enter a destination name, lowercase GHCR image path, and the GitHub username owning the token. Select an existing token secret or add one in the form, choose **Enable synchronization**, and save. Each artifact has its own destinations. Use **Edit destination** to change its name, image path, credentials or activation, and **Sync status** to inspect tag digests, attempts, errors, and successful sync times or retry failed requests.

Token secrets can also be managed in **Pipelines → Secrets**. Use a GitHub personal access token **(classic)** with `write:packages`; fine-grained tokens are not supported by GHCR. The destination username must identify the token owner, who needs write access to an existing target package. If the organization requires SSO, authorize the token for it. See the [GHCR authentication documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry#authenticating-with-a-personal-access-token-classic). The API server and Bosca Runner must share `PIPELINE_SECRET_KEY` to read the encrypted secret. Sync controls are available to administrators.

HTTP failures report the sync operation, such as requesting a registry token or assigning the image tag. HTTP 401/403 errors include credential and authorization checks; errors exclude credential values, upload URLs and provider response bodies. After correcting the destination or its token secret, use **Push image** or retry the failed sync in **Sync status**.

An administrator creates a destination through the API server:

```graphql
mutation {
  artifactsAdmin {
    createSyncDestination(input: {
      repositoryId: "<Bosca Docker repository UUID>"
      key: "ghcr"
      remoteRepository: "bosca-io/bosca/server"
      username: "<GitHub username owning the token>"
      tokenSecretName: "ghcr-token"
      enabled: true
    }) {
      id version remoteRepository enabled
    }
  }
}
```

`remoteRepository` is the lowercase owner/image path without `ghcr.io/` or a tag. Multiple enabled destinations can copy the same source repository. Destinations default to disabled. `updateSyncDestination(id, version, enabled, username, tokenSecretName, key, remoteRepository)` updates configuration with optimistic locking. Omitted name, image path and credential fields retain their current values. Renaming preserves sync status. Changing the image path removes its previous sync records and queued targets for the previous path are skipped; use **Push image** to copy existing tags to the new path. Images already pushed to GHCR are kept. Use **Delete destination** in Studio or `deleteSyncDestination(id, version)` to remove a destination and its sync status records. Source artifacts, images already pushed to GHCR, and the token secret are kept. Queued syncs for the deleted destination are skipped.

Use **Push image** beside an enabled destination to select an existing source tag and queue a push on demand. The tag name is preserved, and only the selected destination is pushed. No previous sync record or new source publish is needed; a completed tag can be pushed again. **View push run** opens the durable run status. `Mutation.artifactsAdmin.pushImage(destinationId, tagName)` returns that run's ID and requires administrator access. It uses the editable **Sync Docker Image to GHCR** pipeline under the caller's principal. If that pipeline was renamed, it must be the only non-triggered pipeline accepting `ArtifactSyncTarget`. Disabled destinations and missing token secrets reject the request.

The ordinary `ArtifactRepositoryService.setTag` write path dispatches `bosca.artifacts.model.ArtifactTagPublished` in the same transaction as the local tag. Pipeline event delivery waits for commit; rolled-back writes enqueue nothing. The tagged manifest route stores the manifest and its blob association before setting the tag. Digest-only child manifest uploads do not fire tag events. The standalone artifacts server produces events on the configured Bosca `pipelines` queue; Bosca Runner matches and executes triggered pipelines. No Docker daemon is required.

The API server's **Default Artifact Sync Pipelines** package installs **Sync Published Docker Tags to GHCR** and its **Sync Docker Image to GHCR** body pipeline. The triggered graph connects the tag event to **Get Artifact Sync Destinations**, then **For Each** invokes the body for every enabled destination. The body uses **Sync Docker Image to GHCR**, a durable action with ordinary pipeline retries and run history. Destination failures are recorded independently so other destinations can still run. Both nodes verify artifact push permission under the pipeline execution identity (`pipelines.serviceAccount` for triggered runs). Existing graphs are preserved when the package is reinstalled; edit or disable the triggered pipeline in Studio to change syncing.

The API server's default startup `packages` list includes `default-artifact-sync-pipelines`. Deployments that override that list must include this key to install the graphs at startup. To install them on an existing server, open **System → Packages** in Studio and install **Default Artifact Sync Pipelines**, version **1.1.0**. A missing on-demand pipeline reports this installation step; multiple matching graphs require one to retain the name **Sync Docker Image to GHCR**.

The sync checks that the stored manifests and distributable blobs are present, uploads missing blobs, uploads child manifests before their indexes, then assigns and verifies the remote tag. Root manifests retain their stored publication media type when their JSON omits `mediaType`. Foreign and non-distributable layers with external URLs keep their original descriptors; syncing does not download or upload those layers. Pipeline retries reuse blobs already present on GHCR. Obsolete tag events and moved tags are skipped; pending records for skipped obsolete digests are removed while completed records and newer requests are kept. The current tag's event prepares its desired digest and resets the previous result. Concurrent pipeline runs serialize copies of the same destination/tag. Disabling a destination stops tag publication; local version deletion cancels its pending copies and leaves GHCR content in place.

Read `Query.artifactsAdmin.syncDestinations(repositoryId)` for configuration and `Query.artifactsAdmin.syncs(repositoryId, limit, offset)` for prepared digests, attempts, safe errors and successful `synced` timestamps. `Mutation.artifactsAdmin.retrySync(id)` clears the selected result and dispatches the current local tag's publication event through pipelines. Re-enabling a destination does not backfill existing tags; repush those tags or retry their existing sync records. Apply the registered artifact migration `V8__artifact_sync.sql`; Pipeline secret storage is managed by the API server's existing migrations.

## GitHub release publication

Raw artifact repositories can publish completed versions as GitHub release assets. Apply the registered artifact migration `V7__artifact_publication.sql`. Administrators configure destinations through the API server; publication runs through ordinary pipelines in Bosca Runner.

Manage the GitHub credential in Studio's **Pipelines → Secrets**, using the existing encrypted Pipeline secret store. The API server and runner use the same `PIPELINE_SECRET_KEY`. A destination holds only the secret's name; credential values are absent from GraphQL results. GitHub applies its authorization to every publication operation.

An administrator creates a destination through `Mutation.artifactsAdmin.createPublicationDestination(input)`. The input identifies the raw artifact repository with `repositoryId`, names the destination with `key`, and supplies the immutable `githubRepositoryId`, `owner`, `githubRepository` name, `tagPrefix` and `tokenSecretName`. Destinations start disabled unless `enabled: true` is supplied. `updatePublicationDestination(id, version, enabled, tokenSecretName)` enables/disables the destination or changes its secret reference using the returned optimistic-lock version.

In Studio, open **Artifacts → Repositories**, select a raw repository, and open **Settings → GitHub release sync → Add destination**. Enter a destination name, GitHub owner and repository name, numeric repository ID, tag prefix, and a Pipeline token secret. Select an existing secret or create one in the form. The server verifies the GitHub repository ID before saving; the repository identity and tag prefix remain fixed. Use **Edit destination** to enable or disable syncing and change the secret reference. **Release sync status** shows each version's fixed asset manifest, attempts, safe errors, and independent publication and verification timestamps. Status uses offset pagination. Inspect or retry failed runs in the pipeline run history.

The **Default Artifact Sync Pipelines** package, version **1.1.0**, installs **Sync Completed Raw Artifacts to GitHub** and its **Publish Raw Artifact to GitHub** body alongside the Docker sync pipelines. Package upgrades preserve existing operator graphs. Each completion event selects enabled destinations, and For Each invokes the durable publication body independently for every destination. The default body publishes stable releases; edit its **Prerelease** setting for a prerelease workflow. Enabling a destination does not backfill existing versions.

For CLI releases, configure the raw `bosca/bosca-cli` repository with owner `bosca-io`, repository `bosca`, the numeric ID returned by the GitHub repository API, and an empty tag prefix. Use a token with Contents read and write access to that repository. Before running the CLI release pipeline, ensure the bare `<version>` tag, such as `1.3.3`, exists on GitHub at the build commit. CI's completed version includes the Linux tarball, macOS installer, `SHA256SUMS`, and `install.sh`; the artifact pipeline publishes those stored files together.

Use **Publish release** beside an enabled destination to publish a completed raw version. Select the version, enter its exact 40-character build commit SHA, and choose **Queue publication**. The corresponding GitHub tag must already point to that commit. **View publication run** opens the durable pipeline run. This operation reuses the recorded upload completion and publishes only the selected destination. Disabled destinations and missing token secrets cannot be published.

For a stored version without completion history, open **Publish release** and review **Stored files**. Enter the exact build commit, select **All files have finished uploading and match this build commit**, and choose **Confirm completed uploads**. This records completion without rebuilding or uploading again. The server compares the reviewed filenames, digests, roles and media types against the entire current manifest under the version lock; changed files require a fresh review. Confirmation can start automatic publication to enabled destinations. Until publication finalizes the version, completion can be corrected by reviewing and confirming it again.

`Mutation.artifactsAdmin.completeVersion(versionId, commitSha, expectedBlobs)` requires administrator access and returns the updated version. `Query.artifactsAdmin.version(id)` exposes its current blobs and `completedCommitSha` for review. Confirmation uses the existing artifact completion event and records the confirming principal. An empty or partial manifest, duplicate entries, a different version's blobs, a changed manifest or a non-raw repository is rejected. Producer-driven completion remains unchanged.

`Mutation.artifactsAdmin.publishRelease(destinationId, versionId, commitSha)` requires administrator access and returns the run ID. It uses the editable **Publish Raw Artifact to GitHub** body pipeline under the caller's identity, including suspended actions and their resumes. The body pipeline chooses **Prerelease** for the first publication; later requests retain that original choice. If the body was renamed, it must be the only non-triggered pipeline accepting `ArtifactPublicationTarget`; when several match, exactly one must retain the default name. Missing graphs report the **Default Artifact Sync Pipelines** installation step. The action fixes the file manifest when it executes. Repeated publications retain the original manifest, commit, remote release identity, and first publication timestamp, reuse matching remote assets, and verify the release again. Failed runs use the existing pipeline retry and replay controls.

To configure custom graphs in Studio's pipeline editor, create a triggered pipeline accepting `bosca.artifacts.model.ArtifactCompleted`. Connect its input to **Get Artifact Publication Destinations**, then connect the selected pairs to **For Each**. Select a body pipeline that connects its input to **Publish GitHub Release** and its result to an Output node. Get Destinations returns enabled destinations for the artifact's repository without finalizing it or creating publication records. The publication action publishes one selected pair; set **Prerelease** there when needed. Both nodes check artifact push permission under the pipeline's execution identity.

CI stores resolved artifact declarations on each producing job. The ordinary `registry-upload` action uploads to Bosca and reports job completion. The job service verifies declared outputs before recording success. When every producer of an artifact in that run succeeds, the existing registry integration calls `ArtifactRepositoryService.completeVersion`; the artifacts service dispatches `ArtifactCompleted`. The event carries the stored version ID, producer job IDs, build commit and initiating principal. Unrelated jobs do not delay completion. This boundary waits for all files in a multi-file version instead of treating its first file upload as completion.

The GitHub tag `tagPrefix + artifactVersion` must already exist and resolve to the event's exact commit, including annotated tags. Publication does not create a tag. Asset filenames must be unique, start with an ASCII letter or digit, and contain only letters, digits, dots, underscores or hyphens, without a trailing dot. Files use their stored SHA-256 digests and bytes.

Publishing fixes the version's file list and records its publication to the selected destination. Pipeline retries reuse the original manifest, commit and prerelease choice, including when destinations change. Finalized versions reject file changes while accepting identical upload redelivery. Completion and destination lookup leave raw versions mutable until publication prepares their publication.

The publisher creates a managed draft, uploads the stored files and publishes the draft after validating the assets. Retries recover matching existing assets and empty failed uploads in that managed draft. Uploaded assets with different bytes, unrelated drafts, changed repository identities and conflicting tags fail without replacing uploaded assets. Provider API behavior follows the [GitHub release asset API](https://docs.github.com/en/rest/releases/assets?apiVersion=2026-03-10).

Read `Query.artifactsAdmin.publications(versionId, limit, offset)` for the fixed file manifest, attempt count, remote release ID, error, and independent `published` and `verified` timestamps. Each execution commits its attempt number before contacting GitHub, so overlapping failures both count. Only the current attempt can update the publication result; older errors and verification cannot overwrite newer results. `verifiedAttempt` records which attempt last passed verification; the current attempt is verified only when it matches `attempts`, independently of transaction timestamps. A failed repeat preserves the first publication and last successful verification timestamps. Studio shows the latest failed result and labels the historical date **Last verified**; a successful retry clears the error. An unfinished attempt remains eligible for retry even when a previous attempt was verified. Explicit local version deletion cancels remaining publication work and does not delete a GitHub release.

Maven/npm syncing, Maven Central and remote retention remain to be implemented. Retention needs the version count, prerelease treatment and exemptions before implementation.

Apply `V9__artifact_completion.sql` to record the exact build commit when producers complete their uploads. Incomplete versions cannot be published; a manifest edit clears completion until all uploads finish again. Tag and commit validation must succeed before the fixed publication input is committed. Republishing retains the original prerelease choice and verification history until a valid attempt runs.

Apply `V10__artifact_publication_verified_attempt.sql` to record the verified attempt number. Existing successful records retain their verified state; ambiguous historical records retain their timestamps and are verified again on retry.
