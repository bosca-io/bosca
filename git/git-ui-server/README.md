# Git UI server

The BML host for `:git:git-ui`. It serves pages and packaged browser assets and forwards GraphQL, OAuth entry points, subscriptions, raw files and CI artifacts to `:git:git-server`. Authentication and domain authorization remain in the API. The UI host has no database, Git implementation or security-provider setup.

Run from the workspace root in separate terminals:

```bash
./gradlew :git:git-server:run
./gradlew :git:git-ui-server:run
```

Open `http://localhost:8092`. Git server defaults to port 8091. Its existing PostgreSQL, NATS and storage/search configuration still applies. Fresh installations use the API's normal initial-administrator setup.

| Setting | Owner | Default / purpose |
| --- | --- | --- |
| `GIT_UI_HTTP_PORT` | UI | `8092` |
| `BML_GRAPHQL_ENDPOINT` | UI | `http://localhost:8091/graphql` |
| `BML_AUTH_COOKIE_PREFIX` | UI | Unset selects `_bat`; set alongside API `jwt.cookie-prefixes`. |
| `GIT_HTTP_PORT` | API | `8091` |
| `GIT_URL` | API | Public clone/transport origin, default `http://localhost:8091`. |
| `GIT_UI_URL` | API | Public UI origin for clone-URL browser redirects, default `http://localhost:8092`. |
| `APP_URL`, `APP_ALLOWED_ORIGIN` | API | UI origin, default `http://localhost:8092`; used by auth and email return flows. |
| `OAUTH2_ALLOWED_REDIRECT` | API | UI origin, default `http://localhost:8092`. |
| `OAUTH2_GOOGLE_REDIRECT`, `OAUTH2_GOOGLE_ADMIN_REDIRECT` | API | Provider callback on Git server, default `http://localhost:8091/oauth2/google/callback`. |

Google login/connect use BML's existing same-origin OAuth proxy. The provider returns to the API callback; the API redirects back to the allowed UI destination for the SDK exchange. Browser GraphQL uses `/graphql` on the UI origin, including session-cookie responses. Configure provider credentials and public callback URLs on the API. For a custom cookie prefix, configure `jwt.cookie-prefixes` for the UI/API public hosts and select the same prefix with `BML_AUTH_COOKIE_PREFIX`.

Subscriptions relay from UI `/_git/subscriptions` to API `/graphqlws`. Raw files and artifact downloads keep their existing paths on the UI origin. Their proxy forwards credentials and origin without interpreting tokens, streams the response, and preserves upstream status, MIME and file security headers. Git smart HTTP, LFS, webhooks and CI-agent transports stay on the API origin.

Build and validate:

```bash
./gradlew :git:git-ui:check :git:git-ui-server:test :git:git-server:test
./gradlew :git:git-ui-server:installDist :git:git-server:installDist
./gradlew :git:git-ui-server:nativeCompile
```

`GitUiServerIntegrationTest` starts separate production UI/API processes with isolated PostgreSQL and NATS. It checks password signup/login, profile identity, packaged assets, repository reads and mutations, deferred files, search, raw delivery, subscriptions, invalid credentials, sign-out cookies, custom prefixes, anonymous shared shells and token-override scope isolation. `GitFileProxyTest` checks encoded nested paths, binary bytes, file headers, upstream rejections and delivery before the upstream finishes producing a download. Tests do not send email or complete an external OAuth exchange.

See the [Git application specification](../../specs/git/bml-git-application-spec.md) and [validation record](../../specs/git/parity-validation.md). Studio and the main server remain in place until their separate cutover gates pass.
