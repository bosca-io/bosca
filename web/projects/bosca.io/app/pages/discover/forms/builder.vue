<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Form Builder — Drag, Drop, Preview, Publish',
  description: 'A three-pane visual builder: a palette of fields, a canvas you arrange them on, and an inspector for rules and configuration — with a live preview that renders the real form.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Forms', path: '/discover/forms' },
  { name: 'Builder', path: '/discover/forms/builder' }
], '/og-forms.png')
</script>

<template>
  <DiscoverShell section-id="forms">
    <section class="page-hero">
      <p class="kicker load-1">
        The builder
      </p>
      <h1 class="load-2">
        Drag, drop, <em>preview, publish</em>
      </h1>
      <p class="section-sub load-3">
        Forms are built visually in Studio: pick fields from a palette,
        arrange them on a canvas, tune each one in an inspector — and watch
        the real form render as you go.
      </p>
    </section>

    <!-- ── Three panes ─────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The canvas
          </p>
          <h2>Three panes, <em>one form</em></h2>
          <p class="section-sub">
            The palette holds everything you can add — fields, layout, and
            display elements. The canvas is the form as it will render. The
            inspector edits whatever you've selected.
          </p>
          <ul class="point-list">
            <li><strong>Click or drag to add</strong> — palette items drop onto the canvas, into sections, or into rows.</li>
            <li><strong>Arrange by dragging</strong> — reorder fields, nest them into sections, and split rows into columns.</li>
            <li><strong>Edit in place</strong> — select a field and the inspector opens its label, placeholder, rules, and configuration.</li>
          </ul>
        </div>
        <div class="builder-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">builder · Volunteer signup</span>
          </div>
          <div class="builder-body">
            <div class="b-palette">
              <span class="bp-item">Text Input</span>
              <span class="bp-item">Select</span>
              <span class="bp-item">Date Picker</span>
              <span class="bp-item">File Upload</span>
              <span class="bp-item bp-more">+ 11 more</span>
            </div>
            <div class="b-canvas">
              <div class="bc-node">
                Name
              </div>
              <div class="bc-node bc-selected">
                Email
              </div>
              <div class="bc-row">
                <div class="bc-node">
                  T-shirt size
                </div>
                <div class="bc-node">
                  First available
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Preview ─────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Preview
          </p>
          <h2>Preview the <em>real thing</em></h2>
          <p class="section-sub">
            The preview tab renders your form with the same component your
            visitors get — not a sketch of it. Beneath it, the collected data
            appears as you type.
          </p>
          <ul class="point-list">
            <li><strong>Fill it in yourself</strong> — try the form before anyone else does, rules and all.</li>
            <li><strong>See the data live</strong> — the panel under the preview shows exactly what a submission will contain.</li>
          </ul>
        </div>
        <div class="preview-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">preview</span>
          </div>
          <div class="preview-body">
            <div class="pv-field">
              <span class="pv-label">Name</span>
              <span class="pv-input">Jane Miller</span>
            </div>
            <div class="pv-data">
              <span class="pv-data-title">Form data</span>
              <span class="pv-data-row">name · Jane Miller</span>
              <span class="pv-data-row">email · jane@example.com</span>
              <span class="pv-data-row">size · M</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Identity & lifecycle ────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            From draft to live
          </p>
          <h2>Save, version, <em>publish</em></h2>
          <p class="section-sub">
            A form has a stable key, a display name, and a type — and it goes
            live on your terms.
          </p>
          <ul class="point-list">
            <li><strong>Three kinds of form</strong> — Submission forms face your audience, Internal forms serve your own people, and Work Ops forms open tasks.</li>
            <li><strong>Every save is a version</strong> — the key stays stable while the version counts up.</li>
            <li><strong>Publish when ready</strong> — a draft stays yours; Publish makes it live, and Unpublish takes it back.</li>
            <li><strong>Public is its own switch</strong> — you decide separately whether anonymous visitors can submit.</li>
          </ul>
        </div>
        <div class="meta-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">form settings</span>
          </div>
          <div class="cfg-body">
            <div class="cfg-row">
              <span class="cfg-key">Type</span>
              <span class="cfg-val cfg-pill">Submission</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Key</span>
              <span class="cfg-val cfg-pill">form.volunteer.signup</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Version</span>
              <span class="cfg-val">7</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Published</span>
              <span class="cfg-val cfg-on">live</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Public</span>
              <span class="cfg-val cfg-on">on</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Rules ───────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Field rules
          </p>
          <h2>Rules, set <em>where the field is</em></h2>
          <p class="section-sub">
            Select a field and set what counts as a valid answer — visually,
            in the inspector. The form checks answers against the rules
            before it submits.
          </p>
          <ul class="point-list">
            <li><strong>Required</strong> — mark a field as must-answer.</li>
            <li><strong>Lengths and ranges</strong> — minimum and maximum length for text, bounds for numbers.</li>
            <li><strong>Patterns and formats</strong> — match a pattern, or a known format like email, date, or URL.</li>
            <li><strong>Allowed values</strong> — the options a select or radio group accepts.</li>
          </ul>
        </div>
        <div class="rules-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">inspector · Email</span>
          </div>
          <div class="cfg-body">
            <div class="cfg-row">
              <span class="cfg-key">Label</span>
              <span class="cfg-val">Email</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Required</span>
              <span class="cfg-val cfg-on">yes</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Format</span>
              <span class="cfg-val cfg-pill">email</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Max length</span>
              <span class="cfg-val">120</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverFormsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Window shells ───────────────────────────── */

.builder-window,
.preview-window,
.meta-window,
.rules-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Builder window ──────────────────────────── */

.builder-body {
  display: grid;
  grid-template-columns: minmax(0, 0.42fr) minmax(0, 0.58fr);
  gap: 0;
}

.b-palette {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px;
  border-right: 1px solid var(--line);
}

.bp-item {
  font-size: 12px;
  color: var(--fg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 7px 10px;
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.bp-more {
  color: var(--fg-3);
  border-style: dashed;
  background: none;
}

.b-canvas {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
}

.bc-node {
  font-size: 12.5px;
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: var(--r-xs);
  padding: 10px 12px;
  flex: 1;
  min-width: 0;
}

.bc-selected {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--accent) 45%, transparent);
}

.bc-row {
  display: flex;
  gap: 8px;
}

/* ── Preview window ──────────────────────────── */

.preview-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.pv-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.pv-label {
  font-size: 11.5px;
  color: var(--fg-3);
}

.pv-input {
  font-size: 13px;
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: var(--r-xs);
  padding: 8px 10px;
}

.pv-data {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border-top: 1px solid var(--line);
  padding-top: 12px;
}

.pv-data-title {
  font-size: 11px;
  color: var(--fg-3);
  margin-bottom: 2px;
}

.pv-data-row {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-2);
}

/* ── Config rows ─────────────────────────────── */

.cfg-body {
  padding: 10px 8px;
}

.cfg-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
}

.cfg-row + .cfg-row {
  border-top: 1px solid var(--line);
}

.cfg-key {
  font-size: 13px;
  color: var(--fg-2);
}

.cfg-val {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.cfg-pill {
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 10px;
}

.cfg-on {
  color: #34d99a;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 480px) {
  .builder-body {
    grid-template-columns: minmax(0, 1fr);
  }

  .b-palette {
    flex-direction: row;
    flex-wrap: wrap;
    border-right: none;
    border-bottom: 1px solid var(--line);
  }
}
</style>
