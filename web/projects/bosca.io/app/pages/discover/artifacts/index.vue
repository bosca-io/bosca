<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Artifacts — One registry, six formats',
  description: 'Bosca Artifacts is a multi-format artifact registry — Docker, Helm, Maven, npm, ML models, and raw files — with content-addressed storage, digest verification, scoped tokens, and publish events that gate CI.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Artifacts', path: '/discover/artifacts' }
], '/og-artifacts.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Create a namespace',
    body: 'Group repositories under a namespace — public for anonymous pulls, or private behind group permissions. Add typed repositories: docker, helm, maven, npm, ml, or raw.'
  },
  {
    step: '02',
    title: 'Push with your tools',
    body: 'Docker, Helm, Gradle, Maven, and npm publish straight to the registry — it speaks each client\'s native protocol, hashes every upload, and stores identical blobs once.'
  },
  {
    step: '03',
    title: 'Pull anywhere',
    body: 'People pull with group permissions, machines with scoped tokens, and anyone from public namespaces. CI jobs can require an artifact and dispatch the moment it lands.'
  }
]

const FEATURES = [
  {
    icon: 'container',
    title: 'Six formats',
    body: 'Docker images, Helm charts, Maven artifacts, npm packages, ML models, and raw files — each a typed repository inside a namespace.'
  },
  {
    icon: 'globe',
    title: 'Standard protocols',
    body: 'The OCI distribution API, Maven layout with generated metadata, the npm registry protocol with search, and Helm\'s index.yaml — clients don\'t change.'
  },
  {
    icon: 'database',
    title: 'Content-addressed storage',
    body: 'Blobs are keyed by digest and stored once — push the same layer into two repositories and it lands as one object.'
  },
  {
    icon: 'fingerprint',
    title: 'Digest verification',
    body: 'Uploads are hashed as they stream in; Docker content is verified against its declared digest and rejected on mismatch.'
  },
  {
    icon: 'key',
    title: 'Scoped tokens',
    body: 'Token scopes narrow to a format, a namespace or repository, and a version pattern — granting exactly pull, push, or admin.'
  },
  {
    icon: 'users',
    title: 'One permission model',
    body: 'Namespaces are public or private, and private access is granted to the same security groups used across Bosca.'
  },
  {
    icon: 'zap',
    title: 'Publish events',
    body: 'Every published version fires a platform event — the signal CI requirement gates and automation listen for.'
  },
  {
    icon: 'server',
    title: 'A lean server',
    body: 'A standalone service that compiles to a GraalVM native image — bytes in S3-compatible object storage, metadata in PostgreSQL.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="artifacts"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Artifacts
        </p>
        <h1 class="load-2">
          One registry,<br>
          <em>six formats.</em>
        </h1>
        <p class="hero-sub load-3">
          Docker images, Helm charts, Maven artifacts, npm packages, ML models,
          and raw files — pushed with the standard tools you already use,
          stored once in content-addressed storage, and governed by one
          permission model.
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
        <div class="push-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">docker push · library/api:1.4.0</span>
          </div>
          <div class="push-body">
            <div class="push-rows">
              <div class="push-row">
                <span class="push-dot exists" />
                <span class="push-digest">7d9c4f2a</span>
                <span class="push-state muted">layer already exists</span>
              </div>
              <div class="push-row">
                <span class="push-dot ok" />
                <span class="push-digest">e3b0c442</span>
                <span class="push-state ok">pushed</span>
              </div>
              <div class="push-row">
                <span class="push-dot ok" />
                <span class="push-digest">manifest</span>
                <span class="push-state ok">sha256 verified</span>
              </div>
            </div>
            <div class="push-summary">
              <Icon
                name="tag"
                :size="12"
              />
              <span><code>1.4.0</code> → <code>library/api</code> · stored once</span>
            </div>
            <div class="push-event">
              <Icon
                name="zap"
                :size="12"
              />
              event · <code>bosca.artifacts.version.published</code>
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
        <h2>Push, verify, <em>serve.</em></h2>
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
          <h2>A real registry. <em>And part of the platform.</em></h2>
          <p class="section-sub">
            Everything you expect from an artifact registry — native protocols,
            deduplicated storage, scoped credentials — plus the integrations you
            only get when the registry lives next to your code and CI.
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

    <!-- ── Integration ─────────────────────────── -->
    <section class="section integ">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Deep platform integration
          </p>
          <h2>Not a shelf — <em>part of the pipeline</em></h2>
          <p class="section-sub">
            Because the registry isn't a separate product bolted onto Bosca, a
            published version isn't just a file on a shelf — it's an event the
            rest of the platform acts on.
          </p>
          <ul class="point-list">
            <li>CI jobs declare artifact requirements — a release that compiles against <code>io.bosca:core</code> at the new version simply waits, then dispatches the moment the registry publishes it.</li>
            <li>Every published version fires a platform event over pub/sub, so waiting builds re-evaluate immediately — no polling loops, no retry scripts.</li>
            <li>ML models are artifacts too: recommendation models are published as versioned archives and pulled for serving from the same registry as your images and jars.</li>
            <li>Namespaces, repositories, versions, tags, and permissions are managed in Studio and over a GraphQL admin API — with the same security groups as the rest of Bosca.</li>
          </ul>
        </div>
        <div class="integ-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">connected</span>
          </div>
          <div class="integ-body">
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="rocket"
                :size="15"
              /></span>
              <span class="integ-text">CI job <code>waiting on artifacts</code> → dispatched</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="zap"
                :size="15"
              /></span>
              <span class="integ-text">Event → <code>bosca.artifacts.version.published</code></span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="brain"
                :size="15"
              /></span>
              <span class="integ-text">Model pulled from <code>model/…</code> for serving</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="monitor"
                :size="15"
              /></span>
              <span class="integ-text">Managed in Studio → <code>Artifacts</code></span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverArtifactsExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#f764f6"
        class="closing-mark"
      />
      <h2>Artifacts ship <em>with Bosca</em></h2>
      <p class="section-sub">
        One registry beside your code, your CI, and your deployments — no
        separate product to run, no second set of accounts. The docs cover
        namespaces, repositories, versions, and permissions end to end.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
  align-items: center;
  gap: 48px;
  max-width: 1140px;
  margin: 0 auto;
  padding: 64px 32px 100px;
}

.hero h1 {
  font-size: clamp(40px, 5.4vw, 64px);
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
  max-width: 470px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

/* ── Push window ─────────────────────────────── */

.push-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.push-body {
  padding: 18px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.push-rows {
  display: flex;
  flex-direction: column;
  gap: 2px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 6px;
}

.push-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
}

.push-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
}

.push-dot.ok { background: #34d99a; }
.push-dot.exists { background: #94a3b8; }

.push-digest {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.push-state {
  font-family: var(--font-mono);
  font-size: 11px;
}

.push-state.ok { color: #34d99a; }
.push-state.muted { color: var(--fg-3); }

.push-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--fg-1);
}

.push-summary code,
.push-event code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

.push-event {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--fg-3);
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
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #f0d5f5;
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

/* ── Integration ─────────────────────────────── */

.integ {
  padding-top: 90px;
}

.integ-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.integ-body {
  padding: 10px 8px;
}

.integ-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
}

.integ-row + .integ-row {
  border-top: 1px solid var(--line);
}

.integ-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #f0d5f5;
}

.integ-text {
  font-size: 13px;
  color: var(--fg-1);
}

.integ-text code {
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
    grid-template-columns: minmax(0, 1fr);
    padding-top: 36px;
    padding-bottom: 64px;
  }

  .how-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .feature-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .feature-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
