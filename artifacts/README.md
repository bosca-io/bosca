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

## GitHub release publication

Raw artifact repositories can publish completed versions as GitHub release assets. Apply the registered artifact migration `V7__artifact_publication.sql`. Administrators configure destinations through the API server; publication runs through ordinary pipelines in Bosca Runner.

Manage the GitHub credential in Studio's **Pipelines → Secrets**, using the existing encrypted Pipeline secret store. The API server and runner use the same `PIPELINE_SECRET_KEY`. A destination holds only the secret's name; credential values are absent from GraphQL results. GitHub applies its authorization to every publication operation.

An administrator creates a destination through `Mutation.artifactsAdmin.createPublicationDestination(input)`. The input identifies the raw artifact repository with `repositoryId`, names the destination with `key`, and supplies the immutable `githubRepositoryId`, `owner`, `githubRepository` name, `tagPrefix` and `tokenSecretName`. Destinations start disabled unless `enabled: true` is supplied. `updatePublicationDestination(id, version, enabled, tokenSecretName)` enables/disables the destination or changes its secret reference using the returned optimistic-lock version.

In Studio's pipeline editor, configure a triggered pipeline accepting `bosca.artifacts.model.ArtifactCompleted`. Connect its input to **Get Artifact Publication Destinations**, then connect the selected pairs to **For Each**. Select a body pipeline that connects its input to **Publish GitHub Release** and its result to an Output node. Get Destinations returns enabled destinations for the artifact's repository without finalizing it or creating publication records. The push action publishes one selected pair; set **Prerelease** there when needed. Both nodes check artifact push permission under the pipeline's execution identity.

CI stores resolved artifact declarations on each producing job. The ordinary `registry-upload` action uploads to Bosca and reports job completion. The job service verifies declared outputs before recording success. When every producer of an artifact in that run succeeds, the existing registry integration calls `ArtifactRepositoryService.completeVersion`; the artifacts service dispatches `ArtifactCompleted`. The event carries the stored version ID, producer job IDs, build commit and initiating principal. Unrelated jobs do not delay completion. This boundary waits for all files in a multi-file version instead of treating its first file upload as completion.

The GitHub tag `tagPrefix + artifactVersion` must already exist and resolve to the event's exact commit, including annotated tags. Publication does not create a tag. Asset filenames must be unique, start with an ASCII letter or digit, and contain only letters, digits, dots, underscores or hyphens, without a trailing dot. Files use their stored SHA-256 digests and bytes.

Pushing fixes the version's file list and records its publication to the selected destination. Pipeline retries reuse the original manifest, commit and prerelease choice, including when destinations change. Finalized versions reject file changes while accepting identical upload redelivery. Completion and destination lookup leave raw versions mutable until a push prepares their publication.

The publisher creates a managed draft, uploads the stored files and publishes the draft after validating the assets. Retries recover matching existing assets and empty failed uploads in that managed draft. Uploaded assets with different bytes, unrelated drafts, changed repository identities and conflicting tags fail without replacing uploaded assets. Provider API behavior follows the [GitHub release asset API](https://docs.github.com/en/rest/releases/assets?apiVersion=2026-03-10).

Read `Query.artifactsAdmin.publications(versionId, limit, offset)` for the fixed file manifest, attempt count, remote release ID, error, and independent `published` and `verified` timestamps. Failed verification preserves the recorded publication result. Explicit local version deletion cancels remaining publication work and does not delete a GitHub release.

Studio destination configuration and verification pages, package registries, Maven Central and remote retention remain to be implemented. Retention needs the version count, prerelease treatment and exemptions before implementation.
