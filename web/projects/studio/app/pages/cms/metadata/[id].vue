<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import Sortable from 'sortablejs'
import type { Metadata, Profile, MetadataInput, MetadataParentCollectionInput } from '~/types/graphql'
import { applyAttributes } from '~/utils/editor/attributes'
import {
  BIBLE_CONTENT_TYPE,
  DBL_BUNDLE_ACCEPT,
  isBibleContentType,
  isDblBundle,
  uploadMetadataContent,
} from '~/utils/bibleBundleUpload'
import type { OverflowMenuItem, SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const route = useRoute()
const router = useRouter()
const toast = useToast()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation, useSubscription } = useGraphQL()
const { isAdmin, hasGroup, load: loadPersonas } = usePersonas()
const { commentsEnabled, load: loadFeatures } = useServerFeatures()
const canModerate = computed(() => commentsEnabled.value && (isAdmin.value || hasGroup('sa')))

// ── Queries ──────────────────────────────────────────────────────────────────
const metadataGql = gql`
  query GetMetadataView($id: UUID!) {
    profiles {
      current {
        id
        name
      }
    }
    content {
      documentTemplates {
        all {
          metadata { id version name }
        }
      }
      dataTemplates {
        all {
          metadata { id version name }
        }
      }
      guideTemplates {
        all {
          metadata { id version name }
        }
      }
      metadata(id: $id) {
        __typename
        id
        version
        permissions {
          action
          group { id name }
        }
        parentId
        parentCollections(offset: 0, limit: 1000) {
          id
          name
          attributes
          workflow { state pending }
        }
        relationships {
          __typename
          relationship
          attributes
          metadata {
            id
            name
            languageTag
            type
            attributes
            content { type }
          }
        }
        supplementary {
          id
          name
          key
          created
          modified
          uploaded
          content {
            type
            length
            urls {
              download { url }
            }
          }
        }
        variants {
          id
          name
          languageTag
        }
        slug
        name
        languageTag
        public
        publicContent
        publicSupplementary
        ready
        created
        modified
        uploaded
        source {
          id
          identifier
          url
          status
          source { id name description }
        }
        attributes
        systemAttributes
        locked
        labels
        type
        version
        searchable
        recommendable
        commentsEnabled
        commentRepliesEnabled
        syncVariantCollections
        syncVariantRelationships
        content {
          type
          length
          urls {
            download { url headers { name value } }
            upload { url headers { name value } }
          }
        }
        data {
          template {
            id
            version
            dataTemplate {
              attributes {
                configuration description key list location name supplementaryKey type ui
                workflows { autoRun }
                tools { id name description query resultPath }
              }
              defaultAttributes
            }
          }
        }
        guide {
          template {
            id
            version
            documentTemplate {
              attributes {
                configuration description key list location name supplementaryKey type ui
                workflows { autoRun }
                tools { id name description query resultPath }
              }
            }
          }
        }
        document {
          title
          content
          template {
            id
            version
            documentTemplate {
              content
              schema
              attributes {
                configuration description key list location name supplementaryKey type ui
                workflows { autoRun }
                tools { id name description query resultPath }
              }
            }
          }
        }
        categories {
          id
          name
        }
        traits {
          id
          name
        }
        media {
          status
          hls { url audioOnlyUrl }
          downloadUrl
          thumbnailUrl
          durationSeconds
          maxResolution
          aspectRatio
          actualVideoQuality
          videoQuality
          maxResolutionTier
          transcriptions { id languageCode name status }
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
    metadata: Metadata
    documentTemplates: { all: Array<{ metadata: { id: string; version: number; name: string } | null }> }
    dataTemplates: { all: Array<{ metadata: { id: string; version: number; name: string } | null }> }
    guideTemplates: { all: Array<{ metadata: { id: string; version: number; name: string } | null }> }
    states: { all: Array<{ id: string; name: string; description: string }> }
  }
}>('metadata-view', metadataGql, { id: route.params.id })

const metadata = computed<Metadata | null>(() => data.value?.content?.metadata ?? null)
const profile = computed<Profile>(() => data.value?.profiles?.current ?? { id: '', name: 'Unknown' } as Profile)
const initialLoadDone = ref(status.value === 'success')
watch(status, (s) => { if (s === 'success') initialLoadDone.value = true })
const isLoading = computed(() => !initialLoadDone.value && status.value === 'pending')

const metadataSubscriptionGql = gql`
  subscription MetadataViewChanges {
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

const item = computed(() => metadata.value)
const collab = useCollaborationAndAttributes(
  item,
  profile,
)
const ydoc = collab.ydoc
const ydocReady = collab.ready
const metadataAttributes = collab.attributes
const rawAttributes = collab.rawAttributes
const uploader = useUploader()

// ── Editable fields ──────────────────────────────────────────────────────────
const nameField = ref('')
const contentTypeField = ref('')
const languageTagField = ref('')
const labelsField = ref('')
const selectedTemplate = ref('')
const originalTemplate = ref('')

const publicFlag = ref(false)
const publicContent = ref(false)
const publicSupplementary = ref(false)
const locked = ref(false)
const searchable = ref(false)
const recommendable = ref(false)
// Per-item comment gates (distinct from the deployment-wide `commentsEnabled`
// feature flag above). These persist immediately on toggle.
const itemCommentsEnabled = ref(false)
const itemCommentRepliesEnabled = ref(false)
const syncVariantCollections = ref(true)
const syncVariantRelationships = ref(true)

const workflowState = ref('')
const activeTab = ref('Attributes')

function bind() {
  const m = metadata.value
  if (!m) return
  nameField.value = m.name || ''
  contentTypeField.value = m.content?.type || ''
  languageTagField.value = m.languageTag || ''
  labelsField.value = (m as any).labels?.join(', ') || ''
  publicFlag.value = m.public || false
  publicContent.value = m.publicContent || false
  publicSupplementary.value = m.publicSupplementary || false
  locked.value = m.locked || false
  searchable.value = m.searchable || false
  recommendable.value = (m as any).recommendable ?? true
  itemCommentsEnabled.value = m.commentsEnabled ?? false
  itemCommentRepliesEnabled.value = m.commentRepliesEnabled ?? false
  syncVariantCollections.value = (m as any).syncVariantCollections ?? true
  syncVariantRelationships.value = (m as any).syncVariantRelationships ?? true
  rawAttributes.value = m.attributes || {}
  workflowState.value = m.workflow?.state || ''
  const ct = m.content?.type || ''
  if (ct.startsWith('bosca/v-data')) selectedTemplate.value = (m as any).data?.template?.id || ''
  else if (ct.startsWith('bosca/v-guide')) selectedTemplate.value = (m as any).guide?.template?.id || ''
  else selectedTemplate.value = (m as any).document?.template?.id || ''
  originalTemplate.value = selectedTemplate.value
}

watch(metadata, bind)
onMounted(() => { bind(); loadPersonas(); loadFeatures() })

// ── Templates ────────────────────────────────────────────────────────────────
const templateOptions = computed<SelectOption[]>(() => {
  const ct = metadata.value?.content?.type || ''
  let all: Array<{ metadata: { id: string; name: string } | null }> = []
  if (ct.startsWith('bosca/v-data')) all = data.value?.content?.dataTemplates?.all ?? []
  else if (ct.startsWith('bosca/v-guide')) all = data.value?.content?.guideTemplates?.all ?? []
  else all = data.value?.content?.documentTemplates?.all ?? []
  return all.filter(t => t.metadata != null).map(t => ({
    value: t.metadata!.id,
    label: t.metadata!.name,
  }))
})

// ── Workflow states ──────────────────────────────────────────────────────────
const availableStates = computed<SelectOption[]>(() =>
  (data.value?.content?.states?.all ?? []).map(s => ({
    value: s.id,
    label: s.name,
  })),
)

// ── Tabs ─────────────────────────────────────────────────────────────────────
const tabItems = computed(() => {
  const items = ['Attributes', 'Collections', 'Supplementary', 'Relationships', 'Permissions']
  // Recommendations (engine item-to-item) — not meaningful for template metadata.
  if (!metadata.value?.content?.type?.includes('template')) items.push('Recommendations')
  return items
})

// ── Permissions ──────────────────────────────────────────────────────────────
const groupsGql = gql`
  query GetMetadataGroups($limit: Int!, $offset: Long!) {
    security { groups { all(limit: $limit, offset: $offset) { id name } } }
  }
`
const findGroupsGql = gql`
  query FindMetadataGroups($q: String!, $limit: Int!, $offset: Long!) {
    security { groups { find(nameOrDescription: $q, limit: $limit, offset: $offset) { id name } } }
  }
`
const addPermissionGql = gql`
  mutation AddMetadataPermission($perm: PermissionInput!) {
    content { metadata { addPermission(permission: $perm) { action group { id name } } } }
  }
`
const deletePermissionGql = gql`
  mutation DeleteMetadataPermission($perm: PermissionInput!) {
    content { metadata { deletePermission(permission: $perm) { action group { id name } } } }
  }
`

interface MetadataPermission {
  action: string
  group: { id: string; name: string }
}

const PERMISSION_ACTIONS = ['VIEW', 'EDIT', 'DELETE', 'MANAGE', 'LIST']
const showPermModal = ref(false)
const permAction = ref('VIEW')
const permGroupId = ref('')
const permSaving = ref(false)

const { data: groupsData } = useAsyncQuery<{ security: { groups: { all: Array<{ id: string; name: string }> } } }>('metadata-security-groups', groupsGql, { limit: 200, offset: 0 })
const groupOptions = computed<SelectOption[]>(() =>
  (groupsData.value?.security?.groups?.all ?? []).map(g => ({ value: g.id, label: g.name })),
)

async function searchGroups(q: string): Promise<SelectOption[]> {
  const trimmed = q.trim()
  if (!trimmed) return groupOptions.value
  try {
    const result = await gqlQuery<{ security: { groups: { find: Array<{ id: string; name: string }> } } }>(
      findGroupsGql,
      { q: trimmed, limit: 50, offset: 0 },
    )
    return (result.security?.groups?.find ?? []).map(g => ({ value: g.id, label: g.name }))
  } catch {
    return groupOptions.value.filter(o => o.label.toLowerCase().includes(trimmed.toLowerCase()))
  }
}

const permissions = computed<MetadataPermission[]>(() => (metadata.value?.permissions ?? []) as MetadataPermission[])

async function addPermission() {
  const m = metadata.value
  if (!m || !permGroupId.value || !permAction.value) return
  permSaving.value = true
  try {
    await gqlMutation(addPermissionGql, {
      perm: { action: permAction.value, entityId: m.id, groupId: permGroupId.value },
    })
    await refresh()
    toast.success('Permission added')
    showPermModal.value = false
    permGroupId.value = ''
    permAction.value = 'VIEW'
  } catch (e: any) {
    toast.error(e?.message || 'Failed to add permission')
  } finally {
    permSaving.value = false
  }
}

async function removePermission(perm: MetadataPermission) {
  const m = metadata.value
  if (!m) return
  try {
    await gqlMutation(deletePermissionGql, {
      perm: { action: perm.action, entityId: m.id, groupId: perm.group.id },
    })
    await refresh()
    toast.success('Permission removed')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to remove permission')
  }
}

// ── Collection search (for adding parent collections) ────────────────────────
const collectionSearchGql = gql`
  query SearchParentCollections($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          collection { id name type }
        }
      }
    }
  }
`

async function searchCollections(q: string): Promise<SelectOption[]> {
  const result = await gqlQuery<any>(collectionSearchGql, { query: q, filter: '_type = "collection"', limit: 20, offset: 0 })
  return (result?.search?.search?.documents ?? [])
    .filter((d: any) => d.collection != null)
    .map((d: any) => ({ value: d.collection.id, label: d.collection.name, icon: d.collection.type === 'folder' ? 'folder' : 'boxes' }))
}

const addCollectionId = ref('')

async function onAddCollection() {
  const m = metadata.value
  if (!m || !addCollectionId.value) return
  const existing = ((m as any).parentCollections || []).map((c: any) => ({ id: c.id, attributes: c.itemAttributes || c.attributes || {} }))
  existing.push({ id: addCollectionId.value, attributes: {} })
  try {
    await gqlMutation(setCollectionsGql, { id: m.id, collections: existing })
    addCollectionId.value = ''
    await refresh()
    toast.success('Collection added')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to add collection')
  }
}

async function onRemoveCollection(collectionId: string) {
  const m = metadata.value
  if (!m) return
  const existing = ((m as any).parentCollections || [])
    .filter((c: any) => c.id !== collectionId)
    .map((c: any) => ({ id: c.id, attributes: c.itemAttributes || c.attributes || {} }))
  try {
    await gqlMutation(setCollectionsGql, { id: m.id, collections: existing })
    await refresh()
    toast.success('Collection removed')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to remove collection')
  }
}

// ── Relationship management ──────────────────────────────────────────────────
const metadataSearchGql = gql`
  query SearchRelMetadata($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          metadata { id name content { type } }
        }
      }
    }
  }
`

async function searchMetadata(q: string): Promise<SelectOption[]> {
  const result = await gqlQuery<any>(metadataSearchGql, { query: q, filter: '_type = "metadata"', limit: 20, offset: 0 })
  return (result?.search?.search?.documents ?? [])
    .filter((d: any) => d.metadata != null)
    .map((d: any) => ({ value: d.metadata.id, label: d.metadata.name }))
}

const addRelMetadataId = ref('')
const addRelType = ref('')

const addRelationshipGql = gql`
  mutation AddMVRelationship($relationship: MetadataRelationshipInput!) {
    content { metadata { addRelationship(relationship: $relationship) { relationship } } }
  }
`

const deleteRelationshipGql = gql`
  mutation DeleteMVRelationship($id1: UUID!, $id2: UUID!, $relationship: String!) {
    content { metadata { deleteRelationship(id1: $id1, id2: $id2, relationship: $relationship) } }
  }
`

async function onAddRelationship() {
  const m = metadata.value
  if (!m || !addRelMetadataId.value || !addRelType.value) return
  try {
    await gqlMutation(addRelationshipGql, {
      relationship: {
        id1: m.id,
        id2: addRelMetadataId.value,
        relationship: addRelType.value,
        attributes: { sort: 0 },
      },
    })
    addRelMetadataId.value = ''
    addRelType.value = ''
    await refresh()
    toast.success('Relationship added')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to add relationship')
  }
}

async function onRemoveRelationship(rel: any) {
  const m = metadata.value
  if (!m) return
  try {
    await gqlMutation(deleteRelationshipGql, {
      id1: m.id,
      id2: rel.metadata?.id,
      relationship: rel.relationship,
    })
    await refresh()
    toast.success('Relationship removed')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to remove relationship')
  }
}

const editRelationshipGql = gql`
  mutation EditMVRelationship($relationship: MetadataRelationshipInput!) {
    content { metadata { editRelationship(relationship: $relationship) } }
  }
`

const mergeRelAttributesGql = gql`
  mutation MergeMVRelationshipAttributes($id1: UUID!, $id2: UUID!, $relationship: String!, $attributes: JSON!) {
    content {
      metadata {
        mergeMetadataRelationshipAttributes(metadata1Id: $id1, metadata2Id: $id2, relationship: $relationship, attributes: $attributes)
      }
    }
  }
`

// Relationship attribute editing (JSON)
const relAttributesOpen = ref(false)
const relAttributesSaving = ref(false)
const selectedRelationship = ref<any>(null)
const relAttributesJson = ref<unknown>({})

function openRelationshipAttributes(rel: any) {
  selectedRelationship.value = rel
  const attrs = rel.attributes
  relAttributesJson.value = attrs && typeof attrs === 'object'
    ? JSON.parse(JSON.stringify(attrs))
    : {}
  relAttributesOpen.value = true
}

// JsonEditorVue emits a string while the user is in text mode; normalize back
// to an object before sending it to the API.
function normalizeJsonValue(value: unknown): Record<string, unknown> {
  if (typeof value === 'string') {
    return value.trim() ? JSON.parse(value) : {}
  }
  return (value ?? {}) as Record<string, unknown>
}

async function onSaveRelationshipAttributes() {
  const m = metadata.value
  const rel = selectedRelationship.value
  if (!m || !rel || relAttributesSaving.value) return
  relAttributesSaving.value = true
  try {
    const attributes = normalizeJsonValue(relAttributesJson.value)
    await gqlMutation(editRelationshipGql, {
      relationship: {
        id1: m.id,
        id2: rel.metadata?.id,
        relationship: rel.relationship,
        attributes,
      },
    })
    await refresh()
    relAttributesOpen.value = false
    toast.success('Relationship attributes updated')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to update attributes')
  } finally {
    relAttributesSaving.value = false
  }
}

// Relationship reordering. Order is carried by a `sort` key inside each
// relationship's attributes; the server returns relationships already sorted.
// This local list exists only so SortableJS has a mutable mirror to splice —
// every server response replaces it wholesale, so the server's order wins.
const localRelationships = ref<any[]>([])
watch(metadata, (m) => {
  localRelationships.value = [...(m?.relationships ?? [])]
}, { immediate: true })

async function onRelationshipReorder(oldIndex: number, newIndex: number) {
  const m = metadata.value
  if (!m) return
  const moved = localRelationships.value[oldIndex]
  if (!moved) return

  const start = Math.max(Number(localRelationships.value[0]?.attributes?.sort) || 0, 0)
  localRelationships.value.splice(oldIndex, 1)
  localRelationships.value.splice(newIndex, 0, moved)

  try {
    let index = 0
    for (const rel of localRelationships.value) {
      const attrs: Record<string, unknown> = { ...(rel.attributes ?? {}) }
      const newSort = start + index
      // Merge (not edit) so only `sort` changes; other attribute keys written
      // concurrently elsewhere are preserved.
      if (attrs.sort !== newSort) {
        attrs.sort = newSort
        await gqlMutation(mergeRelAttributesGql, {
          id1: m.id,
          id2: rel.metadata?.id,
          relationship: rel.relationship,
          attributes: attrs,
        })
      }
      index++
    }
    await refresh()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to reorder relationships')
    await refresh()
  }
}

const relListEl = ref<HTMLElement | null>(null)
let relSortable: Sortable | null = null

watch([relListEl, activeTab], () => {
  relSortable?.destroy()
  relSortable = null
  const el = relListEl.value
  if (activeTab.value !== 'Relationships' || !el) return
  relSortable = Sortable.create(el, {
    handle: '.rel-drag-handle',
    animation: 150,
    onEnd: async (evt) => {
      if (evt.oldIndex === undefined || evt.newIndex === undefined) return
      if (evt.oldIndex === evt.newIndex) return
      await onRelationshipReorder(evt.oldIndex, evt.newIndex)
    },
  })
}, { flush: 'post' })

onBeforeUnmount(() => {
  relSortable?.destroy()
  relSortable = null
})

// ── Supplementary management ─────────────────────────────────────────────────
const deleteSupplementaryGql = gql`
  mutation DeleteMVSupplementary($id: UUID!) {
    content { metadata { deleteSupplementary(id: $id) } }
  }
`

async function onDeleteSupplementary(supId: string) {
  try {
    await gqlMutation(deleteSupplementaryGql, { id: supId })
    await refresh()
    toast.success('Supplementary deleted')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to delete')
  }
}

// ── Mutations ────────────────────────────────────────────────────────────────
const saveGql = gql`
  mutation SaveMetadataView($id: UUID!, $input: MetadataInput!) {
    content { metadata { edit(id: $id, metadata: $input) { id } } }
  }
`

const setPublicGql = gql`
  mutation SetMVPublic($id: UUID!, $public: Boolean!) {
    content { metadata { setPublic(id: $id, public: $public) { id } } }
  }
`

const setPublicContentGql = gql`
  mutation SetMVPublicContent($id: UUID!, $public: Boolean!) {
    content { metadata { setPublicContent(id: $id, public: $public) { id } } }
  }
`

const setPublicSupplementaryGql = gql`
  mutation SetMVPublicSupplementary($id: UUID!, $public: Boolean!) {
    content { metadata { setPublicSupplementary(id: $id, public: $public) { id } } }
  }
`

const setSearchableGql = gql`
  mutation SetMVSearchable($id: UUID!, $searchable: Boolean!) {
    content { metadata { setMetadataSearchable(id: $id, searchable: $searchable) } }
  }
`

const setRecommendableGql = gql`
  mutation SetMVRecommendable($id: UUID!, $recommendable: Boolean!) {
    content { metadata { setMetadataRecommendable(id: $id, recommendable: $recommendable) } }
  }
`

const setCommentsEnabledGql = gql`
  mutation SetMVCommentsEnabled($id: UUID!, $enabled: Boolean!) {
    content { metadata { setMetadataCommentsEnabled(id: $id, enabled: $enabled) } }
  }
`

const setCommentRepliesEnabledGql = gql`
  mutation SetMVCommentRepliesEnabled($id: UUID!, $enabled: Boolean!) {
    content { metadata { setMetadataCommentRepliesEnabled(id: $id, enabled: $enabled) } }
  }
`

const setSyncVariantCollectionsGql = gql`
  mutation SetMVSyncVarCols($id: UUID!, $syncVariants: Boolean!) {
    content { metadata { setMetadataSyncVariantCollections(id: $id, syncVariants: $syncVariants) } }
  }
`

const setSyncVariantRelationshipsGql = gql`
  mutation SetMVSyncVarRels($id: UUID!, $syncVariants: Boolean!) {
    content { metadata { setMetadataSyncVariantRelationships(id: $id, syncVariants: $syncVariants) } }
  }
`

const setLockedGql = gql`
  mutation SetMVLocked($id: UUID!, $version: Int!, $locked: Boolean!) {
    content { metadata { setLocked(id: $id, version: $version, locked: $locked) { id } } }
  }
`

const setCollectionsGql = gql`
  mutation SetMVParentCollections($id: UUID!, $collections: [MetadataParentCollectionInput!]!) {
    content { metadata { setMetadataParentCollections(id: $id, collections: $collections) } }
  }
`

const setRelationshipsGql = gql`
  mutation SetMVRelationships($id: UUID!, $relationships: [MetadataRelationshipInput!]!) {
    content { metadata { setMetadataRelationships(id: $id, relationships: $relationships) } }
  }
`

const setTemplateGql = gql`
  mutation SetMVTemplate($id: UUID!, $version: Int!, $templateId: UUID!, $templateVersion: Int!) {
    content { metadata { setTemplate(id: $id, version: $version, templateId: $templateId, templateVersion: $templateVersion) { id } } }
  }
`

const transitionGql = gql`
  mutation BeginMVTransition($id: UUID!, $version: Int!, $state: String!, $status: String!) {
    content { transitions { beginTransition(request: { metadataId: $id, version: $version, stateId: $state, status: $status }) } }
  }
`

const cancelTransitionGql = gql`
  mutation CancelMVTransition($id: UUID!, $version: Int!) {
    content { transitions { cancelTransition(metadataId: $id, metadataVersion: $version) } }
  }
`

const setReadyGql = gql`
  mutation SetMVReady($id: UUID!) {
    content { metadata { setMetadataReady(id: $id) } }
  }
`

const setNotReadyGql = gql`
  mutation SetMVNotReady($id: UUID!) {
    content { metadata { setMetadataNotReady(id: $id) } }
  }
`

const setUploadedGql = gql`
  mutation SetMVUploaded($id: UUID!, $contentType: String!, $len: Int!) {
    content { metadata { setMetadataUploaded(id: $id, contentType: $contentType, len: $len, ready: false) } }
  }
`

const deleteGql = gql`
  mutation DeleteMVMetadata($metadataId: UUID!) {
    content { metadata { delete(metadataId: $metadataId) } }
  }
`

const setSlugGql = gql`
  mutation SetMVSlug($id: UUID!, $slug: String!) {
    content { metadata { setMetadataSlug(id: $id, slug: $slug) } }
  }
`

const clearDocCollabGql = gql`
  mutation ClearMVDocCollab($id: UUID!, $version: Int!) {
    content { metadata { clearDocumentCollaboration(metadataId: $id, metadataVersion: $version) } }
  }
`

const clearDataCollabGql = gql`
  mutation ClearMVDataCollab($id: UUID!, $version: Int!) {
    content { metadata { clearDataCollaboration(metadataId: $id, metadataVersion: $version) } }
  }
`

const reprocessBibleGql = gql`
  mutation ReprocessMVBible($id: UUID!, $version: Int!) {
    content { metadata { reprocessBible(metadataId: $id, metadataVersion: $version) } }
  }
`

// Per-item media (Mux) processing. Options are merged over the global defaults
// configured under System → Integrations → Mux.
const processMediaGql = gql`
  mutation ProcessMVMedia($id: UUID!, $options: MediaProcessingOptionsInput) {
    content { metadata { processMedia(id: $id, options: $options) } }
  }
`

const deleteMediaGql = gql`
  mutation DeleteMVMedia($id: UUID!) {
    content { metadata { deleteMedia(id: $id) } }
  }
`

const setMediaThumbnailOffsetGql = gql`
  mutation SetMVMediaThumbnailOffset($id: UUID!, $offsetSeconds: Float!) {
    content { metadata { setMediaThumbnailOffset(id: $id, offsetSeconds: $offsetSeconds) } }
  }
`

const updateMediaSettingsGql = gql`
  mutation UpdateMVMediaSettings($id: UUID!, $options: MediaProcessingOptionsInput!) {
    content { metadata { updateMediaSettings(id: $id, options: $options) } }
  }
`

// ── Save ─────────────────────────────────────────────────────────────────────
const saving = ref(false)

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
      contentType: contentTypeField.value,
      locked: locked.value,
      labels: labelsField.value.split(',').map(l => l.trim()).filter(l => l.length > 0),
      syncVariantCollections: syncVariantCollections.value,
      syncVariantRelationships: syncVariantRelationships.value,
    } as MetadataInput
    await gqlMutation(saveGql, { id: m.id, input })
    await gqlMutation(setPublicGql, { id: m.id, public: publicFlag.value })
    await gqlMutation(setPublicContentGql, { id: m.id, public: publicContent.value })
    await gqlMutation(setPublicSupplementaryGql, { id: m.id, public: publicSupplementary.value })
    await gqlMutation(setSearchableGql, { id: m.id, searchable: searchable.value })
    await gqlMutation(setRecommendableGql, { id: m.id, recommendable: recommendable.value })
    await gqlMutation(setSyncVariantCollectionsGql, { id: m.id, syncVariants: syncVariantCollections.value })
    await gqlMutation(setSyncVariantRelationshipsGql, { id: m.id, syncVariants: syncVariantRelationships.value })
    await gqlMutation(setCollectionsGql, { id: m.id, collections: parentCollections })
    await gqlMutation(setRelationshipsGql, { id: m.id, relationships: metadataRelationships })

    const selectedTemplateId = selectedTemplate.value
    const ct = m.content?.type || ''
    let templateChanged = false
    if (selectedTemplateId && ct !== 'bosca/v-guide') {
      const t = templateOptions.value.find(o => o.value === selectedTemplateId)
      if (t) {
        let all: any[] = []
        if (ct.startsWith('bosca/v-data')) all = data.value?.content?.dataTemplates?.all ?? []
        else all = data.value?.content?.documentTemplates?.all ?? []
        const tmpl = all.find(a => a.metadata?.id === selectedTemplateId)
        if (tmpl?.metadata) {
          await gqlMutation(setTemplateGql, {
            id: m.id,
            version: m.version,
            templateId: tmpl.metadata.id,
            templateVersion: tmpl.metadata.version,
          })
          templateChanged = selectedTemplateId !== originalTemplate.value
        }
      }
    }

    if (workflowState.value && workflowState.value !== m.workflow?.state) {
      await gqlMutation(transitionGql, {
        id: m.id,
        version: m.version,
        state: workflowState.value,
        status: `Admin changed state to ${workflowState.value}`,
      })
    }

    // Switching templates restructures the document/attribute UI and its
    // collaboration bindings, which a soft refetch can't fully rebuild. Force a
    // full page reload so the page reflects the newly selected template.
    if (templateChanged) {
      reloadNuxtApp({ persistState: false })
      return
    }

    await refresh()
    collab.refreshState()
    toast.success('Metadata saved')
  } catch (e: any) {
    toast.error(e?.message || 'Save failed')
  } finally {
    saving.value = false
  }
}

// ── Actions ──────────────────────────────────────────────────────────────────
async function onPublish() {
  const m = metadata.value
  if (!m) return
  try {
    await onSave()
    await gqlMutation(transitionGql, { id: m.id, version: m.version, state: 'published', status: 'Admin Published' })
    await refresh()
    toast.success('Published')
  } catch (e: any) {
    toast.error(e?.message || 'Publish failed')
  }
}

async function onUnpublish() {
  const m = metadata.value
  if (!m) return
  try {
    await gqlMutation(transitionGql, { id: m.id, version: m.version, state: 'draft', status: 'Admin Unpublished' })
    await refresh()
    toast.success('Unpublished')
  } catch (e: any) {
    toast.error(e?.message || 'Unpublish failed')
  }
}

async function onSetReady() {
  const m = metadata.value
  if (!m) return
  try {
    await onSave()
    await gqlMutation(setReadyGql, { id: m.id })
    await refresh()
    toast.success('Marked ready')
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

async function onSetNotReady() {
  const m = metadata.value
  if (!m) return
  try {
    await gqlMutation(setNotReadyGql, { id: m.id })
    await refresh()
    toast.success('Marked not ready')
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

const contentInputRef = ref<HTMLInputElement>()
const contentUploading = ref(false)
const isBibleMetadata = computed(() => isBibleContentType(metadata.value?.content?.type))

function openContentPicker() {
  if (!contentUploading.value) contentInputRef.value?.click()
}

async function onContentSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  try {
    if (file) await replaceContent(file)
  } finally {
    input.value = ''
  }
}

async function replaceContent(file: File) {
  if (!metadata.value || contentUploading.value) return
  const bibleUpload = isBibleMetadata.value
  if (bibleUpload && !isDblBundle(file)) {
    toast.error('Please select a DBL ZIP bundle.')
    return
  }

  contentUploading.value = true
  try {
    // Signed upload URLs are short-lived, so refresh immediately before using one.
    await refresh()
    const current = metadata.value
    const target = current?.content?.urls?.upload
    if (!current || !target) {
      throw new Error('No content upload URL is available')
    }
    await uploadMetadataContent(target, file)
    if (bibleUpload) {
      await gqlMutation(setUploadedGql, {
        id: current.id,
        contentType: BIBLE_CONTENT_TYPE,
        len: file.size,
      })
      await gqlMutation(reprocessBibleGql, { id: current.id, version: current.version })
      toast.success('DBL bundle uploaded and queued for processing')
    } else {
      toast.success('Content uploaded')
    }
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Content upload failed')
  } finally {
    contentUploading.value = false
  }
}

async function onCancelTransition() {
  const m = metadata.value
  if (!m) return
  try {
    await gqlMutation(cancelTransitionGql, { id: m.id, version: m.version })
    await refresh()
    toast.success('Transition cancelled')
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

async function onDownload() {
  const url = (metadata.value?.content as any)?.urls?.download?.url
  if (url) window.location.href = url
  else toast.error('No download URL available')
}

async function clearCollaboration() {
  const m = metadata.value
  if (!m) return
  try {
    if ((m as any).data) {
      await gqlMutation(clearDataCollabGql, { id: m.id, version: m.version })
    } else {
      await gqlMutation(clearDocCollabGql, { id: m.id, version: m.version })
    }
    toast.success('Collaboration data cleared')
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

async function onReprocessBible() {
  const m = metadata.value
  if (!m) return
  try {
    toast.info('Reprocessing Bible…')
    await gqlMutation(reprocessBibleGql, { id: m.id, version: m.version })
    toast.success('Bible reprocessed')
    await refresh()
  } catch (e: any) {
    toast.error(e?.message || 'Reprocess failed')
  }
}

// ── Media (Mux) management ───────────────────────────────────────────────────
// Per-item controls for submitting uploaded video/audio to the transcoding
// provider (Mux), tuning quality/resolution/thumbnail, and removing the
// processed asset. Per-item overrides take precedence over the global defaults
// configured under System → Integrations → Mux.
const itemVideoQuality = ref('none')
const itemMaxResolutionTier = ref('none')
const itemThumbnailOffset = ref<number | null>(0)
const mediaBusy = ref(false)

const videoQualityOptions: SelectOption[] = [
  { value: 'none', label: 'Use Global Default' },
  { value: 'basic', label: 'Basic' },
  { value: 'plus', label: 'Plus' },
  { value: 'premium', label: 'Premium' },
]

const maxResolutionOptions: SelectOption[] = [
  { value: 'none', label: 'Use Global Default' },
  { value: '1080p', label: '1080p' },
  { value: '1440p', label: '1440p' },
  { value: '2160p', label: '2160p (4K)' },
]

const isMediaProcessable = computed(() => {
  const ct = metadata.value?.content?.type || ''
  return ct.startsWith('video/') || ct.startsWith('audio/')
})

// Sync the per-item controls from the server whenever the media object changes.
// The current thumbnail offset is carried as a `time` query param on the URL.
watch(() => metadata.value?.media, (media) => {
  if (!media) {
    itemVideoQuality.value = 'none'
    itemMaxResolutionTier.value = 'none'
    itemThumbnailOffset.value = 0
    return
  }
  itemVideoQuality.value = media.videoQuality || 'none'
  itemMaxResolutionTier.value = media.maxResolutionTier || 'none'
  try {
    const time = media.thumbnailUrl ? new URL(media.thumbnailUrl).searchParams.get('time') : null
    itemThumbnailOffset.value = time ? Number(time) : 0
  } catch {
    itemThumbnailOffset.value = 0
  }
}, { immediate: true })

// Build a MediaProcessingOptionsInput from the per-item controls, omitting any
// field left on its global default so the server falls back to configured defaults.
function buildMediaOptions(includeThumbnail: boolean): Record<string, unknown> {
  const options: Record<string, unknown> = {}
  if (itemVideoQuality.value !== 'none') options.videoQuality = itemVideoQuality.value
  if (itemMaxResolutionTier.value !== 'none') options.maxResolutionTier = itemMaxResolutionTier.value
  if (includeThumbnail) {
    const offset = itemThumbnailOffset.value
    if (offset != null && offset > 0) options.thumbnailTimeOffsetSeconds = offset
  }
  return options
}

async function onUploadToMux() {
  const m = metadata.value
  if (!m || mediaBusy.value) return
  mediaBusy.value = true
  try {
    const options = buildMediaOptions(true)
    await gqlMutation(processMediaGql, {
      id: m.id,
      options: Object.keys(options).length ? options : undefined,
    })
    await refresh()
    toast.success('Submitted for Mux processing')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to upload to Mux')
  } finally {
    mediaBusy.value = false
  }
}

async function onUpdateMediaSettings() {
  const m = metadata.value
  if (!m || mediaBusy.value) return
  const options = buildMediaOptions(false)
  if (!Object.keys(options).length) {
    toast.warn('Select a quality or resolution to update')
    return
  }
  mediaBusy.value = true
  try {
    await gqlMutation(updateMediaSettingsGql, { id: m.id, options })
    await refresh()
    toast.success('Media settings updated — applied on the next upload to Mux')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to update media settings')
  } finally {
    mediaBusy.value = false
  }
}

async function onSetThumbnailOffset() {
  const m = metadata.value
  if (!m || mediaBusy.value) return
  const offset = itemThumbnailOffset.value ?? 0
  if (offset < 0) {
    toast.error('Enter a non-negative offset in seconds')
    return
  }
  mediaBusy.value = true
  try {
    await gqlMutation(setMediaThumbnailOffsetGql, { id: m.id, offsetSeconds: offset })
    await refresh()
    toast.success('Thumbnail updated')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to update thumbnail')
  } finally {
    mediaBusy.value = false
  }
}

async function onRemoveFromMux() {
  const m = metadata.value
  if (!m || mediaBusy.value) return
  mediaBusy.value = true
  try {
    await gqlMutation(deleteMediaGql, { id: m.id })
    await refresh()
    toast.success('Removing media from Mux')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to remove from Mux')
  } finally {
    mediaBusy.value = false
  }
}

function mediaStatusColor(s: string | null | undefined): string {
  if (s === 'ready') return '#34d99a'
  if (s === 'errored') return '#ff5d6c'
  return '#ffb547'
}

function formatDuration(seconds: number | null | undefined): string {
  if (!seconds) return '--'
  return `${Math.floor(seconds / 60)}m ${Math.round(seconds % 60)}s`
}

// ── Toggle actions (immediate save) ──────────────────────────────────────────
async function onLockChanged() {
  const m = metadata.value
  if (!m) return
  try {
    await gqlMutation(setLockedGql, { id: m.id, version: m.version, locked: locked.value })
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

async function onSearchableChanged() {
  const m = metadata.value
  if (!m) return
  try {
    await gqlMutation(setSearchableGql, { id: m.id, searchable: searchable.value })
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

async function onRecommendableChanged() {
  const m = metadata.value
  if (!m) return
  try {
    await gqlMutation(setRecommendableGql, { id: m.id, recommendable: recommendable.value })
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

async function onCommentsEnabledChanged() {
  const m = metadata.value
  if (!m) return
  try {
    await gqlMutation(setCommentsEnabledGql, { id: m.id, enabled: itemCommentsEnabled.value })
    // The server disables replies when comments are turned off; mirror that locally
    // so the replies switch doesn't show a stale "on" state.
    if (!itemCommentsEnabled.value) itemCommentRepliesEnabled.value = false
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

async function onCommentRepliesEnabledChanged() {
  const m = metadata.value
  if (!m) return
  try {
    await gqlMutation(setCommentRepliesEnabledGql, { id: m.id, enabled: itemCommentRepliesEnabled.value })
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

// ── Delete ───────────────────────────────────────────────────────────────────
const deleteTarget = ref(false)
const deleteLoading = ref(false)

async function confirmDelete() {
  const m = metadata.value
  if (!m) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { metadataId: m.id })
    router.push('/cms/metadata')
  } catch (e: any) {
    toast.error(e?.message || 'Delete failed')
  } finally {
    deleteLoading.value = false
  }
}

// ── Timeline events modal ────────────────────────────────────────────────────
const timelineEditorOpen = ref(false)

// ── Slug modal ───────────────────────────────────────────────────────────────
const slugModalOpen = ref(false)
const slugValue = ref('')
const slugSaving = ref(false)
const slugError = ref('')
const slugInputRef = ref<{ available: boolean | null } | null>(null)

const slugAvailableGql = gql`
  query CheckMVSlug($slug: String!) {
    content { slugAvailable(slug: $slug) }
  }
`

async function checkSlugAvailable(slugVal: string): Promise<boolean> {
  if (slugVal === metadata.value?.slug) return true
  const result = await gqlQuery<{ content: { slugAvailable: boolean } }>(slugAvailableGql, { slug: slugVal })
  return result.content.slugAvailable
}

function openSlugModal() {
  slugValue.value = metadata.value?.slug || ''
  slugError.value = ''
  slugSaving.value = false
  slugModalOpen.value = true
}

async function onSaveSlug() {
  const m = metadata.value
  if (!m || !slugValue.value || slugSaving.value) return
  if (slugValue.value === m.slug) { slugModalOpen.value = false; return }
  slugSaving.value = true
  slugError.value = ''
  try {
    await gqlMutation(setSlugGql, { id: m.id, slug: slugValue.value })
    slugModalOpen.value = false
    await refresh()
  } catch (e: any) {
    slugError.value = e?.message || 'Failed to update slug'
  } finally {
    slugSaving.value = false
  }
}

// ── Overflow menu ────────────────────────────────────────────────────────────
const overflowItems = computed<OverflowMenuItem[]>(() => {
  const m = metadata.value
  if (!m) return []
  const items: OverflowMenuItem[] = []
  if (m.workflow?.state === 'published' && !m.workflow?.pending) {
    items.push({ id: 'unpublish', label: 'Unpublish', icon: 'globe' })
  }
  if (m.workflow?.pending) {
    items.push({ id: 'cancel-transition', label: 'Cancel transition', icon: 'x' })
  }
  const ct = m.content?.type || ''
  if (ct.includes('template')) {
    items.push({ id: 'open-editor', label: 'Open Template Editor', icon: 'pencil' })
  } else if (ct.startsWith('bosca/v-document') || ct.startsWith('bosca/v-guide') || ct.startsWith('bosca/v-data')) {
    items.push({ id: 'open-editor', label: 'Open Document Editor', icon: 'pencil' })
  }
  if (canModerate.value) {
    items.push({ id: 'comments', label: 'Comments', icon: 'message-square' })
  }
  items.push(
    { id: 'slug', label: 'Edit slug…', icon: 'link' },
    { id: 'sep1', label: '', separator: true },
    { id: 'copy-id', label: 'Copy ID', icon: 'copy' },
    { id: 'download', label: 'Download content', icon: 'download', disabled: !m.content?.length },
  )
  if (isBibleContentType(ct)) {
    items.push({ id: 'reprocess-bible', label: 'Reprocess Bible', icon: 'refresh' })
  }
  items.push(
    { id: 'sep2', label: '', separator: true },
    { id: 'clear-collab', label: 'Clear collaboration data', icon: 'trash', danger: true },
    { id: 'sep3', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  )
  return items
})

async function onOverflowAction(id: string) {
  const m = metadata.value
  switch (id) {
    case 'open-editor': {
      if (!m) break
      router.push(getEditorRoute(m.id, m.content?.type))
      break
    }
    case 'unpublish': onUnpublish(); break
    case 'cancel-transition': onCancelTransition(); break
    case 'comments': navigateTo(`/cms/comments/${route.params.id}`); break
    case 'slug': openSlugModal(); break
    case 'copy-id': navigator.clipboard.writeText(metadata.value!.id); toast.success('Copied'); break
    case 'download': onDownload(); break
    case 'reprocess-bible': onReprocessBible(); break
    case 'clear-collab': clearCollaboration(); break
    case 'delete': deleteTarget.value = true; break
  }
}

// ── Helpers ──────────────────────────────────────────────────────────────────
function formatDate(d: string | null | undefined): string {
  if (!d) return '--'
  try {
    return new Date(d).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short' })
  } catch { return d }
}

function downloadUrl(url: string) {
  window.location.href = url
}

function formatBytes(n: number | null | undefined): string {
  if (!n) return '--'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

const canPublish = computed(() => {
  const w = metadata.value?.workflow
  return w?.state === 'draft' && !w?.pending
})


const otherVariants = computed(() =>
  ((metadata.value as any)?.variants ?? []).filter((v: any) => v.id !== metadata.value?.id),
)

function goToVariant(id: string) {
  window.location.href = `/cms/metadata/${id}`
}

const existingLanguages = computed(() =>
  ((metadata.value as any)?.variants ?? []).map((v: any) => v.languageTag),
)

const addVariantModalOpen = ref(false)

function onVariantCreated(newId: string) {
  addVariantModalOpen.value = false
  if (newId) router.push(`/cms/metadata/${newId}`)
}
</script>

<template>
  <PageShell class="mv-shell">
    <template #header>
      <PageHeader
        :title="nameField || 'Metadata'"
        :subtitle="metadata ? `${metadata.type || 'metadata'} · v${metadata.version}` : ''"
        :breadcrumb="buildBreadcrumb('CMS', 'Everything', nameField || '…')"
        :accent="accent"
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

    <div v-if="isLoading" class="loading-state">Loading…</div>

    <div v-else-if="metadata" class="mv-body">
      <!-- ══ Left: main editing panel ══════════════════════════════════ -->
      <div class="panel panel--main">
        <div class="panel-scroll">
          <div class="section-head">Details</div>
          <div class="form-fields">
            <div class="field">
              <label class="field-label">Name</label>
              <TextInput v-model="nameField" placeholder="Name" icon="file" />
            </div>
            <div class="field">
              <label class="field-label">Slug</label>
              <div class="slug-row" @click="openSlugModal">
                <span class="mono slug-value">{{ metadata.slug || '(none)' }}</span>
                <Icon
                  name="pencil"
                  :size="12"
                  color="var(--fg-4)"
                  class="slug-edit-icon" />
              </div>
            </div>
            <div class="field-row">
              <div class="field">
                <label class="field-label">Language Tag</label>
                <TextInput v-model="languageTagField" placeholder="en" />
              </div>
              <div class="field">
                <label class="field-label">Content Type</label>
                <TextInput v-model="contentTypeField" placeholder="text/html" />
              </div>
            </div>
            <div class="field">
              <label class="field-label">Labels <span class="field-hint">(comma-separated)</span></label>
              <TextInput v-model="labelsField" placeholder="tag1, tag2" />
            </div>
          </div>

          <div class="hr" />

          <div class="tab-row">
            <button
              v-for="tab in tabItems"
              :key="tab"
              class="tab-btn"
              :class="{ active: activeTab === tab }"
              :style="activeTab === tab ? { color: accent, borderColor: accent } : {}"
              @click="activeTab = tab">{{ tab }}</button>
          </div>

          <!-- Attributes -->
          <div v-if="activeTab === 'Attributes'" class="tab-body">
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
              <div class="raw-label">Raw Attributes</div>
              <JsonEditorVue
                v-model="rawAttributes"
                :main-menu-bar="false"
                :navigation-bar="false"
                class="json-editor" />
            </div>
          </div>

          <!-- Collections -->
          <div v-else-if="activeTab === 'Collections'" class="tab-body">
            <div class="add-bar">
              <Select
                v-model="addCollectionId"
                searchable
                :on-search="searchCollections"
                placeholder="Search collections…"
                size="sm"
                icon="folder"
                :accent="accent" />
              <Button
                size="sm"
                primary
                :accent="accent"
                :disabled="!addCollectionId"
                @click="onAddCollection">Add</Button>
            </div>
            <MetadataParentCollectionsList
              v-if="(metadata as any).parentCollections?.length"
              :collections="(metadata as any).parentCollections"
              :accent="accent"
              @remove="onRemoveCollection" />
            <div v-else class="empty-msg">No parent collections.</div>
          </div>

          <!-- Supplementary -->
          <div v-else-if="activeTab === 'Supplementary'" class="tab-body">
            <div v-if="(metadata as any).supplementary?.length" class="list-items">
              <div v-for="sup in (metadata as any).supplementary" :key="sup.id" class="list-row">
                <Icon name="file" :size="14" color="var(--fg-3)" />
                <div class="list-text"><div class="list-name">{{ sup.name || sup.key }}</div><div class="mono list-sub">{{ sup.content?.type }} · {{ formatBytes(sup.content?.length) }}</div></div>
                <Button
                  v-if="sup.content?.urls?.download?.url"
                  size="sm"
                  icon="download"
                  @click="downloadUrl(sup.content.urls.download.url)">Download</Button>
                <button class="rm-btn rm-btn--danger" @click="onDeleteSupplementary(sup.id)"><Icon name="trash" :size="12" color="var(--err)" /></button>
              </div>
            </div>
            <div v-else class="empty-msg">No supplementary files.</div>
          </div>

          <!-- Relationships -->
          <div v-else-if="activeTab === 'Relationships'" class="tab-body">
            <div class="add-bar">
              <Select
                v-model="addRelMetadataId"
                searchable
                :on-search="searchMetadata"
                placeholder="Search metadata…"
                size="sm"
                icon="search"
                :accent="accent" />
              <TextInput v-model="addRelType" placeholder="Type (e.g. image.featured)" />
              <Button
                size="sm"
                primary
                :accent="accent"
                :disabled="!addRelMetadataId || !addRelType"
                @click="onAddRelationship">Add</Button>
            </div>
            <div v-if="localRelationships.length" ref="relListEl" class="list-items">
              <div v-for="rel in localRelationships" :key="`${rel.relationship}-${rel.metadata?.id}`" class="list-row">
                <button class="rel-drag-handle" title="Drag to reorder" @click.stop>
                  <Icon name="grip" :size="14" color="var(--fg-3)" />
                </button>
                <Icon name="link" :size="14" color="var(--fg-3)" />
                <div class="list-text list-text--link" @click="router.push(`/cms/metadata/${rel.metadata?.id}`)">
                  <div class="list-name">{{ rel.metadata?.name || rel.metadata?.id }}</div>
                  <div class="mono list-sub">{{ rel.relationship }}</div>
                </div>
                <Badge color="#5ec5ff">{{ rel.metadata?.content?.type || 'metadata' }}</Badge>
                <button class="rm-btn" title="Edit attributes" @click="openRelationshipAttributes(rel)"><Icon name="code" :size="12" color="var(--fg-3)" /></button>
                <button class="rm-btn" title="Remove relationship" @click="onRemoveRelationship(rel)"><Icon name="x" :size="12" color="var(--fg-3)" /></button>
              </div>
            </div>
            <div v-else class="empty-msg">No relationships.</div>

            <Modal
              v-if="relAttributesOpen"
              title="Relationship Attributes"
              :subtitle="`${selectedRelationship?.relationship} → ${selectedRelationship?.metadata?.name || selectedRelationship?.metadata?.id}`"
              icon="code"
              width="640px"
              @close="relAttributesOpen = false"
            >
              <JsonEditorVue
                v-model="relAttributesJson"
                :main-menu-bar="false"
                :navigation-bar="false"
                class="json-editor" />
              <template #footer>
                <span class="spacer" />
                <Button size="sm" @click="relAttributesOpen = false">Cancel</Button>
                <Button
                  size="sm"
                  primary
                  :accent="accent"
                  :disabled="relAttributesSaving"
                  @click="onSaveRelationshipAttributes">
                  {{ relAttributesSaving ? 'Saving…' : 'Save Attributes' }}
                </Button>
              </template>
            </Modal>
          </div>

          <!-- Permissions -->
          <div v-else-if="activeTab === 'Permissions'" class="tab-body">
            <div class="add-bar">
              <span class="tab-count">{{ permissions.length }} permission{{ permissions.length === 1 ? '' : 's' }}</span>
              <span class="spacer" />
              <Button
                size="sm"
                icon="plus"
                :accent="accent"
                @click="showPermModal = true">Add</Button>
            </div>
            <div v-if="permissions.length" class="list-items">
              <div v-for="(perm, idx) in permissions" :key="idx" class="list-row">
                <Badge :color="accent">{{ perm.action }}</Badge>
                <span class="perm-group">{{ perm.group.name }}</span>
                <button class="rm-btn" title="Remove" @click="removePermission(perm)">
                  <Icon name="x" :size="12" color="var(--fg-3)" />
                </button>
              </div>
            </div>
            <div v-else class="empty-msg">No permissions assigned. Admins and service accounts retain implicit access.</div>

            <Modal
              v-if="showPermModal"
              title="Add Permission"
              icon="shield"
              :accent="accent"
              @close="showPermModal = false"
            >
              <div class="perm-form">
                <Select
                  v-model="permAction"
                  label="Action"
                  :options="PERMISSION_ACTIONS.map(a => ({ value: a, label: a }))"
                  :accent="accent" />
                <Select
                  v-model="permGroupId"
                  label="Group"
                  searchable
                  placeholder="Search groups…"
                  :on-search="searchGroups"
                  :accent="accent" />
              </div>
              <template #footer>
                <span class="spacer" />
                <Button size="sm" @click="showPermModal = false">Cancel</Button>
                <Button
                  size="sm"
                  primary
                  :accent="accent"
                  :disabled="!permGroupId || permSaving"
                  @click="addPermission">
                  {{ permSaving ? 'Adding…' : 'Add' }}
                </Button>
              </template>
            </Modal>
          </div>

          <!-- Recommendations (engine item-to-item): Recommended (merged) + Related (co-occurrence) -->
          <div v-else-if="activeTab === 'Recommendations'" class="tab-body">
            <ItemRecommendationsCard :metadata-id="metadata.id" />
          </div>
        </div>
      </div>

      <!-- ══ Right: sidebar ════════════════════════════════════════════ -->
      <div class="panel panel--side">
        <!-- Info -->
        <SectionCard title="Info">
          <div class="kvs">
            <div class="kv"><span class="kv-k">Type</span><span class="mono kv-v">{{ metadata.type }}</span></div>
            <div class="kv"><span class="kv-k">Version</span><span class="mono kv-v">{{ metadata.version }}</span></div>
            <div class="kv"><span class="kv-k">Created</span><span class="kv-v">{{ formatDate((metadata as any).created) }}</span></div>
            <div class="kv"><span class="kv-k">Modified</span><span class="kv-v">{{ formatDate(metadata.modified) }}</span></div>
            <div v-if="(metadata as any).uploaded" class="kv"><span class="kv-k">Uploaded</span><span class="kv-v">{{ formatDate((metadata as any).uploaded) }}</span></div>
            <div v-if="metadata.content?.length" class="kv"><span class="kv-k">Size</span><span class="kv-v">{{ formatBytes(metadata.content.length as any) }}</span></div>
            <div class="kv"><span class="kv-k">Ready</span><span class="kv-v">{{ metadata.ready ? 'Yes' : 'No' }}</span></div>
          </div>
        </SectionCard>

        <!-- Content -->
        <SectionCard title="Content">
          <div class="sc-pad content-controls">
            <input
              ref="contentInputRef"
              class="content-file-input"
              type="file"
              :accept="isBibleMetadata ? DBL_BUNDLE_ACCEPT : undefined"
              @change="onContentSelected"
            >
            <div class="content-actions">
              <Button
                size="sm"
                icon="upload"
                primary
                :accent="accent"
                :disabled="contentUploading"
                @click="openContentPicker">
                {{ contentUploading ? 'Uploading…' : isBibleMetadata ? 'Upload DBL Bundle' : 'Upload Content' }}
              </Button>
              <Button
                v-if="metadata.content?.length"
                size="sm"
                icon="download"
                @click="onDownload">Download</Button>
            </div>
            <p v-if="isBibleMetadata" class="content-hint">
              Uploading a DBL ZIP bundle replaces the stored bundle and rebuilds its books and chapters.
            </p>
          </div>
        </SectionCard>

        <!-- Template -->
        <SectionCard v-if="!metadata.content?.type?.includes('template')" title="Template">
          <div class="sc-pad">
            <Select
              v-model="selectedTemplate"
              :options="templateOptions"
              searchable
              placeholder="Select template…"
              size="sm"
              :accent="accent"
              :disabled="metadata.content?.type === 'bosca/v-guide'" />
          </div>
        </SectionCard>

        <!-- Image preview -->
        <SectionCard v-if="metadata.content?.type?.startsWith('image/')" title="Preview">
          <div class="sc-pad"><img :src="`/content/image/${metadata.slug}`" class="img-preview" ></div>
        </SectionCard>

        <!-- Media preview (video/audio) -->
        <SectionCard
          v-else-if="metadata.content?.type?.startsWith('video/') || metadata.content?.type?.startsWith('audio/') || metadata.content?.type === 'bosca/x-youtube-video' || metadata.media?.hls?.url"
          title="Preview"
        >
          <div class="sc-pad">
            <ClientOnly>
              <SMediaPlayer :item="metadata" />
            </ClientOnly>
          </div>
        </SectionCard>

        <!-- Media (Mux) management (video/audio) -->
        <SectionCard v-if="isMediaProcessable" title="Media">
          <template #right>
            <Badge v-if="metadata.media" :color="mediaStatusColor(metadata.media.status)">{{ metadata.media.status }}</Badge>
          </template>

          <!-- Processed media info -->
          <div v-if="metadata.media?.status === 'ready'" class="kvs">
            <div v-if="metadata.media.durationSeconds" class="kv"><span class="kv-k">Duration</span><span class="kv-v">{{ formatDuration(metadata.media.durationSeconds) }}</span></div>
            <div v-if="metadata.media.maxResolution" class="kv"><span class="kv-k">Resolution</span><span class="kv-v">{{ metadata.media.maxResolution }}</span></div>
            <div v-if="metadata.media.aspectRatio" class="kv"><span class="kv-k">Aspect Ratio</span><span class="kv-v">{{ metadata.media.aspectRatio }}</span></div>
            <div class="kv"><span class="kv-k">Video Quality</span><span class="kv-v media-cap">{{ metadata.media.actualVideoQuality || 'basic' }}</span></div>
            <div v-if="metadata.media.transcriptions?.length" class="kv">
              <span class="kv-k">Transcriptions</span>
              <span class="kv-v">{{ metadata.media.transcriptions.map(t => `${t.name} (${t.status})`).join(', ') }}</span>
            </div>
          </div>

          <!-- Per-item controls -->
          <div class="sc-pad media-controls">
            <div class="media-grid">
              <div class="field">
                <label class="field-label">Video Quality</label>
                <Select
                  v-model="itemVideoQuality"
                  :options="videoQualityOptions"
                  size="sm"
                  :accent="accent" />
              </div>
              <div class="field">
                <label class="field-label">Max Resolution</label>
                <Select
                  v-model="itemMaxResolutionTier"
                  :options="maxResolutionOptions"
                  size="sm"
                  :accent="accent" />
              </div>
            </div>
            <div class="field">
              <label class="field-label">Thumbnail Offset <span class="field-hint">(seconds)</span></label>
              <NumberInput
                v-model="itemThumbnailOffset"
                :min="0"
                :step="0.1"
                placeholder="0" />
            </div>

            <div class="media-actions">
              <Button
                v-if="!metadata.media"
                size="sm"
                icon="upload"
                primary
                :accent="accent"
                :disabled="mediaBusy"
                @click="onUploadToMux">Upload to Mux</Button>
              <Button
                v-if="metadata.media?.status === 'ready'"
                size="sm"
                icon="settings"
                :disabled="mediaBusy"
                @click="onUpdateMediaSettings">Update Quality</Button>
              <Button
                v-if="metadata.media?.status === 'ready'"
                size="sm"
                icon="image"
                :disabled="mediaBusy"
                @click="onSetThumbnailOffset">Update Thumbnail</Button>
              <Button
                v-if="metadata.media?.status === 'preparing'"
                size="sm"
                icon="x"
                :disabled="mediaBusy"
                @click="onRemoveFromMux">Cancel Upload</Button>
              <Button
                v-if="metadata.media?.status === 'ready' || metadata.media?.status === 'errored'"
                size="sm"
                icon="trash"
                :disabled="mediaBusy"
                @click="onRemoveFromMux">Remove from Mux</Button>
            </div>
          </div>
        </SectionCard>

        <!-- Timeline events (for video/audio) -->
        <SectionCard
          v-if="metadata.content?.type?.startsWith('video/') || metadata.content?.type?.startsWith('audio/') || metadata.content?.type === 'bosca/x-youtube-video'"
          title="Timeline Events"
        >
          <div class="sc-pad">
            <Button size="sm" icon="clock" @click="timelineEditorOpen = true">
              Open Timeline Editor
            </Button>
          </div>
        </SectionCard>

        <!-- Source -->
        <SectionCard v-if="(metadata as any).source?.id || (metadata as any).source?.url" title="Source">
          <div class="kvs">
            <div v-if="(metadata as any).source?.source?.name" class="kv"><span class="kv-k">System</span><span class="kv-v">{{ (metadata as any).source.source.name }}</span></div>
            <div v-if="(metadata as any).source?.identifier" class="kv"><span class="kv-k">ID</span><span class="mono kv-v">{{ (metadata as any).source.identifier }}</span></div>
            <div v-if="(metadata as any).source?.status" class="kv">
              <span class="kv-k">Status</span>
              <Badge :color="(metadata as any).source.status === 'IMPORTED' ? '#34d99a' : '#ffb547'">{{ (metadata as any).source.status }}</Badge>
            </div>
          </div>
        </SectionCard>

        <!-- Status -->
        <SectionCard title="Status">
          <div class="sc-switches">
            <Switch v-model="publicFlag" label="Public" :accent="accent" />
            <Switch v-model="publicContent" label="Public Content" :accent="accent" />
            <Switch v-model="publicSupplementary" label="Public Supplementary" :accent="accent" />
            <Switch
              v-model="locked"
              label="Locked"
              :accent="accent"
              @update:model-value="onLockChanged" />
            <Switch
              v-model="searchable"
              label="Searchable"
              :accent="accent"
              @update:model-value="onSearchableChanged" />
            <Switch
              v-model="recommendable"
              label="Recommendable"
              :accent="accent"
              @update:model-value="onRecommendableChanged" />
            <Switch v-model="syncVariantCollections" label="Sync Variant Collections" :accent="accent" />
            <Switch v-model="syncVariantRelationships" label="Sync Variant Relationships" :accent="accent" />
          </div>
        </SectionCard>

        <!-- Comments (only when the deployment has the comments feature enabled) -->
        <SectionCard v-if="commentsEnabled" title="Comments">
          <div class="sc-switches">
            <Switch
              v-model="itemCommentsEnabled"
              label="Enable Comments"
              :accent="accent"
              @update:model-value="onCommentsEnabledChanged" />
            <Switch
              v-if="itemCommentsEnabled"
              v-model="itemCommentRepliesEnabled"
              label="Allow Replies"
              :accent="accent"
              @update:model-value="onCommentRepliesEnabledChanged" />
          </div>
          <div class="sc-pad comments-foot">
            <p class="comments-note">
              {{ itemCommentsEnabled
                ? 'End users can comment on this content. Moderators can always comment.'
                : 'Comments are off — only moderators can post.' }}
            </p>
            <Button
              v-if="canModerate"
              size="sm"
              icon="message-square"
              @click="navigateTo(`/cms/comments/${route.params.id}`)">Moderate</Button>
          </div>
        </SectionCard>

        <!-- Categories -->
        <SectionCard v-if="metadata.categories?.length" title="Categories">
          <div class="sc-tags"><Badge v-for="cat in metadata.categories" :key="cat.id" color="#5ec5ff">{{ cat.name }}</Badge></div>
        </SectionCard>

        <!-- Traits -->
        <SectionCard v-if="(metadata as any).traits?.length" title="Traits">
          <div class="sc-tags"><Badge v-for="trait in (metadata as any).traits" :key="trait.id" color="#a78bff">{{ trait.name }}</Badge></div>
        </SectionCard>

        <!-- Variants -->
        <SectionCard title="Variants">
          <template #right>
            <Button size="sm" icon="plus" @click="addVariantModalOpen = true">Add</Button>
          </template>
          <div class="sc-variants">
            <div
              v-for="v in otherVariants"
              :key="v.id"
              class="variant-row"
              @click="goToVariant(v.id)">
              <Icon name="languages" :size="13" color="var(--fg-3)" />
              <span class="variant-name">{{ v.name }}</span>
              <Badge color="var(--fg-4)">{{ v.languageTag }}</Badge>
            </div>
            <div v-if="!otherVariants.length" class="variant-empty">No other variants</div>
          </div>
        </SectionCard>

        <!-- Active jobs -->
        <SectionCard v-if="metadata.workflow?.activeJobs?.length" title="Active Jobs">
          <div class="kvs">
            <div v-for="job in metadata.workflow.activeJobs" :key="job.jobId" class="kv">
              <span class="kv-k">{{ job.displayName || job.jobName }}</span>
              <Badge :color="job.complete ? (job.success ? '#34d99a' : '#ff5d6c') : '#ffb547'">{{ job.status }}</Badge>
            </div>
          </div>
        </SectionCard>

        <!-- Workflow -->
        <SectionCard title="Workflow">
          <div class="sc-pad sc-workflow">
            <Select
              v-model="workflowState"
              :options="availableStates"
              placeholder="Select state…"
              size="sm"
              :accent="accent" />
            <div v-if="metadata.workflow?.stateValid" class="kv"><span class="kv-k">Valid</span><span class="kv-v">{{ formatDate(metadata.workflow.stateValid) }}</span></div>
            <div v-if="metadata.workflow?.pending" class="kv"><span class="kv-k">Pending</span><span class="kv-v">{{ metadata.workflow.pending }}</span></div>
            <div v-if="metadata.workflow?.running" class="kv"><span class="kv-k">Running</span><span class="kv-v">{{ metadata.workflow.running }}</span></div>
            <Button
              v-if="metadata.ready && !metadata.workflow?.pending"
              size="sm"
              icon="x"
              @click="onSetNotReady">Mark Not Ready</Button>
          </div>
        </SectionCard>

        <!-- Collaboration -->
        <SectionCard title="Collaboration">
          <div class="sc-pad"><Button size="sm" icon="trash" @click="clearCollaboration">Delete Collaboration Data</Button></div>
        </SectionCard>
      </div>
    </div>
  </PageShell>

  <!-- Timeline events editor -->
  <Modal
    v-if="timelineEditorOpen && metadata"
    title="Timeline Events"
    icon="clock"
    width="1200px"
    @close="timelineEditorOpen = false"
  >
    <ClientOnly>
      <LazyTimeEventsEditor :metadata="metadata" />
    </ClientOnly>
  </Modal>

  <!-- Slug modal -->
  <Modal
    v-if="slugModalOpen"
    title="Edit Slug"
    subtitle="URL-friendly identifier"
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
        :disabled="!slugValue || slugSaving || slugInputRef?.available === false"
        @click="onSaveSlug"
      >
        {{ slugSaving ? 'Saving…' : 'Save' }}
      </Button>
    </template>
  </Modal>

  <!-- Delete confirmation -->
  <ConfirmModal
    v-if="deleteTarget"
    :title="`Delete '${nameField}'?`"
    subtitle="This will soft-delete the metadata. It can be restored later."
    :loading="deleteLoading"
    @close="deleteTarget = false"
    @confirm="confirmDelete"
  />

  <!-- Add Language Variant -->
  <AddLanguageVariantModal
    v-if="addVariantModalOpen && metadata"
    :metadata-id="metadata.id"
    :metadata-version="(metadata as any).version ?? 1"
    :existing-languages="existingLanguages"
    @close="addVariantModalOpen = false"
    @created="onVariantCreated"
  />
</template>

<style scoped>
.mv-shell :deep(.page-content) { padding: 0; display: flex; flex-direction: column; }

.mv-body { flex: 1; display: flex; min-height: 0; overflow: hidden; padding: 14px; gap: 14px; }

.panel { background: var(--bg-1); border: 1px solid var(--line); border-radius: var(--r-md); display: flex; flex-direction: column; overflow: hidden; }
.panel--main { flex: 1; min-width: 0; }
.panel--side { width: 340px; flex: 0 0 340px; background: none; border: none; border-radius: 0; overflow-y: auto; gap: 12px; }
.panel-scroll { flex: 1; overflow-y: auto; padding: 22px 26px; }

.section-head { font-size: 10.5px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.08em; color: var(--fg-3); margin-bottom: 14px; }
.hr { height: 1px; background: color-mix(in oklch, var(--line) 42%, transparent); margin: 18px 0; }

.form-fields { display: flex; flex-direction: column; gap: 14px; }
.field { display: flex; flex-direction: column; gap: 5px; }
.field-label { font-size: 12px; font-weight: 550; color: var(--fg-2); }
.field-hint { font-weight: 400; color: var(--fg-4); }
.field-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.slug-row { display: flex; align-items: center; gap: 8px; height: 32px; cursor: pointer; border-radius: var(--r-xs); padding: 0 6px; margin: 0 -6px; transition: background 0.12s; }
.slug-row:hover { background: var(--bg-3); }
.slug-edit-icon { opacity: 0; transition: opacity 0.12s; flex-shrink: 0; margin-left: auto; }
.slug-row:hover .slug-edit-icon { opacity: 1; }
.slug-value { font-size: 13px; color: var(--fg-2); }

.tab-row { display: flex; gap: 0; border-bottom: 1px solid color-mix(in oklch, var(--line) 42%, transparent); margin-bottom: 2px; }
.tab-btn { padding: 8px 14px; font-size: 12.5px; font-weight: 550; color: var(--fg-3); background: none; border: none; border-bottom: 2px solid transparent; cursor: pointer; margin-bottom: -1px; transition: color 0.15s, border-color 0.15s; }
.tab-btn:hover { color: var(--fg-1); }
.tab-btn.active { color: var(--fg-0); }
.tab-body { padding-top: 14px; min-height: 120px; }
.empty-msg { padding: 32px; text-align: center; color: var(--fg-4); font-size: 13px; }
.tab-count { font-size: 12px; color: var(--fg-3); font-weight: 500; }
.perm-group { font-size: 12px; color: var(--fg-1); flex: 1; }
.perm-form { display: flex; flex-direction: column; gap: 14px; }

.add-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }

.list-items { display: flex; flex-direction: column; }
.list-row { display: flex; align-items: center; gap: 10px; padding: 8px 6px; border-radius: var(--r-xs); transition: background 0.12s; }
.list-row:hover { background: color-mix(in oklch, var(--brand-2) 5%, transparent); }
.list-text { flex: 1; min-width: 0; }
.list-name { font-size: 13px; font-weight: 500; color: var(--fg-0); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.list-sub { font-size: 11px; color: var(--fg-4); margin-top: 1px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.rm-btn { width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; border-radius: var(--r-xs); background: none; border: none; cursor: pointer; opacity: 0; transition: opacity 0.12s, background 0.12s; flex-shrink: 0; }
.list-row:hover .rm-btn { opacity: 1; }
.rm-btn:hover { background: var(--bg-3); }

.rel-drag-handle { width: 22px; height: 24px; display: flex; align-items: center; justify-content: center; background: none; border: none; cursor: grab; opacity: 0; transition: opacity 0.12s; flex-shrink: 0; }
.list-row:hover .rel-drag-handle { opacity: 1; }
.rel-drag-handle:active { cursor: grabbing; }
.list-text--link { cursor: pointer; }
.list-text--link:hover .list-name { color: var(--brand-2); }

.raw-fallback { display: flex; flex-direction: column; gap: 8px; }
.raw-label { font-size: 12px; font-weight: 550; color: var(--fg-2); }
.json-editor { border-radius: var(--r-sm); min-height: 200px; }

.sc-pad { padding: 10px 16px 14px; }
.content-file-input { display: none; }
.content-controls { display: flex; flex-direction: column; gap: 8px; }
.content-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.content-hint { margin: 0; color: var(--fg-3); font-size: 11.5px; line-height: 1.5; }
.sc-switches { padding: 10px 16px 14px; display: flex; flex-direction: column; gap: 9px; }
.sc-tags { padding: 10px 16px 14px; display: flex; flex-wrap: wrap; gap: 5px; }
.sc-workflow { display: flex; flex-direction: column; gap: 8px; }
.sc-variants { padding: 6px 10px 10px; }

.kvs { padding: 8px 16px 12px; }
.kv { display: flex; justify-content: space-between; align-items: center; padding: 4px 0; font-size: 12.5px; gap: 8px; }
.kv-k { color: var(--fg-3); flex-shrink: 0; }
.kv-v { color: var(--fg-1); font-size: 12px; text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }

.variant-row { display: flex; align-items: center; gap: 8px; padding: 6px 6px; border-radius: var(--r-xs); cursor: pointer; font-size: 12.5px; color: var(--fg-2); transition: background 0.1s; }
.variant-row:hover { background: var(--bg-3); }
.variant-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.variant-empty { font-size: 12px; color: var(--fg-4); padding: 4px 6px; }

.img-preview { width: 100%; border-radius: var(--r-sm); }

.comments-foot { display: flex; flex-direction: column; gap: 10px; }
.comments-note { font-size: 12px; color: var(--fg-3); margin: 0; line-height: 1.4; }

.media-controls { display: flex; flex-direction: column; gap: 12px; }
.media-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.media-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.media-cap { text-transform: capitalize; }

.loading-state { flex: 1; display: flex; align-items: center; justify-content: center; color: var(--fg-3); font-size: 13px; }
.slug-error { font-size: 12px; color: var(--err); margin: 8px 0 0; }
.spacer { flex: 1; }
</style>
