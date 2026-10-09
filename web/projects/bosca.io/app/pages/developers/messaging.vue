<script setup lang="ts">
definePageMeta({ layout: 'developers' })

useSeoMeta({
  title: 'Messaging & Events',
  description: 'PubSubService, domain events, @JobEvent, event dispatch, deduplication, job queues, and @JobDefinition executors.'
})
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
    <p>KSP generates a <code>dispatch()</code> extension. Job queues defer enqueueing while a transaction is active until commit. Pub/sub publication happens immediately during dispatch.</p>
    <CodeBlock
      lang="kotlin"
      :code="`// Excerpt inside a transactional collection write:
val newCollection = repository.add(collection)
CollectionCreated(newCollection).dispatch()`"
    />

    <p>
      Dispatch directly inside the transaction. Job enqueueing already waits for commit.
      Subscribers should treat pub/sub messages as notifications and apply the normal read
      permissions when fetching data.
    </p>
    <h2 id="deduplication">
      Event Deduplication
    </h2>
    <p>
      Within an event-manager context, <code>deferredEvents {}</code> captures events and flushes them
      on scope exit. Events of the same class and <code>identityKey()</code> collapse to the last occurrence.
      Event types must override the default key to deduplicate separate instances.
      Scope-exit flushing is separate from transaction commit timing:
    </p>
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
    <p>The following excerpt uses the collection indexing executor and its named transformation provider. A job executor extends <code>AbstractJobExecutor&lt;T&gt;</code> and is annotated with <code>@JobDefinition</code>:</p>
    <CodeBlock
      lang="kotlin"
      :code="`@JobDefinition(CollectionIndexJob::class, JobQueueNames.contentJobQueue, &quot;index-collection&quot;)
class CollectionIndexExecutor(
    @ProviderName(TransformProvider)
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

    <p>
      The <code>queue</code> in <code>@JobDefinition</code> is a DI provider name, such as
      <code>JobQueueNames.contentJobQueue</code>. Register the generated executor provider in the
      runner that handles the job. At-least-once delivery means executors must handle redelivery
      without duplicating completed effects.
    </p>
    <h3>Job payload</h3>
    <CodeBlock
      lang="kotlin"
      :code="`@Serializable
data class CollectionIndexJob(
    val id: UUID? = null,
    val storage: IndexStorageSystem? = null,
    val deleteFirst: Boolean = false,
    val deleteOnly: Boolean = false,
    val batchSize: Int? = null,
) : IJobDefinition`"
    />

    <h2 id="flow">
      Full Event Flow
    </h2>
    <p>The complete lifecycle:</p>
    <CodeBlock
      lang="text"
      title="Event flow"
      :code="`Service method (e.g., CollectionServiceImpl.add)
  → CollectionCreated(newCollection).dispatch()
    → PubSubService.publish(COLLECTION_CREATED_CHANNEL) immediately
    → JobQueue.enqueue(CollectionIndexJob) after transaction commit
    → PipelineEventDispatcher dispatches registered event-triggered pipelines`"
    />
  </div>
</template>
