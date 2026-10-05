<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Studio Personas — One app, shaped per person',
  description: 'A Studio Persona is a named set of subsystems attached to a profile. Carry several and they combine — shaping the sidebar, the switcher, the search, and where Studio opens you, so an editor and an operator each find their own app.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Studio', path: '/discover/studio' },
  { name: 'Studio Personas', path: '/discover/studio/personas' }
], '/og-studio.png')

const PERSONA_SUBS = ['CMS', 'Localization', 'Calendar']

const SURFACES = [
  { icon: 'layout-grid', name: 'The sidebar', body: 'Only the subsystems a persona grants are there to navigate.' },
  { icon: 'search', name: 'The switcher', body: 'The categorized modal lists the same set, and nothing else.' },
  { icon: 'zap', name: 'Search & actions', body: 'Destinations and quick actions follow the subsystems you hold.' },
  { icon: 'monitor', name: 'The home hub', body: 'The launcher grid lists the subsystems your personas cover.' }
]
</script>

<template>
  <DiscoverShell section-id="studio">
    <section class="page-hero">
      <p class="kicker load-1">
        Studio Personas
      </p>
      <h1 class="load-2">
        One app, <em>shaped per person</em>
      </h1>
      <p class="section-sub load-3">
        Bosca spans a lot of ground. A Studio Persona decides how much of it any
        one person meets — so the editor, the analyst, and the operator open the
        same app and each find the work that's theirs.
      </p>
    </section>

    <!-- ── What a persona is ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The idea
          </p>
          <h2>A named set of <em>subsystems</em></h2>
          <p class="section-sub">
            A Studio Persona is exactly that: a name, a short description, and the
            subsystems it covers, attached to a profile. Someone can carry more
            than one — a person who edits content and runs the analytics both gets
            everything each persona brings, combined into a single set.
          </p>
          <ul class="point-list">
            <li>A name, a description, and the subsystems it grants.</li>
            <li>Attached to a profile, and switched on or off as needed.</li>
            <li>Hold several, and their subsystems combine into one set.</li>
          </ul>
        </div>
        <div class="per-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">persona</span>
          </div>
          <div class="per-body">
            <div class="per-head">
              <span class="per-name">Editor</span>
              <span class="per-state">enabled</span>
            </div>
            <p class="per-desc">
              Writes and publishes content, and keeps translations moving.
            </p>
            <div class="per-subs">
              <span
                v-for="s in PERSONA_SUBS"
                :key="s"
                class="per-chip"
              >{{ s }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Where it takes effect ───────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Where it shows
          </p>
          <h2>It shapes <em>the whole shell</em></h2>
          <p class="section-sub">
            A persona is the reason Studio looks the way it does when you open it.
            The sidebar, the switcher, the search results, and the hub you start
            from all follow from the set of subsystems you hold.
          </p>
          <ul class="point-list">
            <li>Navigation offers the subsystems your personas cover.</li>
            <li>Search and quick actions reach the same set.</li>
            <li>Administrators see the whole platform.</li>
          </ul>
        </div>
        <div class="surf-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">effect</span>
          </div>
          <div class="surf-body">
            <div
              v-for="s in SURFACES"
              :key="s.name"
              class="surf-row"
            >
              <span class="surf-icon"><Icon
                :name="s.icon"
                :size="13"
              /></span>
              <span class="surf-text">
                <span class="surf-name">{{ s.name }}</span>
                <span class="surf-body-text">{{ s.body }}</span>
              </span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Assigning ───────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Assigning them
        </p>
        <h2>Defined once, <em>attached where you work</em></h2>
        <p class="section-sub">
          Personas are defined in System security, alongside the rest of the
          platform's access settings. Attaching one happens where you already
          manage people — on a profile's Security tab in Audience — so giving
          someone the analytics view is part of the same visit as the rest of
          their setup.
        </p>
      </div>
    </section>

    <DiscoverStudioExplore />
  </DiscoverShell>
</template>

<style scoped>
.per-window,
.surf-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Persona card ────────────────────────────── */

.per-body {
  padding: 20px 20px 22px;
}

.per-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.per-name {
  font-size: 17px;
  font-weight: 700;
  color: var(--fg-0);
}

.per-state {
  font-family: var(--font-mono);
  font-size: 9.5px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 999px;
  padding: 2px 9px;
}

.per-desc {
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0 0 18px;
}

.per-subs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.per-chip {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 30%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  border-radius: var(--r-xs);
  padding: 4px 10px;
}

/* ── Surfaces ────────────────────────────────── */

.surf-body {
  padding: 12px 10px;
}

.surf-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 12px;
}

.surf-row + .surf-row {
  border-top: 1px solid var(--line);
}

.surf-icon {
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.surf-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.surf-name {
  font-size: 12.5px;
  font-weight: 650;
  color: var(--fg-0);
}

.surf-body-text {
  font-size: 12px;
  line-height: 1.55;
  color: var(--fg-2);
}
</style>
