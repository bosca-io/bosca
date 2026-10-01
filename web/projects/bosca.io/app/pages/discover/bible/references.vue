<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'References — Say it how people say it',
  description: 'Bosca parses a plain-English scripture reference like "John 3:16" or "Genesis 1:1–5" into canonical USFM, resolves ranges into their individual verses, and pulls exactly that scripture — recognizing long names, short names, and abbreviations.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Bible', path: '/discover/bible' },
  { name: 'References', path: '/discover/bible/references' }
], '/og-bible.png')

const PARSED = [
  { human: 'John 3:16', usfm: 'JHN.3.16' },
  { human: 'Genesis 1:1', usfm: 'GEN.1.1' },
  { human: 'Ps 23', usfm: 'PSA.23' }
]
</script>

<template>
  <DiscoverShell section-id="bible">
    <section class="page-hero">
      <p class="kicker load-1">
        References
      </p>
      <h1 class="load-2">
        Say it how <em>people say it</em>
      </h1>
      <p class="section-sub load-3">
        Nobody types <code>JHN.3.16</code>. They write "John 3:16," or "Ps 23,"
        or "Genesis 1:1–5." Bosca parses the human form into a canonical
        reference and pulls exactly the scripture it names.
      </p>
    </section>

    <!-- ── Parse ───────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Human in, canonical out
          </p>
          <h2>From &quot;John 3:16&quot; <em>to a reference</em></h2>
          <p class="section-sub">
            The parser knows a book by its full name, its short name, and its
            abbreviation, and turns any of them into the canonical USFM address.
            What a person naturally writes becomes something your code can
            resolve without guesswork.
          </p>
          <ul class="point-list">
            <li>Long names, short names, and abbreviations all resolve.</li>
            <li>A whole chapter or a single verse — both are valid references.</li>
            <li>Available over GraphQL and a plain REST endpoint.</li>
          </ul>
        </div>
        <div class="parse-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">find</span>
          </div>
          <div class="parse-body">
            <div
              v-for="p in PARSED"
              :key="p.usfm"
              class="parse-row"
            >
              <span class="parse-human">&quot;{{ p.human }}&quot;</span>
              <Icon
                name="arrow-right"
                :size="13"
              />
              <span class="parse-usfm">{{ p.usfm }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Ranges ──────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Ranges &amp; spans
          </p>
          <h2>One reference, <em>many verses</em></h2>
          <p class="section-sub">
            A reference isn't only a single point. Ask for "Genesis 1:1–5" and it
            expands into each verse in the span, so a passage comes back whole —
            no looping through verse numbers on your side.
          </p>
          <ul class="point-list">
            <li>Verse ranges expand into their individual verses automatically.</li>
            <li>Several verses combine into one reference when you need a set.</li>
            <li>The expanded reference maps straight to the content behind it.</li>
          </ul>
        </div>
        <div class="range-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">Genesis 1:1–5</span>
          </div>
          <div class="range-body">
            <div class="range-in">
              <Icon
                name="quote"
                :size="13"
              />
              GEN.1.1<span class="range-op">–</span>5
            </div>
            <div class="range-arrow">
              <Icon
                name="chevron-down"
                :size="14"
              />
            </div>
            <div class="range-out">
              <span
                v-for="n in 5"
                :key="n"
                class="range-chip"
              >GEN.1.{{ n }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Resolve to content ──────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Straight to scripture
          </p>
          <h2>A reference <em>returns the text</em></h2>
          <p class="section-sub">
            Resolving a reference doesn't stop at an address — it reaches the
            actual chapter and verses behind it, in the translation you asked
            for. The reference is the handle; the scripture is what you get
            back.
          </p>
          <ul class="point-list">
            <li>A resolved reference returns the real chapter and verse content.</li>
            <li>It respects the translation and language you requested.</li>
            <li>The same reference works across any translation of that system.</li>
          </ul>
        </div>
        <div class="resolve-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">JHN.3.16 · WEB</span>
          </div>
          <div class="resolve-body">
            <p class="resolve-text">
              For God so loved the world, that he gave his one and only Son…
            </p>
            <span class="resolve-src">
              <Icon
                name="check-circle"
                :size="12"
              />
              resolved to real content
            </span>
          </div>
        </div>
      </div>
    </section>

    <DiscoverBibleExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.parse-window,
.range-window,
.resolve-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Parse window ────────────────────────────── */

.parse-body {
  padding: 12px 10px;
}

.parse-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 15px 14px;
}

.parse-row + .parse-row {
  border-top: 1px solid var(--line);
}

.parse-row svg {
  color: var(--accent);
  flex-shrink: 0;
}

.parse-human {
  flex: 1;
  font-size: 14px;
  color: var(--fg-1);
}

.parse-usfm {
  font-family: var(--font-mono);
  font-size: 12.5px;
  font-weight: 650;
  color: var(--accent);
}

/* ── Range window ────────────────────────────── */

.range-body {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}

.range-in {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 12px 18px;
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-0);
}

.range-in svg { color: var(--accent); }

.range-op { color: var(--accent); }

.range-arrow {
  color: var(--accent);
}

.range-out {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.range-chip {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-2);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 4px 11px;
}

/* ── Resolve window ──────────────────────────── */

.resolve-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.resolve-text {
  font-family: var(--font-display);
  font-style: italic;
  font-size: 18px;
  line-height: 1.5;
  color: var(--fg-0);
  margin: 0;
}

.resolve-src {
  display: flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
}
</style>
