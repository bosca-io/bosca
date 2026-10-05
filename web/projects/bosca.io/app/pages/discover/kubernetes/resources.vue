<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Resources — Every resource, in one console',
  description: 'Networking and the Gateway API, ConfigMaps and Secrets with masked values, storage, the operators you run — cert-manager and CloudNativePG — and read-only RBAC, all surfaced for every registered Kubernetes cluster.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Kubernetes', path: '/discover/kubernetes' },
  { name: 'Resources', path: '/discover/kubernetes/resources' }
], '/og-kubernetes.png')

const NET = [
  { name: 'web', kind: 'Service', note: 'LoadBalancer' },
  { name: 'api', kind: 'Ingress', note: 'api.example.com' },
  { name: 'edge', kind: 'Gateway', note: 'HTTPRoute · 4' }
]

const CONFIG = [
  { name: 'app-config', kind: 'ConfigMap', masked: false },
  { name: 'db-creds', kind: 'Secret', masked: true },
  { name: 'tls-cert', kind: 'Secret', masked: true }
]

const OPERATORS = [
  { name: 'api-tls', kind: 'Certificate', note: 'renews in 21d', tone: 'ok' },
  { name: 'star-tls', kind: 'Certificate', note: 'expires in 9d', tone: 'warn' },
  { name: 'orders-db', kind: 'CNPG Cluster', note: '3 instances · primary', tone: 'ok' }
]
</script>

<template>
  <DiscoverShell section-id="kubernetes">
    <section class="page-hero">
      <p class="kicker load-1">
        Resources
      </p>
      <h1 class="load-2">
        Every resource, <em>in one console</em>
      </h1>
      <p class="section-sub load-3">
        Beyond workloads: networking and the Gateway API, config and secrets,
        storage, the operators you run, and the RBAC that governs it — each
        surfaced for the cluster you've got selected.
      </p>
    </section>

    <!-- ── Networking ──────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Networking
          </p>
          <h2>Services, ingress, <em>and Gateway API</em></h2>
          <p class="section-sub">
            See how traffic reaches your workloads — Services by type, Ingresses
            with their hosts and TLS, and Network Policies. The Gateway API is
            here too: Gateways with their listeners and status, and the HTTPRoutes
            attached to them. Apply a new resource straight from the console.
          </p>
          <ul class="point-list">
            <li>Services (ClusterIP, NodePort, LoadBalancer), Ingresses, and policies.</li>
            <li>Gateways with listeners, status, and attached HTTPRoutes.</li>
            <li>Apply a networking manifest to the active cluster.</li>
            <li>Edit or delete a Gateway from its detail page.</li>
          </ul>
        </div>
        <div class="tbl-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">networking</span>
          </div>
          <div class="tbl-body">
            <div
              v-for="n in NET"
              :key="n.name"
              class="tbl-row"
            >
              <span class="tbl-name">{{ n.name }}</span>
              <span class="tbl-kind">{{ n.kind }}</span>
              <span class="tbl-note">{{ n.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Config & secrets ────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Config, secrets &amp; storage
          </p>
          <h2>Settings you can see, <em>secrets you can't</em></h2>
          <p class="section-sub">
            ConfigMaps and Secrets sit together — but a Secret's values stay
            masked. You see its keys and shape, never the raw values, so nothing
            sensitive is pulled back through the server. Storage is here as well:
            persistent volume claims and storage classes, with their capacity,
            access modes, and provisioners.
          </p>
          <ul class="point-list">
            <li>ConfigMaps and Secrets, side by side.</li>
            <li>A Secret's values stay masked — its keys are all you see.</li>
            <li>Edit, create, or delete config from a manifest.</li>
            <li>Persistent volume claims and storage classes, at a glance.</li>
          </ul>
        </div>
        <div class="cfg-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">config &amp; secrets</span>
          </div>
          <div class="cfg-body">
            <div
              v-for="c in CONFIG"
              :key="c.name"
              class="cfg-row"
            >
              <span class="cfg-icon"><Icon
                :name="c.masked ? 'lock' : 'braces'"
                :size="13"
              /></span>
              <span class="cfg-name">{{ c.name }}</span>
              <span class="cfg-kind">{{ c.kind }}</span>
              <span
                v-if="c.masked"
                class="cfg-mask"
              >••••••</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Operators ───────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Operators
          </p>
          <h2>It understands <em>your operators</em></h2>
          <p class="section-sub">
            The console doesn't stop at raw custom resources. It surfaces the
            operators running in your cluster — and gives the ones you lean on a
            real view. cert-manager certificates show their status, DNS names, and
            how long until they expire and renew. CloudNativePG Postgres clusters
            show their topology, instances, and backups.
          </p>
          <ul class="point-list">
            <li>Every operator and the custom resources it defines.</li>
            <li>cert-manager: certificate status, expiry, and renewal windows.</li>
            <li>CloudNativePG: Postgres topology, instances, and backups.</li>
            <li>Issue a certificate, or apply a new resource, from a manifest.</li>
          </ul>
        </div>
        <div class="op-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">operators</span>
          </div>
          <div class="op-body">
            <div
              v-for="o in OPERATORS"
              :key="o.name"
              class="op-row"
            >
              <span class="op-name">{{ o.name }}</span>
              <span class="op-kind">{{ o.kind }}</span>
              <span
                class="op-note"
                :class="o.tone"
              >{{ o.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Access control ──────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Access control
        </p>
        <h2>See who <em>can do what</em></h2>
        <p class="section-sub">
          The cluster's own RBAC is here to inspect — Roles and ClusterRoles with
          their rule counts, RoleBindings and their subjects, and Service Accounts
          down to the cloud-IAM annotations that bind them. A clear picture of who
          holds what, right beside everything else.
        </p>
      </div>
      <div class="rbac-window reveal">
        <div class="rbac-row">
          <span class="rbac-kind">ClusterRole</span>
          <span class="rbac-name">admin</span>
          <span class="rbac-meta">42 rules · 3 bindings</span>
        </div>
        <div class="rbac-row">
          <span class="rbac-kind">RoleBinding</span>
          <span class="rbac-name">deploy-bot</span>
          <span class="rbac-meta">ServiceAccount · ci</span>
        </div>
      </div>
    </section>

    <DiscoverKubernetesExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.tbl-window,
.cfg-window,
.op-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Table window (networking / operators) ───── */

.tbl-body,
.op-body {
  padding: 12px 10px;
}

.tbl-row,
.op-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 12px;
}

.tbl-row + .tbl-row,
.op-row + .op-row {
  border-top: 1px solid var(--line);
}

.tbl-name,
.op-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.tbl-kind,
.op-kind {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
}

.tbl-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  width: 128px;
  text-align: right;
}

.op-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: #34d99a;
  width: 118px;
  text-align: right;
}

.op-note.warn { color: #e0a23a; }

/* ── Config window ───────────────────────────── */

.cfg-body {
  padding: 12px 10px;
}

.cfg-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 12px;
}

.cfg-row + .cfg-row {
  border-top: 1px solid var(--line);
}

.cfg-icon {
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.cfg-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.cfg-kind {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.cfg-mask {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-3);
  letter-spacing: 0.1em;
}

/* ── RBAC window ─────────────────────────────── */

.rbac-window {
  max-width: 560px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.rbac-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
}

.rbac-row + .rbac-row {
  border-top: 1px solid var(--line);
}

.rbac-kind {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
  width: 92px;
}

.rbac-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-1);
}

.rbac-meta {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 520px) {
  .tbl-note {
    display: none;
  }
}
</style>
