<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Boards & Sprints — See the work, run the work',
  description: 'Bosca boards are kanban or scrum, with columns mapped to your statuses, WIP limits, and swimlanes by assignee, epic, priority, or project. Sprints have a goal and dates, and route unfinished work to the next sprint or the backlog when they close.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Work Ops', path: '/discover/workops' },
  { name: 'Boards & Sprints', path: '/discover/workops/boards' }
], '/og-workops.png')

const COLUMNS = [
  { name: 'To Do', wip: null, cards: 2 },
  { name: 'In Progress', wip: 3, cards: 2 },
  { name: 'In Review', wip: 2, cards: 1 },
  { name: 'Done', wip: null, cards: 3 }
]

const LANES = ['assignee', 'epic', 'priority', 'project']
</script>

<template>
  <DiscoverShell section-id="workops">
    <section class="page-hero">
      <p class="kicker load-1">
        Boards &amp; Sprints
      </p>
      <h1 class="load-2">
        See the work, <em>run the work</em>
      </h1>
      <p class="section-sub load-3">
        A board shows where everything stands; a sprint gives it a start, an
        end, and a goal. Between them, your team sees the whole picture and
        moves it forward, without leaving the platform.
      </p>
    </section>

    <!-- ── Boards ──────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Boards
          </p>
          <h2>Kanban or scrum, <em>your columns</em></h2>
          <p class="section-sub">
            A board's columns map to your statuses, so moving a card moves the
            task. Set a limit on how many cards a column holds when you want to
            keep work in check, and group the rows by whatever matters — the
            person, the epic, the priority, or the project.
          </p>
          <ul class="point-list">
            <li>Kanban or scrum, with columns mapped to your own statuses.</li>
            <li>Limit the cards in a column to keep work moving.</li>
            <li>Split the board into rows by assignee, epic, priority, or project.</li>
          </ul>
        </div>
        <div class="board-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">board · Platform</span>
          </div>
          <div class="board-body">
            <div
              v-for="c in COLUMNS"
              :key="c.name"
              class="board-col"
            >
              <div class="board-col-head">
                <span class="board-col-name">{{ c.name }}</span>
                <span
                  v-if="c.wip"
                  class="board-wip"
                >{{ c.wip }}</span>
              </div>
              <div
                v-for="n in c.cards"
                :key="n"
                class="board-card"
              />
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Sprints ─────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Sprints
          </p>
          <h2>A start, an end, <em>a goal</em></h2>
          <p class="section-sub">
            Commit a set of tasks to a sprint, give it a goal and some dates, and
            start it. Anything added mid-sprint is tracked apart from what you
            committed. Closing the sprint has you account for every open task —
            the next sprint, or the backlog — before it wraps up.
          </p>
          <ul class="point-list">
            <li>Commit tasks to a sprint with a goal and a date range.</li>
            <li>Work added after the start is tracked apart from what you committed.</li>
            <li>Closing a sprint accounts for every open task first.</li>
          </ul>
        </div>
        <div class="sprint-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">sprint · Sprint 7</span>
          </div>
          <div class="sprint-body">
            <div class="sprint-top">
              <span class="sprint-state">Active</span>
              <span class="sprint-dates">Sep 16 – Sep 30</span>
            </div>
            <div class="sprint-goal">
              Ship the live sessions dashboard
            </div>
            <div class="sprint-nums">
              <div class="sprint-num">
                <strong>12</strong><span>committed</span>
              </div>
              <div class="sprint-num">
                <strong>3</strong><span>added mid-sprint</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Across projects ─────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          One board, many projects
        </p>
        <h2>See it all <em>in one place</em></h2>
        <p class="section-sub">
          A board isn't stuck to one project. Point it at a single project, a
          whole program or portfolio, or a set of projects you pick — then group
          the rows the way that helps most.
        </p>
      </div>
      <div class="scope-grid reveal">
        <div class="scope-card">
          <span class="scope-icon"><Icon
            name="layers"
            :size="16"
          /></span>
          <h3>Scope it</h3>
          <p>One project, a program, a portfolio, or a hand-picked set of projects on a single board.</p>
        </div>
        <div class="scope-card">
          <span class="scope-icon"><Icon
            name="rows-3"
            :size="16"
          /></span>
          <h3>Group it</h3>
          <p>Split the rows by {{ LANES.join(', ') }}, or a saved filter of your own.</p>
        </div>
      </div>
    </section>

    <DiscoverWorkopsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.board-window,
.sprint-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Board window ────────────────────────────── */

.board-body {
  padding: 16px 14px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.board-col {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.board-col-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  margin-bottom: 3px;
}

.board-col-name {
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.board-wip {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 4px;
  padding: 0 4px;
}

.board-card {
  height: 26px;
  border-radius: var(--r-xs);
  border: 1px solid var(--line);
  background: color-mix(in srgb, var(--bg-1) 60%, transparent);
}

.board-col:nth-child(2) .board-card:first-child {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
}

/* ── Sprint window ───────────────────────────── */

.sprint-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.sprint-top {
  display: flex;
  align-items: center;
  gap: 12px;
}

.sprint-state {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: #34d99a;
  border: 1px solid color-mix(in srgb, #34d99a 40%, transparent);
  border-radius: 999px;
  padding: 2px 10px;
}

.sprint-dates {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.sprint-goal {
  font-size: 15.5px;
  font-weight: 650;
  color: var(--fg-0);
}

.sprint-nums {
  display: flex;
  gap: 10px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.sprint-num {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sprint-num strong {
  font-size: 20px;
  font-weight: 700;
  color: var(--accent);
}

.sprint-num span {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--fg-3);
}

/* ── Scope cards ─────────────────────────────── */

.scope-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.scope-card {
  padding: 24px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.scope-icon {
  width: 34px;
  height: 34px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
  margin-bottom: 14px;
}

.scope-card h3 {
  font-size: 16px;
  font-weight: 650;
  margin: 0 0 8px;
}

.scope-card p {
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 620px) {
  .scope-grid {
    grid-template-columns: 1fr;
  }
}
</style>
