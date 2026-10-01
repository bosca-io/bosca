<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'
import type { FulfillmentCenterInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

interface CenterRow {
  id: string
  name: string
  connectorKey: string
  shippingProvider: { id: string; name: string } | null
  address1: string
  address2: string | null
  city: string
  state: string
  country: string
  zip: string
}

const listGql = gql`
  query CommerceFulfillmentCenters($companyId: UUID!) {
    ecom {
      fulfillmentCenters(companyId: $companyId) {
        id
        name
        connectorKey
        shippingProvider { id name }
        address1
        address2
        city
        state
        country
        zip
      }
    }
  }
`
const { data, status, refresh, error: loadError } = useAsyncQuery<{ ecom: { fulfillmentCenters: CenterRow[] } }>(
  'commerce-fulfillment-centers', listGql, { companyId },
)
const centers = computed(() => data.value?.ecom?.fulfillmentCenters ?? [])

// Shipping providers for the create form (a center hands off to a shipping provider).
const providersGql = gql`query CommerceFcShippingProviders($companyId: UUID!) { ecom { shippingProviders(companyId: $companyId) { id name } } }`
const { data: provData } = useAsyncQuery<{ ecom: { shippingProviders: { id: string; name: string }[] } }>(
  'commerce-fc-shipping-providers', providersGql, { companyId },
)
const providerOptions = computed<SelectOption[]>(() => (provData.value?.ecom?.shippingProviders ?? []).map(p => ({ value: p.id, label: p.name })))

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Center', width: 'minmax(180px, 2fr)' },
  { key: 'connector', label: 'Connector', width: '120px', muted: true },
  { key: 'provider', label: 'Shipping provider', width: '1fr', muted: true },
  { key: 'location', label: 'Location', width: '1fr', muted: true },
]

const showForm = ref(false)
const editId = ref<string | null>(null)
const form = reactive({
  name: '',
  connectorKey: 'manual',
  shippingProviderId: '',
  address1: '',
  address2: '',
  city: '',
  state: '',
  country: '',
  zip: '',
})
const saving = ref(false)
const error = ref('')

const rowActions: OverflowMenuItem[] = [{ id: 'edit', label: 'Edit', icon: 'edit' }]

function openCreate() {
  editId.value = null
  form.name = ''
  form.connectorKey = 'manual'
  form.shippingProviderId = ''
  form.address1 = ''
  form.address2 = ''
  form.city = ''
  form.state = ''
  form.country = ''
  form.zip = ''
  error.value = ''
  showForm.value = true
}

function openEdit(row: CenterRow) {
  editId.value = row.id
  form.name = row.name
  form.connectorKey = row.connectorKey
  form.shippingProviderId = row.shippingProvider?.id ?? ''
  form.address1 = row.address1
  form.address2 = row.address2 ?? ''
  form.city = row.city
  form.state = row.state
  form.country = row.country
  form.zip = row.zip
  error.value = ''
  showForm.value = true
}

function onRowAction(payload: { action: string; row: CenterRow }) {
  if (payload.action === 'edit') openEdit(payload.row)
}

const addGql = gql`mutation AddFulfillmentCenter($input: FulfillmentCenterInput!) { ecom { fulfillment { addCenter(input: $input) { id } } } }`
const editGql = gql`mutation EditFulfillmentCenter($id: UUID!, $input: FulfillmentCenterInput!) { ecom { fulfillment { editCenter(id: $id, input: $input) { id } } } }`

async function handleSave() {
  if (!selectedId.value) { error.value = 'Select a company first.'; return }
  if (!form.name.trim()) { error.value = 'Name is required.'; return }
  if (!form.connectorKey.trim()) { error.value = 'Connector key is required.'; return }
  if (!form.shippingProviderId) { error.value = 'Shipping provider is required.'; return }
  if (!form.address1.trim() || !form.city.trim() || !form.state.trim() || !form.country.trim() || !form.zip.trim()) {
    error.value = 'Address, city, state, country, and zip are required.'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const input: FulfillmentCenterInput = {
      companyId: selectedId.value,
      name: form.name.trim(),
      connectorKey: form.connectorKey.trim(),
      shippingProviderId: form.shippingProviderId,
      address1: form.address1.trim(),
      address2: form.address2.trim() || null,
      city: form.city.trim(),
      state: form.state.trim(),
      country: form.country.trim(),
      zip: form.zip.trim(),
    }
    if (editId.value) await mutation(editGql, { id: editId.value, input })
    else await mutation(addGql, { input })
    showForm.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to save fulfillment center'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Fulfillment Centers')"
        title="Fulfillment Centers"
        :subtitle="selectedId ? `${centers.length} centers` : 'Select a company'">
        <template #actions>
          <div class="pick"><Select
            v-model="selectedId"
            placeholder="Company…"
            :options="companyOptions"
            size="sm" /></div>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            :disabled="!selectedId"
            @click="openCreate">New Center</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load fulfillment centers — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company to view its fulfillment centers.</div>
    <GlassTable
      v-else
      :columns="columns"
      :rows="centers"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No fulfillment centers yet. A center holds inventory and hands off to a shipping provider."
      :row-actions="rowActions"
      @row-action="onRowAction"
      @row-click="openEdit">
      <template #col-name="{ row }">{{ row.name }}</template>
      <template #col-connector="{ row }"><code class="mono">{{ row.connectorKey }}</code></template>
      <template #col-provider="{ row }">{{ row.shippingProvider?.name ?? '—' }}</template>
      <template #col-location="{ row }">{{ row.city }}, {{ row.state }} {{ row.country }}</template>
    </GlassTable>

    <Modal
      v-if="showForm"
      :title="editId ? 'Edit Fulfillment Center' : 'New Fulfillment Center'"
      icon="container"
      :accent="accent"
      @close="showForm = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="e.g. West Coast DC" />
        <div class="form-row">
          <Select v-model="form.connectorKey" label="Connector" :options="CONNECTOR_KEY_OPTIONS" />
          <Select
            v-model="form.shippingProviderId"
            label="Shipping provider"
            placeholder="Select…"
            :options="providerOptions" />
        </div>
        <p class="hint">The connector drives inventory sync — <code class="mono">manual</code> means stock is managed here with no external sync.</p>
        <TextInput v-model="form.address1" label="Address" placeholder="Street address" />
        <TextInput v-model="form.address2" label="Address line 2" placeholder="Suite, unit (optional)" />
        <div class="form-row">
          <TextInput v-model="form.city" label="City" />
          <TextInput v-model="form.state" label="State / region" />
        </div>
        <div class="form-row">
          <TextInput v-model="form.country" label="Country" placeholder="US" />
          <TextInput v-model="form.zip" label="Zip / postal code" />
        </div>
        <p v-if="!providerOptions.length" class="hint">This company has no shipping providers — register one first.</p>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showForm = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleSave">
          {{ saving ? 'Saving…' : editId ? 'Save' : 'Create' }}
        </Button>
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
.pick { width: 180px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
