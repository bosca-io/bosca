<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Building a feature — From a model to a live API',
  description: 'Add a feature the way Bosca does: a model, a repository from raw SQL, a service with its dependencies injected, and a controller bound to the schema — or a REST route. A few annotated lines of Kotlin, wired into the running server.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Developers', path: '/discover/developers' },
  { name: 'Building a feature', path: '/discover/developers/build' }
], '/og-developers.png')
</script>

<template>
  <DiscoverShell section-id="developers">
    <section class="page-hero">
      <p class="kicker load-1">
        Building a feature
      </p>
      <h1 class="load-2">
        From a model, <em>to a live API</em>
      </h1>
      <p class="section-sub load-3">
        Add a feature the way the platform does. A model, a repository, a
        service, and a controller — each a few annotated lines, each wired into
        the running server. Here is the whole slice.
      </p>
    </section>

    <!-- ── Model ───────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            1 · Model
          </p>
          <h2>Start with <em>a model</em></h2>
          <p class="section-sub">
            A plain Kotlin data class in the core module — the one shape your
            feature is about. It's just the data; the repository, service, and API
            you build next give it behavior.
          </p>
          <ul class="point-list">
            <li>A plain data class in the core module.</li>
            <li>Shared by the repository, service, and API you build next.</li>
            <li>Just the data — behavior comes from the layers around it.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">Task.kt</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-kt">data class</span> Task(
  <span class="tok-kt">val</span> id: UUID,
  <span class="tok-kt">val</span> title: String,
  <span class="tok-kt">val</span> status: TaskStatus,
)</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Repository ──────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            2 · Repository
          </p>
          <h2>Query with <em>@Repository</em></h2>
          <p class="section-sub">
            Annotate a class <code>@Repository</code> and write your queries as
            raw SQL with named parameters. The processor generates the JDBC — the
            statement, the parameter binding, the row mapping to your model — as a
            suspending function. You write the SQL; the rest is generated.
          </p>
          <ul class="point-list">
            <li>Raw SQL with <code>:named</code> parameters, mapped to your model.</li>
            <li>Every call is a suspending function.</li>
            <li>Column names bind explicitly — no hidden conventions.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">TaskRepository.kt</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-ann">@Repository</span>
<span class="tok-kt">class</span> TaskRepository {

  <span class="tok-ann">@Query</span>(<span class="tok-str">"SELECT * FROM tasks WHERE status = :status"</span>)
  <span class="tok-kt">suspend fun</span> <span class="tok-fn">byStatus</span>(status: TaskStatus): List&lt;Task&gt;
}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Service ─────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            3 · Service
          </p>
          <h2>Logic in <em>a service</em></h2>
          <p class="section-sub">
            <code>@ServiceImplementation</code> registers your service with the
            container, its dependencies injected through the constructor. The
            service is the boundary every caller goes through — controllers and
            other subsystems talk to it, never to the repository directly.
          </p>
          <ul class="point-list">
            <li>Constructor injection, resolved at startup.</li>
            <li>The one place your feature's rules live.</li>
            <li>Repositories stay behind the service, always.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">TaskServiceImpl.kt</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-ann">@ServiceImplementation</span>
<span class="tok-kt">class</span> TaskServiceImpl(
  <span class="tok-kt">private val</span> tasks: TaskRepository,
) : TaskService {

  <span class="tok-kt">override suspend fun</span> <span class="tok-fn">open</span>() =
    tasks.<span class="tok-fn">byStatus</span>(TaskStatus.OPEN)
}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── API ─────────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            4 · API
          </p>
          <h2>Expose it — <em>GraphQL or REST</em></h2>
          <p class="section-sub">
            Declare the type in the schema, then bind a controller with
            <code>@TypeController</code> and <code>@Field</code> — your fields join
            the one schema every subsystem shares. Prefer REST? Annotate a handler
            <code>@RouteController</code> with its path, method, and
            authentication. Either way, the endpoint is live in the same server.
          </p>
          <ul class="point-list">
            <li>Schema-first GraphQL: your type sits in the shared graph.</li>
            <li>Or a REST route, with auth declared right on the handler.</li>
            <li>Push live updates to clients with a subscription.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">TaskController.kt</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-ann">@TypeController</span>(<span class="tok-str">"Query"</span>)
<span class="tok-kt">class</span> TaskController(
  <span class="tok-kt">private val</span> tasks: TaskService,
) {

  <span class="tok-ann">@Field</span>
  <span class="tok-kt">suspend fun</span> <span class="tok-fn">openTasks</span>() = tasks.<span class="tok-fn">open</span>()
}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <DiscoverDevelopersExplore />
  </DiscoverShell>
</template>

<style scoped>
.code-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.code-body {
  padding: 18px 20px;
  overflow-x: auto;
}

.code-body pre {
  margin: 0;
}

.code-body code {
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.8;
  color: var(--fg-1);
  white-space: pre;
}

.tok-ann { color: var(--accent); }
.tok-fn { color: #ffb547; }
</style>
