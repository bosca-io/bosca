<script setup lang="ts">
definePageMeta({ layout: 'developers' })
useSeoMeta({ title: 'Caching', description: 'Use request-local and distributed caches through Bosca\'s service abstractions.' })
const simple = 'private val categoryAll = ServiceCache("categories:all", UnitKeySerializer) {\n    repository.getAll()\n}\n\noverride suspend fun getAll() = categoryAll.get(Unit) ?: emptyList()'
const batch = 'private val categoryIds = ServiceCache("categories", UUIDKeySerializer, { keys, batch ->\n    val categories = repository.getAll(keys).associateBy { it.id }\n    keys.forEach { key ->\n        categories[key]?.let { batch.setData(key, it) }\n    }\n}) {\n    repository.getById(it)\n}\n\noverride suspend fun getAll(ids: List<UUID>) = categoryIds.getAll(ids).filterNotNull()'
const invalidate = 'val updated = repository.editCategory(existing.copy(name = input.name))\ncategoryAll.clear()\ncategoryIds.remove(id)\nreturn updated'
</script>

<template>
  <div class="doc-content article">
    <h1>Caching</h1>
    <p class="subtitle">
      Use request-local and distributed caches through Bosca's service abstractions.
    </p>
    <h2 id="architecture">
      Two cache tiers
    </h2>
    <ol>
      <li><strong>RequestCache:</strong> stores values locally for one request scope, avoiding repeated remote lookups and deserialization.</li>
      <li><strong>CacheManager:</strong> manages named distributed caches shared by application instances. Redis and NATS KV implementations are available.</li>
    </ol>
    <p>
      Services normally use <code>ServiceCache</code>, which accesses both tiers through the current
      request cache. Backend selection belongs in configuration.
    </p>
    <h2 id="service-cache">
      A cache for one value
    </h2>
    <p>
      The following excerpts come from <code>CategoryServiceImpl</code>. The resolver loads the value
      when it is missing. <code>UnitKeySerializer</code> is useful for a single cached list.
    </p>
    <CodeBlock
      lang="kotlin"
      :code="simple"
    />
    <h2 id="batching">
      A cache for IDs and batched reads
    </h2>
    <p>
      Use a batch resolver to load multiple missing keys together. <code>getAll()</code> and GraphQL
      <code>addToBatch()</code> use this resolver. A cache used for batch reads needs a batch resolver;
      the default reports that batch support is unavailable.
    </p>
    <CodeBlock
      lang="kotlin"
      :code="batch"
    />
    <p>
      The <code>ServiceCache</code> factory takes a cache name, key serializer, optional batch resolver,
      expiration, and single-key resolver. Its default expiration is ten minutes. Import key
      serializers from <code>bosca.cache.serializers</code>.
    </p>
    <h2 id="invalidation">
      Invalidate every affected cache
    </h2>
    <p>
      An edit can make both the full list and the ID entry stale. In this excerpt, <code>existing</code>
      is the category already loaded by the service:
    </p>
    <CodeBlock
      lang="kotlin"
      :code="invalidate"
    />
    <Callout type="info">
      Run broad <code>clear()</code> operations before per-key <code>remove()</code> or
      <code>put()</code> operations. Clearing a named cache also clears the request's local state
      and pending cache operations.
    </Callout>
    <h2 id="writes">
      Flush timing
    </h2>
    <p>
      Request-cache puts and removes queue remote operations. They flush through connection callbacks
      on commit or release, or immediately if there is no connection manager. Whole-cache
      <code>clear()</code> reaches the distributed cache immediately.
    </p>
    <p>
      Do not assume a database rollback discards every pending cache operation: the request cache
      also flushes at connection release. See
      <NuxtLink to="/developers/transactions">Transactions &amp; Connections</NuxtLink> for the database lifecycle.
    </p>
    <h2 id="scope">
      Establish a request-cache scope
    </h2>
    <p>
      HTTP handlers and GraphQL execution supply a request cache. Background listeners invoking
      cached services must establish one with <code>withRequestCache { }</code>, together with
      <code>withConnectionManager { }</code> when SQL is involved.
    </p>
  </div>
</template>
