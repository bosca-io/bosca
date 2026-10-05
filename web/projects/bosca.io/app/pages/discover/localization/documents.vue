<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Documents — Localize whole content, not just strings',
  description: 'Link a content document from the CMS to a localization project and translate it per language — stored as structured content that mirrors the source, moving through the same review workflow, and round-tripped through HTML for outside translation tools.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Localization', path: '/discover/localization' },
  { name: 'Documents', path: '/discover/localization/documents' }
], '/og-localization.png')

const VARIANTS = [
  { lang: 'en', note: 'source', tone: 'src' },
  { lang: 'es', note: 'published', tone: 'ok' },
  { lang: 'fr', note: 'in review', tone: 'review' },
  { lang: 'de', note: 'draft', tone: 'draft' }
]
</script>

<template>
  <DiscoverShell section-id="localization">
    <section class="page-hero">
      <p class="kicker load-1">
        Documents
      </p>
      <h1 class="load-2">
        Not just strings — <em>whole documents</em>
      </h1>
      <p class="section-sub load-3">
        A product isn't only labels and buttons. Articles, guides, and pages
        need translating too. Link a content document from the CMS and localize
        the whole thing, per language, with the same care and the same workflow.
      </p>
    </section>

    <!-- ── Link & translate ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Link &amp; translate
          </p>
          <h2>One source, <em>a version per language</em></h2>
          <p class="section-sub">
            Point a localization project at an existing content document and it
            becomes the source. Each language gets its own translated version,
            stored as structured content that mirrors the source — not a flat
            copy — so formatting and structure carry across intact.
          </p>
          <ul class="point-list">
            <li>Link any content document from the CMS as a source to translate.</li>
            <li>Each language's version is stored as structured content, matching the source shape.</li>
            <li>Every version moves through the same review workflow as a string.</li>
          </ul>
        </div>
        <div class="doc-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">document · Welcome Guide</span>
          </div>
          <div class="doc-body">
            <div
              v-for="v in VARIANTS"
              :key="v.lang"
              class="doc-row"
            >
              <span class="doc-lang">{{ v.lang }}</span>
              <span class="doc-bar"><span :class="v.tone" /></span>
              <span
                class="doc-note"
                :class="v.tone"
              >{{ v.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── HTML round-trip ─────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Round-trip
          </p>
          <h2>Out to your tools, <em>and back</em></h2>
          <p class="section-sub">
            Translators often live in their own tools. A document can be exported
            to HTML, translated wherever your team works, and imported straight
            back — the structured content rebuilt from the HTML, no copy-paste,
            no lost formatting.
          </p>
          <ul class="point-list">
            <li>Export a document's language to clean HTML for outside translation.</li>
            <li>Import the translated HTML back into structured content.</li>
            <li>The round-trip preserves the document's shape end to end.</li>
          </ul>
        </div>
        <div class="rt-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">Welcome Guide · es</span>
          </div>
          <div class="rt-body">
            <div class="rt-node">
              <span class="rt-icon"><Icon
                name="download"
                :size="14"
              /></span>
              <span class="rt-text">export → HTML</span>
            </div>
            <div class="rt-mid">
              <Icon
                name="languages"
                :size="13"
              />
              translate in your tool
            </div>
            <div class="rt-node accent">
              <span class="rt-icon"><Icon
                name="upload"
                :size="14"
              /></span>
              <span class="rt-text">import → structured content</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Document vs string ──────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Two units, one system
        </p>
        <h2>Strings and documents, <em>side by side</em></h2>
        <p class="section-sub">
          A project holds both. Strings cover the interface; documents cover the
          content. Linking a document doesn't shred it into strings — it stays
          whole, translated as a document, while your UI strings stay strings.
        </p>
      </div>
      <div class="unit-grid reveal">
        <div class="unit-card">
          <span class="unit-icon"><Icon
            name="type"
            :size="16"
          /></span>
          <h3>Strings</h3>
          <p>Keys, placeholders, and plurals — the interface layer, translated key by key.</p>
        </div>
        <div class="unit-card">
          <span class="unit-icon"><Icon
            name="file-text"
            :size="16"
          /></span>
          <h3>Documents</h3>
          <p>Whole content items — articles, guides, pages — translated as a structured whole.</p>
        </div>
      </div>
    </section>

    <DiscoverLocalizationExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.doc-window,
.rt-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Document window ─────────────────────────── */

.doc-body {
  padding: 14px 12px;
}

.doc-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 12px;
}

.doc-row + .doc-row {
  border-top: 1px solid var(--line);
}

.doc-lang {
  width: 24px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
  text-transform: uppercase;
}

.doc-bar {
  flex: 1;
  height: 6px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--fg-3) 16%, transparent);
  overflow: hidden;
}

.doc-bar span {
  display: block;
  height: 100%;
  border-radius: 999px;
}

.doc-bar span.src { width: 100%; background: var(--fg-3); }
.doc-bar span.ok { width: 100%; background: #34d99a; }
.doc-bar span.review { width: 66%; background: #e0a23a; }
.doc-bar span.draft { width: 30%; background: var(--accent); }

.doc-note {
  width: 74px;
  text-align: right;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.doc-note.ok { color: #34d99a; }
.doc-note.review { color: #e0a23a; }
.doc-note.draft { color: var(--accent); }

/* ── Round-trip window ───────────────────────── */

.rt-body {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
}

.rt-node {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.rt-node.accent {
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

.rt-icon {
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

.rt-text {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.rt-mid {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.rt-mid svg { color: var(--accent); }

/* ── Unit cards ──────────────────────────────── */

.unit-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.unit-card {
  padding: 24px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.unit-icon {
  width: 34px;
  height: 34px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 18%, transparent);
  color: var(--accent);
  margin-bottom: 14px;
}

.unit-card h3 {
  font-size: 16px;
  font-weight: 650;
  margin: 0 0 8px;
}

.unit-card p {
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 620px) {
  .unit-grid {
    grid-template-columns: 1fr;
  }
}
</style>
