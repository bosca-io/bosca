<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Localization — Ship your product in every language',
  description: 'Bosca Localization brings your UI strings and your CMS content into every language you support — with translator context, CLDR-correct plurals, a review workflow that records every change and its origin, and export to Android, iOS, Flutter, web, and XLIFF.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Localization', path: '/discover/localization' }
], '/og-localization.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Collect',
    body: 'Group your translatable strings and content documents into a project, with one source language and the target languages you support.'
  },
  {
    step: '02',
    title: 'Translate & review',
    body: 'Translators work with context and correct plural forms, or Kit generates configured target-language strings in a batch. Every result moves through review with its origin on the record.'
  },
  {
    step: '03',
    title: 'Ship',
    body: 'Export to the format each platform expects — Android, iOS, Flutter, web, or XLIFF — filtered to just the translations that are ready.'
  }
]

const FEATURES = [
  {
    icon: 'type',
    title: 'Strings with context',
    body: 'Translatable keys carry translator notes, character limits, tags, and a screenshot for visual context.'
  },
  {
    icon: 'braces',
    title: 'ICU placeholders',
    body: 'Placeholders declare their type — number, date, time, currency — with examples, so a translation never breaks on a variable.'
  },
  {
    icon: 'list-checks',
    title: 'Plurals done right',
    body: 'Every CLDR plural form — zero, one, few, many, other — is its own editable value, per language.'
  },
  {
    icon: 'file-text',
    title: 'Documents, not just strings',
    body: 'Localize whole content documents from the CMS, stored per language and round-tripped through HTML for outside tools.'
  },
  {
    icon: 'workflow',
    title: 'A real review workflow',
    body: 'Translations move through draft, review, approved, and published — with invalid jumps blocked outright.'
  },
  {
    icon: 'history',
    title: 'Every change on the record',
    body: 'An append-only history captures who changed what, from which state to which, and the text before and after.'
  },
  {
    icon: 'shield-check',
    title: 'Generate, then review',
    body: 'Ask Kit to fill missing target-language strings in a batch. Each result is marked AI-generated and held for human review.'
  },
  {
    icon: 'download',
    title: 'Export to every platform',
    body: 'Eight formats out of the box: Android, iOS strings and stringsdict, Flutter ARB, three JSON shapes, and XLIFF.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="localization"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Localization
        </p>
        <h1 class="load-2">
          Ship in every<br>
          <em>language.</em>
        </h1>
        <p class="hero-sub load-3">
          Bring your UI strings and your content into every language you
          support — with translator context, plural forms done right, a review
          workflow that keeps track of every change, and export to the format
          each platform expects.
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
        <div class="str-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">key · checkout.pay</span>
          </div>
          <div class="str-body">
            <div class="str-row">
              <span class="str-lang">en</span>
              <span class="str-val">Pay now</span>
              <span class="str-state ok">published</span>
            </div>
            <div class="str-row">
              <span class="str-lang">es</span>
              <span class="str-val">Pagar ahora</span>
              <span class="str-state ok">published</span>
            </div>
            <div class="str-row">
              <span class="str-lang">fr</span>
              <span class="str-val">Payer</span>
              <span class="str-state review">in review</span>
            </div>
            <div class="str-row">
              <span class="str-lang">de</span>
              <span class="str-val">Jetzt bezahlen</span>
              <span class="str-state draft">ai · draft</span>
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
        <h2>Collect, review, <em>ship.</em></h2>
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
          <h2>Translation done <em>like engineering</em></h2>
          <p class="section-sub">
            Context for translators, correct plurals, a review workflow with a
            full history, and the export formats every platform actually reads.
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

    <!-- ── Reviewed before it ships ────────────── -->
    <section class="section reviewed">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Reviewed before it ships
          </p>
          <h2>Nothing goes live <em>unchecked</em></h2>
          <p class="section-sub">
            Every translation has a state and knows where it came from. A change
            to something already approved sends it back for review, AI-marked
            work can't skip the human step, and an export takes only the
            translations you've marked ready — so half-finished strings never
            reach production.
          </p>
          <ul class="point-list">
            <li>Each translation moves through a defined workflow; invalid jumps are blocked.</li>
            <li>Origin is recorded — human, imported, or AI — and AI-marked work is held for review.</li>
            <li>Editing an approved translation resets it to draft so it's reviewed again.</li>
            <li>Export by state, so only the ready translations ship.</li>
          </ul>
        </div>
        <div class="flow-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">checkout.pay · de</span>
          </div>
          <div class="flow-body">
            <div class="flow-stage done">
              <span class="flow-dot" />
              <span class="flow-name">ai-generated</span>
              <span class="flow-note">origin: ai</span>
            </div>
            <div class="flow-stage done">
              <span class="flow-dot" />
              <span class="flow-name">in review</span>
              <span class="flow-note">human check</span>
            </div>
            <div class="flow-stage current">
              <span class="flow-dot" />
              <span class="flow-name">approved</span>
              <span class="flow-note">by a reviewer</span>
            </div>
            <div class="flow-stage pending">
              <span class="flow-dot" />
              <span class="flow-name">published</span>
              <span class="flow-note">exportable</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverLocalizationExplore title="Go deeper" />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#7ceb70"
        class="closing-mark"
      />
      <h2>Every language, <em>the right way</em></h2>
      <p class="section-sub">
        Strings and documents, plural-correct and context-rich, reviewed on a
        real workflow and exported to the format your apps expect — localization
        built into the platform, not bolted on beside it.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.95fr);
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

/* ── String window ───────────────────────────── */

.str-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.str-body {
  padding: 12px 10px;
}

.str-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.str-row + .str-row {
  border-top: 1px solid var(--line);
}

.str-lang {
  width: 24px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
  text-transform: uppercase;
}

.str-val {
  flex: 1;
  font-size: 14px;
  color: var(--fg-0);
}

.str-state {
  flex-shrink: 0;
  white-space: nowrap;
  align-self: center;
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 1.5;
  border-radius: 999px;
  padding: 3px 9px;
  border: 1px solid var(--line-2);
  color: var(--fg-3);
}

.str-state.ok {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.str-state.review {
  color: #e0a23a;
  border-color: color-mix(in srgb, #e0a23a 40%, transparent);
}

.str-state.draft {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
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

/* ── Review ──────────────────────────────────── */

.reviewed {
  padding-top: 90px;
}

.flow-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.flow-body {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.flow-stage {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
  border-radius: var(--r-sm);
}

.flow-dot {
  width: 11px;
  height: 11px;
  border-radius: 999px;
  flex-shrink: 0;
  border: 2px solid var(--line-2);
}

.flow-stage.done .flow-dot {
  background: var(--accent);
  border-color: var(--accent);
}

.flow-stage.current {
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

.flow-stage.current .flow-dot {
  border-color: var(--accent);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--accent) 22%, transparent);
}

.flow-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-1);
}

.flow-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
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
