<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Artifact Registries — Docker, Helm, Maven, npm, ML & Raw',
  description: 'Each repository type speaks its client\'s native protocol: the OCI distribution API for Docker, standard Maven layout with generated metadata, the npm registry protocol with dist-tags and search, Helm\'s index.yaml, plus versioned ML model and raw file storage.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Artifacts', path: '/discover/artifacts' },
  { name: 'Registries', path: '/discover/artifacts/registries' }
], '/og-artifacts.png')

const MORE_FORMATS = [
  {
    icon: 'boxes',
    title: 'npm',
    points: [
      'Scoped and unscoped packages, published straight from npm publish.',
      'dist-tags like latest resolve exactly as npm expects; tarballs carry shasums and SRI integrity.',
      'A search endpoint that returns only the packages the caller may see.'
    ]
  },
  {
    icon: 'kubernetes',
    title: 'Helm',
    points: [
      'index.yaml is generated on request from the charts in the namespace.',
      'Push a .tgz and its Chart.yaml is parsed into version metadata.',
      'helm repo add and helm install work against any namespace.'
    ]
  },
  {
    icon: 'brain',
    title: 'ML models',
    points: [
      'One versioned model archive per version — push, list, pull, delete.',
      'A dedicated service principal with push and pull tokens is provisioned on first boot.',
      'Recommendation models are published and served from the registry.'
    ]
  },
  {
    icon: 'folder-open',
    title: 'Raw files',
    points: [
      'Any files, versioned — several per version for platform-specific binaries.',
      'Push and pull over plain HTTP, each blob addressed by filename.',
      'List versions and their files; delete a version when it\'s done.'
    ]
  }
]
</script>

<template>
  <DiscoverShell section-id="artifacts">
    <section class="page-hero">
      <p class="kicker load-1">
        Registries
      </p>
      <h1 class="load-2">
        The protocols <em>your tools already speak</em>
      </h1>
      <p class="section-sub load-3">
        A repository's type decides the protocol it speaks. <code>docker
          push</code>, <code>gradle publish</code>, <code>npm install</code>,
        <code>helm repo add</code> — the standard tools work as-is, because the
        registry implements each one's real protocol rather than a lookalike.
      </p>
    </section>

    <!-- ── Docker / OCI ────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Docker &amp; OCI
          </p>
          <h2>A real <em>container registry</em></h2>
          <p class="section-sub">
            Docker repositories implement the OCI distribution API — the same
            endpoints Docker and every OCI-compatible client already speak.
          </p>
          <ul class="point-list">
            <li>Chunked blob uploads with tracked sessions; content is verified against its declared digest before a blob is accepted.</li>
            <li>Layers deduplicate — clients check a blob by digest first, and bytes that exist are never stored twice.</li>
            <li>Manifests resolve by tag or digest, tag lists paginate, and the catalog lists only what you're allowed to see.</li>
            <li>Tags are mutable pointers to immutable, digest-addressed manifests — re-push <code>latest</code> and only the pointer moves.</li>
          </ul>
        </div>
        <div class="tags-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">library/api · tags</span>
          </div>
          <div class="tags-body">
            <div class="tag-row">
              <span class="tag-name">latest</span>
              <span class="tag-arrow">→</span>
              <span class="tag-digest">sha256:9f86d081…</span>
            </div>
            <div class="tag-row">
              <span class="tag-name">v1.4.0</span>
              <span class="tag-arrow">→</span>
              <span class="tag-digest">sha256:9f86d081…</span>
            </div>
            <div class="tag-row">
              <span class="tag-name">v1.3.2</span>
              <span class="tag-arrow">→</span>
              <span class="tag-digest">sha256:a1b0c7d4…</span>
            </div>
            <div class="tag-note">
              two tags, one manifest — stored once
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Maven ───────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Maven
          </p>
          <h2>A repository <em>Gradle understands</em></h2>
          <p class="section-sub">
            Standard Maven layout, real metadata — point a build at the
            registry and publish or resolve with plain coordinates, no plugins.
          </p>
          <ul class="point-list">
            <li>POMs are parsed on upload — the coordinates inside the POM are what place the artifact, not just the path it was pushed to.</li>
            <li><code>maven-metadata.xml</code> is generated — version lists with <code>latest</code> and <code>release</code>, SNAPSHOT-aware.</li>
            <li>Checksum files (<code>.sha1</code>, <code>.md5</code>, <code>.sha256</code>) accompany every artifact.</li>
            <li>Beyond jars: sources, javadoc, <code>.aar</code>, Kotlin <code>.klib</code>, Gradle <code>.module</code> metadata, and <code>.asc</code> signatures — Kotlin Multiplatform publishes cleanly.</li>
          </ul>
        </div>
        <div class="tree-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">io/bosca/core</span>
          </div>
          <div class="code-body">
            <pre><span class="tok-com">io/bosca/core/6.0.16/</span>
  core-6.0.16.pom
  core-6.0.16.jar
  core-6.0.16.module
  core-6.0.16.jar.sha256
<span class="tok-com">io/bosca/core/</span>
  maven-metadata.xml   <span class="tok-str">← generated</span></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── The other four ──────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          And four more
        </p>
        <h2>npm, Helm, <em>models &amp; files</em></h2>
        <p class="section-sub">
          The same namespaces, permissions, and content-addressed storage
          behind four more repository types.
        </p>
      </div>
      <div class="format-grid reveal">
        <article
          v-for="format in MORE_FORMATS"
          :key="format.title"
          class="format-card"
        >
          <span class="format-icon">
            <Icon
              :name="format.icon"
              :size="16"
            />
          </span>
          <h3>{{ format.title }}</h3>
          <ul class="point-list">
            <li
              v-for="point in format.points"
              :key="point"
            >
              {{ point }}
            </li>
          </ul>
        </article>
      </div>
    </section>

    <DiscoverArtifactsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Tags window ─────────────────────────────── */

.tags-window,
.tree-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.tags-body {
  padding: 10px 8px;
}

.tag-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.tag-row + .tag-row {
  border-top: 1px solid var(--line);
}

.tag-name {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-0);
  min-width: 72px;
}

.tag-arrow {
  color: var(--fg-3);
  font-size: 12px;
}

.tag-digest {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}

.tag-note {
  border-top: 1px solid var(--line);
  padding: 11px 14px 8px;
  font-size: 12px;
  color: var(--fg-3);
}

/* ── Format cards ────────────────────────────── */

.format-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.format-card {
  padding: 22px 22px 24px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.format-card:hover {
  border-color: color-mix(in srgb, var(--accent) 50%, transparent);
  transform: translateY(-2px);
}

.format-icon {
  width: 30px;
  height: 30px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #f0d5f5;
  margin-bottom: 12px;
}

.format-card h3 {
  font-size: 15.5px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 4px;
}

.format-card .point-list {
  margin-top: 12px;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 720px) {
  .format-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
