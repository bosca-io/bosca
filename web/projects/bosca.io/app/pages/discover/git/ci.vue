<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Git CI/CD — Pipelines on Your Own Agents',
  description: 'Define CI in repository YAML, run it on registered, ephemeral, or Kubernetes agents, and operate it with schedules, targeted retries and cancellation, artifacts, release gates, and commit statuses.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Git', path: '/discover/git' },
  { name: 'CI/CD', path: '/discover/git/ci' }
], '/og-git.png')
</script>

<template>
  <DiscoverShell section-id="git">
    <section class="page-hero">
      <p class="kicker load-1">
        CI/CD
      </p>
      <h1 class="load-2">
        Build and ship, <em>on your machines</em>
      </h1>
      <p class="section-sub load-3">
        A pipeline is YAML checked into the repo. The server discovers it,
        schedules its jobs, and dispatches them to CI agents you run — then the
        results report back to the branch as commit statuses.
      </p>
    </section>

    <!-- ── The pipeline ────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The pipeline
          </p>
          <h2>YAML in, <em>a run out</em></h2>
          <p class="section-sub">
            Jobs declare which agent they need, what they depend on, and an
            ordered list of steps. Matrixed jobs fan out across values, each step
            records its status, exit code, and timing, and a run moves from
            queued to running to a final outcome you can watch live.
          </p>
          <ul class="point-list">
            <li>Jobs depend on other jobs, so a run is a graph, not just a list.</li>
            <li>Steps publish artifacts — named, sized, and retained by policy — for later jobs or other repos.</li>
            <li>Pipeline secrets are set per repo, name-only and write-only, and injected at run time.</li>
            <li>Run on repository activity, on demand, or from a five-field cron schedule kept in the same YAML.</li>
          </ul>
        </div>
        <div class="run-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">run #512 · main</span>
          </div>
          <div class="run-body">
            <div class="job-row">
              <span class="job-dot ok" />
              <span class="job-name">build</span>
              <span class="job-agent">linux · x64</span>
              <span class="job-state ok">success</span>
            </div>
            <div class="job-row">
              <span class="job-dot ok" />
              <span class="job-name">test <span class="job-matrix">×3</span></span>
              <span class="job-agent">linux</span>
              <span class="job-state ok">success</span>
            </div>
            <div class="job-row">
              <span class="job-dot run" />
              <span class="job-name">deploy</span>
              <span class="job-agent">prod-readonly</span>
              <span class="job-state run">running</span>
            </div>
            <div class="run-foot">
              <Icon
                name="circle-check"
                :size="13"
              />
              reports <code>ci/build</code>, <code>ci/test</code>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Your own agents ─────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Your own agents
          </p>
          <h2>Runners you <em>control</em></h2>
          <p class="section-sub">
            Jobs run on agents you register — nothing executes on hardware you
            don't own. An agent registers once with a one-time token, then pulls
            work it's eligible for. Free-text labels route each job to the right
            machine.
          </p>
          <ul class="point-list">
            <li>A <strong>runner</strong> executes jobs directly; an <strong>orchestrator</strong> boots ephemeral runners on demand — cloud VMs that report back and tear down.</li>
            <li>Runner profiles can also dispatch durable Kubernetes jobs with an ephemeral agent for each CI job.</li>
            <li>Labels like <code>linux</code>, <code>gpu</code>, or <code>prod-readonly</code> match a job's requirement to an online agent.</li>
            <li>Agents show online, busy, draining, or offline — with the exact step a busy one is running.</li>
          </ul>
        </div>
        <div class="agents-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">agents</span>
          </div>
          <div class="agents-body">
            <div class="ag-row">
              <span class="ag-dot busy" />
              <span class="ag-name">runner-01</span>
              <span class="ag-labels">linux · x64 · docker</span>
              <span class="ag-state busy">busy</span>
            </div>
            <div class="ag-row">
              <span class="ag-dot online" />
              <span class="ag-name">runner-02</span>
              <span class="ag-labels">linux · gpu</span>
              <span class="ag-state online">online</span>
            </div>
            <div class="ag-row">
              <span class="ag-dot online" />
              <span class="ag-name">orchestrator</span>
              <span class="ag-labels">boots ephemeral VMs</span>
              <span class="ag-state online">online</span>
            </div>
            <div class="ag-row ag-ephemeral">
              <span class="ag-dot busy" />
              <span class="ag-name">vm-9f3c <span class="ag-tag">ephemeral</span></span>
              <span class="ag-labels">large</span>
              <span class="ag-state busy">busy</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Artifact requirements ───────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Cross-repo builds
          </p>
          <h2>Wait for what <em>another build makes</em></h2>
          <p class="section-sub">
            A job can declare that it needs an artifact from the registry before
            it dispatches — even one produced by a different repository. The
            coordinates resolve when the run is created, a version prefix like
            <code>6.0.*</code> matches any patch, and the job waits, then
            dispatches once the last requirement is published.
          </p>
          <ul class="point-list">
            <li>Multi-repo release trains coordinate through artifacts instead of brittle timing.</li>
            <li>If a requirement never arrives, the job times out and names exactly what was missing.</li>
          </ul>
        </div>
        <div class="artifact-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">job · package</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-tag">requires:</span>
  - <span class="tok-attr">artifact:</span> <span class="tok-str">core-server</span>
    <span class="tok-attr">version:</span> <span class="tok-interp">6.0.*</span>
  - <span class="tok-attr">artifact:</span> <span class="tok-str">web-bundle</span>
    <span class="tok-attr">version:</span> <span class="tok-interp">6.0.*</span>
<span class="tok-com"># waits, then dispatches on publish</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Operate and release ─────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Operate and release
        </p>
        <h2>Recover the run. <em>Control the release.</em></h2>
        <p class="section-sub">
          CI operations stay at the level where the problem happened. Restart
          a failed job without replaying successful work, stop work that should
          no longer run, and move artifacts through release and promotion gates
          with the evidence attached.
        </p>
      </div>
      <div class="ops-grid reveal">
        <article class="ops-card">
          <span class="ops-icon"><Icon
            name="rotate-ccw"
            :size="15"
          /></span>
          <h3>Targeted recovery</h3>
          <p>Re-run one job or only the failed jobs, cancel a queued or active job, and delete terminal runs when their record is no longer needed.</p>
        </article>
        <article class="ops-card">
          <span class="ops-icon"><Icon
            name="calendar"
            :size="15"
          /></span>
          <h3>Scheduled CI</h3>
          <p>Put a cron schedule beside the jobs in repository YAML and let Bosca create the run at the appointed time.</p>
        </article>
        <article class="ops-card">
          <span class="ops-icon"><Icon
            name="shield-check"
            :size="15"
          /></span>
          <h3>Release and promotion</h3>
          <p>Publish native release artifacts, promote an existing build, and keep approval and verification gates in the pipeline that ships it.</p>
        </article>
      </div>
    </section>

    <DiscoverGitExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.run-window,
.agents-window,
.artifact-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Run window ──────────────────────────────── */

.run-body {
  padding: 10px 8px 8px;
}

.job-row {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
}

.job-row + .job-row {
  border-top: 1px solid var(--line);
}

.job-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
}

.job-dot.ok { background: #34d99a; }
.job-dot.run { background: #f6c453; animation: discover-pulse 1.6s ease-in-out infinite; }

.job-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-1);
}

.job-matrix {
  color: var(--fg-3);
  font-size: 11px;
}

.job-agent {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.job-state {
  font-family: var(--font-mono);
  font-size: 11px;
  min-width: 58px;
  text-align: right;
}

.job-state.ok { color: #34d99a; }
.job-state.run { color: #f6c453; }

.run-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 12px 14px 8px;
  border-top: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

.run-foot code {
  color: var(--fg-2);
}

/* ── Agents window ───────────────────────────── */

.agents-body {
  padding: 10px 8px;
}

.ag-row {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
}

.ag-row + .ag-row {
  border-top: 1px solid var(--line);
}

.ag-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
}

.ag-dot.online { background: #34d99a; }
.ag-dot.busy { background: #f6c453; }

.ag-name {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
  display: flex;
  align-items: center;
  gap: 7px;
}

.ag-tag {
  font-size: 9px;
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 50%, transparent);
  border-radius: 3px;
  padding: 0 4px;
}

.ag-labels {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  text-align: right;
}

.ag-state {
  font-family: var(--font-mono);
  font-size: 10.5px;
  min-width: 48px;
  text-align: right;
}

.ag-state.online { color: #34d99a; }
.ag-state.busy { color: #f6c453; }

.ag-ephemeral {
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

/* ── Operations ─────────────────────────────── */

.ops-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.ops-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 50%, transparent);
}

.ops-icon {
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

.ops-card h3 {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 650;
}

.ops-card p {
  margin: 0;
  color: var(--fg-2);
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 760px) {
  .ops-grid { grid-template-columns: 1fr; }
}
</style>
