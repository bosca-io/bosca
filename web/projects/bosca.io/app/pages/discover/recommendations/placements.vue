<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Recommendation Placements — Named Surfaces, Ranked to Order',
  description: 'A placement is a named surface — a home feed, a sidebar, a "related" row — that binds strategies and runs every request through a fixed ranking pipeline: filter, de-duplicate, freshen, re-rank, diversify, and trim.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Recommendations', path: '/discover/recommendations' },
  { name: 'Placements', path: '/discover/recommendations/placements' }
], '/og-recommendations.png')

const PIPELINE = [
  { name: 'Filter dismissals', body: 'Anything the viewer hid is dropped up front.' },
  { name: 'De-duplicate', body: 'The same item from two strategies keeps its highest score.' },
  { name: 'Freshen', body: 'Recent content gets a boost so the surface never goes stale.' },
  { name: 'Re-rank for the viewer', body: 'Scores tilt toward the categories this person favors.' },
  { name: 'Diversify', body: 'A per-category cap keeps one topic from taking over.' },
  { name: 'Floor & trim', body: 'A score floor drops weak candidates; the rest is cut to the limit.' }
]
</script>

<template>
  <DiscoverShell section-id="recommendations">
    <section class="page-hero">
      <p class="kicker load-1">
        Placements
      </p>
      <h1 class="load-2">
        A surface for <em>every spot</em>
      </h1>
      <p class="section-sub load-3">
        A placement is a named serving surface — a home feed, an article
        sidebar, a "you might also like" row. It binds a set of strategies and
        an item limit, and every request runs through the same ranking pipeline
        before it returns.
      </p>
    </section>

    <!-- ── What a placement is ─────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The building block
          </p>
          <h2>Name it, <em>bind it, place it</em></h2>
          <p class="section-sub">
            A placement has a slug your app requests by, a set of strategies it
            draws candidates from, and a limit. Ask for it by slug and it
            assembles a ranked set from whatever its strategies have
            materialized — personalized when you pass a profile, general when
            you don't.
          </p>
          <ul class="point-list">
            <li>Bind more than one strategy; priority decides who contributes first.</li>
            <li>Editing the bound strategies replaces the list — the placement is exactly what you declare.</li>
            <li>Empty results almost always mean the strategies haven't been evaluated yet — the test console can trigger that in one place.</li>
          </ul>
        </div>
        <div class="placement-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">placement</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">{</span>
  <span class="tok-str">"slug"</span><span class="tok-tag">:</span> <span class="tok-str">"home_feed"</span><span class="tok-tag">,</span>
  <span class="tok-str">"name"</span><span class="tok-tag">:</span> <span class="tok-str">"Home Feed"</span><span class="tok-tag">,</span>
  <span class="tok-str">"maxItems"</span><span class="tok-tag">:</span> <span class="tok-kt">20</span><span class="tok-tag">,</span>
  <span class="tok-str">"strategyIds"</span><span class="tok-tag">:</span> <span class="tok-tag">[</span><span class="tok-str">"personalized"</span><span class="tok-tag">,</span> <span class="tok-str">"trending"</span><span class="tok-tag">]</span>
<span class="tok-tag">}</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── The pipeline ────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          The serving pipeline
        </p>
        <h2>From pool to <em>polished feed</em></h2>
        <p class="section-sub">
          Every request runs the candidate pool through the same fixed sequence.
          It's what turns a raw set of candidates into a feed that feels
          considered — fresh, personal, and varied.
        </p>
      </div>
      <ol class="pipeline reveal">
        <li
          v-for="(stage, i) in PIPELINE"
          :key="stage.name"
          class="pipe-stage"
        >
          <span class="pipe-num">{{ i + 1 }}</span>
          <div class="pipe-text">
            <h3>{{ stage.name }}</h3>
            <p>{{ stage.body }}</p>
          </div>
        </li>
      </ol>
    </section>

    <!-- ── Quality gates ───────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Quality on the surface
          </p>
          <h2>Only what's <em>worth showing</em></h2>
          <p class="section-sub">
            Two controls keep a placement honest. A score floor drops candidates
            the engine isn't confident about, so a thin surface shows fewer
            results rather than weak ones. A per-category cap keeps any single
            topic from crowding out the rest.
          </p>
          <ul class="point-list">
            <li>The floor is off by default — turn it up when you'd rather show less than show filler.</li>
            <li>Diversity is applied before the final trim, so the cut set is already balanced.</li>
          </ul>
        </div>
        <div class="gate-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">before → after floor</span>
          </div>
          <div class="gate-body">
            <div class="gate-row gate-keep">
              <span class="gate-name">The Deep Field</span><span class="gate-score">0.94</span>
            </div>
            <div class="gate-row gate-keep">
              <span class="gate-name">Notes on Slow Media</span><span class="gate-score">0.88</span>
            </div>
            <div class="gate-row gate-keep">
              <span class="gate-name">Field Guide: Tides</span><span class="gate-score">0.62</span>
            </div>
            <div class="gate-line">
              <span>score floor · 0.50</span>
            </div>
            <div class="gate-row gate-drop">
              <span class="gate-name">Loosely Related</span><span class="gate-score">0.41</span>
            </div>
            <div class="gate-row gate-drop">
              <span class="gate-name">Barely a Match</span><span class="gate-score">0.28</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverRecommendationsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Placement window ────────────────────────── */

.placement-window,
.gate-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Pipeline ────────────────────────────────── */

.pipeline {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  counter-reset: none;
}

.pipe-stage {
  display: flex;
  gap: 14px;
  padding: 20px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.pipe-num {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
}

.pipe-text h3 {
  font-size: 14.5px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 2px 0 6px;
}

.pipe-text p {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Gate window ─────────────────────────────── */

.gate-body {
  padding: 14px 16px 18px;
}

.gate-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-radius: var(--r-xs);
  font-size: 13px;
}

.gate-name {
  color: var(--fg-1);
}

.gate-score {
  font-family: var(--font-mono);
  font-size: 12px;
}

.gate-keep .gate-score {
  color: var(--accent);
}

.gate-drop {
  opacity: 0.4;
}

.gate-drop .gate-name {
  text-decoration: line-through;
}

.gate-drop .gate-score {
  color: var(--fg-3);
}

.gate-line {
  display: flex;
  align-items: center;
  margin: 8px 2px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
}

.gate-line::before,
.gate-line::after {
  content: '';
  flex: 1;
  height: 1px;
  background: color-mix(in srgb, var(--accent) 30%, transparent);
}

.gate-line span {
  padding: 0 10px;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 820px) {
  .pipeline {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
