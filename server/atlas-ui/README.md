# Bosca Atlas UI

Shared BML authentication and landing routes for Bosca Atlas. The module owns `/`, `/login`,
`/auth/signup`, `/auth/forgot`, `/auth/reset-password`, `/auth/verify`, and `/auth/link/confirm`,
along with authentication forms, browser SDK initialization, return navigation, and shared
profile-menu actions. The authenticated root route retains the existing redirect to `/repositories`.

`AtlasUi` exposes the compiled page/component registries and global assets for application hosts.
`AtlasUi.server` and `AtlasUi.install` can host the shared routes independently of Git.
`:git:git-ui` composes those registries with its domain pages and uses the same Atlas browser
session. `:server:atlas-ui-server` owns composition of Atlas, Git, and artifacts UI modules;
Atlas UI has no Git, artifacts, or backend implementation dependencies.

Authentication uses the existing `@bosca/auth-client-browser` SDK and BML same-origin proxies.
The backend validates credentials and authorizes domain operations. Return destinations, custom
cookie prefixes, refresh, verification, recovery, account linking, and OAuth exchange use the
established flows. Auth requests identify their originator as `atlas-ui`.

## Source layout

- `src/main/bml/pages`: the landing route and public authentication pages.
- `src/main/bml/components/auth`: authentication forms and presentation.
- `src/main/bml/components/layout`: reserved for the shared Atlas shell and navigation.
- `src/main/kotlin/bosca/atlas/ui`: host composition through `AtlasUi`.
- `src/main/graphql`: checked-in schema subset and typed operations when needed.
- `src/main/client`: shared CSS, SDK bootstrap, auth flows, appearance and profile-menu actions.

BML generates into `bosca.atlas.ui.generated`; GraphQL clients generate into
`bosca.atlas.ui.graphql`. Shared browser imports use the local `@bosca/atlas-ui-client` package.
Generated page assets are indexed and packaged by the BML plugin. Native resource metadata
includes the Atlas application assets and global CSS/JavaScript.

The compiled Bosca Design Library supplies generic controls and presentation. Domain modules own
their views and adapters; Git's profile-menu adapter calls the shared Atlas account actions.

## Validation

Run from the workspace root:

```bash
./gradlew :server:atlas-ui:check :git:git-ui:check :git:git-ui-server:test
```

Atlas checks include TypeScript checking, the migrated authentication-flow tests, and real HTTP
checks of public pages, root authentication, packaged assets, custom cookies and proxy forwarding.
Git's HTTP and browser fixtures also exercise the shared implementation within its domain host.
