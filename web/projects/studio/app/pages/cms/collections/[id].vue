<script setup lang="ts">
/* eslint-disable @typescript-eslint/no-explicit-any */
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import Sortable from 'sortablejs'
import type { GlassTableColumn, OverflowMenuItem, SelectOption } from '@bosca/ui'
import type { OrderingInput, Profile, Collection as GqlCollection } from '~/types/graphql'
import { AttributeLocation, AttributeType, Order } from '~/types/graphql'
import { applyAttributes } from '~/utils/editor/attributes'
import {
  ORDERING_PRESET_HINTS,
  ORDERING_PRESET_OPTIONS,
  blankOrderingRule,
  matchOrderingPreset,
  orderingPresetRule,
} from '~/utils/collectionOrderingPresets'
import { getWorkflowBadge, getWorkflowState } from '~/utils/workflowStatus'

definePageMeta({ key: route => route.fullPath })

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation, query: gqlQuery } = useGraphQL()
const toast = useToast()

const collectionId = computed(() => route.params.id as string)
// Variant addressing mirrors the legacy admin: `?languageTag=es` binds the
// editor to that language variant of the same collection.
const variantTagParam = computed(() => (route.query.languageTag as string) || null)

// ── Queries ──────────────────────────────────────────────────────────────────
const collectionGql = gql`
  query GetCollection($id: UUID!, $languageTag: String) {
    profiles {
      current { id name }
    }
    content {
      collections {
        templates {
          all {
            metadata { id version name }
          }
        }
        collection(id: $id) {
          __typename
          id
          slug
          name
          languageTag
          languageVariant(languageTag: $languageTag) {
            languageTag
            name
            slug
            description
            attributes
            public
            publicList
            publicSupplementary
            searchable
            recommendable
            ready
            workflow {
              state stateValid pending running
              activeJobs { jobName displayName jobId status created complete success delayedUntil }
            }
            metadataRelationships {
              __typename
              metadata {
                id name type slug attributes
                content { type }
              }
              relationship
              attributes
            }
          }
          attributes
          public
          publicList
          publicSupplementary
          searchable
          recommendable
          ready
          locked
          type
          created
          modified
          itemsCount
          labels
          categories { id name }
          traits { id name }
          ordering { field location order path type }
          templateMetadata {
            id
            version
            collectionTemplate {
              attributes {
                key
                name
                description
                type
                configuration
                ui
                list
                location
                supplementaryKey
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
          parentCollections(limit: 100, offset: 0) {
            __typename
            id name attributes
            workflow { state pending }
          }
          metadataRelationships {
            __typename
            metadata {
              id name type slug attributes
              content { type }
            }
            relationship
            attributes
          }
          languageVariants
          permissions {
            action
            group { id name }
          }
          workflow {
            state stateValid pending running
            activeJobs { jobName displayName jobId status created complete success delayedUntil }
          }
        }
      }
      states {
        all { id name description }
      }
    }
  }
`

const itemsGql = gql`
  query GetCollectionItems($id: UUID!, $limit: Int!, $offset: Long!) {
    content {
      collections {
        collection(id: $id) {
          itemsCount
          items(limit: $limit, offset: $offset) {
            ... on Collection {
              __typename id name slug modified attributes itemAttributes
              workflow { state pending }
              metadataRelationships {
                relationship
                attributes
                metadata { id slug attributes }
              }
            }
            ... on Metadata {
              __typename id name slug modified attributes itemAttributes
              content { type }
              workflow { state pending }
              relationships {
                relationship
                attributes
                metadata { id slug attributes }
              }
            }
          }
        }
      }
    }
  }
`

// ── Mutations ────────────────────────────────────────────────────────────────
const saveCollectionGql = gql`
  mutation SaveCollection($id: UUID!, $input: CollectionInput!) {
    content { collection { edit(id: $id, collection: $input) { id } } }
  }
`

const setReadyGql = gql`
  mutation SetCollectionReady($id: UUID!, $languageTag: String) {
    content { collection { setReady(id: $id, languageTag: $languageTag) } }
  }
`

const setNotReadyGql = gql`
  mutation SetCollectionNotReady($id: UUID!, $languageTag: String) {
    content { collection { setNotReady(id: $id, languageTag: $languageTag) } }
  }
`

const setPublicGql = gql`
  mutation SetCollectionPublic($id: UUID!, $public: Boolean!, $languageTag: String) {
    content { collection { setPublic(id: $id, public: $public, languageTag: $languageTag) { id } } }
  }
`

const setPublicListGql = gql`
  mutation SetCollectionPublicList($id: UUID!, $public: Boolean!, $languageTag: String) {
    content { collection { setPublicList(id: $id, public: $public, languageTag: $languageTag) { id } } }
  }
`

const setPublicSupplementaryGql = gql`
  mutation SetCollectionPublicSupplementary($id: UUID!, $public: Boolean!, $languageTag: String) {
    content { collection { setPublicSupplementary(id: $id, public: $public, languageTag: $languageTag) { id } } }
  }
`

const addLanguageVariantGql = gql`
  mutation AddCollectionLanguageVariant($variant: CollectionLanguageVariantInput!, $setReady: Boolean) {
    content { collection { addLanguageVariant(variant: $variant, setReady: $setReady) { id } } }
  }
`

const setLockedGql = gql`
  mutation SetCollectionLocked($id: UUID!, $locked: Boolean!) {
    content { collection { setLocked(id: $id, locked: $locked) { id } } }
  }
`

const setSearchableGql = gql`
  mutation SetCollectionSearchable($id: UUID!, $searchable: Boolean!, $languageTag: String) {
    content { collection { setCollectionSearchable(id: $id, searchable: $searchable, languageTag: $languageTag) } }
  }
`

const setRecommendableGql = gql`
  mutation SetCollectionRecommendable($id: UUID!, $recommendable: Boolean!, $languageTag: String) {
    content { collection { setCollectionRecommendable(id: $id, recommendable: $recommendable, languageTag: $languageTag) } }
  }
`

const setTemplateGql = gql`
  mutation SetCollectionTemplate($id: UUID!, $metadataId: UUID!, $metadataVersion: Int!) {
    content { collection { setTemplate(collectionId: $id, templateId: $metadataId, templateVersion: $metadataVersion) } }
  }
`

const setSlugGql = gql`
  mutation SetCollectionSlug($id: UUID!, $slug: String!, $languageTag: String) {
    content { collection { setCollectionSlug(id: $id, slug: $slug, languageTag: $languageTag) } }
  }
`

const deleteCollectionGql = gql`
  mutation DeleteCollection($id: UUID!) {
    content { collection { delete(id: $id) } }
  }
`

const beginTransitionGql = gql`
  mutation BeginCollectionTransition($id: UUID!, $state: String!, $status: String!, $languageTag: String) {
    content { transitions { beginTransition(request: { collectionId: $id, stateId: $state, status: $status, languageTag: $languageTag }) } }
  }
`

const cancelTransitionGql = gql`
  mutation CancelCollectionTransition($id: UUID!, $languageTag: String) {
    content { transitions { cancelTransition(collectionId: $id, languageTag: $languageTag) } }
  }
`

const syncVariantsGql = gql`
  mutation SyncCollectionVariants($id: UUID!) {
    content { collection { syncVariants(collectionId: $id) } }
  }
`

const addMetadataItemGql = gql`
  mutation AddMetadataItem($id: UUID!, $child: UUID!, $attributes: JSON!) {
    content { collection { addChildMetadata(id: $id, metadataId: $child, attributes: $attributes) { id } } }
  }
`

const removeMetadataItemGql = gql`
  mutation RemoveMetadataItem($id: UUID!, $parentId: UUID!) {
    content { collection { removeChildMetadata(id: $parentId, metadataId: $id) { id } } }
  }
`

const addCollectionItemGql = gql`
  mutation AddCollectionItem($id: UUID!, $child: UUID!, $attributes: JSON!) {
    content { collection { addChildCollection(id: $id, collectionId: $child, attributes: $attributes) { id } } }
  }
`

const removeCollectionItemGql = gql`
  mutation RemoveCollectionItem($id: UUID!, $parentId: UUID!) {
    content { collection { removeChildCollection(id: $parentId, collectionId: $id) { id } } }
  }
`

const addPermissionGql = gql`
  mutation AddCollectionPermission($perm: PermissionInput!) {
    content { collection { addPermission(permission: $perm) { action group { id name } } } }
  }
`

const deletePermissionGql = gql`
  mutation DeleteCollectionPermission($perm: PermissionInput!) {
    content { collection { deletePermission(permission: $perm) { action group { id name } } } }
  }
`

const groupsGql = gql`
  query GetGroups($limit: Int!, $offset: Long!) {
    security { groups { all(limit: $limit, offset: $offset) { id name } } }
  }
`

const findGroupsGql = gql`
  query FindGroups($q: String!, $limit: Int!, $offset: Long!) {
    security { groups { find(nameOrDescription: $q, limit: $limit, offset: $offset) { id name } } }
  }
`

const slugAvailableGql = gql`
  query CheckCollectionSlug($slug: String!) {
    content { slugAvailable(slug: $slug) }
  }
`

const addParentCollectionGql = gql`
  mutation AddParentCollection($id: UUID!, $parentId: UUID!, $attributes: JSON!) {
    content { collection { addChildCollection(id: $parentId, collectionId: $id, attributes: $attributes) { id } } }
  }
`

const removeParentCollectionGql = gql`
  mutation RemoveParentCollection($id: UUID!, $parentId: UUID!) {
    content { collection { removeChildCollection(id: $parentId, collectionId: $id) { id } } }
  }
`

const addCollectionMetadataRelationshipGql = gql`
  mutation AddCollectionMetadataRelationship($relationship: CollectionMetadataRelationshipInput!) {
    content {
      collection {
        addMetadataRelationship(relationship: $relationship) { __typename }
      }
    }
  }
`

const deleteCollectionMetadataRelationshipGql = gql`
  mutation DeleteCollectionMetadataRelationship($id: UUID!, $metadataId: UUID!, $relationship: String!, $languageTag: String) {
    content { collection { deleteMetadataRelationship(id: $id, metadataId: $metadataId, relationship: $relationship, languageTag: $languageTag) } }
  }
`

const editCollectionMetadataRelationshipGql = gql`
  mutation EditCollectionMetadataRelationship($relationship: CollectionMetadataRelationshipInput!) {
    content { collection { editMetadataRelationship(relationship: $relationship) } }
  }
`

const setCollectionMetadataRelationshipsGql = gql`
  mutation SetCollectionMetadataRelationships($id: UUID!, $relationships: [CollectionMetadataRelationshipInput!]!, $languageTag: String) {
    content { collection { setMetadataRelationships(id: $id, relationships: $relationships, languageTag: $languageTag) } }
  }
`

const setChildItemAttributesGql = gql`
  mutation SetChildItemAttributes($id: UUID!, $childCollectionId: UUID, $childMetadataId: UUID, $attributes: JSON) {
    content { collection { setChildItemAttributes(id: $id, childCollectionId: $childCollectionId, childMetadataId: $childMetadataId, attributes: $attributes) { id } } }
  }
`

const cancelJobGql = gql`
  mutation CancelCollectionJob($jobId: UUID!) {
    jobs { cancel(jobId: $jobId) }
  }
`

const searchEntitiesGql = gql`
  query SearchCollectionSidebarEntities($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
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
          metadata { id name content { type } }
        }
      }
    }
  }
`

// ── Types ────────────────────────────────────────────────────────────────────
interface ItemRelationship {
  relationship: string
  attributes: Record<string, unknown> | null
  metadata: { id: string; slug: string | null; attributes: Record<string, unknown> | null } | null
}

interface CollectionItem {
  __typename: string
  id: string
  name: string
  slug: string | null
  modified: string | null
  attributes: Record<string, unknown> | null
  itemAttributes: Record<string, unknown> | null
  content?: { type: string }
  workflow: { state: string; pending: string | null } | null
  relationships?: ItemRelationship[]
  metadataRelationships?: ItemRelationship[]
}

interface CollectionPermission {
  action: string
  group: { id: string; name: string }
}

interface CollectionVariant {
  languageTag: string
  name: string
  slug: string | null
  description: string | null
  attributes: Record<string, unknown> | null
  public: boolean
  publicList: boolean
  publicSupplementary: boolean
  searchable: boolean
  recommendable: boolean
  ready: string | null
  workflow: Collection['workflow']
  metadataRelationships: Collection['metadataRelationships']
}

interface Collection {
  __typename?: string
  id: string
  slug: string
  name: string
  languageTag: string
  languageVariant: CollectionVariant | null
  attributes: Record<string, unknown> | null
  public: boolean
  publicList: boolean
  publicSupplementary: boolean
  searchable: boolean
  recommendable: boolean
  ready: boolean
  locked: boolean
  type: string
  created: string
  modified: string
  itemsCount: number
  labels: string[]
  categories: Array<{ id: string; name: string }>
  traits: Array<{ id: string; name: string }>
  ordering: Array<OrderingInput & { __typename?: string }> | null
  parentCollections: Array<{ id: string; name: string; workflow?: { state: string; pending: string | null } }>
  metadataRelationships: Array<{ metadata: { id: string; name: string; type: string; content?: { type: string } }; relationship: string; attributes: unknown }>
  languageVariants: string[]
  templateMetadata: { id: string; version: number; collectionTemplate: Record<string, unknown> | null } | null
  permissions: CollectionPermission[]
  workflow: {
    state: string
    stateValid: string | null
    pending: string | null
    // Schema is Int (count of running steps). See useWorkflowValidation.
    running: number
    activeJobs: Array<Record<string, unknown>>
  } | null
}

// ── Data fetching ────────────────────────────────────────────────────────────
// Awaited (like the metadata/data pages) so `collection` is populated before
// useCollaborationAndAttributes runs. Without this, client-side navigation
// constructs the composable with a null collection, so its template attributes
// never initialize and the attribute editors render empty until a hard refresh.
const { data, status, refresh } = await useAsyncQuery<{
  profiles: { current: Profile }
  content: {
    collections: {
      collection: Collection | null
      templates: { all: Array<{ metadata: { id: string; version: number; name: string } | null }> }
    }
    states: { all: Array<{ id: string; name: string; description: string }> }
  }
}>(`collection-detail-${collectionId.value}`, collectionGql, { id: collectionId, languageTag: variantTagParam })

const collection = computed(() => data.value?.content?.collections?.collection ?? null)

// When bound, the editor edits this language variant's name, visibility and
// workflow; items, ordering, template and permissions stay collection-level.
const variant = computed(() => collection.value?.languageVariant ?? null)
const variantTag = computed(() => variant.value?.languageTag ?? null)
const profile = computed<Profile>(() => data.value?.profiles?.current ?? { id: '', name: 'Unknown' } as Profile)

const collab = useCollaborationAndAttributes(
  collection as Ref<GqlCollection | null>,
  profile,
)
const ydoc = collab.ydoc
const ydocReady = collab.ready
const attributeStates = collab.attributes
const rawAttributes = collab.rawAttributes
const uploader = useUploader()
// The sidebar attribute editors snapshot the attribute map when they mount, so
// a template change (a different set of fields) needs a remount to show the
// new fields. Bumped by onSave when the template actually changed.
const attributesEditorKey = ref(0)

const itemsOffset = ref(0)
const itemsLimit = ref(25)

const { data: itemsData, status: itemsStatus, refresh: refreshItems } = useAsyncQuery<{
  content: { collections: { collection: { itemsCount: number; items: CollectionItem[] } | null } }
}>(`collection-items-${collectionId.value}`, itemsGql, { id: collectionId, limit: itemsLimit, offset: itemsOffset })

// Local mirror of the server's items. The server is authoritative for order
// (it owns the collection's `ordering` rules); this list exists only to carry
// transient UI state — the optimistic position of a dragged row — while the
// reorder is synced. Every server response replaces it wholesale, so the
// server's order always wins. The table renders from this list so SortableJS's
// DOM move stays in sync with Vue (a read-only computed left Vue's state behind
// the node SortableJS physically moved, so dropped rows snapped back instead of
// sorting). Mirrors the legacy admin's collection/Items.vue.
const items = ref<CollectionItem[]>([])
watch(itemsData, (val) => {
  items.value = [...(val?.content?.collections?.collection?.items ?? [])]
}, { immediate: true })
const totalItems = computed(() => itemsData.value?.content?.collections?.collection?.itemsCount ?? 0)
const totalPages = computed(() => Math.ceil(totalItems.value / itemsLimit.value))
const currentPage = computed(() => Math.floor(itemsOffset.value / itemsLimit.value) + 1)

const initialLoadDone = ref(status.value === 'success')
watch(status, (s) => { if (s === 'success') initialLoadDone.value = true })
const isLoading = computed(() => !initialLoadDone.value && status.value === 'pending')
const itemsLoading = computed(() => itemsStatus.value === 'pending')

// ── Editable fields ──────────────────────────────────────────────────────────
const name = ref('')
const labelsField = ref('')
const selectedTemplate = ref('')
const publicFlag = ref(false)
const publicList = ref(false)
const publicSupplementary = ref(false)
const locked = ref(false)
const searchable = ref(false)
const recommendable = ref(false)
const workflowState = ref('')
const ordering = ref<OrderingInput[]>([])
const saving = ref(false)
const deleting = ref(false)
const publishing = ref(false)
const deleteModalOpen = ref(false)
const addItemModalOpen = ref(false)
const activeTab = ref('Items')

watch(collection, (c) => {
  if (!c) return
  const v = c.languageVariant
  name.value = v ? v.name : c.name
  labelsField.value = c.labels?.join(', ') || ''
  selectedTemplate.value = c.templateMetadata?.id || ''
  publicFlag.value = v ? v.public : c.public
  publicList.value = v ? v.publicList : c.publicList
  publicSupplementary.value = v ? v.publicSupplementary : c.publicSupplementary
  locked.value = c.locked
  searchable.value = v ? v.searchable : c.searchable
  recommendable.value = v ? v.recommendable : (c.recommendable ?? true)
  workflowState.value = (v ? v.workflow?.state : c.workflow?.state) || ''
  ordering.value = (c.ordering ?? []).map((o) => {
    const { __typename: _t, ...rest } = o
    return {
      field: rest.field ?? null,
      location: rest.location ?? AttributeLocation.Item,
      order: rest.order ?? Order.Ascending,
      path: rest.path ? [...rest.path] : null,
      type: rest.type ?? AttributeType.String,
    }
  })
  rawAttributes.value = c.attributes ?? {}
}, { immediate: true })

// ── Ordering rules ──────────────────────────────────────────────────────────
const locationOptions: SelectOption[] = [
  { value: AttributeLocation.Item, label: 'Item' },
  { value: AttributeLocation.Relationship, label: 'Relationship' },
]
const orderOptions: SelectOption[] = [
  { value: Order.Ascending, label: 'Ascending' },
  { value: Order.Descending, label: 'Descending' },
]
const typeOptions: SelectOption[] = Object.values(AttributeType).map(t => ({ value: t, label: t }))

function isRuleValid(rule: OrderingInput): boolean {
  const hasField = !!rule.field && rule.field.trim().length > 0
  const hasPath = !!rule.path && rule.path.length > 0 && rule.path.some(p => p && p.trim().length > 0)
  return hasField || hasPath
}

function addOrderingRule() {
  ordering.value.push(blankOrderingRule())
}

// The "Sort items by" dropdown is the simple front door for content editors:
// a preset writes its one rule, Custom opens a blank rule with every field
// exposed. The value is derived from the rules (not stored) so hand-edits in
// the rule settings — or a rule set that never matched a preset — read back
// as Custom instead of a preset the rules no longer match.
const orderingPresetOptions = ORDERING_PRESET_OPTIONS
const orderingPreset = computed(() => matchOrderingPreset(ordering.value))
const orderingPresetHint = computed(() => orderingPreset.value ? ORDERING_PRESET_HINTS[orderingPreset.value] : '')
// Presets keep their rule fields tucked behind the settings button; Custom
// always shows them because the fields *are* the configuration.
const showOrderingDetails = ref(false)
const showOrderingRules = computed(() => orderingPreset.value === 'custom' || showOrderingDetails.value)

function onOrderingPresetChange(value: string | string[] | null | undefined) {
  if (value === 'custom') {
    ordering.value = [blankOrderingRule()]
  } else if (value === 'newest' || value === 'oldest' || value === 'manual') {
    ordering.value = [orderingPresetRule(value)]
    // A fresh preset choice starts with a clean view; the settings button
    // re-opens the rule fields on demand.
    showOrderingDetails.value = false
  }
}

function removeOrderingRule(index: number) {
  ordering.value.splice(index, 1)
}

function onRuleFieldChange(rule: OrderingInput, value: string) {
  rule.field = value
  if (value) rule.path = null
}

function addPathSegment(rule: OrderingInput) {
  if (!rule.path) rule.path = []
  rule.path.push('')
  rule.field = null
}

function removePathSegment(rule: OrderingInput, index: number) {
  if (!rule.path) return
  rule.path.splice(index, 1)
  if (rule.path.length === 0) rule.path = null
}

const hasInvalidRules = computed(() => ordering.value.some(r => !isRuleValid(r)))

const orderingListEl = ref<HTMLElement | null>(null)
let orderingSortable: Sortable | null = null

watch([orderingListEl, () => activeTab.value], ([el, tab]) => {
  if (orderingSortable) {
    orderingSortable.destroy()
    orderingSortable = null
  }
  if (tab !== 'Ordering' || !el) return
  orderingSortable = Sortable.create(el, {
    handle: '.ordering-drag-handle',
    animation: 150,
    ghostClass: 'ordering-rule--ghost',
    onEnd: (evt) => {
      if (evt.oldIndex === undefined || evt.newIndex === undefined) return
      if (evt.oldIndex === evt.newIndex) return
      const [moved] = ordering.value.splice(evt.oldIndex, 1)
      if (moved) ordering.value.splice(evt.newIndex, 0, moved)
    },
  })
})

onBeforeUnmount(() => {
  orderingSortable?.destroy()
  orderingSortable = null
})

// ── Template options ─────────────────────────────────────────────────────────
const templateOptions = computed<SelectOption[]>(() => {
  const all = data.value?.content?.collections?.templates?.all ?? []
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

const workflow = computed(() => variant.value?.workflow ?? collection.value?.workflow ?? null)
// Variant `ready` is a timestamp; the base collection's is a boolean.
const isReady = computed(() => variant.value ? !!variant.value.ready : !!collection.value?.ready)
const wfState = computed(() => getWorkflowState(workflow.value))
const wfBadge = computed(() => getWorkflowBadge(wfState.value))
const { canPublish, validateBeforeTransition } = useWorkflowValidation(workflow)

// ── Tabs ─────────────────────────────────────────────────────────────────────
// Template attributes are edited in the sidebar (see the template), so there is
// no Attributes tab; Settings holds the less-frequently-touched options.
const tabItems = computed(() => ['Items', 'Parents', 'Ordering', 'Relationships', 'Permissions', 'Settings'])

// ── Items table ──────────────────────────────────────────────────────────────
const isManualOrdering = computed(() => {
  const rules = collection.value?.ordering
  if (!rules || rules.length === 0) return false
  return rules.some((r) => {
    const loc = (r.location ?? '').toString().toUpperCase()
    const path = r.path ?? []
    return loc === 'RELATIONSHIP' && path.includes('sort')
  })
})

const itemsTableEl = ref<HTMLElement | null>(null)
let itemsSortable: Sortable | null = null
const isReorderingItems = ref(false)

async function applyItemReorder(oldIndex: number, newIndex: number) {
  if (oldIndex === newIndex) return
  const list = items.value
  const moved = list[oldIndex]
  if (!moved) return

  // Baseline taken from the current first row *before* the move, so the
  // renumbered values stay contiguous with what's already stored (matches the
  // legacy admin). These sort values are only a hint to the server — it
  // re-applies the collection's ordering rules on refresh, and that result is
  // what ultimately renders.
  const start = Math.max(Number(list[0]?.itemAttributes?.sort ?? 0), 0)

  // Optimistically reflect the drop in the reactive mirror so Vue's view matches
  // the row SortableJS just moved; refreshItems() below reconciles with the
  // server's authoritative order.
  list.splice(oldIndex, 1)
  list.splice(newIndex, 0, moved)

  try {
    let position = 0
    for (const item of list) {
      const newSort = start + position
      position++
      const oldSort = Number(item.itemAttributes?.sort ?? NaN)
      if (newSort === oldSort) continue
      await gqlMutation(setChildItemAttributesGql, {
        id: collectionId.value,
        childCollectionId: item.__typename === 'Collection' ? item.id : null,
        childMetadataId: item.__typename === 'Metadata' ? item.id : null,
        attributes: { ...(item.itemAttributes ?? {}), sort: newSort },
      })
    }
    await refreshItems()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to reorder items')
    await refreshItems()
  }
}

watch([itemsTableEl, isManualOrdering, () => activeTab.value, items], () => {
  if (itemsSortable) {
    itemsSortable.destroy()
    itemsSortable = null
  }
  if (activeTab.value !== 'Items' || !isManualOrdering.value) return
  const host = itemsTableEl.value?.querySelector('.glass-table') as HTMLElement | null
  if (!host) return
  itemsSortable = Sortable.create(host, {
    draggable: '.gt-row',
    handle: '.items-drag-handle',
    animation: 150,
    ghostClass: 'gt-row--ghost',
    onStart: () => { isReorderingItems.value = true },
    onEnd: async (evt) => {
      isReorderingItems.value = false
      // Sortable is bound to `.glass-table`, whose first child is the
      // non-draggable `.gt-header` — so evt.oldIndex/newIndex are offset by it
      // and would point at the wrong rows, scrambling every item's sort. The
      // draggable-relative indices count only `.gt-row`s, mapping straight onto
      // the items array. (The legacy admin binds Sortable to a rows-only
      // `<tbody>`, so its plain oldIndex already lines up; this is the
      // equivalent for our header-in-container layout.)
      const oldIndex = evt.oldDraggableIndex
      const newIndex = evt.newDraggableIndex
      if (oldIndex === undefined || newIndex === undefined) return
      await applyItemReorder(oldIndex, newIndex)
    },
  })
}, { flush: 'post' })

onBeforeUnmount(() => {
  itemsSortable?.destroy()
  itemsSortable = null
})

function getItemStatus(item: CollectionItem): { label: string; color: string } {
  const state = item.workflow?.pending ?? item.workflow?.state ?? 'draft'
  return getWorkflowBadge(state)
}

function getItemType(item: CollectionItem): string {
  const attrType = (item.attributes as Record<string, unknown> | null)?.type
  if (typeof attrType === 'string' && attrType.length > 0) return attrType
  if (item.__typename === 'Collection') return 'Collection'
  return 'Unknown'
}

const itemColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '120px', muted: true },
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'modified', label: 'Modified', width: '100px', muted: true },
]

function getRowActions(row: unknown): OverflowMenuItem[] {
  const item = row as CollectionItem
  const actions: OverflowMenuItem[] = [
    { id: 'open', label: 'Open', icon: 'eye' },
  ]
  if (item.__typename === 'Metadata') {
    actions.push({ id: 'view-metadata', label: 'View metadata', icon: 'database' })
  }
  actions.push(
    { id: 'edit-attrs', label: 'Edit item attributes', icon: 'pencil' },
    { id: 'copy', label: 'Copy ID', icon: 'copy' },
    { id: 'sep', label: '', separator: true },
    { id: 'remove', label: 'Remove from collection', icon: 'trash', danger: true },
  )
  return actions
}

function onItemClick(row: unknown) {
  const item = row as CollectionItem
  if (item.__typename === 'Collection') {
    router.push(`/cms/collections/${item.id}`)
  } else {
    router.push(getEditorRoute(item.id, item.content?.type))
  }
}

// ── Item attributes editor ──────────────────────────────────────────────────
const itemAttrsModalOpen = ref(false)
const itemAttrsTarget = ref<{ id: string; name: string; typename: string } | null>(null)
const itemAttrsText = ref('')
const itemAttrsError = ref('')
const itemAttrsSaving = ref(false)

function openItemAttrsModal(item: CollectionItem) {
  itemAttrsTarget.value = { id: item.id, name: item.name, typename: item.__typename }
  itemAttrsText.value = JSON.stringify(item.itemAttributes ?? {}, null, 2)
  itemAttrsError.value = ''
  itemAttrsSaving.value = false
  itemAttrsModalOpen.value = true
}

async function onSaveItemAttrs() {
  const t = itemAttrsTarget.value
  if (!t || itemAttrsSaving.value) return
  let parsed: unknown
  try {
    parsed = itemAttrsText.value.trim() === '' ? {} : JSON.parse(itemAttrsText.value)
  } catch (e: any) {
    itemAttrsError.value = e?.message || 'Invalid JSON'
    return
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    itemAttrsError.value = 'Attributes must be a JSON object.'
    return
  }
  itemAttrsSaving.value = true
  itemAttrsError.value = ''
  try {
    await gqlMutation(setChildItemAttributesGql, {
      id: collectionId.value,
      childCollectionId: t.typename === 'Collection' ? t.id : null,
      childMetadataId: t.typename === 'Metadata' ? t.id : null,
      attributes: parsed,
    })
    itemAttrsModalOpen.value = false
    itemAttrsTarget.value = null
    await refreshItems()
    toast.success('Item attributes updated')
  } catch (e: any) {
    itemAttrsError.value = e?.message || 'Failed to update item attributes'
  } finally {
    itemAttrsSaving.value = false
  }
}

async function onRowAction(action: string, row: unknown) {
  const item = row as CollectionItem
  if (action === 'open') {
    onItemClick(row)
  } else if (action === 'view-metadata' && item.__typename === 'Metadata') {
    router.push(`/cms/metadata/${item.id}`)
  } else if (action === 'edit-attrs') {
    openItemAttrsModal(item)
  } else if (action === 'copy') {
    await navigator.clipboard.writeText(item.id)
    toast.success('ID copied')
  } else if (action === 'remove') {
    try {
      if (item.__typename === 'Collection') {
        await gqlMutation(removeCollectionItemGql, { id: item.id, parentId: collectionId.value })
      } else {
        await gqlMutation(removeMetadataItemGql, { id: item.id, parentId: collectionId.value })
      }
      toast.success('Item removed')
      refreshItems()
    } catch {
      toast.error('Failed to remove item')
    }
  }
}

async function onAddSearchItem(item: { id: string; name: string; type: string; typename: string }) {
  try {
    if (item.typename === 'Collection') {
      await gqlMutation(addCollectionItemGql, { id: collectionId.value, child: item.id, attributes: {} })
    } else {
      await gqlMutation(addMetadataItemGql, { id: collectionId.value, child: item.id, attributes: {} })
    }
    toast.success(`Added "${item.name}"`)
    refreshItems()
  } catch {
    toast.error('Failed to add item')
  }
}

// ── Parent collections (editable) ───────────────────────────────────────────
async function searchCollections(q: string): Promise<SelectOption[]> {
  const result = await gqlQuery<any>(searchEntitiesGql, {
    query: q,
    filter: '_type = "collection"',
    limit: 20,
    offset: 0,
  })
  return (result?.search?.search?.documents ?? [])
    .filter((d: any) => d.collection != null && d.collection.id !== collectionId.value)
    .map((d: any) => ({
      value: d.collection.id,
      label: d.collection.name,
      icon: d.collection.type === 'folder' ? 'folder' : 'boxes',
    }))
}

const addParentId = ref('')

async function onAddParentCollection() {
  const c = collection.value
  if (!c || !addParentId.value) return
  if (c.parentCollections.some(p => p.id === addParentId.value)) {
    toast.error('Already a parent collection')
    return
  }
  try {
    await gqlMutation(addParentCollectionGql, {
      id: collectionId.value,
      parentId: addParentId.value,
      attributes: {},
    })
    addParentId.value = ''
    await refresh()
    toast.success('Parent collection added')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to add parent collection')
  }
}

async function onRemoveParentCollection(parentId: string) {
  try {
    await gqlMutation(removeParentCollectionGql, {
      id: collectionId.value,
      parentId,
    })
    await refresh()
    toast.success('Parent collection removed')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to remove parent collection')
  }
}

// ── Metadata relationships (editable) ───────────────────────────────────────
async function searchRelationshipMetadata(q: string): Promise<SelectOption[]> {
  const result = await gqlQuery<any>(searchEntitiesGql, {
    query: q,
    filter: '_type = "metadata"',
    limit: 20,
    offset: 0,
  })
  return (result?.search?.search?.documents ?? [])
    .filter((d: any) => d.metadata != null)
    .map((d: any) => ({ value: d.metadata.id, label: d.metadata.name }))
}

const addRelMetadataId = ref('')
const addRelType = ref('')

async function onAddRelationship() {
  if (!addRelMetadataId.value || !addRelType.value.trim()) return
  try {
    await gqlMutation(addCollectionMetadataRelationshipGql, {
      relationship: {
        id: collectionId.value,
        metadataId: addRelMetadataId.value,
        relationship: addRelType.value.trim(),
        attributes: {},
        languageTag: variantTag.value,
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

async function onRemoveRelationship(rel: { metadata: { id: string }; relationship: string }) {
  try {
    await gqlMutation(deleteCollectionMetadataRelationshipGql, {
      id: collectionId.value,
      metadataId: rel.metadata.id,
      relationship: rel.relationship,
      languageTag: variantTag.value,
    })
    await refresh()
    toast.success('Relationship removed')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to remove relationship')
  }
}

// ── Relationship attributes editor ──────────────────────────────────────────
const relAttrsModalOpen = ref(false)
const relAttrsTarget = ref<{ metadataId: string; metadataName: string; relationship: string } | null>(null)
const relAttrsText = ref('')
const relAttrsError = ref('')
const relAttrsSaving = ref(false)

function openRelAttrsModal(rel: { metadata: { id: string; name: string }; relationship: string; attributes: unknown }) {
  relAttrsTarget.value = {
    metadataId: rel.metadata.id,
    metadataName: rel.metadata.name || rel.metadata.id,
    relationship: rel.relationship,
  }
  const attrs = (rel.attributes as Record<string, unknown> | null) ?? {}
  relAttrsText.value = JSON.stringify(attrs, null, 2)
  relAttrsError.value = ''
  relAttrsSaving.value = false
  relAttrsModalOpen.value = true
}

async function onSaveRelAttrs() {
  const t = relAttrsTarget.value
  if (!t || relAttrsSaving.value) return
  let parsed: unknown
  try {
    parsed = relAttrsText.value.trim() === '' ? {} : JSON.parse(relAttrsText.value)
  } catch (e: any) {
    relAttrsError.value = e?.message || 'Invalid JSON'
    return
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    relAttrsError.value = 'Attributes must be a JSON object.'
    return
  }
  relAttrsSaving.value = true
  relAttrsError.value = ''
  try {
    await gqlMutation(editCollectionMetadataRelationshipGql, {
      relationship: {
        id: collectionId.value,
        metadataId: t.metadataId,
        relationship: t.relationship,
        attributes: parsed,
        languageTag: variantTag.value,
      },
    })
    relAttrsModalOpen.value = false
    relAttrsTarget.value = null
    await refresh()
    toast.success('Relationship attributes updated')
  } catch (e: any) {
    relAttrsError.value = e?.message || 'Failed to update attributes'
  } finally {
    relAttrsSaving.value = false
  }
}

// ── Relationship reordering ──────────────────────────────────────────────────
// Order is carried by a `sort` key inside each relationship's attributes; the
// merge mutation patches only the changed key so concurrent attribute writes
// survive. This local list is a mutable mirror for SortableJS — every server
// response replaces it wholesale, so the server's order wins.
const mergeRelAttributesGql = gql`
  mutation MergeCollectionRelationshipAttributes($id: UUID!, $metadataId: UUID!, $relationship: String!, $attributes: JSON!, $languageTag: String) {
    content {
      collection {
        mergeMetadataRelationshipAttributes(collectionId: $id, metadataId: $metadataId, relationship: $relationship, attributes: $attributes, languageTag: $languageTag)
      }
    }
  }
`

const localRelationships = ref<Collection['metadataRelationships']>([])
watch(collection, (c) => {
  const source = c?.languageVariant ? c.languageVariant.metadataRelationships : c?.metadataRelationships
  localRelationships.value = [...(source ?? [])]
}, { immediate: true })

async function onRelationshipReorder(oldIndex: number, newIndex: number) {
  const moved = localRelationships.value[oldIndex]
  if (!moved) return

  const start = Math.max(Number((localRelationships.value[0]?.attributes as Record<string, unknown> | null)?.sort) || 0, 0)
  localRelationships.value.splice(oldIndex, 1)
  localRelationships.value.splice(newIndex, 0, moved)

  try {
    let index = 0
    for (const rel of localRelationships.value) {
      const attrs: Record<string, unknown> = { ...((rel.attributes as Record<string, unknown> | null) ?? {}) }
      const newSort = start + index
      if (attrs.sort !== newSort) {
        attrs.sort = newSort
        await gqlMutation(mergeRelAttributesGql, {
          id: collectionId.value,
          metadataId: rel.metadata.id,
          relationship: rel.relationship,
          attributes: attrs,
          languageTag: variantTag.value,
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

// ── Save ─────────────────────────────────────────────────────────────────────
async function onSave() {
  if (!collection.value) return

  // Collapse the live (Yjs-backed) template attribute editors into a flat
  // attributes object, plus the metadata relationships implied by METADATA-type
  // template attributes (e.g. images). Parent collections derived from template
  // attributes are discarded — collection parents are managed by the Parents tab.
  const parentCollections: any[] = []
  const metadataRelationships: { id1: string; id2: string; relationship: string; attributes: any }[] = []
  applyAttributes(
    collection.value as unknown as GqlCollection,
    attributeStates,
    rawAttributes,
    parentCollections,
    metadataRelationships,
  )

  saving.value = true
  try {
    const v = variant.value
    // When bound to a language variant, name and attributes belong to the
    // variant (saved through the variant upsert below); the base collection
    // keeps its own values.
    await gqlMutation(saveCollectionGql, {
      id: collectionId.value,
      input: {
        name: v ? collection.value.name : name.value,
        attributes: v ? collection.value.attributes : rawAttributes.value,
        categoryIds: collection.value.categories.map(c => c.id),
        locked: locked.value,
        ordering: ordering.value.filter(isRuleValid),
        labels: labelsField.value.split(',').map(l => l.trim()).filter(l => l.length > 0),
        searchable: v ? collection.value.searchable : searchable.value,
        recommendable: v ? collection.value.recommendable : recommendable.value,
      },
    })
    if (v) {
      await gqlMutation(addLanguageVariantGql, {
        variant: {
          id: collectionId.value,
          languageTag: v.languageTag,
          name: name.value,
          description: v.description ?? '',
          attributes: v.attributes ?? {},
          public: publicFlag.value,
          publicList: publicList.value,
          publicSupplementary: publicSupplementary.value,
          searchable: searchable.value,
          recommendable: recommendable.value,
        },
      })
    } else {
      await gqlMutation(setPublicGql, { id: collectionId.value, public: publicFlag.value })
      await gqlMutation(setPublicListGql, { id: collectionId.value, public: publicList.value })
      await gqlMutation(setPublicSupplementaryGql, { id: collectionId.value, public: publicSupplementary.value })
    }
    await gqlMutation(setCollectionMetadataRelationshipsGql, {
      id: collectionId.value,
      relationships: metadataRelationships.map(r => ({
        id: r.id1,
        metadataId: r.id2,
        relationship: r.relationship,
        attributes: r.attributes,
        languageTag: variantTag.value,
      })),
      languageTag: variantTag.value,
    })

    const tmplId = selectedTemplate.value
    const templateChanged = !!tmplId && collection.value.templateMetadata?.id !== tmplId
    if (templateChanged) {
      const all = data.value?.content?.collections?.templates?.all ?? []
      const tmpl = all.find(a => a.metadata?.id === tmplId)
      if (tmpl?.metadata) {
        await gqlMutation(setTemplateGql, { id: collectionId.value, metadataId: tmpl.metadata.id, metadataVersion: tmpl.metadata.version })
      }
    }

    const currentState = v ? v.workflow?.state : collection.value.workflow?.state
    if (workflowState.value && workflowState.value !== currentState) {
      await gqlMutation(beginTransitionGql, {
        id: collectionId.value,
        state: workflowState.value,
        status: `Admin changed state to ${workflowState.value}`,
        languageTag: variantTag.value,
      })
    }

    toast.success('Collection saved')
    await refresh()
    // refreshState() updates the template attributes; the composable rebuilds
    // the attribute map in a pre-flush watcher, which runs before the keyed
    // remount below renders — so the sidebar mounts with the new field set.
    collab.refreshState()
    if (templateChanged) attributesEditorKey.value++
  } catch (e: any) {
    toast.error(e?.message || 'Failed to save')
  } finally {
    saving.value = false
  }
}

// ── Actions ──────────────────────────────────────────────────────────────────
const preview = usePreviewableUrl()
onMounted(() => { preview.load() })

function onPreview() {
  const c = collection.value
  if (!c) return
  const v = c.languageVariant
  preview.openPreview({
    id: c.id,
    slug: (v?.slug || c.slug),
    languageTag: (v?.languageTag || c.languageTag),
  })
}

async function onPublish() {
  if (!validateBeforeTransition()) return
  publishing.value = true
  try {
    await gqlMutation(beginTransitionGql, {
      id: collectionId.value,
      state: 'published',
      status: 'complete',
      languageTag: variantTag.value,
    })
    toast.success('Published')
    refresh()
  } catch {
    toast.error('Failed to publish')
  } finally {
    publishing.value = false
  }
}

async function onMarkReady() {
  try {
    await gqlMutation(setReadyGql, { id: collectionId.value, languageTag: variantTag.value })
    toast.success('Marked ready')
    refresh()
  } catch {
    toast.error('Failed to mark ready')
  }
}

async function onSetNotReady() {
  try {
    await gqlMutation(setNotReadyGql, { id: collectionId.value, languageTag: variantTag.value })
    toast.success('Marked not ready')
    refresh()
  } catch {
    toast.error('Failed')
  }
}

async function onUnpublish() {
  try {
    await gqlMutation(beginTransitionGql, { id: collectionId.value, state: 'draft', status: 'Admin Unpublished', languageTag: variantTag.value })
    toast.success('Unpublished')
    refresh()
  } catch {
    toast.error('Failed to unpublish')
  }
}

const cancellingJobs = ref<Set<string>>(new Set())

async function onCancelJob(jobId: string) {
  if (!jobId || cancellingJobs.value.has(jobId)) return
  cancellingJobs.value.add(jobId)
  try {
    await gqlMutation(cancelJobGql, { jobId })
    toast.success('Job cancelled')
    refresh()
  } catch (e: any) {
    toast.error(e?.message || 'Failed to cancel job')
  } finally {
    cancellingJobs.value.delete(jobId)
  }
}

async function onCancelTransition() {
  try {
    await gqlMutation(cancelTransitionGql, { id: collectionId.value, languageTag: variantTag.value })
    toast.success('Transition cancelled')
    refresh()
  } catch {
    toast.error('Failed')
  }
}

async function onConfirmDelete() {
  deleting.value = true
  try {
    await gqlMutation(deleteCollectionGql, { id: collectionId.value })
    toast.success('Collection deleted')
    deleteModalOpen.value = false
    router.push('/cms/collections')
  } catch {
    toast.error('Failed to delete')
  } finally {
    deleting.value = false
  }
}

async function onLockChanged() {
  try {
    await gqlMutation(setLockedGql, { id: collectionId.value, locked: locked.value })
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

async function onSearchableChanged() {
  try {
    await gqlMutation(setSearchableGql, {
      id: collectionId.value,
      searchable: searchable.value,
      languageTag: variantTag.value,
    })
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

async function onRecommendableChanged() {
  try {
    await gqlMutation(setRecommendableGql, {
      id: collectionId.value,
      recommendable: recommendable.value,
      languageTag: variantTag.value,
    })
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

async function onSyncVariants() {
  try {
    await gqlMutation(syncVariantsGql, { id: collectionId.value })
    toast.success('Variants synced')
  } catch (e: any) {
    toast.error(e?.message || 'Failed')
  }
}

// ── Slug modal ───────────────────────────────────────────────────────────────
const slugModalOpen = ref(false)
const slugValue = ref('')
const slugSaving = ref(false)
const slugError = ref('')
const slugInputRef = ref<{ available: boolean | null } | null>(null)

const currentSlug = computed(() => variant.value ? (variant.value.slug || '') : (collection.value?.slug || ''))

async function checkSlugAvailable(slugVal: string): Promise<boolean> {
  if (slugVal === currentSlug.value) return true
  const result = await gqlQuery<{ content: { slugAvailable: boolean } }>(slugAvailableGql, { slug: slugVal })
  return result.content.slugAvailable
}

function openSlugModal() {
  slugValue.value = currentSlug.value
  slugError.value = ''
  slugSaving.value = false
  slugModalOpen.value = true
}

async function onSaveSlug() {
  if (!collection.value || !slugValue.value || slugSaving.value) return
  if (slugValue.value === currentSlug.value) { slugModalOpen.value = false; return }
  slugSaving.value = true
  slugError.value = ''
  try {
    await gqlMutation(setSlugGql, { id: collectionId.value, slug: slugValue.value, languageTag: variantTag.value })
    slugModalOpen.value = false
    await refresh()
  } catch (e: any) {
    slugError.value = e?.message || 'Failed to update slug'
  } finally {
    slugSaving.value = false
  }
}

// ── Permissions ──────────────────────────────────────────────────────────────
const PERMISSION_ACTIONS = ['VIEW', 'EDIT', 'DELETE', 'MANAGE', 'LIST']
const showPermModal = ref(false)
const permAction = ref('VIEW')
const permGroupId = ref('')
const permSaving = ref(false)

const { data: groupsData } = useAsyncQuery<{ security: { groups: { all: Array<{ id: string; name: string }> } } }>('security-groups', groupsGql, { limit: 200, offset: 0 })
const groups = computed(() => groupsData.value?.security?.groups?.all ?? [])
const groupOptions = computed(() => groups.value.map((g) => ({ value: g.id, label: g.name })))

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
const permissions = computed(() => collection.value?.permissions ?? [])

async function addPermission() {
  if (!permGroupId.value || !permAction.value) return
  permSaving.value = true
  try {
    await gqlMutation(addPermissionGql, {
      perm: { action: permAction.value, entityId: collectionId.value, groupId: permGroupId.value },
    })
    await refresh()
    toast.success('Permission added')
    showPermModal.value = false
    permGroupId.value = ''
  } catch (e: any) {
    toast.error(e?.message || 'Failed to add permission')
  } finally {
    permSaving.value = false
  }
}

async function removePermission(perm: CollectionPermission) {
  try {
    await gqlMutation(deletePermissionGql, {
      perm: { action: perm.action, entityId: collectionId.value, groupId: perm.group.id },
    })
    await refresh()
    toast.success('Permission removed')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to remove permission')
  }
}

// ── Language variants ────────────────────────────────────────────────────────
// Full navigation (not router.push) so collaboration and attribute state fully
// reset for the selected variant.
function onSelectLanguage(tag: string) {
  const base = `/cms/collections/${collectionId.value}`
  window.location.href = tag === collection.value?.languageTag
    ? base
    : `${base}?languageTag=${encodeURIComponent(tag)}`
}

async function onCreateLanguageVariant(tag: string) {
  const c = collection.value
  if (!c) return
  try {
    await gqlMutation(addLanguageVariantGql, {
      variant: {
        id: collectionId.value,
        languageTag: tag,
        name: name.value || c.name,
        description: '',
        attributes: {},
      },
      setReady: true,
    })
    toast.success(`Language variant "${tag}" created`)
    onSelectLanguage(tag)
  } catch (e: any) {
    toast.error(e?.message || 'Failed to create language variant')
  }
}

// ── Overflow menu ────────────────────────────────────────────────────────────
const overflowItems = computed<OverflowMenuItem[]>(() => {
  const c = collection.value
  if (!c) return []
  const items: OverflowMenuItem[] = []
  const w = workflow.value
  if (w?.state === 'published' && !w?.pending) {
    items.push({ id: 'unpublish', label: 'Unpublish', icon: 'globe' })
  }
  if (w?.pending) {
    items.push({ id: 'cancel-transition', label: 'Cancel transition', icon: 'x' })
  }
  items.push(
    { id: 'slug', label: 'Edit slug…', icon: 'link' },
    { id: 'sync-variants', label: 'Sync variants', icon: 'refresh' },
    { id: 'sep1', label: '', separator: true },
    { id: 'copy-id', label: 'Copy ID', icon: 'copy' },
    { id: 'sep2', label: '', separator: true },
    { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
  )
  return items
})

async function onOverflowAction(id: string) {
  switch (id) {
    case 'unpublish': onUnpublish(); break
    case 'cancel-transition': onCancelTransition(); break
    case 'slug': openSlugModal(); break
    case 'sync-variants': onSyncVariants(); break
    case 'copy-id': navigator.clipboard.writeText(collectionId.value); toast.success('Copied'); break
    case 'delete': deleteModalOpen.value = true; break
  }
}

// ── Helpers ──────────────────────────────────────────────────────────────────
function formatDate(d: string | null | undefined): string {
  if (!d) return '--'
  try {
    return new Date(d).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short' })
  } catch { return d }
}

useCollectionSubscription(collectionId, () => { refresh(); refreshItems() })
</script>

<template>
  <PageShell class="col-shell">
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Collections', collection?.name ?? '…')"
        :title="collection?.name ?? 'Loading…'"
        :subtitle="collection ? `${totalItems} items · ${collection.type}` : ''"
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
            v-if="collection && !isReady"
            size="sm"
            icon="check"
            :accent="accent"
            @click="onMarkReady">
            Ready
          </Button>
          <Button
            v-if="canPublish"
            size="sm"
            icon="globe"
            primary
            :accent="accent"
            :disabled="publishing"
            @click="onPublish">
            Publish
          </Button>
          <Button
            v-if="preview.canPreview.value && collection"
            size="sm"
            icon="eye"
            :accent="accent"
            @click="onPreview">
            Preview
          </Button>
          <CollectionLanguageMenu
            v-if="collection"
            :base-tag="collection.languageTag"
            :variant-tags="collection.languageVariants ?? []"
            :current-tag="variantTag || collection.languageTag"
            :accent="accent"
            @select="onSelectLanguage"
            @create="onCreateLanguageVariant"
          />
          <OverflowMenu :items="overflowItems" @select="onOverflowAction">
            <template #default="{ toggle }">
              <Button size="sm" icon="more" @click="toggle" />
            </template>
          </OverflowMenu>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !collection" class="loading-state">Loading…</div>

    <div v-else-if="collection" class="col-body">
      <!-- Main panel -->
      <div class="panel panel--main">
        <div class="panel-scroll">
          <div class="section-head">Details</div>
          <div class="form-fields">
            <div class="field">
              <label class="field-label">Name</label>
              <TextInput v-model="name" placeholder="Collection name" icon="boxes" />
            </div>
            <div class="field">
              <label class="field-label">Slug</label>
              <div class="slug-row" @click="openSlugModal">
                <span class="mono slug-value">{{ collection.slug || '(none)' }}</span>
                <Icon
                  name="pencil"
                  :size="12"
                  color="var(--fg-4)"
                  class="slug-edit-icon" />
              </div>
            </div>
            <div class="field-row">
              <div class="field">
                <label class="field-label">Type</label>
                <TextInput :model-value="collection.type" disabled />
              </div>
              <div class="field">
                <label class="field-label">Language</label>
                <TextInput :model-value="collection.languageTag" disabled />
              </div>
            </div>
            <div class="field">
              <label class="field-label">Labels <span class="field-hint">(comma-separated)</span></label>
              <TextInput v-model="labelsField" placeholder="tag1, tag2" />
            </div>
          </div>

          <div class="hr" />

          <Tabs
            v-model="activeTab"
            :tabs="tabItems"
            :accent="accent"
          />

          <!-- Items -->
          <div v-if="activeTab === 'Items'" class="tab-body">
            <div class="add-bar">
              <span class="tab-count">{{ totalItems }} items</span>
              <span class="spacer" />
              <Button
                size="sm"
                icon="plus"
                :accent="accent"
                @click="addItemModalOpen = true">Add Item</Button>
            </div>

            <div ref="itemsTableEl" class="items-table-wrap">
              <GlassTable
                :columns="itemColumns"
                :rows="items"
                :loading="itemsLoading && items.length === 0"
                empty-text="No items in this collection."
                :row-actions="getRowActions"
                arrow
                @row-click="onItemClick"
                @row-action="({ action, row }) => onRowAction(action, row)"
              >
                <template #col-name="{ row }">
                  <div class="item-name-cell">
                    <button
                      v-if="isManualOrdering"
                      class="items-drag-handle"
                      title="Drag to reorder"
                      @click.stop>
                      <Icon name="grip" :size="14" color="var(--fg-3)" />
                    </button>
                    <SPicture :item="(row as any)" picture-class="item-thumb" />
                    <span class="item-name">{{ row.name }}</span>
                  </div>
                </template>
                <template #col-type="{ row }">
                  <span class="item-type">{{ getItemType(row) }}</span>
                </template>
                <template #col-status="{ row }">
                  <Badge :color="getItemStatus(row).color">{{ getItemStatus(row).label }}</Badge>
                </template>
                <template #col-modified="{ row }">
                  {{ formatDate(row.modified) }}
                </template>
              </GlassTable>
            </div>

            <Pagination
              v-if="totalPages > 1"
              :page="currentPage"
              :total-pages="totalPages"
              @prev="itemsOffset = Math.max(0, itemsOffset - itemsLimit)"
              @next="itemsOffset += itemsLimit"
            />
          </div>

          <!-- Parents -->
          <div v-else-if="activeTab === 'Parents'" class="tab-body">
            <div class="add-bar">
              <Select
                v-model="addParentId"
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
                :disabled="!addParentId"
                @click="onAddParentCollection">Add</Button>
            </div>
            <div v-if="collection.parentCollections?.length" class="list-items">
              <div
                v-for="p in collection.parentCollections"
                :key="p.id"
                class="list-row list-row--clickable"
                @click="router.push(`/cms/collections/${p.id}`)">
                <Icon name="boxes" :size="14" :color="accent" />
                <div class="list-text">
                  <div class="list-name">{{ p.name }}</div>
                  <div class="mono list-sub">{{ p.id }}</div>
                </div>
                <Badge v-if="p.workflow?.state" :color="p.workflow.state === 'published' ? '#34d99a' : '#6c7388'">{{ p.workflow.state }}</Badge>
                <button class="rm-btn" title="Remove parent" @click.stop="onRemoveParentCollection(p.id)">
                  <Icon name="x" :size="12" color="var(--fg-3)" />
                </button>
              </div>
            </div>
            <div v-else class="empty-msg">No parent collections.</div>
          </div>

          <!-- Ordering -->
          <div v-else-if="activeTab === 'Ordering'" class="tab-body ordering-body">
            <div class="ordering-preset">
              <div class="ordering-preset-bar">
                <div class="field ordering-preset-field">
                  <label class="field-label">Sort items by</label>
                  <Select
                    :model-value="orderingPreset"
                    :options="orderingPresetOptions"
                    placeholder="Choose how items are sorted…"
                    size="sm"
                    :accent="accent"
                    @update:model-value="onOrderingPresetChange" />
                </div>
                <Button
                  v-if="orderingPreset && orderingPreset !== 'custom'"
                  size="sm"
                  icon="settings"
                  :accent="accent"
                  :primary="showOrderingDetails"
                  :title="showOrderingDetails ? 'Hide ordering rule settings' : 'Show ordering rule settings'"
                  @click="showOrderingDetails = !showOrderingDetails" />
              </div>
              <div v-if="orderingPresetHint" class="ordering-preset-hint">{{ orderingPresetHint }}</div>
            </div>

            <div v-if="ordering.length === 0" class="ordering-empty">
              <Icon name="sort" :size="20" color="var(--fg-3)" />
              <div class="ordering-empty-title">No ordering rules configured</div>
              <div class="ordering-empty-sub">Items will be sorted by name (ascending) by default. Choose an option above to change that.</div>
            </div>

            <div v-if="showOrderingRules && hasInvalidRules" class="ordering-warning">
              <Icon name="alert" :size="14" color="#ff5d6c" />
              <div class="ordering-warning-text">
                <div class="ordering-warning-title">Invalid ordering rules</div>
                <div class="ordering-warning-sub">
                  Some rules are missing both a field and a path. These rules will be ignored.
                </div>
              </div>
            </div>

            <div v-if="showOrderingRules" ref="orderingListEl" class="ordering-list">
              <div
                v-for="(rule, index) in ordering"
                :key="index"
                class="ordering-rule"
                :class="{ 'ordering-rule--invalid': !isRuleValid(rule) }">
                <div class="ordering-rule-head">
                  <button class="ordering-drag-handle" title="Drag to reorder">
                    <Icon name="grip" :size="14" color="var(--fg-3)" />
                  </button>
                  <span class="ordering-rule-label">Rule {{ index + 1 }}</span>
                  <Badge v-if="!isRuleValid(rule)" color="#ff5d6c">Needs field or path</Badge>
                  <span class="spacer" />
                  <button class="rm-btn rm-btn--always" title="Remove rule" @click="removeOrderingRule(index)">
                    <Icon name="trash" :size="12" color="var(--fg-3)" />
                  </button>
                </div>

                <div class="ordering-rule-row">
                  <div class="field">
                    <label class="field-label">Location</label>
                    <Select
                      v-model="rule.location"
                      :options="locationOptions"
                      size="sm"
                      :accent="accent" />
                  </div>
                  <div class="field">
                    <label class="field-label">Order</label>
                    <Select
                      v-model="rule.order"
                      :options="orderOptions"
                      size="sm"
                      :accent="accent" />
                  </div>
                  <div class="field">
                    <label class="field-label">Type</label>
                    <Select
                      v-model="rule.type"
                      :options="typeOptions"
                      size="sm"
                      :accent="accent" />
                  </div>
                </div>

                <div class="ordering-rule-fieldpath">
                  <div class="field ordering-fieldpath-col">
                    <label class="field-label">
                      Field
                      <span class="field-hint">(column name)</span>
                    </label>
                    <TextInput
                      :model-value="rule.field ?? ''"
                      :disabled="!!(rule.path && rule.path.length > 0)"
                      placeholder="e.g. name, created"
                      size="sm"
                      @update:model-value="(v: string) => onRuleFieldChange(rule, v)" />
                  </div>
                  <div class="ordering-fieldpath-or">OR</div>
                  <div class="field ordering-fieldpath-col">
                    <label class="field-label">
                      Path
                      <span class="field-hint">(nested JSON attribute)</span>
                    </label>
                    <div v-if="rule.path && rule.path.length > 0" class="path-segments">
                      <div v-for="(_, pIndex) in rule.path" :key="pIndex" class="path-segment">
                        <TextInput v-model="rule.path![pIndex]!" placeholder="Attribute key" size="sm" />
                        <button class="rm-btn rm-btn--always" title="Remove segment" @click="removePathSegment(rule, pIndex)">
                          <Icon name="x" :size="12" color="var(--fg-3)" />
                        </button>
                      </div>
                    </div>
                    <Button
                      v-if="!rule.field"
                      size="sm"
                      icon="plus"
                      @click="addPathSegment(rule)">Add Segment</Button>
                    <div v-else class="ordering-fieldpath-hint">Clear Field to enable Path.</div>
                  </div>
                </div>
              </div>
            </div>

            <button v-if="showOrderingRules" class="ordering-add-block" @click="addOrderingRule">
              <Icon name="plus" :size="14" :color="accent" />
              <span>Add Ordering Rule</span>
            </button>
          </div>

          <!-- Relationships -->
          <div v-else-if="activeTab === 'Relationships'" class="tab-body">
            <div class="add-bar add-bar--rel">
              <Select
                v-model="addRelMetadataId"
                searchable
                :on-search="searchRelationshipMetadata"
                placeholder="Search metadata…"
                size="sm"
                icon="file"
                :accent="accent" />
              <TextInput
                v-model="addRelType"
                placeholder="Relationship (e.g. thumbnail)"
                size="sm" />
              <Button
                size="sm"
                primary
                :accent="accent"
                :disabled="!addRelMetadataId || !addRelType.trim()"
                @click="onAddRelationship">Add</Button>
            </div>
            <div v-if="localRelationships.length" ref="relListEl" class="list-items">
              <div v-for="rel in localRelationships" :key="`${rel.relationship}-${rel.metadata?.id}`" class="list-row">
                <button class="rel-drag-handle" title="Drag to reorder" @click.stop>
                  <Icon name="grip" :size="14" color="var(--fg-3)" />
                </button>
                <Icon name="link" :size="14" color="var(--fg-3)" />
                <div class="list-text">
                  <div class="list-name">{{ rel.metadata?.name || rel.metadata?.id }}</div>
                  <div class="mono list-sub">{{ rel.relationship }}</div>
                </div>
                <Badge color="#5ec5ff">{{ rel.metadata?.content?.type || 'metadata' }}</Badge>
                <button class="rm-btn rm-btn--always" title="Edit attributes" @click="openRelAttrsModal(rel)">
                  <Icon name="pencil" :size="12" color="var(--fg-3)" />
                </button>
                <button class="rm-btn" title="Remove relationship" @click="onRemoveRelationship(rel)">
                  <Icon name="x" :size="12" color="var(--fg-3)" />
                </button>
              </div>
            </div>
            <div v-else class="empty-msg">No relationships.</div>
          </div>

          <!-- Permissions -->
          <div v-else-if="activeTab === 'Permissions'" class="tab-body">
            <div class="add-bar">
              <span class="tab-count">{{ permissions.length }} permissions</span>
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
            <div v-else class="empty-msg">No permissions assigned.</div>
          </div>

          <!-- Settings: the less-frequently-touched options that used to fill
               the sidebar, which now belongs to the template attributes. -->
          <div v-else-if="activeTab === 'Settings'" class="tab-body">
            <div class="settings-grid">
              <SectionCard title="Info">
                <div class="kvs">
                  <div class="kv"><span class="kv-k">Type</span><span class="mono kv-v">{{ collection.type }}</span></div>
                  <div class="kv"><span class="kv-k">Created</span><span class="kv-v">{{ formatDate(collection.created) }}</span></div>
                  <div class="kv"><span class="kv-k">Modified</span><span class="kv-v">{{ formatDate(collection.modified) }}</span></div>
                  <div class="kv"><span class="kv-k">Items</span><span class="mono kv-v tabular">{{ totalItems }}</span></div>
                  <div class="kv"><span class="kv-k">Ready</span><span class="kv-v">{{ isReady ? 'Yes' : 'No' }}</span></div>
                  <div v-if="variant" class="kv"><span class="kv-k">Variant</span><span class="mono kv-v">{{ variant.languageTag }}</span></div>
                </div>
              </SectionCard>

              <SectionCard title="Status">
                <div class="sc-switches">
                  <Switch v-model="publicFlag" label="Public" :accent="accent" />
                  <Switch v-model="publicList" label="Public List" :accent="accent" />
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
                </div>
              </SectionCard>

              <SectionCard title="Template">
                <div class="sc-pad">
                  <Select
                    v-model="selectedTemplate"
                    :options="templateOptions"
                    searchable
                    placeholder="Select template…"
                    size="sm"
                    :accent="accent"
                  />
                </div>
              </SectionCard>

              <SectionCard title="Workflow">
                <div class="sc-pad sc-workflow">
                  <div class="kv"><span class="kv-k">State</span><Badge :color="wfBadge.color">{{ wfBadge.label }}</Badge></div>
                  <Select
                    v-model="workflowState"
                    :options="availableStates"
                    placeholder="Select state…"
                    size="sm"
                    :accent="accent"
                  />
                  <div v-if="workflow?.stateValid" class="kv"><span class="kv-k">Valid</span><span class="kv-v">{{ formatDate(workflow.stateValid) }}</span></div>
                  <div v-if="workflow?.pending" class="kv"><span class="kv-k">Pending</span><span class="kv-v">{{ workflow.pending }}</span></div>
                  <div v-if="workflow?.running" class="kv"><span class="kv-k">Running</span><span class="kv-v">{{ workflow.running }}</span></div>
                  <Button
                    v-if="isReady && !workflow?.pending"
                    size="sm"
                    icon="x"
                    @click="onSetNotReady">Mark Not Ready</Button>
                </div>
              </SectionCard>

              <SectionCard v-if="collection.categories?.length" title="Categories">
                <div class="sc-tags">
                  <Badge v-for="cat in collection.categories" :key="cat.id" :color="accent">{{ cat.name }}</Badge>
                </div>
              </SectionCard>

              <SectionCard v-if="collection.traits?.length" title="Traits">
                <div class="sc-tags">
                  <Badge v-for="trait in collection.traits" :key="trait.id" color="#a78bff">{{ trait.name }}</Badge>
                </div>
              </SectionCard>

              <SectionCard v-if="collection.languageVariants?.length" title="Language Variants">
                <div class="sc-tags">
                  <Badge v-for="lang in collection.languageVariants" :key="lang" color="var(--fg-4)">{{ lang }}</Badge>
                </div>
              </SectionCard>

              <SectionCard v-if="workflow?.activeJobs?.length" title="Active Jobs">
                <div class="kvs">
                  <div v-for="job in (workflow.activeJobs as any[])" :key="job.jobId" class="kv kv--job">
                    <span class="kv-k job-name">{{ job.displayName || job.jobName }}</span>
                    <Badge :color="job.complete ? (job.success ? '#34d99a' : '#ff5d6c') : '#ffb547'">{{ job.status }}</Badge>
                    <button
                      v-if="!job.complete && job.jobId"
                      class="rm-btn"
                      :title="cancellingJobs.has(job.jobId) ? 'Cancelling…' : 'Cancel job'"
                      :disabled="cancellingJobs.has(job.jobId)"
                      @click="onCancelJob(job.jobId)">
                      <Icon name="x" :size="12" color="var(--fg-3)" />
                    </button>
                  </div>
                </div>
              </SectionCard>
            </div>
          </div>
        </div>
      </div>

      <!-- Attributes sidebar — the same editor the document editor uses, so the
           template attributes the content team edits most are always visible.
           Keyed so a template change remounts it with the new field set. -->
      <div class="panel panel--side">
        <MetadataEditor
          v-if="ydoc && attributeStates"
          :key="attributesEditorKey"
          v-model:raw-attributes="rawAttributes"
          :metadata="(collection as any)"
          :state="(workflow as any)"
          :ydoc="ydoc"
          :editable="ydocReady"
          :uploader="uploader"
          :attributes="attributeStates"
          empty-text="No template attributes. Choose a template under Settings to add fields."
        />
        <div v-else class="raw-fallback raw-fallback--side">
          <div class="raw-label">Raw Attributes</div>
          <JsonEditorVue
            v-model="rawAttributes"
            :main-menu-bar="false"
            :navigation-bar="false"
            class="json-editor" />
        </div>
      </div>
    </div>
  </PageShell>

  <!-- Add Item modal -->
  <Modal
    v-if="addItemModalOpen"
    title="Add Item"
    icon="plus"
    :accent="accent"
    @close="addItemModalOpen = false">
    <CollectionItemSearch :accent="accent" placeholder="Search metadata or collections…" @select="onAddSearchItem" />
    <template #footer>
      <span class="spacer" />
      <Button size="sm" @click="addItemModalOpen = false">Cancel</Button>
    </template>
  </Modal>

  <!-- Delete confirmation -->
  <ConfirmModal
    v-if="deleteModalOpen"
    :title="`Delete '${collection?.name}'?`"
    subtitle="This will delete the collection. This cannot be undone."
    :loading="deleting"
    @close="deleteModalOpen = false"
    @confirm="onConfirmDelete"
  />

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

  <!-- Add permission modal -->
  <Modal
    v-if="showPermModal"
    title="Add Permission"
    icon="shield"
    :accent="accent"
    @close="showPermModal = false">
    <div class="perm-form">
      <Select v-model="permAction" :options="PERMISSION_ACTIONS.map(a => ({ value: a, label: a }))" label="Action" />
      <Select
        v-model="permGroupId"
        :on-search="searchGroups"
        searchable
        label="Group"
        placeholder="Search groups…"
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

  <!-- Relationship attributes modal -->
  <Modal
    v-if="relAttrsModalOpen"
    title="Edit Relationship Attributes"
    :subtitle="relAttrsTarget ? `${relAttrsTarget.metadataName} · ${relAttrsTarget.relationship}` : ''"
    icon="link"
    width="600px"
    :accent="accent"
    @close="relAttrsModalOpen = false">
    <textarea
      v-model="relAttrsText"
      class="rel-attrs-editor mono"
      spellcheck="false"
      placeholder="{}"
    />
    <p v-if="relAttrsError" class="rel-attrs-error">{{ relAttrsError }}</p>
    <template #footer>
      <span class="spacer" />
      <Button size="sm" @click="relAttrsModalOpen = false">Cancel</Button>
      <Button
        size="sm"
        primary
        :accent="accent"
        :disabled="relAttrsSaving"
        @click="onSaveRelAttrs">
        {{ relAttrsSaving ? 'Saving…' : 'Save' }}
      </Button>
    </template>
  </Modal>

  <!-- Item attributes modal -->
  <Modal
    v-if="itemAttrsModalOpen"
    title="Edit Item Attributes"
    :subtitle="itemAttrsTarget ? `${itemAttrsTarget.name} · ${itemAttrsTarget.typename.toLowerCase()}` : ''"
    icon="pencil"
    width="600px"
    :accent="accent"
    @close="itemAttrsModalOpen = false">
    <textarea
      v-model="itemAttrsText"
      class="rel-attrs-editor mono"
      spellcheck="false"
      placeholder="{}"
    />
    <p v-if="itemAttrsError" class="rel-attrs-error">{{ itemAttrsError }}</p>
    <template #footer>
      <span class="spacer" />
      <Button size="sm" @click="itemAttrsModalOpen = false">Cancel</Button>
      <Button
        size="sm"
        primary
        :accent="accent"
        :disabled="itemAttrsSaving"
        @click="onSaveItemAttrs">
        {{ itemAttrsSaving ? 'Saving…' : 'Save' }}
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.col-shell :deep(.page-content) { padding: 0; display: flex; flex-direction: column; }

.col-body { flex: 1; display: flex; min-height: 0; overflow: hidden; padding: 14px; gap: 14px; }

.panel { background: var(--bg-1); border: 1px solid var(--line); border-radius: var(--r-md); display: flex; flex-direction: column; overflow: hidden; }
.panel--main { flex: 1; min-width: 0; }
/* Same chrome as the document editor's attributes panel: a bordered card whose
   attribute list scrolls internally under a pinned status row. */
.panel--side { width: 340px; flex: 0 0 340px; }
.panel--side :deep(.metadata-editor) { gap: 0; }
.panel--side :deep(.metadata-editor-toggle) { padding: 10px 16px; border-top: 1px solid var(--line); }
.panel--side :deep(.metadata-editor > .json-editor) { margin: 12px 12px 0; }
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

.tab-body { padding-top: 14px; min-height: 120px; }
.add-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.tab-count { font-size: 12px; color: var(--fg-3); font-weight: 500; }
.spacer { flex: 1; }
.empty-msg { padding: 32px; text-align: center; color: var(--fg-4); font-size: 13px; }

.items-table-wrap { display: contents; }
.items-drag-handle {
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
.items-drag-handle:hover { background: var(--bg-3); }
.items-drag-handle:active { cursor: grabbing; }
.items-table-wrap :deep(.gt-row--ghost) { opacity: 0.4; }

.item-name-cell { display: flex; align-items: center; gap: 10px; min-width: 0; }
.item-name-cell :deep(.item-thumb),
.item-name-cell :deep(.item-thumb .s-picture-img),
.item-name-cell :deep(.item-thumb.s-picture-placeholder) {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: var(--r-xs);
  object-fit: cover;
}
.item-name { font-weight: 500; color: var(--fg-0); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item-type { font-size: 12px; color: var(--fg-2); text-transform: capitalize; }

.list-items { display: flex; flex-direction: column; }
.list-row { display: flex; align-items: center; gap: 10px; padding: 8px 6px; border-radius: var(--r-xs); transition: background 0.12s; }
.list-row:hover { background: color-mix(in oklch, var(--brand-2) 5%, transparent); }
.list-text { flex: 1; min-width: 0; }
.list-name { font-size: 13px; font-weight: 500; color: var(--fg-0); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.list-sub { font-size: 11px; color: var(--fg-4); margin-top: 1px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.perm-group { font-size: 12px; color: var(--fg-1); flex: 1; }
.rm-btn { width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; border-radius: var(--r-xs); background: none; border: none; cursor: pointer; opacity: 0; transition: opacity 0.12s, background 0.12s; flex-shrink: 0; }
.list-row:hover .rm-btn { opacity: 1; }

.rel-drag-handle { width: 22px; height: 24px; display: flex; align-items: center; justify-content: center; background: none; border: none; cursor: grab; opacity: 0; transition: opacity 0.12s; flex-shrink: 0; }
.list-row:hover .rel-drag-handle { opacity: 1; }
.rel-drag-handle:active { cursor: grabbing; }
.rm-btn:hover { background: var(--bg-3); }

/* Settings tab — the option cards that used to stack in the sidebar */
.settings-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; align-items: start; }

.sc-pad { padding: 10px 16px 14px; }
.sc-switches { padding: 10px 16px 14px; display: flex; flex-direction: column; gap: 9px; }
.sc-tags { padding: 10px 16px 14px; display: flex; flex-wrap: wrap; gap: 5px; }
.sc-workflow { display: flex; flex-direction: column; gap: 8px; }

.kvs { padding: 8px 16px 12px; }
.kv { display: flex; justify-content: space-between; align-items: center; padding: 4px 0; font-size: 12.5px; gap: 8px; }
.kv-k { color: var(--fg-3); flex-shrink: 0; }
.kv-v { color: var(--fg-1); font-size: 12px; text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }

.list-row--clickable { cursor: pointer; }
.kv--job { gap: 8px; }
.kv--job .job-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kv--job .rm-btn { opacity: 1; flex-shrink: 0; }
.kv--job .rm-btn:disabled { opacity: 0.4; cursor: default; }
.add-bar--rel { gap: 8px; }

.ordering-body { display: flex; flex-direction: column; gap: 14px; }

.ordering-preset { display: flex; flex-direction: column; gap: 6px; }
.ordering-preset-bar { display: flex; align-items: flex-end; gap: 8px; }
.ordering-preset-field { flex: 1; min-width: 0; max-width: 440px; }
.ordering-preset-hint { font-size: 11.5px; color: var(--fg-3); }

.ordering-warning {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  background: color-mix(in oklch, #ff5d6c 8%, transparent);
  border: 1px solid color-mix(in oklch, #ff5d6c 28%, transparent);
  border-radius: var(--r-sm);
}
.ordering-warning-text { display: flex; flex-direction: column; gap: 2px; }
.ordering-warning-title { font-size: 12.5px; font-weight: 600; color: var(--fg-0); }
.ordering-warning-sub { font-size: 11.5px; color: var(--fg-2); }

.ordering-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 28px 16px;
  border: 1px dashed var(--line);
  border-radius: var(--r-md);
  background: color-mix(in oklch, var(--bg-2) 35%, transparent);
}
.ordering-empty-title { font-size: 12.5px; font-weight: 600; color: var(--fg-1); }
.ordering-empty-sub { font-size: 11.5px; color: var(--fg-3); }

.ordering-list { display: flex; flex-direction: column; gap: 10px; }
.ordering-rule {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: color-mix(in oklch, var(--bg-2) 55%, transparent);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.ordering-rule--invalid { border-color: color-mix(in oklch, #ff5d6c 55%, var(--line)); }
.ordering-rule--ghost { opacity: 0.4; }

.ordering-rule-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 42%, transparent);
  background: color-mix(in oklch, var(--bg-1) 70%, transparent);
}
.ordering-drag-handle {
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
}
.ordering-drag-handle:hover { background: var(--bg-3); }
.ordering-drag-handle:active { cursor: grabbing; }
.ordering-rule-label { font-size: 12px; font-weight: 600; color: var(--fg-1); }

.ordering-rule-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  padding: 12px;
}

.ordering-rule-fieldpath {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: start;
  gap: 12px;
  padding: 0 12px 12px;
}
.ordering-fieldpath-col { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.ordering-fieldpath-or {
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--fg-4);
  padding-top: 26px;
  text-align: center;
}
.ordering-fieldpath-hint { font-size: 11.5px; color: var(--fg-4); padding: 4px 0; }

.path-segments { display: flex; flex-direction: column; gap: 6px; }
.path-segment { display: flex; align-items: center; gap: 6px; }
.path-segment > :deep(.text-input) { flex: 1; min-width: 0; }

.ordering-add-block {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  padding: 12px;
  border: 1px dashed var(--line);
  border-radius: var(--r-md);
  background: none;
  color: var(--fg-2);
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.12s ease, border-color 0.12s ease, color 0.12s ease;
}
.ordering-add-block:hover {
  background: color-mix(in oklch, var(--brand-2) 5%, transparent);
  border-color: color-mix(in oklch, var(--brand-2) 38%, var(--line));
  color: var(--fg-0);
}

.rm-btn--always { opacity: 1; }
.empty-msg--inline { padding: 8px 0; text-align: left; }

.rel-attrs-editor {
  width: 100%;
  min-height: 260px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-2);
  color: var(--fg-1);
  font-size: 12.5px;
  font-family: inherit;
  resize: vertical;
}
.rel-attrs-editor:focus { outline: none; border-color: var(--brand-2); }
.rel-attrs-error { font-size: 12px; color: var(--err); margin: 8px 0 0; }

.raw-fallback { display: flex; flex-direction: column; gap: 8px; }
.raw-fallback--side { flex: 1; min-height: 0; padding: 16px; overflow-y: auto; }
.raw-label { font-size: 12px; font-weight: 550; color: var(--fg-2); }
.json-editor { border-radius: var(--r-sm); min-height: 200px; }
.add-bar--rel > :deep(.select) { flex: 1.4; min-width: 0; }
.add-bar--rel > :deep(.text-input) { flex: 1; min-width: 0; }

.loading-state { flex: 1; display: flex; align-items: center; justify-content: center; color: var(--fg-3); font-size: 13px; }
.slug-error { font-size: 12px; color: var(--err); margin: 8px 0 0; }
.perm-form { display: flex; flex-direction: column; gap: 14px; }
</style>
