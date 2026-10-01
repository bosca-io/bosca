<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Gateway Routes — Path & Host Patterns',
  description: 'A route binds a path pattern — and optionally a host — to an upstream. Prefix, single-segment, and exact matches, host wildcards with clear precedence, prefix stripping, and presets for picky services.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Gateway', path: '/discover/gateway' },
  { name: 'Routes', path: '/discover/gateway/routes' }
], '/og-gateway.png')
</script>

<template>
  <DiscoverShell section-id="gateway">
    <section class="page-hero">
      <p class="kicker load-1">
        Routes
      </p>
      <h1 class="load-2">
        The path decides <em>where it goes</em>
      </h1>
      <p class="section-sub load-3">
        A route matches a request — by path, and by host if you want — and
        hands it to an upstream. The matching rules are few, and their
        precedence is predictable.
      </p>
    </section>

    <!-- ── Patterns ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Patterns
          </p>
          <h2>Three pattern shapes, <em>two host rules</em></h2>
          <p class="section-sub">
            Paths match by prefix, by a single segment, or exactly. Hosts
            match literally or by a leading wildcard — and when several
            routes could match, the more specific one wins.
          </p>
          <ul class="point-list">
            <li><strong>Path shapes</strong> — <code>/reports/**</code> for a whole subtree, <code>/reports/*</code> for one segment, or an exact path like <code>/health</code>.</li>
            <li><strong>Hosts</strong> — a literal like <code>api.bosca.io</code>, a wildcard like <code>*.bosca.io</code>, or nothing to match any host. Literal beats wildcard beats any.</li>
            <li><strong>Ties are yours to break</strong> — a sort order decides between equally-specific routes; lower wins.</li>
            <li><strong>Strip the prefix</strong> — optionally drop the matched prefix before forwarding, so <code>/warehouse/v1/query</code> arrives upstream as <code>/v1/query</code>.</li>
          </ul>
        </div>
        <div class="pat-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">routes</span>
          </div>
          <div class="pat-body">
            <div class="p-row">
              <span class="p-path">/warehouse/**</span>
              <span class="p-note">→ warehouse · strip prefix</span>
            </div>
            <div class="p-row">
              <span class="p-path">/dashboards/**</span>
              <span class="p-note">→ dashboards</span>
            </div>
            <div class="p-row">
              <span class="p-path">/health</span>
              <span class="p-note">→ status · exact</span>
            </div>
            <div class="p-row">
              <span class="p-path">*.reports.example.com</span>
              <span class="p-note">host wildcard</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Presets ─────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Presets
          </p>
          <h2>Picky services, <em>preconfigured</em></h2>
          <p class="section-sub">
            Some services are particular about how they're proxied. A preset
            overlays the right field values onto the route form — and leaves
            everything you've already set alone.
          </p>
          <ul class="point-list">
            <li><strong>The data warehouse</strong> — sets the path pattern and prefix stripping, and spells out what the warehouse needs configured to accept a proxy in front of it.</li>
            <li><strong>Raw pass-through</strong> — no prefix stripping, no injected headers; the gateway forwards the request as-is.</li>
            <li><strong>Custom</strong> — the default: start from a blank route and set every field yourself.</li>
          </ul>
        </div>
        <div class="preset-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">preset · data warehouse</span>
          </div>
          <div class="cfg-body">
            <div class="cfg-row">
              <span class="cfg-key">Path pattern</span>
              <span class="cfg-val cfg-pill">/warehouse/**</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Strip prefix</span>
              <span class="cfg-val cfg-on">on</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Hint</span>
              <span class="cfg-val">coordinator settings to check</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverGatewayExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Window shells ───────────────────────────── */

.pat-window,
.preset-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Pattern rows ────────────────────────────── */

.pat-body {
  padding: 10px 8px;
}

.p-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
}

.p-row + .p-row {
  border-top: 1px solid var(--line);
}

.p-path {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-0);
}

.p-note {
  font-size: 12px;
  color: var(--fg-3);
}

/* ── Config rows ─────────────────────────────── */

.cfg-body {
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
  color: var(--fg-1);
}

.cfg-pill {
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 10px;
}

.cfg-on {
  color: #34d99a;
}
</style>
