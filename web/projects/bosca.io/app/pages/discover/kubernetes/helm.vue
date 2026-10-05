<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Helm — Charts, releases, and a wizard that shows its work',
  description: 'Browse a live chart catalog from your own repositories, install through a wizard that runs a server-side dry run first and streams the release status as it comes up, and manage every release with its values, manifest, history, and notes.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Kubernetes', path: '/discover/kubernetes' },
  { name: 'Helm', path: '/discover/kubernetes/helm' }
], '/og-kubernetes.png')

const CHARTS = [
  { name: 'bosca-server', repo: 'bosca', version: '6.1.0' },
  { name: 'bosca-runner', repo: 'bosca', version: '6.1.0' },
  { name: 'cert-manager', repo: 'internal', version: '1.16.1' }
]

const STEPS = [
  { label: 'Namespace', note: 'pick or create' },
  { label: 'Values', note: 'chart defaults, editable' },
  { label: 'Dry run', note: 'checked server-side' },
  { label: 'Install', note: 'status streams in' }
]

const TABS = ['Overview', 'Values', 'Manifest', 'History', 'Notes']

const REPOS = [
  { name: 'bosca', kind: 'OCI' },
  { name: 'internal', kind: 'OCI' },
  { name: 'community', kind: 'HTTP' }
]
</script>

<template>
  <DiscoverShell section-id="kubernetes">
    <section class="page-hero">
      <p class="kicker load-1">
        Helm
      </p>
      <h1 class="load-2">
        Charts, releases, <em>and no guesswork</em>
      </h1>
      <p class="section-sub load-3">
        A live catalog of the charts in your own repositories, an install that
        checks itself before it runs, and a release view that keeps every value,
        manifest, and revision where you can read it.
      </p>
    </section>

    <!-- ── Catalog ─────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The catalog
          </p>
          <h2>Your repositories, <em>browsable</em></h2>
          <p class="section-sub">
            The catalog reads from the repositories you've added, so what you see
            is what your cluster can actually pull. Search across them or narrow to
            one, then open a chart to find every version it offers and the default
            values it ships with — the same values you'll be editing a moment
            later.
          </p>
          <ul class="point-list">
            <li>Charts fetched live from your own repositories.</li>
            <li>Search across every repo, or filter to one.</li>
            <li>Each chart lists all its versions.</li>
            <li>Default values shown before you install.</li>
          </ul>
        </div>
        <div class="k-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">catalog</span>
          </div>
          <div class="k-body">
            <div
              v-for="c in CHARTS"
              :key="c.name"
              class="k-row"
            >
              <span class="k-name">{{ c.name }}</span>
              <span class="k-repo">{{ c.repo }}</span>
              <span class="k-ver">{{ c.version }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Install wizard ──────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Installing
          </p>
          <h2>It checks itself <em>before it runs</em></h2>
          <p class="section-sub">
            Choose a namespace or create one, edit the chart's values, and run a
            dry run — the cluster resolves the whole release and reports back
            without applying anything. When you do install, the console is already
            listening: the status arrives from the first event onward, moving to
            deployed while you watch.
          </p>
          <ul class="point-list">
            <li>Pick an existing namespace or create one on the way through.</li>
            <li>Values start from the chart's defaults and stay editable.</li>
            <li>A server-side dry run before anything is applied.</li>
            <li>Release status streams in from the first event.</li>
          </ul>
        </div>
        <div class="k-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">install</span>
            <span class="k-live">deployed</span>
          </div>
          <div class="k-body">
            <div
              v-for="(s, i) in STEPS"
              :key="s.label"
              class="step-row"
            >
              <span
                class="step-dot"
                :class="{ done: i < STEPS.length - 1 }"
              />
              <span class="step-label">{{ s.label }}</span>
              <span class="step-note">{{ s.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Releases ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Releases
          </p>
          <h2>Everything a release <em>is made of</em></h2>
          <p class="section-sub">
            Every release in the cluster, filterable by namespace and status. Open
            one and it's all there: the values it was given, the manifest it
            produced, the revisions it has been through, and the notes the chart
            printed on install. Upgrade it to a new version, or move it back to an
            earlier revision, from the same place.
          </p>
          <ul class="point-list">
            <li>Filter releases by name, namespace, or status.</li>
            <li>Values, manifest, history, and notes on every release.</li>
            <li>Upgrade to another version, or return to an earlier revision.</li>
          </ul>
        </div>
        <div class="k-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">release · bosca-server</span>
          </div>
          <div class="tab-strip">
            <span
              v-for="(t, i) in TABS"
              :key="t"
              class="tab"
              :class="{ active: i === 0 }"
            >{{ t }}</span>
          </div>
          <div class="k-body">
            <div class="kv-row">
              <span class="kv-k">namespace</span><span class="kv-v">bosca</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">revision</span><span class="kv-v">4</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">status</span><span class="kv-v ok">deployed</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Repositories ────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Repositories
        </p>
        <h2>Where the charts <em>come from</em></h2>
        <p class="section-sub">
          Add the repositories your team publishes to and pulls from — OCI
          registries and classic HTTP repos alike. Credentials for private
          repositories are stored encrypted, and a refresh pulls the latest index
          whenever you need the newest versions.
        </p>
      </div>
      <div class="repo-window reveal">
        <div
          v-for="r in REPOS"
          :key="r.name"
          class="repo-row"
        >
          <span class="repo-icon"><Icon
            name="package"
            :size="13"
          /></span>
          <span class="repo-name">{{ r.name }}</span>
          <span class="repo-kind">{{ r.kind }}</span>
        </div>
      </div>
    </section>

    <DiscoverKubernetesExplore />
  </DiscoverShell>
</template>

<style scoped>
.k-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.k-live {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: #34d99a;
  border: 1px solid color-mix(in srgb, #34d99a 40%, transparent);
  border-radius: 999px;
  padding: 1px 8px;
}

.k-body {
  padding: 12px 10px;
}

.k-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
}

.k-row + .k-row {
  border-top: 1px solid var(--line);
}

.k-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.k-repo {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
}

.k-ver {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  width: 56px;
  text-align: right;
}

/* ── Install steps ───────────────────────────── */

.step-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
}

.step-row + .step-row {
  border-top: 1px solid var(--line);
}

.step-dot {
  width: 9px;
  height: 9px;
  flex-shrink: 0;
  border-radius: 999px;
  border: 1px solid var(--accent);
}

.step-dot.done {
  background: var(--accent);
  box-shadow: 0 0 8px var(--accent);
}

.step-label {
  flex: 1;
  font-size: 12.5px;
  color: var(--fg-1);
}

.step-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Release tabs ────────────────────────────── */

.tab-strip {
  display: flex;
  gap: 4px;
  padding: 10px 12px 0;
  border-bottom: 1px solid var(--line);
  overflow-x: auto;
}

.tab {
  font-size: 11.5px;
  color: var(--fg-3);
  padding: 6px 10px;
  border-bottom: 2px solid transparent;
  white-space: nowrap;
}

.tab.active {
  color: var(--accent);
  border-bottom-color: var(--accent);
}

.kv-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 12px;
}

.kv-row + .kv-row {
  border-top: 1px solid var(--line);
}

.kv-k {
  width: 92px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.kv-v {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.kv-v.ok { color: #34d99a; }

/* ── Repositories ────────────────────────────── */

.repo-window {
  max-width: 520px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.repo-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 15px 18px;
}

.repo-row + .repo-row {
  border-top: 1px solid var(--line);
}

.repo-icon {
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.repo-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.repo-kind {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
}
</style>
