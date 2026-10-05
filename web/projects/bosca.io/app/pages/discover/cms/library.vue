<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'The CMS Content Model — Collections, Metadata & Typed Items',
  description: 'How Bosca organizes content: hierarchical collections, a metadata record behind every item, typed item kinds, and four complementary ways to classify — collections, categories, traits, and labels.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'CMS', path: '/discover/cms' },
  { name: 'The content model', path: '/discover/cms/library' }
], '/og-cms.png')

const ITEM_TYPES = [
  { icon: 'file-text', name: 'Documents', body: 'Rich text for articles and pages, edited collaboratively; body separate from metadata.' },
  { icon: 'list-checks', name: 'Guides', body: 'Ordered, step-based content — each step its own record, with optional scheduled recurrence.' },
  { icon: 'database', name: 'Data', body: 'Structured records typed by a data template and edited through a generated form.' },
  { icon: 'video', name: 'Media', body: 'Video, audio, and images with adaptive streaming, previews, and auto-transcription.' },
  { icon: 'book-open', name: 'Bibles', body: 'Scripture translations with languages, books, and chapters, browsable in the reader.' },
  { icon: 'package', name: 'Supplementary', body: 'Files attached to a collection or item by key — worksheets, transcripts, data exports.' }
]

const CLASSIFY = [
  { name: 'Collections', tag: 'hierarchy', body: 'The 1:1 home — an item lives in a collection, which nests inside other collections.' },
  { name: 'Categories', tag: 'cross-cutting', body: 'Flat, name-only buckets an item can belong to many of at once — topics, series, segments.' },
  { name: 'Traits', tag: 'descriptive', body: 'Named-and-described qualifiers editors apply consistently — Featured, Evergreen, Time-sensitive.' },
  { name: 'Labels', tag: 'internal', body: 'Free-form string tags on the record itself for lightweight, internal grouping.' }
]
</script>

<template>
  <DiscoverShell section-id="cms">
    <section class="page-hero">
      <p class="kicker load-1">
        The content model
      </p>
      <h1 class="load-2">
        A typed, <em>hierarchical library</em>
      </h1>
      <p class="section-sub load-3">
        Everything in the CMS is organized the same way: content lives in
        collections, every item has a metadata record that describes it, and
        four complementary systems classify it — so you can organize by
        structure and by meaning at once.
      </p>
    </section>

    <!-- ── Collections ─────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Collections
          </p>
          <h2>Folders that <em>hold folders</em></h2>
          <p class="section-sub">
            Collections nest into a hierarchy with configurable ordering.
            Standard, Folder, and Queue types shape how a collection behaves,
            and each one tracks its own workflow state independently of the
            items inside it.
          </p>
          <ul class="point-list">
            <li>Its own attributes, labels, workflow state, and language variants ride on the collection, not just its children.</li>
            <li>Four visibility flags — Public, Public List, Public Supplementary, Searchable — gate what anonymous visitors see.</li>
            <li>Group-based permissions grant view, edit, manage, and admin access at any level of the tree.</li>
          </ul>
        </div>
        <div class="tree-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">a collection tree</span>
          </div>
          <ul class="tree">
            <li class="tree-branch">
              <span class="tree-row"><Icon
                name="folder-open"
                :size="15"
                class="ti ti-col"
              /> Handbook <span class="tree-tag">standard</span></span>
              <ul>
                <li>
                  <span class="tree-row"><Icon
                    name="folder-open"
                    :size="15"
                    class="ti ti-col"
                  /> Policies <span class="tree-tag">folder</span></span>
                </li>
                <li>
                  <span class="tree-row"><Icon
                    name="file-text"
                    :size="15"
                    class="ti ti-doc"
                  /> Code of Conduct <span class="tree-tag tag-pub">published</span></span>
                </li>
                <li>
                  <span class="tree-row"><Icon
                    name="list-checks"
                    :size="15"
                    class="ti ti-guide"
                  /> Onboarding <span class="tree-tag">guide</span></span>
                </li>
              </ul>
            </li>
            <li class="tree-branch">
              <span class="tree-row"><Icon
                name="folder-open"
                :size="15"
                class="ti ti-col"
              /> Incoming <span class="tree-tag">queue</span></span>
              <ul>
                <li>
                  <span class="tree-row"><Icon
                    name="file-text"
                    :size="15"
                    class="ti ti-doc"
                  /> Draft Post <span class="tree-tag">draft</span></span>
                </li>
              </ul>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <!-- ── Metadata ────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Metadata
          </p>
          <h2>One record <em>describes everything</em></h2>
          <p class="section-sub">
            Behind every item is a metadata record: name, content type,
            language tag, free-form attributes, labels, categories, and source
            tracking — the same description record for every kind of content.
          </p>
          <ul class="point-list">
            <li>The item's <em>body</em> — the rich text, the video, the data record — is separate from the metadata that describes it.</li>
            <li>Free-form attributes hold whatever a content type needs — a reading time, a location, a price.</li>
            <li>Explicit, directional relationships connect records — a featured image, a translation, a related article.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">metadata.json</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">{</span>
  <span class="tok-str">"name"</span><span class="tok-tag">:</span> <span class="tok-str">"Launch Keynote"</span><span class="tok-tag">,</span>
  <span class="tok-str">"contentType"</span><span class="tok-tag">:</span> <span class="tok-str">"video/mp4"</span><span class="tok-tag">,</span>
  <span class="tok-str">"languageTag"</span><span class="tok-tag">:</span> <span class="tok-str">"en"</span><span class="tok-tag">,</span>
  <span class="tok-str">"labels"</span><span class="tok-tag">:</span> <span class="tok-tag">[</span><span class="tok-str">"keynote"</span><span class="tok-tag">,</span> <span class="tok-str">"2026"</span><span class="tok-tag">]</span><span class="tok-tag">,</span>
  <span class="tok-str">"categories"</span><span class="tok-tag">:</span> <span class="tok-tag">[</span><span class="tok-str">"Events"</span><span class="tok-tag">]</span><span class="tok-tag">,</span>
  <span class="tok-str">"source"</span><span class="tok-tag">:</span> <span class="tok-tag">{</span> <span class="tok-str">"id"</span><span class="tok-tag">:</span> <span class="tok-str">"upload"</span> <span class="tok-tag">}</span>
<span class="tok-tag">}</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Item types ──────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Item kinds
        </p>
        <h2>One library, <em>many kinds</em></h2>
        <p class="section-sub">
          Every kind of content shares the same metadata, workflow, and
          publishing rules — but each has a body suited to what it is.
        </p>
      </div>
      <div class="type-grid">
        <article
          v-for="type in ITEM_TYPES"
          :key="type.name"
          class="type-card reveal"
        >
          <span class="type-icon">
            <Icon
              :name="type.icon"
              :size="16"
            />
          </span>
          <h3>{{ type.name }}</h3>
          <p>{{ type.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── Classification ──────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Classification
        </p>
        <h2>Four ways to <em>organize</em></h2>
        <p class="section-sub">
          Structure and meaning are different problems, so the CMS gives you
          different tools — used together, they organize content without ever
          duplicating it.
        </p>
      </div>
      <div class="classify-grid reveal">
        <div
          v-for="c in CLASSIFY"
          :key="c.name"
          class="classify-card"
        >
          <div class="classify-head">
            <h3>{{ c.name }}</h3>
            <span class="classify-tag">{{ c.tag }}</span>
          </div>
          <p>{{ c.body }}</p>
        </div>
      </div>
    </section>

    <!-- ── Templates ───────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Templates
          </p>
          <h2>Blueprints for <em>new content</em></h2>
          <p class="section-sub">
            A template defines the shape of what editors create — typed
            attribute slots, a schema, and default attribute values that
            pre-fill on every new item. There is a template type for each
            content kind: document, data, guide, and collection.
          </p>
          <ul class="point-list">
            <li>Collection templates carry filters, ordering rules, and configuration for the collections they mint.</li>
            <li>Guide templates hold the guide type, an optional recurrence rule, and the step blueprint.</li>
            <li>Template changes apply to content created afterward — existing items are never rewritten underneath you.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">document template</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">{</span>
  <span class="tok-str">"type"</span><span class="tok-tag">:</span> <span class="tok-str">"Document"</span><span class="tok-tag">,</span>
  <span class="tok-str">"attributes"</span><span class="tok-tag">:</span> <span class="tok-tag">[</span>
    <span class="tok-tag">{</span> <span class="tok-str">"key"</span><span class="tok-tag">:</span> <span class="tok-str">"readingTime"</span><span class="tok-tag">,</span> <span class="tok-str">"type"</span><span class="tok-tag">:</span> <span class="tok-str">"int"</span> <span class="tok-tag">}</span>
  <span class="tok-tag">]</span><span class="tok-tag">,</span>
  <span class="tok-str">"defaultAttributes"</span><span class="tok-tag">:</span> <span class="tok-tag">{</span> <span class="tok-str">"template"</span><span class="tok-tag">:</span> <span class="tok-str">"article"</span> <span class="tok-tag">}</span><span class="tok-tag">,</span>
  <span class="tok-str">"schema"</span><span class="tok-tag">:</span> <span class="tok-tag">{</span> <span class="tok-com">/* expected shape */</span> <span class="tok-tag">}</span>
<span class="tok-tag">}</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <DiscoverCmsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Library tree ────────────────────────────── */

.tree-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.tree {
  list-style: none;
  margin: 0;
  padding: 16px 18px 20px;
  font-family: var(--font-mono);
  font-size: 13px;
}

.tree ul {
  list-style: none;
  margin: 0 0 0 7px;
  padding-left: 22px;
  border-left: 1px solid var(--line);
}

.tree li {
  padding: 5px 0;
}

.tree-row {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--fg-1);
}

.tree .ti-col { color: var(--accent); }
.tree .ti-doc { color: #93a5f6; }
.tree .ti-guide { color: #f6c453; }

.tree-tag {
  font-size: 10.5px;
  color: var(--fg-3);
  background: color-mix(in srgb, var(--fg-3) 12%, transparent);
  border-radius: 4px;
  padding: 1px 6px;
}

.tree-tag.tag-pub {
  color: #34d99a;
  background: color-mix(in srgb, #34d99a 12%, transparent);
}

/* ── Item type cards ─────────────────────────── */

.type-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.type-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.type-card:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  transform: translateY(-2px);
}

.type-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
  margin-bottom: 14px;
}

.type-card h3 {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.type-card p {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

/* ── Classification cards ────────────────────── */

.classify-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.classify-card {
  padding: 20px 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.classify-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.classify-head h3 {
  font-size: 14.5px;
  font-weight: 650;
  margin: 0;
}

.classify-tag {
  font-family: var(--font-mono);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--accent);
}

.classify-card p {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 960px) {
  .type-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .classify-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .type-grid,
  .classify-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
