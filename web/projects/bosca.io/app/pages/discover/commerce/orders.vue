<script setup lang="ts">
definePageMeta({ layout: false })

useSeoMeta({
  title: 'Commerce Orders — Carts, Checkout, Refunds & Returns',
  description: 'The cart is the order: checkout that can settle across several payments, refunds and price corrections on the order itself, a structured returns workflow, and store credit.'
})

useDiscoverSeo([
  { name: 'Bosca', path: '/' },
  { name: 'Commerce', path: '/discover/commerce' },
  { name: 'Carts & orders', path: '/discover/commerce/orders' }
], '/og-commerce.png')

const BUYER_CARDS = [
  {
    icon: 'user',
    title: 'Customers',
    body: 'Who is shopping. Your shoppers are people the platform already knows — one sign-in, one profile, no second user directory to maintain.'
  },
  {
    icon: 'users',
    title: 'Accounts',
    body: 'Who is paying. Orders, subscriptions, and store credit belong to an account — a household can share one, and one person can keep several.'
  },
  {
    icon: 'credit-card',
    title: 'Store credit',
    body: 'A stored balance on every account — spend it at checkout, refund to it, top it up, with every change recorded. Numbered gift credits work at checkout too.'
  }
]
</script>

<template>
  <DiscoverShell section-id="commerce">
    <section class="page-hero">
      <p class="kicker load-1">
        Carts, checkout &amp; orders
      </p>
      <h1 class="load-2">
        The cart <em>is the order</em>
      </h1>
      <p class="section-sub load-3">
        The cart someone fills is the order you fulfill. The items, the
        addresses, and every payment stay together — so refunds, returns, and
        fulfillment all happen in one place, not in a second system.
      </p>
    </section>

    <!-- ── The cart ────────────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            The cart
          </p>
          <h2>Totals that are <em>always current</em></h2>
          <p class="section-sub">
            Add a line, change an address, apply a code — every change
            recalculates discounts, tax, shipping, and what's still due.
          </p>
          <ul class="point-list">
            <li><strong>An order can be two things at once</strong> — paid <em>and</em> shipping, for example. The happy path: open → pending → paid → shipping → complete.</li>
            <li><strong>Lines snapshot their price</strong> — the catalog price is captured at add-time; promotions, tax, and shipping refine the final line price.</li>
            <li><strong>Adding a stocked product holds inventory</strong> — quantity changes re-reserve, removals release, and an expired cart gives its holds back.</li>
            <li><strong>Idle carts expire</strong> — carts left open past the store's policy are reclaimed automatically, and their holds go back on the shelf.</li>
          </ul>
        </div>
        <div class="cart-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">cart · north-shop</span>
          </div>
          <div class="cart-body">
            <div class="cart-flags">
              <span class="flag-chip ok">PAID</span>
              <span class="flag-chip">PENDING</span>
              <span class="flag-chip">SHIPPING</span>
            </div>
            <div class="totals-grid">
              <div class="total-cell">
                <span class="tc-key">Subtotal</span>
                <span class="tc-val">42.00</span>
              </div>
              <div class="total-cell">
                <span class="tc-key">Discounts</span>
                <span class="tc-val">−4.20</span>
              </div>
              <div class="total-cell">
                <span class="tc-key">Shipping</span>
                <span class="tc-val">8.45</span>
              </div>
              <div class="total-cell">
                <span class="tc-key">Tax</span>
                <span class="tc-val">3.31</span>
              </div>
              <div class="total-cell">
                <span class="tc-key">Paid</span>
                <span class="tc-val ok">49.56</span>
              </div>
              <div class="total-cell">
                <span class="tc-key">Due</span>
                <span class="tc-val">0.00</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Checkout ────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Checkout
          </p>
          <h2>One order, <em>several payments</em></h2>
          <p class="section-sub">
            Checkout takes payment for what's due — and it can take several:
            a card now, store credit on top, a check that clears later.
          </p>
          <ul class="point-list">
            <li><strong>Addresses are required</strong> — physical orders need a shipping address; payments need a billing address.</li>
            <li><strong>Checks clear later</strong> — a check counts as pending until someone confirms it arrived, and only then as paid.</li>
            <li><strong>Store credit spends like money</strong> — account balances and numbered gift credits are accepted right at checkout.</li>
          </ul>
        </div>
        <div class="pay-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">payments</span>
          </div>
          <div class="pay-body">
            <div class="pay-row">
              <span class="pay-dot ok" />
              <span class="pay-name">Card ·· 4242</span>
              <span class="pay-state ok">confirmed</span>
              <span class="pay-amt">25.00</span>
            </div>
            <div class="pay-row">
              <span class="pay-dot ok" />
              <span class="pay-name">Store credit</span>
              <span class="pay-state ok">confirmed</span>
              <span class="pay-amt">10.00</span>
            </div>
            <div class="pay-row">
              <span class="pay-dot wait" />
              <span class="pay-name">Check #1042</span>
              <span class="pay-state">pending</span>
              <span class="pay-amt">12.00</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Admin recourse ──────────────────────── -->
    <section class="section">
      <div class="split reveal">
        <div class="split-copy">
          <p class="kicker">
            When plans change
          </p>
          <h2>Fix problems <em>on the order itself</em></h2>
          <p class="section-sub">
            No parallel case system, no detached credit memos — you fix the
            order where it lives, and every action recalculates what's owed
            and is recorded.
          </p>
          <ul class="point-list">
            <li><strong>Item refunds</strong> — refund one unit or several: stock goes back on the shelf, and the money goes back to the original payment, to store credit, or by check.</li>
            <li><strong>Price corrections</strong> — fix a line's price after the sale; the order works out what it owes back.</li>
            <li><strong>Cancel, complete, lock</strong> — mark an order cancelled, close out a fulfilled one, or lock it against further edits.</li>
          </ul>
        </div>
        <div class="refund-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">refund items</span>
          </div>
          <div class="refund-body">
            <div class="refund-line">
              <span class="rl-name">Field Notes Journal</span>
              <span class="rl-qty">1 of 2</span>
            </div>
            <div class="refund-meta">
              <span class="rm-key">Tender</span>
              <span class="rm-val">store credit</span>
            </div>
            <div class="refund-meta">
              <span class="rm-key">Reason</span>
              <span class="rm-val">damaged in transit</span>
            </div>
            <div class="refund-due">
              <span>Refund due</span>
              <span class="rd-amt">23.49</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Returns ─────────────────────────────── -->
    <section class="section">
      <div class="split flipped reveal">
        <div class="split-copy">
          <p class="kicker">
            Returns
          </p>
          <h2>Approve, receive, <em>then refund</em></h2>
          <p class="section-sub">
            Returns follow a clear path — requested, approved, received,
            refunded — with rejection as the off-ramp. No money moves until
            the goods are actually back.
          </p>
          <ul class="point-list">
            <li><strong>One unit or all of them</strong> — take back one unit of a three-unit line while the customer keeps the rest.</li>
            <li><strong>Restock and refund together</strong> — both happen at the final step, once the goods are in hand.</li>
            <li><strong>The math is done for you</strong> — the refund is what the order still owes back, after anything already refunded.</li>
            <li><strong>Decided up front</strong> — how the money comes back (original payment, store credit, or check) is set when the return is opened.</li>
          </ul>
        </div>
        <div class="return-window">
          <div class="code-chrome">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span class="code-title">return · wrong size</span>
          </div>
          <div class="return-body">
            <div class="return-steps">
              <span class="rstep done">requested</span>
              <span class="rstep-sep" />
              <span class="rstep done">approved</span>
              <span class="rstep-sep" />
              <span class="rstep active">received</span>
              <span class="rstep-sep" />
              <span class="rstep">refunded</span>
            </div>
            <div class="return-line">
              <span class="rl-name">Canvas Tote</span>
              <span class="rl-qty">1 of 1</span>
            </div>
            <div class="refund-meta">
              <span class="rm-key">Tender</span>
              <span class="rm-val">original method</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── Buyers ──────────────────────────────── -->
    <section class="section">
      <div class="section-head reveal">
        <p class="kicker">
          Buyers
        </p>
        <h2>Who's shopping, <em>who's paying</em></h2>
        <p class="section-sub">
          People shop; accounts pay. Keeping the two distinct is what lets a
          household share one account, one person keep work and home apart,
          and every refund know where to go.
        </p>
      </div>
      <div class="buyer-grid reveal">
        <article
          v-for="card in BUYER_CARDS"
          :key="card.title"
          class="buyer-card"
        >
          <span class="buyer-icon">
            <Icon
              :name="card.icon"
              :size="16"
            />
          </span>
          <h3>{{ card.title }}</h3>
          <p>{{ card.body }}</p>
        </article>
      </div>
    </section>

    <DiscoverCommerceExplore />
  </DiscoverShell>
</template>

<style scoped>
/* ── Shared window shells ────────────────────── */

.cart-window,
.pay-window,
.refund-window,
.return-window {
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--bg-0);
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.4);
}

/* ── Cart window ─────────────────────────────── */

.cart-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.cart-flags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
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

.totals-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.total-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 10px 12px;
}

.tc-key {
  font-size: 11px;
  color: var(--fg-3);
}

.tc-val {
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--fg-1);
}

.tc-val.ok {
  color: #34d99a;
}

/* ── Payments window ─────────────────────────── */

.pay-body {
  padding: 10px 8px;
}

.pay-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 14px;
}

.pay-row + .pay-row {
  border-top: 1px solid var(--line);
}

.pay-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  flex-shrink: 0;
}

.pay-dot.ok { background: #34d99a; }
.pay-dot.wait { background: #f6c453; }

.pay-name {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
}

.pay-state {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
}

.pay-state.ok {
  color: #34d99a;
}

.pay-amt {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

/* ── Refund window ───────────────────────────── */

.refund-body,
.return-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.refund-line,
.return-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 11px 12px;
}

.rl-name {
  font-size: 13px;
  color: var(--fg-1);
}

.rl-qty {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--fg-3);
}

.refund-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 4px;
}

.rm-key {
  font-size: 12px;
  color: var(--fg-3);
}

.rm-val {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
}

.refund-due {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-top: 1px solid var(--line);
  padding: 12px 4px 2px;
  font-size: 13px;
  color: var(--fg-2);
}

.rd-amt {
  font-family: var(--font-mono);
  font-size: 13px;
  color: #34d99a;
}

/* ── Return steps ────────────────────────────── */

.return-steps {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-bottom: 4px;
}

.rstep {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--fg-3);
  border: 1px solid var(--line-2);
  border-radius: 999px;
  padding: 3px 10px;
}

.rstep.done {
  color: #34d99a;
  border-color: color-mix(in srgb, #34d99a 40%, transparent);
}

.rstep.active {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
}

.rstep-sep {
  width: 10px;
  height: 1px;
  background: var(--line-2);
  flex-shrink: 0;
}

/* ── Buyer cards ─────────────────────────────── */

.buyer-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.buyer-card {
  padding: 22px 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.buyer-card:hover {
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
  transform: translateY(-2px);
}

.buyer-icon {
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

.buyer-card h3 {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.01em;
  margin: 0 0 8px;
}

.buyer-card p {
  font-size: 13px;
  line-height: 1.65;
  color: var(--fg-2);
  margin: 0;
}

/* ── Responsive ──────────────────────────────── */

@media (max-width: 820px) {
  .buyer-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 480px) {
  .totals-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
