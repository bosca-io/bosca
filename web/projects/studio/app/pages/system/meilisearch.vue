<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

/* ── Formatting helpers ────────────────────────────────────────────── */

function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '—'
  if (bytes === 0) return '0 B'
  const k = 1024
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(k)), units.length - 1)
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${units[i]}`
}

function formatNumber(n: number | null | undefined): string {
  if (n == null) return '—'
  return new Intl.NumberFormat('en-US').format(n)
}

function formatTimestamp(ts: string | null | undefined): string {
  if (!ts) return '—'
  const normalized = ts.replace(/(\.\d{3})\d+/, '$1')
  const d = new Date(normalized)
  return isNaN(d.getTime()) ? ts : d.toLocaleString()
}

/**
 * Splits a timestamp into a short hero date (e.g. "May 25") and a small
 * time sub-line (e.g. "6:30 PM") so it fits a narrow StatTile without
 * overflowing the default 28px hero font. The year is added only when
 * the timestamp falls outside the current calendar year.
 */
function formatTimestampSplit(ts: string | null | undefined): { primary: string; secondary: string } {
  if (!ts) return { primary: '—', secondary: '' }
  const normalized = ts.replace(/(\.\d{3})\d+/, '$1')
  const d = new Date(normalized)
  if (isNaN(d.getTime())) return { primary: ts, secondary: '' }
  const sameYear = d.getFullYear() === new Date().getFullYear()
  const primary = d.toLocaleDateString(undefined, sameYear
    ? { month: 'short', day: 'numeric' }
    : { year: 'numeric', month: 'short', day: 'numeric' })
  const secondary = d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
  return { primary, secondary }
}

function parseJsonOrString(value: string | null | undefined): unknown {
  if (!value) return null
  try { return JSON.parse(value) }
  catch { return value }
}

function tryParseJson(value: string | null | undefined): unknown {
  if (!value) return undefined
  if (typeof value === 'object') return value
  try { return JSON.parse(value) }
  catch { return undefined }
}

function jsonToString(val: unknown): string {
  if (val == null) return ''
  if (typeof val === 'string') return val
  return JSON.stringify(val, null, 2)
}

function stripTypename<T>(obj: T): T {
  if (obj === null || obj === undefined || typeof obj !== 'object') return obj
  if (Array.isArray(obj)) return obj.map(stripTypename) as unknown as T
  const result: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(obj as Record<string, unknown>)) {
    if (key === '__typename') continue
    result[key] = stripTypename(value)
  }
  return result as T
}

/* ── Result types ─────────────────────────────────────────────────── */

interface MeilisearchVersion {
  pkgVersion: string
  commitDate: string | null
  commitSha: string | null
}

interface MeilisearchHealth { healthy: boolean }

interface MeilisearchGlobalStats {
  numberOfIndexes: number
  databaseSize: number
  lastUpdate: string | null
}

interface MeilisearchIndexStats {
  numberOfDocuments: number
  isIndexing: boolean
}

interface MeilisearchIndex {
  uid: string
  primaryKey: string | null
  createdAt: string
  updatedAt: string
  stats: MeilisearchIndexStats
}

interface MeilisearchTaskError {
  message: string
  code: string
}

interface MeilisearchTask {
  uid: number
  type: string
  status: string
  indexUid: string | null
  enqueuedAt: string
  startedAt: string | null
  finishedAt: string | null
  duration: string | null
  error: MeilisearchTaskError | null
}

interface MeilisearchKey {
  name: string | null
  uid: string
  key: string
  actions: string[]
  indexes: string[]
  expiresAt: string | null
  createdAt: string
}

interface MeilisearchNode {
  id: string
  name: string
  description: string
  url: string
  healthy: boolean
  types: string[]
  indexes: string[]
  version: MeilisearchVersion | null
  stats: MeilisearchGlobalStats | null
  storageSystemIds: string[]
}

interface MeilisearchSynonym { word: string; synonyms: string[] }

interface MeilisearchSettings {
  rankingRules: string[]
  searchableAttributes: string[]
  filterableAttributes: string[]
  sortableAttributes: string[]
  displayedAttributes: string[]
  stopWords: string[]
  synonyms: MeilisearchSynonym[]
}

interface MeilisearchEmbedder {
  name: string
  source: string | null
  model: string | null
  dimensions: number | null
  documentTemplate: string | null
  documentTemplateMaxBytes: number | null
  url: string | null
  request: unknown
  response: unknown
  headers: unknown
}

interface StorageSystem { id: string; name: string }

interface MeilisearchOverview {
  meilisearchAdmin: {
    version: MeilisearchVersion
    health: MeilisearchHealth
    stats: MeilisearchGlobalStats
    indexes: MeilisearchIndex[]
    tasks: MeilisearchTask[]
    keys: MeilisearchKey[]
    nodes: MeilisearchNode[]
  }
}

/* ── Queries ─────────────────────────────────────────────────────── */

const overviewGql = gql`
  query GetMeilisearchAdminOverview {
    meilisearchAdmin {
      version { pkgVersion commitDate commitSha }
      health { healthy }
      stats { numberOfIndexes databaseSize lastUpdate }
      indexes {
        uid primaryKey createdAt updatedAt
        stats { numberOfDocuments isIndexing }
      }
      tasks(limit: 20) {
        uid type status indexUid
        enqueuedAt startedAt finishedAt duration
        error { message code }
      }
      keys {
        name uid key actions indexes expiresAt createdAt
      }
      nodes {
        id name description url healthy types indexes
        version { pkgVersion commitDate commitSha }
        stats { numberOfIndexes databaseSize lastUpdate }
        storageSystemIds
      }
    }
  }
`

const storageSystemsGql = gql`
  query GetStorageSystemsForMeilisearch {
    storageSystems { all { id name } }
  }
`

const indexSettingsGql = gql`
  query GetMeilisearchIndexSettings($uid: String!) {
    meilisearchAdmin {
      index(uid: $uid) {
        settings {
          rankingRules searchableAttributes filterableAttributes
          sortableAttributes displayedAttributes stopWords
          synonyms { word synonyms }
        }
      }
    }
  }
`

const indexDocumentsGql = gql`
  query GetMeilisearchIndexDocuments($uid: String!, $offset: Int, $limit: Int) {
    meilisearchAdmin {
      index(uid: $uid) {
        stats { numberOfDocuments }
        documents(offset: $offset, limit: $limit)
      }
    }
  }
`

const embeddersGql = gql`
  query GetMeilisearchEmbedders($uid: String!) {
    meilisearchAdmin {
      index(uid: $uid) {
        settings {
          embedders {
            name source model dimensions
            documentTemplate documentTemplateMaxBytes
            url request response headers
          }
        }
      }
    }
  }
`

const createIndexGql = gql`
  mutation CreateMeilisearchIndex($uid: String!, $primaryKey: String) {
    meilisearchAdmin { createIndex(uid: $uid, primaryKey: $primaryKey) { uid status } }
  }
`

const deleteIndexGql = gql`
  mutation DeleteMeilisearchIndex($uid: String!) {
    meilisearchAdmin { deleteIndex(uid: $uid) { uid status } }
  }
`

const updateSettingsGql = gql`
  mutation UpdateMeilisearchSettings($uid: String!, $settings: MeilisearchSettingsInput!) {
    meilisearchAdmin { updateSettings(uid: $uid, settings: $settings) { uid status } }
  }
`

const updateEmbeddersGql = gql`
  mutation UpdateMeilisearchEmbedders($uid: String!, $embedders: [MeilisearchEmbedderInput!]!) {
    meilisearchAdmin { updateEmbedders(uid: $uid, embedders: $embedders) { uid status } }
  }
`

const resetEmbeddersGql = gql`
  mutation ResetMeilisearchEmbedders($uid: String!) {
    meilisearchAdmin { resetEmbedders(uid: $uid) { uid status } }
  }
`

const deleteAllDocumentsGql = gql`
  mutation DeleteAllMeilisearchDocuments($uid: String!) {
    meilisearchAdmin { deleteAllDocuments(uid: $uid) { uid status } }
  }
`

const cancelTasksGql = gql`
  mutation CancelMeilisearchTasks($uids: [Int!]!) {
    meilisearchAdmin { cancelTasks(uids: $uids) { uid status } }
  }
`

const addNodeGql = gql`
  mutation AddMeilisearchNode($input: MeilisearchNodeInput!) {
    meilisearchAdmin { addNode(input: $input) { id name } }
  }
`

const editNodeGql = gql`
  mutation EditMeilisearchNode($id: UUID!, $input: MeilisearchEditNodeInput!) {
    meilisearchAdmin { editNode(id: $id, input: $input) { id name } }
  }
`

const deleteNodeGql = gql`
  mutation DeleteMeilisearchNode($id: UUID!) {
    meilisearchAdmin { deleteNode(id: $id) }
  }
`

const assignNodeToStorageGql = gql`
  mutation AssignMeilisearchNodeToStorage($nodeId: UUID!, $storageSystemId: UUID!) {
    meilisearchAdmin { assignNodeToStorageSystem(nodeId: $nodeId, storageSystemId: $storageSystemId) }
  }
`

const removeNodeFromStorageGql = gql`
  mutation RemoveMeilisearchNodeFromStorage($nodeId: UUID!, $storageSystemId: UUID!) {
    meilisearchAdmin { removeNodeFromStorageSystem(nodeId: $nodeId, storageSystemId: $storageSystemId) }
  }
`

/* ── State ────────────────────────────────────────────────────────── */

const TABS = ['Nodes', 'Indexes', 'Tasks', 'Keys'] as const
const activeTab = ref<(typeof TABS)[number]>('Nodes')

const { data: overviewData, refresh: refreshOverview } = useAsyncQuery<MeilisearchOverview>(
  'meilisearch-admin-overview',
  overviewGql,
)
const { data: storageSystemsData } = useAsyncQuery<{ storageSystems: { all: StorageSystem[] } }>(
  'meilisearch-storage-systems',
  storageSystemsGql,
)

const root = computed(() => overviewData.value?.meilisearchAdmin ?? null)
const version = computed<MeilisearchVersion | null>(() => root.value?.version ?? null)
const health = computed<MeilisearchHealth | null>(() => root.value?.health ?? null)
const stats = computed<MeilisearchGlobalStats | null>(() => root.value?.stats ?? null)
const indexes = computed<MeilisearchIndex[]>(() => root.value?.indexes ?? [])
const tasks = computed<MeilisearchTask[]>(() => root.value?.tasks ?? [])
const keys = computed<MeilisearchKey[]>(() => root.value?.keys ?? [])
const nodes = computed<MeilisearchNode[]>(() => root.value?.nodes ?? [])
const storageSystems = computed<StorageSystem[]>(() => storageSystemsData.value?.storageSystems?.all ?? [])

const expandedTaskUid = ref<number | null>(null)

/* ── Node modals ──────────────────────────────────────────────────── */

const nodeModalOpen = ref(false)
const editingNode = ref<MeilisearchNode | null>(null)
const nodeForm = reactive({ name: '', description: '', url: '', key: '', types: [] as string[] })
const nodeSaving = ref(false)
const nodeError = ref('')

const assignStorageModalOpen = ref(false)
const assignStorageNodeId = ref('')
const assignStorageSystemId = ref('')
const assignStorageSaving = ref(false)

function resetNodeForm() {
  nodeForm.name = ''
  nodeForm.description = ''
  nodeForm.url = ''
  nodeForm.key = ''
  nodeForm.types = []
  nodeError.value = ''
}

function openAddNodeModal() {
  editingNode.value = null
  resetNodeForm()
  nodeModalOpen.value = true
}

function openEditNodeModal(node: MeilisearchNode) {
  editingNode.value = node
  nodeForm.name = node.name
  nodeForm.description = node.description ?? ''
  nodeForm.url = node.url
  nodeForm.key = ''
  nodeForm.types = [...node.types]
  nodeError.value = ''
  nodeModalOpen.value = true
}

function toggleNodeType(type: string) {
  const idx = nodeForm.types.indexOf(type)
  if (idx >= 0) nodeForm.types.splice(idx, 1)
  else nodeForm.types.push(type)
}

async function handleSaveNode() {
  if (!nodeForm.name || !nodeForm.url) {
    nodeError.value = 'Name and URL are required.'
    return
  }
  if (!editingNode.value && !nodeForm.key) {
    nodeError.value = 'API key is required for a new node.'
    return
  }
  nodeSaving.value = true
  nodeError.value = ''
  try {
    const input: Record<string, unknown> = {
      name: nodeForm.name,
      description: nodeForm.description,
      url: nodeForm.url,
      types: nodeForm.types,
    }
    if (!editingNode.value || nodeForm.key) input.key = nodeForm.key
    if (editingNode.value) {
      await gqlMutation(editNodeGql, { id: editingNode.value.id, input })
      toast.success(`Updated ${nodeForm.name}`)
    } else {
      await gqlMutation(addNodeGql, { input })
      toast.success(`Added ${nodeForm.name}`)
    }
    nodeModalOpen.value = false
    await refreshOverview()
  } catch (e: unknown) {
    nodeError.value = e instanceof Error ? e.message : 'Failed to save node'
  } finally {
    nodeSaving.value = false
  }
}

const nodeToDelete = ref<MeilisearchNode | null>(null)
const deleteNodeOpen = ref(false)
const deletingNode = ref(false)

function openDeleteNode(node: MeilisearchNode) {
  nodeToDelete.value = node
  deleteNodeOpen.value = true
}

async function handleDeleteNode() {
  if (!nodeToDelete.value) return
  deletingNode.value = true
  try {
    await gqlMutation(deleteNodeGql, { id: nodeToDelete.value.id })
    toast.success(`Deleted ${nodeToDelete.value.name}`)
    deleteNodeOpen.value = false
    await refreshOverview()
  } catch (e: unknown) {
    toast.error(`Failed to delete node: ${e instanceof Error ? e.message : 'Unknown error'}`)
  } finally {
    deletingNode.value = false
  }
}

const availableStorageOptions = computed(() => {
  const node = nodes.value.find(n => n.id === assignStorageNodeId.value)
  const assigned = new Set(node?.storageSystemIds ?? [])
  return storageSystems.value
    .filter(s => !assigned.has(s.id))
    .map(s => ({ value: s.id, label: s.name }))
})

function openAssignStorageModal(nodeId: string) {
  assignStorageNodeId.value = nodeId
  assignStorageSystemId.value = ''
  assignStorageModalOpen.value = true
}

async function handleAssignStorage() {
  if (!assignStorageNodeId.value || !assignStorageSystemId.value) return
  assignStorageSaving.value = true
  try {
    await gqlMutation(assignNodeToStorageGql, {
      nodeId: assignStorageNodeId.value,
      storageSystemId: assignStorageSystemId.value,
    })
    toast.success('Storage system assigned')
    assignStorageModalOpen.value = false
    await refreshOverview()
  } catch (e: unknown) {
    toast.error(`Failed to assign storage: ${e instanceof Error ? e.message : 'Unknown error'}`)
  } finally {
    assignStorageSaving.value = false
  }
}

async function handleRemoveStorage(nodeId: string, storageSystemId: string) {
  if (!confirm('Remove this storage system assignment?')) return
  try {
    await gqlMutation(removeNodeFromStorageGql, { nodeId, storageSystemId })
    toast.success('Storage system removed')
    await refreshOverview()
  } catch (e: unknown) {
    toast.error(`Failed to remove storage: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

function storageSystemName(id: string): string {
  return storageSystems.value.find(s => s.id === id)?.name ?? id
}

/* ── Index creation / deletion ─────────────────────────────────────── */

const createIndexModalOpen = ref(false)
const newIndexUid = ref('')
const newIndexPrimaryKey = ref('')
const createIndexSaving = ref(false)
const createIndexError = ref('')

function openCreateIndexModal() {
  newIndexUid.value = ''
  newIndexPrimaryKey.value = ''
  createIndexError.value = ''
  createIndexModalOpen.value = true
}

async function handleCreateIndex() {
  if (!newIndexUid.value) {
    createIndexError.value = 'UID is required.'
    return
  }
  createIndexSaving.value = true
  createIndexError.value = ''
  try {
    await gqlMutation(createIndexGql, {
      uid: newIndexUid.value,
      primaryKey: newIndexPrimaryKey.value || null,
    })
    toast.success(`Index ${newIndexUid.value} creation queued`)
    createIndexModalOpen.value = false
    await refreshOverview()
  } catch (e: unknown) {
    createIndexError.value = e instanceof Error ? e.message : 'Failed to create index'
  } finally {
    createIndexSaving.value = false
  }
}

async function handleDeleteIndex(uid: string) {
  if (!confirm(`Delete index "${uid}"? This cannot be undone.`)) return
  try {
    await gqlMutation(deleteIndexGql, { uid })
    toast.success(`Index ${uid} deletion queued`)
    indexDrawerOpen.value = false
    await refreshOverview()
  } catch (e: unknown) {
    toast.error(`Failed to delete index: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

/* ── Index drawer (Stats / Settings / Embedders / Documents) ───────── */

const indexDrawerOpen = ref(false)
const selectedIndex = ref<MeilisearchIndex | null>(null)
const indexSettings = ref<MeilisearchSettings | null>(null)
const indexEmbedders = ref<MeilisearchEmbedder[]>([])
const indexDocumentsResults = ref<unknown[]>([])
const indexDocumentsTotal = ref(0)
const loadingIndexDetails = ref(false)
const documentsOffset = ref(0)
const documentsLimit = ref(20)
const INDEX_DETAIL_TABS = ['Stats', 'Settings', 'Embedders', 'Documents'] as const
const indexDetailTab = ref<(typeof INDEX_DETAIL_TABS)[number]>('Stats')

async function openIndexDrawer(idx: MeilisearchIndex) {
  selectedIndex.value = idx
  indexDetailTab.value = 'Stats'
  indexDrawerOpen.value = true
  loadingIndexDetails.value = true
  documentsOffset.value = 0
  try {
    const [settingsRes, embeddersRes, docsRes] = await Promise.all([
      gqlQuery<{ meilisearchAdmin: { index: { settings: MeilisearchSettings } | null } }>(
        indexSettingsGql,
        { uid: idx.uid },
      ),
      gqlQuery<{ meilisearchAdmin: { index: { settings: { embedders: MeilisearchEmbedder[] } } | null } }>(
        embeddersGql,
        { uid: idx.uid },
      ),
      gqlQuery<{ meilisearchAdmin: { index: { stats: MeilisearchIndexStats; documents: unknown[] } | null } }>(
        indexDocumentsGql,
        { uid: idx.uid, offset: 0, limit: documentsLimit.value },
      ),
    ])
    indexSettings.value = settingsRes.meilisearchAdmin?.index?.settings ?? null
    indexEmbedders.value = embeddersRes.meilisearchAdmin?.index?.settings?.embedders ?? []
    const idxData = docsRes.meilisearchAdmin?.index
    indexDocumentsResults.value = idxData?.documents ?? []
    indexDocumentsTotal.value = idxData?.stats?.numberOfDocuments ?? 0
  } catch (e: unknown) {
    toast.error(`Failed to load index details: ${e instanceof Error ? e.message : 'Unknown error'}`)
  } finally {
    loadingIndexDetails.value = false
  }
}

async function loadDocumentsPage(offset: number) {
  if (!selectedIndex.value) return
  try {
    const res = await gqlQuery<{ meilisearchAdmin: { index: { stats: MeilisearchIndexStats; documents: unknown[] } | null } }>(
      indexDocumentsGql,
      { uid: selectedIndex.value.uid, offset, limit: documentsLimit.value },
    )
    const idxData = res.meilisearchAdmin?.index
    indexDocumentsResults.value = idxData?.documents ?? []
    indexDocumentsTotal.value = idxData?.stats?.numberOfDocuments ?? 0
    documentsOffset.value = offset
  } catch (e: unknown) {
    toast.error(`Failed to load documents: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

async function handleDeleteAllDocuments() {
  if (!selectedIndex.value) return
  const uid = selectedIndex.value.uid
  if (!confirm(`Delete all documents from "${uid}"? This cannot be undone.`)) return
  try {
    await gqlMutation(deleteAllDocumentsGql, { uid })
    toast.success(`Document deletion queued for ${uid}`)
    if (selectedIndex.value) await openIndexDrawer(selectedIndex.value)
    await refreshOverview()
  } catch (e: unknown) {
    toast.error(`Failed to delete documents: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

async function handleUpdateSettings() {
  if (!selectedIndex.value || !indexSettings.value) return
  const cleaned = stripTypename({ ...indexSettings.value })
  try {
    await gqlMutation(updateSettingsGql, { uid: selectedIndex.value.uid, settings: cleaned })
    toast.success('Settings update queued')
    await refreshOverview()
  } catch (e: unknown) {
    toast.error(`Failed to update settings: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

/* ── Embedder editor ───────────────────────────────────────────────── */

const EMBEDDER_SOURCES = [
  { value: 'openAi', label: 'openAi' },
  { value: 'huggingFace', label: 'huggingFace' },
  { value: 'ollama', label: 'ollama' },
  { value: 'rest', label: 'rest' },
  { value: 'userProvided', label: 'userProvided' },
]

interface EmbedderForm {
  name: string
  source: string
  model: string
  key: string
  dimensions: number | null
  documentTemplate: string
  documentTemplateMaxBytes: number | null
  url: string
  request: string
  response: string
  headers: string
}

const embedderModalOpen = ref(false)
const editingEmbedder = ref<MeilisearchEmbedder | null>(null)
const embedderForm = reactive<EmbedderForm>({
  name: '', source: '', model: '', key: '',
  dimensions: null, documentTemplate: '', documentTemplateMaxBytes: null,
  url: '', request: '', response: '', headers: '',
})
const embedderSaving = ref(false)
const embedderError = ref('')

function resetEmbedderForm() {
  embedderForm.name = ''
  embedderForm.source = ''
  embedderForm.model = ''
  embedderForm.key = ''
  embedderForm.dimensions = null
  embedderForm.documentTemplate = ''
  embedderForm.documentTemplateMaxBytes = null
  embedderForm.url = ''
  embedderForm.request = ''
  embedderForm.response = ''
  embedderForm.headers = ''
  embedderError.value = ''
}

function openAddEmbedderModal() {
  editingEmbedder.value = null
  resetEmbedderForm()
  embedderModalOpen.value = true
}

function openEditEmbedderModal(emb: MeilisearchEmbedder) {
  editingEmbedder.value = emb
  embedderForm.name = emb.name
  embedderForm.source = emb.source ?? ''
  embedderForm.model = emb.model ?? ''
  embedderForm.key = ''
  embedderForm.dimensions = emb.dimensions ?? null
  embedderForm.documentTemplate = emb.documentTemplate ?? ''
  embedderForm.documentTemplateMaxBytes = emb.documentTemplateMaxBytes ?? null
  embedderForm.url = emb.url ?? ''
  embedderForm.request = jsonToString(emb.request)
  embedderForm.response = jsonToString(emb.response)
  embedderForm.headers = jsonToString(emb.headers)
  embedderError.value = ''
  embedderModalOpen.value = true
}

function toEmbedderInput(e: EmbedderForm | MeilisearchEmbedder): Record<string, unknown> {
  const input: Record<string, unknown> = { name: e.name, source: e.source }
  if (e.model) input.model = e.model
  if ('key' in e && e.key) input.key = e.key
  if (e.dimensions) input.dimensions = e.dimensions
  if (e.documentTemplate) input.documentTemplate = e.documentTemplate
  if (e.documentTemplateMaxBytes) input.documentTemplateMaxBytes = e.documentTemplateMaxBytes
  if (e.url) input.url = e.url
  const req = tryParseJson(typeof e.request === 'string' ? e.request : jsonToString(e.request))
  if (req !== undefined) input.request = req
  const res = tryParseJson(typeof e.response === 'string' ? e.response : jsonToString(e.response))
  if (res !== undefined) input.response = res
  const hdrs = tryParseJson(typeof e.headers === 'string' ? e.headers : jsonToString(e.headers))
  if (hdrs !== undefined) input.headers = hdrs
  return input
}

async function refreshEmbedders() {
  if (!selectedIndex.value) return
  try {
    const res = await gqlQuery<{ meilisearchAdmin: { index: { settings: { embedders: MeilisearchEmbedder[] } } | null } }>(
      embeddersGql,
      { uid: selectedIndex.value.uid },
    )
    indexEmbedders.value = res.meilisearchAdmin?.index?.settings?.embedders ?? []
  } catch { /* surfaced via the original toast */ }
}

async function handleSaveEmbedder() {
  if (!selectedIndex.value) return
  if (!embedderForm.name || !embedderForm.source) {
    embedderError.value = 'Name and source are required.'
    return
  }
  embedderSaving.value = true
  embedderError.value = ''
  try {
    const existing = indexEmbedders.value.map(e => toEmbedderInput(e))
    const formInput = toEmbedderInput(embedderForm)
    if (editingEmbedder.value) {
      const idx = existing.findIndex(e => e.name === editingEmbedder.value?.name)
      if (idx >= 0) existing[idx] = formInput
      else existing.push(formInput)
    } else {
      existing.push(formInput)
    }
    await gqlMutation(updateEmbeddersGql, { uid: selectedIndex.value.uid, embedders: existing })
    toast.success(`Embedder ${embedderForm.name} saved`)
    embedderModalOpen.value = false
    await refreshEmbedders()
  } catch (e: unknown) {
    embedderError.value = e instanceof Error ? e.message : 'Failed to save embedder'
  } finally {
    embedderSaving.value = false
  }
}

async function handleRemoveEmbedder(name: string) {
  if (!selectedIndex.value) return
  if (!confirm(`Remove embedder "${name}"?`)) return
  try {
    const remaining = indexEmbedders.value
      .filter(e => e.name !== name)
      .map(e => toEmbedderInput(e))
    if (remaining.length === 0) {
      await gqlMutation(resetEmbeddersGql, { uid: selectedIndex.value.uid })
    } else {
      await gqlMutation(updateEmbeddersGql, { uid: selectedIndex.value.uid, embedders: remaining })
    }
    toast.success(`Embedder ${name} removed`)
    await refreshEmbedders()
  } catch (e: unknown) {
    toast.error(`Failed to remove embedder: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

/* ── Tasks ────────────────────────────────────────────────────────── */

async function handleCancelTask(uid: number) {
  if (!confirm(`Cancel task #${uid}?`)) return
  try {
    await gqlMutation(cancelTasksGql, { uids: [uid] })
    toast.success(`Cancel queued for task #${uid}`)
    await refreshOverview()
  } catch (e: unknown) {
    toast.error(`Failed to cancel task: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

function taskStatusColor(status: string): string {
  switch (status?.toLowerCase()) {
    case 'succeeded': return ACCENT_OK
    case 'failed': return ACCENT_ERR
    case 'processing': return ACCENT_INFO
    case 'enqueued': return ACCENT_WARN
    case 'canceled': return ACCENT_NEUTRAL
    default: return ACCENT_NEUTRAL
  }
}

function toggleTaskRow(uid: number) {
  expandedTaskUid.value = expandedTaskUid.value === uid ? null : uid
}

/* ── Auto-refresh overview every 10s ───────────────────────────────── */

let refreshTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  refreshTimer = setInterval(() => {
    refreshOverview().catch(() => { /* surfaced individually */ })
  }, 10000)
})
onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})

/* ── Display helpers ───────────────────────────────────────────────── */

const ACCENT_OK = '#34d99a'
const ACCENT_WARN = '#ffb547'
const ACCENT_ERR = '#ff5d6c'
const ACCENT_INFO = '#5ec5ff'
const ACCENT_NEUTRAL = '#6c7388'

const NODE_TYPE_COLORS: Record<string, string> = {
  SEARCH: ACCENT_INFO,
  VECTOR: '#a78bff',
  SUPPLEMENTARY: ACCENT_OK,
}

/* ── GlassTable column definitions ─────────────────────────────────── */

const nodeColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(160px, 1.2fr)' },
  { key: 'url', label: 'URL', width: '2fr', muted: true },
  { key: 'healthy', label: 'Health', width: '120px' },
  { key: 'types', label: 'Types', width: '180px' },
  { key: 'numberOfIndexes', label: 'Indexes', width: '90px', align: 'right' },
  { key: 'pkgVersion', label: 'Version', width: '120px', muted: true },
]

const indexColumns: GlassTableColumn[] = [
  { key: 'uid', label: 'UID', width: 'minmax(180px, 1.5fr)' },
  { key: 'primaryKey', label: 'Primary Key', width: '140px', muted: true },
  { key: 'numberOfDocuments', label: 'Documents', width: '130px', align: 'right' },
  { key: 'createdAt', label: 'Created', width: '170px', muted: true },
  { key: 'updatedAt', label: 'Updated', width: '170px', muted: true },
  { key: 'isIndexing', label: 'Indexing', width: '100px' },
]

const keyColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(160px, 1fr)' },
  { key: 'uid', label: 'UID', width: '160px', muted: true },
  { key: 'actions', label: 'Actions', width: '2fr', muted: true },
  { key: 'indexes', label: 'Indexes', width: '1fr', muted: true },
  { key: 'expiresAt', label: 'Expires', width: '170px', muted: true },
]

/* ── Subtitle ──────────────────────────────────────────────────────── */

const meiliSubtitle = computed(() => {
  if (!version.value) return undefined
  const parts = [`v${version.value.pkgVersion}`]
  if (health.value) parts.push(health.value.healthy ? 'healthy' : 'unhealthy')
  return parts.join(' · ')
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Meilisearch')"
        title="Meilisearch"
        :subtitle="meiliSubtitle"
        :tabs="[...TABS]"
        :active-tab="activeTab"
        @tab="(t: string) => activeTab = t as typeof TABS[number]"
      >
        <template #actions>
          <Badge v-if="health" :color="health.healthy ? ACCENT_OK : ACCENT_ERR">
            {{ health.healthy ? 'Healthy' : 'Unhealthy' }}
          </Badge>
          <Button size="sm" icon="pulse" @click="refreshOverview()">Refresh</Button>
        </template>
      </PageHeader>
    </template>

    <!-- Server Overview -->
    <SectionCard title="Server Overview" padded>
      <div class="stat-grid">
        <StatTile
          label="Status"
          :value="health ? (health.healthy ? 'Healthy' : 'Unhealthy') : '—'"
          :accent="health ? (health.healthy ? ACCENT_OK : ACCENT_ERR) : undefined"
        />
        <StatTile label="Version" :value="version?.pkgVersion ?? '—'" :accent="accent" />
        <StatTile label="Indexes" :value="stats ? formatNumber(stats.numberOfIndexes) : '—'" />
        <StatTile label="Database Size" :value="stats ? formatBytes(stats.databaseSize) : '—'" />
        <StatTile
          label="Last Update"
          :value="stats?.lastUpdate ? formatTimestampSplit(stats.lastUpdate).primary : '—'"
          :sub="stats?.lastUpdate ? formatTimestampSplit(stats.lastUpdate).secondary : undefined"
        />
        <StatTile
          label="Commit"
          :value="version?.commitSha ? version.commitSha.substring(0, 10) : '—'"
        />
      </div>
    </SectionCard>

    <!-- ── Nodes Tab ─────────────────────────────────────────────── -->
    <template v-if="activeTab === 'Nodes'">
      <SectionCard
        :title="`Nodes (${nodes.length})`"
        padded
      >
        <template #right>
          <Button
            primary
            size="sm"
            icon="plus"
            :accent="accent"
            @click="openAddNodeModal">Add Node</Button>
        </template>

        <GlassTable
          v-if="nodes.length > 0"
          :columns="nodeColumns"
          :rows="nodes"
          row-key="id"
          @row-click="openEditNodeModal"
        >
          <template #col-name="{ row }">
            <span class="mono" style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span>
          </template>
          <template #col-url="{ row }">
            <span class="mono small">{{ row.url }}</span>
          </template>
          <template #col-healthy="{ row }">
            <Badge :color="row.healthy ? ACCENT_OK : ACCENT_ERR">{{ row.healthy ? 'Healthy' : 'Unhealthy' }}</Badge>
          </template>
          <template #col-types="{ row }">
            <div class="badge-row">
              <Badge v-for="t in row.types" :key="t" :color="NODE_TYPE_COLORS[t] || ACCENT_NEUTRAL">{{ t }}</Badge>
              <span v-if="!row.types?.length" class="muted">—</span>
            </div>
          </template>
          <template #col-numberOfIndexes="{ row }">
            <span class="mono tabular">{{ row.stats?.numberOfIndexes ?? '—' }}</span>
          </template>
          <template #col-pkgVersion="{ row }">
            <span class="mono small">{{ row.version?.pkgVersion || '—' }}</span>
          </template>
        </GlassTable>
        <div v-else class="empty-state">No Meilisearch nodes configured.</div>

        <div v-if="nodes.length > 0" class="node-cards">
          <div v-for="node in nodes" :key="node.id" class="node-card">
            <div class="node-header">
              <span class="node-title">{{ node.name }}</span>
              <div class="node-actions">
                <Button size="xs" icon="link" @click="openAssignStorageModal(node.id)">Assign Storage</Button>
                <Button size="xs" icon="edit" @click="openEditNodeModal(node)">Edit</Button>
                <Button size="xs" icon="trash" @click="openDeleteNode(node)">Delete</Button>
              </div>
            </div>
            <p v-if="node.description" class="node-description">{{ node.description }}</p>
            <div v-if="node.storageSystemIds?.length" class="storage-row">
              <span class="kv-label">Storage Systems</span>
              <button
                v-for="ssId in node.storageSystemIds"
                :key="ssId"
                type="button"
                class="storage-chip"
                @click="handleRemoveStorage(node.id, ssId)"
              >
                {{ storageSystemName(ssId) }}
                <Icon name="x" :size="10" color="var(--fg-3)" />
              </button>
            </div>
            <p v-else class="node-empty">No storage system assignments.</p>
          </div>
        </div>
      </SectionCard>
    </template>

    <!-- ── Indexes Tab ───────────────────────────────────────────── -->
    <template v-if="activeTab === 'Indexes'">
      <SectionCard
        :title="`Indexes (${indexes.length})`"
        padded
      >
        <template #right>
          <Button
            primary
            size="sm"
            icon="plus"
            :accent="accent"
            @click="openCreateIndexModal">Create Index</Button>
        </template>

        <GlassTable
          :columns="indexColumns"
          :rows="indexes"
          row-key="uid"
          empty-text="No indexes found."
          @row-click="openIndexDrawer"
        >
          <template #col-uid="{ row }">
            <span class="mono" style="font-weight: 500; color: var(--fg-0)">{{ row.uid }}</span>
          </template>
          <template #col-primaryKey="{ row }">
            <span class="mono">{{ row.primaryKey || '—' }}</span>
          </template>
          <template #col-numberOfDocuments="{ row }">
            <span class="mono tabular">{{ formatNumber(row.stats?.numberOfDocuments ?? 0) }}</span>
          </template>
          <template #col-createdAt="{ row }">
            <span class="mono small">{{ formatTimestamp(row.createdAt) }}</span>
          </template>
          <template #col-updatedAt="{ row }">
            <span class="mono small">{{ formatTimestamp(row.updatedAt) }}</span>
          </template>
          <template #col-isIndexing="{ row }">
            <Badge :color="row.stats?.isIndexing ? ACCENT_WARN : ACCENT_OK">
              {{ row.stats?.isIndexing ? 'Indexing' : 'Idle' }}
            </Badge>
          </template>
        </GlassTable>
      </SectionCard>
    </template>

    <!-- ── Tasks Tab ─────────────────────────────────────────────── -->
    <template v-if="activeTab === 'Tasks'">
      <SectionCard :title="`Recent Tasks (${tasks.length})`">
        <div v-if="tasks.length === 0" class="empty-state">No tasks found.</div>
        <div v-else class="task-list">
          <div v-for="task in tasks" :key="task.uid" class="task-item">
            <button
              type="button"
              class="task-summary"
              @click="toggleTaskRow(task.uid)"
            >
              <span class="mono tabular task-uid">{{ task.uid }}</span>
              <span class="mono task-type">{{ task.type }}</span>
              <Badge :color="taskStatusColor(task.status)">{{ task.status }}</Badge>
              <span class="muted task-index">{{ task.indexUid || '—' }}</span>
              <span class="mono small task-time">{{ formatTimestamp(task.enqueuedAt) }}</span>
              <button
                v-if="task.status === 'enqueued' || task.status === 'processing'"
                type="button"
                class="task-cancel"
                @click.stop="handleCancelTask(task.uid)"
              >Cancel</button>
            </button>
            <div v-if="expandedTaskUid === task.uid" class="task-expanded">
              <div class="task-meta">
                <span><span class="kv-label">UID:</span> <span class="mono tabular">{{ task.uid }}</span></span>
                <span><span class="kv-label">Type:</span> <span class="mono">{{ task.type }}</span></span>
                <span><span class="kv-label">Index:</span> <span class="mono">{{ task.indexUid || '—' }}</span></span>
                <span><span class="kv-label">Enqueued:</span> <span class="mono small">{{ formatTimestamp(task.enqueuedAt) }}</span></span>
                <span><span class="kv-label">Started:</span> <span class="mono small">{{ formatTimestamp(task.startedAt) }}</span></span>
                <span><span class="kv-label">Finished:</span> <span class="mono small">{{ formatTimestamp(task.finishedAt) }}</span></span>
                <span><span class="kv-label">Duration:</span> <span class="mono">{{ task.duration || '—' }}</span></span>
              </div>
              <div v-if="task.error" class="task-error">
                <div class="task-error-title">Error: {{ task.error.code }}</div>
                <div class="task-error-message">{{ task.error.message }}</div>
              </div>
            </div>
          </div>
        </div>
      </SectionCard>
    </template>

    <!-- ── Keys Tab ──────────────────────────────────────────────── -->
    <template v-if="activeTab === 'Keys'">
      <SectionCard :title="`API Keys (${keys.length})`">
        <GlassTable
          :columns="keyColumns"
          :rows="keys"
          row-key="uid"
          empty-text="No API keys found."
          :arrow="false"
        >
          <template #col-name="{ row }">
            <span class="mono" style="font-weight: 500">{{ row.name || '—' }}</span>
          </template>
          <template #col-uid="{ row }">
            <span class="mono small">{{ row.uid ? `${row.uid.substring(0, 12)}…` : '—' }}</span>
          </template>
          <template #col-actions="{ row }">
            <span class="mono small">{{ row.actions?.join(', ') || '—' }}</span>
          </template>
          <template #col-indexes="{ row }">
            <span class="mono small">{{ row.indexes?.join(', ') || '*' }}</span>
          </template>
          <template #col-expiresAt="{ row }">
            <span class="mono small">{{ row.expiresAt ? formatTimestamp(row.expiresAt) : 'Never' }}</span>
          </template>
        </GlassTable>
      </SectionCard>
    </template>

    <!-- ── Node modal ────────────────────────────────────────────── -->
    <Modal
      v-if="nodeModalOpen"
      :title="editingNode ? 'Edit Node' : 'Add Node'"
      icon="database"
      :accent="accent"
      width="540px"
      @close="nodeModalOpen = false"
    >
      <div class="form-stack">
        <TextInput v-model="nodeForm.name" label="Name" placeholder="Node name" />
        <TextInput v-model="nodeForm.description" label="Description" placeholder="Optional" />
        <TextInput
          v-model="nodeForm.url"
          label="URL"
          placeholder="http://localhost:7700"
          mono />
        <div class="form-field">
          <label class="field-label">API Key</label>
          <input
            v-model="nodeForm.key"
            type="password"
            class="password-input"
            :placeholder="editingNode ? 'Leave blank to keep current key' : 'Enter Meilisearch API key'"
          >
        </div>
        <div class="form-field">
          <label class="field-label">Types</label>
          <div class="checkbox-row">
            <label class="checkbox-label">
              <input type="checkbox" :checked="nodeForm.types.includes('SEARCH')" @change="toggleNodeType('SEARCH')" >
              SEARCH
            </label>
            <label class="checkbox-label">
              <input type="checkbox" :checked="nodeForm.types.includes('VECTOR')" @change="toggleNodeType('VECTOR')" >
              VECTOR
            </label>
          </div>
        </div>
        <p v-if="nodeError" class="form-error">{{ nodeError }}</p>
      </div>
      <template #footer>
        <Button @click="nodeModalOpen = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="nodeSaving"
          @click="handleSaveNode"
        >{{ nodeSaving ? 'Saving…' : 'Save' }}</Button>
      </template>
    </Modal>

    <!-- ── Delete node confirm ───────────────────────────────────── -->
    <ConfirmModal
      v-if="deleteNodeOpen"
      title="Delete Node"
      :loading="deletingNode"
      @close="deleteNodeOpen = false"
      @confirm="handleDeleteNode"
    >
      <p>Delete node <strong>{{ nodeToDelete?.name }}</strong>?</p>
    </ConfirmModal>

    <!-- ── Assign storage modal ──────────────────────────────────── -->
    <Modal
      v-if="assignStorageModalOpen"
      title="Assign to Storage System"
      icon="link"
      :accent="accent"
      width="440px"
      @close="assignStorageModalOpen = false"
    >
      <div class="form-stack">
        <div class="form-field">
          <label class="field-label">Storage System</label>
          <Select
            v-model="assignStorageSystemId"
            :options="availableStorageOptions"
            placeholder="Select a storage system"
            :accent="accent"
            searchable
          />
        </div>
      </div>
      <template #footer>
        <Button @click="assignStorageModalOpen = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="!assignStorageSystemId || assignStorageSaving"
          @click="handleAssignStorage"
        >{{ assignStorageSaving ? 'Assigning…' : 'Assign' }}</Button>
      </template>
    </Modal>

    <!-- ── Create index modal ────────────────────────────────────── -->
    <Modal
      v-if="createIndexModalOpen"
      title="Create Index"
      icon="plus"
      :accent="accent"
      width="440px"
      @close="createIndexModalOpen = false"
    >
      <div class="form-stack">
        <TextInput
          v-model="newIndexUid"
          label="UID"
          placeholder="my-index"
          mono />
        <TextInput
          v-model="newIndexPrimaryKey"
          label="Primary Key (optional)"
          placeholder="id"
          mono />
        <p v-if="createIndexError" class="form-error">{{ createIndexError }}</p>
      </div>
      <template #footer>
        <Button @click="createIndexModalOpen = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="!newIndexUid || createIndexSaving"
          @click="handleCreateIndex"
        >{{ createIndexSaving ? 'Creating…' : 'Create' }}</Button>
      </template>
    </Modal>

    <!-- ── Index drawer ──────────────────────────────────────────── -->
    <Drawer
      v-if="indexDrawerOpen && selectedIndex"
      :title="selectedIndex.uid"
      subtitle="Index"
      icon="search"
      :accent="accent"
      width="900px"
      @close="indexDrawerOpen = false"
    >
      <template #actions>
        <Button size="sm" icon="trash" @click="handleDeleteAllDocuments">Delete All Docs</Button>
        <Button size="sm" icon="trash" @click="handleDeleteIndex(selectedIndex.uid)">Delete Index</Button>
      </template>

      <Tabs v-model="indexDetailTab" :tabs="[...INDEX_DETAIL_TABS]" :accent="accent" />

      <div v-if="loadingIndexDetails" class="empty-state">Loading index details…</div>

      <!-- Stats sub-tab -->
      <div v-else-if="indexDetailTab === 'Stats'" class="drawer-section">
        <div class="drawer-stat-grid">
          <StatTile label="Documents" :value="formatNumber(selectedIndex.stats?.numberOfDocuments ?? 0)" :accent="accent" />
          <StatTile
            label="Status"
            :value="selectedIndex.stats?.isIndexing ? 'Indexing' : 'Idle'"
            :accent="selectedIndex.stats?.isIndexing ? ACCENT_WARN : ACCENT_OK"
          />
          <StatTile label="Primary Key" :value="selectedIndex.primaryKey || '—'" />
          <StatTile
            label="Created"
            :value="formatTimestampSplit(selectedIndex.createdAt).primary"
            :sub="formatTimestampSplit(selectedIndex.createdAt).secondary"
          />
          <StatTile
            label="Updated"
            :value="formatTimestampSplit(selectedIndex.updatedAt).primary"
            :sub="formatTimestampSplit(selectedIndex.updatedAt).secondary"
          />
        </div>
      </div>

      <!-- Settings sub-tab -->
      <div v-else-if="indexDetailTab === 'Settings' && indexSettings" class="drawer-section">
        <h4 class="detail-heading">Ranking Rules</h4>
        <div class="badge-row">
          <Badge v-for="rule in indexSettings.rankingRules" :key="rule" :color="ACCENT_NEUTRAL">{{ rule }}</Badge>
          <span v-if="!indexSettings.rankingRules.length" class="muted">None</span>
        </div>

        <h4 class="detail-heading">Searchable Attributes</h4>
        <div class="badge-row">
          <Badge v-for="a in indexSettings.searchableAttributes" :key="a" :color="ACCENT_NEUTRAL">{{ a }}</Badge>
          <span v-if="!indexSettings.searchableAttributes.length" class="muted">None</span>
        </div>

        <h4 class="detail-heading">Filterable Attributes</h4>
        <div class="badge-row">
          <Badge v-for="a in indexSettings.filterableAttributes" :key="a" :color="ACCENT_INFO">{{ a }}</Badge>
          <span v-if="!indexSettings.filterableAttributes.length" class="muted">None</span>
        </div>

        <h4 class="detail-heading">Sortable Attributes</h4>
        <div class="badge-row">
          <Badge v-for="a in indexSettings.sortableAttributes" :key="a" :color="ACCENT_WARN">{{ a }}</Badge>
          <span v-if="!indexSettings.sortableAttributes.length" class="muted">None</span>
        </div>

        <h4 class="detail-heading">Displayed Attributes</h4>
        <div class="badge-row">
          <Badge v-for="a in indexSettings.displayedAttributes" :key="a" :color="ACCENT_NEUTRAL">{{ a }}</Badge>
          <span v-if="!indexSettings.displayedAttributes.length" class="muted">None</span>
        </div>

        <h4 class="detail-heading">Stop Words</h4>
        <div class="badge-row">
          <Badge v-for="w in indexSettings.stopWords" :key="w" :color="ACCENT_NEUTRAL">{{ w }}</Badge>
          <span v-if="!indexSettings.stopWords.length" class="muted">None</span>
        </div>

        <h4 class="detail-heading">Synonyms</h4>
        <ClientOnly v-if="indexSettings.synonyms?.length">
          <JsonEditorVue
            :model-value="indexSettings.synonyms"
            :read-only="true"
            :main-menu-bar="false"
            :navigation-bar="false"
            class="jse-theme-dark payload-editor"
          />
        </ClientOnly>
        <span v-else class="muted">None</span>

        <div class="settings-actions">
          <Button primary :accent="accent" @click="handleUpdateSettings">Update Settings</Button>
        </div>
      </div>

      <!-- Embedders sub-tab -->
      <div v-else-if="indexDetailTab === 'Embedders'" class="drawer-section">
        <div class="drawer-section-header">
          <h4 class="detail-heading no-margin">Embedders ({{ indexEmbedders.length }})</h4>
          <Button
            size="sm"
            icon="plus"
            :accent="accent"
            @click="openAddEmbedderModal">Add Embedder</Button>
        </div>

        <div v-if="indexEmbedders.length === 0" class="empty-state">No embedders configured.</div>
        <div v-else class="embedder-list">
          <div v-for="emb in indexEmbedders" :key="emb.name" class="embedder-card">
            <div class="embedder-header">
              <span class="mono" style="font-weight: 500">{{ emb.name }}</span>
              <div class="embedder-actions">
                <Button size="xs" icon="edit" @click="openEditEmbedderModal(emb)">Edit</Button>
                <Button size="xs" icon="trash" @click="handleRemoveEmbedder(emb.name)">Remove</Button>
              </div>
            </div>
            <div class="kv-grid">
              <span class="kv-label">Source</span>
              <span class="mono">{{ emb.source || '—' }}</span>
              <span class="kv-label">Model</span>
              <span class="mono">{{ emb.model || '—' }}</span>
              <span class="kv-label">Dimensions</span>
              <span class="mono tabular">{{ emb.dimensions ?? '—' }}</span>
              <template v-if="emb.url">
                <span class="kv-label">URL</span>
                <span class="mono small">{{ emb.url }}</span>
              </template>
              <template v-if="emb.documentTemplate">
                <span class="kv-label">Template</span>
                <span class="mono small">{{ emb.documentTemplate }}</span>
              </template>
              <template v-if="emb.documentTemplateMaxBytes">
                <span class="kv-label">Template max bytes</span>
                <span class="mono tabular">{{ formatNumber(emb.documentTemplateMaxBytes) }}</span>
              </template>
            </div>
            <template v-if="emb.request || emb.response || emb.headers">
              <div v-if="emb.request" class="embedder-json-block">
                <span class="kv-label">Request</span>
                <pre class="payload-pre small">{{ jsonToString(emb.request) }}</pre>
              </div>
              <div v-if="emb.response" class="embedder-json-block">
                <span class="kv-label">Response</span>
                <pre class="payload-pre small">{{ jsonToString(emb.response) }}</pre>
              </div>
              <div v-if="emb.headers" class="embedder-json-block">
                <span class="kv-label">Headers</span>
                <pre class="payload-pre small">{{ jsonToString(emb.headers) }}</pre>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- Documents sub-tab -->
      <div v-else-if="indexDetailTab === 'Documents'" class="drawer-section">
        <div class="drawer-section-header">
          <h4 class="detail-heading no-margin">
            Documents
            <span class="muted small">({{ formatNumber(indexDocumentsTotal) }} total)</span>
          </h4>
        </div>
        <div v-if="indexDocumentsResults.length === 0" class="empty-state">No documents.</div>
        <div v-else class="document-list">
          <div v-for="(doc, i) in indexDocumentsResults" :key="i" class="document-card">
            <ClientOnly>
              <JsonEditorVue
                :model-value="typeof doc === 'string' ? parseJsonOrString(doc) : doc"
                :read-only="true"
                :main-menu-bar="false"
                :navigation-bar="false"
                class="jse-theme-dark payload-editor"
              />
            </ClientOnly>
          </div>
        </div>
        <div v-if="indexDocumentsResults.length > 0" class="pagination">
          <Button
            size="sm"
            :disabled="documentsOffset === 0"
            @click="loadDocumentsPage(Math.max(0, documentsOffset - documentsLimit))"
          >Previous</Button>
          <span class="muted small">
            {{ documentsOffset + 1 }} – {{ Math.min(documentsOffset + documentsLimit, indexDocumentsTotal) }}
            of {{ formatNumber(indexDocumentsTotal) }}
          </span>
          <Button
            size="sm"
            :disabled="documentsOffset + documentsLimit >= indexDocumentsTotal"
            @click="loadDocumentsPage(documentsOffset + documentsLimit)"
          >Next</Button>
        </div>
      </div>

      <template #footer>
        <Button @click="indexDrawerOpen = false">Close</Button>
      </template>
    </Drawer>

    <!-- ── Embedder modal ───────────────────────────────────────── -->
    <Modal
      v-if="embedderModalOpen"
      :title="editingEmbedder ? 'Edit Embedder' : 'Add Embedder'"
      icon="ai"
      :accent="accent"
      width="640px"
      @close="embedderModalOpen = false"
    >
      <div class="form-stack">
        <div class="form-grid-2">
          <TextInput
            v-model="embedderForm.name"
            label="Name"
            placeholder="default"
            :disabled="!!editingEmbedder"
          />
          <div class="form-field">
            <label class="field-label">Source</label>
            <Select
              v-model="embedderForm.source"
              :options="EMBEDDER_SOURCES"
              placeholder="Select a source"
              :accent="accent"
            />
          </div>
        </div>

        <TextInput
          v-if="embedderForm.source === 'rest' || embedderForm.source === 'ollama'"
          v-model="embedderForm.url"
          label="URL"
          placeholder="https://api.example.com/v1/embeddings"
          mono
        />

        <div v-if="embedderForm.source && embedderForm.source !== 'userProvided'" class="form-grid-2">
          <TextInput v-model="embedderForm.model" label="Model" placeholder="text-embedding-3-small" />
          <div v-if="embedderForm.source === 'openAi' || embedderForm.source === 'rest'" class="form-field">
            <label class="field-label">API Key</label>
            <input
              v-model="embedderForm.key"
              type="password"
              class="password-input"
              placeholder="sk-…"
            >
          </div>
        </div>

        <div class="form-grid-2">
          <div class="form-field">
            <label class="field-label">Dimensions</label>
            <input
              v-model.number="embedderForm.dimensions"
              type="number"
              class="number-input"
              placeholder="1536" >
          </div>
          <div class="form-field">
            <label class="field-label">Template Max Bytes</label>
            <input
              v-model.number="embedderForm.documentTemplateMaxBytes"
              type="number"
              class="number-input"
              placeholder="400" >
          </div>
        </div>

        <div class="form-field">
          <label class="field-label">Document Template</label>
          <textarea
            v-model="embedderForm.documentTemplate"
            class="config-editor"
            rows="3"
            spellcheck="false"
            placeholder="{{ doc.title }} {{ doc.description }}"
          />
        </div>

        <template v-if="embedderForm.source === 'rest'">
          <div class="form-field">
            <label class="field-label">Request Mapping (JSON)</label>
            <textarea
              v-model="embedderForm.request"
              class="config-editor"
              rows="5"
              spellcheck="false"
            />
          </div>
          <div class="form-field">
            <label class="field-label">Response Mapping (JSON)</label>
            <textarea
              v-model="embedderForm.response"
              class="config-editor"
              rows="5"
              spellcheck="false"
            />
          </div>
          <div class="form-field">
            <label class="field-label">Headers (JSON)</label>
            <textarea
              v-model="embedderForm.headers"
              class="config-editor"
              rows="3"
              spellcheck="false"
            />
          </div>
        </template>

        <p v-if="embedderError" class="form-error">{{ embedderError }}</p>
      </div>
      <template #footer>
        <Button @click="embedderModalOpen = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="embedderSaving"
          @click="handleSaveEmbedder"
        >{{ embedderSaving ? 'Saving…' : 'Save' }}</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.drawer-stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
}

.badge-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.empty-state {
  padding: 24px 16px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.muted { color: var(--fg-3); }
.small { font-size: 11.5px; }

.kv-grid {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 4px 12px;
  font-size: 12.5px;
  align-items: baseline;
}

.kv-label {
  color: var(--fg-3);
  font-size: 11.5px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-weight: 600;
}

.detail-heading {
  margin: 14px 0 8px;
  font-size: 11.5px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-weight: 600;
  color: var(--fg-3);
}

.detail-heading.no-margin { margin: 0; }

/* Node cards */
.node-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 16px;
}

.node-card {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.node-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.node-title { font-weight: 500; font-size: 13px; color: var(--fg-0); }

.node-actions { display: flex; gap: 6px; }

.node-description { margin: 0; font-size: 12px; color: var(--fg-2); }
.node-empty { margin: 0; font-size: 11.5px; color: var(--fg-3); }

.storage-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.storage-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  background: var(--bg-3);
  border: 1px solid var(--line);
  border-radius: 999px;
  font-size: 11.5px;
  color: var(--fg-1);
  cursor: pointer;
  transition: background 0.15s;
}

.storage-chip:hover { background: color-mix(in oklch, var(--err) 10%, var(--bg-3)); }

/* Form */
.form-stack { display: flex; flex-direction: column; gap: 12px; }
.form-field { display: flex; flex-direction: column; gap: 6px; }
.form-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.field-label { font-size: 12px; font-weight: 600; color: var(--fg-2); }

.password-input,
.number-input {
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  padding: 0 10px;
  height: 32px;
  font-size: 13px;
  color: var(--fg-0);
  outline: none;
  transition: border-color 0.15s;
}

.password-input:focus,
.number-input:focus { border-color: var(--brand-2); }

.config-editor {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 14px;
  color: var(--fg-0);
  resize: vertical;
  outline: none;
}

.config-editor:focus { border-color: v-bind(accent); }

.checkbox-row { display: flex; gap: 16px; padding: 4px 0; }
.checkbox-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--fg-1);
  cursor: pointer;
}

.form-error { color: var(--err); font-size: 12px; margin: 0; }

/* Tasks */
.task-list { display: flex; flex-direction: column; }

.task-item { border-bottom: 1px solid var(--line); }
.task-item:last-child { border-bottom: none; }

.task-summary {
  width: 100%;
  background: none;
  border: none;
  padding: 10px 14px;
  display: flex;
  align-items: center;
  gap: 12px;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s;
  font-size: 12.5px;
}

.task-summary:hover { background: var(--bg-2); }

.task-uid { width: 70px; flex-shrink: 0; color: var(--fg-2); }
.task-type { width: 200px; flex-shrink: 0; color: var(--fg-0); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-index { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-time { flex-shrink: 0; color: var(--fg-3); }

.task-cancel {
  background: none;
  border: 1px solid color-mix(in oklch, var(--err) 40%, transparent);
  color: var(--err);
  padding: 3px 8px;
  border-radius: 6px;
  font-size: 11.5px;
  font-weight: 500;
  cursor: pointer;
}

.task-cancel:hover { background: color-mix(in oklch, var(--err) 10%, transparent); }

.task-expanded {
  background: var(--bg-2);
  border-top: 1px solid var(--line);
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.task-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 18px;
  font-size: 12px;
}

.task-meta .kv-label { margin-right: 4px; }

.task-error {
  background: color-mix(in oklch, var(--err) 10%, transparent);
  border: 1px solid color-mix(in oklch, var(--err) 30%, transparent);
  border-radius: 6px;
  padding: 10px 12px;
}

.task-error-title { color: var(--err); font-weight: 600; font-size: 12.5px; }
.task-error-message { color: var(--err); font-size: 12px; margin-top: 4px; }

/* Drawer */
.drawer-section { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }

.drawer-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.embedder-list { display: flex; flex-direction: column; gap: 10px; }

.embedder-card {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.embedder-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.embedder-actions { display: flex; gap: 6px; }

.embedder-json-block { display: flex; flex-direction: column; gap: 4px; }

.document-list { display: flex; flex-direction: column; gap: 8px; }

.document-card {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 8px;
}

.payload-editor {
  border-radius: 6px;
  overflow: hidden;
}

.payload-pre {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 8px 10px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--fg-0);
  margin: 0;
  max-height: 240px;
  overflow-y: auto;
}

.payload-pre.small { font-size: 11.5px; }

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 12px 0;
}

.settings-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
