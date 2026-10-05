<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Pipeline Triggers & Runs — Durable, Event-Driven Execution',
  description: 'Bosca pipelines run from platform events, schedules, Studio, APIs, or other pipelines on a durable background queue. Runs suspend and resume across restarts, stream live progress, and log every outcome.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Pipelines', path: '/discover/pipelines' },
  { name: 'Triggers & runs', path: '/discover/pipelines/runs' }
], '/og-pipelines.png')
</script>

<template>
  <DiscoverShell section-id="pipelines">
    <section class="page-hero">
      <p class="kicker load-1">
        Triggers &amp; runs
      </p>
      <h1 class="load-2">
        Triggered. Durable. <em>Logged.</em>
      </h1>
      <p class="section-sub load-3">
        Start a pipeline from a platform event, a schedule, Studio, an API, or
        another pipeline. Every run uses the platform's background job
        infrastructure, survives restarts, streams live progress, and leaves an
        append-only history.
      </p>
    </section>

    <!-- ── Execution path ──────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The execution path
          </p>
          <h2>An event fires. <em>A job runs.</em></h2>
          <p class="section-sub">
            When an event fires it is matched against the active pipelines
            accepting its type, and one background job is enqueued per match —
            each pipeline gets its own independent run. The job runner decodes
            the typed event, executes the graph, and records the outcome.
          </p>
          <ul class="point-list">
            <li>Runs go through the durable job queue: a runner restart doesn't lose queued runs, and delivery is at-least-once — design webhook receivers to tolerate an occasional duplicate.</li>
            <li>Triggered runs execute under the pipeline <em>service account</em>; dry runs and manual runs execute under yours.</li>
            <li>The caller's identity is carried through the whole run — across suspensions and into sub-pipelines — for authorization and audit.</li>
          </ul>
        </div>
        <div class="code-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">graphql — run history</span>
          </div>
          <div class="code-body">
            <pre><code><span class="tok-kt">query</span> <span class="tok-interp">PipelineRuns</span><span class="tok-tag">(</span><span class="tok-attr">$id</span><span class="tok-tag">:</span> <span class="tok-kt">UUID!</span><span class="tok-tag">)</span> <span class="tok-tag">{</span>
  <span class="tok-interp">pipelines</span> <span class="tok-tag">{</span>
    <span class="tok-interp">runs</span><span class="tok-tag">(</span><span class="tok-attr">pipelineId:</span> <span class="tok-kt">$id</span><span class="tok-tag">,</span> <span class="tok-attr">limit:</span> <span class="tok-kt">50</span><span class="tok-tag">)</span> <span class="tok-tag">{</span>
      <span class="tok-str">eventName</span>
      <span class="tok-str">outcome</span>
      <span class="tok-str">startedAt</span>
      <span class="tok-str">durationMs</span>
      <span class="tok-str">errorMessage</span>
    <span class="tok-tag">}</span>
  <span class="tok-tag">}</span>
<span class="tok-tag">}</span></code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Durability ──────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Durability
          </p>
          <h2>Runs that <em>wait around</em></h2>
          <p class="section-sub">
            A run that hits a suspending node — Delay, Wait Until, or Execute
            Job with waiting enabled — parks durably and resumes when its
            condition is met, surviving restarts in between. A pipeline can
            wait a minute or a month; nothing is held in memory while it does.
          </p>
          <ul class="point-list">
            <li>The <code>pipelineRun</code> GraphQL subscription streams run-level transitions and per-node events — this powers Studio's live run view.</li>
            <li>Suspended runs report <code>SUSPENDED</code> with the run id, so callers can track them from run history or the live subscription.</li>
          </ul>
        </div>
        <div class="code-window timeline-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">a run, live</span>
          </div>
          <ul class="timeline">
            <li>
              <span class="tl-dot tl-running" />
              <span class="tl-status">RUNNING</span>
              <span class="tl-note">event decoded, graph executing</span>
            </li>
            <li>
              <span class="tl-dot tl-suspended" />
              <span class="tl-status">SUSPENDED</span>
              <span class="tl-note">Wait Until · publish window opens</span>
            </li>
            <li>
              <span class="tl-dot tl-running" />
              <span class="tl-status">RUNNING</span>
              <span class="tl-note">resumed — restarts didn't matter</span>
            </li>
            <li>
              <span class="tl-dot tl-ok" />
              <span class="tl-status">OK</span>
              <span class="tl-note">outcome recorded in run history</span>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <!-- ── Live run view screenshot ────────────── -->
    <section class="section shot-section">
      <div class="section-head reveal">
        <p class="kicker">
          The live run view
        </p>
        <h2>Watch the path <em>light up</em></h2>
        <p class="section-sub">
          Open any run and the graph replays it: nodes turn green as they
          finish, and the edges along the path the run actually took animate
          in the accent color — so you see exactly where the value flowed.
        </p>
      </div>
      <figure class="reveal">
        <div class="shot-window">
          <img
            :src="'/screenshots/pipelines-run-graph.png'"
            alt="A completed pipeline run in Bosca Studio: every node green, the taken path drawn as animated dashed edges"
            width="3000"
            height="2000"
            loading="lazy"
          >
        </div>
        <figcaption class="shot-caption">
          A completed run — the taken path drawn as animated edges, with a
          status legend.
        </figcaption>
      </figure>
    </section>

    <!-- ── Other ways in ───────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Other ways in
        </p>
        <h2>Events are the default, <em>not the only door</em></h2>
      </div>
      <div class="notes-grid reveal">
        <div class="note-card">
          <h3>Scheduled runs</h3>
          <p>
            Attach a standard five-field cron schedule to a pipeline. Bosca
            records when the run was scheduled, applies concurrency and rate
            limits, and sends it through the same durable lifecycle as any
            other run.
          </p>
        </div>
        <div class="note-card">
          <h3>Manual runs</h3>
          <p>
            The <em>Run</em> action in Studio — or the
            <code>pipelines.run</code> mutation — executes a pipeline
            immediately with an ad-hoc payload, under your own principal.
          </p>
        </div>
        <div class="note-card">
          <h3>REST endpoints</h3>
          <p>
            A pipeline with API enabled is callable at
            <code>/api/v1/p/{key}</code>. Non-public endpoints require an
            execute grant; a public one needs no authentication.
          </p>
        </div>
        <div class="note-card">
          <h3>From other pipelines</h3>
          <p>
            <em>Run Pipeline</em>, <em>For Each</em>, and
            <em>Run Pipeline for Segment</em> invoke other pipelines;
            <em>Dispatch Event</em> fans out to every active pipeline accepting
            the emitted event.
          </p>
        </div>
      </div>
    </section>

    <!-- ── Screenshot ──────────────────────────── -->
    <section class="section shot-section">
      <figure class="reveal">
        <div class="shot-window">
          <img
            :src="'/screenshots/pipelines-runs.png'"
            alt="Pipeline run history in Bosca Studio with outcomes, timing, and errors"
            width="2400"
            height="1500"
            loading="lazy"
          >
        </div>
        <figcaption class="shot-caption">
          Run history — every run with its outcome, timing, and any error.
        </figcaption>
      </figure>
    </section>

    <!-- ── History ─────────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Run history
        </p>
        <h2>"Did it run, and <em>what went wrong?"</em></h2>
        <p class="section-sub">
          Every run is recorded: the event that fired it, the outcome, when
          it started and finished, its duration, and the error message when a
          node failed. History is append-only and survives pipeline
          deletion-and-restore cycles; failed runs are retained for
          post-mortem inspection and swept on a configurable retention
          schedule.
        </p>
      </div>
    </section>

    <DiscoverPipelinesExplore />
  </DiscoverShell>
</template>

<style scoped>
.shot-section {
  padding-bottom: 70px;
}

/* ── Run timeline ────────────────────────────── */

.timeline {
  list-style: none;
  margin: 0;
  padding: 18px 8px;
}

.timeline li {
  position: relative;
  display: flex;
  align-items: baseline;
  gap: 14px;
  padding: 12px 20px 12px 44px;
}

.timeline li::before {
  content: '';
  position: absolute;
  left: 27px;
  top: 0;
  bottom: 0;
  width: 1px;
  background: var(--line-2);
}

.timeline li:first-child::before {
  top: 50%;
}

.timeline li:last-child::before {
  bottom: 50%;
}

.tl-dot {
  position: absolute;
  left: 22px;
  top: calc(50% - 5px);
  width: 11px;
  height: 11px;
  border-radius: 999px;
  border: 2px solid var(--fg-3);
  background: var(--bg-0);
}

.tl-running {
  border-color: var(--accent);
}

.tl-suspended {
  border-color: #ffb547;
}

.tl-ok {
  border-color: #34d99a;
  background: #34d99a;
}

.tl-status {
  flex-shrink: 0;
  width: 96px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
}

.tl-note {
  font-size: 12.5px;
  color: var(--fg-2);
}

/* ── Note cards ──────────────────────────────── */

.notes-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.note-card {
  padding: 20px 22px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.note-card h3 {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.note-card p {
  font-size: 13px;
  line-height: 1.7;
  color: var(--fg-2);
  margin: 0;
}

.note-card code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

@media (max-width: 960px) {
  .notes-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
