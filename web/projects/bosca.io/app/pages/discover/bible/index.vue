<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Bible — Scripture as structured, addressable content',
  description: 'Bosca treats the Bible as first-class content: every book, chapter, and verse addressable by reference, many translations served language-aware, imported from standard Paratext and USX bundles, and quoted back exactly — even by AI agents, which read the real translation instead of inventing verses.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Bible', path: '/discover/bible' }
], '/og-bible.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Import',
    body: 'Upload a translation as a standard USX bundle. The compiler reads the Paratext manifest and files and turns them into books, chapters, and verses you can address.'
  },
  {
    step: '02',
    title: 'Address',
    body: 'Every verse has a canonical reference, so "John 3:16" resolves to exact content — a single verse, a range, or a whole chapter, with footnotes and styling intact.'
  },
  {
    step: '03',
    title: 'Serve',
    body: 'Read scripture over GraphQL or REST, language-aware and permission-controlled — and hand the very same text to AI agents, which quote it rather than invent it.'
  }
]

const FEATURES = [
  {
    icon: 'book-open',
    title: 'Books, chapters, verses',
    body: 'A Bible is structured content — the canonical books plus the deuterocanon, each chapter a tree of verses, footnotes, and styling.'
  },
  {
    icon: 'languages',
    title: 'Many translations',
    body: 'Hold as many translations as you like, each with its own names, abbreviations, and language.'
  },
  {
    icon: 'globe',
    title: 'Language-aware',
    body: 'Ask for a Bible and get the right translation for the reader\'s language — scripts and right-to-left included.'
  },
  {
    icon: 'quote',
    title: 'Real references',
    body: 'Parse "John 3:16" or "Genesis 1:1–5" into canonical USFM, resolve ranges, and pull exactly those verses.'
  },
  {
    icon: 'upload',
    title: 'Import from Paratext',
    body: 'Bring a translation as a standard USX bundle; the compiler turns the Paratext manifest and files into addressable scripture.'
  },
  {
    icon: 'sparkles',
    title: 'Quoted, never invented',
    body: 'AI agents read scripture through tools that return the real text of the installed translation — the model never makes a verse up.'
  },
  {
    icon: 'shield-check',
    title: 'Governed like any content',
    body: 'Scripture served by Bosca, so the same permissions, versions, and workflow states apply to it.'
  },
  {
    icon: 'code',
    title: 'One API, two shapes',
    body: 'Reach any book, chapter, or reference over GraphQL or a plain REST endpoint.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="bible"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Bible
        </p>
        <h1 class="load-2">
          Scripture,<br>
          <em>structured and exact.</em>
        </h1>
        <p class="hero-sub load-3">
          Every book, chapter, and verse is addressable content — many
          translations, language-aware, imported from the standard Paratext and
          USX bundles you already use, and served exactly as written, to your
          app or an AI agent alike.
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
        <div class="verse-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">bible · WEB</span>
          </div>
          <div class="verse-body">
            <div class="verse-ref">
              <Icon
                name="quote"
                :size="13"
              />
              John 3:16
              <span class="verse-usfm">JHN.3.16</span>
            </div>
            <p class="verse-text">
              For God so loved the world, that he gave his one and only Son,
              that whoever believes in him should not perish, but have eternal
              life.
            </p>
            <div class="verse-foot">
              <Icon
                name="shield-check"
                :size="12"
              />
              from the translation, exactly as written
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
        <h2>Import, address, <em>serve.</em></h2>
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
          <h2>Scripture as <em>real, structured content</em></h2>
          <p class="section-sub">
            Not a wall of text — books, chapters, and verses you can address,
            translate, import, govern, and serve like everything else on the
            platform.
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

    <!-- ── Exact, by design ────────────────────── -->
    <section class="section exact">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Exact, by design
          </p>
          <h2>The text is <em>the text</em></h2>
          <p class="section-sub">
            Scripture demands to be exact, so nothing paraphrases it. What you
            import is what you serve — and when an AI agent needs a verse, it
            calls a tool that returns the real text of the installed
            translation. The model plans and then uses the exact text; it never
            sources the words itself.
          </p>
          <ul class="point-list">
            <li>Agents list translations, look up books, resolve references, and fetch verses — all against the real Bible.</li>
            <li>The language model is never the source of a verse; the installed translation is.</li>
            <li>What you imported is served back unchanged, footnotes and styling included.</li>
          </ul>
        </div>
        <div class="tool-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">agent · tool call</span>
          </div>
          <div class="tool-body">
            <div class="tool-call">
              <span class="tool-fn">bible_get_verses</span>
              <span class="tool-arg">"John 3:16"</span>
            </div>
            <div class="tool-arrow">
              <Icon
                name="chevron-down"
                :size="14"
              />
            </div>
            <div class="tool-result">
              <p>For God so loved the world…</p>
              <span class="tool-src">
                <Icon
                  name="shield-check"
                  :size="11"
                />
                read from WEB · not generated
              </span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverBibleExplore title="Go deeper" />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#e79b4a"
        class="closing-mark"
      />
      <h2>Scripture, <em>as a first-class citizen</em></h2>
      <p class="section-sub">
        Structured, multilingual, importable, and governed like all your other
        content — reachable by your apps, your references, and your agents, and
        always served exactly as written.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.92fr);
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
  max-width: 480px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

/* ── Verse window ────────────────────────────── */

.verse-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.verse-body {
  padding: 22px 22px 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.verse-ref {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13.5px;
  font-weight: 650;
  color: var(--fg-1);
}

.verse-ref svg {
  color: var(--accent);
}

.verse-usfm {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 9px;
}

.verse-text {
  font-family: var(--font-display);
  font-style: italic;
  font-size: 22px;
  line-height: 1.55;
  color: var(--fg-0);
  margin: 0;
}

.verse-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
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

/* ── Exact ───────────────────────────────────── */

.exact {
  padding-top: 90px;
}

.tool-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.tool-body {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}

.tool-call {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 15px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.tool-fn {
  color: var(--accent);
}

.tool-arg {
  color: var(--fg-1);
}

.tool-arrow {
  color: var(--accent);
}

.tool-result {
  width: 100%;
  padding: 15px 16px;
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

.tool-result p {
  font-family: var(--font-display);
  font-style: italic;
  font-size: 16px;
  color: var(--fg-0);
  margin: 0 0 10px;
}

.tool-src {
  display: flex;
  align-items: center;
  gap: 6px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
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
