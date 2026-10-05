<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Script Library — Versioned, permissioned, Git-sourced',
  description: 'Every Bosca script is a governed record — a stable key, a type, a version that bumps on every edit, and group-based permissions. Attach a script to a file in a Git repo and a push keeps its source in sync.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Scripts', path: '/discover/scripts' },
  { name: 'Script Library', path: '/discover/scripts/library' }
], '/og-scripts.png')

const PERMS = [
  { name: 'View', body: 'See the script and its source.' },
  { name: 'Edit', body: 'Change the source and settings.' },
  { name: 'Execute', body: 'Run it, or call its endpoint.' },
  { name: 'Manage', body: 'Grant access and control the script.' }
]
</script>

<template>
  <DiscoverShell section-id="scripts">
    <section class="page-hero">
      <p class="kicker load-1">
        Script Library
      </p>
      <h1 class="load-2">
        Versioned <em>and governed</em>
      </h1>
      <p class="section-sub load-3">
        A script isn't a loose file on a server. It's a first-class record with
        a stable key, a version, and permissions — organized, controlled, and
        optionally kept in Git, like the rest of your code.
      </p>
    </section>

    <!-- ── The record ──────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            A first-class record
          </p>
          <h2>More than <em>a blob of source</em></h2>
          <p class="section-sub">
            Each script carries a stable key you reference it by, a type that
            decides how it runs, a source, a version, and flags for whether it's
            enabled and whether it's public. Its input and output can even be
            described up front.
          </p>
          <ul class="point-list">
            <li>A stable key names the script; the source can change beneath it.</li>
            <li>Enabled and public flags control whether and how it can run.</li>
            <li>Optional input and output schemas describe what it takes and returns.</li>
          </ul>
        </div>
        <div class="rec-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">script · welcome-email</span>
          </div>
          <div class="rec-body">
            <div class="rec-row">
              <span class="rec-key">type</span>
              <span class="rec-val accent">TRIGGER</span>
            </div>
            <div class="rec-row">
              <span class="rec-key">version</span>
              <span class="rec-val">7</span>
            </div>
            <div class="rec-row">
              <span class="rec-key">enabled</span>
              <span class="rec-val ok">true</span>
            </div>
            <div class="rec-row">
              <span class="rec-key">public</span>
              <span class="rec-val">false</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Versioning ──────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Versioning
          </p>
          <h2>Every edit <em>counts</em></h2>
          <p class="section-sub">
            Change a script's source and its version bumps. That version is what
            the compile cache keys on, so an edit quietly invalidates the old
            compiled copy and the next run picks up the new one — no stale
            bytecode lingering around.
          </p>
          <ul class="point-list">
            <li>The version increments on each change to the source.</li>
            <li>A new version invalidates the previous compiled result.</li>
            <li>The next run compiles fresh, then caches again.</li>
          </ul>
        </div>
        <div class="ver-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">versions</span>
          </div>
          <div class="ver-body">
            <div class="ver-row">
              <span class="ver-tag">v7</span>
              <span class="ver-note">current · cached</span>
            </div>
            <div class="ver-row muted">
              <span class="ver-tag">v6</span>
              <span class="ver-note">superseded</span>
            </div>
            <div class="ver-row muted">
              <span class="ver-tag">v5</span>
              <span class="ver-note">superseded</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Git source ──────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Sourced from Git
          </p>
          <h2>Keep scripts <em>in version control</em></h2>
          <p class="section-sub">
            A script can live in a repo. Attach it to a file in a script
            project, and a push updates its source automatically — the repo
            becomes the source of truth, and the next push overwrites whatever's
            in the platform.
          </p>
          <ul class="point-list">
            <li>Attach a script to a file in a Git script project.</li>
            <li>A push syncs the file's contents into the script.</li>
            <li>The repo wins — a push overwrites the in-platform source.</li>
          </ul>
        </div>
        <div class="git-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">git · ops-scripts</span>
          </div>
          <div class="git-body">
            <div class="git-node">
              <span class="git-icon"><Icon
                name="git-commit"
                :size="14"
              /></span>
              <span class="git-text"><code>scripts/welcome.bosca.kts</code></span>
            </div>
            <div class="git-arrow">
              <Icon
                name="chevron-down"
                :size="14"
              />
            </div>
            <div class="git-node accent">
              <span class="git-icon"><Icon
                name="check-circle"
                :size="14"
              /></span>
              <span class="git-text">source synced from Git</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Permissions ─────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Permissions
        </p>
        <h2>Who can <em>see, edit, and run</em></h2>
        <p class="section-sub">
          Scripts use the same group-based permissions as everything else on the
          platform, so access to a powerful script is as controlled as access to
          the data it touches.
        </p>
      </div>
      <div class="perm-grid reveal">
        <div
          v-for="p in PERMS"
          :key="p.name"
          class="perm-card"
        >
          <h3>{{ p.name }}</h3>
          <p>{{ p.body }}</p>
        </div>
      </div>
    </section>

    <DiscoverScriptsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.rec-window,
.ver-window,
.git-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Record window ───────────────────────────── */

.rec-body {
  padding: 12px 10px;
}

.rec-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 14px;
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.rec-row + .rec-row {
  border-top: 1px solid var(--line);
}

.rec-key { color: var(--fg-3); }
.rec-val { color: var(--fg-1); }
.rec-val.accent { color: var(--accent); }
.rec-val.ok { color: #34d99a; }

/* ── Version window ──────────────────────────── */

.ver-body {
  padding: 12px 10px;
}

.ver-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 14px;
}

.ver-row + .ver-row {
  border-top: 1px solid var(--line);
}

.ver-row.muted {
  opacity: 0.55;
}

.ver-tag {
  font-family: var(--font-mono);
  font-size: 12.5px;
  font-weight: 650;
  width: 34px;
  color: var(--accent);
}

.ver-row.muted .ver-tag {
  color: var(--fg-2);
}

.ver-note {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
}

/* ── Git window ──────────────────────────────── */

.git-body {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}

.git-node {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.git-node.accent {
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

.git-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.git-text {
  font-size: 13px;
  color: var(--fg-1);
}

.git-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

.git-arrow {
  color: var(--accent);
}

/* ── Permission cards ────────────────────────── */

.perm-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.perm-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.perm-card h3 {
  font-size: 15px;
  font-weight: 650;
  color: var(--accent);
  margin: 0 0 7px;
}

.perm-card p {
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--fg-2);
  margin: 0;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 760px) {
  .perm-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
