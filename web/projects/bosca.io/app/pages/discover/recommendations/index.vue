<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Recommendations — Personalized discovery that learns',
  description: 'Bosca Recommendations generates candidates with tunable strategies, ranks them live for each person by blending behavioral, semantic, and cohort signals, works on brand-new content from day one, and learns from every gesture.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Recommendations', path: '/discover/recommendations' }
], '/og-recommendations.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Strategies generate candidates',
    body: 'Strategies build pools of candidates in the background — what\'s trending, what people engaged with together — with no per-person targeting. That work is shared across everyone.'
  },
  {
    step: '02',
    title: 'Serving ranks them live',
    body: 'When a person opens a feed, the platform ranks the candidates for that viewer on the spot — blending the model\'s scores, their ratings, and what they\'ve hidden. Only people who ask for a feed pay for it.'
  },
  {
    step: '03',
    title: 'Feedback tunes it',
    body: 'A gesture — boost, lower, care, or hide — feeds straight back into the next ranking, so the feed keeps adjusting to what each person actually wants.'
  }
]

const FEATURES = [
  {
    icon: 'zap',
    title: 'Live personalization',
    body: 'Feeds are ranked on demand, not precomputed for every profile in a batch. Ranking, rating-based re-ranking, and dismissal filtering all run at request time — so only the people who ask for a feed cost anything to serve.'
  },
  {
    icon: 'sparkles',
    title: 'Cold-start on day one',
    body: 'New content is recommendable immediately. A model that learns from a piece\'s own features and meaning surfaces it before it has a single interaction.'
  },
  {
    icon: 'layers',
    title: 'Many signals, one feed',
    body: 'Behavioral co-engagement, semantic similarity, and cohort personalization merge into a single ranked set — and every result can explain which signal put it there.'
  },
  {
    icon: 'heart-pulse',
    title: 'Learns from feedback',
    body: 'Boost, lower, care, and hide gestures become ratings and dismissals that steer the next ranking. High ratings even become durable learned interests.'
  },
  {
    icon: 'target',
    title: 'People like you',
    body: 'Co-engagement can be conditioned on a viewer\'s cohort — a deterministic key built from the signals you define — so a feed aligns to people who behave alike.'
  },
  {
    icon: 'shield',
    title: 'Validated models',
    body: 'Each model trains on the complete eligible snapshot, passes serving-contract checks, and publishes automatically; live experiments compare product outcomes from real exposure.'
  },
  {
    icon: 'flask',
    title: 'Built-in A/B testing',
    body: 'Provision an experiment to pit the model against a heuristic, or a new model against the current one — split by a feature flag, with a safe default if anything is off.'
  },
  {
    icon: 'scan-line',
    title: 'A test console',
    body: 'Try every surface live from Studio against any profile or item — feeds, trending, similar, co-engagement — each result tagged with its score, strategy, and reason.'
  }
]

const SURFACES = [
  { name: 'For You', code: 'forYou', body: 'A person\'s personalized feed, ranked live from the trained model.' },
  { name: 'Placement', code: 'placement', body: 'Recommendations for a named spot in your app, assembled from its strategies.' },
  { name: 'Recommended', code: 'recommended', body: 'The "related content" surface — behavioral and content signals merged, re-ranked for the viewer.' },
  { name: 'Similar', code: 'similar', body: 'Content alike in meaning, from the content model\'s item-to-item index.' },
  { name: 'Co-engaged', code: 'coEngaged', body: '"People who engaged with this also engaged with" — item-to-item behavior.' },
  { name: 'Trending', code: 'trending', body: 'Globally trending content by recent interaction velocity.' }
]
</script>

<template>
  <DiscoverShell
    section-id="recommendations"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Recommendations
        </p>
        <h1 class="load-2">
          The right content,<br>
          for each person,<br>
          <em>as they arrive.</em>
        </h1>
        <p class="hero-sub load-3">
          Bosca Recommendations generates candidates with strategies you tune,
          then ranks them live for each viewer — blending behavior, meaning, and
          cohort. It works on brand-new content from day one, and learns from
          every gesture.
        </p>
        <div class="hero-ctas load-4">
          <a
            href="#how"
            class="btn btn-primary"
          >
            How it works
          </a>
        </div>
      </div>

      <div
        class="hero-visual load-4"
        aria-hidden="true"
      >
        <div class="feed-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">forYou · ranked live</span>
          </div>
          <ul class="feed">
            <li class="feed-row">
              <span class="feed-rank">1</span>
              <span class="feed-body">
                <span class="feed-name">The Deep Field</span>
                <span class="feed-reason">people like you</span>
              </span>
              <span class="feed-score">0.94</span>
            </li>
            <li class="feed-row">
              <span class="feed-rank">2</span>
              <span class="feed-body">
                <span class="feed-name">Notes on Slow Media</span>
                <span class="feed-reason">alike in content</span>
              </span>
              <span class="feed-score">0.88</span>
            </li>
            <li class="feed-row">
              <span class="feed-rank">3</span>
              <span class="feed-body">
                <span class="feed-name">Field Guide: Tides</span>
                <span class="feed-reason">engaged together</span>
              </span>
              <span class="feed-score">0.81</span>
            </li>
            <li class="feed-row feed-fresh">
              <span class="feed-rank">4</span>
              <span class="feed-body">
                <span class="feed-name">Just Published Today</span>
                <span class="feed-reason">new · cold-start</span>
              </span>
              <span class="feed-score">0.77</span>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <!-- ── How it works ────────────────────────── -->
    <section
      id="how"
      class="section"
    >
      <div class="section-head reveal">
        <p class="kicker">
          How it works
        </p>
        <h2>Generate broadly. <em>Rank personally.</em></h2>
        <p class="section-sub">
          The work splits in two: building candidate pools is global and shared;
          ranking them is personal and live. That's what keeps personalization
          fresh without precomputing a feed for everyone.
        </p>
      </div>
      <div class="how-grid reveal">
        <article
          v-for="item in HOW_IT_WORKS"
          :key="item.step"
          class="how-card"
        >
          <span class="how-step">{{ item.step }}</span>
          <h3>{{ item.title }}</h3>
          <p>{{ item.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── Feature wall ────────────────────────── -->
    <section
      id="features"
      class="features"
    >
      <div class="features-inner">
        <div class="section-head reveal">
          <p class="kicker">
            What makes it powerful
          </p>
          <h2>More than "most popular." <em>Personal.</em></h2>
          <p class="section-sub">
            A production recommender — cold-start, multi-signal ranking, quality
            gates, and experimentation — wired into the same content, profiles,
            and events as the rest of the platform.
          </p>
        </div>
        <div class="feature-grid">
          <article
            v-for="feature in FEATURES"
            :key="feature.title"
            class="feature-card reveal"
          >
            <span class="feature-icon">
              <Icon
                :name="feature.icon"
                :size="16"
              />
            </span>
            <h3>{{ feature.title }}</h3>
            <p>{{ feature.body }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- ── Surfaces ────────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Serving surfaces
        </p>
        <h2>One engine, <em>many surfaces</em></h2>
        <p class="section-sub">
          Every surface is a query on the same recommendation engine — a feed, a
          "related" row, a "people also viewed" strip — so you place the right
          one wherever it belongs.
        </p>
      </div>
      <div class="surface-grid reveal">
        <div
          v-for="surface in SURFACES"
          :key="surface.code"
          class="surface-card"
        >
          <div class="surface-head">
            <h3>{{ surface.name }}</h3>
            <code>{{ surface.code }}</code>
          </div>
          <p>{{ surface.body }}</p>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverRecommendationsExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#84c032"
        class="closing-mark"
      />
      <h2>Recommendations ship <em>with Bosca</em></h2>
      <p class="section-sub">
        The recommender learns from the same content, profiles, and events every
        other subsystem produces — no pipeline to build, no data to ship out. The
        docs cover strategies, placements, signals, and the model end to end.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
  align-items: center;
  gap: 48px;
  max-width: 1140px;
  margin: 0 auto;
  padding: 64px 32px 100px;
}

.hero h1 {
  font-size: clamp(40px, 5.4vw, 64px);
  font-weight: 700;
}

.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 30%, transparent);
  border-radius: 999px;
  padding: 6px 14px;
  margin-bottom: 26px;
}

.eyebrow-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--accent);
  box-shadow: 0 0 10px var(--accent);
  animation: discover-pulse 2.4s ease-in-out infinite;
}

.hero-sub {
  font-size: 16.5px;
  line-height: 1.7;
  color: var(--fg-2);
  max-width: 470px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

/* ── For You feed ────────────────────────────── */

.feed-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 10%, transparent);
}

.feed {
  list-style: none;
  margin: 0;
  padding: 8px;
}

.feed-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 14px;
  border-radius: var(--r-sm);
}

.feed-row + .feed-row {
  margin-top: 2px;
}

.feed-rank {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
}

.feed-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.feed-name {
  font-size: 13.5px;
  color: var(--fg-0);
  font-weight: 550;
}

.feed-reason {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.feed-score {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
}

.feed-fresh {
  background: color-mix(in srgb, var(--accent) 7%, transparent);
}

.feed-fresh .feed-reason {
  color: var(--accent);
}

/* ── How it works ────────────────────────────── */

.how-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.how-card {
  padding: 24px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.how-step {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
}

.how-card h3 {
  font-size: 16.5px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 12px 0 10px;
}

.how-card p {
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--fg-2);
  margin: 0;
}

/* ── Feature wall ────────────────────────────── */

.features {
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--bg-1) 45%, transparent);
}

.features-inner {
  max-width: 1140px;
  margin: 0 auto;
  padding: 80px 32px 90px;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.feature-card {
  padding: 20px 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-0) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.feature-card:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  transform: translateY(-2px);
}

.feature-icon {
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

.feature-card h3 {
  font-size: 14px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.feature-card p {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

/* ── Serving surfaces ────────────────────────── */

.surface-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.surface-card {
  padding: 20px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.surface-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 9px;
}

.surface-head h3 {
  font-size: 14.5px;
  font-weight: 650;
  margin: 0;
}

.surface-head code {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
  border-radius: 4px;
  padding: 1px 6px;
}

.surface-card p {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Closing ─────────────────────────────────── */

.closing {
  max-width: 640px;
  margin: 0 auto;
  padding: 40px 32px 110px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.closing h2 {
  font-size: clamp(30px, 4vw, 44px);
  font-weight: 700;
  margin-top: 18px;
}

.closing .section-sub {
  margin-bottom: 26px;
}

.closing-mark {
  animation: discover-bob 7s ease-in-out infinite;
}

.btn-cta {
  padding: 13px 34px;
  font-size: 15px;
}

/* ── Motion & responsive ─────────────────────── */

@media (prefers-reduced-motion: reduce) {
  .eyebrow-dot,
  .closing-mark {
    animation: none;
  }
}

@media (max-width: 960px) {
  .hero {
    grid-template-columns: minmax(0, 1fr);
    padding-top: 36px;
    padding-bottom: 64px;
  }

  .how-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .feature-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .surface-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .feature-grid,
  .surface-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
