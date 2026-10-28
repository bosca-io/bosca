# Bosca Atlas UI server

Standalone BML host for the shared Atlas authentication/landing pages and Git UI. It serves the
same application used by Atlas's embedded UI listener and uses HTTP proxies for domain operations.
It has no database, migration, security-provider, or job-consumer initialization.

Run from the workspace root:

```bash
./gradlew :server:atlas-ui-server:run
./gradlew :server:atlas-ui-server:installDist
```

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
and the root landing route. Artifacts UI remains a separate BML module for the Studio port.
See [Atlas server](../atlas-server/README.md) for deployment roles and production-process tests.
