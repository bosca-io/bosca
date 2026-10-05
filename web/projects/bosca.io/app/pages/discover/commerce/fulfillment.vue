<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Commerce Fulfillment — Inventory, Packing & Shipments',
  description: 'Live stock counts per warehouse, per-warehouse shipments with packing suggestions from your own box sizes, label purchase through shipping providers, and tracking to the door.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Commerce', path: '/discover/commerce' },
  { name: 'Fulfillment', path: '/discover/commerce/fulfillment' }
], '/og-commerce.png')
</script>

<template>
  <DiscoverShell section-id="commerce">
    <section class="page-hero">
      <p class="kicker load-1">
        Fulfillment &amp; shipping
      </p>
      <h1 class="load-2">
        From warehouse <em>to doorstep</em>
      </h1>
      <p class="section-sub load-3">
        Live stock counts per warehouse, packing suggestions built from your
        own box sizes, and shipments with labels and tracking, followed to
        the door.
      </p>
    </section>

    <!-- ── Inventory ───────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Inventory
          </p>
          <h2>Stock, <em>counted for you</em></h2>
          <p class="section-sub">
            Stock is counted per product, per warehouse — and the counts move
            with the orders themselves: carts, checkouts, shipments, returns.
          </p>
          <ul class="point-list">
            <li><strong>Held, promised, shipped</strong> — a cart holds units, checkout promises them, shipping takes them off the shelf. Abandoned carts give them back.</li>
            <li><strong>Refunds restock</strong> — refunded and returned goods go back on the shelf at their warehouse.</li>
            <li><strong>Corrections apply to unsold stock</strong> — units already paid for stay reserved until they ship.</li>
            <li><strong>Syncing respects orders</strong> — counts pulled from an outside system keep what's already sold reserved, and hand-managed warehouses are left alone.</li>
          </ul>
        </div>
        <div class="inv-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">inventory · FN-JRNL-02 · East Warehouse</span>
          </div>
          <div class="inv-body">
            <div class="inv-cell">
              <span class="ic-key">On hand</span>
              <span class="ic-val">120</span>
            </div>
            <div class="inv-cell">
              <span class="ic-key">In cart</span>
              <span class="ic-val">3</span>
            </div>
            <div class="inv-cell">
              <span class="ic-key">Pending</span>
              <span class="ic-val">9</span>
            </div>
            <div class="inv-cell">
              <span class="ic-key">Available</span>
              <span class="ic-val ok">108</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Warehouses ──────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Fulfillment centers
          </p>
          <h2>Ship from <em>every warehouse you have</em></h2>
          <p class="section-sub">
            A warehouse holds stock and hands finished orders to a carrier.
            When an order is paid, it splits naturally — one shipment from
            each warehouse that stocks its items.
          </p>
          <ul class="point-list">
            <li><strong>Managed by hand or connected</strong> — sync stock from an outside system, or let the platform keep counts correct on its own.</li>
            <li><strong>Each warehouse has its carrier</strong> — its shipping provider quotes the rates, buys the labels, and reports the tracking.</li>
          </ul>
        </div>
        <div class="center-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">fulfillment center</span>
          </div>
          <div class="cfg-body">
            <div class="cfg-row">
              <span class="cfg-key">Name</span>
              <span class="cfg-val cfg-pill">East Warehouse</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Connector</span>
              <span class="cfg-val cfg-pill">manual</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Shipping provider</span>
              <span class="cfg-val cfg-pill">shippo</span>
            </div>
            <div class="cfg-row">
              <span class="cfg-key">Address</span>
              <span class="cfg-val">Columbus, OH</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Packing ─────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            Packing
          </p>
          <h2>Packing, <em>suggested for you</em></h2>
          <p class="section-sub">
            You keep a catalog of your box sizes — outer size, empty weight,
            what fits inside, how much each can carry. Bosca suggests how to
            box each shipment from them; your warehouse does the packing.
          </p>
          <ul class="point-list">
            <li><strong>A concrete plan</strong> — each suggested parcel lists what goes inside it, so shipping quotes and labels are based on the actual boxing.</li>
            <li><strong>Unpackable items get flagged</strong> — an item that fits no box holds its shipment for attention.</li>
            <li><strong>Fix and repack</strong> — add a box size that fits and repack; the plan rebuilds.</li>
          </ul>
        </div>
        <div class="pack-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">shipment · East Warehouse</span>
          </div>
          <div class="pack-body">
            <div class="parcel">
              <div class="parcel-head">
                <Icon
                  name="package"
                  :size="13"
                />
                <span>Box M · 30 × 22 × 12</span>
              </div>
              <div class="parcel-items">
                <span>FN-JRNL-02 ×2</span>
                <span>TOTE-CANVAS ×1</span>
              </div>
            </div>
            <div class="parcel">
              <div class="parcel-head">
                <Icon
                  name="package"
                  :size="13"
                />
                <span>Box S · 18 × 14 × 6</span>
              </div>
              <div class="parcel-items">
                <span>PRINT-LP-01 ×1</span>
              </div>
            </div>
            <div class="pack-note ok">
              suggested packing · 2 parcels
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Shipping & tracking ─────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Shipping &amp; tracking
          </p>
          <h2>Shipped once, <em>followed to the door</em></h2>
          <p class="section-sub">
            Shipping is one step: hand the shipment a carrier and tracking
            number, or let the warehouse's provider buy the label and assign
            one. From there, tracking takes over.
          </p>
          <ul class="point-list">
            <li><strong>Shipping updates the shelf</strong> — shipped units come off the books, and the order moves toward complete.</li>
            <li><strong>Tracking follows the parcel</strong> — shipped, in transit, out for delivery, delivered.</li>
            <li><strong>Refreshed for you</strong> — every in-flight shipment is checked automatically; delivered ones stop being checked.</li>
            <li><strong>The carrier's words are kept</strong> — the shipment records what the carrier said, and when it was delivered.</li>
          </ul>
        </div>
        <div class="track-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">tracking · 9400 1118 9956 …</span>
          </div>
          <div class="track-body">
            <div class="track-step done">
              <span class="ts-dot" />
              <span class="ts-name">Shipped</span>
              <span class="ts-note">label purchased</span>
            </div>
            <div class="track-step done">
              <span class="ts-dot" />
              <span class="ts-name">In transit</span>
              <span class="ts-note">departed Columbus OH</span>
            </div>
            <div class="track-step active">
              <span class="ts-dot" />
              <span class="ts-name">Out for delivery</span>
              <span class="ts-note">this morning</span>
            </div>
            <div class="track-step">
              <span class="ts-dot" />
              <span class="ts-name">Delivered</span>
              <span class="ts-note">—</span>
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

.inv-window,
.center-window,
.pack-window,
.track-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Inventory window ────────────────────────── */

.inv-body {
  padding: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.inv-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 12px 14px;
}

.ic-key {
  font-size: 11px;
  color: var(--fg-3);
}

.ic-val {
  font-family: var(--font-mono);
  font-size: 17px;
  color: var(--fg-0);
}

.ic-val.ok {
  color: #34d99a;
}

/* ── Center window ───────────────────────────── */

.cfg-body {
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

/* ── Pack window ─────────────────────────────── */

.pack-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.parcel {
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.parcel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--fg-1);
}

.parcel-items {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
}

.pack-note {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 2px 4px;
}

.pack-note.ok {
  color: #34d99a;
}

/* ── Track window ────────────────────────────── */

.track-body {
  padding: 14px 16px 16px;
  display: flex;
  flex-direction: column;
}

.track-step {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 2px;
}

.track-step + .track-step {
  border-top: 1px solid var(--line);
}

.ts-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
  background: var(--fg-3);
}

.track-step.done .ts-dot {
  background: #34d99a;
}

.track-step.active .ts-dot {
  background: var(--accent);
  box-shadow: 0 0 8px var(--accent);
}

.ts-name {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
}

.track-step.active .ts-name {
  color: var(--fg-0);
  font-weight: 600;
}

.ts-note {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 480px) {
  .inv-body {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
