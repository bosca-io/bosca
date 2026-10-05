<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Search & quick actions — One box for the whole app',
  description: 'One keystroke in Bosca Studio searches documents, collections, profiles, tasks, and specs, lists every place you can navigate to, and starts new work in any subsystem — all from the same input.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Studio', path: '/discover/studio' },
  { name: 'Search & quick actions', path: '/discover/studio/search' }
], '/og-studio.png')

const RESULTS = [
  { group: 'Content', label: 'Spring launch checklist', kind: 'Document' },
  { group: 'Content', label: 'Campaign assets', kind: 'Collection' },
  { group: 'People', label: 'Dana Whitfield', kind: 'Profile' },
  { group: 'Content', label: 'PLAT-42 · Ship the editor', kind: 'Task' },
  { group: 'Navigate', label: 'Analytics · Dashboards', kind: 'Go' }
]

const ACTIONS = [
  { label: 'New document', sub: 'CMS' },
  { label: 'New task', sub: 'Work Ops' },
  { label: 'New experiment', sub: 'Experiments' },
  { label: 'New pipeline', sub: 'Pipelines' },
  { label: 'New repository', sub: 'Git' }
]

const GROUPS = [
  { icon: 'zap', name: 'Actions', body: 'Start something new, in any subsystem you can reach.' },
  { icon: 'arrow-right', name: 'Navigate', body: 'Every destination in every subsystem open to you.' },
  { icon: 'file-text', name: 'Content', body: 'Documents, collections, tasks, and specs.' },
  { icon: 'user', name: 'People', body: 'Profiles from across the platform.' }
]
</script>

<template>
  <DiscoverShell section-id="studio">
    <section class="page-hero">
      <p class="kicker load-1">
        Search &amp; quick actions
      </p>
      <h1 class="load-2">
        One box, <em>the whole app</em>
      </h1>
      <p class="section-sub load-3">
        One input reaches the whole platform. Press
        <code>Cmd/Ctrl+K</code> and search what you've made, jump to any part of
        Studio, or start something new — without deciding which of those you
        meant first.
      </p>
    </section>

    <!-- ── One search ──────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            One search
          </p>
          <h2>Documents, people, and work — <em>in one list</em></h2>
          <p class="section-sub">
            Type once and Studio searches your documents, collections, and
            profiles, and asks Work Ops for matching tasks and specs at the same
            time. Results arrive together and grouped, so a document, a person,
            and a task can all answer the same query.
          </p>
          <ul class="point-list">
            <li>Documents, collections, and profiles, searched live.</li>
            <li>Tasks and specs from Work Ops, in the same results.</li>
            <li>Grouped by kind, so you can tell answers apart at a glance.</li>
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

    <!-- ── Quick actions ───────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Quick actions
          </p>
          <h2>Start it <em>from the same input</em></h2>
          <p class="section-sub">
            The palette creates as well as finds. Begin a
            document, a task, a spec, a calendar event, a form, an experiment, a
            segment, a dashboard, a query, a repository, a channel, an agent, a
            pipeline, or a script — the search box is also the fastest way to
            create in any subsystem.
          </p>
          <ul class="point-list">
            <li>Create in any subsystem without navigating there first.</li>
            <li>Actions span content, work, measurement, delivery, and more.</li>
            <li>The same keystroke that finds is the one that starts.</li>
          </ul>
        </div>
        <div class="pal-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">actions</span>
          </div>
          <div class="act-body">
            <div
              v-for="a in ACTIONS"
              :key="a.label"
              class="act-row"
            >
              <span class="act-icon"><Icon
                name="plus"
                :size="12"
              /></span>
              <span class="act-label">{{ a.label }}</span>
              <span class="act-sub">{{ a.sub }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Grouped & keyboard-first ────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Keyboard first
        </p>
        <h2>Grouped results, <em>one set of keys</em></h2>
        <p class="section-sub">
          Results come back grouped by kind, so the shape of the list tells you
          what you're looking at before you read it. Open with
          <code>Cmd/Ctrl+K</code>, move with the arrow keys, and press Enter —
          your hands never have to leave the keyboard.
        </p>
      </div>
      <div class="grp-grid reveal">
        <article
          v-for="g in GROUPS"
          :key="g.name"
          class="grp-card"
        >
          <span class="grp-icon">
            <Icon
              :name="g.icon"
              :size="15"
            />
          </span>
          <h3>{{ g.name }}</h3>
          <p>{{ g.body }}</p>
        </article>
      </div>
    </section>

    <DiscoverStudioExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Palette windows ─────────────────────────── */

.pal-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.pal-body,
.act-body {
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
  width: 66px;
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

/* ── Actions ─────────────────────────────────── */

.act-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 12px;
}

.act-row + .act-row {
  border-top: 1px solid var(--line);
}

.act-icon {
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.act-label {
  flex: 1;
  font-size: 12.5px;
  color: var(--fg-1);
}

.act-sub {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Groups ──────────────────────────────────── */

.grp-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.grp-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.grp-icon {
  width: 30px;
  height: 30px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
  margin-bottom: 14px;
}

.grp-card h3 {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.grp-card p {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

@media (max-width: 960px) {
  .grp-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .grp-grid {
    grid-template-columns: 1fr;
  }

  .pal-group {
    display: none;
  }
}
</style>
