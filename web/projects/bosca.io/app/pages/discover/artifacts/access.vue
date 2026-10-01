<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Artifacts Access — Namespaces, Groups & Scoped Tokens',
  description: 'Public namespaces serve anonymous pulls; private ones check group grants. Machines authenticate with API tokens whose scopes narrow to a format, a repository, and a version pattern — pull, push, or admin.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Artifacts', path: '/discover/artifacts' },
  { name: 'Access', path: '/discover/artifacts/access' }
], '/og-artifacts.png')
</script>

<template>
  <DiscoverShell section-id="artifacts">
    <section class="page-hero">
      <p class="kicker load-1">
        Access control
      </p>
      <h1 class="load-2">
        Open to the world, <em>or exactly one job</em>
      </h1>
      <p class="section-sub load-3">
        A public namespace serves anonymous pulls like any open registry. A
        private one checks the same security groups as the rest of Bosca. And
        for machines, a token can be narrowed until it can do exactly one
        thing.
      </p>
    </section>

    <!-- ── Namespaces ──────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Namespaces
          </p>
          <h2>Public or private, <em>per namespace</em></h2>
          <p class="section-sub">
            The namespace is the unit of visibility and the unit of access —
            everything inside one answers to the same rules.
          </p>
          <ul class="point-list">
            <li>Public means anonymous pull access; private means every read checks permissions.</li>
            <li>Names shape how clients see it: <code>library</code> for images, <code>com.acme</code> for Maven groups, <code>@scope</code> for npm.</li>
            <li>Visibility is a switch, not a migration — flip a namespace public when it's ready to be.</li>
            <li>Deleting a namespace removes its repositories, versions, and blob references after confirmation.</li>
          </ul>
        </div>
        <div class="ns-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">namespaces</span>
          </div>
          <div class="ns-body">
            <div class="ns-row">
              <span class="ns-name">library</span>
              <span class="ns-vis pub">Public</span>
              <span class="ns-types">docker 4 · helm 2</span>
            </div>
            <div class="ns-row">
              <span class="ns-name">bosca-maven</span>
              <span class="ns-vis">Private</span>
              <span class="ns-types">maven 12</span>
            </div>
            <div class="ns-row">
              <span class="ns-name">model</span>
              <span class="ns-vis">Private</span>
              <span class="ns-types">ml 3</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Groups ──────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Group permissions
          </p>
          <h2>The groups <em>you already have</em></h2>
          <p class="section-sub">
            No registry user directory, no second set of accounts — grants
            pair a Bosca security group with an action on a namespace.
          </p>
          <ul class="point-list">
            <li>Pulls check view, pushes check edit, and administrative or destructive operations check manage.</li>
            <li>Admin and service-account groups carry implicit access; everyone else needs an explicit grant.</li>
            <li>Signed-in people and platform services use their existing identity — the registry recognizes both.</li>
            <li>Grants are managed per namespace in Studio, one <em>(group, action)</em> pair per entry.</li>
          </ul>
        </div>
        <div class="perm-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">library · permissions</span>
          </div>
          <div class="perm-body">
            <div class="perm-row">
              <span class="perm-group">engineering</span>
              <span class="perm-action">EDIT</span>
            </div>
            <div class="perm-row">
              <span class="perm-group">ci-agents</span>
              <span class="perm-action">VIEW</span>
            </div>
            <div class="perm-row">
              <span class="perm-group">release-managers</span>
              <span class="perm-action">MANAGE</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Scoped tokens ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Scoped tokens
          </p>
          <h2>Tokens that name <em>exactly what they may do</em></h2>
          <p class="section-sub">
            A registry token carries <code>artifacts</code> scopes — format,
            namespace and repository, version or tag pattern, and action — so
            a leaked CI credential is a small problem, not a master key.
          </p>
          <ul class="point-list">
            <li>Scopes follow <code>artifacts:type:namespace/repository:version:action</code>, with wildcards and globs at every level — every repo in a namespace, or only versions matching <code>v3.*</code>.</li>
            <li>Actions are <code>pull</code>, <code>push</code>, and <code>admin</code> — and push implies pull, so a publisher can read what it wrote.</li>
            <li>Broad scopes exist when you want them: <code>artifacts:pull</code>, <code>artifacts:push</code>, <code>artifacts:admin</code>.</li>
            <li>Hand one to <code>docker login</code> or <code>npm login</code> as the credential — the registry challenges and accepts it like any registry auth.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">token scopes</span>
          </div>
          <div class="code-body">
            <pre><span class="tok-com"># training may publish models</span>
<span class="tok-attr">artifacts</span>:ml:model/*:*:<span class="tok-str">push</span>

<span class="tok-com"># agents may pull any library image</span>
<span class="tok-attr">artifacts</span>:docker:library/*:*:<span class="tok-str">pull</span>

<span class="tok-com"># only 3.x of one package</span>
<span class="tok-attr">artifacts</span>:npm:@acme/ui:v3.*:<span class="tok-str">pull</span></pre>
          </div>
        </div>
      </div>
    </section>

    <DiscoverArtifactsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Namespace window ────────────────────────── */

.ns-window,
.perm-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.ns-body,
.perm-body {
  padding: 10px 8px;
}

.ns-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 14px;
}

.ns-row + .ns-row,
.perm-row + .perm-row {
  border-top: 1px solid var(--line);
}

.ns-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-0);
}

.ns-vis {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 10px;
}

.ns-vis.pub {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.ns-types {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

/* ── Permission window ───────────────────────── */

.perm-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 13px 14px;
}

.perm-group {
  font-size: 13px;
  color: var(--fg-1);
}

.perm-action {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 999px;
  padding: 2px 10px;
}
</style>
