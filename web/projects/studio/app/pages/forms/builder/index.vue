<script setup lang="ts">
import { getAllFormSchemas, deleteFormSchema, type FormSchema } from '@bosca/forms'
import type { GlassTableColumn } from '@bosca/ui'

type FormSchemaType = 'SUBMISSION' | 'INTERNAL' | 'WORK_OPS'

const { accent } = useCurrentSubsystem()
const { $auth } = useNuxtApp()
const config = useRuntimeConfig()
const router = useRouter()

const schemas = ref<FormSchema[]>([])
const loading = ref(true)
const deleteTarget = ref<FormSchema | null>(null)
const showDeleteModal = ref(false)

const selectedType = ref<'all' | FormSchemaType>('all')
const typeTabs = ['All', 'Submission', 'Internal', 'Work Ops']
const activeTab = ref('All')

function onTab(tab: string) {
  activeTab.value = tab
  const map: Record<string, 'all' | FormSchemaType> = {
    'All': 'all',
    'Submission': 'SUBMISSION',
    'Internal': 'INTERNAL',
    'Work Ops': 'WORK_OPS',
  }
  selectedType.value = map[tab] ?? 'all'
}

const filteredSchemas = computed(() => {
  if (selectedType.value === 'all') return schemas.value
  return schemas.value.filter(s => s.type === selectedType.value)
})

async function fetchSchemas() {
  loading.value = true
  try {
    const token = $auth.token ?? undefined
    schemas.value = await getAllFormSchemas(config.public.apiUrl as string, token)
  } catch (err) {
    console.error('Failed to fetch form schemas:', err)
  } finally {
    loading.value = false
  }
}

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 2fr)' },
  { key: 'key', label: 'Key', width: 'minmax(120px, 1fr)', muted: true },
  { key: 'version', label: 'Version', width: '80px', align: 'right', muted: true },
  { key: 'published', label: 'Status', width: '100px' },
  { key: 'public', label: 'Visibility', width: '100px' },
]

const rows = computed(() =>
  filteredSchemas.value.map(s => ({
    id: s.id,
    name: s.name,
    key: s.key,
    version: s.version,
    published: s.published,
    public: s.public,
    _raw: s,
  })),
)

function openSchema(row: Record<string, unknown>) {
  router.push(`/forms/builder/${row.id}`)
}

function confirmDelete(schema: FormSchema) {
  deleteTarget.value = schema
  showDeleteModal.value = true
}

async function handleDelete() {
  if (!deleteTarget.value) return
  try {
    const token = $auth.token ?? undefined
    await deleteFormSchema(config.public.apiUrl as string, deleteTarget.value.id, token)
    showDeleteModal.value = false
    deleteTarget.value = null
    await fetchSchemas()
  } catch (err) {
    console.error('Failed to delete form schema:', err)
  }
}

onMounted(fetchSchemas)
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Forms', 'Builder')"
        title="Form Builder"
        :subtitle="`${filteredSchemas.length} form${filteredSchemas.length !== 1 ? 's' : ''}`"
        :tabs="typeTabs"
        :active-tab="activeTab"
        @tab="onTab"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="router.push('/forms/builder/new')">
            New Form
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Form Schemas" glass>
      <GlassTable
        :columns="columns"
        :rows="rows"
        row-key="id"
        :loading="loading"
        empty-text="No form schemas found."
        @row-click="openSchema"
      >
        <template #col-name="{ row }">
          <div class="name-cell">
            <div
              class="form-icon-box"
              :style="{
                background: `color-mix(in oklch, ${accent} 16%, transparent)`,
                border: `1px solid color-mix(in oklch, ${accent} 28%, transparent)`,
              }"
            >
              <Icon name="form" :size="13" :color="accent" />
            </div>
            <span class="form-name">{{ row.name }}</span>
          </div>
        </template>
        <template #col-key="{ value }">
          <span class="mono">{{ value }}</span>
        </template>
        <template #col-version="{ value }">
          <span class="mono tabular">v{{ value }}</span>
        </template>
        <template #col-published="{ value }">
          <Badge :color="value ? '#34d99a' : '#ffb547'">
            {{ value ? 'Published' : 'Draft' }}
          </Badge>
        </template>
        <template #col-public="{ value }">
          <Badge :color="value ? '#34d99a' : 'var(--fg-3)'">
            {{ value ? 'Public' : 'Private' }}
          </Badge>
        </template>
        <template #actions="{ row }">
          <button class="delete-action" @click.stop="confirmDelete(row._raw)">
            <Icon name="trash" :size="13" color="var(--fg-3)" />
          </button>
        </template>
      </GlassTable>
    </SectionCard>

    <ConfirmModal
      v-if="showDeleteModal"
      :title="`Delete &quot;${deleteTarget?.name}&quot;?`"
      subtitle="This action cannot be undone."
      @close="showDeleteModal = false"
      @confirm="handleDelete"
    />
  </PageShell>
</template>

<style scoped>
.name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.form-icon-box {
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  flex: 0 0 28px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.form-name {
  font-weight: 500;
  color: var(--fg-0);
}

.mono {
  font-family: 'Geist Mono', monospace;
  font-size: 12px;
}

.tabular {
  font-variant-numeric: tabular-nums;
}

.delete-action {
  padding: 4px;
  border-radius: var(--r-xs);
  transition: background 0.12s;
}

.delete-action:hover {
  background: color-mix(in oklch, var(--err) 12%, transparent);
}
</style>
