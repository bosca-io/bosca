<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Bosca Studio — One app, every subsystem',
  description: 'Bosca Studio is the admin app for the whole platform. Content, work, analytics, Git, Kubernetes, commerce, and the rest sit behind one sign-in and one shell — with a search that spans your content, people, and work, and Studio Personas that shape the app to each person.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Studio', path: '/discover/studio' }
], '/og-studio.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'What you get', href: '#features' },
  { label: 'Search', href: '#search' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Sign in',
    body: 'One sign-in for the whole platform. Studio opens on a hub that lays out every subsystem you can reach, grouped by what they are for.'
  },
  {
    step: '02',
    title: 'Pick a subsystem',
    body: 'Choose from the switcher or press Cmd/Ctrl+K. The shell takes on that subsystem\'s accent — sidebar, gradient, and brand mark.'
  },
  {
    step: '03',
    title: 'Work',
    body: 'Write a document, run a query, review a pull request, or scale a workload — the same app, the same session, the same navigation.'
  }
]

const FEATURES = [
  {
    icon: 'monitor',
    title: 'One shell',
    body: 'Every subsystem shares one sidebar, one switcher, and one search. The shape never changes as you move.'
  },
  {
    icon: 'layers',
    title: 'A subsystem per job',
    body: 'Content, work, analytics, Git, Kubernetes, commerce and more, each with its own navigation groups.'
  },
  {
    icon: 'users',
    title: 'Studio Personas',
    body: 'A persona is a named set of subsystems on a profile — each person opens the same app and finds their own.'
  },
  {
    icon: 'search',
    title: 'Search & navigate',
    body: 'One keystroke searches documents, collections, profiles, tasks, and specs, plus every place you can navigate to.'
  },
  {
    icon: 'zap',
    title: 'Start new work',
    body: 'Begin a document, task, spec, form, experiment, dashboard, repository, agent, or pipeline from the same input.'
  },
  {
    icon: 'edit',
    title: 'Edit together, live',
    body: 'Two people in the same record see each other\'s named cursors — on content and Work Ops alike — with a chat dock that follows you.'
  },
  {
    icon: 'color',
    title: 'An accent per subsystem',
    body: 'Each subsystem carries its own color through the ambient gradient, the sidebar, and the brand mark.'
  },
  {
    icon: 'server',
    title: 'Real operator surfaces',
    body: 'Install and upgrade Helm releases, and watch clusters stream live, in the app you write content in.'
  }
]

const NAV_ITEMS = ['Collections', 'Documents', 'Media', 'Templates']

const RESULTS = [
  { group: 'Actions', label: 'New document', kind: 'CMS' },
  { group: 'Navigate', label: 'Work Ops · Sprints', kind: 'Go' },
  { group: 'Content', label: 'Launch checklist', kind: 'Doc' },
  { group: 'People', label: 'Dana Whitfield', kind: 'Profile' }
]

const PERSONAS = [
  { name: 'Editor', subs: 'CMS · Localization · Calendar' },
  { name: 'Analyst', subs: 'Analytics · Experiments' },
  { name: 'Operator', subs: 'Kubernetes · System · Git' }
]
</script>

<template>
  <DiscoverShell
    section-id="studio"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Studio
        </p>
        <h1 class="load-2">
          One app,<br>
          <em>every subsystem.</em>
        </h1>
        <p class="hero-sub load-3">
          Studio is the admin app for the whole Bosca platform. Content, work,
          analytics, Git, Kubernetes, commerce and the rest sit behind one
          sign-in and one shell that tints itself to whichever subsystem you're
          in.
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
        <div class="shell-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">studio</span>
          </div>
          <div class="shell-body">
            <div class="shell-rail">
              <div class="rail-brand">
                <BoscaMark :size="16" />
                <span>Bosca</span>
              </div>
              <div class="rail-sub">
                <span class="rail-icon"><Icon
                  name="library"
                  :size="13"
                /></span>
                <span class="rail-sub-text">
                  <span class="rail-sub-name">CMS</span>
                  <span class="rail-sub-desc">Content</span>
                </span>
              </div>
              <div
                v-for="(item, i) in NAV_ITEMS"
                :key="item"
                class="rail-item"
                :class="{ active: i === 0 }"
              >
                {{ item }}
              </div>
            </div>
            <div class="shell-stage">
              <span class="stage-bar wide" />
              <span class="stage-bar" />
              <span class="stage-bar short" />
              <span class="stage-block" />
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
        <h2>Sign in, pick a subsystem, <em>work.</em></h2>
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
            What you get
          </p>
          <h2>The whole platform, <em>in one place</em></h2>
          <p class="section-sub">
            Studio spans every subsystem Bosca runs and keeps one shape across all
            of them, so moving from a content library to a Kubernetes cluster is
            just a change of subject.
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

    <!-- ── Search ──────────────────────────────── -->
    <section
      id="search"
      class="section"
    >
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            One keystroke
          </p>
          <h2>The whole platform, <em>a search away</em></h2>
          <p class="section-sub">
            Press <code>Cmd/Ctrl+K</code> anywhere in Studio. One input searches
            your documents, collections, and profiles alongside tasks and specs
            from Work Ops — and lists every place you can navigate to, in every
            subsystem you can reach. The same input starts new work.
          </p>
          <ul class="point-list">
            <li>Documents, collections, and profiles, searched live.</li>
            <li>Tasks and specs from Work Ops, in the same results.</li>
            <li>Every nav destination across the subsystems you can reach.</li>
            <li>Start a document, task, experiment, or pipeline from the input.</li>
          </ul>
        </div>
        <div class="pal-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">⌘K</span>
          </div>
          <div class="pal-body">
            <div class="pal-input">
              <Icon
                name="search"
                :size="13"
              />
              <span>launch</span>
            </div>
            <div
              v-for="r in RESULTS"
              :key="r.label"
              class="pal-row"
            >
              <span class="pal-group">{{ r.group }}</span>
              <span class="pal-label">{{ r.label }}</span>
              <span class="pal-kind">{{ r.kind }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Personas ────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Studio Personas
          </p>
          <h2>Everyone opens <em>their own Studio</em></h2>
          <p class="section-sub">
            A Studio Persona is a named set of subsystems attached to a profile. An
            editor, an analyst, and an operator sign into the same app and each
            find the subsystems their work needs — the sidebar, the switcher, and
            the search all follow from it.
          </p>
          <ul class="point-list">
            <li>A persona is a named set of subsystems on a profile.</li>
            <li>Carry several, and their subsystems combine.</li>
            <li>Shapes the sidebar, the switcher, and the search results.</li>
            <li>Administrators see the whole platform.</li>
          </ul>
        </div>
        <div class="per-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">personas</span>
          </div>
          <div class="per-body">
            <div
              v-for="p in PERSONAS"
              :key="p.name"
              class="per-row"
            >
              <span class="per-name">{{ p.name }}</span>
              <span class="per-subs">{{ p.subs }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverStudioExplore title="Go deeper" />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#4fe3a6"
        class="closing-mark"
      />
      <h2>One app for <em>all of it</em></h2>
      <p class="section-sub">
        Write a document and scale a cluster without leaving the app — every
        subsystem Bosca runs, behind one sign-in, one search, and one shell.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.95fr);
  align-items: center;
  gap: 48px;
  max-width: 1140px;
  margin: 0 auto;
  padding: 64px 32px 100px;
}

.hero h1 {
  font-size: clamp(40px, 5.4vw, 62px);
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

/* ── Shell window (hero) ─────────────────────── */

.shell-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.shell-body {
  display: grid;
  grid-template-columns: 152px minmax(0, 1fr);
  min-height: 230px;
}

.shell-rail {
  border-right: 1px solid var(--line);
  background: color-mix(in srgb, var(--accent) 5%, transparent);
  padding: 12px 10px;
}

.rail-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  font-weight: 700;
  color: var(--fg-1);
  padding: 2px 6px 12px;
}

.rail-sub {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 6px;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  margin-bottom: 10px;
}

.rail-icon {
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
}

.rail-sub-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.rail-sub-name {
  font-size: 12px;
  font-weight: 650;
  color: var(--fg-0);
}

.rail-sub-desc {
  font-size: 9.5px;
  color: var(--fg-3);
}

.rail-item {
  position: relative;
  font-size: 11.5px;
  color: var(--fg-2);
  padding: 7px 8px;
  border-radius: var(--r-xs);
}

.rail-item.active {
  color: var(--fg-0);
  background: color-mix(in srgb, var(--accent) 14%, transparent);
}

.rail-item.active::before {
  content: '';
  position: absolute;
  left: -10px;
  top: 6px;
  bottom: 6px;
  width: 2px;
  border-radius: 999px;
  background: var(--accent);
  box-shadow: 0 0 8px var(--accent);
}

.shell-stage {
  padding: 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.stage-bar {
  height: 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--fg-3) 20%, transparent);
  width: 60%;
}

.stage-bar.wide {
  width: 78%;
  background: color-mix(in srgb, var(--accent) 30%, transparent);
}

.stage-bar.short { width: 40%; }

.stage-block {
  margin-top: 6px;
  flex: 1;
  border-radius: var(--r-sm);
  border: 1px solid var(--line);
  background: color-mix(in srgb, var(--bg-1) 60%, transparent);
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

/* ── Palette & persona windows ───────────────── */

.pal-window,
.per-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.pal-body {
  padding: 12px 10px;
}

.pal-input {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  margin-bottom: 8px;
  border-radius: var(--r-xs);
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.pal-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
}

.pal-row + .pal-row {
  border-top: 1px solid var(--line);
}

.pal-group {
  width: 68px;
  font-family: var(--font-mono);
  font-size: 9.5px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-4);
}

.pal-label {
  flex: 1;
  font-size: 12.5px;
  color: var(--fg-1);
}

.pal-kind {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--accent);
}

.per-body {
  padding: 12px 10px;
}

.per-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 15px 12px;
}

.per-row + .per-row {
  border-top: 1px solid var(--line);
}

.per-name {
  width: 84px;
  font-size: 13px;
  font-weight: 650;
  color: var(--fg-0);
}

.per-subs {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
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

/* ── Motion & responsive ─────────────────────── */

@media (prefers-reduced-motion: reduce) {
  .eyebrow-dot,
  .closing-mark {
    animation: none;
  }
}

@media (max-width: 960px) {
  .hero {
    grid-template-columns: 1fr;
    padding-top: 36px;
    padding-bottom: 64px;
  }

  .how-grid {
    grid-template-columns: 1fr;
  }

  .feature-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .feature-grid {
    grid-template-columns: 1fr;
  }

  .pal-group {
    display: none;
  }
}
</style>
