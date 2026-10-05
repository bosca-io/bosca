<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Calendar — Everything scheduled, one calendar',
  description: 'Bosca Calendar pairs calendars and events with repeating schedules, participants with RSVP statuses, and content attachments — plus read-only overlays that project scheduled campaigns, jobs, and content publishes onto the same grid.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Calendar', path: '/discover/calendar' }
], '/og-calendar.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Create calendars',
    body: 'Each calendar is a named, colored container backed by a content record — so who can see or edit it follows the same permissions as the rest of your content.'
  },
  {
    step: '02',
    title: 'Schedule events',
    body: 'One-off, or repeating on whatever rhythm you need. Invite participants with roles and RSVP statuses, and attach the content that matters — an agenda, the minutes, a collection of materials.'
  },
  {
    step: '03',
    title: 'See the whole picture',
    body: 'Month, week, and day views with drag to move and resize — plus read-only overlays that put scheduled campaigns, jobs, and content publishes on the same grid.'
  }
]

const FEATURES = [
  {
    icon: 'repeat',
    title: 'Repeats that hold up',
    body: 'Daily, weekly, monthly, or yearly — every second week, weekdays only, ending after ten times or on a date — on the same standard the major calendars use.'
  },
  {
    icon: 'scissors',
    title: 'Surgical series edits',
    body: 'Change one occurrence, this-and-following, or the whole series — real exceptions and splits, not workarounds.'
  },
  {
    icon: 'users',
    title: 'Participants & RSVPs',
    body: 'Invite profiles to an event with a role and an RSVP status — accepted, declined, or still pending — stored on the participant record.'
  },
  {
    icon: 'paperclip',
    title: 'Content attachments',
    body: 'Link documents or collections to an event under a relationship label — agenda, minutes, or anything you name.'
  },
  {
    icon: 'layers',
    title: 'Platform overlays',
    body: 'Read-only synthetic calendars project scheduled campaigns, jobs, and content publishes onto the same views as your events.'
  },
  {
    icon: 'monitor',
    title: 'Month, week & day',
    body: 'Three views in Studio with direct manipulation — drag an event to move it, drag its edge to resize it.'
  },
  {
    icon: 'clock',
    title: 'On-demand expansion',
    body: 'Repeating events are computed for the window you\'re looking at — no stored copies to drift out of sync, and runaway rules are capped.'
  },
  {
    icon: 'shield',
    title: 'Content-backed permissions',
    body: 'A calendar is backed by a content record, so visibility and editing follow your existing content permissions — no second ACL.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="calendar"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Calendar
        </p>
        <h1 class="load-2">
          Everything scheduled,<br>
          <em>one calendar.</em>
        </h1>
        <p class="hero-sub load-3">
          Calendars and events with repeating schedules that survive real
          life, participants with RSVP statuses, and attachments that point
          at actual content — plus overlays for everything else the platform
          has scheduled.
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
        <div class="week-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">occurrences · Mar 9 – 13</span>
          </div>
          <div class="week-body">
            <div class="week-row">
              <span class="week-time">Mon 09:00</span>
              <span
                class="week-chip"
                style="--chip: #7659f2"
              >Standup</span>
              <span class="week-note">↻ weekly</span>
            </div>
            <div class="week-row">
              <span class="week-time">Tue 14:00</span>
              <span
                class="week-chip"
                style="--chip: #7659f2"
              >Product review</span>
              <span class="week-note">exception · moved</span>
            </div>
            <div class="week-row">
              <span class="week-time">Thu 10:00</span>
              <span
                class="week-chip"
                style="--chip: #da9e30"
              >Spring campaign</span>
              <span class="week-note">overlay · campaign</span>
            </div>
            <div class="week-row">
              <span class="week-time">Fri 16:00</span>
              <span
                class="week-chip"
                style="--chip: #34d99a"
              >Landing page v2</span>
              <span class="week-note">overlay · publish</span>
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
        <h2>Plan, invite, <em>see it all.</em></h2>
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
          <h2>A real calendar. <em>And a platform lens.</em></h2>
          <p class="section-sub">
            Everything you expect from a calendar — recurrence, RSVPs, drag to
            reschedule — plus the view you only get when the calendar lives
            inside the platform doing the scheduling.
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
            Built on content, fed by the platform
          </p>
          <h2>The platform's schedule, <em>on your grid</em></h2>
          <p class="section-sub">
            The calendar isn't a silo with its own accounts and its own idea
            of a document — it's built from the platform's own parts, and it
            shows the platform's own plans.
          </p>
          <ul class="point-list">
            <li>A calendar is a content record (<code>bosca/v-calendar</code>) — its name and its permissions come from the content system, so sharing a calendar is sharing content.</li>
            <li>Three synthetic feeds project the platform's schedule onto the grid: audience campaigns, scheduler jobs, and content publishes — read-only, each with a completed flag.</li>
            <li>Attachments point at real content — link the agenda document or a collection of materials to the meeting itself.</li>
            <li>Everything is a typed GraphQL operation — query occurrences for a window across chosen calendars, edit events and series, update RSVPs.</li>
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
                name="library"
                :size="15"
              /></span>
              <span class="integ-text">Calendar → content record <code>bosca/v-calendar</code></span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="megaphone"
                :size="15"
              /></span>
              <span class="integ-text">Overlay · campaign <code>Spring launch</code></span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="upload"
                :size="15"
              /></span>
              <span class="integ-text">Overlay · publish <code>Landing page v2</code></span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="braces"
                :size="15"
              /></span>
              <span class="integ-text">GraphQL · <code>occurrences(from, to, calendars)</code></span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverCalendarExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#7659f2"
        class="closing-mark"
      />
      <h2>Calendar ships <em>with Bosca</em></h2>
      <p class="section-sub">
        Your meetings and the platform's own schedule, on one grid, under one
        permission model. The docs cover calendars, events, participants, and
        repeating series end to end.
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
  font-size: clamp(38px, 5vw, 58px);
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

/* ── Week window ─────────────────────────────── */

.week-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.week-body {
  padding: 10px 8px;
}

.week-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.week-row + .week-row {
  border-top: 1px solid var(--line);
}

.week-time {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
  min-width: 76px;
  flex-shrink: 0;
}

.week-chip {
  flex: 1;
  font-size: 13px;
  font-weight: 550;
  color: var(--fg-0);
  border-left: 3px solid var(--chip);
  background: color-mix(in srgb, var(--chip) 12%, transparent);
  border-radius: 4px;
  padding: 5px 10px;
}

.week-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  flex-shrink: 0;
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
  color: #ded5fb;
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
  color: #ded5fb;
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
</style>
