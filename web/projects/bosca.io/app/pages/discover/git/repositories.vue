<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Git Repositories — Host, Browse, Search & Compare',
  description: 'Host standard Git repositories on object storage with visibility levels, merge strategies, and signed commits. Browse the tree, search content and paths, compare any two refs, fork, and archive.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Git', path: '/discover/git' },
  { name: 'Repositories', path: '/discover/git/repositories' }
], '/og-git.png')

const CONTENT_TYPES = [
  { name: 'General', note: 'any code' },
  { name: 'Script Project', note: 'platform scripts' },
  { name: 'Analytic Query Project', note: 'analytics SQL' },
  { name: 'Pipeline Project', note: 'pipelines' },
  { name: 'Agent Project', note: 'AI agents' },
  { name: 'Documentation', note: 'docs' }
]
</script>

<template>
  <DiscoverShell section-id="git">
    <section class="page-hero">
      <p class="kicker load-1">
        Repositories
      </p>
      <h1 class="load-2">
        Standard Git, <em>your storage</em>
      </h1>
      <p class="section-sub load-3">
        Push ordinary Git repositories to a server backed by S3-compatible
        object storage — so instances scale horizontally with no shared local
        disk. Browse, search, and compare code without leaving Studio.
      </p>
    </section>

    <!-- ── Repository config ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Configuration
          </p>
          <h2>A repo, <em>on your terms</em></h2>
          <p class="section-sub">
            Each repository carries the settings that shape how a team works in
            it — who can see it, how changes land, and how strict the history
            has to be.
          </p>
          <ul class="point-list">
            <li><strong>Visibility</strong> — public, internal, or private, defaulting to private.</li>
            <li><strong>Merge strategies</strong> — allow fast-forward, squash, rebase, or merge commits, and squash by default if you like.</li>
            <li><strong>Signed commits</strong> — require cryptographic signatures on every commit.</li>
            <li><strong>Delete branch on merge</strong> — auto-clean a source branch once its PR lands.</li>
            <li><strong>Fork &amp; archive</strong> — fork a repo into a linked copy of its parent, or archive it read-only, reversibly.</li>
          </ul>
        </div>
        <div class="config-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">repository settings</span>
          </div>
          <div class="config-body">
            <div class="cfg-row">
              <span class="cfg-key">Visibility</span>
              <span class="cfg-val cfg-pill">Private</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Default merge</span>
              <span class="cfg-val cfg-pill">Squash</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Signed commits</span>
              <span class="cfg-val cfg-on">required</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Delete branch on merge</span>
              <span class="cfg-val cfg-on">on</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Content types ───────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Content types
        </p>
        <h2>A repo that <em>knows what it holds</em></h2>
        <p class="section-sub">
          Every repository has a content type, and the type is how the rest of
          the platform attaches to it — a Script Project sources scripts, an
          Analytic Query Project sources queries, a Pipeline Project sources
          pipelines.
        </p>
      </div>
      <div class="ctype-grid reveal">
        <div
          v-for="ct in CONTENT_TYPES"
          :key="ct.name"
          class="ctype-card"
        >
          <h3>{{ ct.name }}</h3>
          <span class="ctype-note">{{ ct.note }}</span>
        </div>
      </div>
    </section>

    <!-- ── Search & compare ────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Search &amp; compare
          </p>
          <h2>Find it, <em>then diff it</em></h2>
          <p class="section-sub">
            Search inside file contents for a path, a line, and a snippet, or
            search file and directory names to jump straight to what you know
            by name. When you're ready, compare any two refs to see exactly
            what changed.
          </p>
          <ul class="point-list">
            <li>Content search returns the file, the line, and the surrounding snippet; a click jumps you to that line.</li>
            <li>Compare two refs for a stat line, per-file diffs with change-type badges, and the full commit list — the natural pre-PR preview.</li>
            <li>Searches and comparisons live at shareable URLs, so a link carries the exact view.</li>
          </ul>
        </div>
        <div class="diff-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">compare · main…feature</span>
          </div>
          <div class="diff-body">
            <div class="diff-stats">
              <span>4 files</span>
              <span class="diff-add">+128</span>
              <span class="diff-del">−36</span>
            </div>
            <div class="diff-file">
              <span class="diff-badge b-added">added</span>
              <code>server/livesessions/Map.kt</code>
            </div>
            <div class="diff-file">
              <span class="diff-badge b-modified">modified</span>
              <code>transform/Heartbeat.kt</code>
            </div>
            <div class="diff-lines">
              <span class="dl dl-ctx">  val geo = enrich(event)</span>
              <span class="dl dl-add">+ publish(liveSessions, geo)</span>
              <span class="dl dl-del">- // TODO: publish to map</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverGitExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Config window ───────────────────────────── */

.config-window,
.diff-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.config-body {
  padding: 10px 8px;
}

.cfg-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
}

.cfg-row + .cfg-row {
  border-top: 1px solid var(--line);
}

.cfg-key {
  font-size: 13px;
  color: var(--fg-2);
}

.cfg-val {
  font-family: var(--font-mono);
  font-size: 12px;
}

.cfg-pill {
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 10px;
}

.cfg-on {
  color: #34d99a;
}

/* ── Content type cards ──────────────────────── */

.ctype-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.ctype-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 16px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.ctype-card:hover {
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  transform: translateY(-2px);
}

.ctype-card h3 {
  font-size: 13.5px;
  font-weight: 650;
  margin: 0;
}

.ctype-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Diff window ─────────────────────────────── */

.diff-body {
  padding: 14px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.diff-stats {
  display: flex;
  gap: 14px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}

.diff-add { color: #34d99a; }
.diff-del { color: #f87171; }

.diff-file {
  display: flex;
  align-items: center;
  gap: 10px;
}

.diff-file code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.diff-badge {
  font-family: var(--font-mono);
  font-size: 9.5px;
  border-radius: 3px;
  padding: 1px 6px;
  border: 1px solid transparent;
}

.b-added { color: #34d99a; border-color: color-mix(in srgb, #34d99a 40%, transparent); }
.b-modified { color: #f6c453; border-color: color-mix(in srgb, #f6c453 40%, transparent); }

.diff-lines {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  overflow: hidden;
}

.dl {
  font-family: var(--font-mono);
  font-size: 11.5px;
  padding: 5px 12px;
  white-space: pre;
}

.dl-ctx { color: var(--fg-3); }
.dl-add { color: #34d99a; background: color-mix(in srgb, #34d99a 10%, transparent); }
.dl-del { color: #f87171; background: color-mix(in srgb, #f87171 10%, transparent); }

/* ── Responsive ──────────────────────────────── */

@media (max-width: 820px) {
  .ctype-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 480px) {
  .ctype-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
