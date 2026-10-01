<script setup lang="ts">
useSeoMeta({ title: 'Security' })
</script>

<template>
  <div class="doc-content article">
    <h1>Security</h1>
    <p class="subtitle">
      Authentication, credentials, token lifecycle, and session management —
      <code>SecurityService</code>, JWT token versioning, OAuth2, passkeys, and API tokens.
    </p>

    <h2 id="overview">
      Overview
    </h2>
    <p>
      Bosca's security layer is built around <strong>Principals</strong> (user accounts) and
      <strong>Credentials</strong> (authentication methods). A single Principal can have multiple
      credentials — password, OAuth2, API tokens, and WebAuthn passkeys — all managed through
      the <code>SecurityService</code>.
    </p>

    <h2 id="principal">
      Principal
    </h2>
    <p>The core identity record. Every authenticated user is a Principal.</p>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class Principal(
    val id: UUID = UUID.NIL,
    val created: OffsetDateTime,
    val modified: OffsetDateTime,
    val verified: Boolean = false,
    val anonymous: Boolean = true,
    val attributes: JsonElement? = null,
    val verificationToken: String? = null,
    val primaryProfileId: UUID? = null,
    val tokenVersion: Int = 0,    // monotonic counter — bumped to invalidate sessions
)`"
    />
    <Callout type="info">
      <code>tokenVersion</code> is central to session security — every JWT includes a <code>tver</code> claim
      that must match the Principal's current version, or the token is rejected.
    </Callout>

    <h2 id="credentials">
      Credential Types
    </h2>
    <p>
      Credentials are polymorphic — each type stores its data in a JSON <code>attributes</code> field
      on <code>PrincipalCredential</code>.
    </p>
    <table>
      <thead>
        <tr>
          <th>Type</th>
          <th>Storage</th>
          <th>Notes</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>PASSWORD</code></td>
          <td>Argon2 hash</td>
          <td>Primary login method</td>
        </tr>
        <tr>
          <td><code>PASSWORD_SCRYPT</code></td>
          <td>Scrypt hash</td>
          <td>Legacy support — migrated on next login</td>
        </tr>
        <tr>
          <td><code>OAUTH2</code></td>
          <td>Provider token</td>
          <td>Google, Facebook, Apple</td>
        </tr>
        <tr>
          <td><code>API_TOKEN</code></td>
          <td>SHA-256 hash</td>
          <td>Scoped tokens with group restrictions</td>
        </tr>
        <tr>
          <td><code>PASSKEY</code></td>
          <td>WebAuthn public key</td>
          <td>FIDO2 with sign-count anti-cloning</td>
        </tr>
      </tbody>
    </table>

    <h2 id="authentication-flows">
      Authentication Flows
    </h2>

    <h3>Password Login</h3>
    <CodeBlock
      lang="graphql"
      :code="`mutation Login($identifier: String!, $password: String!) {
  security {
    login {
      password(identifier: $identifier, password: $password) {
        token { token, expiresAt }
        refreshToken
      }
    }
  }
}`"
    />
    <p>
      Returns a short-lived JWT (<code>token</code>) and a long-lived <code>refreshToken</code>.
      The refresh token is single-use — exchanging it atomically deletes the old one and issues a new pair.
    </p>

    <h3>OAuth2 / Third-Party</h3>
    <p>
      Supports <code>GOOGLE</code>, <code>FACEBOOK</code>, and <code>APPLE</code>. The client obtains a
      provider token and exchanges it via the <code>signupThirdParty</code> or <code>loginThirdParty</code>
      mutations. Bosca validates the token with the provider and creates or links the credential.
    </p>

    <h3>Passkeys (WebAuthn)</h3>
    <p>
      FIDO2/WebAuthn passkeys for passwordless authentication. The server tracks the
      <code>signCount</code> to detect cloned authenticators.
    </p>

    <h3>API Tokens</h3>
    <p>
      Long-lived tokens for programmatic access. Each token has optional <strong>scopes</strong> and
      <strong>allowed groups</strong> to restrict what it can do. The raw token is shown once at creation;
      only the SHA-256 hash is stored.
    </p>

    <h2 id="token-versioning">
      Token Versioning
    </h2>
    <p>
      Every JWT includes a <code>tver</code> (token version) claim. The Principal's <code>tokenVersion</code>
      is a monotonic counter that increments on security-sensitive events:
    </p>
    <ul>
      <li>Password change</li>
      <li>Identifier (email) change</li>
      <li>Credential deletion</li>
      <li>Explicit logout (all sessions)</li>
    </ul>
    <p>
      When <code>tver</code> in the JWT doesn't match the current <code>tokenVersion</code>, the token
      is rejected — effectively invalidating all outstanding sessions without maintaining a token blacklist.
    </p>

    <h2 id="groups">
      Groups &amp; Roles
    </h2>
    <p>
      Groups are the foundation of authorization. Every <code>Group</code> carries a
      <code>GroupType</code>:
    </p>
    <table>
      <thead>
        <tr>
          <th>GroupType</th>
          <th>Purpose</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>SYSTEM</code></td>
          <td>Built-in roles (SA, admin, editor, manager) plus user-defined groups for team/project access control</td>
        </tr>
        <tr>
          <td><code>PRINCIPAL</code></td>
          <td>Auto-created per user, used for personal entity permissions</td>
        </tr>
      </tbody>
    </table>
    <Callout type="tip">
      See the <NuxtLink to="/developers/permissions">Permissions</NuxtLink> page for how groups
      interact with <code>PermissionEvaluator</code> to control entity access.
    </Callout>

    <h2 id="security-service">
      SecurityService
    </h2>
    <p>The central interface for all authentication and credential management:</p>
    <CodeBlock
      lang="kotlin"
      :code="`interface SecurityService {
    suspend fun loginWithCredential(identifier: String, password: String): LoginResponse
    suspend fun authenticateWithPayload(payload: JWTPayload): AuthenticationContext
    suspend fun createToken(principal: Principal): Token
    suspend fun refreshToken(refreshToken: String): LoginResponse
    suspend fun addCredential(principalId: UUID, credential: PrincipalCredentialInput)
    suspend fun deleteCredential(principalId: UUID, credentialId: Long)
    suspend fun resetPassword(token: String, password: String)
    suspend fun verifyEmail(token: String): Principal
    // ... group management, principal CRUD, API tokens, passkeys
}`"
    />

    <h2 id="email-verification">
      Email Verification
    </h2>
    <p>
      New accounts can optionally require email verification. A <code>verificationToken</code> is generated
      on signup and sent via email. The <code>verify</code> mutation validates the token and marks
      the Principal as <code>verified</code>.
    </p>

    <h2 id="password-reset">
      Password Reset
    </h2>
    <p>
      The forgot-password flow generates a time-limited reset token sent via email. The
      <code>resetPassword</code> mutation validates the token, updates the password hash, and bumps
      <code>tokenVersion</code> to invalidate all existing sessions.
    </p>
  </div>
</template>
