<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import gql from 'graphql-tag'
import type { Metadata, Profile } from '~/types/graphql'
import { applyAttributes } from '~/utils/editor/attributes'
import { executeTool, type TemplateAttributeTool } from '~/utils/editor/tool'
import type { AttributeState } from '~/utils/editor/attribute'
import { captureYDocRevision, getYDocSlugs, markYDocSaved } from '~/utils/editor/ydoc'
import DocumentEditor from "~/components/document/DocumentEditor.vue";
import type { OverflowMenuItem } from '@bosca/ui'
import type { YMapEvent } from 'yjs'

const { accent } = useCurrentSubsystem()
const route = useRoute()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation, useSubscription } = useGraphQL()
const { isAdmin, hasGroup, load: loadPersonas } = usePersonas()
const { commentsEnabled, load: loadFeatures } = useServerFeatures()
const canModerate = computed(() => commentsEnabled.value && (isAdmin.value || hasGroup('sa')))

const metadataGql = gql`
  query GetMetadata($id: UUID!) {
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
        labels
        ready
        uploaded
        content {
          type
        }
        document {
          content
          title
          template {
            id
            version
            documentTemplate {
              schema
              attributes {
                key
                name
                description
                type
                ui
                location
                list
                configuration
                supplementaryKey
                tools {
                  id
                  name
                  description
                  query
                  resultPath
                }
              }
              containers {
                id
                name
                description
                type
                filters
                tools {
                  id
                  name
                  description
                  query
                  resultPath
                }
              }
            }
          }
        }
        workflow {
          state
          stateValid
          pending
          running
          activeJobs {
            jobName
            displayName
            jobId
            status
            created
            complete
            success
            delayedUntil
          }
        }
        parentCollections(offset: 0, limit: 1000) {
          id
          name
          attributes
          itemAttributes
        }
        parentId
        variants {
          id
          languageTag
          name
        }
        relationships {
          __typename
          metadata {
            id
            name
            content {
              type
            }
          }
          relationship
          attributes
        }
        content {
          type
          urls {
            upload {
              url
              headers {
                name
                value
              }
            }
          }
        }
      }
    }
  }
`

const { data, status, refresh } = await useAsyncQuery<{
  profiles: { current: Profile }
  content: { metadata: Metadata }
}>('editor-metadata', metadataGql, { id: route.params.id })

const metadataSubscriptionGql = gql`
  subscription MetadataChanges {
    metadata {
      id
      type
      version
    }
  }
`

let refreshTimer: ReturnType<typeof setTimeout> | undefined
function refreshDebounced() {
  clearTimeout(refreshTimer)
  refreshTimer = setTimeout(() => refresh(), 500)
}

useSubscription(metadataSubscriptionGql, {}, (event: any) => {
  const changedId = event?.metadata?.id
  if (changedId === route.params.id) {
    refreshDebounced()
  }
})

const metadata = computed<Metadata | null>(() => data.value?.content?.metadata ?? null)
const profile = computed<Profile>(() => data.value?.profiles?.current ?? { id: '', name: 'Unknown' } as Profile)
const initialLoadDone = ref(status.value === 'success')
watch(status, (s) => { if (s === 'success') initialLoadDone.value = true })
const isLoading = computed(() => !initialLoadDone.value && status.value === 'pending')
const editorTitle = ref('')
const documentName = computed(() => editorTitle.value || metadata.value?.name?.trim() || '')

const item = computed(() => metadata.value)
const collab = useCollaborationAndAttributes(
  item,
  profile,
)
const ydoc = collab.ydoc
const ydocReady = collab.ready
const uploader = useUploader()

const saving = ref(false)
const slugSaving = ref(false)
const publishing = ref(false)
const saveStatus = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
const saveError = ref('')
const hasUnsavedChanges = ref(false)
const hasManualSlug = ref(false)
const pendingSlugTitle = ref<string>()
const editorRef = ref<InstanceType<typeof DocumentEditor> | null>(null)

watch(ydoc, (doc, _, onCleanup) => {
  if (!doc) return
  const changesText = doc.getText('changes')
  const slugs = getYDocSlugs(doc)
  const updateUnsavedChanges = () => {
    hasUnsavedChanges.value = changesText.getAttribute('changes') === 'true'
  }
  const updateSlugs = (event?: YMapEvent<string>) => {
    hasManualSlug.value = slugs.get('manual-slug') === 'true'
    pendingSlugTitle.value = slugs.get('pending-title')
    if (event && !event.transaction.local && event.keysChanged.has('saved-slug')) {
      void refresh()
    }
  }
  updateUnsavedChanges()
  updateSlugs()
  changesText.observe(updateUnsavedChanges)
  slugs.observe(updateSlugs)
  onCleanup(() => {
    changesText.unobserve(updateUnsavedChanges)
    slugs.unobserve(updateSlugs)
  })
}, { immediate: true })

const currentSlug = computed(() => {
  const m = metadata.value
  if (!m) return ''
  const slug = m.slug ?? ''
  const title = editorTitle.value
  if (!title || hasManualSlug.value || m.workflow?.state === 'published') return slug

  const savedTitle = m.document?.title?.trim() || m.name.trim()
  const generatedSlug = title
    .toLowerCase()
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/[\s_]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
  const hasPlaceholderSlug = /^new-document(?:-?\d+)?$/.test(slug) || slug === `new-document${m.id}`
  const shouldReplacePlaceholder = hasPlaceholderSlug && generatedSlug !== 'new-document'
  if (title !== savedTitle || title === pendingSlugTitle.value || shouldReplacePlaceholder) {
    return generatedSlug || slug
  }
  return slug
})
const needsSave = computed(() => hasUnsavedChanges.value || currentSlug.value !== (metadata.value?.slug ?? ''))

const saveDocumentGql = gql`
  mutation SaveDocument($id: UUID!, $version: Int!, $document: DocumentInput!) {
    content {
      metadata {
        setMetadataDocument(id: $id, version: $version, document: $document)
      }
    }
  }
`

const saveAttributesGql = gql`
  mutation SaveAttributes($id: UUID!, $attributes: JSON!) {
    content {
      metadata {
        setMetadataAttributes(id: $id, attributes: $attributes)
      }
    }
  }
`

const setNameGql = gql`
  mutation SetMetadataName($id: UUID!, $name: String!) {
    content {
      metadata {
        setMetadataName(id: $id, name: $name) {
          id
          name
        }
      }
    }
  }
`

const setCollectionsGql = gql`
  mutation SetEditorParentCollections($id: UUID!, $collections: [MetadataParentCollectionInput!]!) {
    content { metadata { setMetadataParentCollections(id: $id, collections: $collections) } }
  }
`

const setRelationshipsGql = gql`
  mutation SetEditorRelationships($id: UUID!, $relationships: [MetadataRelationshipInput!]!) {
    content { metadata { setMetadataRelationships(id: $id, relationships: $relationships) } }
  }
`

async function onSave() {
  const m = metadata.value
  if (!m || saving.value || slugSaving.value) return false
  const doc = ydoc.value
  const slugs = doc ? getYDocSlugs(doc) : null
  const saveRevision = doc ? captureYDocRevision(doc) : null
  saving.value = true
  saveStatus.value = 'saving'
  saveError.value = ''
  try {
    const document = editorRef.value?.getDocument()
    const name = documentName.value
    const title = editorTitle.value
    const slug = currentSlug.value
    const shouldSaveSlug = !!document && !!slug && slug !== m.slug
    if (shouldSaveSlug) {
      // Keep the generated slug pending if a write fails after the title has been saved.
      slugs?.set('pending-title', title)
    }

    if (document) {
      await gqlMutation(saveDocumentGql, {
        id: m.id,
        version: m.version,
        document,
      })
    }

    const parentCollections: any[] = []
    const relationships: any[] = []
    applyAttributes(
      m,
      collab.attributes,
      collab.rawAttributes,
      parentCollections,
      relationships,
    )

    await gqlMutation(saveAttributesGql, {
      id: m.id,
      attributes: collab.rawAttributes.value,
    })
    await gqlMutation(setCollectionsGql, {
      id: m.id,
      collections: parentCollections,
    })
    await gqlMutation(setRelationshipsGql, {
      id: m.id,
      relationships: relationships,
    })

    if (name && name !== m.name) {
      await gqlMutation(setNameGql, { id: m.id, name })
    }

    if (shouldSaveSlug && slugs?.get('manual-slug') !== 'true') {
      const savedSlug = await persistSlug(m.id, slug)
      slugs?.set('saved-slug', savedSlug)
    }
    if (slugs?.get('pending-title') === title) {
      slugs.delete('pending-title')
    }

    if (doc && saveRevision) {
      markYDocSaved(doc, saveRevision)
    }

    // Re-read relationships/collections so connection indicators reflect the save
    await refresh()
    collab.reloadRelationships()
    collab.reloadParentCollections()

    saveStatus.value = 'saved'
    setTimeout(() => { saveStatus.value = 'idle' }, 3000)
    return true
  } catch (e) {
    console.error('Save failed', e)
    saveError.value = e instanceof Error ? e.message : ''
    saveStatus.value = 'error'
    return false
  } finally {
    saving.value = false
  }
}

function onTitleUpdate(title: string) {
  editorTitle.value = title.trim()
}

function onRunTool(attribute: AttributeState, tool: TemplateAttributeTool) {
  if (!metadata.value) return
  // The attribute's own loading ref drives the tool button's spinner state.
  executeTool(metadata.value, tool, attribute, attribute.loading)
}

const saveStatusLabel = computed(() => {
  switch (saveStatus.value) {
    case 'saving': return 'Saving…'
    case 'saved': return 'Saved'
    case 'error': return saveError.value ? `Save failed — ${saveError.value}` : 'Save failed'
    default: return needsSave.value ? 'Draft' : ''
  }
})

const publishModalOpen = ref(false)
const deleteModalOpen = ref(false)
const slugModalOpen = ref(false)
const slugValue = ref('')
const slugError = ref('')
const slugInputRef = ref<{ available: boolean | null } | null>(null)
const deleting = ref(false)
const deleteError = ref('')

watch(currentSlug, (slug, previousSlug) => {
  if (slugModalOpen.value && !slugSaving.value && slugValue.value === previousSlug) {
    slugValue.value = slug
  }
})

const slugAvailableGql = gql`
  query CheckSlug($slug: String!) {
    content {
      slugAvailable(slug: $slug)
    }
  }
`

async function checkSlugAvailable(slugVal: string): Promise<boolean> {
  if (slugVal === metadata.value?.slug) return true
  const result = await gqlQuery<{
    content: { slugAvailable: boolean }
  }>(slugAvailableGql, { slug: slugVal })
  return result.content.slugAvailable
}

function openSlugModal() {
  slugValue.value = currentSlug.value
  slugError.value = ''
  slugModalOpen.value = true
}

const canSaveSlug = computed(() => {
  return slugValue.value
    && !slugSaving.value
    && !saving.value
    && !publishing.value
    && slugInputRef.value?.available !== false
})

async function onSaveSlug() {
  const m = metadata.value
  const doc = ydoc.value
  const slug = slugValue.value
  if (!m || !canSaveSlug.value) return
  if (slug === m.slug && currentSlug.value === m.slug) {
    slugModalOpen.value = false
    return
  }
  slugSaving.value = true
  slugError.value = ''
  try {
    const savedSlug = slug === m.slug ? slug : await persistSlug(m.id, slug)
    doc?.transact(() => {
      const slugs = getYDocSlugs(doc)
      slugs.set('manual-slug', 'true')
      slugs.set('saved-slug', savedSlug)
      slugs.delete('pending-title')
    })
    slugModalOpen.value = false
    await refresh()
  } catch (e: any) {
    slugError.value = e?.message || 'Failed to update slug'
  } finally {
    slugSaving.value = false
  }
}

async function onConfirmDelete() {
  if (!metadata.value || deleting.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await gqlMutation(deleteGql, { metadataId: metadata.value.id })
    navigateTo('/cms/documents')
  } catch (e: any) {
    console.error('Delete failed', e)
    deleteError.value = e?.message || 'Delete failed'
    deleting.value = false
  }
}

const setSlugGql = gql`
  mutation SetMetadataSlug($id: UUID!, $slug: String!) {
    content { metadata { setMetadataSlug(id: $id, slug: $slug) } }
  }
`

async function persistSlug(id: Metadata['id'], slug: string) {
  const result = await gqlMutation<{ content: { metadata: { setMetadataSlug: string } } }>(setSlugGql, { id, slug })
  const savedSlug = result.content.metadata.setMetadataSlug
  const current = data.value
  if (current?.content?.metadata?.id === id) {
    data.value = {
      ...current,
      content: { metadata: { ...current.content.metadata, slug: savedSlug } },
    }
  }
  return savedSlug
}

const setLockedGql = gql`
  mutation SetLocked($id: UUID!, $locked: Boolean!) {
    content { metadata { setLocked(id: $id, locked: $locked) { id locked } } }
  }
`

const deleteGql = gql`
  mutation DeleteMetadata($metadataId: UUID!) {
    content { metadata { delete(metadataId: $metadataId) } }
  }
`

const transitionGql = gql`
  mutation BeginTransition($id: UUID!, $version: Int!, $state: String!, $status: String!) {
    content {
      transitions {
        beginTransition(request: {
          metadataId: $id
          version: $version
          stateId: $state
          status: $status
        })
      }
    }
  }
`

const cancelTransitionGql = gql`
  mutation CancelEditorTransition($id: UUID!, $version: Int!) {
    content { transitions { cancelTransition(metadataId: $id, metadataVersion: $version) } }
  }
`

const hasPendingTransition = computed(() => !!metadata.value?.workflow?.pending)

const preview = usePreviewableUrl()
onMounted(() => { preview.load(); loadPersonas(); loadFeatures() })

function onPreview() {
  const m = metadata.value
  if (!m) return
  preview.openPreview({ id: m.id, slug: m.slug, languageTag: m.languageTag })
}

// ── Language variants ────────────────────────────────────────────────────────
interface MetadataVariant { id: string; languageTag: string; name: string }

const variants = computed<MetadataVariant[]>(() =>
  ((metadata.value as Metadata & { variants?: MetadataVariant[] })?.variants) ?? [])

async function onCancelTransition() {
  const m = metadata.value
  if (!m) return
  try {
    await gqlMutation(cancelTransitionGql, { id: m.id, version: m.version })
  } catch (e: any) {
    console.error('Cancel transition failed', e)
  }
}

const setReadyGql = gql`
  mutation SetMetadataReady($id: UUID!) {
    content { metadata { setMetadataReady(id: $id) } }
  }
`

const setNotReadyGql = gql`
  mutation SetMetadataNotReady($id: UUID!) {
    content { metadata { setMetadataNotReady(id: $id) } }
  }
`

const readyLoading = ref(false)

async function onMarkNotReady() {
  if (!metadata.value || readyLoading.value) return
  readyLoading.value = true
  try {
    await gqlMutation(setNotReadyGql, { id: metadata.value.id })
  } catch (e: any) {
    console.error('Mark not ready failed', e)
  } finally {
    readyLoading.value = false
  }
}

// Discards local (unsaved) changes by restoring the last-saved document and
// attribute state. DocumentEditor owns the editor instance, so it listens for
// this event and performs the actual restore.
function onResetDocument() {
  window.dispatchEvent(new Event('reset-document'))
}

const editorStateOpen = ref(false)

async function onMarkReady() {
  if (!metadata.value || readyLoading.value || saving.value || slugSaving.value) return
  readyLoading.value = true
  try {
    if (needsSave.value && (!await onSave() || needsSave.value)) return
    const m = metadata.value
    if (m) await gqlMutation(setReadyGql, { id: m.id })
  } catch (e: any) {
    console.error('Mark ready failed', e)
  } finally {
    readyLoading.value = false
  }
}

const canPublish = computed(() => {
  const w = metadata.value?.workflow
  if (!w) return false
  return w.state === 'draft' && !w.pending
})

const canUnpublish = computed(() => {
  const w = metadata.value?.workflow
  if (!w) return false
  return w.state === 'published' && !w.pending
})

async function onPublish() {
  if (!metadata.value || publishing.value || saving.value || slugSaving.value) return
  publishing.value = true
  try {
    if (needsSave.value && (!await onSave() || needsSave.value)) return
    const m = metadata.value
    if (!m) return
    await gqlMutation(transitionGql, {
      id: m.id,
      version: m.version,
      state: 'published',
      status: 'Admin Published',
    })
  } catch (e: any) {
    console.error('Publish failed', e)
  } finally {
    publishing.value = false
  }
}

async function onUnpublish() {
  if (!metadata.value || publishing.value) return
  publishing.value = true
  try {
    await gqlMutation(transitionGql, {
      id: metadata.value.id,
      version: metadata.value.version,
      state: 'draft',
      status: 'Admin Unpublished',
    })
  } catch (e: any) {
    console.error('Unpublish failed', e)
  } finally {
    publishing.value = false
  }
}

const overflowItems = computed<OverflowMenuItem[]>(() => {
  const m = metadata.value
  if (!m) return []
  const locked = m.locked
  const items = []
  if (canUnpublish.value) {
    items.push({ id: 'unpublish', label: publishing.value ? 'Unpublishing…' : 'Unpublish', icon: 'globe', disabled: publishing.value })
  }
  if (hasPendingTransition.value) {
    items.push({ id: 'cancel-transition', label: 'Cancel Transition', icon: 'x' })
  }
  if (canModerate.value) {
    items.push({ id: 'comments', label: 'Comments', icon: 'message-square' })
  }
  items.push(
    { id: 'view-metadata', label: 'View Metadata', icon: 'database' },
    { id: 'slug', label: 'Edit Slug…', icon: 'link' },
    { id: 'separator-1', label: '', separator: true },
    { id: 'copy-id', label: 'Copy ID', icon: 'copy' },
    { id: 'view-json', label: 'View as JSON', icon: 'code' },
    { id: 'editor-state', label: 'View Editor State', icon: 'code' },
    { id: 'separator-2', label: '', separator: true },
    { id: 'reset', label: 'Reset Unsaved Changes', icon: 'refresh', disabled: !needsSave.value },
    { id: 'lock', label: locked ? 'Unlock' : 'Lock', icon: locked ? 'unlock' : 'lock' },
  )
  if (m.ready) {
    items.push({ id: 'not-ready', label: 'Mark Not Ready', icon: 'x', disabled: readyLoading.value })
  }
  items.push(
    { id: 'separator-3', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true }
  )
  return items
})

async function onOverflowAction(id: string) {
  const m = metadata.value
  if (!m) return

  switch (id) {
    case 'comments':
      navigateTo(`/cms/comments/${m.id}`)
      break
    case 'view-metadata':
      navigateTo(`/cms/metadata/${m.id}`)
      break
    case 'publish':
      publishModalOpen.value = true
      break
    case 'unpublish':
      onUnpublish()
      break
    case 'cancel-transition':
      onCancelTransition()
      break
    case 'not-ready':
      onMarkNotReady()
      break
    case 'reset':
      onResetDocument()
      break
    case 'editor-state':
      editorStateOpen.value = true
      break
    case 'slug':
      openSlugModal()
      break
    case 'copy-id':
      await navigator.clipboard.writeText(m.id)
      break
    case 'view-json':
      window.open(`/graphql?query={content{metadata(id:"${m.id}"){id name attributes}}}`, '_blank')
      break
    case 'lock':
      await gqlMutation(setLockedGql, { id: m.id, locked: !m.locked })
      break
    case 'delete':
      deleteModalOpen.value = true
      break
  }
}
</script>

<template>
  <PageShell class="editor-shell">
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Documents', documentName || (isLoading ? 'Loading…' : 'Untitled'))"
        :title="documentName || (isLoading ? 'Loading…' : 'Untitled')"
      >
        <template #actions>
          <span v-if="saveStatusLabel" class="save-status">
            <span :class="saveStatus === 'saved' ? 'dot ok' : saveStatus === 'error' ? 'dot err' : saveStatus === 'idle' && needsSave ? 'dot warn' : 'dot info'" />
            {{ saveStatusLabel }}
          </span>

          <Button
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="saving || slugSaving || publishing"
            @click="onSave">
            Save
          </Button>

          <Button
            v-if="metadata && !metadata.ready"
            size="sm"
            icon="check"
            :accent="accent"
            :disabled="readyLoading || saving || slugSaving"
            @click="onMarkReady">
            {{ readyLoading ? 'Marking…' : 'Mark Ready' }}
          </Button>

          <Button
            v-if="canPublish"
            size="sm"
            icon="globe"
            primary
            :accent="accent"
            :disabled="publishing || saving || slugSaving"
            @click="onPublish">
            {{ publishing ? 'Publishing…' : 'Publish' }}
          </Button>

          <Button
            v-if="preview.canPreview.value && metadata"
            size="sm"
            icon="eye"
            :accent="accent"
            @click="onPreview">
            Preview
          </Button>

          <MetadataLanguageMenu
            v-if="metadata"
            :metadata-id="metadata.id"
            :parent-id="(metadata as Metadata & { parentId?: string | null }).parentId"
            :metadata-version="metadata.version"
            :language-tag="metadata.languageTag"
            :variants="variants"
            url-prefix="/cms/editor/"
            :accent="accent"
          />

          <OverflowMenu :items="overflowItems" @select="onOverflowAction">
            <template #default="{ toggle }">
              <Button size="sm" icon="more" @click="toggle">
                More
              </Button>
            </template>
          </OverflowMenu>
        </template>
      </PageHeader>
    </template>

    <AttributesPublishModal
      v-if="metadata"
      v-model:open="publishModalOpen"
      :metadata-ids="[metadata.id]"
    />


    <Modal
      v-if="editorStateOpen"
      title="Editor State"
      subtitle="Live Yjs shared types for this document"
      icon="code"
      width="860px"
      @close="editorStateOpen = false"
    >
      <ClientOnly>
        <YDocIntrospection :ydoc="ydoc" />
      </ClientOnly>
    </Modal>

    <Modal
      v-if="deleteModalOpen"
      title="Delete Document"
      subtitle="This action cannot be undone."
      icon="trash"
      accent="#f87171"
      width="420px"
      @close="deleteModalOpen = false"
    >
      <p class="delete-msg">
        Are you sure you want to delete <strong>{{ documentName || 'this document' }}</strong>?
      </p>
      <p v-if="deleteError" class="delete-error">{{ deleteError }}</p>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="deleteModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          accent="#f87171"
          :disabled="deleting"
          @click="onConfirmDelete">
          {{ deleting ? 'Deleting…' : 'Delete' }}
        </Button>
      </template>
    </Modal>

    <Modal
      v-if="slugModalOpen"
      title="Edit Slug"
      subtitle="URL-friendly identifier for this document"
      icon="link"
      width="440px"
      @close="slugModalOpen = false"
    >
      <SlugInput
        ref="slugInputRef"
        v-model="slugValue"
        label="Slug"
        size="sm"
        :on-validate="checkSlugAvailable"
        :debounce="300"
      />
      <p v-if="slugError" class="slug-error">{{ slugError }}</p>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="slugModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :disabled="!canSaveSlug"
          @click="onSaveSlug">
          {{ slugSaving ? 'Saving…' : 'Save' }}
        </Button>
      </template>
    </Modal>

    <!-- Loading state -->
    <div v-if="isLoading" class="loading-state">
      Loading…
    </div>

    <!-- Editor body -->
    <div v-else-if="metadata" class="editor-body">
      <!-- Main panel -->
      <div class="main-panel">
        <div class="main-panel-scroll">
          <ClientOnly>
            <DocumentEditor
              v-if="metadata && ydoc && collab.attributes"
              ref="editorRef"
              :metadata="metadata"
              :profile="profile"
              :attributes="collab.attributes"
              :ydoc="ydoc"
              :editable="ydocReady"
              :on-document="() => {}"
              :on-title-update="onTitleUpdate"
            />
            <div v-else class="loading-state">
              Loading…
            </div>
          </ClientOnly>
        </div>
      </div>

      <!-- Attributes panel -->
      <div class="attr-panel">
        <AttributesSidebar
          v-if="metadata && ydoc && collab.attributes"
          :content="metadata"
          :slug="currentSlug"
          :state="metadata.workflow"
          :ydoc="ydoc"
          :attributes="collab.attributes"
          :uploader="uploader"
          :editable="ydocReady"
          :tools-enabled="true"
          :on-run-tool="onRunTool"
          @edit-slug="openSlugModal"
        />
      </div>
    </div>
  </PageShell>
</template>

<style scoped>
.editor-shell :deep(.page-content) {
  padding: 0;
  display: flex;
  flex-direction: column;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--fg-3);
}
.dot.ok { background: var(--ok); }
.dot.err { background: var(--err); }
.dot.warn { background: var(--warn); }
.dot.info { background: var(--brand-2); }

.save-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--fg-3);
}

.editor-body {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
  padding: 14px;
  gap: 14px;
}

.main-panel {
  flex: 1;
  min-width: 0;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.main-panel-scroll {
  flex: 1;
  overflow: auto;
  /* No left padding — the editor content provides its own left gutter for
     the block drag handle. */
  padding: 22px 26px 22px 0;
}

.attr-panel {
  width: 340px;
  flex: 0 0 340px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.loading-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--fg-3);
  font-size: 13px;
}

.delete-msg {
  font-size: 13px;
  color: var(--fg-2);
  margin: 0;
}

.slug-error {
  font-size: 12px;
  color: var(--err);
  margin: 0;
}

.delete-error {
  font-size: 12px;
  color: var(--err);
  margin: 0;
}

.spacer {
  flex: 1;
}
</style>
