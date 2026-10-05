<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Work Ops — Plan and track work, built into the platform',
  description: 'Bosca Work Ops is project management inside the platform: portfolios, specs, tasks, sprints, boards, releases, configurable workflows, and a personal notification inbox — beside the content and code the work is about.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Work Ops', path: '/discover/workops' }
], '/og-workops.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Agents', href: '#agents' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Organize',
    body: 'Group work into portfolios, programs, and projects, so a single task and the whole roadmap sit in the same structure.'
  },
  {
    step: '02',
    title: 'Plan',
    body: 'Write a spec, break it into requirements, and turn each into a task — or add tasks straight to a board or a sprint.'
  },
  {
    step: '03',
    title: 'Track',
    body: 'Move tasks through the workflows you define, on boards and in sprints, with your own statuses, priorities, and labels.'
  }
]

const FEATURES = [
  {
    icon: 'layers',
    title: 'Four levels of structure',
    body: 'Portfolios hold programs, programs hold projects, projects hold tasks — organize a single team or a whole org.'
  },
  {
    icon: 'file-text',
    title: 'Specs and requirements',
    body: 'Write a spec as a rich document, break it into requirements, and track each one as its own task.'
  },
  {
    icon: 'tag',
    title: 'Releases and milestones',
    body: 'Versions collect what ships in a release; milestones set dated targets that roll up across a program.'
  },
  {
    icon: 'sparkles',
    title: 'People and agents',
    body: 'Your team works in the editor — and through the Bosca CLI\'s MCP server, Claude and other agents write specs and track tasks here too.'
  },
  {
    icon: 'list-checks',
    title: 'Tasks on your terms',
    body: 'Task types, statuses, priorities, resolutions, and labels are yours to define and compose into your project workflows.'
  },
  {
    icon: 'git-branch',
    title: 'Workflows you define',
    body: 'Give each task type its own set of statuses and the moves allowed between them.'
  },
  {
    icon: 'columns',
    title: 'Boards and sprints',
    body: 'Run the work on kanban or scrum boards, in sprints with a start, an end, and a goal.'
  },
  {
    icon: 'zap',
    title: 'Automation',
    body: 'When a task is created, transitioned, or an SLA is at risk, run a rule that edits, transitions, comments, or opens a task.'
  },
  {
    icon: 'bell',
    title: 'A personal work inbox',
    body: 'Assignments, mentions, approvals, due work, and watched changes reach each person through the event channels and focus controls they choose.'
  }
]

const AGENT_CALLS = [
  { tool: 'workops_spec', act: 'create · "Live sessions dashboard"' },
  { tool: 'workops_requirement', act: 'create · 4 requirements' },
  { tool: 'workops_task', act: 'create · BOS-142 … BOS-145' },
  { tool: 'workops_task', act: 'transition · BOS-142 → Done' }
]
</script>

<template>
  <DiscoverShell
    section-id="workops"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Work Ops
        </p>
        <h1 class="load-2">
          Plan and track work,<br>
          <em>built into the platform.</em>
        </h1>
        <p class="hero-sub load-3">
          Portfolios, specs, tasks, sprints, boards, and releases — all with
          your own workflows, in the same platform as your content and code. Your
          team plans and tracks here, and so do the AI agents working alongside
          them.
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
        <div class="task-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">BOS-142</span>
          </div>
          <div class="task-body">
            <div class="task-top">
              <span class="task-type">Story</span>
              <span class="task-status">In Progress</span>
            </div>
            <div class="task-title">
              Add the live sessions map
            </div>
            <div class="task-meta">
              <span>Priority · High</span>
              <span>Sprint 7</span>
            </div>
            <div class="task-links">
              <span class="task-link"><Icon
                name="file-text"
                :size="12"
              /> spec DASH-2</span>
              <span class="task-link"><Icon
                name="git-commit"
                :size="12"
              /> commit a1b0c7</span>
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
        <h2>Organize, plan, <em>track.</em></h2>
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
          <h2>A full tracker, <em>shaped to your team</em></h2>
          <p class="section-sub">
            The hierarchy, the work items, the boards, and the rules — with the
            statuses, workflows, and types set the way your team already works.
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

    <!-- ── Built for agents ────────────────────── -->
    <section
      id="agents"
      class="section"
    >
      <div class="agent-panel reveal">
        <div class="split">
          <div class="split-copy">
            <p class="kicker">
              How work gets done
            </p>
            <h2>Your agents plan and track <em>right here</em></h2>
            <p class="section-sub">
              The Bosca CLI runs an MCP server, so Claude and other AI agents
              work in the same Work Ops your team does. An agent writes the spec,
              breaks it into requirements, opens the tasks, and moves them along
              as it goes — the plan it makes and the work it does, in one shared
              place.
            </p>
            <ul class="point-list">
              <li>The Bosca CLI exposes Work Ops to AI agents as MCP tools.</li>
              <li>An agent writes a spec, its requirements, and the tasks that carry it out.</li>
              <li>It keeps its own statuses current on the same board your team watches.</li>
              <li>What an agent plans and does sits right beside your team's work.</li>
            </ul>
          </div>
          <div class="agent-window">
            <div class="code-chrome">
              <span class="dot" /><span class="dot" /><span class="dot" />
              <span class="code-title">agent · bosca mcp</span>
            </div>
            <div class="agent-body">
              <div
                v-for="c in AGENT_CALLS"
                :key="c.act"
                class="agent-row"
              >
                <span class="agent-tool">{{ c.tool }}</span>
                <span class="agent-act">{{ c.act }}</span>
              </div>
              <div class="agent-out">
                <Icon
                  name="check-circle"
                  :size="13"
                />
                spec, requirements, and tasks — tracked on your board
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Linked to content & code ────────────── -->
    <section class="section linked">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Part of the platform
          </p>
          <h2>Linked to your content <em>and your code</em></h2>
          <p class="section-sub">
            Because Work Ops is part of Bosca, a task isn't off in a separate
            tool. It links to the document it's about, the spec it came from, and
            the commits that reference it — all in one place.
          </p>
          <ul class="point-list">
            <li>A task can link to the content document it concerns.</li>
            <li>A spec can point at the repository and path where its work lives.</li>
            <li>Commits and pull requests that name a task's key link back to it.</li>
            <li>Tasks link to each other — blocks, duplicates, relates to, and more.</li>
          </ul>
        </div>
        <div class="linked-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">BOS-142 · links</span>
          </div>
          <div class="linked-body">
            <div class="linked-row">
              <span class="linked-icon"><Icon
                name="file-text"
                :size="14"
              /></span>
              <span class="linked-text">document · <code>Live map design</code></span>
            </div>
            <div class="linked-row">
              <span class="linked-icon"><Icon
                name="list-checks"
                :size="14"
              /></span>
              <span class="linked-text">spec · <code>DASH-2</code></span>
            </div>
            <div class="linked-row">
              <span class="linked-icon"><Icon
                name="git-commit"
                :size="14"
              /></span>
              <span class="linked-text">commit · <code>a1b0c7</code> references BOS-142</span>
            </div>
            <div class="linked-row">
              <span class="linked-icon"><Icon
                name="link"
                :size="14"
              /></span>
              <span class="linked-text">blocks · <code>BOS-149</code></span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverWorkopsExplore title="Go deeper" />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#aab6f7"
        class="closing-mark"
      />
      <h2>The plan and the work, <em>in one place</em></h2>
      <p class="section-sub">
        Portfolios down to subtasks, specs down to sprints, all with your own
        workflows — next to the content and code your team is actually shipping.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.85fr);
  align-items: center;
  gap: 48px;
  max-width: 1140px;
  margin: 0 auto;
  padding: 64px 32px 100px;
}

.hero h1 {
  font-size: clamp(40px, 5.4vw, 62px);
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
  max-width: 480px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

/* ── Task window ─────────────────────────────── */

.task-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.task-body {
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.task-top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-type,
.task-status {
  font-family: var(--font-mono);
  font-size: 10.5px;
  border-radius: 999px;
  padding: 3px 10px;
}

.task-type {
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
}

.task-status {
  color: #e0a23a;
  border: 1px solid color-mix(in srgb, #e0a23a 40%, transparent);
}

.task-title {
  font-size: 16px;
  font-weight: 650;
  color: var(--fg-0);
}

.task-meta {
  display: flex;
  gap: 16px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.task-links {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.task-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-2);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 3px 9px;
}

.task-link svg { color: var(--accent); }

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
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
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

/* ── Agent panel ─────────────────────────────── */

.agent-panel {
  padding: 44px;
  border: 1px solid color-mix(in srgb, var(--accent) 32%, transparent);
  border-radius: var(--r-md);
  background:
    radial-gradient(80% 130% at 88% 0%, color-mix(in srgb, var(--accent) 11%, transparent), transparent 68%),
    color-mix(in srgb, var(--bg-1) 50%, transparent);
  box-shadow: 0 0 80px color-mix(in srgb, var(--accent) 8%, transparent);
}

.agent-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.agent-body {
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.agent-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.agent-tool {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 650;
  color: var(--accent);
  flex-shrink: 0;
}

.agent-act {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-2);
}

.agent-out {
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

/* ── Linked ──────────────────────────────────── */

.linked {
  padding-top: 90px;
}

.linked-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.linked-body {
  padding: 10px 8px;
}

.linked-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
}

.linked-row + .linked-row {
  border-top: 1px solid var(--line);
}

.linked-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
}

.linked-text {
  font-size: 13px;
  color: var(--fg-1);
}

.linked-text code {
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
    grid-template-columns: 1fr;
    padding-top: 36px;
    padding-bottom: 64px;
  }

  .how-grid {
    grid-template-columns: 1fr;
  }

  .feature-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .agent-panel {
    padding: 26px 22px;
  }
}

@media (max-width: 720px) {
  .feature-grid {
    grid-template-columns: 1fr;
  }
}
</style>
