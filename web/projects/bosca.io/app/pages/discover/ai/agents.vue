<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Agents — Compose an agent from the parts',
  description: 'An agent gathers a model, a prompt, and a set of tools under one key, composed with sub-agents, authored in Studio, and versioned in Git. Tools bind to one of your scripts or a registered MCP server. The harness that runs an agent you compose yourself is coming soon.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'AI', path: '/discover/ai' },
  { name: 'Agents', path: '/discover/ai/agents' }
], '/og-ai.png')

const AGENT = [
  { k: 'key', v: 'content-helper' },
  { k: 'model', v: 'gpt-5 · openai' },
  { k: 'prompt', v: 'summarize-doc' },
  { k: 'tools', v: 'execute_query · create_document' },
  { k: 'sub-agent', v: 'topics-agent' }
]

const TOOLS = [
  { name: 'run-report', bind: 'script', icon: 'code' },
  { name: 'search-docs', bind: 'mcp server', icon: 'server' },
  { name: 'make-cover', bind: 'script', icon: 'code' }
]
</script>

<template>
  <DiscoverShell section-id="ai">
    <section class="page-hero">
      <p class="kicker load-1">
        Agents
      </p>
      <h1 class="load-2">
        Compose an agent <em>from the parts</em>
      </h1>
      <p class="section-sub load-3">
        An agent gathers a model, a prompt, and a set of tools under one key —
        composed with sub-agents, authored in Studio, and versioned in Git. The
        harness that runs an agent you compose yourself, end to end, is coming
        soon.
      </p>
    </section>

    <!-- ── Anatomy ─────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The record
          </p>
          <h2>A model, a prompt, <em>a set of tools</em></h2>
          <p class="section-sub">
            Each agent is one record under a unique key — the handle other
            surfaces reference it by. Pick the model it runs and the prompt it
            follows, attach the tools it can call, and drop in a configuration
            object for anything the runtime needs.
          </p>
          <ul class="point-list">
            <li>A unique key that other surfaces reference it by.</li>
            <li>One model and one prompt, chosen from your records.</li>
            <li>The tools it's allowed to call, attached to the agent.</li>
            <li>A freeform configuration object for runtime settings.</li>
          </ul>
        </div>
        <div class="agent-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">agent · content-helper</span>
          </div>
          <div class="agent-body">
            <div
              v-for="row in AGENT"
              :key="row.k"
              class="agent-row"
            >
              <span class="agent-k">{{ row.k }}</span>
              <span class="agent-v">{{ row.v }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Tools ───────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Tools it can call
          </p>
          <h2>Your scripts, <em>or an MCP server</em></h2>
          <p class="section-sub">
            A tool is a named record with a description and a configuration
            object, wired to a backend that carries it out — most often a Bosca
            script you wrote or an MCP server you registered. Register a server by
            its transport — streamable HTTP, SSE, or stdio — and bind a tool to it.
          </p>
          <ul class="point-list">
            <li>A tool binds to a script or a registered MCP server.</li>
            <li>Register MCP servers by transport: streamable HTTP, SSE, or stdio.</li>
            <li>A configuration object carries its settings and connection details.</li>
            <li>Attach a tool to an agent to make it callable there.</li>
          </ul>
        </div>
        <div class="tools-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">agent tools</span>
          </div>
          <div class="tools-body">
            <div
              v-for="t in TOOLS"
              :key="t.name"
              class="tool-row"
            >
              <span class="tool-name">{{ t.name }}</span>
              <span class="tool-arrow"><Icon
                name="arrow-right"
                :size="12"
              /></span>
              <span class="tool-bind"><Icon
                :name="t.icon"
                :size="12"
              /> {{ t.bind }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Author, version, run ────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Author now, <span class="soon">Harness coming soon</span>
        </p>
        <h2>Write it down, <em>version it in Git</em></h2>
        <p class="section-sub">
          Agents, prompts, tools, and MCP servers are records you author in Studio
          and keep in Git, so your AI setup lives beside your code with a full history
          and a clean way to review a change. Today these parts already power AI
          across the platform; the harness that runs an agent you compose
          yourself, end to end, is on the way.
        </p>
      </div>
      <div class="steps-row reveal">
        <div class="step-card">
          <span class="step-icon"><Icon
            name="workflow"
            :size="16"
          /></span>
          <h3>Compose</h3>
          <p>Assemble a model, a prompt, and tools — and sub-agents to delegate to.</p>
        </div>
        <div class="step-card">
          <span class="step-icon"><Icon
            name="git-branch"
            :size="16"
          /></span>
          <h3>Version</h3>
          <p>Author in Studio and round-trip to Git, with a history you can review.</p>
        </div>
        <div class="step-card muted">
          <span class="step-icon"><Icon
            name="rocket"
            :size="16"
          /></span>
          <h3>Run <span class="soon">Soon</span></h3>
          <p>The harness that runs an agent you compose yourself is coming soon.</p>
        </div>
      </div>
    </section>

    <DiscoverAiExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.agent-window,
.tools-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Agent window ────────────────────────────── */

.agent-body {
  padding: 12px 10px;
}

.agent-row {
  display: flex;
  align-items: baseline;
  gap: 14px;
  padding: 13px 12px;
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.agent-row + .agent-row {
  border-top: 1px solid var(--line);
}

.agent-k {
  width: 82px;
  flex-shrink: 0;
  color: var(--fg-3);
}

.agent-v { color: var(--fg-1); }

.agent-row:first-child .agent-v { color: var(--accent); }

/* ── Tools window ────────────────────────────── */

.tools-body {
  padding: 12px 10px;
}

.tool-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
}

.tool-row + .tool-row {
  border-top: 1px solid var(--line);
}

.tool-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.tool-arrow {
  color: var(--accent);
  display: flex;
}

.tool-bind {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
}

.tool-bind svg { color: var(--accent); }

/* ── Steps row ───────────────────────────────── */

.steps-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.step-card {
  padding: 24px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 50%, transparent);
}

.step-card.muted {
  border-style: dashed;
  background: transparent;
}

.step-icon {
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

.step-card h3 {
  font-size: 16px;
  font-weight: 650;
  margin: 0 0 8px;
  display: flex;
  align-items: center;
}

.step-card p {
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Coming-soon pill ────────────────────────── */

.soon {
  display: inline-flex;
  align-items: center;
  font-family: var(--font-mono);
  font-size: 9.5px;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 999px;
  padding: 2px 9px;
  margin-left: 10px;
  vertical-align: middle;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 720px) {
  .steps-row {
    grid-template-columns: 1fr;
  }
}
</style>
