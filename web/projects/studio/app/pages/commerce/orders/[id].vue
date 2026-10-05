<script setup lang="ts">
import gql from 'graphql-tag'

definePageMeta({ middleware: 'commerce-admin' })

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()

const orderId = computed(() => route.params.id as string)

interface Order {
  id: string
  status: string[]
  quantity: number
  salesTotal: string
  currency: string
  created: string
  account: { id: string; type: string } | null
  customer: { id: string; profile: { name: string } } | null
}
const orderGql = gql`
  query CommerceOrder($id: UUID!) {
    ecom {
      cart(id: $id) {
        id status quantity salesTotal currency created
        account { id type }
        customer { id profile { name } }
      }
    }
  }
`
const { data: orderData, status: orderStatus, error: orderError, refresh: refreshOrder } = useAsyncQuery<{ ecom: { cart: Order | null } }>(
  'commerce-order', orderGql, { id: orderId },
)
const order = computed(() => orderData.value?.ecom?.cart ?? null)

interface ShipmentLine { sku: string; quantity: number }
interface ShipmentParcel { containerId: string | null; lines: ShipmentLine[] }
interface Shipment {
  id: string
  status: string
  carrier: string | null
  tracking: string | null
  carrierStatus: string | null
  labelUrl: string | null
  shipped: string | null
  delivered: string | null
  fulfillmentCenter: { id: string; name: string }
  parcels: ShipmentParcel[]
  unpacked: ShipmentLine[]
}
const shipmentsGql = gql`
  query CommerceOrderShipments($cartId: UUID!) {
    ecom {
      shipments(cartId: $cartId) {
        id status carrier tracking carrierStatus labelUrl shipped delivered
        fulfillmentCenter { id name }
        parcels { containerId lines { sku quantity } }
        unpacked { sku quantity }
      }
    }
  }
`
const cartId = computed(() => orderId.value)
const { data: shipData, refresh: refreshShipments } = useAsyncQuery<{ ecom: { shipments: Shipment[] } }>(
  'commerce-order-shipments', shipmentsGql, { cartId },
)
const shipments = computed(() => shipData.value?.ecom?.shipments ?? [])

function buyer(o: Order): string {
  return o.customer?.profile?.name || (o.account ? `${o.account.type} account` : 'Guest')
}
const stage = computed(() => fulfillmentStage(order.value?.status ?? []))
function fmtDate(iso: string | null): string { return iso ? new Date(iso).toLocaleString() : '—' }

// --- mark shipped ---
const showShip = ref(false)
const shipTarget = ref<Shipment | null>(null)
const shipForm = reactive({ carrier: '', tracking: '' })
const shipping = ref(false)
const shipError = ref('')
const shipGql = gql`
  mutation ShipOrderShipment($id: UUID!, $carrier: String, $tracking: String) {
    ecom { fulfillment { shipment(id: $id) { ship(carrier: $carrier, tracking: $tracking) { id status } } } }
  }
`

function openShip(s: Shipment) {
  shipTarget.value = s
  shipForm.carrier = ''
  shipForm.tracking = ''
  shipError.value = ''
  showShip.value = true
}

async function handleShip() {
  if (!shipTarget.value) return
  shipping.value = true
  shipError.value = ''
  try {
    await mutation(shipGql, {
      id: shipTarget.value.id,
      carrier: shipForm.carrier.trim() || null,
      tracking: shipForm.tracking.trim() || null,
    })
    showShip.value = false
    // Refresh shipments (now SHIPPED) and the order (its status advances to SHIPPED/COMPLETE).
    await refreshShipments()
    await refreshOrder()
  } catch (e: unknown) {
    shipError.value = e instanceof Error ? e.message : 'Failed to mark shipped'
  } finally {
    shipping.value = false
  }
}

const awaitingCount = computed(() => shipments.value.filter(s => s.status === 'AWAITING').length)

// --- carrier tracking ---
// Human label for a lifecycle status (the carrier states read better lowercased-with-spaces).
const STATUS_LABELS: Record<string, string> = {
  UNABLE_TO_PACKAGE: 'Unable to package', AWAITING: 'Awaiting dispatch', SHIPPED: 'Shipped', IN_TRANSIT: 'In transit',
  OUT_FOR_DELIVERY: 'Out for delivery', DELIVERED: 'Delivered', RETURNED: 'Returned',
  FAILURE: 'Delivery failed', CANCELLED: 'Cancelled',
}
function statusLabel(s: string): string { return STATUS_LABELS[s] ?? s }
// In-flight = dispatched but not yet delivered/terminal — the states tracking can still advance.
const inFlight = ['SHIPPED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY']
const refreshingId = ref<string | null>(null)
const refreshTrackingGql = gql`
  mutation RefreshShipmentTracking($id: UUID!) {
    ecom { fulfillment { shipment(id: $id) { refreshTracking { id status carrierStatus delivered } } } }
  }
`
async function refreshTracking(s: Shipment) {
  refreshingId.value = s.id
  try {
    await mutation(refreshTrackingGql, { id: s.id })
    await refreshShipments()
    await refreshOrder()
  } catch (e: unknown) {
    shipError.value = e instanceof Error ? e.message : 'Failed to refresh tracking'
  } finally {
    refreshingId.value = null
  }
}

// --- re-pack (re-run the packer with current containers; for an awaiting shipment) ---
const repackingId = ref<string | null>(null)
const repackGql = gql`
  mutation RepackShipment($id: UUID!) {
    ecom { fulfillment { shipment(id: $id) { repack { id parcels { containerId lines { sku quantity } } } } } }
  }
`
async function repack(s: Shipment) {
  repackingId.value = s.id
  try {
    await mutation(repackGql, { id: s.id })
    await refreshShipments()
  } catch (e: unknown) {
    shipError.value = e instanceof Error ? e.message : 'Failed to re-pack'
  } finally {
    repackingId.value = null
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        v-if="order"
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Orders', { label: order.id.slice(0, 8) })"
        :title="`Order ${order.id.slice(0, 8)}`"
        :subtitle="`${buyer(order)} · ${formatMoney(order.salesTotal, order.currency)}`">
        <template #actions>
          <Badge :color="accent">{{ stage }}</Badge>
        </template>
      </PageHeader>
    </template>

    <div v-if="orderError" class="query-error">Couldn't load order — {{ orderError.message }}</div>
    <div v-if="orderStatus === 'pending' && !order" class="state">Loading…</div>
    <div v-else-if="!order" class="state">Order not found.</div>
    <template v-else>
      <SectionCard title="Order" padded>
        <dl class="summary">
          <div><dt>Buyer</dt><dd>{{ buyer(order) }}</dd></div>
          <div><dt>Items</dt><dd>{{ order.quantity }}</dd></div>
          <div><dt>Total</dt><dd>{{ formatMoney(order.salesTotal, order.currency) }}</dd></div>
          <div><dt>Placed</dt><dd>{{ fmtDate(order.created) }}</dd></div>
          <div><dt>Status</dt><dd class="flags"><Badge v-for="f in order.status" :key="f" :color="accent">{{ f }}</Badge></dd></div>
        </dl>
      </SectionCard>

      <SectionCard :title="`Shipments (${shipments.length})`" padded>
        <p v-if="!shipments.length" class="hint">
          No shipments. A paid physical order is packed into boxes (a shipment per fulfillment center, carrying one or more boxes); an all-digital order has none.
        </p>
        <p v-else-if="awaitingCount" class="hint">{{ awaitingCount }} awaiting dispatch. Marking a shipment shipped draws down its inventory, assigns a carrier, and advances the order.</p>
        <div
          v-for="s in shipments"
          :key="s.id"
          class="shipment"
          :class="{ blocked: s.status === 'UNABLE_TO_PACKAGE' }">
          <div class="shipment-head">
            <div class="ship-where">
              <Badge :color="s.status === 'UNABLE_TO_PACKAGE' ? 'var(--err, #ff5c5c)' : s.status === 'DELIVERED' ? 'var(--ok, #4ade80)' : accent">{{ statusLabel(s.status) }}</Badge>
              <strong v-if="s.parcels.length">{{ s.parcels.length }} {{ s.parcels.length === 1 ? 'box' : 'boxes' }}</strong>
              <span class="muted">· {{ s.fulfillmentCenter.name }}</span>
            </div>
            <div class="ship-actions">
              <template v-if="s.status === 'AWAITING' || s.status === 'UNABLE_TO_PACKAGE'">
                <Button
                  size="sm"
                  icon="refresh"
                  :disabled="repackingId === s.id"
                  @click="repack(s)">{{ repackingId === s.id ? 'Re-packing…' : 'Re-pack' }}</Button>
                <Button
                  v-if="s.status === 'AWAITING'"
                  primary
                  size="sm"
                  icon="package"
                  :accent="accent"
                  @click="openShip(s)">Mark shipped</Button>
              </template>
              <template v-else>
                <span v-if="s.tracking" class="tracking">{{ s.carrier }} · {{ s.tracking }}</span>
                <span v-else-if="s.carrier" class="tracking">{{ s.carrier }}</span>
                <a
                  v-if="s.labelUrl"
                  :href="s.labelUrl"
                  target="_blank"
                  rel="noopener"
                  class="label-link">Label</a>
                <Button
                  v-if="inFlight.includes(s.status)"
                  size="sm"
                  icon="refresh"
                  :disabled="refreshingId === s.id"
                  @click="refreshTracking(s)">
                  {{ refreshingId === s.id ? 'Refreshing…' : 'Refresh tracking' }}
                </Button>
              </template>
            </div>
          </div>
          <p v-if="s.status === 'UNABLE_TO_PACKAGE'" class="unpackable-note">
            No container fits these items. Add a fitting container (Commerce → Containers), then Re-pack.
          </p>
          <p v-if="s.carrierStatus || s.delivered" class="track-line">
            <span v-if="s.carrierStatus" class="muted">{{ s.carrierStatus }}</span>
            <span v-if="s.delivered" class="delivered">Delivered {{ fmtDate(s.delivered) }}</span>
          </p>
          <div v-for="(p, pi) in s.parcels" :key="pi" class="parcel">
            <span class="parcel-tag">Box {{ pi + 1 }}</span>
            <ul class="lines">
              <li v-for="(l, i) in p.lines" :key="i"><code class="mono">{{ l.sku }}</code> × {{ l.quantity }}</li>
            </ul>
          </div>
          <div v-if="s.unpacked.length" class="parcel">
            <span class="parcel-tag err">Unpackable</span>
            <ul class="lines">
              <li v-for="(l, i) in s.unpacked" :key="i"><code class="mono">{{ l.sku }}</code> × {{ l.quantity }}</li>
            </ul>
          </div>
        </div>
      </SectionCard>
    </template>

    <Modal
      v-if="showShip"
      title="Mark Shipped"
      icon="package"
      :accent="accent"
      @close="showShip = false">
      <div class="form-stack">
        <p class="hint">
          Dispatches <strong>{{ shipTarget?.fulfillmentCenter.name }}</strong>'s shipment: its inventory is drawn down and the
          order advances toward Complete. Carrier and tracking are optional.
        </p>
        <div class="form-row">
          <TextInput v-model="shipForm.carrier" label="Carrier" placeholder="e.g. UPS" />
          <TextInput
            v-model="shipForm.tracking"
            label="Tracking #"
            placeholder="e.g. 1Z…"
            mono />
        </div>
        <p v-if="shipError" class="form-error">{{ shipError }}</p>
      </div>
      <template #footer>
        <Button @click="showShip = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="shipping"
          @click="handleShip">{{ shipping ? 'Shipping…' : 'Mark shipped' }}</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px 24px; margin: 0; }
.summary dt { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--fg-3); margin-bottom: 2px; }
.summary dd { margin: 0; font-size: 13px; color: var(--fg-1); }
.flags { display: inline-flex; flex-wrap: wrap; gap: 4px; }
.shipment { border: 1px solid var(--border, #2a2a2a); border-radius: 6px; padding: 12px; margin-top: 10px; }
.shipment-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.ship-where { display: inline-flex; align-items: center; gap: 8px; }
.ship-where .muted { font-size: 12px; color: var(--fg-3); }
.ship-actions { display: inline-flex; align-items: center; gap: 10px; }
.tracking { font-size: 12px; color: var(--fg-2); }
.tracking.muted { color: var(--fg-3); }
.label-link { font-size: 12px; color: var(--accent, #4ea1ff); text-decoration: none; }
.label-link:hover { text-decoration: underline; }
.lines { list-style: none; padding: 4px 0 0; margin: 0; display: flex; flex-wrap: wrap; gap: 6px 16px; }
.lines li { font-size: 12.5px; color: var(--fg-2); }
.track-line { display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 12.5px; margin: 6px 0 0; }
.track-line .delivered { color: var(--ok, #4ade80); }
.parcel { margin-top: 8px; }
.parcel-tag { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--fg-3); }
.parcel-tag.err { color: var(--err, #ff5c5c); }
.shipment.blocked { border-color: var(--err, #ff5c5c); }
.unpackable-note { color: var(--err, #ff5c5c); font-size: 12.5px; margin: 6px 0 0; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0 0 4px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
