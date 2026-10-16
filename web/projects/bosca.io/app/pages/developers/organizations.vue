<script setup lang="ts">
definePageMeta({ layout: 'developers' })
useSeoMeta({ title: 'Organizations', description: 'Group members, maintain an organization profile, and configure signup and access rules.' })
const list = 'query Organizations {\n  organizations {\n    all(offset: 0, limit: 20) {\n      id\n      name\n      visibility\n      profile { id name }\n      memberCount\n    }\n  }\n}'
const create = 'mutation CreateOrganization($organization: OrganizationInput!, $profile: ProfileInput!) {\n  organizations {\n    add(organization: $organization, profile: $profile) {\n      id name profile { id name }\n    }\n  }\n}'
const invite = 'mutation Invite($id: UUID!, $token: OrganizationSignupTokenInput!) {\n  organizations {\n    addSignupToken(id: $id, token: $token) {\n      id\n      signupTokens { token type group { id name } }\n    }\n  }\n}'
const member = 'mutation AddMember($id: UUID!, $principalId: UUID!) {\n  organizations { addMember(id: $id, principalId: $principalId) }\n}\n\nmutation RemoveMember($id: UUID!, $principalId: UUID!) {\n  organizations { removeMember(id: $id, principalId: $principalId) }\n}'
</script>

<template>
  <div class="doc-content article">
    <h1>Organizations</h1>
    <p class="subtitle">
      Group members, maintain an organization profile, and configure signup and access rules.
    </p>
    <h2 id="overview">
      Organizations and access boundaries
    </h2>
    <p>
      An organization groups members and has a linked
      <NuxtLink to="/developers/profile">Profile</NuxtLink> for its identity.
      Its record has a name, JSON attributes, system attributes, visibility, and a profile ID.
    </p>
    <p>
      Bosca is effectively single-tenant. An organization does not create an isolated tenant or
      automatically scope content visibility. Collections, metadata, and other entities keep their
      own access rules. Use <NuxtLink to="/developers/permissions">group-based permissions</NuxtLink>
      to grant access to those entities.
    </p>
    <h2 id="query">
      List organizations
    </h2>
    <CodeBlock
      lang="graphql"
      :code="list"
    />
    <p>
      <code>organizations.all</code> returns records for administrators and an empty list otherwise.
      A single organization lookup checks <code>VIEW</code> permission. Management fields such as signup configuration,
      permissions, and member lists require additional authority.
      GraphQL exposes the associated <code>profile</code> object; <code>profileId</code> is a Kotlin model property.
    </p>
    <h2 id="creating-organizations">
      Create an organization
    </h2>
    <CodeBlock
      lang="graphql"
      :code="create"
    />
    <p>
      <code>OrganizationInput</code> requires <code>name</code>, <code>attributes</code>,
      <code>systemAttributes</code>, <code>visibility</code>, <code>domains</code>,
      <code>signupEmails</code>, and <code>signupTokens</code>. Empty arrays are valid for the signup
      lists. Its optional <code>id</code> identifies an existing organization when editing.
      <code>ProfileInput</code> supplies the associated name, attributes, and visibility.
    </p>
    <p>
      The creation resolver is intentionally available to signup flows without a caller authentication
      parameter. Edits, deletion, and membership management have their own permission checks.
    </p>
    <h2 id="membership">
      Membership and groups
    </h2>
    <p>
      Membership records link an organization ID and a principal ID. The profile API exposes these
      organizations for profiles linked to that principal. Organization creation also establishes
      administrator and user groups for its signup and permission configuration.
    </p>
    <p>
      Membership and group grants are separate records. Adding a member directly does not assign
      a signup group. Removing the membership record does not revoke its existing security-group
      memberships. Manage those memberships explicitly when changing access.
    </p>
    <CodeBlock
      lang="graphql"
      :code="member"
    />
    <p>Both membership mutations require <code>MANAGE</code> permission on the organization.</p>
    <h2 id="signup-mechanisms">
      Signup mechanisms
    </h2>
    <ul>
      <li>
        <strong>Signup tokens:</strong> share a token that selects an organization signup group.
        Creating or deleting a token requires organization <code>MANAGE</code> permission.
      </li>
      <li>
        <strong>Signup emails:</strong> preconfigure an email address and signup group type.
        These mutations require administrator authority.
      </li>
      <li>
        <strong>Domains:</strong> configure a matching email domain, <code>autoJoin</code>, and a
        default group. Adding or removing domains requires administrator authority.
      </li>
    </ul>
    <p>
      Signup group types are <code>ADMINISTRATORS</code>, <code>USERS</code>, and <code>UNKNOWN</code>.
      They select the organization's groups, not the platform-wide administrator role.
    </p>
    <CodeBlock
      lang="graphql"
      :code="invite"
    />
    <p>
      For an ordinary member invitation, supply <code>{ "type": "USERS" }</code> as the
      <code>token</code> variable. Read the generated token from the returned
      <code>signupTokens</code> list.
    </p>
    <h2 id="permissions">
      Organization permissions
    </h2>
    <p>
      <code>OrganizationPermission</code> grants an action to a security group on the organization
      record. It does not grant that action on every item associated with a member or the organization.
      The organization permission evaluator maps API-token access to profile scopes such as <code>profiles:read</code> and <code>profiles:manage</code>.
    </p>
    <h2 id="source">
      Where to look in the workspace
    </h2>
    <p>
      Models live in <code>social/core-profile</code>. Membership, signup, and resolver behavior live in
      <code>social/profile</code>, alongside <code>src/main/resources/graphql/organizations.graphqls</code>.
    </p>
  </div>
</template>
