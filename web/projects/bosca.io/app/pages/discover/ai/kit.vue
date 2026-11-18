<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Kit — The assistant built into Studio',
  description: 'Use native harnesses in Bosca Studio with permitted context and tools. Learn how user permissions, scoped credentials, session archives, and deployment fit together.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'AI', path: '/discover/ai' },
  { name: 'Kit', path: '/discover/ai/kit' }
], '/og-ai.png')

const CAPS = [
  { icon: 'file-text', title: 'Work on documents', body: 'Read permitted documents, draft structured content, and save edits. Readiness and publication are separate operations.' },
  { icon: 'image', title: 'Make images', body: 'Use Bosca’s configured image service to generate or edit an image and save a new content item.' },
  { icon: 'code', title: 'Manage scripts', body: 'Read, validate, edit, enable, and execute server-side scripts through the script API.' },
  { icon: 'workflow', title: 'Build pipelines', body: 'Inspect, validate, and save pipeline definitions, then start runs through Bosca’s durable execution service.' },
  { icon: 'braces', title: 'Query your API', body: 'Run authorized GraphQL operations. Live schema discovery also requires administrator access in production.' },
  { icon: 'database', title: 'Explore analytics', body: 'Run read-only warehouse queries and work with saved queries, visualizations, and dashboards. Query results include bounded rows and cache status.' },
  { icon: 'file-text', title: 'Use selected context', body: 'Work from permitted content and WorkOps records or selected Git files. Source selection does not clone a repository.' },
  { icon: 'book', title: 'Look up scripture', body: 'Retrieve passages from a selected available translation, with their reference and attribution.' },
  { icon: 'message-circle', title: 'Save memories', body: 'Explicitly save facts or decisions as permissioned Bosca documents for use in other permitted conversations.' }
]
</script>

<template>
  <DiscoverShell section-id="ai">
    <section class="page-hero">
      <p class="kicker load-1">
        Kit
      </p>
      <h1 class="load-2">
        The assistant <em>built into Studio</em>
      </h1>
      <p class="section-sub load-3">
        Kit connects a Bosca conversation to a native harness, such as Codex or
        Claude, running in a managed workload. Studio displays the conversation
        and tool results. The harness calls the model and chooses tools;
        Bosca supplies permitted context and manages the workload’s lifetime.
      </p>
      <p class="section-sub">
        Read the <NuxtLink to="/kit-reference/getting-started">Kit Reference</NuxtLink>
        for the architecture, available tools, deployment, operations, and extension points.
      </p>
    </section>

    <!-- ── Capabilities ────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          What it can do
        </p>
        <h2>Tools for <em>platform work</em></h2>
        <p class="section-sub">
          These capabilities depend on the deployed modules, configured tools
          and providers, your permissions, and the runtime profile’s scopes.
          Inspect returned records to confirm what was saved or changed.
        </p>
      </div>
      <div class="cap-grid reveal">
        <article
          v-for="c in CAPS"
          :key="c.title"
          class="cap-card"
        >
          <span class="cap-icon"><Icon
            :name="c.icon"
            :size="16"
          /></span>
          <h3>{{ c.title }}</h3>
          <p>{{ c.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── Responsibilities ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            How a turn works
          </p>
          <h2>The harness manages <em>its context</em></h2>
          <p class="section-sub">
            The native harness owns its chat history, model context window,
            compaction, and tool selection. Bosca records the conversation and
            durable turn outcomes, and streams supported native updates to Studio.
          </p>
          <ul class="point-list">
            <li>ACP carries prompts, native updates, and permission requests.</li>
            <li>MCP supplies Bosca tools and approved external tool connections.</li>
            <li>Bosca also supplies bounded instructions and permitted context directly with session setup and prompts.</li>
          </ul>
          <p class="section-sub">
            Read <NuxtLink to="/kit-reference/concepts">How Kit Works</NuxtLink>
            for the responsibility and state boundaries.
          </p>
        </div>
        <aside
          class="explain-panel"
          aria-label="Kit responsibilities"
        >
          <p class="panel-title">
            Three responsibilities
          </p>
          <dl class="panel-list">
            <div>
              <dt>Bosca</dt>
              <dd>Conversation, permitted context, policy, and workload lifecycle.</dd>
            </div>
            <div>
              <dt>Native harness</dt>
              <dd>Model calls, internal history, context management, and tool choice.</dd>
            </div>
            <div>
              <dt>Tools and domain APIs</dt>
              <dd>Read or change actual records under their authorization rules.</dd>
            </div>
          </dl>
        </aside>
      </div>
    </section>

    <!-- ── Access ──────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Access and credentials
          </p>
          <h2>Your permissions <em>still apply</em></h2>
          <p class="section-sub">
            Bosca tools use a generation-specific scoped token for the same
            principal as the user who started the conversation. Existing group
            membership and document permissions still determine which records
            are accessible. Scopes only narrow that access; they never make an
            inaccessible document readable.
          </p>
          <ul class="point-list">
            <li>The initiating credential’s restrictions and the runtime profile’s scopes both apply.</li>
            <li>Approving a native permission request does not grant additional Bosca access.</li>
            <li>External providers use separately configured credentials; shell commands run as the container’s operating-system user.</li>
          </ul>
        </div>
        <aside
          class="explain-panel"
          aria-label="Scope example"
        >
          <p class="panel-title">
            Example: content:view
          </p>
          <dl class="panel-list">
            <div>
              <dt>Document access</dt>
              <dd>The user must already be allowed to read the document under Bosca’s permission rules.</dd>
            </div>
            <div>
              <dt>Operation scope</dt>
              <dd>The token must also permit the content read operation.</dd>
            </div>
            <div>
              <dt>Both checks apply</dt>
              <dd>Adding content:view grants no group membership or document permission.</dd>
            </div>
          </dl>
        </aside>
      </div>
    </section>

    <!-- ── Sessions ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Session continuity
          </p>
          <h2>Stay warm, archive, <em>return</em></h2>
          <p class="section-sub">
            Follow-up turns normally reuse the same native process. After the
            configured idle timeout, Bosca stops its writers, archives the native
            operational directory and workspace files, verifies the stored archive, and
            removes the workload. A later message starts a new generation with
            fresh credentials and restores eligible state.
          </p>
          <ul class="point-list">
            <li>The archive is a file checkpoint, not a running-process snapshot.</li>
            <li>Upgrading the same harness reopens its saved directory; the harness manages its own file migrations.</li>
            <li>Changed access, source content, or harness choice can require a new native session.</li>
            <li>Unarchived local changes can be lost if a workload fails. Uncertain submitted work is interrupted without automatic replay.</li>
            <li>Cancellation does not undo completed tool operations.</li>
          </ul>
        </div>
        <aside
          class="explain-panel"
          aria-label="Retained information"
        >
          <p class="panel-title">
            Different kinds of state
          </p>
          <dl class="panel-list">
            <div>
              <dt>Conversation history</dt>
              <dd>Bosca’s durable record of messages and turn outcomes.</dd>
            </div>
            <div>
              <dt>Native checkpoint</dt>
              <dd>The harness’s operational directory and working files for session continuity.</dd>
            </div>
            <div>
              <dt>Saved memories and artifacts</dt>
              <dd>Explicitly saved Bosca records with their own permissions and lifecycle. Workspace files are not automatically saved content.</dd>
            </div>
          </dl>
        </aside>
      </div>
    </section>

    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Run and extend Kit
        </p>
        <h2>Choose your <em>deployment</em></h2>
        <p class="section-sub">
          Managed harness workloads run as
          <NuxtLink to="/kit-reference/kubernetes">Kubernetes Jobs</NuxtLink> or
          <NuxtLink to="/kit-reference/swarm">Swarm Services</NuxtLink>.
          <NuxtLink to="/kit-reference/compose">Compose</NuxtLink> can run Bosca
          components and supporting services; it does not create a harness
          container per conversation. Standard Helm and Swarm deployments provide
          the runtime configuration and workload defaults. Operators select a
          harness and supply its provider credential.
        </p>
        <p class="section-sub">
          Use <NuxtLink to="/kit-reference/configuration">Configuration</NuxtLink>
          to define profiles and permitted tools,
          <NuxtLink to="/kit-reference/operations">Operations and Support</NuxtLink>
          for monitoring, recovery, retention, and upgrades, and
          <NuxtLink to="/kit-reference/extending">Extension Points</NuxtLink>
          for instructions, MCP connections, tools, and harness adapters.
        </p>
      </div>
    </section>

    <DiscoverAiExplore />
  </DiscoverShell>
</template>

<style scoped>
.section-sub a {
  color: var(--accent);
  text-underline-offset: 3px;
}

/* ── Capabilities grid ───────────────────────── */

.cap-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.cap-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 50%, transparent);
}

.cap-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
  margin-bottom: 14px;
}

.cap-card h3 {
  font-size: 15px;
  font-weight: 650;
  margin: 0 0 8px;
}

.cap-card p {
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Explanatory panels ──────────────────────── */

.explain-panel {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  padding: 24px;
  min-width: 0;
}

.panel-title {
  margin: 0 0 20px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
}

.panel-list {
  margin: 0;
}

.panel-list > div + div {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--line);
}

.panel-list dt {
  font-size: 13px;
  font-weight: 650;
  color: var(--fg-1);
}

.panel-list dd {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-2);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 860px) {
  .cap-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 520px) {
  .cap-grid {
    grid-template-columns: 1fr;
  }
}
</style>
