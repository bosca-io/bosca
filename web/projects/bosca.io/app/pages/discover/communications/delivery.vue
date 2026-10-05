<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Delivery Status — Know your messages got through',
  description: 'Every Bosca email reports its outcome per recipient — delivered, deferred, bounced, or dropped — fed by the provider so a silent failure becomes something you can catch and fix. Hard bounces suppress the address automatically.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Communications', path: '/discover/communications' },
  { name: 'Delivery Status', path: '/discover/communications/delivery' }
], '/og-communications.png')

const OUTCOMES = [
  { label: 'On the way', tone: 'neutral', items: ['Pending', 'Sent', 'Deferred'] },
  { label: 'Arrived', tone: 'ok', items: ['Delivered'] },
  { label: 'Didn\'t arrive', tone: 'bad', items: ['Bounced', 'Dropped', 'Failed'] },
  { label: 'Their call', tone: 'muted', items: ['Spam report', 'Unsubscribed'] }
]
</script>

<template>
  <DiscoverShell section-id="communications">
    <section class="page-hero">
      <p class="kicker load-1">
        Delivery Status
      </p>
      <h1 class="load-2">
        Know it <em>got through</em>
      </h1>
      <p class="section-sub load-3">
        Send an important email and you shouldn't have to wonder whether it
        arrived. Each send reports its outcome for every recipient — delivered,
        or the reason it wasn't — so a failure you'd otherwise never notice
        becomes something you can fix.
      </p>
    </section>

    <!-- ── Per recipient ───────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            One send, many outcomes
          </p>
          <h2>See who <em>it reached</em></h2>
          <p class="section-sub">
            A message to a group rarely succeeds or fails all at once — one
            address bounces while the rest go through. Delivery status is kept
            per recipient, so a single bad address stands out instead of hiding
            in a &quot;sent&quot; count.
          </p>
          <ul class="point-list">
            <li>Each recipient's copy carries its own status, not just the batch.</li>
            <li>A bounce or a drop is surfaced, not buried — you can act on it.</li>
            <li>Attempts are counted, so a transient failure and a permanent one look different.</li>
          </ul>
        </div>
        <div class="deliv-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">password-reset · 3 recipients</span>
          </div>
          <div class="deliv-body">
            <div class="deliv-row">
              <span class="deliv-to">ada@example.com</span>
              <span class="deliv-state ok">delivered</span>
            </div>
            <div class="deliv-row">
              <span class="deliv-to">sam@example.com</span>
              <span class="deliv-state bad">bounced</span>
            </div>
            <div class="deliv-row">
              <span class="deliv-to">lee@example.com</span>
              <span class="deliv-state ok">delivered</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Where status comes from ─────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Straight from the provider
          </p>
          <h2>The mail server <em>reports back</em></h2>
          <p class="section-sub">
            You don't have to guess. SendGrid reports signed delivery events as
            a message moves — accepted, delivered, bounced, marked as spam, or
            unsubscribed — and each one is written to an append-only record
            where the latest word wins. Provider attempts and failures remain
            visible for every send.
          </p>
          <ul class="point-list">
            <li>SendGrid events arrive over a signed webhook and update the status.</li>
            <li>A hard bounce doesn't just get recorded — it suppresses the address so you stop sending to it.</li>
            <li>Opens and clicks are noted when the provider reports them, but it's the delivery outcome that tells you a message got through.</li>
          </ul>
        </div>
        <div class="events-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">delivery events</span>
          </div>
          <div class="events-body">
            <div class="event-row">
              <span class="event-dot ok" />
              <span class="event-name">accepted</span>
              <span class="event-arrow">→ sent</span>
            </div>
            <div class="event-row">
              <span class="event-dot ok" />
              <span class="event-name">delivered</span>
              <span class="event-arrow">→ delivered</span>
            </div>
            <div class="event-row">
              <span class="event-dot bad" />
              <span class="event-name">bounce · hard</span>
              <span class="event-arrow">→ suppressed</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Outcomes ────────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          What can happen
        </p>
        <h2>A message's <em>possible ends</em></h2>
        <p class="section-sub">
          From the moment it's queued, a send lands in one of a handful of
          honest outcomes — most arrive, some don't, and a few reflect a choice
          the recipient made.
        </p>
      </div>
      <div class="outcome-grid reveal">
        <div
          v-for="o in OUTCOMES"
          :key="o.label"
          class="outcome-col"
        >
          <span
            class="outcome-label"
            :class="o.tone"
          >{{ o.label }}</span>
          <span
            v-for="i in o.items"
            :key="i"
            class="outcome-chip"
          >{{ i }}</span>
        </div>
      </div>
    </section>

    <DiscoverCommunicationsExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared windows ──────────────────────────── */

.deliv-window,
.events-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Delivery window ─────────────────────────── */

.deliv-body {
  padding: 12px 10px;
}

.deliv-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 14px;
}

.deliv-row + .deliv-row {
  border-top: 1px solid var(--line);
}

.deliv-to {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.deliv-state {
  font-family: var(--font-mono);
  font-size: 11px;
  border-radius: 999px;
  padding: 3px 10px;
  border: 1px solid var(--line-2);
}

.deliv-state.ok {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.deliv-state.bad {
  color: #f2757f;
  border-color: color-mix(in srgb, #f2757f 40%, transparent);
}

/* ── Events window ───────────────────────────── */

.events-body {
  padding: 12px 10px;
}

.event-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 14px;
}

.event-row + .event-row {
  border-top: 1px solid var(--line);
}

.event-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
}

.event-dot.ok { background: #34d99a; }
.event-dot.bad { background: #f2757f; }

.event-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.event-arrow {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

/* ── Outcome grid ────────────────────────────── */

.outcome-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.outcome-col {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 18px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
}

.outcome-label {
  font-family: var(--font-mono);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 4px;
}

.outcome-label.neutral { color: var(--fg-2); }
.outcome-label.ok { color: #34d99a; }
.outcome-label.bad { color: #f2757f; }
.outcome-label.muted { color: var(--fg-3); }

.outcome-chip {
  font-size: 13px;
  color: var(--fg-1);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 760px) {
  .outcome-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
