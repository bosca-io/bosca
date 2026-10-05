<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Scripts — Extend the platform with real Kotlin',
  description: 'Bosca Scripts are real Kotlin programs that run inside the platform, with typed access to your content and services. Write one, validate it, and run it five ways — by hand, on an event, as an HTTP endpoint, as an AI tool, or as a throwaway.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Scripts', path: '/discover/scripts' }
], '/og-scripts.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Write',
    body: 'Author a script in Kotlin with direct, typed access to platform services — content, collections, metadata, search — and a context carrying its input and the caller\'s identity.'
  },
  {
    step: '02',
    title: 'Validate',
    body: 'Before it ever runs, the source is checked for dangerous calls and compiled — errors surfaced up front — then cached, so the next run starts instantly.'
  },
  {
    step: '03',
    title: 'Run it your way',
    body: 'Trigger it on an event, publish it as an HTTP endpoint, register it as a tool your AI agents can call, or run it by hand. One script, many entry points.'
  }
]

const FEATURES = [
  {
    icon: 'code',
    title: 'Real Kotlin',
    body: 'Scripts are Kotlin, compiled to JVM bytecode — a real language, not a limited expression DSL.'
  },
  {
    icon: 'boxes',
    title: 'Typed platform access',
    body: 'Call content, collection, metadata, and search services directly, wired in the same way the platform wires itself.'
  },
  {
    icon: 'shield-check',
    title: 'Validated & guarded',
    body: 'Source is checked for dangerous calls, the classpath is allowlisted, and every run has a hard timeout.'
  },
  {
    icon: 'zap',
    title: 'Run on events',
    body: 'Run a script on any platform event — through a triggered pipeline — asynchronously, with the event\'s payload as its input.'
  },
  {
    icon: 'globe',
    title: 'HTTP endpoints',
    body: 'Publish a script at a URL — GET or POST, JSON in and out, public or permission-gated.'
  },
  {
    icon: 'sparkles',
    title: 'AI agent tools',
    body: 'Register a script as a tool, with a JSON-schema input, that your AI agents can call to act on your behalf.'
  },
  {
    icon: 'git-branch',
    title: 'Sourced from Git',
    body: 'Attach a script to a file in a repo; a push syncs the source, so scripts live in version control.'
  },
  {
    icon: 'layers',
    title: 'Versioned & cached',
    body: 'Each edit bumps the version and recompiles once; compiled scripts are cached in memory and the database.'
  }
]

const RUNS = [
  { icon: 'play', name: 'By hand', body: 'Run it on demand for a one-off task.' },
  { icon: 'zap', name: 'On an event', body: 'Fire it asynchronously when something happens.' },
  { icon: 'globe', name: 'As an endpoint', body: 'Expose it at an HTTP URL for anything to call.' },
  { icon: 'sparkles', name: 'As an AI tool', body: 'Let your agents invoke it with a typed input.' },
  { icon: 'clock', name: 'As a throwaway', body: 'Run an ephemeral script that cleans itself up.' }
]
</script>

<template>
  <DiscoverShell
    section-id="scripts"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Scripts
        </p>
        <h1 class="load-2">
          Extend the platform<br>
          <em>with real Kotlin.</em>
        </h1>
        <p class="hero-sub load-3">
          Scripts are real Kotlin that runs inside Bosca — with typed, direct
          access to your content and services. Write one and run it however you
          need: by hand, on an event, behind an HTTP endpoint, or as a tool your
          AI agents can call.
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
            <span class="code-title">on-publish.bosca.kts</span>
          </div>
          <div class="code-body">
            <pre><code><span class="cmt">// runs when a document is published</span>
<span class="tok-kt">val</span> content = <span class="tok-fn">provide</span><span class="tok-op">&lt;</span>MetadataService<span class="tok-op">&gt;</span>()
<span class="tok-kt">val</span> id = context.input.jsonObject[<span class="tok-str">"id"</span>]
log.<span class="tok-fn">info</span>(<span class="tok-str">"handling publish for $id"</span>)</code></pre>
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
        <h2>Write, validate, <em>run.</em></h2>
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
            What makes it powerful
          </p>
          <h2>A real runtime, <em>not a macro language</em></h2>
          <p class="section-sub">
            Everything you'd want from server-side code — a real language, typed
            service access, and version control — running safely inside the
            platform it extends.
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

    <!-- ── One script, five ways ───────────────── -->
    <section class="section runs-sec">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            One script, five ways
          </p>
          <h2>Write once, <em>run it however</em></h2>
          <p class="section-sub">
            A script's type decides how it's invoked — nothing about the code
            changes. The same logic can be a manual utility today and an HTTP
            endpoint or an AI tool tomorrow, just by how you register it.
          </p>
          <ul class="point-list">
            <li>The script is the unit; its type is just how it's entered.</li>
            <li>Move logic from a hand-run job to an endpoint without rewriting it.</li>
            <li>Every path runs the same validated, compiled, cached code.</li>
          </ul>
        </div>
        <div class="runs-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">entry points</span>
          </div>
          <div class="runs-body">
            <div
              v-for="r in RUNS"
              :key="r.name"
              class="runs-row"
            >
              <span class="runs-icon"><Icon
                :name="r.icon"
                :size="14"
              /></span>
              <span class="runs-name">{{ r.name }}</span>
              <span class="runs-note">{{ r.body }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverScriptsExplore title="Go deeper" />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#5cc0dd"
        class="closing-mark"
      />
      <h2>Your logic, <em>inside the platform</em></h2>
      <p class="section-sub">
        Real Kotlin with typed access to everything Bosca holds — validated,
        compiled, cached, and reachable by hand, by event, by URL, or by an
        agent. Automation that lives where your data does.
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
  max-width: 470px;
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
.tok-op { color: var(--fg-3); }
.tok-fn { color: var(--accent); }

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

/* ── Runs ────────────────────────────────────── */

.runs-sec {
  padding-top: 90px;
}

.runs-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.runs-body {
  padding: 12px 10px;
}

.runs-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 14px;
}

.runs-row + .runs-row {
  border-top: 1px solid var(--line);
}

.runs-icon {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.runs-name {
  width: 116px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg-1);
}

.runs-note {
  font-size: 12.5px;
  color: var(--fg-3);
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

  .runs-name {
    width: auto;
  }

  .runs-note {
    display: none;
  }
}
</style>
