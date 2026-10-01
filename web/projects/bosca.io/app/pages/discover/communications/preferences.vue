<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Preferences & Suppression — Reach people on their terms',
  description: 'Bosca notification preferences are per person, per channel, and per category — with transactional and security messages that always get through, timezone-aware quiet hours for push, one-click unsubscribe, and automatic suppression of bounced addresses.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Communications', path: '/discover/communications' },
  { name: 'Preferences', path: '/discover/communications/preferences' }
], '/og-communications.png')

const CATEGORIES = [
  { name: 'Transactional', email: 'lock', push: 'lock' },
  { name: 'Security', email: 'lock', push: 'lock' },
  { name: 'Digest', email: 'on', push: 'off' },
  { name: 'Marketing', email: 'off', push: 'off' }
]
</script>

<template>
  <DiscoverShell section-id="communications">
    <section class="page-hero">
      <p class="kicker load-1">
        Preferences &amp; Suppression
      </p>
      <h1 class="load-2">
        Reach people <em>on their terms</em>
      </h1>
      <p class="section-sub load-3">
        Everyone decides what they hear from you, and where. Preferences are per
        person, per channel, and per category — and the messages that truly
        matter still get through, so honoring a choice never breaks the
        essentials.
      </p>
    </section>

    <!-- ── Preference matrix ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Per channel, per category
          </p>
          <h2>Opt out of <em>the noise, not the essentials</em></h2>
          <p class="section-sub">
            A person can turn off a whole category on a channel — no more
            marketing email, no digest push — while the messages they can't
            afford to miss stay on. Everything is opt-out from a sensible
            default, and only the choices someone actually makes are stored.
          </p>
          <ul class="point-list">
            <li>Preferences are set per person, per channel (email and push), and per category.</li>
            <li>Transactional and security messages can't be switched off — they always deliver.</li>
            <li>The built-in categories are transactional, digest, marketing, and security; admins can add their own.</li>
          </ul>
        </div>
        <div class="pref-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">notification preferences</span>
          </div>
          <div class="pref-body">
            <div class="pref-head">
              <span class="pref-cat">category</span>
              <span class="pref-ch">email</span>
              <span class="pref-ch">push</span>
            </div>
            <div
              v-for="c in CATEGORIES"
              :key="c.name"
              class="pref-row"
            >
              <span class="pref-cat">{{ c.name }}</span>
              <span class="pref-ch">
                <Icon
                  v-if="c.email === 'lock'"
                  name="lock"
                  :size="13"
                  class="pref-lock"
                />
                <span
                  v-else
                  class="pref-toggle"
                  :class="c.email"
                />
              </span>
              <span class="pref-ch">
                <Icon
                  v-if="c.push === 'lock'"
                  name="lock"
                  :size="13"
                  class="pref-lock"
                />
                <span
                  v-else
                  class="pref-toggle"
                  :class="c.push"
                />
              </span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Quiet hours ─────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Quiet hours
          </p>
          <h2>Not at <em>2 a.m.</em></h2>
          <p class="section-sub">
            Push notifications honor a do-not-disturb window set in the person's
            own timezone. A digest that would have buzzed at midnight waits until
            morning — but a security alert or a genuinely urgent message still
            comes through right away.
          </p>
          <ul class="point-list">
            <li>A timezone-aware window holds non-urgent push until it lifts.</li>
            <li>Security and high-priority messages bypass the window entirely.</li>
            <li>Held messages aren't dropped — they wait, then deliver.</li>
          </ul>
        </div>
        <div class="quiet-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">quiet hours · push</span>
          </div>
          <div class="quiet-body">
            <div class="quiet-window-row">
              <Icon
                name="moon"
                :size="15"
              />
              <span>22:00 – 07:00</span>
              <span class="quiet-tz">America/Chicago</span>
            </div>
            <div class="quiet-rule held">
              <span class="quiet-dot" />
              digest &amp; marketing — held until morning
            </div>
            <div class="quiet-rule thru">
              <span class="quiet-dot" />
              security &amp; urgent — always through
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Suppression & unsubscribe ───────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Suppression &amp; unsubscribe
          </p>
          <h2>One click out, <em>and it sticks</em></h2>
          <p class="section-sub">
            Every email carries a one-click unsubscribe that works without a
            login. And when an address hard-bounces, it's added to a suppression
            list automatically — future messages to it are skipped before they
            ever reach the mail server, so you never keep hammering a dead
            address.
          </p>
          <ul class="point-list">
            <li>One-click unsubscribe and a preferences page, both reachable without signing in.</li>
            <li>A hard bounce suppresses the address on its own — no manual step.</li>
            <li>Suppressed addresses are skipped at send time, and the skip is recorded.</li>
          </ul>
        </div>
        <div class="supp-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">suppression list</span>
          </div>
          <div class="supp-body">
            <div class="supp-row">
              <Icon
                name="shield"
                :size="14"
              />
              <span class="supp-addr">bounced@example.com</span>
              <span class="supp-reason">hard bounce</span>
            </div>
            <div class="supp-note">
              <Icon
                name="key-round"
                :size="12"
              />
              one-click unsubscribe · no login required
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverCommunicationsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.pref-window,
.quiet-window,
.supp-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Preference matrix ───────────────────────── */

.pref-body {
  padding: 14px 16px 16px;
}

.pref-head,
.pref-row {
  display: grid;
  grid-template-columns: 1.6fr 0.7fr 0.7fr;
  align-items: center;
  gap: 8px;
  padding: 11px 6px;
}

.pref-head {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.pref-row + .pref-row,
.pref-head + .pref-row {
  border-top: 1px solid var(--line);
}

.pref-cat {
  font-size: 13px;
  color: var(--fg-1);
}

.pref-ch {
  display: flex;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.pref-toggle {
  width: 11px;
  height: 11px;
  border-radius: 999px;
}

.pref-toggle.on {
  background: #34d99a;
}

.pref-toggle.off {
  border: 1px solid var(--line-2);
}

.pref-lock {
  color: var(--accent);
}

/* ── Quiet window ────────────────────────────── */

.quiet-body {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.quiet-window-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 15px;
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  font-size: 14px;
  color: var(--fg-0);
}

.quiet-window-row svg {
  color: var(--accent);
}

.quiet-tz {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.quiet-rule {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 15px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  font-size: 12.5px;
  color: var(--fg-1);
}

.quiet-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex-shrink: 0;
}

.quiet-rule.held .quiet-dot { background: #e0a23a; }
.quiet-rule.thru .quiet-dot { background: #34d99a; }

/* ── Suppression window ──────────────────────── */

.supp-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.supp-row {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 13px 15px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.supp-row svg {
  color: var(--fg-3);
  flex-shrink: 0;
}

.supp-addr {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.supp-reason {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: #f2757f;
  border: 1px solid color-mix(in srgb, #f2757f 40%, transparent);
  border-radius: 999px;
  padding: 2px 9px;
}

.supp-note {
  display: flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
  padding-left: 4px;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 560px) {
  .quiet-tz {
    display: none;
  }
}
</style>
