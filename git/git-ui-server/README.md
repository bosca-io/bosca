# Git UI server

The BML host for `:git:git-ui`. It serves pages and packaged browser assets and forwards GraphQL, OAuth entry points, subscriptions, raw files and CI artifacts to `:git:git-server`. Authentication and domain authorization remain in the API. The UI host has no database, Git implementation or security-provider setup. `git-server` embeds this module on a second port in the same process, with its own router and request middleware. The API owns process-wide services; UI shutdown drains UI requests before stopping the API. A failed listener stops the whole host.

Run from the workspace root:

```bash
./gradlew :git:git-server:run
```

Open `http://localhost:8093`. Git server defaults to port 8091. Its existing PostgreSQL, NATS and storage/search configuration still applies. Fresh installations use the API's normal initial-administrator setup.

| Setting | Owner | Default / purpose |
| --- | --- | --- |
| `GIT_UI_HTTP_PORT` | UI | `8093` |
| `BML_GRAPHQL_ENDPOINT` | Standalone UI | `http://localhost:8091/graphql`; embedded UI always uses loopback HTTP to the API’s bound port. |
| `BML_AUTH_COOKIE_PREFIX` | UI | Unset selects `_bat`; set alongside API `jwt.cookie-prefixes`. |
| `GIT_HTTP_PORT` | API | `8091` |
| `GIT_URL` | API | Public clone/transport origin, default `http://localhost:8091`. |
| `GIT_UI_URL` | API | Public UI origin for clone-URL browser redirects, default `http://localhost:8093`. |
| `APP_URL`, `APP_ALLOWED_ORIGIN` | API | UI origin, default `http://localhost:8093`; used by auth and email return flows. |
| `OAUTH2_ALLOWED_REDIRECT` | API | UI origin, default `http://localhost:8093`. |
| `OAUTH2_GOOGLE_REDIRECT`, `OAUTH2_GOOGLE_ADMIN_REDIRECT` | API | Provider callback on Git server, default `http://localhost:8091/oauth2/google/callback`. |

Google login/connect use BML's existing same-origin OAuth proxy. The provider returns to the API callback; the API redirects back to the allowed UI destination for the SDK exchange. Browser GraphQL uses `/graphql` on the UI origin, including session-cookie responses. Configure provider credentials and public callback URLs on the API. For a custom cookie prefix, configure `jwt.cookie-prefixes` for the UI/API public hosts and select the same prefix with `BML_AUTH_COOKIE_PREFIX`.

Subscriptions relay from UI `/_git/subscriptions` to API `/graphqlws`. Raw files and artifact downloads keep their existing paths on the UI origin. Their proxy forwards credentials and origin without interpreting tokens, streams the response, and preserves upstream status, MIME and file security headers. Git smart HTTP, LFS, webhooks and CI-agent transports stay on the API origin.

For a standalone UI host, `:git:git-ui-server:run` still reads `git-ui-server.yaml` and `BML_GRAPHQL_ENDPOINT`. Do not start it on the same UI port as the combined host. The combined host reads `application.yaml` (or `-config=...`): API transport settings remain under `bosca.server`; UI transport settings live under `git.ui.server`, with cookie selection under `git.ui.auth-cookie-prefix`. Both listeners support ordinary Netty transport settings independently.

Helm, the portable Compose installer and Swarm serve both listeners on the existing
`git.<domain>` public origin with path routing. Known UI pages and BML endpoints
use 8093; Git/API paths use 8091. See [Helm deployment settings](../../helm/README.md#git-api-and-bml-ui)
and [Compose upgrade instructions](../../deployments/bosca-compose/README.md#data-updates-and-removal).
Root Compose serves both at `http://bosca.localhost:8091`, configurable with
`BOSCA_GIT_PORT`. Direct JVM development still uses the two local listeners.
Use a matching rebuilt Git image.

Both runnable hosts apply `io.bosca.bml.host` to package their own build identity, including in native
images. The compiled `git-ui` library has no identity. A local identity stays stable until `clean`;
fresh CI builds receive a new one. The combined host includes both application identities in BML's
deployment digest, so classpath order cannot hide changes to either host.

Build and validate:

```bash
./gradlew :git:git-ui:check :git:git-ui-server:test :git:git-server:test
./gradlew :git:git-server:installDist
./gradlew :git:git-server:nativeCompile
```

`GitServerIntegrationTest` starts one production process with both listeners against isolated PostgreSQL and NATS. It checks password signup/login, profile identity, packaged assets, repository reads and mutations, deferred files, search, raw delivery, subscriptions, invalid credentials, sign-out cookies, custom prefixes, anonymous shared shells and token-override scope isolation, including graceful process shutdown releasing both ports. `GitFileProxyTest` checks encoded nested paths, binary bytes, file headers, upstream rejections and delivery before the upstream finishes producing a download. `GitServerLifecycleTest` covers API/UI bind failures, UI installation failure and sibling-listener shutdown. Tests do not send email or complete an external OAuth exchange.

Run native acceptance for the standalone UI host with:

```bash
./gradlew :git:git-ui-server:nativeCompile --build-args=-Ob :git:git-ui:nativeUiHostTest
```

The dedicated test copies the executable into a fresh directory with explicit local configuration
and starts the production entry point against the existing isolated API/file fixture. HTTP checks
cover six representative pages, editor isolation, one CodeMirror/runtime instance, asset budgets
and exact packaged font bytes. Real browser checks cover source search, unsaved Markdown preview,
wrapping, rejected/successful saves, structured JSON editing and responsive layouts. The normal
JVM test task excludes this native check; explicitly invoking it always executes the check.

Native compilation emits `embedded-resources.json`. Acceptance verifies its resource origins and
sizes against digest-checked JAR inputs, including indexed assets and dependency notices. Native
font bytes are also checked over HTTP. Logs, the input-resource digest record, executable identity
and browser captures are retained under `git/git-ui/build/reports/native-ui/`. `-Ob` selects quick
optimization for validation; omit it to use the host's normal `-O3` build. Combined API/UI native
acceptance uses the separate Git server integration path.

See the [Git application specification](../../specs/git/bml-git-application-spec.md) and [validation record](../../specs/git/parity-validation.md). Studio and the main server remain in place until their separate cutover gates pass.
