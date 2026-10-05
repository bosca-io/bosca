<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Gateway Access — Sign-In, Groups & Forwarded Identity',
  description: 'Per route: tokens, the Studio browser session, username-and-password, or public. Read and write are granted to separate groups, and injected headers carry the caller\'s identity to the upstream.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Gateway', path: '/discover/gateway' },
  { name: 'Access', path: '/discover/gateway/access' }
], '/og-gateway.png')
</script>

<template>
  <DiscoverShell section-id="gateway">
    <section class="page-hero">
      <p class="kicker load-1">
        Access
      </p>
      <h1 class="load-2">
        Who gets through, <em>and as whom</em>
      </h1>
      <p class="section-sub load-3">
        Each route chooses how callers prove who they are, which groups may
        read and which may write — and what the upstream learns about the
        person on the other end.
      </p>
    </section>

    <!-- ── Sign-in methods ─────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Sign-in
          </p>
          <h2>Four ways in, <em>chosen per route</em></h2>
          <p class="section-sub">
            The same route table can hold an API secured by tokens, a
            dashboard that rides the Studio session, and a public health
            endpoint — each with the sign-in that fits it.
          </p>
          <ul class="point-list">
            <li><strong>Tokens</strong> — for APIs and automation, using the platform's own tokens.</li>
            <li><strong>Browser session</strong> — signed into Studio means signed into the service; ideal for dashboards.</li>
            <li><strong>Username and password</strong> — for older tools that only speak basic sign-in, validated against the platform.</li>
            <li><strong>Public</strong> — no sign-in. Studio flags public routes in amber so nothing is exposed by oversight.</li>
          </ul>
        </div>
        <div class="auth-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">auth per route</span>
          </div>
          <div class="auth-body">
            <div class="a-row">
              <span class="a-path">/warehouse/**</span>
              <span class="a-badge">OAuth2</span>
            </div>
            <div class="a-row">
              <span class="a-path">/api/reports/**</span>
              <span class="a-badge">JWT</span>
            </div>
            <div class="a-row">
              <span class="a-path">/legacy/**</span>
              <span class="a-badge">Basic</span>
            </div>
            <div class="a-row">
              <span class="a-path">/health</span>
              <span class="a-badge warn">Public</span>
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
            Groups
          </p>
          <h2>Reading and writing are <em>different privileges</em></h2>
          <p class="section-sub">
            Viewing a dashboard and changing what's behind it aren't the same
            thing, so a route grants them separately — each to the groups you
            choose.
          </p>
          <ul class="point-list">
            <li><strong>Read groups</strong> — who may make safe requests: fetching pages, loading data.</li>
            <li><strong>Write groups</strong> — who may change things: posts, updates, deletes.</li>
            <li><strong>Tokens carry scopes</strong> — an API token needs the gateway's read or write scope, on top of its group memberships.</li>
          </ul>
        </div>
        <div class="acl-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">/warehouse/** access</span>
          </div>
          <div class="cfg-body">
            <div class="cfg-row">
              <span class="cfg-key">Read</span>
              <span class="cfg-val">analysts</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Write</span>
              <span class="cfg-val">data-eng</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Token scope</span>
              <span class="cfg-val cfg-pill">gateway:read</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Forwarded identity ──────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Forwarded identity
          </p>
          <h2>The upstream learns <em>who's asking</em></h2>
          <p class="section-sub">
            Services behind the gateway don't have to know what Bosca is. A
            route can inject headers on the way through, filled from the
            signed-in caller and the request itself.
          </p>
          <ul class="point-list">
            <li><strong>Who</strong> — the caller's name, email, subject, and groups, dropped into whatever header names the upstream expects.</li>
            <li><strong>Where from</strong> — the original host, scheme, port, and client address, for services that care.</li>
            <li><strong>Per route</strong> — one upstream can receive full identity while another gets a clean, header-free pass-through.</li>
          </ul>
        </div>
        <div class="hdr-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">injected headers</span>
          </div>
          <div class="hdr-body">
            <div class="hd-row">
              <span class="hd-name">X-User</span>
              <span class="hd-val">jane@example.com</span>
            </div>
            <div class="hd-row">
              <span class="hd-name">X-Groups</span>
              <span class="hd-val">analysts</span>
            </div>
            <div class="hd-row">
              <span class="hd-name">X-Forwarded-Host</span>
              <span class="hd-val">studio.example.com</span>
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

.auth-window,
.acl-window,
.hdr-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Auth rows ───────────────────────────────── */

.auth-body,
.hdr-body {
  padding: 10px 8px;
}

.a-row,
.hd-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
}

.a-row + .a-row,
.hd-row + .hd-row {
  border-top: 1px solid var(--line);
}

.a-path,
.hd-name {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-0);
}

.a-badge {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-2);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 10px;
}

.a-badge.warn {
  color: #f6c453;
  border-color: color-mix(in srgb, #f6c453 45%, transparent);
}

.hd-val {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
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
</style>
