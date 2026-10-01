<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Workloads — Run the workloads, watch the logs',
  description: 'Deployments through CronJobs in every registered cluster — scale, rolling-restart, and delete from the table, stream a pod\'s logs and metrics live, and apply any manifest straight to the cluster. Every change admin-gated at every layer.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Kubernetes', path: '/discover/kubernetes' },
  { name: 'Workloads', path: '/discover/kubernetes/workloads' }
], '/og-kubernetes.png')

const WORKLOAD = [
  { k: 'kind', v: 'Deployment' },
  { k: 'replicas', v: '3 / 3' },
  { k: 'image', v: 'ghcr.io/app:6.1' },
  { k: 'actions', v: 'scale · restart · delete' }
]

const ACTIONS = [
  { name: 'Scale', note: 'set desired replicas', icon: 'trending-up' },
  { name: 'Restart', note: 'rolling restart', icon: 'rotate-cw' },
  { name: 'Delete', note: 'remove workload', icon: 'trash-2' }
]
</script>

<template>
  <DiscoverShell section-id="kubernetes">
    <section class="page-hero">
      <p class="kicker load-1">
        Workloads
      </p>
      <h1 class="load-2">
        Run the workloads, <em>watch the logs</em>
      </h1>
      <p class="section-sub load-3">
        Every Deployment, StatefulSet, and CronJob in the cluster — scale,
        restart, and delete from the table, stream a pod's logs as they happen,
        and apply a manifest when you need to change something.
      </p>
    </section>

    <!-- ── Workloads ───────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The work
          </p>
          <h2>Scale, restart, <em>delete</em></h2>
          <p class="section-sub">
            Deployments, StatefulSets, DaemonSets, ReplicaSets, Jobs, and CronJobs
            all sit in one table — with replicas, status, image, and restarts.
            Scale a workload to a new replica count, roll a restart through its
            pods, or delete it — right from the row. These take effect
            immediately, so there's no dry-run to hide behind.
          </p>
          <ul class="point-list">
            <li>Every workload kind, from Deployment to CronJob, in one place.</li>
            <li>Scale to a new replica count from the row or the detail page.</li>
            <li>Roll a workload's pods with a restart, one at a time.</li>
            <li>Delete a workload and its pods when you mean to.</li>
          </ul>
        </div>
        <div class="wl-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">workload · web</span>
          </div>
          <div class="wl-body">
            <div
              v-for="row in WORKLOAD"
              :key="row.k"
              class="wl-row"
            >
              <span class="wl-k">{{ row.k }}</span>
              <span class="wl-v">{{ row.v }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Live pod logs ───────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Live pod logs
          </p>
          <h2>Logs as the pod <em>writes them</em></h2>
          <p class="section-sub">
            Open a pod and its logs stream in over a live connection, following
            the tail — while its CPU and memory tick alongside, in real time.
            Pause the view to read, clear the buffer, and let it reconnect on its
            own if the connection blips. Need a pod gone? Delete it, and its
            controller brings a fresh one back.
          </p>
          <ul class="point-list">
            <li>Live log streaming, following the tail as lines arrive.</li>
            <li>Pod CPU and memory ticking in real time beside the logs.</li>
            <li>Pause and clear the view; the stream reconnects on its own.</li>
            <li>Delete a pod — its controller replaces it.</li>
          </ul>
        </div>
        <div class="log-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">pod · web-7c9 · logs</span>
            <span class="log-state">streaming</span>
          </div>
          <div class="log-body">
            <div class="log-line">
              <span class="log-t">12:04:01</span> GET /api/health 200 3ms
            </div>
            <div class="log-line">
              <span class="log-t">12:04:01</span> worker picked up job 4821
            </div>
            <div class="log-line">
              <span class="log-t">12:04:02</span> GET /api/feed 200 41ms
            </div>
            <div class="log-line accent">
              <span class="log-t">12:04:02</span> POST /api/track 202 2ms
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Apply ───────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Apply
          </p>
          <h2>Change it <em>with a manifest</em></h2>
          <p class="section-sub">
            When a workload needs more than a scale or a restart, apply a
            manifest. A YAML editor sends it straight to the active cluster — the
            same path that scales and restarts run on, gated to admins at every
            layer, with an optional server-side dry-run. Jobs and CronJobs work
            the same way.
          </p>
          <ul class="point-list">
            <li>Apply raw YAML straight to the active cluster.</li>
            <li>The same admin-gated path as scale, restart, and delete.</li>
            <li>Create a CronJob from a manifest.</li>
            <li>View a workload's live manifest on its detail page.</li>
          </ul>
        </div>
        <div class="act-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">workload actions</span>
          </div>
          <div class="act-body">
            <div
              v-for="a in ACTIONS"
              :key="a.name"
              class="act-row"
            >
              <span class="act-icon"><Icon
                :name="a.icon"
                :size="14"
              /></span>
              <span class="act-name">{{ a.name }}</span>
              <span class="act-note">{{ a.note }}</span>
            </div>
            <div class="act-foot">
              <Icon
                name="braces"
                :size="12"
              />
              or apply a manifest · admin-gated
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

.wl-window,
.log-window,
.act-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Workload window ─────────────────────────── */

.wl-body {
  padding: 12px 10px;
}

.wl-row {
  display: flex;
  align-items: baseline;
  gap: 14px;
  padding: 14px 12px;
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.wl-row + .wl-row {
  border-top: 1px solid var(--line);
}

.wl-k {
  width: 78px;
  flex-shrink: 0;
  color: var(--fg-3);
}

.wl-v { color: var(--fg-1); }

.wl-row:last-child .wl-v { color: var(--accent); }

/* ── Log window ──────────────────────────────── */

.log-state {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: #34d99a;
  border: 1px solid color-mix(in srgb, #34d99a 40%, transparent);
  border-radius: 999px;
  padding: 1px 8px;
}

.log-body {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.log-line {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-2);
}

.log-line.accent { color: var(--fg-0); }

.log-t {
  color: var(--fg-3);
  margin-right: 8px;
}

/* ── Actions window ──────────────────────────── */

.act-body {
  padding: 12px 10px;
}

.act-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 12px;
}

.act-row + .act-row {
  border-top: 1px solid var(--line);
}

.act-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.act-name {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-1);
}

.act-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.act-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  padding: 13px 12px 8px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}
</style>
