<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Audience — The people layer on Bosca',
  description: 'Bosca Audience models people as profiles with multi-source typed attributes, groups them into organizations and communities, and addresses them with static and analytics-backed segments — the precise audiences campaigns, recommendations, and personalization run on.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Audience', path: '/discover/audience' }
], '/og-audience.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Know each person',
    body: 'A profile carries typed attribute records from every source you have — each with its own confidence, priority, and visibility — so a person is one profile carrying traceable data, not a pile of anonymous rows.'
  },
  {
    step: '02',
    title: 'Organize them',
    body: 'Organize people into organizations and communities, then define segments — static lists you curate, or dynamic audiences whose membership is computed from an analytics query on a schedule.'
  },
  {
    step: '03',
    title: 'Serve them',
    body: 'Those segments are how the rest of the platform serves each person well — the campaigns Communications delivers, the recommendations they see, and the personalization that shapes their experience.'
  }
]

const FEATURES = [
  {
    icon: 'user',
    title: 'Multi-source profiles',
    body: 'A profile gathers typed attribute records from many sources, each carrying a source, a priority, a confidence weight, and a visibility — so you can weigh competing values by how much to trust each.'
  },
  {
    icon: 'filter',
    title: 'Dynamic segments',
    body: 'A segment can be a static list you curate — or a dynamic audience whose membership is computed from an analytics query and refreshed on a schedule.'
  },
  {
    icon: 'fingerprint',
    title: 'One person, many roles',
    body: 'A security principal can own several profiles, with a primary one returned at sign-in — and a profile without a principal is still a first-class record.'
  },
  {
    icon: 'eye',
    title: 'Fine-grained visibility',
    body: 'A profile and each individual attribute record carry their own visibility — public, friends, user, or system — so privacy is set where the data lives.'
  },
  {
    icon: 'list-checks',
    title: 'Typed attribute registry',
    body: 'Attribute types declare the shapes a profile can carry — and a type can bind a form, so editing is a real editor rather than raw JSON.'
  },
  {
    icon: 'building',
    title: 'Organizations & signup',
    body: 'Group people into organizations with members and managers, and admit new signups by domain, by specific email, or by a token you hand out.'
  },
  {
    icon: 'users',
    title: 'Communities',
    body: 'Small groups, families, and custom groups carry members, activities, and channels — public, private, or hidden, with invite tokens of their own.'
  },
  {
    icon: 'sparkles',
    title: 'Feeds the platform',
    body: 'The same profiles and segments power recommendations and personalization, feed the campaigns Communications delivers, and build on the analytics your apps already produce.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="audience"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Audience
        </p>
        <h1 class="load-2">
          Know your people.<br>
          <em>Serve them well.</em>
        </h1>
        <p class="hero-sub load-3">
          Audience is the platform's people layer. Model each person as a
          profile with multi-source typed attributes, organize them into
          organizations and communities, and define segments — the audiences
          the rest of the platform serves.
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
        <div class="flow-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">profile → segment</span>
          </div>
          <div class="flow-body">
            <div class="flow-node fn-profile">
              <span class="fn-icon"><Icon
                name="user"
                :size="15"
              /></span>
              <span class="fn-text">
                <span class="fn-name">Ada Lovelace</span>
                <span class="fn-meta">topic: nature · conf 70</span>
              </span>
            </div>
            <div class="flow-arrow">
              <Icon
                name="chevron-down"
                :size="15"
              />
            </div>
            <div class="flow-node fn-segment">
              <span class="fn-icon"><Icon
                name="filter"
                :size="15"
              /></span>
              <span class="fn-text">
                <span class="fn-name">Active nature readers</span>
                <span class="fn-meta">dynamic · 4,812 members</span>
              </span>
            </div>
            <div class="flow-foot">
              <Icon
                name="arrow-right"
                :size="13"
              />
              feeds campaigns, recommendations &amp; personalization
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
        <h2>Know them, organize them, <em>serve them.</em></h2>
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
          <h2>People, <em>not just rows</em></h2>
          <p class="section-sub">
            One coherent profile per person, audiences that keep themselves
            current, and the same records feeding analytics, recommendations,
            and communications.
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

    <!-- ── Attributes ──────────────────────────── -->
    <section class="section attrs">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            One coherent record
          </p>
          <h2>Many sources, <em>one profile</em></h2>
          <p class="section-sub">
            Real people leave data in many places — a signup form, an import, a
            behavioral signal, a support tool. A Bosca profile keeps every one
            as a typed attribute record with its own source, priority, and
            confidence, so you can tell what's most trustworthy, not just what
            wrote last.
          </p>
          <p class="section-sub">
            Each record has its own visibility, too — so a public display name
            and a system-only note can sit on the same profile without leaking.
          </p>
        </div>
        <div class="attr-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">profile · attributes</span>
          </div>
          <div class="attr-body">
            <div class="attr-row">
              <span class="attr-key">topic affinity</span>
              <span class="attr-val">nature</span>
              <span class="attr-meta">learned · 70</span>
            </div>
            <div class="attr-row">
              <span class="attr-key">location</span>
              <span class="attr-val">London</span>
              <span class="attr-meta">signup · 90</span>
            </div>
            <div class="attr-row attr-dim">
              <span class="attr-key">location</span>
              <span class="attr-val">UK</span>
              <span class="attr-meta">import · 40</span>
            </div>
            <div class="attr-foot">
              priority + confidence tell you which to trust
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverAudienceExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#da9e30"
        class="closing-mark"
      />
      <h2>Audience ships <em>with Bosca</em></h2>
      <p class="section-sub">
        The people your platform already knows become profiles and
        segments without a separate CDP — and the same records feed
        recommendations, personalization, and the campaigns Communications
        delivers. The docs cover profiles, organizations, community, and
        segments end to end.
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

/* ── Flow window ─────────────────────────────── */

.flow-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 10%, transparent);
}

.flow-body {
  padding: 22px 20px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
}

.flow-node {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.fn-icon {
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

.fn-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.fn-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg-0);
}

.fn-meta {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.flow-arrow {
  display: flex;
  justify-content: center;
  color: var(--accent);
  padding: 2px 0;
}

.flow-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding: 12px 16px;
  border-top: 1px dashed var(--line-2);
  font-family: var(--font-mono);
  font-size: 11px;
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

/* ── Attributes ──────────────────────────────── */

.attrs {
  padding-top: 90px;
}

.attr-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.attr-body {
  padding: 10px 8px;
}

.attr-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}

.attr-row + .attr-row {
  border-top: 1px solid var(--line);
}

.attr-key {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-3);
}

.attr-val {
  font-size: 13px;
  color: var(--fg-0);
  font-weight: 550;
}

.attr-meta {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
  min-width: 78px;
  text-align: right;
}

.attr-dim {
  opacity: 0.45;
}

.attr-dim .attr-meta {
  color: var(--fg-3);
}

.attr-foot {
  padding: 12px 14px 8px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  text-align: right;
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
