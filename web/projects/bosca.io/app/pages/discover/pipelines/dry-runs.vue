<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Pipeline Dry Runs — Trace Before It Goes Live',
  description: 'A dry run feeds a sample event through the whole pipeline graph exactly as a real run would — except action nodes record what they would do instead of doing it.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Pipelines', path: '/discover/pipelines' },
  { name: 'Dry runs', path: '/discover/pipelines/dry-runs' }
], '/og-pipelines.png')

const STATUSES = [
  { snippet: 'output shown', body: 'The node ran and produced the displayed value — exactly what flowed to downstream nodes.' },
  { snippet: 'would execute', body: 'An action node recorded its intended side effect — the job, the recipients, the URL — without performing it.' },
  { snippet: 'routed', body: 'A Condition or Switch evaluated its expression and chose a branch.' },
  { snippet: 'skipped', body: 'The node sits on a branch the route did not take, so it never ran.' },
  { snippet: 'failed', body: 'The node threw; its error message is shown. A real run hitting the same error would be recorded as failed.' },
  { snippet: 'no output', body: 'The node ran but produced nothing — normal for most action nodes.' }
]
</script>

<template>
  <DiscoverShell section-id="pipelines">
    <section class="page-hero">
      <p class="kicker load-1">
        Dry runs
      </p>
      <h1 class="load-2">
        The whole graph runs.<br>
        <em>Nothing happens.</em>
      </h1>
      <p class="section-sub load-3">
        A dry run feeds a sample event into the pipeline and evaluates every
        node exactly as a real run would — with one difference: action nodes
        record what they <em>would</em> have done instead of doing it. The
        result is a node-by-node trace you inspect before flipping the
        pipeline to active.
      </p>
    </section>

    <!-- ── Running one ─────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Running one
          </p>
          <h2>A payload, <em>pre-filled</em></h2>
          <p class="section-sub">
            The dry-run modal pre-fills a JSON skeleton from the accepted
            event's catalogued fields. For events that reference an entity, a
            picker lets you choose a real profile, organization, collection,
            or document — and fills the payload's id for you.
          </p>
          <ul class="point-list">
            <li>Dry runs always execute the <em>last saved</em> graph, so the button is disabled while you have unsaved changes.</li>
            <li>The payload is decoded as the accepted event type; if it doesn't decode, the run aborts with the reason before any node executes.</li>
            <li>An Execute Script node can opt in to running during dry runs — for scripts that are pure transforms.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">dry run — sample payload</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">{</span>
  <span class="tok-str">"id"</span><span class="tok-tag">:</span> <span class="tok-str">"7c9e…a104"</span><span class="tok-tag">,</span>   <span class="tok-com">← picked from real profiles</span>
  <span class="tok-str">"name"</span><span class="tok-tag">:</span> <span class="tok-str">"Ada Lovelace"</span><span class="tok-tag">,</span>
  <span class="tok-str">"email"</span><span class="tok-tag">:</span> <span class="tok-str">"ada@example.com"</span>
<span class="tok-tag">}</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── The trace vocabulary ────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Reading the trace
        </p>
        <h2>Six statuses to <em>learn</em></h2>
        <p class="section-sub">
          The results list shows one row per node, in canvas order, each with
          its category, name, and what happened.
        </p>
      </div>
      <div class="vocab-grid reveal">
        <div
          v-for="status in STATUSES"
          :key="status.snippet"
          class="vocab-card"
        >
          <code>{{ status.snippet }}</code>
          <p>{{ status.body }}</p>
        </div>
      </div>
    </section>

    <!-- ── The trace as a tool ─────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            More than a check
          </p>
          <h2>The trace is <em>a schema explorer</em></h2>
          <p class="section-sub">
            Output blocks show the exact field names and shapes flowing along
            each edge — the easiest way to discover what paths a Condition or
            JSONata expression can reference. Write the expression against
            what you see, not what you guess.
          </p>
          <ul class="point-list">
            <li>Dry runs execute under <em>your</em> account; triggered runs execute under the pipeline service account. If a fetch behaves differently live, check permissions first.</li>
            <li>Nodes that reference secrets trace the secret's <em>name</em> only — the value never appears in a trace or in run history.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">trace — a node's output</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-com">Event → Profile · output</span>
<span class="tok-tag">{</span>
  <span class="tok-str">"id"</span><span class="tok-tag">:</span> <span class="tok-str">"7c9e…a104"</span><span class="tok-tag">,</span>
  <span class="tok-str">"name"</span><span class="tok-tag">:</span> <span class="tok-str">"Ada Lovelace"</span><span class="tok-tag">,</span>
  <span class="tok-str">"attributes"</span><span class="tok-tag">:</span> <span class="tok-tag">{</span> <span class="tok-str">"marketingOptIn"</span><span class="tok-tag">:</span> <span class="tok-kt">true</span> <span class="tok-tag">}</span>
<span class="tok-tag">}</span>

<span class="tok-com">Send Webhook · would execute (skipped)</span>
<span class="tok-kt">POST</span> <span class="tok-attr">secret:</span><span class="tok-str">crm-url</span>  <span class="tok-com">— value resolved at run time</span></code></pre>
          </div>
        </div>
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

@media (max-width: 960px) {
  .vocab-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .vocab-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
