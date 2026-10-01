<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'System — Run the platform, end to end',
  description: 'Bosca System is the operating layer of the platform: durable background jobs and a cron scheduler, principals and groups and passkeys, object storage and backups, and the pluggable datastores it all runs on.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'System', path: '/discover/system' }
], '/og-system.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Operate',
    body: 'Run background jobs on a durable queue, schedule them with cron, and see every run in one history.'
  },
  {
    step: '02',
    title: 'Secure',
    body: 'Principals, groups, and permissions; passwordless passkeys, OAuth sign-in, and scoped API tokens.'
  },
  {
    step: '03',
    title: 'Configure',
    body: 'Change how the platform behaves without a redeploy, and bring in the outside services you already use.'
  }
]

const FEATURES = [
  {
    icon: 'zap',
    title: 'Durable jobs',
    body: 'Background work on NATS JetStream or Redis, with at-least-once delivery and automatic retries.'
  },
  {
    icon: 'clock',
    title: 'Cron scheduler',
    body: 'Scheduled jobs on standard cron, with catch-up for missed runs and a full run history.'
  },
  {
    icon: 'heart-pulse',
    title: 'Content health check',
    body: 'A report over your content graph — published items pointing at unpublished ones, and more.'
  },
  {
    icon: 'users',
    title: 'Principals & groups',
    body: 'One identity behind every profile, device, and token; permissions granted to flat groups.'
  },
  {
    icon: 'fingerprint',
    title: 'Passkeys & OAuth',
    body: 'Passwordless WebAuthn sign-in, plus OAuth with Google, Apple, and more.'
  },
  {
    icon: 'key-round',
    title: 'Scoped API tokens',
    body: 'Programmatic tokens scoped within a principal\'s permissions, for scripts and CI.'
  },
  {
    icon: 'package',
    title: 'Backups & restore',
    body: 'Snapshot Postgres and your files to one archive, and restore with a conflict strategy.'
  },
  {
    icon: 'database',
    title: 'Pluggable services',
    body: 'Cache, job queue, and object storage sit behind interfaces — Redis or NATS, S3 or GCS, your choice.'
  }
]

const STATUS = [
  { label: 'jobs', val: '3 running · 128 today', tone: 'ok' },
  { label: 'scheduler', val: 'next 03:00', tone: 'ok' },
  { label: 'postgres', val: '42 / 100 conns', tone: 'ok' },
  { label: 'nats', val: '6 streams · 9 consumers', tone: 'ok' }
]

const PLUGGABLE = [
  { k: 'job queue', v: 'nats · redis' },
  { k: 'cache', v: 'redis · nats' },
  { k: 'pub / sub', v: 'nats · redis' },
  { k: 'object storage', v: 's3 · gcs · fs' }
]
</script>

<template>
  <DiscoverShell
    section-id="system"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca System
        </p>
        <h1 class="load-2">
          Run the platform,<br>
          <em>end to end.</em>
        </h1>
        <p class="hero-sub load-3">
          System is the operating layer underneath Bosca — durable background
          jobs and scheduling, identity and access, object storage and backups,
          the datastores it all runs on, and the settings that tie it together.
          In one place.
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
        <div class="stat-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">system · status</span>
          </div>
          <div class="stat-body">
            <div
              v-for="s in STATUS"
              :key="s.label"
              class="stat-row"
            >
              <span
                class="stat-dot"
                :class="s.tone"
              />
              <span class="stat-label">{{ s.label }}</span>
              <span class="stat-val">{{ s.val }}</span>
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
        <h2>Operate, secure, <em>configure.</em></h2>
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
            What's under the hood
          </p>
          <h2>The infrastructure, <em>handled</em></h2>
          <p class="section-sub">
            The jobs, the scheduler, the identity model, storage, and backups —
            the machinery your platform runs on, in one place.
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

    <!-- ── Content health ──────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Content health
          </p>
          <h2>It checks your content, <em>not just your CPU</em></h2>
          <p class="section-sub">
            Most admin panels watch connections and memory. Bosca's health check
            reads your content graph and flags what publishing can miss — a
            published page pointing at an unpublished document, a guide with steps
            that never shipped, a collection with hidden children, a schedule that
            came and went. Some it can fix on the spot.
          </p>
          <ul class="point-list">
            <li>Find published content that depends on unpublished content.</li>
            <li>Catch scheduled items that never went out, and stuck workflows.</li>
            <li>See failed content jobs, and items published without content.</li>
            <li>Resolve some issues inline, without leaving the report.</li>
          </ul>
        </div>
        <div class="health-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">content · health check</span>
          </div>
          <div class="health-body">
            <div class="health-row">
              <span class="health-count">4</span>
              <span class="health-text">Published with unpublished relationships</span>
            </div>
            <div class="health-row">
              <span class="health-count">2</span>
              <span class="health-text">Scheduled but not published</span>
            </div>
            <div class="health-row">
              <span class="health-count">1</span>
              <span class="health-text">Guides with unpublished steps</span>
            </div>
            <div class="health-foot">
              <Icon
                name="check-circle"
                :size="12"
              />
              resolve unpublished relationships
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Pluggable infrastructure ────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Pluggable by design
          </p>
          <h2>Nothing's <em>hardwired</em></h2>
          <p class="section-sub">
            Bosca's services sit behind interfaces, so you're not bolted to one
            vendor's stack. The job queue, the cache, and pub/sub each run on
            Redis or NATS. Object storage is S3, Google Cloud, or the filesystem —
            or anything S3-compatible. Swap a backend, and the code that uses it
            never notices.
          </p>
          <ul class="point-list">
            <li>Services sit behind interfaces — swap a backend, keep the code.</li>
            <li>Job queue, cache, and pub/sub on Redis or NATS.</li>
            <li>Object storage on S3, Google Cloud, or the filesystem.</li>
            <li>Anything S3-compatible works, too.</li>
          </ul>
        </div>
        <div class="plug-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">infrastructure</span>
          </div>
          <div class="plug-body">
            <div
              v-for="row in PLUGGABLE"
              :key="row.k"
              class="plug-row"
            >
              <span class="plug-k">{{ row.k }}</span>
              <span class="plug-v">{{ row.v }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverSystemExplore title="Go deeper" />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#f4726f"
        class="closing-mark"
      />
      <h2>The platform, <em>and everything under it</em></h2>
      <p class="section-sub">
        Jobs, scheduling, identity, storage, backups, and configuration — the
        operating layer that keeps Bosca running.
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

/* ── Status window (hero) ────────────────────── */

.stat-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.stat-body {
  padding: 10px 8px;
}

.stat-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
}

.stat-row + .stat-row {
  border-top: 1px solid var(--line);
}

.stat-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex-shrink: 0;
  background: var(--fg-3);
}

.stat-dot.ok {
  background: #34d99a;
  box-shadow: 0 0 8px color-mix(in srgb, #34d99a 60%, transparent);
}

.stat-label {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-3);
  width: 92px;
}

.stat-val {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
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

/* ── Health window ───────────────────────────── */

.health-window,
.plug-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.health-body {
  padding: 12px 10px;
}

.health-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 12px;
}

.health-row + .health-row {
  border-top: 1px solid var(--line);
}

.health-count {
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 650;
  color: #f2757f;
  width: 20px;
  text-align: center;
  flex-shrink: 0;
}

.health-text {
  font-size: 13px;
  color: var(--fg-1);
}

.health-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  padding: 12px 12px 8px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}

/* ── Pluggable window ────────────────────────── */

.plug-body {
  padding: 12px 10px;
}

.plug-row {
  display: flex;
  align-items: baseline;
  gap: 14px;
  padding: 14px 12px;
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.plug-row + .plug-row {
  border-top: 1px solid var(--line);
}

.plug-k {
  width: 108px;
  flex-shrink: 0;
  color: var(--fg-3);
}

.plug-v { color: var(--accent); }

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
