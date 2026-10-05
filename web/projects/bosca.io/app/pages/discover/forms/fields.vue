<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Form Fields — Fifteen Field Types, Layout & Live Options',
  description: 'Text, numbers, dates, uploads, tags, toggles, and selects — including selects that pull their options from a GraphQL query, an API script, or Work Ops. Arranged in sections and twelve-column rows.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Forms', path: '/discover/forms' },
  { name: 'Fields', path: '/discover/forms/fields' }
], '/og-forms.png')

const FIELD_TYPES = [
  { name: 'Text Input', note: 'single line' },
  { name: 'Text Area', note: 'long text' },
  { name: 'Number', note: 'min, max, step' },
  { name: 'Select', note: 'fixed options' },
  { name: 'GraphQL Select', note: 'options from a query' },
  { name: 'API Script Select', note: 'options from a script' },
  { name: 'Work Ops Select', note: 'live Work Ops options' },
  { name: 'Radio Group', note: 'one of a few' },
  { name: 'Checkbox', note: 'yes or no' },
  { name: 'Switch', note: 'on or off' },
  { name: 'Date Picker', note: 'a date' },
  { name: 'Tags', note: 'several values' },
  { name: 'Color Picker', note: 'a color' },
  { name: 'Image Upload', note: 'a picture' },
  { name: 'File Upload', note: 'an attachment' }
]
</script>

<template>
  <DiscoverShell section-id="forms">
    <section class="page-hero">
      <p class="kicker load-1">
        Fields
      </p>
      <h1 class="load-2">
        Every field, <em>ready to drag in</em>
      </h1>
      <p class="section-sub load-3">
        Fifteen field types cover the answers people actually give — words,
        numbers, dates, choices, files — and three of the selects fill their
        own options from your platform's data.
      </p>
    </section>

    <!-- ── Field types ─────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Field types
        </p>
        <h2>Fifteen ways <em>to ask</em></h2>
      </div>
      <div class="ftype-grid reveal">
        <div
          v-for="ft in FIELD_TYPES"
          :key="ft.name"
          class="ftype-card"
        >
          <h3>{{ ft.name }}</h3>
          <span class="ftype-note">{{ ft.note }}</span>
        </div>
      </div>
    </section>

    <!-- ── Dynamic selects ─────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Live options
          </p>
          <h2>Selects that <em>feed themselves</em></h2>
          <p class="section-sub">
            Most form tools stop at typed-in option lists. Here, a dropdown
            can draw its options from the platform it lives in — and they're
            current every time the form renders.
          </p>
          <ul class="point-list">
            <li><strong>GraphQL Select</strong> — point it at a query, name the value and label, and the options are whatever the query returns.</li>
            <li><strong>API Script Select</strong> — a script registered on the platform supplies the options.</li>
            <li><strong>Work Ops Select</strong> — options come straight from Work Ops: your projects, task types, priorities, statuses, or resolutions.</li>
          </ul>
        </div>
        <div class="dyn-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">Work Ops Select · projects</span>
          </div>
          <div class="dyn-body">
            <div class="dyn-option">
              Facilities
            </div>
            <div class="dyn-option dyn-active">
              Sunday Services
            </div>
            <div class="dyn-option">
              Youth Ministry
            </div>
            <div class="dyn-note">
              options from Work Ops
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Layout & display ────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Layout &amp; display
          </p>
          <h2>Shaped like a form, <em>not a list</em></h2>
          <p class="section-sub">
            Fields sit in a layout you control — grouped into sections,
            spread across columns, and explained where explanation helps.
          </p>
          <ul class="point-list">
            <li><strong>Sections</strong> — group related fields under a heading.</li>
            <li><strong>Rows</strong> — place fields side by side on a twelve-column grid, each with its own width.</li>
            <li><strong>Display elements</strong> — alerts, dividers, headings, and help text between the fields, no data attached.</li>
          </ul>
        </div>
        <div class="layout-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">layout</span>
          </div>
          <div class="layout-body">
            <div class="lo-section">
              Contact details
            </div>
            <div class="lo-full" />
            <div class="lo-row">
              <div class="lo-half" />
              <div class="lo-half" />
            </div>
            <div class="lo-help">
              We'll only use this to coordinate your first visit.
            </div>
            <div class="lo-full" />
          </div>
        </div>
      </div>
    </section>

    <DiscoverFormsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Field type cards ────────────────────────── */

.ftype-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.ftype-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 16px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.ftype-card:hover {
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  transform: translateY(-2px);
}

.ftype-card h3 {
  font-size: 13.5px;
  font-weight: 650;
  margin: 0;
}

.ftype-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Dynamic select window ───────────────────── */

.dyn-window,
.layout-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.dyn-body {
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dyn-option {
  font-size: 13px;
  color: var(--fg-1);
  border-radius: var(--r-xs);
  padding: 10px 12px;
}

.dyn-active {
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--fg-0);
}

.dyn-note {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
  padding: 8px 12px 4px;
  border-top: 1px solid var(--line);
  margin-top: 6px;
}

/* ── Layout window ───────────────────────────── */

.layout-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.lo-section {
  font-size: 12.5px;
  font-weight: 650;
  color: var(--fg-1);
}

.lo-full,
.lo-half {
  height: 34px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-xs);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.lo-row {
  display: flex;
  gap: 10px;
}

.lo-half {
  flex: 1;
  min-width: 0;
}

.lo-help {
  font-size: 12px;
  color: var(--fg-3);
  font-style: italic;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 960px) {
  .ftype-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .ftype-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 400px) {
  .ftype-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
