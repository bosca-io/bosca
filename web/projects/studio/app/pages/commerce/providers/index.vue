<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'
import type { ProviderInput, ShippingProviderInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

interface PaymentRow { id: string; name: string; providerKey: string }
interface ShippingRow { id: string; name: string; key: string; providerKey: string }

// The lists request only ungated fields — `configuration` (admin-gated, secret-bearing) is never read
// back into Studio. Provider settings are write-only here: set once at registration time.
const paymentsGql = gql`
  query CommercePaymentProviders($companyId: UUID!) {
    ecom { paymentProviders(companyId: $companyId) { id name providerKey } }
  }
`
const shippingGql = gql`
  query CommerceShippingProviders($companyId: UUID!) {
    ecom { shippingProviders(companyId: $companyId) { id name key providerKey } }
  }
`

const { data: payData, status: payStatus, refresh: refreshPay, error: payError } =
  useAsyncQuery<{ ecom: { paymentProviders: PaymentRow[] } }>('commerce-payment-providers', paymentsGql, { companyId })
const { data: shipData, status: shipStatus, refresh: refreshShip, error: shipError } =
  useAsyncQuery<{ ecom: { shippingProviders: ShippingRow[] } }>('commerce-shipping-providers', shippingGql, { companyId })

const payments = computed(() => payData.value?.ecom?.paymentProviders ?? [])
const shipments = computed(() => shipData.value?.ecom?.shippingProviders ?? [])

// The valid providerKey values are the DI-registered SPI implementations — enumerated by the backend,
// so the form offers a dropdown rather than free text (a typo'd key would never resolve at checkout).
const keysGql = gql`query CommerceProviderKeys { ecom { paymentProviderKeys shippingProviderKeys } }`
const { data: keysData } = useAsyncQuery<{ ecom: { paymentProviderKeys: string[]; shippingProviderKeys: string[] } }>(
  'commerce-provider-keys', keysGql,
)
const paymentKeyOptions = computed<SelectOption[]>(() => (keysData.value?.ecom?.paymentProviderKeys ?? []).map(k => ({ value: k, label: k })))
const shippingKeyOptions = computed<SelectOption[]>(() => (keysData.value?.ecom?.shippingProviderKeys ?? []).map(k => ({ value: k, label: k })))

const paymentColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 2fr)' },
  { key: 'providerKey', label: 'Provider key', width: '1fr', muted: true },
]
const shippingColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 2fr)' },
  { key: 'key', label: 'Business key', width: '1fr', muted: true },
  { key: 'providerKey', label: 'Provider key', width: '1fr', muted: true },
]

// --- create / edit modal (shared between payment and shipping kinds) ---
const showForm = ref(false)
const kind = ref<'payment' | 'shipping'>('payment')
const editId = ref<string | null>(null)
const form = reactive({ name: '', key: '', providerKey: '' })
const cfgDraft = reactive(emptyProviderConfigDraft())
const replaceSettings = ref(false)
const saving = ref(false)
const error = ref('')

const providerKeyOptions = computed<SelectOption[]>(() =>
  kind.value === 'payment' ? paymentKeyOptions.value : shippingKeyOptions.value,
)
// On edit the settings editor is opt-in: secrets are never read back, so it starts blank and we send
// a new configuration only when the admin explicitly replaces it (otherwise the existing one is kept).
const showSettingsEditor = computed(() => !editId.value || replaceSettings.value)
const modalTitle = computed(() => {
  const noun = kind.value === 'payment' ? 'Payment Provider' : 'Shipping Provider'
  return editId.value ? `Edit ${noun}` : `Register ${noun}`
})
const rowActions: OverflowMenuItem[] = [
  { id: 'edit', label: 'Edit', icon: 'edit' },
  { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
]

// --- delete ---
const showDelete = ref(false)
const deleteKind = ref<'payment' | 'shipping'>('payment')
const deleteTarget = ref<{ id: string; name: string } | null>(null)
const deleting = ref(false)
const deletePaymentGql = gql`mutation DeletePaymentProvider($id: UUID!) { ecom { providers { deletePayment(id: $id) } } }`
const deleteShippingGql = gql`mutation DeleteShippingProvider($id: UUID!) { ecom { providers { deleteShipping(id: $id) } } }`

function onAction(k: 'payment' | 'shipping', payload: { action: string; row: { id: string; name: string; providerKey: string; key?: string } }) {
  if (payload.action === 'edit') openEdit(k, payload.row)
  else if (payload.action === 'delete') {
    deleteKind.value = k
    deleteTarget.value = { id: payload.row.id, name: payload.row.name }
    showDelete.value = true
  }
}

async function handleDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    if (deleteKind.value === 'payment') { await mutation(deletePaymentGql, { id: deleteTarget.value.id }); await refreshPay() }
    else { await mutation(deleteShippingGql, { id: deleteTarget.value.id }); await refreshShip() }
    showDelete.value = false
  } finally {
    deleting.value = false
  }
}

function resetForm() {
  form.name = ''
  form.key = ''
  form.providerKey = ''
  Object.assign(cfgDraft, emptyProviderConfigDraft())
  cfgDraft.entries = []
  replaceSettings.value = false
  error.value = ''
}

function openRegister(k: 'payment' | 'shipping') {
  editId.value = null
  kind.value = k
  resetForm()
  showForm.value = true
}

function openEdit(k: 'payment' | 'shipping', row: { id: string; name: string; providerKey: string; key?: string }) {
  editId.value = row.id
  kind.value = k
  resetForm()
  form.name = row.name
  form.providerKey = row.providerKey
  form.key = row.key ?? ''
  showForm.value = true
}

const addPaymentGql = gql`mutation AddPaymentProvider($input: ProviderInput!) { ecom { providers { addPayment(input: $input) { id } } } }`
const addShippingGql = gql`mutation AddShippingProvider($input: ShippingProviderInput!) { ecom { providers { addShipping(input: $input) { id } } } }`
const editPaymentGql = gql`mutation EditPaymentProvider($id: UUID!, $input: ProviderInput!) { ecom { providers { editPayment(id: $id, input: $input) { id } } } }`
const editShippingGql = gql`mutation EditShippingProvider($id: UUID!, $input: ShippingProviderInput!) { ecom { providers { editShipping(id: $id, input: $input) { id } } } }`

async function handleSave() {
  if (!selectedId.value) { error.value = 'Select a company first.'; return }
  if (!form.name.trim()) { error.value = 'Name is required.'; return }
  if (!form.providerKey.trim()) { error.value = 'Provider implementation is required.'; return }
  if (kind.value === 'shipping' && !form.key.trim()) { error.value = 'Business key is required.'; return }
  saving.value = true
  error.value = ''
  try {
    // Create always sets configuration; edit sends it only when replacing (null = keep existing).
    const configuration = showSettingsEditor.value ? buildProviderConfiguration(cfgDraft) : null
    if (kind.value === 'payment') {
      const input: ProviderInput = { companyId: selectedId.value, name: form.name.trim(), providerKey: form.providerKey.trim(), configuration }
      if (editId.value) await mutation(editPaymentGql, { id: editId.value, input })
      else await mutation(addPaymentGql, { input })
      await refreshPay()
    } else {
      const input: ShippingProviderInput = {
        companyId: selectedId.value,
        name: form.name.trim(),
        key: form.key.trim(),
        providerKey: form.providerKey.trim(),
        configuration,
      }
      if (editId.value) await mutation(editShippingGql, { id: editId.value, input })
      else await mutation(addShippingGql, { input })
      await refreshShip()
    }
    showForm.value = false
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : editId.value ? 'Failed to save provider' : 'Failed to register provider'
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
        :breadcrumb="buildBreadcrumb('Commerce', 'Providers')"
        title="Providers"
        :subtitle="selectedId ? 'Payment & shipping gateways' : 'Select a company'">
        <template #actions>
          <div class="pick"><Select
            v-model="selectedId"
            placeholder="Company…"
            :options="companyOptions"
            size="sm" /></div>
        </template>
      </PageHeader>
    </template>

    <div v-if="payError" class="query-error">Couldn't load payment providers — {{ payError.message }}</div>
    <div v-if="shipError" class="query-error">Couldn't load shipping providers — {{ shipError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company to manage its providers.</div>
    <template v-else>
      <SectionCard title="Payment providers">
        <template #right>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openRegister('payment')">Register</Button>
        </template>
        <GlassTable
          :columns="paymentColumns"
          :rows="payments"
          row-key="id"
          :loading="payStatus === 'pending'"
          empty-text="No payment providers registered."
          :row-actions="rowActions"
          @row-action="(p) => onAction('payment', p)"
          @row-click="(row) => openEdit('payment', row)">
          <template #col-name="{ row }">{{ row.name }}</template>
          <template #col-providerKey="{ row }"><code class="mono">{{ row.providerKey }}</code></template>
        </GlassTable>
      </SectionCard>

      <SectionCard title="Shipping providers">
        <template #right>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openRegister('shipping')">Register</Button>
        </template>
        <GlassTable
          :columns="shippingColumns"
          :rows="shipments"
          row-key="id"
          :loading="shipStatus === 'pending'"
          empty-text="No shipping providers registered."
          :row-actions="rowActions"
          @row-action="(p) => onAction('shipping', p)"
          @row-click="(row) => openEdit('shipping', row)">
          <template #col-name="{ row }">{{ row.name }}</template>
          <template #col-key="{ row }"><code class="mono">{{ row.key }}</code></template>
          <template #col-providerKey="{ row }"><code class="mono">{{ row.providerKey }}</code></template>
        </GlassTable>
      </SectionCard>
    </template>

    <Modal
      v-if="showForm"
      :title="modalTitle"
      icon="credit-card"
      :accent="accent"
      @close="showForm = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="e.g. Stripe (live)" />
        <TextInput
          v-if="kind === 'shipping'"
          v-model="form.key"
          label="Business key"
          placeholder="unique-across-module"
          mono />
        <Select
          v-model="form.providerKey"
          label="Provider implementation"
          placeholder="Select…"
          :options="providerKeyOptions" />
        <p v-if="!providerKeyOptions.length" class="hint">No {{ kind }} provider implementations are registered on the server.</p>

        <label v-if="editId" class="toggle">
          <Switch v-model="replaceSettings" :accent="accent" /> Replace settings
        </label>
        <p v-if="editId && !replaceSettings" class="hint">Existing settings are kept. Secrets are never shown — toggle to enter new ones.</p>

        <template v-if="showSettingsEditor">
          <Select v-model="cfgDraft.type" label="Settings" :options="PROVIDER_CONFIG_TYPE_OPTIONS" />
          <div v-if="cfgDraft.type === 'keyValue'" class="kv-editor">
            <div v-for="(entry, i) in cfgDraft.entries" :key="i" class="kv-row">
              <TextInput v-model="entry.key" placeholder="Key (e.g. apiKey)" mono />
              <TextInput v-model="entry.value" placeholder="Value" mono />
              <Button icon="trash" size="sm" @click="cfgDraft.entries.splice(i, 1)" />
            </div>
            <Button icon="plus" size="sm" @click="cfgDraft.entries.push({ key: '', value: '' })">Add entry</Button>
            <p class="hint">Values may hold gateway secrets. They're write-only here and never read back into Studio.</p>
          </div>
        </template>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showForm = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleSave">
          {{ saving ? (editId ? 'Saving…' : 'Registering…') : editId ? 'Save' : 'Register' }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Provider"
      subtitle="Soft-deletes the provider configuration."
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Delete <strong>{{ deleteTarget?.name }}</strong>? A store or fulfillment center still bound to it must be repointed first.</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.toggle { display: inline-flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--fg-2); cursor: pointer; }
.kv-editor { display: flex; flex-direction: column; gap: 10px; padding: 12px; background: var(--bg-3); border-radius: 6px; }
.kv-row { display: grid; grid-template-columns: 1fr 1fr auto; gap: 8px; align-items: center; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.pick { width: 200px; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
