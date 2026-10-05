<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Books & Chapters — Scripture, addressable to the verse',
  description: 'A Bosca Bible is a tree of books, chapters, and verses — the canonical books plus the deuterocanon — each chapter a rich structure of verses, footnotes, and styling, every part addressable by canonical USFM reference.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Bible', path: '/discover/bible' },
  { name: 'Books & Chapters', path: '/discover/bible/books' }
], '/og-bible.png')

const CANON = [
  { label: 'Old Testament', count: '39 books' },
  { label: 'New Testament', count: '27 books' },
  { label: 'Deuterocanon', count: 'supported' }
]

const ADDRESSES = [
  { usfm: 'GEN.1.1', human: 'Genesis 1:1' },
  { usfm: 'JHN.3.16', human: 'John 3:16' },
  { usfm: 'PSA.23', human: 'Psalm 23 · whole chapter' }
]
</script>

<template>
  <DiscoverShell section-id="bible">
    <section class="page-hero">
      <p class="kicker load-1">
        Books &amp; Chapters
      </p>
      <h1 class="load-2">
        Addressable <em>to the verse</em>
      </h1>
      <p class="section-sub load-3">
        A Bible is a tree, not a document. Books hold chapters, chapters hold
        verses, and every piece — down to a footnote — sits at a known address,
        so you can reach exactly the part you mean.
      </p>
    </section>

    <!-- ── The structure ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The structure
          </p>
          <h2>A tree, <em>from book to footnote</em></h2>
          <p class="section-sub">
            Each translation is a set of books; each book a set of chapters; each
            chapter a structured tree of verses carrying their text, footnotes,
            and styling. Nothing is flattened into a blob — the shape of
            scripture is preserved.
          </p>
          <ul class="point-list">
            <li>Books carry their code, short name, long name, and abbreviation.</li>
            <li>A chapter is a component tree — verses, footnotes, and formatting, intact.</li>
            <li>Ask for a book, a chapter, or a single verse — you get just that.</li>
          </ul>
        </div>
        <div class="tree-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">structure</span>
          </div>
          <div class="tree-body">
            <div class="tree-row l0">
              <Icon
                name="book"
                :size="14"
              />
              Bible · WEB
            </div>
            <div class="tree-row l1">
              <Icon
                name="book-open"
                :size="14"
              />
              book · John
            </div>
            <div class="tree-row l2">
              <Icon
                name="file-text"
                :size="14"
              />
              chapter · 3
            </div>
            <div class="tree-row l3 accent">
              <Icon
                name="quote"
                :size="14"
              />
              verse 16 · text · footnotes · styling
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── The canon ───────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          The canon
        </p>
        <h2>The whole library, <em>and then some</em></h2>
        <p class="section-sub">
          The full canonical set is here — and translations that carry the
          deuterocanonical books are handled too, with the standard book codes
          the Paratext world uses.
        </p>
      </div>
      <div class="canon-grid reveal">
        <div
          v-for="c in CANON"
          :key="c.label"
          class="canon-card"
        >
          <span class="canon-count">{{ c.count }}</span>
          <span class="canon-label">{{ c.label }}</span>
        </div>
      </div>
    </section>

    <!-- ── USFM addressing ─────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Canonical addressing
          </p>
          <h2>One address <em>per place</em></h2>
          <p class="section-sub">
            Every book, chapter, and verse has a canonical USFM address — the
            same standard identifiers used across the scripture ecosystem. It's
            what makes "the third chapter of John" a thing your code can point
            at without ambiguity.
          </p>
          <ul class="point-list">
            <li>Books use standard USFM codes; chapters and verses extend them.</li>
            <li>A whole chapter has an address, and so does a single verse.</li>
            <li>Human references resolve into these addresses and back.</li>
          </ul>
        </div>
        <div class="addr-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">addressing</span>
          </div>
          <div class="addr-body">
            <div
              v-for="a in ADDRESSES"
              :key="a.usfm"
              class="addr-row"
            >
              <span class="addr-usfm">{{ a.usfm }}</span>
              <span class="addr-human">{{ a.human }}</span>
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

.tree-window,
.addr-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Tree window ─────────────────────────────── */

.tree-body {
  padding: 16px 14px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tree-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border-radius: var(--r-sm);
  font-size: 13px;
  color: var(--fg-1);
}

.tree-row svg {
  color: var(--fg-3);
  flex-shrink: 0;
}

.tree-row.l1 { margin-left: 20px; }
.tree-row.l2 { margin-left: 40px; }
.tree-row.l3 { margin-left: 60px; }

.tree-row.accent {
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  color: var(--fg-0);
}

.tree-row.accent svg {
  color: var(--accent);
}

/* ── Canon cards ─────────────────────────────── */

.canon-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.canon-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 26px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.canon-count {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--accent);
}

.canon-label {
  font-size: 13.5px;
  color: var(--fg-2);
}

/* ── Address window ──────────────────────────── */

.addr-body {
  padding: 12px 10px;
}

.addr-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 14px;
}

.addr-row + .addr-row {
  border-top: 1px solid var(--line);
}

.addr-usfm {
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 650;
  color: var(--accent);
  width: 96px;
}

.addr-human {
  font-size: 13.5px;
  color: var(--fg-1);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 620px) {
  .canon-grid {
    grid-template-columns: 1fr;
  }
}
</style>
