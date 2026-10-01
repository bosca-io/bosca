<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Analytics Queries — Saved SQL, Typed Parameters, Git-Backed',
  description: 'An analytics query is SQL plus a typed parameter list — the single source of truth every chart and dashboard runs. Queries can live in Git with two-way sync, and their results cache and refresh on a schedule.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Analytics', path: '/discover/analytics' },
  { name: 'Queries', path: '/discover/analytics/queries' }
], '/og-analytics.png')

const PARAM_TYPES = ['STRING', 'INTEGER', 'FLOAT', 'BOOLEAN', 'DATE', 'DATETIME', 'TIME', 'ARRAY', 'OBJECT']
</script>

<template>
  <DiscoverShell section-id="analytics">
    <section class="page-hero">
      <p class="kicker load-1">
        Queries
      </p>
      <h1 class="load-2">
        One query, <em>every chart</em>
      </h1>
      <p class="section-sub load-3">
        A saved query is SQL plus a typed parameter list. It's the single
        source of truth every visualization and dashboard draws from — get the
        number right once, in one place, and everything downstream follows.
      </p>
    </section>

    <!-- ── Anatomy ─────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Anatomy of a query
          </p>
          <h2>SQL, <em>plus its inputs</em></h2>
          <p class="section-sub">
            Every query has a stable key you reference it by, a readable name
            and description, the SQL body itself, and a list of typed
            parameters. Charts and dashboards don't copy the SQL — they run the
            query, so there's exactly one definition to maintain.
          </p>
          <ul class="point-list">
            <li>The <strong>key</strong> is the handle code and dashboards reference — distinct from the display name, so renaming a query never breaks a link.</li>
            <li>Each <strong>parameter</strong> carries a name, a type, an optional default, and a required flag.</li>
            <li>Run the SQL right in the detail page with parameter inputs and preview the rows inline.</li>
          </ul>
        </div>
        <div class="query-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">sessions-by-day</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-com">-- key: sessions-by-day</span>
<span class="tok-kt">SELECT</span> date_trunc(<span class="tok-str">'day'</span><span class="tok-tag">,</span> created) <span class="tok-kt">AS</span> day<span class="tok-tag">,</span>
       <span class="tok-kt">count</span>(*) <span class="tok-kt">AS</span> sessions
<span class="tok-kt">FROM</span> events
<span class="tok-kt">WHERE</span> type <span class="tok-tag">=</span> <span class="tok-str">'session'</span>
  <span class="tok-kt">AND</span> app <span class="tok-tag">=</span> <span class="tok-interp">:appId</span>
  <span class="tok-kt">AND</span> created <span class="tok-tag">&gt;=</span> <span class="tok-interp">:from</span>
<span class="tok-kt">GROUP BY</span> <span class="tok-kt">1 ORDER BY 1</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Typed parameters ────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Typed parameters
        </p>
        <h2>Inputs with <em>a type</em></h2>
        <p class="section-sub">
          A parameter isn't just a placeholder — it declares its type, so a date
          range is a date range and an id list is an array. The same query
          serves one app or a whole fleet; you just change the inputs.
        </p>
      </div>
      <div class="param-chips reveal">
        <span
          v-for="t in PARAM_TYPES"
          :key="t"
          class="param-chip"
        >{{ t }}</span>
        <span class="param-chip chip-array">ARRAY&lt;of any type&gt;</span>
      </div>
    </section>

    <!-- ── Git-backed ──────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Git-backed queries
          </p>
          <h2>Your SQL, <em>in version control</em></h2>
          <p class="section-sub">
            A query can bind to a SQL file in a Git repository and sync both
            ways. Push a change and the query updates in Studio; edit it in
            Studio and it commits back to the file, with author info.
          </p>
          <ul class="point-list">
            <li>Parameter definitions travel with the SQL, so a file carries its own typed inputs.</li>
            <li>Review query changes in a pull request like any other code.</li>
          </ul>
        </div>
        <div class="sync-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">two-way sync</span>
          </div>
          <div class="sync-body">
            <div class="sync-side">
              <span class="sync-icon"><Icon
                name="git-branch"
                :size="16"
              /></span>
              <span class="sync-label">Git</span>
              <span class="sync-file">sessions-by-day.sql</span>
            </div>
            <div class="sync-arrows">
              <Icon
                name="arrow-right"
                :size="15"
              />
              <Icon
                name="arrow-left"
                :size="15"
              />
            </div>
            <div class="sync-side">
              <span class="sync-icon"><Icon
                name="search"
                :size="16"
              /></span>
              <span class="sync-label">Studio</span>
              <span class="sync-file">saved query</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Caching ─────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Caching
          </p>
          <h2>Fast dashboards, <em>fresh numbers</em></h2>
          <p class="section-sub">
            Opt a query into result caching and each distinct set of parameters
            is cached and refreshed on an interval by a background job. A
            dashboard loads from cache instantly, and every result says whether
            it is live, cached, refreshing, or stale — including when it was
            last computed.
          </p>
          <ul class="point-list">
            <li>Every parameter combination is cached on its own, so a per-app view and a fleet view each stay warm.</li>
            <li>Trigger a refresh on demand to recompute every cached combination at once.</li>
            <li>If a refresh fails, the last good result stays available and is clearly marked stale while the next refresh retries.</li>
          </ul>
        </div>
        <div class="cache-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">cached results</span>
          </div>
          <div class="cache-body">
            <div class="cache-row">
              <span class="cache-params">appId = acme-web</span>
              <span class="cache-tag cache-hit">cached · 2m ago</span>
            </div>
            <div class="cache-row">
              <span class="cache-params">appId = acme-ios</span>
              <span class="cache-tag cache-stale">stale · 11m ago</span>
            </div>
            <div class="cache-row">
              <span class="cache-params">appId = acme-web · 90d</span>
              <span class="cache-tag cache-fresh">refreshing…</span>
            </div>
            <div class="cache-foot">
              refresh interval · 5 min
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverAnalyticsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.query-window,
.sync-window,
.cache-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Parameter chips ─────────────────────────── */

.param-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.param-chip {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 8px 16px;
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.param-chip.chip-array {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

/* ── Git two-way sync ────────────────────────── */

.sync-body {
  display: flex;
  align-items: stretch;
  gap: 14px;
  padding: 26px 22px;
}

.sync-side {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 20px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  text-align: center;
}

.sync-icon {
  width: 34px;
  height: 34px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}

.sync-label {
  font-size: 13.5px;
  font-weight: 650;
  color: var(--fg-0);
}

.sync-file {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.sync-arrows {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: var(--accent);
}

/* ── Cache window ────────────────────────────── */

.cache-body {
  padding: 10px 8px;
}

.cache-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
}

.cache-row + .cache-row {
  border-top: 1px solid var(--line);
}

.cache-params {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.cache-tag {
  font-family: var(--font-mono);
  font-size: 10.5px;
  border-radius: 999px;
  padding: 3px 9px;
}

.cache-hit {
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
}

.cache-fresh {
  color: #f6c453;
  border: 1px solid color-mix(in srgb, #f6c453 40%, transparent);
}

.cache-stale {
  color: #e0a23a;
  border: 1px solid color-mix(in srgb, #e0a23a 40%, transparent);
}

.cache-foot {
  padding: 12px 14px 6px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  text-align: right;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 560px) {
  .sync-body {
    flex-direction: column;
  }

  .sync-arrows {
    flex-direction: row;
    transform: rotate(90deg);
  }
}
</style>
