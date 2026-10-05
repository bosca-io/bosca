<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Prompts — Named, and versioned in Git',
  description: 'A Bosca prompt names a system and user prompt pair with optional input and output labels and a JSON schema, ready to attach to any agent by key and versioned alongside your code in Git.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'AI', path: '/discover/ai' },
  { name: 'Prompts', path: '/discover/ai/prompts' }
], '/og-ai.png')
</script>

<template>
  <DiscoverShell section-id="ai">
    <section class="page-hero">
      <p class="kicker load-1">
        Prompts
      </p>
      <h1 class="load-2">
        A prompt, named <em>and versioned</em>
      </h1>
      <p class="section-sub load-3">
        A prompt is a system and user prompt pair kept as its own record — under
        a key, with optional labels and a schema — ready to attach to any agent
        and version alongside your code.
      </p>
    </section>

    <!-- ── System + user ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The pair
          </p>
          <h2>System and user, <em>together</em></h2>
          <p class="section-sub">
            One record holds both halves of a prompt: the system prompt that sets
            the model's role, and the user prompt it works from. Give it a key
            and a name, and it's ready to attach to an agent.
          </p>
          <ul class="point-list">
            <li>A system prompt and a user prompt in one named record.</li>
            <li>A unique key to reference it by.</li>
            <li>Attach it to any agent that should follow it.</li>
          </ul>
        </div>
        <div class="prompt-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">prompt · summarize-doc</span>
          </div>
          <div class="prompt-body">
            <div class="prompt-block">
              <span class="prompt-label">system</span>
              <p>You are a precise technical writer. Keep it plain.</p>
            </div>
            <div class="prompt-block">
              <span class="prompt-label">user</span>
              <p>Summarize the document in three bullet points.</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Labels & schema ─────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Labels and schema
          </p>
          <h2>An optional <em>shape</em></h2>
          <p class="section-sub">
            Tag a prompt with input and output labels to keep your library
            organized — they're plain text, yours to use as you like. And when a
            prompt needs a structured contract, attach a JSON schema for the input
            or output an agent should produce.
          </p>
          <ul class="point-list">
            <li>Input and output labels — free text, for your own organizing.</li>
            <li>An optional JSON schema for a structured input or output.</li>
            <li>Consumed by agents that ask for a structured contract.</li>
          </ul>
        </div>
        <div class="schema-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">schema.json</span>
          </div>
          <div class="schema-body">
            <pre><code>{
  "type": "object",
  "properties": {
    "summary": { "type": "string" },
    "topics":  { "type": "array" }
  }
}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Versioned ───────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          In Git
        </p>
        <h2>Kept beside <em>your code</em></h2>
        <p class="section-sub">
          Prompts live in Git, so a change to how your AI behaves is a change you
          can see, review, and roll back — the same way you handle everything else
          in the repository.
        </p>
      </div>
    </section>

    <DiscoverAiExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.prompt-window,
.schema-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Prompt window ───────────────────────────── */

.prompt-body {
  padding: 16px 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.prompt-block {
  padding: 13px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 50%, transparent);
}

.prompt-label {
  font-family: var(--font-mono);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--accent);
}

.prompt-block p {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--fg-1);
}

/* ── Schema window ───────────────────────────── */

.schema-body {
  padding: 16px 18px;
  overflow-x: auto;
}

.schema-body pre {
  margin: 0;
}

.schema-body code {
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.7;
  color: var(--fg-2);
  white-space: pre;
}
</style>
