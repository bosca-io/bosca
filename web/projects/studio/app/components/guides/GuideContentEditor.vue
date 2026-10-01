<script setup lang="ts">
import type { Metadata, MetadataParentCollectionInput, Profile } from '~/types/graphql'
import { useAuth } from '@bosca/auth-client-browser'
import { applyAttributes } from '~/utils/editor/attributes'
import { executeTool, type TemplateAttributeTool } from '~/utils/editor/tool'
import type { AttributeState } from '~/utils/editor/attribute'
import DocumentEditor from '~/components/document/DocumentEditor.vue'
import { captureYDocRevision, markYDocSaved } from '~/utils/editor/ydoc'
import * as Y from 'yjs'
import gql from 'graphql-tag'

const props = defineProps<{
  metadata: Metadata
  profile: Profile
  accent?: string
  refreshMetadata: () => Promise<void>
}>()

const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const authState = import.meta.client ? useAuth() : null

const saveDocumentGql = gql`
  mutation SaveGuideContentDoc($id: UUID!, $version: Int!, $document: DocumentInput!) {
    content { metadata { setMetadataDocument(id: $id, version: $version, document: $document) } }
  }
`
const saveAttributesGql = gql`
  mutation SaveGuideContentAttrs($id: UUID!, $attributes: JSON!) {
    content { metadata { setMetadataAttributes(id: $id, attributes: $attributes) } }
  }
`
const setNameGql = gql`
  mutation SetGuideContentName($id: UUID!, $name: String!) {
    content { metadata { setMetadataName(id: $id, name: $name) { id name } } }
  }
`
const setCollectionsGql = gql`
  mutation SetGuideContentCollections($id: UUID!, $collections: [MetadataParentCollectionInput!]!) {
    content { metadata { setMetadataParentCollections(id: $id, collections: $collections) } }
  }
`
const setRelationshipsGql = gql`
  mutation SetGuideContentRelationships($id: UUID!, $relationships: [MetadataRelationshipInput!]!) {
    content { metadata { setMetadataRelationships(id: $id, relationships: $relationships) } }
  }
`
const setSlugGql = gql`
  mutation SetGuideContentSlug($id: UUID!, $slug: String!) {
    content { metadata { setMetadataSlug(id: $id, slug: $slug) } }
  }
`
const slugAvailableGql = gql`
  query CheckGuideContentSlug($slug: String!) {
    content { slugAvailable(slug: $slug) }
  }
`

const item = computed(() => props.metadata)
const profileRef = computed(() => props.profile)

const collab = useCollaborationAndAttributes(item, profileRef)
const ydoc = collab.ydoc
const ydocReady = collab.ready
const uploader = useUploader()
const editorRef = ref<InstanceType<typeof DocumentEditor> | null>(null)

const saving = ref(false)
const documentName = ref(props.metadata.name ?? '')

watch(() => props.metadata.name, (n) => { if (n?.trim()) documentName.value = n.trim() })

const hasUnsavedChanges = ref(false)
watch(ydoc, (doc) => {
  if (!doc) { hasUnsavedChanges.value = false; return }
  const changesText = doc.getText('changes')
  hasUnsavedChanges.value = changesText.getAttribute('changes') === 'true'
  changesText.observe(() => {
    hasUnsavedChanges.value = changesText.getAttribute('changes') === 'true'
  })
}, { immediate: true })

async function save(): Promise<boolean> {
  const m = props.metadata
  if (!m || saving.value) return false
  const saveRevision = ydoc.value ? captureYDocRevision(ydoc.value) : null
  saving.value = true

  try {
    const doc = editorRef.value?.getDocument?.()
    if (!doc) { saving.value = false; return false }

    // 1. Save YDoc collaboration state
    if (ydoc.value) {
      const token = authState?.auth.token
      if (!token) throw new Error('Not authenticated')
      const collabResponse = await fetch(
        `/api/v1/content/metadata/${m.id}/document/collaboration?version=${m.version}`,
        {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/octet-stream',
            'Accept': 'application/octet-stream',
            'Authorization': `Bearer ${token}`,
          },
          body: Y.encodeStateAsUpdate(ydoc.value) as unknown as BodyInit,
        },
      )
      if (!collabResponse.ok) {
        throw new Error(`Failed to save collaboration data: ${collabResponse.status} ${collabResponse.statusText}`)
      }
    }

    // 2. Save document
    await gqlMutation(saveDocumentGql, { id: m.id, version: m.version, document: doc })

    // 3. Refresh metadata to get updated version
    await props.refreshMetadata()

    // 4. Apply and save attributes, collections, relationships
    const parentCollections: MetadataParentCollectionInput[] = []
    const metadataRelationships: Array<{ id1: string; id2: string; relationship: string; attributes: Record<string, unknown> }> = []
    applyAttributes(m, collab.attributes, collab.rawAttributes, parentCollections, metadataRelationships)

    await gqlMutation(setNameGql, { id: m.id, name: doc.title })
    await gqlMutation(saveAttributesGql, { id: m.id, attributes: collab.rawAttributes.value })
    await gqlMutation(setCollectionsGql, { id: m.id, collections: parentCollections })
    await gqlMutation(setRelationshipsGql, { id: m.id, relationships: metadataRelationships })

    // 5. Mark YDoc as saved
    if (ydoc.value && saveRevision) {
      markYDocSaved(ydoc.value, saveRevision)
    }

    // 6. Refresh and reload collaboration state
    await props.refreshMetadata()
    collab.refreshState()

    return true
  } catch (e: unknown) {
    console.error('Save failed', e)
    toast.error(e instanceof Error ? e.message : 'Failed to save')
    return false
  } finally {
    saving.value = false
  }
}

function onTitleUpdate(title: string) {
  const t = title.trim()
  if (t) documentName.value = t
}

function onRunTool(attribute: AttributeState, tool: TemplateAttributeTool) {
  // The attribute's own loading ref drives the tool button's spinner state.
  executeTool(props.metadata, tool, attribute, attribute.loading)
}

// ── Slug editing ───────────────────────────────────────────────────────────
// The sidebar shows the slug read-only; clicking it emits `edit-slug`, which
// opens this modal. Mirrors the slug flow in the document editor page.
const slugModalOpen = ref(false)
const slugValue = ref('')
const slugSaving = ref(false)
const slugError = ref('')
const slugInputRef = ref<{ available: boolean | null } | null>(null)

async function checkSlugAvailable(slugVal: string): Promise<boolean> {
  if (slugVal === props.metadata.slug) return true
  const result = await gqlQuery<{ content: { slugAvailable: boolean } }>(slugAvailableGql, { slug: slugVal })
  return result.content.slugAvailable
}

function openSlugModal() {
  slugValue.value = props.metadata.slug || ''
  slugError.value = ''
  slugSaving.value = false
  slugModalOpen.value = true
}

const canSaveSlug = computed(() =>
  !!slugValue.value && !slugSaving.value && slugInputRef.value?.available !== false)

async function onSaveSlug() {
  const m = props.metadata
  if (!m || !slugValue.value || slugSaving.value) return
  if (slugValue.value === m.slug) {
    slugModalOpen.value = false
    return
  }
  slugSaving.value = true
  slugError.value = ''
  try {
    await gqlMutation(setSlugGql, { id: m.id, slug: slugValue.value })
    slugModalOpen.value = false
    // Refresh so the read-only slug field in the sidebar reflects the change.
    await props.refreshMetadata()
  } catch (e: unknown) {
    slugError.value = e instanceof Error ? e.message : 'Failed to update slug'
  } finally {
    slugSaving.value = false
  }
}

defineExpose({ save, saving, hasUnsavedChanges, documentName })
</script>

<template>
  <div class="editor-body">
    <div class="main-panel">
      <div class="main-panel-scroll">
        <ClientOnly>
          <DocumentEditor
            v-if="ydoc && collab.attributes"
            ref="editorRef"
            :metadata="metadata"
            :profile="profile"
            :attributes="collab.attributes"
            :ydoc="ydoc"
            :editable="ydocReady"
            :on-document="() => {}"
            :on-title-update="onTitleUpdate"
          />
          <div v-else class="loading-state">Loading editor…</div>
        </ClientOnly>
      </div>
    </div>

    <div class="attr-panel">
      <AttributesSidebar
        v-if="ydoc && collab.attributes"
        :content="metadata"
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

    <Modal
      v-if="slugModalOpen"
      title="Edit Slug"
      subtitle="URL-friendly identifier for this content"
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
          :accent="accent"
          :disabled="!canSaveSlug"
          @click="onSaveSlug">
          {{ slugSaving ? 'Saving…' : 'Save' }}
        </Button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.editor-body {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
  padding: 8px 14px 14px;
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

.slug-error {
  font-size: 12px;
  color: var(--err);
  margin: 0;
}

.spacer {
  flex: 1;
}
</style>
