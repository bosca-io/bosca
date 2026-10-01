<script setup lang="ts">
import gql from 'graphql-tag'
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

function formatMs(ms: number | null | undefined): string {
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

function formatMicroseconds(us: number | null | undefined): string {
  if (us == null) return '—'
  if (us < 1000) return `${us}\u00B5s`
  if (us < 1000000) return `${(us / 1000).toFixed(1)}ms`
  return `${(us / 1000000).toFixed(2)}s`
}

function truncateQuery(q: string | null | undefined, max = 120): string {
  if (!q) return ''
  return q.length <= max ? q : `${q.substring(0, max)}…`
}

/* ── Result types ─────────────────────────────────────────────────── */

interface PgDatabaseStats {
  databaseName: string
  numBackends: number
  xactCommit: number
  xactRollback: number
  blksRead: number
  blksHit: number
  tupReturned: number
  tupFetched: number
  tupInserted: number
  tupUpdated: number
  tupDeleted: number
  conflicts: number
  tempFiles: number
  tempBytes: number
  deadlocks: number
  cacheHitRatio: number
  databaseSize: number
  statsReset: string | null
}

interface PgActiveQuery {
  pid: number
  databaseName: string | null
  userName: string | null
  applicationName: string | null
  clientAddr: string | null
  state: string | null
  query: string | null
  queryStart: string | null
  queryDurationSeconds: number | null
  waitEventType: string | null
  waitEvent: string | null
  backendType: string | null
}

interface PgConnectionPoolStats {
  poolName: string
  maxConnections: number
  activeConnections: number
  createdConnections: number
  hasAvailableConnections: boolean
}

interface PgVacuumProgress {
  pid: number
  databaseName: string | null
  schemaName: string | null
  tableName: string | null
  phase: string
  heapBlksTotal: number
  heapBlksScanned: number
  heapBlksVacuumed: number
  numDeadTuples: number
}

interface PgLockInfo {
  pid: number
  lockType: string
  databaseName: string | null
  relationName: string | null
  mode: string
  granted: boolean
  query: string | null
  state: string | null
  durationSeconds: number | null
}

interface PgReplicationSlot {
  slotName: string
  slotType: string
  active: boolean
  databaseName: string | null
  confirmedFlushLsn: string | null
  retainedWalBytes: number | null
}

interface PgReplicationStatus {
  pid: number
  userName: string | null
  applicationName: string | null
  clientAddr: string | null
  state: string | null
  sentLsn: string | null
  writeLsn: string | null
  flushLsn: string | null
  replayLsn: string | null
  replayLagSeconds: number | null
  writeLagSeconds: number | null
  flushLagSeconds: number | null
  syncState: string | null
  syncPriority: number | null
}

interface PgBouncerPool {
  database: string
  user: string
  clActive: number
  clWaiting: number
  svActive: number
  svIdle: number
  svUsed: number
  svLogin: number
  maxwait: number
  poolMode: string
}

interface PgBouncerStat {
  database: string
  avgXactCount: number
  avgQueryCount: number
  avgXactTime: number
  avgQueryTime: number
  avgWaitTime: number
}

interface PgBouncerDatabase {
  name: string
  host: string | null
  port: number
  database: string
  poolSize: number
  maxConnections: number
  currentConnections: number
  poolMode: string | null
  paused: boolean
  disabled: boolean
}

interface PgBouncerInfo {
  available: boolean
  version: string | null
  pools: PgBouncerPool[]
  stats: PgBouncerStat[]
  databases: PgBouncerDatabase[]
}

interface PgTableStats {
  schemaName: string
  tableName: string
  seqScan: number
  seqTupRead: number
  idxScan: number
  idxTupFetch: number
  nTupIns: number
  nTupUpd: number
  nTupDel: number
  nTupHotUpd: number
  nLiveTup: number
  nDeadTup: number
  lastVacuum: string | null
  lastAutovacuum: string | null
  lastAnalyze: string | null
  lastAutoanalyze: string | null
  vacuumCount: number
  autovacuumCount: number
  analyzeCount: number
  autoanalyzeCount: number
  totalSize: number
  tableSize: number
  indexSize: number
  bloatRatio: number
}

interface PgIndexStats {
  schemaName: string
  tableName: string
  indexName: string
  idxScan: number
  idxTupRead: number
  idxTupFetch: number
  indexSize: number
  indexDef: string | null
}

interface PgIndexSuggestion {
  schemaName: string
  tableName: string
  seqScan: number
  seqTupRead: number
  idxScan: number
  tableSize: number
  reason: string
}

interface PgSlowQuery {
  query: string
  calls: number
  totalTimeMs: number
  meanTimeMs: number
  minTimeMs: number
  maxTimeMs: number
  stddevTimeMs: number
  rows: number
  sharedBlksHit: number
  sharedBlksRead: number
  hitRatio: number
}

interface PgTableIOStats {
  schemaName: string
  tableName: string
  heapBlksRead: number
  heapBlksHit: number
  idxBlksRead: number
  idxBlksHit: number
  toastBlksRead: number
  toastBlksHit: number
  cacheHitRatio: number
}

interface PgSetting {
  name: string
  setting: string
  unit: string | null
  category: string
  shortDesc: string
  context: string
  vartype: string
  source: string
  minVal: string | null
  maxVal: string | null
  pendingRestart: boolean
}

interface PgOverview {
  postgresAdmin: {
    serverVersion: string
    uptime: string
    databaseStats: PgDatabaseStats
    activeQueries: PgActiveQuery[]
    connectionPoolStats: PgConnectionPoolStats[]
    vacuumProgress: PgVacuumProgress[]
    locks: PgLockInfo[]
    replicationSlots: PgReplicationSlot[]
    isInRecovery: boolean
    replicationStatus: PgReplicationStatus[]
    pgBouncerInfo: PgBouncerInfo
  }
}

/* ── Queries ─────────────────────────────────────────────────────── */

const overviewGql = gql`
  query GetPostgresAdminOverview {
    postgresAdmin {
      serverVersion
      uptime
      databaseStats {
        databaseName numBackends xactCommit xactRollback
        blksRead blksHit tupReturned tupFetched
        tupInserted tupUpdated tupDeleted
        conflicts tempFiles tempBytes deadlocks
        cacheHitRatio databaseSize statsReset
      }
      activeQueries {
        pid databaseName userName applicationName clientAddr
        state query queryStart queryDurationSeconds
        waitEventType waitEvent backendType
      }
      connectionPoolStats {
        poolName maxConnections activeConnections createdConnections hasAvailableConnections
      }
      vacuumProgress {
        pid databaseName schemaName tableName phase
        heapBlksTotal heapBlksScanned heapBlksVacuumed numDeadTuples
      }
      locks {
        pid lockType databaseName relationName mode
        granted query state durationSeconds
      }
      replicationSlots {
        slotName slotType active databaseName confirmedFlushLsn retainedWalBytes
      }
      isInRecovery
      replicationStatus {
        pid userName applicationName clientAddr state
        sentLsn writeLsn flushLsn replayLsn
        replayLagSeconds writeLagSeconds flushLagSeconds
        syncState syncPriority
      }
      pgBouncerInfo {
        available version
        pools {
          database user clActive clWaiting
          svActive svIdle svUsed svLogin maxwait poolMode
        }
        stats {
          database avgXactCount avgQueryCount
          avgXactTime avgQueryTime avgWaitTime
        }
        databases {
          name host port database poolSize maxConnections
          currentConnections poolMode paused disabled
        }
      }
    }
  }
`

const tableStatsGql = gql`
  query GetPostgresTableStats($schemaName: String) {
    postgresAdmin {
      tableStats(schemaName: $schemaName) {
        schemaName tableName seqScan seqTupRead idxScan idxTupFetch
        nTupIns nTupUpd nTupDel nTupHotUpd nLiveTup nDeadTup
        lastVacuum lastAutovacuum lastAnalyze lastAutoanalyze
        vacuumCount autovacuumCount analyzeCount autoanalyzeCount
        totalSize tableSize indexSize bloatRatio
      }
    }
  }
`

const indexStatsGql = gql`
  query GetPostgresIndexStats($schemaName: String) {
    postgresAdmin {
      indexStats(schemaName: $schemaName) {
        schemaName tableName indexName idxScan idxTupRead idxTupFetch indexSize indexDef
      }
    }
  }
`

const unusedIndexesGql = gql`
  query GetPostgresUnusedIndexes {
    postgresAdmin {
      unusedIndexes {
        schemaName tableName indexName idxScan idxTupRead idxTupFetch indexSize indexDef
      }
    }
  }
`

const indexSuggestionsGql = gql`
  query GetPostgresIndexSuggestions {
    postgresAdmin {
      indexSuggestions {
        schemaName tableName seqScan seqTupRead idxScan tableSize reason
      }
    }
  }
`

const slowQueriesGql = gql`
  query GetPostgresSlowQueries($limit: Int, $orderBy: String) {
    postgresAdmin {
      slowQueries(limit: $limit, orderBy: $orderBy) {
        query calls totalTimeMs meanTimeMs minTimeMs maxTimeMs stddevTimeMs
        rows sharedBlksHit sharedBlksRead hitRatio
      }
    }
  }
`

const bloatedTablesGql = gql`
  query GetPostgresBloatedTables($minBloatPercent: Float) {
    postgresAdmin {
      bloatedTables(minBloatPercent: $minBloatPercent) {
        schemaName tableName seqScan seqTupRead idxScan idxTupFetch
        nTupIns nTupUpd nTupDel nTupHotUpd nLiveTup nDeadTup
        lastVacuum lastAutovacuum lastAnalyze lastAutoanalyze
        vacuumCount autovacuumCount analyzeCount autoanalyzeCount
        totalSize tableSize indexSize bloatRatio
      }
    }
  }
`

const tableIOStatsGql = gql`
  query GetPostgresTableIOStats($schemaName: String) {
    postgresAdmin {
      tableIOStats(schemaName: $schemaName) {
        schemaName tableName heapBlksRead heapBlksHit
        idxBlksRead idxBlksHit toastBlksRead toastBlksHit cacheHitRatio
      }
    }
  }
`

const settingsGql = gql`
  query GetPostgresSettings($filter: String) {
    postgresAdmin {
      settings(filter: $filter) {
        name setting unit category shortDesc context
        vartype source minVal maxVal pendingRestart
      }
    }
  }
`

const cancelQueryGql = gql`mutation CancelPgQuery($pid: Int!) { postgresAdmin { cancelQuery(pid: $pid) } }`
const terminateBackendGql = gql`mutation TerminatePgBackend($pid: Int!) { postgresAdmin { terminateBackend(pid: $pid) } }`
const analyzeTableGql = gql`mutation AnalyzePgTable($schemaName: String!, $tableName: String!) { postgresAdmin { analyzeTable(schemaName: $schemaName, tableName: $tableName) } }`
const resetStatStatementsGql = gql`mutation ResetPgStatStatements { postgresAdmin { resetStatStatements } }`

/* ── State ────────────────────────────────────────────────────────── */

const TABS = ['Overview', 'Cluster', 'Tables', 'Indexes', 'Queries', 'Locks', 'Settings'] as const
const activeTab = ref<(typeof TABS)[number]>('Overview')

const { data: overviewData, refresh: refreshOverview } = useAsyncQuery<PgOverview>(
  'postgres-admin-overview',
  overviewGql,
)

const overview = computed(() => overviewData.value?.postgresAdmin ?? null)
const serverVersion = computed(() => overview.value?.serverVersion ?? '')
const uptime = computed(() => overview.value?.uptime ?? '')
const dbStats = computed<PgDatabaseStats | null>(() => overview.value?.databaseStats ?? null)
const activeQueries = computed<PgActiveQuery[]>(() => overview.value?.activeQueries ?? [])
const poolStats = computed<PgConnectionPoolStats[]>(() => overview.value?.connectionPoolStats ?? [])
const vacuumProgress = computed<PgVacuumProgress[]>(() => overview.value?.vacuumProgress ?? [])
const locks = computed<PgLockInfo[]>(() => overview.value?.locks ?? [])
const replicationSlots = computed<PgReplicationSlot[]>(() => overview.value?.replicationSlots ?? [])
const isInRecovery = computed(() => overview.value?.isInRecovery ?? false)
const replicationStatus = computed<PgReplicationStatus[]>(() => overview.value?.replicationStatus ?? [])
const pgBouncerInfo = computed<PgBouncerInfo | null>(() => overview.value?.pgBouncerInfo ?? null)

// Tables tab
const tableStats = ref<PgTableStats[]>([])
const loadingTables = ref(false)
const tableSchemaFilter = ref('')
const bloatedTables = ref<PgTableStats[]>([])
const loadingBloated = ref(false)
const tableIOStats = ref<PgTableIOStats[]>([])
const loadingTableIO = ref(false)

// Indexes tab
const indexStats = ref<PgIndexStats[]>([])
const unusedIndexes = ref<PgIndexStats[]>([])
const indexSuggestions = ref<PgIndexSuggestion[]>([])
const loadingIndexes = ref(false)
const indexSchemaFilter = ref('')
const indexSubTab = ref<'all' | 'unused' | 'suggestions'>('all')

// Queries tab
const slowQueries = ref<PgSlowQuery[]>([])
const loadingSlowQueries = ref(false)
const slowQueryOrderBy = ref<'total' | 'mean'>('total')
const expandedQueryIndex = ref<number | null>(null)
const expandedQuery = computed<PgSlowQuery | null>(() => {
  if (expandedQueryIndex.value == null) return null
  return slowQueries.value[expandedQueryIndex.value] ?? null
})

// Settings tab
const settings = ref<PgSetting[]>([])
const loadingSettings = ref(false)
const settingsFilter = ref('')

// Table-detail drawer
const drawerOpen = ref(false)
const drawerTable = ref<PgTableStats | null>(null)

/* ── Loaders (lazy on tab activation) ──────────────────────────────── */

async function loadTableStats() {
  loadingTables.value = true
  try {
    const result = await gqlQuery<{ postgresAdmin: { tableStats: PgTableStats[] } }>(
      tableStatsGql,
      tableSchemaFilter.value ? { schemaName: tableSchemaFilter.value } : {},
    )
    tableStats.value = result.postgresAdmin?.tableStats ?? []
  } catch (e: unknown) {
    toast.error(`Failed to load table stats: ${e instanceof Error ? e.message : 'Unknown error'}`)
    tableStats.value = []
  } finally {
    loadingTables.value = false
  }
}

async function loadIndexStats() {
  loadingIndexes.value = true
  try {
    const [indexResult, unusedResult, suggestionsResult] = await Promise.all([
      gqlQuery<{ postgresAdmin: { indexStats: PgIndexStats[] } }>(
        indexStatsGql,
        indexSchemaFilter.value ? { schemaName: indexSchemaFilter.value } : {},
      ),
      gqlQuery<{ postgresAdmin: { unusedIndexes: PgIndexStats[] } }>(unusedIndexesGql, {}),
      gqlQuery<{ postgresAdmin: { indexSuggestions: PgIndexSuggestion[] } }>(indexSuggestionsGql, {}),
    ])
    indexStats.value = indexResult.postgresAdmin?.indexStats ?? []
    unusedIndexes.value = unusedResult.postgresAdmin?.unusedIndexes ?? []
    indexSuggestions.value = suggestionsResult.postgresAdmin?.indexSuggestions ?? []
  } catch (e: unknown) {
    toast.error(`Failed to load index stats: ${e instanceof Error ? e.message : 'Unknown error'}`)
  } finally {
    loadingIndexes.value = false
  }
}

async function loadSlowQueries() {
  loadingSlowQueries.value = true
  try {
    const result = await gqlQuery<{ postgresAdmin: { slowQueries: PgSlowQuery[] } }>(
      slowQueriesGql,
      { limit: 30, orderBy: slowQueryOrderBy.value },
    )
    slowQueries.value = result.postgresAdmin?.slowQueries ?? []
  } catch (e: unknown) {
    toast.error(`Failed to load slow queries: ${e instanceof Error ? e.message : 'Unknown error'}`)
    slowQueries.value = []
  } finally {
    loadingSlowQueries.value = false
  }
}

async function loadSettings() {
  loadingSettings.value = true
  try {
    const result = await gqlQuery<{ postgresAdmin: { settings: PgSetting[] } }>(
      settingsGql,
      settingsFilter.value ? { filter: settingsFilter.value } : {},
    )
    settings.value = result.postgresAdmin?.settings ?? []
  } catch (e: unknown) {
    toast.error(`Failed to load settings: ${e instanceof Error ? e.message : 'Unknown error'}`)
    settings.value = []
  } finally {
    loadingSettings.value = false
  }
}

async function loadBloatedTables() {
  loadingBloated.value = true
  try {
    const result = await gqlQuery<{ postgresAdmin: { bloatedTables: PgTableStats[] } }>(
      bloatedTablesGql,
      { minBloatPercent: 5 },
    )
    bloatedTables.value = result.postgresAdmin?.bloatedTables ?? []
  } catch (e: unknown) {
    toast.error(`Failed to load bloated tables: ${e instanceof Error ? e.message : 'Unknown error'}`)
    bloatedTables.value = []
  } finally {
    loadingBloated.value = false
  }
}

async function loadTableIOStats() {
  loadingTableIO.value = true
  try {
    const result = await gqlQuery<{ postgresAdmin: { tableIOStats: PgTableIOStats[] } }>(
      tableIOStatsGql,
      {},
    )
    tableIOStats.value = result.postgresAdmin?.tableIOStats ?? []
  } catch (e: unknown) {
    toast.error(`Failed to load table I/O stats: ${e instanceof Error ? e.message : 'Unknown error'}`)
    tableIOStats.value = []
  } finally {
    loadingTableIO.value = false
  }
}

// Auto-load when a tab first opens (mirrors the legacy lazy-load behavior).
watch(activeTab, (tab) => {
  if (tab === 'Tables' && tableStats.value.length === 0) loadTableStats()
  if (tab === 'Indexes' && indexStats.value.length === 0) loadIndexStats()
  if (tab === 'Queries' && slowQueries.value.length === 0) loadSlowQueries()
  if (tab === 'Settings' && settings.value.length === 0) loadSettings()
})

/* ── Mutations ─────────────────────────────────────────────────────── */

async function handleCancelQuery(pid: number) {
  if (!confirm(`Cancel query on backend PID ${pid}?`)) return
  try {
    await gqlMutation(cancelQueryGql, { pid })
    toast.success(`Cancel signal sent to PID ${pid}`)
    await refreshOverview()
  } catch (e: unknown) {
    toast.error(`Failed to cancel query: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

async function handleTerminateBackend(pid: number) {
  if (!confirm(`Terminate backend PID ${pid}? This will forcefully close the connection.`)) return
  try {
    await gqlMutation(terminateBackendGql, { pid })
    toast.success(`Terminate signal sent to PID ${pid}`)
    await refreshOverview()
  } catch (e: unknown) {
    toast.error(`Failed to terminate backend: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

async function handleAnalyzeTable(schemaName: string, tableName: string) {
  if (!confirm(`Run ANALYZE on "${schemaName}"."${tableName}"?`)) return
  try {
    await gqlMutation(analyzeTableGql, { schemaName, tableName })
    toast.success(`ANALYZE complete on ${schemaName}.${tableName}`)
    await loadTableStats()
  } catch (e: unknown) {
    toast.error(`Failed to analyze table: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

async function handleResetStatStatements() {
  if (!confirm('Reset all pg_stat_statements statistics? This cannot be undone.')) return
  try {
    await gqlMutation(resetStatStatementsGql, {})
    toast.success('pg_stat_statements statistics reset')
    await loadSlowQueries()
  } catch (e: unknown) {
    toast.error(`Failed to reset statistics: ${e instanceof Error ? e.message : 'Unknown error'}`)
  }
}

/* ── Auto-refresh overview every 10s ───────────────────────────────── */

let refreshTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  refreshTimer = setInterval(() => {
    refreshOverview().catch(() => { /* errors surfaced via the loaders */ })
  }, 10000)
})
onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})

/* ── GlassTable column definitions ─────────────────────────────────── */

const activeQueryColumns: GlassTableColumn[] = [
  { key: 'pid', label: 'PID', width: '80px' },
  { key: 'userName', label: 'User', width: '120px', muted: true },
  { key: 'applicationName', label: 'App', width: '140px', muted: true },
  { key: 'state', label: 'State', width: '140px' },
  { key: 'queryDurationSeconds', label: 'Duration', width: '90px' },
  { key: 'wait', label: 'Wait', width: '160px', muted: true },
  { key: 'query', label: 'Query', width: 'minmax(240px, 2fr)' },
  { key: 'actions', label: '', width: '120px' },
]

const tableStatsColumns: GlassTableColumn[] = [
  { key: 'tableName', label: 'Table', width: 'minmax(200px, 1.5fr)' },
  { key: 'schemaName', label: 'Schema', width: '140px', muted: true },
  { key: 'totalSize', label: 'Size', width: '110px', align: 'right' },
  { key: 'nLiveTup', label: 'Live Rows', width: '110px', align: 'right' },
  { key: 'nDeadTup', label: 'Dead Rows', width: '110px', align: 'right' },
  { key: 'seqScan', label: 'Seq Scans', width: '110px', align: 'right' },
  { key: 'idxScan', label: 'Idx Scans', width: '110px', align: 'right' },
  { key: 'bloatRatio', label: 'Bloat %', width: '100px' },
]

const indexStatsColumns: GlassTableColumn[] = [
  { key: 'indexName', label: 'Index', width: 'minmax(200px, 1.5fr)' },
  { key: 'tableName', label: 'Table', width: '1fr' },
  { key: 'schemaName', label: 'Schema', width: '140px', muted: true },
  { key: 'idxScan', label: 'Scans', width: '110px', align: 'right' },
  { key: 'idxTupRead', label: 'Tuples Read', width: '120px', align: 'right' },
  { key: 'indexSize', label: 'Size', width: '110px', align: 'right' },
]

const slowQueryColumns: GlassTableColumn[] = [
  { key: 'query', label: 'Query', width: 'minmax(240px, 2fr)' },
  { key: 'calls', label: 'Calls', width: '90px', align: 'right' },
  { key: 'totalTimeMs', label: 'Total', width: '110px', align: 'right' },
  { key: 'meanTimeMs', label: 'Mean', width: '100px', align: 'right' },
  { key: 'maxTimeMs', label: 'Max', width: '100px', align: 'right' },
  { key: 'rows', label: 'Rows', width: '100px', align: 'right' },
  { key: 'hitRatio', label: 'Hit %', width: '90px' },
]

const lockColumns: GlassTableColumn[] = [
  { key: 'pid', label: 'PID', width: '80px' },
  { key: 'lockType', label: 'Type', width: '140px' },
  { key: 'relationName', label: 'Relation', width: '1fr', muted: true },
  { key: 'mode', label: 'Mode', width: '180px' },
  { key: 'granted', label: 'Granted', width: '110px' },
  { key: 'state', label: 'State', width: '140px', muted: true },
  { key: 'durationSeconds', label: 'Duration', width: '100px', align: 'right' },
]

const settingsColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 1.2fr)' },
  { key: 'setting', label: 'Value', width: '1fr' },
  { key: 'category', label: 'Category', width: '1fr', muted: true },
  { key: 'context', label: 'Context', width: '140px', muted: true },
  { key: 'source', label: 'Source', width: '140px', muted: true },
  { key: 'pendingRestart', label: 'Restart?', width: '100px' },
]

/* ── Display helpers ───────────────────────────────────────────────── */

const ACCENT_OK = '#34d99a'
const ACCENT_WARN = '#ffb547'
const ACCENT_ERR = '#ff5d6c'
const ACCENT_INFO = '#5ec5ff'
const ACCENT_NEUTRAL = '#6c7388'

function stateBadgeColor(state: string | null): string {
  if (state === 'active') return ACCENT_OK
  if (state === 'idle in transaction') return ACCENT_WARN
  if (state === 'idle') return ACCENT_NEUTRAL
  return ACCENT_INFO
}

function durationBadgeColor(seconds: number | null): string {
  if (seconds == null) return ACCENT_NEUTRAL
  if (seconds > 30) return ACCENT_ERR
  if (seconds > 5) return ACCENT_WARN
  return ACCENT_INFO
}

function bloatBadgeColor(ratio: number): string {
  if (ratio > 50) return ACCENT_ERR
  if (ratio > 20) return ACCENT_WARN
  return ACCENT_OK
}

function hitRatioColor(ratio: number): string {
  if (ratio < 90) return ACCENT_WARN
  return ACCENT_OK
}

function cacheHitColor(ratio: number): string {
  if (ratio >= 99) return ACCENT_OK
  if (ratio >= 90) return ACCENT_WARN
  return ACCENT_ERR
}

function poolUtilizationColor(active: number, max: number): string {
  if (max <= 0) return ACCENT_NEUTRAL
  const ratio = active / max
  if (ratio >= 0.9) return ACCENT_ERR
  if (ratio >= 0.7) return ACCENT_WARN
  return ACCENT_OK
}

function showTableDetail(row: PgTableStats) {
  drawerTable.value = row
  drawerOpen.value = true
}

function toggleQueryRow(idx: number) {
  expandedQueryIndex.value = expandedQueryIndex.value === idx ? null : idx
}

const ORDER_BY_OPTIONS = [
  { value: 'total', label: 'Total Time' },
  { value: 'mean', label: 'Mean Time' },
]

const INDEX_SUB_TABS = computed(() => [
  `All Indexes (${indexStats.value.length})`,
  `Unused (${unusedIndexes.value.length})`,
  `Suggestions (${indexSuggestions.value.length})`,
])

const indexSubTabValue = computed({
  get: () => {
    if (indexSubTab.value === 'all') return INDEX_SUB_TABS.value[0] ?? ''
    if (indexSubTab.value === 'unused') return INDEX_SUB_TABS.value[1] ?? ''
    return INDEX_SUB_TABS.value[2] ?? ''
  },
  set: (label: string) => {
    if (label.startsWith('All')) indexSubTab.value = 'all'
    else if (label.startsWith('Unused')) indexSubTab.value = 'unused'
    else indexSubTab.value = 'suggestions'
  },
})

const subtitle = computed(() => {
  const parts: string[] = []
  if (serverVersion.value) parts.push(serverVersion.value)
  if (uptime.value) parts.push(`uptime ${uptime.value}`)
  return parts.join(' · ')
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'PostgreSQL')"
        title="PostgreSQL"
        :subtitle="subtitle"
        :tabs="[...TABS]"
        :active-tab="activeTab"
        @tab="(t: string) => activeTab = t as typeof TABS[number]"
      >
        <template #actions>
          <Button size="sm" icon="pulse" @click="refreshOverview()">Refresh</Button>
        </template>
      </PageHeader>
    </template>

    <!-- Always-visible top stat row -->
    <div class="stat-grid">
      <StatTile
        label="Status"
        :value="overview ? 'Connected' : 'Disconnected'"
        :accent="overview ? ACCENT_OK : ACCENT_ERR"
        value-size="sm"
      />
      <StatTile
        label="Connections"
        :value="dbStats ? String(dbStats.numBackends) : '—'"
        :accent="accent"
      />
      <StatTile
        label="Cache Hit Ratio"
        :value="dbStats ? `${dbStats.cacheHitRatio.toFixed(1)}%` : '—'"
        :accent="dbStats ? hitRatioColor(dbStats.cacheHitRatio) : undefined"
      />
      <StatTile
        label="Database Size"
        :value="dbStats ? formatBytes(dbStats.databaseSize) : '—'"
      />
      <StatTile
        label="Commits"
        :value="dbStats ? formatNumber(dbStats.xactCommit) : '—'"
      />
      <StatTile
        label="Rollbacks"
        :value="dbStats ? formatNumber(dbStats.xactRollback) : '—'"
      />
      <StatTile
        label="Deadlocks"
        :value="dbStats ? formatNumber(dbStats.deadlocks) : '—'"
        :accent="dbStats && dbStats.deadlocks > 0 ? ACCENT_ERR : undefined"
      />
    </div>

    <!-- ── Overview Tab ──────────────────────────────────────────── -->
    <template v-if="activeTab === 'Overview'">
      <SectionCard title="Connection Pools" :subtitle="`${poolStats.length} pools`">
        <div v-if="poolStats.length === 0" class="empty-state">No application pools reported.</div>
        <div v-else class="pool-grid">
          <div v-for="pool in poolStats" :key="pool.poolName" class="pool-card">
            <div class="pool-header">
              <span class="pool-name">{{ pool.poolName }}</span>
              <Badge :color="pool.hasAvailableConnections ? ACCENT_OK : ACCENT_ERR">
                {{ pool.hasAvailableConnections ? 'Available' : 'Saturated' }}
              </Badge>
            </div>
            <div class="kv-grid">
              <span class="kv-label">Active</span>
              <span class="mono tabular">{{ pool.activeConnections }} / {{ pool.maxConnections }}</span>
              <span class="kv-label">Created</span>
              <span class="mono tabular">{{ formatNumber(pool.createdConnections) }}</span>
            </div>
            <ProgressBar
              :value="pool.maxConnections > 0 ? (pool.activeConnections / pool.maxConnections) * 100 : 0"
              :accent="poolUtilizationColor(pool.activeConnections, pool.maxConnections)"
              :height="6"
            />
          </div>
        </div>
      </SectionCard>

      <SectionCard title="Database I/O">
        <div class="io-grid">
          <StatTile label="Rows Inserted" :value="dbStats ? formatNumber(dbStats.tupInserted) : '—'" />
          <StatTile label="Rows Updated" :value="dbStats ? formatNumber(dbStats.tupUpdated) : '—'" />
          <StatTile label="Rows Deleted" :value="dbStats ? formatNumber(dbStats.tupDeleted) : '—'" />
          <StatTile
            label="Temp Files"
            :value="dbStats ? formatNumber(dbStats.tempFiles) : '—'"
            :sub="dbStats ? formatBytes(dbStats.tempBytes) : undefined"
          />
        </div>
      </SectionCard>

      <SectionCard
        :title="`Active Queries (${activeQueries.length})`"
      >
        <GlassTable
          :columns="activeQueryColumns"
          :rows="activeQueries"
          row-key="pid"
          :arrow="false"
          empty-text="No active queries."
        >
          <template #col-userName="{ row }">
            <span class="mono">{{ row.userName || '—' }}</span>
          </template>
          <template #col-applicationName="{ row }">
            <span class="mono">{{ row.applicationName || '—' }}</span>
          </template>
          <template #col-state="{ row }">
            <Badge v-if="row.state" :color="stateBadgeColor(row.state)">{{ row.state }}</Badge>
            <span v-else class="muted">—</span>
          </template>
          <template #col-queryDurationSeconds="{ row }">
            <Badge
              v-if="row.queryDurationSeconds != null"
              :color="durationBadgeColor(row.queryDurationSeconds)"
            >
              {{ row.queryDurationSeconds.toFixed(1) }}s
            </Badge>
            <span v-else class="muted">—</span>
          </template>
          <template #col-wait="{ row }">
            <span v-if="row.waitEventType" class="mono">{{ row.waitEventType }}: {{ row.waitEvent }}</span>
            <span v-else class="muted">—</span>
          </template>
          <template #col-query="{ row }">
            <span class="mono small">{{ truncateQuery(row.query, 80) }}</span>
          </template>
          <template #col-actions="{ row }">
            <div v-if="row.state === 'active'" class="row-actions">
              <button class="action-btn warn" @click.stop="handleCancelQuery(row.pid)">Cancel</button>
              <button class="action-btn err" @click.stop="handleTerminateBackend(row.pid)">Kill</button>
            </div>
          </template>
        </GlassTable>
      </SectionCard>

      <SectionCard
        v-if="vacuumProgress.length > 0"
        :title="`Vacuum Progress (${vacuumProgress.length})`"
        padded
      >
        <div class="vacuum-list">
          <div v-for="v in vacuumProgress" :key="v.pid" class="vacuum-card">
            <div class="vacuum-header">
              <span class="vacuum-target">
                <span class="mono">{{ v.schemaName }}.{{ v.tableName }}</span>
                <span class="muted small">PID {{ v.pid }}</span>
              </span>
              <Badge :color="ACCENT_INFO">{{ v.phase }}</Badge>
            </div>
            <ProgressBar
              :value="v.heapBlksTotal > 0 ? (v.heapBlksVacuumed / v.heapBlksTotal) * 100 : 0"
              :accent="ACCENT_INFO"
              :sub="`${formatNumber(v.heapBlksVacuumed)} / ${formatNumber(v.heapBlksTotal)} blocks · ${formatNumber(v.numDeadTuples)} dead tuples`"
            />
          </div>
        </div>
      </SectionCard>
    </template>

    <!-- ── Cluster Tab ───────────────────────────────────────────── -->
    <template v-if="activeTab === 'Cluster'">
      <SectionCard padded>
        <template #header>
          <span class="section-title-inline">Node Role</span>
          <Badge :color="isInRecovery ? ACCENT_WARN : ACCENT_OK">{{ isInRecovery ? 'Standby' : 'Primary' }}</Badge>
        </template>
      </SectionCard>

      <SectionCard
        v-if="pgBouncerInfo?.available"
        :title="`PgBouncer ${pgBouncerInfo.version ?? ''}`"
        :subtitle="`${pgBouncerInfo.pools.length} pools · ${pgBouncerInfo.databases.length} databases`"
        padded
      >
        <div class="subsection-label">Pools</div>
        <div v-if="pgBouncerInfo.pools.length === 0" class="empty-state">No pools.</div>
        <div v-else class="pool-grid">
          <div v-for="pool in pgBouncerInfo.pools" :key="`${pool.database}-${pool.user}`" class="pool-card">
            <div class="pool-header">
              <span class="pool-name">{{ pool.database }}</span>
              <Badge :color="ACCENT_NEUTRAL">{{ pool.poolMode }}</Badge>
            </div>
            <div class="kv-grid">
              <span class="kv-label">Clients active</span>
              <span class="mono tabular">{{ pool.clActive }}</span>
              <span class="kv-label">Clients waiting</span>
              <span class="mono tabular" :class="{ warn: pool.clWaiting > 0 }">{{ pool.clWaiting }}</span>
              <span class="kv-label">Servers active</span>
              <span class="mono tabular">{{ pool.svActive }}</span>
              <span class="kv-label">Servers idle</span>
              <span class="mono tabular">{{ pool.svIdle }}</span>
              <span class="kv-label">Max wait</span>
              <span class="mono tabular" :class="{ warn: pool.maxwait > 0 }">{{ pool.maxwait }}s</span>
            </div>
          </div>
        </div>

        <div v-if="pgBouncerInfo.stats.length > 0" class="subsection-label">Throughput</div>
        <div v-if="pgBouncerInfo.stats.length > 0" class="stat-grid sm">
          <div
            v-for="stat in pgBouncerInfo.stats.filter(s => s.database !== 'pgbouncer')"
            :key="stat.database"
            class="pool-card compact"
          >
            <div class="pool-name small">{{ stat.database }}</div>
            <div class="kv-grid tiny">
              <span class="kv-label">Avg TPS</span>
              <span class="mono tabular">{{ stat.avgXactCount }}</span>
              <span class="kv-label">Avg QPS</span>
              <span class="mono tabular">{{ stat.avgQueryCount }}</span>
              <span class="kv-label">Avg query</span>
              <span class="mono tabular">{{ formatMicroseconds(stat.avgQueryTime) }}</span>
              <span class="kv-label">Avg wait</span>
              <span class="mono tabular" :class="{ warn: stat.avgWaitTime > 1000 }">{{ formatMicroseconds(stat.avgWaitTime) }}</span>
            </div>
          </div>
        </div>

        <div v-if="pgBouncerInfo.databases.length > 0" class="subsection-label">Databases</div>
        <div v-if="pgBouncerInfo.databases.length > 0" class="pool-grid">
          <div
            v-for="db in pgBouncerInfo.databases.filter(d => d.name !== 'pgbouncer')"
            :key="db.name"
            class="pool-card compact"
          >
            <div class="pool-header">
              <span class="pool-name small">{{ db.name }}</span>
              <div class="row-badges">
                <Badge v-if="db.paused" :color="ACCENT_WARN">Paused</Badge>
                <Badge v-if="db.disabled" :color="ACCENT_ERR">Disabled</Badge>
              </div>
            </div>
            <div class="kv-grid tiny">
              <span class="kv-label">Host</span>
              <span class="mono tabular">{{ db.host || 'local' }}:{{ db.port }}</span>
              <span class="kv-label">Pool size</span>
              <span class="mono tabular">{{ db.poolSize }}</span>
              <span class="kv-label">Connections</span>
              <span class="mono tabular">{{ db.currentConnections }} / {{ db.maxConnections }}</span>
              <span class="kv-label">Mode</span>
              <span class="mono tabular">{{ db.poolMode || '—' }}</span>
            </div>
          </div>
        </div>
      </SectionCard>
      <SectionCard
        v-else-if="pgBouncerInfo && !pgBouncerInfo.available"
        title="PgBouncer"
        padded
      >
        <p class="muted">PgBouncer not detected — direct PostgreSQL connection.</p>
      </SectionCard>

      <SectionCard
        v-if="replicationStatus.length > 0"
        :title="`Streaming Replicas (${replicationStatus.length})`"
        padded
      >
        <div class="pool-grid">
          <div v-for="r in replicationStatus" :key="r.pid" class="pool-card">
            <div class="pool-header">
              <span class="pool-name">{{ r.applicationName || `PID ${r.pid}` }}</span>
              <div class="row-badges">
                <Badge :color="r.state === 'streaming' ? ACCENT_OK : ACCENT_WARN">
                  {{ r.state || 'unknown' }}
                </Badge>
                <Badge :color="ACCENT_NEUTRAL">{{ r.syncState || 'async' }}</Badge>
              </div>
            </div>
            <div class="kv-grid">
              <span class="kv-label">Client</span>
              <span class="mono tabular">{{ r.clientAddr || '—' }}</span>
              <span class="kv-label">User</span>
              <span class="mono tabular">{{ r.userName || '—' }}</span>
              <span class="kv-label">Replay lag</span>
              <span
                class="mono tabular"
                :class="{
                  err: (r.replayLagSeconds ?? 0) > 5,
                  warn: (r.replayLagSeconds ?? 0) > 1 && (r.replayLagSeconds ?? 0) <= 5,
                }"
              >{{ r.replayLagSeconds != null ? `${r.replayLagSeconds.toFixed(3)}s` : '—' }}</span>
              <span class="kv-label">Write lag</span>
              <span class="mono tabular">{{ r.writeLagSeconds != null ? `${r.writeLagSeconds.toFixed(3)}s` : '—' }}</span>
              <span class="kv-label">Flush lag</span>
              <span class="mono tabular">{{ r.flushLagSeconds != null ? `${r.flushLagSeconds.toFixed(3)}s` : '—' }}</span>
              <span class="kv-label">Sent LSN</span>
              <span class="mono tabular small">{{ r.sentLsn || '—' }}</span>
              <span class="kv-label">Replay LSN</span>
              <span class="mono tabular small">{{ r.replayLsn || '—' }}</span>
            </div>
          </div>
        </div>
      </SectionCard>

      <SectionCard
        v-if="replicationSlots.length > 0"
        :title="`Replication Slots (${replicationSlots.length})`"
        padded
      >
        <div class="pool-grid">
          <div v-for="slot in replicationSlots" :key="slot.slotName" class="pool-card">
            <div class="pool-header">
              <span class="pool-name">{{ slot.slotName }}</span>
              <Badge :color="slot.active ? ACCENT_OK : ACCENT_ERR">
                {{ slot.active ? 'Active' : 'Inactive' }}
              </Badge>
            </div>
            <div class="kv-grid">
              <span class="kv-label">Type</span>
              <span class="mono tabular">{{ slot.slotType }}</span>
              <span class="kv-label">Database</span>
              <span class="mono tabular">{{ slot.databaseName || '—' }}</span>
              <span class="kv-label">WAL retained</span>
              <span class="mono tabular">{{ slot.retainedWalBytes != null ? formatBytes(slot.retainedWalBytes) : '—' }}</span>
            </div>
          </div>
        </div>
      </SectionCard>
    </template>

    <!-- ── Tables Tab ────────────────────────────────────────────── -->
    <template v-if="activeTab === 'Tables'">
      <SectionCard title="Tables" padded>
        <div class="filter-bar">
          <TextInput v-model="tableSchemaFilter" placeholder="Filter by schema…" size="sm" />
          <Button size="sm" icon="pulse" @click="loadTableStats()">Load</Button>
          <Button size="sm" @click="loadBloatedTables()">Show Bloated</Button>
          <Button size="sm" @click="loadTableIOStats()">Show I/O Stats</Button>
        </div>
        <GlassTable
          :columns="tableStatsColumns"
          :rows="tableStats"
          :row-key="'tableName'"
          :loading="loadingTables"
          loading-text="Loading table statistics…"
          empty-text="Click Load to fetch table statistics."
          @row-click="showTableDetail"
        >
          <template #col-tableName="{ row }">
            <span class="mono" style="font-weight: 500; color: var(--fg-0)">{{ row.tableName }}</span>
          </template>
          <template #col-schemaName="{ row }">
            <span class="mono">{{ row.schemaName }}</span>
          </template>
          <template #col-totalSize="{ row }">
            <span class="mono tabular">{{ formatBytes(row.totalSize) }}</span>
          </template>
          <template #col-nLiveTup="{ row }">
            <span class="mono tabular">{{ formatNumber(row.nLiveTup) }}</span>
          </template>
          <template #col-nDeadTup="{ row }">
            <Badge v-if="row.nDeadTup > 10000" :color="ACCENT_WARN">{{ formatNumber(row.nDeadTup) }}</Badge>
            <span v-else class="mono tabular">{{ formatNumber(row.nDeadTup) }}</span>
          </template>
          <template #col-seqScan="{ row }">
            <span class="mono tabular">{{ formatNumber(row.seqScan) }}</span>
          </template>
          <template #col-idxScan="{ row }">
            <span class="mono tabular">{{ formatNumber(row.idxScan) }}</span>
          </template>
          <template #col-bloatRatio="{ row }">
            <Badge :color="bloatBadgeColor(row.bloatRatio)">{{ row.bloatRatio.toFixed(1) }}%</Badge>
          </template>
        </GlassTable>
      </SectionCard>

      <SectionCard
        v-if="bloatedTables.length > 0"
        title="Bloated Tables"
        subtitle="dead/live ratio > 5%"
        padded
      >
        <div class="bloat-list">
          <div v-for="t in bloatedTables" :key="`${t.schemaName}.${t.tableName}`" class="bloat-row">
            <div class="bloat-meta">
              <span class="mono">{{ t.schemaName }}.{{ t.tableName }}</span>
              <span class="muted small">{{ formatBytes(t.totalSize) }}</span>
            </div>
            <div class="bloat-stats">
              <span class="muted small">
                {{ formatNumber(t.nLiveTup) }} live / {{ formatNumber(t.nDeadTup) }} dead · last vacuum
                {{ formatTimestamp(t.lastVacuum || t.lastAutovacuum) }}
              </span>
              <Badge :color="bloatBadgeColor(t.bloatRatio)">{{ t.bloatRatio.toFixed(1) }}% bloat</Badge>
              <Button size="xs" @click="handleAnalyzeTable(t.schemaName, t.tableName)">Analyze</Button>
            </div>
          </div>
        </div>
      </SectionCard>

      <SectionCard
        v-if="tableIOStats.length > 0"
        title="Table I/O Statistics"
        :subtitle="`Top ${Math.min(20, tableIOStats.length)} tables`"
        padded
      >
        <div class="io-list">
          <div v-for="io in tableIOStats.slice(0, 20)" :key="`${io.schemaName}.${io.tableName}`" class="io-row">
            <div class="io-meta">
              <span class="mono">{{ io.schemaName }}.{{ io.tableName }}</span>
              <Badge :color="cacheHitColor(io.cacheHitRatio)">
                {{ io.cacheHitRatio.toFixed(1) }}% cache hits
              </Badge>
            </div>
            <span class="muted small">
              Heap: {{ formatNumber(io.heapBlksHit) }} hits / {{ formatNumber(io.heapBlksRead) }} reads ·
              Index: {{ formatNumber(io.idxBlksHit) }} hits / {{ formatNumber(io.idxBlksRead) }} reads
            </span>
          </div>
        </div>
      </SectionCard>
    </template>

    <!-- ── Indexes Tab ───────────────────────────────────────────── -->
    <template v-if="activeTab === 'Indexes'">
      <SectionCard title="Indexes" padded>
        <div class="filter-bar">
          <TextInput v-model="indexSchemaFilter" placeholder="Filter by schema…" size="sm" />
          <Button size="sm" icon="pulse" @click="loadIndexStats()">Load</Button>
        </div>

        <Tabs v-model="indexSubTabValue" :tabs="INDEX_SUB_TABS" :accent="accent" />

        <div v-if="indexSubTab === 'all'" class="sub-tab-content">
          <GlassTable
            :columns="indexStatsColumns"
            :rows="indexStats"
            :row-key="'indexName'"
            :loading="loadingIndexes"
            loading-text="Loading index statistics…"
            empty-text="Click Load to fetch index statistics."
            :arrow="false"
          >
            <template #col-indexName="{ row }">
              <span class="mono" style="font-weight: 500; color: var(--fg-0)">{{ row.indexName }}</span>
            </template>
            <template #col-tableName="{ row }">
              <span class="mono">{{ row.tableName }}</span>
            </template>
            <template #col-schemaName="{ row }">
              <span class="mono">{{ row.schemaName }}</span>
            </template>
            <template #col-idxScan="{ row }">
              <Badge v-if="row.idxScan === 0" :color="ACCENT_ERR">0</Badge>
              <span v-else class="mono tabular">{{ formatNumber(row.idxScan) }}</span>
            </template>
            <template #col-idxTupRead="{ row }">
              <span class="mono tabular">{{ formatNumber(row.idxTupRead) }}</span>
            </template>
            <template #col-indexSize="{ row }">
              <span class="mono tabular">{{ formatBytes(row.indexSize) }}</span>
            </template>
          </GlassTable>
        </div>

        <div v-if="indexSubTab === 'unused'" class="sub-tab-content">
          <p class="muted small">
            Indexes that have never been scanned, excluding primary keys and unique indexes.
          </p>
          <GlassTable
            :columns="indexStatsColumns"
            :rows="unusedIndexes"
            :row-key="'indexName'"
            :loading="loadingIndexes"
            loading-text="Loading…"
            empty-text="No unused indexes detected."
            :arrow="false"
          >
            <template #col-indexName="{ row }">
              <span class="mono" style="font-weight: 500; color: var(--fg-0)">{{ row.indexName }}</span>
            </template>
            <template #col-tableName="{ row }">
              <span class="mono">{{ row.tableName }}</span>
            </template>
            <template #col-schemaName="{ row }">
              <span class="mono">{{ row.schemaName }}</span>
            </template>
            <template #col-idxScan="{ row }">
              <Badge :color="ACCENT_ERR">{{ formatNumber(row.idxScan) }}</Badge>
            </template>
            <template #col-idxTupRead="{ row }">
              <span class="mono tabular">{{ formatNumber(row.idxTupRead) }}</span>
            </template>
            <template #col-indexSize="{ row }">
              <span class="mono tabular">{{ formatBytes(row.indexSize) }}</span>
            </template>
          </GlassTable>
        </div>

        <div v-if="indexSubTab === 'suggestions'" class="sub-tab-content">
          <p class="muted small">
            Tables with high sequential scan counts that may benefit from indexes.
          </p>
          <div v-if="loadingIndexes" class="empty-state">Loading…</div>
          <div v-else-if="indexSuggestions.length === 0" class="empty-state">
            No suggestions — sequential scan counts are within healthy limits.
          </div>
          <div v-else class="suggestion-list">
            <div v-for="s in indexSuggestions" :key="`${s.schemaName}.${s.tableName}`" class="suggestion-row">
              <div class="suggestion-header">
                <span class="mono">{{ s.schemaName }}.{{ s.tableName }}</span>
                <span class="muted small">{{ formatBytes(s.tableSize) }}</span>
              </div>
              <p class="suggestion-reason">{{ s.reason }}</p>
              <div class="suggestion-stats">
                <span class="muted small">Seq scans: {{ formatNumber(s.seqScan) }}</span>
                <span class="muted small">Idx scans: {{ formatNumber(s.idxScan) }}</span>
                <span class="muted small">Rows read: {{ formatNumber(s.seqTupRead) }}</span>
              </div>
            </div>
          </div>
        </div>
      </SectionCard>
    </template>

    <!-- ── Queries Tab ───────────────────────────────────────────── -->
    <template v-if="activeTab === 'Queries'">
      <SectionCard title="Slow Queries" padded>
        <div class="filter-bar">
          <Select
            v-model="slowQueryOrderBy"
            :options="ORDER_BY_OPTIONS"
            size="sm"
            :accent="accent"
          />
          <Button size="sm" icon="pulse" @click="loadSlowQueries()">Refresh</Button>
          <span style="flex: 1" />
          <Button size="sm" @click="handleResetStatStatements()">Reset Stats</Button>
        </div>

        <div v-if="!loadingSlowQueries && slowQueries.length === 0" class="empty-state">
          No slow query data available — the pg_stat_statements extension may not be installed.
        </div>

        <GlassTable
          v-else
          :columns="slowQueryColumns"
          :rows="slowQueries"
          row-key="query"
          :loading="loadingSlowQueries"
          loading-text="Loading slow queries…"
          :arrow="false"
        >
          <template #col-query="{ row, value }">
            <button
              type="button"
              class="link-btn mono small"
              @click.stop="toggleQueryRow(slowQueries.indexOf(row))"
            >
              {{ truncateQuery(value, 60) }}
            </button>
          </template>
          <template #col-calls="{ row }">
            <span class="mono tabular">{{ formatNumber(row.calls) }}</span>
          </template>
          <template #col-totalTimeMs="{ row }">
            <span class="mono tabular">{{ formatMs(row.totalTimeMs) }}</span>
          </template>
          <template #col-meanTimeMs="{ row }">
            <span class="mono tabular">{{ formatMs(row.meanTimeMs) }}</span>
          </template>
          <template #col-maxTimeMs="{ row }">
            <Badge v-if="row.maxTimeMs > 1000" :color="ACCENT_ERR">{{ formatMs(row.maxTimeMs) }}</Badge>
            <span v-else class="mono tabular">{{ formatMs(row.maxTimeMs) }}</span>
          </template>
          <template #col-rows="{ row }">
            <span class="mono tabular">{{ formatNumber(row.rows) }}</span>
          </template>
          <template #col-hitRatio="{ row }">
            <Badge :color="hitRatioColor(row.hitRatio)">{{ row.hitRatio.toFixed(1) }}%</Badge>
          </template>
        </GlassTable>

        <div
          v-if="expandedQuery"
          class="query-detail"
        >
          <h4 class="detail-heading">Full Query</h4>
          <pre class="query-pre">{{ expandedQuery.query }}</pre>
          <div class="kv-grid quad">
            <span class="kv-label">Calls</span>
            <span class="mono tabular">{{ formatNumber(expandedQuery.calls) }}</span>
            <span class="kv-label">Total</span>
            <span class="mono tabular">{{ formatMs(expandedQuery.totalTimeMs) }}</span>
            <span class="kv-label">Mean</span>
            <span class="mono tabular">{{ formatMs(expandedQuery.meanTimeMs) }}</span>
            <span class="kv-label">Min</span>
            <span class="mono tabular">{{ formatMs(expandedQuery.minTimeMs) }}</span>
            <span class="kv-label">Max</span>
            <span class="mono tabular">{{ formatMs(expandedQuery.maxTimeMs) }}</span>
            <span class="kv-label">Stddev</span>
            <span class="mono tabular">{{ formatMs(expandedQuery.stddevTimeMs) }}</span>
            <span class="kv-label">Rows</span>
            <span class="mono tabular">{{ formatNumber(expandedQuery.rows) }}</span>
            <span class="kv-label">Hit ratio</span>
            <span class="mono tabular">{{ expandedQuery.hitRatio.toFixed(1) }}%</span>
            <span class="kv-label">Shared hits</span>
            <span class="mono tabular">{{ formatNumber(expandedQuery.sharedBlksHit) }}</span>
            <span class="kv-label">Shared reads</span>
            <span class="mono tabular">{{ formatNumber(expandedQuery.sharedBlksRead) }}</span>
          </div>
        </div>
      </SectionCard>
    </template>

    <!-- ── Locks Tab ─────────────────────────────────────────────── -->
    <template v-if="activeTab === 'Locks'">
      <SectionCard :title="`Locks (${locks.length})`">
        <GlassTable
          :columns="lockColumns"
          :rows="locks"
          row-key="pid"
          empty-text="No active locks."
          :arrow="false"
        >
          <template #col-pid="{ row }">
            <span class="mono tabular">{{ row.pid }}</span>
          </template>
          <template #col-lockType="{ row }">
            <span class="mono">{{ row.lockType }}</span>
          </template>
          <template #col-relationName="{ row }">
            <span class="mono">{{ row.relationName || '—' }}</span>
          </template>
          <template #col-mode="{ row }">
            <span class="mono">{{ row.mode }}</span>
          </template>
          <template #col-granted="{ row }">
            <Badge :color="row.granted ? ACCENT_OK : ACCENT_ERR">
              {{ row.granted ? 'Yes' : 'Waiting' }}
            </Badge>
          </template>
          <template #col-state="{ row }">
            <span class="mono small">{{ row.state || '—' }}</span>
          </template>
          <template #col-durationSeconds="{ row }">
            <span v-if="row.durationSeconds != null" class="mono tabular">
              {{ row.durationSeconds.toFixed(1) }}s
            </span>
            <span v-else class="muted">—</span>
          </template>
        </GlassTable>
      </SectionCard>
    </template>

    <!-- ── Settings Tab ──────────────────────────────────────────── -->
    <template v-if="activeTab === 'Settings'">
      <SectionCard title="Server Settings" padded>
        <div class="filter-bar">
          <TextInput
            v-model="settingsFilter"
            placeholder="Filter by name or category…"
            size="sm"
            @keyup.enter="loadSettings()"
          />
          <Button size="sm" icon="pulse" @click="loadSettings()">Search</Button>
        </div>
        <GlassTable
          :columns="settingsColumns"
          :rows="settings"
          row-key="name"
          :loading="loadingSettings"
          loading-text="Loading settings…"
          empty-text="Click Search to load configuration settings."
          :arrow="false"
        >
          <template #col-name="{ row }">
            <span class="mono" style="font-weight: 500">{{ row.name }}</span>
          </template>
          <template #col-setting="{ row }">
            <span class="mono tabular">{{ row.unit ? `${row.setting} ${row.unit}` : row.setting }}</span>
          </template>
          <template #col-category="{ row }">
            <span class="small">{{ row.category }}</span>
          </template>
          <template #col-context="{ row }">
            <span class="mono small">{{ row.context }}</span>
          </template>
          <template #col-source="{ row }">
            <span class="mono small">{{ row.source }}</span>
          </template>
          <template #col-pendingRestart="{ row }">
            <Badge v-if="row.pendingRestart" :color="ACCENT_WARN">Restart</Badge>
            <span v-else class="muted">—</span>
          </template>
        </GlassTable>
      </SectionCard>
    </template>

    <!-- ── Table-detail drawer ──────────────────────────────────── -->
    <Drawer
      v-if="drawerOpen && drawerTable"
      :title="`${drawerTable.schemaName}.${drawerTable.tableName}`"
      icon="database"
      :accent="accent"
      width="520px"
      @close="drawerOpen = false"
    >
      <h4 class="detail-heading">Size</h4>
      <div class="kv-grid">
        <span class="kv-label">Total</span>
        <span class="mono tabular">{{ formatBytes(drawerTable.totalSize) }}</span>
        <span class="kv-label">Table</span>
        <span class="mono tabular">{{ formatBytes(drawerTable.tableSize) }}</span>
        <span class="kv-label">Indexes</span>
        <span class="mono tabular">{{ formatBytes(drawerTable.indexSize) }}</span>
      </div>

      <h4 class="detail-heading">Rows</h4>
      <div class="kv-grid">
        <span class="kv-label">Live</span>
        <span class="mono tabular">{{ formatNumber(drawerTable.nLiveTup) }}</span>
        <span class="kv-label">Dead</span>
        <span class="mono tabular">{{ formatNumber(drawerTable.nDeadTup) }}</span>
        <span class="kv-label">Bloat</span>
        <span><Badge :color="bloatBadgeColor(drawerTable.bloatRatio)">{{ drawerTable.bloatRatio.toFixed(1) }}%</Badge></span>
      </div>

      <h4 class="detail-heading">Scans</h4>
      <div class="kv-grid">
        <span class="kv-label">Seq scans</span>
        <span class="mono tabular">{{ formatNumber(drawerTable.seqScan) }}</span>
        <span class="kv-label">Seq rows read</span>
        <span class="mono tabular">{{ formatNumber(drawerTable.seqTupRead) }}</span>
        <span class="kv-label">Index scans</span>
        <span class="mono tabular">{{ formatNumber(drawerTable.idxScan) }}</span>
        <span class="kv-label">Index rows fetched</span>
        <span class="mono tabular">{{ formatNumber(drawerTable.idxTupFetch) }}</span>
      </div>

      <h4 class="detail-heading">Modifications</h4>
      <div class="kv-grid">
        <span class="kv-label">Inserted</span>
        <span class="mono tabular">{{ formatNumber(drawerTable.nTupIns) }}</span>
        <span class="kv-label">Updated</span>
        <span class="mono tabular">{{ formatNumber(drawerTable.nTupUpd) }}</span>
        <span class="kv-label">Deleted</span>
        <span class="mono tabular">{{ formatNumber(drawerTable.nTupDel) }}</span>
        <span class="kv-label">HOT updated</span>
        <span class="mono tabular">{{ formatNumber(drawerTable.nTupHotUpd) }}</span>
      </div>

      <h4 class="detail-heading">Maintenance</h4>
      <div class="kv-grid">
        <span class="kv-label">Last vacuum</span>
        <span class="mono small">{{ formatTimestamp(drawerTable.lastVacuum) }}</span>
        <span class="kv-label">Last autovacuum</span>
        <span class="mono small">{{ formatTimestamp(drawerTable.lastAutovacuum) }}</span>
        <span class="kv-label">Last analyze</span>
        <span class="mono small">{{ formatTimestamp(drawerTable.lastAnalyze) }}</span>
        <span class="kv-label">Last autoanalyze</span>
        <span class="mono small">{{ formatTimestamp(drawerTable.lastAutoanalyze) }}</span>
        <span class="kv-label">Vacuum count</span>
        <span class="mono tabular">{{ drawerTable.vacuumCount }}</span>
        <span class="kv-label">Autovacuum count</span>
        <span class="mono tabular">{{ drawerTable.autovacuumCount }}</span>
        <span class="kv-label">Analyze count</span>
        <span class="mono tabular">{{ drawerTable.analyzeCount }}</span>
        <span class="kv-label">Autoanalyze count</span>
        <span class="mono tabular">{{ drawerTable.autoanalyzeCount }}</span>
      </div>

      <template #footer>
        <Button @click="drawerOpen = false">Close</Button>
        <Button
          primary
          :accent="accent"
          @click="handleAnalyzeTable(drawerTable.schemaName, drawerTable.tableName)"
        >Run ANALYZE</Button>
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

.stat-grid.sm {
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  margin-bottom: 12px;
}

.io-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  padding: 12px 16px;
}

.pool-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
  padding: 12px 16px;
}

.pool-card {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pool-card.compact { padding: 10px; gap: 6px; }

.pool-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.pool-name {
  font-weight: 500;
  font-size: 13px;
  color: var(--fg-0);
}

.pool-name.small { font-size: 12px; }

.row-badges {
  display: inline-flex;
  gap: 4px;
}

.kv-grid {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 4px 12px;
  font-size: 12.5px;
  align-items: baseline;
}

.kv-grid.tiny { font-size: 11.5px; gap: 3px 10px; }

.kv-grid.quad {
  grid-template-columns: max-content 1fr max-content 1fr;
  gap: 6px 16px;
  margin-top: 12px;
}

.kv-label {
  color: var(--fg-3);
  font-size: 11.5px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.subsection-label {
  font-size: 11px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-weight: 600;
  margin-top: 4px;
}

.section-title-inline {
  font-size: 13px;
  font-weight: 600;
  flex: 1;
}

.vacuum-list { display: flex; flex-direction: column; gap: 10px; }

.vacuum-card {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.vacuum-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.vacuum-target { display: flex; align-items: center; gap: 8px; }

.bloat-list,
.io-list,
.suggestion-list { display: flex; flex-direction: column; gap: 8px; }

.bloat-row,
.io-row,
.suggestion-row {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.bloat-meta,
.io-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.bloat-stats {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.suggestion-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.suggestion-reason {
  margin: 0;
  font-size: 12.5px;
  color: var(--fg-1);
}

.suggestion-stats {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.sub-tab-content { margin-top: 14px; display: flex; flex-direction: column; gap: 10px; }

.row-actions {
  display: inline-flex;
  gap: 4px;
  justify-content: flex-end;
}

.action-btn {
  background: none;
  border: none;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 11.5px;
  cursor: pointer;
  font-weight: 500;
  transition: background 0.15s;
}

.action-btn.warn { color: var(--warn); }
.action-btn.warn:hover { background: color-mix(in oklch, var(--warn) 10%, transparent); }

.action-btn.err { color: var(--err); }
.action-btn.err:hover { background: color-mix(in oklch, var(--err) 10%, transparent); }

.link-btn {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--fg-1);
  padding: 0;
  text-align: left;
}

.link-btn:hover { color: v-bind(accent); }

.query-detail {
  margin-top: 14px;
  padding: 14px 16px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.detail-heading {
  margin: 12px 0 6px;
  font-size: 11.5px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-weight: 600;
  color: var(--fg-3);
}

.detail-heading:first-child { margin-top: 0; }

.query-pre {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  overflow-x: auto;
  white-space: pre-wrap;
  color: var(--fg-0);
  margin: 0;
}

.empty-state {
  padding: 24px 16px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.muted { color: var(--fg-3); }
.small { font-size: 11.5px; }

.mono.tabular.warn { color: var(--warn); }
.mono.tabular.err { color: var(--err); font-weight: 500; }
</style>
