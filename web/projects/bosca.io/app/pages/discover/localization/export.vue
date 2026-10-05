<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Export — The format each platform expects',
  description: 'Export Bosca translations to eight formats out of the box — Android strings.xml, iOS .strings and .stringsdict, Flutter ARB, three JSON shapes, and XLIFF — with placeholders converted to each platform\'s syntax and filtering by workflow state.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Localization', path: '/discover/localization' },
  { name: 'Export', path: '/discover/localization/export' }
], '/og-localization.png')

const FORMATS = [
  { platform: 'Android', file: 'strings.xml', note: 'with <plurals>' },
  { platform: 'iOS', file: '.strings', note: 'key–value' },
  { platform: 'iOS', file: '.stringsdict', note: 'plural forms' },
  { platform: 'Flutter', file: '.arb', note: 'ICU preserved' },
  { platform: 'Web', file: 'i18n JSON', note: 'Nuxt nested' },
  { platform: 'Web', file: 'flat JSON', note: 'key: value' },
  { platform: 'Web', file: 'nested JSON', note: 'dot keys expanded' },
  { platform: 'Any tool', file: 'XLIFF 1.2', note: 'CAT interop' }
]
</script>

<template>
  <DiscoverShell section-id="localization">
    <section class="page-hero">
      <p class="kicker load-1">
        Export
      </p>
      <h1 class="load-2">
        The format each <em>platform expects</em>
      </h1>
      <p class="section-sub load-3">
        Translations don't have to live in Bosca. Export them in the exact shape
        your apps read — mobile, web, or a translation tool — with placeholders
        rewritten to each platform's syntax and only the strings you've marked
        ready.
      </p>
    </section>

    <!-- ── The formats ─────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Eight formats
        </p>
        <h2>One project, <em>every target</em></h2>
        <p class="section-sub">
          The same strings export to whatever your stack needs — no
          hand-conversion, no glue scripts to maintain.
        </p>
      </div>
      <div class="fmt-grid reveal">
        <div
          v-for="f in FORMATS"
          :key="f.file"
          class="fmt-card"
        >
          <span class="fmt-platform">{{ f.platform }}</span>
          <span class="fmt-file">{{ f.file }}</span>
          <span class="fmt-note">{{ f.note }}</span>
        </div>
      </div>
    </section>

    <!-- ── Placeholders convert ────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Placeholders travel
          </p>
          <h2>The same variable, <em>each platform's way</em></h2>
          <p class="section-sub">
            You write a placeholder once, in the standard ICU form. On the way
            out, the exporter rewrites it to the syntax the target expects — so
            an Android build and an iOS build both get a variable they
            understand, from the one source.
          </p>
          <ul class="point-list">
            <li>Author once in ICU MessageFormat; export converts it per platform.</li>
            <li>Plural forms are emitted in each platform's native shape.</li>
            <li>No per-platform string files to keep in sync by hand.</li>
          </ul>
        </div>
        <div class="conv-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">{count} items</span>
          </div>
          <div class="conv-body">
            <div class="conv-row">
              <span class="conv-plat">Android</span>
              <span class="conv-code">%1$s items</span>
            </div>
            <div class="conv-row">
              <span class="conv-plat">iOS</span>
              <span class="conv-code">%@ items</span>
            </div>
            <div class="conv-row">
              <span class="conv-plat">Flutter</span>
              <span class="conv-code">{count} items</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Export by state ─────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Only what's ready
          </p>
          <h2>Ship the finished, <em>hold the rest</em></h2>
          <p class="section-sub">
            An export isn't all-or-nothing. Choose which workflow states to
            include — published by default — and half-translated or in-review
            strings simply stay behind, so a release never ships a draft by
            accident.
          </p>
          <ul class="point-list">
            <li>Filter an export to the states you want; published is the default.</li>
            <li>In-progress translations are left out until they're ready.</li>
            <li>One request returns the file, its type, and a sensible name.</li>
          </ul>
        </div>
        <div class="exp-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">export</span>
          </div>
          <div class="exp-body">
            <div class="exp-row">
              <span class="exp-key">format</span>
              <span class="exp-val">android-xml</span>
            </div>
            <div class="exp-row">
              <span class="exp-key">include</span>
              <span class="exp-val accent">published</span>
            </div>
            <div class="exp-out">
              <Icon
                name="download"
                :size="13"
              />
              strings.xml · 214 strings
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverLocalizationExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Format cards ────────────────────────────── */

.fmt-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.fmt-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 20px 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.fmt-card:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  transform: translateY(-2px);
}

.fmt-platform {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--fg-3);
}

.fmt-file {
  font-family: var(--font-mono);
  font-size: 14px;
  font-weight: 650;
  color: var(--accent);
}

.fmt-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Shared windows ──────────────────────────── */

.conv-window,
.exp-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Conversion window ───────────────────────── */

.conv-body {
  padding: 12px 10px;
}

.conv-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 14px;
}

.conv-row + .conv-row {
  border-top: 1px solid var(--line);
}

.conv-plat {
  width: 70px;
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
}

.conv-code {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-0);
}

/* ── Export window ───────────────────────────── */

.exp-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.exp-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.exp-key { color: var(--fg-3); }
.exp-val { color: var(--fg-1); }
.exp-val.accent { color: var(--accent); }

.exp-out {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  padding: 12px 14px;
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 11.5px;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 860px) {
  .fmt-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 460px) {
  .fmt-grid {
    grid-template-columns: 1fr;
  }
}
</style>
