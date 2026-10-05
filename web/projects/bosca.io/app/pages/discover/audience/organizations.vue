<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Audience Organizations — Members, Roles & Signup',
  description: 'Organizations group people with members and managers, group-based permissions, and three ways to admit new signups — by domain, by specific email, or by a token you hand out.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Audience', path: '/discover/audience' },
  { name: 'Organizations', path: '/discover/audience/organizations' }
], '/og-audience.png')

const CHANNELS = [
  { icon: 'globe', name: 'By domain', body: 'Anyone signing up with an email on an allowed domain can auto-join — set by system admins.' },
  { icon: 'mail', name: 'By email', body: 'Admit specific email addresses individually, for named invitees.' },
  { icon: 'key-round', name: 'By token', body: 'Generate a token and hand it out; whoever redeems it joins with the assigned group.' }
]
</script>

<template>
  <DiscoverShell section-id="audience">
    <section class="page-hero">
      <p class="kicker load-1">
        Organizations
      </p>
      <h1 class="load-2">
        People, <em>grouped and governed</em>
      </h1>
      <p class="section-sub load-3">
        An organization gathers people into a formal group — with members and
        managers, group-based permissions, and its own free-form attributes.
        It's a profile itself, so it lives in the same system as everyone else.
      </p>
    </section>

    <!-- ── Members & roles ─────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Members &amp; roles
          </p>
          <h2>Who's in, <em>and who runs it</em></h2>
          <p class="section-sub">
            People join an organization as members, and some of them manage it.
            Access is granted to security groups rather than individuals, so it
            follows the group rather than the person — and a group is typed as
            users or administrators.
          </p>
          <ul class="point-list">
            <li>Each permission grants an action to a security group, so access follows the group rather than the person.</li>
            <li>An organization tracks its member count as people come and go.</li>
            <li>Organization-specific data lives in a free-form attributes object — country, plan, anything you need.</li>
          </ul>
        </div>
        <div class="org-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">Acme Inc · members</span>
          </div>
          <div class="org-body">
            <div class="org-row">
              <span class="org-avatar">M</span>
              <span class="org-name">Maria Chen</span>
              <span class="org-role role-mgr">manager</span>
            </div>
            <div class="org-row">
              <span class="org-avatar">K</span>
              <span class="org-name">Kai Ortiz</span>
              <span class="org-role">member</span>
            </div>
            <div class="org-row">
              <span class="org-avatar">A</span>
              <span class="org-name">Ada Lovelace</span>
              <span class="org-role">member</span>
            </div>
            <div class="org-foot">
              <span>3 members</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Signup channels ─────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Signup channels
        </p>
        <h2>Three ways <em>to let people in</em></h2>
        <p class="section-sub">
          New members can be admitted however fits — and each channel assigns
          the security group they land in, so people arrive with the right
          access from the start.
        </p>
      </div>
      <div class="channel-grid reveal">
        <article
          v-for="ch in CHANNELS"
          :key="ch.name"
          class="channel-card"
        >
          <span class="channel-icon">
            <Icon
              :name="ch.icon"
              :size="16"
            />
          </span>
          <h3>{{ ch.name }}</h3>
          <p>{{ ch.body }}</p>
        </article>
      </div>
    </section>

    <DiscoverAudienceExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Org window ──────────────────────────────── */

.org-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.org-body {
  padding: 10px 8px;
}

.org-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}

.org-row + .org-row {
  border-top: 1px solid var(--line);
}

.org-avatar {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  color: #1a1205;
  background: var(--accent);
}

.org-name {
  flex: 1;
  font-size: 13.5px;
  color: var(--fg-1);
}

.org-role {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 9px;
}

.org-role.role-mgr {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}

.org-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 16px 9px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

/* ── Channel cards ───────────────────────────── */

.channel-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.channel-card {
  padding: 24px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.channel-card:hover {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
  transform: translateY(-2px);
}

.channel-icon {
  width: 34px;
  height: 34px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
  margin-bottom: 14px;
}

.channel-card h3 {
  font-size: 15px;
  font-weight: 650;
  margin: 0 0 8px;
}

.channel-card p {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 760px) {
  .channel-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
