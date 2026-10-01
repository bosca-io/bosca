<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Forms — Collect answers, then act on them',
  description: 'Bosca Forms is a drag-and-drop form builder built into the platform. Publish a form, collect submissions, and turn them into profiles, tasks, or pipeline runs — notifications, HubSpot syncs, whatever you automate.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Forms', path: '/discover/forms' }
], '/og-forms.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Build it',
    body: 'Drag fields onto a canvas, arrange them in sections and rows, and set each field\'s rules in the inspector. The preview renders the real form as you go.'
  },
  {
    step: '02',
    title: 'Publish it',
    body: 'Publish when it\'s ready. A public form takes submissions from anyone on the web; an internal one serves signed-in people in Studio.'
  },
  {
    step: '03',
    title: 'Act on it',
    body: 'Review every submission in one queue — and let the form do more than collect: a submission can create a profile, open a task, or kick off a pipeline.'
  }
]

const FEATURES = [
  {
    icon: 'edit',
    title: 'A visual builder',
    body: 'A palette of fields, a canvas you arrange them on, and an inspector for the selected one. The preview renders the same form component your visitors get.'
  },
  {
    icon: 'sliders',
    title: 'Fifteen field types',
    body: 'Text and long text, numbers, dates, selects, radios, checkboxes and switches, tags, colors, and image and file uploads.'
  },
  {
    icon: 'braces',
    title: 'Options from live data',
    body: 'A select can pull its options from your platform — a GraphQL query, an API script, or Work Ops projects, task types, and priorities.'
  },
  {
    icon: 'list-checks',
    title: 'Rules per field',
    body: 'Required, length and number ranges, patterns, and formats like email or date — set visually on each field, checked before submit.'
  },
  {
    icon: 'globe',
    title: 'Public or internal',
    body: 'A public form accepts submissions from anonymous visitors. An internal one collects data from your own signed-in people.'
  },
  {
    icon: 'user-plus',
    title: 'Submissions become profiles',
    body: 'Map form fields to profile attributes, and an anonymous submission creates a profile in your audience. Signed-in submitters link to the profile they already have.'
  },
  {
    icon: 'kanban',
    title: 'Submissions become tasks',
    body: 'A Work Ops form opens a task from each submission — project, priority, summary, and more, mapped straight from the form.'
  },
  {
    icon: 'shield-check',
    title: 'Versioned & permissioned',
    body: 'Every save is a new version of the form. Access is granted per group — who can view it, edit it, or submit it.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="forms"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Forms
        </p>
        <h1 class="load-2">
          Collect answers.<br>
          <em>Then act on them.</em>
        </h1>
        <p class="hero-sub load-3">
          Bosca Forms is a drag-and-drop form builder built into the platform.
          Publish a form, collect submissions, and turn them into profiles in
          your audience or tasks for your team.
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
        <div class="form-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">Volunteer signup</span>
          </div>
          <div class="form-body">
            <div class="ff">
              <span class="ff-label">Name</span>
              <span class="ff-input">Jane Miller</span>
            </div>
            <div class="ff-row">
              <div class="ff">
                <span class="ff-label">Email</span>
                <span class="ff-input">jane@example.com</span>
              </div>
              <div class="ff">
                <span class="ff-label">T-shirt size</span>
                <span class="ff-input ff-select">M</span>
              </div>
            </div>
            <div class="ff">
              <span class="ff-label">First available</span>
              <span class="ff-input ff-select">Aug 2</span>
            </div>
            <button class="ff-submit">
              Sign up
            </button>
            <div class="ff-after">
              <Icon
                name="user-plus"
                :size="12"
              />
              submission → profile <code>Jane Miller</code>
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
        <h2>Build, publish, <em>act.</em></h2>
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
          <h2>A real form builder. <em>And part of the platform.</em></h2>
          <p class="section-sub">
            Everything you expect from a form tool — a visual builder, field
            rules, a submissions queue — plus what you only get when forms
            live next to your audience and your work.
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

    <!-- ── Integration ─────────────────────────── -->
    <section class="section integ">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Deep platform integration
          </p>
          <h2>Not a silo — <em>part of the whole</em></h2>
          <p class="section-sub">
            Answers don't pile up in a spreadsheet somewhere. They land in the
            platform, next to the profiles they update and the tasks they
            open.
          </p>
          <ul class="point-list">
            <li>An anonymous submission can create a profile — name, visibility, and attributes all mapped from the form's fields.</li>
            <li>A Work Ops form opens a task from each submission, with the project, type, and priority set by the form or its defaults.</li>
            <li>Every submission fires an event pipelines react to — notify the right people, sync the contact to HubSpot, or run any automation you've built. A pipeline can read the full submission mid-flow.</li>
            <li>Access uses the same group-based permissions as the rest of Bosca — per form, down to who can submit.</li>
          </ul>
        </div>
        <div class="integ-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">connected</span>
          </div>
          <div class="integ-body">
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="user-plus"
                :size="15"
              /></span>
              <span class="integ-text">Submission → profile <code>Jane Miller</code></span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="kanban"
                :size="15"
              /></span>
              <span class="integ-text">Submission → task <code>FAC-142</code></span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="workflow"
                :size="15"
              /></span>
              <span class="integ-text">Submission event → pipeline · HubSpot sync</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="users"
                :size="15"
              /></span>
              <span class="integ-text">Editing granted to the <code>outreach</code> group</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverFormsExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#f6399c"
        class="closing-mark"
      />
      <h2>Forms ship <em>with Bosca</em></h2>
      <p class="section-sub">
        No embeds from a third-party form service, no exporting responses to
        somewhere useful. Build the form where the answers belong — next to
        your audience, your content, and your work.
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
  max-width: 470px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

/* ── Form window ─────────────────────────────── */

.form-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.form-body {
  padding: 18px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ff {
  display: flex;
  flex-direction: column;
  gap: 5px;
  flex: 1;
  min-width: 0;
}

.ff-row {
  display: flex;
  gap: 10px;
}

.ff-label {
  font-size: 11.5px;
  color: var(--fg-3);
}

.ff-input {
  font-size: 13px;
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: var(--r-xs);
  padding: 8px 10px;
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.ff-select::after {
  content: '▾';
  float: right;
  color: var(--fg-3);
  font-size: 11px;
}

.ff-submit {
  margin-top: 4px;
  width: 100%;
  padding: 11px;
  border-radius: var(--r-sm);
  border: none;
  background: var(--accent);
  color: #1c0512;
  font-size: 13.5px;
  font-weight: 650;
  font-family: inherit;
}

.ff-after {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--fg-3);
}

.ff-after code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
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
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #cbd5e1;
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

/* ── Integration ─────────────────────────────── */

.integ {
  padding-top: 90px;
}

.integ-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.integ-body {
  padding: 10px 8px;
}

.integ-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
}

.integ-row + .integ-row {
  border-top: 1px solid var(--line);
}

.integ-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #cbd5e1;
}

.integ-text {
  font-size: 13px;
  color: var(--fg-1);
}

.integ-text code {
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

@media (max-width: 480px) {
  .ff-row {
    flex-direction: column;
    gap: 12px;
  }
}
</style>
