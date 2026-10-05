<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Work Ops Notifications — A personal, configurable inbox',
  description: 'Bosca Work Ops gives each person an inbox for assignments, mentions, comments, approvals, due work, SLA risk, and watched tasks, with per-event channels, daily digests, do-not-disturb windows, and durable permission-aware delivery.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Work Ops', path: '/discover/workops' },
  { name: 'Notifications', path: '/discover/workops/notifications' }
], '/og-workops.png')

const INBOX = [
  { icon: 'message-circle', title: 'Ada mentioned you', detail: 'BOS-142 · Live sessions map', time: 'now', unread: true },
  { icon: 'check-circle', title: 'Review requested', detail: 'Approve production promotion', time: '8m', unread: true },
  { icon: 'timer', title: 'Task is at risk', detail: 'BOS-149 · due tomorrow', time: '1h', unread: false }
]

const CHANNELS = [
  { event: 'Mentioned', app: true, email: true, slack: false },
  { event: 'Assigned', app: true, email: false, slack: true },
  { event: 'Approval requested', app: true, email: true, slack: true },
  { event: 'Status changed', app: false, email: false, slack: true }
]
</script>

<template>
  <DiscoverShell section-id="workops">
    <section class="page-hero">
      <p class="kicker load-1">
        Notifications
      </p>
      <h1 class="load-2">
        Work finds you, <em>without becoming noise.</em>
      </h1>
      <p class="section-sub load-3">
        A personal Work Ops inbox gathers the moments that need your attention:
        assignments, mentions, comments, approvals, due work, SLA risk, and
        changes to tasks you watch. You decide which events stay in-app and
        which should reach another channel.
      </p>
    </section>

    <!-- ── Inbox ──────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Your inbox
          </p>
          <h2>The work that needs <em>your attention</em></h2>
          <p class="section-sub">
            Notifications are personal and actionable. Open the task from the
            message, work through unread items, or clear the inbox at once. The
            all-items view keeps the record after you've read it.
          </p>
          <ul class="point-list">
            <li>Assignments, mentions, comments, status changes, resolutions, and closures.</li>
            <li>Due, breached, and at-risk work, plus pipeline approval requests.</li>
            <li>Watch tasks you authored or commented on and follow what changes.</li>
          </ul>
        </div>
        <div class="inbox-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">work ops · inbox</span>
            <span class="unread-count">2 unread</span>
          </div>
          <div class="inbox-body">
            <div
              v-for="item in INBOX"
              :key="item.title"
              class="inbox-row"
              :class="{ unread: item.unread }"
            >
              <span class="inbox-icon"><Icon
                :name="item.icon"
                :size="14"
              /></span>
              <span class="inbox-copy">
                <strong>{{ item.title }}</strong>
                <small>{{ item.detail }}</small>
              </span>
              <span class="inbox-time">{{ item.time }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Preferences ────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Preferences by event
          </p>
          <h2>Choose what reaches you, <em>and where.</em></h2>
          <p class="section-sub">
            Keep mentions in the inbox, send approval requests to email, or
            route selected events to Slack or a webhook when your organization
            has configured those delivery pipelines. The choice is per event,
            not one switch for everything.
          </p>
          <ul class="point-list">
            <li>In-app and email preferences for every supported Work Ops event.</li>
            <li>Slack and webhook delivery when those channels are configured.</li>
            <li>A daily digest gathers routine activity into one update.</li>
            <li>Do-not-disturb windows and project or task mutes protect focus.</li>
          </ul>
        </div>
        <div class="pref-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">notification preferences</span>
          </div>
          <div class="pref-head">
            <span>Event</span><span>In-app</span><span>Email</span><span>Slack</span>
          </div>
          <div
            v-for="channel in CHANNELS"
            :key="channel.event"
            class="pref-row"
          >
            <span>{{ channel.event }}</span>
            <span :class="{ enabled: channel.app }">{{ channel.app ? 'on' : 'off' }}</span>
            <span :class="{ enabled: channel.email }">{{ channel.email ? 'on' : 'off' }}</span>
            <span :class="{ enabled: channel.slack }">{{ channel.slack ? 'on' : 'off' }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Delivery ───────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Dependable by design
        </p>
        <h2>The task changes first. <em>The notification follows.</em></h2>
        <p class="section-sub">
          Work Ops records notification work durably alongside the change that
          caused it, then delivers in the background. A retry doesn't create a
          duplicate inbox item, and access is checked before a recipient sees
          the linked work.
        </p>
      </div>
      <div class="delivery-grid reveal">
        <article class="delivery-card">
          <span class="delivery-icon"><Icon
            name="shield-check"
            :size="15"
          /></span>
          <h3>Permission-aware</h3>
          <p>A notification never becomes a shortcut around the task, project, or specification's access rules.</p>
        </article>
        <article class="delivery-card">
          <span class="delivery-icon"><Icon
            name="refresh"
            :size="15"
          /></span>
          <h3>Safe to retry</h3>
          <p>Background delivery can resume after an interruption without duplicating the personal inbox record.</p>
        </article>
        <article class="delivery-card">
          <span class="delivery-icon"><Icon
            name="route"
            :size="15"
          /></span>
          <h3>One event, chosen channels</h3>
          <p>The same work event follows each recipient's preferences and the channels your organization has enabled.</p>
        </article>
      </div>
    </section>

    <DiscoverWorkopsExplore />
  </DiscoverShell>
</template>

<style scoped>
.inbox-window,
.pref-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.unread-count {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--accent);
}

.inbox-body { padding: 9px 8px; }

.inbox-row {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 13px 12px;
  border-left: 2px solid transparent;
}

.inbox-row + .inbox-row { border-top: 1px solid var(--line); }
.inbox-row.unread { border-left-color: var(--accent); background: color-mix(in srgb, var(--accent) 6%, transparent); }

.inbox-icon {
  width: 29px;
  height: 29px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 14%, transparent);
}

.inbox-copy {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.inbox-copy strong { font-size: 12.5px; color: var(--fg-1); }
.inbox-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--fg-3); }
.inbox-time { font-family: var(--font-mono); font-size: 10px; color: var(--fg-3); }

.pref-head,
.pref-row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) repeat(3, 56px);
  align-items: center;
  gap: 8px;
  padding: 11px 14px;
}

.pref-head {
  font-family: var(--font-mono);
  font-size: 9.5px;
  color: var(--fg-3);
  text-transform: uppercase;
  border-bottom: 1px solid var(--line);
}

.pref-head span:not(:first-child),
.pref-row span:not(:first-child) { text-align: center; }

.pref-row {
  font-size: 12px;
  color: var(--fg-1);
}

.pref-row + .pref-row { border-top: 1px solid var(--line); }

.pref-row span:not(:first-child) {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--fg-3);
}

.pref-row span.enabled { color: var(--accent); }

.delivery-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.delivery-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 50%, transparent);
}

.delivery-icon {
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 13px;
  border-radius: var(--r-xs);
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 16%, transparent);
}

.delivery-card h3 { margin: 0 0 8px; font-size: 15px; font-weight: 650; }
.delivery-card p { margin: 0; color: var(--fg-2); font-size: 13px; line-height: 1.6; }

@media (max-width: 760px) {
  .delivery-grid { grid-template-columns: 1fr; }
}

@media (max-width: 480px) {
  .pref-head,
  .pref-row { grid-template-columns: minmax(95px, 1fr) repeat(3, 43px); padding-inline: 10px; }
}
</style>
