<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Authoring — Write Kotlin with typed platform access',
  description: 'A Bosca script is real Kotlin with a context — its input, the caller\'s identity, a coroutine scope, and a logger — and typed, dependency-injected access to platform services. Source is validated and compiled before it ever runs.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Scripts', path: '/discover/scripts' },
  { name: 'Authoring', path: '/discover/scripts/authoring' }
], '/og-scripts.png')

const CONTEXT = [
  { icon: 'braces', name: 'input', body: 'The typed JSON payload the script was called with.' },
  { icon: 'key-round', name: 'authentication', body: 'The caller\'s identity and permissions.' },
  { icon: 'activity', name: 'scope', body: 'A coroutine scope for async work.' },
  { icon: 'file-text', name: 'log', body: 'A structured logger for output.' }
]
</script>

<template>
  <DiscoverShell section-id="scripts">
    <section class="page-hero">
      <p class="kicker load-1">
        Authoring
      </p>
      <h1 class="load-2">
        Write Kotlin, <em>not a DSL</em>
      </h1>
      <p class="section-sub load-3">
        A script isn't a string of expressions in a box — it's real Kotlin, with
        a context handed to it and the platform's own services a call away. If
        you can write it in Kotlin, you can run it here.
      </p>
    </section>

    <!-- ── The context ─────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The context
          </p>
          <h2>Everything a run <em>needs, handed to it</em></h2>
          <p class="section-sub">
            When a script runs, it's given a context: the input it was called
            with, who called it, a scope to launch async work in, and a logger.
            No global reaching around — what a run needs is right there.
          </p>
          <ul class="point-list">
            <li>Read the call's input as typed JSON.</li>
            <li>Act as the caller, with exactly their permissions.</li>
            <li>Launch coroutines on the provided scope, and log as you go.</li>
          </ul>
        </div>
        <div class="ctx-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">context</span>
          </div>
          <div class="ctx-body">
            <div
              v-for="c in CONTEXT"
              :key="c.name"
              class="ctx-row"
            >
              <span class="ctx-icon"><Icon
                :name="c.icon"
                :size="14"
              /></span>
              <span class="ctx-name">{{ c.name }}</span>
              <span class="ctx-note">{{ c.body }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Typed service access ────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Typed service access
          </p>
          <h2>The platform's services, <em>one call away</em></h2>
          <p class="section-sub">
            Scripts reach platform services the same way the platform reaches
            them itself — asked for by type. Content, collections, metadata, and
            search are there to call directly, fully typed, no HTTP hop in
            between.
          </p>
          <ul class="point-list">
            <li>Ask for a service by its type and get a real, typed instance.</li>
            <li>Content, collection, metadata, and search services come pre-imported.</li>
            <li>It's an in-process call, not a round-trip through the API.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">services</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-kt">val</span> content = <span class="tok-fn">provide</span><span class="tok-op">&lt;</span>MetadataService<span class="tok-op">&gt;</span>()
<span class="tok-kt">val</span> collections = <span class="tok-fn">provide</span><span class="tok-op">&lt;</span>CollectionService<span class="tok-op">&gt;</span>()

<span class="tok-kt">val</span> item = content.<span class="tok-fn">getById</span>(id)
log.<span class="tok-fn">info</span>(<span class="tok-str">"loaded ${item.name}"</span>)</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Validated & compiled ────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Validated &amp; compiled
          </p>
          <h2>Checked before <em>it ever runs</em></h2>
          <p class="section-sub">
            Nothing runs unseen. The source is validated for dangerous calls,
            then compiled to bytecode — any error shows up now, not in
            production. The compiled result is cached, so the second run doesn't
            pay the compile cost again.
          </p>
          <ul class="point-list">
            <li>Source validation rejects unsafe system calls up front.</li>
            <li>Compilation surfaces errors before the script is used.</li>
            <li>Compiled scripts are cached in memory and the database.</li>
          </ul>
        </div>
        <div class="val-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">validate</span>
          </div>
          <div class="val-body">
            <div class="val-row ok">
              <Icon
                name="check-circle"
                :size="14"
              />
              source check · no blocked calls
            </div>
            <div class="val-row ok">
              <Icon
                name="check-circle"
                :size="14"
              />
              compiles to bytecode
            </div>
            <div class="val-out">
              <Icon
                name="layers"
                :size="13"
              />
              cached · next run is instant
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverScriptsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.ctx-window,
.code-window,
.val-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Context window ──────────────────────────── */

.ctx-body {
  padding: 12px 10px;
}

.ctx-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.ctx-row + .ctx-row {
  border-top: 1px solid var(--line);
}

.ctx-icon {
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

.ctx-name {
  width: 116px;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.ctx-note {
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

.tok-op { color: var(--fg-3); }
.tok-fn { color: var(--accent); }

/* ── Validate window ─────────────────────────── */

.val-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.val-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 15px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.val-row.ok svg {
  color: #34d99a;
  flex-shrink: 0;
}

.val-out {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
  padding: 12px 15px;
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 11.5px;
}
</style>
