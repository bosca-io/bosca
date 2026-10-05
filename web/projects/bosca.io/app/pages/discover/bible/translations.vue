<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Translations — Many versions, language-aware',
  description: 'Hold as many Bible translations as you like, each with its own names, abbreviations, and language — served to the reader\'s language automatically, scripts and right-to-left included, imported from standard Paratext and USX bundles.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Bible', path: '/discover/bible' },
  { name: 'Translations', path: '/discover/bible/translations' }
], '/og-bible.png')

const TRANSLATIONS = [
  { abbr: 'WEB', name: 'World English Bible', lang: 'en', dir: 'ltr' },
  { abbr: 'KJV', name: 'King James Version', lang: 'en', dir: 'ltr' },
  { abbr: 'RVA', name: 'Reina-Valera Antigua', lang: 'es', dir: 'ltr' },
  { abbr: 'AVD', name: 'Arabic (Van Dyck)', lang: 'ar', dir: 'rtl' }
]
</script>

<template>
  <DiscoverShell section-id="bible">
    <section class="page-hero">
      <p class="kicker load-1">
        Translations
      </p>
      <h1 class="load-2">
        Every version, <em>every language</em>
      </h1>
      <p class="section-sub load-3">
        A Bible on Bosca isn't a single book — it's as many translations as you
        care to hold, each a version in its own right, in its own language and
        script. Ask for one and the reader gets the right one.
      </p>
    </section>

    <!-- ── Many translations ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Many versions
          </p>
          <h2>Every translation <em>a first-class record</em></h2>
          <p class="section-sub">
            Each translation carries its own name and abbreviation — in English
            and in its own language — and its own description. They sit side by side, ready to be served, compared, or
            searched by reference.
          </p>
          <ul class="point-list">
            <li>Names and abbreviations in both English and the translation's own language.</li>
            <li>Each can carry a human description of its own, too.</li>
            <li>Hold as many as you need — no single canonical version is assumed.</li>
          </ul>
        </div>
        <div class="trans-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">translations</span>
          </div>
          <div class="trans-body">
            <div
              v-for="t in TRANSLATIONS"
              :key="t.abbr"
              class="trans-row"
            >
              <span class="trans-abbr">{{ t.abbr }}</span>
              <span class="trans-name">{{ t.name }}</span>
              <span class="trans-lang">{{ t.lang }} · {{ t.dir }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Language-aware ──────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Language-aware
          </p>
          <h2>The right one, <em>automatically</em></h2>
          <p class="section-sub">
            Request a Bible and Bosca picks the translation that matches the
            reader's language — from the same Accept-Language header a browser
            already sends, or an explicit choice. Right-to-left scripts come
            through correctly, no special handling on your end.
          </p>
          <ul class="point-list">
            <li>Matches the reader's language from the request, or an explicit language choice.</li>
            <li>Left-to-right and right-to-left scripts are both first-class.</li>
            <li>One reference, served in whichever translation fits the reader.</li>
          </ul>
        </div>
        <div class="lang-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">GET · bible</span>
          </div>
          <div class="lang-body">
            <div class="lang-req">
              <span class="lang-key">Accept-Language</span>
              <span class="lang-val">es</span>
            </div>
            <div class="lang-arrow">
              <Icon
                name="chevron-down"
                :size="14"
              />
            </div>
            <div class="lang-res">
              <Icon
                name="check-circle"
                :size="14"
              />
              served <strong>RVA</strong> — Reina-Valera Antigua
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Import ──────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Bring your own
          </p>
          <h2>Import from <em>Paratext and USX</em></h2>
          <p class="section-sub">
            You don't retype scripture. Upload a translation as a standard USX
            bundle — the Paratext manifest and its files — and the compiler
            parses it into books, chapters, and verses, stored as addressable
            content on the platform.
          </p>
          <ul class="point-list">
            <li>Upload a standard USX bundle; the compiler reads the manifest and every book.</li>
            <li>Out come structured books and chapters, not a flat document.</li>
            <li>The imported translation becomes governed content like everything else.</li>
          </ul>
        </div>
        <div class="import-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">import</span>
          </div>
          <div class="import-body">
            <div class="import-node">
              <span class="import-icon"><Icon
                name="upload"
                :size="15"
              /></span>
              <span class="import-text"><code>web.usx.zip</code> · Paratext bundle</span>
            </div>
            <div class="import-arrow">
              <Icon
                name="chevron-down"
                :size="14"
              />
            </div>
            <div class="import-node accent">
              <span class="import-icon"><Icon
                name="book-open"
                :size="15"
              /></span>
              <span class="import-text">66 books · addressable chapters</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverBibleExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.trans-window,
.lang-window,
.import-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Translations table ──────────────────────── */

.trans-body {
  padding: 12px 10px;
}

.trans-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.trans-row + .trans-row {
  border-top: 1px solid var(--line);
}

.trans-abbr {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 650;
  color: var(--accent);
  width: 42px;
}

.trans-name {
  flex: 1;
  font-size: 13.5px;
  color: var(--fg-1);
}

.trans-lang {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Language window ─────────────────────────── */

.lang-body {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}

.lang-req {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.lang-key { color: var(--fg-3); }
.lang-val { color: var(--fg-0); }

.lang-arrow {
  color: var(--accent);
}

.lang-res {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 14px 16px;
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  font-size: 13.5px;
  color: var(--fg-1);
}

.lang-res svg { color: var(--accent); }

/* ── Import window ───────────────────────────── */

.import-body {
  padding: 24px 22px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}

.import-node {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.import-node.accent {
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

.import-icon {
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

.import-text {
  font-size: 13px;
  color: var(--fg-1);
}

.import-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

.import-arrow {
  color: var(--accent);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 480px) {
  .trans-lang {
    display: none;
  }
}
</style>
