<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'API Endpoints — Publish a script as an HTTP endpoint',
  description: 'Expose a Bosca script at an HTTP URL — GET or POST, JSON in and out. Make it public or require an execute permission, and shape the request handling yourself, right in the script.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Scripts', path: '/discover/scripts' },
  { name: 'API Endpoints', path: '/discover/scripts/endpoints' }
], '/og-scripts.png')
</script>

<template>
  <DiscoverShell section-id="scripts">
    <section class="page-hero">
      <p class="kicker load-1">
        API Endpoints
      </p>
      <h1 class="load-2">
        A script, <em>behind a URL</em>
      </h1>
      <p class="section-sub load-3">
        Sometimes the thing you want is a small endpoint — take some JSON, do
        something, return some JSON. Publish a script at a URL and you have
        exactly that, without standing up a service for it.
      </p>
    </section>

    <!-- ── JSON in, JSON out ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            JSON in, JSON out
          </p>
          <h2>A URL that <em>runs your code</em></h2>
          <p class="section-sub">
            A script becomes an endpoint at a stable path. POST a JSON body or
            GET with query parameters — either way it lands on the script's
            input — and whatever the script returns comes back as the JSON
            response.
          </p>
          <ul class="point-list">
            <li>Reachable by GET or POST at a stable, key-based path.</li>
            <li>The request body or query becomes the script's input.</li>
            <li>The script's result is serialized straight to the response.</li>
          </ul>
        </div>
        <div class="ep-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">POST · lead-capture</span>
          </div>
          <div class="ep-body">
            <div class="ep-req">
              <span class="ep-verb">POST</span>
              <span class="ep-path">/api/v1/s/lead-capture</span>
            </div>
            <div class="ep-json">
              <pre><code><span class="tok-tag">{</span> <span class="tok-str">"email"</span><span class="tok-tag">:</span> <span class="tok-str">"a@example.com"</span> <span class="tok-tag">}</span></code></pre>
            </div>
            <div class="ep-arrow">
              <Icon
                name="chevron-down"
                :size="14"
              />
            </div>
            <div class="ep-res">
              <pre><code><span class="tok-tag">{</span> <span class="tok-str">"ok"</span><span class="tok-tag">:</span> <span class="tok-kt">true</span><span class="tok-tag">,</span> <span class="tok-str">"id"</span><span class="tok-tag">:</span> <span class="tok-str">"9f3c…"</span> <span class="tok-tag">}</span></code></pre>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Open or gated ───────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Open or gated
          </p>
          <h2>Public, <em>or permission-gated</em></h2>
          <p class="section-sub">
            Mark an endpoint public and anyone can call it — handy for a webhook
            receiver or a public form. Leave it private and a caller needs
            execute permission on the script, enforced before a line of it runs.
            Either way, a run is bounded by a timeout.
          </p>
          <ul class="point-list">
            <li>Public endpoints take no auth; private ones require execute permission.</li>
            <li>Permission is checked before the script executes.</li>
            <li>Each request runs under a hard timeout.</li>
          </ul>
        </div>
        <div class="gate-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">access</span>
          </div>
          <div class="gate-body">
            <div class="gate-row">
              <span class="gate-icon"><Icon
                name="globe"
                :size="14"
              /></span>
              <span class="gate-name">public</span>
              <span class="gate-note">anyone can call it</span>
            </div>
            <div class="gate-row">
              <span class="gate-icon"><Icon
                name="lock"
                :size="14"
              /></span>
              <span class="gate-name">private</span>
              <span class="gate-note">execute permission required</span>
            </div>
            <div class="gate-row">
              <span class="gate-icon"><Icon
                name="clock"
                :size="14"
              /></span>
              <span class="gate-name">timeout</span>
              <span class="gate-note">every request is bounded</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── You own the request ─────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            You own the request
          </p>
          <h2>Shape it <em>in the script</em></h2>
          <p class="section-sub">
            Because it's real code, the endpoint's behavior is yours. Validate
            the body, verify a webhook signature, branch on a header, decide what
            to return — it's all just Kotlin, right where the request arrives.
          </p>
          <ul class="point-list">
            <li>Validate and reject bad input yourself, with clear errors.</li>
            <li>Verify a signature or token when you're receiving a webhook.</li>
            <li>Return exactly the shape a caller expects.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">lead-capture.bosca.kts</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-kt">val</span> email = context.input.jsonObject[<span class="tok-str">"email"</span>]
<span class="tok-fn">require</span>(email != <span class="tok-kt">null</span>) <span class="tok-tag">{</span> <span class="tok-str">"email required"</span> <span class="tok-tag">}</span>

<span class="tok-kt">val</span> leads = <span class="tok-fn">provide</span><span class="tok-op">&lt;</span>MetadataService<span class="tok-op">&gt;</span>()
<span class="cmt">// …store the lead, return a result</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <DiscoverScriptsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.ep-window,
.gate-window,
.code-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Endpoint window ─────────────────────────── */

.ep-body {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}

.ep-req {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.ep-verb {
  font-family: var(--font-mono);
  font-size: 10.5px;
  font-weight: 700;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 4px;
  padding: 2px 7px;
}

.ep-path {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.ep-json,
.ep-res {
  width: 100%;
  padding: 12px 14px;
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.ep-res {
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

.ep-json pre,
.ep-res pre {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.ep-arrow {
  color: var(--accent);
}

/* ── Gate window ─────────────────────────────── */

.gate-body {
  padding: 12px 10px;
}

.gate-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 14px;
}

.gate-row + .gate-row {
  border-top: 1px solid var(--line);
}

.gate-icon {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.gate-name {
  width: 70px;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.gate-note {
  font-size: 12.5px;
  color: var(--fg-3);
}

/* ── Code window ─────────────────────────────── */

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
.tok-op { color: var(--fg-3); }
.tok-fn { color: var(--accent); }
</style>
