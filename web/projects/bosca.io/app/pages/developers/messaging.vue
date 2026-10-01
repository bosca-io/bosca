<script setup lang="ts">
useSeoMeta({ title: 'Messaging & Events' })
</script>

<template>
  <div class="doc-content article">
    <h1>Messaging &amp; Events</h1>
    <p class="subtitle">
      <code>PubSubService</code>, domain events, <code>@JobEvent</code>, event dispatch, deduplication, job queues, and <code>@JobDefinition</code> executors.
    </p>

    <h2 id="overview">
      Overview
    </h2>
    <ol>
      <li><strong>Domain Events</strong> — dispatched from services after writes, trigger job enqueuing and pub-sub publishing.</li>
      <li><strong>PubSubService</strong> — real-time publish/subscribe for inter-process notification and GraphQL subscriptions.</li>
      <li><strong>Job Queues</strong> — durable, at-least-once background job processing.</li>
    </ol>

    <h2 id="defining-events">
      Defining Events
    </h2>
    <p>Events are <code>@Serializable</code> classes annotated with <code>@JobEvent</code>:</p>
    <CodeBlock
      lang="kotlin"
      :code="`@JobEvent(
    jobs = [CollectionIndexJob::class, CollectionProcessContentJob::class],
    pubsubChannel = COLLECTION_CREATED_CHANNEL
)
@Serializable
class CollectionCreated(override val id: UUID) : CollectionEvent {
    override val supplementaryId: UUID? = null
    constructor(collection: Collection) : this(id = collection.id)
}`"
    />

    <table>
      <thead>
        <tr>
          <th>@JobEvent Parameter</th>
          <th>Purpose</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>jobs</code></td>
          <td>Job classes to enqueue when dispatched</td>
        </tr>
        <tr>
          <td><code>pubsubChannel</code></td>
          <td>Channel to publish to (empty = no publish)</td>
        </tr>
      </tbody>
    </table>

    <h2 id="dispatching">
      Dispatching Events
    </h2>
    <p>KSP generates a <code>dispatch()</code> extension. Both job enqueuing and pub-sub publishing are <strong>deferred until after the transaction commits</strong>.</p>
    <CodeBlock
      lang="kotlin"
      :code="`override suspend fun add(input: CollectionInput): Collection = transaction {
    val newCollection = repository.add(collection)
    CollectionCreated(newCollection).dispatch()
    newCollection
}`"
    />

    <h2 id="deduplication">
      Event Deduplication
    </h2>
    <p>The <code>deferredEvents {}</code> scope deduplicates events by <code>(eventClass, identityKey())</code>:</p>
    <CodeBlock
      lang="kotlin"
      :code="`deferredEvents {
    service.setPublic(id, true)        // dispatches CollectionUpdated(id)
    service.setSearchable(id, true)    // dispatches CollectionUpdated(id) again
    // Only one CollectionUpdated(id) is actually dispatched
}`"
    />

    <h2 id="job-executors">
      Job Executors
    </h2>
    <p>A job executor extends <code>AbstractJobExecutor&lt;T&gt;</code> and is annotated with <code>@JobDefinition</code>:</p>
    <CodeBlock
      lang="kotlin"
      :code="`@JobDefinition(CollectionIndexJob::class, JobQueueNames.contentJobQueue, &quot;index-collection&quot;)
class CollectionIndexExecutor(
    private val transform: Transformation<IndexStorageSystem, Collection, List<JsonElement>>,
    private val distributedLock: DistributedLockFactory,
    application: BoscaApplication
) : AbstractJobExecutor<CollectionIndexJob>(CollectionIndexJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        // ... process the job
    }
}`"
    />

    <h3>Job payload</h3>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class CollectionIndexJob(
    val id: UUID? = null,
    val storage: IndexStorageSystem? = null,
    val deleteFirst: Boolean = false,
) : IJobDefinition`"
    />

    <h2 id="flow">
      Full Event Flow
    </h2>
    <p>The complete lifecycle:</p>
    <CodeBlock
      lang="bash"
      title="Event flow"
      :code="`Service method (e.g., CollectionServiceImpl.add)
  → CollectionCreated(newCollection).dispatch()
    → [deferred until transaction commits]
      → JobQueue.enqueue(CollectionIndexJob)
      → PubSubService.publish(COLLECTION_CREATED_CHANNEL)
        → SubscriptionController Flow emits to WebSocket clients`"
    />
  </div>
</template>
