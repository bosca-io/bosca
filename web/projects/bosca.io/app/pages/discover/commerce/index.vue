<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Commerce — Sell anything, on your own platform',
  description: 'Bosca Commerce is a full commerce engine — stores, catalogs, carts, payments, subscriptions, and fulfillment — built into the platform, so products are content documents, customers are real profiles, and every sale can drive automation.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Commerce', path: '/discover/commerce' }
], '/og-commerce.png')

const NAV_LINKS = [
  { label: 'How it works', href: '#how' },
  { label: 'Capabilities', href: '#features' },
  { label: 'Go deeper', href: '#deeper' }
]

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Stock the shelf',
    body: 'Create products and tell their story with the same editor you use for the rest of your content. Price them in catalogs, and open a store in the currency you sell in.'
  },
  {
    step: '02',
    title: 'Sell & settle',
    body: 'Shoppers fill carts that hold their items and keep totals current. Checkout takes payment through your own provider — promotion codes, taxes, and shipping included.'
  },
  {
    step: '03',
    title: 'Fulfill & follow through',
    body: 'Paid orders come with packing suggestions, labels, and tracking — while subscriptions renew on schedule.'
  }
]

const FEATURES = [
  {
    icon: 'file-text',
    title: 'Products are content',
    body: 'Write a product\'s name, story, and imagery with the same editor as the rest of your content. Shoppers see the version you published.'
  },
  {
    icon: 'globe',
    title: 'Multi-currency by design',
    body: 'Price each catalog in its own currency and open a store for each market. An order stays in the currency the shopper saw, start to finish.'
  },
  {
    icon: 'boxes',
    title: 'Live inventory',
    body: 'In a cart, it\'s held; paid for, it\'s promised; shipped, it\'s gone. Counts move with the orders themselves.'
  },
  {
    icon: 'credit-card',
    title: 'Pay in parts',
    body: 'One order can settle across several payments — a card now, store credit on top, a check that clears later. Refunds go back the same ways.'
  },
  {
    icon: 'tag',
    title: 'Promotions with guardrails',
    body: 'Coded offers — a percentage off, an amount off, free shipping, buy-one-get-one — with start and end dates, total caps, and per-buyer limits.'
  },
  {
    icon: 'repeat',
    title: 'Subscriptions built in',
    body: 'Sell plans right in the cart next to everything else. Renewals bill saved payment methods on schedule and follow the retry policy you set.'
  },
  {
    icon: 'package',
    title: 'Packing & shipping',
    body: 'Bosca suggests how to box each order from your own box sizes. Shipping is quoted and labeled through your carriers, and tracking follows every parcel to the door.'
  },
  {
    icon: 'shield-check',
    title: 'One audit trail',
    body: 'Every change — a price edit, a refund, a stock correction — is recorded with who did it and exactly what changed.'
  }
]
</script>

<template>
  <DiscoverShell
    section-id="commerce"
    :links="NAV_LINKS"
  >
    <!-- ── Hero ────────────────────────────────── -->
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow load-1">
          <span class="eyebrow-dot" /> Bosca Commerce
        </p>
        <h1 class="load-2">
          Sell anything,<br>
          <em>on your own platform.</em>
        </h1>
        <p class="hero-sub load-3">
          Bosca Commerce is a full commerce engine — stores, catalogs, carts,
          payments, subscriptions, and fulfillment — built into the platform,
          so products are content documents, customers are real profiles, and
          every sale can drive automation.
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
        <div class="order-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">order · 2 minutes ago</span>
          </div>
          <div class="order-body">
            <div class="order-flags">
              <span class="flag-chip ok">PAID</span>
              <span class="flag-chip">SHIPPING</span>
            </div>
            <div class="order-lines">
              <div class="order-line">
                <span class="line-name">Field Notes Journal <span class="line-qty">×1</span></span>
                <span class="line-amt">24.00</span>
              </div>
              <div class="order-line">
                <span class="line-name">Canvas Tote <span class="line-qty">×1</span></span>
                <span class="line-amt">18.00</span>
              </div>
              <div class="order-line">
                <span class="line-name">Shipping — Priority</span>
                <span class="line-amt">8.45</span>
              </div>
            </div>
            <div class="order-totals">
              <span>Tax 3.68</span>
              <span class="total-paid">Paid 54.13</span>
            </div>
            <div class="order-link">
              <Icon
                name="file-text"
                :size="12"
              />
              content <code>field-notes.doc</code> · v7 published
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
        <h2>Stock, sell, <em>follow through.</em></h2>
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
          <h2>A real commerce engine. <em>And part of the platform.</em></h2>
          <p class="section-sub">
            Everything you expect from a store backend — carts, payments,
            subscriptions, fulfillment — plus what you only get when commerce
            isn't a separate product bolted on.
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
            Because commerce isn't bolted onto Bosca, it shares the systems
            everything else uses: content, identity, events, permissions, and
            one audit trail.
          </p>
          <ul class="point-list">
            <li>Publish, and the shelf updates — product pages go through the same review and publish flow as the rest of your content.</li>
            <li>Your shoppers are people the platform already knows — one sign-in, one profile, no second user directory to maintain.</li>
            <li>A paid order, a refund, or a renewal can kick off automation — a pipeline picks up the event and sends the thank-you, syncs the ledger, or updates the dashboard.</li>
            <li>Administration works like the rest of Bosca — the same permissions, and one audit trail that remembers every change.</li>
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
                name="file-text"
                :size="15"
              /></span>
              <span class="integ-text"><code>field-notes.doc</code> v7 published → storefront advanced</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="users"
                :size="15"
              /></span>
              <span class="integ-text">Customer resolved from profile <code>jane</code></span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="workflow"
                :size="15"
              /></span>
              <span class="integ-text"><code>cart.paid</code> event → pipeline run</span>
            </div>
            <div class="integ-row">
              <span class="integ-icon"><Icon
                name="history"
                :size="15"
              /></span>
              <span class="integ-text">Price <code>26.00 → 24.00</code> · audited, before &amp; after</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Go deeper ───────────────────────────── -->
    <div id="deeper">
      <DiscoverCommerceExplore
        title="Go deeper"
      />
    </div>

    <!-- ── Closing ─────────────────────────────── -->
    <section class="closing reveal">
      <BoscaMark
        :size="56"
        color="#a670f4"
        class="closing-mark"
      />
      <h2>Commerce ships <em>with Bosca</em></h2>
      <p class="section-sub">
        An optional module of the platform: enable it and the whole engine is
        there — stores, catalogs, carts, payments, subscriptions, and
        fulfillment — under the same permissions, events, and audit trail as
        everything else.
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

/* ── Order window ────────────────────────────── */

.order-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow:
    0 24px 60px rgba(0, 0, 0, 0.45),
    0 0 80px color-mix(in srgb, var(--accent) 12%, transparent);
}

.order-body {
  padding: 18px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.order-flags {
  display: flex;
  gap: 8px;
}

.flag-chip {
  font-family: var(--font-mono);
  font-size: 10.5px;
  letter-spacing: 0.06em;
  color: var(--fg-2);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 3px 10px;
}

.flag-chip.ok {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 45%, transparent);
}

.order-lines {
  display: flex;
  flex-direction: column;
  gap: 2px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 6px;
}

.order-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
}

.line-name {
  font-size: 13px;
  color: var(--fg-1);
}

.line-qty {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
}

.line-amt {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.order-totals {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-2);
  padding: 0 4px;
}

.total-paid {
  color: #34d99a;
}

.order-link {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--fg-3);
}

.order-link code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
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

.integ-text code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
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
