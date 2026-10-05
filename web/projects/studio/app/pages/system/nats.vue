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

function formatPercent(n: number | null | undefined): string {
  if (n == null) return '—'
  return `${(n * 100).toFixed(1)}%`
}

function formatTimestamp(ts: string | null | undefined): string {
  if (!ts) return '—'
  const normalized = ts.replace(/(\.\d{3})\d+/, '$1')
  const d = new Date(normalized)
  return isNaN(d.getTime()) ? ts : d.toLocaleString()
}

function formatDurationMs(ms: number | null | undefined): string {
  if (ms == null) return '—'
  if (ms === 0) return 'None'
  if (ms < 1) return `${(ms * 1000).toFixed(0)}\u00B5s`
  if (ms < 1000) return `${ms.toFixed(1)}ms`
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return `${(ms / 1000).toFixed(2)}s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ${seconds % 60}s`
  const hours = Math.floor(minutes / 60)
  return `${hours}h ${minutes % 60}m`
}

function truncate(value: string | null | undefined, max: number): string {
  if (!value) return ''
  return value.length <= max ? value : `${value.substring(0, max)}…`
}

function parseJsonOrString(value: string | null | undefined): unknown {
  if (!value) return null
  try { return JSON.parse(value) }
  catch { return value }
}

function isJsonObject(value: string | null | undefined): boolean {
  if (!value) return false
  try {
    const parsed = JSON.parse(value)
    return typeof parsed === 'object' && parsed !== null
  } catch {
    return false
  }
}

/* ── Result types ─────────────────────────────────────────────────── */

interface JetStreamApiStats { total: number; errors: number }
interface JetStreamConfig { maxMemory: number; maxStorage: number; storeDir: string }
interface JetStreamStats {
  memory: number; storage: number; reservedMemory: number; reservedStorage: number
  accounts: number; haAssets: number; api: JetStreamApiStats | null
}
interface JetStreamServerConfig { config: JetStreamConfig | null; stats: JetStreamStats | null }

interface NatsServerInfo {
  serverId: string
  serverName: string
  version: string
  host: string
  port: number
  maxConnections: number
  maxPayload: number
  uptime: string
  mem: number
  cpu: number
  connections: number
  totalConnections: number
  subscriptions: number
  slowConsumers: number
  inMsgs: number
  outMsgs: number
  inBytes: number
  outBytes: number
  jetstream: JetStreamServerConfig | null
}

interface NatsConnection {
  cid: number
  kind: string | null
  type: string | null
  ip: string
  port: number
  start: string | null
  lastActivity: string | null
  rtt: string | null
  uptime: string
  idle: string
  pendingBytes: number
  inMsgs: number
  outMsgs: number
  inBytes: number
  outBytes: number
  subscriptions: number
  name: string | null
  lang: string | null
  version: string | null
}

interface NatsConnections {
  numConnections: number
  total: number
  connections: NatsConnection[]
}

interface JetStreamInfo {
  memory: number
  storage: number
  streams: number
  consumers: number
  messages: number
  bytes: number
  api: JetStreamApiStats | null
}

interface JetStreamStreamState {
  messages: number
  bytes: number
  firstSeq: number
  lastSeq: number
  consumerCount: number
  numSubjects: number
  numDeleted: number
}

interface JetStreamCluster { leader: string | null }

interface JetStreamStreamDetail {
  name: string
  created: string | null
  state: JetStreamStreamState | null
  cluster: JetStreamCluster | null
}

interface JetStreamSequenceInfo { consumerSeq: number; streamSeq: number }

interface JetStreamConsumerDetail {
  name: string
  streamName: string
  created: string | null
  delivered: JetStreamSequenceInfo | null
  ackFloor: JetStreamSequenceInfo | null
  numAckPending: number
  numRedelivered: number
  numWaiting: number
  numPending: number
  cluster: JetStreamCluster | null
}

interface NatsRoute {
  rid: number
  remoteId: string
  remoteName: string
  didSolicit: boolean
  isConfigured: boolean
  ip: string
  port: number
  pendingSize: number
  inMsgs: number
  outMsgs: number
  inBytes: number
  outBytes: number
  subscriptions: number
}

interface NatsRoutes { numRoutes: number; routes: NatsRoute[] }

interface NatsSubscriptionsInfo {
  numSubscriptions: number
  numCache: number
  numInserts: number
  numRemoves: number
  numMatches: number
  cacheHitRate: number
  maxFanout: number
  avgFanout: number
}

interface NatsKeyValueStore {
  bucket: string
  entryCount: number
  bytes: number
  ttl: number
  maxBytes: number
  maxValueSize: number
  history: number
}

interface NatsKeyValueEntry {
  key: string
  value: string | null
  revision: number
  created: string
  operation: string
}

interface NatsHeader { key: string; values: string[] }

interface NatsStreamMessage {
  subject: string
  sequence: number
  timestamp: string
  data: string | null
  headers: NatsHeader[]
}

interface NatsOverview {
  natsAdmin: {
    serverInfo: NatsServerInfo
    connections: NatsConnections
    jetStreamInfo: JetStreamInfo
    jetStreamStreams: JetStreamStreamDetail[]
    clusterRoutes: NatsRoutes
    subscriptionsInfo: NatsSubscriptionsInfo
    keyValueStores: NatsKeyValueStore[]
  }
}

/* ── Queries ─────────────────────────────────────────────────────── */

const overviewGql = gql`
  query GetNatsAdminOverview {
    natsAdmin {
      serverInfo {
        serverId serverName version host port
        maxConnections maxPayload uptime
        mem cpu connections totalConnections subscriptions slowConsumers
        inMsgs outMsgs inBytes outBytes
        jetstream {
          config { maxMemory maxStorage storeDir }
          stats {
            memory storage reservedMemory reservedStorage accounts haAssets
            api { total errors }
          }
        }
      }
      connections(limit: 100) {
        numConnections total
        connections {
          cid kind type ip port start lastActivity rtt
          uptime idle pendingBytes
          inMsgs outMsgs inBytes outBytes subscriptions
          name lang version
        }
      }
      jetStreamInfo {
        memory storage streams consumers messages bytes
        api { total errors }
      }
      jetStreamStreams {
        name created
        state { messages bytes firstSeq lastSeq consumerCount numSubjects numDeleted }
        cluster { leader }
      }
      clusterRoutes {
        numRoutes
        routes {
          rid remoteId remoteName didSolicit isConfigured
          ip port pendingSize inMsgs outMsgs inBytes outBytes subscriptions
        }
      }
      subscriptionsInfo {
        numSubscriptions numCache numInserts numRemoves numMatches
        cacheHitRate maxFanout avgFanout
      }
      keyValueStores {
        bucket entryCount bytes ttl maxBytes maxValueSize history
      }
    }
  }
`

const consumersGql = gql`
  query GetJetStreamConsumers($streamName: String!) {
    natsAdmin {
      jetStreamConsumers(streamName: $streamName) {
        name streamName created
        delivered { consumerSeq streamSeq }
        ackFloor { consumerSeq streamSeq }
        numAckPending numRedelivered numWaiting numPending
        cluster { leader }
      }
    }
  }
`

const kvEntriesGql = gql`
  query GetKeyValueEntries($bucket: String!, $keyFilter: String, $valueSearch: String) {
    natsAdmin {
      keyValueEntries(bucket: $bucket, keyFilter: $keyFilter, valueSearch: $valueSearch) {
        key value revision created operation
      }
    }
  }
`

const streamMessagesGql = gql`
  query GetStreamMessages($streamName: String!, $limit: Int, $subjectFilter: String, $fromSeq: Long, $toSeq: Long, $dataSearch: String) {
    natsAdmin {
      streamMessages(streamName: $streamName, limit: $limit, subjectFilter: $subjectFilter, fromSeq: $fromSeq, toSeq: $toSeq, dataSearch: $dataSearch) {
        subject sequence timestamp data
        headers { key values }
      }
    }
  }
`

const putKvEntryGql = gql`
  mutation PutKvEntry($bucket: String!, $key: String!, $value: String!) {
    natsAdmin {
      putKeyValueEntry(bucket: $bucket, key: $key, value: $value) {
        key value revision created operation
      }
    }
  }
`

const deleteKvEntryGql = gql`
  mutation DeleteKvEntry($bucket: String!, $key: String!) {
    natsAdmin { deleteKeyValueEntry(bucket: $bucket, key: $key) }
  }
`

const purgeKvDeletesGql = gql`
  mutation PurgeKvDeletes($bucket: String!) {
    natsAdmin { purgeKeyValueDeletes(bucket: $bucket) }
  }
`

const purgeStreamGql = gql`
  mutation PurgeStream($streamName: String!) {
    natsAdmin { purgeStream(streamName: $streamName) }
  }
`

const purgeStreamDeletesGql = gql`
  mutation PurgeStreamDeletes($streamName: String!) {
    natsAdmin { purgeStreamDeletes(streamName: $streamName) }
  }
`

const deleteStreamMessageGql = gql`
  mutation DeleteStreamMessage($streamName: String!, $sequence: Long!) {
    natsAdmin { deleteStreamMessage(streamName: $streamName, sequence: $sequence) }
  }
`

/* ── State ────────────────────────────────────────────────────────── */

const TABS = ['Overview', 'Streams', 'Key Values', 'Connections', 'Routes'] as const
const activeTab = ref<(typeof TABS)[number]>('Overview')

const { data: overviewData, refresh: refreshOverview } = useAsyncQuery<NatsOverview>(
  'nats-admin-overview',
  overviewGql,
)

const root = computed(() => overviewData.value?.natsAdmin ?? null)
const serverInfo = computed<NatsServerInfo | null>(() => root.value?.serverInfo ?? null)
const connectionsBlock = computed<NatsConnections | null>(() => root.value?.connections ?? null)
const jetStreamInfo = computed<JetStreamInfo | null>(() => root.value?.jetStreamInfo ?? null)
const streams = computed<JetStreamStreamDetail[]>(() => root.value?.jetStreamStreams ?? [])
const clusterRoutes = computed<NatsRoutes | null>(() => root.value?.clusterRoutes ?? null)
const subsInfo = computed<NatsSubscriptionsInfo | null>(() => root.value?.subscriptionsInfo ?? null)
const kvStores = computed<NatsKeyValueStore[]>(() => root.value?.keyValueStores ?? [])

const jetStreamEnabled = computed(() => serverInfo.value?.jetstream != null)

/**
 * NATS reports uptime as a packed string like "9d21h4m37s". The full string
 * is too long for a hero StatTile value at the default 28px font. Split it
 * so the two most-significant units become the hero value and the rest
 * drops to the sub line — keeps the tile visually aligned with sibling
 * numeric stats without losing the full duration.
 */
const uptimeDisplay = computed(() => {
  const raw = serverInfo.value?.uptime
  if (!raw) return { primary: '—', secondary: '' }
  const match = raw.match(/^(\d+d)?(\d+h)?(\d+m)?(\d+s)?$/)
  if (!match) return { primary: raw, secondary: '' }
  const units = [match[1], match[2], match[3], match[4]].filter((u): u is string => Boolean(u))
  if (units.length === 0) return { primary: raw, secondary: '' }
  if (units.length <= 2) return { primary: units.join(' '), secondary: '' }
  return { primary: units.slice(0, 2).join(' '), secondary: units.slice(2).join(' ') }
})

// Filters
const streamNameFilter = ref('')
const connectionNameFilter = ref('')
const kvBucketFilter = ref('')

// Stream drawer state
const streamDrawerOpen = ref(false)
const selectedStream = ref<JetStreamStreamDetail | null>(null)
const consumers = ref<JetStreamConsumerDetail[]>([])
const loadingConsumers = ref(false)
const streamMessages = ref<NatsStreamMessage[]>([])
const loadingMessages = ref(false)
const expandedMessageSeq = ref<number | null>(null)
const msgSubjectFilter = ref('')
const msgFromSeq = ref('')
const msgToSeq = ref('')
const msgDataSearch = ref('')
const purgingStreamDeletes = ref(false)

// KV drawer state
const kvDrawerOpen = ref(false)
const selectedKvBucket = ref<NatsKeyValueStore | null>(null)
const kvEntries = ref<NatsKeyValueEntry[]>([])
const loadingKvEntries = ref(false)
const kvKeyFilter = ref('')
const kvValueSearch = ref('')
const expandedKvKey = ref<string | null>(null)
const newKvKey = ref('')
const newKvValue = ref('')
const savingKvEntry = ref(false)

/* ── Filtered lists ───────────────────────────────────────────────── */

const filteredStreams = computed(() => {
  if (!streamNameFilter.value) return streams.value
  const f = streamNameFilter.value.toLowerCase()
  return streams.value.filter(s => s.name.toLowerCase().includes(f))
})

const filteredConnections = computed(() => {
  const conns = connectionsBlock.value?.connections ?? []
  if (!connectionNameFilter.value) return conns
  const f = connectionNameFilter.value.toLowerCase()
  return conns.filter(c =>
    (c.name ?? '').toLowerCase().includes(f)
    || c.ip.toLowerCase().includes(f)
    || (c.lang ?? '').toLowerCase().includes(f),
  )
})

const filteredKvStores = computed(() => {
  if (!kvBucketFilter.value) return kvStores.value
  const f = kvBucketFilter.value.toLowerCase()
  return kvStores.value.filter(s => s.bucket.toLowerCase().includes(f))
})

/* ── Lazy loaders ─────────────────────────────────────────────────── */

async function loadConsumers(streamName: string) {
  loadingConsumers.value = true
  try {
    const result = await gqlQuery<{ natsAdmin: { jetStreamConsumers: JetStreamConsumerDetail[] } }>(
      consumersGql,
      { streamName },
    )
    consumers.value = result.natsAdmin?.jetStreamConsumers ?? []
  } catch (e: unknown) {
    toast.error(`Failed to load consumers: ${e instanceof Error ? e.message : 'Unknown error'}`)
    consumers.value = []
  } finally {
    loadingConsumers.value = false
  }
}

async function loadStreamMessages(streamName: string) {
  loadingMessages.value = true
  try {
    const vars: Record<string, unknown> = { streamName, limit: 50 }
    if (msgSubjectFilter.value) vars.subjectFilter = msgSubjectFilter.value
    if (msgFromSeq.value) vars.fromSeq = Number.parseInt(msgFromSeq.value, 10)
    if (msgToSeq.value) vars.toSeq = Number.parseInt(msgToSeq.value, 10)
    if (msgDataSearch.value) vars.dataSearch = msgDataSearch.value
    const result = await gqlQuery<{ natsAdmin: { streamMessages: NatsStreamMessage[] } }>(
      streamMessagesGql,
      vars,
    )
    streamMessages.value = result.natsAdmin?.streamMessages ?? []
  } catch (e: unknown) {
    toast.error(`Failed to load messages: ${e instanceof Error ? e.message : 'Unknown error'}`)
    streamMessages.value = []
  } finally {
    loadingMessages.value = false
  }
}

async function loadKvEntries(bucket: string) {
  loadingKvEntries.value = true
  try {
    const vars: Record<string, unknown> = { bucket }
    if (kvKeyFilter.value) vars.keyFilter = kvKeyFilter.value
    if (kvValueSearch.value) vars.valueSearch = kvValueSearch.value
    const result = await gqlQuery<{ natsAdmin: { keyValueEntries: NatsKeyValueEntry[] } }>(
      kvEntriesGql,
      vars,
    )
    kvEntries.value = result.natsAdmin?.keyValueEntries ?? []
  } catch (e: unknown) {
    toast.error(`Failed to load KV entries: ${e instanceof Error ? e.message : 'Unknown error'}`)
    kvEntries.value = []
  } finally {
    loadingKvEntries.value = false
  }
}

/* ── Mutations ─────────────────────────────────────────────────────── */

async function handlePutKvEntry() {
  if (!selectedKvBucket.value || !newKvKey.value) return
  savingKvEntry.value = true
  try {
    await gqlMutation(putKvEntryGql, {
      bucket: selectedKvBucket.value.bucket,
      key: newKvKey.value,
      value: newKvValue.value,
    })
    toast.success(`Stored ${newKvKey.value}`)
    newKvKey.value = ''
    newKvValue.value = ''
    await loadKvEntries(selectedKvBucket.value.bucket)
  } catch (e: unknown) {
    toast.error(`Failed to put entry: ${e instanceof Error ? e.message : 'Unknown error'}`)
  } finally {
    savingKvEntry.value = false
  }
}

async function handleDeleteKvEntry(key: string) {
  if (!selectedKvBucket.value || !confirm(`Delete key "${key}"?`)) return
  try {
    await gqlMutation(deleteKvEntryGql, { bucket: selectedKvBucket.value.bucket, key })
    toast.success(`Deleted ${key}`)
    expandedKvKey.value = null
    await loadKvEntries(selectedKvBucket.value.bucket)
  } catch (e: unknown) {
    toast.error(`Failed to delete entry: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

async function handlePurgeKvDeletes() {
  if (!selectedKvBucket.value) return
  const bucket = selectedKvBucket.value.bucket
  if (!confirm(`Purge all deleted/tombstoned entries from "${bucket}"?`)) return
  try {
    await gqlMutation(purgeKvDeletesGql, { bucket })
    toast.success(`Tombstones purged from ${bucket}`)
    await refreshOverview()
    await loadKvEntries(bucket)
  } catch (e: unknown) {
    toast.error(`Failed to purge tombstones: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

async function handlePurgeStream(streamName: string) {
  if (!confirm(`Purge all messages from stream "${streamName}"? This cannot be undone.`)) return
  try {
    await gqlMutation(purgeStreamGql, { streamName })
    toast.success(`Stream ${streamName} purged`)
    streamMessages.value = []
    expandedMessageSeq.value = null
    await refreshOverview()
  } catch (e: unknown) {
    toast.error(`Failed to purge stream: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

async function handlePurgeStreamDeletes(streamName: string) {
  if (!confirm(`Purge all tombstone (DEL/PURGE) markers from stream "${streamName}"?`)) return
  purgingStreamDeletes.value = true
  try {
    const result = await gqlMutation<{ natsAdmin: { purgeStreamDeletes: number } }>(
      purgeStreamDeletesGql,
      { streamName },
    )
    const removed = result.natsAdmin?.purgeStreamDeletes ?? 0
    toast.success(`Removed ${removed} tombstones from ${streamName}`)
    await refreshOverview()
    if (selectedStream.value?.name === streamName) await loadStreamMessages(streamName)
  } catch (e: unknown) {
    toast.error(`Failed to purge tombstones: ${e instanceof Error ? e.message : 'Unknown error'}`)
  } finally {
    purgingStreamDeletes.value = false
  }
}

async function handleDeleteStreamMessage(sequence: number) {
  if (!selectedStream.value || !confirm(`Delete message #${sequence}?`)) return
  const streamName = selectedStream.value.name
  try {
    await gqlMutation(deleteStreamMessageGql, { streamName, sequence })
    toast.success(`Deleted message #${sequence}`)
    expandedMessageSeq.value = null
    await loadStreamMessages(streamName)
  } catch (e: unknown) {
    toast.error(`Failed to delete message: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

/* ── Drawer openers ───────────────────────────────────────────────── */

async function openStreamDrawer(row: JetStreamStreamDetail) {
  selectedStream.value = row
  consumers.value = []
  streamMessages.value = []
  expandedMessageSeq.value = null
  msgSubjectFilter.value = ''
  msgFromSeq.value = ''
  msgToSeq.value = ''
  msgDataSearch.value = ''
  streamDrawerOpen.value = true
  await Promise.all([loadConsumers(row.name), loadStreamMessages(row.name)])
}

async function openKvDrawer(row: NatsKeyValueStore) {
  selectedKvBucket.value = row
  kvEntries.value = []
  expandedKvKey.value = null
  kvKeyFilter.value = ''
  kvValueSearch.value = ''
  newKvKey.value = ''
  newKvValue.value = ''
  kvDrawerOpen.value = true
  await loadKvEntries(row.bucket)
}

function toggleMessageRow(seq: number) {
  expandedMessageSeq.value = expandedMessageSeq.value === seq ? null : seq
}

function toggleKvRow(key: string) {
  expandedKvKey.value = expandedKvKey.value === key ? null : key
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

/* ── GlassTable column definitions ─────────────────────────────────── */

const streamColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 1.5fr)' },
  { key: 'messages', label: 'Messages', width: '120px', align: 'right' },
  { key: 'bytes', label: 'Size', width: '110px', align: 'right' },
  { key: 'consumers', label: 'Consumers', width: '110px', align: 'right' },
  { key: 'subjects', label: 'Subjects', width: '100px', align: 'right' },
  { key: 'lastSeq', label: 'Last Seq', width: '120px', align: 'right' },
  { key: 'deleted', label: 'Deleted', width: '100px', align: 'right' },
]

const connectionColumns: GlassTableColumn[] = [
  { key: 'cid', label: 'ID', width: '80px' },
  { key: 'name', label: 'Name', width: '1fr', muted: true },
  { key: 'ip', label: 'IP', width: '160px' },
  { key: 'subscriptions', label: 'Subs', width: '70px', align: 'right' },
  { key: 'inMsgs', label: 'Msgs In', width: '100px', align: 'right' },
  { key: 'outMsgs', label: 'Msgs Out', width: '100px', align: 'right' },
  { key: 'inBytes', label: 'Bytes In', width: '110px', align: 'right' },
  { key: 'outBytes', label: 'Bytes Out', width: '110px', align: 'right' },
  { key: 'uptime', label: 'Uptime', width: '100px', muted: true },
  { key: 'idle', label: 'Idle', width: '90px', muted: true },
  { key: 'client', label: 'Client', width: '140px', muted: true },
]

const routeColumns: GlassTableColumn[] = [
  { key: 'rid', label: 'ID', width: '80px' },
  { key: 'remoteName', label: 'Remote Name', width: '1fr' },
  { key: 'remoteId', label: 'Remote ID', width: '160px', muted: true },
  { key: 'ip', label: 'IP', width: '140px' },
  { key: 'port', label: 'Port', width: '80px' },
  { key: 'didSolicit', label: 'Solicited', width: '100px' },
  { key: 'subscriptions', label: 'Subs', width: '70px', align: 'right' },
  { key: 'inMsgs', label: 'Msgs In', width: '100px', align: 'right' },
  { key: 'outMsgs', label: 'Msgs Out', width: '100px', align: 'right' },
  { key: 'inBytes', label: 'Bytes In', width: '110px', align: 'right' },
  { key: 'outBytes', label: 'Bytes Out', width: '110px', align: 'right' },
  { key: 'pendingSize', label: 'Pending', width: '100px', align: 'right' },
]

const kvStoreColumns: GlassTableColumn[] = [
  { key: 'bucket', label: 'Bucket', width: 'minmax(200px, 1.5fr)' },
  { key: 'entryCount', label: 'Entries', width: '110px', align: 'right' },
  { key: 'bytes', label: 'Size', width: '110px', align: 'right' },
  { key: 'ttl', label: 'TTL', width: '120px', align: 'right' },
  { key: 'history', label: 'History', width: '90px', align: 'right' },
  { key: 'maxBytes', label: 'Max Size', width: '120px', align: 'right' },
]

const consumerColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 1.5fr)' },
  { key: 'numPending', label: 'Pending', width: '100px', align: 'right' },
  { key: 'numAckPending', label: 'Ack Pending', width: '120px', align: 'right' },
  { key: 'numRedelivered', label: 'Redelivered', width: '120px', align: 'right' },
  { key: 'numWaiting', label: 'Waiting', width: '90px', align: 'right' },
  { key: 'deliveredSeq', label: 'Delivered Seq', width: '130px', align: 'right' },
  { key: 'ackFloorSeq', label: 'Ack Floor', width: '120px', align: 'right' },
]

/* ── Display helpers ───────────────────────────────────────────────── */

const ACCENT_OK = '#34d99a'
const ACCENT_WARN = '#ffb547'
const ACCENT_ERR = '#ff5d6c'
const ACCENT_NEUTRAL = '#6c7388'
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'NATS')"
        title="NATS"
        :subtitle="serverInfo ? `v${serverInfo.version} · ${serverInfo.serverName}` : undefined"
        :tabs="[...TABS]"
        :active-tab="activeTab"
        @tab="(t: string) => activeTab = t as typeof TABS[number]"
      >
        <template #actions>
          <Badge v-if="jetStreamEnabled" :color="ACCENT_OK">JetStream</Badge>
          <Button size="sm" icon="pulse" @click="refreshOverview()">Refresh</Button>
        </template>
      </PageHeader>
    </template>

    <!-- ── Overview Tab ──────────────────────────────────────────── -->
    <template v-if="activeTab === 'Overview'">
      <!-- Server Overview -->
      <SectionCard title="Server Overview" padded>
        <div class="stat-grid">
          <StatTile
            label="Status"
            :value="serverInfo ? 'Connected' : 'Disconnected'"
            :accent="serverInfo ? ACCENT_OK : ACCENT_ERR"
            value-size="sm" />
          <StatTile label="Uptime" :value="uptimeDisplay.primary" :sub="uptimeDisplay.secondary" />
          <StatTile label="Connections" :value="serverInfo ? formatNumber(serverInfo.connections) : '—'" :accent="accent" />
          <StatTile label="Total Connections" :value="serverInfo ? formatNumber(serverInfo.totalConnections) : '—'" />
          <StatTile label="Memory" :value="serverInfo ? formatBytes(serverInfo.mem) : '—'" />
          <StatTile label="CPU" :value="serverInfo ? `${serverInfo.cpu.toFixed(1)}%` : '—'" />
          <StatTile label="Subscriptions" :value="serverInfo ? formatNumber(serverInfo.subscriptions) : '—'" />
          <StatTile
            label="Slow Consumers"
            :value="serverInfo ? formatNumber(serverInfo.slowConsumers) : '—'"
            :accent="serverInfo && serverInfo.slowConsumers > 0 ? ACCENT_ERR : undefined"
          />
          <StatTile label="Msgs In" :value="serverInfo ? formatNumber(serverInfo.inMsgs) : '—'" />
          <StatTile label="Msgs Out" :value="serverInfo ? formatNumber(serverInfo.outMsgs) : '—'" />
          <StatTile label="Bytes In" :value="serverInfo ? formatBytes(serverInfo.inBytes) : '—'" />
          <StatTile label="Bytes Out" :value="serverInfo ? formatBytes(serverInfo.outBytes) : '—'" />
          <StatTile label="Max Payload" :value="serverInfo ? formatBytes(serverInfo.maxPayload) : '—'" />
        </div>
      </SectionCard>

      <!-- JetStream Overview -->
      <SectionCard v-if="jetStreamInfo" title="JetStream" padded>
        <div class="stat-grid">
          <StatTile label="Streams" :value="formatNumber(jetStreamInfo.streams)" :accent="accent" />
          <StatTile label="Consumers" :value="formatNumber(jetStreamInfo.consumers)" />
          <StatTile label="Messages" :value="formatNumber(jetStreamInfo.messages)" />
          <StatTile label="Storage Used" :value="formatBytes(jetStreamInfo.bytes)" />
          <StatTile label="Memory Used" :value="formatBytes(jetStreamInfo.memory)" />
          <StatTile
            label="API Calls"
            :value="formatNumber(jetStreamInfo.api?.total ?? 0)"
            :sub="jetStreamInfo.api && jetStreamInfo.api.errors > 0 ? `${formatNumber(jetStreamInfo.api.errors)} errors` : undefined"
            :accent="jetStreamInfo.api && jetStreamInfo.api.errors > 0 ? ACCENT_ERR : undefined"
          />
        </div>
      </SectionCard>

      <!-- Subscription Routing -->
      <SectionCard v-if="subsInfo" title="Subscription Routing" padded>
        <div class="stat-grid">
          <StatTile label="Subscriptions" :value="formatNumber(subsInfo.numSubscriptions)" />
          <StatTile label="Cache Entries" :value="formatNumber(subsInfo.numCache)" />
          <StatTile label="Cache Hit Rate" :value="formatPercent(subsInfo.cacheHitRate)" :accent="subsInfo.cacheHitRate >= 0.9 ? ACCENT_OK : ACCENT_WARN" />
          <StatTile label="Matches" :value="formatNumber(subsInfo.numMatches)" />
          <StatTile label="Max Fanout" :value="String(subsInfo.maxFanout)" />
          <StatTile label="Avg Fanout" :value="subsInfo.avgFanout.toFixed(2)" />
          <StatTile label="Inserts" :value="formatNumber(subsInfo.numInserts)" />
          <StatTile label="Removes" :value="formatNumber(subsInfo.numRemoves)" />
        </div>
      </SectionCard>
    </template>

    <!-- ── Streams Tab ───────────────────────────────────────────── -->
    <template v-if="activeTab === 'Streams'">
      <SectionCard
        :title="`JetStream Streams (${filteredStreams.length})`"
        padded
      >
        <div class="filter-bar">
          <TextInput
            v-model="streamNameFilter"
            placeholder="Filter streams…"
            size="sm"
            icon="search" />
        </div>
        <GlassTable
          :columns="streamColumns"
          :rows="filteredStreams"
          row-key="name"
          empty-text="No JetStream streams found."
          @row-click="openStreamDrawer"
        >
          <template #col-name="{ row }">
            <span class="mono" style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</span>
          </template>
          <template #col-messages="{ row }">
            <span class="mono tabular">{{ formatNumber(row.state?.messages ?? 0) }}</span>
          </template>
          <template #col-bytes="{ row }">
            <span class="mono tabular">{{ formatBytes(row.state?.bytes ?? 0) }}</span>
          </template>
          <template #col-consumers="{ row }">
            <span class="mono tabular">{{ row.state?.consumerCount ?? 0 }}</span>
          </template>
          <template #col-subjects="{ row }">
            <span class="mono tabular">{{ row.state?.numSubjects ?? 0 }}</span>
          </template>
          <template #col-lastSeq="{ row }">
            <span class="mono tabular">{{ formatNumber(row.state?.lastSeq ?? 0) }}</span>
          </template>
          <template #col-deleted="{ row }">
            <span class="mono tabular">{{ formatNumber(row.state?.numDeleted ?? 0) }}</span>
          </template>
        </GlassTable>
      </SectionCard>
    </template>

    <!-- ── Key Values Tab ────────────────────────────────────────── -->
    <template v-if="activeTab === 'Key Values'">
      <SectionCard
        :title="`KeyValue Stores (${filteredKvStores.length})`"
        padded
      >
        <div class="filter-bar">
          <TextInput
            v-model="kvBucketFilter"
            placeholder="Filter buckets…"
            size="sm"
            icon="search" />
        </div>
        <GlassTable
          :columns="kvStoreColumns"
          :rows="filteredKvStores"
          row-key="bucket"
          empty-text="No KeyValue stores found."
          @row-click="openKvDrawer"
        >
          <template #col-bucket="{ row }">
            <span class="mono" style="font-weight: 500; color: var(--fg-0)">{{ row.bucket }}</span>
          </template>
          <template #col-entryCount="{ row }">
            <span class="mono tabular">{{ formatNumber(row.entryCount) }}</span>
          </template>
          <template #col-bytes="{ row }">
            <span class="mono tabular">{{ formatBytes(row.bytes) }}</span>
          </template>
          <template #col-ttl="{ row }">
            <span class="mono tabular">{{ row.ttl === 0 ? '∞' : formatDurationMs(row.ttl) }}</span>
          </template>
          <template #col-history="{ row }">
            <span class="mono tabular">{{ row.history }}</span>
          </template>
          <template #col-maxBytes="{ row }">
            <span class="mono tabular">{{ row.maxBytes > 0 ? formatBytes(row.maxBytes) : 'Unlimited' }}</span>
          </template>
        </GlassTable>
      </SectionCard>
    </template>

    <!-- ── Connections Tab ───────────────────────────────────────── -->
    <template v-if="activeTab === 'Connections'">
      <SectionCard
        :title="`Active Connections (${filteredConnections.length})`"
        :subtitle="connectionsBlock ? `${connectionsBlock.total} total` : undefined"
        padded
      >
        <div class="filter-bar">
          <TextInput
            v-model="connectionNameFilter"
            placeholder="Filter by name, IP, or language…"
            size="sm"
            icon="search" />
        </div>
        <GlassTable
          :columns="connectionColumns"
          :rows="filteredConnections"
          row-key="cid"
          empty-text="No active connections."
          :arrow="false"
        >
          <template #col-cid="{ row }">
            <span class="mono tabular">{{ row.cid }}</span>
          </template>
          <template #col-name="{ row }">
            <span class="mono">{{ row.name || '—' }}</span>
          </template>
          <template #col-ip="{ row }">
            <span class="mono tabular">{{ row.ip }}</span>
          </template>
          <template #col-subscriptions="{ row }">
            <span class="mono tabular">{{ row.subscriptions }}</span>
          </template>
          <template #col-inMsgs="{ row }">
            <span class="mono tabular">{{ formatNumber(row.inMsgs) }}</span>
          </template>
          <template #col-outMsgs="{ row }">
            <span class="mono tabular">{{ formatNumber(row.outMsgs) }}</span>
          </template>
          <template #col-inBytes="{ row }">
            <span class="mono tabular">{{ formatBytes(row.inBytes) }}</span>
          </template>
          <template #col-outBytes="{ row }">
            <span class="mono tabular">{{ formatBytes(row.outBytes) }}</span>
          </template>
          <template #col-uptime="{ row }">
            <span class="mono small">{{ row.uptime }}</span>
          </template>
          <template #col-idle="{ row }">
            <span class="mono small">{{ row.idle }}</span>
          </template>
          <template #col-client="{ row }">
            <span v-if="row.lang" class="mono small">{{ row.version ? `${row.lang} ${row.version}` : row.lang }}</span>
            <span v-else class="muted">—</span>
          </template>
        </GlassTable>
      </SectionCard>
    </template>

    <!-- ── Routes Tab ────────────────────────────────────────────── -->
    <template v-if="activeTab === 'Routes'">
      <SectionCard
        :title="`Cluster Routes (${clusterRoutes?.numRoutes ?? 0})`"
        padded
      >
        <GlassTable
          v-if="clusterRoutes && clusterRoutes.routes.length > 0"
          :columns="routeColumns"
          :rows="clusterRoutes.routes"
          row-key="rid"
          :arrow="false"
        >
          <template #col-rid="{ row }">
            <span class="mono tabular">{{ row.rid }}</span>
          </template>
          <template #col-remoteName="{ row }">
            <span class="mono">{{ row.remoteName || '—' }}</span>
          </template>
          <template #col-remoteId="{ row }">
            <span class="mono small">{{ row.remoteId ? `${row.remoteId.substring(0, 12)}…` : '—' }}</span>
          </template>
          <template #col-ip="{ row }">
            <span class="mono tabular">{{ row.ip }}</span>
          </template>
          <template #col-port="{ row }">
            <span class="mono tabular">{{ row.port }}</span>
          </template>
          <template #col-didSolicit="{ row }">
            <Badge :color="row.didSolicit ? ACCENT_OK : ACCENT_NEUTRAL">{{ row.didSolicit ? 'Yes' : 'No' }}</Badge>
          </template>
          <template #col-subscriptions="{ row }">
            <span class="mono tabular">{{ row.subscriptions }}</span>
          </template>
          <template #col-inMsgs="{ row }">
            <span class="mono tabular">{{ formatNumber(row.inMsgs) }}</span>
          </template>
          <template #col-outMsgs="{ row }">
            <span class="mono tabular">{{ formatNumber(row.outMsgs) }}</span>
          </template>
          <template #col-inBytes="{ row }">
            <span class="mono tabular">{{ formatBytes(row.inBytes) }}</span>
          </template>
          <template #col-outBytes="{ row }">
            <span class="mono tabular">{{ formatBytes(row.outBytes) }}</span>
          </template>
          <template #col-pendingSize="{ row }">
            <span class="mono tabular">{{ formatBytes(row.pendingSize) }}</span>
          </template>
        </GlassTable>
        <div v-else class="empty-state">
          No cluster routes (standalone server).
        </div>
      </SectionCard>
    </template>

    <!-- ── Stream details drawer ─────────────────────────────────── -->
    <Drawer
      v-if="streamDrawerOpen && selectedStream"
      :title="selectedStream.name"
      subtitle="JetStream"
      icon="pulse"
      :accent="accent"
      width="900px"
      @close="streamDrawerOpen = false"
    >
      <template #actions>
        <Button
          size="sm"
          icon="trash"
          :disabled="purgingStreamDeletes"
          @click="handlePurgeStreamDeletes(selectedStream.name)"
        >Purge Tombstones</Button>
        <Button
          size="sm"
          icon="trash"
          @click="handlePurgeStream(selectedStream.name)"
        >Purge Stream</Button>
      </template>

      <div v-if="selectedStream.state" class="drawer-stat-grid">
        <StatTile label="Messages" :value="formatNumber(selectedStream.state.messages)" />
        <StatTile label="Size" :value="formatBytes(selectedStream.state.bytes)" />
        <StatTile label="Consumers" :value="String(selectedStream.state.consumerCount)" :accent="accent" />
        <StatTile label="Subjects" :value="String(selectedStream.state.numSubjects)" />
        <StatTile label="Last Seq" :value="formatNumber(selectedStream.state.lastSeq)" />
        <StatTile label="Deleted" :value="formatNumber(selectedStream.state.numDeleted)" />
      </div>

      <h4 class="detail-heading">Consumers ({{ consumers.length }})</h4>
      <GlassTable
        :columns="consumerColumns"
        :rows="consumers"
        row-key="name"
        :loading="loadingConsumers"
        loading-text="Loading consumers…"
        empty-text="No consumers."
        :arrow="false"
      >
        <template #col-name="{ row }">
          <span class="mono" style="font-weight: 500">{{ row.name }}</span>
        </template>
        <template #col-numPending="{ row }">
          <Badge v-if="row.numPending > 0" :color="ACCENT_WARN">{{ formatNumber(row.numPending) }}</Badge>
          <span v-else class="mono tabular">0</span>
        </template>
        <template #col-numAckPending="{ row }">
          <Badge v-if="row.numAckPending > 0" :color="ACCENT_WARN">{{ formatNumber(row.numAckPending) }}</Badge>
          <span v-else class="mono tabular">0</span>
        </template>
        <template #col-numRedelivered="{ row }">
          <Badge v-if="row.numRedelivered > 0" :color="ACCENT_ERR">{{ formatNumber(row.numRedelivered) }}</Badge>
          <span v-else class="mono tabular">0</span>
        </template>
        <template #col-numWaiting="{ row }">
          <span class="mono tabular">{{ formatNumber(row.numWaiting) }}</span>
        </template>
        <template #col-deliveredSeq="{ row }">
          <span class="mono tabular">{{ formatNumber(row.delivered?.streamSeq ?? 0) }}</span>
        </template>
        <template #col-ackFloorSeq="{ row }">
          <span class="mono tabular">{{ formatNumber(row.ackFloor?.streamSeq ?? 0) }}</span>
        </template>
      </GlassTable>

      <h4 class="detail-heading">Messages</h4>
      <div class="filter-bar">
        <TextInput v-model="msgSubjectFilter" placeholder="Subject filter (e.g. jobs.>)" size="sm" />
        <TextInput v-model="msgFromSeq" placeholder="From seq" size="sm" />
        <TextInput v-model="msgToSeq" placeholder="To seq" size="sm" />
        <TextInput
          v-model="msgDataSearch"
          placeholder="Search in payload…"
          size="sm"
          icon="search" />
        <Button size="sm" @click="loadStreamMessages(selectedStream.name)">Apply</Button>
      </div>
      <div v-if="loadingMessages" class="empty-state">Loading messages…</div>
      <div v-else-if="streamMessages.length === 0" class="empty-state">No messages.</div>
      <div v-else class="row-list">
        <div v-for="msg in streamMessages" :key="msg.sequence" class="row-item">
          <button
            type="button"
            class="row-summary"
            @click="toggleMessageRow(msg.sequence)"
          >
            <span class="mono tabular row-seq">{{ msg.sequence }}</span>
            <span class="mono row-subject">{{ msg.subject }}</span>
            <span class="muted row-preview small">{{ truncate(msg.data, 60) || '(empty)' }}</span>
            <span class="mono small row-time">{{ formatTimestamp(msg.timestamp) }}</span>
          </button>
          <div v-if="expandedMessageSeq === msg.sequence" class="row-expanded">
            <div class="row-meta">
              <span><span class="kv-label">Subject:</span> <span class="mono">{{ msg.subject }}</span></span>
              <span><span class="kv-label">Seq:</span> <span class="mono tabular">{{ msg.sequence }}</span></span>
              <span><span class="kv-label">Time:</span> <span class="mono small">{{ formatTimestamp(msg.timestamp) }}</span></span>
              <span class="row-meta-spacer" />
              <Button size="xs" icon="trash" @click="handleDeleteStreamMessage(msg.sequence)">Delete</Button>
            </div>
            <div v-if="msg.headers.length > 0" class="headers-block">
              <span class="kv-label">Headers</span>
              <div v-for="h in msg.headers" :key="h.key" class="header-row">
                <span class="mono">{{ h.key }}:</span>
                <span class="mono small">{{ h.values.join(', ') }}</span>
              </div>
            </div>
            <ClientOnly v-if="isJsonObject(msg.data)">
              <JsonEditorVue
                :model-value="parseJsonOrString(msg.data)"
                :read-only="true"
                :main-menu-bar="false"
                :navigation-bar="false"
                class="jse-theme-dark payload-editor"
              />
            </ClientOnly>
            <pre v-else class="payload-pre">{{ msg.data || '(empty)' }}</pre>
          </div>
        </div>
      </div>

      <template #footer>
        <Button @click="streamDrawerOpen = false">Close</Button>
      </template>
    </Drawer>

    <!-- ── KV entries drawer ─────────────────────────────────────── -->
    <Drawer
      v-if="kvDrawerOpen && selectedKvBucket"
      :title="selectedKvBucket.bucket"
      subtitle="KeyValue store"
      icon="database"
      :accent="accent"
      width="900px"
      @close="kvDrawerOpen = false"
    >
      <template #actions>
        <Button size="sm" icon="trash" @click="handlePurgeKvDeletes()">Purge Tombstones</Button>
      </template>

      <div class="drawer-stat-grid">
        <StatTile label="Entries" :value="formatNumber(selectedKvBucket.entryCount)" :accent="accent" />
        <StatTile label="Size" :value="formatBytes(selectedKvBucket.bytes)" />
        <StatTile label="History" :value="String(selectedKvBucket.history)" />
        <StatTile label="TTL" :value="selectedKvBucket.ttl === 0 ? '∞' : formatDurationMs(selectedKvBucket.ttl)" />
        <StatTile label="Max Size" :value="selectedKvBucket.maxBytes > 0 ? formatBytes(selectedKvBucket.maxBytes) : 'Unlimited'" />
        <StatTile label="Max Value" :value="selectedKvBucket.maxValueSize > 0 ? formatBytes(selectedKvBucket.maxValueSize) : 'Unlimited'" />
      </div>

      <h4 class="detail-heading">Put Entry</h4>
      <div class="put-form">
        <TextInput v-model="newKvKey" placeholder="key" size="sm" />
        <TextInput v-model="newKvValue" placeholder="value" size="sm" />
        <Button
          primary
          size="sm"
          icon="check"
          :accent="accent"
          :disabled="!newKvKey || savingKvEntry"
          @click="handlePutKvEntry()"
        >
          {{ savingKvEntry ? 'Saving…' : 'Put' }}
        </Button>
      </div>

      <h4 class="detail-heading">Entries</h4>
      <div class="filter-bar">
        <TextInput
          v-model="kvKeyFilter"
          placeholder="Key filter (glob: prefix.*)"
          size="sm"
          @keyup.enter="loadKvEntries(selectedKvBucket.bucket)"
        />
        <TextInput
          v-model="kvValueSearch"
          placeholder="Search in values…"
          size="sm"
          icon="search"
          @keyup.enter="loadKvEntries(selectedKvBucket.bucket)"
        />
        <Button size="sm" @click="loadKvEntries(selectedKvBucket.bucket)">Apply</Button>
      </div>
      <div v-if="loadingKvEntries" class="empty-state">Loading entries…</div>
      <div v-else-if="kvEntries.length === 0" class="empty-state">
        No live entries (bucket may contain only tombstones).
      </div>
      <div v-else class="row-list">
        <div v-for="entry in kvEntries" :key="entry.key" class="row-item">
          <button
            type="button"
            class="row-summary"
            @click="toggleKvRow(entry.key)"
          >
            <span class="mono row-key">{{ entry.key }}</span>
            <span class="muted row-preview small">{{ truncate(entry.value, 80) || '—' }}</span>
            <Badge :color="ACCENT_NEUTRAL">Rev {{ entry.revision }}</Badge>
            <Badge :color="entry.operation === 'PUT' ? ACCENT_OK : ACCENT_ERR">{{ entry.operation }}</Badge>
          </button>
          <div v-if="expandedKvKey === entry.key" class="row-expanded">
            <div class="row-meta">
              <span><span class="kv-label">Key:</span> <span class="mono">{{ entry.key }}</span></span>
              <span><span class="kv-label">Rev:</span> <span class="mono tabular">{{ entry.revision }}</span></span>
              <span><span class="kv-label">Op:</span> <span class="mono">{{ entry.operation }}</span></span>
              <span><span class="kv-label">Created:</span> <span class="mono small">{{ formatTimestamp(entry.created) }}</span></span>
              <span class="row-meta-spacer" />
              <Button size="xs" icon="trash" @click="handleDeleteKvEntry(entry.key)">Delete</Button>
            </div>
            <ClientOnly v-if="isJsonObject(entry.value)">
              <JsonEditorVue
                :model-value="parseJsonOrString(entry.value)"
                :read-only="true"
                :main-menu-bar="false"
                :navigation-bar="false"
                class="jse-theme-dark payload-editor"
              />
            </ClientOnly>
            <pre v-else class="payload-pre">{{ entry.value || '(empty)' }}</pre>
          </div>
        </div>
      </div>

      <template #footer>
        <Button @click="kvDrawerOpen = false">Close</Button>
      </template>
    </Drawer>
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
  margin-bottom: 14px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.detail-heading {
  margin: 14px 0 8px;
  font-size: 11.5px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-weight: 600;
  color: var(--fg-3);
}

.detail-heading:first-of-type { margin-top: 0; }

.put-form {
  display: grid;
  grid-template-columns: 200px 1fr auto;
  gap: 8px;
  align-items: end;
  padding: 12px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  margin-bottom: 8px;
}

.row-list {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
  background: var(--bg-1);
}

.row-item {
  border-bottom: 1px solid var(--line);
}

.row-item:last-child { border-bottom: none; }

.row-summary {
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

.row-summary:hover { background: var(--bg-2); }

.row-seq {
  width: 60px;
  flex-shrink: 0;
  color: var(--fg-2);
}

.row-key {
  width: 220px;
  flex-shrink: 0;
  font-weight: 500;
  color: var(--fg-0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-subject {
  width: 200px;
  flex-shrink: 0;
  color: var(--fg-0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-preview {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-time {
  flex-shrink: 0;
  color: var(--fg-3);
}

.row-expanded {
  background: var(--bg-2);
  border-top: 1px solid var(--line);
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.row-meta {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 12px;
  flex-wrap: wrap;
}

.row-meta-spacer { flex: 1; }

.kv-label {
  color: var(--fg-3);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-weight: 600;
  margin-right: 4px;
}

.headers-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 6px;
}

.header-row { display: flex; gap: 8px; font-size: 12px; }

.payload-editor {
  border-radius: 8px;
  overflow: hidden;
}

.payload-pre {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--fg-0);
  margin: 0;
  max-height: 320px;
  overflow-y: auto;
}

.empty-state {
  padding: 24px 16px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.muted { color: var(--fg-3); }
.small { font-size: 11.5px; }
</style>
