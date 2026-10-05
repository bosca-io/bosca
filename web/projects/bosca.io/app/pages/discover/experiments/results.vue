<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Results & Analysis — Real statistics, a clear verdict',
  description: 'Bosca reads experiment results from your own analytics warehouse and turns them into a per-variation breakdown with lift and confidence, a ship / don\'t-ship recommendation, and rigorous statistics — frequentist or Bayesian, with CUPED variance reduction and sample-ratio checks.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Experiments', path: '/discover/experiments' },
  { name: 'Results & Analysis', path: '/discover/experiments/results' }
], '/og-experiments.png')

const ROLES = [
  { icon: 'target', name: 'Primary', body: 'The metric the ship decision hangs on. Move it, and the verdict follows.' },
  { icon: 'list-checks', name: 'Secondary', body: 'Measured for context and reported alongside — but it doesn\'t drive the call.' },
  { icon: 'shield-check', name: 'Guardrail', body: 'A metric that must not regress. If it slips, it can veto an otherwise-positive result.' }
]
</script>

<template>
  <DiscoverShell section-id="experiments">
    <section class="page-hero">
      <p class="kicker load-1">
        Results & Analysis
      </p>
      <h1 class="load-2">
        The verdict, <em>with the math</em>
      </h1>
      <p class="section-sub load-3">
        Results come straight from the analytics you already collect — turned
        into a per-variation breakdown, a statistical read on the difference,
        and a plain recommendation: ship, keep running, or don't.
      </p>
    </section>

    <!-- ── The scoreboard ──────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The scoreboard
          </p>
          <h2>Every variation, <em>side by side</em></h2>
          <p class="section-sub">
            Each variation gets its own line — impressions, conversions,
            conversion rate, the lift over control, and the confidence behind
            it. The control is picked consistently, so the comparison is always
            anchored the same way.
          </p>
          <ul class="point-list">
            <li>Lift and confidence are computed against the control variation.</li>
            <li>A verdict banner turns the numbers into a call you can act on.</li>
            <li>Guardrail goals can flip a positive result to don't-ship on their own.</li>
          </ul>
        </div>
        <div class="res-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">results · checkout-cta</span>
          </div>
          <div class="res-body">
            <div class="res-head">
              <span class="rc-name">variation</span>
              <span class="rc-num">rate</span>
              <span class="rc-num">lift</span>
              <span class="rc-num">conf</span>
            </div>
            <div class="res-row">
              <span class="rc-name"><span class="rc-dot control" />control</span>
              <span class="rc-num">4.8%</span>
              <span class="rc-num dim">—</span>
              <span class="rc-num dim">—</span>
            </div>
            <div class="res-row win">
              <span class="rc-name"><span class="rc-dot treat" />treatment</span>
              <span class="rc-num">5.6%</span>
              <span class="rc-num pos">+16.7%</span>
              <span class="rc-num">98%</span>
            </div>
            <div class="res-verdict">
              <Icon
                name="circle-check"
                :size="14"
              />
              SHIP
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Goals ───────────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Goals
        </p>
        <h2>Not every metric <em>gets a vote</em></h2>
        <p class="section-sub">
          A goal is either a rate — each converting person counted once — or a
          count, averaged per person. What a goal does with its result depends
          on the role you give it.
        </p>
      </div>
      <div class="role-grid reveal">
        <article
          v-for="r in ROLES"
          :key="r.name"
          class="role-card"
        >
          <span class="role-icon">
            <Icon
              :name="r.icon"
              :size="16"
            />
          </span>
          <h3>{{ r.name }}</h3>
          <p>{{ r.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── The statistics ──────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            The statistics
          </p>
          <h2>Rigor <em>you can trust</em></h2>
          <p class="section-sub">
            The numbers are computed properly, not eyeballed. Analysis runs
            frequentist by default or Bayesian when you want posteriors — and it
            reads from the same analytics warehouse the rest of the platform
            queries, so there's no separate metrics pipeline to keep honest.
          </p>
          <ul class="point-list">
            <li>Rates use a proportions test; per-person counts use a means test — each metric matched to its math.</li>
            <li>CUPED variance reduction tightens count-based goals using pre-experiment data.</li>
            <li>A sample-ratio check flags bucketing bugs before you trust a result.</li>
            <li>Optional AI insights add a hypothesis read and follow-up ideas on top of — never instead of — the numbers.</li>
          </ul>
        </div>
        <div class="stat-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">analysis</span>
          </div>
          <div class="stat-body">
            <div class="stat-row">
              <span class="stat-key">method</span>
              <span class="stat-val">frequentist <span class="stat-alt">· Bayesian optional</span></span>
            </div>
            <div class="stat-row">
              <span class="stat-key">variance</span>
              <span class="stat-val">CUPED <span class="pos">−38%</span></span>
            </div>
            <div class="stat-row">
              <span class="stat-key">sample ratio</span>
              <span class="stat-val"><span class="pos">pass</span> · no SRM</span>
            </div>
            <div class="stat-row">
              <span class="stat-key">source</span>
              <span class="stat-val">analytics warehouse</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverExperimentsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.res-window,
.stat-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Results table ───────────────────────────── */

.res-body {
  padding: 14px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.res-head,
.res-row {
  display: grid;
  grid-template-columns: 1.6fr 0.8fr 0.9fr 0.7fr;
  align-items: center;
  gap: 6px;
  padding: 10px 10px;
}

.res-head {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.res-row {
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.res-row.win {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  background: color-mix(in srgb, var(--accent) 9%, transparent);
}

.rc-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.rc-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
}

.rc-dot.control { background: #5b8def; }
.rc-dot.treat { background: var(--accent); }

.rc-num {
  text-align: right;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.rc-num.dim { color: var(--fg-3); }
.rc-num.pos { color: #34d99a; }

.res-verdict {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 8px;
  padding: 11px;
  border-radius: var(--r-sm);
  background: #34d99a;
  color: #05130c;
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

/* ── Role cards ──────────────────────────────── */

.role-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.role-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.role-icon {
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

.role-card h3 {
  font-size: 15px;
  font-weight: 650;
  margin: 0 0 7px;
}

.role-card p {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Stat window ─────────────────────────────── */

.stat-body {
  padding: 10px 8px;
}

.stat-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 14px;
}

.stat-row + .stat-row {
  border-top: 1px solid var(--line);
}

.stat-key {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
}

.stat-val {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.stat-alt {
  color: var(--fg-3);
}

.stat-val .pos {
  color: #34d99a;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 760px) {
  .role-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
