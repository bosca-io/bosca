<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Calendar Events — Fields, RSVPs & Attachments',
  description: 'Events carry a title, times, location, and an all-day flag; participants join with a role and an RSVP status; and attachments link real platform content — an agenda document, the minutes, a collection of materials.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Calendar', path: '/discover/calendar' },
  { name: 'Events', path: '/discover/calendar/events' }
], '/og-calendar.png')
</script>

<template>
  <DiscoverShell section-id="calendar">
    <section class="page-hero">
      <p class="kicker load-1">
        Events &amp; participants
      </p>
      <h1 class="load-2">
        The event, the people, <em>the materials</em>
      </h1>
      <p class="section-sub load-3">
        An event isn't just a block of time — it carries the people invited,
        their answers, and links to the actual content the meeting is about.
      </p>
    </section>

    <!-- ── The event ───────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The event
          </p>
          <h2>A block of time, <em>fully described</em></h2>
          <p class="section-sub">
            Every event belongs to a calendar and carries the fields a
            scheduler actually needs — no more, no less.
          </p>
          <ul class="point-list">
            <li>A required title, an optional description and free-text location.</li>
            <li>Timezone-aware start and end timestamps — an event can never end before it starts; the database enforces it.</li>
            <li>An all-day flag for date-scoped entries alongside timed ones.</li>
            <li>Set it to repeat and the event becomes a series — see <NuxtLink to="/discover/calendar/recurrence">Recurrence</NuxtLink>.</li>
          </ul>
        </div>
        <div class="event-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">event</span>
          </div>
          <div class="event-body">
            <div class="event-field">
              <span class="event-key">Title</span>
              <span class="event-val">Q3 planning</span>
            </div>
            <div class="event-field">
              <span class="event-key">When</span>
              <span class="event-val"><code>Tue 14:00 – 15:30</code></span>
            </div>
            <div class="event-field">
              <span class="event-key">Location</span>
              <span class="event-val">Riverside room</span>
            </div>
            <div class="event-field">
              <span class="event-key">All day</span>
              <span class="event-val event-muted">off</span>
            </div>
            <div class="event-field">
              <span class="event-key">Repeats</span>
              <span class="event-val">Weekly on Tuesday</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Participants ────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Participants
          </p>
          <h2>Invitations with <em>honest answers</em></h2>
          <p class="section-sub">
            A participant binds a profile to an event — one row per person,
            with their role and their current answer.
          </p>
          <ul class="point-list">
            <li>Each participant carries a role — <code>attendee</code> by default — and an RSVP status: accepted, declined, or still pending.</li>
            <li>Participants are platform profiles, so the invite list is searched from the people you already have.</li>
            <li>Adding is an upsert and removing is explicit — the event editor diffs your working list and applies exactly the changes you made.</li>
            <li>Status lives on the participant record, so a response is data you can query — not a flag lost in an inbox.</li>
          </ul>
        </div>
        <div class="people-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">participants · 3</span>
          </div>
          <div class="people-body">
            <div class="people-row">
              <span class="people-name">Amara Osei</span>
              <span class="people-role">organizer</span>
              <span class="people-status ok">accepted</span>
            </div>
            <div class="people-row">
              <span class="people-name">Jonah Reyes</span>
              <span class="people-role">attendee</span>
              <span class="people-status ok">accepted</span>
            </div>
            <div class="people-row">
              <span class="people-name">Mei Lin</span>
              <span class="people-role">attendee</span>
              <span class="people-status pending">pending</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Attachments ─────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Attachments
          </p>
          <h2>The meeting and <em>its materials</em></h2>
          <p class="section-sub">
            Attachments aren't uploads bolted onto an event — they're links to
            content that already lives in the platform.
          </p>
          <ul class="point-list">
            <li>An attachment points at a document or a collection — one target per link.</li>
            <li>Each link carries a relationship label — <code>attachment</code> by default, or something more precise like <code>agenda</code> or <code>minutes</code>.</li>
            <li>Because the target is real platform content, it keeps its own permissions, versions, and life after the meeting ends.</li>
          </ul>
        </div>
        <div class="attach-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">attachments</span>
          </div>
          <div class="attach-body">
            <div class="attach-row">
              <span class="attach-label">agenda</span>
              <span class="attach-target"><Icon
                name="doc"
                :size="13"
              /> Q3 planning agenda</span>
            </div>
            <div class="attach-row">
              <span class="attach-label">minutes</span>
              <span class="attach-target"><Icon
                name="doc"
                :size="13"
              /> Q3 planning notes</span>
            </div>
            <div class="attach-row">
              <span class="attach-label">materials</span>
              <span class="attach-target"><Icon
                name="folder"
                :size="13"
              /> Q3 research collection</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverCalendarExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Event window ────────────────────────────── */

.event-window,
.people-window,
.attach-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.event-body,
.people-body,
.attach-body {
  padding: 10px 8px;
}

.event-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
}

.event-field + .event-field,
.people-row + .people-row,
.attach-row + .attach-row {
  border-top: 1px solid var(--line);
}

.event-key {
  font-size: 13px;
  color: var(--fg-2);
}

.event-val {
  font-size: 13px;
  color: var(--fg-0);
}

.event-val code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

.event-muted {
  color: var(--fg-3);
}

/* ── People window ───────────────────────────── */

.people-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.people-name {
  flex: 1;
  font-size: 13px;
  color: var(--fg-0);
}

.people-role {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 10px;
}

.people-status {
  font-family: var(--font-mono);
  font-size: 11px;
}

.people-status.ok { color: #34d99a; }
.people-status.pending { color: #f6c453; }

/* ── Attachments window ──────────────────────── */

.attach-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 14px;
}

.attach-label {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 999px;
  padding: 2px 10px;
  min-width: 76px;
  text-align: center;
}

.attach-target {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--fg-1);
}
</style>
