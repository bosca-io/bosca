<script setup lang="ts">
useSeoMeta({ title: 'Profile' })
</script>

<template>
  <div class="doc-content article">
    <h1>Profile</h1>
    <p class="subtitle">
      User identity beyond authentication — <code>ProfileService</code>, profile types, flexible
      attributes, visibility controls, and the relationship to Principals.
    </p>

    <h2 id="overview">
      Overview
    </h2>
    <p>
      A <strong>Profile</strong> represents a user's public-facing identity. While a
      <NuxtLink to="/developers/security">Principal</NuxtLink> handles authentication,
      Profiles hold the information others see — name, avatar, email, and extensible attributes.
      A single Principal can own multiple Profiles.
    </p>

    <h2 id="model">
      Profile Model
    </h2>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class Profile(
    override val id: UUID = UUID.NIL,
    val type: ProfileType,            // GENERIC, ORGANIZATION, or CHILD
    val principal: UUID? = null,      // owning principal (nullable for org / placeholder profiles)
    val collectionId: UUID? = null,   // associated content collection
    val name: String,
    val visibility: ProfileVisibility,
    val created: OffsetDateTime,
    val modified: OffsetDateTime,
) : PermissibleEntity<UUID>, Indexable, ContentItem`"
    />

    <h2 id="types">
      Profile Types
    </h2>
    <table>
      <thead>
        <tr>
          <th>Type</th>
          <th>Purpose</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>GENERIC</code></td>
          <td>Standard user profile — one per Principal by default</td>
        </tr>
        <tr>
          <td><code>ORGANIZATION</code></td>
          <td>Linked to an Organization entity for org-level identity</td>
        </tr>
        <tr>
          <td><code>CHILD</code></td>
          <td>Sub-profile managed by a parent — for dependent accounts</td>
        </tr>
      </tbody>
    </table>
    <p>
      Every Principal has a <code>primaryProfileId</code> that determines which Profile is used by
      default in the UI and API responses.
    </p>

    <h2 id="attributes">
      Flexible Attributes
    </h2>
    <p>
      Profile data is stored as typed <strong>attributes</strong> rather than fixed columns. Each
      attribute has metadata for merging, deduplication, and progressive enrichment.
    </p>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class ProfileAttribute(
    val id: UUID = UUID.NIL,
    val profile: UUID,                 // owning profile id
    val typeId: String,                // e.g. &quot;bosca.profiles.email&quot;
    val visibility: ProfileVisibility,
    val confidence: Int,               // reconciliation weight
    val priority: Int,                 // ordering when multiple records share a typeId
    val source: String,                // free-text label (e.g. &quot;oauth2&quot;, &quot;signup&quot;)
    val attributes: JsonElement? = null,   // structured value (schema depends on typeId)
    val metadataId: UUID? = null,
    val expires: OffsetDateTime? = null,
)`"
    />

    <h3>Common Attribute Types</h3>
    <table>
      <thead>
        <tr>
          <th>Type ID</th>
          <th>Purpose</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>bosca.profiles.name</code></td>
          <td>Display name</td>
        </tr>
        <tr>
          <td><code>bosca.profiles.email</code></td>
          <td>Email address</td>
        </tr>
        <tr>
          <td><code>bosca.profiles.avatar</code></td>
          <td>Profile picture URL</td>
        </tr>
        <tr>
          <td><code>bosca.profiles.locale</code></td>
          <td>Language/locale preference</td>
        </tr>
        <tr>
          <td><code>bosca.profiles.name.given</code></td>
          <td>First name</td>
        </tr>
        <tr>
          <td><code>bosca.profiles.name.family</code></td>
          <td>Last name</td>
        </tr>
      </tbody>
    </table>
    <Callout type="info">
      The <code>source</code> field tracks where each attribute came from — <code>"oauth2"</code> for
      data pulled from a third-party login, <code>"signup"</code> for registration data,
      <code>"manual"</code> for user edits. This enables progressive enrichment without overwriting
      higher-confidence data.
    </Callout>

    <h2 id="graphql">
      GraphQL API
    </h2>

    <h3>Fetching the Current Profile</h3>
    <CodeBlock
      lang="graphql"
      :code="`query GetCurrentProfile {
  profiles {
    current {
      id
      name
      attributes {
        id
        typeId
        attributes
        confidence
        priority
        source
        visibility
      }
    }
  }
}`"
    />

    <h3>Editing a Profile</h3>
    <CodeBlock
      lang="graphql"
      :code="`mutation EditProfile($id: UUID!, $profile: ProfileInput!) {
  profiles {
    edit(id: $id, profile: $profile) {
      id
      name
    }
  }
}`"
    />

    <h3>Managing Attributes</h3>
    <CodeBlock
      lang="graphql"
      :code="`mutation AddProfileAttributes($id: UUID!, $attributes: [ProfileAttributeInput!]!) {
  profiles {
    addAttributes(id: $id, attributes: $attributes)
  }
}`"
    />

    <h2 id="visibility">
      Visibility
    </h2>
    <p>
      Each Profile and its individual attributes have a <code>ProfileVisibility</code> setting that
      controls who can see the data. This allows users to share their name publicly while keeping
      their email private, for example.
    </p>

    <h2 id="service">
      ProfileService
    </h2>
    <p>The service interface for profile management:</p>
    <CodeBlock
      lang="kotlin"
      :code="`interface ProfileService {
    suspend fun getCurrent(authentication: AuthenticationContext): Profile?
    suspend fun getById(id: UUID): Profile?
    suspend fun getByPrincipal(principalId: UUID): List<Profile>
    suspend fun create(authentication: AuthenticationContext, profile: ProfileInput): Profile
    suspend fun edit(authentication: AuthenticationContext, id: UUID, profile: ProfileInput): Profile
    suspend fun delete(authentication: AuthenticationContext, id: UUID)
    suspend fun getAttributes(profileId: UUID): List<ProfileAttribute>
    suspend fun addAttributes(profileId: UUID, attributes: List<ProfileAttributeInput>)
    suspend fun getAttributeTypes(): List<ProfileAttributeType>
    // ... filtering, bulk loading, relationships
}`"
    />

    <h2 id="principal-relationship">
      Relationship to Principals
    </h2>
    <p>
      A <strong>Principal</strong> is the security identity (credentials, tokens, groups). A
      <strong>Profile</strong> is the social identity (name, avatar, preferences). They are linked
      by <code>Profile.principal</code>, and the Principal's <code>primaryProfileId</code> points back
      to the default Profile.
    </p>
    <p>
      During signup, a Profile is created automatically from the registration data or OAuth2 provider
      response. Additional Profiles can be created later for different contexts (e.g., an organization
      profile vs. a personal profile).
    </p>
  </div>
</template>
