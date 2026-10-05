<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Security & Identity — Who you are, and what you can do',
  description: 'A Bosca principal is the sign-in identity behind every profile, device, and token; permissions are granted to flat groups. Sign in with passkeys, OAuth, or a password, and mint scoped API tokens that stay within a principal\'s own permissions.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'System', path: '/discover/system' },
  { name: 'Security & Identity', path: '/discover/system/security' }
], '/og-system.png')

const PRINCIPAL = [
  { k: 'identifier', v: 'ada@example.com' },
  { k: 'profiles', v: 'Ada (primary) · Editor' },
  { k: 'status', v: 'verified' },
  { k: 'groups', v: 'administrators' }
]

const SIGNIN = [
  { name: 'Passkey · Touch ID', note: 'WebAuthn', icon: 'fingerprint' },
  { name: 'Google', note: 'OAuth', icon: 'globe' },
  { name: 'Password', note: 'email', icon: 'key' }
]

const TOKEN = [
  { k: 'token', v: 'bsk_a1b0c7…' },
  { k: 'scopes', v: 'content:view · content:edit' },
  { k: 'expires', v: 'in 90 days' },
  { k: 'last used', v: '2h ago · 10.0.0.4' }
]

const PERSONA = ['CMS', 'Work Ops', 'Localization']
</script>

<template>
  <DiscoverShell section-id="system">
    <section class="page-hero">
      <p class="kicker load-1">
        Security &amp; Identity
      </p>
      <h1 class="load-2">
        Who you are, <em>and what you can do</em>
      </h1>
      <p class="section-sub load-3">
        One identity model under the whole platform: principals sign in, groups
        carry the permissions, and passkeys, OAuth, and scoped tokens cover every
        way in.
      </p>
    </section>

    <!-- ── Principals ──────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Principals
          </p>
          <h2>The identity <em>behind everything</em></h2>
          <p class="section-sub">
            A principal is the identity you sign in as — not a profile. One
            principal can own several profiles, with one marked primary, along
            with its credentials, devices, and API tokens. What it's allowed to do
            comes from the groups it belongs to.
          </p>
          <ul class="point-list">
            <li>A principal is the sign-in identity, distinct from a profile.</li>
            <li>One principal, many profiles — one of them primary.</li>
            <li>It owns the credentials, devices, and tokens.</li>
            <li>Its permissions come from its group memberships.</li>
          </ul>
        </div>
        <div class="rec-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">principal</span>
          </div>
          <div class="rec-body">
            <div
              v-for="row in PRINCIPAL"
              :key="row.k"
              class="rec-row"
            >
              <span class="rec-k">{{ row.k }}</span>
              <span class="rec-v">{{ row.v }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Groups & permissions ────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Groups &amp; permissions
          </p>
          <h2>Permissions go <em>to groups</em></h2>
          <p class="section-sub">
            Access is granted to groups, not to people. A group is a flat set of
            principals — no nesting, no inherited membership. Actions are
            explicit, and groups come in two types: principal groups for everyday
            access, and system groups for platform-level control.
          </p>
          <ul class="point-list">
            <li>Permissions attach to groups; principals join groups.</li>
            <li>Groups are flat — no nesting or inherited membership.</li>
            <li>Actions: view, list, edit, delete, manage, execute, impersonate.</li>
            <li>Principal groups for access, system groups for control.</li>
          </ul>
        </div>
        <div class="perm-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">group · editors</span>
          </div>
          <div class="perm-body">
            <div class="perm-row">
              <span class="perm-res">documents</span>
              <span class="perm-acts">view · list · edit</span>
            </div>
            <div class="perm-row">
              <span class="perm-res">collections</span>
              <span class="perm-acts">view · edit · manage</span>
            </div>
            <div class="perm-row">
              <span class="perm-res">scripts</span>
              <span class="perm-acts">view · execute</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Sign-in ─────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Sign-in
          </p>
          <h2>Every way <em>in</em></h2>
          <p class="section-sub">
            Sign in the way that fits. Passkeys bring passwordless WebAuthn — the
            private key never leaves the device. OAuth connects Google, Apple,
            Facebook, or any OpenID provider. And when someone needs to be signed
            out, you can end every one of their sessions at once.
          </p>
          <ul class="point-list">
            <li>Passkeys: passwordless WebAuthn, keys stay on the device.</li>
            <li>OAuth and OpenID — Google, Apple, Facebook, or your own.</li>
            <li>Classic email and password, when you want it.</li>
            <li>End every one of a principal's sessions at once.</li>
          </ul>
        </div>
        <div class="signin-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">sign-in methods</span>
          </div>
          <div class="signin-body">
            <div
              v-for="m in SIGNIN"
              :key="m.name"
              class="signin-row"
            >
              <span class="signin-icon"><Icon
                :name="m.icon"
                :size="14"
              /></span>
              <span class="signin-name">{{ m.name }}</span>
              <span class="signin-note">{{ m.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── API tokens ──────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            API tokens
          </p>
          <h2>Programmatic access, <em>scoped</em></h2>
          <p class="section-sub">
            For scripts, CI, and server-to-server calls, a principal mints an API
            token — scoped to a subset of its own permissions, never beyond them.
            Give it an expiry, see when it was last used, and revoke it the moment
            you need to.
          </p>
          <ul class="point-list">
            <li>Authenticates as one principal, for non-interactive access.</li>
            <li>Scoped within the principal's permissions — never past them.</li>
            <li>Set an expiry, or leave it open-ended.</li>
            <li>See its last use, and revoke it instantly.</li>
          </ul>
        </div>
        <div class="rec-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">api token · ci-deploy</span>
          </div>
          <div class="rec-body">
            <div
              v-for="row in TOKEN"
              :key="row.k"
              class="rec-row"
            >
              <span class="rec-k">{{ row.k }}</span>
              <span class="rec-v">{{ row.v }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Studio Personas ─────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Studio Personas
        </p>
        <h2>Shape what <em>Studio shows</em></h2>
        <p class="section-sub">
          A Studio Persona is a named set of the Studio areas a profile can open.
          Give someone the Content Manager persona and Studio shows them the
          content tools; give an analyst theirs and they land in dashboards and
          queries. Assign more than one, and they add up.
        </p>
      </div>
      <div class="persona-window reveal">
        <div class="persona-head">
          <Icon
            name="user"
            :size="14"
          />
          <span>Content Manager</span>
        </div>
        <div class="persona-chips">
          <span
            v-for="p in PERSONA"
            :key="p"
            class="persona-chip"
          >{{ p }}</span>
        </div>
      </div>
    </section>

    <DiscoverSystemExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.rec-window,
.perm-window,
.signin-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Record window (principal / token) ───────── */

.rec-body {
  padding: 12px 10px;
}

.rec-row {
  display: flex;
  align-items: baseline;
  gap: 14px;
  padding: 14px 12px;
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.rec-row + .rec-row {
  border-top: 1px solid var(--line);
}

.rec-k {
  width: 82px;
  flex-shrink: 0;
  color: var(--fg-3);
}

.rec-v { color: var(--fg-1); }

.rec-row:first-child .rec-v { color: var(--accent); }

/* ── Permission window ───────────────────────── */

.perm-body {
  padding: 12px 10px;
}

.perm-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 15px 12px;
}

.perm-row + .perm-row {
  border-top: 1px solid var(--line);
}

.perm-res {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.perm-acts {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
}

/* ── Sign-in window ──────────────────────────── */

.signin-body {
  padding: 12px 10px;
}

.signin-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 12px;
}

.signin-row + .signin-row {
  border-top: 1px solid var(--line);
}

.signin-icon {
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

.signin-name {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
}

.signin-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Studio Persona window ───────────────────── */

.persona-window {
  max-width: 460px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.persona-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--line);
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.persona-head svg { color: var(--accent); }

.persona-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 16px 18px;
}

.persona-chip {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: 999px;
  padding: 4px 12px;
}
</style>
