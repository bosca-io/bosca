<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Storage & Data — Where it all lives',
  description: 'Bosca stores files in object storage behind one interface — S3, Google Cloud Storage, or the filesystem, anything S3-compatible — with signed URLs. Back up Postgres and your files to one archive and restore with a conflict strategy. Postgres, NATS, and Meilisearch underneath, abstracted, not hardwired.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'System', path: '/discover/system' },
  { name: 'Storage & Data', path: '/discover/system/storage' }
], '/og-system.png')

const BACKENDS = [
  { name: 'S3', note: 'AWS · signed URLs', icon: 'server' },
  { name: 'Google Cloud Storage', note: 'GCS', icon: 'server' },
  { name: 'Filesystem', note: 'dev · local', icon: 'folder' }
]

const BACKUP = [
  { k: 'snapshot', v: 'all tables · consistent' },
  { k: 'files', v: 'object storage included' },
  { k: 'archive', v: 'one .zip archive' },
  { k: 'restore', v: 'skip · overwrite · fail' }
]

const SYSTEMS = [
  { name: 'content-index', note: 'full-text + vector' },
  { name: 'collections-index', note: 'full-text' },
  { name: 'media-index', note: 'full-text' }
]
</script>

<template>
  <DiscoverShell section-id="system">
    <section class="page-hero">
      <p class="kicker load-1">
        Storage &amp; Data
      </p>
      <h1 class="load-2">
        Where it <em>all lives</em>
      </h1>
      <p class="section-sub load-3">
        Files in object storage behind one interface, whole-platform backups you
        can restore, and the services it runs on — pluggable where it counts.
      </p>
    </section>

    <!-- ── Object storage ──────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Object storage
          </p>
          <h2>Your files, <em>one interface</em></h2>
          <p class="section-sub">
            Files live in object storage behind a single interface. Point it at
            S3, Google Cloud Storage, or the local filesystem — or anything
            S3-compatible. Uploads and downloads go through signed URLs with an
            expiry, so a link works for exactly as long as you allow.
          </p>
          <ul class="point-list">
            <li>S3, Google Cloud Storage, or the local filesystem.</li>
            <li>Anything S3-compatible works, too.</li>
            <li>Signed upload and download URLs, with an expiry.</li>
            <li>One interface, whichever backend you run.</li>
          </ul>
        </div>
        <div class="back-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">object storage</span>
          </div>
          <div class="back-body">
            <div
              v-for="b in BACKENDS"
              :key="b.name"
              class="back-row"
            >
              <span class="back-icon"><Icon
                :name="b.icon"
                :size="14"
              /></span>
              <span class="back-name">{{ b.name }}</span>
              <span class="back-note">{{ b.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Backups ─────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Backups &amp; restore
          </p>
          <h2>Snapshot it all, <em>put it back</em></h2>
          <p class="section-sub">
            Back up the whole platform to one archive — every Postgres table under
            a consistent snapshot, plus your object-storage files when you want
            them — and get a downloadable ZIP. Restoring reads it back, and you
            choose how conflicts resolve: skip what's there, overwrite it, or stop
            at the first clash. Kick one off whenever you need it.
          </p>
          <ul class="point-list">
            <li>One archive: a consistent Postgres snapshot plus your files.</li>
            <li>Restore with a conflict strategy — skip, overwrite, or fail.</li>
            <li>Create a backup on demand, whenever you need one.</li>
            <li>The archive is stored back and stays downloadable.</li>
          </ul>
        </div>
        <div class="rec-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">backup · 6f2a…</span>
          </div>
          <div class="rec-body">
            <div
              v-for="row in BACKUP"
              :key="row.k"
              class="rec-row"
            >
              <span class="rec-k">{{ row.k }}</span>
              <span class="rec-v">{{ row.v }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Search & indexes ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Search &amp; indexes
          </p>
          <h2>Full-text and <em>vector search</em></h2>
          <p class="section-sub">
            Your content is searchable the moment it lands. Full-text search
            matches keywords, and vector search — powered by an embedding model —
            matches on meaning, so results stay relevant even when the words
            don't. Rebuild any index on demand.
          </p>
          <ul class="point-list">
            <li>Full-text keyword search across your content and collections.</li>
            <li>Vector search that matches on meaning, not just words.</li>
            <li>Attach an embedding model to power the vectors.</li>
            <li>Rebuild any index on demand.</li>
          </ul>
        </div>
        <div class="store-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">search indexes</span>
          </div>
          <div class="store-body">
            <div
              v-for="s in SYSTEMS"
              :key="s.name"
              class="store-row"
            >
              <span class="store-name">{{ s.name }}</span>
              <span class="store-note">{{ s.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverSystemExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.back-window,
.rec-window,
.store-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Backends window ─────────────────────────── */

.back-body {
  padding: 12px 10px;
}

.back-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 12px;
}

.back-row + .back-row {
  border-top: 1px solid var(--line);
}

.back-icon {
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

.back-name {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
}

.back-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Record window (backup) ──────────────────── */

.rec-body {
  padding: 12px 10px;
}

.rec-row {
  display: flex;
  align-items: baseline;
  gap: 14px;
  padding: 14px 12px;
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.rec-row + .rec-row {
  border-top: 1px solid var(--line);
}

.rec-k {
  width: 74px;
  flex-shrink: 0;
  color: var(--fg-3);
}

.rec-v { color: var(--fg-1); }

/* ── Search indexes window ───────────────────── */

.store-body {
  padding: 12px 10px;
}

.store-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 12px;
}

.store-row + .store-row {
  border-top: 1px solid var(--line);
}

.store-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.store-type {
  font-family: var(--font-mono);
  font-size: 9.5px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  border-radius: 999px;
  padding: 1px 8px;
  border: 1px solid var(--line-2);
  color: var(--fg-3);
}

.store-type.search {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
}

.store-type.vector {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.store-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}
</style>
