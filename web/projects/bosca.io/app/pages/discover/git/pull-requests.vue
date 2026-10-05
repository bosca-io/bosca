<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Git Pull Requests — Review, Merge & Branch Protection',
  description: 'Propose changes with draft PRs, review with inline comments and three verdicts, merge with four strategies, and gate critical branches with pattern-scoped protection rules — required approvals, code-owner review, linear history, and passing checks.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Git', path: '/discover/git' },
  { name: 'Pull requests', path: '/discover/git/pull-requests' }
], '/og-git.png')

const MERGE_STRATEGIES = [
  { name: 'Merge commit', note: 'keeps both histories with a merge commit' },
  { name: 'Squash', note: 'collapses the branch into one commit' },
  { name: 'Rebase', note: 'replays the commits onto the target' },
  { name: 'Fast forward', note: 'moves the target ref, strict descendant only' }
]

const RULES = [
  'Require a pull request',
  'A minimum number of approvals',
  'Dismiss stale approvals on new commits',
  'Require code-owner review',
  'Require linear history',
  'Require named status checks to pass'
]
</script>

<template>
  <DiscoverShell section-id="git">
    <section class="page-hero">
      <p class="kicker load-1">
        Pull requests
      </p>
      <h1 class="load-2">
        Propose, review, <em>merge</em>
      </h1>
      <p class="section-sub load-3">
        Every change lands through a pull request: open it as a draft, gather
        inline feedback, collect the approvals that count, and merge with the
        strategy your repo allows — with branch protection deciding when a merge
        is actually allowed.
      </p>
    </section>

    <!-- ── Review ──────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Review
          </p>
          <h2>Feedback <em>on the line</em></h2>
          <p class="section-sub">
            Reviewers comment directly on a diff line, and each comment opens a
            thread you can resolve once it's addressed. A review carries a
            verdict, and the verdict is what moves the PR forward — or holds it.
          </p>
          <ul class="point-list">
            <li><strong>Approve</strong> counts toward the required approvals; <strong>Request changes</strong> blocks the merge; <strong>Comment</strong> leaves feedback without a verdict.</li>
            <li>A draft PR can't merge until you mark it ready.</li>
            <li>Conflicts are detected and listed by file; rebase or merge in the latest target, then re-push.</li>
          </ul>
        </div>
        <div class="review-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">review · Map.kt</span>
          </div>
          <div class="review-body">
            <div class="rv-diff">
              <span class="dl dl-ctx">  fun publish(session: Session) {</span>
              <span class="dl dl-add">+   liveSessions.emit(session.geo)</span>
              <span class="dl dl-ctx">  }</span>
            </div>
            <div class="rv-comment">
              <span class="rv-avatar">M</span>
              <div class="rv-thread">
                <span class="rv-author">Maria <span class="rv-line">on line 2</span></span>
                <p>Guard for a null geo before emitting?</p>
                <span class="rv-resolved">✓ resolved</span>
              </div>
            </div>
            <div class="rv-verdicts">
              <span class="verdict v-approve">✓ Approve</span>
              <span class="verdict v-changes">Request changes</span>
              <span class="verdict v-comment">Comment</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Merge strategies ────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Merging
        </p>
        <h2>Four ways to <em>land it</em></h2>
        <p class="section-sub">
          Which strategies are on offer is set by the repository and narrowed by
          branch protection — so a merge is only ever done the way you allow.
        </p>
      </div>
      <div class="merge-grid reveal">
        <div
          v-for="m in MERGE_STRATEGIES"
          :key="m.name"
          class="merge-card"
        >
          <span class="merge-icon"><Icon
            name="git-merge"
            :size="16"
          /></span>
          <h3>{{ m.name }}</h3>
          <p>{{ m.note }}</p>
        </div>
      </div>
    </section>

    <!-- ── Branch protection ───────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Branch protection
          </p>
          <h2>Rules that <em>hold the line</em></h2>
          <p class="section-sub">
            A protection rule targets branches by name or glob — <code>main</code>,
            <code>release/*</code>, <code>feature/**</code> — and turns your
            standards into gates a merge has to clear. Direct pushes are blocked;
            everything goes through a reviewed, checked PR.
          </p>
          <ul class="point-list">
            <li>Force pushes and branch deletion are blocked by default; opt in only where you mean to.</li>
            <li>Required status checks name the exact contexts — <code>ci/build</code>, <code>ci/test</code> — that must be green.</li>
            <li>Each rule is scoped to a branch pattern, so <code>main</code> and <code>release/*</code> can carry different standards.</li>
          </ul>
        </div>
        <div class="rules-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">protection · main</span>
          </div>
          <ul class="rules">
            <li
              v-for="rule in RULES"
              :key="rule"
            >
              <Icon
                name="shield-check"
                :size="14"
                class="rule-icon"
              />
              {{ rule }}
            </li>
          </ul>
        </div>
      </div>
    </section>

    <DiscoverGitExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Review window ───────────────────────────── */

.review-window,
.rules-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.review-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.rv-diff {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  overflow: hidden;
}

.dl {
  font-family: var(--font-mono);
  font-size: 11.5px;
  padding: 5px 12px;
  white-space: pre;
}

.dl-ctx { color: var(--fg-3); }
.dl-add { color: #34d99a; background: color-mix(in srgb, #34d99a 10%, transparent); }

.rv-comment {
  display: flex;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.rv-avatar {
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  color: #05130c;
  background: #94a3b8;
}

.rv-thread {
  flex: 1;
}

.rv-author {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--fg-1);
}

.rv-line {
  font-family: var(--font-mono);
  font-size: 10.5px;
  font-weight: 400;
  color: var(--fg-3);
}

.rv-thread p {
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--fg-2);
  margin: 5px 0 8px;
}

.rv-resolved {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: #34d99a;
}

.rv-verdicts {
  display: flex;
  gap: 8px;
}

.verdict {
  font-size: 11.5px;
  border-radius: 999px;
  padding: 5px 12px;
  border: 1px solid var(--line-2);
  color: var(--fg-2);
}

.verdict.v-approve {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 45%, transparent);
  background: color-mix(in srgb, #34d99a 10%, transparent);
}

/* ── Merge strategy cards ────────────────────── */

.merge-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.merge-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.merge-icon {
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

.merge-card h3 {
  font-size: 14px;
  font-weight: 650;
  margin: 0 0 6px;
}

.merge-card p {
  font-size: 12px;
  line-height: 1.55;
  color: var(--fg-2);
  margin: 0;
}

/* ── Rules window ────────────────────────────── */

.rules {
  list-style: none;
  margin: 0;
  padding: 12px 10px;
}

.rules li {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 12px;
  font-size: 13px;
  color: var(--fg-1);
}

.rules li + li {
  border-top: 1px solid var(--line);
}

.rule-icon {
  color: #34d99a;
  flex-shrink: 0;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 820px) {
  .merge-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 480px) {
  .merge-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
