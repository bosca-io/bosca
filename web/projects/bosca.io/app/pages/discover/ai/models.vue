<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Models — Bring your model, any provider',
  description: 'A Bosca model record names an LLM backend and holds the configuration the runtime needs to call it. Bosca AI runs on OpenAI and Google — provider-agnostic, with no provider lock-in — and the same models power AI features across the platform.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'AI', path: '/discover/ai' },
  { name: 'Models', path: '/discover/ai/models' }
], '/og-ai.png')

const MODEL = [
  { k: 'key', v: 'gpt-5' },
  { k: 'name', v: 'GPT-5' },
  { k: 'type', v: 'openai.chat.GPT5' },
  { k: 'config', v: '{ endpoint, apiKeyRef }' }
]

const PROVIDERS = ['OpenAI', 'Google']
</script>

<template>
  <DiscoverShell section-id="ai">
    <section class="page-hero">
      <p class="kicker load-1">
        Models
      </p>
      <h1 class="load-2">
        Bring your model, <em>any provider</em>
      </h1>
      <p class="section-sub load-3">
        A model record names an LLM backend and holds the configuration the
        runtime needs to call it. Bosca AI runs on OpenAI and Google —
        provider-agnostic, with no lock-in.
      </p>
    </section>

    <!-- ── The record ──────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The record
          </p>
          <h2>A name and <em>a config bag</em></h2>
          <p class="section-sub">
            A model is a thin record: a key, a name, a type, and a configuration
            object. The type names the backend, and the configuration holds
            whatever it takes to reach it — an endpoint, a key reference, default
            parameters. No provider list is baked in; the record is a label and a
            bag of settings.
          </p>
          <ul class="point-list">
            <li>A key and a name to reference the model by.</li>
            <li>A type that names the backend it talks to.</li>
            <li>A configuration object for endpoint, credentials, and defaults.</li>
          </ul>
        </div>
        <div class="model-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">model · gpt-5</span>
          </div>
          <div class="model-body">
            <div
              v-for="row in MODEL"
              :key="row.k"
              class="model-row"
            >
              <span class="model-k">{{ row.k }}</span>
              <span class="model-v">{{ row.v }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Providers ───────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Provider-agnostic
          </p>
          <h2>OpenAI <em>or Google</em></h2>
          <p class="section-sub">
            The runtime speaks to both out of the box — point a model at the
            provider you want and the executor handles the rest. Which backends
            and credentials are reachable is up to how your deployment is
            configured, not a fixed menu.
          </p>
          <ul class="point-list">
            <li>OpenAI and Google, wired and ready.</li>
            <li>Your deployment decides which backends and keys are reachable.</li>
            <li>No provider enum to work around — the type is yours to set.</li>
          </ul>
        </div>
        <div class="prov-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">providers</span>
          </div>
          <div class="prov-body">
            <div
              v-for="p in PROVIDERS"
              :key="p"
              class="prov-row"
            >
              <span class="prov-name">{{ p }}</span>
              <span class="prov-state"><Icon
                name="check-circle"
                :size="13"
              /> wired</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Powers the platform ─────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Across the platform
        </p>
        <h2>The same models, <em>everywhere</em></h2>
        <p class="section-sub">
          These records aren't just for chat. The models you configure power AI
          features throughout Bosca — the Kit assistant, image and audio
          generation, content descriptions, and more — all pointed at the
          providers you choose.
        </p>
      </div>
    </section>

    <DiscoverAiExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.model-window,
.prov-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Model window ────────────────────────────── */

.model-body {
  padding: 12px 10px;
}

.model-row {
  display: flex;
  align-items: baseline;
  gap: 14px;
  padding: 14px 12px;
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.model-row + .model-row {
  border-top: 1px solid var(--line);
}

.model-k {
  width: 64px;
  flex-shrink: 0;
  color: var(--fg-3);
}

.model-v { color: var(--fg-1); }

.model-row:first-child .model-v { color: var(--accent); }

/* ── Providers window ────────────────────────── */

.prov-body {
  padding: 12px 10px;
}

.prov-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 14px;
}

.prov-row + .prov-row {
  border-top: 1px solid var(--line);
}

.prov-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-1);
}

.prov-state {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: #34d99a;
}
</style>
