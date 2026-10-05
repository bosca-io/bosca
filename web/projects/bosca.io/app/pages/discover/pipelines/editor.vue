<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'The Pipeline Editor — Build Automation on a Canvas',
  description: 'Studio\'s pipeline editor is a three-pane graph workspace: a node palette, a canvas, and an inspector — with typed connections, group frames, undo, and versioned saves.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Pipelines', path: '/discover/pipelines' },
  { name: 'The editor', path: '/discover/pipelines/editor' }
], '/og-pipelines.png')
</script>

<template>
  <DiscoverShell section-id="pipelines">
    <section class="page-hero">
      <p class="kicker load-1">
        The editor
      </p>
      <h1 class="load-2">
        Three panes. <em>One canvas.</em>
      </h1>
      <p class="section-sub load-3">
        Pipelines are built in Studio's graph editor: a node palette on the
        left, the canvas in the middle, and an inspector on the right that
        configures whatever is selected. Drag nodes on, draw edges between
        them, and the graph you see is the automation that runs.
      </p>
    </section>

    <!-- ── The workspace ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The workspace
          </p>
          <h2>Palette, canvas, <em>inspector</em></h2>
          <p class="section-sub">
            The palette lists every node type available in your installation,
            grouped by category — hover for a description, click to add, or
            drag to place. Selecting a node opens its settings in the
            inspector: a name and description for what it does in
            <em>this</em> pipeline, then whatever the node type contributes —
            a JSONata expression, a script picker, email fields.
          </p>
          <ul class="point-list">
            <li><em>Maximize</em> expands the editor to the full viewport for larger graphs; <code>Escape</code> restores it.</li>
            <li><code>⌘Z</code> undoes any graph change, <code>⇧⌘Z</code> redoes, <code>Delete</code> removes the selection.</li>
            <li>The pipeline's metadata — name, accepted event, description, and the Active switch — sits above the canvas.</li>
          </ul>
        </div>
        <div
          class="pane-diagram"
          aria-hidden="true"
        >
          <div class="pane pane-palette">
            <span class="pane-label">Palette</span>
            <span class="pane-line" /><span class="pane-line" /><span class="pane-line" /><span class="pane-line" />
          </div>
          <div class="pane pane-canvas">
            <span class="pane-label">Canvas</span>
            <span class="mini-node" /><span class="mini-node mini-b" /><span class="mini-node mini-c" />
          </div>
          <div class="pane pane-inspector">
            <span class="pane-label">Inspector</span>
            <span class="pane-line" /><span class="pane-line" /><span class="pane-line" />
          </div>
        </div>
      </div>
    </section>

    <!-- ── Screenshot ──────────────────────────── -->
    <section class="section shot-section">
      <figure class="reveal">
        <div class="shot-window">
          <img
            :src="'/screenshots/pipelines-editor.png'"
            alt="The pipeline editor in Bosca Studio: the node palette, a pipeline graph on the canvas, and the inspector"
            width="2400"
            height="1500"
            loading="lazy"
          >
        </div>
        <figcaption class="shot-caption">
          The editor in Studio — palette, canvas, and inspector.
        </figcaption>
      </figure>
    </section>

    <!-- ── Typed connections ───────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Typed connections
          </p>
          <h2>Edges that <em>refuse to be wrong</em></h2>
          <p class="section-sub">
            Connection handles follow the node's category, and input slots are
            typed — object, string, integer, UUID. The editor refuses a
            connection whose value kind the target slot cannot accept, so a
            wiring mistake surfaces while you draw it, not in a failed run.
          </p>
          <ul class="point-list">
            <li>A Condition routes out a green <em>true</em> handle and a red <em>false</em> handle; a Switch gets one handle per case plus a default.</li>
            <li>Some nodes expose a red <em>error port</em> — wire it to route failures into recovery logic instead of aborting the run.</li>
            <li>Edges into a Combine node carry a <em>port name</em>; each inbound branch becomes that key in the merged result.</li>
          </ul>
        </div>
        <div
          class="ports-diagram"
          aria-hidden="true"
        >
          <svg
            class="ports"
            viewBox="0 0 460 240"
            role="presentation"
          >
            <g class="pnode">
              <rect
                x="140"
                y="70"
                width="180"
                height="66"
                rx="9"
              />
              <text
                x="158"
                y="98"
                class="pnode-title"
              >opt-in?</text>
              <text
                x="158"
                y="116"
                class="pnode-sub"
              >route · condition</text>
              <circle
                cx="140"
                cy="103"
                r="4.5"
                class="pport"
              />
              <circle
                cx="320"
                cy="88"
                r="4.5"
                class="pport pport-true"
              />
              <circle
                cx="320"
                cy="118"
                r="4.5"
                class="pport pport-false"
              />
            </g>
            <path
              class="pedge"
              d="M40 103 H140"
            />
            <path
              class="pedge pedge-true"
              d="M320 88 C360 88 380 60 420 60"
            />
            <path
              class="pedge pedge-false"
              d="M320 118 C360 118 380 150 420 150"
            />
            <text
              x="336"
              y="72"
              class="pedge-label plabel-true"
            >true</text>
            <text
              x="336"
              y="142"
              class="pedge-label plabel-false"
            >false</text>
            <text
              x="40"
              y="92"
              class="pedge-label plabel-in"
            >typed input</text>
          </svg>
        </div>
      </div>
    </section>

    <!-- ── Working safely ──────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Working safely
        </p>
        <h2>Built for <em>shared editing</em></h2>
      </div>
      <div class="notes-grid reveal">
        <div class="note-card">
          <h3>Group frames</h3>
          <p>
            Organize large graphs with labeled, color-coded frames drawn
            behind related nodes. Groups are purely visual — the executor
            ignores them — but they save with the graph, and a group can be
            collapsed while you work elsewhere.
          </p>
        </div>
        <div class="note-card">
          <h3>Versioned saves</h3>
          <p>
            Saving validates the graph and increments the pipeline's
            version, which doubles as optimistic locking: if someone else
            saved since you loaded, your save is rejected instead of
            silently overwriting their work.
          </p>
        </div>
        <div class="note-card">
          <h3>Safe by default</h3>
          <p>
            Cloning a pipeline copies the full graph but arrives inactive,
            so it never fires before you've reviewed it. Deletes are soft —
            the pipeline stops matching events immediately, but its run
            history is retained.
          </p>
        </div>
      </div>
    </section>

    <DiscoverPipelinesExplore />
  </DiscoverShell>
</template>

<style scoped>
.shot-section {
  padding-top: 0;
  padding-bottom: 90px;
}

/* ── Three-pane diagram ──────────────────────── */

.pane-diagram {
  display: grid;
  grid-template-columns: 0.55fr 1.3fr 0.7fr;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
  min-height: 260px;
}

.pane {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 60%, transparent);
}

.pane-canvas {
  position: relative;
  background:
    radial-gradient(circle, var(--line) 1px, transparent 1px) 0 0 / 22px 22px,
    color-mix(in srgb, var(--bg-1) 30%, transparent);
}

.pane-label {
  font-family: var(--font-mono);
  font-size: 10.5px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-3);
}

.pane-line {
  height: 9px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--fg-3) 18%, transparent);
}

.mini-node {
  position: absolute;
  width: 64px;
  height: 26px;
  border: 1px solid color-mix(in srgb, var(--accent) 55%, transparent);
  border-radius: 6px;
  background: color-mix(in srgb, var(--accent) 10%, var(--bg-1));
  top: 46px;
  left: 18px;
}

.mini-b {
  top: 110px;
  left: 96px;
}

.mini-c {
  top: 174px;
  left: 174px;
}

/* ── Ports diagram ───────────────────────────── */

.ports-diagram {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background:
    radial-gradient(circle, var(--line) 1px, transparent 1px) 0 0 / 22px 22px,
    var(--bg-0);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
  padding: 20px;
}

.ports {
  display: block;
  width: 100%;
  height: auto;
}

.ports .pnode rect {
  fill: color-mix(in srgb, var(--bg-1) 88%, transparent);
  stroke: var(--line-2);
  stroke-width: 1;
}

.ports .pnode-title {
  font-family: var(--font-mono);
  font-size: 13px;
  fill: var(--fg-0);
}

.ports .pnode-sub {
  font-family: var(--font-mono);
  font-size: 10.5px;
  fill: var(--fg-3);
}

.ports .pport {
  fill: var(--bg-0);
  stroke: var(--accent);
  stroke-width: 1.5;
}

.ports .pport-true {
  stroke: #34d99a;
}

.ports .pport-false {
  stroke: #f87171;
}

.ports .pedge {
  fill: none;
  stroke: color-mix(in srgb, var(--accent) 55%, transparent);
  stroke-width: 1.5;
  stroke-dasharray: 6 6;
}

.ports .pedge-true {
  stroke: color-mix(in srgb, #34d99a 60%, transparent);
}

.ports .pedge-false {
  stroke: color-mix(in srgb, #f87171 45%, transparent);
}

.ports .pedge-label {
  font-family: var(--font-mono);
  font-size: 10.5px;
  fill: var(--fg-3);
}

.ports .plabel-true {
  fill: #34d99a;
}

.ports .plabel-false {
  fill: #f87171;
}

/* ── Note cards ──────────────────────────────── */

.notes-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 960px) {
  .notes-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.note-card {
  padding: 20px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.note-card h3 {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.note-card p {
  font-size: 13px;
  line-height: 1.7;
  color: var(--fg-2);
  margin: 0;
}
</style>
