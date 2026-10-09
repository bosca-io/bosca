<script setup lang="ts">
definePageMeta({ layout: 'developers' })

useSeoMeta({
  title: 'Services',
  description: 'Business logic, @ServiceImplementation, constructor injection, and the service lifecycle.'
})
</script>

<template>
  <div class="doc-content article">
    <h1>Services</h1>
    <p class="subtitle">
      Business logic, <code>@ServiceImplementation</code>, constructor injection, and the service lifecycle.
    </p>

    <h2 id="defining">
      Defining a Service
    </h2>
    <p>A service has two parts:</p>
    <ol>
      <li><strong>Interface</strong> — defined in the <code>core-*</code> module.</li>
      <li><strong>Implementation</strong> — annotated with <code>@ServiceImplementation</code>. KSP generates the DI wiring.</li>
    </ol>

    <h3 id="interface">
      Interface (in core-* module)
    </h3>
    <CodeBlock
      lang="kotlin"
      :code="`interface CategoryService : Service {
    suspend fun getAll(): List<Category>
    suspend fun getAll(ids: List<UUID>): List<Category>
    suspend fun add(input: CategoryInput): Category
    suspend fun edit(id: UUID, input: CategoryInput): Category
    suspend fun delete(id: UUID)
}`"
    />

    <h3 id="implementation">
      Implementation
    </h3>
    <CodeBlock
      lang="kotlin"
      :code="`@ServiceImplementation
class CategoryServiceImpl(private val repository: CategoryRepository) : CategoryService {

    private val categoryAll = ServiceCache(&quot;categories:all&quot;, UnitKeySerializer) {
        repository.getAll()
    }

    private val categoryIds = ServiceCache(&quot;categories&quot;, UUIDKeySerializer, { keys, batch ->
        val all = repository.getAll(keys).associateBy { it.id }
        keys.forEach { key ->
            all[key]?.let { batch.setData(key, it) }
        }
    }) {
        repository.getById(it)
    }

    override suspend fun getAll() = categoryAll.get(Unit) ?: emptyList()

    override suspend fun getAll(ids: List<UUID>) = categoryIds.getAll(ids).filterNotNull()

    override suspend fun add(input: CategoryInput): Category {
        val added = repository.addCategory(Category(name = input.name))
        categoryAll.clear()
        return added
    }

    override suspend fun edit(id: UUID, input: CategoryInput): Category {
        val existing = repository.getById(id)
            ?: throw NoSuchElementException(&quot;Category not found: \$id&quot;)
        var updated = existing.copy(name = input.name)
        updated = repository.editCategory(updated)
        categoryAll.clear()
        categoryIds.remove(id)
        return updated
    }

    override suspend fun delete(id: UUID) {
        repository.deleteById(id)
        categoryAll.clear()
        categoryIds.remove(id)
    }
}`"
    />

    <h2 id="injection">
      Constructor Injection
    </h2>
    <p>Dependencies are injected via the constructor. KSP reads the constructor parameters and generates the provider wiring. You can inject:</p>
    <ul>
      <li><strong>Repositories</strong> — <code>@Repository</code>-annotated interfaces</li>
      <li><strong>Other services</strong> — via their interface</li>
      <li><strong>Infrastructure</strong> — <code>CacheManager</code>, <code>PubSubService</code>, <code>BoscaApplication</code>, etc.</li>
      <li><strong><code>ObjectProvider&lt;T&gt;</code></strong> — for lazy/deferred resolution</li>
    </ul>

    <h2 id="responsibilities">
      Service Responsibilities
    </h2>
    <p>A service typically handles:</p>
    <ol>
      <li><strong>Cache-through reads</strong> — look up in cache first, fall back to repository on miss.</li>
      <li><strong>Writes with cache invalidation</strong> — perform the repository write, then invalidate all affected caches.</li>
      <li><strong>Event dispatch</strong> — dispatch domain events after writes for background jobs and subscribers.</li>
      <li><strong>Business logic</strong> — validation, transformation, orchestration across repositories.</li>
    </ol>
    <Callout type="info">
      API authorization belongs in controllers through <code>PermissionEvaluator</code> and
      <code>GroupEvaluator</code>. Services enforce domain rules and expose reusable operations.
      Calling a service directly does not perform the controller's access checks.
    </Callout>

    <h2 id="events">
      Event Dispatch
    </h2>
    <p>Services dispatch domain events after writes using KSP-generated <code>dispatch()</code> extensions:</p>
    <CodeBlock
      lang="kotlin"
      title="Inside a transactional collection write (excerpt)"
      :code="`val newCollection = repository.add(collection)
CollectionCreated(newCollection).dispatch()
newCollection`"
    />
    <p>
      Call the generated <code>dispatch()</code> inside the transaction. Job queues defer enqueueing
      until commit; direct pub/sub notifications are sent during dispatch.
      See <NuxtLink to="/developers/messaging">
        Messaging &amp; Events
      </NuxtLink> for full details.
    </p>
  </div>
</template>
