<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Form Submissions — One Queue, Real Follow-Through',
  description: 'Every submission lands in one reviewable queue with a clear status — and fires an event pipelines react to. Public forms take anonymous submissions, and a mapped form creates a profile from each one.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Forms', path: '/discover/forms' },
  { name: 'Submissions', path: '/discover/forms/submissions' }
], '/og-forms.png')
</script>

<template>
  <DiscoverShell section-id="forms">
    <section class="page-hero">
      <p class="kicker load-1">
        Submissions
      </p>
      <h1 class="load-2">
        Every answer, <em>in one place</em>
      </h1>
      <p class="section-sub load-3">
        Submissions land in a queue you review per form — who sent it, when,
        and where it stands. Public forms take answers from anyone; mapped
        ones turn those answers into profiles.
      </p>
    </section>

    <!-- ── The queue ───────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The queue
          </p>
          <h2>Reviewed per form, <em>row by row</em></h2>
          <p class="section-sub">
            Each form has its own submission list. A row shows the submitting
            profile — or Anonymous — alongside its status and timestamps;
            opening it shows exactly what was sent.
          </p>
          <ul class="point-list">
            <li><strong>Five statuses</strong> — pending, processed, rejected, spam, and archived. A new submission starts pending.</li>
            <li><strong>One-click triage</strong> — set the status from the row; the change is immediate.</li>
            <li><strong>Full detail on open</strong> — the submitter, the status, and every answer as submitted.</li>
          </ul>
        </div>
        <div class="queue-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">submissions · Volunteer signup</span>
          </div>
          <div class="queue-body">
            <div class="q-row">
              <span class="q-who">Jane Miller</span>
              <span class="q-status ok">processed</span>
              <span class="q-when">2h ago</span>
            </div>
            <div class="q-row">
              <span class="q-who">Anonymous</span>
              <span class="q-status wait">pending</span>
              <span class="q-when">5h ago</span>
            </div>
            <div class="q-row">
              <span class="q-who">Sam Ortiz</span>
              <span class="q-status ok">processed</span>
              <span class="q-when">yesterday</span>
            </div>
            <div class="q-row">
              <span class="q-who">Anonymous</span>
              <span class="q-status bad">spam</span>
              <span class="q-when">yesterday</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Anonymous & profiles ────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            From answer to person
          </p>
          <h2>A submission can <em>introduce someone</em></h2>
          <p class="section-sub">
            A public form is often the first time someone reaches you. With a
            profile mapping, that first contact becomes a profile in your
            audience — not a row in a spreadsheet.
          </p>
          <ul class="point-list">
            <li><strong>You choose the mapping</strong> — which field becomes the profile's name, what visibility it gets, and which fields land in which profile attributes.</li>
            <li><strong>Anonymous only</strong> — profiles are created for anonymous submissions; a signed-in submitter is linked to the profile they already have.</li>
            <li><strong>Signed-in visitors get a head start</strong> — the same mapping prefills the form from the profile they already have.</li>
            <li><strong>The link is kept</strong> — every submission records the profile it came from, and you can pull up a profile's submissions.</li>
          </ul>
        </div>
        <div class="map-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">profile mapping</span>
          </div>
          <div class="map-body">
            <div class="map-row">
              <span class="map-from">name</span>
              <Icon
                name="arrow-right"
                :size="12"
                class="map-arrow"
              />
              <span class="map-to">profile name</span>
            </div>
            <div class="map-row">
              <span class="map-from">email</span>
              <Icon
                name="arrow-right"
                :size="12"
                class="map-arrow"
              />
              <span class="map-to">contact · email</span>
            </div>
            <div class="map-row">
              <span class="map-from">size</span>
              <Icon
                name="arrow-right"
                :size="12"
                class="map-arrow"
              />
              <span class="map-to">preferences · shirt</span>
            </div>
            <div class="map-note">
              <Icon
                name="user-plus"
                :size="12"
              />
              anonymous submission → new profile
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Pipelines ───────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            When a submission arrives
          </p>
          <h2>Answers that <em>set things in motion</em></h2>
          <p class="section-sub">
            Every submission fires an event — and so does every status change.
            Pipelines pick them up and run whatever you've built.
          </p>
          <ul class="point-list">
            <li><strong>Notify the right people</strong> — a new submission can alert the team that handles it.</li>
            <li><strong>Send it onward</strong> — sync the contact to HubSpot, or hand the answers to any system a pipeline can reach.</li>
            <li><strong>Work with the answers</strong> — a pipeline can fetch the full submission mid-flow and use every field.</li>
          </ul>
        </div>
        <div class="pipe-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">on submission</span>
          </div>
          <div class="pipe-body">
            <div class="pipe-row">
              <span class="pipe-icon"><Icon
                name="bell"
                :size="14"
              /></span>
              <span class="pipe-text">Notify <code>outreach</code> team</span>
            </div>
            <div class="pipe-row">
              <span class="pipe-icon"><Icon
                name="workflow"
                :size="14"
              /></span>
              <span class="pipe-text">Pipeline → HubSpot contact</span>
            </div>
            <div class="pipe-row">
              <span class="pipe-icon"><Icon
                name="user-plus"
                :size="14"
              /></span>
              <span class="pipe-text">Profile created · <code>Jane Miller</code></span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverFormsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Window shells ───────────────────────────── */

.queue-window,
.map-window,
.pipe-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Queue window ────────────────────────────── */

.queue-body {
  padding: 10px 8px;
}

.q-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.q-row + .q-row {
  border-top: 1px solid var(--line);
}

.q-who {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
}

.q-status {
  font-family: var(--font-mono);
  font-size: 10.5px;
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 9px;
  color: var(--fg-2);
}

.q-status.ok {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.q-status.wait {
  color: #f6c453;
  border-color: color-mix(in srgb, #f6c453 40%, transparent);
}

.q-status.bad {
  color: #f87171;
  border-color: color-mix(in srgb, #f87171 40%, transparent);
}

.q-when {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

/* ── Mapping window ──────────────────────────── */

.map-body {
  padding: 14px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.map-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 2px;
}

.map-row + .map-row {
  border-top: 1px solid var(--line);
}

.map-from {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
  min-width: 64px;
}

.map-arrow {
  color: var(--fg-3);
  flex-shrink: 0;
}

.map-to {
  font-size: 12.5px;
  color: var(--fg-2);
}

.map-note {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--fg-3);
  border-top: 1px solid var(--line);
  padding: 12px 2px 2px;
  margin-top: 6px;
}

/* ── Pipeline window ─────────────────────────── */

.pipe-body {
  padding: 10px 8px;
}

.pipe-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.pipe-row + .pipe-row {
  border-top: 1px solid var(--line);
}

.pipe-icon {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #cbd5e1;
}

.pipe-text {
  font-size: 13px;
  color: var(--fg-1);
}

.pipe-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}
</style>
