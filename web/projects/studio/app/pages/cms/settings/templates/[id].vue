<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const templateId = computed(() => route.params.id as string)

const metadataQuery = gql`
  query GetTemplateMetadata($id: UUID!) {
    content {
      metadata(id: $id) {
        id name version
        content { type }
      }
    }
  }
`

const setNameGql = gql`
  mutation SetTemplateName($id: UUID!, $name: String!) {
    content { metadata { setMetadataName(id: $id, name: $name) { id name } } }
  }
`

const templateUsageGql = gql`
  query GetTemplateUsage($id: UUID!) {
    content { metadata(id: $id) { id templateUsage { items collections templates total } } }
  }
`

const deleteTemplateGql = gql`
  mutation DeleteTemplate($id: UUID!) {
    content { metadata { delete(metadataId: $id) } }
  }
`

interface TemplateMetadata {
  id: string
  name: string
  version: number
  content: { type: string } | null
}

const { data, status, refresh } = useAsyncQuery<{ content: { metadata: TemplateMetadata | null } }>('template-meta', metadataQuery, { id: templateId })

const metadata = computed(() => data.value?.content?.metadata ?? null)
const isLoading = computed(() => status.value === 'pending')
const contentType = computed(() => metadata.value?.content?.type ?? '')

const templateType = computed(() => {
  const ct = contentType.value
  if (ct.includes('document-template') || ct.includes('v-document')) return 'Document'
  if (ct.includes('data-template') || ct.includes('v-data')) return 'Data'
  if (ct.includes('guide-template') || ct.includes('v-guide')) return 'Guide'
  if (ct.includes('collection-template') || ct.includes('v-collection')) return 'Collection'
  return 'Unknown'
})

interface TemplateEditorRef {
  saving?: boolean
  activeTab?: string
  addAttribute?: () => void
  addContainer?: () => void
  addFilter?: () => void
  addOrdering?: () => void
  save?: () => void
}

const templateRef = ref<TemplateEditorRef | null>(null)
const docTemplateRef = ref<TemplateEditorRef | null>(null)
const guideEditorTab = ref('Guide Template')
const saving = computed(() => templateRef.value?.saving ?? false)
const activeChildTab = computed(() => {
  if (templateType.value === 'Guide' && guideEditorTab.value === 'Document Template') {
    return docTemplateRef.value?.activeTab ?? ''
  }
  return templateRef.value?.activeTab ?? ''
})

const activeRef = computed(() => {
  if (templateType.value === 'Guide' && guideEditorTab.value === 'Document Template') {
    return docTemplateRef.value
  }
  return templateRef.value
})

const addAction = computed(() => {
  const tab = activeChildTab.value
  const r = activeRef.value
  if (!r) return null
  if (tab === 'Attributes' && r.addAttribute) return { label: 'Add Attribute', fn: r.addAttribute }
  if (tab === 'Containers' && r.addContainer) return { label: 'Add Container', fn: r.addContainer }
  if (tab === 'Filters' && r.addFilter) return { label: 'Add Filter', fn: r.addFilter }
  if (tab === 'Ordering' && r.addOrdering) return { label: 'Add Ordering', fn: r.addOrdering }
  return null
})

const overflowItems = computed(() => [
  { id: 'view-metadata', label: 'View Metadata', icon: 'link' },
  { id: 'sep', label: '', separator: true },
  { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
])

interface TemplateUsage { items: number; collections: number; templates: number; total: number }

const deleteModalOpen = ref(false)
const deleting = ref(false)
const checkingUsage = ref(false)
// Set when the template can't be deleted: either content still uses it, or
// the usage check itself failed (usage is then null).
const blockedUsage = ref<{ usage: TemplateUsage | null } | null>(null)

function plural(count: number, one: string, many: string) {
  return `${count} ${count === 1 ? one : many}`
}

const blockedMessage = computed(() => {
  const usage = blockedUsage.value?.usage
  if (!usage) return 'Studio couldn\'t check whether any content uses this template, so it can\'t be deleted right now.'
  const parts: string[] = []
  if (usage.items) parts.push(plural(usage.items, 'content item', 'content items'))
  if (usage.collections) parts.push(plural(usage.collections, 'collection', 'collections'))
  if (usage.templates) parts.push(plural(usage.templates, 'guide template', 'guide templates'))
  return `This template is used by ${parts.join(', ')}. Delete that content or move it to another template first.`
})

async function startDelete() {
  checkingUsage.value = true
  try {
    const result = await gqlQuery<{ content: { metadata: { templateUsage: TemplateUsage | null } | null } }>(templateUsageGql, { id: templateId.value })
    const usage = result.content.metadata?.templateUsage ?? null
    if (!usage) blockedUsage.value = { usage: null }
    else if (usage.total > 0) blockedUsage.value = { usage }
    else deleteModalOpen.value = true
  } catch {
    blockedUsage.value = { usage: null }
  } finally {
    checkingUsage.value = false
  }
}

function onOverflowSelect(id: string) {
  if (id === 'view-metadata' && metadata.value) {
    router.push(`/cms/metadata/${metadata.value.id}`)
  } else if (id === 'delete' && !checkingUsage.value) {
    startDelete()
  }
}

async function onConfirmDelete() {
  deleting.value = true
  try {
    await gqlMutation(deleteTemplateGql, { id: templateId.value })
    toast.success('Template deleted')
    deleteModalOpen.value = false
    router.push('/cms/settings/templates')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to delete template')
  } finally {
    deleting.value = false
  }
}

const editingName = ref(false)
const nameValue = ref('')

watch(metadata, (m) => {
  if (m && !editingName.value) nameValue.value = m.name
}, { immediate: true })

function startEditName() {
  nameValue.value = metadata.value?.name ?? ''
  editingName.value = true
  nextTick(() => {
    const input = document.querySelector('.name-input input') as HTMLInputElement | null
    input?.focus()
    input?.select()
  })
}

async function saveName() {
  if (!metadata.value || nameValue.value === metadata.value.name) {
    editingName.value = false
    return
  }
  try {
    await gqlMutation(setNameGql, { id: templateId.value, name: nameValue.value })
    toast.success('Name updated')
    editingName.value = false
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update name')
  }
}

function cancelEditName() {
  nameValue.value = metadata.value?.name ?? ''
  editingName.value = false
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Settings', 'Templates', metadata?.name ?? '…')"
        :title="metadata?.name ?? 'Loading…'"
        :subtitle="metadata ? `${templateType} template · v${metadata.version}` : ''"
      >
        <template #title>
          <div class="name-row">
            <template v-if="editingName">
              <TextInput
                v-model="nameValue"
                class="name-input"
                placeholder="Template name"
                @keydown.enter="saveName"
                @keydown.escape="cancelEditName"
                @blur="saveName"
              />
            </template>
            <template v-else>
              <span class="name-text" @click="startEditName">
                {{ metadata?.name ?? 'Loading…' }}
              </span>
              <button v-if="metadata" class="name-edit-btn" @click="startEditName">
                <Icon name="pencil" :size="12" color="var(--fg-3)" />
              </button>
            </template>
          </div>
        </template>
        <template #actions>
          <Button
            v-if="addAction"
            size="sm"
            icon="plus"
            :accent="accent"
            @click="addAction.fn"
          >
            {{ addAction.label }}
          </Button>
          <Button
            v-if="metadata"
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="saving"
            @click="activeRef?.save?.()"
          >
            {{ saving ? 'Saving…' : 'Save' }}
          </Button>
          <OverflowMenu
            v-if="metadata"
            :items="overflowItems"
            @select="onOverflowSelect"
          >
            <template #default="{ toggle }">
              <Button size="sm" icon="more" @click="toggle" />
            </template>
          </OverflowMenu>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !metadata" class="loading-state">Loading…</div>

    <template v-else-if="metadata">
      <TemplatesDocument
        v-if="templateType === 'Document'"
        ref="templateRef"
        :metadata-id="metadata.id"
        :metadata-version="metadata.version"
      />
      <TemplatesCollection
        v-else-if="templateType === 'Collection'"
        ref="templateRef"
        :metadata-id="metadata.id"
        :metadata-version="metadata.version"
      />
      <div v-else-if="templateType === 'Guide'" class="guide-tabs">
        <Tabs v-model="guideEditorTab" :tabs="['Guide Template', 'Document Template']" />
        <TemplatesGuide
          v-show="guideEditorTab === 'Guide Template'"
          ref="templateRef"
          :metadata-id="metadata.id"
          :metadata-version="metadata.version"
        />
        <TemplatesDocument
          v-show="guideEditorTab === 'Document Template'"
          ref="docTemplateRef"
          :metadata-id="metadata.id"
          :metadata-version="metadata.version"
        />
      </div>
      <TemplatesData
        v-else-if="templateType === 'Data'"
        ref="templateRef"
        :metadata-id="metadata.id"
        :metadata-version="metadata.version"
      />
      <div v-else class="loading-state">Unknown template type: {{ contentType }}</div>
    </template>

    <ConfirmModal
      v-if="deleteModalOpen"
      :title="`Delete '${metadata?.name}'?`"
      subtitle="No content uses this template. Deleting it is permanent and can't be undone."
      :loading="deleting"
      @close="deleteModalOpen = false"
      @confirm="onConfirmDelete"
    />

    <Modal
      v-if="blockedUsage"
      :title="`Can't delete '${metadata?.name}'`"
      icon="trash"
      :accent="accent"
      @close="blockedUsage = null">
      <p class="modal-text">{{ blockedMessage }}</p>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="blockedUsage = null">Close</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.name-text {
  cursor: pointer;
  transition: color 0.15s;
}

.name-text:hover {
  color: var(--brand-2);
}

.name-edit-btn {
  width: 24px;
  height: 24px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0.5;
  transition: opacity 0.15s, background 0.15s;
}

.name-edit-btn:hover {
  opacity: 1;
  background: var(--bg-3);
}

.name-input {
  min-width: 300px;
}

.loading-state {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.modal-text { color: var(--fg-2); margin: 0; font-size: 13px; }

.guide-tabs {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
