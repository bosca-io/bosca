<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'A/B Experiments — Measure one variation against another',
  description: 'A Bosca experiment is a measurement lens over a feature flag\'s targeting rule. Assignment is deterministic and sticky per person, and exclusion layers keep concurrent experiments from interfering with each other.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Experiments', path: '/discover/experiments' },
  { name: 'A/B Experiments', path: '/discover/experiments/ab-tests' }
], '/og-experiments.png')

const STATUSES = ['Draft', 'Running', 'Paused', 'Completed', 'Archived']
</script>

<template>
  <DiscoverShell section-id="experiments">
    <section class="page-hero">
      <p class="kicker load-1">
        A/B Experiments
      </p>
      <h1 class="load-2">
        Measure the <em>difference</em>
      </h1>
      <p class="section-sub load-3">
        An experiment doesn't invent its own variants — it watches a flag's
        targeting rule and measures the variations that rule already serves. So
        the thing you test is exactly the thing you ship, with nothing to
        reconcile between them.
      </p>
    </section>

    <!-- ── A lens on a rule ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            A lens on a rule
          </p>
          <h2>The flag serves, <em>the experiment measures</em></h2>
          <p class="section-sub">
            Attach an experiment to one of a flag's targeting rules and it
            starts measuring. The flag still decides which variation each person
            gets; the experiment simply records exposures and outcomes for the
            variations that rule rolls out.
          </p>
          <ul class="point-list">
            <li>The experiment's variants are the variations its attached rule already serves.</li>
            <li>Point it at the default path instead to measure the flag's fallback.</li>
            <li>No parallel variant list to keep in sync — one source of truth.</li>
          </ul>
        </div>
        <div class="lens-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">experiment · checkout-cta</span>
          </div>
          <div class="lens-body">
            <div class="lens-row">
              <span class="lens-icon"><Icon
                name="flag"
                :size="15"
              /></span>
              <span class="lens-text">Flag <code>checkout-cta</code></span>
            </div>
            <div class="lens-row indent">
              <span class="lens-icon"><Icon
                name="filter"
                :size="15"
              /></span>
              <span class="lens-text">Rule <code>mobile-users</code> — control / treatment</span>
            </div>
            <div class="lens-row indent2">
              <span class="lens-icon accent"><Icon
                name="beaker"
                :size="15"
              /></span>
              <span class="lens-text">Experiment measures this rule</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Sticky assignment ───────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Sticky assignment
          </p>
          <h2>One person, <em>one variation</em></h2>
          <p class="section-sub">
            Assignment is deterministic: the same person always lands in the
            same variation for the life of the experiment. It works for
            signed-in identities and anonymous installs alike, so results
            aren't muddied by someone flip-flopping between versions.
          </p>
          <ul class="point-list">
            <li>Keyed by security principal when signed in, or by installation id when anonymous.</li>
            <li>Computed from a stable hash — consistent across sessions and devices, with no lookup required.</li>
            <li>Stored as an assignment record so exposures line up cleanly with outcomes.</li>
          </ul>
        </div>
        <div class="assign-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">assignment</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">{</span>
  <span class="tok-str">"experimentId"</span><span class="tok-tag">:</span> <span class="tok-str">"exp_9f3c…"</span><span class="tok-tag">,</span>
  <span class="tok-str">"principalId"</span><span class="tok-tag">:</span> <span class="tok-str">"usr_1a2b…"</span><span class="tok-tag">,</span>
  <span class="tok-str">"variationKey"</span><span class="tok-tag">:</span> <span class="tok-str">"treatment"</span><span class="tok-tag">,</span>
  <span class="tok-str">"assignedAt"</span><span class="tok-tag">:</span> <span class="tok-str">"2026-07-18T14:22Z"</span>
<span class="tok-tag">}</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Exclusion layers ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Exclusion layers
          </p>
          <h2>Keep tests <em>from colliding</em></h2>
          <p class="section-sub">
            Run many experiments at once without letting them contaminate each
            other. Put them in a shared exclusion layer and each person falls
            into at most one — once they're bucketed into an experiment, they're
            held out of the rest in that layer.
          </p>
          <ul class="point-list">
            <li>A layer partitions traffic so overlapping experiments never see the same user.</li>
            <li>Layers are independent — enrollment in one says nothing about the others.</li>
            <li>Leave an experiment out of any layer when it's safe to overlap.</li>
          </ul>
        </div>
        <div class="layer-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">layer · checkout</span>
          </div>
          <div class="layer-body">
            <div class="layer-exp">
              <span class="layer-dot a" />
              <span class="layer-name">exp · button-color</span>
              <span class="layer-tag in">enrolled</span>
            </div>
            <div class="layer-exp muted">
              <span class="layer-dot b" />
              <span class="layer-name">exp · cart-copy</span>
              <span class="layer-tag">excluded</span>
            </div>
            <div class="layer-exp muted">
              <span class="layer-dot c" />
              <span class="layer-name">exp · promo-banner</span>
              <span class="layer-tag">excluded</span>
            </div>
            <p class="layer-note">
              one user → one experiment per layer
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Lifecycle ───────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Lifecycle
        </p>
        <h2>From draft <em>to done</em></h2>
        <p class="section-sub">
          An experiment moves through a clear lifecycle — drafted, running while
          it gathers exposures, paused if you need to hold, and completed or
          archived when the call is made.
        </p>
      </div>
      <div class="status-scale reveal">
        <span
          v-for="(s, i) in STATUSES"
          :key="s"
          class="status-step"
          :style="{ '--i': i }"
        >{{ s }}</span>
      </div>
    </section>

    <DiscoverExperimentsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.lens-window,
.assign-window,
.layer-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Lens window ─────────────────────────────── */

.lens-body {
  padding: 12px 10px;
}

.lens-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 12px;
}

.lens-row.indent { padding-left: 34px; }
.lens-row.indent2 { padding-left: 58px; }

.lens-icon {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  color: var(--fg-2);
}

.lens-icon.accent {
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: var(--accent);
}

.lens-text {
  font-size: 13px;
  color: var(--fg-1);
}

.lens-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

/* ── Layer window ────────────────────────────── */

.layer-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.layer-exp {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 15px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.layer-exp.muted {
  opacity: 0.6;
}

.layer-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  flex-shrink: 0;
}

.layer-dot.a { background: var(--accent); }
.layer-dot.b { background: #5b8def; }
.layer-dot.c { background: #e0a23a; }

.layer-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.layer-tag {
  font-family: var(--font-mono);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 9px;
}

.layer-tag.in {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}

.layer-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  margin: 4px 0 0;
  text-align: center;
}

/* ── Status scale ────────────────────────────── */

.status-scale {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.status-step {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 8px 16px;
  background: color-mix(in srgb, var(--accent) calc(3% + var(--i) * 4%), transparent);
}
</style>
