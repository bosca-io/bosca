<script setup lang="ts">
useSeoMeta({ title: 'Transactions & Connections' })
</script>

<template>
  <div class="doc-content article">
    <h1>Transactions &amp; Connections</h1>
    <p class="subtitle">
      Connection lifecycle, <code>ConnectionManager</code>, the <code>transaction {}</code> function, nested savepoints, deferred side effects, and how GraphQL requests manage connections.
    </p>

    <h2 id="connection-manager">
      ConnectionManager
    </h2>
    <p>
      <code>ConnectionManager</code> is the per-request handle to a database connection. It is <strong>lazy</strong> — no physical connection is obtained from the pool until the first SQL operation.
    </p>
    <p>It lives in the coroutine context. Repository methods, <code>transaction {}</code>, and <code>RequestCache</code> all access it automatically.</p>

    <h2 id="transaction">
      The <code>transaction {}</code> Function
    </h2>
    <CodeBlock
      lang="kotlin"
      :code="`suspend fun <T> transaction(block: suspend () -> T): T`"
    />
    <p>Wraps a block in a database transaction. If the block completes normally, it commits. If it throws, it rolls back and the exception propagates.</p>
    <CodeBlock
      lang="kotlin"
      :code="`override suspend fun add(input: CollectionInput): Collection = transaction {
    val newCollection = repository.add(collection)
    CollectionCreated(newCollection).dispatch()
    newCollection
}`"
    />
    <Callout type="info">
      Begin, commit, and rollback run in <code>NonCancellable</code> context — coroutine cancellation cannot leave a transaction half-open.
    </Callout>

    <h2 id="nested">
      Nested Transactions via Savepoints
    </h2>
    <p>Calling <code>transaction {}</code> inside another <code>transaction {}</code> creates a SQL <strong>SAVEPOINT</strong>:</p>
    <ul>
      <li>Inner commit releases the savepoint (does not commit to the database).</li>
      <li>Inner rollback rolls back to the savepoint (outer transaction continues).</li>
      <li>Only the outermost commit actually calls <code>connection.commit()</code>.</li>
    </ul>
    <CodeBlock
      lang="kotlin"
      :code="`// Safe to compose transactional methods:
suspend fun doWork() = transaction {
    repository.insert(...)
    serviceB.doOtherWork()  // also calls transaction {} — creates a savepoint
}`"
    />

    <h2 id="graphql-connections">
      GraphQL Connection Management
    </h2>
    <p>GraphQL requests work differently from HTTP routes:</p>
    <ul>
      <li>Each field resolver gets its <strong>own <code>ConnectionManager</code></strong> — no shared transaction across fields.</li>
      <li>Connections are acquired <strong>lazily</strong> per field — fields that don't touch the database never acquire a connection.</li>
      <li>A <strong><code>ConnectionLimiter</code></strong> semaphore (default limit 5) prevents a single GraphQL request from holding too many concurrent connections.</li>
    </ul>

    <h2 id="deferred">
      Deferred Side Effects
    </h2>
    <p>Several systems register callbacks to defer side effects until after the transaction commits:</p>
    <ol>
      <li><strong>Remote cache writes</strong> — <code>RequestCache</code> flushes pending puts/removes on <code>onCommit()</code>. Rolled-back writes are discarded.</li>
      <li><strong>Job queue enqueues</strong> — <code>dispatch()</code> defers via <code>dbRunAfterCommit</code>. Background jobs don't start before data is visible.</li>
      <li><strong>Pub-sub publishing</strong> — subscribers don't receive notifications about uncommitted data.</li>
    </ol>

    <h2 id="with-connection">
      Background Jobs
    </h2>
    <p>For code outside a request context, use <code>withConnectionManager</code>:</p>
    <CodeBlock
      lang="kotlin"
      :code="`withConnectionManager {
    val items = service.getAll()
    // process items...
}`"
    />
  </div>
</template>
