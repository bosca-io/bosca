<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Artifacts Integration — CI Gates, Events & ML Serving',
  description: 'Every published version fires a platform event. CI jobs declare artifact requirements and dispatch the moment the registry publishes them; recommendation models publish and serve from the same registry; Studio and a GraphQL API manage it all.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Artifacts', path: '/discover/artifacts' },
  { name: 'Integration', path: '/discover/artifacts/integration' }
], '/og-artifacts.png')
</script>

<template>
  <DiscoverShell section-id="artifacts">
    <section class="page-hero">
      <p class="kicker load-1">
        Platform integration
      </p>
      <h1 class="load-2">
        The registry <em>your releases wait on</em>
      </h1>
      <p class="section-sub load-3">
        Because the registry is part of the platform, a published version
        isn't a file on a shelf — it's a signal. CI gates open on it, events
        carry it, and the models behind your recommendations ship through it.
      </p>
    </section>

    <!-- ── CI gates ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            CI requirement gates
          </p>
          <h2>Builds that wait for <em>the right artifact</em></h2>
          <p class="section-sub">
            A CI job can require an artifact — a Maven coordinate at an exact
            version — and it won't dispatch until the registry has it.
          </p>
          <ul class="point-list">
            <li>The run shows <em>waiting on artifacts</em> until the requirement is met, then dispatches — no polling loops, no retry scripts.</li>
            <li>Publish events re-evaluate waiting jobs the moment a version lands.</li>
            <li>A <code>setup-registry</code> step points the job's build tools at a registry repository, so publishing from CI is an ordinary <code>gradle publish</code>.</li>
            <li>That's how cross-repo releases order themselves: downstream builds gate on the upstream artifacts they compile against.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">release.yaml</span>
          </div>
          <div class="code-body">
            <!-- v-pre: the sample contains literal `${{ }}` pipeline interpolation -->
            <pre v-pre><span class="tok-attr">jobs</span>:
  <span class="tok-attr">build-and-publish</span>:
    <span class="tok-attr">runner</span>: linux
    <span class="tok-attr">requires</span>:
      - <span class="tok-attr">type</span>: <span class="tok-str">maven</span>
        <span class="tok-attr">namespace</span>: <span class="tok-str">bosca-maven</span>
        <span class="tok-attr">coordinate</span>: <span class="tok-str">"io.bosca:core:<span class="tok-interp">${{ version }}</span>"</span>
        <span class="tok-attr">timeout</span>: <span class="tok-str">60m</span>
    <span class="tok-attr">steps</span>:
      - <span class="tok-attr">uses</span>: <span class="tok-str">setup-registry</span>
        <span class="tok-attr">with</span>:
          <span class="tok-attr">repository</span>: <span class="tok-str">bosca-maven</span></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Publish events ──────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Publish events
          </p>
          <h2>Publishes are <em>platform events</em></h2>
          <p class="section-sub">
            Every publish is announced on the platform's pub/sub — the same
            signal the CI requirement gate listens for, available to any
            service that subscribes.
          </p>
          <ul class="point-list">
            <li>Every published version fires <code>bosca.artifacts.version.published</code> with the namespace, repository, type, and version.</li>
            <li>Docker fires on tag updates too — re-pointing <code>latest</code> is a publish.</li>
            <li>One event shape across all six formats, serialized like every other platform event.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">bosca.artifacts.version.published</span>
          </div>
          <div class="code-body">
            <pre>{
  <span class="tok-attr">"namespace"</span>: <span class="tok-str">"library"</span>,
  <span class="tok-attr">"repository"</span>: <span class="tok-str">"api"</span>,
  <span class="tok-attr">"type"</span>: <span class="tok-str">"docker"</span>,
  <span class="tok-attr">"version"</span>: <span class="tok-str">"sha256:9f86d081…"</span>
}</pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Models & management ─────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Models &amp; management
          </p>
          <h2>Models ship like <em>any other artifact</em></h2>
          <p class="section-sub">
            The registry isn't only for code you deploy — it's where the
            platform keeps the ML models it serves, and it's managed with the
            same tools as everything else.
          </p>
          <ul class="point-list">
            <li>Recommendation models are versioned archives in an <code>ml</code> repository — published after training, pulled for serving, from the same registry as your images and jars.</li>
            <li>A dedicated service principal with narrowly-scoped push and pull tokens is provisioned automatically on first boot.</li>
            <li>Studio manages the registry end to end: namespaces, repositories, versions, tags, and permission grants.</li>
            <li>A GraphQL admin API covers the same ground for automation — create namespaces, add repositories, re-point tags, grant access.</li>
          </ul>
        </div>
        <div class="integ-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">connected</span>
          </div>
          <div class="integ-body">
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="brain"
                :size="15"
              /></span>
              <span class="integ-text">Model archive pulled from <code>model/…</code> for serving</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="key"
                :size="15"
              /></span>
              <span class="integ-text"><code>ml-service</code> principal · push + pull tokens</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="monitor"
                :size="15"
              /></span>
              <span class="integ-text">Namespaces &amp; grants managed in Studio</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="braces"
                :size="15"
              /></span>
              <span class="integ-text">GraphQL · <code>createRepository(namespaceId, …)</code></span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverArtifactsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Integration window ──────────────────────── */

.integ-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.integ-body {
  padding: 10px 8px;
}

.integ-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
}

.integ-row + .integ-row {
  border-top: 1px solid var(--line);
}

.integ-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #f0d5f5;
}

.integ-text {
  font-size: 13px;
  color: var(--fg-1);
}

.integ-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}
</style>
