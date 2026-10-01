<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Strings & Translations — Keys, context, and correct plurals',
  description: 'A Bosca translatable string carries a stable key, translator context, a screenshot, character limits, and typed ICU placeholders — and every CLDR plural form is its own editable value, so translations never break on a variable or a count.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Localization', path: '/discover/localization' },
  { name: 'Strings & Translations', path: '/discover/localization/strings' }
], '/og-localization.png')

const PLURALS = [
  { cat: 'one', val: '1 item' },
  { cat: 'other', val: '{count} items' }
]
</script>

<template>
  <DiscoverShell section-id="localization">
    <section class="page-hero">
      <p class="kicker load-1">
        Strings &amp; Translations
      </p>
      <h1 class="load-2">
        Keys, context, <em>correct plurals</em>
      </h1>
      <p class="section-sub load-3">
        A string is more than a line of text to translate. It carries the
        context a translator needs, the rules a variable follows, and the plural
        forms a language actually uses — so what ships reads right, everywhere.
      </p>
    </section>

    <!-- ── The string ──────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            More than text
          </p>
          <h2>Everything a translator <em>needs to get it right</em></h2>
          <p class="section-sub">
            Each string has a stable key and travels with the context around it —
            a note on where it appears, a character limit for tight UI, tags to
            group it, and even a screenshot, so a translator is never guessing
            what they're translating.
          </p>
          <ul class="point-list">
            <li>A stable key identifies the string; its text is free to change underneath.</li>
            <li>Context notes, tags, and a maximum length guide the translation.</li>
            <li>Attach a screenshot or reference so the translator sees it in place.</li>
          </ul>
        </div>
        <div class="str-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">string · checkout.pay</span>
          </div>
          <div class="str-body">
            <div class="str-line">
              <span class="str-key">key</span>
              <span class="str-mono">checkout.pay</span>
            </div>
            <div class="str-line">
              <span class="str-key">context</span>
              <span class="str-txt">primary button on the checkout screen</span>
            </div>
            <div class="str-meta">
              <span class="str-chip">max 20</span>
              <span class="str-chip">checkout</span>
              <span class="str-chip">cta</span>
            </div>
            <div class="str-shot">
              <Icon
                name="image"
                :size="13"
              />
              screenshot attached
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── ICU placeholders ────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Typed placeholders
          </p>
          <h2>Variables that <em>don't break</em></h2>
          <p class="section-sub">
            When a string has a variable, it's declared — with a type and an
            example. A translator knows that <code>{count}</code> is a number and
            <code>{date}</code> is a date, so the placeholder survives the
            translation intact, in the right place for the language.
          </p>
          <ul class="point-list">
            <li>Placeholders declare a type — number, date, time, or currency.</li>
            <li>An example value shows the translator what to expect.</li>
            <li>Built on ICU MessageFormat, the standard for this.</li>
          </ul>
        </div>
        <div class="icu-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">placeholder</span>
          </div>
          <div class="icu-body">
            <p class="icu-text">
              You have <span class="icu-ph">{count}</span> items in your cart
            </p>
            <div class="icu-decl">
              <span class="icu-name">count</span>
              <span class="icu-type">number</span>
              <span class="icu-ex">e.g. 3</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Plurals ─────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Plurals done right
          </p>
          <h2>One, few, many — <em>per language</em></h2>
          <p class="section-sub">
            "1 item" and "5 items" aren't the same shape, and languages disagree
            on how many shapes there are. Bosca keeps a separate value for each
            CLDR plural category, so a language uses exactly the forms it needs —
            no cramming it all into one string.
          </p>
          <ul class="point-list">
            <li>Every CLDR category — zero, one, two, few, many, other — is editable on its own.</li>
            <li>Each language uses just the forms its rules call for.</li>
            <li>Exporters emit the right plural shape for each target platform.</li>
          </ul>
        </div>
        <div class="plural-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">cart.items · en</span>
          </div>
          <div class="plural-body">
            <div
              v-for="p in PLURALS"
              :key="p.cat"
              class="plural-row"
            >
              <span class="plural-cat">{{ p.cat }}</span>
              <span class="plural-val">{{ p.val }}</span>
            </div>
            <p class="plural-note">
              English uses one + other; other languages use more
            </p>
          </div>
        </div>
      </div>
    </section>

    <DiscoverLocalizationExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.str-window,
.icu-window,
.plural-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── String window ───────────────────────────── */

.str-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.str-line {
  display: flex;
  gap: 14px;
  align-items: baseline;
}

.str-key {
  width: 62px;
  flex-shrink: 0;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  text-transform: uppercase;
}

.str-mono {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--accent);
}

.str-txt {
  font-size: 13.5px;
  color: var(--fg-1);
}

.str-meta {
  display: flex;
  gap: 8px;
  padding-left: 76px;
}

.str-chip {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-2);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 3px 10px;
}

.str-shot {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-left: 76px;
  padding: 10px 12px;
  border: 1px dashed var(--line-2);
  border-radius: var(--r-sm);
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.str-shot svg { color: var(--accent); }

/* ── ICU window ──────────────────────────────── */

.icu-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.icu-text {
  font-size: 15px;
  color: var(--fg-0);
  margin: 0;
}

.icu-ph {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  border-radius: 4px;
  padding: 1px 6px;
}

.icu-decl {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.icu-name {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.icu-type {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 999px;
  padding: 2px 9px;
}

.icu-ex {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Plural window ───────────────────────────── */

.plural-body {
  padding: 14px 12px 16px;
}

.plural-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 13px 14px;
}

.plural-row + .plural-row {
  border-top: 1px solid var(--line);
}

.plural-cat {
  width: 56px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--accent);
}

.plural-val {
  font-size: 14px;
  color: var(--fg-0);
}

.plural-note {
  margin: 10px 0 0;
  padding: 0 14px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 480px) {
  .str-meta,
  .str-shot {
    padding-left: 0;
    margin-left: 0;
  }
}
</style>
