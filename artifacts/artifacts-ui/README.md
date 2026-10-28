# Artifacts UI

BML UI scaffold for Bosca Atlas, following the current `:git:git-ui` architecture and Bosca
Design Library presentation. It declares BML and typed GraphQL tooling without depending on
artifact backend implementations. Artifact pages and client behavior are not implemented yet.

## Source layout

- `src/main/bml`: artifact pages and artifact-specific components.
- `src/main/kotlin/bosca/artifacts/ui`: page loaders, models, and server actions.
- `src/main/graphql`: checked-in schema subset and typed operations.
- `src/main/client`: artifact-specific CSS and client modules.

BML generates into `bosca.artifacts.ui.generated`; GraphQL clients generate into
`bosca.artifacts.ui.graphql`. Atlas composes these views through `:server:atlas-ui`, hosted by
`:server:atlas-ui-server` in standalone and combined deployments.

## Studio parity baseline

Use Studio's artifacts pages and components as the functional baseline: namespace and repository
browsing, versions/tags/files, raw uploads, namespace and repository permissions, repository
settings, sync destinations/status, and release publication destinations/status. Follow Git's
source-level inventory, HTTP integration tests, responsive browser checks, and packaged-asset
verification when implementing those workflows.

Pages load data and compose domain components. Shared controls come from `:bml:bml-design`.
Loaders and server actions use typed HTTP GraphQL operations under the caller's authentication;
client islands use declarative BML actions and the host's same-origin proxies.
