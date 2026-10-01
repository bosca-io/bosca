<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Form Task Intake — A Form in Front, a Task Behind It',
  description: 'A Work Ops form opens a task from each submission. Set defaults for project, type, and priority, or map them from the form\'s own fields — and scaffold a ready-made intake form in one click.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Forms', path: '/discover/forms' },
  { name: 'Task intake', path: '/discover/forms/tasks' }
], '/og-forms.png')
</script>

<template>
  <DiscoverShell section-id="forms">
    <section class="page-hero">
      <p class="kicker load-1">
        Task intake
      </p>
      <h1 class="load-2">
        A form in front, <em>a task behind it</em>
      </h1>
      <p class="section-sub load-3">
        Requests come in as forms; work happens as tasks. A Work Ops form
        connects the two — each submission opens a task, filed where you
        decided it should go.
      </p>
    </section>

    <!-- ── Intake ──────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Intake
          </p>
          <h2>From "can someone…" <em>to on the board</em></h2>
          <p class="section-sub">
            Facility requests, prayer follow-ups, bug reports, event help —
            anything people ask for through a form arrives as a task your
            team already knows how to handle.
          </p>
          <ul class="point-list">
            <li><strong>Every submission opens a task</strong> — filed in a project, typed, and prioritized.</li>
            <li><strong>The submission stays attached</strong> — the answers that created the task remain in the submissions queue.</li>
            <li><strong>Requesters don't need Work Ops</strong> — they see a form; your team sees a task.</li>
          </ul>
        </div>
        <div class="intake-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">Facilities request</span>
          </div>
          <div class="intake-body">
            <div class="in-field">
              <span class="in-label">What needs attention?</span>
              <span class="in-input">Projector bulb is out in room 204</span>
            </div>
            <div class="in-task">
              <Icon
                name="kanban"
                :size="13"
              />
              <span>task <code>FAC-142</code> · Facilities · High</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Mapping ─────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Mapping
          </p>
          <h2>You decide <em>what fills the task</em></h2>
          <p class="section-sub">
            For each task field, choose where its value comes from: a default
            you set on the form, or an answer the submitter gives.
          </p>
          <ul class="point-list">
            <li><strong>Defaults per form</strong> — a fixed project, task type, and priority when the form shouldn't ask.</li>
            <li><strong>Or from the form</strong> — map task fields — summary, description, assignee, due date, and more — to the form's own fields.</li>
            <li><strong>Ask only what you must</strong> — a public request form can stay simple while the task still lands fully filed.</li>
          </ul>
        </div>
        <div class="mapping-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">task mapping</span>
          </div>
          <div class="cfg-body">
            <div class="cfg-row">
              <span class="cfg-key">Project</span>
              <span class="cfg-val cfg-pill">Facilities</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Priority</span>
              <span class="cfg-val">from form field</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Summary</span>
              <span class="cfg-val">from form field</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Due date</span>
              <span class="cfg-val">from form field</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Scaffold ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Starting point
          </p>
          <h2>An intake form, <em>in one click</em></h2>
          <p class="section-sub">
            Starting from a blank canvas? Scaffold generates a working intake
            form — a project selector, a task-type and priority row, a
            summary, and a description — and you shape it from there.
          </p>
          <ul class="point-list">
            <li><strong>A working form first</strong> — scaffold, then rename, rearrange, and trim like any other form.</li>
            <li><strong>Selectors wired to Work Ops</strong> — the generated project, type, and priority fields list what your Work Ops actually has.</li>
          </ul>
        </div>
        <div class="scaffold-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">scaffolded layout</span>
          </div>
          <div class="scaffold-body">
            <div class="sc-node">
              Project <span class="sc-tag">Work Ops Select</span>
            </div>
            <div class="sc-row">
              <div class="sc-node">
                Task type
              </div>
              <div class="sc-node">
                Priority
              </div>
            </div>
            <div class="sc-node">
              Summary
            </div>
            <div class="sc-node sc-tall">
              Description
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

.intake-window,
.mapping-window,
.scaffold-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Intake window ───────────────────────────── */

.intake-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.in-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.in-label {
  font-size: 11.5px;
  color: var(--fg-3);
}

.in-input {
  font-size: 13px;
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: var(--r-xs);
  padding: 8px 10px;
}

.in-task {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--fg-2);
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: var(--r-sm);
  padding: 10px 12px;
}

.in-task code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

/* ── Config rows ─────────────────────────────── */

.cfg-body {
  padding: 10px 8px;
}

.cfg-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
}

.cfg-row + .cfg-row {
  border-top: 1px solid var(--line);
}

.cfg-key {
  font-size: 13px;
  color: var(--fg-2);
}

.cfg-val {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.cfg-pill {
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 10px;
}

/* ── Scaffold window ─────────────────────────── */

.scaffold-body {
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sc-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 12.5px;
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: var(--r-xs);
  padding: 10px 12px;
  flex: 1;
  min-width: 0;
}

.sc-tall {
  padding-bottom: 30px;
  align-items: flex-start;
}

.sc-row {
  display: flex;
  gap: 8px;
}

.sc-tag {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--fg-3);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 480px) {
  .sc-row {
    flex-direction: column;
  }
}
</style>
