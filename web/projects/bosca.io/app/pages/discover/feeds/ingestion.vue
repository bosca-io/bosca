<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Feed Ingestion — Dedup, Normalization & Images',
  description: 'Every feed entry becomes a first-class content record: deduplicated by GUID, its HTML normalized to rich text with the original preserved, and its featured image extracted, credited, and imported into platform storage.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Feeds', path: '/discover/feeds' },
  { name: 'Ingestion', path: '/discover/feeds/ingestion' }
], '/og-feeds.png')
</script>

<template>
  <DiscoverShell section-id="feeds">
    <section class="page-hero">
      <p class="kicker load-1">
        Ingestion
      </p>
      <h1 class="load-2">
        From entry <em>to content</em>
      </h1>
      <p class="section-sub load-3">
        A feed entry doesn't land in a side table — it becomes a content
        record with a readable body, an attributed image, and the same
        lifecycle every other piece of platform content follows.
      </p>
    </section>

    <!-- ── Dedup & lifecycle ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Dedup &amp; lifecycle
          </p>
          <h2>Fetch twice, <em>store once</em></h2>
          <p class="section-sub">
            Every entry is keyed by its source and external GUID — so
            re-fetching a feed is idempotent, however often the schedule
            fires.
          </p>
          <ul class="point-list">
            <li>A new GUID creates a content record with the feed fields — title, summary, author, published date — and source attribution: source id, GUID, article link.</li>
            <li>A known GUID updates the existing record in place; nothing is ever duplicated.</li>
            <li>New items are brought to ready, firing the standard readiness event downstream consumers react to — updates don't re-fire it.</li>
            <li>The dedup mapping — source plus GUID — is recorded alongside the record, so every item traces back to the exact entry it came from.</li>
          </ul>
        </div>
        <div class="ingest-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">ingest · 12 entries</span>
          </div>
          <div class="ingest-body">
            <div class="ingest-row">
              <span class="ingest-guid">guid:a41f…</span>
              <span class="ingest-state muted">known → updated in place</span>
            </div>
            <div class="ingest-row">
              <span class="ingest-guid">guid:b7c2…</span>
              <span class="ingest-state ok">new → created · ready</span>
            </div>
            <div class="ingest-row">
              <span class="ingest-guid">guid:c9d8…</span>
              <span class="ingest-state ok">new → created · ready</span>
            </div>
            <div class="ingest-note">
              2 readiness events fired · 0 duplicates
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Normalization ───────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Normalization
          </p>
          <h2>Readable bodies, <em>originals kept</em></h2>
          <p class="section-sub">
            Feed HTML is messy. Every item gets a clean, structured body —
            and the mess is kept on file, in case you ever want to
            re-process it.
          </p>
          <ul class="point-list">
            <li>The entry's HTML — <code>content:encoded</code>, Atom content, or JSON Feed's <code>content_html</code> — is converted to the platform's rich-text document model.</li>
            <li>That normalized document is the item's body, so serving and preview render it like any authored content.</li>
            <li>The original, un-normalized HTML is preserved as a supplementary attachment under the key <code>original</code> — for audit or re-processing.</li>
            <li>Every item is bound to a shared document template, so feed items carry a consistent type across the platform.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">body · normalized</span>
          </div>
          <div class="code-body">
            <pre><span class="tok-com">&lt;!-- original — kept as `original` --&gt;</span>
<span class="tok-tag">&lt;div&gt;</span>&lt;b&gt;Breaking:&lt;/b&gt; markets rally…<span class="tok-tag">&lt;/div&gt;</span>

<span class="tok-com">// stored body — rich-text document</span>
{ <span class="tok-attr">"type"</span>: <span class="tok-str">"doc"</span>, <span class="tok-attr">"content"</span>: [
  { <span class="tok-attr">"type"</span>: <span class="tok-str">"paragraph"</span>, … }
] }</pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Featured images ─────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Featured images
          </p>
          <h2>The right image, <em>with its credit</em></h2>
          <p class="section-sub">
            Each item's most prominent image becomes its own content record —
            imported into platform storage, attributed, and linked to the
            item.
          </p>
          <ul class="point-list">
            <li>Chosen in order: <code>media:content</code> with image semantics, <code>media:thumbnail</code>, an image enclosure, JSON Feed's <code>image</code> or <code>banner_image</code> — falling back to the first image in the body HTML.</li>
            <li>Attribution comes from the feed's declared <code>media:credit</code>, or the publisher's host when none is declared.</li>
            <li>The bytes are imported into platform storage by the import job — items don't hotlink the origin.</li>
            <li>The image links to its item as an <code>image.featured</code> relationship, and relationships persist across re-fetches — a forced fetch backfills images for older items.</li>
          </ul>
        </div>
        <div class="ladder-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">image extraction</span>
          </div>
          <div class="ladder-body">
            <div class="ladder-row">
              <span class="ladder-step">1</span>
              <span class="ladder-text"><code>media:content</code> / <code>media:thumbnail</code></span>
            </div>
            <div class="ladder-row">
              <span class="ladder-step">2</span>
              <span class="ladder-text">image <code>enclosure</code></span>
            </div>
            <div class="ladder-row">
              <span class="ladder-step">3</span>
              <span class="ladder-text">JSON Feed <code>image</code> · <code>banner_image</code></span>
            </div>
            <div class="ladder-row">
              <span class="ladder-step">4</span>
              <span class="ladder-text">first <code>&lt;img&gt;</code> in the body</span>
            </div>
            <div class="ladder-note">
              → imported · credited · linked as <code>image.featured</code>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverFeedsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Ingest window ───────────────────────────── */

.ingest-window,
.ladder-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.ingest-body,
.ladder-body {
  padding: 10px 8px;
}

.ingest-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 14px;
}

.ingest-row + .ingest-row,
.ladder-row + .ladder-row {
  border-top: 1px solid var(--line);
}

.ingest-guid {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
  min-width: 110px;
}

.ingest-state {
  font-family: var(--font-mono);
  font-size: 11.5px;
}

.ingest-state.ok { color: #34d99a; }
.ingest-state.muted { color: var(--fg-3); }

.ingest-note,
.ladder-note {
  border-top: 1px solid var(--line);
  padding: 11px 14px 8px;
  font-size: 12px;
  color: var(--fg-3);
}

.ladder-note code {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
}

/* ── Ladder window ───────────────────────────── */

.ladder-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.ladder-step {
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
}

.ladder-text {
  font-size: 13px;
  color: var(--fg-1);
}

.ladder-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}
</style>
