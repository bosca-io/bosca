<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Analytics Ingestion — Events In, Inspectable Live',
  description: 'Your apps send typed events — sessions, interactions, impressions, completions, installations, and errors — that land in the analytics warehouse and show up live in the Raw Events explorer, down to the full detail of any single event.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Analytics', path: '/discover/analytics' },
  { name: 'Ingestion', path: '/discover/analytics/ingestion' }
], '/og-analytics.png')

const EVENT_TYPES = [
  { name: 'Session', body: 'A visit or app run — the container other events belong to.' },
  { name: 'Interaction', body: 'A click, tap, or gesture on a named element.' },
  { name: 'Impression', body: 'Something was shown — an item, a placement, a card.' },
  { name: 'Completion', body: 'A goal reached — a finish, a conversion, a milestone.' },
  { name: 'Installation', body: 'A new install or first run of an app.' },
  { name: 'Error', body: 'A captured error, with its message, type, and stack trace.' }
]
</script>

<template>
  <DiscoverShell section-id="analytics">
    <section class="page-hero">
      <p class="kicker load-1">
        Ingestion
      </p>
      <h1 class="load-2">
        Events in, <em>nothing hidden</em>
      </h1>
      <p class="section-sub load-3">
        Your apps send events; they land in the analytics warehouse; and the
        Raw Events explorer shows them exactly as the warehouse sees them — so
        you can watch data arrive and open any single event in full.
      </p>
    </section>

    <!-- ── Event types ─────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Event types
        </p>
        <h2>Six kinds of <em>event</em></h2>
        <p class="section-sub">
          Everything a query reads is one of these — a typed record with a
          context, a device, and a payload.
        </p>
      </div>
      <div class="type-grid reveal">
        <div
          v-for="type in EVENT_TYPES"
          :key="type.name"
          class="type-card"
        >
          <h3>{{ type.name }}</h3>
          <p>{{ type.body }}</p>
        </div>
      </div>
    </section>

    <!-- ── Raw Events explorer ─────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Raw Events explorer
          </p>
          <h2>Watch data <em>as it lands</em></h2>
          <p class="section-sub">
            The Raw Events explorer is a live window onto ingestion. Filter by
            event type, by a time range, or by app, session, or user, page
            through the results, and click any row to open the full event.
          </p>
          <ul class="point-list">
            <li>Every event opens to its sections — identity and timing, context, device and location, the element, any error, and the full raw JSON.</li>
            <li>An error event carries its message, type, fatal flag, and stack trace, right there in the detail.</li>
            <li>It defaults to the last seven days, so the newest data is always in view.</li>
          </ul>
        </div>
        <div class="events-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">raw events</span>
          </div>
          <div class="events-body">
            <div class="ev-row">
              <span class="ev-badge b-session">session</span>
              <span class="ev-app">acme-web</span>
              <span class="ev-when">12:04:31</span>
            </div>
            <div class="ev-row">
              <span class="ev-badge b-interaction">interaction</span>
              <span class="ev-app">acme-web</span>
              <span class="ev-when">12:04:33</span>
            </div>
            <div class="ev-row">
              <span class="ev-badge b-impression">impression</span>
              <span class="ev-app">acme-web</span>
              <span class="ev-when">12:04:34</span>
            </div>
            <div class="ev-row">
              <span class="ev-badge b-completion">completion</span>
              <span class="ev-app">acme-ios</span>
              <span class="ev-when">12:04:36</span>
            </div>
            <div class="ev-row ev-error">
              <span class="ev-badge b-error">error</span>
              <span class="ev-app">acme-ios</span>
              <span class="ev-when">12:04:39</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── It's a query ────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            It's queries all the way down
          </p>
          <h2>Even the explorer is <em>a query</em></h2>
          <p class="section-sub">
            The Raw Events page isn't a special view — it's a saved,
            parameterized query running through the very same query layer that
            powers every chart and dashboard. Edit that query and you change
            what the page shows.
          </p>
          <ul class="point-list">
            <li>One query layer means one place to shape data, whether it feeds an explorer, a chart, or an export.</li>
            <li>Its time range and paging are parameters on that query, just like any other.</li>
          </ul>
        </div>
        <div class="query-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">raw-events</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-kt">SELECT</span> * <span class="tok-kt">FROM</span> events
<span class="tok-kt">WHERE</span> created <span class="tok-tag">&gt;=</span> <span class="tok-interp">:start_date</span>
  <span class="tok-kt">AND</span> created <span class="tok-tag">&lt;=</span> <span class="tok-interp">:end_date</span>
<span class="tok-kt">ORDER BY</span> created <span class="tok-kt">DESC</span>
<span class="tok-kt">OFFSET</span> <span class="tok-interp">:offset</span> <span class="tok-kt">ROWS FETCH NEXT</span> <span class="tok-interp">:page_size</span> <span class="tok-kt">ROWS ONLY</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <DiscoverAnalyticsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Event type cards ────────────────────────── */

.type-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.type-card {
  padding: 20px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.type-card h3 {
  font-size: 14.5px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 7px;
}

.type-card p {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Raw events window ───────────────────────── */

.events-window,
.query-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.events-body {
  padding: 10px 8px;
}

.ev-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}

.ev-row + .ev-row {
  border-top: 1px solid var(--line);
}

.ev-badge {
  flex-shrink: 0;
  min-width: 88px;
  text-align: center;
  font-family: var(--font-mono);
  font-size: 10.5px;
  border-radius: 999px;
  padding: 3px 8px;
  border: 1px solid transparent;
}

.b-session { color: #4a8cff; border-color: color-mix(in srgb, #4a8cff 40%, transparent); }
.b-interaction { color: #268e71; border-color: color-mix(in srgb, #268e71 45%, transparent); }
.b-impression { color: #a670f4; border-color: color-mix(in srgb, #a670f4 40%, transparent); }
.b-completion { color: #f6c453; border-color: color-mix(in srgb, #f6c453 40%, transparent); }
.b-error { color: #f87171; border-color: color-mix(in srgb, #f87171 40%, transparent); }

.ev-app {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.ev-when {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
}

.ev-error {
  background: color-mix(in srgb, #f87171 7%, transparent);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 820px) {
  .type-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .type-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
