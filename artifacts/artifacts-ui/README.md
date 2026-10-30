# Artifacts UI

Artifact administration BML application imported by `server/atlas-ui-server` alongside Git UI.
Pages and server actions use generated, typed HTTP operations through Atlas's shared `/graphql`.
The host uses the same authentication session, design library, and request credentials as Git.
The Atlas brand returns to the home launcher. The shared system switcher moves between Git and
artifacts; the artifacts sidebar keeps its own repository, namespace, and permission links.

The existing nested artifact administration fields require administrator membership. These pages
show that requirement before loading protected data. Registry clients continue using namespace
VIEW/EDIT permissions; the UI does not change backend authorization.

## Routes and workflows

- `/artifacts/namespaces`: protocol counts, visibility, creation, and confirmed deletion.
- `/artifacts/namespaces/{id}`: registry type filtering, offset pagination, repository creation,
  public pull visibility, and namespace administration.
- `/artifacts/repositories`: registry filtering, paged browsing, copy IDs, confirmed deletion, and
  a file drop target for raw uploads.
- `/artifacts/repositories/{id}`: paged versions and Docker tags, expandable file manifests,
  metadata, sizes, digests, copyable protocol download URLs, raw uploads, and confirmed deletion.
- `/artifacts/repositories/upload`: existing or new raw repository targets, editable filenames,
  multiple files, progress, cancellation, per-file failures, and retry of unfinished files.
  Files selected on repository pages are staged for the next full-page navigation and consumed
  once on the upload page. File bytes stay in the browser until the upload request.
- `/artifacts/settings/permissions`: namespace group grants and revocation through the existing API.
- `/artifacts/settings/repositories/{id}`: Docker sync destinations/status/tag pushes, or raw release
  destinations/status, stored-file completion review, and publication. Destination updates retain
  optimistic locking. Token values are written to the existing pipeline secret store and are not
  kept in BML model state.
- `/artifacts/runs/{id}`: platform pipeline run state, node errors, refresh, confirmed restart of
  failed runs, and cancellation of active runs. These are distinct from Git CI run IDs.

## Registry origins

Browser uploads and file reads use the host's same-origin `/_artifacts/` routes. Atlas exposes matching
backend routes using the existing registry handlers and authorization. `BML_ARTIFACTS_ENDPOINT`
optionally selects a separate registry upstream using its native protocol paths, while GraphQL stays
on `BML_GRAPHQL_ENDPOINT`. `ATLAS_ARTIFACTS_URL` optionally sets the public registry origin used in
copied native protocol URLs; otherwise copied URLs use the UI origin and `/_artifacts/` file proxy.
No browser-to-registry CORS configuration is required for these UI workflows.

Atlas and the main API server use `artifacts-installer` to seed the same default sync/publication
pipeline package. Existing operator graphs and the package's version are preserved.

## Structure and validation

File drops, cards, descriptions, disclosures, form bodies/footers, destructive actions, navigation controls,
empty states, and pagination come from `bml-design`. Artifact CSS supplies the subsystem theme
and domain layout; it must not override descendant button or dialog presentation. Destructive
actions use the library's `danger` class, and ghost styling belongs only to the dialog trigger.
The shared `file-drop` emits local file selections; Artifacts owns staging, filenames and transport.

`src/main/bml` owns pages/components; `src/main/kotlin` owns loaders, view models, and actions;
`src/main/graphql` owns the maintained schema subset and operations; `src/main/client` handles
browser file I/O. Generated BML code uses `bosca.artifacts.ui.generated`, and GraphQL code uses
`bosca.artifacts.ui.graphql`.

Run from the workspace root:

```bash
./gradlew :artifacts:artifacts-ui:check :artifacts:artifacts-installer:test
./gradlew :server:atlas-server:test --tests 'bosca.atlas.server.AtlasArtifactsIntegrationTest'
./gradlew :server:atlas-server:test --tests 'bosca.atlas.server.AtlasArtifactsIntegrationTest' -Patlas.artifacts.browser=true
```

The browser option starts a separate Chrome profile against isolated production Atlas processes.
It never uses a deployed authenticated session. Screenshots and width measurements are written to
`artifacts/artifacts-ui/build/reports/browser`. See the [Studio inventory](docs/studio-ui-inventory.md).
Native compilation and external provider operations require separate validation.

Browser upload acceptance also requires visible staged filename fields, disabled filename/removal
controls during transfer, and release of removed File objects before navigating away. The same
checks run against JVM and native Atlas hosts.
