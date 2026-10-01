<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Git — Self-hosted repositories, woven into the platform',
  description: 'Bosca Git is a self-hosted Git server with pull requests, code review, branch protection, and CI/CD — and it links commits to tasks, sources scripts and queries from repos, and fires platform events, all under your own infrastructure.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Git', path: '/discover/git' }
], '/og-git.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Host & browse',
    body: 'Push standard Git repositories backed by object storage. Browse the tree, search content and paths, and compare any two refs — all in Studio, all under your own infrastructure.'
  },
  {
    step: '02',
    title: 'Review & merge',
    body: 'Open pull requests, leave inline comments, and record verdicts. Branch protection turns review approvals and passing checks into the gate a merge has to clear.'
  },
  {
    step: '03',
    title: 'Build & ship',
    body: 'A YAML pipeline in the repo runs on your own CI agents, reports commit statuses back to the branch, and can fire a platform event to publish, deploy, or notify.'
  }
]

const FEATURES = [
  {
    icon: 'server',
    title: 'Self-hosted',
    body: 'A full Git server inside your platform, backed by S3-compatible object storage so instances scale horizontally with no shared local disk.'
  },
  {
    icon: 'git-pull-request',
    title: 'Pull requests & review',
    body: 'Draft PRs, inline diff comments with threads, and three reviewer verdicts — approve, request changes, or comment — driving the merge decision.'
  },
  {
    icon: 'shield-check',
    title: 'Branch protection',
    body: 'Pattern-scoped rules gate critical branches: require a PR, a minimum number of approvals, code-owner review, linear history, and passing checks.'
  },
  {
    icon: 'rocket',
    title: 'CI/CD pipelines',
    body: 'Repository YAML defines jobs, matrices, schedules, artifacts, releases, and promotion gates, with targeted reruns and cancellation when work needs attention.'
  },
  {
    icon: 'server',
    title: 'Your own agents',
    body: 'Register runners, boot ephemeral agents through an orchestrator, or dispatch durable Kubernetes jobs. Labels route each job to the right profile.'
  },
  {
    icon: 'circle-check',
    title: 'Commit statuses',
    body: 'Builds and external tools report named statuses on a commit; branch protection can require specific ones to be green before a merge.'
  },
  {
    icon: 'share-2',
    title: 'Webhooks',
    body: 'Twelve event types — push, branch and tag, pull-request, review, and pipeline lifecycle — POST signed payloads to any endpoint you choose.'
  },
  {
    icon: 'link',
    title: 'Woven into the platform',
    body: 'Commits and PRs auto-link to Work Ops tasks, scripts and queries are sourced from repos, and pipelines fire platform events.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="git"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Git
        </p>
        <h1 class="load-2">
          Your code,<br>
          <em>fully integrated.</em>
        </h1>
        <p class="hero-sub load-3">
          Bosca Git is a self-hosted Git server with pull requests, code review,
          branch protection, and CI/CD — woven into the rest of the platform, so
          commits link to tasks, repos source scripts and queries, and builds
          fire events.
        </p>
        <div class="hero-ctas load-4">
          <a
            href="#how"
            class="btn btn-primary"
          >
            How it works
          </a>
        </div>
      </div>

      <div
        class="hero-visual load-4"
        aria-hidden="true"
      >
        <div class="pr-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">#248 · Add live sessions map</span>
          </div>
          <div class="pr-body">
            <div class="pr-branch">
              <Icon
                name="git-pull-request"
                :size="14"
                class="pr-icon"
              />
              <span><code>feature/live-map</code> → <code>main</code></span>
            </div>
            <div class="pr-checks">
              <div class="check-row">
                <span class="check-dot ok" />
                <span class="check-name">ci/build</span>
                <span class="check-state ok">success</span>
              </div>
              <div class="check-row">
                <span class="check-dot ok" />
                <span class="check-name">ci/test</span>
                <span class="check-state ok">success</span>
              </div>
              <div class="check-row">
                <span class="check-dot review" />
                <span class="check-name">2 approvals</span>
                <span class="check-state ok">met</span>
              </div>
            </div>
            <button class="pr-merge">
              Squash and merge
            </button>
            <div class="pr-link">
              <Icon
                name="link"
                :size="12"
              />
              linked to <code>WEB-142</code>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── How it works ────────────────────────── -->
    <section
      id="how"
      class="section"
    >
      <div class="section-head reveal">
        <p class="kicker">
          How it works
        </p>
        <h2>Push, review, <em>ship.</em></h2>
      </div>
      <div class="how-grid reveal">
        <article
          v-for="item in HOW_IT_WORKS"
          :key="item.step"
          class="how-card"
        >
          <span class="how-step">{{ item.step }}</span>
          <h3>{{ item.title }}</h3>
          <p>{{ item.body }}</p>
        </article>
      </div>
    </section>

    <!-- ── Feature wall ────────────────────────── -->
    <section
      id="features"
      class="features"
    >
      <div class="features-inner">
        <div class="section-head reveal">
          <p class="kicker">
            What makes it powerful
          </p>
          <h2>A real Git host. <em>And part of the platform.</em></h2>
          <p class="section-sub">
            Everything you expect from code hosting — pull requests, protection,
            CI/CD — plus the integrations you only get when Git isn't a separate
            product bolted on.
          </p>
        </div>
        <div class="feature-grid">
          <article
            v-for="feature in FEATURES"
            :key="feature.title"
            class="feature-card reveal"
          >
            <span class="feature-icon">
              <Icon
                :name="feature.icon"
                :size="16"
              />
            </span>
            <h3>{{ feature.title }}</h3>
            <p>{{ feature.body }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- ── Integration ─────────────────────────── -->
    <section class="section integ">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Deep platform integration
          </p>
          <h2>Not a silo — <em>part of the whole</em></h2>
          <p class="section-sub">
            Because Git isn't a separate product bolted onto Bosca, it connects
            to everything else. Your repositories, your work, and your
            automation share one system — one set of permissions, one graph of
            links.
          </p>
          <ul class="point-list">
            <li>Commit messages, PR titles, and branch names that mention a task auto-link it — trace a task to its commits, and any commit back to its task.</li>
            <li>Scripts and analytics queries live in repos, so your automation gets version control, review, and the full PR workflow.</li>
            <li>A pipeline can fire a platform event on completion — publish content, invalidate a cache, kick off a deploy.</li>
            <li>Repository access uses the same group-based permissions as the rest of Bosca — no separate user directory.</li>
          </ul>
        </div>
        <div class="integ-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">connected</span>
          </div>
          <div class="integ-body">
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="kanban"
                :size="15"
              /></span>
              <span class="integ-text">Commit <code>a1b0c7</code> → task <code>WEB-142</code></span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="code"
                :size="15"
              /></span>
              <span class="integ-text">Script <code>nightly-sync</code> sourced from <code>ops/</code></span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="workflow"
                :size="15"
              /></span>
              <span class="integ-text">Pipeline done → <code>content.publish</code> event</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="users"
                :size="15"
              /></span>
              <span class="integ-text">Access via the <code>engineering</code> group</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverGitExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#94a3b8"
        class="closing-mark"
      />
      <h2>Git ships <em>with Bosca</em></h2>
      <p class="section-sub">
        Self-hosted, Object Storage, and horizontally scalable — the code lives next
        to the work it implements and the automation it drives. The docs cover
        repositories, pull requests, CI/CD, and platform integration end to end.
      </p>
    </section>
  </DiscoverShell>
</template>

<style scoped>
/* ── Hero ────────────────────────────────────── */

.hero {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
  align-items: center;
  gap: 48px;
  max-width: 1140px;
  margin: 0 auto;
  padding: 64px 32px 100px;
}

.hero h1 {
  font-size: clamp(40px, 5.4vw, 64px);
  font-weight: 700;
}

.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 999px;
  padding: 6px 14px;
  margin-bottom: 26px;
}

.eyebrow-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--accent);
  box-shadow: 0 0 10px var(--accent);
  animation: discover-pulse 2.4s ease-in-out infinite;
}

.hero-sub {
  font-size: 16.5px;
  line-height: 1.7;
  color: var(--fg-2);
  max-width: 470px;
  margin: 26px 0 34px;
}

.hero-ctas {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

/* ── PR window ───────────────────────────────── */

.pr-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.pr-body {
  padding: 18px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.pr-branch {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--fg-1);
}

.pr-branch code,
.pr-link code,
.integ-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

.pr-icon {
  color: #94a3b8;
}

.pr-checks {
  display: flex;
  flex-direction: column;
  gap: 2px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 6px;
}

.check-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
}

.check-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
}

.check-dot.ok { background: #34d99a; }
.check-dot.review { background: #94a3b8; }

.check-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.check-state {
  font-family: var(--font-mono);
  font-size: 11px;
}

.check-state.ok { color: #34d99a; }

.pr-merge {
  width: 100%;
  padding: 11px;
  border-radius: var(--r-sm);
  border: none;
  background: #34d99a;
  color: #05130c;
  font-size: 13.5px;
  font-weight: 650;
  font-family: inherit;
}

.pr-link {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--fg-3);
}

/* ── How it works ────────────────────────────── */

.how-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.how-card {
  padding: 24px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.how-step {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent);
}

.how-card h3 {
  font-size: 16.5px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 12px 0 10px;
}

.how-card p {
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--fg-2);
  margin: 0;
}

/* ── Feature wall ────────────────────────────── */

.features {
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--bg-1) 45%, transparent);
}

.features-inner {
  max-width: 1140px;
  margin: 0 auto;
  padding: 80px 32px 90px;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.feature-card {
  padding: 20px 18px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-0) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.feature-card:hover {
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  transform: translateY(-2px);
}

.feature-icon {
  width: 30px;
  height: 30px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #cbd5e1;
  margin-bottom: 14px;
}

.feature-card h3 {
  font-size: 14px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.feature-card p {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

/* ── Integration ─────────────────────────────── */

.integ {
  padding-top: 90px;
}

.integ-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.integ-body {
  padding: 10px 8px;
}

.integ-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
}

.integ-row + .integ-row {
  border-top: 1px solid var(--line);
}

.integ-icon {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--accent) 20%, transparent);
  color: #cbd5e1;
}

.integ-text {
  font-size: 13px;
  color: var(--fg-1);
}

/* ── Closing ─────────────────────────────────── */

.closing {
  max-width: 640px;
  margin: 0 auto;
  padding: 40px 32px 110px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.closing h2 {
  font-size: clamp(30px, 4vw, 44px);
  font-weight: 700;
  margin-top: 18px;
}

.closing .section-sub {
  margin-bottom: 26px;
}

.closing-mark {
  animation: discover-bob 7s ease-in-out infinite;
}

.btn-cta {
  padding: 13px 34px;
  font-size: 15px;
}

/* ── Motion & responsive ─────────────────────── */

@media (prefers-reduced-motion: reduce) {
  .eyebrow-dot,
  .closing-mark {
    animation: none;
  }
}

@media (max-width: 960px) {
  .hero {
    grid-template-columns: minmax(0, 1fr);
    padding-top: 36px;
    padding-bottom: 64px;
  }

  .how-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .feature-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .feature-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
