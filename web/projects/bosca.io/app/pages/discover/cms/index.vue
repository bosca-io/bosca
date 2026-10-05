<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'CMS — A content engine, not just a CMS',
  description: 'Bosca CMS organizes collections, documents, media, guides, and data in one typed library — with real-time collaborative editing, adaptive media, workflow-driven publishing, and granular visibility controls.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'CMS', path: '/discover/cms' }
], '/og-cms.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Organize into collections',
    body: 'Collections are hierarchical containers — folders that hold folders and items. Each carries its own attributes, labels, workflow state, language variants, and visibility flags.'
  },
  {
    step: '02',
    title: 'Create typed items',
    body: 'Documents, guides, data records, media, bibles, and supplementary files each have a metadata record describing them — name, type, language, attributes, and labels.'
  },
  {
    step: '03',
    title: 'Review, then publish',
    body: 'Every item moves through a defined workflow — draft, review, approval, processing — before it is published or advertised, with transitions you can schedule in advance.'
  }
]

const FEATURES = [
  {
    icon: 'edit-3',
    title: 'Real-time collaboration',
    body: 'Documents edit live with multiple people at once — everyone\'s changes merge as they type, and the editor catches back up on its own after a dropped connection.'
  },
  {
    icon: 'video',
    title: 'Adaptive media',
    body: 'Video and audio transcode into adaptive quality tiers with automatic quality selection, generated thumbnails, and animated previews.'
  },
  {
    icon: 'database',
    title: 'Structured data',
    body: 'Beyond prose and media, data records store typed JSON — author profiles, pricing, locations — edited through generated forms and embedded into documents.'
  },
  {
    icon: 'align-left',
    title: 'Searchable transcripts',
    body: 'Media is auto-transcribed into plain-text and subtitle tracks, indexed for full-text search and reused for accessibility.'
  },
  {
    icon: 'shield',
    title: 'Two-dimensional publishing',
    body: 'Access is gated by both workflow state and independent visibility flags — Public, Public Content, Public Supplementary, Searchable.'
  },
  {
    icon: 'clock',
    title: 'Scheduled publishing',
    body: 'Workflow plans schedule a transition for a future time — automated go-live, embargo lift, and content rotation without anyone standing by.'
  },
  {
    icon: 'heart-pulse',
    title: 'Content health',
    body: 'A diagnostics dashboard flags failed jobs, broken relationships, unpublished references, and guides with unpublished steps — with a triage list.'
  },
  {
    icon: 'layers',
    title: 'Flexible classification',
    body: 'Hierarchical collections, cross-cutting categories, descriptive traits, and free-form labels organize content without duplicating it.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="cms"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca CMS
        </p>
        <h1 class="load-2">
          Collections, documents,<br>
          data, and media.<br>
          <em>One library.</em>
        </h1>
        <p class="hero-sub load-3">
          Bosca CMS is a content engine, not just a CMS: a typed, hierarchical
          library where rich documents, media, guides, and data live together —
          with real-time collaboration, adaptive media, and workflow-driven
          publishing built in.
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
        <div class="tree-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">the library</span>
          </div>
          <ul class="tree">
            <li class="tree-branch">
              <span class="tree-row"><Icon
                name="folder-open"
                :size="15"
                class="ti ti-col"
              /> Field Guides <span class="tree-tag">collection</span></span>
              <ul>
                <li>
                  <span class="tree-row"><Icon
                    name="file-text"
                    :size="15"
                    class="ti ti-doc"
                  /> Getting Started <span class="tree-tag tag-pub">published</span></span>
                </li>
                <li>
                  <span class="tree-row"><Icon
                    name="list-checks"
                    :size="15"
                    class="ti ti-guide"
                  /> Onboarding Guide <span class="tree-tag">guide · 6 steps</span></span>
                </li>
              </ul>
            </li>
            <li class="tree-branch">
              <span class="tree-row"><Icon
                name="folder-open"
                :size="15"
                class="ti ti-col"
              /> Media <span class="tree-tag">collection</span></span>
              <ul>
                <li>
                  <span class="tree-row"><Icon
                    name="video"
                    :size="15"
                    class="ti ti-media"
                  /> Launch Keynote <span class="tree-tag">1080p</span></span>
                </li>
                <li>
                  <span class="tree-row"><Icon
                    name="image"
                    :size="15"
                    class="ti ti-media"
                  /> Cover Art <span class="tree-tag tag-adv">advertised</span></span>
                </li>
              </ul>
            </li>
            <li>
              <span class="tree-row"><Icon
                name="database"
                :size="15"
                class="ti ti-data"
              /> Authors <span class="tree-tag">data · typed</span></span>
            </li>
          </ul>
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
        <h2>Organize, create, <em>publish.</em></h2>
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
          <h2>More than storage. <em>A content engine.</em></h2>
          <p class="section-sub">
            The platform handles collaboration, media processing, workflow, and
            access — so your team works on the content, not the plumbing.
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

    <!-- ── The model ───────────────────────────── -->
    <section class="section model">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            One record, every type
          </p>
          <h2>Everything is <em>metadata</em></h2>
          <p class="section-sub">
            Behind every document, video, guide, and data record is one
            description record: a name, a content type, a language tag,
            free-form attributes, labels, and categories.
          </p>
          <p class="section-sub">
            The item's <em>body</em> — rich text, a video file, a JSON record —
            is separate from the metadata that describes it, so the same
            workflow, permissions, and publishing rules apply to every kind of
            content.
          </p>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">a metadata record</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">{</span>
  <span class="tok-str">"name"</span><span class="tok-tag">:</span> <span class="tok-str">"Getting Started"</span><span class="tok-tag">,</span>
  <span class="tok-str">"contentType"</span><span class="tok-tag">:</span> <span class="tok-str">"bosca/v-document"</span><span class="tok-tag">,</span>
  <span class="tok-str">"languageTag"</span><span class="tok-tag">:</span> <span class="tok-str">"en"</span><span class="tok-tag">,</span>
  <span class="tok-str">"labels"</span><span class="tok-tag">:</span> <span class="tok-tag">[</span><span class="tok-str">"featured"</span><span class="tok-tag">]</span><span class="tok-tag">,</span>
  <span class="tok-str">"attributes"</span><span class="tok-tag">:</span> <span class="tok-tag">{</span> <span class="tok-str">"readingTime"</span><span class="tok-tag">:</span> <span class="tok-kt">4</span> <span class="tok-tag">}</span><span class="tok-tag">,</span>
  <span class="tok-str">"workflowState"</span><span class="tok-tag">:</span> <span class="tok-str">"published"</span>
<span class="tok-tag">}</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverCmsExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#f7828c"
        class="closing-mark"
      />
      <h2>The CMS ships <em>with Bosca</em></h2>
      <p class="section-sub">
        The same content the CMS manages is served through one GraphQL API —
        to your apps, to a BML site, to feeds and search. Studio manages it;
        everything else renders it.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
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
  border: 1px solid color-mix(in srgb, var(--accent) 30%, transparent);
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
  max-width: 470px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

/* ── Library tree ────────────────────────────── */

.tree-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 10%, transparent);
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
  margin: 0;
  padding-left: 22px;
  border-left: 1px solid var(--line);
  margin-left: 7px;
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
.tree .ti-media { color: #5cccf0; }
.tree .ti-data { color: #8fdca0; }

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

.tree-tag.tag-adv {
  color: #f6c453;
  background: color-mix(in srgb, #f6c453 12%, transparent);
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
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  transform: translateY(-2px);
}

.feature-icon {
  width: 30px;
  height: 30px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
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

/* ── Sections ────────────────────────────────── */

.model {
  padding-top: 90px;
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

.btn-cta {
  padding: 13px 34px;
  font-size: 15px;
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
    grid-template-columns: minmax(0, 1fr);
    padding-top: 36px;
    padding-bottom: 64px;
  }

  .how-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .feature-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .feature-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
