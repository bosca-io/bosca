<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Pipelines — Visual event-driven automation on Bosca',
  description: 'Bosca Pipelines are stored, reusable automation graphs you build visually or with Kit. Start from events, schedules, APIs, or Studio; transform, route, and act with durable runs and segment-wide fan-out.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Pipelines', path: '/discover/pipelines' }
], '/og-pipelines.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Dry runs', href: '#trace' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Choose a starting point',
    body: 'Start from a catalogued platform event, a cron schedule, an API call, Studio, or another pipeline. The input arrives as a typed value that seeds the graph.'
  },
  {
    step: '02',
    title: 'Wire the graph',
    body: 'Drag nodes from the palette onto the canvas and connect them: transforms reshape the data, routes branch on conditions, actions do something with the result. The inspector configures whatever is selected.'
  },
  {
    step: '03',
    title: 'Activate',
    body: 'Flip the Active switch and the pipeline runs on the platform\'s background job infrastructure every time its event fires. Every run is logged with its outcome, timing, and any error.'
  }
]

const FEATURES = [
  {
    icon: 'workflow',
    title: 'A graph, not glue code',
    body: 'Transforms, conditions, and actions wired on a canvas — a diagram anyone on the team can read and edit.'
  },
  {
    icon: 'zap',
    title: 'Events and schedules',
    body: 'Run on matching platform events or a standard cron schedule, with the same durable history and limits either way.'
  },
  {
    icon: 'shield',
    title: 'Typed edges',
    body: 'Values keep their types as they flow. Input slots declare what they accept, and the editor refuses connections that don\'t fit.'
  },
  {
    icon: 'flask',
    title: 'Dry runs',
    body: 'Trace a sample event through the saved graph. Action nodes record what they would do — and do nothing.'
  },
  {
    icon: 'hourglass',
    title: 'Durable runs',
    body: 'Delay, Wait Until, and job waits park a run durably and resume it later. A restart doesn\'t lose it.'
  },
  {
    icon: 'boxes',
    title: 'Composable',
    body: 'Run Pipeline, For Each, segment-wide fan-out, and Dispatch Event build large automations out of small, reusable graphs.'
  },
  {
    icon: 'sparkles',
    title: 'Build with Kit',
    body: 'Describe the automation in conversation, refine the result with Kit, and continue visually in the graph editor.'
  },
  {
    icon: 'key',
    title: 'Secrets stay sealed',
    body: 'Webhook URLs and signing keys are encrypted secrets, referenced by name and resolved only at execution time.'
  },
  {
    icon: 'pulse',
    title: 'Every run logged',
    body: 'Outcome, timing, and errors for every run — plus a live view streaming node-by-node progress as it executes.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="pipelines"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Pipelines
        </p>
        <h1 class="load-2">
          Take an event.<br>
          Shape it. Decide.<br>
          <em>Act.</em>
        </h1>
        <p class="hero-sub load-3">
          Pipelines are Bosca's visual automation: stored transform graphs
          that accept a platform event, reshape it, branch on conditions,
          and act — enqueue a job, send an email, post to Slack, call a
          webhook — without writing or deploying code.
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
        <div class="code-window hero-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">profile.created → crm sync</span>
          </div>
          <svg
            class="graph"
            viewBox="0 0 620 320"
            role="presentation"
          >
            <defs>
              <pattern
                id="graph-dots"
                width="24"
                height="24"
                patternUnits="userSpaceOnUse"
              >
                <circle
                  cx="1.5"
                  cy="1.5"
                  r="1"
                  class="dot-grid"
                />
              </pattern>
            </defs>
            <rect
              width="620"
              height="320"
              fill="url(#graph-dots)"
            />

            <!-- edges -->
            <path
              class="edge"
              d="M184 89 H240"
            />
            <path
              class="edge"
              d="M410 89 H466"
            />
            <path
              class="edge edge-true"
              d="M496 118 C496 170 475 185 475 225"
            />
            <path
              class="edge edge-false"
              d="M576 118 C576 140 576 150 576 166"
            />
            <text
              x="506"
              y="150"
              class="edge-label label-true"
            >true</text>
            <text
              x="586"
              y="146"
              class="edge-label label-false"
            >false</text>

            <!-- Input node -->
            <g class="node">
              <rect
                x="14"
                y="60"
                width="170"
                height="58"
                rx="9"
              />
              <text
                x="30"
                y="85"
                class="node-title"
              >profile.created</text>
              <text
                x="30"
                y="103"
                class="node-sub"
              >input · platform event</text>
              <circle
                cx="184"
                cy="89"
                r="4"
                class="port"
              />
            </g>

            <!-- Fetch node -->
            <g class="node">
              <rect
                x="240"
                y="60"
                width="170"
                height="58"
                rx="9"
              />
              <text
                x="256"
                y="85"
                class="node-title"
              >Event → Profile</text>
              <text
                x="256"
                y="103"
                class="node-sub"
              >fetch · resolve entity</text>
              <circle
                cx="240"
                cy="89"
                r="4"
                class="port"
              />
              <circle
                cx="410"
                cy="89"
                r="4"
                class="port"
              />
            </g>

            <!-- Condition node -->
            <g class="node">
              <rect
                x="466"
                y="60"
                width="140"
                height="58"
                rx="9"
              />
              <text
                x="482"
                y="85"
                class="node-title"
              >opt-in?</text>
              <text
                x="482"
                y="103"
                class="node-sub"
              >route · condition</text>
              <circle
                cx="466"
                cy="89"
                r="4"
                class="port"
              />
              <circle
                cx="496"
                cy="118"
                r="4"
                class="port port-true"
              />
              <circle
                cx="576"
                cy="118"
                r="4"
                class="port port-false"
              />
            </g>

            <!-- Webhook action node -->
            <g class="node">
              <rect
                x="380"
                y="225"
                width="190"
                height="58"
                rx="9"
              />
              <text
                x="396"
                y="250"
                class="node-title"
              >Send Webhook</text>
              <text
                x="396"
                y="268"
                class="node-sub"
              >action · secret: crm-url</text>
              <circle
                cx="475"
                cy="225"
                r="4"
                class="port"
              />
            </g>

            <!-- false branch terminus -->
            <g class="node node-ghost">
              <rect
                x="536"
                y="166"
                width="80"
                height="26"
                rx="13"
              />
              <text
                x="576"
                y="183"
                class="node-sub ghost-label"
              >run ends</text>
            </g>
          </svg>
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
        <h2>Event in. <em>Actions out.</em></h2>
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
    <section class="features">
      <div class="features-inner">
        <div class="section-head reveal">
          <p class="kicker">
            What makes it powerful
          </p>
          <h2>Automation you can <em>read</em></h2>
          <p class="section-sub">
            When the logic is "take this event, shape it, decide, act", a
            pipeline expresses it as a diagram — with the execution engine,
            durability, and audit trail handled by the platform.
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

    <!-- ── Shaping data ────────────────────────── -->
    <section class="section shaping">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Shaping data
          </p>
          <h2>Reshape with <em>an expression</em></h2>
          <p class="section-sub">
            Events are intentionally lean — an id, a name, a timestamp.
            Fetch nodes resolve the full entity, and a JSONata node reshapes
            it into exactly what the receiving end expects. The inspector's
            workbench previews the expression against a sample input without
            leaving the editor.
          </p>
          <ul class="point-list">
            <li>Values flow through the graph with their types intact; bridge nodes convert to and from JSON where a node works structurally.</li>
            <li><code>$eventCreated</code> is bound to the run's occurrence timestamp in every expression.</li>
            <li>Declare the expression's output type and downstream typed slots accept the result.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">jsonata — shape a webhook payload</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">{</span>
  <span class="tok-str">"profileId"</span><span class="tok-tag">:</span> <span class="tok-interp">id</span><span class="tok-tag">,</span>
  <span class="tok-str">"displayName"</span><span class="tok-tag">:</span> <span class="tok-interp">name</span><span class="tok-tag">,</span>
  <span class="tok-str">"occurredAt"</span><span class="tok-tag">:</span> <span class="tok-kt">$eventCreated</span>
<span class="tok-tag">}</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Dry runs ────────────────────────────── -->
    <section
      id="trace"
      class="section"
    >
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Dry runs
          </p>
          <h2>Trace it <em>before it's live</em></h2>
          <p class="section-sub">
            A dry run feeds a sample event through the whole graph exactly as
            a real run would — except action nodes record what they
            <em>would</em> do instead of doing it. The node-by-node trace
            shows every value on every edge, so you see the shape of the data
            before you flip the pipeline to active.
          </p>
          <ul class="point-list">
            <li>The payload builder pre-fills a skeleton from the event catalog; entity pickers fill in real ids.</li>
            <li>The trace doubles as a schema explorer for writing conditions and expressions.</li>
            <li>Secrets trace by name only — the value never appears.</li>
          </ul>
        </div>
        <div class="code-window trace-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">dry run — node trace</span>
          </div>
          <ul class="trace-rows">
            <li>
              <span class="trace-cat">input</span>
              <span class="trace-name">profile.created</span>
              <span class="trace-status">output</span>
            </li>
            <li>
              <span class="trace-cat">fetch</span>
              <span class="trace-name">Event → Profile</span>
              <span class="trace-status">output</span>
            </li>
            <li>
              <span class="trace-cat">route</span>
              <span class="trace-name">opt-in?</span>
              <span class="trace-status status-true">routed → true</span>
            </li>
            <li>
              <span class="trace-cat">transform</span>
              <span class="trace-name">Shape payload</span>
              <span class="trace-status">output</span>
            </li>
            <li>
              <span class="trace-cat">action</span>
              <span class="trace-name">Send Webhook</span>
              <span class="trace-status status-would">would execute (skipped)</span>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverPipelinesExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#38c78e"
        class="closing-mark"
      />
      <h2>Pipelines ship <em>with Bosca</em></h2>
      <p class="section-sub">
        Every subsystem publishes its events to one catalog, and every
        catalogued event can trigger a pipeline — content publishes, profile
        changes, task transitions, comments. The docs cover the editor, the
        node vocabulary, and the execution model end to end.
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
  max-width: 460px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

.hero-window {
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 10%, transparent);
}

/* ── The graph ───────────────────────────────── */

.graph {
  display: block;
  width: 100%;
  height: auto;
}

.graph .dot-grid {
  fill: var(--line);
}

.graph .edge {
  fill: none;
  stroke: color-mix(in srgb, var(--accent) 55%, transparent);
  stroke-width: 1.5;
  stroke-dasharray: 6 6;
  animation: graph-flow 1.6s linear infinite;
}

.graph .edge-true {
  stroke: color-mix(in srgb, #34d99a 65%, transparent);
}

.graph .edge-false {
  stroke: color-mix(in srgb, #f87171 45%, transparent);
  animation: none;
}

.graph .edge-label {
  font-family: var(--font-mono);
  font-size: 10px;
}

.graph .label-true {
  fill: #34d99a;
}

.graph .label-false {
  fill: #f87171;
}

.graph .node rect {
  fill: color-mix(in srgb, var(--bg-1) 88%, transparent);
  stroke: var(--line-2);
  stroke-width: 1;
}

.graph .node-title {
  font-family: var(--font-mono);
  font-size: 12.5px;
  fill: var(--fg-0);
}

.graph .node-sub {
  font-family: var(--font-mono);
  font-size: 10px;
  fill: var(--fg-3);
}

.graph .port {
  fill: var(--bg-0);
  stroke: var(--accent);
  stroke-width: 1.5;
}

.graph .port-true {
  stroke: #34d99a;
}

.graph .port-false {
  stroke: #f87171;
}

.graph .node-ghost rect {
  fill: transparent;
  stroke: var(--line);
  stroke-dasharray: 4 4;
}

.graph .ghost-label {
  text-anchor: middle;
}

@keyframes graph-flow {
  to {
    stroke-dashoffset: -12;
  }
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

/* ── Sections ────────────────────────────────── */

.shaping {
  padding-top: 90px;
}

/* ── Dry-run trace window ────────────────────── */

.trace-rows {
  list-style: none;
  margin: 0;
  padding: 8px 0;
}

.trace-rows li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 20px;
}

.trace-rows li + li {
  border-top: 1px solid var(--line);
}

.trace-cat {
  flex-shrink: 0;
  width: 76px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
}

.trace-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-1);
}

.trace-status {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 3px 10px;
}

.status-true {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.status-would {
  color: #ffb547;
  border-color: color-mix(in srgb, #ffb547 40%, transparent);
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

  .graph .edge {
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

  .trace-cat {
    display: none;
  }
}
</style>
