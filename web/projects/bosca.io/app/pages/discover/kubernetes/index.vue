<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Kubernetes — Your clusters, in your platform',
  description: 'A full multi-cluster Kubernetes console built into Bosca Studio. Register clusters, watch nodes, pods, and events stream in live, and operate your workloads — with kubeconfigs that stay on the server, every action admin-gated at every layer.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Kubernetes', path: '/discover/kubernetes' }
], '/og-kubernetes.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Secure', href: '#secure' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Register',
    body: 'Upload a cluster\'s kubeconfig. Bosca encrypts it, keeps it on the server, and connects through the controller.'
  },
  {
    step: '02',
    title: 'Watch',
    body: 'Health, nodes, pods, and events stream into Studio — logs and metrics tick in real time as they happen.'
  },
  {
    step: '03',
    title: 'Operate',
    body: 'Scale, restart, delete, and apply — every action gated to admins, checked at every layer.'
  }
]

const FEATURES = [
  {
    icon: 'layers',
    title: 'Multi-cluster',
    body: 'Register every cluster and switch between them from one console — the switcher follows you across the subsystem.'
  },
  {
    icon: 'activity',
    title: 'Live fleet health',
    body: 'Nodes, capacity, and a healthy / degraded / failing badge per cluster, kept current.'
  },
  {
    icon: 'boxes',
    title: 'Workloads',
    body: 'Deployments through CronJobs — scale, restart, delete, and apply a manifest, right from the table.'
  },
  {
    icon: 'align-left',
    title: 'Live pod logs',
    body: 'Stream a pod\'s logs and metrics over a live connection, with pause and clear.'
  },
  {
    icon: 'key-round',
    title: 'Config & secrets',
    body: 'ConfigMaps and Secrets, with a Secret\'s values kept masked — its keys are all you see.'
  },
  {
    icon: 'share-2',
    title: 'Networking & Gateway API',
    body: 'Services, Ingresses, and Network Policies, plus Gateways and their HTTPRoutes.'
  },
  {
    icon: 'braces',
    title: 'Operators',
    body: 'The operators you run get first-class views — cert-manager certificates and CloudNativePG Postgres clusters.'
  },
  {
    icon: 'shield-check',
    title: 'Credentials stay server-side',
    body: 'The controller holds every kubeconfig; the browser gets data about your cluster, never the credentials.'
  }
]

const OVERVIEW = [
  { k: 'nodes', v: '12 ready · 12', bar: null },
  { k: 'pods', v: '248 running', bar: null },
  { k: 'cpu', v: '41%', bar: 41 },
  { k: 'memory', v: '58%', bar: 58 }
]

const GATE = [
  { layer: 'resolver', note: 'admin check' },
  { layer: 'stream', note: 're-checked every 60s' },
  { layer: 'controller', note: 'admin check' }
]
</script>

<template>
  <DiscoverShell
    section-id="kubernetes"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Kubernetes
        </p>
        <h1 class="load-2">
          Your clusters,<br>
          <em>in your platform.</em>
        </h1>
        <p class="hero-sub load-3">
          A full Kubernetes console, built into Bosca Studio. Register your
          clusters, watch their state stream in live, and operate your workloads —
          with kubeconfigs that stay on the server, never in the browser.
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
        <div class="ov-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">cluster · prod-us-east</span>
            <span class="ov-health">healthy</span>
          </div>
          <div class="ov-body">
            <div
              v-for="o in OVERVIEW"
              :key="o.k"
              class="ov-row"
            >
              <span class="ov-k">{{ o.k }}</span>
              <span
                v-if="o.bar !== null"
                class="ov-bar"
              ><span
                class="ov-fill"
                :style="{ width: o.bar + '%' }"
              /></span>
              <span class="ov-v">{{ o.v }}</span>
              <span
                v-if="o.bar !== null"
                class="ov-live"
              >live</span>
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
        <h2>Register, watch, <em>operate.</em></h2>
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
            What you can do
          </p>
          <h2>A real console, <em>not a dashboard</em></h2>
          <p class="section-sub">
            View, stream, and operate — every registered cluster, every workload,
            and the resources around them, from inside the platform you already
            run.
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

    <!-- ── Live streaming ──────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Live, not polled
          </p>
          <h2>State that <em>streams in</em></h2>
          <p class="section-sub">
            The console holds a live connection open instead of making you reload.
            A pod's logs ride a Kubernetes watch and land as it writes them; its
            CPU and memory, and the cluster's capacity, are sampled on a live
            interval and pushed straight to you. Switch clusters and the streams
            follow.
          </p>
          <ul class="point-list">
            <li>Pod logs ride a Kubernetes watch, arriving as they're written.</li>
            <li>Pod and cluster metrics are sampled live and pushed to you.</li>
            <li>The stream reconnects on its own, and shows its state.</li>
            <li>Pause or clear the log view without dropping the stream.</li>
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
              <span class="log-t">12:04:01</span> GET /api/feed 200 41ms
            </div>
            <div class="log-line">
              <span class="log-t">12:04:02</span> cache hit · feed:home
            </div>
            <div class="log-line accent">
              <span class="log-t">12:04:02</span> POST /api/track 202 2ms
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Secure ──────────────────────────────── -->
    <section
      id="secure"
      class="section"
    >
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Secure by design
          </p>
          <h2>Credentials that <em>never leave the server</em></h2>
          <p class="section-sub">
            A cluster's kubeconfig is encrypted at rest and lives only on the
            server — the controller decrypts it in memory to make a call, and the
            browser only ever receives data about your cluster, never the
            credentials. And every operation is gated to a Bosca administrator —
            checked on the server, then checked again at the controller.
          </p>
          <ul class="point-list">
            <li>Kubeconfigs are encrypted and stay on the server.</li>
            <li>The browser gets cluster data, never the credentials.</li>
            <li>Every read, write, and stream is gated to an admin.</li>
            <li>That gate holds at every layer, not just the UI.</li>
          </ul>
        </div>
        <div class="gate-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">admin gate</span>
          </div>
          <div class="gate-body">
            <div
              v-for="g in GATE"
              :key="g.layer"
              class="gate-row"
            >
              <span class="gate-icon"><Icon
                name="shield-check"
                :size="13"
              /></span>
              <span class="gate-layer">{{ g.layer }}</span>
              <span class="gate-note">{{ g.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverKubernetesExplore title="Go deeper" />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#88a9f7"
        class="closing-mark"
      />
      <h2>Your clusters, <em>where your platform is</em></h2>
      <p class="section-sub">
        One console for every cluster — live, secure, and part of the platform you
        already run, not another tool on the side.
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

/* ── Overview window (hero) ──────────────────── */

.ov-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.ov-health {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: #34d99a;
  border: 1px solid color-mix(in srgb, #34d99a 40%, transparent);
  border-radius: 999px;
  padding: 1px 8px;
}

.ov-body {
  padding: 14px 12px;
}

.ov-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 12px;
}

.ov-row + .ov-row {
  border-top: 1px solid var(--line);
}

.ov-k {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-3);
  width: 60px;
}

.ov-bar {
  flex: 1;
  height: 6px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--fg-3) 18%, transparent);
  overflow: hidden;
}

.ov-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--accent);
}

.ov-v {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
  margin-left: auto;
}

.ov-row:first-child .ov-v,
.ov-row:nth-child(2) .ov-v {
  margin-left: 0;
}

.ov-live {
  font-family: var(--font-mono);
  font-size: 9px;
  color: var(--accent);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

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

/* ── Log window ──────────────────────────────── */

.log-window,
.gate-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

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

/* ── Gate window ─────────────────────────────── */

.gate-body {
  padding: 12px 10px;
}

.gate-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 12px;
}

.gate-row + .gate-row {
  border-top: 1px solid var(--line);
}

.gate-icon {
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

.gate-layer {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.gate-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
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
}

@media (max-width: 720px) {
  .feature-grid {
    grid-template-columns: 1fr;
  }
}
</style>
