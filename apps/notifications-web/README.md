# notifications-web

The public notification preferences / unsubscribe site — a BML server-side-rendered app
(bml-server, port 9094) deployed as a GraalVM native image. It serves the pages the links minted
into notification email footers land on:

- `/preferences?token=…` — the per-channel notification matrix (email + push, per type)
- `/unsubscribe?token=…` — one-click unsubscribe from all optional email

Set `BOSCA_NPM_REGISTRY` to the registry that serves `@bosca` packages, as host and path (for example
`artifacts.example.com/npm`), and `NPM_TOKEN` to a token for it. The checked-in `.npmrc` reads both variables.

Both pages are fully anonymous: the unsubscribe token minted per send is the
credential, carried in the link's query string. There is no auth SDK or authored client script;
the declarative `@click` live-island actions compile to BML runtime bundles, while every action
executes server-side over the token-scoped GraphQL operations (`tokenNotificationPreferences`,
`updateTokenNotificationPreference`, `unsubscribe`).

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
```

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
