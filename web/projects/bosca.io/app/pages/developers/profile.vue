<script setup lang="ts">
definePageMeta({ layout: 'developers' })
useSeoMeta({ title: 'Profiles', description: 'Keep account identity separate from social data, typed attributes, visibility, and relationships.' })
const current = 'query MyProfiles {\n  profiles {\n    current {\n      id\n      name\n      type\n      visibility\n      searchable\n      isPrimary\n      attributes { id typeId attributes confidence priority source visibility }\n    }\n  }\n}'
const edit = 'mutation EditProfile($id: UUID!, $profile: ProfileInput!) {\n  profiles {\n    edit(id: $id, profile: $profile) { id name visibility searchable }\n  }\n}'
const variables = '{\n  "id": "00000000-0000-0000-0000-000000000000",\n  "profile": {\n    "name": "Alex",\n    "attributes": [],\n    "visibility": "USER",\n    "searchable": false\n  }\n}'
const types = 'query AttributeTypes {\n  profiles {\n    attributeTypes {\n      all { id name description visibility protected formSchemaId }\n    }\n  }\n}'
const attributes = 'mutation AddAttributes($id: UUID!, $attributes: [ProfileAttributeInput!]!) {\n  profiles { addAttributes(id: $id, attributes: $attributes) }\n}'
</script>

<template>
  <div class="doc-content article">
    <h1>Profiles</h1>
    <p class="subtitle">
      Keep account identity separate from social data, typed attributes, visibility, and relationships.
    </p>
    <h2 id="overview">
      What a profile represents
    </h2>
    <p>
      A profile holds a name, typed attributes, and social information such as relationships,
      bookmarks, marks, ratings, and guide progress. It can be linked to a
      <NuxtLink to="/developers/security">Principal</NuxtLink> or exist independently.
      Profiles and principals use separate IDs.
    </p>
    <p>
      <code>Profile.principal</code> is the optional owning principal ID in Kotlin; GraphQL resolves it
      to a principal object. <code>Principal.primaryProfileId</code> identifies the selected default
      profile and can be unset.
    </p>
    <h2 id="types">
      Profile types
    </h2>
    <ul>
      <li><code>GENERIC</code>: an individual or standalone profile.</li>
      <li><code>ORGANIZATION</code>: the profile associated with an organization.</li>
      <li><code>CHILD</code>: a managed child profile.</li>
    </ul>
    <h2 id="graphql">
      Read your profiles
    </h2>
    <CodeBlock
      lang="graphql"
      :code="current"
    />
    <p>
      <code>profiles.current</code> returns a list of profiles associated with the current principal.
      Use <code>isPrimary</code> to identify its default. Unauthenticated callers receive an Anonymous placeholder profile with a nil UUID.
    </p>
    <h2 id="editing">
      Edit a profile
    </h2>
    <CodeBlock
      lang="graphql"
      :code="edit"
    />
    <p>
      Replace the example ID with a profile you manage. <code>ProfileInput</code> requires
      <code>name</code>, <code>attributes</code>, and <code>visibility</code>.
      Omitting <code>searchable</code> while editing preserves its current value.
    </p>
    <CodeBlock
      lang="json"
      title="Variables"
      :code="variables"
    />
    <p>
      Editing submits the provided attribute inputs; it does not replace the entire attribute list. For a targeted attribute addition,
      use <code>addAttributes</code> instead.
    </p>
    <h2 id="attributes">
      Typed attributes
    </h2>
    <p>
      Attributes reference an entry in the attribute-type registry by <code>typeId</code>.
      Their JSON values can carry contact data, preferences, or other structured information.
      Each record also has visibility, confidence, priority, source, and an optional expiration.
      Discover the installed types rather than assuming a fixed list:
    </p>
    <CodeBlock
      lang="graphql"
      :code="types"
    />
    <CodeBlock
      lang="graphql"
      :code="attributes"
    />
    <p>
      <code>ProfileAttributeInput</code> requires <code>typeId</code>, <code>confidence</code>,
      <code>priority</code>, <code>source</code>, and
      <code>visibility</code>. The JSON <code>attributes</code> value itself is optional.
      Use <code>expiration</code> in input; the result field is named <code>expires</code>.
      Protected attribute types require administrator authority to write.
    </p>
    <h2 id="visibility">
      Visibility and search
    </h2>
    <p>
      Profiles and attributes each carry a <code>ProfileVisibility</code>: <code>USER</code>,
      <code>FRIENDS</code>, <code>FRIENDS_OF_FRIENDS</code>, <code>PUBLIC</code>, or <code>SYSTEM</code>.
      Read access also goes through permission evaluation. A <code>PUBLIC</code> profile permits
      public record reads; it does not make every attribute public.
    </p>
    <p>
      <code>searchable</code> controls profile search eligibility. Setting it does not override
      visibility or access rules. Soft-deleted profiles are excluded from search.
    </p>
    <h2 id="relationships">
      Relationships and account administration
    </h2>
    <p>
      Applications can request relationships through <code>profiles.requestRelationship</code> and
      approve, decline, or cancel pending requests. Direct <code>addRelationship</code> is reserved
      for administrators and system services.
    </p>
    <p>
      Administrator operations can link or clear a principal, mark a profile deleted, restore it,
      and permanently delete it. Permanent deletion requires the profile to have been marked deleted first.
    </p>
    <h2 id="source">
      Where to look in the workspace
    </h2>
    <p>
      The base model lives in <code>bosca-core/core/src/main/kotlin/bosca/profile/model/Profile.kt</code>.
      Profile services, controllers, attribute handling, and the <code>profiles.graphqls</code> schema
      belong to the <code>social</code> component.
    </p>
  </div>
</template>
