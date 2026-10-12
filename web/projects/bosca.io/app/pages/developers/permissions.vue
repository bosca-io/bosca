<script setup lang="ts">
definePageMeta({ layout: 'developers' })

useSeoMeta({
  title: 'Permissions',
  description: 'Grant actions to groups, filter readable records, and check authority before a write.'
})
</script>

<template>
  <div class="doc-content article">
    <h1>Permissions</h1>
    <p class="subtitle">
      Grant actions to groups, filter readable records, and check authority before a write.
    </p>

    <p>
      Permissions are granted to security groups. A principal receives access through its group
      memberships, including its personal group. Organization membership does not automatically
      grant access to content. Domain-specific evaluators can add rules to the shared behavior below.
    </p>

    <h2 id="permissible-entity">
      PermissibleEntity
    </h2>
    <CodeBlock
      lang="kotlin"
      :code="`interface PermissibleEntity<ID> {
    val id: ID
    val public: Boolean              // entity record visible to unauthenticated users
    val publicContent: Boolean       // binary/file content visible unauthenticated
    val publicList: Boolean          // typically for collections, allowing someone to list items in the collection
    val publicSupplementary: Boolean // supplementary attachments visible unauthenticated
    val isPublished: Boolean         // in \&quot;published\&quot; workflow state
    val isAdvertised: Boolean        // in \&quot;advertised\&quot; workflow state
    val isDeleted: Boolean           // soft-deleted
}`"
    />

    <h2 id="actions">
      PermissionAction
    </h2>
    <table>
      <thead>
        <tr>
          <th>Action</th>
          <th>Purpose</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>VIEW</code></td>
          <td>See the entity exists and read its fields</td>
        </tr>
        <tr>
          <td><code>LIST</code></td>
          <td>List an entity or its items, as defined by the owning API</td>
        </tr>
        <tr>
          <td><code>EDIT</code></td>
          <td>Modify the entity</td>
        </tr>
        <tr>
          <td><code>MANAGE</code></td>
          <td>Grant/revoke permissions (implies EDIT)</td>
        </tr>
        <tr>
          <td><code>DELETE</code></td>
          <td>Soft-delete the entity</td>
        </tr>
        <tr>
          <td><code>EXECUTE</code></td>
          <td>Execute an operation such as a script, job, or workflow transition</td>
        </tr>
        <tr>
          <td><code>IMPERSONATE</code></td>
          <td>Act as another principal in this context</td>
        </tr>
      </tbody>
    </table>

    <h2 id="decision-chain">
      The Decision Chain
    </h2>
    <p>When <code>isAllowed(authentication, entity, action)</code> is called:</p>
    <ol>
      <li><strong>Deleted check</strong> — access to deleted records requires <code>hasSaGroup()</code>, which accepts SA or administrators with the required token scope.</li>
      <li><strong>Public access</strong> — if <code>public</code> and published/advertised, <code>VIEW</code> is granted. If <code>publicList</code> and published/advertised, <code>LIST</code> is granted.</li>
      <li><strong>Token scopes</strong> — private access and writes through scoped API tokens must satisfy the evaluator's domain scope rules. Public reads remain independently accessible.</li>
      <li><strong>Editor group</strong> — the shared evaluator grants <code>EDIT</code> through <code>hasEditorGroup()</code>; domain evaluators can impose additional rules.</li>
      <li><strong>Entity-level permissions</strong> — checks user's groups against <code>EntityPermission</code> records. <code>MANAGE</code> also satisfies <code>EDIT</code>.</li>
      <li><strong>Role-based fallback</strong> — the shared evaluator accepts SA and administrators for all actions. Editors and managers satisfy actions other than <code>MANAGE</code>, <code>EXECUTE</code>, and <code>IMPERSONATE</code>, subject to scope checks.</li>
      <li><strong>Parent access</strong> — if no earlier rule allows access, the owning permission service evaluates inherited access through <code>isParentAllowed()</code>.</li>
    </ol>

    <h2 id="published-vs-advertised">
      Published vs. Advertised
    </h2>
    <p><strong>Advertised</strong> is a "soft-public" state — the entity can be found and viewed, but its binary content and supplementary attachments remain restricted.</p>
    <table>
      <thead>
        <tr>
          <th>What</th>
          <th>Required Flag</th>
          <th>Required State</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>Entity record (title, description)</td>
          <td><code>public = true</code></td>
          <td>published <strong>or</strong> advertised</td>
        </tr>
        <tr>
          <td>Entity in list/search results</td>
          <td><code>publicList = true</code></td>
          <td>published <strong>or</strong> advertised</td>
        </tr>
        <tr>
          <td>Binary content (file download)</td>
          <td><code>publicContent = true</code></td>
          <td>published <strong>only</strong></td>
        </tr>
        <tr>
          <td>Supplementary attachments</td>
          <td><code>publicSupplementary = true</code></td>
          <td>published <strong>only</strong></td>
        </tr>
      </tbody>
    </table>

    <h2 id="queries">
      In Queries: Filter, Don't Throw
    </h2>
    <p>Query fields silently filter unauthorized data to avoid leaking information:</p>
    <CodeBlock
      lang="kotlin"
      :code="`@Field
suspend fun permissions(authentication: AuthenticationContext?, collection: Collection): List<Permission> {
    if (!collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.MANAGE)) {
        return emptyList()
    }
    return collectionService.getPermissions(collection).map { Permission(it.groupId, it.action) }
}`"
    />
    <Callout type="info">
      Public query resolvers can accept an unauthenticated context and return only accessible data.
      Administrative queries require explicit group or permission checks. Parameter nullability alone
      does not authorize a request.
    </Callout>

    <h2 id="mutations">
      In Mutations: Verify and Throw
    </h2>
    <CodeBlock
      lang="kotlin"
      :code="`@Field
suspend fun edit(authentication: AuthenticationContext, id: UUID, collection: CollectionInput): Collection {
    val current = service.getById(id) ?: throw NoSuchElementException(&quot;Collection not found: \$id&quot;)
    permissionEvaluator.verifyAllowed(authentication, current, PermissionAction.EDIT)
    return service.edit(id, collection)
}`"
    />

    <h2 id="creating">
      Creating New Entities
    </h2>
    <p>When creating, check permissions on the <strong>parent</strong> or require a group:</p>
    <CodeBlock
      lang="kotlin"
      :code="`@Field
suspend fun add(authentication: AuthenticationContext, collection: CollectionInput): Collection {
    val parent = collection.parentCollectionId?.let { service.getById(it) }
    if (parent != null) {
        permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
    } else {
        groupEvaluator.verifyHasEditorGroup(authentication)
    }
    return service.add(collection, parent)
}`"
    />

    <h2 id="group-evaluator">
      GroupEvaluator
    </h2>
    <p>For operations that don't target a specific entity:</p>
    <CodeBlock
      lang="kotlin"
      :code="`groupEvaluator.verifyHasSaGroup(authentication)       // sa or administrators
groupEvaluator.verifyHasAdminGroup(authentication)     // administrators
groupEvaluator.verifyHasEditorGroup(authentication)    // editors (or admin/sa/managers)
groupEvaluator.verifyHasManagerGroup(authentication)   // managers (or admin)

val isAdmin = groupEvaluator.hasAdminGroup(authentication)  // check without throwing`"
    />

    <h2 id="locked">
      Locked Entities
    </h2>
    <p>
      Collection controllers restrict edits to locked records using <code>hasSaGroup()</code>,
      which accepts SA or administrators with the required scope:
    </p>
    <CodeBlock
      lang="kotlin"
      :code="`if (collection.locked && !groupEvaluator.hasSaGroup(authentication)) {
    throw SecurityException(&quot;locked&quot;)
}
permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)`"
    />
  </div>
</template>
