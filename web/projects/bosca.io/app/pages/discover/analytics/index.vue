<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Analytics — Query-first event analytics on Bosca',
  description: 'Bosca Analytics is SQL-first: events land in a warehouse, one saved query with typed parameters is the source of truth for every chart, dashboards lay them out on a grid, and error tracking adds AI root-cause hints.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Analytics', path: '/discover/analytics' }
], '/og-analytics.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Events arrive',
    body: 'Your apps send events — sessions, interactions, impressions, completions, installations, errors. They land in the analytics warehouse, and the Raw Events explorer shows them exactly as the warehouse sees them.'
  },
  {
    step: '02',
    title: 'A query shapes them',
    body: 'A saved query is SQL plus a typed parameter list. It reads from your events and returns rows — and that one query is the single source of truth every chart and dashboard draws from.'
  },
  {
    step: '03',
    title: 'Charts and dashboards',
    body: 'Bind a query to a visualization type, then lay visualizations out on a dashboard grid. Dashboard parameters flow into each chart\'s query by name, so one control moves the whole board.'
  }
]

const FEATURES = [
  {
    icon: 'search',
    title: 'Query-first',
    body: 'One saved SQL query is the single source of truth for every chart that uses it. Change the query, and every visualization and dashboard follows.'
  },
  {
    icon: 'sliders',
    title: 'Typed parameters',
    body: 'Queries declare a typed parameter list — string, integer, date, boolean, array, and more — with defaults and required flags, so a chart is a query plus its inputs.'
  },
  {
    icon: 'git-branch',
    title: 'Git-backed queries',
    body: 'A query can bind to a SQL file in a Git repository and sync both ways — a push updates the query, a Studio edit commits back, most recent edit wins.'
  },
  {
    icon: 'scan-line',
    title: 'Live event inspection',
    body: 'The Raw Events explorer is a parameterized window onto ingestion — filter by type, time range, app, session, or user, and open any event in full detail.'
  },
  {
    icon: 'layout-grid',
    title: 'Grid dashboards',
    body: 'Drag, drop, and resize visualizations on a grid, with per-tile title, border, and background overrides — the layout you build is the layout everyone sees.'
  },
  {
    icon: 'pulse',
    title: 'Charts, tables & maps',
    body: 'Bars, lines, scatter, and bubbles; pie and doughnut; numbers, tables, and labels; timelines, graphs, and stacked areas; and region, point, and live-session maps — each bound to a query.'
  },
  {
    icon: 'clock',
    title: 'Cached & refreshed',
    body: 'Opt a query into result caching and each distinct set of parameters is cached and refreshed on an interval by a background job — dashboards load instantly.'
  },
  {
    icon: 'alert',
    title: 'Error tracking',
    body: 'App errors are fingerprinted into groups with counts, stack traces, and status — plus an AI root-cause hypothesis generated from the trace on demand.'
  }
]

// The reach story: analytics events don't stay in the analytics subsystem —
// they feed and connect to the rest of the platform.
const REACH = [
  { icon: 'target', name: 'Segments', body: 'The same events build audience segments — behavior becomes who to reach.' },
  { icon: 'globe', name: 'Live session maps', body: 'Watch who\'s active right now light up a live map of sessions.' },
  { icon: 'wand', name: 'Kit', body: 'Ask Kit, the built-in AI, questions about your analytics and get answers back.' },
  { icon: 'workflow', name: 'Pipelines', body: 'Pipelines observe analytics events and react — automation fires the moment one does.' },
  { icon: 'code', name: 'Scripts', body: 'Manage and act on analytics events from Kotlin scripts.' },
  { icon: 'braces', name: 'Transform API', body: 'Shape events as they arrive with the events pipeline transform API.' }
]
</script>

<template>
  <DiscoverShell
    section-id="analytics"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Analytics
        </p>
        <h1 class="load-2">
          Events in.<br>
          Answers out.<br>
          <em>All in SQL.</em>
        </h1>
        <p class="hero-sub load-3">
          Bosca Analytics is query-first. Events land in a warehouse, one saved
          SQL query with typed parameters becomes the source of truth for every
          chart, and dashboards lay them out on a grid — with error tracking and
          AI root-cause hints alongside.
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
        <div class="flow-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">event → query → chart</span>
          </div>
          <div class="flow-body">
            <div class="flow-sql">
              <pre><code><span class="tok-kt">SELECT</span> day<span class="tok-tag">,</span> <span class="tok-kt">count</span>(*) <span class="tok-kt">AS</span> sessions
<span class="tok-kt">FROM</span> events
<span class="tok-kt">WHERE</span> type <span class="tok-tag">=</span> <span class="tok-str">'session'</span>
  <span class="tok-kt">AND</span> app <span class="tok-tag">=</span> <span class="tok-interp">:appId</span>
<span class="tok-kt">GROUP BY</span> day</code></pre>
            </div>
            <div class="flow-chart">
              <span
                class="bar"
                style="height: 38%;"
              />
              <span
                class="bar"
                style="height: 60%;"
              />
              <span
                class="bar"
                style="height: 48%;"
              />
              <span
                class="bar"
                style="height: 76%;"
              />
              <span
                class="bar"
                style="height: 64%;"
              />
              <span
                class="bar"
                style="height: 92%;"
              />
              <span
                class="bar"
                style="height: 71%;"
              />
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
        <h2>Events to answers, <em>in three moves</em></h2>
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
          <h2>The query is <em>the product</em></h2>
          <p class="section-sub">
            One SQL query drives a chart, a dashboard, an export, and the Raw
            Events explorer alike — so there's one place to get a number right,
            and everything downstream follows.
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

    <!-- ── Parameters ──────────────────────────── -->
    <section class="section params">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Parameters
          </p>
          <h2>One board, <em>one control</em></h2>
          <p class="section-sub">
            A dashboard declares its own typed parameters, and when it runs each
            visualization it passes only the parameters that chart's query asks
            for — matched by name. Drop a date picker on the board and every
            chart that accepts a date range moves together.
          </p>
          <p class="section-sub">
            Because parameters are typed and named, the same query powers a
            focused single-app view and a whole-fleet overview — you just change
            the inputs.
          </p>
        </div>
        <div class="params-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">dashboard parameters</span>
          </div>
          <div class="params-body">
            <div class="param-row">
              <span class="param-name">appId</span>
              <span class="param-type">string</span>
              <span class="param-val">acme-web</span>
            </div>
            <div class="param-row">
              <span class="param-name">range</span>
              <span class="param-type">date · date</span>
              <span class="param-val">last 30 days</span>
            </div>
            <div class="param-row">
              <span class="param-name">segment</span>
              <span class="param-type">array&lt;string&gt;</span>
              <span class="param-val">returning</span>
            </div>
            <div class="param-flow">
              <Icon
                name="arrow-right"
                :size="14"
              />
              matched by name into each chart's query
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Reach ───────────────────────────────── -->
    <section class="section reach">
      <div class="section-head reveal">
        <p class="kicker">
          Beyond dashboards
        </p>
        <h2>The same events, <em>everywhere</em></h2>
        <p class="section-sub">
          Analytics isn't a walled garden. The events your apps already send
          flow into the rest of the platform — and other subsystems can watch,
          shape, and act on them.
        </p>
      </div>
      <div class="reach-grid reveal">
        <article
          v-for="item in REACH"
          :key="item.name"
          class="reach-card"
        >
          <span class="reach-icon">
            <Icon
              :name="item.icon"
              :size="16"
            />
          </span>
          <h3>{{ item.name }}</h3>
          <p>{{ item.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverAnalyticsExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#268e71"
        class="closing-mark"
      />
      <h2>Analytics ships <em>with Bosca</em></h2>
      <p class="section-sub">
        The events your apps already send become queries, charts, and
        dashboards without a separate pipeline — and the same events power
        segments, a live session map, answers from Kit, and automations in
        Pipelines and Scripts. The docs cover ingestion, queries, dashboards,
        and error tracking end to end.
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
  border: 1px solid color-mix(in srgb, var(--accent) 30%, transparent);
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

/* ── Query → chart flow ──────────────────────── */

.flow-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 10%, transparent);
}

.flow-body {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.flow-sql {
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 60%, transparent);
  padding: 14px 16px;
}

.flow-sql pre {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--fg-1);
  overflow-x: auto;
}

.flow-chart {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  height: 120px;
  padding: 12px 14px 4px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 40%, transparent);
}

.flow-chart .bar {
  flex: 1;
  border-radius: 4px 4px 0 0;
  background: linear-gradient(180deg, var(--accent), color-mix(in srgb, var(--accent) 40%, transparent));
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
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  transform: translateY(-2px);
}

.feature-icon {
  width: 30px;
  height: 30px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
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

/* ── Parameters ──────────────────────────────── */

.params {
  padding-top: 90px;
}

.params-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.params-body {
  padding: 10px 8px;
}

.param-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}

.param-row + .param-row {
  border-top: 1px solid var(--line);
}

.param-name {
  flex-shrink: 0;
  width: 78px;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--accent);
}

.param-type {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.param-val {
  font-size: 12.5px;
  color: var(--fg-1);
}

.param-flow {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 14px 10px;
  margin-top: 4px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}

/* ── Reach ───────────────────────────────────── */

.reach {
  padding-top: 30px;
}

.reach-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.reach-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.reach-card:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  transform: translateY(-2px);
}

.reach-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
  margin-bottom: 14px;
}

.reach-card h3 {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.reach-card p {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
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

.btn-cta {
  padding: 13px 34px;
  font-size: 15px;
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
    grid-template-columns: minmax(0, 1fr);
    padding-top: 36px;
    padding-bottom: 64px;
  }

  .how-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .feature-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .feature-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
