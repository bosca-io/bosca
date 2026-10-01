<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Gateway Upstreams — Register, Watch, Tune',
  description: 'An upstream is a service the proxy knows how to reach: its URL, a health probe on your interval, timeouts, and a connection pool — with live health reported back into Studio.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Gateway', path: '/discover/gateway' },
  { name: 'Upstreams', path: '/discover/gateway/upstreams' }
], '/og-gateway.png')
</script>

<template>
  <DiscoverShell section-id="gateway">
    <section class="page-hero">
      <p class="kicker load-1">
        Upstreams
      </p>
      <h1 class="load-2">
        A service the proxy <em>knows how to reach</em>
      </h1>
      <p class="section-sub load-3">
        Each upstream is one URL plus everything about talking to it well — a
        health probe, timeouts, and a connection pool — managed as a record
        in Studio, not a config file on a box.
      </p>
    </section>

    <!-- ── Registration ────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Registration
          </p>
          <h2>The address, <em>and how to treat it</em></h2>
          <p class="section-sub">
            Point the gateway at the service and set how the proxy should
            treat it — per upstream, because a query engine and a tiny
            dashboard don't deserve the same timeouts.
          </p>
          <ul class="point-list">
            <li><strong>The address</strong> — an HTTP or HTTPS URL, typically an internal service with no public address of its own.</li>
            <li><strong>A health probe</strong> — a path the proxy checks on the interval you choose.</li>
            <li><strong>Timeouts</strong> — connect and request limits, per upstream.</li>
            <li><strong>A connection pool</strong> — how many idle connections to keep, and for how long.</li>
          </ul>
        </div>
        <div class="up-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">upstream · warehouse</span>
          </div>
          <div class="cfg-body">
            <div class="cfg-row">
              <span class="cfg-key">URL</span>
              <span class="cfg-val cfg-pill">http://warehouse:8080</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Health check</span>
              <span class="cfg-val">/v1/info · every 30s</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Request timeout</span>
              <span class="cfg-val">60s</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Pool</span>
              <span class="cfg-val">8 idle · 90s</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Health ──────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Health
          </p>
          <h2>You'll know <em>before your users do</em></h2>
          <p class="section-sub">
            The proxy probes each upstream on its interval and reports what it
            finds into Studio — the status, when it last changed, and the
            reason when something failed.
          </p>
          <ul class="point-list">
            <li><strong>Up</strong> — the last probe on the configured path succeeded.</li>
            <li><strong>Down</strong> — the probe failed; the failure reason is shown on the upstream's page.</li>
            <li><strong>Unknown</strong> — no probe configured, or none has run yet.</li>
          </ul>
        </div>
        <div class="health-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">upstream health</span>
          </div>
          <div class="health-body">
            <div class="h-row">
              <span class="h-dot ok" />
              <span class="h-name">warehouse</span>
              <span class="h-note ok">UP · 30s probe</span>
            </div>
            <div class="h-row">
              <span class="h-dot ok" />
              <span class="h-name">dashboards</span>
              <span class="h-note ok">UP · 60s probe</span>
            </div>
            <div class="h-row">
              <span class="h-dot bad" />
              <span class="h-name">legacy-reports</span>
              <span class="h-note bad">DOWN · connection refused</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── The switch ──────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The switch
          </p>
          <h2>On, off, <em>and accounted for</em></h2>
          <p class="section-sub">
            Routes and upstreams each carry their own enabled switch, and the
            relationships between them stay visible.
          </p>
          <ul class="point-list">
            <li><strong>Disable a route and it stops matching</strong> — the upstream and its other routes are untouched.</li>
            <li><strong>Routes are listed where you need them</strong> — the upstream's page shows every route bound to it, with its pattern, sign-in method, and groups.</li>
            <li><strong>Deleting is deliberate</strong> — an upstream's routes are removed first, then the upstream.</li>
          </ul>
        </div>
        <div class="switch-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">routes on warehouse</span>
          </div>
          <div class="health-body">
            <div class="h-row">
              <span class="h-path">/warehouse/**</span>
              <span class="h-note">OAuth2 · analysts</span>
            </div>
            <div class="h-row">
              <span class="h-path">/warehouse-api/**</span>
              <span class="h-note">JWT · data-eng</span>
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

.up-window,
.health-window,
.switch-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
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

/* ── Health rows ─────────────────────────────── */

.health-body {
  padding: 10px 8px;
}

.h-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 14px;
}

.h-row + .h-row {
  border-top: 1px solid var(--line);
}

.h-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
  background: var(--fg-3);
}

.h-dot.ok { background: #34d99a; }
.h-dot.bad { background: #f87171; }

.h-name {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
}

.h-path {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.h-note {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.h-note.ok { color: #34d99a; }
.h-note.bad { color: #f87171; }
</style>
