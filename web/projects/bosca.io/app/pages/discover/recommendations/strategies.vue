<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Recommendation Strategies — How Candidates Are Generated',
  description: 'Strategies build the candidate pools recommendations draw from: trending, co-engagement, cohort co-engagement, and a live personalized model — each with a status, a priority, and a schedule.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Recommendations', path: '/discover/recommendations' },
  { name: 'Strategies', path: '/discover/recommendations/strategies' }
], '/og-recommendations.png')

const TYPES = [
  {
    icon: 'trending-up',
    name: 'Trending',
    body: 'Globally popular content by recent interaction velocity — the shared baseline every viewer can fall back to.'
  },
  {
    icon: 'share-2',
    name: 'Co-engagement',
    body: '"People who engaged with this also engaged with" — item-to-item edges built from real behavior.'
  },
  {
    icon: 'target',
    name: 'Cohort co-engagement',
    body: 'The same co-engagement, conditioned on a viewer\'s cohort, so "people like you" means people who behave alike.'
  },
  {
    icon: 'brain',
    name: 'Personalized',
    body: 'The live trained model — the source behind a person\'s ranked feed and the per-viewer re-ranking everywhere else.'
  }
]
</script>

<template>
  <DiscoverShell section-id="recommendations">
    <section class="page-hero">
      <p class="kicker load-1">
        Strategies
      </p>
      <h1 class="load-2">
        Where candidates <em>come from</em>
      </h1>
      <p class="section-sub load-3">
        A strategy is a candidate generator plus its configuration. Strategies
        build pools of candidates with no per-person targeting — that happens
        later, at serving time — so the expensive work is done once and shared
        across everyone.
      </p>
    </section>

    <!-- ── The four types ──────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Strategy types
        </p>
        <h2>Four ways to <em>find candidates</em></h2>
      </div>
      <div class="type-grid reveal">
        <article
          v-for="type in TYPES"
          :key="type.name"
          class="type-card"
        >
          <span class="type-icon">
            <Icon
              :name="type.icon"
              :size="16"
            />
          </span>
          <h3>{{ type.name }}</h3>
          <p>{{ type.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── Generate vs serve ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Generate, then serve
          </p>
          <h2>Broad pools, <em>personal ranking</em></h2>
          <p class="section-sub">
            Strategies never rank for a specific person. They materialize a pool
            — the trending set, the co-engagement edges — and serving does the
            personal part live, at request time, for whoever is looking. It's why
            a feed can reflect the latest behavior without precomputing one for
            every profile.
          </p>
          <ul class="point-list">
            <li>The three behavioral types are driven by an analytics query and produce a materialized pool.</li>
            <li>The personalized type holds the live model that ranks and re-ranks per viewer.</li>
            <li>Deleting a strategy removes the recommendations it produced — pools are owned, not orphaned.</li>
          </ul>
        </div>
        <div
          class="split-diagram"
          aria-hidden="true"
        >
          <div class="gen-side">
            <span class="gen-label">Candidate generation</span>
            <span class="gen-sub">global · scheduled</span>
            <div class="gen-pool">
              <span class="pool-chip">trending</span>
              <span class="pool-chip">co-engagement</span>
              <span class="pool-chip">cohort</span>
            </div>
          </div>
          <Icon
            name="arrow-right"
            :size="18"
            class="gen-arrow"
          />
          <div class="serve-side">
            <span class="gen-label">Serving</span>
            <span class="gen-sub">per person · live</span>
            <div class="serve-feed">
              <span class="serve-line" />
              <span class="serve-line" />
              <span class="serve-line short" />
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Lifecycle ───────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Running strategies
          </p>
          <h2>Status, priority, <em>schedule</em></h2>
          <p class="section-sub">
            Every strategy moves through a simple lifecycle and only produces
            recommendations while it's active — so you can stage one, pause it,
            or retire it without deleting its history.
          </p>
          <ul class="point-list">
            <li><strong>Status</strong> — draft, active, paused, or archived; only active strategies contribute.</li>
            <li><strong>Priority</strong> — decides which strategies feed a placement first.</li>
            <li><strong>Schedule</strong> — behavioral strategies refresh on a cron schedule, or on demand the moment you ask.</li>
          </ul>
        </div>
        <div class="lifecycle-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">strategy</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">{</span>
  <span class="tok-str">"name"</span><span class="tok-tag">:</span> <span class="tok-str">"Home — Trending"</span><span class="tok-tag">,</span>
  <span class="tok-str">"type"</span><span class="tok-tag">:</span> <span class="tok-str">"TRENDING"</span><span class="tok-tag">,</span>
  <span class="tok-str">"status"</span><span class="tok-tag">:</span> <span class="tok-str">"ACTIVE"</span><span class="tok-tag">,</span>
  <span class="tok-str">"priority"</span><span class="tok-tag">:</span> <span class="tok-kt">10</span><span class="tok-tag">,</span>
  <span class="tok-str">"evaluationSchedule"</span><span class="tok-tag">:</span> <span class="tok-str">"0 * * * *"</span>
<span class="tok-tag">}</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <DiscoverRecommendationsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Type cards ──────────────────────────────── */

.type-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
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
  font-size: 16px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.type-card p {
  font-size: 13px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

/* ── Generate → serve diagram ────────────────── */

.split-diagram {
  display: flex;
  align-items: stretch;
  gap: 10px;
  padding: 22px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.gen-side,
.serve-side {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 18px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.gen-label {
  font-size: 13.5px;
  font-weight: 650;
  color: var(--fg-0);
}

.gen-sub {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.gen-pool {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.pool-chip {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: 999px;
  padding: 3px 9px;
}

.gen-arrow {
  align-self: center;
  color: var(--accent);
  flex-shrink: 0;
}

.serve-feed {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-top: 14px;
}

.serve-line {
  height: 9px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--accent) 25%, transparent);
}

.serve-line.short {
  width: 60%;
}

/* ── Lifecycle window ────────────────────────── */

.lifecycle-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 720px) {
  .type-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .split-diagram {
    flex-direction: column;
  }

  .gen-arrow {
    transform: rotate(90deg);
  }
}
</style>
