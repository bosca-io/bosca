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

Package installs inherit the `@bosca` registry and authentication from npm's user configuration
(`$HOME/.npmrc` by default). CI configures both automatically. For local builds, configure the
registry that serves `@bosca` packages and its token before running Gradle or npm:

```bash
npm config set @bosca:registry 'https://artifacts.example.com/npm/' --location=user
npm config set '//artifacts.example.com/npm/:_authToken' '<Bosca API token>' --location=user
```

From the workspace root:

```bash
./gradlew :profiles-web:run
./gradlew :profiles-web:test
./gradlew :profiles-web:check
./gradlew :profiles-web:bmlBundleClient
```

`BML_GRAPHQL_ENDPOINT` configures both SSR data loading and the `/graphql` proxy. It defaults to
`http://localhost:8080/graphql`; in Kubernetes it should use the internal Bosca API service.

The root Gradle build resolves the BML runtime from the local workspace project. The browser
auth package is linked from `web/packages/auth` for local development. BML version pins in this
application track available published releases while coordinated protocol changes are developed
in the workspace.

Interactive tags own their scoped client setup, updates and cleanup. The global `profiles.ts`
entry initializes authentication only. Dates format within `local-date-time`; profile details
and attribute forms own dirty state, submission data and dialogs; visibility and action menus
own disclosure handling. The standalone `sign-out-link` owns clearing the session before navigation.

Keep `toast-message` mounted with a stable, instance-specific `preserveKey` and increment its
message `id` for each notice. The initial value is a baseline; later increases display notices,
including repeated text. Each tag disposes its timers and notices on unmount. Active notices
share a stacking surface created on demand without application initialization or page scans.

`check` runs JVM tests, TypeScript checking and lifecycle regressions against actual compiled tags
and the BML runtime in an isolated DOM. These tests cover independent instances, fragment updates,
dialog validation/cancellation and cleanup; they do not use a deployed API.
