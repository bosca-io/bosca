<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Releases & Milestones — Launch a release, ship every project',
  description: 'Release management in Bosca: a release gathers a version from every project, and launching it runs a pipeline that builds and deploys each one in the order you set — tracked per project, with rollback. Release notes draft from the tasks that shipped; milestones set dated targets across a program.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Work Ops', path: '/discover/workops' },
  { name: 'Releases & Milestones', path: '/discover/workops/releases' }
], '/og-workops.png')

const DEPLOY = [
  { order: 1, name: 'web', ver: 'v6.1', state: 'deployed', tone: 'ok' },
  { order: 2, name: 'server', ver: 'v6.1', state: 'deploying', tone: 'active' },
  { order: 3, name: 'runner', ver: 'v6.1', state: 'pending', tone: 'pending' }
]

const NOTES = [
  { kind: 'New', tone: 'ok', items: ['BOS-142 Add the live sessions map'] },
  { kind: 'Fixes', tone: 'neutral', items: ['BOS-149 Cluster nearby sessions'] },
  { kind: 'Breaking', tone: 'bad', items: ['BOS-151 Rename the events API'] }
]
</script>

<template>
  <DiscoverShell section-id="workops">
    <section class="page-hero">
      <p class="kicker load-1">
        Releases &amp; Milestones
      </p>
      <h1 class="load-2">
        Launch a release, <em>ship every project</em>
      </h1>
      <p class="section-sub load-3">
        Release management, built into the platform. A release gathers a version
        from every project — launch it, and a release pipeline builds and deploys
        each one in the order you set. Watch every project move from pending to
        deployed on the release, and roll back if you need to.
      </p>
    </section>

    <!-- ── Launch & execute (centerpiece) ──────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            One release, every project
          </p>
          <h2>Launch it, and the pipeline <em>runs the rollout</em></h2>
          <p class="section-sub">
            A release gathers a version from each project into one shipment.
            Launch it, and a release pipeline builds and deploys every project in
            the order you set — each one moving from pending, to deploying, to
            deployed, right on the release. If something goes wrong, roll back the
            git tags and published artifacts, then release again.
          </p>
          <ul class="point-list">
            <li>Bundle a version from each project into one release.</li>
            <li>Launching runs a pipeline that builds and deploys every project.</li>
            <li>Deploys go in the order you set, tracked per project as they run.</li>
            <li>Roll back the git tags and published artifacts, then re-release.</li>
          </ul>
        </div>
        <div class="rel-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">release · 6.1 · Platform</span>
            <span class="rel-run">running</span>
          </div>
          <div class="rel-body">
            <div
              v-for="d in DEPLOY"
              :key="d.name"
              class="rel-row"
            >
              <span class="rel-order">{{ d.order }}</span>
              <span class="rel-proj">{{ d.name }}</span>
              <span class="rel-ver">{{ d.ver }}</span>
              <span
                class="rel-state"
                :class="d.tone"
              >{{ d.state }}</span>
            </div>
            <div class="rel-foot">
              <Icon
                name="rocket"
                :size="12"
              />
              release pipeline · build &amp; deploy in order · rollback ready
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Versions ────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Versions
          </p>
          <h2>A version is <em>a project's release</em></h2>
          <p class="section-sub">
            Every project has its own versions. Mark the tasks a version fixes,
            and it becomes the record of what that release contains. When it's
            out the door, mark it released — that stamps its release date.
          </p>
          <ul class="point-list">
            <li>Tag each task with the version its fix ships in.</li>
            <li>A version stays unreleased until you mark it released.</li>
            <li>A bug can also record the versions it affects.</li>
          </ul>
        </div>
        <div class="ver-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">version · web 6.1</span>
          </div>
          <div class="ver-body">
            <div class="ver-row">
              <span class="ver-key">status</span>
              <span class="ver-val accent">unreleased</span>
            </div>
            <div class="ver-row">
              <span class="ver-key">release date</span>
              <span class="ver-val">Sep 30</span>
            </div>
            <div class="ver-row">
              <span class="ver-key">fixes</span>
              <span class="ver-val">18 tasks</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Release notes ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Release notes
          </p>
          <h2>Notes, drafted <em>from what shipped</em></h2>
          <p class="section-sub">
            Because you tagged each fix with its version, Bosca knows what
            shipped — so it drafts the release notes from those tasks and sorts
            them into new features, fixes, breaking changes, and more. Take the
            draft as-is, or edit it before it goes out.
          </p>
          <ul class="point-list">
            <li>Notes are drafted from the tasks fixed in the release.</li>
            <li>Bosca sorts entries into features, fixes, breaking changes, and more.</li>
            <li>Edit the draft, or ship it as written.</li>
          </ul>
        </div>
        <div class="notes-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">release notes · 6.1</span>
          </div>
          <div class="notes-body">
            <div
              v-for="n in NOTES"
              :key="n.kind"
              class="notes-group"
            >
              <span
                class="notes-kind"
                :class="n.tone"
              >{{ n.kind }}</span>
              <span
                v-for="i in n.items"
                :key="i"
                class="notes-item"
              >{{ i }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Milestones ──────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Milestones
        </p>
        <h2>The dates <em>everyone's aiming for</em></h2>
        <p class="section-sub">
          A milestone is a dated target for a whole program — the date the work
          across its projects is meant to hit. Tasks from any of those projects
          point at it, and it marks the roadmap. Close it when it's reached.
        </p>
      </div>
      <div class="mile-window reveal">
        <div class="mile-row">
          <span class="mile-icon"><Icon
            name="flag"
            :size="14"
          /></span>
          <span class="mile-name">Public beta</span>
          <span class="mile-date">Sep 30</span>
          <span class="mile-state open">open</span>
        </div>
        <div class="mile-row">
          <span class="mile-icon"><Icon
            name="flag"
            :size="14"
          /></span>
          <span class="mile-name">1.0 launch</span>
          <span class="mile-date">Dec 15</span>
          <span class="mile-state">open</span>
        </div>
      </div>
    </section>

    <DiscoverWorkopsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.ver-window,
.rel-window,
.notes-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Release window (centerpiece) ────────────── */

.rel-window {
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.rel-run {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: #e0a23a;
  border: 1px solid color-mix(in srgb, #e0a23a 40%, transparent);
  border-radius: 999px;
  padding: 1px 8px;
}

.rel-body {
  padding: 14px 12px;
}

.rel-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 12px;
}

.rel-row + .rel-row {
  border-top: 1px solid var(--line);
}

.rel-order {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  width: 10px;
  flex-shrink: 0;
}

.rel-proj {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-1);
}

.rel-ver {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
}

.rel-state {
  width: 82px;
  text-align: right;
  font-family: var(--font-mono);
  font-size: 10.5px;
}

.rel-state.ok { color: #34d99a; }
.rel-state.active { color: #e0a23a; }
.rel-state.pending { color: var(--fg-3); }

.rel-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  padding: 12px 12px 6px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}

/* ── Version window ──────────────────────────── */

.ver-body {
  padding: 12px 10px;
}

.ver-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 14px;
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.ver-row + .ver-row {
  border-top: 1px solid var(--line);
}

.ver-key { color: var(--fg-3); }
.ver-val { color: var(--fg-1); }
.ver-val.accent { color: var(--accent); }

/* ── Notes window ────────────────────────────── */

.notes-body {
  padding: 16px 14px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.notes-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.notes-kind {
  font-family: var(--font-mono);
  font-size: 10.5px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 2px;
}

.notes-kind.ok { color: #34d99a; }
.notes-kind.neutral { color: var(--accent); }
.notes-kind.bad { color: #f2757f; }

.notes-item {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
  padding-left: 10px;
}

/* ── Milestones ──────────────────────────────── */

.mile-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.mile-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
}

.mile-row + .mile-row {
  border-top: 1px solid var(--line);
}

.mile-icon {
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

.mile-name {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-1);
}

.mile-date {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}

.mile-state {
  width: 118px;
  text-align: right;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.mile-state.open {
  color: var(--accent);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 560px) {
  .mile-date {
    display: none;
  }
}
</style>
