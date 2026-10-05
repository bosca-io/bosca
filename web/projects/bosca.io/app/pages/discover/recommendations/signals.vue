<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Personalization Signals — What Actually Personalizes a Feed',
  description: 'Personalization signals are admin-defined rules that turn a profile attribute or segment into a typed value the recommender can use — as a model feature, a cohort membership, or both — with no code.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Recommendations', path: '/discover/recommendations' },
  { name: 'Personalization signals', path: '/discover/recommendations/signals' }
], '/og-recommendations.png')

const VALUE_TYPES = [
  { name: 'Categorical', tag: 'one value', body: 'A single label — a favorite topic, a tier. Can also create a cohort membership.' },
  { name: 'Multi-categorical', tag: 'many values', body: 'A set of labels — the topics someone follows. A model feature.' },
  { name: 'Numeric', tag: 'a number', body: 'A measured value, bucketed and normalized before the model sees it.' },
  { name: 'Boolean', tag: 'yes / no', body: 'A binary flag — subscriber or not. Can also create a cohort membership.' }
]
</script>

<template>
  <DiscoverShell section-id="recommendations">
    <section class="page-hero">
      <p class="kicker load-1">
        Personalization signals
      </p>
      <h1 class="load-2">
        What the model <em>knows about a person</em>
      </h1>
      <p class="section-sub load-3">
        A signal is a rule that turns something you already know about a profile
        — an attribute, a segment — into a typed value the recommender can use.
        Signals are how you tell the model what matters, without writing any
        code.
      </p>
    </section>

    <!-- ── What a signal is ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Deriving a value
          </p>
          <h2>Attribute in, <em>signal out</em></h2>
          <p class="section-sub">
            You pick a source — a profile attribute type or a segment — and
            write a short rule that reads the value along with how much to trust
            it. Gate on confidence, gate on whether it's verified, or reshape it
            entirely. A signal that comes back empty simply sits out; it never
            blocks a save.
          </p>
          <ul class="point-list">
            <li>Author the rule in Studio and preview it against a sample before it goes live.</li>
            <li>Attribute-sourced signals are computed and cached on the profile, and recomputed automatically when the attribute changes.</li>
            <li>Change a definition and the platform backfills every profile it touches in the background.</li>
          </ul>
        </div>
        <div class="signal-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">signal · favorite topic</span>
          </div>
          <div class="signal-body">
            <div class="sig-flow">
              <span class="sig-node sig-src">profile attribute<small>topic affinity</small></span>
              <Icon
                name="arrow-right"
                :size="16"
                class="sig-arrow"
              />
              <span class="sig-node sig-rule">rule<small>confidence &gt; 60</small></span>
              <Icon
                name="arrow-right"
                :size="16"
                class="sig-arrow"
              />
              <span class="sig-node sig-out">signal<small>"nature"</small></span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Value types ─────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Typed values
        </p>
        <h2>Four shapes of <em>signal</em></h2>
        <p class="section-sub">
          A signal has a type, and the type decides how the model reads it — and
          whether it can also group people.
        </p>
      </div>
      <div class="value-grid reveal">
        <div
          v-for="v in VALUE_TYPES"
          :key="v.name"
          class="value-card"
        >
          <div class="value-head">
            <h3>{{ v.name }}</h3>
            <span class="value-tag">{{ v.tag }}</span>
          </div>
          <p>{{ v.body }}</p>
        </div>
      </div>
    </section>

    <!-- ── Feature vs cohort ───────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Two jobs
          </p>
          <h2>A feature, <em>a cohort, or both</em></h2>
          <p class="section-sub">
            Every signal can do two things, and you choose which. As a
            <strong>feature</strong>, it feeds the model's picture of a person,
            sharpening their ranking. As a <strong>cohort</strong>, each distinct
            generated value creates an independent membership that powers
            "people like you" — so viewers who share any membership can share
            behavioral recommendations.
          </p>
          <ul class="point-list">
            <li>A viewer can have several memberships, including several learned-interest categories.</li>
            <li>A viewer with no cohort memberships falls back to global co-engagement — never a dead end.</li>
            <li>Each membership label is deterministic and finite, so "people like you" stays stable and bounded.</li>
          </ul>
        </div>
        <div class="role-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">signal roles</span>
          </div>
          <div class="role-body">
            <div class="role-card">
              <span class="role-icon"><Icon
                name="brain"
                :size="15"
              /></span>
              <div>
                <h4>Feature</h4>
                <p>Feeds the model — sharpens this person's ranking.</p>
              </div>
              <span class="role-toggle on" />
            </div>
            <div class="role-card">
              <span class="role-icon"><Icon
                name="target"
                :size="15"
              /></span>
              <div>
                <h4>Cohort</h4>
                <p>Groups similar people for "people like you".</p>
              </div>
              <span class="role-toggle on" />
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Learned interest ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Learned interest
          </p>
          <h2>Behavior becomes <em>a signal too</em></h2>
          <p class="section-sub">
            Signals aren't only about what you already store. When someone rates
            content highly, that interest is written back to their profile as a
            learned attribute — with a confidence that grows the more they repeat
            it, but never reaches certainty. Over time, a person's own behavior
            personalizes their feed.
          </p>
          <ul class="point-list">
            <li>Learned interests are ordinary attributes, so they can feed signals like any other.</li>
            <li>Repeat engagement reinforces confidence; a single rating never dominates.</li>
          </ul>
        </div>
        <div class="learned-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">learned over time</span>
          </div>
          <div class="learned-body">
            <div class="learn-row">
              <span class="learn-topic">Nature</span>
              <span class="learn-bar"><span
                class="learn-fill"
                style="width: 70%;"
              /></span>
              <span class="learn-conf">70</span>
            </div>
            <div class="learn-row">
              <span class="learn-topic">Photography</span>
              <span class="learn-bar"><span
                class="learn-fill"
                style="width: 55%;"
              /></span>
              <span class="learn-conf">55</span>
            </div>
            <div class="learn-row">
              <span class="learn-topic">Long Reads</span>
              <span class="learn-bar"><span
                class="learn-fill"
                style="width: 85%;"
              /></span>
              <span class="learn-conf">85</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverRecommendationsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Signal flow ─────────────────────────────── */

.signal-window,
.role-window,
.learned-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.signal-body {
  padding: 30px 22px;
}

.sig-flow {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.sig-node {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 60%, transparent);
  font-size: 12.5px;
  color: var(--fg-1);
  text-align: center;
}

.sig-node small {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--fg-3);
}

.sig-out {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
}

.sig-out small {
  color: var(--accent);
}

.sig-arrow {
  color: var(--accent);
  flex-shrink: 0;
}

/* ── Value type cards ────────────────────────── */

.value-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.value-card {
  padding: 20px 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.value-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 6px;
  margin-bottom: 9px;
}

.value-head h3 {
  font-size: 14px;
  font-weight: 650;
  margin: 0;
}

.value-tag {
  font-family: var(--font-mono);
  font-size: 9.5px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--accent);
}

.value-card p {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Role cards ──────────────────────────────── */

.role-body {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.role-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.role-icon {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}

.role-card > div {
  flex: 1;
}

.role-card h4 {
  font-size: 14px;
  font-weight: 650;
  margin: 0 0 3px;
}

.role-card p {
  font-size: 12px;
  line-height: 1.5;
  color: var(--fg-2);
  margin: 0;
}

.role-toggle {
  flex-shrink: 0;
  width: 34px;
  height: 20px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--accent) 30%, transparent);
  position: relative;
}

.role-toggle.on {
  background: var(--accent);
}

.role-toggle::after {
  content: '';
  position: absolute;
  top: 2px;
  right: 2px;
  width: 16px;
  height: 16px;
  border-radius: 999px;
  background: var(--bg-0);
}

/* ── Learned interest ────────────────────────── */

.learned-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.learn-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.learn-topic {
  width: 96px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--fg-1);
}

.learn-bar {
  flex: 1;
  height: 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--fg-3) 15%, transparent);
  overflow: hidden;
}

.learn-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--accent);
}

.learn-conf {
  width: 26px;
  flex-shrink: 0;
  text-align: right;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--accent);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 960px) {
  .value-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .value-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .sig-flow {
    flex-direction: column;
  }

  .sig-arrow {
    transform: rotate(90deg);
  }
}
</style>
