<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Clusters & Fleet — Register a cluster, see the fleet',
  description: 'Register a Kubernetes cluster in Bosca by uploading its kubeconfig — encrypted and kept server-side. Watch fleet health, nodes, capacity, live events, and namespaces across every registered cluster from one console.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Kubernetes', path: '/discover/kubernetes' },
  { name: 'Clusters & Fleet', path: '/discover/kubernetes/clusters' }
], '/og-kubernetes.png')

const CLUSTER = [
  { k: 'name', v: 'prod-us-east' },
  { k: 'provider', v: 'EKS' },
  { k: 'environment', v: 'Production' },
  { k: 'kubeconfig', v: 'encrypted · server-side' }
]

const NODES = [
  { name: 'cp-1', role: 'control-plane', state: 'Ready', tone: 'ok' },
  { name: 'worker-a', role: 'worker', state: 'Ready', tone: 'ok' },
  { name: 'worker-b', role: 'worker', state: 'NotReady', tone: 'warn' }
]

const EVENTS = [
  { level: 'info', text: 'Scaled up replica set web-7c9 to 5' },
  { level: 'warn', text: 'Back-off restarting failed container' },
  { level: 'info', text: 'Pulled image ghcr.io/app:6.1' }
]
</script>

<template>
  <DiscoverShell section-id="kubernetes">
    <section class="page-hero">
      <p class="kicker load-1">
        Clusters &amp; Fleet
      </p>
      <h1 class="load-2">
        Register a cluster, <em>see the fleet</em>
      </h1>
      <p class="section-sub load-3">
        Bring a cluster into Bosca with its kubeconfig, and watch it from one
        console — health, nodes, capacity, live events, and namespaces, across
        every cluster you register.
      </p>
    </section>

    <!-- ── Register ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Register
          </p>
          <h2>A kubeconfig, <em>and you're in</em></h2>
          <p class="section-sub">
            Register an existing cluster by uploading its kubeconfig, with a
            name, provider, region, and environment. Bosca encrypts it and keeps
            it on the server — the browser never sees it. Rotate the credential
            when it changes, or remove the cluster to disconnect entirely.
          </p>
          <ul class="point-list">
            <li>Upload a kubeconfig with provider, region, and environment.</li>
            <li>It's encrypted at rest and stays server-side.</li>
            <li>Rotate the kubeconfig without re-registering.</li>
            <li>Remove a cluster to disconnect and delete the stored credential.</li>
          </ul>
        </div>
        <div class="rec-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">register cluster</span>
          </div>
          <div class="rec-body">
            <div
              v-for="row in CLUSTER"
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

    <!-- ── Fleet health ────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Fleet health
          </p>
          <h2>Nodes and capacity, <em>at a glance</em></h2>
          <p class="section-sub">
            Every cluster carries a healthy, degraded, or failing badge, probed
            for you. Drop into the nodes for role, readiness, zone, and live CPU
            and memory — control plane and workers side by side, with fleet
            capacity across the whole cluster.
          </p>
          <ul class="point-list">
            <li>A healthy / degraded / failing badge per cluster.</li>
            <li>Nodes by role — control plane and worker — with readiness.</li>
            <li>Live CPU and memory per node, and fleet capacity overall.</li>
            <li>Zone, instance type, and pod count, node by node.</li>
          </ul>
        </div>
        <div class="node-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">nodes · prod-us-east</span>
          </div>
          <div class="node-body">
            <div
              v-for="n in NODES"
              :key="n.name"
              class="node-row"
            >
              <span
                class="node-dot"
                :class="n.tone"
              />
              <span class="node-name">{{ n.name }}</span>
              <span class="node-role">{{ n.role }}</span>
              <span
                class="node-state"
                :class="n.tone"
              >{{ n.state }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Events & namespaces ─────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Events &amp; namespaces
          </p>
          <h2>What the cluster <em>is doing</em></h2>
          <p class="section-sub">
            Cluster events are the running commentary — scheduling, image pulls,
            probe failures, controller decisions — newest first, filtered by
            namespace and by level. And namespaces come as a grid with their
            workload, pod, and service counts, ready for you to add a new one.
          </p>
          <ul class="point-list">
            <li>Events newest-first, filtered by namespace and level.</li>
            <li>Info, warning, and error, with the count that needs attention.</li>
            <li>Namespaces as a grid, with their resource counts.</li>
            <li>Create a namespace right from the console.</li>
          </ul>
        </div>
        <div class="ev-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">events · prod-us-east</span>
          </div>
          <div class="ev-body">
            <div
              v-for="e in EVENTS"
              :key="e.text"
              class="ev-row"
            >
              <span
                class="ev-level"
                :class="e.level"
              >{{ e.level }}</span>
              <span class="ev-text">{{ e.text }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverKubernetesExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.rec-window,
.node-window,
.ev-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Record window ───────────────────────────── */

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
  width: 96px;
  flex-shrink: 0;
  color: var(--fg-3);
}

.rec-v { color: var(--fg-1); }

.rec-row:first-child .rec-v { color: var(--accent); }

/* ── Node window ─────────────────────────────── */

.node-body {
  padding: 12px 10px;
}

.node-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
}

.node-row + .node-row {
  border-top: 1px solid var(--line);
}

.node-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex-shrink: 0;
  background: #34d99a;
  box-shadow: 0 0 8px color-mix(in srgb, #34d99a 55%, transparent);
}

.node-dot.warn {
  background: #e0a23a;
  box-shadow: 0 0 8px color-mix(in srgb, #e0a23a 55%, transparent);
}

.node-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.node-role {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.node-state {
  width: 72px;
  text-align: right;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: #34d99a;
}

.node-state.warn { color: #e0a23a; }

/* ── Events window ───────────────────────────── */

.ev-body {
  padding: 12px 10px;
}

.ev-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
}

.ev-row + .ev-row {
  border-top: 1px solid var(--line);
}

.ev-level {
  width: 44px;
  flex-shrink: 0;
  font-family: var(--font-mono);
  font-size: 9.5px;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  color: var(--fg-3);
}

.ev-level.info { color: var(--accent); }
.ev-level.warn { color: #e0a23a; }
.ev-level.error { color: #f2757f; }

.ev-text {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-1);
}
</style>
