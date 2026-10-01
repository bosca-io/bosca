<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Git Platform Integration — Tasks, Sources, Events & Webhooks',
  description: 'Because Git is built into Bosca, commits and PRs auto-link to Work Ops tasks, scripts and queries are sourced from repos with the full review workflow, pipelines fire platform events, and webhooks deliver signed payloads.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Git', path: '/discover/git' },
  { name: 'Platform integration', path: '/discover/git/integration' }
], '/og-git.png')

const WEBHOOK_EVENTS = [
  'push', 'branch created', 'branch deleted', 'tag created', 'tag deleted',
  'PR opened', 'PR closed', 'PR merged', 'PR updated', 'review submitted',
  'pipeline started', 'pipeline completed'
]

const SOURCED = [
  { icon: 'code', name: 'Scripts', body: 'A Script Project repo backs the platform\'s Kotlin scripts.' },
  { icon: 'database', name: 'Queries', body: 'An Analytic Query Project repo backs your saved analytics SQL.' },
  { icon: 'workflow', name: 'Pipelines', body: 'A Pipeline Project repo backs event-driven automation graphs.' },
  { icon: 'wand', name: 'Agents', body: 'An Agent Project repo backs AI agents and their tools.' }
]
</script>

<template>
  <DiscoverShell section-id="git">
    <section class="page-hero">
      <p class="kicker load-1">
        Platform integration
      </p>
      <h1 class="load-2">
        The part a bolt-on <em>can't do</em>
      </h1>
      <p class="section-sub load-3">
        Because Git is built into Bosca rather than integrated after the fact,
        your repositories connect to everything else — the work they implement,
        the automation they hold, and the events they set off.
      </p>
    </section>

    <!-- ── Work Ops linking ────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Work Ops linking
          </p>
          <h2>From task <em>to commit and back</em></h2>
          <p class="section-sub">
            When a commit message, PR title, or branch name mentions a task key,
            the platform links them automatically — in both directions. Trace a
            task to every commit and PR that delivered it, and any commit back to
            the task that motivated it.
          </p>
          <ul class="point-list">
            <li>No plugin, no manual linking — the detection is built into the platform.</li>
            <li>The link is a first-class relationship, so it shows on both the task and the PR.</li>
          </ul>
        </div>
        <div class="link-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">auto-linked</span>
          </div>
          <div class="link-body">
            <div class="link-side">
              <span class="link-icon"><Icon
                name="kanban"
                :size="16"
              /></span>
              <span class="link-label">Task</span>
              <span class="link-ref">WEB-142</span>
            </div>
            <div class="link-chain">
              <Icon
                name="link"
                :size="16"
              />
            </div>
            <div class="link-side">
              <span class="link-icon"><Icon
                name="git-pull-request"
                :size="16"
              /></span>
              <span class="link-label">PR #248</span>
              <span class="link-ref">+ 3 commits</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Sourced from Git ────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Sourced from Git
        </p>
        <h2>Your automation, <em>version-controlled</em></h2>
        <p class="section-sub">
          A repository's content type tells the platform what it holds — so
          scripts, queries, pipelines, and agents can live in Git and gain
          version control, review, and the full pull-request workflow.
        </p>
      </div>
      <div class="sourced-grid reveal">
        <article
          v-for="s in SOURCED"
          :key="s.name"
          class="sourced-card"
        >
          <span class="sourced-icon">
            <Icon
              :name="s.icon"
              :size="16"
            />
          </span>
          <h3>{{ s.name }}</h3>
          <p>{{ s.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── Events & webhooks ───────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Events out
          </p>
          <h2>When Git moves, <em>the platform reacts</em></h2>
          <p class="section-sub">
            A finished pipeline can fire a platform event — publish content,
            invalidate a cache, kick off a deploy, send a notification. And for
            anything outside Bosca, webhooks POST a signed payload to a URL you
            choose, on the events you pick.
          </p>
          <ul class="point-list">
            <li>Every webhook payload is signed with a per-hook secret, so a receiver can verify it's genuine.</li>
            <li>An active/inactive toggle pauses delivery without losing the configuration.</li>
          </ul>
        </div>
        <div class="hook-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">webhook · events</span>
          </div>
          <div class="hook-body">
            <div class="hook-chips">
              <span
                v-for="ev in WEBHOOK_EVENTS"
                :key="ev"
                class="hook-chip"
              >{{ ev }}</span>
            </div>
            <div class="hook-foot">
              <Icon
                name="shield-check"
                :size="13"
              />
              signed · <code>X-Hub-Signature-256</code>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Unified permissions ─────────────────── -->
    <section class="section">
      <div class="perm-band reveal">
        <span class="perm-icon"><Icon
          name="users"
          :size="20"
        /></span>
        <div class="perm-text">
          <h3>One permission system</h3>
          <p>
            Repository access uses the same group-based permissions as the rest
            of Bosca — no separate user directory to maintain, no second place
            to grant or revoke access.
          </p>
        </div>
      </div>
    </section>

    <DiscoverGitExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Work Ops link window ────────────────────── */

.link-window,
.hook-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.link-body {
  display: flex;
  align-items: stretch;
  gap: 12px;
  padding: 26px 22px;
}

.link-side {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 20px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  text-align: center;
}

.link-icon {
  width: 34px;
  height: 34px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #cbd5e1;
}

.link-label {
  font-size: 13px;
  font-weight: 650;
  color: var(--fg-0);
}

.link-ref {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.link-chain {
  display: flex;
  align-items: center;
  color: var(--accent);
}

/* ── Sourced cards ───────────────────────────── */

.sourced-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.sourced-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.sourced-card:hover {
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  transform: translateY(-2px);
}

.sourced-icon {
  width: 32px;
  height: 32px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #cbd5e1;
  margin-bottom: 12px;
}

.sourced-card h3 {
  font-size: 14px;
  font-weight: 650;
  margin: 0 0 7px;
}

.sourced-card p {
  font-size: 12px;
  line-height: 1.55;
  color: var(--fg-2);
  margin: 0;
}

/* ── Webhook window ──────────────────────────── */

.hook-body {
  padding: 18px;
}

.hook-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.hook-chip {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-1);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 5px 11px;
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.hook-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.hook-foot code {
  color: var(--accent);
}

/* ── Unified permissions band ────────────────── */

.perm-band {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 28px 30px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.perm-icon {
  width: 46px;
  height: 46px;
  flex-shrink: 0;
  border-radius: var(--r-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #cbd5e1;
}

.perm-text h3 {
  font-size: 16px;
  font-weight: 650;
  margin: 0 0 6px;
}

.perm-text p {
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-2);
  margin: 0;
  max-width: 720px;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 820px) {
  .sourced-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .sourced-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .link-body {
    flex-direction: column;
  }

  .link-chain {
    justify-content: center;
    transform: rotate(90deg);
  }

  .perm-band {
    flex-direction: column;
    text-align: center;
  }
}
</style>
