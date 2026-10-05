<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Commerce Catalog — Stores, Products & Pricing',
  description: 'Stores sell one catalog in one currency. Product pages are real content, prices live in catalogs with availability windows and an audit trail, and coded promotions carry per-buyer limits.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Commerce', path: '/discover/commerce' },
  { name: 'Catalog & pricing', path: '/discover/commerce/catalog' }
], '/og-commerce.png')

const PRODUCT_TYPES = [
  { name: 'Physical', note: 'ships from inventory' },
  { name: 'Virtual', note: 'no fulfillment' },
  { name: 'Service', note: 'no fulfillment' },
  { name: 'Subscription', note: 'recurring purchase' },
  { name: 'Shipping', note: 'carries shipping charges' },
  { name: 'Promotion', note: 'carries a discount' }
]
</script>

<template>
  <DiscoverShell section-id="commerce">
    <section class="page-hero">
      <p class="kicker load-1">
        Catalog &amp; pricing
      </p>
      <h1 class="load-2">
        Stores, products, <em>and prices</em>
      </h1>
      <p class="section-sub load-3">
        Set up a store, fill its catalog, and price everything in the
        currency you sell in. Product pages are real content, and coded
        offers bring people in.
      </p>
    </section>

    <!-- ── Stores & catalogs ───────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Stores &amp; catalogs
          </p>
          <h2>One store, one catalog, <em>one currency</em></h2>
          <p class="section-sub">
            The catalog decides what a store sells, at what prices, in what
            currency. The same catalog can back several stores — a store
            never sells more than one.
          </p>
          <ul class="point-list">
            <li><strong>Online or in person</strong> — a store can be a web storefront or a physical point of sale.</li>
            <li><strong>Catalogs carry the prices</strong> — the same product can sit in several catalogs at different prices, in different currencies.</li>
            <li><strong>Each store sets its own policy</strong> — its payment provider, its shipping charges, and how long abandoned carts stick around.</li>
            <li><strong>One currency per store</strong> — every order is in the store's currency, start to finish.</li>
          </ul>
        </div>
        <div class="config-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">store settings</span>
          </div>
          <div class="config-body">
            <div class="cfg-row">
              <span class="cfg-key">Identifier</span>
              <span class="cfg-val cfg-pill">north-shop</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Catalog</span>
              <span class="cfg-val cfg-pill">Spring 2026 · USD</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Payment provider</span>
              <span class="cfg-val cfg-pill">stripe</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Cart expiration</span>
              <span class="cfg-val cfg-on">24h</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Content-backed products ─────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Products
          </p>
          <h2>The product is commerce; <em>the story is content</em></h2>
          <p class="section-sub">
            A product's name, story, and imagery are content — written,
            reviewed, and published with the same tools as everything else
            you make. The product itself keeps the facts: brand, SKU, size,
            weight.
          </p>
          <ul class="point-list">
            <li><strong>Brands and SKUs</strong> — every product belongs to a manufacturer and carries its own SKU under that brand.</li>
            <li><strong>Weight &amp; size</strong> — recorded once on the product, used for shipping quotes and packing suggestions.</li>
            <li><strong>Products know their kind</strong> — a clothing product carries its sizes; a subscription product carries its plans.</li>
            <li><strong>Publish to update the shelf</strong> — the storefront shows the published version, and updates the moment a new one goes live.</li>
          </ul>
        </div>
        <div class="product-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">product · Field Notes Journal</span>
          </div>
          <div class="product-body">
            <div class="prod-facts">
              <div class="prod-fact">
                <span class="pf-key">SKU</span>
                <span class="pf-val">FN-JRNL-02</span>
              </div>
              <div class="prod-fact">
                <span class="pf-key">Type</span>
                <span class="pf-val">PHYSICAL</span>
              </div>
              <div class="prod-fact">
                <span class="pf-key">Weight</span>
                <span class="pf-val">0.4</span>
              </div>
              <div class="prod-fact">
                <span class="pf-key">Size</span>
                <span class="pf-val">21 × 14 × 2</span>
              </div>
            </div>
            <div class="prod-doc">
              <Icon
                name="file-text"
                :size="13"
              />
              <span><code>field-notes.doc</code> · v7 published · pinned</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Product types ───────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Product types
        </p>
        <h2>The type drives <em>what happens after the sale</em></h2>
        <p class="section-sub">
          Sell goods, services, downloads, or subscriptions. Physical
          products ship from stock, virtual ones need no shipping, and a
          subscription starts billing on schedule right from checkout.
        </p>
      </div>
      <div class="ptype-grid reveal">
        <div
          v-for="pt in PRODUCT_TYPES"
          :key="pt.name"
          class="ptype-card"
        >
          <h3>{{ pt.name }}</h3>
          <span class="ptype-note">{{ pt.note }}</span>
        </div>
      </div>
    </section>

    <!-- ── Pricing ─────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Pricing
          </p>
          <h2>Priced in the catalog, <em>honored in the cart</em></h2>
          <p class="section-sub">
            Put a product in a catalog at a price and it's on sale — from
            when to when, taxed or not, with optional quantity limits per
            order.
          </p>
          <ul class="point-list">
            <li><strong>On sale exactly when you want</strong> — each entry has a start and an end, and the storefront shows what's on sale right now.</li>
            <li><strong>Price changes leave a trail</strong> — every edit records the before and the after.</li>
            <li><strong>Open carts keep their price</strong> — a shopper checks out at the price they added, even if it has changed since.</li>
          </ul>
        </div>
        <div class="pricing-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">catalog · Spring 2026 · USD</span>
          </div>
          <div class="pricing-body">
            <div class="price-row">
              <span class="price-name">Field Notes Journal</span>
              <span class="price-state on">active</span>
              <span class="price-amt">24.00</span>
            </div>
            <div class="price-row">
              <span class="price-name">Canvas Tote</span>
              <span class="price-state on">active</span>
              <span class="price-amt">18.00</span>
            </div>
            <div class="price-row">
              <span class="price-name">Letterpress Print</span>
              <span class="price-state">starts Aug 1</span>
              <span class="price-amt">32.00</span>
            </div>
            <div class="price-audit">
              <Icon
                name="history"
                :size="12"
              />
              <span>Journal <code>26.00 → 24.00</code> · audited</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Promotions ──────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Promotions
          </p>
          <h2>Offers with <em>guardrails</em></h2>
          <p class="section-sub">
            A promotion is a coded offer scoped to a store — the code a buyer
            types at checkout, a rule, an active window, and limits that keep
            it from running away.
          </p>
          <ul class="point-list">
            <li><strong>Six kinds of offer</strong> — a percentage off, an amount off, free shipping, buy-one-get-one, and discounts on subscription renewals.</li>
            <li><strong>On a schedule</strong> — a code works while its window is open.</li>
            <li><strong>Redemption limits</strong> — a total cap, plus per-buyer limits (counted against their account) by day, week, month, year, or ever.</li>
          </ul>
        </div>
        <div class="promo-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">promotion</span>
          </div>
          <div class="config-body">
            <div class="cfg-row">
              <span class="cfg-key">Code</span>
              <span class="cfg-val cfg-pill">SPRING10</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Rule</span>
              <span class="cfg-val cfg-pill">10% off cart</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Window</span>
              <span class="cfg-val">Mar 1 → Mar 31</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Per account</span>
              <span class="cfg-val cfg-on">1 · forever</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Redeemed</span>
              <span class="cfg-val">132 / 500</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <DiscoverCommerceExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Config & promo windows ──────────────────── */

.config-window,
.promo-window,
.product-window,
.pricing-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

.config-body {
  padding: 10px 8px;
}

.cfg-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
}

.cfg-row + .cfg-row {
  border-top: 1px solid var(--line);
}

.cfg-key {
  font-size: 13px;
  color: var(--fg-2);
}

.cfg-val {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.cfg-pill {
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 2px 10px;
}

.cfg-on {
  color: #34d99a;
}

/* ── Product window ──────────────────────────── */

.product-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.prod-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.prod-fact {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 10px 12px;
}

.pf-key {
  font-size: 11px;
  color: var(--fg-3);
}

.pf-val {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.prod-doc {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--fg-2);
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: var(--r-sm);
  padding: 10px 12px;
}

.prod-doc code,
.price-audit code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-0);
  background: color-mix(in srgb, var(--fg-3) 14%, transparent);
  border-radius: 4px;
  padding: 1px 5px;
}

/* ── Product type cards ──────────────────────── */

.ptype-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.ptype-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 16px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.ptype-card:hover {
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  transform: translateY(-2px);
}

.ptype-card h3 {
  font-size: 13.5px;
  font-weight: 650;
  margin: 0;
}

.ptype-note {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

/* ── Pricing window ──────────────────────────── */

.pricing-body {
  padding: 10px 8px;
}

.price-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}

.price-row + .price-row {
  border-top: 1px solid var(--line);
}

.price-name {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
}

.price-state {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.price-state.on {
  color: #34d99a;
}

.price-amt {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.price-audit {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--fg-3);
  border-top: 1px solid var(--line);
  padding: 12px 14px 6px;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 820px) {
  .ptype-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 480px) {
  .ptype-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .prod-facts {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
