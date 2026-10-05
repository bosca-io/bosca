<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Error Tracking — Grouped Errors with AI Root-Cause Hints',
  description: 'Errors your apps report are fingerprinted into groups with counts, stack traces, and status. Each group can generate an AI root-cause hypothesis from its stack trace, on demand.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Analytics', path: '/discover/analytics' },
  { name: 'Error tracking', path: '/discover/analytics/error-tracking' }
], '/og-analytics.png')
</script>

<template>
  <DiscoverShell section-id="analytics">
    <section class="page-hero">
      <p class="kicker load-1">
        Error tracking
      </p>
      <h1 class="load-2">
        Errors, <em>grouped and explained</em>
      </h1>
      <p class="section-sub load-3">
        The error events your apps report don't pile up as a flat list. They're
        fingerprinted into groups — one per distinct fault — each with its
        counts, a stack trace, a status, and an AI root-cause hint you can ask
        for.
      </p>
    </section>

    <!-- ── Grouping ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Grouping
          </p>
          <h2>One fault, <em>one group</em></h2>
          <p class="section-sub">
            Errors are fingerprinted, so the same fault from a thousand sessions
            becomes a single group with a count — not a thousand rows. Each
            group shows its status, type, message, whether it's fatal, how many
            events it holds, and when it was last seen.
          </p>
          <ul class="point-list">
            <li>Search by type or message, and filter by status or by fatal versus non-fatal.</li>
            <li>Move a group through open, resolved, and ignored as you work it.</li>
          </ul>
        </div>
        <div class="groups-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">error groups</span>
          </div>
          <div class="groups-body">
            <div class="grp-row">
              <span class="grp-status s-open">open</span>
              <span class="grp-text">
                <span class="grp-type">TypeError</span>
                <span class="grp-msg">Cannot read 'id' of undefined</span>
              </span>
              <span class="grp-count">1.2k</span>
            </div>
            <div class="grp-row">
              <span class="grp-status s-open">open</span>
              <span class="grp-text">
                <span class="grp-type">NetworkError <span class="grp-fatal">fatal</span></span>
                <span class="grp-msg">Request timed out after 30s</span>
              </span>
              <span class="grp-count">418</span>
            </div>
            <div class="grp-row grp-muted">
              <span class="grp-status s-resolved">resolved</span>
              <span class="grp-text">
                <span class="grp-type">RangeError</span>
                <span class="grp-msg">Invalid array length</span>
              </span>
              <span class="grp-count">37</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── AI root cause ───────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            AI root cause
          </p>
          <h2>A first guess, <em>from the trace</em></h2>
          <p class="section-sub">
            Open a group and ask for an analysis: the platform reads the
            captured stack trace and generates a root-cause hypothesis — a
            starting point that saves the first ten minutes of every
            investigation. Re-analyze after a cooldown when you've learned more,
            and the last analysis is timestamped.
          </p>
          <ul class="point-list">
            <li>The hypothesis is generated from a representative event's real stack trace.</li>
            <li>The group's summary carries everything else — app, fingerprint, first and last seen, event count.</li>
          </ul>
        </div>
        <div class="rca-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">root cause · TypeError</span>
          </div>
          <div class="rca-body">
            <div class="rca-trace">
              <pre><code><span class="tok-com">at renderProfile (profile.ts:42)</span>
<span class="tok-com">at ProfileCard (card.tsx:18)</span>
<span class="tok-com">at mount (runtime.js:220)</span></code></pre>
            </div>
            <div class="rca-analysis">
              <span class="rca-tag"><Icon
                name="wand"
                :size="13"
              /> hypothesis</span>
              <p>
                <code>profile.ts:42</code> reads <code>user.id</code> before
                the profile query resolves. Guard the render, or wait for the
                query, when a card mounts without a loaded user.
              </p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Triage ──────────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Triage
        </p>
        <h2>Work them <em>to zero</em></h2>
        <p class="section-sub">
          Every group carries a status you drive as you go — so the list stays a
          worklist, not a graveyard.
        </p>
      </div>
      <div class="triage-grid reveal">
        <div class="triage-card">
          <span class="triage-dot s-open" />
          <h3>Open</h3>
          <p>A live fault, awaiting attention. New groups start here.</p>
        </div>
        <div class="triage-card">
          <span class="triage-dot s-resolved" />
          <h3>Resolved</h3>
          <p>Fixed and closed — reopen it in a click if it returns.</p>
        </div>
        <div class="triage-card">
          <span class="triage-dot s-ignored" />
          <h3>Ignored</h3>
          <p>Known and accepted noise you don't want cluttering the list.</p>
        </div>
      </div>
    </section>

    <DiscoverAnalyticsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.groups-window,
.rca-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Error groups ────────────────────────────── */

.groups-body {
  padding: 8px;
}

.grp-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.grp-row + .grp-row {
  border-top: 1px solid var(--line);
}

.grp-status {
  flex-shrink: 0;
  min-width: 62px;
  text-align: center;
  font-family: var(--font-mono);
  font-size: 10px;
  border-radius: 999px;
  padding: 3px 8px;
  border: 1px solid transparent;
}

.s-open { color: #f87171; border-color: color-mix(in srgb, #f87171 40%, transparent); }
.s-resolved { color: #268e71; border-color: color-mix(in srgb, #268e71 45%, transparent); }
.s-ignored { color: var(--fg-3); border-color: var(--line-2); }

.grp-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.grp-type {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-0);
  display: flex;
  align-items: center;
  gap: 8px;
}

.grp-fatal {
  font-size: 9.5px;
  color: #f87171;
  border: 1px solid color-mix(in srgb, #f87171 40%, transparent);
  border-radius: 3px;
  padding: 0 4px;
}

.grp-msg {
  font-size: 12px;
  color: var(--fg-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.grp-count {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}

.grp-muted {
  opacity: 0.5;
}

/* ── Root cause ──────────────────────────────── */

.rca-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.rca-trace {
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  padding: 12px 14px;
}

.rca-trace pre {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.7;
}

.rca-analysis {
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  padding: 14px 16px;
}

.rca-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--accent);
  margin-bottom: 8px;
}

.rca-analysis p {
  font-size: 13px;
  line-height: 1.65;
  color: var(--fg-1);
  margin: 0;
}

.rca-analysis code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
}

/* ── Triage ──────────────────────────────────── */

.triage-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.triage-card {
  padding: 22px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.triage-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 999px;
  margin-bottom: 12px;
  border: 2px solid;
}

.triage-dot.s-open { border-color: #f87171; }
.triage-dot.s-resolved { border-color: #268e71; background: #268e71; }
.triage-dot.s-ignored { border-color: var(--fg-3); }

.triage-card h3 {
  font-size: 15px;
  font-weight: 650;
  margin: 0 0 7px;
}

.triage-card p {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 720px) {
  .triage-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
