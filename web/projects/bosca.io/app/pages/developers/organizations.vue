<script setup lang="ts">
useSeoMeta({ title: 'Organizations' })
</script>

<template>
  <div class="doc-content article">
    <h1>Organizations</h1>
    <p class="subtitle">
      Multi-tenant workspaces — the <code>Organization</code> model, membership, signup tokens,
      signup emails, domain auto-join, and organization-scoped permissions.
    </p>

    <h2 id="overview">
      Overview
    </h2>
    <p>
      Organizations are the top-level grouping for teams working together in Bosca. Each
      Organization has its own members, permissions, and content. Organizations extend
      <code>PermissibleEntity</code>, so all standard
      <NuxtLink to="/developers/permissions">permission checks</NuxtLink> apply, and they expose
      <code>ContentItem</code> for inclusion in collections.
    </p>

    <h2 id="model">
      Organization Model
    </h2>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class Organization(
    override val id: UUID = UUID.NIL,
    val name: String,
    override val attributes: JsonElement,
    val systemAttributes: JsonElement,
    val visibility: ProfileVisibility,
    val profileId: UUID,           // linked Profile for org identity
    val created: OffsetDateTime,
    val modified: OffsetDateTime,
) : PermissibleEntity<UUID>, ContentItem`"
    />
    <Callout type="info">
      Every Organization has an associated <NuxtLink to="/developers/profile">Profile</NuxtLink>
      (<code>profileId</code>) that serves as its public identity — name, logo, description, and
      other attributes.
    </Callout>

    <h2 id="membership">
      Membership
    </h2>
    <p>
      Members are <NuxtLink to="/developers/security">Principals</NuxtLink> linked to an
      Organization. Membership is stored as a simple join record:
    </p>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class OrganizationMember(
    val organizationId: UUID,
    val principalId: UUID,
)`"
    />
    <p>
      Permissions within an Organization are granted to <strong>groups</strong>, not individual
      members. Members inherit access through their group memberships.
    </p>

    <h2 id="signup-mechanisms">
      Signup Mechanisms
    </h2>
    <p>Bosca supports three ways to add members to an Organization:</p>

    <h3>1. Signup Tokens</h3>
    <p>
      Time-limited invitation strings tied to a specific group. The recipient redeems the token
      to join the Organization and be assigned to the linked group.
    </p>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class OrganizationSignupToken(
    val token: String,
    val organizationId: UUID,
    val groupId: UUID?,
    val created: OffsetDateTime,
    val expires: OffsetDateTime,         // default: 60 days after creation
)`"
    />
    <p>
      The signup token input identifies which built-in group new members should land in via the
      <code>OrganizationSignupGroupType</code> enum (<code>ADMINISTRATORS</code>,
      <code>USERS</code>, <code>UNKNOWN</code>); the server resolves it to a concrete group and
      stores the resulting <code>groupId</code> on the token.
    </p>
    <CodeBlock
      lang="graphql"
      :code="`# Generate a signup token for an organization
mutation AddSignupToken($id: UUID!, $token: OrganizationSignupTokenInput!) {
  organizations {
    addSignupToken(id: $id, token: $token) {
      id
      name
    }
  }
}

# Delete an issued token
mutation DeleteSignupToken($id: UUID!, $token: String!) {
  organizations {
    deleteSignupToken(id: $id, token: $token) {
      id
    }
  }
}`"
    />

    <h3>2. Signup Emails</h3>
    <p>
      Pre-authorize specific email addresses. When a user signs up with a matching email, they
      are added to the Organization and assigned to the group implied by the
      <code>OrganizationSignupGroupType</code>.
    </p>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class OrganizationSignupEmail(
    val email: String,
    val organizationId: UUID,
    val groupId: UUID?,
    val created: OffsetDateTime,
    val expires: OffsetDateTime,
)

@Serializable
data class OrganizationSignupEmailInput(
    val email: String,
    val type: OrganizationSignupGroupType,
)`"
    />
    <CodeBlock
      lang="graphql"
      :code="`mutation AddSignupEmail($id: UUID!, $token: OrganizationSignupEmailInput!) {
  organizations {
    addSignupEmailToken(id: $id, token: $token) {
      id
    }
  }
}`"
    />

    <h3>3. Domain Auto-Join</h3>
    <p>
      Link an email domain to an Organization. When <code>autoJoin</code> is true, any user
      signing up with a matching email domain is automatically added.
    </p>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class OrganizationDomain(
    val organizationId: UUID,
    val domain: String,
    val autoJoin: Boolean,
    val groupId: UUID? = null,
)

@Serializable
data class OrganizationDomainInput(
    val domain: String,
    val autoJoin: Boolean,
    val defaultGroupId: UUID? = null,
    val type: OrganizationSignupGroupType? = null,
)`"
    />
    <CodeBlock
      lang="graphql"
      :code="`mutation AddDomain($id: UUID!, $domain: OrganizationDomainInput!) {
  organizations {
    addOrganizationDomain(id: $id, domain: $domain) {
      id
    }
  }
}

mutation RemoveDomain($id: UUID!, $domain: String!) {
  organizations {
    removeOrganizationDomain(id: $id, domain: $domain) {
      id
    }
  }
}`"
    />
    <Callout type="tip">
      Domain auto-join is useful for enterprise deployments where all employees share a
      corporate email domain. Combined with OAuth2 SSO, it enables zero-friction onboarding.
    </Callout>

    <h2 id="permissions">
      Organization Permissions
    </h2>
    <p>
      Permissions are granted at the Organization level to <strong>groups</strong>. Each
      permission record maps a group to a <code>PermissionAction</code>:
    </p>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class OrganizationPermission(
    val organizationId: UUID,
    override val groupId: UUID,
    override val action: PermissionAction,
) : EntityPermission`"
    />
    <p>
      Since Organization extends <code>PermissibleEntity</code>, the standard
      <NuxtLink to="/developers/permissions">decision chain</NuxtLink> applies — public access
      flags, group checks, and role-based fallbacks all work the same way.
    </p>

    <h2 id="membership-mutations">
      Membership Mutations
    </h2>
    <p>
      Member management requires <code>MANAGE</code> permission on the Organization:
    </p>
    <CodeBlock
      lang="graphql"
      :code="`mutation AddMember($id: UUID!, $principalId: UUID!) {
  organizations {
    addMember(id: $id, principalId: $principalId)
  }
}

mutation RemoveMember($id: UUID!, $principalId: UUID!) {
  organizations {
    removeMember(id: $id, principalId: $principalId)
  }
}`"
    />

    <h2 id="creating-organizations">
      Creating an Organization
    </h2>
    <p>
      <code>organizations.add</code> is intentionally open so that signup flows can create an
      organization and its linked profile in one step:
    </p>
    <CodeBlock
      lang="graphql"
      :code="`mutation CreateOrganization(
  $organization: OrganizationInput!,
  $profile: ProfileInput!
) {
  organizations {
    add(organization: $organization, profile: $profile) {
      id
      name
      profileId
    }
  }
}`"
    />

    <h2 id="relationship">
      How It Fits Together
    </h2>
    <ul>
      <li>
        <strong>Principal</strong> (security identity) → is a <strong>Member</strong> of an
        Organization
      </li>
      <li>
        <strong>Organization</strong> → has a linked <strong>Profile</strong> (public identity)
      </li>
      <li>
        <strong>Groups</strong> → receive permission grants on the Organization; members inherit
        access through them
      </li>
      <li>
        <strong>Signup mechanisms</strong> → control how new Principals are routed into the
        Organization and into a specific group
      </li>
    </ul>
  </div>
</template>
