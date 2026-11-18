# Git UI server

An independent BML host for `:git:git-ui`. It proxies GraphQL, OAuth, subscriptions,
raw files and CI artifacts to its configured Git API. It owns no database or Git
implementation. Atlas imports the Git UI and uses these proxy helpers; the optional
`git-server` deployment is backend-only and does not embed this host.

Run an independent development UI from the workspace root:

```bash
./gradlew :git:git-ui-server:run
```

It listens on 8093 by default and reads `git-ui-server.yaml`. `GIT_UI_HTTP_PORT`
selects the UI port; `BML_GRAPHQL_ENDPOINT` selects the API (default
`http://localhost:8091/graphql`). `BML_AUTH_COOKIE_PREFIX` selects the browser cookie
prefix. For this development arrangement, set the backend's `GIT_UI_URL`, `APP_URL`,
`APP_ALLOWED_ORIGIN` and `OAUTH2_ALLOWED_REDIRECT` to `http://localhost:8093`.
The backend otherwise defaults its UI links and auth return origin to Atlas on 8095.
Register its configured provider callback before using external OAuth.

Production deployments serve the UI through Atlas or `atlas-ui-server`. Git and
artifact backends can run independently behind the same Atlas hostname; see the
[Helm guide](../../helm/README.md#bosca-atlas) and
[Swarm guide](../../deployments/bosca-swarm/README.md).

Each runnable BML host has its own build identity; the reusable `git-ui` library
has none. Packaged UI assets, fonts and third-party notices belong to the UI host,
not the backend Git executable.

Validation from the workspace root:

```bash
./gradlew :git:git-ui:check :git:git-ui-server:test :git:git-server:test
./gradlew :git:git-ui-server:nativeCompile --build-args=-Ob :git:git-ui:nativeUiHostTest
./gradlew --no-configuration-cache --no-parallel :git:git-server:nativeCompile --build-args=-Ob :git:git-server:nativeHostTest
```

Backend acceptance starts Git and an independent JVM UI client with isolated
PostgreSQL/NATS/storage. It checks auth, repository actions, files, subscriptions,
custom cookie prefixes and shutdown. Native Git acceptance uses the backend native
binary and verifies it contains API resources without BML UI assets. Native UI
acceptance separately tests the UI executable against HTTP/WebSocket fixtures.
`GitFileProxyTest` checks encoded paths, binary data, headers, upstream errors and
streaming delivery. No test performs an external OAuth exchange or sends mail.
