<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Feature Flags — Typed flags with deterministic targeting',
  description: 'A Bosca feature flag is a palette of typed variations — boolean, percentage, string, or JSON — served through ordered targeting rules and deterministic per-user bucketing, so the right people get the right variation, every time.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Experiments', path: '/discover/experiments' },
  { name: 'Feature Flags', path: '/discover/experiments/feature-flags' }
], '/og-experiments.png')

const CONDITIONS = [
  { icon: 'users', name: 'Segment', body: 'Match anyone in a given audience segment.' },
  { icon: 'fingerprint', name: 'Principal', body: 'Match specific signed-in identities.' },
  { icon: 'user', name: 'Profile attribute', body: 'Match on any attribute a profile carries.' },
  { icon: 'smartphone', name: 'Device attribute', body: 'Match on platform, version, or locale.' },
  { icon: 'flag', name: 'Flag dependency', body: 'Match on how another flag resolved.' }
]

const STATUSES = ['Draft', 'Enabled', 'Disabled', 'Archived']
</script>

<template>
  <DiscoverShell section-id="experiments">
    <section class="page-hero">
      <p class="kicker load-1">
        Feature Flags
      </p>
      <h1 class="load-2">
        One flag, <em>many answers</em>
      </h1>
      <p class="section-sub load-3">
        A feature flag is a palette of typed values and the rules that decide
        who gets which one. Wrap a change in a flag and you control its release
        without shipping again — for everyone, a segment, or a single person.
      </p>
    </section>

    <!-- ── Typed variations ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Typed variations
          </p>
          <h2>A palette of <em>typed values</em></h2>
          <p class="section-sub">
            Every flag has a type, and its variations are values of that type —
            validated when you save, so a boolean flag can't accidentally serve
            a string. One variation is marked the default for anyone no rule
            matches.
          </p>
          <ul class="point-list">
            <li>Four flag types: <strong>boolean</strong>, <strong>percentage</strong>, <strong>string</strong>, and <strong>JSON</strong>.</li>
            <li>Each variation is a named value in the flag's palette, referenced by key.</li>
            <li>A default variation catches everyone a targeting rule doesn't.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">flag · checkout-cta</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">{</span>
  <span class="tok-str">"key"</span><span class="tok-tag">:</span> <span class="tok-str">"checkout-cta"</span><span class="tok-tag">,</span>
  <span class="tok-str">"type"</span><span class="tok-tag">:</span> <span class="tok-str">"string"</span><span class="tok-tag">,</span>
  <span class="tok-str">"variations"</span><span class="tok-tag">:</span> <span class="tok-tag">{</span>
    <span class="tok-str">"control"</span><span class="tok-tag">:</span> <span class="tok-str">"Buy now"</span><span class="tok-tag">,</span>
    <span class="tok-str">"treatment"</span><span class="tok-tag">:</span> <span class="tok-str">"Add to cart"</span>
  <span class="tok-tag">}</span><span class="tok-tag">,</span>
  <span class="tok-str">"defaultVariationKey"</span><span class="tok-tag">:</span> <span class="tok-str">"control"</span>
<span class="tok-tag">}</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Targeting rules ─────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Targeting rules
          </p>
          <h2>Ordered rules, <em>first match wins</em></h2>
          <p class="section-sub">
            A flag carries an ordered list of targeting rules, evaluated top to
            bottom. The first rule whose conditions all match decides the
            variation — and a rule can split its matched traffic across
            variations by weight.
          </p>
          <ul class="point-list">
            <li>Conditions combine five kinds of match, from broad segments to a single flag's result.</li>
            <li>A matched rule can serve one variation or roll traffic across several by weight.</li>
            <li>Reorder rules to change precedence — the list is the logic.</li>
          </ul>
        </div>
        <div class="cond-grid">
          <div
            v-for="c in CONDITIONS"
            :key="c.name"
            class="cond-card"
          >
            <span class="cond-icon">
              <Icon
                :name="c.icon"
                :size="15"
              />
            </span>
            <div>
              <h3>{{ c.name }}</h3>
              <p>{{ c.body }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Deterministic bucketing ─────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Deterministic bucketing
          </p>
          <h2>The same answer, <em>every time</em></h2>
          <p class="section-sub">
            When a rule splits traffic, who lands where isn't random. A stable
            hash of the user and a per-flag salt maps everyone to a bucket, so a
            person sees the same variation on every visit — until you
            regenerate the salt to reshuffle.
          </p>
          <ul class="point-list">
            <li>Bucketing is computed from the user plus the flag's salt — no assignment table needed to stay consistent.</li>
            <li>The same hash powers experiments, so a test and its flag agree on who's where.</li>
            <li>Regenerate the salt to re-randomize the split for a fresh run.</li>
          </ul>
        </div>
        <div class="bucket-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">bucketing</span>
          </div>
          <div class="bucket-body">
            <div class="bucket-in">
              <Icon
                name="fingerprint"
                :size="14"
              />
              <code>user · salt</code>
            </div>
            <div class="bucket-arrow">
              <Icon
                name="chevron-down"
                :size="14"
              />
            </div>
            <div class="bucket-out">
              <span class="bucket-seg control">control · 50%</span>
              <span class="bucket-seg treat">treatment · 50%</span>
            </div>
            <p class="bucket-note">
              stable hash → the same bucket, every visit
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
        <h2>From draft <em>to retired</em></h2>
        <p class="section-sub">
          A flag moves through a simple lifecycle — drafted in private, enabled
          into service, disabled to fall back to its default, and archived when
          it has done its job.
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

.code-window,
.bucket-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Condition cards ─────────────────────────── */

.cond-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.cond-card {
  display: flex;
  gap: 13px;
  align-items: center;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.cond-icon {
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

.cond-card h3 {
  font-size: 13.5px;
  font-weight: 650;
  margin: 0 0 3px;
}

.cond-card p {
  font-size: 12px;
  line-height: 1.5;
  color: var(--fg-2);
  margin: 0;
}

/* ── Bucketing window ────────────────────────── */

.bucket-body {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}

.bucket-in {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 12px 18px;
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  color: var(--fg-1);
}

.bucket-in code {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-0);
}

.bucket-arrow {
  color: var(--accent);
}

.bucket-out {
  display: flex;
  gap: 10px;
  width: 100%;
}

.bucket-seg {
  flex: 1;
  text-align: center;
  padding: 11px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  font-family: var(--font-mono);
  font-size: 11.5px;
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.bucket-seg.control { color: #5b8def; }
.bucket-seg.treat { color: var(--accent); }

.bucket-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  margin: 2px 0 0;
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

/* ── Responsive ──────────────────────────────── */

@media (max-width: 560px) {
  .bucket-out {
    flex-direction: column;
  }
}
</style>
