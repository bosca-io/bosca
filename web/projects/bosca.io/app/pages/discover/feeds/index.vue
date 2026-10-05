<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Feeds — RSS in, for-you out',
  description: 'Bosca Feeds pulls RSS, Atom, and JSON Feed sources into the platform on a schedule — deduplicated by GUID, normalized to rich text, and served back as each reader\'s Following and For-you feeds.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Feeds', path: '/discover/feeds' }
], '/og-feeds.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Register a source',
    body: 'Point at an RSS, Atom, or JSON Feed endpoint, set a cron schedule and outbound auth, and enable it. Managed sources serve everyone; user sources belong to one profile.'
  },
  {
    step: '02',
    title: 'Fetch & ingest',
    body: 'Each fetch is a conditional GET — an unchanged feed answers 304 and no-ops. New entries become content records deduplicated by GUID; bodies are normalized to rich text with the original HTML preserved.'
  },
  {
    step: '03',
    title: 'Subscribe & serve',
    body: 'Readers subscribe to sources and get a chronological Following feed. The For-you feed is ranked by the Recommendations subsystem from the same ingested content.'
  }
]

const FEATURES = [
  {
    icon: 'rss',
    title: 'Three formats',
    body: 'RSS 2.0, Atom, and JSON Feed 1.1 — with content:encoded, dc:creator, and media extensions read when present.'
  },
  {
    icon: 'clock',
    title: 'Per-source scheduling',
    body: 'Every enabled source gets its own cron-scheduled fetch job; editing reschedules it, disabling removes it.'
  },
  {
    icon: 'refresh',
    title: 'Conditional fetches',
    body: 'ETag and Last-Modified validators ride each request — an unchanged feed answers 304 Not Modified and the fetch no-ops.'
  },
  {
    icon: 'copy',
    title: 'Idempotent ingestion',
    body: 'Items dedupe by their external GUID: a known entry is updated in place, never duplicated, however often you re-fetch.'
  },
  {
    icon: 'doc',
    title: 'Normalized bodies',
    body: 'Raw HTML becomes the platform\'s rich-text document; the original source HTML is preserved as a supplementary attachment.'
  },
  {
    icon: 'image',
    title: 'Featured images',
    body: 'The most prominent image — media tags, enclosures, or the first image in the body — is imported and linked to the item.'
  },
  {
    icon: 'key',
    title: 'Authenticated origins',
    body: 'Outbound auth for protected feeds — basic, bearer, or API key — with the secret stored write-only.'
  },
  {
    icon: 'sparkles',
    title: 'A personalization seam',
    body: 'Items land as ordinary platform content, firing the standard lifecycle events Recommendations ranks the For-you feed from.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="feeds"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Feeds
        </p>
        <h1 class="load-2">
          RSS in,<br>
          <em>for-you out.</em>
        </h1>
        <p class="hero-sub load-3">
          Feeds pulls RSS, Atom, and JSON Feed sources into the platform on a
          schedule — deduplicated, normalized to rich text, and served back as
          each reader's Following and For-you feeds.
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
        <div class="fetch-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">fetch · example.com/feed.xml</span>
          </div>
          <div class="fetch-body">
            <div class="fetch-rows">
              <div class="fetch-row">
                <span class="fetch-dot ok" />
                <span class="fetch-label">GET · If-None-Match</span>
                <span class="fetch-state ok">200 · 12 items</span>
              </div>
              <div class="fetch-row">
                <span class="fetch-dot muted" />
                <span class="fetch-label">10 known GUIDs</span>
                <span class="fetch-state muted">updated in place</span>
              </div>
              <div class="fetch-row">
                <span class="fetch-dot ok" />
                <span class="fetch-label">2 new GUIDs</span>
                <span class="fetch-state ok">created → ready</span>
              </div>
            </div>
            <div class="fetch-summary">
              <Icon
                name="doc"
                :size="12"
              />
              <span>body → rich text · original kept as <code>original</code></span>
            </div>
            <div class="fetch-event">
              <Icon
                name="sparkles"
                :size="12"
              />
              lifecycle events → <code>recommendations</code>
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
        <h2>Fetch, ingest, <em>serve.</em></h2>
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
          <h2>A real ingester. <em>And a serving surface.</em></h2>
          <p class="section-sub">
            Everything you expect from a feed reader's backend — formats,
            schedules, dedup — plus what you only get when ingested items are
            first-class platform content.
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
            Content-native by design
          </p>
          <h2>Ingested items are <em>real content</em></h2>
          <p class="section-sub">
            Feeds doesn't build a parallel item store. Every ingested entry is
            a content record in the same model your documents and media use —
            so everything the platform does for content, it does for feed
            items.
          </p>
          <ul class="point-list">
            <li>Each item is a content Metadata record carrying source attribution — the source id, the external GUID, and the article link.</li>
            <li>Ingestion fires the standard content lifecycle events; the Recommendations subsystem reacts to those to rank the For-you feed — no feeds-specific wiring.</li>
            <li>Sources reuse the content Source entity — the same one every Metadata record's attribution points at.</li>
            <li>The serving surface is a plain GraphQL namespace — Following, For-you, and item-context recommendations — built to power reader clients.</li>
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
                name="doc"
                :size="15"
              /></span>
              <span class="integ-text">Item → Metadata · source <code>Example News</code></span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="zap"
                :size="15"
              /></span>
              <span class="integ-text">Ready → standard lifecycle event → recommendable</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="image"
                :size="15"
              /></span>
              <span class="integ-text"><code>image.featured</code> → imported into storage</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="users"
                :size="15"
              /></span>
              <span class="integ-text"><code>forYou</code> → ranked per reader</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverFeedsExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#42daf4"
        class="closing-mark"
      />
      <h2>Feeds ship <em>with Bosca</em></h2>
      <p class="section-sub">
        The outside web, flowing into the same platform that stores, ranks,
        and serves your own content. The docs cover sources, ingestion, and
        serving end to end.
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

/* ── Fetch window ────────────────────────────── */

.fetch-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.fetch-body {
  padding: 18px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.fetch-rows {
  display: flex;
  flex-direction: column;
  gap: 2px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 6px;
}

.fetch-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
}

.fetch-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
}

.fetch-dot.ok { background: #34d99a; }
.fetch-dot.muted { background: #94a3b8; }

.fetch-label {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.fetch-state {
  font-family: var(--font-mono);
  font-size: 11px;
}

.fetch-state.ok { color: #34d99a; }
.fetch-state.muted { color: var(--fg-3); }

.fetch-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--fg-1);
}

.fetch-summary code,
.fetch-event code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

.fetch-event {
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
  color: #d3f4fb;
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
  color: #d3f4fb;
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
