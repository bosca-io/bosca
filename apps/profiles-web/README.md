# profiles-web

`profiles-web` is the branded BML self-service account site. It runs on port `9095` and provides:

- profile name, slug, visibility, and discoverability editing;
- incoming, outgoing, and active relationship management;
- email and push preferences plus push quiet hours;
- registered-device review and removal;
- credential and passkey review, passkey removal, and recent login history.

Protected pages use BML's auth gate. Sign-in remains client-managed through
`@bosca/auth-client-browser`, with browser API traffic sent through the same-origin `/graphql`
proxy. When `BML_AUTH_COOKIE_PREFIX` is set, both BML and the browser SDK use that exact host-only
auth cookie prefix without a metadata request. Without it, the shared
default `_bat` cookie remains in use.

Cross-domain OAuth callbacks return through the public `/login` route with a one-time
`exchangeToken`. The global browser client exchanges it before navigating to the originally
requested protected page; sending the callback straight to a protected route would let the SSR
auth gate redirect before the browser could consume the token.

Branding follows `notifications-web` and is configured per deployment:

- `BRAND_NAME`
- `BRAND_LOGO_URL`
- `BRAND_PRIMARY_COLOR` (`#RGB[A]` or `#RRGGBB[AA]`)
- `BRAND_ACCENT_COLOR`
- `BRAND_FOOTER_HTML_FILE` (trusted HTML rendered verbatim)

Set `BML_AUTH_COOKIE_PREFIX` to the custom prefix configured for this deployment's domain on the server.
`PROFILES_WEB_COOKIE_DOMAIN` applies only to the default `_bat` cookie when no custom prefix is
configured; leave it unset for host-only local development.

The default brand is Bosca. Each deployment routes its own profiles host (for example
`profiles.example.com`) to this service with its own values and assets.

The checked-in `.npmrc` selects `BOSCA_NPM_REGISTRY` (the registry serving `@bosca` packages, as host
and path, for example `artifacts.example.com/npm`) and inherits authentication from `$HOME/.npmrc`.
CI's `setup-registry` action exports the registry address and writes the agent's Bosca token to that
user configuration. For local builds, select the registry and configure its token before running
Gradle or npm:

```bash
export BOSCA_NPM_REGISTRY=artifacts.example.com/npm
npm config set "//${BOSCA_NPM_REGISTRY}/:_authToken" '<Bosca API token>' --location=user
```

From the workspace root:

```bash
./gradlew :profiles-web:run
./gradlew :profiles-web:test
./gradlew :profiles-web:bmlBundleClient
```

`BML_GRAPHQL_ENDPOINT` configures both SSR data loading and the `/graphql` proxy. It defaults to
`http://localhost:8080/graphql`; in Kubernetes it should use the internal Bosca API service.

The root Gradle build resolves the BML runtime from the local workspace project. The browser
auth package is linked from `web/packages/auth` for local development. BML version pins in this
application track available published releases while coordinated protocol changes are developed
in the workspace.
