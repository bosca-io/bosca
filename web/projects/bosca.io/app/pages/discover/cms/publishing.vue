<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Workflow & Publishing in the CMS — States, Schedules & Health',
  description: 'Every item moves through a defined workflow before it goes live. Bosca schedules publishing in advance, gates access on both state and visibility, checks content health, and moderates comments.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'CMS', path: '/discover/cms' },
  { name: 'Workflow & publishing', path: '/discover/cms/publishing' }
], '/og-cms.png')

// The main content lifecycle path; ADVERTISED and FAILURE branch off it.
const FLOW = ['Draft', 'Approval', 'Approved', 'Processing', 'Published']

const HEALTH_CHECKS = [
  'Failed processing jobs',
  'Published with unpublished relationships',
  'Scheduled but not yet published',
  'Pending items not ready',
  'Missing content',
  'Guides with unpublished steps'
]
</script>

<template>
  <DiscoverShell section-id="cms">
    <section class="page-hero">
      <p class="kicker load-1">
        Workflow &amp; publishing
      </p>
      <h1 class="load-2">
        Nothing ships <em>by accident</em>
      </h1>
      <p class="section-sub load-3">
        Every item moves through a defined workflow before it reaches your
        audience — draft, review, approval, processing, published. Publishing
        can be scheduled in advance, access is gated on two independent
        dimensions, and a health dashboard catches what slipped.
      </p>
    </section>

    <!-- ── The workflow ────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          The workflow
        </p>
        <h2>A path from <em>draft to published</em></h2>
        <p class="section-sub">
          Content follows a state machine. Each transition can fire background
          work on the way in or out — start transcoding when an item enters
          processing, notify reviewers when it enters approval — and every move
          is recorded with who did it and when.
        </p>
      </div>
      <div class="flow reveal">
        <template
          v-for="(state, i) in FLOW"
          :key="state"
        >
          <span
            class="flow-state"
            :class="{ 'flow-last': i === FLOW.length - 1 }"
          >{{ state }}</span>
          <Icon
            v-if="i < FLOW.length - 1"
            name="arrow-right"
            :size="15"
            class="flow-arrow"
          />
        </template>
      </div>
      <div class="flow-branches reveal">
        <span class="branch"><span class="branch-dot dot-adv" /> <strong>Advertised</strong> — visible as a preview, binary content held back</span>
        <span class="branch"><span class="branch-dot dot-fail" /> <strong>Failure</strong> — a processing error, surfaced for a person to fix</span>
      </div>
    </section>

    <!-- ── Scheduled publishing ────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Scheduled publishing
          </p>
          <h2>Go live <em>on a timer</em></h2>
          <p class="section-sub">
            A workflow plan schedules a transition for a future moment. Set an
            article to publish at 9am, lift an embargo the instant it ends, or
            rotate seasonal content in and out — no one has to be at a keyboard
            when it happens.
          </p>
          <ul class="point-list">
            <li>Plans work on individual items and on whole collections.</li>
            <li>The processing state guarantees transcoding, indexing, and thumbnails finish <em>before</em> anything is publicly visible.</li>
          </ul>
        </div>
        <div class="schedule-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">scheduled transition</span>
          </div>
          <div class="schedule-body">
            <div class="sched-row">
              <Icon
                name="clock"
                :size="15"
                class="sched-icon"
              />
              <span class="sched-item">Spring Campaign</span>
              <span class="sched-when">Mar 1 · 09:00</span>
              <span class="sched-tag">→ published</span>
            </div>
            <div class="sched-row">
              <Icon
                name="clock"
                :size="15"
                class="sched-icon"
              />
              <span class="sched-item">Q4 Report</span>
              <span class="sched-when">embargo lifts</span>
              <span class="sched-tag">→ published</span>
            </div>
            <div class="sched-row sched-muted">
              <Icon
                name="clock"
                :size="15"
                class="sched-icon"
              />
              <span class="sched-item">Holiday Banner</span>
              <span class="sched-when">Jan 2 · 00:00</span>
              <span class="sched-tag">→ draft</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Visibility ──────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Visibility
          </p>
          <h2>Two switches, <em>not one</em></h2>
          <p class="section-sub">
            Being published isn't the same as being public. Access depends on
            two independent things: the workflow state, and a set of visibility
            flags. Both have to line up — a published item with its public flag
            off stays private.
          </p>
          <ul class="point-list">
            <li><strong>Public</strong> — the metadata is readable at all.</li>
            <li><strong>Public Content</strong> — the binary — the video, the file — is reachable.</li>
            <li><strong>Public Supplementary</strong> — attached files are reachable.</li>
            <li><strong>Searchable</strong> — the item appears in the public search index.</li>
          </ul>
        </div>
        <div class="matrix-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">state × visibility</span>
          </div>
          <div class="matrix">
            <div class="matrix-row matrix-head">
              <span /><span>Public</span><span>Searchable</span>
            </div>
            <div class="matrix-row">
              <span class="mx-label">Draft</span>
              <span class="mx-cell mx-no">✕</span>
              <span class="mx-cell mx-no">✕</span>
            </div>
            <div class="matrix-row">
              <span class="mx-label">Published</span>
              <span class="mx-cell mx-yes">✓</span>
              <span class="mx-cell mx-no">✕</span>
            </div>
            <div class="matrix-row">
              <span class="mx-label">Published</span>
              <span class="mx-cell mx-yes">✓</span>
              <span class="mx-cell mx-yes">✓</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Health & moderation ─────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Keeping it healthy
        </p>
        <h2>Catch what <em>slipped</em></h2>
        <p class="section-sub">
          Two safety nets run over a live library: a health dashboard that
          flags problems, and a moderation flow that keeps community content in
          check.
        </p>
      </div>
      <div class="health-split reveal">
        <div class="health-card">
          <h3>Content health</h3>
          <p class="health-lead">
            A dashboard of aggregate stats plus a triage list — click an issue
            to jump straight to the item. Its checks include:
          </p>
          <ul class="check-list">
            <li
              v-for="check in HEALTH_CHECKS"
              :key="check"
            >
              <Icon
                name="heart-pulse"
                :size="13"
                class="check-icon"
              />
              {{ check }}
            </li>
          </ul>
        </div>
        <div class="health-card">
          <h3>Comments &amp; moderation</h3>
          <p class="health-lead">
            Threaded comments on any item, gated by per-item switches and a
            moderation flow that runs before anything appears.
          </p>
          <ul class="mod-flow">
            <li><span class="mod-badge">pending</span> a new comment awaits moderation</li>
            <li><span class="mod-badge">review</span> anything inconclusive goes to a person</li>
            <li><span class="mod-badge mod-ok">approved</span> it becomes visible</li>
            <li><span class="mod-badge mod-block">blocked</span> or it is turned away</li>
          </ul>
          <p class="health-note">
            The moderation logic is a pipeline you can shape — automatic checks,
            human review, or both.
          </p>
        </div>
      </div>
    </section>

    <DiscoverCmsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Lifecycle flow ──────────────────────────── */

.flow {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.flow-state {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 8px 16px;
  background: color-mix(in srgb, var(--bg-1) 60%, transparent);
}

.flow-state.flow-last {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 45%, transparent);
  background: color-mix(in srgb, #34d99a 10%, transparent);
}

.flow-arrow {
  color: var(--accent);
  flex-shrink: 0;
}

.flow-branches {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 32px;
  margin-top: 22px;
}

.branch {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  font-size: 13px;
  color: var(--fg-2);
}

.branch strong {
  color: var(--fg-1);
  font-weight: 650;
}

.branch-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
}

.dot-adv { background: #f6c453; }
.dot-fail { background: #f87171; }

/* ── Schedule window ─────────────────────────── */

.schedule-window,
.matrix-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.schedule-body {
  padding: 10px 8px;
}

.sched-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}

.sched-row + .sched-row {
  border-top: 1px solid var(--line);
}

.sched-icon {
  color: var(--accent);
  flex-shrink: 0;
}

.sched-item {
  flex: 1;
  font-size: 13.5px;
  color: var(--fg-1);
}

.sched-when {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
}

.sched-tag {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: 999px;
  padding: 2px 9px;
}

.sched-muted {
  opacity: 0.5;
}

.sched-muted .sched-tag {
  color: var(--fg-3);
  border-color: var(--line);
}

/* ── Visibility matrix ───────────────────────── */

.matrix {
  padding: 16px 18px 20px;
}

.matrix-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr;
  align-items: center;
  padding: 9px 4px;
}

.matrix-row + .matrix-row {
  border-top: 1px solid var(--line);
}

.matrix-head {
  font-family: var(--font-mono);
  font-size: 10.5px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
}

.matrix-head span {
  text-align: center;
}

.matrix-head span:first-child {
  text-align: left;
}

.mx-label {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.mx-cell {
  text-align: center;
  font-size: 14px;
}

.mx-yes { color: #34d99a; }
.mx-no { color: var(--fg-4, #556); }

/* ── Health & moderation ─────────────────────── */

.health-split {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.health-card {
  padding: 26px 24px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.health-card h3 {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.01em;
  margin: 0 0 10px;
}

.health-lead {
  font-size: 13.5px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0 0 16px;
}

.check-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.check-list li {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 13px;
  color: var(--fg-1);
}

.check-icon {
  color: var(--accent);
  flex-shrink: 0;
}

.mod-flow {
  list-style: none;
  margin: 0 0 14px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.mod-flow li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--fg-2);
}

.mod-badge {
  flex-shrink: 0;
  min-width: 66px;
  text-align: center;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 8px;
}

.mod-badge.mod-ok {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.mod-badge.mod-block {
  color: #f87171;
  border-color: color-mix(in srgb, #f87171 40%, transparent);
}

.health-note {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-3);
  margin: 0;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 820px) {
  .health-split {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
