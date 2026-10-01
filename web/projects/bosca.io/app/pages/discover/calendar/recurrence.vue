<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Calendar Recurrence — Repeating Events Done Right',
  description: 'Repeating events on any rhythm — daily, weekly, monthly, or yearly, with intervals, chosen weekdays, and clean end conditions. Edit one occurrence, split a series, or end it cleanly; occurrences appear on demand for the window you view.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Calendar', path: '/discover/calendar' },
  { name: 'Recurrence', path: '/discover/calendar/recurrence' }
], '/og-calendar.png')
</script>

<template>
  <DiscoverShell section-id="calendar">
    <section class="page-hero">
      <p class="kicker load-1">
        Recurrence
      </p>
      <h1 class="load-2">
        Series that survive <em>real life</em>
      </h1>
      <p class="section-sub load-3">
        Repeating events are where calendars usually fall apart — the moved
        standup, the cancelled week, the "new time starting next month."
        Bosca models all of it with real schedules, real exceptions, and
        clean splits.
      </p>
    </section>

    <!-- ── The rule ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The schedule
          </p>
          <h2>Any rhythm, <em>not a preset list</em></h2>
          <p class="section-sub">
            Repeating events run on the same recurrence standard the major
            calendars speak — so any rhythm you can schedule elsewhere, you
            can schedule here.
          </p>
          <ul class="point-list">
            <li>Daily, weekly, monthly, or yearly, with an interval — every week, every second week, every third month.</li>
            <li>Pick the weekdays — Mondays and Wednesdays, weekdays only, whatever the rhythm calls for.</li>
            <li>End after a number of times or on a date — one or the other, never an ambiguous both.</li>
            <li>Studio's recurrence editor builds the schedule visually; the API accepts the standard rule format directly.</li>
          </ul>
        </div>
        <div class="rhythm-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">repeats</span>
          </div>
          <div class="rhythm-body">
            <div class="rhythm-row">
              <span class="rhythm-rule">Weekly · Mon &amp; Wed</span>
              <span class="rhythm-end">ends after 10 times</span>
            </div>
            <div class="rhythm-row">
              <span class="rhythm-rule">Every 2 weeks · Friday</span>
              <span class="rhythm-end">until Jun 30</span>
            </div>
            <div class="rhythm-row">
              <span class="rhythm-rule">Monthly · the 1st</span>
              <span class="rhythm-end">never ends</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Expansion ───────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Expansion
          </p>
          <h2>Occurrences on demand, <em>never a copy table</em></h2>
          <p class="section-sub">
            A series is one event, not a thousand copies. Occurrences are
            computed for the window you're looking at, every time.
          </p>
          <ul class="point-list">
            <li>Open any date range and each visible series unfolds into its occurrences — there are no stored copies to drift out of sync.</li>
            <li>Every occurrence knows which series it belongs to and whether it's been edited into an exception.</li>
            <li>Every occurrence carries the exact time the series was set up with — consistent from the first to the last.</li>
            <li>Cancelled dates stay cancelled, and expansion is capped per series — a runaway rule can't take the calendar down with it.</li>
          </ul>
        </div>
        <div class="expand-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">this month · weekly on Mon &amp; Wed</span>
          </div>
          <div class="expand-body">
            <div class="expand-row">
              <span class="expand-date">Mon Mar 09 · 09:00</span>
              <span class="expand-tag">occurrence</span>
            </div>
            <div class="expand-row">
              <span class="expand-date">Wed Mar 11 · 09:00</span>
              <span class="expand-tag skip">cancelled</span>
            </div>
            <div class="expand-row">
              <span class="expand-date">Mon Mar 16 · 10:00</span>
              <span class="expand-tag exc">exception · moved</span>
            </div>
            <div class="expand-row">
              <span class="expand-date">Wed Mar 18 · 09:00</span>
              <span class="expand-tag">occurrence</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Edit scopes ─────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Edit scopes
          </p>
          <h2>This one, from here on, <em>or all of them</em></h2>
          <p class="section-sub">
            Every edit or delete on a series asks one question — how far
            should this change reach? — and each answer maps to a precise
            operation.
          </p>
          <ul class="point-list">
            <li><strong>This occurrence</strong> — saves an exception for that date only; each series keeps at most one override per occurrence.</li>
            <li><strong>This and following</strong> — splits the series: the old schedule ends just before the split, and a new series carries the changes forward.</li>
            <li><strong>All</strong> — changes the whole series at once; exceptions that no longer fit the new schedule are cleaned up automatically.</li>
            <li>Deleting scopes the same way — cancel a single occurrence, end the series at a chosen date, or delete the whole thing outright.</li>
          </ul>
        </div>
        <div class="scope-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">apply changes to…</span>
          </div>
          <div class="scope-body">
            <div class="scope-row selected">
              <span class="scope-radio on" />
              <span class="scope-text">This occurrence<span class="scope-note">saves an exception</span></span>
            </div>
            <div class="scope-row">
              <span class="scope-radio" />
              <span class="scope-text">This and following<span class="scope-note">splits the series</span></span>
            </div>
            <div class="scope-row">
              <span class="scope-radio" />
              <span class="scope-text">All events<span class="scope-note">changes the whole series</span></span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverCalendarExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Rhythm window ───────────────────────────── */

.rhythm-body {
  padding: 10px 8px;
}

.rhythm-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px;
}

.rhythm-row + .rhythm-row {
  border-top: 1px solid var(--line);
}

.rhythm-rule {
  font-size: 13.5px;
  font-weight: 550;
  color: var(--fg-0);
}

.rhythm-end {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

/* ── Expansion window ────────────────────────── */

.rhythm-window,
.expand-window,
.scope-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.expand-body,
.scope-body {
  padding: 10px 8px;
}

.expand-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
}

.expand-row + .expand-row,
.scope-row + .scope-row {
  border-top: 1px solid var(--line);
}

.expand-date {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.expand-tag {
  font-family: var(--font-mono);
  font-size: 11px;
  color: #34d99a;
}

.expand-tag.skip {
  color: var(--fg-3);
  text-decoration: line-through;
}

.expand-tag.exc {
  color: #f6c453;
}

/* ── Scope window ────────────────────────────── */

.scope-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
}

.scope-row.selected {
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

.scope-radio {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  border-radius: 999px;
  border: 1.5px solid var(--fg-3);
}

.scope-radio.on {
  border-color: var(--accent);
  box-shadow: inset 0 0 0 3.5px var(--bg-0), inset 0 0 0 10px var(--accent);
}

.scope-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13.5px;
  color: var(--fg-0);
}

.scope-note {
  font-size: 11.5px;
  color: var(--fg-3);
}
</style>
