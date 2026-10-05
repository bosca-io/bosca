<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Audience Profiles — One Coherent Record per Person',
  description: 'A Bosca profile carries typed attribute records from many sources, each with its own priority, confidence, and visibility. Profiles link to security principals, one person can hold several, and attribute types take typed editors from the Forms subsystem.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Audience', path: '/discover/audience' },
  { name: 'Profiles', path: '/discover/audience/profiles' }
], '/og-audience.png')

const VISIBILITY = ['System', 'User', 'Friends', 'Friends of friends', 'Public']
</script>

<template>
  <DiscoverShell section-id="audience">
    <section class="page-hero">
      <p class="kicker load-1">
        Profiles
      </p>
      <h1 class="load-2">
        A person is <em>one record</em>
      </h1>
      <p class="section-sub load-3">
        A profile represents one person: a name, a slug, a visibility, and a set
        of typed attribute records drawn from every source you have. It can link
        to a security identity — or stand alone as a record for someone who
        hasn't signed in yet.
      </p>
    </section>

    <!-- ── Attribute records ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Attribute records
          </p>
          <h2>Typed, and <em>traceable</em></h2>
          <p class="section-sub">
            Each piece of what you know about a person is its own record — a
            typed value plus the metadata that says how much to trust it. When
            two sources disagree, both records are kept — each carrying the
            priority and confidence to weigh it by.
          </p>
          <ul class="point-list">
            <li>Every record carries a <strong>source</strong>, a <strong>priority</strong>, a numeric <strong>confidence</strong>, and its own <strong>visibility</strong>.</li>
            <li>A record can expire, so time-limited facts don't linger past their shelf life.</li>
            <li>Records of the same attribute coexist — nothing is overwritten, and each keeps its own provenance.</li>
          </ul>
        </div>
        <div class="rec-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">attribute · location</span>
          </div>
          <div class="rec-body">
            <div class="rec-card">
              <div class="rec-head">
                <span class="rec-val">London</span>
              </div>
              <div class="rec-meta">
                <span>source: signup</span><span>priority: 10</span><span>confidence: 90</span>
              </div>
            </div>
            <div class="rec-card">
              <div class="rec-head">
                <span class="rec-val">United Kingdom</span>
              </div>
              <div class="rec-meta">
                <span>source: import</span><span>priority: 5</span><span>confidence: 40</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Attribute types ─────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Attribute types
          </p>
          <h2>A registry of <em>what a person can carry</em></h2>
          <p class="section-sub">
            An attribute type declares a kind of data a profile can hold — a
            stable, dot-namespaced id, a display name, and a default visibility.
            The data lives on the profile; the type says what shape it takes.
          </p>
          <ul class="point-list">
            <li>Bind a type to a form from the Forms subsystem and the profile page renders a proper typed editor — no raw JSON.</li>
            <li>Its id is stable and dot-namespaced, so data that points at it doesn't break when names change.</li>
            <li>Mark a type protected so only administrators can write its records.</li>
          </ul>
        </div>
        <div class="type-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">attribute type</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">{</span>
  <span class="tok-str">"id"</span><span class="tok-tag">:</span> <span class="tok-str">"bosca.recommendations.learned_interest"</span><span class="tok-tag">,</span>
  <span class="tok-str">"name"</span><span class="tok-tag">:</span> <span class="tok-str">"Learned Interest"</span><span class="tok-tag">,</span>
  <span class="tok-str">"visibility"</span><span class="tok-tag">:</span> <span class="tok-str">"system"</span><span class="tok-tag">,</span>
  <span class="tok-str">"protected"</span><span class="tok-tag">:</span> <span class="tok-kt">true</span>
<span class="tok-tag">}</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Identity ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Identity
          </p>
          <h2>One person, <em>many profiles</em></h2>
          <p class="section-sub">
            A profile can link to a security principal — the sign-in identity
            that carries credentials, groups, and devices. One principal can own
            several profiles, with a primary one returned at sign-in, so a person
            can wear different hats without a second account.
          </p>
          <ul class="point-list">
            <li>A profile with no principal is still a first-class record — an imported contact, a pending invite.</li>
            <li>Everything a person touches — the orgs and communities they're in, the segments they fall into — hangs off this one profile.</li>
          </ul>
        </div>
        <div class="id-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">principal ↔ profiles</span>
          </div>
          <div class="id-body">
            <div class="id-principal">
              <span class="id-icon"><Icon
                name="fingerprint"
                :size="15"
              /></span>
              <span>one principal</span>
            </div>
            <div class="id-links">
              <span class="id-profile"><Icon
                name="user"
                :size="13"
              /> Ada — reader <span class="id-primary">primary</span></span>
              <span class="id-profile"><Icon
                name="user"
                :size="13"
              /> Ada — org admin</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Visibility ──────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Visibility
        </p>
        <h2>Set where the <em>data lives</em></h2>
        <p class="section-sub">
          A profile and each individual record carry their own visibility, so a
          public display name and a system-only note sit on the same person
          without leaking.
        </p>
      </div>
      <div class="vis-scale reveal">
        <span
          v-for="(v, i) in VISIBILITY"
          :key="v"
          class="vis-step"
          :style="{ '--i': i }"
        >{{ v }}</span>
      </div>
    </section>

    <DiscoverAudienceExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.rec-window,
.type-window,
.id-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Attribute records ───────────────────────── */

.rec-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.rec-card {
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 14px 16px;
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.rec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.rec-val {
  font-size: 15px;
  font-weight: 650;
  color: var(--fg-0);
}

.rec-meta {
  display: flex;
  gap: 14px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Identity window ─────────────────────────── */

.id-body {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.id-principal {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  font-size: 13.5px;
  color: var(--fg-1);
}

.id-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.id-links {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-left: 22px;
}

.id-profile {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  font-size: 13px;
  color: var(--fg-1);
}

.id-primary {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 3px;
  padding: 1px 5px;
}

/* ── Visibility scale ────────────────────────── */

.vis-scale {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.vis-step {
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
  .rec-meta {
    flex-direction: column;
    gap: 4px;
  }
}
</style>
