<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Tasks & Workflows — Work items shaped to your process',
  description: 'A Bosca task carries a type, status, priority, resolution, assignee, estimates, worklog, links, and labels — all of which your team defines per project, moving through the workflows you set for each task type.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Work Ops', path: '/discover/workops' },
  { name: 'Tasks & Workflows', path: '/discover/workops/tasks' }
], '/og-workops.png')

const LEVELS = [
  { name: 'Initiative', body: 'A big goal that spans work.' },
  { name: 'Epic', body: 'A chunk of that goal.' },
  { name: 'Standard', body: 'A story, task, or bug.' },
  { name: 'Subtask', body: 'A step within one.' }
]

const FLOW = ['To Do', 'In Progress', 'In Review', 'Done']
</script>

<template>
  <DiscoverShell section-id="workops">
    <section class="page-hero">
      <p class="kicker load-1">
        Tasks &amp; Workflows
      </p>
      <h1 class="load-2">
        Work items, <em>shaped to your process</em>
      </h1>
      <p class="section-sub load-3">
        A task is the unit of work — and a rich one. It carries who's on it,
        what it's blocked by, how long it's taken, and where it is in your
        process, with the types and statuses set the way your team already
        works.
      </p>
    </section>

    <!-- ── The task ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            A rich work item
          </p>
          <h2>More than <em>a checkbox</em></h2>
          <p class="section-sub">
            Each task has an assignee and a reporter, a priority and a status,
            estimates and logged time, labels, and links to other tasks. Watch
            one to follow it, or leave a comment — it's the full record of a
            piece of work.
          </p>
          <ul class="point-list">
            <li>Assignee, reporter, watchers, priority, and labels.</li>
            <li>Estimates and logged time, start and due dates.</li>
            <li>Links to the tasks it blocks, duplicates, or relates to.</li>
          </ul>
        </div>
        <div class="task-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">BOS-142</span>
          </div>
          <div class="task-body">
            <div class="task-top">
              <span class="task-badge type">Story</span>
              <span class="task-badge status">In Progress</span>
              <span class="task-badge prio">High</span>
            </div>
            <div class="task-title">
              Add the live sessions map
            </div>
            <div class="task-rows">
              <div class="task-r">
                <span class="task-k">assignee</span><span>Ada Lovelace</span>
              </div>
              <div class="task-r">
                <span class="task-k">estimate</span><span>2d · logged 6h</span>
              </div>
              <div class="task-r">
                <span class="task-k">labels</span><span>dashboard · maps</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Configurable ────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Your reference data
          </p>
          <h2>Types that <em>nest, terms that are yours</em></h2>
          <p class="section-sub">
            Task types are organized into four levels, so an initiative nests
            over epics, standard items like stories and bugs, and their subtasks.
            Statuses, priorities, resolutions, and link types are yours to define
            and compose into each project's workflow — while labels, versions,
            and components are set per project.
          </p>
          <ul class="point-list">
            <li>Four hierarchy levels: initiative, epic, standard, and subtask.</li>
            <li>Define your own statuses, priorities, resolutions, and link types.</li>
            <li>Add labels, versions, and components per project.</li>
          </ul>
        </div>
        <div class="levels-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">task types</span>
          </div>
          <div class="levels-body">
            <div
              v-for="(l, i) in LEVELS"
              :key="l.name"
              class="level-row"
              :style="{ marginLeft: `${i * 18}px` }"
            >
              <span class="level-dot" />
              <span class="level-name">{{ l.name }}</span>
              <span class="level-note">{{ l.body }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Workflows ───────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Workflows
          </p>
          <h2>The moves <em>you allow</em></h2>
          <p class="section-sub">
            A workflow gives a task type its statuses and the moves allowed
            between them — so a task goes from one status to the next along paths
            you set, not any which way. A transition can require a resolution, to
            record how the task ended.
          </p>
          <ul class="point-list">
            <li>Each task type follows the workflow you give it.</li>
            <li>Only the transitions you define are allowed.</li>
            <li>A transition can require a resolution to record the outcome.</li>
          </ul>
        </div>
        <div class="wf-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">workflow · Story</span>
          </div>
          <div class="wf-body">
            <div class="wf-flow">
              <template
                v-for="(s, i) in FLOW"
                :key="s"
              >
                <span
                  class="wf-state"
                  :class="{ done: i === FLOW.length - 1 }"
                >{{ s }}</span>
                <Icon
                  v-if="i < FLOW.length - 1"
                  name="arrow-right"
                  :size="12"
                  class="wf-arrow"
                />
              </template>
            </div>
            <div class="wf-note">
              A transition can require a resolution · Fixed / Won't Fix / Duplicate
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverWorkopsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.task-window,
.levels-window,
.wf-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Task window ─────────────────────────────── */

.task-body {
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.task-top {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.task-badge {
  font-family: var(--font-mono);
  font-size: 10.5px;
  border-radius: 999px;
  padding: 3px 10px;
  border: 1px solid var(--line-2);
  color: var(--fg-2);
}

.task-badge.type {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
}

.task-badge.status { color: #e0a23a; border-color: color-mix(in srgb, #e0a23a 40%, transparent); }
.task-badge.prio { color: #f2757f; border-color: color-mix(in srgb, #f2757f 40%, transparent); }

.task-title {
  font-size: 16px;
  font-weight: 650;
  color: var(--fg-0);
}

.task-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.task-r {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.task-k { color: var(--fg-3); }

/* ── Levels window ───────────────────────────── */

.levels-body {
  padding: 18px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.level-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.level-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex-shrink: 0;
  background: var(--accent);
}

.level-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-1);
  width: 82px;
}

.level-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Workflow window ─────────────────────────── */

.wf-body {
  padding: 22px 18px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.wf-flow {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.wf-state {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  padding: 7px 11px;
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.wf-state.done {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.wf-arrow {
  color: var(--accent);
  flex-shrink: 0;
}

.wf-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 560px) {
  .level-note {
    display: none;
  }
}
</style>
