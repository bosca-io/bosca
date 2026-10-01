<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'The BML Language — Pages, Components & Control Flow',
  description: 'How BML pages, components, typed props, control flow, and scoped styles work — the whole language for a site page lives in one markup file.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'BML', path: '/discover/bml' },
  { name: 'The language', path: '/discover/bml/language' }
], '/og-bml.png')

const VOCAB = [
  { snippet: '{ expr }', body: 'A server-evaluated Kotlin expression, HTML-escaped by default.' },
  { snippet: '{@ expr }', body: 'Raw HTML output — the explicit, visible trusted path.' },
  { snippet: ':href="user.url"', body: 'A bound attribute: one server expression. false or null omits the attribute entirely.' },
  { snippet: 'class="card-{ size }"', body: 'Static attributes interpolate server values inline.' },
  { snippet: 'requireAuth', body: 'A bare attribute means true by being there — presence is the value.' },
  { snippet: '{# note #}', body: 'A build-only comment: never emitted, unlike an HTML comment.' }
]
</script>

<template>
  <DiscoverShell section-id="bml">
    <section class="page-hero">
      <p class="kicker load-1">
        The language
      </p>
      <h1 class="load-2">
        Markup with a <em>type system</em>
      </h1>
      <p class="section-sub load-3">
        A BML file reads like HTML because it mostly is HTML — plus routable
        pages, components with typed Kotlin props, control flow as tags, and
        styles that scope themselves. Everything a page needs lives in the
        file that renders it.
      </p>
    </section>

    <!-- ── Pages & control flow ────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Pages
          </p>
          <h2>A route is <em>a file</em></h2>
          <p class="section-sub">
            Each page file declares exactly one <code>&lt;page route="…"&gt;</code>.
            Path parameters like <code>{id}</code> are matched by the router
            and arrive in server code as in-scope values; the bare
            <code>requireAuth</code> attribute redirects signed-out visitors
            before anything renders.
          </p>
          <ul class="point-list">
            <li><code>&lt;if&gt;</code> takes <code>&lt;else-if&gt;</code> and <code>&lt;else&gt;</code> as bare separators — one closing tag ends the whole conditional.</li>
            <li><code>&lt;for item in expr&gt;</code> iterates any Iterable; <code>&lt;for (i, item) in expr&gt;</code> adds the index.</li>
            <li>Server data comes from <code>&lt;script server provides="…"&gt;</code> — the block's last expression binds into scope.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">group.bml</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">&lt;page</span> <span class="tok-attr">route</span>=<span class="tok-str">"/group/{id}"</span> <span class="tok-attr">requireAuth</span><span class="tok-tag">&gt;</span>
  <span class="tok-tag">&lt;script</span> <span class="tok-attr">server</span> <span class="tok-attr">provides</span>=<span class="tok-str">"page"</span><span class="tok-tag">&gt;</span>
    <span class="tok-kt">myapp.group(ctx)</span>
  <span class="tok-tag">&lt;/script&gt;</span>

  <span class="tok-tag">&lt;html</span> <span class="tok-attr">lang</span>=<span class="tok-str">"en"</span><span class="tok-tag">&gt;</span>
    <span class="tok-tag">&lt;head&gt;&lt;title&gt;</span>Group<span class="tok-tag">&lt;/title&gt;&lt;/head&gt;</span>
    <span class="tok-tag">&lt;body&gt;</span>
      <span class="tok-tag">&lt;main</span> <span class="tok-attr">class</span>=<span class="tok-str">"wrap"</span><span class="tok-tag">&gt;</span>
        <span class="tok-tag">&lt;if</span> <span class="tok-kt">page.error != null</span><span class="tok-tag">&gt;</span>
          <span class="tok-tag">&lt;p</span> <span class="tok-attr">class</span>=<span class="tok-str">"error"</span><span class="tok-tag">&gt;</span><span class="tok-interp">{ page.error }</span><span class="tok-tag">&lt;/p&gt;</span>
        <span class="tok-tag">&lt;else-if</span> <span class="tok-kt">page.found</span><span class="tok-tag">&gt;</span>
          <span class="tok-tag">&lt;group-head</span> <span class="tok-attr">:name</span>=<span class="tok-str">"page.groupName"</span> <span class="tok-attr">:members</span>=<span class="tok-str">"page.members"</span><span class="tok-tag">/&gt;</span>
        <span class="tok-tag">&lt;else&gt;</span>
          <span class="tok-tag">&lt;p&gt;</span>This group isn't available.<span class="tok-tag">&lt;/p&gt;</span>
        <span class="tok-tag">&lt;/if&gt;</span>
      <span class="tok-tag">&lt;/main&gt;</span>
    <span class="tok-tag">&lt;/body&gt;</span>
  <span class="tok-tag">&lt;/html&gt;</span>
<span class="tok-tag">&lt;/page&gt;</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Expression vocabulary ───────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Expressions
        </p>
        <h2>Six marks to <em>learn</em></h2>
        <p class="section-sub">
          The expression syntax is small on purpose. Escaped by default, raw
          only when you say so.
        </p>
      </div>
      <div class="vocab-grid reveal">
        <div
          v-for="item in VOCAB"
          :key="item.snippet"
          class="vocab-card"
        >
          <code>{{ item.snippet }}</code>
          <p>{{ item.body }}</p>
        </div>
      </div>
    </section>

    <!-- ── Components ──────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Components
          </p>
          <h2>Typed props, <em>scoped styles</em></h2>
          <p class="section-sub">
            <code>&lt;component tag="…"&gt;</code> declares a reusable tag.
            Props are typed inputs — any Kotlin type, including your own
            classes and generics — and they're in scope in the body and its
            server script. Callers pass them with the same binding syntax:
            <code>&lt;prayer-card :prayer="p"/&gt;</code>.
          </p>
          <ul class="point-list">
            <li>A <code>&lt;style scoped&gt;</code> is isolated to the component: the compiler stamps a marker attribute and rewrites each selector to require it.</li>
            <li>Scoped CSS is emitted once per page no matter how many times the component renders; a plain <code>&lt;style&gt;</code> stays global.</li>
            <li>Slotted content is scoped to the caller — it's the caller's markup.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">prayer-card.bml</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">&lt;component</span> <span class="tok-attr">tag</span>=<span class="tok-str">"prayer-card"</span><span class="tok-tag">&gt;</span>
  <span class="tok-tag">&lt;prop</span> <span class="tok-attr">name</span>=<span class="tok-str">"prayer"</span> <span class="tok-attr">type</span>=<span class="tok-str">"myapp.PrayerRow"</span> <span class="tok-attr">required</span><span class="tok-tag">/&gt;</span>

  <span class="tok-tag">&lt;article</span> <span class="tok-attr">class</span>=<span class="tok-str">"prayer-card"</span><span class="tok-tag">&gt;</span>
    <span class="tok-tag">&lt;span</span> <span class="tok-attr">class</span>=<span class="tok-str">"author"</span><span class="tok-tag">&gt;</span><span class="tok-interp">{ prayer.profileName }</span><span class="tok-tag">&lt;/span&gt;</span>
    <span class="tok-tag">&lt;if</span> <span class="tok-kt">prayer.answered</span><span class="tok-tag">&gt;</span>
      <span class="tok-tag">&lt;span</span> <span class="tok-attr">class</span>=<span class="tok-str">"status-pill"</span><span class="tok-tag">&gt;</span>Answered<span class="tok-tag">&lt;/span&gt;</span>
    <span class="tok-tag">&lt;/if&gt;</span>
    <span class="tok-tag">&lt;h3&gt;&lt;a</span> <span class="tok-attr">href</span>=<span class="tok-str">"{ prayer.href }"</span><span class="tok-tag">&gt;</span><span class="tok-interp">{ prayer.title }</span><span class="tok-tag">&lt;/a&gt;&lt;/h3&gt;</span>
  <span class="tok-tag">&lt;/article&gt;</span>

  <span class="tok-tag">&lt;style</span> <span class="tok-attr">scoped</span><span class="tok-tag">&gt;</span>
    <span class="tok-kt">.prayer-card { border: 1px solid var(--divider); }</span>
    <span class="tok-kt">.status-pill { color: var(--forest); }</span>
  <span class="tok-tag">&lt;/style&gt;</span>
<span class="tok-tag">&lt;/component&gt;</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Slots & overrides ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Slots &amp; overrides
          </p>
          <h2>Reuse markup <em>with slots</em></h2>
          <p class="section-sub">
            A component with a <code>&lt;slot/&gt;</code> renders the caller's
            children inside shared markup. Use it for repeated sections; each
            page still emits its own <code>&lt;html&gt;</code>,
            <code>&lt;head&gt;</code>, and <code>&lt;body&gt;</code>.
            <code>&lt;template&gt;</code> and <code>layout=</code> are reserved
            syntax and do not wrap a page.
          </p>
          <ul class="point-list">
            <li>Most built-in HTML tags are overrideable: <code>&lt;component tag="a"&gt;</code> replaces every anchor on the site.</li>
            <li>The <code>html:</code> namespace always resolves to the native element — how an override emits the real tag without recursing.</li>
            <li>Special tags (<code>page</code>, <code>component</code>, <code>if</code>, <code>for</code>, <code>island</code>, <code>slot</code>) are protected.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">content-frame.bml</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">&lt;component</span> <span class="tok-attr">tag</span>=<span class="tok-str">"content-frame"</span><span class="tok-tag">&gt;</span>
  <span class="tok-tag">&lt;main</span> <span class="tok-attr">class</span>=<span class="tok-str">"content-frame"</span><span class="tok-tag">&gt;</span>
    <span class="tok-tag">&lt;slot/&gt;</span>
  <span class="tok-tag">&lt;/main&gt;</span>
<span class="tok-tag">&lt;/component&gt;</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <DiscoverBmlExplore />
  </DiscoverShell>
</template>

<style scoped>
.vocab-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.vocab-card {
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.vocab-card > code {
  display: inline-block;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--accent);
  margin-bottom: 10px;
}

.vocab-card p {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

@media (max-width: 960px) {
  .vocab-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .vocab-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
