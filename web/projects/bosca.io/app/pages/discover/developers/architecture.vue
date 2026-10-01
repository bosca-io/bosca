<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Architecture — How Bosca fits together',
  description: 'A core-and-implementation split, a platform composed from independently-versioned modules, compile-time code generation with no runtime reflection, and infrastructure reached through interfaces — one set of libraries that composes into every role, from the API server to the background runner.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Developers', path: '/discover/developers' },
  { name: 'Architecture', path: '/discover/developers/architecture' }
], '/og-developers.png')

const INFRA = [
  { name: 'CacheManager', backends: 'Redis · NATS KV' },
  { name: 'PubSubService', backends: 'Redis · NATS' },
  { name: 'JobQueue', backends: 'NATS · Redis' }
]

const ROLES = [
  { name: 'API server', note: 'GraphQL & REST' },
  { name: 'Background runner', note: 'jobs & events' },
  { name: 'Analytics collector', note: 'event ingestion' },
  { name: 'Analytics processor', note: 'event processing' },
  { name: 'Git server', note: 'repositories & CI' },
  { name: 'Artifacts registry', note: 'package distribution' },
  { name: 'Kubernetes controller', note: 'cluster operations' }
]
</script>

<template>
  <DiscoverShell section-id="developers">
    <section class="page-hero">
      <p class="kicker load-1">
        Architecture
      </p>
      <h1 class="load-2">
        How Bosca <em>fits together</em>
      </h1>
      <p class="section-sub load-3">
        Contracts split from implementations, a platform built from
        independently-versioned modules, wiring written at compile time, and
        infrastructure you reach through interfaces — not products. The same
        shape, in every subsystem.
      </p>
    </section>

    <!-- ── Core / impl ─────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Core &amp; implementation
          </p>
          <h2>Contracts in core, <em>code beside them</em></h2>
          <p class="section-sub">
            Every domain is a pair. <code>core-&lt;name&gt;</code> holds the
            interfaces and models — pure contracts, nothing infrastructural.
            <code>&lt;name&gt;</code> holds the repositories, controllers, and
            services that implement them. Anything downstream depends only on the
            core, so subsystems talk to each other through service interfaces,
            never each other's internals.
          </p>
          <ul class="point-list">
            <li>core modules: interfaces and models, no infrastructure.</li>
            <li>impl modules: repositories, services, controllers, migrations.</li>
            <li>Cross-subsystem calls go through a service, never a repository.</li>
          </ul>
        </div>
        <div class="tree-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">content</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-dir">core-content/</span>        <span class="cmt"># contracts</span>
  ContentService.kt   <span class="cmt"># interface</span>
  Content.kt          <span class="cmt"># model</span>
<span class="tok-dir">content/</span>             <span class="cmt"># implementation</span>
  ContentServiceImpl.kt
  ContentRepository.kt
  ContentController.kt</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Modular & versioned ─────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Modular &amp; versioned
          </p>
          <h2>Composed from <em>versioned modules</em></h2>
          <p class="section-sub">
            Bosca is a set of independently-versioned modules, published as
            artifacts. A running service is composed from the ones it needs. Each
            module carries its own repositories, services, and controllers, and
            their wiring is generated at build time — you add your feature as a
            module in the same shape, depending on the contracts you build
            against.
          </p>
          <ul class="point-list">
            <li>Independently-versioned modules, published as artifacts.</li>
            <li>A service is composed from the modules it needs.</li>
            <li>Depend on the core contracts, and add yours the same way.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">build.gradle.kts</span>
          </div>
          <div class="code-body">
            <pre><code>dependencies {
  <span class="tok-fn">implementation</span>(<span class="tok-str">"io.bosca:core-content"</span>)
  <span class="tok-fn">implementation</span>(<span class="tok-str">"io.bosca:core-workops"</span>)
}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── KSP codegen ─────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Code generation
          </p>
          <h2>Wiring written <em>at build time</em></h2>
          <p class="section-sub">
            Annotation processors read your repositories, services, controllers,
            and jobs and emit real Kotlin into the build output — the JDBC calls,
            the dependency graph, the controller dispatch, the route handlers. It's
            generated by the build and never checked in, so what runs is ordinary
            code with no runtime reflection.
          </p>
          <ul class="point-list">
            <li>Generators run at build time, from your annotations.</li>
            <li>The generated code lives in the build output, never committed.</li>
            <li>At run time the wiring is ordinary compiled code, not reflection.</li>
          </ul>
        </div>
        <div class="tree-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">build/generated/ksp/</span>
          </div>
          <div class="code-body">
            <pre><code>TaskRepositoryImpl.kt   <span class="cmt"># JDBC</span>
TaskControllerWiring.kt <span class="cmt"># GraphQL</span>
RouteHandlers.kt        <span class="cmt"># REST</span>
JobExecutors.kt         <span class="cmt"># jobs</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Infrastructure behind interfaces ────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Infrastructure
          </p>
          <h2>Products behind <em>interfaces</em></h2>
          <p class="section-sub">
            Domain code never names a cache, a broker, or a queue product. It uses
            an interface; configuration decides the backend. Operational data
            lives in PostgreSQL, on a bounded pool that runs JDBC on virtual
            threads — and because job enqueueing is tied to the transaction, work
            is only queued once the data that triggered it has committed.
          </p>
          <ul class="point-list">
            <li>Cache, pub/sub, and the job queue each pick a backend by config.</li>
            <li>PostgreSQL holds operational data behind a bounded pool.</li>
            <li>Jobs wait for the transaction to commit before they enqueue.</li>
          </ul>
        </div>
        <div class="infra-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">interfaces · backends</span>
          </div>
          <div class="infra-body">
            <div
              v-for="i in INFRA"
              :key="i.name"
              class="infra-row"
            >
              <span class="infra-name">{{ i.name }}</span>
              <span class="infra-backends">{{ i.backends }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Many roles ──────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          One codebase, many roles
        </p>
        <h2>Composed into <em>the role it plays</em></h2>
        <p class="section-sub">
          The same platform runs as more than one service — a GraphQL and REST
          API, a background runner for jobs and events, the analytics collector
          and processor, the Git server, the artifacts registry, and the
          Kubernetes controller. Each runs only the part of the platform its role
          needs, from the one shared codebase.
        </p>
      </div>
      <div class="roles-window reveal">
        <div
          v-for="role in ROLES"
          :key="role.name"
          class="roles-row"
        >
          <span class="roles-name">{{ role.name }}</span>
          <span class="roles-note">{{ role.note }}</span>
        </div>
      </div>
    </section>

    <DiscoverDevelopersExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Code + tree windows ─────────────────────── */

.code-window,
.tree-window,
.infra-window {
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

.cmt { color: var(--fg-3); }
.tok-dir { color: var(--accent); }
.tok-fn { color: #ffb547; }

/* ── Infra window ────────────────────────────── */

.infra-body {
  padding: 12px 10px;
}

.infra-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 14px;
}

.infra-row + .infra-row {
  border-top: 1px solid var(--line);
}

.infra-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.infra-backends {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--accent);
}

/* ── Many roles ──────────────────────────────── */

.roles-window {
  max-width: 560px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.roles-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
}

.roles-row + .roles-row {
  border-top: 1px solid var(--line);
}

.roles-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-1);
}

.roles-note {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}
</style>
