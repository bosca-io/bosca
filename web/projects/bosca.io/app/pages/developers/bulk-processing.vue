<script setup lang="ts">
definePageMeta({ layout: 'developers' })
useSeoMeta({ title: 'Bulk Processing', description: 'Load and authorize groups of records, keep writes consistent, and bound background processing.' })
const guard = 'require(collectionIds.size <= MAX_BULK_OPERATION_SIZE) {\n    "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once"\n}'
const deleteAll = '@Field\nsuspend fun deleteAll(authentication: AuthenticationContext, collectionIds: List<UUID>): Int {\n    require(collectionIds.size <= MAX_BULK_OPERATION_SIZE) {\n        "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once"\n    }\n    return transaction {\n        val collections = service.getByIds(collectionIds)\n        val allowed = permissionEvaluator.filterAllowed(\n            authentication, collections, PermissionAction.DELETE\n        )\n        for (collection in allowed) {\n            service.markDeleted(collection.id)\n        }\n        allowed.size\n    }\n}'
const tolerant = 'var count = 0\nfor (item in items) {\n    try {\n        processItem(item)\n        count++\n    } catch (e: CancellationException) {\n        throw e\n    } catch (e: Exception) {\n        log.error("Failed to process item", e)\n    }\n}\nreturn count'
const locked = 'val isSa = groupEvaluator.hasSaGroup(authentication)\nval collections = service.getByIds(collectionIds)\nval unlocked = if (isSa) collections else collections.filter { !it.locked }\nval allowed = permissionEvaluator.filterAllowed(authentication, unlocked, PermissionAction.EDIT)'
</script>

<template>
  <div class="doc-content article">
    <h1>Bulk Processing</h1>
    <p class="subtitle">
      Load and authorize groups of records, keep writes consistent, and bound background processing.
    </p>
    <h2 id="guard">
      Bound mutation inputs
    </h2>
    <p>
      Content bulk mutations use <code>MAX_BULK_OPERATION_SIZE</code>, currently 500. Import it from
      <code>bosca.content.configuration</code> and reject oversized inputs before processing them:
    </p>
    <CodeBlock
      lang="kotlin"
      :code="guard"
    />
    <h2 id="pattern">
      Load and authorize in batches
    </h2>
    <p>
      This is the collection controller's <code>deleteAll</code> implementation. It loads records and
      evaluates permissions in batches, then calls the service for each allowed record inside one
      transaction. Missing or inaccessible records do not contribute to the returned count.
    </p>
    <CodeBlock
      lang="kotlin"
      :code="deleteAll"
    />
    <p>
      Batch loading does not mean the whole mutation issues one SQL statement. Service calls can
      perform additional writes, history updates, cache invalidation, and event dispatch.
      When adding a bulk service operation, preserve those effects.
    </p>
    <h2 id="transactional-vs-tolerant">
      Choose the failure behavior
    </h2>
    <p>
      Collection <code>deleteAll</code> and <code>setReadyAll</code> wrap allowed records in a transaction.
      A failure during that work rolls back the database writes. Other operations, such as metadata
      media processing, handle failures per item and return a success count.
    </p>
    <p>
      For a new best-effort loop, propagate cancellation before catching ordinary failures.
      This illustrative excerpt assumes <code>items</code> and <code>processItem</code> are supplied by the caller:
    </p>
    <CodeBlock
      lang="kotlin"
      :code="tolerant"
    />
    <h2 id="locked">
      Respect locked records
    </h2>
    <p>
      Collection bulk edits filter locked records before evaluating edit permissions, unless
      <code>GroupEvaluator.hasSaGroup()</code> grants the elevated path:
    </p>
    <CodeBlock
      lang="kotlin"
      :code="locked"
    />
    <h2 id="background">
      Process long-running work in chunks
    </h2>
    <p>
      Background indexing jobs page through records with an offset and limit. Advance the offset by
      the page size and stop when no records remain. Use the job's established distributed-lock
      pattern to avoid overlapping work.
    </p>
    <p>
      Clear local request-cache data between completed pages when needed to bound memory.
      <code>requestCache().clearLocal()</code> also discards pending puts and removes, so use it after
      the page's database and cache work has finished. Follow the owning service's side-effect and
      transaction conventions rather than replacing it with direct SQL.
    </p>
  </div>
</template>
