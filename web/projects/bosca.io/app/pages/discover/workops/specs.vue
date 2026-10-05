<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Specs & Requirements — Plan in a doc, track as a task',
  description: 'A Bosca spec is a rich document your team edits together, broken into requirements that each become a task. Specs can even be written by AI agents through the Bosca CLI\'s MCP server, and track the tasks they generate.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Work Ops', path: '/discover/workops' },
  { name: 'Specs & Requirements', path: '/discover/workops/specs' }
], '/og-workops.png')
</script>

<template>
  <DiscoverShell section-id="workops">
    <section class="page-hero">
      <p class="kicker load-1">
        Specs &amp; Requirements
      </p>
      <h1 class="load-2">
        Plan in a doc, <em>track as a task</em>
      </h1>
      <p class="section-sub load-3">
        A spec is where the thinking happens — a real document your team writes
        together. Break it into requirements, turn each into a task, and the
        plan and the work stay tied together from the first line.
      </p>
    </section>

    <!-- ── Rich specs ──────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            A real document
          </p>
          <h2>Write the spec, <em>together</em></h2>
          <p class="section-sub">
            A spec is a full rich document, not a text box. Several people edit
            it at once and the changes merge as they type. Nest a spec under a
            parent to break a big piece of work into smaller specs, each with its
            own status — and the parent rolls up how many of its children are
            done.
          </p>
          <ul class="point-list">
            <li>A spec is a rich document — headings, lists, tables, and links.</li>
            <li>Several people can edit the same spec at the same time.</li>
            <li>Nest child specs under a parent, each tracked on its own.</li>
          </ul>
        </div>
        <div class="spec-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">spec · DASH-2</span>
          </div>
          <div class="spec-body">
            <div class="spec-title">
              Live sessions dashboard
            </div>
            <div class="spec-meta">
              <span class="spec-status">In Progress</span>
              <span>4 requirements · 1 done</span>
            </div>
            <div class="spec-child">
              <Icon
                name="git-branch"
                :size="12"
              />
              child spec · Map rendering
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Requirements → tasks ────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            From plan to work
          </p>
          <h2>Every requirement <em>becomes a task</em></h2>
          <p class="section-sub">
            Break a spec into requirements — each its own document — and turn each
            one into a task that carries it out. A spec can generate those tasks
            for you and remember which ones it made, so you can trace a shipped
            change back to the requirement that asked for it.
          </p>
          <ul class="point-list">
            <li>A requirement is its own document, attached to a spec or a task.</li>
            <li>Each requirement links to the task that implements it.</li>
            <li>A spec can generate its tasks, and keeps track of which it made.</li>
          </ul>
        </div>
        <div class="req-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">requirements</span>
          </div>
          <div class="req-body">
            <div class="req-row">
              <span class="req-text">Show live points on a map</span>
              <span class="req-arrow"><Icon
                name="arrow-right"
                :size="12"
              /></span>
              <span class="req-task">BOS-142</span>
            </div>
            <div class="req-row">
              <span class="req-text">Cluster nearby sessions</span>
              <span class="req-arrow"><Icon
                name="arrow-right"
                :size="12"
              /></span>
              <span class="req-task">BOS-149</span>
            </div>
            <div class="req-row">
              <span class="req-text">Refresh every 5 seconds</span>
              <span class="req-arrow"><Icon
                name="arrow-right"
                :size="12"
              /></span>
              <span class="req-task">BOS-151</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── People or agents ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            People or agents
          </p>
          <h2>Written by your team, <em>or by an agent</em></h2>
          <p class="section-sub">
            Specs and tasks aren't only for people. The Bosca CLI runs an MCP
            server, so Claude and other AI agents write specs, break them into
            requirements, and track their tasks in the same Work Ops your team
            uses — the plan and the progress in one shared place.
          </p>
          <ul class="point-list">
            <li>The Bosca CLI exposes Work Ops as MCP tools for AI agents.</li>
            <li>An agent can create a spec, its requirements, and its tasks.</li>
            <li>Agents track their own work here, alongside the team's.</li>
          </ul>
        </div>
        <div class="mcp-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">agent · bosca mcp</span>
          </div>
          <div class="mcp-body">
            <div class="mcp-row">
              <span class="mcp-tool">workops_spec</span>
              <span class="mcp-arg">create · "Auth rework"</span>
            </div>
            <div class="mcp-row">
              <span class="mcp-tool">workops_requirement</span>
              <span class="mcp-arg">create · 3 requirements</span>
            </div>
            <div class="mcp-row">
              <span class="mcp-tool">workops_task</span>
              <span class="mcp-arg">create · assign to me</span>
            </div>
            <div class="mcp-out">
              <Icon
                name="check-circle"
                :size="13"
              />
              spec, requirements, and tasks — tracked here
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

.spec-window,
.req-window,
.mcp-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Spec window ─────────────────────────────── */

.spec-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.spec-title {
  font-size: 17px;
  font-weight: 650;
  color: var(--fg-0);
}

.spec-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.spec-status {
  color: #e0a23a;
  border: 1px solid color-mix(in srgb, #e0a23a 40%, transparent);
  border-radius: 999px;
  padding: 2px 9px;
}

.spec-child {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 4px;
  padding: 11px 13px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-2);
}

.spec-child svg { color: var(--accent); }

/* ── Requirements window ─────────────────────── */

.req-body {
  padding: 12px 10px;
}

.req-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
}

.req-row + .req-row {
  border-top: 1px solid var(--line);
}

.req-text {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
}

.req-arrow {
  color: var(--accent);
  display: flex;
}

.req-task {
  font-family: var(--font-mono);
  font-size: 11.5px;
  font-weight: 650;
  color: var(--accent);
}

/* ── MCP window ──────────────────────────────── */

.mcp-body {
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.mcp-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.mcp-tool {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 650;
  color: var(--accent);
}

.mcp-arg {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-2);
}

.mcp-out {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  padding: 12px;
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 11px;
}
</style>
