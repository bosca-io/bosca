<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Extending & automating — Beyond the request',
  description: 'Run durable background jobs, publish and subscribe to events, cache behind one interface, script the platform in Kotlin, and drive all of it from a single native CLI that embeds an MCP server for AI agents.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Developers', path: '/discover/developers' },
  { name: 'Extending & automating', path: '/discover/developers/extend' }
], '/og-developers.png')
</script>

<template>
  <DiscoverShell section-id="developers">
    <section class="page-hero">
      <p class="kicker load-1">
        Extending &amp; automating
      </p>
      <h1 class="load-2">
        Extend it, <em>automate it, script it</em>
      </h1>
      <p class="section-sub load-3">
        Reach past a single request. Run durable background jobs, publish and
        subscribe to events, cache what's hot, script the platform in Kotlin, and
        drive all of it from one native CLI.
      </p>
    </section>

    <!-- ── Jobs & events ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Jobs &amp; events
          </p>
          <h2>Fire work, <em>publish an event</em></h2>
          <p class="section-sub">
            A background job is a definition; an event ties jobs to a name.
            Calling <code>dispatch()</code> does both — it enqueues the work on a
            durable, at-least-once queue and publishes the event to whoever's
            subscribed. And because enqueueing waits for your transaction to
            commit, a job never runs for a change that rolled back.
          </p>
          <ul class="point-list">
            <li>Durable, at-least-once job queue — NATS or Redis.</li>
            <li>dispatch() enqueues jobs and publishes an event together.</li>
            <li>Subscribers receive events as a Kotlin Flow.</li>
            <li>Enqueue waits for the transaction to commit.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">TaskPublished.kt</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-ann">@JobEvent</span>(jobs = [ReindexTask::<span class="tok-kt">class</span>])
<span class="tok-kt">class</span> TaskPublished(<span class="tok-kt">val</span> id: UUID)

<span class="cmt">// one call: enqueue the job + publish</span>
<span class="tok-fn">TaskPublished</span>(id).<span class="tok-fn">dispatch</span>()</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Caching ─────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Caching
          </p>
          <h2>Cache behind <em>one interface</em></h2>
          <p class="section-sub">
            Reach the distributed cache through a single interface and let
            configuration choose the backend — Redis or a NATS key-value store.
            The same call works either way, so caching is a decision you make in
            config, not a dependency baked into your code.
          </p>
          <ul class="point-list">
            <li>One cache interface, Redis or NATS behind it.</li>
            <li>Chosen by configuration, not by code.</li>
            <li>The same call, whichever backend runs.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">cache</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-kt">val</span> cache = <span class="tok-fn">provide</span>&lt;ServiceCache&gt;()

<span class="tok-kt">val</span> feed = cache.<span class="tok-fn">get</span>(<span class="tok-str">"feed:home"</span>)
  ?: <span class="tok-fn">build</span>().<span class="tok-fn">also</span> { cache.<span class="tok-fn">put</span>(<span class="tok-str">"feed:home"</span>, it) }</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Scripting ───────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Scripting
          </p>
          <h2>Automate in <em>Kotlin</em></h2>
          <p class="section-sub">
            For logic that shouldn't ship inside the server, write a Bosca Script
            — real Kotlin, with typed access to the same services, running inside
            the platform. Trigger it by hand, on an event, or behind an HTTP
            endpoint, and version it in Git.
          </p>
          <ul class="point-list">
            <li>Real Kotlin, with typed access to platform services.</li>
            <li>Run by hand, on an event, or as an HTTP endpoint.</li>
            <li>Edited, versioned, and compiled inside the platform.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">on-publish.bosca.kts</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-kt">val</span> content = <span class="tok-fn">provide</span>&lt;MetadataService&gt;()
<span class="tok-kt">val</span> id = context.input.jsonObject[<span class="tok-str">"id"</span>]
log.<span class="tok-fn">info</span>(<span class="tok-str">"handling publish for $id"</span>)</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── CLI & MCP ───────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            The CLI
          </p>
          <h2>Drive it <em>from your terminal</em></h2>
          <p class="section-sub">
            The <code>bosca</code> CLI is a single native binary. It talks to the
            server over a typed GraphQL client generated from the same schema your
            code uses, and it embeds an MCP server — so the platform is scriptable
            from your shell and operable by your AI agents through the very same
            commands.
          </p>
          <ul class="point-list">
            <li>One native binary — <code>bosca</code>.</li>
            <li>A typed GraphQL client, generated from the schema.</li>
            <li>Embeds an MCP server for AI agents.</li>
          </ul>
        </div>
        <div class="term-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">bosca</span>
          </div>
          <div class="term-body">
            <div class="term-line">
              <span class="term-p">$</span> bosca login
            </div>
            <div class="term-line">
              <span class="term-p">$</span> bosca metadata list
            </div>
            <div class="term-line">
              <span class="term-p">$</span> bosca mcp-server <span class="cmt"># embedded MCP server</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverDevelopersExplore />
  </DiscoverShell>
</template>

<style scoped>
.code-window,
.term-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.code-body {
  padding: 18px 20px;
  overflow-x: auto;
}

.code-body pre {
  margin: 0;
}

.code-body code {
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.8;
  color: var(--fg-1);
  white-space: pre;
}

.cmt { color: var(--fg-3); }
.tok-ann { color: var(--accent); }
.tok-fn { color: #ffb547; }

/* ── Terminal window ─────────────────────────── */

.term-body {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.term-line {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-1);
}

.term-p {
  color: var(--accent);
  margin-right: 10px;
}
</style>
