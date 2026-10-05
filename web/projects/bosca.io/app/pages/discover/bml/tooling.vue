<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'BML Tooling — Compiler, IDE Plugin & Deployment',
  description: 'The BML toolchain: a Kotlin K2 compiler plugin that produces typed pages, an IntelliJ plugin for .bml editing, in-process application hot-swap with last-good fallback, and one-process deployment.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'BML', path: '/discover/bml' },
  { name: 'Tooling & deployment', path: '/discover/bml/tooling' }
], '/og-bml.png')
</script>

<template>
  <DiscoverShell section-id="bml">
    <section class="page-hero">
      <p class="kicker load-1">
        Tooling
      </p>
      <h1 class="load-2">
        From compiler <em>to production</em>
      </h1>
      <p class="section-sub load-3">
        BML is compiled, not interpreted. A Kotlin K2 compiler plugin produces
        typed page objects, the IDE understands your files, the dev loop
        reloads the browser for you, and a finished site deploys as one
        server process.
      </p>
    </section>

    <!-- ── The compiler ────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The compiler
          </p>
          <h2>Pages are <em>typed objects</em></h2>
          <p class="section-sub">
            There are no hand-written page classes. The build parses every
            <code>.bml</code> file and generates a Kotlin render object per
            page, which the Kotlin compiler compiles alongside the rest of the
            project. Embedded server Kotlin lowers into the generated code
            with source maps back to the <code>.bml</code> file, so
            diagnostics point at the line you wrote.
          </p>
          <ul class="point-list">
            <li>Client <code>&lt;script client&gt;</code> blocks compile to TypeScript and bundle into browser ES modules.</li>
            <li>The IntelliJ plugin adds file-type support, syntax highlighting, comment toggling, and language injection — Kotlin inside server scripts, interpolation, and bound attributes; TypeScript inside client scripts.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">generated page object</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-com">// Generated in memory; optionally mirrored under build/generated/bml/kotlin</span>
<span class="tok-attr">@BmlPage</span>(route = <span class="tok-str">"/prayers/{id}"</span>)
<span class="tok-tag">object</span> <span class="tok-kt">PrayerPage</span> : BmlPageRenderer {
    <span class="tok-tag">override suspend fun</span> <span class="tok-kt">render</span>(ctx: RenderContext) {
        <span class="tok-com">// compiled from prayer.bml</span>
    }
}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── The dev loop ────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            The dev loop
          </p>
          <h2>Save the file. <em>The browser follows.</em></h2>
          <p class="section-sub">
            <code>bosca bml dev</code> watches application sources, rebuilds the
            affected output, and swaps the new application generation into the
            running server. The process, port, and active sessions stay put,
            while the server pushes a live-reload event so the browser follows
            automatically.
          </p>
          <p class="section-sub">
            If a build fails, the last good generation keeps serving while the
            diagnostics point back to the source. Framework or dependency
            changes still ask for a deliberate restart; ordinary application
            work stays in the fast loop.
          </p>
          <p class="section-sub">
            The GraphQL endpoint is configuration, so local development can
            point at a remote Bosca for real data and the same code runs
            unchanged once deployed.
          </p>
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

<span class="tok-com"># rebuild, hot-swap, live-reload</span>
<span class="tok-kt">bosca bml dev</span> my-site</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Deployment ──────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Deployment
          </p>
          <h2>A site is <em>one process</em></h2>
          <p class="section-sub">
            Hand the generated registries to <code>BmlServer</code> and start
            it. The server registers each page's route, serves static assets,
            and hosts the action, contract, and GraphQL proxy endpoints —
            there is nothing else to run.
          </p>
          <ul class="point-list">
            <li>In production a page downloads at most one merged stylesheet and one merged bundle on top of the global tier — what every page shares is normalized into the global file. Dev serves per-component files so hot reload invalidates one small file at a time.</li>
            <li>Served CSS/JS carries an ETag; repeat loads answer with a body-less 304.</li>
            <li>Files under the public directory can opt into a long-lived Cache-Control policy.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">Main.kt — wiring a compiled site</span>
          </div>
          <div class="code-body">
            <pre><code>BmlServer(
    pages = bml.generated.BmlPages.all,
    components = bml.generated.BmlComponents.all,
    graphqlEndpoint = System.getenv(<span class="tok-str">"BML_GRAPHQL_ENDPOINT"</span>),
    publicDir = File(<span class="tok-str">"public"</span>),
    globalCss = File(<span class="tok-str">"app.css"</span>).readText(),
    port = <span class="tok-kt">9092</span>,
).start()</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Measurement ─────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Measurement
          </p>
          <h2>Page weight is <em>a build output</em></h2>
          <p class="section-sub">
            The compiler knows exactly which assets each page links — its
            island bundle, the CSS chunks of the components it renders, the
            global tier — so the build can price every page. Running
            <code>bmlMetrics</code> prints each page's first-load payload in
            raw and gzip bytes and writes a JSON report, so size changes are
            visible build over build.
          </p>
          <p class="section-sub">
            For a running site there's <code>bosca bml audit</code>: it fetches
            a route, discovers the assets the page actually declares, and
            records real transfer bytes and timing per request. It works
            against any HTTP site — not just BML — so you can measure a BML
            deployment against the same pages on another stack.
          </p>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">./gradlew bmlMetrics — the sample site</span>
          </div>
          <div class="code-body">
            <pre><code>BML site metrics — first-load payload per page (raw, gzip)

Page           Route             JS               CSS            First load
-------------  ----------------  ---------------  -------------  ----------------
HomePage       /                 3.4 KB <span class="tok-str">(1.6 KB)</span>  564 B <span class="tok-str">(291 B)</span>  3.9 KB <span class="tok-str">(1.9 KB)</span>
CartPage       /cart             3.3 KB <span class="tok-str">(1.6 KB)</span>  55 B <span class="tok-str">(72 B)</span>    3.4 KB <span class="tok-str">(1.6 KB)</span>
DashboardPage  /dashboard        -                -              -
ScenarioPage   /scenario/{mode}  -                508 B <span class="tok-str">(269 B)</span>  508 B <span class="tok-str">(269 B)</span>

<span class="tok-com">JSON report: build/reports/bml/site-metrics.json</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <DiscoverBmlExplore />
  </DiscoverShell>
</template>
