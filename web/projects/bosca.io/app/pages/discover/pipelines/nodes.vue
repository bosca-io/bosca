<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'The Pipeline Node Vocabulary — Transforms, Routes & Actions',
  description: 'Every pipeline node belongs to a category — input, transform, fetch, combine, route, action, output — and platform modules contribute domain nodes for content, moderation, search, audience, and more.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Pipelines', path: '/discover/pipelines' },
  { name: 'The node vocabulary', path: '/discover/pipelines/nodes' }
], '/og-pipelines.png')

const CATEGORIES = [
  { name: 'Input', body: 'The graph\'s entry point — receives the triggering value, typed as the accepted event.' },
  { name: 'Transform', body: 'Reshapes data and always produces an output. JSONata, bridges, and conversions live here.' },
  { name: 'Fetch', body: 'Resolves a full platform entity from a reference in the inbound value — events carry ids, not entities.' },
  { name: 'Combine', body: 'Fans in — merges multiple inbound branches into one value, keyed by each edge\'s port name.' },
  { name: 'Route', body: 'Branches — sends the value down one of several output ports. Condition and Switch.' },
  { name: 'Action', body: 'Performs a side effect or controls execution; some actions suspend the run and resume it later.' },
  { name: 'Output', body: 'Marks the pipeline\'s result. Optional — a pipeline that only performs actions needs none.' }
]

const ACTIONS = [
  { name: 'Execute Job', body: 'Enqueue a platform background job — fire-and-forget, or suspend until it finishes with failures routed out an error port.' },
  { name: 'Execute Script', body: 'Run a stored Kotlin script synchronously; its result flows to downstream nodes, so it can sit mid-graph.' },
  { name: 'Run Pipeline', body: 'Invoke another pipeline as a sub-graph and pass its result downstream — the composition building block.' },
  { name: 'Run Pipeline for Segment', body: 'Fan a body pipeline out once for every current member of an audience segment, carrying the segment, profile, context, and caller identity into each durable run.' },
  { name: 'For Each', body: 'Run a body pipeline once per item of an array, with configurable concurrency and per-item error handling.' },
  { name: 'Dispatch Event', body: 'Publish a catalogued platform event — the fan-out primitive; every active pipeline accepting it runs.' },
  { name: 'Delay / Wait Until', body: 'Suspend the run for a duration or until a timestamp — even one inside the flowing value — then resume.' },
  { name: 'Send Email', body: 'Send to recipient profiles through the platform\'s messaging service, as text or HTML.' },
  { name: 'Send Slack Message', body: 'Post to a Slack incoming webhook referenced by secret name — the URL is never stored in the graph.' },
  { name: 'Send Webhook', body: 'POST the inbound JSON to a secret-held URL, optionally signed with HMAC-SHA256 so the receiver can verify it.' }
]

const DOMAINS = [
  { icon: 'library', name: 'Content', body: 'Set attributes, flip public/ready/searchable, run workflow transitions, compute embeddings, summarize.' },
  { icon: 'message', name: 'Comments', body: 'A moderation toolkit — Moderate Text, evaluate verdicts, and set comment status, composable into custom flows.' },
  { icon: 'search', name: 'Search', body: 'Index and remove documents, with a replace mode that clears an entity\'s previous documents first.' },
  { icon: 'users', name: 'Audience', body: 'Profile event resolvers, get and set profile attributes, and durable segment-wide pipeline fan-out.' },
  { icon: 'kanban', name: 'Work Ops', body: 'Spec, requirement, and task event resolvers plus project and sprint lookups.' },
  { icon: 'wand', name: 'Recommendations', body: 'Compute profile signals, infer interests, and classify content for personalization.' },
  { icon: 'arrow-right-left', name: 'HubSpot', body: 'A family of CRM sync nodes — create and update objects, manage lists, route by sync state.' }
]
</script>

<template>
  <DiscoverShell section-id="pipelines">
    <section class="page-hero">
      <p class="kicker load-1">
        The node vocabulary
      </p>
      <h1 class="load-2">
        Seven categories. <em>One vocabulary.</em>
      </h1>
      <p class="section-sub load-3">
        Every node belongs to a category that determines its role in the
        graph, and the palette is the authoritative list for your
        installation — platform modules contribute nodes for their own
        domains alongside the built-ins.
      </p>
    </section>

    <!-- ── Categories ──────────────────────────── -->
    <section class="section">
      <div class="vocab-grid reveal">
        <div
          v-for="cat in CATEGORIES"
          :key="cat.name"
          class="vocab-card"
        >
          <code>{{ cat.name }}</code>
          <p>{{ cat.body }}</p>
        </div>
      </div>
    </section>

    <!-- ── JSONata ─────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Transforms
          </p>
          <h2>JSONata, with <em>a workbench</em></h2>
          <p class="section-sub">
            The JSONata node shapes or extracts JSON with a single
            expression. Its inspector expands into a workbench: paste a
            sample input on one side, edit the expression on the other, and
            preview the result without leaving the editor.
          </p>
          <ul class="point-list">
            <li>Declare the expression's output type and downstream typed input slots accept the result.</li>
            <li>Optionally validate the result against a JSON Schema before it flows on.</li>
            <li><code>$eventCreated</code> is bound to the run's occurrence timestamp in every expression.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">jsonata — extract and rename</span>
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

    <!-- ── Typed values ────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Typed values
          </p>
          <h2>Types flow. <em>JSON when you ask.</em></h2>
          <p class="section-sub">
            The event that seeds the Input node is a real typed object, not a
            JSON blob, and values keep their serialization as they flow.
            Nodes that work structurally — JSONata, Condition, Switch —
            operate on a JSON view; two bridge nodes cross the boundary
            explicitly.
          </p>
          <ul class="point-list">
            <li><em>Serializable → JSON</em> encodes a typed value while remembering its origin; <em>JSON → Typed</em> restores it.</li>
            <li>Edges also carry scalars — string, integer, number, boolean, UUID — and input slots declare which kinds they accept.</li>
            <li><em>To String</em>, <em>To UUID</em>, and <em>Get Id</em> make a type change explicit instead of relying on coercion.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">a typed edge, refused</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-com">// the editor checks value kinds as you draw</span>
<span class="tok-kt">ProfileCreated</span>  <span class="tok-tag">→</span>  <span class="tok-kt">Event → Profile</span>      <span class="tok-str">✓ typed event</span>
<span class="tok-kt">Profile</span>         <span class="tok-tag">→</span>  <span class="tok-kt">JSONata</span>              <span class="tok-str">✓ json view</span>
<span class="tok-kt">JSONata</span>         <span class="tok-tag">→</span>  <span class="tok-kt">Get Project (UUID)</span>   <span class="tok-attr">✗ not a UUID</span>
<span class="tok-kt">Get Id</span>          <span class="tok-tag">→</span>  <span class="tok-kt">Get Project (UUID)</span>   <span class="tok-str">✓ typed UUID</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Actions ─────────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Actions
        </p>
        <h2>Do something <em>with the result</em></h2>
        <p class="section-sub">
          Action nodes end most branches — and some control execution itself,
          suspending the run durably until a timer fires or a job finishes.
        </p>
      </div>
      <div class="action-grid reveal">
        <div
          v-for="action in ACTIONS"
          :key="action.name"
          class="action-card"
        >
          <h3>{{ action.name }}</h3>
          <p>{{ action.body }}</p>
        </div>
      </div>
    </section>

    <!-- ── Failure handling ────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          When things fail
        </p>
        <h2>Failure is <em>part of the graph</em></h2>
      </div>
      <div class="notes-grid reveal">
        <div class="note-card">
          <h3>Error ports</h3>
          <p>
            Some nodes expose a red error port alongside their output. When
            the node fails, the error routes out that port instead of
            aborting the run — wire it to notify someone, fall back, or
            retry another way.
          </p>
        </div>
        <div class="note-card">
          <h3>Retry policies</h3>
          <p>
            Every node can carry a retry policy: on failure the executor
            re-runs it up to the configured attempts with fixed or
            exponential backoff. Retries re-execute the node, so reserve
            them for idempotent work.
          </p>
        </div>
        <div class="note-card">
          <h3>Secrets</h3>
          <p>
            Webhook URLs and signing keys are named, encrypted secrets,
            resolved only at execution time. The graph JSON, run history,
            and dry-run traces never contain the value.
          </p>
        </div>
      </div>
    </section>

    <!-- ── Domain nodes ────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Domain nodes
        </p>
        <h2>Your platform, <em>in the palette</em></h2>
        <p class="section-sub">
          Beyond the built-ins, platform modules contribute nodes for their
          own domains — resolvers that turn lean events into full entities,
          and actions that operate on them.
        </p>
      </div>
      <div class="domain-grid">
        <article
          v-for="domain in DOMAINS"
          :key="domain.name"
          class="domain-card reveal"
        >
          <span class="domain-icon">
            <Icon
              :name="domain.icon"
              :size="16"
            />
          </span>
          <h3>{{ domain.name }}</h3>
          <p>{{ domain.body }}</p>
        </article>
      </div>
    </section>

    <DiscoverPipelinesExplore />
  </DiscoverShell>
</template>

<style scoped>
.vocab-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.vocab-card {
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.vocab-card > code {
  display: inline-block;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--accent);
  margin-bottom: 10px;
}

.vocab-card p {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

/* ── Action cards ────────────────────────────── */

.action-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.action-card {
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.action-card h3 {
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
  margin: 0 0 8px;
}

.action-card p {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

/* ── Note cards ──────────────────────────────── */

.notes-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.note-card {
  padding: 20px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.note-card h3 {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.note-card p {
  font-size: 13px;
  line-height: 1.7;
  color: var(--fg-2);
  margin: 0;
}

/* ── Domain cards ────────────────────────────── */

.domain-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.domain-card {
  padding: 20px 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-0) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.domain-card:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  transform: translateY(-2px);
}

.domain-icon {
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

.domain-card h3 {
  font-size: 14px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.domain-card p {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 960px) {
  .vocab-grid,
  .action-grid,
  .domain-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .notes-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 640px) {
  .vocab-grid,
  .action-grid,
  .domain-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
