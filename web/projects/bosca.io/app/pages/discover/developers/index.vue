<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Developers — Build on Bosca',
  description: 'Bosca is a complete content, work, identity, and Git platform — and you extend it with the very same patterns it is built from. Write repositories, services, controllers, routes, and jobs in Kotlin; the build wires them into the running server, where your feature joins the same GraphQL schema as every subsystem and can build on top of them.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Developers', path: '/discover/developers' }
], '/og-developers.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'The toolkit', href: '#features' },
  { label: 'One schema', href: '#schema' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Declare',
    body: 'Define your types in the GraphQL schema, then write the model, repository, service, and controller in Kotlin that back them.'
  },
  {
    step: '02',
    title: 'Wire',
    body: 'At build time, the compiler reads your annotations and connects it all — generating the JDBC from your SQL, and wiring your services, controllers, and routes into the server.'
  },
  {
    step: '03',
    title: 'Serve',
    body: 'Your feature is live in the same server, on the same schema — and can call content, work, Git, and every other subsystem through their services, the way they call each other.'
  }
]

const FEATURES = [
  {
    icon: 'braces',
    title: 'Repositories from SQL',
    body: '@Repository with @Query turns raw SQL and named parameters into a typed, suspending JDBC repository.'
  },
  {
    icon: 'layers',
    title: 'Services & injection',
    body: '@ServiceImplementation registers a service with the container — resolved and constructor-injected at startup.'
  },
  {
    icon: 'share-2',
    title: 'GraphQL controllers',
    body: '@TypeController and @Field bind a controller to your schema types, right beside every other subsystem\'s.'
  },
  {
    icon: 'route',
    title: 'REST routes',
    body: '@RouteController maps a handler to a path and method, with its authentication declared inline.'
  },
  {
    icon: 'workflow',
    title: 'Jobs & events',
    body: '@JobDefinition and @JobEvent generate typed enqueue helpers and a dispatch() that runs work and publishes events.'
  },
  {
    icon: 'zap',
    title: 'No runtime reflection',
    body: 'Every binding is generated at build time. What ships is the code you would have written by hand.'
  },
  {
    icon: 'package',
    title: 'Modular & versioned',
    body: 'Bosca is a set of independently-versioned modules, published as artifacts — depend on the contracts you build against.'
  },
  {
    icon: 'activity',
    title: 'Live subscriptions',
    body: 'The composed schema carries GraphQL subscriptions over a WebSocket, for pushing live updates to clients.'
  }
]

const ANNOTATIONS = [
  { name: '@Repository', gen: 'JDBC from your SQL' },
  { name: '@ServiceImplementation', gen: 'dependency injection' },
  { name: '@TypeController', gen: 'GraphQL controllers' },
  { name: '@RouteController', gen: 'REST endpoints' },
  { name: '@JobDefinition', gen: 'a job executor' },
  { name: '@JobEvent', gen: 'dispatch() + pub/sub' },
  { name: '@Schema', gen: 'schema merged in' }
]

const INFRA = [
  { k: 'cache', v: 'Redis · NATS' },
  { k: 'pub / sub', v: 'Redis · NATS' },
  { k: 'job queue', v: 'NATS · Redis' },
  { k: 'storage', v: 'S3 · GCS' }
]
</script>

<template>
  <DiscoverShell
    section-id="developers"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca for Developers
        </p>
        <h1 class="load-2">
          Build on Bosca,<br>
          <em>the way Bosca is built.</em>
        </h1>
        <p class="hero-sub load-3">
          Bosca is a full platform — content, work, identity, Git, and more — and
          you extend it the same way it's built: repositories, services, GraphQL
          types, REST routes, and jobs, written in Kotlin and wired together at
          compile time.
        </p>
        <div class="hero-ctas load-4">
          <a
            href="#how"
            class="btn btn-primary"
          >
            How it works
          </a>
        </div>
      </div>

      <div
        class="hero-visual load-4"
        aria-hidden="true"
      >
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">TaskRepository.kt</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-ann">@Repository</span>
<span class="tok-kt">class</span> TaskRepository {

  <span class="tok-ann">@Query</span>(<span class="tok-str">"SELECT * FROM tasks WHERE id = :id"</span>)
  <span class="tok-kt">suspend fun</span> <span class="tok-fn">findById</span>(id: UUID): Task?
}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── How it works ────────────────────────── -->
    <section
      id="how"
      class="section"
    >
      <div class="section-head reveal">
        <p class="kicker">
          How it works
        </p>
        <h2>Declare, wire, <em>serve.</em></h2>
      </div>
      <div class="how-grid reveal">
        <article
          v-for="item in HOW_IT_WORKS"
          :key="item.step"
          class="how-card"
        >
          <span class="how-step">{{ item.step }}</span>
          <h3>{{ item.title }}</h3>
          <p>{{ item.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── Feature wall ────────────────────────── -->
    <section
      id="features"
      class="features"
    >
      <div class="features-inner">
        <div class="section-head reveal">
          <p class="kicker">
            The toolkit
          </p>
          <h2>The tools you <em>build with</em></h2>
          <p class="section-sub">
            The building blocks are the ones the platform itself is made of —
            repositories, services, controllers, routes, and jobs, written in
            Kotlin and wired together at compile time.
          </p>
        </div>
        <div class="feature-grid">
          <article
            v-for="feature in FEATURES"
            :key="feature.title"
            class="feature-card reveal"
          >
            <span class="feature-icon">
              <Icon
                :name="feature.icon"
                :size="16"
              />
            </span>
            <h3>{{ feature.title }}</h3>
            <p>{{ feature.body }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- ── The annotation set ──────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Compile-time wiring
          </p>
          <h2>Annotations in, <em>wiring out</em></h2>
          <p class="section-sub">
            You write the intent; the build writes the wiring. Each annotation
            has a generator that emits real Kotlin into your build output — the
            JDBC calls, the controller dispatch, the route handlers — and it's
            never checked in.
          </p>
          <ul class="point-list">
            <li>Generated code lives in the build output, never committed.</li>
            <li>No runtime reflection — the wiring is ordinary code at run time.</li>
            <li>The same annotations power every subsystem in the platform.</li>
          </ul>
        </div>
        <div class="map-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">annotations · generates</span>
          </div>
          <div class="map-body">
            <div
              v-for="a in ANNOTATIONS"
              :key="a.name"
              class="map-row"
            >
              <span class="map-ann">{{ a.name }}</span>
              <Icon
                name="arrow-right"
                :size="12"
                class="map-arrow"
              />
              <span class="map-gen">{{ a.gen }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── One schema ──────────────────────────── -->
    <section
      id="schema"
      class="section"
    >
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            One platform, one schema
          </p>
          <h2>Your types, <em>in the same graph</em></h2>
          <p class="section-sub">
            Every subsystem contributes to a single GraphQL schema, and your
            feature joins it. A query can walk from your type into content, work,
            identity, or commerce in one round trip, and subscriptions push live
            updates over a WebSocket — the same way the rest of the platform does.
          </p>
          <ul class="point-list">
            <li>Your types sit next to every built-in type, in one schema.</li>
            <li>Resolve across subsystems in a single query.</li>
            <li>Stream live changes with GraphQL subscriptions.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">schema.graphql</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-kt">type</span> <span class="tok-fn">Query</span> {
  content(id: ID!): Content
  task(id: ID!): Task
  widget(id: ID!): Widget   <span class="cmt"># yours</span>
}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Yours to run ────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Yours to run
          </p>
          <h2>Self-hosted, <em>on your terms</em></h2>
          <p class="section-sub">
            Run the whole platform yourself with the official Helm charts.
            Caching, messaging, the job queue, and storage all sit behind
            interfaces — pick the backend that fits your stack through
            configuration, and the code never changes.
          </p>
          <ul class="point-list">
            <li>Official Helm charts; the server compiles to a native image.</li>
            <li>PostgreSQL at the core, for every subsystem's operational data.</li>
            <li>Swap the backend behind each interface through configuration.</li>
          </ul>
        </div>
        <div class="infra-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">infrastructure</span>
          </div>
          <div class="infra-body">
            <div
              v-for="i in INFRA"
              :key="i.k"
              class="infra-row"
            >
              <span class="infra-k">{{ i.k }}</span>
              <span class="infra-v">{{ i.v }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverDevelopersExplore title="Go deeper" />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#93b4fb"
        class="closing-mark"
      />
      <h2>The same platform, <em>running your code</em></h2>
      <p class="section-sub">
        Content, work, identity, Git, commerce — everything Bosca already does is
        there for your feature to build on. It runs in the same server, on the
        same graph, and reaches all of it through the same service interfaces, on
        infrastructure you host yourself.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
  align-items: center;
  gap: 48px;
  max-width: 1140px;
  margin: 0 auto;
  padding: 64px 32px 100px;
}

.hero h1 {
  font-size: clamp(40px, 5.4vw, 64px);
  font-weight: 700;
}

.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 999px;
  padding: 6px 14px;
  margin-bottom: 26px;
}

.eyebrow-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--accent);
  box-shadow: 0 0 10px var(--accent);
  animation: discover-pulse 2.4s ease-in-out infinite;
}

.hero-sub {
  font-size: 16.5px;
  line-height: 1.7;
  color: var(--fg-2);
  max-width: 480px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

/* ── Code window ─────────────────────────────── */

.code-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
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
.tok-ann { color: var(--accent); }
.tok-fn { color: #ffb547; }

/* ── How it works ────────────────────────────── */

.how-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.how-card {
  padding: 24px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.how-step {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
}

.how-card h3 {
  font-size: 16.5px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 12px 0 10px;
}

.how-card p {
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--fg-2);
  margin: 0;
}

/* ── Feature wall ────────────────────────────── */

.features {
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--bg-1) 45%, transparent);
}

.features-inner {
  max-width: 1140px;
  margin: 0 auto;
  padding: 80px 32px 90px;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.feature-card {
  padding: 20px 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-0) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.feature-card:hover {
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  transform: translateY(-2px);
}

.feature-icon {
  width: 30px;
  height: 30px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
  margin-bottom: 14px;
}

.feature-card h3 {
  font-size: 14px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.feature-card p {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

/* ── Annotation map window ───────────────────── */

.map-window,
.infra-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.map-body {
  padding: 12px 10px;
}

.map-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.map-row + .map-row {
  border-top: 1px solid var(--line);
}

.map-ann {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--accent);
  width: 210px;
}

.map-arrow {
  color: var(--fg-4);
  flex-shrink: 0;
}

.map-gen {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
}

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

.infra-k {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.infra-v {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--accent);
}

/* ── Closing ─────────────────────────────────── */

.closing {
  max-width: 640px;
  margin: 0 auto;
  padding: 40px 32px 110px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.closing h2 {
  font-size: clamp(30px, 4vw, 44px);
  font-weight: 700;
  margin-top: 18px;
}

.closing .section-sub {
  margin-bottom: 26px;
}

.closing-mark {
  animation: discover-bob 7s ease-in-out infinite;
}

/* ── Motion & responsive ─────────────────────── */

@media (prefers-reduced-motion: reduce) {
  .eyebrow-dot,
  .closing-mark {
    animation: none;
  }
}

@media (max-width: 960px) {
  .hero {
    grid-template-columns: 1fr;
    padding-top: 36px;
    padding-bottom: 64px;
  }

  .how-grid {
    grid-template-columns: 1fr;
  }

  .feature-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .feature-grid {
    grid-template-columns: 1fr;
  }

  .map-ann {
    width: 160px;
  }
}
</style>
