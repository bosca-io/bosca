<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption, OverflowMenuItem } from '@bosca/ui'
import type { ContainerInput } from '~/types/graphql'

definePageMeta({ middleware: 'commerce-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const { companies, selected, selectedId } = useCommerceCompany()

const companyOptions = computed<SelectOption[]>(() => companies.value.map(c => ({ value: c.id, label: c.name })))
const companyId = computed(() => selectedId.value ?? undefined)

// Unit suffixes for the selected company — dimensions and weights are stored in the company's units.
const lenAbbr = computed(() => lengthUnitAbbr(selected.value?.lengthUnit))
const wtAbbr = computed(() => weightUnitAbbr(selected.value?.weightUnit))

interface ContainerRow {
  id: string
  name: string
  width: number
  height: number
  length: number
  weight: number
  supportedWidth: number
  supportedHeight: number
  supportedLength: number
  supportedWeight: number
}
const listGql = gql`
  query CommerceContainers($companyId: UUID!) {
    ecom {
      containers(companyId: $companyId) {
        id name
        width height length weight
        supportedWidth supportedHeight supportedLength supportedWeight
      }
    }
  }
`
const { data, status, refresh, error: loadError } = useAsyncQuery<{ ecom: { containers: ContainerRow[] } }>(
  'commerce-containers', listGql, { companyId },
)
const containers = computed(() => data.value?.ecom?.containers ?? [])

const columns = computed<GlassTableColumn[]>(() => [
  { key: 'name', label: 'Container', width: 'minmax(160px, 1.5fr)' },
  { key: 'outer', label: `Outer L×W×H (${lenAbbr.value})`, width: '1fr', muted: true },
  { key: 'tare', label: `Tare (${wtAbbr.value})`, width: '90px', align: 'right' },
  { key: 'capacity', label: `Capacity L×W×H (${lenAbbr.value})`, width: '1fr', muted: true },
  { key: 'maxWeight', label: `Max weight (${wtAbbr.value})`, width: '110px', align: 'right' },
])
const rowActions: OverflowMenuItem[] = [
  { id: 'edit', label: 'Edit', icon: 'edit' },
  { id: 'delete', label: 'Delete', icon: 'trash' },
]
function dims(l: number, w: number, h: number): string { return `${l} × ${w} × ${h}` }

// --- add / edit ---
const showForm = ref(false)
const editId = ref<string | null>(null)
const saving = ref(false)
const error = ref('')
const form = reactive({
  name: '', width: 0, height: 0, length: 0, weight: 0,
  supportedWidth: 0, supportedHeight: 0, supportedLength: 0, supportedWeight: 0,
})

function reset() {
  Object.assign(form, { name: '', width: 0, height: 0, length: 0, weight: 0, supportedWidth: 0, supportedHeight: 0, supportedLength: 0, supportedWeight: 0 })
}
function openCreate() { editId.value = null; reset(); error.value = ''; showForm.value = true }
function openEdit(row: ContainerRow) {
  editId.value = row.id
  Object.assign(form, {
    name: row.name, width: row.width, height: row.height, length: row.length, weight: row.weight,
    supportedWidth: row.supportedWidth, supportedHeight: row.supportedHeight, supportedLength: row.supportedLength, supportedWeight: row.supportedWeight,
  })
  error.value = ''
  showForm.value = true
}

const addGql = gql`mutation AddContainer($input: ContainerInput!) { ecom { containers { add(input: $input) { id } } } }`
const editGql = gql`mutation EditContainer($id: UUID!, $input: ContainerInput!) { ecom { containers { container(id: $id) { edit(input: $input) { id } } } } }`

async function handleSave() {
  if (!selectedId.value) { error.value = 'Select a company first.'; return }
  if (!form.name.trim()) { error.value = 'Name is required.'; return }
  saving.value = true
  error.value = ''
  try {
    const input: ContainerInput = {
      companyId: selectedId.value,
      name: form.name.trim(),
      width: Number(form.width), height: Number(form.height), length: Number(form.length), weight: Number(form.weight),
      supportedWidth: Number(form.supportedWidth), supportedHeight: Number(form.supportedHeight),
      supportedLength: Number(form.supportedLength), supportedWeight: Number(form.supportedWeight),
    }
    if (editId.value) await mutation(editGql, { id: editId.value, input })
    else await mutation(addGql, { input })
    showForm.value = false
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to save container'
  } finally {
    saving.value = false
  }
}

// --- delete ---
const showDelete = ref(false)
const deleteTarget = ref<ContainerRow | null>(null)
const deleting = ref(false)
const deleteGql = gql`mutation DeleteContainer($id: UUID!) { ecom { containers { container(id: $id) { delete } } } }`

function onRowAction(payload: { action: string; row: ContainerRow }) {
  if (payload.action === 'edit') openEdit(payload.row)
  else if (payload.action === 'delete') { deleteTarget.value = payload.row; showDelete.value = true }
}
async function handleDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await mutation(deleteGql, { id: deleteTarget.value.id })
    showDelete.value = false
    await refresh()
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Commerce', 'Containers')"
        title="Containers"
        :subtitle="selectedId ? `${containers.length} box types` : 'Select a company'">
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
            @click="openCreate">New Container</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load containers — {{ loadError.message }}</div>

    <div v-if="!selectedId" class="state">Select a company.</div>
    <template v-else>
      <p class="hint">Box types the packer fills when an order is paid. Capacity is the inner space + max payload weight an item must fit within.</p>
      <GlassTable
        :columns="columns"
        :rows="containers"
        row-key="id"
        :loading="status === 'pending'"
        empty-text="No containers yet. Add box types so orders pack for density."
        :row-actions="rowActions"
        @row-action="onRowAction">
        <template #col-name="{ row }">{{ row.name }}</template>
        <template #col-outer="{ row }">{{ dims(row.length, row.width, row.height) }}</template>
        <template #col-tare="{ row }">{{ row.weight }}</template>
        <template #col-capacity="{ row }">{{ dims(row.supportedLength, row.supportedWidth, row.supportedHeight) }}</template>
        <template #col-maxWeight="{ row }">{{ row.supportedWeight }}</template>
      </GlassTable>
    </template>

    <Modal
      v-if="showForm"
      :title="editId ? 'Edit Container' : 'New Container'"
      icon="container"
      :accent="accent"
      @close="showForm = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="e.g. Small Box" />
        <p class="section">Outer dimensions + tare weight</p>
        <div class="form-row dims">
          <NumberInput
            v-model="form.length"
            :label="`Length (${lenAbbr})`"
            :min="0"
            :step="0.1" />
          <NumberInput
            v-model="form.width"
            :label="`Width (${lenAbbr})`"
            :min="0"
            :step="0.1" />
          <NumberInput
            v-model="form.height"
            :label="`Height (${lenAbbr})`"
            :min="0"
            :step="0.1" />
          <NumberInput
            v-model="form.weight"
            :label="`Tare wt (${wtAbbr})`"
            :min="0"
            :step="0.1" />
        </div>
        <p class="section">Inner capacity + max payload weight</p>
        <div class="form-row dims">
          <NumberInput
            v-model="form.supportedLength"
            :label="`Length (${lenAbbr})`"
            :min="0"
            :step="0.1" />
          <NumberInput
            v-model="form.supportedWidth"
            :label="`Width (${lenAbbr})`"
            :min="0"
            :step="0.1" />
          <NumberInput
            v-model="form.supportedHeight"
            :label="`Height (${lenAbbr})`"
            :min="0"
            :step="0.1" />
          <NumberInput
            v-model="form.supportedWeight"
            :label="`Max wt (${wtAbbr})`"
            :min="0"
            :step="0.1" />
        </div>
        <p class="hint">Dimensions are in {{ lenAbbr }} and weights in {{ wtAbbr }} — the selected company's units (change them on the company page).</p>
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showForm = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleSave">{{ saving ? 'Saving…' : 'Save' }}</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Container"
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Remove <strong>{{ deleteTarget?.name }}</strong>? Existing shipments keep their packed boxes.</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.query-error { background: var(--bg-3); border-left: 3px solid var(--err, #ff5c5c); padding: 8px 10px; font-size: 12.5px; color: var(--fg-2); border-radius: 4px; margin-bottom: 12px; }
.hint { color: var(--fg-3); font-size: 12.5px; margin: 0 0 10px; }
.section { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--fg-3); margin: 4px 0 0; }
.form-stack { display: flex; flex-direction: column; gap: 12px; }
.form-row { display: grid; gap: 12px; }
.form-row.dims { grid-template-columns: 1fr 1fr 1fr 1fr; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.pick { width: 170px; }
</style>
