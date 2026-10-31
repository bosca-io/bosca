# Bosca Atlas UI

Shared BML authentication and landing routes for Bosca Atlas. The module owns `/`, `/login`,
`/auth/signup`, `/auth/forgot`, `/auth/reset-password`, `/auth/verify`, and `/auth/link/confirm`,
using the neutral `bml-auth-ui` library for forms, SDK flows, appearance, and profile-menu actions. The authenticated root route is the post-login home launcher, with Git and artifacts entry points.
The subsystem header opens Studio-style categorized navigation with search, recent items, and keyboard selection.
The design library owns the shared switcher and launcher components; `AtlasSubsystems` in this module
defines Atlas's systems, routes, descriptions, icons, and colors. Home, Git, and artifacts use those
components directly with the shared catalog.
Git keeps Studio's slate accent; Artifacts uses its magenta accent.
The brand returns home; local sidebars remain scoped to their system. Explicit login return paths are preserved.
Standalone Git owns its authentication routes and `/` redirect to `/repositories`, without an Atlas dependency.

`AtlasUi` exposes the compiled page/component registries and global assets for application hosts.
`AtlasUi.server` and `AtlasUi.install` can host the shared routes independently of Git.
Atlas supplies `GitUiNavigation` when importing the independent Git UI. `:server:atlas-ui-server` owns composition of Atlas, Git, and artifacts UI modules;
Atlas UI has no Git, artifacts, or backend implementation dependencies.

Authentication uses the existing `@bosca/auth-client-browser` SDK and BML same-origin proxies.
The backend validates credentials and authorizes domain operations. Return destinations, custom
cookie prefixes, refresh, verification, recovery, account linking, and OAuth exchange use the
established flows. Auth requests identify their originator as `atlas-ui`.

## Source layout

- `src/main/bml/pages`: the landing route and public authentication pages.
- `src/main/bml/components/auth`: Atlas branding around the shared authentication layout.
- `src/main/bml/components/layout`: shared profile adapter.
- `src/main/kotlin/bosca/atlas/ui`: host composition through `AtlasUi`.
- `src/main/graphql`: checked-in schema subset and typed operations when needed.
- `src/main/client`: Atlas landing styles and the host SDK bootstrap.

BML generates into `bosca.atlas.ui.generated`; GraphQL clients generate into
`bosca.atlas.ui.graphql`. Shared browser imports use the neutral `@bosca/bml-auth-ui` package.
Generated page assets are indexed and packaged by the BML plugin. Native resource metadata
includes the Atlas application assets and global CSS/JavaScript.

The compiled Bosca Design Library supplies generic controls and presentation. Domain modules own
their views and adapters; profile-menu adapters call the shared authentication library.

## Validation

Run from the workspace root:

```bash
./gradlew :server:atlas-ui:check :git:git-ui:check :git:git-ui-server:test
```

Atlas checks include TypeScript checking, shared authentication-flow tests, and real HTTP
checks of public pages, root authentication, packaged assets, custom cookies and proxy forwarding.
Git's HTTP and browser fixtures also exercise the shared implementation within its domain host.
