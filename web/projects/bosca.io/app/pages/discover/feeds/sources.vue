<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Feed Sources — Formats, Schedules & Auth',
  description: 'Register RSS, Atom, and JSON Feed sources with a cron fetch schedule and outbound auth. Fetches are conditional — unchanged feeds no-op with a 304 — and every source is either platform-managed or owned by one user.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Feeds', path: '/discover/feeds' },
  { name: 'Sources', path: '/discover/feeds/sources' }
], '/og-feeds.png')
</script>

<template>
  <DiscoverShell section-id="feeds">
    <section class="page-hero">
      <p class="kicker load-1">
        Sources
      </p>
      <h1 class="load-2">
        A feed, <em>on a schedule</em>
      </h1>
      <p class="section-sub load-3">
        A source is an endpoint plus a fetch schedule — registered once, then
        fetched on its own cron, politely, with auth when the origin needs it.
      </p>
    </section>

    <!-- ── Formats & registration ──────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Formats
          </p>
          <h2>The formats <em>the web uses</em></h2>
          <p class="section-sub">
            The source's type selects the parser — each one reading the
            format's real conventions, not a lowest common denominator.
          </p>
          <ul class="point-list">
            <li><strong>RSS 2.0</strong> — items keyed by <code>guid</code> (falling back to <code>link</code>), with <code>content:encoded</code> and <code>dc:creator</code> read when present.</li>
            <li><strong>Atom</strong> — entries keyed by their entry id.</li>
            <li><strong>JSON Feed 1.1</strong> — items keyed by <code>id</code>, body from <code>content_html</code> falling back to <code>content_text</code>.</li>
            <li>Endpoints are normalized into a canonical URL that's unique across live sources — registering a duplicate is rejected.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">register a source</span>
          </div>
          <div class="code-body">
            <pre><span class="tok-kt">mutation</span> {
  feeds { sources { <span class="tok-attr">add</span>(input: {
    name: <span class="tok-str">"Example News"</span>
    configuration: {
      type: <span class="tok-interp">RSS</span>
      endpoint: <span class="tok-str">"https://example.com/feed.xml"</span>
      cronInterval: <span class="tok-str">"0 * * * *"</span>
      enabled: <span class="tok-interp">true</span>
    }
  }) { id url } } }
}</pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Scheduling & conditional fetches ────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Scheduling
          </p>
          <h2>Fetches that <em>don't waste a byte</em></h2>
          <p class="section-sub">
            Every enabled source runs on its own cron — and every scheduled
            fetch is conditional, so unchanged feeds cost one round trip and
            nothing more.
          </p>
          <ul class="point-list">
            <li>Each source gets its own scheduled job from its <code>cronInterval</code>; enabling creates it, disabling removes it, and edits reschedule it from scratch.</li>
            <li>ETag and Last-Modified validators are stored after each fetch and sent back as <code>If-None-Match</code> / <code>If-Modified-Since</code> — a 304 short-circuits to a no-op.</li>
            <li>Fetches run as durable jobs on the background job infrastructure; a non-2xx response fails the fetch and records it.</li>
            <li>A manual fetch is always forced — it omits the validators so the origin returns the full feed, which is how you backfill after an ingestion change.</li>
          </ul>
        </div>
        <div class="sched-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">fetch log</span>
          </div>
          <div class="sched-body">
            <div class="sched-row">
              <span class="sched-time">09:00</span>
              <span class="sched-text">cron → GET · <code>304 Not Modified</code></span>
              <span class="sched-state muted">no-op</span>
            </div>
            <div class="sched-row">
              <span class="sched-time">10:00</span>
              <span class="sched-text">cron → GET · <code>200</code> · 3 new items</span>
              <span class="sched-state ok">ingested</span>
            </div>
            <div class="sched-row">
              <span class="sched-time">10:12</span>
              <span class="sched-text">manual → forced, full feed</span>
              <span class="sched-state ok">backfilled</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Auth & ownership ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Auth &amp; ownership
          </p>
          <h2>Private origins, <em>private sources</em></h2>
          <p class="section-sub">
            Protected feeds get outbound credentials; personal feeds get
            personal sources — with the secret handled the way secrets should
            be.
          </p>
          <ul class="point-list">
            <li>Outbound auth types: none, basic, bearer, or API key.</li>
            <li>The one secret value is write-only — set via <code>authSecret</code>, stored in the platform's configuration service, never returned by any query. Deleting the source deletes the secret.</li>
            <li>Sources are <strong>managed</strong> (platform-wide, admin-gated) or <strong>user</strong> (private to one profile, every operation ownership-gated).</li>
            <li>A user source can't claim a canonical URL a managed source already uses.</li>
          </ul>
        </div>
        <div class="cfg-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">source configuration</span>
          </div>
          <div class="cfg-body">
            <div class="cfg-row">
              <span class="cfg-key">Type</span>
              <span class="cfg-val cfg-pill">RSS</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Schedule</span>
              <span class="cfg-val"><code>0 * * * *</code></span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Auth</span>
              <span class="cfg-val cfg-pill">Bearer</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Secret</span>
              <span class="cfg-val cfg-secret">write-only</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Ownership</span>
              <span class="cfg-val cfg-pill">Managed</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverFeedsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Schedule window ─────────────────────────── */

.sched-window,
.cfg-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.sched-body,
.cfg-body {
  padding: 10px 8px;
}

.sched-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.sched-row + .sched-row,
.cfg-row + .cfg-row {
  border-top: 1px solid var(--line);
}

.sched-time {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
  flex-shrink: 0;
}

.sched-text {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
}

.sched-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

.sched-state {
  font-family: var(--font-mono);
  font-size: 11px;
}

.sched-state.ok { color: #34d99a; }
.sched-state.muted { color: var(--fg-3); }

/* ── Config window ───────────────────────────── */

.cfg-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
}

.cfg-key {
  font-size: 13px;
  color: var(--fg-2);
}

.cfg-val {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.cfg-val code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

.cfg-pill {
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 10px;
}

.cfg-secret {
  color: #f6c453;
}
</style>
