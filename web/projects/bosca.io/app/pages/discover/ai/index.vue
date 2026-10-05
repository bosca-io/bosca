<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'AI — An assistant that knows your platform',
  description: 'Bosca AI is Kit: a built-in assistant that writes, generates, translates, builds pipelines conversationally, and turns data questions into persistent investigations and reusable analytics — powered by models, prompts, and tools you control.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'AI', path: '/discover/ai' }
], '/og-ai.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Build your own', href: '#build' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Ask Kit',
    body: 'Open Kit in Studio and ask. It chats in the open, streams as it thinks, and keeps every session so you can pick up where you left off.'
  },
  {
    step: '02',
    title: 'It gets to work',
    body: 'Kit writes documents, generates images, runs scripts, builds pipelines, translates strings, and investigates your data — real work on your platform, not just answers.'
  },
  {
    step: '03',
    title: 'Built from parts',
    body: 'Prompts, tools, agents, and MCP servers are named records you author, version in Git, and reuse across the platform.'
  }
]

const FEATURES = [
  {
    icon: 'file-text',
    title: 'Documents',
    body: 'Kit drafts and converts documents, and writes descriptions, topics, and reading times.'
  },
  {
    icon: 'image',
    title: 'Images',
    body: 'Generate and edit images from a prompt, right inside a session.'
  },
  {
    icon: 'code',
    title: 'Scripts',
    body: 'Kit creates, edits, validates, and runs server-side scripts for you.'
  },
  {
    icon: 'database',
    title: 'Your own data',
    body: 'Ask about your content and analytics — Kit builds persistent investigations with SQL, tables, charts, freshness, and reusable results.'
  },
  {
    icon: 'workflow',
    title: 'Pipelines',
    body: 'Describe an automation in conversation, refine it with Kit, then open the resulting graph in the visual editor.'
  },
  {
    icon: 'book',
    title: 'Scripture',
    body: 'Fetch real Bible passages by reference — the exact text, never generated.'
  },
  {
    icon: 'boxes',
    title: 'Any model',
    body: 'Point AI at OpenAI or Google. The runtime is provider-agnostic.'
  },
  {
    icon: 'quote',
    title: 'Prompts',
    body: 'Name a system and user prompt, add an optional schema, and version it in Git.'
  },
  {
    icon: 'wrench',
    title: 'Tools & MCP',
    body: 'Name a tool that binds to one of your scripts or an MCP server.'
  }
]

const HISTORY = [
  { title: 'Summarize last week\'s signups', time: '2m' },
  { title: 'Draft the 6.1 release notes', time: '1h' },
  { title: 'Make a hero image for the blog', time: '3h' }
]

const COMPOSE = [
  { k: 'model', v: 'gpt-5 · openai' },
  { k: 'prompt', v: 'summarize-doc' },
  { k: 'tools', v: 'execute_query · create_document' },
  { k: 'sub-agent', v: 'topics-agent' }
]
</script>

<template>
  <DiscoverShell
    section-id="ai"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca AI
        </p>
        <h1 class="load-2">
          An assistant that<br>
          <em>knows your platform.</em>
        </h1>
        <p class="hero-sub load-3">
          Kit is Bosca's built-in AI assistant — it writes documents, generates
          images, runs scripts, builds pipelines, translates strings, and turns
          questions about your data into investigations you can keep. Alongside
          it: the models, prompts, and tools that power AI across the platform,
          authored and versioned in Git.
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
        <div class="kit-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">kit · Studio</span>
            <span class="kit-stream">streaming</span>
          </div>
          <div class="kit-body">
            <div class="kit-msg user">
              Summarize last week's signups
            </div>
            <div class="kit-msg bot">
              1,284 new profiles — up 12% from the week before.
            </div>
            <div class="kit-trace">
              <Icon
                name="database"
                :size="12"
              />
              ran execute_query · analytics
            </div>
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
        <h2>Ask, and it <em>acts.</em></h2>
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
            What it can do
          </p>
          <h2>Real work, <em>not just chat</em></h2>
          <p class="section-sub">
            Kit acts on your platform — documents, images, scripts, and your own
            data — with the models, prompts, and tools behind it all yours to
            shape.
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

    <!-- ── Kit works as you ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The assistant
          </p>
          <h2>It works as you, <em>on your content</em></h2>
          <p class="section-sub">
            Kit runs with your permissions — only what you can do yourself. Every
            session streams as the model works, keeps its full history, and picks
            up where it left off. It's an assistant with the run of your platform,
            not a chat box bolted on the side.
          </p>
          <ul class="point-list">
            <li>Kit acts with your permissions — only what you can do.</li>
            <li>Answers stream in as the model works, over a live connection.</li>
            <li>Every session is kept, titled, and resumable from where it stopped.</li>
            <li>Reopen a session to continue, or delete it and its messages.</li>
          </ul>
        </div>
        <div class="hist-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">kit · history</span>
          </div>
          <div class="hist-body">
            <div
              v-for="h in HISTORY"
              :key="h.title"
              class="hist-row"
            >
              <Icon
                name="message-circle"
                :size="13"
              />
              <span class="hist-title">{{ h.title }}</span>
              <span class="hist-time">{{ h.time }}</span>
            </div>
            <div class="hist-foot">
              acting as you · streamed · resumable
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Build your own ──────────────────────── -->
    <section
      id="build"
      class="section"
    >
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Build your own <span class="soon">Harness coming soon</span>
          </p>
          <h2>Compose an agent <em>from the parts</em></h2>
          <p class="section-sub">
            AI in Bosca is assembled from named, reusable parts. Pick a model,
            write a prompt, name the tools an agent can call — from your scripts
            or an MCP server — and compose agents from other agents. Author it all
            in Studio and version it in Git. The harness that runs an agent you
            compose yourself, end to end, is coming soon.
          </p>
          <ul class="point-list">
            <li>An agent is a model, a prompt, and a set of tools.</li>
            <li>Tools bind to one of your scripts or an MCP server.</li>
            <li>Compose agents from sub-agents.</li>
            <li>Author every part in Studio; version it in Git.</li>
          </ul>
        </div>
        <div class="compose-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">agent · content-helper</span>
          </div>
          <div class="compose-body">
            <div
              v-for="row in COMPOSE"
              :key="row.k"
              class="compose-row"
            >
              <span class="compose-k">{{ row.k }}</span>
              <span class="compose-v">{{ row.v }}</span>
            </div>
            <div class="compose-foot">
              <Icon
                name="workflow"
                :size="12"
              />
              harness — coming soon
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverAiExplore title="Go deeper" />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#f9b18f"
        class="closing-mark"
      />
      <h2>AI, made of parts <em>you can see</em></h2>
      <p class="section-sub">
        A live assistant on your content, plus the models, prompts, and tools that
        power AI across the platform — named, authored, and yours to build on.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.85fr);
  align-items: center;
  gap: 48px;
  max-width: 1140px;
  margin: 0 auto;
  padding: 64px 32px 100px;
}

.hero h1 {
  font-size: clamp(40px, 5.4vw, 62px);
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

/* ── Kit window (hero) ───────────────────────── */

.kit-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.kit-stream {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: #34d99a;
  border: 1px solid color-mix(in srgb, #34d99a 40%, transparent);
  border-radius: 999px;
  padding: 1px 8px;
}

.kit-body {
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.kit-msg {
  font-size: 13px;
  line-height: 1.5;
  padding: 10px 13px;
  border-radius: var(--r-sm);
  max-width: 86%;
}

.kit-msg.user {
  align-self: flex-end;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent) 30%, transparent);
  color: var(--fg-0);
}

.kit-msg.bot {
  align-self: flex-start;
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  border: 1px solid var(--line);
  color: var(--fg-1);
}

.kit-trace {
  display: flex;
  align-items: center;
  gap: 7px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}

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

/* ── History window ──────────────────────────── */

.hist-window,
.compose-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.hist-body {
  padding: 10px 8px;
}

.hist-row {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 14px 12px;
  color: var(--fg-3);
}

.hist-row + .hist-row {
  border-top: 1px solid var(--line);
}

.hist-row svg { color: var(--accent); flex-shrink: 0; }

.hist-title {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
}

.hist-time {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.hist-foot {
  margin-top: 4px;
  padding: 13px 12px 8px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}

/* ── Compose window ──────────────────────────── */

.compose-body {
  padding: 12px 10px;
}

.compose-row {
  display: flex;
  align-items: baseline;
  gap: 14px;
  padding: 13px 12px;
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.compose-row + .compose-row {
  border-top: 1px solid var(--line);
}

.compose-k {
  width: 78px;
  flex-shrink: 0;
  color: var(--fg-3);
}

.compose-v { color: var(--fg-1); }

.compose-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  padding: 12px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}

/* ── Coming-soon pill ────────────────────────── */

.soon {
  display: inline-flex;
  align-items: center;
  font-family: var(--font-mono);
  font-size: 9.5px;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 999px;
  padding: 2px 9px;
  margin-left: 10px;
  vertical-align: middle;
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
}
</style>
