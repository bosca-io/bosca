<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Identity & access — Built in, not bolted on',
  description: 'Every request arrives as a principal, mapped to a profile. Access is per-entity permissions granted to groups: reads are filtered to what the caller may see, writes are verified before they take effect — enforced by a shared evaluator every subsystem uses.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Developers', path: '/discover/developers' },
  { name: 'Identity & access', path: '/discover/developers/identity' }
], '/og-developers.png')

const CONTEXT = [
  { k: 'principal', v: 'the authenticated identity' },
  { k: 'profile', v: 'the person it represents' }
]

const DECISIONS = [
  { icon: 'eye', k: 'read', v: 'filtered to what you may see' },
  { icon: 'lock', k: 'write', v: 'verified before it takes effect' }
]

const GROUPS = [
  { name: 'administrators', note: 'full access' },
  { name: 'editors', note: 'create & edit content' },
  { name: 'viewers', note: 'read-only' }
]
</script>

<template>
  <DiscoverShell section-id="developers">
    <section class="page-hero">
      <p class="kicker load-1">
        Identity &amp; access
      </p>
      <h1 class="load-2">
        Identity and access, <em>built in</em>
      </h1>
      <p class="section-sub load-3">
        Every request arrives as a principal, mapped to a profile, and
        permissions — granted to groups — decide who can see and change what,
        enforced by a shared evaluator on every read and write.
      </p>
    </section>

    <!-- ── Principal & profile ─────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Principal &amp; profile
          </p>
          <h2>Who's calling, <em>and who they are</em></h2>
          <p class="section-sub">
            Authentication resolves a principal — the account behind the request,
            whether it signed in as a person or as an API token. The principal is
            just identity; the profile is who they are in your data, a separate
            record with its own id. Your code reads both from the request context,
            so a controller or a route always knows who it's acting for — and one
            principal can carry more than one profile.
          </p>
          <ul class="point-list">
            <li>A principal is the authenticated identity; a profile is the person's record.</li>
            <li>Separate entities with separate ids, bridged through the context.</li>
            <li>API tokens arrive as scoped principals, narrowed to what they may do.</li>
          </ul>
        </div>
        <div class="ctx-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">request context</span>
          </div>
          <div class="ctx-body">
            <div
              v-for="c in CONTEXT"
              :key="c.k"
              class="ctx-row"
            >
              <span class="ctx-k">{{ c.k }}</span>
              <span class="ctx-v">{{ c.v }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Permissions ─────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Permissions
          </p>
          <h2>Checked where <em>the data lives</em></h2>
          <p class="section-sub">
            Each entity carries its own permissions — a grant of an action, like
            view, edit, or manage, to a group. On a read, the owning service
            filters the results to what you're allowed to see; on a write, it
            verifies your access before the change lands. Public items need no
            grant, and a child inherits its parent's access.
          </p>
          <ul class="point-list">
            <li>Per-entity permissions: an action granted on a specific record.</li>
            <li>Reads are filtered to what you may see; writes are verified first.</li>
            <li>Public items need no grant; children inherit their parent's access.</li>
          </ul>
        </div>
        <div class="ctx-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">access</span>
          </div>
          <div class="ctx-body">
            <div
              v-for="d in DECISIONS"
              :key="d.k"
              class="ctx-row"
            >
              <span class="ctx-icon"><Icon
                :name="d.icon"
                :size="13"
              /></span>
              <span class="ctx-k">{{ d.k }}</span>
              <span class="ctx-v">{{ d.v }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Groups ──────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Groups
          </p>
          <h2>Granted to <em>groups</em></h2>
          <p class="section-sub">
            Permissions are granted to groups. A principal gets access by
            belonging to a group that holds the grant, so access stays easy to
            reason about: put a principal in a group, and it inherits everything
            that group can do. Built-in roles come ready to use, and you add your
            own.
          </p>
          <ul class="point-list">
            <li>Permissions are granted to groups.</li>
            <li>A principal inherits access from the groups it belongs to.</li>
            <li>Built-in roles ship ready; add your own groups alongside them.</li>
          </ul>
        </div>
        <div class="ctx-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">groups</span>
          </div>
          <div class="ctx-body">
            <div
              v-for="g in GROUPS"
              :key="g.name"
              class="ctx-row"
            >
              <span class="ctx-k">{{ g.name }}</span>
              <span class="ctx-v">{{ g.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverDevelopersExplore />
  </DiscoverShell>
</template>

<style scoped>
.ctx-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.ctx-body {
  padding: 12px 10px;
}

.ctx-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 14px;
}

.ctx-row + .ctx-row {
  border-top: 1px solid var(--line);
}

.ctx-icon {
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 16%, transparent);
  color: var(--accent);
}

.ctx-k {
  width: 120px;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--accent);
}

.ctx-v {
  flex: 1;
  font-size: 13px;
  color: var(--fg-2);
}

@media (max-width: 520px) {
  .ctx-k {
    width: 96px;
  }
}
</style>
