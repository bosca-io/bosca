<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'
import type { AddCartItemInput, SetCartAddressInput, SubmitPaymentInput, AddressType, PaymentType, CreditCardType, RefundItemInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()

const cartId = computed(() => route.params.id as string)

const detailGql = gql`
  query CommerceCart($id: UUID!) {
    ecom {
      cart(id: $id) {
        id
        status
        quantity
        retailTotal
        salesTotal
        shipping
        tax
        discounts
        paid
        pendingPaid
        refundDue
        due
        currency
        store { id name catalog { id } paymentProvider { providerKey } }
        account { id type }
        customer { id profile { name } }
        items {
          id
          quantity
          type
          retailPrice
          salesSubtotal
          catalogProduct { id metadata { name } }
        }
        addresses { id type firstName lastName address1 address2 city state country zip phone email }
        payments {
          id transactionType type amount complete confirmed
          refundedAmount nonRefundableAmount voided refunded
        }
      }
    }
  }
`
interface CartItem { id: string; quantity: number; type: string; retailPrice: string; salesSubtotal: string; catalogProduct: { id: string; metadata: { name: string } } }
interface CartAddr { id: string; type: string; firstName: string; lastName: string; address1: string; address2: string | null; city: string; state: string; country: string; zip: string; phone: string; email: string | null }
interface CartPayment {
  id: string; transactionType: string; type: string; amount: string; complete: boolean; confirmed: boolean
  refundedAmount: string; nonRefundableAmount: string; voided: string | null; refunded: string | null
}
interface CartDetail {
  id: string; status: string[]; quantity: number
  retailTotal: string; salesTotal: string; shipping: string; tax: string; discounts: string; paid: string; pendingPaid: string; refundDue: string; due: string; currency: string
  store: { id: string; name: string; catalog: { id: string }; paymentProvider: { providerKey: string } | null }
  account: { id: string; type: string } | null
  customer: { id: string; profile: { name: string } } | null
  items: CartItem[]
  addresses: CartAddr[]
  payments: CartPayment[]
}
const { data, status, refresh, error: loadError } = useAsyncQuery<{ ecom: { cart: CartDetail | null } }>(
  'commerce-cart', detailGql, { id: cartId },
)
const cart = computed(() => data.value?.ecom?.cart ?? null)
/** Format a Money string in this order's currency (every amount on the page is the cart's currency). */
const fmt = (s: string | null | undefined): string => formatMoney(s, cart.value?.currency)
/** The order's ISO-4217 code, for labelling amount inputs (e.g. "Amount (EUR)"). */
const currencyCode = computed(() => cart.value?.currency || 'USD')
const isOpen = computed(() => cart.value?.status.includes('OPEN') ?? false)
const busy = ref(false)
const opError = ref('')

// Catalog products for the add-item picker (the cart's store catalog).
const catalogId = computed(() => cart.value?.store?.catalog?.id ?? undefined)
const productsGql = gql`
  query CommerceCartCatalogProducts($catalogId: UUID!) {
    ecom { catalogProducts(catalogId: $catalogId, activeOnly: true, offset: 0, limit: 200) { id type price metadata { name } } }
  }
`
const { data: cpData } = useAsyncQuery<{ ecom: { catalogProducts: { id: string; type: string; price: string; metadata: { name: string } }[] } }>(
  'commerce-cart-catalog-products', productsGql, { catalogId },
)
const productOptions = computed<SelectOption[]>(() =>
  (cpData.value?.ecom?.catalogProducts ?? []).map(p => ({ value: p.id, label: `${p.metadata.name} — ${fmt(p.price)} (${p.type})` })),
)

const addItemGql = gql`mutation CartAddItem($id: UUID!, $input: AddCartItemInput!) { ecom { carts { cart(id: $id) { addItem(input: $input) { id } } } } }`
const updateItemGql = gql`mutation CartUpdateItem($id: UUID!, $itemId: UUID!, $quantity: Int!) { ecom { carts { cart(id: $id) { updateItem(itemId: $itemId, quantity: $quantity) { id } } } } }`
const removeItemGql = gql`mutation CartRemoveItem($id: UUID!, $itemId: UUID!) { ecom { carts { cart(id: $id) { removeItem(itemId: $itemId) { id } } } } }`
const setAddressGql = gql`mutation CartSetAddress($id: UUID!, $input: SetCartAddressInput!) { ecom { carts { cart(id: $id) { setAddress(input: $input) { id } } } } }`
const submitGql = gql`mutation CartSubmit($id: UUID!, $payment: SubmitPaymentInput) { ecom { carts { cart(id: $id) { submit(payment: $payment) { cart { id status } } } } } }`

async function run(fn: () => Promise<unknown>) {
  busy.value = true
  opError.value = ''
  try { await fn(); await refresh() } catch (e: unknown) { opError.value = e instanceof Error ? e.message : 'Operation failed' } finally { busy.value = false }
}

async function changeQty(item: CartItem, delta: number) {
  const q = item.quantity + delta
  if (q < 1) return
  await run(() => mutation(updateItemGql, { id: cartId.value, itemId: item.id, quantity: q }))
}
async function removeItem(item: CartItem) {
  await run(() => mutation(removeItemGql, { id: cartId.value, itemId: item.id }))
}

// --- add item ---
const showAdd = ref(false)
const addForm = reactive({ catalogProductId: '', quantity: 1 })
function openAdd() { addForm.catalogProductId = ''; addForm.quantity = 1; opError.value = ''; showAdd.value = true }
async function handleAdd() {
  if (!addForm.catalogProductId) { opError.value = 'Select a product.'; return }
  const input: AddCartItemInput = { catalogProductId: addForm.catalogProductId, quantity: Number(addForm.quantity) }
  await run(() => mutation(addItemGql, { id: cartId.value, input }))
  if (!opError.value) showAdd.value = false
}

// --- set address ---
const showAddr = ref(false)
const addrForm = reactive({
  type: 'SHIPPING', firstName: '', lastName: '', address1: '', address2: '', city: '', state: '', country: '', zip: '', phone: '', email: '',
})
function openAddr(type: 'SHIPPING' | 'BILLING') {
  const existing = cart.value?.addresses.find(a => a.type === type)
  addrForm.type = type
  addrForm.firstName = existing?.firstName ?? ''
  addrForm.lastName = existing?.lastName ?? ''
  addrForm.address1 = existing?.address1 ?? ''
  addrForm.address2 = existing?.address2 ?? ''
  addrForm.city = existing?.city ?? ''
  addrForm.state = existing?.state ?? ''
  addrForm.country = existing?.country ?? ''
  addrForm.zip = existing?.zip ?? ''
  addrForm.phone = existing?.phone ?? ''
  addrForm.email = existing?.email ?? ''
  opError.value = ''
  showAddr.value = true
}
async function handleAddr() {
  if (!addrForm.firstName.trim() || !addrForm.lastName.trim() || !addrForm.address1.trim() || !addrForm.city.trim() || !addrForm.state.trim() || !addrForm.country.trim() || !addrForm.zip.trim() || !addrForm.phone.trim()) {
    opError.value = 'Name, address, city, state, country, zip, and phone are required.'
    return
  }
  const input: SetCartAddressInput = {
    type: addrForm.type as AddressType, firstName: addrForm.firstName.trim(), lastName: addrForm.lastName.trim(),
    address1: addrForm.address1.trim(), address2: addrForm.address2.trim() || null,
    city: addrForm.city.trim(), state: addrForm.state.trim(), country: addrForm.country.trim(),
    zip: addrForm.zip.trim(), phone: addrForm.phone.trim(), email: addrForm.email.trim() || null,
  }
  await run(() => mutation(setAddressGql, { id: cartId.value, input }))
  if (!opError.value) showAddr.value = false
}

// --- payments (split tender: take one payment at a time until the cart is covered) ---
const isPaid = computed(() => cart.value?.status.includes('PAID') ?? false)
const isPending = computed(() => cart.value?.status.includes('PENDING') ?? false)
const payable = computed(() => !!cart.value && !isPaid.value && (isOpen.value || isPending.value))
const dueAmount = computed(() => Number(cart.value?.due ?? 0))

// Submit-time address requirements (mirrors the backend guard in CartServiceImpl.submit): a cart
// with any physical line needs a shipping address, and taking a payment needs a billing address.
// Surfaced up front so the requirement is visible rather than only as a rejected submit.
const hasPhysical = computed(() => cart.value?.items.some(i => i.type === 'PHYSICAL') ?? false)
const hasShipping = computed(() => cart.value?.addresses.some(a => a.type === 'SHIPPING') ?? false)
const hasBilling = computed(() => cart.value?.addresses.some(a => a.type === 'BILLING') ?? false)
const needsShipping = computed(() => hasPhysical.value && !hasShipping.value)

// The store's configured gateway decides how a card is captured — the admin never picks. Stripe-style
// gateways tokenize the card in the browser (Stripe.js, wired with the Stripe provider); PAN gateways
// (BluePay) take the raw card. There's no Stripe provider yet, so today this resolves to the card form.
const providerKey = computed(() => cart.value?.store?.paymentProvider?.providerKey ?? null)
const isStripe = computed(() => providerKey.value === 'stripe')

const showPay = ref(false)
const cardValid = ref(false)
const payForm = reactive({
  type: 'CREDIT_CARD',
  amount: 0,
  token: '',
  checkNumber: '',
  companyCreditNumber: '',
  card: { name: '', number: '', cvv: '', expirationMonth: 1, expirationYear: new Date().getFullYear() },
})
// Cash may be tendered above the due (change); other tenders are capped at the due by the backend.
const cashChange = computed(() => payForm.type === 'CASH' ? Number(Math.max(0, payForm.amount - dueAmount.value).toFixed(2)) : 0)
const willRemain = computed(() => Number(Math.max(0, dueAmount.value - payForm.amount).toFixed(2)))
const applied = computed(() => Math.min(payForm.amount, dueAmount.value))

function openPay() {
  payForm.type = 'CREDIT_CARD'
  payForm.amount = dueAmount.value
  payForm.token = ''
  payForm.checkNumber = ''
  payForm.companyCreditNumber = ''
  Object.assign(payForm.card, { name: '', number: '', cvv: '', expirationMonth: 1, expirationYear: new Date().getFullYear() })
  cardValid.value = false
  opError.value = ''
  showPay.value = true
}

// One payment of `amount` (the backend caps it at the remaining due). For a card charge a Stripe
// gateway takes a browser-tokenized `token`; a PAN gateway takes the raw `creditCard` (brand derived).
function buildPayment(): SubmitPaymentInput {
  const p: SubmitPaymentInput = { type: payForm.type as PaymentType, amount: payForm.amount.toFixed(2) }
  switch (payForm.type) {
    case 'CREDIT_CARD':
      if (isStripe.value) {
        p.token = payForm.token.trim() || null
      } else {
        p.creditCard = {
          name: payForm.card.name,
          number: payForm.card.number.replace(/\s/g, ''),
          cvv: payForm.card.cvv,
          type: detectCardBrand(payForm.card.number) as CreditCardType,
          expirationMonth: Number(payForm.card.expirationMonth),
          expirationYear: Number(payForm.card.expirationYear),
        }
      }
      break
    case 'CHECK':
      p.checkNumber = payForm.checkNumber.trim() || null
      break
    case 'COMPANY_CREDIT':
      p.companyCreditNumber = payForm.companyCreditNumber.trim() || null
      break
    // CASH and ACCOUNT_CREDIT carry no extra fields (account credit draws the cart account's balance).
  }
  return p
}

function payValid(): string | null {
  if (payForm.amount <= 0) return 'Enter a payment amount.'
  if (payForm.type === 'CHECK' && !payForm.checkNumber.trim()) return 'A check number is required.'
  if (payForm.type === 'COMPANY_CREDIT' && !payForm.companyCreditNumber.trim()) return 'A company-credit number is required.'
  if (payForm.type === 'CREDIT_CARD' && !isStripe.value && !cardValid.value) return 'Enter complete, valid card details.'
  if (payForm.type === 'ACCOUNT_CREDIT' && !cart.value?.account) return 'This cart has no account to draw credit from.'
  return null
}

async function takePayment() {
  const invalid = payValid()
  if (invalid) { opError.value = invalid; return }
  await run(() => mutation(submitGql, { id: cartId.value, payment: buildPayment() }))
  if (!opError.value) showPay.value = false
}

/** Commit the cart (OPEN -> PENDING) with no payment — bill later. The cart stays payable afterward. */
async function payLater() {
  await run(() => mutation(submitGql, { id: cartId.value, payment: null }))
}

function buyer(c: CartDetail): string {
  return c.customer?.profile?.name || (c.account ? `${c.account.type} account` : 'Guest')
}

// --- order admin: complete / cancel / refund (paid orders) ---
const isComplete = computed(() => cart.value?.status.includes('COMPLETE') ?? false)
const isCancelled = computed(() => cart.value?.status.includes('CANCELLED') ?? false)
// A paid order an admin can still act on (finalize, cancel, or refund).
const orderActionable = computed(() => isPaid.value && !isComplete.value && !isCancelled.value)

const completeGql = gql`mutation CartComplete($id: UUID!) { ecom { carts { cart(id: $id) { complete { id status } } } } }`
const cancelGql = gql`mutation CartCancel($id: UUID!) { ecom { carts { cart(id: $id) { cancel { id status } } } } }`
const refundItemsGql = gql`
  mutation CartRefundItems($id: UUID!, $items: [RefundItemInput!]!, $tender: RefundTender!, $checkNumber: String, $reason: String) {
    ecom { carts { cart(id: $id) { refundItems(items: $items, tender: $tender, checkNumber: $checkNumber, reason: $reason) { id status paid } } } }
  }
`
const paymentRefundGql = gql`mutation PaymentRefund($id: UUID!, $amount: Money!, $reason: String) { ecom { payments { payment(id: $id) { refund(amount: $amount, reason: $reason) { id } } } } }`
const paymentVoidGql = gql`mutation PaymentVoid($id: UUID!, $reason: String) { ecom { payments { payment(id: $id) { void(reason: $reason) { id } } } } }`

// complete + cancel (confirmed)
const showComplete = ref(false)
const showCancel = ref(false)
async function handleComplete() { await run(() => mutation(completeGql, { id: cartId.value })); if (!opError.value) showComplete.value = false }
async function handleCancel() { await run(() => mutation(cancelGql, { id: cartId.value })); if (!opError.value) showCancel.value = false }

// --- batch / partial item refund ---
const showRefund = ref(false)
const refundQty = reactive<Record<string, number>>({})
const refundForm = reactive({ tender: 'ORIGINAL', checkNumber: '', reason: '' })

function unitPrice(it: CartItem): number {
  return it.quantity > 0 ? Number(it.salesSubtotal) / it.quantity : 0
}
// A pre-tax estimate of the refund; the server computes the exact amount (incl. tax) from the price drop.
const refundEstimate = computed(() =>
  (cart.value?.items ?? []).reduce((sum, it) => sum + (refundQty[it.id] ?? 0) * unitPrice(it), 0),
)
const refundLines = computed<RefundItemInput[]>(() =>
  (cart.value?.items ?? [])
    .filter(it => (refundQty[it.id] ?? 0) > 0)
    .map(it => ({ itemId: it.id, quantity: refundQty[it.id]! })),
)

function openRefund() {
  for (const it of cart.value?.items ?? []) refundQty[it.id] = 0
  refundForm.tender = 'ORIGINAL'
  refundForm.checkNumber = ''
  refundForm.reason = ''
  opError.value = ''
  showRefund.value = true
}
async function handleRefundItems() {
  if (!refundLines.value.length) { opError.value = 'Choose at least one unit to refund.'; return }
  if (refundForm.tender === 'CHECK' && !refundForm.checkNumber.trim()) { opError.value = 'A check number is required for a check refund.'; return }
  await run(() => mutation(refundItemsGql, {
    id: cartId.value,
    items: refundLines.value,
    tender: refundForm.tender,
    checkNumber: refundForm.tender === 'CHECK' ? refundForm.checkNumber.trim() : null,
    reason: refundForm.reason.trim() || null,
  }))
  if (!opError.value) showRefund.value = false
}

// --- per-payment refund / void (from the order's payments) ---
function paymentRefundable(p: CartPayment): number {
  return Number(p.amount) - Number(p.refundedAmount) - Number(p.nonRefundableAmount)
}
async function refundPayment(p: CartPayment) {
  const amount = paymentRefundable(p)
  if (amount <= 0) return
  await run(() => mutation(paymentRefundGql, { id: p.id, amount: amount.toFixed(2), reason: null }))
}
async function voidPayment(p: CartPayment) {
  await run(() => mutation(paymentVoidGql, { id: p.id, reason: null }))
}

// --- check confirmation / lock / remove address / item price override ---
const isLocked = computed(() => cart.value?.status.includes('LOCKED') ?? false)
function checkPending(p: CartPayment): boolean {
  return p.transactionType === 'PAYMENT' && p.type === 'CHECK' && p.complete && !p.confirmed && !p.voided
}

const confirmCheckGql = gql`mutation CartConfirmCheck($id: UUID!, $paymentId: UUID!) { ecom { carts { cart(id: $id) { confirmCheck(paymentId: $paymentId) { id status } } } } }`
const setLockedGql = gql`mutation CartSetLocked($id: UUID!, $locked: Boolean!) { ecom { carts { cart(id: $id) { setLocked(locked: $locked) { id status } } } } }`
const removeAddressGql = gql`mutation CartRemoveAddress($id: UUID!, $type: AddressType!) { ecom { carts { cart(id: $id) { removeAddress(type: $type) { id } } } } }`
const setItemPriceGql = gql`mutation CartSetItemPrice($id: UUID!, $itemId: UUID!, $retailPrice: Money!, $salesPrice: Money!) { ecom { carts { cart(id: $id) { setItemPrice(itemId: $itemId, retailPrice: $retailPrice, salesPrice: $salesPrice) { id } } } } }`

async function confirmCheckPayment(p: CartPayment) {
  await run(() => mutation(confirmCheckGql, { id: cartId.value, paymentId: p.id }))
}
async function toggleLock() {
  await run(() => mutation(setLockedGql, { id: cartId.value, locked: !isLocked.value }))
}
async function removeAddr(a: CartAddr) {
  await run(() => mutation(removeAddressGql, { id: cartId.value, type: a.type as AddressType }))
}

// item price override
const showItemPrice = ref(false)
const priceTarget = ref<CartItem | null>(null)
const priceForm = reactive({ retailPrice: 0, salesPrice: 0 })
function openItemPrice(it: CartItem) {
  priceTarget.value = it
  priceForm.retailPrice = Number(it.retailPrice)
  priceForm.salesPrice = it.quantity > 0 ? Number(it.salesSubtotal) / it.quantity : Number(it.retailPrice)
  opError.value = ''
  showItemPrice.value = true
}
async function handleItemPrice() {
  if (!priceTarget.value) return
  await run(() => mutation(setItemPriceGql, {
    id: cartId.value,
    itemId: priceTarget.value!.id,
    retailPrice: priceForm.retailPrice.toFixed(2),
    salesPrice: priceForm.salesPrice.toFixed(2),
  }))
  if (!opError.value) showItemPrice.value = false
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        v-if="cart"
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Carts', { label: cart.id.slice(0, 8) })"
        :title="`Cart ${cart.id.slice(0, 8)}`"
        :subtitle="`${cart.store.name} · ${buyer(cart)}`">
        <template #actions>
          <Button
            v-if="isOpen"
            size="sm"
            :disabled="busy || !cart.items.length || needsShipping"
            @click="payLater">Submit (pay later)</Button>
          <Button
            v-if="payable"
            primary
            icon="credit-card"
            size="sm"
            :accent="accent"
            :disabled="busy || !cart.items.length || needsShipping || !hasBilling"
            @click="openPay">Take payment</Button>
          <Button
            v-if="orderActionable"
            icon="undo"
            size="sm"
            :disabled="busy"
            @click="openRefund">Refund items</Button>
          <Button
            v-if="orderActionable"
            icon="check"
            size="sm"
            :disabled="busy"
            @click="showComplete = true">Complete</Button>
          <Button
            v-if="orderActionable"
            icon="x"
            size="sm"
            :disabled="busy"
            @click="showCancel = true">Cancel order</Button>
          <Button
            v-if="isPaid"
            :icon="isLocked ? 'unlock' : 'lock'"
            size="sm"
            :disabled="busy"
            @click="toggleLock">{{ isLocked ? 'Unlock' : 'Lock' }}</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load cart — {{ loadError.message }}</div>
    <div v-if="status === 'pending' && !cart" class="state">Loading…</div>
    <div v-else-if="!cart" class="state">Cart not found.</div>
    <template v-else>
      <div v-if="opError" class="query-error">{{ opError }}</div>

      <SectionCard title="Status" padded>
        <div class="flags">
          <Badge v-for="f in cart.status" :key="f" :color="accent">{{ f }}</Badge>
        </div>
        <p v-if="!isOpen && payable" class="hint">Committed — items are locked. {{ fmt(cart.due) }} remaining; take one or more payments to fulfill it.</p>
        <p v-else-if="!isOpen && !payable" class="hint">This cart is closed, so its contents are read-only.</p>
      </SectionCard>

      <SectionCard title="Items">
        <template #right>
          <Button
            v-if="isOpen"
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="busy"
            @click="openAdd">Add item</Button>
        </template>
        <table v-if="cart.items.length" class="items">
          <thead><tr><th>Product</th><th>Type</th><th class="num">Price</th><th class="num">Qty</th><th class="num">Subtotal</th><th/></tr></thead>
          <tbody>
            <tr v-for="it in cart.items" :key="it.id">
              <td>{{ it.catalogProduct?.metadata?.name }}</td>
              <td class="muted">{{ it.type }}</td>
              <td class="num">{{ fmt(it.retailPrice) }}</td>
              <td class="num">
                <div v-if="isOpen" class="qty">
                  <Button
                    icon="minus"
                    size="sm"
                    :disabled="busy || it.quantity <= 1"
                    @click="changeQty(it, -1)" />
                  <span class="qval">{{ it.quantity }}</span>
                  <Button
                    icon="plus"
                    size="sm"
                    :disabled="busy"
                    @click="changeQty(it, 1)" />
                </div>
                <span v-else>{{ it.quantity }}</span>
              </td>
              <td class="num">{{ fmt(it.salesSubtotal) }}</td>
              <td class="num">
                <Button
                  v-if="isOpen"
                  icon="trash"
                  size="sm"
                  :disabled="busy"
                  @click="removeItem(it)" />
                <Button
                  v-else-if="orderActionable"
                  icon="edit"
                  size="sm"
                  :disabled="busy"
                  @click="openItemPrice(it)" />
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="empty">No items. {{ isOpen ? 'Add a product to this cart.' : '' }}</p>
      </SectionCard>

      <SectionCard title="Addresses" padded>
        <template #right>
          <div v-if="payable" class="addr-actions">
            <Button icon="pin" size="sm" @click="openAddr('SHIPPING')">Set shipping</Button>
            <Button icon="pin" size="sm" @click="openAddr('BILLING')">Set billing</Button>
          </div>
        </template>
        <div v-if="cart.addresses.length" class="addr-list">
          <div v-for="a in cart.addresses" :key="a.id" class="addr">
            <Badge :color="accent">{{ a.type }}</Badge>
            <div class="addr-body">
              {{ a.firstName }} {{ a.lastName }} — {{ a.address1 }}<span v-if="a.address2">, {{ a.address2 }}</span><br>
              {{ a.city }}, {{ a.state }} {{ a.zip }} · {{ a.country }} · <span class="muted">{{ a.phone }}</span>
            </div>
            <Button
              v-if="isOpen || orderActionable"
              icon="trash"
              size="sm"
              :disabled="busy"
              @click="removeAddr(a)" />
          </div>
        </div>
        <p v-else class="hint">No addresses set.</p>
        <p v-if="needsShipping" class="addr-warn">A shipping address is required to submit a cart with physical items.</p>
        <p v-else-if="payable && !hasBilling" class="addr-warn">A billing address is required to take a payment.</p>
      </SectionCard>

      <SectionCard title="Totals" padded>
        <dl class="kv">
          <dt>Subtotal</dt><dd>{{ fmt(cart.retailTotal) }}</dd>
          <dt>Discounts</dt><dd>{{ fmt(cart.discounts) }}</dd>
          <dt>Shipping</dt><dd>{{ fmt(cart.shipping) }}</dd>
          <dt>Tax</dt><dd>{{ fmt(cart.tax) }}</dd>
          <dt>Total</dt><dd><strong>{{ fmt(cart.salesTotal) }}</strong></dd>
          <dt>Paid</dt><dd>{{ fmt(cart.paid) }}</dd>
          <dt v-if="Number(cart.pendingPaid) > 0">Pending</dt><dd v-if="Number(cart.pendingPaid) > 0">{{ fmt(cart.pendingPaid) }}</dd>
          <dt v-if="Number(cart.refundDue) > 0">Refund due</dt><dd v-if="Number(cart.refundDue) > 0">{{ fmt(cart.refundDue) }}</dd>
          <dt>Due</dt><dd>{{ fmt(cart.due) }}</dd>
        </dl>
      </SectionCard>

      <SectionCard v-if="cart.payments.length" title="Payments" padded>
        <table class="items">
          <thead><tr><th>Type</th><th>Kind</th><th class="num">Amount</th><th class="num">Refunded</th><th>State</th><th/></tr></thead>
          <tbody>
            <tr v-for="p in cart.payments" :key="p.id">
              <td>{{ p.type }}</td>
              <td class="muted">{{ p.transactionType }}</td>
              <td class="num">{{ fmt(p.amount) }}</td>
              <td class="num">{{ fmt(p.refundedAmount) }}</td>
              <td>
                <Badge v-if="p.voided" color="#888">Voided</Badge>
                <Badge v-else-if="Number(p.refundedAmount) > 0" color="var(--accent, #f59e0b)">Refunded</Badge>
                <Badge v-else-if="p.complete && p.confirmed" :color="accent">Confirmed</Badge>
                <Badge v-else-if="p.complete" color="#888">Pending</Badge>
                <Badge v-else color="var(--err, #ff5c5c)">Failed</Badge>
              </td>
              <td class="num">
                <div v-if="p.transactionType === 'PAYMENT' && !p.voided" class="pay-actions">
                  <Button
                    v-if="checkPending(p)"
                    size="sm"
                    :disabled="busy"
                    @click="confirmCheckPayment(p)">Confirm check</Button>
                  <Button
                    v-if="p.complete && paymentRefundable(p) > 0"
                    size="sm"
                    :disabled="busy"
                    @click="refundPayment(p)">Refund {{ fmt(paymentRefundable(p).toFixed(2)) }}</Button>
                  <Button
                    v-if="!p.complete"
                    size="sm"
                    :disabled="busy"
                    @click="voidPayment(p)">Void</Button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <p class="hint">Refund individual items (with quantities) via <strong>Refund items</strong>; or refund a whole payment here.</p>
      </SectionCard>
    </template>

    <Modal
      v-if="showAdd"
      title="Add Item"
      icon="plus"
      :accent="accent"
      @close="showAdd = false">
      <div class="form-stack">
        <Select
          v-model="addForm.catalogProductId"
          label="Catalog product"
          placeholder="Select…"
          :options="productOptions" />
        <NumberInput v-model="addForm.quantity" label="Quantity" :min="1" />
        <p v-if="!productOptions.length" class="hint">This store's catalog has no active products to add.</p>
        <p v-if="opError" class="form-error">{{ opError }}</p>
      </div>
      <template #footer>
        <Button @click="showAdd = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="busy"
          @click="handleAdd">Add</Button>
      </template>
    </Modal>

    <Modal
      v-if="showAddr"
      :title="`Set ${addrForm.type === 'SHIPPING' ? 'Shipping' : 'Billing'} Address`"
      icon="pin"
      :accent="accent"
      @close="showAddr = false">
      <div class="form-stack">
        <div class="form-row">
          <TextInput v-model="addrForm.firstName" label="First name" />
          <TextInput v-model="addrForm.lastName" label="Last name" />
        </div>
        <TextInput v-model="addrForm.address1" label="Address" />
        <TextInput v-model="addrForm.address2" label="Address line 2" placeholder="optional" />
        <div class="form-row">
          <TextInput v-model="addrForm.city" label="City" />
          <TextInput v-model="addrForm.state" label="State / region" />
        </div>
        <div class="form-row">
          <TextInput v-model="addrForm.country" label="Country" placeholder="US" />
          <TextInput v-model="addrForm.zip" label="Zip" />
        </div>
        <div class="form-row">
          <TextInput v-model="addrForm.phone" label="Phone" />
          <TextInput v-model="addrForm.email" label="Email" placeholder="optional" />
        </div>
        <p v-if="opError" class="form-error">{{ opError }}</p>
      </div>
      <template #footer>
        <Button @click="showAddr = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="busy"
          @click="handleAddr">Save address</Button>
      </template>
    </Modal>

    <Modal
      v-if="showPay"
      title="Take Payment"
      icon="credit-card"
      :accent="accent"
      @close="showPay = false">
      <div class="form-stack">
        <p class="hint">Remaining due: <strong>{{ fmt(dueAmount.toFixed(2)) }}</strong>. A cart can take several payments of different types until it's covered.</p>
        <Select v-model="payForm.type" label="Payment method" :options="PAYMENT_TYPE_OPTIONS" />
        <NumberInput
          v-model="payForm.amount"
          :label="`${payForm.type === 'CASH' ? 'Cash received' : 'Amount'} (${currencyCode})`"
          :min="0"
          :step="0.01" />
        <p v-if="cashChange > 0" class="hint">Change due: <strong>{{ fmt(cashChange.toFixed(2)) }}</strong></p>
        <p v-else-if="willRemain > 0" class="hint">Partial — {{ fmt(willRemain.toFixed(2)) }} will remain due after this.</p>

        <template v-if="payForm.type === 'CREDIT_CARD'">
          <!-- Stripe-style gateway: card is collected + tokenized in the browser, never on our server. -->
          <div v-if="isStripe" class="stripe-note">
            This store uses <strong>{{ providerKey }}</strong>. The card is entered and tokenized securely in the
            browser (Stripe Elements), which lands with the Stripe provider — raw card entry here is disabled.
          </div>
          <!-- PAN gateway (BluePay) / test: a proper card form; brand detected from the number. -->
          <CreditCardForm v-else v-model="payForm.card" v-model:valid="cardValid" />
        </template>
        <TextInput
          v-else-if="payForm.type === 'CHECK'"
          v-model="payForm.checkNumber"
          label="Check number"
          mono />
        <TextInput
          v-else-if="payForm.type === 'COMPANY_CREDIT'"
          v-model="payForm.companyCreditNumber"
          label="Company-credit number"
          mono />
        <p v-else-if="payForm.type === 'ACCOUNT_CREDIT'" class="hint">Charged to the account's stored credit balance.</p>

        <p v-if="opError" class="form-error">{{ opError }}</p>
      </div>
      <template #footer>
        <Button @click="showPay = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="busy || (payForm.type === 'CREDIT_CARD' && isStripe)"
          @click="takePayment">
          Charge {{ fmt(applied.toFixed(2)) }}
        </Button>
      </template>
    </Modal>

    <Modal
      v-if="showRefund && cart"
      title="Refund Items"
      icon="undo"
      :accent="accent"
      @close="showRefund = false">
      <div class="form-stack">
        <p class="hint">Choose how many units of each line to refund. The line is reduced (removed when fully refunded), its inventory restocked, and the amount returned to the chosen tender.</p>
        <table class="items">
          <thead><tr><th>Product</th><th class="num">Unit</th><th class="num">Ordered</th><th class="num">Refund</th></tr></thead>
          <tbody>
            <tr v-for="it in cart.items" :key="it.id">
              <td>{{ it.catalogProduct?.metadata?.name }}</td>
              <td class="num">{{ fmt(unitPrice(it).toFixed(2)) }}</td>
              <td class="num">{{ it.quantity }}</td>
              <td class="num"><div class="refund-qty"><NumberInput v-model="refundQty[it.id]" :min="0" :max="it.quantity" /></div></td>
            </tr>
          </tbody>
        </table>
        <Select
          v-model="refundForm.tender"
          label="Refund to"
          :options="[{ value: 'ORIGINAL', label: 'Original payment' }, { value: 'ACCOUNT_CREDIT', label: 'Account store credit' }, { value: 'CHECK', label: 'Check' }]" />
        <TextInput
          v-if="refundForm.tender === 'CHECK'"
          v-model="refundForm.checkNumber"
          label="Check number"
          mono />
        <TextInput v-model="refundForm.reason" label="Reason" placeholder="optional" />
        <p class="hint">Estimated refund: <strong>{{ fmt(refundEstimate.toFixed(2)) }}</strong> <span class="muted">(pre-tax; the exact amount incl. tax is computed on submit)</span></p>
        <p v-if="opError" class="form-error">{{ opError }}</p>
      </div>
      <template #footer>
        <Button @click="showRefund = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="busy || !refundLines.length"
          @click="handleRefundItems">{{ busy ? 'Refunding…' : 'Refund' }}</Button>
      </template>
    </Modal>

    <Modal
      v-if="showItemPrice && priceTarget"
      title="Override Line Price"
      icon="edit"
      :accent="accent"
      @close="showItemPrice = false">
      <div class="form-stack">
        <p class="hint">Override the unit price for <strong>{{ priceTarget.catalogProduct?.metadata?.name }}</strong> (×{{ priceTarget.quantity }}). Repricing surfaces any resulting refund due or balance.</p>
        <div class="form-row">
          <NumberInput
            v-model="priceForm.retailPrice"
            :label="`Unit retail price (${currencyCode})`"
            :min="0"
            :step="0.01" />
          <NumberInput
            v-model="priceForm.salesPrice"
            :label="`Unit sales price (${currencyCode})`"
            :min="0"
            :step="0.01" />
        </div>
        <p v-if="opError" class="form-error">{{ opError }}</p>
      </div>
      <template #footer>
        <Button @click="showItemPrice = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="busy"
          @click="handleItemPrice">{{ busy ? 'Saving…' : 'Set price' }}</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showComplete"
      title="Complete Order"
      confirm-label="Complete"
      :loading="busy"
      @close="showComplete = false"
      @confirm="handleComplete">
      <p>Mark this paid order complete? This finalizes it and emits the order-completed event.</p>
    </ConfirmModal>

    <ConfirmModal
      v-if="showCancel"
      title="Cancel Order"
      confirm-label="Cancel order"
      :loading="busy"
      @close="showCancel = false"
      @confirm="handleCancel">
      <p>Cancel this order? This is a status change only — refund any money taken separately via <strong>Refund items</strong> or the Payments section.</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.flags { display: inline-flex; flex-wrap: wrap; gap: 4px; align-items: center; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 8px 0 0; }
.addr-warn { color: var(--err, #ff5c5c); font-size: 12.5px; margin: 8px 0 0; }
.empty { color: var(--fg-3); font-size: 13px; padding: 14px 16px; margin: 0; }
.items { width: 100%; border-collapse: collapse; font-size: 13px; }
.items th { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--line); font-size: 11px; color: var(--fg-3); text-transform: uppercase; letter-spacing: 0.04em; }
.items td { padding: 8px 12px; border-bottom: 1px solid var(--line); }
.items tr:last-child td { border-bottom: none; }
.items .num { text-align: right; white-space: nowrap; }
.qty { display: inline-flex; align-items: center; gap: 8px; justify-content: flex-end; }
.qval { min-width: 20px; text-align: center; }
.muted { color: var(--fg-3); }
.addr-actions { display: flex; gap: 8px; }
.addr-list { display: flex; flex-direction: column; gap: 12px; }
.addr { display: flex; gap: 10px; align-items: flex-start; }
.addr-body { font-size: 13px; line-height: 1.5; }
.kv { display: grid; grid-template-columns: 140px 1fr; gap: 6px 16px; margin: 0; }
.kv dt { color: var(--fg-3); font-size: 12.5px; }
.kv dd { margin: 0; font-size: 13px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.toggle { display: inline-flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--fg-2); cursor: pointer; }
.stripe-note { background: var(--bg-3); border-left: 3px solid var(--accent, #888); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
.pay-actions { display: inline-flex; gap: 6px; justify-content: flex-end; }
.refund-qty { width: 88px; margin-left: auto; }
</style>
