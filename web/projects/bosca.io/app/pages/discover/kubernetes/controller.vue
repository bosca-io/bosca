<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'The Controller — One secure path to every cluster',
  description: 'Bosca\'s kubernetes-controller is the one service between Studio and your clusters: it holds encrypted kubeconfigs server-side, streams live cluster state over one connection, and gates every read, write, and subscription to an admin — checked at every layer.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Kubernetes', path: '/discover/kubernetes' },
  { name: 'The Controller', path: '/discover/kubernetes/controller' }
], '/og-kubernetes.png')

const FLOW = [
  { step: 'studio', note: 'GraphQL + your token' },
  { step: 'server', note: 'validates · checks admin' },
  { step: 'controller', note: 'decrypts kubeconfig in memory' },
  { step: 'cluster', note: 'kube-apiserver call' }
]

const STREAMS = [
  { name: 'pod logs', note: 'as the pod writes them' },
  { name: 'pod metrics', note: 'CPU · memory, live' },
  { name: 'cluster capacity', note: 'every few seconds' }
]
</script>

<template>
  <DiscoverShell section-id="kubernetes">
    <section class="page-hero">
      <p class="kicker load-1">
        The Controller
      </p>
      <h1 class="load-2">
        One secure path <em>to every cluster</em>
      </h1>
      <p class="section-sub load-3">
        Studio never talks to a cluster directly. One service — the
        kubernetes-controller — holds the credentials, streams the live state, and
        gates every operation. It's how the console stays fast, live, and safe.
      </p>
    </section>

    <!-- ── The controller ──────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The broker
          </p>
          <h2>Between you <em>and the cluster</em></h2>
          <p class="section-sub">
            Every list, watch, apply, and delete flows through the
            kubernetes-controller. It stores each cluster's kubeconfig encrypted,
            decrypts it in memory only when it makes a call, and keeps a warm
            client per cluster — dropped the moment a credential rotates. Because
            it's the single door to your clusters, it's also the single place for
            connection pooling and access control.
          </p>
          <ul class="point-list">
            <li>One service between Studio and every kube-apiserver.</li>
            <li>Kubeconfigs decrypted in memory, never written to disk.</li>
            <li>A warm client per cluster, dropped on rotation.</li>
            <li>One place for health probes and the admin gate.</li>
          </ul>
        </div>
        <div class="flow-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">request path</span>
          </div>
          <div class="flow-body">
            <div
              v-for="(f, i) in FLOW"
              :key="f.step"
              class="flow-row"
            >
              <span class="flow-idx">{{ i + 1 }}</span>
              <span class="flow-step">{{ f.step }}</span>
              <span class="flow-note">{{ f.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Streaming ───────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Live streaming
          </p>
          <h2>Live over <em>one connection</em></h2>
          <p class="section-sub">
            The controller holds a persistent connection open to the browser. A
            pod's logs ride a Kubernetes watch and arrive as it writes them; pod
            metrics and cluster capacity are sampled on a live interval — metrics
            have no watch to ride — and pushed the same way. Streams open when you
            land on a page and close when you leave; switch clusters and they
            follow, reconnecting if a connection blips.
          </p>
          <ul class="point-list">
            <li>Pod logs, pod metrics, and cluster capacity, all streamed live.</li>
            <li>Logs ride Kubernetes watches; metrics sample on a live interval.</li>
            <li>Open on page land, closed on leave, following the cluster.</li>
            <li>Auto-reconnect, with the stream's state always in view.</li>
          </ul>
        </div>
        <div class="stream-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">live streams</span>
          </div>
          <div class="stream-body">
            <div
              v-for="s in STREAMS"
              :key="s.name"
              class="stream-row"
            >
              <span class="stream-dot" />
              <span class="stream-name">{{ s.name }}</span>
              <span class="stream-note">{{ s.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Security model ──────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Security model
          </p>
          <h2>Admin-gated, <em>at every layer</em></h2>
          <p class="section-sub">
            Every operation against a cluster — read, write, or stream — is gated
            to a Bosca administrator, checked on the server before the request
            ever reaches the controller, then checked again there. Even a live
            stream re-checks that you're still an admin while it stays open. And
            the kubeconfig never leaves the server: it's encrypted at rest and
            decrypted only in the controller's memory.
          </p>
          <ul class="point-list">
            <li>Every read, write, and stream is gated to an admin, server-side.</li>
            <li>Kubeconfigs are encrypted at rest and never sent to the browser.</li>
            <li>Rotate or remove a credential; in-flight connections drop.</li>
            <li>Three independent admin checks — resolver, stream, and controller.</li>
          </ul>
        </div>
        <div class="flow-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">security</span>
          </div>
          <div class="sec-body">
            <div class="sec-row">
              <Icon
                name="lock"
                :size="13"
              />
              kubeconfig · encrypted, never returned
            </div>
            <div class="sec-row">
              <Icon
                name="shield-check"
                :size="13"
              />
              admin gate · resolver, stream, controller
            </div>
            <div class="sec-row">
              <Icon
                name="activity"
                :size="13"
              />
              live streams · admin re-checked as they run
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

.flow-window,
.stream-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Flow window ─────────────────────────────── */

.flow-body {
  padding: 12px 10px;
}

.flow-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
}

.flow-row + .flow-row {
  border-top: 1px solid var(--line);
}

.flow-idx {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
}

.flow-step {
  width: 92px;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.flow-note {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Stream window ───────────────────────────── */

.stream-body {
  padding: 12px 10px;
}

.stream-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 12px;
}

.stream-row + .stream-row {
  border-top: 1px solid var(--line);
}

.stream-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex-shrink: 0;
  background: #34d99a;
  box-shadow: 0 0 8px color-mix(in srgb, #34d99a 55%, transparent);
}

.stream-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.stream-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Security window ─────────────────────────── */

.sec-body {
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sec-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 50%, transparent);
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-2);
}

.sec-row svg { color: var(--accent); flex-shrink: 0; }
</style>
