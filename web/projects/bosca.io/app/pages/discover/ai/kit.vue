<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Kit — The assistant built into Studio',
  description: 'Kit is Bosca\'s built-in AI assistant. It writes content, creates images and scripts, builds pipelines conversationally, translates strings, and turns analytics questions into persistent investigations, charts, queries, visualizations, and dashboards.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'AI', path: '/discover/ai' },
  { name: 'Kit', path: '/discover/ai/kit' }
], '/og-ai.png')

const CAPS = [
  { icon: 'file-text', title: 'Write documents', body: 'Draft and convert documents, and generate descriptions, topics, and reading times.' },
  { icon: 'image', title: 'Make images', body: 'Generate and edit images from a prompt, right in the session.' },
  { icon: 'code', title: 'Run scripts', body: 'Create, edit, validate, enable, and run server-side scripts.' },
  { icon: 'workflow', title: 'Build pipelines', body: 'Describe an automation in conversation, refine the graph with Kit, and open it in the visual editor.' },
  { icon: 'braces', title: 'Query your API', body: 'Search the schema and run GraphQL against your own platform.' },
  { icon: 'database', title: 'Investigate your data', body: 'Turn a question into a multi-step SQL investigation with inline tables, charts, source SQL, and freshness.' },
  { icon: 'languages', title: 'Translate and release', body: 'Generate configured target translations and draft release notes from the work that shipped.' },
  { icon: 'book', title: 'Look up scripture', body: 'Fetch real Bible passages by reference — the exact text.' }
]

const STATUS = [
  { title: 'Draft the 6.1 release notes', state: 'completed', tone: 'ok' },
  { title: 'Generate a hero image', state: 'streaming', tone: 'active' },
  { title: 'Summarize signups', state: 'completed', tone: 'ok' }
]
</script>

<template>
  <DiscoverShell section-id="ai">
    <section class="page-hero">
      <p class="kicker load-1">
        Kit
      </p>
      <h1 class="load-2">
        The assistant <em>built into Studio</em>
      </h1>
      <p class="section-sub load-3">
        Kit is a conversational assistant that lives in Studio and works on your
        platform. Ask it in plain language, and it writes, generates, runs, and
        queries — with your permissions, streaming as it goes.
      </p>
    </section>

    <!-- ── Capabilities ────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          What it can do
        </p>
        <h2>One assistant, <em>many hands</em></h2>
        <p class="section-sub">
          Kit doesn't just answer — it takes real actions on your platform, each
          one carried out by a purpose-built tool.
        </p>
      </div>
      <div class="cap-grid reveal">
        <article
          v-for="c in CAPS"
          :key="c.title"
          class="cap-card"
        >
          <span class="cap-icon"><Icon
            :name="c.icon"
            :size="16"
          /></span>
          <h3>{{ c.title }}</h3>
          <p>{{ c.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── On your data ────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Grounded in your platform
          </p>
          <h2>It works on <em>your own data</em></h2>
          <p class="section-sub">
            Kit isn't a generic chatbot. It runs with your permissions — only
            what you can see and do — and reaches into your own platform: search
            the GraphQL schema, run a query, or read your analytics with SQL to
            answer a real question about your content.
          </p>
          <ul class="point-list">
            <li>Kit acts with your permissions, as you.</li>
            <li>Query your platform's GraphQL API, schema and all.</li>
            <li>Run read-only SQL over analytics with result freshness and source provenance.</li>
            <li>Search the schema first, then run the query that answers you.</li>
          </ul>
        </div>
        <div class="chat-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">kit · session</span>
          </div>
          <div class="chat-body">
            <div class="chat-msg user">
              Which collections have no cover image?
            </div>
            <div class="chat-trace">
              <Icon
                name="braces"
                :size="12"
              />
              ran graphql_query · collections
            </div>
            <div class="chat-msg bot">
              Three: Field Notes, 2019 Archive, and Press Kit.
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Investigations ─────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Analytics investigations
          </p>
          <h2>A question becomes <em>work you can keep</em></h2>
          <p class="section-sub">
            Ask Kit what changed and it builds an investigation over several
            queries, keeping the timeline, result tables, charts, source SQL,
            and data freshness together. When the answer matters beyond the
            conversation, save the useful pieces as ordinary Bosca analytics.
          </p>
          <ul class="point-list">
            <li>Follow-up questions stay attached to the same investigation.</li>
            <li>Tables and charts render inline with the query and its freshness.</li>
            <li>Save a query, visualization, or complete dashboard without rebuilding the analysis.</li>
          </ul>
        </div>
        <div class="investigation-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">investigation · weekly engagement</span>
          </div>
          <div class="investigation-body">
            <div class="investigation-step">
              <span class="step-index">01</span>
              <span class="step-copy">Compare weekly active profiles</span>
              <span class="step-state">complete</span>
            </div>
            <div
              class="mini-chart"
              aria-label="Weekly engagement rose over four periods"
            >
              <span style="height: 38%" />
              <span style="height: 52%" />
              <span style="height: 63%" />
              <span style="height: 82%" />
            </div>
            <div class="investigation-meta">
              <span>cached · refreshed 2m ago</span>
              <span>view SQL</span>
            </div>
            <div class="save-row">
              <span><Icon
                name="database"
                :size="11"
              /> Save query</span>
              <span><Icon
                name="trending-up"
                :size="11"
              /> Save visualization</span>
              <span><Icon
                name="dashboard"
                :size="11"
              /> Add to dashboard</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Sessions ────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Sessions
          </p>
          <h2>Streams, remembers, <em>resumes</em></h2>
          <p class="section-sub">
            Every reply streams in as the model works. Each session is kept with
            a title and its full message history, and shows where its last turn
            landed — streaming, completed, or failed — so you can reopen it and
            carry on, or clear it out when you're done.
          </p>
          <ul class="point-list">
            <li>Replies stream over a live connection as they're written.</li>
            <li>Sessions are titled from your opening line and kept in History.</li>
            <li>Each shows its last state: streaming, completed, or failed.</li>
            <li>Reopen to continue, or delete a session and its messages.</li>
          </ul>
        </div>
        <div class="status-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">kit · history</span>
          </div>
          <div class="status-body">
            <div
              v-for="s in STATUS"
              :key="s.title"
              class="status-row"
            >
              <span class="status-title">{{ s.title }}</span>
              <span
                class="status-state"
                :class="s.tone"
              >{{ s.state }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverAiExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Capabilities grid ───────────────────────── */

.cap-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.cap-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 50%, transparent);
}

.cap-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
  margin-bottom: 14px;
}

.cap-card h3 {
  font-size: 15px;
  font-weight: 650;
  margin: 0 0 8px;
}

.cap-card p {
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Shared windows ──────────────────────────── */

.chat-window,
.investigation-window,
.status-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Chat window ─────────────────────────────── */

.chat-body {
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 11px;
}

.chat-msg {
  font-size: 13px;
  line-height: 1.5;
  padding: 10px 13px;
  border-radius: var(--r-sm);
  max-width: 88%;
}

.chat-msg.user {
  align-self: flex-end;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent) 30%, transparent);
  color: var(--fg-0);
}

.chat-msg.bot {
  align-self: flex-start;
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  border: 1px solid var(--line);
  color: var(--fg-1);
}

.chat-trace {
  align-self: flex-start;
  display: flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
}

/* ── Investigation window ───────────────────── */

.investigation-body {
  padding: 18px;
}

.investigation-step,
.investigation-meta,
.save-row {
  display: flex;
  align-items: center;
}

.investigation-step {
  gap: 10px;
}

.step-index,
.step-state,
.investigation-meta,
.save-row {
  font-family: var(--font-mono);
  font-size: 10.5px;
}

.step-index { color: var(--accent); }
.step-copy { flex: 1; font-size: 13px; color: var(--fg-1); }
.step-state { color: #34d99a; }

.mini-chart {
  height: 118px;
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin: 20px 0 12px;
  padding: 12px 16px 0;
  border-left: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.mini-chart span {
  flex: 1;
  min-height: 8px;
  border-radius: 4px 4px 0 0;
  background: linear-gradient(180deg, var(--accent), color-mix(in srgb, var(--accent) 35%, transparent));
}

.investigation-meta {
  justify-content: space-between;
  color: var(--fg-3);
}

.investigation-meta span:last-child { color: var(--accent); }

.save-row {
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.save-row span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 8px;
  border: 1px solid var(--line-2);
  border-radius: 999px;
  color: var(--fg-2);
}

/* ── Status window ───────────────────────────── */

.status-body {
  padding: 12px 10px;
}

.status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 15px 12px;
}

.status-row + .status-row {
  border-top: 1px solid var(--line);
}

.status-title {
  font-size: 13px;
  color: var(--fg-1);
}

.status-state {
  font-family: var(--font-mono);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  border-radius: 999px;
  padding: 2px 9px;
  border: 1px solid var(--line-2);
  color: var(--fg-3);
}

.status-state.ok {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.status-state.active {
  color: #e0a23a;
  border-color: color-mix(in srgb, #e0a23a 40%, transparent);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 860px) {
  .cap-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 520px) {
  .cap-grid {
    grid-template-columns: 1fr;
  }
}
</style>
