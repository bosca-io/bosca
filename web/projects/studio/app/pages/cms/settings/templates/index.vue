<script setup lang="ts">
import gql from 'graphql-tag'
import Sortable from 'sortablejs'
import type { GlassTableColumn } from '@bosca/ui'
import { compareTemplatesByOrder, templateSortValue, TEMPLATE_SORT_ATTRIBUTE } from '~/utils/templateOrder'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const templatesGql = gql`
  query GetTemplates {
    content {
      documentTemplates { all { metadata { id name attributes } } }
      dataTemplates { all { metadata { id name attributes } } }
      guideTemplates { all { metadata { id name attributes } } }
      collections { templates { all { metadata { id name attributes } } } }
    }
  }
`

const addTemplateGql = gql`
  mutation AddTemplate($input: MetadataInput!) {
    content { metadata { add(metadata: $input, setReady: true) { id } } }
  }
`

const setTemplateSortGql = gql`
  mutation SetTemplateSort($id: UUID!, $attributes: JSON!) {
    content { metadata { mergeMetadataAttributes(id: $id, attributes: $attributes) } }
  }
`

interface TemplateEntry { metadata: { id: string; name: string; attributes: Record<string, unknown> | null } }
interface TemplateRow { id: string; name: string; type: string; attributes: Record<string, unknown> | null }
interface TemplatesData {
  content: {
    documentTemplates: { all: TemplateEntry[] }
    dataTemplates: { all: TemplateEntry[] }
    guideTemplates: { all: TemplateEntry[] }
    collections: { templates: { all: TemplateEntry[] } }
  }
}

const { data, status, refresh } = useAsyncQuery<TemplatesData>('cms-templates', templatesGql, {})

function toRows(entries: TemplateEntry[] | undefined, type: string): TemplateRow[] {
  return (entries ?? [])
    .map(t => ({ id: t.metadata.id, name: t.metadata.name, type, attributes: t.metadata.attributes }))
    .sort(compareTemplatesByOrder)
}

const allTemplates = computed<TemplateRow[]>(() => [
  ...toRows(data.value?.content?.documentTemplates?.all, 'Document'),
  ...toRows(data.value?.content?.dataTemplates?.all, 'Data'),
  ...toRows(data.value?.content?.guideTemplates?.all, 'Guide'),
  ...toRows(data.value?.content?.collections?.templates?.all, 'Collection'),
])

// ── Type tabs + drag-and-drop order ─────────────────────────────────────────
// Order is per template type, so rows can only be dragged on a single-type
// tab. Each template's position is saved in its `sort` attribute; the New
// Document and New Guide menus list templates in that order.
const TYPE_TABS = ['All', 'Document', 'Data', 'Guide', 'Collection']
const activeTab = ref('All')
const canReorder = computed(() => activeTab.value !== 'All')

// Local mirror of the visible rows. SortableJS moves DOM nodes directly, so the
// table renders from this list and we splice it on drop to keep Vue in step;
// every server response replaces it. Same approach as the collection Items tab.
const rows = ref<TemplateRow[]>([])
watch([allTemplates, activeTab], () => {
  rows.value = activeTab.value === 'All'
    ? [...allTemplates.value]
    : allTemplates.value.filter(t => t.type === activeTab.value)
}, { immediate: true })

const tableEl = ref<HTMLElement | null>(null)
let sortable: Sortable | null = null

async function applyReorder(oldIndex: number, newIndex: number) {
  if (oldIndex === newIndex) return
  const list = rows.value
  const moved = list[oldIndex]
  if (!moved) return
  list.splice(oldIndex, 1)
  list.splice(newIndex, 0, moved)
  try {
    // Number every row in this tab so the whole visible order is saved,
    // including templates that had no position yet.
    for (const [position, row] of list.entries()) {
      if (templateSortValue(row) === position) continue
      await gqlMutation(setTemplateSortGql, { id: row.id, attributes: { [TEMPLATE_SORT_ATTRIBUTE]: position } })
    }
  } catch (e) {
    toast.error((e instanceof Error && e.message) || 'Failed to reorder templates')
  } finally {
    await refresh()
  }
}

watch([tableEl, canReorder, rows], () => {
  sortable?.destroy()
  sortable = null
  if (!canReorder.value) return
  const host = tableEl.value?.querySelector('.glass-table') as HTMLElement | null
  if (!host) return
  sortable = Sortable.create(host, {
    draggable: '.gt-row',
    handle: '.template-drag-handle',
    animation: 150,
    ghostClass: 'gt-row--ghost',
    onEnd: async (evt) => {
      // The table's header row shares the container, so use the
      // draggable-only indices (they count rows, not the header).
      const oldIndex = evt.oldDraggableIndex
      const newIndex = evt.newDraggableIndex
      if (oldIndex === undefined || newIndex === undefined) return
      await applyReorder(oldIndex, newIndex)
    },
  })
}, { flush: 'post' })

onBeforeUnmount(() => {
  sortable?.destroy()
  sortable = null
})

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '120px' },
]

const showCreate = ref(false)
const newName = ref('New Template')
const newType = ref('Document')
const creating = ref(false)

const TEMPLATE_TYPES = [
  { value: 'Document', label: 'Document' },
  { value: 'Data', label: 'Data' },
  { value: 'Guide', label: 'Guide' },
  { value: 'Collection', label: 'Collection' },
]

const CONTENT_TYPES: Record<string, string> = {
  Document: 'bosca/v-document-template',
  Data: 'bosca/v-data-template',
  Guide: 'bosca/v-guide-template',
  Collection: 'bosca/v-collection-template',
}

function buildTemplateInput(name: string, type: string): Record<string, unknown> {
  const base: Record<string, unknown> = {
    contentType: CONTENT_TYPES[type],
    languageTag: 'en',
    name,
    attributes: { 'editor.type': 'Template', 'template.type': type },
  }

  if (type === 'Document') {
    base.documentTemplate = {
      attributes: [],
      content: { document: { type: 'doc', content: [{ type: 'heading', attrs: { level: 1 } }, { type: 'paragraph' }] } },
      schema: { content: 'heading block+' },
      configuration: { content: 'heading block+' },
      containers: [],
      defaultAttributes: { type: 'Item' },
    }
  } else if (type === 'Data') {
    base.dataTemplate = { attributes: [], defaultAttributes: {} }
  } else if (type === 'Guide') {
    base.documentTemplate = { attributes: [], content: {}, configuration: {}, containers: [], defaultAttributes: {} }
    base.guideTemplate = { rrule: '', steps: [], type: 'LINEAR' }
  } else if (type === 'Collection') {
    base.collectionTemplate = { attributes: [], filters: { filters: [] }, configuration: {}, defaultAttributes: {}, ordering: [] }
  }

  return base
}

async function handleCreate() {
  creating.value = true
  try {
    const input = buildTemplateInput(newName.value, newType.value)
    const result = await gqlMutation<{ content: { metadata: { add: { id: string } } } }>(addTemplateGql, { input })
    const newId = result.content.metadata.add.id
    showCreate.value = false
    toast.success('Template created')
    router.push(`/cms/settings/templates/${newId}`)
  } catch {
    toast.error('Failed to create template')
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Settings', 'Templates')"
        title="Templates"
        :subtitle="`${allTemplates.length} templates`"
        :tabs="TYPE_TABS"
        :active-tab="activeTab"
        @tab="activeTab = $event">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showCreate = true">New Template</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Templates">
      <div ref="tableEl" class="templates-table-wrap">
        <GlassTable
          :columns="columns"
          :rows="rows"
          :loading="status === 'pending' && rows.length === 0"
          empty-text="No templates."
          arrow
          @row-click="(r: { id: string }) => router.push(`/cms/settings/templates/${r.id}`)">
          <template #col-name="{ row }">
            <div class="template-name-cell">
              <button
                v-if="canReorder"
                class="template-drag-handle"
                title="Drag to reorder"
                @click.stop>
                <Icon name="grip" :size="14" color="var(--fg-3)" />
              </button>
              <span style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span>
            </div>
          </template>
          <template #col-type="{ row }"><Badge :color="accent">{{ row.type }}</Badge></template>
        </GlassTable>
      </div>
    </SectionCard>

    <Modal
      v-if="showCreate"
      title="New Template"
      icon="wand"
      :accent="accent"
      @close="showCreate = false">
      <TextInput
        v-model="newName"
        label="Name"
        placeholder="Template name"
        autofocus />
      <Select v-model="newType" :options="TEMPLATE_TYPES" label="Template Type" />
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newName.trim() || creating"
          @click="handleCreate">
          {{ creating ? 'Creating…' : 'Create' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.templates-table-wrap { display: contents; }
.template-name-cell { display: flex; align-items: center; gap: 10px; min-width: 0; }
.template-drag-handle {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  cursor: grab;
  border-radius: var(--r-xs);
  padding: 0;
  flex-shrink: 0;
}
.template-drag-handle:hover { background: var(--bg-3); }
.template-drag-handle:active { cursor: grabbing; }
.templates-table-wrap :deep(.gt-row--ghost) { opacity: 0.4; }
</style>
