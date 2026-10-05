<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import type { Metadata, Profile, MetadataInput, MetadataParentCollectionInput } from '~/types/graphql'
import { applyAttributes } from '~/utils/editor/attributes'
import type { OverflowMenuItem } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const route = useRoute()
const router = useRouter()
const toast = useToast()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()

const metadataId = computed(() => route.params.id as string)

const dataGql = gql`
  query GetDataEditor($id: UUID!) {
    profiles {
      current {
        id
        name
      }
    }
    content {
      metadata(id: $id) {
        __typename
        id
        version
        parentId
        name
        slug
        type
        languageTag
        attributes
        created
        modified
        public
        publicContent
        publicSupplementary
        searchable
        locked
        ready
        labels
        content { type length }
        data {
          template {
            id
            version
            dataTemplate {
              attributes {
                key name description type configuration ui location list supplementaryKey
                workflows { autoRun }
                tools { id name description query resultPath }
              }
              defaultAttributes
            }
          }
        }
        supplementary {
          id name key created modified uploaded
          content { type length urls { download { url } } }
        }
        workflow {
          state stateValid pending running
          activeJobs { jobName displayName jobId status created complete success delayedUntil }
        }
        parentCollections(offset: 0, limit: 1000) {
          id name attributes
          workflow { state pending }
        }
        relationships {
          __typename
          relationship attributes
          metadata { id name languageTag type attributes content { type } }
        }
        variants { id name languageTag }
        categories { id name }
        traits { id name }
      }
      states {
        all { id name description }
      }
    }
  }
`

const { data, status, refresh } = await useAsyncQuery<{
  profiles: { current: Profile }
  content: {
    metadata: Metadata | null
    states: { all: Array<{ id: string; name: string; description: string }> }
  }
}>('data-editor', dataGql, { id: metadataId })

const metadata = computed(() => data.value?.content?.metadata ?? null)
const profile = computed(() => data.value?.profiles?.current ?? { id: '', name: '' } as Profile)
const isLoading = computed(() => status.value === 'pending')

const item = computed(() => metadata.value)
const collab = useCollaborationAndAttributes(item, profile)
const ydoc = collab.ydoc
const ydocReady = collab.ready
const metadataAttributes = collab.attributes
const rawAttributes = collab.rawAttributes
const uploader = useUploader()

// ── Editable fields ────────────────────────────────────────────────────────
const nameField = ref('')
const languageTagField = ref('')
const labelsField = ref('')

function bind() {
  const m = metadata.value
  if (!m) return
  nameField.value = m.name || ''
  languageTagField.value = m.languageTag || ''
  labelsField.value = (m as any).labels?.join(', ') || ''
  rawAttributes.value = m.attributes || {}
}

watch(metadata, () => bind(), { immediate: true })

const workflowState = computed(() => {
  const wf = metadata.value?.workflow
  return wf?.pending ?? wf?.state ?? 'draft'
})

const canPublish = computed(() => metadata.value?.ready && workflowState.value !== 'published')
const canUnpublish = computed(() => workflowState.value === 'published')

// ── Mutations ──────────────────────────────────────────────────────────────
const saveGql = gql`
  mutation SaveDataEditor($id: UUID!, $input: MetadataInput!) {
    content { metadata { edit(id: $id, metadata: $input) { id } } }
  }
`

const setReadyGql = gql`
  mutation SetDataReady($id: UUID!) {
    content { metadata { setMetadataReady(id: $id) } }
  }
`

const beginTransitionGql = gql`
  mutation BeginDataTransition($id: UUID!, $version: Int!, $state: String!, $status: String!) {
    content { transitions { beginTransition(request: { metadataId: $id, version: $version, stateId: $state, status: $status }) } }
  }
`

const deleteGql = gql`
  mutation DeleteDataEditor($id: UUID!) {
    content { metadata { delete(metadataId: $id) } }
  }
`

// ── Actions ────────────────────────────────────────────────────────────────
const saving = ref(false)
const deleting = ref(false)
const deleteModalOpen = ref(false)

async function onSave() {
  const m = metadata.value
  if (!m || saving.value) return
  saving.value = true
  try {
    const parentCollections: MetadataParentCollectionInput[] = []
    const metadataRelationships: { id1: string; id2: string; relationship: string; attributes: any }[] = []
    applyAttributes(m, metadataAttributes, rawAttributes, parentCollections, metadataRelationships)

    const input: MetadataInput = {
      parentId: (m as any).parentId,
      name: nameField.value,
      attributes: rawAttributes.value,
      languageTag: languageTagField.value,
      contentLength: m.content?.length || 0,
      categoryIds: m.categories?.map(c => c.id) || [],
      contentType: m.content?.type || '',
      locked: m.locked || false,
      labels: labelsField.value.split(',').map(l => l.trim()).filter(l => l.length > 0),
    } as MetadataInput
    await gqlMutation(saveGql, { id: m.id, input })
    toast.success('Data saved')
    await refresh()
    collab.refreshState()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to save')
  } finally {
    saving.value = false
  }
}

async function onSetReady() {
  try {
    await gqlMutation(setReadyGql, { id: metadataId.value })
    toast.success('Marked ready')
    await refresh()
  } catch { toast.error('Failed to mark ready') }
}

async function onPublish() {
  try {
    await gqlMutation(beginTransitionGql, { id: metadataId.value, version: metadata.value!.version, state: 'published', status: 'complete' })
    toast.success('Published')
    await refresh()
  } catch { toast.error('Failed to publish') }
}

async function onUnpublish() {
  try {
    await gqlMutation(beginTransitionGql, { id: metadataId.value, version: metadata.value!.version, state: 'draft', status: 'Unpublished' })
    toast.success('Unpublished')
    await refresh()
  } catch { toast.error('Failed to unpublish') }
}

async function onConfirmDelete() {
  deleting.value = true
  try {
    await gqlMutation(deleteGql, { id: metadataId.value })
    toast.success('Data deleted')
    deleteModalOpen.value = false
    router.push('/cms/data')
  } catch { toast.error('Failed to delete') }
  finally { deleting.value = false }
}

const overflowItems = computed<OverflowMenuItem[]>(() => {
  const items: OverflowMenuItem[] = [
    { id: 'metadata', label: 'View metadata details', icon: 'inspect' },
    { id: 'copy', label: 'Copy metadata ID', icon: 'copy' },
  ]
  if (canUnpublish.value) {
    items.push({ id: 'unpublish', label: 'Unpublish', icon: 'archive' })
  }
  items.push({ id: 'sep', label: '', separator: true })
  items.push({ id: 'delete', label: 'Delete', icon: 'trash', danger: true })
  return items
})

function onOverflowAction(id: string) {
  if (id === 'metadata') router.push(`/cms/metadata/${metadataId.value}`)
  else if (id === 'copy') { navigator.clipboard.writeText(metadataId.value); toast.success('Copied') }
  else if (id === 'unpublish') onUnpublish()
  else if (id === 'delete') deleteModalOpen.value = true
}

const otherVariants = computed(() =>
  ((metadata.value as any)?.variants ?? []).filter((v: any) => v.id !== metadata.value?.id),
)
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Data', metadata?.name ?? '…')"
        :title="metadata?.name ?? 'Loading…'"
        :subtitle="metadata ? `${metadata.content?.type} · ${metadata.languageTag}` : ''"
      >
        <template #actions>
          <Button
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="saving"
            @click="onSave">
            {{ saving ? 'Saving…' : 'Save' }}
          </Button>
          <Button
            v-if="metadata && !metadata.ready"
            size="sm"
            icon="check"
            :accent="accent"
            @click="onSetReady">
            Mark Ready
          </Button>
          <Button
            v-if="canPublish"
            size="sm"
            icon="globe"
            primary
            :accent="accent"
            @click="onPublish">
            Publish
          </Button>
          <OverflowMenu :items="overflowItems" @select="onOverflowAction">
            <template #default="{ toggle }">
              <Button size="sm" icon="more" @click="toggle" />
            </template>
          </OverflowMenu>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !metadata" class="loading-state">Loading…</div>

    <div v-else-if="metadata" class="mv-body">
      <!-- Main editing panel -->
      <div class="panel panel--main">
        <div class="panel-scroll">
          <div class="section-head">Details</div>
          <div class="form-fields">
            <div class="field">
              <label class="field-label">Name</label>
              <TextInput v-model="nameField" placeholder="Data item name" icon="database" />
            </div>
            <div class="field-row">
              <div class="field">
                <label class="field-label">Language</label>
                <TextInput v-model="languageTagField" placeholder="en" />
              </div>
              <div class="field">
                <label class="field-label">Content Type</label>
                <TextInput :model-value="metadata.content?.type ?? ''" disabled />
              </div>
            </div>
            <div class="field">
              <label class="field-label">Labels <span class="field-hint">(comma-separated)</span></label>
              <TextInput v-model="labelsField" placeholder="tag1, tag2" />
            </div>
          </div>

          <div class="hr" />

          <div class="section-head">Attributes</div>
          <MetadataEditor
            v-if="ydoc && metadataAttributes"
            v-model:raw-attributes="rawAttributes"
            :metadata="metadata"
            :ydoc="ydoc"
            :editable="ydocReady"
            :uploader="uploader"
            :attributes="metadataAttributes"
          />
          <div v-else class="raw-fallback">
            <JsonEditorVue
              v-model="rawAttributes"
              :main-menu-bar="false"
              :navigation-bar="false"
              class="json-editor" />
          </div>
        </div>
      </div>

      <!-- Sidebar -->
      <div v-if="metadata.categories?.length || (metadata as any).traits?.length || otherVariants.length" class="panel panel--side">
        <SectionCard v-if="metadata.categories?.length" title="Categories">
          <div class="sc-tags">
            <Badge v-for="cat in metadata.categories" :key="cat.id" color="#5ec5ff">{{ cat.name }}</Badge>
          </div>
        </SectionCard>

        <SectionCard v-if="(metadata as any).traits?.length" title="Traits">
          <div class="sc-tags">
            <Badge v-for="trait in (metadata as any).traits" :key="trait.id" color="#a78bff">{{ trait.name }}</Badge>
          </div>
        </SectionCard>

        <SectionCard v-if="otherVariants.length" title="Variants">
          <div class="sc-variants">
            <div
              v-for="v in otherVariants"
              :key="v.id"
              class="variant-row"
              @click="router.push(`/cms/data/${v.id}`)">
              <Icon name="languages" :size="13" color="var(--fg-3)" />
              <span class="variant-name">{{ v.name }}</span>
              <Badge color="var(--fg-4)">{{ v.languageTag }}</Badge>
            </div>
          </div>
        </SectionCard>
      </div>
    </div>
  </PageShell>

  <ConfirmModal
    v-if="deleteModalOpen"
    :title="`Delete '${nameField}'?`"
    subtitle="This will soft-delete the data. It can be restored later."
    :loading="deleting"
    @close="deleteModalOpen = false"
    @confirm="onConfirmDelete"
  />
</template>

<style scoped>
.mv-body { display: flex; padding: 0; gap: 14px; }

.panel { background: var(--bg-1); border: 1px solid var(--line); border-radius: var(--r-md); }
.panel--main { flex: 1; min-width: 0; }
.panel--side { width: 340px; flex: 0 0 340px; background: none; border: none; border-radius: 0; gap: 12px; }
.panel-scroll { padding: 22px 26px; }

.section-head { font-size: 10.5px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.08em; color: var(--fg-3); margin-bottom: 14px; }
.hr { height: 1px; background: color-mix(in oklch, var(--line) 42%, transparent); margin: 18px 0; }

.form-fields { display: flex; flex-direction: column; gap: 14px; }
.field { display: flex; flex-direction: column; gap: 5px; }
.field-label { font-size: 12px; font-weight: 550; color: var(--fg-2); }
.field-hint { font-weight: 400; color: var(--fg-4); }
.field-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }

.raw-fallback { display: flex; flex-direction: column; gap: 8px; }
.json-editor { border-radius: var(--r-sm); min-height: 200px; }

.sc-tags { padding: 10px 16px 14px; display: flex; flex-wrap: wrap; gap: 5px; }
.sc-variants { padding: 6px 10px 10px; }

.variant-row { display: flex; align-items: center; gap: 8px; padding: 6px 6px; border-radius: var(--r-xs); cursor: pointer; font-size: 12.5px; color: var(--fg-2); transition: background 0.1s; }
.variant-row:hover { background: var(--bg-3); }
.variant-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.loading-state { flex: 1; display: flex; align-items: center; justify-content: center; color: var(--fg-3); font-size: 13px; }
</style>
