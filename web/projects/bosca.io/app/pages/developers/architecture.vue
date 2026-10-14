<script setup lang="ts">
definePageMeta({ layout: 'developers' })

useSeoMeta({
  title: 'Architecture',
  description: 'How the workspace organizes domain code, generates wiring, and connects applications to infrastructure.'
})
</script>

<template>
  <div class="doc-content article">
    <h1>Architecture Overview</h1>
    <p class="subtitle">
      How the workspace organizes domain code, generates wiring, and connects applications to infrastructure.
    </p>

    <h2 id="core-impl">
      Core/Impl Split
    </h2>
    <p>
      Backend domains generally separate <strong>contracts</strong> from <strong>implementations</strong>:
    </p>
    <ul>
      <li><strong><code>core-*</code> modules</strong> define contracts: interfaces, models, and annotations. They publish to Maven so any module in any repository can depend on the contract without pulling in the implementation.</li>
      <li><strong>Implementation modules</strong> provide concrete classes: repositories backed by PostgreSQL, services with business logic, and GraphQL controllers.</li>
    </ul>
    <p>
      When extending a domain, depend on another domain's core contracts and call its service interfaces.
      Keep SQL and persistence details in the module that owns the data.
    </p>

    <h2 id="ksp">
      KSP Code Generation
    </h2>
    <p>
      <strong>KSP (Kotlin Symbol Processing)</strong> generates dependency injection, database, GraphQL,
      route, and job wiring at compile time. Follow these patterns when adding code that must also run
      in GraalVM native images.
    </p>

    <table>
      <thead>
        <tr>
          <th>Annotation</th>
          <th>What KSP Generates</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>@Repository</code></td>
          <td>Full JDBC implementation from the interface's <code>@Query</code> SQL</td>
        </tr>
        <tr>
          <td><code>@ServiceImplementation</code></td>
          <td>DI provider registration</td>
        </tr>
        <tr>
          <td><code>@TypeController</code> / <code>@Field</code></td>
          <td>GraphQL resolver wiring</td>
        </tr>
        <tr>
          <td><code>@RouteController</code></td>
          <td>HTTP route handler registration + DI provider</td>
        </tr>
        <tr>
          <td><code>@JobDefinition</code></td>
          <td>Job executor provider and enqueue helpers</td>
        </tr>
        <tr>
          <td><code>@JobEvent</code></td>
          <td><code>dispatch()</code> extension that enqueues jobs and publishes to pub-sub</td>
        </tr>
        <tr>
          <td><code>@Schemas</code> / <code>@Schema</code></td>
          <td>Schema file merging across modules</td>
        </tr>
      </tbody>
    </table>

    <Callout type="info">
      Generated code goes to <code>build/generated/ksp/</code> and must never be committed.
    </Callout>

    <h2 id="abstractions">
      Key Abstractions
    </h2>
    <p>
      Bosca uses abstracted infrastructure services. The code works against interfaces, not specific products.
    </p>

    <h3 id="cache-manager">
      CacheManager
    </h3>
    <p>
      Central registry of named caches providing the distributed (remote) cache tier. The backing store is selected at startup via configuration — implementations exist for Redis and NATS KV. Your code never interacts with the backing store directly; it works through <code>ServiceCache</code> and <code>RequestCache</code>, which delegate to <code>CacheManager</code>.
    </p>

    <h3 id="pubsub">
      PubSubService
    </h3>
    <p>
      The publish-subscribe abstraction. Channels are named strings, messages are serialized to JSON. <code>subscribe()</code> returns a Kotlin <code>Flow</code>. Implementations exist for both NATS and Redis pub-sub.
    </p>

    <CodeBlock
      lang="kotlin"
      :code="`interface PubSubService : Service {
    suspend fun <T> publish(channel: String, serializer: SerializationStrategy<T>, message: T)
    fun <T> subscribe(channel: String, deserializer: DeserializationStrategy<T>): Flow<Message<T>>
}`"
    />

    <h3 id="job-queue">
      JobQueue
    </h3>
    <p>
      Durable job queue abstraction with at-least-once delivery semantics. Implementations exist for NATS JetStream and Redis. Job enqueuing is deferred until after any open database transaction commits.
    </p>

    <h2 id="server">
      Server Architecture
    </h2>
    <p>
      The main backend is assembled into two applications:
    </p>
    <ul>
      <li><strong><code>bosca-server</code></strong> — Serves the GraphQL API over Netty (queries, mutations, subscriptions via WebSocket).</li>
      <li><strong><code>bosca-runner</code></strong> — Processes background jobs from the job queue.</li>
    </ul>
    <p>
      Both load domain libraries through application-specific provider registrars. The server is a
      composition root: business logic belongs in the domain modules. Dedicated applications serve
      Git transport, artifacts, analytics collection, and BML messages; Studio is the administration UI.
    </p>

    <h2 id="workspace">
      Build from the workspace root
    </h2>
    <p>
      <code>settings.gradle.kts</code> maps component directories to Gradle projects. Local
      <code>io.bosca:*</code> dependencies resolve to these projects without publishing to Maven Local.
      JVM modules use Java 25. For example:
    </p>
    <CodeBlock
      lang="bash"
      :code="`./gradlew :content:core-content:test
./gradlew :server:bosca-server:run`"
    />
    <p>
      Start with <NuxtLink to="/developers/infrastructure">Infrastructure</NuxtLink> to run from source,
      or <NuxtLink to="/developers/run-locally">Run Bosca Locally</NuxtLink> to use published images.
    </p>

    <h2 id="async">
      Async-First
    </h2>
    <p>
      All I/O uses Kotlin <code>suspend</code> functions throughout the stack. JDBC blocking calls are dispatched to a virtual-thread-per-task executor (<code>DatabaseDispatcher</code>) so they don't block coroutine thread-pool threads.
    </p>
  </div>
</template>
