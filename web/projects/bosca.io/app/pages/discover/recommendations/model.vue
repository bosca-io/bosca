<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'The Recommendation Model — Training, Validation & A/B Testing',
  description: 'Two trained models power Bosca Recommendations: a content model that works on day one and a personalized model that learns from behavior, validates before publication, and supports live A/B testing.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Recommendations', path: '/discover/recommendations' },
  { name: 'The model', path: '/discover/recommendations/model' }
], '/og-recommendations.png')
</script>

<template>
  <DiscoverShell section-id="recommendations">
    <section class="page-hero">
      <p class="kicker load-1">
        The model
      </p>
      <h1 class="load-2">
        A recommender that <em>trains itself</em>
      </h1>
      <p class="section-sub load-3">
        Two models do the work: one understands your content the day it's
        created, and one learns from how people behave. Both retrain on your
        complete eligible data, validated versions publish automatically, and
        you can select a version or compare two versions in a live test.
      </p>
    </section>

    <!-- ── Two models ──────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Two models, one engine
        </p>
        <h2>Meaning <em>and</em> behavior</h2>
      </div>
      <div class="models-grid reveal">
        <article class="model-card">
          <span class="model-icon"><Icon
            name="sparkles"
            :size="18"
          /></span>
          <h3>The content model</h3>
          <p class="model-lead">
            Learns from each item's own features and meaning — its type,
            language, categories, and what the text is actually about.
          </p>
          <ul class="model-points">
            <li>Works from day one — new content is recommendable before its first interaction.</li>
            <li>Powers "similar" and the cold-start fallback for brand-new profiles.</li>
            <li>Understands meaning, so two pieces on the same topic read as alike even with different words.</li>
          </ul>
        </article>
        <article class="model-card">
          <span class="model-icon"><Icon
            name="brain"
            :size="18"
          /></span>
          <h3>The personalized model</h3>
          <p class="model-lead">
            Learns a representation of each person and each item from real
            interactions, then ranks one against the other.
          </p>
          <ul class="model-points">
            <li>Trains once there's enough interaction history to learn from.</li>
            <li>Reads a person through their signals and affinities, so even a new viewer gets a feature-based feed.</li>
            <li>Drives the personalized feed and the per-viewer re-ranking on every surface.</li>
          </ul>
        </article>
      </div>
    </section>

    <!-- ── Training & quality gate ─────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Training &amp; validation
          </p>
          <h2>Every eligible signal <em>stays in</em></h2>
          <p class="section-sub">
            Training runs in the background — kick one off and it produces a new
            candidate model from every active profile and every eligible interaction.
            The exported artifact must then load and satisfy the serving contract.
            A candidate that passes publishes automatically.
          </p>
          <ul class="point-list">
            <li>Profiles without history are still represented, using shared signals when available.</li>
            <li>No interaction or feedback cohort is withheld to grade the model.</li>
            <li>Automatic mode serves the newest validated version; administrators can pin a recent version.</li>
          </ul>
        </div>
        <div class="gate-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">candidate validation</span>
          </div>
          <div class="gate-body">
            <div class="cc-row">
              <span class="cc-label">Contract</span>
              <span class="cc-bar"><span
                class="cc-fill"
                style="width: 72%;"
              /></span>
              <span class="cc-tag cc-live">passed</span>
            </div>
            <div class="cc-row">
              <span class="cc-label">Facets</span>
              <span class="cc-bar"><span
                class="cc-fill cc-win"
                style="width: 79%;"
              /></span>
              <span class="cc-tag cc-pass">preserved ✓</span>
            </div>
            <div class="cc-note">
              validated versions publish automatically
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── A/B testing ─────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            A/B testing
          </p>
          <h2>Prove it <em>in production</em></h2>
          <p class="section-sub">
            Functional validation proves that a model can serve; real usage shows
            whether it is better. Two ready-made experiments let you find out: put the
            model up against a simpler heuristic, or a new model version up
            against the current one. Each splits traffic by a feature flag and
            measures engagement and positive feedback.
          </p>
          <ul class="point-list">
            <li>Experiments start switched off — you provision them, then turn them on when you're ready.</li>
            <li>If a flag is off, missing, or errors, serving quietly falls back to the production default.</li>
          </ul>
        </div>
        <div class="ab-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">engine experiment · 50 / 50</span>
          </div>
          <div class="ab-body">
            <div class="ab-arm">
              <div class="ab-head">
                <span class="ab-name">Arm A — the model</span>
                <span class="ab-pct">50%</span>
              </div>
              <span class="ab-bar"><span
                class="ab-fill"
                style="width: 50%;"
              /></span>
            </div>
            <div class="ab-arm">
              <div class="ab-head">
                <span class="ab-name">Arm B — heuristic</span>
                <span class="ab-pct">50%</span>
              </div>
              <span class="ab-bar"><span
                class="ab-fill ab-b"
                style="width: 50%;"
              /></span>
            </div>
            <div class="ab-goals">
              <span class="ab-goal">goal · engagement</span>
              <span class="ab-goal">goal · positive feedback</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverRecommendationsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Two models ──────────────────────────────── */

.models-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.model-card {
  padding: 28px 26px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.model-icon {
  width: 38px;
  height: 38px;
  border-radius: var(--r-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
  margin-bottom: 16px;
}

.model-card h3 {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.01em;
  margin: 0 0 10px;
}

.model-lead {
  font-size: 14px;
  line-height: 1.65;
  color: var(--fg-1);
  margin: 0 0 16px;
}

.model-points {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.model-points li {
  position: relative;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-2);
}

.model-points li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 7px;
  width: 9px;
  height: 9px;
  background: var(--accent);
  clip-path: polygon(50% 0, 100% 25%, 100% 75%, 50% 100%, 0 75%, 0 25%);
  opacity: 0.9;
}

/* ── Shared window ───────────────────────────── */

.gate-window,
.ab-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Champion vs challenger ──────────────────── */

.gate-body {
  padding: 24px 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.cc-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.cc-label {
  width: 88px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--fg-1);
}

.cc-bar {
  flex: 1;
  height: 10px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--fg-3) 15%, transparent);
  overflow: hidden;
}

.cc-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: color-mix(in srgb, var(--fg-3) 45%, transparent);
}

.cc-fill.cc-win {
  background: var(--accent);
}

.cc-tag {
  width: 78px;
  flex-shrink: 0;
  text-align: right;
  font-family: var(--font-mono);
  font-size: 10.5px;
}

.cc-live {
  color: var(--fg-3);
}

.cc-pass {
  color: var(--accent);
}

.cc-note {
  font-size: 11.5px;
  color: var(--fg-3);
  text-align: center;
  margin-top: 2px;
}

/* ── A/B experiment ──────────────────────────── */

.ab-body {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ab-arm {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ab-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.ab-name {
  font-size: 13px;
  color: var(--fg-1);
}

.ab-pct {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
}

.ab-bar {
  height: 10px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--fg-3) 15%, transparent);
  overflow: hidden;
}

.ab-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--accent);
}

.ab-fill.ab-b {
  background: color-mix(in srgb, var(--accent) 45%, var(--fg-3));
}

.ab-goals {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 2px;
}

.ab-goal {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 3px 9px;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 820px) {
  .models-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
