<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Artifact Storage — Content-Addressed & Verified',
  description: 'Every blob is keyed by its digest and stored once — hashed on upload, reference-counted across versions, and collected when the last reference is deleted. Bytes live in S3-compatible object storage, metadata in PostgreSQL.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Artifacts', path: '/discover/artifacts' },
  { name: 'Storage', path: '/discover/artifacts/storage' }
], '/og-artifacts.png')
</script>

<template>
  <DiscoverShell section-id="artifacts">
    <section class="page-hero">
      <p class="kicker load-1">
        Storage
      </p>
      <h1 class="load-2">
        Stored once, <em>proven intact</em>
      </h1>
      <p class="section-sub load-3">
        Underneath every format sits one blob store, where a blob's digest is
        its identity. Uploads are hashed as they stream in, identical bytes
        are never written twice, and nothing survives that can't prove what
        it is.
      </p>
    </section>

    <!-- ── Content-addressed ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Content-addressed
          </p>
          <h2>The digest <em>is the identity</em></h2>
          <p class="section-sub">
            A blob's digest is both its database identity and its path in
            object storage — so deduplication isn't a feature layered on top,
            it's a consequence of the design.
          </p>
          <ul class="point-list">
            <li>Identical bytes are one object, however many versions or repositories reference them — a Docker base layer shared by twenty images is stored once.</li>
            <li>References are counted: each version that points at a blob holds a reference, and deletes decrement it.</li>
            <li>When the last reference goes, the blob becomes collectable — an atomic check-and-delete that stays correct under concurrent pushes.</li>
            <li>Docker layers, npm tarballs, Maven jars, Helm charts, model archives — every format lands in the same store.</li>
          </ul>
        </div>
        <div class="blob-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">blob store</span>
          </div>
          <div class="blob-body">
            <div class="blob-row">
              <span class="blob-digest">sha256:7d9c4f2a…</span>
              <span class="blob-size">84.2 MB</span>
              <span class="blob-refs">3 refs</span>
            </div>
            <div class="blob-row">
              <span class="blob-digest">sha256:e3b0c442…</span>
              <span class="blob-size">12.7 MB</span>
              <span class="blob-refs">1 ref</span>
            </div>
            <div class="blob-row">
              <span class="blob-digest">sha256:a1b0c7d4…</span>
              <span class="blob-size">4.1 MB</span>
              <span class="blob-refs collectable">0 refs → collectable</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Verification ────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Verification
          </p>
          <h2>Nothing lands <em>unchecked</em></h2>
          <p class="section-sub">
            The registry computes hashes while an upload streams, before
            anything is committed — a corrupt or mislabeled artifact is
            rejected at the door, not discovered at deploy time.
          </p>
          <ul class="point-list">
            <li>Docker uploads declare a digest; the registry verifies the assembled content against it and rejects mismatches.</li>
            <li>Large layers stream in chunks, tracked as upload sessions with byte offsets and expiry.</li>
            <li>Maven artifacts serve <code>.sha1</code>, <code>.md5</code>, and <code>.sha256</code> checksum files; npm tarballs carry shasums and SRI integrity.</li>
            <li>Digests follow uploads all the way through — the same hash that verified the push addresses the blob forever after.</li>
          </ul>
        </div>
        <div class="verify-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">PUT blob · ?digest=sha256:e3b0c442…</span>
          </div>
          <div class="code-body">
            <pre><span class="tok-com">streamed</span>  12.7 MB · sha256 computed
<span class="tok-com">declared</span>  e3b0c442…
<span class="tok-com">computed</span>  e3b0c442…
<span class="tok-str">✓ match — blob stored</span></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Bytes & metadata ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Bytes &amp; metadata
          </p>
          <h2>Object storage below, <em>PostgreSQL beside</em></h2>
          <p class="section-sub">
            The server keeps no artifact bytes on local disk, so instances
            carry no state of their own.
          </p>
          <ul class="point-list">
            <li>Artifact bytes live in S3-compatible object storage at digest-derived paths.</li>
            <li>Namespaces, repositories, versions, tags, permissions, and upload sessions live in PostgreSQL.</li>
            <li>The registry runs as a standalone service and compiles to a GraalVM native image.</li>
            <li>Deleting a namespace or repository cascades — versions, tags, and blob references go with it, and blobs nothing references anymore are collected.</li>
          </ul>
        </div>
        <div class="arch-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">where things live</span>
          </div>
          <div class="arch-body">
            <div class="arch-row">
              <span class="arch-icon"><Icon
                name="database"
                :size="15"
              /></span>
              <span class="arch-text">Bytes → object storage · <code>artifacts/sha256:…</code></span>
            </div>
            <div class="arch-row">
              <span class="arch-icon"><Icon
                name="rows3"
                :size="15"
              /></span>
              <span class="arch-text">Metadata → PostgreSQL · versions, tags, grants</span>
            </div>
            <div class="arch-row">
              <span class="arch-icon"><Icon
                name="server"
                :size="15"
              /></span>
              <span class="arch-text">Server → GraalVM native image, no local state</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverArtifactsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Blob window ─────────────────────────────── */

.blob-window,
.verify-window,
.arch-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.blob-body {
  padding: 10px 8px;
}

.blob-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 14px;
}

.blob-row + .blob-row {
  border-top: 1px solid var(--line);
}

.blob-digest {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.blob-size {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}

.blob-refs {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.blob-refs.collectable {
  color: #f6c453;
}

/* ── Arch window ─────────────────────────────── */

.arch-body {
  padding: 10px 8px;
}

.arch-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
}

.arch-row + .arch-row {
  border-top: 1px solid var(--line);
}

.arch-icon {
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

.arch-text {
  font-size: 13px;
  color: var(--fg-1);
}

.arch-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}
</style>
