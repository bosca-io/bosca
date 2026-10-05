<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'BML Email & Localization — One language, every locale',
  description: 'Build localized sites and transactional email with BML. Use published Bosca strings and CLDR plurals on server and client, then compile versioned email templates into email-safe HTML and plain text.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'BML', path: '/discover/bml' },
  { name: 'Email & localization', path: '/discover/bml/email-and-localization' }
], '/og-bml.png')

const LOCALE_STEPS = [
  { source: 'URL', value: '?lang=fr-CA', state: 'first' },
  { source: 'Cookie', value: 'bml_locale', state: 'next' },
  { source: 'Browser', value: 'Accept-Language', state: 'next' }
]
</script>

<template>
  <DiscoverShell section-id="bml">
    <section class="page-hero">
      <p class="kicker load-1">
        Email &amp; localization
      </p>
      <h1 class="load-2">
        One language. <em>Every locale.</em>
      </h1>
      <p class="section-sub load-3">
        BML uses the same published language on server-rendered pages, inside
        client islands, and in transactional email. Visitors get the right
        locale; recipients get reliable HTML and plain text; your team manages
        the words and template versions in Bosca.
      </p>
    </section>

    <!-- ── Localization ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Locale-aware by default
          </p>
          <h2>Resolve once. <em>Render everywhere.</em></h2>
          <p class="section-sub">
            Bind a BML application to a Bosca localization project and its
            source, default, and target languages become the application's
            catalog. BML negotiates the request locale, loads published strings,
            and uses the same messages during server rendering and client work.
          </p>
          <ul class="point-list">
            <li>A URL override, remembered locale, and the browser's language preferences are considered in order.</li>
            <li>Language matching follows the browser standard, so a regional locale can fall back to its supported language.</li>
            <li>Plural messages use CLDR rules through the same translation helper on the server and client.</li>
            <li>The last good catalog stays available while a fresh one loads.</li>
          </ul>
        </div>
        <div class="locale-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">locale negotiation</span>
          </div>
          <div class="locale-body">
            <div
              v-for="step in LOCALE_STEPS"
              :key="step.source"
              class="locale-row"
            >
              <span class="locale-source">{{ step.source }}</span>
              <span class="locale-value">{{ step.value }}</span>
              <span
                class="locale-order"
                :class="step.state"
              >{{ step.state }}</span>
            </div>
            <div class="locale-result">
              <Icon
                name="languages"
                :size="13"
              />
              resolved · fr-CA → fr
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Email target ───────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            A first-class email target
          </p>
          <h2>Write the message. <em>BML makes it deliverable.</em></h2>
          <p class="section-sub">
            An email template declares its key and subject alongside familiar
            BML markup. At render time Bosca chooses the recipient's locale and
            produces the two formats delivery systems need: email-safe HTML and
            a clean plain-text alternative.
          </p>
          <ul class="point-list">
            <li>CSS is inlined so the design survives restrictive inbox clients.</li>
            <li>Template images become attached content IDs instead of fragile remote dependencies.</li>
            <li>Email excludes client JavaScript while keeping server-side data, components, and localization.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">receipt.bml</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">&lt;email</span> <span class="tok-attr">key</span>=<span class="tok-str">"order.receipt"</span><span class="tok-tag">&gt;</span>
  <span class="tok-tag">&lt;subject&gt;</span><span class="tok-interp">{ t("receipt.subject") }</span><span class="tok-tag">&lt;/subject&gt;</span>
  <span class="tok-tag">&lt;style&gt;</span>
    .total { font-weight: 700; }
  <span class="tok-tag">&lt;/style&gt;</span>

  <span class="tok-tag">&lt;h1&gt;</span><span class="tok-interp">{ t("receipt.thanks") }</span><span class="tok-tag">&lt;/h1&gt;</span>
  <span class="tok-tag">&lt;p</span> <span class="tok-attr">class</span>=<span class="tok-str">"total"</span><span class="tok-tag">&gt;</span><span class="tok-interp">{ order.total }</span><span class="tok-tag">&lt;/p&gt;</span>
  <span class="tok-tag">&lt;img</span> <span class="tok-attr">src</span>=<span class="tok-str">"./logo.png"</span> <span class="tok-attr">alt</span>=<span class="tok-str">""</span><span class="tok-tag">/&gt;</span>
<span class="tok-tag">&lt;/email&gt;</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Versioning and preview ─────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Preview and release
        </p>
        <h2>Change the template, <em>not the application.</em></h2>
        <p class="section-sub">
          Compiled email templates are versioned Bosca artifacts. Preview a
          template in Studio with a payload, recipient, locale, and version;
          then activate a verified build without redeploying the service that
          sends it.
        </p>
      </div>
      <div class="release-grid reveal">
        <article class="release-card">
          <span class="release-icon"><Icon
            name="eye"
            :size="15"
          /></span>
          <h3>Preview real variants</h3>
          <p>Choose the project, template, version, payload, recipient, and locale before the message reaches an inbox.</p>
        </article>
        <article class="release-card">
          <span class="release-icon"><Icon
            name="package-check"
            :size="15"
          /></span>
          <h3>Versioned and verified</h3>
          <p>Each compiled template carries a digest, so Bosca verifies the artifact before it becomes active.</p>
        </article>
        <article class="release-card">
          <span class="release-icon"><Icon
            name="refresh"
            :size="15"
          /></span>
          <h3>Safe hot swap</h3>
          <p>An activated version swaps in atomically, with the bundled template available as a dependable fallback.</p>
        </article>
      </div>
    </section>

    <DiscoverBmlExplore />
  </DiscoverShell>
</template>

<style scoped>
.locale-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.locale-body { padding: 12px 10px; }

.locale-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.locale-row + .locale-row { border-top: 1px solid var(--line); }

.locale-source {
  width: 58px;
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-1);
}

.locale-value {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-2);
}

.locale-order {
  font-family: var(--font-mono);
  font-size: 9.5px;
  text-transform: uppercase;
  color: var(--fg-3);
}

.locale-order.first { color: var(--accent); }

.locale-result {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 5px;
  padding: 13px 14px 8px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}

.release-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.release-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 50%, transparent);
}

.release-icon {
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 13px;
  border-radius: var(--r-xs);
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 16%, transparent);
}

.release-card h3 {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 650;
}

.release-card p {
  margin: 0;
  color: var(--fg-2);
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 760px) {
  .release-grid { grid-template-columns: 1fr; }
}
</style>
