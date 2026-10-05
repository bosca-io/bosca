<script setup lang="ts">
useSeoMeta({ title: 'Caching' })
</script>

<template>
  <div class="doc-content article">
    <h1>Caching</h1>
    <p class="subtitle">
      <code>CacheManager</code>, <code>ServiceCache</code>, <code>RequestCache</code>, two-tier architecture, cache invalidation, and deferred writes.
    </p>

    <h2 id="architecture">
      Two-Tier Architecture
    </h2>
    <p>Bosca uses a two-tier cache with transaction-aware deferred writes:</p>
    <ol>
      <li><strong>Tier 1: RequestCache</strong> — in-memory <code>ConcurrentHashMap</code> per request. Prevents redundant deserialization and remote lookups within a single request.</li>
      <li><strong>Tier 2: CacheManager</strong> — distributed cache shared across all server instances. Backing store is configurable (Redis, NATS KV).</li>
    </ol>
    <Callout type="info">
      Remote cache writes are <strong>deferred until after the database transaction commits</strong>. If the transaction rolls back, cache updates are discarded. See <NuxtLink to="/developers/transactions">Transactions</NuxtLink>.
    </Callout>

    <h2 id="service-cache">
      ServiceCache
    </h2>
    <p>The primary caching abstraction used in services. Each instance represents a named cache slot.</p>

    <h3>Simple cache (single value)</h3>
    <CodeBlock
      lang="kotlin"
      :code="`private val categoryAll = ServiceCache(&quot;categories:all&quot;, UnitKeySerializer) {
    repository.getAll()
}

override suspend fun getAll() = categoryAll.get(Unit) ?: emptyList()`"
    />

    <h3>Keyed cache with batch resolver</h3>
    <CodeBlock
      lang="kotlin"
      :code="`private val categoryIds = ServiceCache(&quot;categories&quot;, UUIDKeySerializer, { keys, batch ->
    val all = repository.getAll(keys).associateBy { it.id }
    keys.forEach { key ->
        all[key]?.let { batch.setData(key, it) }
    }
}) {
    repository.getById(it)
}`"
    />

    <h3>Constructor parameters</h3>
    <table>
      <thead>
        <tr>
          <th>Parameter</th>
          <th>Purpose</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>cacheName</code></td>
          <td>Unique name in <code>CacheManager</code>; used as key prefix in the distributed store</td>
        </tr>
        <tr>
          <td><code>serializer</code></td>
          <td>Key serializer (<code>UUIDKeySerializer</code>, <code>StringKeySerializer</code>, <code>UnitKeySerializer</code>, etc.)</td>
        </tr>
        <tr>
          <td><code>batchResolver</code></td>
          <td>Resolves multiple keys in one query for DataLoader integration</td>
        </tr>
        <tr>
          <td><code>expiration</code></td>
          <td>TTL in the distributed cache (default: 10 minutes)</td>
        </tr>
        <tr>
          <td><code>resolver</code></td>
          <td>Resolves a single key on cache miss</td>
        </tr>
      </tbody>
    </table>

    <h2 id="invalidation">
      Cache Invalidation
    </h2>
    <Callout type="warn">
      Every cache that could contain stale data must be evicted. When you have multiple caches over the same data, you must invalidate <strong>all of them</strong>.
    </Callout>
    <CodeBlock
      lang="kotlin"
      :code="`override suspend fun edit(id: UUID, input: CategoryInput): Category {
    val updated = repository.editCategory(existing.copy(name = input.name))
    categoryAll.clear()       // the &quot;all&quot; list now contains stale data
    categoryIds.remove(id)    // the per-ID entry now contains stale data
    return updated
}

override suspend fun add(input: CategoryInput): Category {
    val added = repository.addCategory(Category(name = input.name))
    categoryAll.clear()       // the &quot;all&quot; list is missing the new entry
    return added              // no need to evict categoryIds — new entries aren't cached yet
}`"
    />
  </div>
</template>
