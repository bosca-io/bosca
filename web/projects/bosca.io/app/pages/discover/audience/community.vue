<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Audience Community — Small Groups, Families & Custom Groups',
  description: 'Community groups gather people into small groups, families, or custom groups — with members, activities, linked channels, and their own invite tokens, set public, private, or hidden.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Audience', path: '/discover/audience' },
  { name: 'Community', path: '/discover/audience/community' }
], '/og-audience.png')

const GROUP_TYPES = [
  { name: 'Small group', note: 'a gathering of members' },
  { name: 'Family', note: 'a household unit' },
  { name: 'Custom', note: 'whatever you need' }
]

const HOLDS = [
  { icon: 'users', name: 'Members', body: 'Profiles added to the group — the people who belong.' },
  { icon: 'calendar', name: 'Activities', body: 'Named happenings with a description, a type, and a schedule.' },
  { icon: 'message', name: 'Channels', body: 'Linked communications channels for the group to talk in.' }
]
</script>

<template>
  <DiscoverShell section-id="audience">
    <section class="page-hero">
      <p class="kicker load-1">
        Community
      </p>
      <h1 class="load-2">
        Where people <em>belong together</em>
      </h1>
      <p class="section-sub load-3">
        Community groups gather people into the units they actually relate in —
        a small group, a family, or a custom group of your own — each with its
        members, its activities, and its own channels to talk in.
      </p>
    </section>

    <!-- ── Group types ─────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Group types
        </p>
        <h2>Three shapes, <em>or your own</em></h2>
      </div>
      <div class="gtype-grid reveal">
        <div
          v-for="g in GROUP_TYPES"
          :key="g.name"
          class="gtype-card"
        >
          <h3>{{ g.name }}</h3>
          <span class="gtype-note">{{ g.note }}</span>
        </div>
      </div>
    </section>

    <!-- ── What a group holds ──────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            What a group holds
          </p>
          <h2>More than <em>a member list</em></h2>
          <p class="section-sub">
            A group is a place, not just a roster. Beyond its members, it carries
            the activities that bring people together and the channels they use
            to stay in touch — and, where it fits, the prayers a group shares.
          </p>
          <ul class="point-list">
            <li>Activities are named and scheduled, each with a type and a description.</li>
            <li>Channels link to the communications subsystem, so a group's conversation lives with everything else.</li>
            <li>A group carries its own free-form attributes, like an organization does.</li>
          </ul>
        </div>
        <div class="holds-grid">
          <article
            v-for="h in HOLDS"
            :key="h.name"
            class="holds-card reveal"
          >
            <span class="holds-icon">
              <Icon
                :name="h.icon"
                :size="16"
              />
            </span>
            <div>
              <h3>{{ h.name }}</h3>
              <p>{{ h.body }}</p>
            </div>
          </article>
        </div>
      </div>
    </section>

    <!-- ── Visibility & invites ────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Visibility &amp; invites
          </p>
          <h2>Open, closed, <em>or unlisted</em></h2>
          <p class="section-sub">
            A group decides how findable it is — public and discoverable,
            private for members only, or hidden so it never shows in listings at
            all. To bring someone in outside the usual signup, a group issues its
            own invite token with an expiry.
          </p>
        </div>
        <div class="vis-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">group visibility</span>
          </div>
          <div class="vis-body">
            <div class="vism-row">
              <span class="vism-dot pub" />
              <span class="vism-name">Public</span>
              <span class="vism-note">discoverable by anyone</span>
            </div>
            <div class="vism-row">
              <span class="vism-dot priv" />
              <span class="vism-name">Private</span>
              <span class="vism-note">members only</span>
            </div>
            <div class="vism-row">
              <span class="vism-dot hid" />
              <span class="vism-name">Hidden</span>
              <span class="vism-note">never listed</span>
            </div>
            <div class="vis-foot">
              <Icon
                name="key-round"
                :size="13"
              />
              invite token · expires in 60 days
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverAudienceExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Group type cards ────────────────────────── */

.gtype-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.gtype-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.gtype-card h3 {
  font-size: 15px;
  font-weight: 650;
  margin: 0;
}

.gtype-note {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

/* ── Holds cards ─────────────────────────────── */

.holds-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.holds-card {
  display: flex;
  gap: 14px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.holds-icon {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
}

.holds-card h3 {
  font-size: 14.5px;
  font-weight: 650;
  margin: 0 0 5px;
}

.holds-card p {
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--fg-2);
  margin: 0;
}

/* ── Visibility window ───────────────────────── */

.vis-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.vis-body {
  padding: 10px 8px;
}

.vism-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.vism-row + .vism-row {
  border-top: 1px solid var(--line);
}

.vism-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
}

.vism-dot.pub { background: #34d99a; }
.vism-dot.priv { background: var(--accent); }
.vism-dot.hid { background: var(--fg-3); }

.vism-name {
  flex: 1;
  font-size: 13.5px;
  color: var(--fg-1);
}

.vism-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.vis-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 13px 14px 9px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 560px) {
  .gtype-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
