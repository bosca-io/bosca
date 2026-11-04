# BML authentication UI

Shared authentication forms and SDK flows for BML applications.
This library has no Atlas, Git or artifacts dependencies. Each application owns its public routes,
branding, landing page, subsystem catalog and same-origin GraphQL endpoint.

Import `io.bosca:bml-auth-ui` in BML, then compose the exported `auth-layout`, `auth-heading` and
`auth-*-form` components. The layout receives `product`, `heading` and `description` from the host.
Hosts include `AuthUi.css` in their global stylesheet so the base theme also reaches domain and landing
pages that do not render authentication components. BML packages the scoped component assets.

The hosting application's global client entry calls `initializeAuth(originator)` from
`@bosca/bml-auth-ui` after BML provides its cookie configuration. Shared controls use the resulting
`boscaAuth` session and `boscaAuthFlow`; no second SDK instance or direct backend origin is needed.
Use `<profile-menu />` from the design library for profile loading, appearance switching and
sign-out. The tag uses the existing host session; no account-menu adapter or manual mount call
is required.

Use `<auth-passkey-form />` inside a design-library dialog for passkey registration. The tag owns
the SDK ceremony, busy/error state and cancellation when the dialog closes or the component
unmounts. Fields and errors use the padded modal body; Cancel and Register use the shared bordered footer. BML forwards the fixed registration begin/complete routes to the configured API with
the current token and request origin. Successful registration reloads the current page.

After an API operation has already revoked the current session, render
`<auth-revoked-session :href="signInHref" />`. It clears local SDK state through `clearSession()`
and navigates to the supplied local return path. Ordinary sign-out remains owned by the profile
menu. The login form recognizes `passkeyRemoved=true` to explain why sign-in is required.

Run from the workspace root:

```bash
./gradlew :bml:bml-auth-ui:check :git:git-ui:check :server:atlas-ui:check
```

Checks cover the shared browser flows, TypeScript, and application-owned route, proxy and asset
behavior. Real standalone Git and composed Atlas processes supply authentication integration tests.
