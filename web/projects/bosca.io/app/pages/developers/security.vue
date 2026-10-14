<script setup lang="ts">
definePageMeta({ layout: 'developers' })
useSeoMeta({ title: 'Security', description: 'Authenticate accounts, manage credentials, and understand access tokens and sign-in sessions.' })
const login = 'mutation Login($identifier: String!, $password: String!) {\n  security {\n    login {\n      password(identifier: $identifier, password: $password) {\n        principal { id primaryProfileId }\n        token { token expiresAt }\n        refreshToken\n      }\n    }\n  }\n}'
const refresh = 'mutation Refresh($refreshToken: String!) {\n  security {\n    login {\n      refreshToken(refreshToken: $refreshToken) {\n        token { token expiresAt }\n        refreshToken\n      }\n    }\n  }\n}'
const sessions = 'query SignIns {\n  security {\n    principals {\n      current {\n        id\n        loginHistory(offset: 0, limit: 25) { id method created revokedAt current }\n      }\n    }\n  }\n}\n\nmutation SignOut {\n  security { login { signOut } }\n}'
</script>

<template>
  <div class="doc-content article">
    <h1>Security</h1>
    <p class="subtitle">
      Authenticate accounts, manage credentials, and understand access tokens and sign-in sessions.
    </p>
    <h2 id="overview">
      Principals and profiles
    </h2>
    <p>
      A <strong>Principal</strong> is an account's security identity. It owns credentials, sign-in
      sessions, and group memberships. A <NuxtLink to="/developers/profile">Profile</NuxtLink>
      stores social and contact information and has a separate ID. A principal can own multiple profiles.
    </p>
    <h2 id="credentials">
      Authentication methods
    </h2>
    <ul>
      <li><strong>Passwords:</strong> stored as Argon2 hashes. Existing Scrypt password credentials are supported.</li>
      <li><strong>OAuth2:</strong> configured third-party providers can sign in or link credentials to an account.</li>
      <li><strong>Passkeys:</strong> WebAuthn credentials use public keys and authenticator sign counters.</li>
      <li><strong>API tokens:</strong> separate programmatic access records, stored as SHA-256 hashes with optional scopes and allowed groups.</li>
    </ul>
    <p>
      Enable OAuth2 providers in the deployment configuration. Query <code>security.thirdPartyProviders</code>
      for the enabled providers. Email verification and password recovery require a configured mailer.
    </p>
    <h2 id="authentication-flows">
      Password login
    </h2>
    <p>
      Send this operation to <code>/graphql</code> with the account's identifier and password as
      GraphQL variables. On the local Compose instance, the initial identifier is <code>admin</code>.
    </p>
    <CodeBlock
      lang="graphql"
      :code="login"
    />
    <p>
      The access token is the string at <code>data.security.login.password.token.token</code>.
      Send it in <code>Authorization: Bearer TOKEN</code> for subsequent programmatic requests.
      <code>expiresAt</code> is a Unix timestamp in seconds. A refresh token is returned when enabled.
    </p>
    <h2 id="refresh">
      Refresh the session
    </h2>
    <CodeBlock
      lang="graphql"
      :code="refresh"
    />
    <p>
      Refresh tokens are single-use. Store the newly returned refresh token along with the access
      token; retrying with the consumed token fails. A revoked sign-in cannot refresh.
    </p>
    <h2 id="sessions">
      Sign-in history and sign-out
    </h2>
    <CodeBlock
      lang="graphql"
      :code="sessions"
    />
    <p>
      <code>signOut</code> revokes the current recorded sign-in and clears its session cookie, leaving
      other recorded sign-ins active. <code>security.principal.revokeLogin(loginId: ...)</code>
      revokes a selected sign-in owned by the current principal.
    </p>
    <p>
      JWTs also carry a <code>tver</code> claim checked against <code>Principal.tokenVersion</code>.
      Password changes, resets, identifier changes, and account deletion can invalidate all sessions.
      Signing out a token without a recorded login uses account-wide revocation.
    </p>
    <h2 id="signup">
      Signup and account linking
    </h2>
    <p>
      New clients should use <code>security.signup.passwordV2</code>,
      <code>thirdpartyV2</code>, and <code>passwordVerifyV2</code>. Their <code>SignupResult</code>
      can contain a principal, a login response, or a link challenge. If signup encounters an existing
      verified account, complete the challenge through <code>security.link</code> using password
      proof or email proof.
    </p>
    <p>
      Third-party token exchange is available through <code>security.signup.thirdpartyV2</code>;
      connecting an additional method uses <code>security.connectThirdParty</code>.
      Browser OAuth2 flows use the configured provider routes.
    </p>
    <h2 id="api-tokens">
      Programmatic access
    </h2>
    <p>
      Create and manage tokens through Studio, <code>security.apiTokens</code>, or the
      <NuxtLink to="/developers/cli">CLI</NuxtLink>. The raw token is returned once at creation.
      Scopes and allowed groups restrict the account's authority; they do not grant access
      the account does not already hold.
    </p>
    <h2 id="browser-auth">
      Browser applications
    </h2>
    <p>
      Bosca's Nuxt applications use <code>@bosca/auth-client-browser</code> and the existing
      same-origin proxy routes. Reuse the auth plugin and middleware for SSR and client navigation.
      The library manages browser-readable tokens and refresh scheduling; avoid implementing a
      separate cookie parser or token lifecycle.
    </p>
    <h2 id="source">
      Where to look in the workspace
    </h2>
    <p>
      GraphQL fields are defined in <code>bosca-core/security/src/main/resources/graphql/security/</code>.
      Authentication behavior lives in <code>SecurityServiceImpl</code>; API authorization is covered by
      <NuxtLink to="/developers/permissions">Permissions</NuxtLink>.
    </p>
  </div>
</template>
