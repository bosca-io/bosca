<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'BML — Server-rendered sites and email on Bosca',
  description: 'BML is Bosca\'s compiled markup language for localized, server-rendered sites and transactional email: complete HTML, selective client islands, CLDR pluralization, email-safe output, and a live hot-swap development loop.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'BML', path: '/discover/bml' }
], '/og-bml.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Get started', href: '#start' },
  { label: 'Performance', href: '#performance' },
  { label: 'Go deeper', href: '#deeper' }
]

// The hero window is tabbed: the page markup and the ordinary Kotlin behind it.
const heroTab = ref<'bml' | 'kt'>('bml')

// In-window callouts on the hero code sample. `line` is 1-based into the
// sample below; tops are computed from the pre's fixed line height.
const CALLOUTS = [
  { line: 3, label: 'server Kotlin' },
  { line: 11, label: 'interpolation' },
  { line: 13, label: 'a typed component' },
  { line: 16, label: 'an island' },
  { line: 19, label: 'an action' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Write markup',
    body: 'Pages, components, and islands live in .bml files — HTML-like markup with embedded server Kotlin and, where needed, client TypeScript in the same file.'
  },
  {
    step: '02',
    title: 'The compiler takes it from there',
    body: 'A Kotlin K2 compiler plugin turns each routable page into a typed renderer and bundles client scripts into browser modules. Diagnostics point at the .bml line you wrote.'
  },
  {
    step: '03',
    title: 'The server renders the page',
    body: 'One server process registers every page route, renders its HTML, and serves the assets it uses. Private deferred islands can render after a public shell loads.'
  }
]

const FEATURES = [
  {
    icon: 'server',
    title: 'Server-rendered first',
    body: 'Pages render on the server. A public shell can include static fallback markup while private deferred islands load.'
  },
  {
    icon: 'layers',
    title: 'Selective client behavior',
    body: 'Use islands and declarative actions where a page needs interaction. Other markup stays server rendered.'
  },
  {
    icon: 'zap',
    title: 'Actions without JavaScript',
    body: '@click and @submit name a method on a server-side model. The server runs it and re-renders the island in place.'
  },
  {
    icon: 'database',
    title: 'One data plane',
    body: 'All reads and writes go through the Bosca GraphQL API — a typed Kotlin client on the server, typed TypeScript stubs in the browser.'
  },
  {
    icon: 'braces',
    title: 'Compiled pages',
    body: 'Pages are typed Kotlin objects produced by a real compiler plugin, with an IntelliJ plugin for .bml editing.'
  },
  {
    icon: 'boxes',
    title: 'Components with typed props',
    body: 'Reusable tags with declared props, slots, scoped styles, and per-instance state. Most built-in HTML tags can be overridden.'
  },
  {
    icon: 'shield',
    title: 'Client-managed auth',
    body: 'The browser owns the token; the server is pure passthrough. requireAuth guards a page with a redirect to sign-in.'
  },
  {
    icon: 'route',
    title: 'Dev/prod parity',
    body: 'The GraphQL endpoint is configuration. Develop against a remote Bosca and deploy the same code unchanged.'
  },
  {
    icon: 'languages',
    title: 'Localization built in',
    body: 'Resolve a visitor\'s locale once and use the same published strings, CLDR plural rules, and fallbacks on the server and in client islands.'
  },
  {
    icon: 'mail',
    title: 'Email is a first-class target',
    body: 'Compile localized templates into email-safe HTML and plain text, with CSS inlined and images attached for reliable delivery.'
  }
]

const STEPS = [
  {
    title: 'Scaffold',
    body: 'bosca bml init creates an ordinary Gradle project that lives in its own repository.'
  },
  {
    title: 'Point it at Bosca',
    body: 'Set the GraphQL endpoint — local or remote. Every query runs as the person viewing the page.'
  },
  {
    title: 'Write and iterate',
    body: 'The dev loop rebuilds and hot-swaps application code in the running process, then reloads the browser. The port, process, and session state stay put; a failed build leaves the last good version live.'
  },
  {
    title: 'Deploy one process',
    body: 'A compiled site is a single server: pages, static assets, actions, contracts, and the same-origin GraphQL proxy.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="bml"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Markup Language
        </p>
        <h1 class="load-2">
          Pages, data,<br>
          and interactivity.<br>
          <em>Server-rendered.</em>
        </h1>
        <p class="hero-sub load-3">
          BML is a server-rendered web framework for building sites on Bosca.
          Write HTML-like markup with embedded server Kotlin; the compiler
          turns each file into a page that ships complete HTML — and only as
          much JavaScript as you declared.
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

      <div class="hero-visual load-4">
        <div class="code-window hero-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <button
              type="button"
              class="code-tab"
              :class="{ active: heroTab === 'bml' }"
              @click="heroTab = 'bml'"
            >
              prayer.bml
            </button>
            <button
              type="button"
              class="code-tab"
              :class="{ active: heroTab === 'kt' }"
              @click="heroTab = 'kt'"
            >
              Prayers.kt
            </button>
          </div>
          <div
            v-show="heroTab === 'bml'"
            class="code-body"
          >
            <pre><code><span class="tok-tag">&lt;page</span> <span class="tok-attr">route</span>=<span class="tok-str">"/prayers/{id}"</span> <span class="tok-attr">requireAuth</span><span class="tok-tag">&gt;</span>
  <span class="tok-tag">&lt;script</span> <span class="tok-attr">server</span> <span class="tok-attr">provides</span>=<span class="tok-str">"prayer"</span><span class="tok-tag">&gt;</span>
    <span class="tok-kt">myapp.prayerDetail(id)</span>
  <span class="tok-tag">&lt;/script&gt;</span>

  <span class="tok-tag">&lt;html</span> <span class="tok-attr">lang</span>=<span class="tok-str">"en"</span><span class="tok-tag">&gt;</span>
    <span class="tok-tag">&lt;head&gt;&lt;title&gt;</span><span class="tok-interp">{ prayer.title }</span><span class="tok-tag">&lt;/title&gt;&lt;/head&gt;</span>
    <span class="tok-tag">&lt;body&gt;</span>
      <span class="tok-tag">&lt;site-head/&gt;</span>
      <span class="tok-tag">&lt;main&gt;</span>
        <span class="tok-tag">&lt;h1&gt;</span><span class="tok-interp">{ prayer.title }</span><span class="tok-tag">&lt;/h1&gt;</span>
        <span class="tok-tag">&lt;for</span> <span class="tok-kt">c</span> <span class="tok-attr">in</span> <span class="tok-kt">prayer.comments</span><span class="tok-tag">&gt;</span>
          <span class="tok-tag">&lt;comment-card</span> <span class="tok-attr">:comment</span>=<span class="tok-str">"c"</span><span class="tok-tag">/&gt;</span>
        <span class="tok-tag">&lt;/for&gt;</span>

        <span class="tok-tag">&lt;island</span> <span class="tok-attr">name</span>=<span class="tok-str">"reactions"</span><span class="tok-tag">&gt;</span>
          <span class="tok-tag">&lt;p&gt;</span><span class="tok-interp">{ prayer.reactionCount }</span> amens<span class="tok-tag">&lt;/p&gt;</span>
        <span class="tok-tag">&lt;/island&gt;</span>
        <span class="tok-tag">&lt;button</span> <span class="tok-attr">@click</span>=<span class="tok-str">"prayer.amen"</span><span class="tok-tag">&gt;</span>Amen<span class="tok-tag">&lt;/button&gt;</span>
      <span class="tok-tag">&lt;/main&gt;</span>
    <span class="tok-tag">&lt;/body&gt;</span>
  <span class="tok-tag">&lt;/html&gt;</span>
<span class="tok-tag">&lt;/page&gt;</span></code></pre>
            <span
              v-for="(callout, i) in CALLOUTS"
              :key="callout.line"
              class="code-callout"
              :style="{ '--line': callout.line, '--callout-delay': `${0.9 + i * 0.15}s` }"
            >{{ callout.label }}</span>
          </div>
          <div
            v-show="heroTab === 'kt'"
            class="code-body"
          >
            <pre><code><span class="tok-com">// Ordinary site Kotlin behind prayer.bml.</span>
<span class="tok-attr">@Serializable</span>
<span class="tok-tag">class</span> <span class="tok-kt">PrayerModel</span>(
    <span class="tok-tag">val</span> id: String,
    <span class="tok-tag">val</span> title: String,
    <span class="tok-tag">val</span> comments: List&lt;CommentRow&gt;,
    <span class="tok-tag">var</span> reactionCount: Int,
) {
    <span class="tok-tag">suspend fun</span> <span class="tok-kt">amen</span>() {
        client().execute(AddAmen, AddAmen.Variables(id))
        reactionCount += <span class="tok-kt">1</span>
    }
}

<span class="tok-tag">suspend fun</span> <span class="tok-kt">prayerDetail</span>(id: String): PrayerModel {
    <span class="tok-tag">val</span> p = client()
        .execute(GetPrayer, GetPrayer.Variables(id))
        .prayers.byId
    <span class="tok-tag">return</span> PrayerModel(
        p.id, p.title, p.comments, p.reactionCount,
    )
}</code></pre>
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
        <h2>Markup in. <em>Finished pages out.</em></h2>
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
    <section class="features">
      <div class="features-inner">
        <div class="section-head reveal">
          <p class="kicker">
            What makes it powerful
          </p>
          <h2>Less to write. <em>Less to ship.</em></h2>
          <p class="section-sub">
            The framework does the plumbing — rendering, data access, auth
            passthrough, asset serving — so a site is markup, data functions,
            and little else.
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

    <!-- ── Actions ─────────────────────────────── -->
    <section class="section actions">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Live state &amp; actions
          </p>
          <h2>Interactive, with <em>zero client code</em></h2>
          <p class="section-sub">
            A page declares a server-side model, an island renders it, and
            <code>@click</code> names a method on it. When the event fires, the
            server runs the method, persists the new state, re-renders the
            island, and the runtime swaps it in place. This cart never required
            a line of JavaScript.
          </p>
          <p class="section-sub">
            With <code>scope="server-session"</code> the model lives in a server-side
            session — the client only ever holds an opaque session cookie, so
            the cart's contents never reach the browser.
          </p>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">cart.bml</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">&lt;page</span> <span class="tok-attr">route</span>=<span class="tok-str">"/cart"</span><span class="tok-tag">&gt;</span>
  <span class="tok-tag">&lt;script</span> <span class="tok-attr">server</span> <span class="tok-attr">provides</span>=<span class="tok-str">"cart"</span> <span class="tok-attr">scope</span>=<span class="tok-str">"server-session"</span><span class="tok-tag">&gt;</span>
    <span class="tok-kt">shop.Cart()</span>
  <span class="tok-tag">&lt;/script&gt;</span>

  <span class="tok-tag">&lt;html</span> <span class="tok-attr">lang</span>=<span class="tok-str">"en"</span><span class="tok-tag">&gt;</span>
    <span class="tok-tag">&lt;head&gt;&lt;title&gt;</span>Cart<span class="tok-tag">&lt;/title&gt;&lt;/head&gt;</span>
    <span class="tok-tag">&lt;body&gt;</span>
      <span class="tok-tag">&lt;island</span> <span class="tok-attr">name</span>=<span class="tok-str">"cart-view"</span><span class="tok-tag">&gt;</span>
        <span class="tok-tag">&lt;p&gt;</span><span class="tok-interp">{ cart.itemCount }</span> item(s) in your cart<span class="tok-tag">&lt;/p&gt;</span>
      <span class="tok-tag">&lt;/island&gt;</span>
      <span class="tok-tag">&lt;button</span> <span class="tok-attr">@click</span>=<span class="tok-str">"cart.addItem"</span><span class="tok-tag">&gt;</span>Add item<span class="tok-tag">&lt;/button&gt;</span>
      <span class="tok-tag">&lt;button</span> <span class="tok-attr">@click</span>=<span class="tok-str">"cart.clear"</span><span class="tok-tag">&gt;</span>Clear<span class="tok-tag">&lt;/button&gt;</span>
    <span class="tok-tag">&lt;/body&gt;</span>
  <span class="tok-tag">&lt;/html&gt;</span>
<span class="tok-tag">&lt;/page&gt;</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Get started ─────────────────────────── -->
    <section
      id="start"
      class="section"
    >
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Get started
          </p>
          <h2>From <em>init</em> to a running site</h2>
          <ol class="start-steps">
            <li
              v-for="(step, i) in STEPS"
              :key="step.title"
            >
              <span class="step-num">{{ i + 1 }}</span>
              <div>
                <h3>{{ step.title }}</h3>
                <p>{{ step.body }}</p>
              </div>
            </li>
          </ol>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">the bosca CLI</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-com"># scaffold a project</span>
<span class="tok-kt">bosca bml init</span> my-site

<span class="tok-com"># generate Kotlin once</span>
<span class="tok-kt">bosca bml compile</span> my-site

<span class="tok-com"># watch, regenerate, restart, live-reload</span>
<span class="tok-kt">bosca bml dev</span> my-site</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Performance ─────────────────────────── -->
    <section
      id="performance"
      class="section"
    >
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Performance
          </p>
          <h2>Small pages, <em>real numbers</em></h2>
          <p class="section-sub">
            Rendering is Kotlin coroutines end to end on Netty. A render in
            flight is a coroutine, not an OS thread — it costs nothing while
            it waits on data — so a single small server holds far more
            concurrent requests than a thread-per-request model. And a
            rendered page is plain HTML text: no virtual DOM, no hydration
            snapshot, no per-page framework state held in server memory.
            Sessions exist only where a page opts in with
            <code>scope="server-session"</code>.
          </p>
          <ul class="point-list">
            <li>In production, a page downloads at most <strong>one stylesheet and one script</strong> on top of the global tier — component CSS and JS merge per page, and what every page shares folds into the global file. Dev keeps per-component files so hot reload stays instant.</li>
            <li>On the sample site, the page with a live island downloads 2.6&nbsp;KB over the wire in 5 requests — 545&nbsp;B of HTML, 393&nbsp;B of CSS, and 1.6&nbsp;KB of JavaScript including the island runtime.</li>
            <li>The page with no islands ships 304&nbsp;B in 3 requests. No islands, no JavaScript.</li>
            <li>Every build reports every page's first-load payload (<code>bmlMetrics</code>), and <code>bosca bml audit</code> measures any running site — whatever it's built with — so size regressions are numbers in a diff, and comparisons are one command.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">bosca bml audit — the sample site</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-kt">$ bosca bml audit</span> http://localhost:9391 <span class="tok-attr">-r</span> / <span class="tok-attr">-r</span> /dashboard

/ — 5 requests, 2.6 KB transfer
  <span class="tok-tag">[200]</span> <span class="tok-attr">html</span>    545 B  <span class="tok-com">/</span>
  <span class="tok-tag">[200]</span> <span class="tok-attr">css</span>      96 B  <span class="tok-com">/_bml/app.css</span>
  <span class="tok-tag">[200]</span> <span class="tok-attr">css</span>     297 B  <span class="tok-com">/_bml/css/index.page.css</span>
  <span class="tok-tag">[200]</span> <span class="tok-attr">js</span>       58 B  <span class="tok-com">/_bml/app.js</span>
  <span class="tok-tag">[200]</span> <span class="tok-attr">js</span>     1.6 KB  <span class="tok-com">/_bml/js/index.page.js</span>

/dashboard — 3 requests, 304 B transfer
  <span class="tok-tag">[200]</span> <span class="tok-attr">html</span>    150 B  <span class="tok-com">/dashboard</span>
  <span class="tok-tag">[200]</span> <span class="tok-attr">css</span>      96 B  <span class="tok-com">/_bml/app.css</span>
  <span class="tok-tag">[200]</span> <span class="tok-attr">js</span>       58 B  <span class="tok-com">/_bml/app.js</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverBmlExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#38bdf8"
        class="closing-mark"
      />
      <h2>BML ships <em>with Bosca</em></h2>
      <p class="section-sub">
        A BML site queries the same GraphQL API Studio writes to — Studio
        manages the content, BML renders it. The docs cover the language,
        the live-state model, and deployment end to end.
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
  max-width: 460px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

.hero-window {
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 10%, transparent);
}

/* The hero window's file tabs (prayer.bml / Prayers.kt). */
.code-tab {
  margin-left: 8px;
  padding: 4px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: none;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease;
}

.code-tab:hover {
  color: var(--fg-1);
}

.code-tab.active {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 35%, transparent);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
}

/* Right-aligned in-window callouts, anchored to a 1-based code line. */
.code-callout {
  position: absolute;
  top: calc(var(--code-pad-y) + (var(--line) - 1) * var(--code-lh) + 1px);
  right: 14px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  line-height: 1;
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 12%, var(--bg-0));
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: 999px;
  padding: 4px 9px;
  white-space: nowrap;
  opacity: 0;
  animation: discover-rise 0.5s cubic-bezier(0.2, 0.6, 0.2, 1) var(--callout-delay) forwards;
  pointer-events: none;
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

.actions {
  padding-top: 90px;
}

.start-steps {
  list-style: none;
  padding: 0;
  margin: 30px 0 0;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.start-steps li {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.step-num {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  margin-top: 1px;
}

.start-steps h3 {
  font-size: 14.5px;
  font-weight: 650;
  margin: 0 0 4px;
}

.start-steps p {
  font-size: 13px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
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
  .code-callout {
    animation: none;
    opacity: 1;
  }

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
  .code-callout {
    display: none;
  }

  .feature-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
