<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'
import type { SubscribeInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// --- account selector (subscriptions are account-scoped) ---
interface AccountOpt { id: string; type: string; credit: string; customers: { profile: { name: string } }[] }
const accountsGql = gql`
  query CommerceSubAccounts($companyId: UUID!) {
    ecom { accounts(companyId: $companyId, offset: 0, limit: 500) { id type credit customers { profile { name } } } }
  }
`
const { data: acctData } = useAsyncQuery<{ ecom: { accounts: AccountOpt[] } }>(
  'commerce-sub-accounts', accountsGql, { companyId },
)
const accounts = computed(() => acctData.value?.ecom?.accounts ?? [])
function accountLabel(a: AccountOpt): string {
  const names = a.customers.map(c => c.profile?.name).filter(Boolean).join(', ')
  return `${names || a.type} · ${formatMoney(a.credit)} credit`
}
const accountOptions = computed<SelectOption[]>(() => accounts.value.map(a => ({ value: a.id, label: accountLabel(a) })))
const selectedAccountId = ref<string | null>(null)
watch(accounts, (list) => {
  if (!list.length) { selectedAccountId.value = null; return }
  if (!selectedAccountId.value || !list.some(a => a.id === selectedAccountId.value)) selectedAccountId.value = list[0]!.id
}, { immediate: true })

interface SubscriptionRow {
  id: string
  status: string
  price: string
  currency: string
  interval: number
  intervalUnit: string
  renews: string
  renewals: number
  paymentFailures: number
  plan: { id: string; name: string; group: { id: string } }
}
const accountId = computed(() => selectedAccountId.value ?? undefined)
const listGql = gql`
  query CommerceSubscriptions($accountId: UUID!) {
    ecom {
      subscriptions(accountId: $accountId) {
        id
        status
        price
        currency
        interval
        intervalUnit
        renews
        renewals
        paymentFailures
        plan { id name group { id } }
      }
    }
  }
`
const { data, status, refresh, error: loadError } = useAsyncQuery<{ ecom: { subscriptions: SubscriptionRow[] } }>(
  'commerce-subscriptions', listGql, { accountId },
)
const subscriptions = computed(() => data.value?.ecom?.subscriptions ?? [])

const columns: GlassTableColumn[] = [
  { key: 'plan', label: 'Plan', width: 'minmax(180px, 2fr)' },
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'price', label: 'Price', width: '100px', align: 'right' },
  { key: 'billing', label: 'Billing', width: '1fr', muted: true },
  { key: 'renews', label: 'Renews', width: '130px', muted: true },
  { key: 'renewals', label: 'Renewals', width: '90px', align: 'right', muted: true },
]

function statusColor(s: string): string {
  if (s === 'ACTIVE' || s === 'TRIALING') return accent.value
  if (s === 'PAST_DUE' || s === 'UNPAID') return '#f59e0b'
  return '#888'
}
const liveStatuses = ['PENDING', 'ACTIVE', 'TRIALING', 'PAST_DUE', 'UNPAID']

const rowActions = (row: SubscriptionRow): OverflowMenuItem[] => {
  if (!liveStatuses.includes(row.status)) return []
  const actions: OverflowMenuItem[] = []
  // changePlans requires an ACTIVE subscription (deferred to the next renewal).
  if (row.status === 'ACTIVE') actions.push({ id: 'changePlan', label: 'Change plan', icon: 'edit' })
  actions.push({ id: 'cancel', label: 'Cancel', icon: 'x', danger: true })
  return actions
}

// --- direct subscribe (store → group → plan cascade) ---
const showSub = ref(false)
const subStoreId = ref<string | null>(null)
const subGroupId = ref<string | null>(null)
const subPlanId = ref<string | null>(null)
const subSaving = ref(false)
const subError = ref('')

const subStoresGql = gql`query CommerceSubStores($companyId: UUID!) { ecom { stores(companyId: $companyId) { id name } } }`
const { data: subStoreData } = useAsyncQuery<{ ecom: { stores: { id: string; name: string }[] } }>(
  'commerce-sub-stores', subStoresGql, { companyId },
)
const subStoreOptions = computed<SelectOption[]>(() => (subStoreData.value?.ecom?.stores ?? []).map(s => ({ value: s.id, label: s.name })))

const subGroupsVar = computed(() => subStoreId.value ?? undefined)
const subGroupsGql = gql`query CommerceSubGroups($storeId: UUID!) { ecom { planGroups(storeId: $storeId) { id name } } }`
const { data: subGroupData } = useAsyncQuery<{ ecom: { planGroups: { id: string; name: string }[] } }>(
  'commerce-sub-groups', subGroupsGql, { storeId: subGroupsVar },
)
const subGroupOptions = computed<SelectOption[]>(() => (subGroupData.value?.ecom?.planGroups ?? []).map(g => ({ value: g.id, label: g.name })))

const subPlansVar = computed(() => subGroupId.value ?? undefined)
const subPlansGql = gql`query CommerceSubPlans($planGroupId: UUID!) { ecom { plans(planGroupId: $planGroupId) { id name price interval intervalUnit } } }`
const { data: subPlanData } = useAsyncQuery<{ ecom: { plans: { id: string; name: string; price: string; interval: number; intervalUnit: string }[] } }>(
  'commerce-sub-plans', subPlansGql, { planGroupId: subPlansVar },
)
const subPlanOptions = computed<SelectOption[]>(() =>
  (subPlanData.value?.ecom?.plans ?? []).map(p => ({ value: p.id, label: `${p.name} — ${formatMoney(p.price)} ${formatInterval(p.interval, p.intervalUnit)}` })),
)

// Reset the cascade when the store/group changes so a stale child selection can't be submitted.
watch(subStoreId, () => { subGroupId.value = null; subPlanId.value = null })
watch(subGroupId, () => { subPlanId.value = null })

function openSubscribe() {
  subStoreId.value = null
  subGroupId.value = null
  subPlanId.value = null
  subError.value = ''
  showSub.value = true
}

const subscribeGql = gql`mutation EcomSubscribe($input: SubscribeInput!) { ecom { subscriptions { subscribe(input: $input) { id } } } }`

async function handleSubscribe() {
  if (!selectedAccountId.value) { subError.value = 'Select an account first.'; return }
  if (!subStoreId.value) { subError.value = 'Store is required.'; return }
  if (!subPlanId.value) { subError.value = 'Plan is required.'; return }
  subSaving.value = true
  subError.value = ''
  try {
    const input: SubscribeInput = { storeId: subStoreId.value, accountId: selectedAccountId.value, planId: subPlanId.value }
    await mutation(subscribeGql, { input })
    showSub.value = false
    await refresh()
  } catch (e: unknown) {
    subError.value = e instanceof Error ? e.message : 'Failed to subscribe'
  } finally {
    subSaving.value = false
  }
}

// --- cancel ---
const showCancel = ref(false)
const cancelTarget = ref<SubscriptionRow | null>(null)
const cancelling = ref(false)
const cancelGql = gql`mutation CancelSubscription($id: UUID!) { ecom { subscriptions { subscription(id: $id) { cancel { id status } } } } }`

function onRowAction(payload: { action: string; row: SubscriptionRow }) {
  if (payload.action === 'cancel') { cancelTarget.value = payload.row; showCancel.value = true }
  else if (payload.action === 'changePlan') openChangePlan(payload.row)
}

// --- change plan (deferred to next renewal) ---
const showChangePlan = ref(false)
const changeTarget = ref<SubscriptionRow | null>(null)
const changePlanId = ref<string | null>(null)
const changing = ref(false)
const changeError = ref('')

// Candidate plans are those in the current subscription's plan group.
const changeGroupVar = computed(() => changeTarget.value?.plan.group.id ?? undefined)
const changePlansGql = gql`query CommerceChangePlanOptions($planGroupId: UUID!) { ecom { plans(planGroupId: $planGroupId) { id name price interval intervalUnit } } }`
const { data: changePlanData } = useAsyncQuery<{ ecom: { plans: { id: string; name: string; price: string; interval: number; intervalUnit: string }[] } }>(
  'commerce-changeplan-options', changePlansGql, { planGroupId: changeGroupVar },
)
const changePlanOptions = computed<SelectOption[]>(() =>
  (changePlanData.value?.ecom?.plans ?? []).map(p => ({ value: p.id, label: `${p.name} — ${formatMoney(p.price)} ${formatInterval(p.interval, p.intervalUnit)}` })),
)

const changePlanGql = gql`mutation ChangeSubscriptionPlan($id: UUID!, $planId: UUID!) { ecom { subscriptions { subscription(id: $id) { changePlan(planId: $planId) { id status } } } } }`

function openChangePlan(row: SubscriptionRow) {
  changeTarget.value = row
  changePlanId.value = null
  changeError.value = ''
  showChangePlan.value = true
}
async function handleChangePlan() {
  if (!changeTarget.value) return
  if (!changePlanId.value) { changeError.value = 'Select a plan.'; return }
  changing.value = true
  changeError.value = ''
  try {
    await mutation(changePlanGql, { id: changeTarget.value.id, planId: changePlanId.value })
    showChangePlan.value = false
    await refresh()
  } catch (e: unknown) {
    changeError.value = e instanceof Error ? e.message : 'Failed to change plan'
  } finally {
    changing.value = false
  }
}

async function handleCancel() {
  if (!cancelTarget.value) return
  cancelling.value = true
  try {
    await mutation(cancelGql, { id: cancelTarget.value.id })
    showCancel.value = false
    await refresh()
  } catch {
    showCancel.value = false
  } finally {
    cancelling.value = false
  }
}

function fmtDate(iso: string): string {
  return iso ? new Date(iso).toLocaleString() : '—'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Subscriptions')"
        title="Subscriptions"
        :subtitle="selectedAccountId ? `${subscriptions.length} subscriptions` : 'Select an account'">
        <template #actions>
          <div class="pick"><Select
            v-model="selectedId"
            placeholder="Company…"
            :options="companyOptions"
            size="sm" /></div>
          <div class="pick-wide"><Select
            v-model="selectedAccountId"
            placeholder="Account…"
            :options="accountOptions"
            size="sm" /></div>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedAccountId"
            @click="openSubscribe">Subscribe</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load subscriptions — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company.</div>
    <div v-else-if="!accounts.length" class="state">This company has no billing accounts yet.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="subscriptions"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No subscriptions for this account."
      :row-actions="rowActions"
      @row-action="onRowAction">
      <template #col-plan="{ row }">{{ row.plan?.name }}</template>
      <template #col-status="{ row }"><Badge :color="statusColor(row.status)">{{ row.status }}</Badge></template>
      <template #col-price="{ row }">{{ formatMoney(row.price, row.currency) }}</template>
      <template #col-billing="{ row }">{{ formatInterval(row.interval, row.intervalUnit) }}</template>
      <template #col-renews="{ row }">{{ fmtDate(row.renews) }}</template>
      <template #col-renewals="{ row }">
        {{ row.renewals }}<span v-if="row.paymentFailures" class="fail"> · {{ row.paymentFailures }} failed</span>
      </template>
    </GlassTable>

    <Modal
      v-if="showSub"
      title="Subscribe Account"
      icon="refresh"
      :accent="accent"
      @close="showSub = false">
      <div class="form-stack">
        <p class="hint">Subscribes the selected account to a plan directly (admin path). Pick the store, plan group, then plan.</p>
        <Select
          v-model="subStoreId"
          label="Store"
          placeholder="Select…"
          :options="subStoreOptions" />
        <Select
          v-model="subGroupId"
          label="Plan group"
          placeholder="Select…"
          :options="subGroupOptions"
          :disabled="!subStoreId" />
        <Select
          v-model="subPlanId"
          label="Plan"
          placeholder="Select…"
          :options="subPlanOptions"
          :disabled="!subGroupId" />
        <p class="hint">No saved payment method is attached here, so renewals will need one added later.</p>
        <p v-if="subError" class="form-error">{{ subError }}</p>
      </div>
      <template #footer>
        <Button @click="showSub = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="subSaving"
          @click="handleSubscribe">{{ subSaving ? 'Subscribing…' : 'Subscribe' }}</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showCancel"
      title="Cancel Subscription"
      subtitle="The subscription stops renewing."
      confirm-label="Cancel subscription"
      :loading="cancelling"
      @close="showCancel = false"
      @confirm="handleCancel">
      <p>Cancel the <strong>{{ cancelTarget?.plan.name }}</strong> subscription?</p>
    </ConfirmModal>

    <Modal
      v-if="showChangePlan"
      title="Change Plan"
      icon="edit"
      :accent="accent"
      @close="showChangePlan = false">
      <div class="form-stack">
        <p class="hint">Schedule a plan change for the <strong>{{ changeTarget?.plan.name }}</strong> subscription. It takes effect at the next renewal; the saved payment method carries over.</p>
        <Select
          v-model="changePlanId"
          label="New plan"
          placeholder="Select a plan…"
          :options="changePlanOptions" />
        <p v-if="changeError" class="form-error">{{ changeError }}</p>
      </div>
      <template #footer>
        <Button @click="showChangePlan = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="changing"
          @click="handleChangePlan">{{ changing ? 'Scheduling…' : 'Change plan' }}</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.fail { color: #f59e0b; }
.pick { width: 160px; }
.pick-wide { width: 260px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
