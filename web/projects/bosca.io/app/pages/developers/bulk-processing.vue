<script setup lang="ts">
useSeoMeta({ title: 'Bulk Processing' })
</script>

<template>
  <div class="doc-content article">
    <h1>Bulk Processing</h1>
    <p class="subtitle">
      <code>MAX_BULK_OPERATION_SIZE</code>, batch permission evaluation, transactional vs. error-tolerant mutations, offset-pagination, and chunking.
    </p>

    <h2 id="guard">
      Input Size Guard
    </h2>
    <p>All bulk mutations enforce a maximum input size of <strong>500</strong>:</p>
    <CodeBlock
      lang="kotlin"
      :code="`require(collectionIds.size <= MAX_BULK_OPERATION_SIZE) {
    &quot;Cannot process more than \$MAX_BULK_OPERATION_SIZE items at once&quot;
}`"
    />

    <h2 id="pattern">
      The Bulk Mutation Pattern
    </h2>
    <CodeBlock
      lang="kotlin"
      :code="`@Field
suspend fun deleteAll(authentication: AuthenticationContext, collectionIds: List<UUID>): Int {
    require(collectionIds.size <= MAX_BULK_OPERATION_SIZE) { &quot;...&quot; }
    return transaction {
        val collections = service.getByIds(collectionIds)          // 1 query
        val allowed = permissionEvaluator.filterAllowed(            // 1 query
            authentication, collections, PermissionAction.DELETE
        )
        service.markDeleted(allowed.map { it.id })                 // 1 query
        allowed.size
    }
}`"
    />
    <Callout type="tip">
      No N+1 at any layer — data loading, permission checking, and the write are all batched.
    </Callout>

    <h2 id="bulk-end-to-end">
      Prefer Bulk Operations End-to-End
    </h2>
    <p>Push the list down through service and repository layers rather than looping at the controller level.</p>

    <h3>Repository: bulk SQL</h3>
    <CodeBlock
      lang="kotlin"
      :code="`@Query(&quot;update collections set deleted = true, modified = now() where id = any(:ids)&quot;)
suspend fun markDeleted(ids: List<UUID>)`"
    />

    <h3>Service: bulk SQL + per-item side effects</h3>
    <CodeBlock
      lang="kotlin"
      :code="`override suspend fun markDeleted(ids: List<UUID>) = transaction {
    repository.markDeleted(ids)
    for (id in ids) {
        removeFromCache(id)
        val collection = getById(id) ?: continue
        CollectionDeleted(collection).dispatch()
    }
}`"
    />

    <Callout type="warn">
      <strong>Avoid</strong> looping at the controller with single-item service calls — that produces N SQL statements instead of 1.
    </Callout>

    <h2 id="transactional-vs-tolerant">
      Transactional vs. Error-Tolerant
    </h2>

    <h3>Transactional (all-or-nothing)</h3>
    <p>If any item fails, everything rolls back. Use when partial completion would be inconsistent.</p>
    <p>Examples: <code>deleteAll</code>, <code>setPublicAll</code>, <code>setWorkflowStateAll</code>, <code>setReadyAll</code></p>

    <h3>Error-tolerant (best-effort)</h3>
    <p>Per-item try/catch. Failed items are logged and skipped. Returns success count.</p>
    <CodeBlock
      lang="kotlin"
      :code="`var count = 0
for (item in allowed) {
    try {
        service.setReady(item, principal)
        count++
    } catch (e: Exception) {
        log.error(&quot;Failed to set ready: \${item.id}&quot;, e)
    }
}
return count`"
    />
    <p>Examples: <code>setMetadataReadyAll</code>, <code>processMediaAll</code>, <code>beginTransitions</code></p>

    <h2 id="locked">
      Locked Entity Handling
    </h2>
    <p>Filter locked entities <strong>before</strong> the permission check. SA users skip the filter:</p>
    <CodeBlock
      lang="kotlin"
      :code="`val isSa = groupEvaluator.hasSaGroup(authentication)
val collections = service.getByIds(collectionIds)
val unlocked = if (isSa) collections else collections.filter { !it.locked }
val allowed = permissionEvaluator.filterAllowed(authentication, unlocked, PermissionAction.EDIT)`"
    />

    <h2 id="background">
      Background Job Batch Processing
    </h2>
    <p>Background jobs use <strong>offset-pagination loops</strong>:</p>
    <CodeBlock
      lang="kotlin"
      :code="`val lock = distributedLock.acquire(&quot;collection:index:all&quot;) ?: return
try {
    var offset = 0L
    while (true) {
        val batch = service.getAll(offset, batchSize)
        if (batch.isEmpty()) break
        for (item in batch) { processItem(item) }
        requestCache().clearLocal()   // prevent unbounded memory growth
        offset += batchSize
    }
} finally {
    lock.release()
}`"
    />
  </div>
</template>
