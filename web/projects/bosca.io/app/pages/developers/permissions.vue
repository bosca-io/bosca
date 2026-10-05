<script setup lang="ts">
useSeoMeta({ title: 'Permissions' })
</script>

<template>
  <div class="doc-content article">
    <h1>Permissions</h1>
    <p class="subtitle">
      Entity-scoped, group-based access control — <code>PermissibleEntity</code>, <code>PermissionEvaluator</code>, <code>GroupEvaluator</code>, visibility flags, and the full decision matrix.
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
          <td>Entity appears in list/search results</td>
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
          <td>Trigger workflow transitions</td>
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
      <li><strong>Deleted check</strong> — if <code>entity.isDeleted</code>, only SA gets access.</li>
      <li><strong>Public access</strong> — if <code>public</code> and published/advertised, <code>VIEW</code> is granted. If <code>publicList</code> and published/advertised, <code>LIST</code> is granted.</li>
      <li><strong>Editor group</strong> — editor group members get <code>EDIT</code> access globally.</li>
      <li><strong>Entity-level permissions</strong> — checks user's groups against <code>EntityPermission</code> records. <code>MANAGE</code> also satisfies <code>EDIT</code>.</li>
      <li><strong>Role-based fallback</strong> — SA and admin get all actions. Editors get everything except <code>MANAGE</code>, <code>EXECUTE</code>, <code>IMPERSONATE</code>.</li>
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
      <code>AuthenticationContext?</code> is <strong>nullable</strong> in queries — unauthenticated requests are allowed but get filtered results.
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
      :code="`groupEvaluator.verifyHasSaGroup(authentication)       // super admin
groupEvaluator.verifyHasAdminGroup(authentication)     // administrators
groupEvaluator.verifyHasEditorGroup(authentication)    // editors (or admin/sa/managers)
groupEvaluator.verifyHasManagerGroup(authentication)   // managers (or admin)

val isAdmin = groupEvaluator.hasAdminGroup(authentication)  // check without throwing`"
    />

    <h2 id="locked">
      Locked Entities
    </h2>
    <p>Some entities have a <code>locked</code> flag. Only the SA group can modify locked entities:</p>
    <CodeBlock
      lang="kotlin"
      :code="`if (collection.locked && !groupEvaluator.hasSaGroup(authentication)) {
    throw SecurityException(&quot;locked&quot;)
}
permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)`"
    />
  </div>
</template>
