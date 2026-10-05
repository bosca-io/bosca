<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Rollout Policies — Ramp the winner, halt on regression',
  description: 'A Bosca rollout policy advances a winning variation toward full traffic — manually, on a schedule, or automatically as confidence holds — while guardrail goals watch for regressions and halt the ramp the moment one crosses your threshold.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Experiments', path: '/discover/experiments' },
  { name: 'Rollout Policies', path: '/discover/experiments/rollout' }
], '/og-experiments.png')

const MODES = [
  { icon: 'sliders', name: 'Manual', body: 'You advance each step yourself, whenever you\'re ready to widen exposure.' },
  { icon: 'clock', name: 'Scheduled steps', body: 'Steps advance on a timer — a set weight after a set duration.' },
  { icon: 'trending-up', name: 'Adaptive steps', body: 'Each step waits until confidence clears your threshold before advancing.' },
  { icon: 'activity', name: 'Adaptive continuous', body: 'Traffic climbs by a fixed increment as long as the numbers hold up.' }
]

const AUDIT = [
  { badge: 'advanced', tone: 'ok', from: '5%', to: '25%', note: 'confidence 96%' },
  { badge: 'advanced', tone: 'ok', from: '25%', to: '50%', note: 'confidence 97%' },
  { badge: 'held', tone: 'warn', from: '50%', to: '50%', note: 'confidence 92% < 95%' },
  { badge: 'advanced', tone: 'ok', from: '50%', to: '100%', note: 'confidence 98%' },
  { badge: 'completed', tone: 'done', from: '100%', to: '100%', note: 'fully rolled out' }
]
</script>

<template>
  <DiscoverShell section-id="experiments">
    <section class="page-hero">
      <p class="kicker load-1">
        Rollout Policies
      </p>
      <h1 class="load-2">
        Ramp it up. <em>Stop on regression.</em>
      </h1>
      <p class="section-sub load-3">
        A rollout policy advances a winning variation toward full traffic on
        your terms — by hand, on a schedule, or automatically as the numbers
        hold. Guardrails watch the whole way and halt the ramp the moment a key
        metric slips.
      </p>
    </section>

    <!-- ── Modes ───────────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          How it advances
        </p>
        <h2>Four ways to <em>widen exposure</em></h2>
        <p class="section-sub">
          Pick how much control you want to keep. Drive the ramp by hand, let
          the clock drive it, or let the results drive it — in discrete steps or
          one continuous climb.
        </p>
      </div>
      <div class="mode-grid reveal">
        <article
          v-for="m in MODES"
          :key="m.name"
          class="mode-card"
        >
          <span class="mode-icon">
            <Icon
              :name="m.icon"
              :size="16"
            />
          </span>
          <h3>{{ m.name }}</h3>
          <p>{{ m.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── Steps & guardrails ──────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Steps & guardrails
          </p>
          <h2>Widen only <em>when it's safe</em></h2>
          <p class="section-sub">
            A policy is a ladder of weight steps and a set of thresholds. The
            ramp only climbs to the next rung when the treatment clears the
            confidence you require — and a guardrail regression can hold it in
            place or halt it outright.
          </p>
          <ul class="point-list">
            <li>Ordered steps set the weight to serve and how long to hold it.</li>
            <li>A minimum confidence gates each advance so you don't widen on noise.</li>
            <li>Guardrail goals define the regression you won't tolerate — cross it and the ramp halts.</li>
          </ul>
        </div>
        <div class="ramp-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">rollout · checkout-cta</span>
          </div>
          <div class="ramp-body">
            <div class="ramp-step done">
              <span class="ramp-mark" />
              <span class="ramp-pct">5%</span>
              <span class="ramp-bar"><span style="width: 5%" /></span>
            </div>
            <div class="ramp-step done">
              <span class="ramp-mark" />
              <span class="ramp-pct">25%</span>
              <span class="ramp-bar"><span style="width: 25%" /></span>
            </div>
            <div class="ramp-step current">
              <span class="ramp-mark" />
              <span class="ramp-pct">50%</span>
              <span class="ramp-bar"><span style="width: 50%" /></span>
            </div>
            <div class="ramp-step pending">
              <span class="ramp-mark" />
              <span class="ramp-pct">100%</span>
              <span class="ramp-bar"><span style="width: 100%" /></span>
            </div>
            <div class="ramp-foot">
              <Icon
                name="shield-check"
                :size="13"
              />
              guardrail: error rate ≤ +0.5% · min confidence 95%
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Audit trail ─────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Audit trail
          </p>
          <h2>Every move <em>on the record</em></h2>
          <p class="section-sub">
            A rollout records what it did and why. Each advance, hold, halt, and
            completion is logged with the weight before and after and the
            confidence at the time — so an automated ramp is never a black box.
          </p>
          <ul class="point-list">
            <li>Four event kinds capture the ramp: advanced, held, halted, and completed.</li>
            <li>Every entry keeps the before-and-after weight, so you can trace the whole climb.</li>
            <li>A guardrail breach that halts the ramp shows up here with the metric that tripped it.</li>
          </ul>
        </div>
        <div class="audit-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">rollout history</span>
          </div>
          <div class="audit-body">
            <div
              v-for="(a, i) in AUDIT"
              :key="i"
              class="audit-row"
            >
              <span
                class="audit-badge"
                :class="a.tone"
              >{{ a.badge }}</span>
              <span class="audit-move"><code>{{ a.from }}</code> → <code>{{ a.to }}</code></span>
              <span class="audit-note">{{ a.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverExperimentsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Mode cards ──────────────────────────────── */

.mode-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.mode-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.mode-card:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  transform: translateY(-2px);
}

.mode-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
  margin-bottom: 13px;
}

.mode-card h3 {
  font-size: 14.5px;
  font-weight: 650;
  margin: 0 0 7px;
}

.mode-card p {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Shared windows ──────────────────────────── */

.ramp-window,
.audit-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Ramp window ─────────────────────────────── */

.ramp-body {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ramp-step {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ramp-mark {
  width: 11px;
  height: 11px;
  border-radius: 999px;
  flex-shrink: 0;
  border: 2px solid var(--line-2);
}

.ramp-step.done .ramp-mark {
  background: var(--accent);
  border-color: var(--accent);
}

.ramp-step.current .ramp-mark {
  border-color: var(--accent);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--accent) 22%, transparent);
}

.ramp-pct {
  width: 42px;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.ramp-bar {
  flex: 1;
  height: 7px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--fg-3) 16%, transparent);
  overflow: hidden;
}

.ramp-bar span {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--accent);
}

.ramp-step.pending .ramp-bar span {
  background: color-mix(in srgb, var(--fg-3) 30%, transparent);
}

.ramp-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}

/* ── Audit window ────────────────────────────── */

.audit-body {
  padding: 10px 8px;
}

.audit-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 12px;
}

.audit-row + .audit-row {
  border-top: 1px solid var(--line);
}

.audit-badge {
  width: 78px;
  text-align: center;
  flex-shrink: 0;
  font-family: var(--font-mono);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  border-radius: 999px;
  padding: 3px 0;
  border: 1px solid var(--line-2);
  color: var(--fg-2);
}

.audit-badge.ok {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.audit-badge.warn {
  color: #e0a23a;
  border-color: color-mix(in srgb, #e0a23a 40%, transparent);
}

.audit-badge.done {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}

.audit-move {
  flex: 1;
  font-size: 12.5px;
  color: var(--fg-1);
}

.audit-move code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
}

.audit-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 860px) {
  .mode-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .mode-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .audit-note {
    display: none;
  }
}
</style>
