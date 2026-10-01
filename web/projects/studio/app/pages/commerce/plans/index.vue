<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'
import type { PlanGroupInput, PlanInput, IntervalUnit } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// --- store selector (plan groups are store-scoped) ---
const storesGql = gql`query CommercePlanStores($companyId: UUID!) { ecom { stores(companyId: $companyId) { id name catalog { currency } } } }`
const { data: storeData } = useAsyncQuery<{ ecom: { stores: { id: string; name: string; catalog: { currency: string } }[] } }>(
  'commerce-plan-stores', storesGql, { companyId },
)
const stores = computed(() => storeData.value?.ecom?.stores ?? [])
const storeOptions = computed<SelectOption[]>(() => stores.value.map(s => ({ value: s.id, label: s.name })))
const selectedStoreId = ref<string | null>(null)
// Plans have no currency of their own — they're priced in their store's catalog's currency.
const storeCurrency = computed(() => stores.value.find(s => s.id === selectedStoreId.value)?.catalog?.currency)
watch(stores, (list) => {
  if (!list.length) { selectedStoreId.value = null; return }
  if (!selectedStoreId.value || !list.some(s => s.id === selectedStoreId.value)) selectedStoreId.value = list[0]!.id
}, { immediate: true })

// --- plan group selector (within the store) ---
const storeId = computed(() => selectedStoreId.value ?? undefined)
const groupsGql = gql`
  query CommercePlanGroups($storeId: UUID!) {
    ecom { planGroups(storeId: $storeId) { id key name description paymentRetries } }
  }
`
const { data: groupData, refresh: refreshGroups } = useAsyncQuery<{ ecom: { planGroups: { id: string; key: string; name: string; description: string; paymentRetries: number }[] } }>(
  'commerce-plan-groups', groupsGql, { storeId },
)
const groups = computed(() => groupData.value?.ecom?.planGroups ?? [])
const groupOptions = computed<SelectOption[]>(() => groups.value.map(g => ({ value: g.id, label: g.name })))
const selectedGroupId = ref<string | null>(null)
watch(groups, (list) => {
  if (!list.length) { selectedGroupId.value = null; return }
  if (!selectedGroupId.value || !list.some(g => g.id === selectedGroupId.value)) selectedGroupId.value = list[0]!.id
}, { immediate: true })

// --- plans within the selected group ---
interface PlanRow {
  id: string
  key: string
  name: string
  status: string
  price: string
  interval: number
  intervalUnit: string
}
const planGroupId = computed(() => selectedGroupId.value ?? undefined)
const plansGql = gql`
  query CommercePlans($planGroupId: UUID!) {
    ecom { plans(planGroupId: $planGroupId) { id key name status price interval intervalUnit } }
  }
`
const { data, status, refresh, error: loadError } = useAsyncQuery<{ ecom: { plans: PlanRow[] } }>(
  'commerce-plans', plansGql, { planGroupId },
)
const plans = computed(() => data.value?.ecom?.plans ?? [])

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Plan', width: 'minmax(180px, 2fr)' },
  { key: 'key', label: 'Key', width: '1fr', muted: true },
  { key: 'price', label: 'Price', width: '110px', align: 'right' },
  { key: 'interval', label: 'Billing', width: '1fr', muted: true },
  { key: 'status', label: 'Status', width: '110px' },
]

// --- create group ---
const showGroup = ref(false)
const groupForm = reactive({ key: '', name: '', description: '', paymentRetries: 3 })
const groupSaving = ref(false)
const groupError = ref('')

function openGroup() {
  groupForm.key = ''
  groupForm.name = ''
  groupForm.description = ''
  groupForm.paymentRetries = 3
  groupError.value = ''
  showGroup.value = true
}

const addGroupGql = gql`mutation AddPlanGroup($input: PlanGroupInput!) { ecom { plans { addGroup(input: $input) { id } } } }`

async function handleGroup() {
  if (!selectedStoreId.value) { groupError.value = 'Select a store first.'; return }
  if (!groupForm.key.trim() || !groupForm.name.trim()) { groupError.value = 'Key and name are required.'; return }
  groupSaving.value = true
  groupError.value = ''
  try {
    const input: PlanGroupInput = {
      storeId: selectedStoreId.value,
      key: groupForm.key.trim(),
      name: groupForm.name.trim(),
      description: groupForm.description.trim(),
      paymentRetries: Number(groupForm.paymentRetries),
    }
    await mutation(addGroupGql, { input })
    showGroup.value = false
    await refreshGroups()
  } catch (e: unknown) {
    groupError.value = e instanceof Error ? e.message : 'Failed to create plan group'
  } finally {
    groupSaving.value = false
  }
}

// --- create plan ---
const showPlan = ref(false)
const planForm = reactive({ key: '', name: '', description: '', price: '', interval: 1, intervalUnit: 'MONTHS', configType: 'standard' })
const planSaving = ref(false)
const planError = ref('')

function openPlan() {
  planForm.key = ''
  planForm.name = ''
  planForm.description = ''
  planForm.price = ''
  planForm.interval = 1
  planForm.intervalUnit = 'MONTHS'
  planForm.configType = 'standard'
  planError.value = ''
  showPlan.value = true
}

const addPlanGql = gql`mutation AddPlan($input: PlanInput!) { ecom { plans { addPlan(input: $input) { id } } } }`

async function handlePlan() {
  if (!selectedStoreId.value || !selectedGroupId.value) { planError.value = 'Select a store and group first.'; return }
  if (!planForm.key.trim() || !planForm.name.trim()) { planError.value = 'Key and name are required.'; return }
  if (!planForm.price.trim()) { planError.value = 'Price is required.'; return }
  if (!planForm.interval || planForm.interval < 1) { planError.value = 'Interval must be at least 1.'; return }
  planSaving.value = true
  planError.value = ''
  try {
    const input: PlanInput = {
      planGroupId: selectedGroupId.value,
      storeId: selectedStoreId.value,
      key: planForm.key.trim(),
      name: planForm.name.trim(),
      description: planForm.description.trim(),
      price: planForm.price.trim(),
      interval: Number(planForm.interval),
      intervalUnit: planForm.intervalUnit as IntervalUnit,
      configuration: buildPlanConfiguration(planForm.configType),
    }
    await mutation(addPlanGql, { input })
    showPlan.value = false
    await refresh()
  } catch (e: unknown) {
    planError.value = e instanceof Error ? e.message : 'Failed to create plan'
  } finally {
    planSaving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Subscription Plans')"
        title="Subscription Plans"
        :subtitle="selectedGroupId ? `${plans.length} plans` : 'Select a store'">
        <template #actions>
          <div class="pick"><Select
            v-model="selectedId"
            placeholder="Company…"
            :options="companyOptions"
            size="sm" /></div>
          <div class="pick"><Select
            v-model="selectedStoreId"
            placeholder="Store…"
            :options="storeOptions"
            size="sm" /></div>
          <div class="pick"><Select
            v-model="selectedGroupId"
            placeholder="Group…"
            :options="groupOptions"
            size="sm" /></div>
          <Button
            icon="plus"
            size="sm"
            :disabled="!selectedStoreId"
            @click="openGroup">Group</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedGroupId"
            @click="openPlan">Plan</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load plans — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company.</div>
    <div v-else-if="!stores.length" class="state">This company has no stores — create one first.</div>
    <div v-else-if="!groups.length" class="state">This store has no plan groups yet — create one to add plans.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="plans"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No plans in this group yet.">
      <template #col-name="{ row }">{{ row.name }}</template>
      <template #col-key="{ row }"><code class="mono">{{ row.key }}</code></template>
      <template #col-price="{ row }">{{ formatMoney(row.price, storeCurrency) }}</template>
      <template #col-interval="{ row }">{{ formatInterval(row.interval, row.intervalUnit) }}</template>
      <template #col-status="{ row }"><Badge :color="row.status === 'ACTIVE' ? accent : '#888'">{{ row.status }}</Badge></template>
    </GlassTable>

    <Modal
      v-if="showGroup"
      title="New Plan Group"
      icon="layers"
      :accent="accent"
      @close="showGroup = false">
      <div class="form-stack">
        <div class="form-row">
          <TextInput
            v-model="groupForm.key"
            label="Key"
            placeholder="pro"
            mono />
          <TextInput v-model="groupForm.name" label="Name" placeholder="Pro" />
        </div>
        <Textarea
          v-model="groupForm.description"
          label="Description"
          :rows="2"
          placeholder="Optional" />
        <NumberInput v-model="groupForm.paymentRetries" label="Payment retries" :min="0" />
        <p class="hint">Renewal payment attempts before a subscription goes UNPAID.</p>
        <p v-if="groupError" class="form-error">{{ groupError }}</p>
      </div>
      <template #footer>
        <Button @click="showGroup = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="groupSaving"
          @click="handleGroup">{{ groupSaving ? 'Creating…' : 'Create' }}</Button>
      </template>
    </Modal>

    <Modal
      v-if="showPlan"
      title="New Plan"
      icon="package"
      :accent="accent"
      @close="showPlan = false">
      <div class="form-stack">
        <div class="form-row">
          <TextInput
            v-model="planForm.key"
            label="Key"
            placeholder="pro-monthly"
            mono />
          <TextInput v-model="planForm.name" label="Name" placeholder="Pro Monthly" />
        </div>
        <Textarea
          v-model="planForm.description"
          label="Description"
          :rows="2"
          placeholder="Optional" />
        <div class="form-row">
          <TextInput
            v-model="planForm.price"
            :label="`Price (${storeCurrency || 'USD'})`"
            placeholder="19.99"
            mono />
          <Select v-model="planForm.configType" label="Configuration" :options="PLAN_CONFIG_TYPE_OPTIONS" />
        </div>
        <div class="form-row">
          <NumberInput v-model="planForm.interval" label="Interval" :min="1" />
          <Select v-model="planForm.intervalUnit" label="Interval unit" :options="INTERVAL_UNIT_OPTIONS" />
        </div>
        <p v-if="planError" class="form-error">{{ planError }}</p>
      </div>
      <template #footer>
        <Button @click="showPlan = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="planSaving"
          @click="handlePlan">{{ planSaving ? 'Creating…' : 'Create' }}</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.pick { width: 160px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
