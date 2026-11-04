# BML authentication UI

Shared authentication forms, SDK flows, appearance and profile-menu behavior for BML applications.
This library has no Atlas, Git or artifacts dependencies. Each application owns its public routes,
branding, landing page, subsystem catalog and same-origin GraphQL endpoint.

Import `io.bosca:bml-auth-ui` in BML, then compose the exported `auth-layout`, `auth-heading` and
`auth-*-form` components. The layout receives `product`, `heading` and `description` from the host.
Hosts include `AuthUi.css` in their global stylesheet so the base theme also reaches domain and landing
pages that do not render authentication components. BML packages the scoped component assets.

The hosting application's global client entry calls `initializeAuth(originator)` from
`@bosca/bml-auth-ui` after BML provides its cookie configuration. Shared controls use the resulting
`boscaAuth` session and `boscaAuthFlow`; no second SDK instance or direct backend origin is needed.
Use `<auth-profile-menu />` for the signed-in account menu. The component owns its client
lifecycle, profile loading, appearance toggle and sign out; consumers need no mount call or action markup.
`<auth-profile-menu showIdentity defaultAppearance="light" />` loads the current profile for
the trigger and menu heading, and uses a light default when no appearance is saved. By default,
identity loads on opening and the appearance fallback is dark. `label`, `icon` and `width` configure
the shared design-library menu; projected content adds menu items before Sign out.
`savedAppearance("light")` uses the same fallback for the host's global entry; initial server
documents read `git_ui_appearance` to apply the saved light/dark choice before client startup.

Run from the workspace root:

```bash
./gradlew :bml:bml-auth-ui:check :git:git-ui:check :server:atlas-ui:check
```

Checks cover the shared browser flows, TypeScript, and application-owned route, proxy and asset
behavior. Real standalone Git and composed Atlas processes supply authentication integration tests.
