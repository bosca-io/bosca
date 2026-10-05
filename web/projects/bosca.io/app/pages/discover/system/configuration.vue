<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Configuration — Settings and integrations',
  description: 'Change how Bosca behaves without shipping new code, control who can read or edit each setting, and configure the services your platform uses — including HubSpot, Mux, SendGrid, Mailgun, push delivery, federation, and OAuth sign-in.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'System', path: '/discover/system' },
  { name: 'Configuration', path: '/discover/system/configuration' }
], '/og-system.png')

const CONFIG = [
  { key: 'app.feature.newEditor', val: 'true' },
  { key: 'search.resultsPerPage', val: '25' },
  { key: 'branding.primaryColor', val: '"#ef4444"' }
]

const ACCESS = [
  { key: 'branding.primaryColor', tag: 'public', pub: true },
  { key: 'app.feature.newEditor', tag: 'public', pub: true },
  { key: 'integration.hubspot.key', tag: 'private', pub: false }
]

const INTEGRATIONS = [
  { name: 'HubSpot', note: 'contact & company sync', on: true },
  { name: 'Mux', note: 'video upload & playback', on: true },
  { name: 'SendGrid', note: 'email delivery', on: true },
  { name: 'Mailgun', note: 'email delivery', on: true },
  { name: 'Push', note: 'mobile, web & desktop', on: true }
]
</script>

<template>
  <DiscoverShell section-id="system">
    <section class="page-hero">
      <p class="kicker load-1">
        Configuration
      </p>
      <h1 class="load-2">
        Settings <em>and integrations</em>
      </h1>
      <p class="section-sub load-3">
        Change how the platform behaves without shipping new code, and bring in
        the outside services you already use — all from one place.
      </p>
    </section>

    <!-- ── Live settings ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Live settings
          </p>
          <h2>Change it <em>without a redeploy</em></h2>
          <p class="section-sub">
            Platform settings are values you edit while Bosca is running — flip a
            feature on, adjust a limit, update a label — and the change takes
            effect right away, no new build required. A value can be a simple
            string or structured JSON, and each setting decides who's allowed to
            read it and who's allowed to change it.
          </p>
          <ul class="point-list">
            <li>Edit settings live — the change takes effect right away.</li>
            <li>A value is a plain string or structured JSON.</li>
            <li>Each setting controls who can read it and who can change it.</li>
          </ul>
        </div>
        <div class="cfg-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">configuration</span>
          </div>
          <div class="cfg-body">
            <div
              v-for="c in CONFIG"
              :key="c.key"
              class="cfg-row"
            >
              <span class="cfg-key">{{ c.key }}</span>
              <span class="cfg-val">{{ c.val }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Public settings ─────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Public settings
          </p>
          <h2>Some of it, <em>public</em></h2>
          <p class="section-sub">
            Mark a setting public and your front-end can read it without anyone
            signing in — the right home for feature flags you toggle live and
            branding values you want on the page. Everything else stays behind
            your permissions.
          </p>
          <ul class="point-list">
            <li>Mark a setting public for your front-end to read.</li>
            <li>Ideal for live feature flags and branding.</li>
            <li>No sign-in needed for the ones you choose.</li>
            <li>Everything else stays private.</li>
          </ul>
        </div>
        <div class="access-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">read access</span>
          </div>
          <div class="access-body">
            <div
              v-for="a in ACCESS"
              :key="a.key"
              class="access-row"
            >
              <span
                class="access-dot"
                :class="{ pub: a.pub }"
              />
              <span class="access-key">{{ a.key }}</span>
              <span
                class="access-tag"
                :class="{ pub: a.pub }"
              >{{ a.tag }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Integrations ────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Integrations
          </p>
          <h2>Bring in <em>the services you use</em></h2>
          <p class="section-sub">
            Connect the outside services your platform depends on — HubSpot to
            sync contacts and companies, Mux for video upload and playback,
            SendGrid or Mailgun for email, push delivery across mobile, web, and
            desktop, federation, and OAuth providers to sign users in. Switch
            each one on and set it up from one place.
          </p>
          <ul class="point-list">
            <li>HubSpot for contact and company sync.</li>
            <li>Mux for video upload, encoding, and playback.</li>
            <li>SendGrid or Mailgun for email delivery.</li>
            <li>Push delivery through FCM and APNs; OAuth providers for sign-in.</li>
            <li>Switch each on and set it up in one place.</li>
          </ul>
        </div>
        <div class="intg-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">integrations</span>
          </div>
          <div class="intg-body">
            <div
              v-for="i in INTEGRATIONS"
              :key="i.name"
              class="intg-row"
            >
              <span
                class="intg-dot"
                :class="{ on: i.on }"
              />
              <span class="intg-name">{{ i.name }}</span>
              <span class="intg-note">{{ i.note }}</span>
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

.cfg-window,
.access-window,
.intg-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Configuration window ────────────────────── */

.cfg-body {
  padding: 12px 10px;
}

.cfg-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 12px;
}

.cfg-row + .cfg-row {
  border-top: 1px solid var(--line);
}

.cfg-key {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-1);
}

.cfg-val {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--accent);
}

/* ── Access window ───────────────────────────── */

.access-body {
  padding: 12px 10px;
}

.access-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 12px;
}

.access-row + .access-row {
  border-top: 1px solid var(--line);
}

.access-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex-shrink: 0;
  background: var(--fg-3);
}

.access-dot.pub {
  background: #34d99a;
  box-shadow: 0 0 8px color-mix(in srgb, #34d99a 55%, transparent);
}

.access-key {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-1);
}

.access-tag {
  font-family: var(--font-mono);
  font-size: 9px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--fg-3);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 1px 8px;
}

.access-tag.pub {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

/* ── Integrations window ─────────────────────── */

.intg-body {
  padding: 12px 10px;
}

.intg-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 12px;
}

.intg-row + .intg-row {
  border-top: 1px solid var(--line);
}

.intg-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex-shrink: 0;
  background: var(--fg-3);
}

.intg-dot.on {
  background: #34d99a;
  box-shadow: 0 0 8px color-mix(in srgb, #34d99a 55%, transparent);
}

.intg-name {
  flex: 1;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg-1);
}

.intg-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}
</style>
