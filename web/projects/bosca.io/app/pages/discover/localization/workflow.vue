<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Workflow — Draft to published, on the record',
  description: 'Generate configured target-language strings through Kit or translate them by hand, then move every Bosca translation through a defined review workflow with recorded origin and append-only history.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Localization', path: '/discover/localization' },
  { name: 'Workflow', path: '/discover/localization/workflow' }
], '/og-localization.png')

const STATES = [
  { name: 'Draft', body: 'Being written or edited.' },
  { name: 'AI-generated', body: 'Produced by AI, awaiting review.' },
  { name: 'In review', body: 'Submitted for a human check.' },
  { name: 'Approved', body: 'Signed off, not yet live.' },
  { name: 'Published', body: 'Live, and ready to export.' },
  { name: 'Rejected', body: 'Sent back for changes.' },
  { name: 'Archived', body: 'Retired from active use.' }
]

const ORIGINS = [
  { icon: 'user', name: 'Human', body: 'Typed by a translator.' },
  { icon: 'download', name: 'Import', body: 'Brought in from a file.' },
  { icon: 'arrow-right-left', name: 'Sync', body: 'Pulled from an external tool.' },
  { icon: 'shield-check', name: 'AI', body: 'Marked, and held for review.' }
]
</script>

<template>
  <DiscoverShell section-id="localization">
    <section class="page-hero">
      <p class="kicker load-1">
        Workflow
      </p>
      <h1 class="load-2">
        Draft to published, <em>on the record</em>
      </h1>
      <p class="section-sub load-3">
        A translation isn't done the moment it's typed. It moves through a
        defined set of states, with the invalid jumps blocked, its origin
        recorded, and every change kept — so what reaches production has been
        through the checks you expect.
      </p>
    </section>

    <!-- ── States ──────────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          The states
        </p>
        <h2>Seven states, <em>and only valid moves</em></h2>
        <p class="section-sub">
          Each translation sits in exactly one state, and the workflow only
          allows the transitions that make sense — you can't publish something
          that was never reviewed.
        </p>
      </div>
      <div class="state-grid reveal">
        <div
          v-for="s in STATES"
          :key="s.name"
          class="state-card"
        >
          <h3>{{ s.name }}</h3>
          <p>{{ s.body }}</p>
        </div>
      </div>
    </section>

    <!-- ── Origin & the AI gate ────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Origin &amp; review
          </p>
          <h2>Know where <em>every word came from</em></h2>
          <p class="section-sub">
            Each translation records how it arrived. For ordinary strings,
            Bosca can ask Kit to generate the configured target languages in a
            batch; those results are marked as AI-generated and enter a state
            that can't be published without human review first.
          </p>
          <ul class="point-list">
            <li>Origin is one of human, import, sync, or AI.</li>
            <li>Generate missing target-language strings through Kit, then review them alongside human work.</li>
            <li>AI-marked work lands in its own state and must pass human review.</li>
            <li>Editing an approved or published translation sends it back to draft.</li>
          </ul>
        </div>
        <div class="origin-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">origin</span>
          </div>
          <div class="origin-body">
            <div
              v-for="o in ORIGINS"
              :key="o.name"
              class="origin-row"
            >
              <span class="origin-icon"><Icon
                :name="o.icon"
                :size="14"
              /></span>
              <span class="origin-name">{{ o.name }}</span>
              <span class="origin-note">{{ o.body }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── History ─────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Every change kept
          </p>
          <h2>An audit trail <em>you can trust</em></h2>
          <p class="section-sub">
            Nothing is silently overwritten. Every state change and every text
            edit is written to an append-only history — who did it, where it came
            from, and the text before and after — across strings, plurals, and
            documents alike.
          </p>
          <ul class="point-list">
            <li>Each entry records the state change and the text before and after.</li>
            <li>The actor and the origin are stamped on every change.</li>
            <li>One history spans strings, plural forms, and documents.</li>
          </ul>
        </div>
        <div class="hist-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">history · checkout.pay · de</span>
          </div>
          <div class="hist-body">
            <div class="hist-row">
              <span class="hist-move">ai-generated → in review</span>
              <span class="hist-by">Ada</span>
            </div>
            <div class="hist-row">
              <span class="hist-move">in review → approved</span>
              <span class="hist-by">Sam</span>
            </div>
            <div class="hist-edit">
              <span class="hist-old">Jetzt zahlen</span>
              <Icon
                name="arrow-right"
                :size="12"
              />
              <span class="hist-new">Jetzt bezahlen</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverLocalizationExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── State cards ─────────────────────────────── */

.state-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.state-card {
  padding: 20px 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.state-card h3 {
  font-size: 14.5px;
  font-weight: 650;
  color: var(--accent);
  margin: 0 0 7px;
}

.state-card p {
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--fg-2);
  margin: 0;
}

/* ── Shared windows ──────────────────────────── */

.origin-window,
.hist-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Origin window ───────────────────────────── */

.origin-body {
  padding: 12px 10px;
}

.origin-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.origin-row + .origin-row {
  border-top: 1px solid var(--line);
}

.origin-icon {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.origin-name {
  width: 64px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg-1);
}

.origin-note {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

/* ── History window ──────────────────────────── */

.hist-body {
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.hist-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 12px;
}

.hist-row + .hist-row {
  border-top: 1px solid var(--line);
}

.hist-move {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.hist-by {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.hist-edit {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  padding: 12px;
  border-top: 1px solid var(--line);
}

.hist-edit svg { color: var(--accent); flex-shrink: 0; }

.hist-old {
  font-size: 12.5px;
  color: var(--fg-3);
  text-decoration: line-through;
}

.hist-new {
  font-size: 12.5px;
  color: var(--fg-0);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 860px) {
  .state-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 460px) {
  .state-grid {
    grid-template-columns: 1fr;
  }
}
</style>
