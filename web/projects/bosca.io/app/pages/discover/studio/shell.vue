<script setup lang="ts">
import { SECTIONS } from '~/composables/useSections'

definePageMeta({ layout: false })

useSeoMeta({
  title: 'The shell — One sidebar, every subsystem',
  description: 'A sidebar that always knows where you are, a switcher that groups every subsystem into categories, an accent that follows the subsystem you are in, and a right-click menu that reaches the whole app.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Studio', path: '/discover/studio' },
  { name: 'The shell', path: '/discover/studio/shell' }
], '/og-studio.png')

// Accents come straight from the section registry so the swatches here can
// never drift from the colors the product actually uses.
const SWATCH_IDS = ['cms', 'workops', 'analytics', 'git', 'kubernetes', 'ai']
const SWATCHES = SWATCH_IDS
  .map(id => SECTIONS.find(s => s.id === id))
  .filter((s): s is NonNullable<typeof s> => Boolean(s))

const NAV_GROUPS = [
  { title: 'Library', items: ['Collections', 'Documents', 'Media'] },
  { title: 'Distribution', items: ['Publishing', 'Workflows'] }
]

const CATEGORIES = [
  { label: 'Content & Experience', subs: 'CMS · Feeds · Localization · Calendar' },
  { label: 'Audience & Reach', subs: 'Audience · Communications · Forms' },
  { label: 'Measure', subs: 'Analytics · Experiments · Recommendations' },
  { label: 'Build & Deliver', subs: 'Work Ops · Git · AI · Scripts · Artifacts · Pipelines' },
  { label: 'Commerce', subs: 'Commerce' },
  { label: 'Operate', subs: 'Gateway · Kubernetes · System' }
]

const MENU = [
  { icon: 'layout-grid', label: 'Subsystems' },
  { icon: 'settings', label: 'Settings' },
  { icon: 'message-circle', label: 'Show collaboration' },
  { icon: 'sun', label: 'Light mode' },
  { icon: 'log-out', label: 'Sign out' }
]
</script>

<template>
  <DiscoverShell section-id="studio">
    <section class="page-hero">
      <p class="kicker load-1">
        The shell
      </p>
      <h1 class="load-2">
        The shell you <em>work inside</em>
      </h1>
      <p class="section-sub load-3">
        One sidebar that always knows where you are, a switcher that holds every
        subsystem, and a color that follows the work. Move from a content library
        to a cluster and the shape stays exactly the same.
      </p>
    </section>

    <!-- ── Sidebar ─────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The sidebar
          </p>
          <h2>Always there, <em>always in context</em></h2>
          <p class="section-sub">
            The sidebar opens with the Bosca mark, then the subsystem you're in —
            its icon, its name, and a line describing it. Below that sit the
            subsystem's own navigation groups. The page you're on carries a tinted
            background and a glowing bar in the subsystem's accent, so you always
            know where you are.
          </p>
          <ul class="point-list">
            <li>The brand mark takes on the accent of the subsystem you're in.</li>
            <li>The subsystem header doubles as the way into the switcher.</li>
            <li>Navigation groups come from the subsystem itself.</li>
            <li>The active page is marked with a tint and an accent bar.</li>
          </ul>
        </div>
        <div class="rail-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">sidebar</span>
          </div>
          <div class="rail-body">
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
                <span class="rail-sub-desc">Collections, documents, media</span>
              </span>
              <Icon
                name="chevron-right"
                :size="12"
                class="rail-chevron"
              />
            </div>
            <div
              v-for="group in NAV_GROUPS"
              :key="group.title"
              class="rail-group"
            >
              <span class="rail-group-title">{{ group.title }}</span>
              <div
                v-for="(item, i) in group.items"
                :key="item"
                class="rail-item"
                :class="{ active: group.title === 'Library' && i === 0 }"
              >
                {{ item }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Switcher ────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            The switcher
          </p>
          <h2>Every subsystem, <em>one modal</em></h2>
          <p class="section-sub">
            Click the subsystem header in the sidebar, or right-click and choose
            Subsystems, and the whole platform lays out in labelled categories —
            content, audience, measurement, delivery, commerce, and operations.
            Filter as you type, move with the arrow keys, and press Enter to land.
          </p>
          <ul class="point-list">
            <li>Subsystems grouped into categories you can scan.</li>
            <li>Filter by name, then move with the arrow keys.</li>
            <li>Landing puts you on that subsystem's first page.</li>
          </ul>
        </div>
        <div class="cat-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">subsystems</span>
          </div>
          <div class="cat-body">
            <div
              v-for="c in CATEGORIES"
              :key="c.label"
              class="cat-row"
            >
              <span class="cat-label">{{ c.label }}</span>
              <span class="cat-subs">{{ c.subs }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Accents ─────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Color as orientation
          </p>
          <h2>A color for <em>every subsystem</em></h2>
          <p class="section-sub">
            Each subsystem carries its own accent, and the shell wears it — the
            ambient gradient behind the page, the icon badge and active bar in the
            sidebar, the hue of the brand mark, and even the icon in your browser
            tab. The color tells you where you are before you read anything, and
            it holds in light and dark alike.
          </p>
          <ul class="point-list">
            <li>One accent per subsystem, carried across the whole shell.</li>
            <li>The gradient, the sidebar, and the brand mark shift together.</li>
            <li>The browser tab icon takes the same accent.</li>
            <li>Light and dark, with the same accents in both.</li>
          </ul>
        </div>
        <div class="sw-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">accents</span>
          </div>
          <div class="sw-body">
            <div
              v-for="s in SWATCHES"
              :key="s.id"
              class="sw-row"
            >
              <span
                class="sw-dot"
                :style="{ background: s.accent }"
              />
              <span class="sw-name">{{ s.label }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Context menu ────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Right-click anywhere
        </p>
        <h2>The whole app, <em>under your cursor</em></h2>
        <p class="section-sub">
          Studio keeps its chrome quiet — there's no top bar taking up room. The
          global moves live one right-click away: jump to another subsystem, open
          settings, show or hide collaboration, switch between light and dark, or
          sign out.
        </p>
      </div>
      <div class="menu-window reveal">
        <div
          v-for="m in MENU"
          :key="m.label"
          class="menu-row"
        >
          <span class="menu-icon"><Icon
            :name="m.icon"
            :size="13"
          /></span>
          <span class="menu-label">{{ m.label }}</span>
        </div>
      </div>
    </section>

    <DiscoverStudioExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.rail-window,
.cat-window,
.sw-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Sidebar mock ────────────────────────────── */

.rail-body {
  padding: 14px 12px;
  background: color-mix(in srgb, var(--accent) 4%, transparent);
}

.rail-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  color: var(--fg-1);
  padding: 2px 6px 12px;
}

.rail-sub {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 6px;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  margin-bottom: 12px;
}

.rail-icon {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
}

.rail-sub-text {
  flex: 1;
  display: flex;
  flex-direction: column;
  line-height: 1.3;
  min-width: 0;
}

.rail-sub-name {
  font-size: 12.5px;
  font-weight: 650;
  color: var(--fg-0);
}

.rail-sub-desc {
  font-size: 10px;
  color: var(--fg-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rail-chevron { color: var(--fg-4); flex-shrink: 0; }

.rail-group { margin-bottom: 12px; }

.rail-group-title {
  display: block;
  font-size: 9.5px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-4);
  padding: 4px 8px 6px;
}

.rail-item {
  position: relative;
  font-size: 12px;
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
  left: -12px;
  top: 6px;
  bottom: 6px;
  width: 2px;
  border-radius: 999px;
  background: var(--accent);
  box-shadow: 0 0 8px var(--accent);
}

/* ── Category mock ───────────────────────────── */

.cat-body { padding: 12px 10px; }

.cat-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px;
}

.cat-row + .cat-row { border-top: 1px solid var(--line); }

.cat-label {
  font-size: 9.5px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--accent);
}

.cat-subs {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-2);
}

/* ── Swatches ────────────────────────────────── */

.sw-body { padding: 12px 10px; }

.sw-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 12px;
}

.sw-row + .sw-row { border-top: 1px solid var(--line); }

.sw-dot {
  width: 12px;
  height: 12px;
  border-radius: 999px;
  flex-shrink: 0;
}

.sw-name {
  flex: 1;
  font-size: 12.5px;
  color: var(--fg-1);
}

/* ── Context menu ────────────────────────────── */

.menu-window {
  max-width: 300px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  padding: 8px;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.menu-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 10px;
  border-radius: var(--r-xs);
}

.menu-row:first-child {
  background: color-mix(in srgb, var(--accent) 12%, transparent);
}

.menu-icon {
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--accent);
}

.menu-label {
  font-size: 13px;
  color: var(--fg-1);
}
</style>
