<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Audience Segments — Static Lists & Dynamic, Analytics-Backed Audiences',
  description: 'A segment addresses a subset of your people. Curate a static list, include everyone, or define a dynamic segment whose membership is computed from an analytics query and refreshed on a schedule — then feed it to campaigns and recommendations.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Audience', path: '/discover/audience' },
  { name: 'Segments', path: '/discover/audience/segments' }
], '/og-audience.png')

const TYPES = [
  { icon: 'list-checks', name: 'Static', body: 'A list you curate — add and remove profiles directly, one at a time or in bulk.' },
  { icon: 'filter', name: 'Dynamic', body: 'Membership computed from an analytics query and refreshed on a schedule, so it stays current on its own.' },
  { icon: 'users', name: 'Everyone', body: 'The whole population — every profile, no rules to maintain.' }
]
</script>

<template>
  <DiscoverShell section-id="audience">
    <section class="page-hero">
      <p class="kicker load-1">
        Segments
      </p>
      <h1 class="load-2">
        The audiences that <em>keep themselves current</em>
      </h1>
      <p class="section-sub load-3">
        A segment addresses a subset of your people. Curate it by hand, include
        everyone, or let it define itself from an analytics query that re-runs on
        a schedule — then hand it to the parts of the platform that reach or
        personalize for those people.
      </p>
    </section>

    <!-- ── Segment types ───────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Segment types
        </p>
        <h2>Three ways to <em>define an audience</em></h2>
      </div>
      <div class="type-grid reveal">
        <article
          v-for="t in TYPES"
          :key="t.name"
          class="type-card"
        >
          <span class="type-icon">
            <Icon
              :name="t.icon"
              :size="16"
            />
          </span>
          <h3>{{ t.name }}</h3>
          <p>{{ t.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── Dynamic segments ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Dynamic segments
          </p>
          <h2>An audience is <em>a query</em></h2>
          <p class="section-sub">
            A dynamic segment is defined by an analytics query and an evaluation
            schedule. Run it on demand or let it refresh automatically, and its
            membership updates as the query results change — so "active nature
            readers this week" is always exactly who that is right now.
          </p>
          <ul class="point-list">
            <li>Built on the same analytics your apps already produce — no separate data pipeline.</li>
            <li>A segment moves through draft, active, paused, and archived as you work it.</li>
            <li>Subtract one segment from another to carve out exactly the audience you want.</li>
          </ul>
        </div>
        <div class="seg-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">segment · active nature readers</span>
          </div>
          <div class="seg-body">
            <div class="seg-def">
              <span class="seg-tag seg-dynamic">dynamic</span>
              <span class="seg-tag seg-active">active</span>
              <span class="seg-schedule">every 6h</span>
            </div>
            <div class="seg-query">
              <pre><code><span class="tok-kt">SELECT</span> profile_id <span class="tok-kt">FROM</span> events
<span class="tok-kt">WHERE</span> type <span class="tok-tag">=</span> <span class="tok-str">'impression'</span>
  <span class="tok-kt">AND</span> topic <span class="tok-tag">=</span> <span class="tok-str">'nature'</span></code></pre>
            </div>
            <div class="seg-count">
              <Icon
                name="users"
                :size="13"
              />
              4,812 members · refreshed 12m ago
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Where segments go ───────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Put to use
          </p>
          <h2>Defined once, <em>used everywhere</em></h2>
          <p class="section-sub">
            A segment isn't the destination — it's the audience the rest of the
            platform runs on. The same segment can target a campaign in
            Communications and steer who sees what in Recommendations, without
            being rebuilt for each.
          </p>
          <ul class="point-list">
            <li>Campaigns in Communications target one or more segments to deliver push, email, or in-app banners.</li>
            <li>Because a dynamic segment is always current, what it feeds downstream is current too.</li>
          </ul>
        </div>
        <div class="uses-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">one segment, many consumers</span>
          </div>
          <div class="uses-body">
            <div class="uses-source">
              <span class="uses-icon"><Icon
                name="filter"
                :size="15"
              /></span>
              <span>Active nature readers</span>
            </div>
            <div class="uses-arrow">
              <Icon
                name="chevron-down"
                :size="14"
              />
            </div>
            <div class="uses-targets">
              <span class="uses-target"><Icon
                name="megaphone"
                :size="13"
              /> Campaign · Communications</span>
              <span class="uses-target"><Icon
                name="sparkles"
                :size="13"
              /> Recommendations</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverAudienceExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Type cards ──────────────────────────────── */

.type-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.type-card {
  padding: 24px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.type-card:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  transform: translateY(-2px);
}

.type-icon {
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

.type-card h3 {
  font-size: 15.5px;
  font-weight: 650;
  margin: 0 0 8px;
}

.type-card p {
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Segment window ──────────────────────────── */

.seg-window,
.uses-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.seg-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.seg-def {
  display: flex;
  align-items: center;
  gap: 8px;
}

.seg-tag {
  font-family: var(--font-mono);
  font-size: 10.5px;
  border-radius: 999px;
  padding: 3px 9px;
  border: 1px solid var(--line-2);
  color: var(--fg-2);
}

.seg-tag.seg-dynamic {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}

.seg-tag.seg-active {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.seg-schedule {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.seg-query {
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  padding: 12px 14px;
}

.seg-query pre {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--fg-1);
  overflow-x: auto;
}

.seg-count {
  display: flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

/* ── Uses window ─────────────────────────────── */

.uses-body {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.uses-source {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  font-size: 13.5px;
  color: var(--fg-1);
}

.uses-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.uses-arrow {
  display: flex;
  justify-content: center;
  color: var(--accent);
}

.uses-targets {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.uses-target {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  font-size: 13px;
  color: var(--fg-1);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 760px) {
  .type-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
