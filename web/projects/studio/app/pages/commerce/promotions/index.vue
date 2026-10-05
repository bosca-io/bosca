<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'
import type { PromotionInput, PromotionType } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// --- store selector (promotions are store-scoped) ---
const storesGql = gql`query CommercePromoStores($companyId: UUID!) { ecom { stores(companyId: $companyId) { id name } } }`
const { data: storeData } = useAsyncQuery<{ ecom: { stores: { id: string; name: string }[] } }>(
  'commerce-promo-stores', storesGql, { companyId },
)
const stores = computed(() => storeData.value?.ecom?.stores ?? [])
const storeOptions = computed<SelectOption[]>(() => stores.value.map(s => ({ value: s.id, label: s.name })))
const selectedStoreId = ref<string | null>(null)
watch(stores, (list) => {
  if (!list.length) { selectedStoreId.value = null; return }
  if (!selectedStoreId.value || !list.some(s => s.id === selectedStoreId.value)) selectedStoreId.value = list[0]!.id
}, { immediate: true })

interface PromotionRow {
  id: string
  code: string
  name: string
  type: string
  starts: string
  ends: string
  rule: { __typename: string; percent?: number; amount?: string; buyQuantity?: number; getQuantity?: number }
}

const listGql = gql`
  query CommercePromotions($storeId: UUID!, $offset: Int!, $limit: Int!) {
    ecom {
      promotions(storeId: $storeId, offset: $offset, limit: $limit) {
        id
        code
        name
        type
        starts
        ends
        rule {
          __typename
          ... on PercentOffCartRule { percent }
          ... on AmountOffCartRule { amount }
          ... on BuyOneGetOneRule { buyQuantity getQuantity }
          ... on PercentOffSubscriptionRule { percent }
          ... on AmountOffSubscriptionRule { amount }
        }
      }
    }
  }
`
const storeId = computed(() => selectedStoreId.value ?? undefined)
const { rows: promotions, hasMore, offset, pageSize, status, refresh, error: loadError } = usePagedList<PromotionRow>(
  'commerce-promotions', listGql, { storeId }, d => (d as { ecom?: { promotions?: PromotionRow[] } })?.ecom?.promotions,
)

const columns: GlassTableColumn[] = [
  { key: 'code', label: 'Code', width: 'minmax(140px, 1fr)' },
  { key: 'name', label: 'Name', width: 'minmax(160px, 1.5fr)' },
  { key: 'type', label: 'Type', width: '130px' },
  { key: 'rule', label: 'Discount', width: '1fr', muted: true },
  { key: 'window', label: 'Window', width: '120px' },
]

function isActive(p: PromotionRow): boolean {
  const now = Date.now()
  return new Date(p.starts).getTime() <= now && now < new Date(p.ends).getTime()
}

const rowActions: OverflowMenuItem[] = [
  { id: 'edit', label: 'Edit', icon: 'edit' },
  { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
]

// --- create / edit ---
const showForm = ref(false)
const editId = ref<string | null>(null)
const form = reactive({ code: '', name: '', type: 'CART', starts: '', ends: '', cap: null as number | null })
const ruleDraft = reactive(emptyRuleDraft())
const saving = ref(false)
const error = ref('')

const ruleTypeOptions = computed(() =>
  form.type === 'SUBSCRIPTION' ? RULE_TYPE_OPTIONS_SUBSCRIPTION : RULE_TYPE_OPTIONS_CART,
)

// When the promotion type flips, snap the rule variant back to a valid one for that type — but only
// if the current variant doesn't belong to it (so opening an edit doesn't clobber the parsed rule).
watch(() => form.type, (t) => {
  const valid = ruleTypeOptions.value.map(o => o.value)
  if (!valid.includes(ruleDraft.type)) ruleDraft.type = defaultRuleType(t)
})

function openCreate() {
  editId.value = null
  form.code = ''
  form.name = ''
  form.type = 'CART'
  form.starts = ''
  form.ends = ''
  form.cap = null
  Object.assign(ruleDraft, emptyRuleDraft())
  error.value = ''
  showForm.value = true
}

function openEdit(row: PromotionRow) {
  editId.value = row.id
  form.code = row.code
  form.name = row.name
  form.type = row.type
  form.starts = isoToLocalInput(row.starts)
  form.ends = isoToLocalInput(row.ends)
  form.cap = null
  Object.assign(ruleDraft, parseRule(row.rule))
  error.value = ''
  showForm.value = true
}

function onRowAction(payload: { action: string; row: PromotionRow }) {
  if (payload.action === 'edit') openEdit(payload.row)
  else if (payload.action === 'delete') { deleteTarget.value = payload.row; showDelete.value = true }
}

const addGql = gql`mutation AddPromotion($input: PromotionInput!) { ecom { promotions { add(input: $input) { id } } } }`
const editGql = gql`mutation EditPromotion($id: UUID!, $input: PromotionInput!) { ecom { promotions { promotion(id: $id) { edit(input: $input) { id } } } } }`

function ruleValid(): boolean {
  switch (ruleDraft.type) {
    case 'cartPercentOff':
    case 'subscriptionPercentOff':
      return ruleDraft.percent != null
    case 'cartAmountOff':
    case 'subscriptionAmountOff':
      return !!ruleDraft.amount.trim()
    case 'cartBuyOneGetOne':
      return ruleDraft.buyQuantity != null && ruleDraft.getQuantity != null
    case 'cartFreeShipping':
      return true
  }
}

async function handleSave() {
  if (!selectedStoreId.value) { error.value = 'Select a store first.'; return }
  if (!form.code.trim()) { error.value = 'Code is required.'; return }
  if (!form.name.trim()) { error.value = 'Name is required.'; return }
  if (!form.starts || !form.ends) { error.value = 'Start and end are required.'; return }
  if (!ruleValid()) { error.value = 'Complete the discount rule fields.'; return }
  const starts = localInputToIso(form.starts)
  const ends = localInputToIso(form.ends)
  if (!starts || !ends) { error.value = 'Start and end must be valid dates.'; return }
  saving.value = true
  error.value = ''
  try {
    const input: PromotionInput = {
      storeId: selectedStoreId.value,
      code: form.code.trim(),
      name: form.name.trim(),
      type: form.type as PromotionType,
      rule: buildRule(ruleDraft),
      starts,
      ends,
    }
    // Redemption cap is create-only (the backend's edit ignores it and it isn't readable back).
    if (!editId.value) input.quantity = form.cap && form.cap > 0 ? Number(form.cap) : null
    if (editId.value) await mutation(editGql, { id: editId.value, input })
    else await mutation(addGql, { input })
    showForm.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to save promotion'
  } finally {
    saving.value = false
  }
}

const showDelete = ref(false)
const deleteTarget = ref<PromotionRow | null>(null)
const deleting = ref(false)
const deleteGql = gql`mutation DeletePromotion($id: UUID!) { ecom { promotions { promotion(id: $id) { delete } } } }`

async function handleDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await mutation(deleteGql, { id: deleteTarget.value.id })
    showDelete.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to delete promotion'
  } finally {
    deleting.value = false
  }
}

const isPercent = computed(() => ruleDraft.type === 'cartPercentOff' || ruleDraft.type === 'subscriptionPercentOff')
const isAmount = computed(() => ruleDraft.type === 'cartAmountOff' || ruleDraft.type === 'subscriptionAmountOff')
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Promotions')"
        title="Promotions"
        :subtitle="selectedStoreId ? `${promotions.length} promotions` : 'Select a store'">
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
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedStoreId"
            @click="openCreate">New Promotion</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load promotions — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company.</div>
    <div v-else-if="!stores.length" class="state">This company has no stores — create one first.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="promotions"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No promotions for this store yet."
      :row-actions="rowActions"
      @row-action="onRowAction"
      @row-click="openEdit">
      <template #col-code="{ row }"><code class="mono">{{ row.code }}</code></template>
      <template #col-name="{ row }">{{ row.name }}</template>
      <template #col-type="{ row }"><Badge :color="accent">{{ row.type }}</Badge></template>
      <template #col-rule="{ row }">{{ formatRuleSummary(row.rule) }}</template>
      <template #col-window="{ row }">
        <Badge :color="isActive(row) ? accent : '#888'">{{ isActive(row) ? 'Active' : 'Inactive' }}</Badge>
      </template>
    </GlassTable>
    <ListPager
      v-if="selectedStoreId"
      v-model:offset="offset"
      :page-size="pageSize"
      :count="promotions.length"
      :has-more="hasMore" />

    <Modal
      v-if="showForm"
      :title="editId ? 'Edit Promotion' : 'New Promotion'"
      icon="megaphone"
      :accent="accent"
      @close="showForm = false">
      <div class="form-stack">
        <div class="form-row">
          <TextInput
            v-model="form.code"
            label="Code"
            placeholder="SAVE10"
            mono />
          <TextInput v-model="form.name" label="Name" placeholder="10% off" />
        </div>
        <Select v-model="form.type" label="Applies to" :options="PROMOTION_TYPE_OPTIONS" />
        <Select v-model="ruleDraft.type" label="Discount rule" :options="ruleTypeOptions" />
        <NumberInput
          v-if="isPercent"
          v-model="ruleDraft.percent"
          label="Percent off"
          :min="0"
          :max="100"
          :step="1" />
        <TextInput
          v-if="isAmount"
          v-model="ruleDraft.amount"
          label="Amount off"
          placeholder="5.00"
          mono />
        <div v-if="ruleDraft.type === 'cartBuyOneGetOne'" class="form-row">
          <NumberInput v-model="ruleDraft.buyQuantity" label="Buy quantity" :min="1" />
          <NumberInput v-model="ruleDraft.getQuantity" label="Get free" :min="1" />
        </div>
        <p v-if="ruleDraft.type === 'cartFreeShipping'" class="hint">Zeroes the cart's shipping line(s).</p>
        <div class="form-row">
          <DateInput v-model="form.starts" label="Active from" type="datetime-local" />
          <DateInput v-model="form.ends" label="Active until" type="datetime-local" />
        </div>
        <NumberInput
          v-if="!editId"
          v-model="form.cap"
          label="Redemption cap"
          :min="0"
          placeholder="Blank = unlimited" />
        <p v-else class="hint">The redemption cap is set at creation and can't be changed here.</p>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showForm = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleSave">{{ saving ? 'Saving…' : editId ? 'Save' : 'Create' }}</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Promotion"
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Delete <strong>{{ deleteTarget?.code }}</strong> ({{ deleteTarget?.name }})?</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.pick { width: 170px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
