<script setup lang="ts">
definePageMeta({ layout: 'developers' })
useSeoMeta({ title: 'Transactions & Connections', description: 'Manage database connections and understand when transactions, jobs, and cache operations take effect.' })
const transactionExample = 'suspend fun renameCategory(id: UUID, name: String): Category = transaction {\n    val category = repository.getById(id)\n        ?: error("Category not found: $id")\n    repository.editCategory(category.copy(name = name))\n}'
const contextExample = 'withConnectionManager {\n    withRequestCache {\n        val categories = categoryService.getAll()\n        // Use the categories within this scope.\n    }\n}'
</script>

<template>
  <div class="doc-content article">
    <h1>Transactions &amp; Connections</h1>
    <p class="subtitle">
      Manage database connections and understand when transactions, jobs, and cache operations take effect.
    </p>
    <h2 id="connection-manager">
      Connection scope
    </h2>
    <p>
      <code>ConnectionManager</code> lives in the coroutine context. It borrows a PostgreSQL connection
      lazily when SQL first runs. Outside a transaction, a statement can release the physical connection;
      the manager can borrow another for a later statement.
    </p>
    <p>
      A transaction uses one connection until commit or rollback. Execute SQL sequentially within that
      scope. Do not launch sibling coroutines to run queries on the same manager.
    </p>
    <h2 id="transaction">
      Use a transaction for a related set of writes
    </h2>
    <p>
      <code>bosca.db.transaction { }</code> commits when the block returns normally. If the block throws
      an exception, it rolls back and rethrows. The example below assumes an injected
      <code>CategoryRepository</code> and an existing connection context.
    </p>
    <CodeBlock
      lang="kotlin"
      :code="transactionExample"
    />
    <p>Transaction begin, commit, and rollback run in a <code>NonCancellable</code> context so cleanup can finish during coroutine cancellation.</p>
    <h2 id="nested">
      Nested transactions
    </h2>
    <p>
      A nested <code>transaction { }</code> creates a SQL savepoint. Inner success completes that nested
      scope without committing the outer transaction. Inner failure rolls back to the savepoint.
      If the exception propagates out of the outer block, the outer transaction also rolls back.
    </p>
    <h2 id="graphql-connections">
      GraphQL and HTTP requests
    </h2>
    <p>
      Suspended GraphQL field resolvers and batch loaders establish their own connection-manager
      scopes. A request-level <code>ConnectionLimiter</code> defaults to five concurrent connections.
      A mutation's transaction covers that resolver's work; it does not span the entire GraphQL document.
    </p>
    <p>
      HTTP <code>Route</code>, <code>APIRoute</code>, and <code>SSERoute</code> handlers establish a
      connection manager and request cache around their execution and release the connection afterward.
    </p>
    <h2 id="deferred">
      Side effects and commit timing
    </h2>
    <ul>
      <li>
        <strong>Jobs:</strong> NATS and Redis job queues defer enqueueing during an active transaction
        until commit. Call generated event <code>dispatch()</code> directly inside the transaction.
      </li>
      <li>
        <strong>Pub/sub:</strong> direct <code>PubSubService.publish()</code>, including publication
        from generated event dispatch, sends immediately. A notification can arrive before commit.
      </li>
      <li>
        <strong>Cache operations:</strong> request-cache puts and removes are queued for flushing on
        commit or connection release. A whole-cache <code>clear()</code> executes immediately.
        Cache updates are not a substitute for database transactions.
      </li>
    </ul>
    <p>
      For a separate side effect that specifically must follow a successful commit, use
      <code>bosca.db.afterCommit { }</code>. It runs immediately when no transaction is active.
      It is not a durable delivery mechanism.
    </p>
    <h2 id="with-connection">
      Code outside an HTTP or GraphQL request
    </h2>
    <p>
      Establish both contexts when invoking services that use SQL and <code>ServiceCache</code>.
      Import <code>bosca.db.withConnectionManager</code> and <code>bosca.cache.withRequestCache</code>:
    </p>
    <CodeBlock
      lang="kotlin"
      :code="contextExample"
    />
    <p>
      Apply the same rule to background listeners that call services. See
      <NuxtLink to="/developers/caching">Caching</NuxtLink> and
      <NuxtLink to="/developers/messaging">Messaging &amp; Events</NuxtLink>.
    </p>
  </div>
</template>
