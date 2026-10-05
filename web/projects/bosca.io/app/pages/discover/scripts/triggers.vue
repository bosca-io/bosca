<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Triggers — Run a script when something happens',
  description: 'Bind a Bosca script to a platform event and it runs asynchronously when that event fires, with the event name and payload in hand. Triggers run off the main path — they never hold up the operation that fired them — and retry with backoff on failure.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Scripts', path: '/discover/scripts' },
  { name: 'Triggers', path: '/discover/scripts/triggers' }
], '/og-scripts.png')
</script>

<template>
  <DiscoverShell section-id="scripts">
    <section class="page-hero">
      <p class="kicker load-1">
        Triggers
      </p>
      <h1 class="load-2">
        Run on <em>what happens</em>
      </h1>
      <p class="section-sub load-3">
        Some logic shouldn't wait to be asked for — it should just happen when
        something does. Drop a script into a triggered pipeline and it runs on
        the event, every time it fires.
      </p>
    </section>

    <!-- ── Run on an event ─────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Run on an event
          </p>
          <h2>Fired by <em>a triggered pipeline</em></h2>
          <p class="section-sub">
            To run a script when something happens, wire it into a pipeline
            that's triggered by that event. Pipelines watches for the event and
            fires the flow; an Execute Script node runs your script — no polling,
            no glue in between.
          </p>
          <ul class="point-list">
            <li>A pipeline triggered by an event carries an Execute Script node.</li>
            <li>When the event fires, the pipeline runs, and your script with it.</li>
            <li>Pipelines handles the watching and the wiring; the script does the work.</li>
          </ul>
        </div>
        <div class="bind-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">event → script</span>
          </div>
          <div class="bind-flow">
            <div class="flow-node">
              <span class="flow-icon"><Icon
                name="zap"
                :size="14"
              /></span>
              <span class="flow-text">event · <code>content.published</code></span>
            </div>
            <div class="flow-arrow">
              <Icon
                name="chevron-down"
                :size="14"
              />
            </div>
            <div class="flow-node">
              <span class="flow-icon"><Icon
                name="workflow"
                :size="14"
              /></span>
              <span class="flow-text">pipeline · on-publish</span>
            </div>
            <div class="flow-node accent">
              <span class="flow-icon"><Icon
                name="code"
                :size="14"
              /></span>
              <span class="flow-text">execute script · welcome-email</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── The event in hand ───────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            The event in hand
          </p>
          <h2>The payload comes <em>with it</em></h2>
          <p class="section-sub">
            When a trigger runs, the script is handed what happened: the event's
            name and its full payload, as typed JSON on the script's input. Read
            the id that changed, the new state, whatever the event carried — and
            act on it.
          </p>
          <ul class="point-list">
            <li>The event name and payload arrive as the script's input.</li>
            <li>Reach the same platform services any script can.</li>
            <li>It runs as a service account, or the principal you configure.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">input</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">{</span>
  <span class="tok-str">"eventName"</span><span class="tok-tag">:</span> <span class="tok-str">"content.metadata.state"</span><span class="tok-tag">,</span>
  <span class="tok-str">"eventPayload"</span><span class="tok-tag">:</span> <span class="tok-tag">{</span>
    <span class="tok-str">"id"</span><span class="tok-tag">:</span> <span class="tok-str">"9f3c…"</span><span class="tok-tag">,</span>
    <span class="tok-str">"state"</span><span class="tok-tag">:</span> <span class="tok-str">"published"</span>
  <span class="tok-tag">}</span>
<span class="tok-tag">}</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Async & resilient ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Off the main path
          </p>
          <h2>It never holds <em>anything up</em></h2>
          <p class="section-sub">
            Triggers run asynchronously, on a worker. The operation that fired
            the event — a publish, an update — finishes without waiting on them.
            And if a trigger fails, it's retried with backoff before the failure
            is recorded, so a transient blip doesn't lose the work.
          </p>
          <ul class="point-list">
            <li>The originating operation completes without waiting for triggers.</li>
            <li>Failures retry with backoff up to a bound, then are logged.</li>
            <li>Work runs on the job queue, not inline on the request.</li>
          </ul>
        </div>
        <div class="async-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">flow</span>
          </div>
          <div class="async-body">
            <div class="async-row done">
              <span class="async-dot" />
              <span class="async-text">document published</span>
              <span class="async-tag">done</span>
            </div>
            <div class="async-row">
              <span class="async-dot" />
              <span class="async-text">trigger enqueued</span>
              <span class="async-tag">async</span>
            </div>
            <div class="async-row">
              <span class="async-dot" />
              <span class="async-text">runs on a worker</span>
              <span class="async-tag retry"><Icon
                name="rotate-cw"
                :size="11"
              /> retries</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverScriptsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.bind-window,
.code-window,
.async-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Bind window ─────────────────────────────── */

.bind-flow {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}

.flow-node {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.flow-node.accent {
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

.flow-icon {
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

.flow-text {
  font-size: 13px;
  color: var(--fg-1);
}

.flow-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

.flow-arrow {
  color: var(--accent);
}

/* ── Code window ─────────────────────────────── */

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
  line-height: 1.75;
  color: var(--fg-1);
  white-space: pre;
}

/* ── Async window ────────────────────────────── */

.async-body {
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.async-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
}

.async-row + .async-row {
  border-top: 1px solid var(--line);
}

.async-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
  background: var(--accent);
}

.async-row.done .async-dot {
  background: #34d99a;
}

.async-text {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
}

.async-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-family: var(--font-mono);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--fg-3);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 3px 9px;
}

.async-row.done .async-tag {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.async-tag.retry {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
}
</style>
