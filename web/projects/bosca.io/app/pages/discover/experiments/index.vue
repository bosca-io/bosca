<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Experiments — Feature flags and A/B tests, one system',
  description: 'Bosca Experiments is one system for feature flags and A/B tests: typed flags with deterministic targeting, experiments that measure one variation against another, automated rollouts with guardrails, and results computed from your own analytics warehouse.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Experiments', path: '/discover/experiments' }
], '/og-experiments.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Flag it',
    body: 'Wrap any change in a typed flag — boolean, percentage, string, or JSON — with ordered targeting rules that decide who sees which variation. Everyone is bucketed deterministically, so a person always gets the same answer.'
  },
  {
    step: '02',
    title: 'Test it',
    body: 'Point an experiment at one of the flag\'s rules and it measures one variation against another. Assignment is sticky per person, and exclusion layers keep overlapping experiments from colliding.'
  },
  {
    step: '03',
    title: 'Decide & roll out',
    body: 'Results come straight from your analytics with real statistics and a clear ship / don\'t-ship call. A rollout policy then ramps the winner step by step, halting the moment a guardrail regresses.'
  }
]

const FEATURES = [
  {
    icon: 'flag',
    title: 'Typed flags',
    body: 'Boolean, percentage, string, or JSON variations — a palette of typed values, each validated against the flag\'s type, with one marked default.'
  },
  {
    icon: 'filter',
    title: 'Ordered targeting',
    body: 'Rules evaluated top to bottom, with conditions on segments, principals, profile and device attributes, and even other flags.'
  },
  {
    icon: 'fingerprint',
    title: 'Deterministic bucketing',
    body: 'A stable hash of the user plus a per-flag salt places everyone in the same variation every time. Regenerate the salt to reshuffle.'
  },
  {
    icon: 'pin',
    title: 'Sticky assignment',
    body: 'Each person — a signed-in principal or an anonymous install — keeps the same variation across sessions and devices.'
  },
  {
    icon: 'layers',
    title: 'Exclusion layers',
    body: 'Group experiments so a user enrolled in one is held out of the others, keeping concurrent tests from interfering.'
  },
  {
    icon: 'trending-up',
    title: 'Automated rollouts',
    body: 'Manual, scheduled, or data-adaptive ramps advance the winning variation step by step toward full traffic.'
  },
  {
    icon: 'shield-check',
    title: 'Guardrails that halt',
    body: 'Guardrail goals block a ship — or halt a rollout in progress — the moment they regress past the threshold you set.'
  },
  {
    icon: 'scale',
    title: 'Real statistics',
    body: 'Frequentist or Bayesian analysis over your analytics warehouse, with lift, confidence, CUPED variance reduction, and sample-ratio checks.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="experiments"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Experiments
        </p>
        <h1 class="load-2">
          Ship behind a flag.<br>
          <em>Prove it with data.</em>
        </h1>
        <p class="hero-sub load-3">
          One system for feature flags and A/B tests — typed flags with
          deterministic targeting, experiments that measure the difference,
          automated rollouts with guardrails, and results computed from your
          own analytics.
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
        <div class="exp-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">experiment · checkout-cta</span>
          </div>
          <div class="exp-body">
            <div class="exp-var">
              <span class="var-dot control" />
              <span class="var-name">control</span>
              <span class="var-rate">4.8%</span>
            </div>
            <div class="exp-var win">
              <span class="var-dot treat" />
              <span class="var-name">treatment</span>
              <span class="var-rate">5.6%</span>
            </div>
            <div class="exp-stats">
              <div class="exp-stat">
                <span>lift</span><strong class="pos">+16.7%</strong>
              </div>
              <div class="exp-stat">
                <span>confidence</span><strong>98%</strong>
              </div>
            </div>
            <div class="exp-verdict">
              <Icon
                name="circle-check"
                :size="15"
              />
              SHIP
            </div>
          </div>
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
        <h2>Flag it, test it, <em>decide.</em></h2>
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
          <h2>Release control <em>and accurate measurement</em></h2>
          <p class="section-sub">
            Everything you need to change software safely — typed flags, precise
            targeting, and gradual rollouts — plus the statistics to know
            whether a change actually worked.
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

    <!-- ── One system ──────────────────────────── -->
    <section class="section sys">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            One system
          </p>
          <h2>Flags and experiments, <em>the same machinery</em></h2>
          <p class="section-sub">
            A flag owns the typed variations, the targeting rules, and the
            deterministic bucketing. An experiment is just a measurement lens
            over one of those rules — so what you test is exactly what you ship,
            with no drift between the two.
          </p>
          <ul class="point-list">
            <li>An experiment doesn't own its variants — it measures the variations the flag already serves.</li>
            <li>The same deterministic bucketing decides both who sees a flag and who lands in an experiment.</li>
            <li>Exclusion layers partition traffic so concurrent experiments never contaminate each other.</li>
            <li>Results read from the same analytics warehouse the rest of the platform uses — no separate metrics pipeline.</li>
          </ul>
        </div>
        <div class="sys-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">one flag, one rule</span>
          </div>
          <div class="sys-body">
            <div class="sys-row">
              <span class="sys-icon"><Icon
                name="flag"
                :size="15"
              /></span>
              <span class="sys-text">Flag <code>checkout-cta</code> — variations <code>control</code>, <code>treatment</code></span>
            </div>
            <div class="sys-row">
              <span class="sys-icon"><Icon
                name="filter"
                :size="15"
              /></span>
              <span class="sys-text">Rule <code>mobile-users</code> — 50 / 50 rollout</span>
            </div>
            <div class="sys-row">
              <span class="sys-icon"><Icon
                name="beaker"
                :size="15"
              /></span>
              <span class="sys-text">Experiment measures that rule</span>
            </div>
            <div class="sys-row">
              <span class="sys-icon"><Icon
                name="trending-up"
                :size="15"
              /></span>
              <span class="sys-text">Rollout ramps the winner to 100%</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverExperimentsExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#c56bd0"
        class="closing-mark"
      />
      <h2>Change software <em>with confidence</em></h2>
      <p class="section-sub">
        Flags to release safely, experiments to measure accurately, rollouts to
        ramp automatically, and statistics you can trust — all reading from the
        analytics you already collect. The docs cover flags, experiments,
        exclusion layers, rollouts, and results end to end.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.92fr);
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
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
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
  max-width: 480px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

/* ── Experiment window ───────────────────────── */

.exp-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.exp-body {
  padding: 18px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.exp-var {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 15px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.exp-var.win {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  background: color-mix(in srgb, var(--accent) 9%, transparent);
}

.var-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  flex-shrink: 0;
}

.var-dot.control { background: #5b8def; }
.var-dot.treat { background: var(--accent); }

.var-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-1);
}

.var-rate {
  font-size: 15px;
  font-weight: 700;
  color: var(--fg-0);
}

.exp-stats {
  display: flex;
  gap: 10px;
}

.exp-stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 11px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

.exp-stat span {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.exp-stat strong {
  font-size: 17px;
  font-weight: 700;
  color: var(--fg-0);
}

.exp-stat strong.pos {
  color: #34d99a;
}

.exp-verdict {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px;
  border-radius: var(--r-sm);
  background: #34d99a;
  color: #05130c;
  font-family: var(--font-mono);
  font-size: 13.5px;
  font-weight: 700;
  letter-spacing: 0.08em;
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
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  transform: translateY(-2px);
}

.feature-icon {
  width: 30px;
  height: 30px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
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

/* ── One system ──────────────────────────────── */

.sys {
  padding-top: 90px;
}

.sys-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.sys-body {
  padding: 10px 8px;
}

.sys-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
}

.sys-row + .sys-row {
  border-top: 1px solid var(--line);
}

.sys-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
}

.sys-text {
  font-size: 13px;
  color: var(--fg-1);
}

.sys-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
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
}

@media (max-width: 720px) {
  .feature-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
