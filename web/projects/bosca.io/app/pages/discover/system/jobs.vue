<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Jobs & Scheduling — Background work that finishes',
  description: 'Bosca runs background jobs on a durable queue — NATS JetStream or Redis — with automatic retries and work that starts only once the change that created it commits. A real cron scheduler with catch-up, plus one history of every run, scheduled or event-driven, that you can cancel.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'System', path: '/discover/system' },
  { name: 'Jobs & Scheduling', path: '/discover/system/jobs' }
], '/og-system.png')

const JOB = [
  { k: 'queue', v: 'notifications' },
  { k: 'retry', v: '2 of 10' },
  { k: 'backoff', v: 'growing delay' },
  { k: 'enqueue', v: 'after commit' }
]

const SCHED = [
  { name: 'nightly-backup', cron: '0 3 * * *', next: 'next 03:00', on: true },
  { name: 'index-rebuild', cron: '*/15 * * * *', next: 'next 12:15', on: true },
  { name: 'cleanup-temp', cron: '0 0 * * 0', next: 'disabled', on: false }
]

const HISTORY = [
  { name: 'index rebuild', src: 'scheduler', state: 'complete', tone: 'ok' },
  { name: 'profile sync', src: 'event', state: 'running', tone: 'active' },
  { name: 'webhook deliver', src: 'event', state: 'retry 2/10', tone: 'warn' }
]
</script>

<template>
  <DiscoverShell section-id="system">
    <section class="page-hero">
      <p class="kicker load-1">
        Jobs &amp; Scheduling
      </p>
      <h1 class="load-2">
        Background work <em>that finishes</em>
      </h1>
      <p class="section-sub load-3">
        The async engine underneath the platform: a durable job queue that
        retries, a real cron scheduler, and one record of every run — scheduled
        or event-driven.
      </p>
    </section>

    <!-- ── Durable jobs ────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Durable jobs
          </p>
          <h2>A queue that <em>doesn't drop work</em></h2>
          <p class="section-sub">
            Background work runs on a durable queue — NATS JetStream or Redis,
            your choice. If a job fails, it retries on its own with a growing
            delay, and anything that stalls gets picked back up. And a job only
            starts once the change that created it is committed, so nothing fires
            on work that rolled back.
          </p>
          <ul class="point-list">
            <li>Runs on NATS JetStream or Redis, your choice.</li>
            <li>Failed jobs retry on their own, with a growing delay.</li>
            <li>Stalled jobs are picked back up.</li>
            <li>A job starts only after its change is committed.</li>
          </ul>
        </div>
        <div class="rec-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">job · webhook-deliver</span>
          </div>
          <div class="rec-body">
            <div
              v-for="row in JOB"
              :key="row.k"
              class="rec-row"
            >
              <span class="rec-k">{{ row.k }}</span>
              <span class="rec-v">{{ row.v }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Scheduler ───────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Scheduler
          </p>
          <h2>Cron, <em>done properly</em></h2>
          <p class="section-sub">
            Scheduled jobs run on standard cron, validated before they're saved.
            Enable or disable one, run it now, and see when it last ran and runs
            next. Missed runs can catch up to a limit you set, and you decide
            whether a job may overlap itself.
          </p>
          <ul class="point-list">
            <li>Standard five-field cron, validated up front.</li>
            <li>Enable, disable, or run a job on demand.</li>
            <li>Catch up missed runs, up to a limit you set.</li>
            <li>Allow or forbid a job from overlapping itself.</li>
          </ul>
        </div>
        <div class="sched-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">scheduler</span>
          </div>
          <div class="sched-body">
            <div
              v-for="s in SCHED"
              :key="s.name"
              class="sched-row"
            >
              <span
                class="sched-dot"
                :class="{ on: s.on }"
              />
              <span class="sched-name">{{ s.name }}</span>
              <span class="sched-cron">{{ s.cron }}</span>
              <span class="sched-next">{{ s.next }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Run history ─────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Run history
          </p>
          <h2>Every run, <em>in one place</em></h2>
          <p class="section-sub">
            Whether a job ran on a schedule or fired in reaction to an event, it
            lands in one history — what ran, when it started and finished, and how
            it ended. Filter by status or by source, and cancel a job that's still
            pending or running.
          </p>
          <ul class="point-list">
            <li>One history for scheduled and event-driven runs.</li>
            <li>See what ran, when, and how it ended.</li>
            <li>Filter by status or by source.</li>
            <li>Cancel a pending or running job.</li>
          </ul>
        </div>
        <div class="job-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">jobs · history</span>
          </div>
          <div class="job-body">
            <div
              v-for="j in HISTORY"
              :key="j.name"
              class="job-row"
            >
              <span class="job-name">{{ j.name }}</span>
              <span class="job-src">{{ j.src }}</span>
              <span
                class="job-state"
                :class="j.tone"
              >{{ j.state }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverSystemExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.rec-window,
.sched-window,
.job-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Record window (job detail) ──────────────── */

.rec-body {
  padding: 12px 10px;
}

.rec-row {
  display: flex;
  align-items: baseline;
  gap: 14px;
  padding: 14px 12px;
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.rec-row + .rec-row {
  border-top: 1px solid var(--line);
}

.rec-k {
  width: 74px;
  flex-shrink: 0;
  color: var(--fg-3);
}

.rec-v { color: var(--fg-1); }

/* ── Scheduler window ────────────────────────── */

.sched-body {
  padding: 12px 10px;
}

.sched-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
}

.sched-row + .sched-row {
  border-top: 1px solid var(--line);
}

.sched-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex-shrink: 0;
  background: var(--fg-3);
}

.sched-dot.on {
  background: #34d99a;
  box-shadow: 0 0 8px color-mix(in srgb, #34d99a 55%, transparent);
}

.sched-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.sched-cron {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}

.sched-next {
  width: 76px;
  text-align: right;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Job history window ──────────────────────── */

.job-body {
  padding: 12px 10px;
}

.job-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
}

.job-row + .job-row {
  border-top: 1px solid var(--line);
}

.job-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.job-src {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.job-state {
  width: 88px;
  text-align: right;
  font-family: var(--font-mono);
  font-size: 10.5px;
}

.job-state.ok { color: #34d99a; }
.job-state.active { color: #e0a23a; }
.job-state.warn { color: #f2757f; }

/* ── Responsive ──────────────────────────────── */

@media (max-width: 520px) {
  .sched-cron {
    display: none;
  }
}
</style>
