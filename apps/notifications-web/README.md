# notifications-web

The public notification preferences / unsubscribe site — a BML server-side-rendered app
(bml-server, port 9094) deployed as a GraalVM native image. It serves the pages the links minted
into notification email footers land on:

- `/preferences?token=…` — the per-channel notification matrix (email + push, per type)
- `/unsubscribe?token=…` — one-click unsubscribe from all optional email

Package installs inherit the `@bosca` registry and authentication from npm's user configuration
(`$HOME/.npmrc` by default). CI configures both automatically. For local builds, configure the
registry that serves `@bosca` packages and its token before running Gradle or npm:

```bash
npm config set @bosca:registry 'https://artifacts.example.com/npm/' --location=user
npm config set '//artifacts.example.com/npm/:_authToken' '<Bosca API token>' --location=user
```

Both pages are fully anonymous: the unsubscribe token minted per send is the
credential, carried in the link's query string. There is no auth SDK or authored client script;
the declarative `@click` live-island actions compile to BML runtime bundles, while every action
executes server-side over the token-scoped GraphQL operations (`tokenNotificationPreferences`,
`updateTokenNotificationPreference`, `unsubscribe`).

The preference switches, action buttons and outcome toasts come from the compiled Bosca Design
Library through one unaliased `imports.bml`. Native attributes and declarative change/submit actions
pass through the short tags. This application owns the token-scoped models, confirmation forms,
navigation and light palette in `src/main/client/notifications.css`. Deployment branding still
overrides its validated primary/accent colors. Repeated outcomes advance a toast sequence; navigation
does not replay them. Invalid links and the final unsubscribe confirmation remain page content.

Branding is configurable per deployment and **Bosca by default**:

- `BRAND_NAME` — name used in titles and copy
- `BRAND_LOGO_URL` — optional logo shown in the header; without it the name is shown as text
- `BRAND_PRIMARY_COLOR` — primary button/header color as CSS hex (`#RGB[A]` or `#RRGGBB[AA]`)
- `BRAND_ACCENT_COLOR` — link, success, and active-toggle color as CSS hex
- `BRAND_FOOTER_HTML_FILE` — path to trusted footer HTML read verbatim from disk; when unset,
  the footer defaults to `© {BRAND_NAME}`. A configured file must exist and be readable.

For example:

```bash
BRAND_NAME=Acme \
BRAND_LOGO_URL=https://cdn.acme.example/logo.svg \
BRAND_PRIMARY_COLOR="#172554" \
BRAND_ACCENT_COLOR="#0369a1" \
BRAND_FOOTER_HTML_FILE=/etc/notifications-web/footer.html \
./gradlew :notifications-web:run
```

In production the site sits behind the `messages.<domain>` host: bml-message-server keeps its
`/assets`, `/c`, and `/o` prefixes (email bundle assets + engagement tracking) and this site
takes everything else via a catch-all HTTPRoute (Gateway API longest-prefix precedence).

## Run

```bash
./gradlew :notifications-web:run                        # from the bosca-workspace root → http://localhost:9094/
./gradlew :notifications-web:downloadBoscaGraphqlSchema # refresh src/main/graphql/schema.graphqls
./gradlew :notifications-web:test                      # unit + isolated product/browser/Maven-fixture checks
./gradlew :notifications-web:nativeCompile --build-args=-Ob :notifications-web:nativeDesignAdoptionTest
```

Gradle prepares the pinned Chromium for browser tests, including the native adoption check. The
first run downloads the browser; later runs reuse Playwright's cache. Set `BML_DESIGN_CHROME_BINARY`
to use an existing executable and bypass preparation.

The product checks use an isolated token-scoped GraphQL fixture and Chrome profile, with successful,
rejected and pending actions, repeated toasts, navigation/Back, consumer CSS changes and desktop/phone
geometry. They exercise packaged workspace and source-free Maven-fixture builds in production and
development. The fixture repository contains the real compiled library and runtime JARs, with no
producer BML or source archives; a copied application compiles its authored source and generated API
clients against those artifacts. Clean and cached builds must retain identical reachable assets.
This does not publish a remote release. Reports and captures are under `build/reports/design-adoption/`.

The dedicated native check uses the production executable from an isolated directory with the same
API/browser contracts and exact shared font checks. Reports are under
`build/reports/native-design-adoption/`. `-Ob` is a quick validation build; omit it for the normal
release optimization. Workspace builds use the local BML toolchain and need no site npm install;
standalone builds retain the configured published package and npm registry setup above.

`BML_GRAPHQL_ENDPOINT` points loaders and the same-origin `/graphql` proxy at the Bosca API
(default `http://localhost:8080/graphql`; in-cluster it is the bosca-server service).

## Deploy

The native image is published as `ghcr.io/bosca-io/bosca/notifications-web`. The chart is
`helm/notifications-web` in the workspace (a `file://` dependency of `bosca-services`);
enablement, branding environment variables, and the mailer link URLs
(`appConfig.mailerUnsubscribeUrl` / `mailerPreferencesUrl` on bosca-server/bosca-runner) are
set in the deployment's Helm values.

Push toggles require the communications release that adds the `channel` argument to
`updateTokenNotificationPreference`; email management works against older servers.

TODO: favicon — needs the real brand mark (transparent background).
