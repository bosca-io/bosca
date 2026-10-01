<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Calendar Overlays — The Platform\'s Own Schedule',
  description: 'Read-only synthetic calendars project scheduled campaigns, scheduler jobs, and content publishes onto the same month, week, and day views as your events — because the platform already knows what it has planned.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Calendar', path: '/discover/calendar' },
  { name: 'Overlays', path: '/discover/calendar/overlays' }
], '/og-calendar.png')
</script>

<template>
  <DiscoverShell section-id="calendar">
    <section class="page-hero">
      <p class="kicker load-1">
        Platform overlays
      </p>
      <h1 class="load-2">
        The platform knows <em>what's coming</em>
      </h1>
      <p class="section-sub load-3">
        Campaigns are scheduled, jobs are queued, publishes are timed — that
        schedule already exists inside Bosca. Overlays put it on the calendar
        next to your own events, instead of leaving it scattered across admin
        pages.
      </p>
    </section>

    <!-- ── Three sources ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Three sources
          </p>
          <h2>Campaigns, jobs, <em>publishes</em></h2>
          <p class="section-sub">
            Three synthetic feeds project other subsystems' schedules into
            calendar events — queried by date range like everything else.
          </p>
          <ul class="point-list">
            <li><strong>Campaigns</strong> — scheduled audience campaigns, so the send date sits next to the meetings about it.</li>
            <li><strong>Scheduled jobs</strong> — what the scheduler will run and when.</li>
            <li><strong>Publishes</strong> — content scheduled to go live, visible before it does.</li>
            <li>Each synthetic event carries its source, title, times, and a completed flag — enough to see at a glance what's done and what's still ahead.</li>
          </ul>
        </div>
        <div class="overlay-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">overlays · this week</span>
          </div>
          <div class="overlay-body">
            <div class="overlay-row">
              <span
                class="overlay-source"
                style="--src: #da9e30"
              >campaign</span>
              <span class="overlay-title">Spring launch email</span>
              <span class="overlay-state">Thu 10:00</span>
            </div>
            <div class="overlay-row">
              <span
                class="overlay-source"
                style="--src: #ef4444"
              >job</span>
              <span class="overlay-title">nightly-sync</span>
              <span class="overlay-state">daily 02:00</span>
            </div>
            <div class="overlay-row">
              <span
                class="overlay-source"
                style="--src: #34d99a"
              >publish</span>
              <span class="overlay-title">Landing page v2</span>
              <span class="overlay-state done">completed</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Read-only by design ─────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Read-only by design
          </p>
          <h2>A lens, <em>not a second control panel</em></h2>
          <p class="section-sub">
            Overlays show the schedule; they don't own it. The systems that
            created those entries stay the single place to change them.
          </p>
          <ul class="point-list">
            <li>Overlay events can't be dragged, edited, or deleted from the calendar — they're projections, refreshed from their source of truth.</li>
            <li>Each overlay renders in its own color, toggled from the sidebar like any calendar.</li>
            <li>To change a campaign's send time or a publish date, you do it where that schedule lives — and the overlay reflects it.</li>
          </ul>
        </div>
        <div class="lens-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">sidebar</span>
          </div>
          <div class="lens-body">
            <div class="lens-row">
              <span
                class="lens-dot"
                style="--dot: #7659f2"
              />
              <span class="lens-name">Team calendar</span>
              <span class="lens-mode">editable</span>
            </div>
            <div class="lens-row">
              <span
                class="lens-dot"
                style="--dot: #da9e30"
              />
              <span class="lens-name">Campaigns</span>
              <span class="lens-mode ro">read-only</span>
            </div>
            <div class="lens-row">
              <span
                class="lens-dot"
                style="--dot: #ef4444"
              />
              <span class="lens-name">Scheduled jobs</span>
              <span class="lens-mode ro">read-only</span>
            </div>
            <div class="lens-row">
              <span
                class="lens-dot"
                style="--dot: #34d99a"
              />
              <span class="lens-name">Publishes</span>
              <span class="lens-mode ro">read-only</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Content-backed calendars ────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Content-backed calendars
          </p>
          <h2>A calendar is <em>content too</em></h2>
          <p class="section-sub">
            Your own calendars follow the same philosophy as the overlays:
            reuse the platform instead of duplicating it.
          </p>
          <ul class="point-list">
            <li>Every calendar is backed by a content record of type <code>bosca/v-calendar</code> — its name lives on the content, the calendar adds color and description.</li>
            <li>Permissions are inherited from that record — who can view, edit, or manage a calendar is decided by the content permissions you already run.</li>
            <li>Deleting a calendar removes its backing record, and its events go with it — one lifecycle, no orphans.</li>
            <li>In Studio, month, week, and day views render whichever calendars you've toggled visible, events colored by their calendar.</li>
          </ul>
        </div>
        <div class="cfg-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">Team calendar</span>
          </div>
          <div class="cfg-body">
            <div class="cfg-row">
              <span class="cfg-key">Backed by</span>
              <span class="cfg-val"><code>bosca/v-calendar</code></span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Color</span>
              <span class="cfg-val cfg-swatch"><span
                class="swatch"
                style="--sw: #7659f2"
              />#7659f2</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Permissions</span>
              <span class="cfg-val cfg-pill">inherited from content</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverCalendarExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Overlay window ──────────────────────────── */

.overlay-window,
.lens-window,
.cfg-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.overlay-body,
.lens-body,
.cfg-body {
  padding: 10px 8px;
}

.overlay-row,
.lens-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.overlay-row + .overlay-row,
.lens-row + .lens-row,
.cfg-row + .cfg-row {
  border-top: 1px solid var(--line);
}

.overlay-source {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--src);
  border: 1px solid color-mix(in srgb, var(--src) 45%, transparent);
  border-radius: 999px;
  padding: 2px 10px;
  min-width: 72px;
  text-align: center;
}

.overlay-title {
  flex: 1;
  font-size: 13px;
  color: var(--fg-0);
}

.overlay-state {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.overlay-state.done {
  color: #34d99a;
}

/* ── Lens window ─────────────────────────────── */

.lens-dot {
  width: 10px;
  height: 10px;
  flex-shrink: 0;
  border-radius: 3px;
  background: var(--dot);
}

.lens-name {
  flex: 1;
  font-size: 13px;
  color: var(--fg-0);
}

.lens-mode {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
}

.lens-mode.ro {
  color: var(--fg-3);
}

/* ── Config window ───────────────────────────── */

.cfg-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
}

.cfg-key {
  font-size: 13px;
  color: var(--fg-2);
}

.cfg-val {
  font-size: 13px;
  color: var(--fg-0);
}

.cfg-val code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

.cfg-swatch {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-mono);
  font-size: 12px;
}

.swatch {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  background: var(--sw);
}

.cfg-pill {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 10px;
}
</style>
