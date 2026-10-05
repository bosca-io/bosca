<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Commerce Subscriptions — Plans, Renewals & App-Store Purchases',
  description: 'Plan groups define the offer; checkout or your team creates the subscription; renewals bill saved payment methods on schedule and follow your retry policy. App-store purchases grant the same subscriptions.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Commerce', path: '/discover/commerce' },
  { name: 'Subscriptions', path: '/discover/commerce/subscriptions' }
], '/og-commerce.png')
</script>

<template>
  <DiscoverShell section-id="commerce">
    <section class="page-hero">
      <p class="kicker load-1">
        Subscriptions
      </p>
      <h1 class="load-2">
        Subscriptions, <em>right in the cart</em>
      </h1>
      <p class="section-sub load-3">
        Plans sell in the same cart as your products — and once someone
        subscribes, renewals happen on schedule, at the price they signed
        up at.
      </p>
    </section>

    <!-- ── Plans & groups ──────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Plans &amp; groups
          </p>
          <h2>A group of plans, <em>one subscription each</em></h2>
          <p class="section-sub">
            Plans come in groups — monthly, annual, tiers of the same
            offering. The group also sets how billing retries when a card
            fails.
          </p>
          <ul class="point-list">
            <li><strong>Any schedule</strong> — weekly, monthly, yearly, or anything in between.</li>
            <li><strong>One subscription per group</strong> — an account holds a single subscription in a group; they don't stack.</li>
            <li><strong>You set the retry policy</strong> — how many failed payment attempts before a subscription is marked unpaid.</li>
            <li><strong>Plans can retire</strong> — close a plan to new signups without touching the people already on it.</li>
          </ul>
        </div>
        <div class="plans-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">plan group · Pro</span>
          </div>
          <div class="plans-body">
            <div class="group-meta">
              one live subscription per account · 3 retries
            </div>
            <div class="plan-row">
              <span class="plan-key">pro-monthly</span>
              <span class="plan-price">9.99 / month</span>
              <span class="plan-state on">active</span>
            </div>
            <div class="plan-row">
              <span class="plan-key">pro-annual</span>
              <span class="plan-price">99.00 / year</span>
              <span class="plan-state on">active</span>
            </div>
            <div class="plan-row">
              <span class="plan-key">pro-legacy</span>
              <span class="plan-price">7.99 / month</span>
              <span class="plan-state">inactive</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Two ways in ─────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Signing up
          </p>
          <h2>Through the cart, <em>or by hand</em></h2>
          <p class="section-sub">
            Put a plan in the catalog and it sells at checkout like anything
            else — the buyer picks the plan, pays, and the subscription
            starts. Your team can also set one up directly.
          </p>
          <ul class="point-list">
            <li><strong>Checkout keeps the card</strong> — the payment method is saved, so renewals just work.</li>
            <li><strong>The price is set at signup</strong> — existing subscribers keep their price, even when the plan changes later.</li>
            <li><strong>Set up by hand when needed</strong> — subscribe an account directly, with a saved payment method for renewals.</li>
            <li><strong>The paper trail is kept</strong> — a subscription sold at checkout links back to the order that started it.</li>
          </ul>
        </div>
        <div class="sub-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">subscription</span>
          </div>
          <div class="sub-body">
            <div class="sub-head">
              <span class="sub-plan">pro-monthly</span>
              <span class="sub-status on">ACTIVE</span>
            </div>
            <div class="sub-meta">
              <div class="sub-cell">
                <span class="sc-key">Price</span>
                <span class="sc-val">9.99 / month</span>
              </div>
              <div class="sub-cell">
                <span class="sc-key">Renews</span>
                <span class="sc-val">Aug 12</span>
              </div>
              <div class="sub-cell">
                <span class="sc-key">Renewals</span>
                <span class="sc-val">6</span>
              </div>
              <div class="sub-cell">
                <span class="sc-key">Failures</span>
                <span class="sc-val">0</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── The renewal engine ──────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Renewals
          </p>
          <h2>Renewals <em>run themselves</em></h2>
          <p class="section-sub">
            Due subscriptions are billed automatically through the store's
            payment provider. You decide the schedule and the retry policy;
            billing follows it.
          </p>
          <ul class="point-list">
            <li><strong>Past due, then unpaid</strong> — a declined payment is retried on a delay; once the group's retry limit is reached, billing stops and the subscription is marked unpaid.</li>
            <li><strong>Success moves the date</strong> — the next billing date advances one interval and the failure count clears.</li>
          </ul>
        </div>
        <div class="renew-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">renewal sweep</span>
          </div>
          <div class="renew-body">
            <div class="renew-row">
              <span class="renew-dot ok" />
              <span class="renew-name">pro-monthly</span>
              <span class="renew-note ok">renewed · 9.99</span>
            </div>
            <div class="renew-row">
              <span class="renew-dot warn" />
              <span class="renew-name">team-annual</span>
              <span class="renew-note warn">declined → past due, retrying</span>
            </div>
            <div class="renew-row">
              <span class="renew-dot" />
              <span class="renew-name">pro-annual</span>
              <span class="renew-note">provider outage · retried next run</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── App-store purchases ─────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            App-store purchases
          </p>
          <h2>Bought in the app, <em>honored everywhere</em></h2>
          <p class="section-sub">
            Purchases made through Apple or Google grant the same
            subscriptions: the app hands over its receipt, the store confirms
            it, and the subscription starts or extends.
          </p>
          <ul class="point-list">
            <li><strong>One receipt, one grant</strong> — each store purchase is redeemed once.</li>
            <li><strong>Extends what's there</strong> — a receipt for an active subscription extends it rather than starting a second one.</li>
          </ul>
        </div>
        <div class="iap-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">receipt redemption</span>
          </div>
          <div class="renew-body">
            <div class="renew-row">
              <span class="renew-dot ok" />
              <span class="renew-name">App Store receipt</span>
              <span class="renew-note ok">verified → granted</span>
            </div>
            <div class="renew-row">
              <span class="renew-dot ok" />
              <span class="renew-name">Play receipt</span>
              <span class="renew-note ok">verified → extended</span>
            </div>
            <div class="renew-row">
              <span class="renew-dot warn" />
              <span class="renew-name">Replayed receipt</span>
              <span class="renew-note warn">already redeemed</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverCommerceExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Window shells ───────────────────────────── */

.plans-window,
.sub-window,
.renew-window,
.iap-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Plans window ────────────────────────────── */

.plans-body {
  padding: 10px 8px 8px;
}

.group-meta {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
  padding: 6px 14px 10px;
  border-bottom: 1px solid var(--line);
}

.plan-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
}

.plan-row + .plan-row {
  border-top: 1px solid var(--line);
}

.plan-key {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.plan-price {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
}

.plan-state {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.plan-state.on {
  color: #34d99a;
}

/* ── Subscription window ─────────────────────── */

.sub-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.sub-plan {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-0);
}

.sub-status {
  font-family: var(--font-mono);
  font-size: 10.5px;
  letter-spacing: 0.06em;
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 3px 10px;
  color: var(--fg-2);
}

.sub-status.on {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 45%, transparent);
}

.sub-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.sub-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 10px 12px;
}

.sc-key {
  font-size: 11px;
  color: var(--fg-3);
}

.sc-val {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

/* ── Renewal / IAP rows ──────────────────────── */

.renew-body {
  padding: 10px 8px;
}

.renew-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 14px;
}

.renew-row + .renew-row {
  border-top: 1px solid var(--line);
}

.renew-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
  background: var(--fg-3);
}

.renew-dot.ok { background: #34d99a; }
.renew-dot.warn { background: #f6c453; }

.renew-name {
  flex: 1;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.renew-note {
  font-size: 11.5px;
  color: var(--fg-3);
}

.renew-note.ok { color: #34d99a; }
.renew-note.warn { color: #f6c453; }

/* ── Responsive ──────────────────────────────── */

@media (max-width: 480px) {
  .sub-meta {
    grid-template-columns: minmax(0, 1fr);
  }

  .renew-row {
    flex-wrap: wrap;
  }
}
</style>
