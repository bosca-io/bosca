<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Authoring in the CMS — Collaborative Documents, Guides & Data',
  description: 'Bosca documents edit in real time with several people at once, guides break content into ordered steps, and data records edit through generated forms — all created from templates.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'CMS', path: '/discover/cms' },
  { name: 'Authoring', path: '/discover/cms/authoring' }
], '/og-cms.png')
</script>

<template>
  <DiscoverShell section-id="cms">
    <section class="page-hero">
      <p class="kicker load-1">
        Authoring
      </p>
      <h1 class="load-2">
        Write together, <em>in real time</em>
      </h1>
      <p class="section-sub load-3">
        Documents in Bosca are collaborative by default. The same rich-text
        editor handles articles, pages, and long-form writing — with multiple
        people editing at once, guides that break content into steps, and data
        records that edit through generated forms.
      </p>
    </section>

    <!-- ── Collaboration ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Real-time collaboration
          </p>
          <h2>Many cursors, <em>one document</em></h2>
          <p class="section-sub">
            Several people can edit the same document at the same time.
            Everyone's changes merge as they type — no locking, no overwriting
            each other's work — and if a connection drops, the editor catches
            back up on its own when it returns.
          </p>
          <ul class="point-list">
            <li>Formatted text, headings, lists, links, and embedded media, all in one editor.</li>
            <li>The document's content and the metadata that describes it — name, labels, workflow state — stay separate, so the same rules apply as to any other item.</li>
            <li>Every document starts from a template that supplies its defaults and structure.</li>
          </ul>
        </div>
        <div class="doc-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">getting-started.doc</span>
            <span class="collab-avatars">
              <span class="avatar av-a">A</span>
              <span class="avatar av-b">M</span>
              <span class="avatar av-c">K</span>
            </span>
          </div>
          <div class="doc-body">
            <h4>Getting Started</h4>
            <p>
              Welcome to the platform. This guide walks through your first
              <span class="sel sel-a">project</span>, from setup to launch.
            </p>
            <p>
              Start by creating a collection to hold your work.<span
                class="caret caret-b"
                data-who="Maria"
              /> Every
              item you add inherits its workflow and permissions.
            </p>
            <p>
              When you're ready, move the document into
              <span class="sel sel-c">review</span><span
                class="caret caret-c"
                data-who="Kai"
              /> and an approver takes it from there.
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Guides ──────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Guides
          </p>
          <h2>Content, <em>step by step</em></h2>
          <p class="section-sub">
            A guide is an ordered sequence of steps — each step its own content
            record with rich text, media, and supplementary materials, and its
            own workflow state. Guides are built from a guide template that
            defines the structural type and step blueprint.
          </p>
          <ul class="point-list">
            <li>A guide template can carry a recurrence rule for content that repeats on a schedule.</li>
            <li>Each step is published on its own; a guide with an unpublished step is flagged in Content Health.</li>
            <li>Steps reorder freely — the position lives on the step, not in the body.</li>
          </ul>
        </div>
        <div class="steps-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">onboarding · guide</span>
          </div>
          <ol class="steps">
            <li><span class="step-n">1</span> <span class="step-name">Create your account</span> <span class="step-tag tag-pub">published</span></li>
            <li><span class="step-n">2</span> <span class="step-name">Set up a collection</span> <span class="step-tag tag-pub">published</span></li>
            <li><span class="step-n">3</span> <span class="step-name">Invite your team</span> <span class="step-tag tag-draft">draft</span></li>
            <li><span class="step-n">4</span> <span class="step-name">Publish your first page</span> <span class="step-tag">pending</span></li>
          </ol>
        </div>
      </div>
    </section>

    <!-- ── Data ────────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Data
          </p>
          <h2>Structured records, <em>generated forms</em></h2>
          <p class="section-sub">
            Not everything is prose. Data items are structured JSON records
            typed by a data template — author profiles, locations, pricing
            tables, taxonomies. The template's fields generate the editing
            form, and documents can embed a data item that renders in place.
          </p>
          <ul class="point-list">
            <li>Field validation comes from the template, so records stay well-formed.</li>
            <li>Data records carry the same metadata as everything else — workflow state, language, and visibility.</li>
          </ul>
        </div>
        <div class="form-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">author · data form</span>
          </div>
          <div class="form-body">
            <label class="field">
              <span class="field-label">Name</span>
              <span class="field-input">Ada Lovelace</span>
            </label>
            <label class="field">
              <span class="field-label">Role</span>
              <span class="field-input">Editor</span>
            </label>
            <label class="field">
              <span class="field-label">Location</span>
              <span class="field-input field-muted">London, UK</span>
            </label>
            <div class="field-row">
              <label class="field">
                <span class="field-label">Lat</span>
                <span class="field-input">51.5074</span>
              </label>
              <label class="field">
                <span class="field-label">Long</span>
                <span class="field-input">-0.1278</span>
              </label>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverCmsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Collaborative document ──────────────────── */

.doc-window,
.steps-window,
.form-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.collab-avatars {
  display: inline-flex;
  margin-left: auto;
}

.avatar {
  width: 22px;
  height: 22px;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 700;
  color: #05131c;
  border: 2px solid var(--bg-1);
  margin-left: -6px;
}

.av-a { background: #f7828c; }
.av-b { background: #5cccf0; }
.av-c { background: #8fdca0; }

.doc-body {
  padding: 22px 24px 26px;
}

.doc-body h4 {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.02em;
  margin: 0 0 12px;
  color: var(--fg-0);
}

.doc-body p {
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--fg-1);
  margin: 0 0 12px;
}

.sel {
  border-radius: 3px;
  padding: 0 2px;
}

.sel-a { background: color-mix(in srgb, #f7828c 28%, transparent); }
.sel-c { background: color-mix(in srgb, #8fdca0 28%, transparent); }

/* Collaborator carets with a floating name flag. */
.caret {
  position: relative;
  display: inline-block;
  width: 2px;
  height: 1em;
  vertical-align: text-bottom;
  margin: 0 1px;
}

.caret-b { background: #5cccf0; }
.caret-c { background: #8fdca0; }

.caret::after {
  content: attr(data-who);
  position: absolute;
  top: -17px;
  left: -2px;
  font-family: var(--font-sans);
  font-size: 9.5px;
  font-weight: 600;
  color: #05131c;
  border-radius: 3px;
  padding: 1px 5px;
  white-space: nowrap;
}

.caret-b::after { background: #5cccf0; }
.caret-c::after { background: #8fdca0; }

.caret-b { animation: caret-blink 1.1s steps(1) infinite; }
.caret-c { animation: caret-blink 1.1s steps(1) infinite 0.4s; }

@keyframes caret-blink {
  0%, 60% { opacity: 1; }
  61%, 100% { opacity: 0.35; }
}

/* ── Guide steps ─────────────────────────────── */

.steps {
  list-style: none;
  margin: 0;
  padding: 14px 18px 18px;
}

.steps li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 8px;
}

.steps li + li {
  border-top: 1px solid var(--line);
}

.step-n {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
}

.step-name {
  flex: 1;
  font-size: 13.5px;
  color: var(--fg-1);
}

.step-tag {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  background: color-mix(in srgb, var(--fg-3) 12%, transparent);
  border-radius: 4px;
  padding: 2px 7px;
}

.step-tag.tag-pub {
  color: #34d99a;
  background: color-mix(in srgb, #34d99a 12%, transparent);
}

.step-tag.tag-draft {
  color: #f6c453;
  background: color-mix(in srgb, #f6c453 12%, transparent);
}

/* ── Data form ───────────────────────────────── */

.form-body {
  padding: 20px 22px 24px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.field-label {
  font-family: var(--font-mono);
  font-size: 10.5px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
}

.field-input {
  font-size: 13.5px;
  color: var(--fg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  background: color-mix(in srgb, var(--bg-1) 60%, transparent);
  padding: 9px 12px;
}

.field-muted {
  color: var(--fg-2);
}

/* ── Reduced motion ──────────────────────────── */

@media (prefers-reduced-motion: reduce) {
  .caret-b,
  .caret-c {
    animation: none;
  }
}
</style>
