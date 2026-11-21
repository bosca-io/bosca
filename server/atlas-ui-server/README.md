# Bosca Atlas UI server

Standalone BML host composing the shared Atlas authentication/landing pages, Git, Artifacts, System and WorkOps UI. It serves the
same application used by Atlas's embedded UI listener and uses HTTP proxies for domain operations.
It has no database, migration, security-provider, or job-consumer initialization.

Run from the workspace root:

```bash
./gradlew :server:atlas-ui-server:run
./gradlew :server:atlas-ui-server:installDist
```

Native compiler resources can be set with `-Pbosca.native.buildHeap=32g` and
`-Pbosca.native.parallelism=4`. Both Atlas native hosts accept these optional build flags;
optimization remains at the configured level.

Package the native host with `./gradlew :server:atlas-ui-server:nativeDistZip`.
Unpack `build/distributions/atlas-ui-server-native.zip` into the Docker build context's
`artifacts/${ARTIFACT_SHA}/atlas-ui-server-native/` directory. Retain the generated runtime
libraries beside the executable, including the management library when emitted by GraalVM.
The Dockerfile copies the complete runtime.

The UI defaults to port 8095 and the backend to `http://localhost:8094/graphql`. Disable the
backend's embedded UI (`ATLAS_UI_ENABLED=false`) before starting this host on the same port.
Use `ATLAS_UI_HTTP_PORT` to change its listener, `BML_GRAPHQL_ENDPOINT` for the API, and
`BML_AUTH_COOKIE_PREFIX` to select the same cookie prefix configured on the backend.

Atlas imports Git UI using one shared backend origin. `/graphql` exposes the composed Atlas schema;
the existing authentication, subscription, raw-file, and CI download proxies use that same upstream.
Backend routing handles independently scaled services without domain-specific UI endpoints.

Both backend and UI accept `-config=/path/to/config.yaml`. This host reads `atlas-ui-server.yaml`.
Public origins, allowed redirects, OAuth credentials/callbacks, and JWT configuration belong to the
backend. The browser uses the existing same-origin authentication model and never needs direct
cross-origin backend calls.

The host reuses the established Git UI proxy implementation; Atlas UI owns auth, session setup,
and the root landing route. The host enables Atlas's system switcher in the imported Git header;
standalone Git keeps its own navigation. Git and artifacts each retain their local links, with the
shared dialog providing system switching and a return to Atlas home.
Artifacts UI is imported from `:artifacts:artifacts-ui`, with typed GraphQL actions and same-origin
registry file proxies under `/_artifacts/`, which have distinct URLs from Git raw files. The composed
Atlas backend exposes matching routes through the existing registry handlers. `BML_ARTIFACTS_ENDPOINT`
optionally selects a separate registry upstream using native protocol paths; `ATLAS_ARTIFACTS_URL`
sets the public origin used in copied native file URLs. GraphQL remains shared.
See [Atlas server](../atlas-server/README.md) for deployment roles and production-process tests.

System administration pages are imported from `:admin-support:system-ui` and are available
in both embedded and standalone hosts. Routes cover PostgreSQL, NATS and Meilisearch consoles, groups, scheduled jobs,
job history and package installation through the shared Atlas API. See
[System UI](../../admin-support/system-ui/README.md) for the implemented scope and checks.

WorkOps pages are imported from `:workops:workops-ui` through the same host installation path.
Activity, specification, requirement, planning, board, sprint, delivery and settings routes use typed
GraphQL HTTP operations, the shared authentication session and BML Design Library controls. The
[WorkOps UI inventory](../../workops/workops-ui/docs/studio-ui-inventory.md) records source parity,
measured workflow evidence and remaining qualification gates.

WorkOps serves its `/collaboration` WebSocket inside this BML host. Content owns atomic CRDT
initialization and persistence through the same-origin collaboration proxy. Multiple UI instances
share document invalidations and presence through Bosca's pub/sub service. Embedded Atlas uses its
configured service. For standalone hosts, set `NATS_URL` and the existing NATS token or
username/password variables, or set `PUBSUB_TYPE=redis` with `REDIS_HOST`, `REDIS_PORT`,
`REDIS_DATABASE` and `REDIS_NAMESPACE`. Every UI instance for the same API must use the same
pub/sub backend and account/namespace. With neither backend configured, standalone development
keeps collaboration local to that UI process.

The Swarm renderer passes its selected NATS/Redis configuration to standalone UI;
the Compose template passes its existing NATS endpoint and token. Both gateway
templates route imported WorkOps pages and `/collaboration` to the UI listener.
Focused deployment tests resolve the packaged broker configuration and exercise
real Caddy/nginx HTTP and WebSocket forwarding. These checks cover deployment
preparation; a target-specific cutover and rollback rehearsal remains separate.
