<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Event Templates — Emails rendered at send time',
  description: 'A Bosca event template maps an event key to a localized BML email rendered per recipient as email-safe HTML and plain text, with inline images, version pins, previews, and rollback without redeploying the sending application.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Communications', path: '/discover/communications' },
  { name: 'Event Templates', path: '/discover/communications/templates' }
], '/og-communications.png')
</script>

<template>
  <DiscoverShell section-id="communications">
    <section class="page-hero">
      <p class="kicker load-1">
        Event Templates
      </p>
      <h1 class="load-2">
        Emails that <em>render themselves</em>
      </h1>
      <p class="section-sub load-3">
        A transactional email doesn't ship its own copy. It names an event, and
        the matching template renders at send time with that event's data — so
        the words, the layout, the locale, and the links come from a versioned
        BML template you can change without redeploying the sending application.
      </p>
    </section>

    <!-- ── Event → template ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            From event to email
          </p>
          <h2>Name the event, <em>not the message</em></h2>
          <p class="section-sub">
            Application code sends a message that references an event key, not a
            block of HTML. A registry resolves that key to the right template at
            send time, so the code that fires an email never has to know how the
            email looks.
          </p>
          <ul class="point-list">
            <li>An event key resolves to a template through a registry, at send time.</li>
            <li>The same send can go to email and push from one message.</li>
            <li>Change the template and the next send picks it up — no redeploy.</li>
          </ul>
        </div>
        <div class="flow-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">resolve</span>
          </div>
          <div class="flow-body">
            <div class="flow-node">
              <span class="flow-icon"><Icon
                name="zap"
                :size="15"
              /></span>
              <span class="flow-text">event <code>order.shipped</code></span>
            </div>
            <div class="flow-arrow">
              <Icon
                name="chevron-down"
                :size="14"
              />
            </div>
            <div class="flow-node accent">
              <span class="flow-icon"><Icon
                name="file-text"
                :size="15"
              /></span>
              <span class="flow-text">template <code>store / shipping-notice</code></span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Rendered per recipient ──────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Rendered per recipient
          </p>
          <h2>Personal, <em>down to the footer</em></h2>
          <p class="section-sub">
            When a message goes to one person, the template renders with their
            data — and the unsubscribe and preferences links in the footer are
            minted just for them, so a single click works without a login. Out
            comes a subject, an HTML body, a plaintext alternative, and any
            inline images.
          </p>
          <ul class="point-list">
            <li>Rendered at send time with the event's payload and the recipient's details.</li>
            <li>The recipient's locale selects the same published language used by BML sites.</li>
            <li>One-click unsubscribe and preferences links, tokenized per recipient.</li>
            <li>Email-safe HTML with inlined CSS, a plaintext alternative, and attached inline images.</li>
          </ul>
        </div>
        <div class="mail-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">rendered email</span>
          </div>
          <div class="mail-body">
            <div class="mail-subject">
              Your order is on its way
            </div>
            <div class="mail-parts">
              <span class="mail-part">HTML</span>
              <span class="mail-part">text</span>
              <span class="mail-part">2 inline images</span>
            </div>
            <div class="mail-foot">
              <Icon
                name="key-round"
                :size="12"
              />
              one-click unsubscribe · minted for this recipient
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Version pins ────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Versions & rollback
          </p>
          <h2>Ship a version, <em>pin a version</em></h2>
          <p class="section-sub">
            Templates are published as versioned artifacts, and a project can
            pin the exact version it sends. If a change goes wrong, roll the pin
            back to a known-good version — no redeploy, no scramble, just the
            previous template taking over on the next send.
          </p>
          <ul class="point-list">
            <li>Each render records the published version that produced it.</li>
            <li>Pin a version per project to control exactly what goes out.</li>
            <li>Roll back by moving the pin — the fix is live on the next send.</li>
          </ul>
        </div>
        <div class="ver-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">store · versions</span>
          </div>
          <div class="ver-body">
            <div class="ver-row">
              <span class="ver-tag">v6</span>
              <span class="ver-note">draft</span>
            </div>
            <div class="ver-row">
              <span class="ver-tag">v5</span>
              <span class="ver-note">last published</span>
            </div>
            <div class="ver-row pinned">
              <span class="ver-tag">v4</span>
              <span class="ver-note">pinned · sending now</span>
              <Icon
                name="pin"
                :size="13"
              />
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

.flow-window,
.mail-window,
.ver-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Flow window ─────────────────────────────── */

.flow-body {
  padding: 24px 22px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}

.flow-node {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.flow-node.accent {
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

.flow-icon {
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

.flow-text {
  font-size: 13.5px;
  color: var(--fg-1);
}

.flow-arrow {
  color: var(--accent);
}

.flow-text code,
.mail-foot code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

/* ── Mail window ─────────────────────────────── */

.mail-body {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mail-subject {
  font-size: 15px;
  font-weight: 650;
  color: var(--fg-0);
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
}

.mail-parts {
  display: flex;
  gap: 8px;
}

.mail-part {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-2);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 3px 10px;
}

.mail-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--accent);
}

/* ── Version window ──────────────────────────── */

.ver-body {
  padding: 12px 10px;
}

.ver-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.ver-row + .ver-row {
  border-top: 1px solid var(--line);
}

.ver-row.pinned {
  color: var(--accent);
}

.ver-tag {
  font-family: var(--font-mono);
  font-size: 12.5px;
  font-weight: 650;
  width: 34px;
  color: var(--fg-1);
}

.ver-row.pinned .ver-tag {
  color: var(--accent);
}

.ver-note {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
}

.ver-row.pinned .ver-note {
  color: var(--accent);
}

.ver-row svg {
  color: var(--accent);
}
</style>
