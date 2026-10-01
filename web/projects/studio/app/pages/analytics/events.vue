<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery } = useGraphQL()
const route = useRoute()

function queryFilter(value: unknown): string {
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.find((entry): entry is string => typeof entry === 'string') ?? ''
  return ''
}

const eventTypes = ['All', 'Session', 'Interaction', 'Impression', 'Completion', 'Installation', 'Error']
const eventTypeFilter = ref('All')
const appIdFilter = ref(queryFilter(route.query.appId))
const sessionIdFilter = ref(queryFilter(route.query.sessionId))
const userIdFilter = ref('')

const page = ref(1)
const pageSize = 50

const selectedEvent = ref<Record<string, unknown> | null>(null)

const startDate = ref(defaultStart())
const endDate = ref(defaultEnd())

function defaultStart() {
  const d = new Date()
  d.setDate(d.getDate() - 7)
  return toLocalDatetimeString(d)
}

function defaultEnd() {
  return toLocalDatetimeString(new Date())
}

function toLocalDatetimeString(d: Date) {
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function parseJson(value: unknown): Record<string, unknown> | null {
  if (value == null) return null
  if (typeof value === 'object') return value as Record<string, unknown>
  try {
    const parsed = JSON.parse(String(value))
    return typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}

function getContext(event: Record<string, unknown>) {
  return parseJson(event.context) || {}
}

function getElement(event: Record<string, unknown>) {
  return parseJson(event.element)
}

function getError(event: Record<string, unknown>) {
  return parseJson(event.error)
}

const executeGql = gql`
  query ExecuteRawEvents($key: String!, $parameters: [AnalyticsQueryExecutionParameterInput!]!) {
    analytics {
      queries {
        executeByKey(key: $key, parameters: $parameters) {
          records
        }
      }
    }
  }
`

const key = ref('raw-events')
const parameters = computed(() => [
  { parameter: 'start_date', value: { now: false, value: new Date(startDate.value).toISOString() } },
  { parameter: 'end_date', value: { now: false, value: new Date(endDate.value).toISOString() } },
  { parameter: 'offset', value: (page.value - 1) * pageSize },
  { parameter: 'page_size', value: pageSize },
])

const { data, status, refresh } = useAsyncQuery<{
  analytics: { queries: { executeByKey: { records: Record<string, unknown>[] } } }
}>('raw-events', executeGql, { key, parameters })

const allEvents = computed(() => data.value?.analytics?.queries?.executeByKey?.records ?? [])
const hasMore = computed(() => allEvents.value.length >= pageSize)

const filteredEvents = computed(() => {
  let events = allEvents.value
  if (eventTypeFilter.value !== 'All') {
    events = events.filter(e => e.type === eventTypeFilter.value)
  }
  if (appIdFilter.value) {
    const term = appIdFilter.value.toLowerCase()
    events = events.filter(e => String(getContext(e).app_id || '').toLowerCase().includes(term))
  }
  if (sessionIdFilter.value) {
    const term = sessionIdFilter.value.toLowerCase()
    events = events.filter(e => String(getContext(e).session_id || '').toLowerCase().includes(term))
  }
  if (userIdFilter.value) {
    const term = userIdFilter.value.toLowerCase()
    events = events.filter(e => String(getContext(e).user_id || '').toLowerCase().includes(term))
  }
  return events
})

watch([startDate, endDate], () => { page.value = 1 })
watch(
  () => [route.query.appId, route.query.sessionId],
  ([appId, sessionId]) => {
    appIdFilter.value = queryFilter(appId)
    sessionIdFilter.value = queryFilter(sessionId)
    page.value = 1
  },
)

const TYPE_COLORS: Record<string, string> = {
  Session: '#3b82f6',
  Interaction: '#5ec5ff',
  Impression: '#34d99a',
  Completion: '#ffb547',
  Installation: '#6c7388',
  Error: '#ff5d6c',
}

const dateFormatter = new Intl.DateTimeFormat('en-US', { dateStyle: 'short', timeStyle: 'medium' })

function formatDate(value: unknown): string {
  if (value == null) return '--'
  const date = typeof value === 'number' ? new Date(value) : new Date(String(value))
  if (Number.isNaN(date.getTime())) return String(value)
  return dateFormatter.format(date)
}

function truncateId(id: unknown): string {
  if (!id) return '--'
  const s = String(id)
  return s.length > 12 ? s.slice(0, 12) + '…' : s
}

const columns: GlassTableColumn[] = [
  { key: 'type', label: 'Type', width: '110px' },
  { key: 'created', label: 'Created', width: '150px' },
  { key: 'app', label: 'App', width: '100px' },
  { key: 'session', label: 'Session', width: '120px', muted: true },
  { key: 'user', label: 'User', width: '120px', muted: true },
  { key: 'element', label: 'Element', width: '1fr', muted: true },
  { key: 'error', label: 'Error', width: '1fr', muted: true },
]

// Detail view helpers
function entries(obj: Record<string, unknown> | null): [string, unknown][] {
  if (!obj) return []
  return Object.entries(obj).filter(([, v]) => v != null && v !== '')
}

function detailContext(event: Record<string, unknown>) { return parseJson(event.context) }
function detailElement(event: Record<string, unknown>) { return parseJson(event.element) }
function detailError(event: Record<string, unknown>) { return parseJson(event.error) }
function detailDevice(ctx: Record<string, unknown> | null) {
  if (!ctx) return null
  return parseJson(ctx.device as unknown) ?? ctx.device as Record<string, unknown> | null
}
function detailBrowser(ctx: Record<string, unknown> | null) {
  if (!ctx) return null
  return parseJson(ctx.browser as unknown) ?? ctx.browser as Record<string, unknown> | null
}
function detailGeo(ctx: Record<string, unknown> | null) {
  if (!ctx) return null
  return parseJson(ctx.geo as unknown) ?? ctx.geo as Record<string, unknown> | null
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Analytics', 'Raw Events')"
        title="Raw Events"
        :subtitle="`${filteredEvents.length} events`"
      >
        <template #actions>
          <Button
            size="sm"
            icon="pulse"
            :disabled="status === 'pending'"
            @click="refresh()">Refresh</Button>
        </template>
      </PageHeader>
    </template>

    <div class="filter-bar">
      <DateInput v-model="startDate" label="Start" type="datetime-local" />
      <DateInput v-model="endDate" label="End" type="datetime-local" />
      <Select
        v-model="eventTypeFilter"
        :options="eventTypes.map(t => ({ value: t, label: t }))"
        label="Type"
        size="sm" />
      <TextInput
        v-model="appIdFilter"
        label="App ID"
        placeholder="Filter…"
        size="sm" />
      <TextInput
        v-model="sessionIdFilter"
        label="Session"
        placeholder="Filter…"
        size="sm" />
      <TextInput
        v-model="userIdFilter"
        label="User"
        placeholder="Filter…"
        size="sm" />
    </div>

    <SectionCard title="Events">
      <GlassTable
        :columns="columns"
        :rows="filteredEvents"
        :loading="status === 'pending' && filteredEvents.length === 0"
        empty-text="No events found."
        arrow
        @row-click="(r: any) => selectedEvent = r"
      >
        <template #col-type="{ row }"><Badge :color="TYPE_COLORS[row.type as string] ?? '#6c7388'">{{ row.type }}</Badge></template>
        <template #col-created="{ row }"><span class="mono" style="font-size: 12px">{{ formatDate(row.created) }}</span></template>
        <template #col-app="{ row }">{{ getContext(row).app_id || '--' }}</template>
        <template #col-session="{ row }"><span class="mono" style="font-size: 11.5px">{{ truncateId(getContext(row).session_id) }}</span></template>
        <template #col-user="{ row }"><span class="mono" style="font-size: 11.5px">{{ truncateId(getContext(row).user_id) }}</span></template>
        <template #col-element="{ row }">
          <template v-if="getElement(row)">{{ getElement(row)!.type }}: {{ getElement(row)!.id }}</template>
          <template v-else>--</template>
        </template>
        <template #col-error="{ row }">{{ getError(row)?.message || '--' }}</template>
      </GlassTable>
    </SectionCard>

    <div class="pagination">
      <Button size="sm" :disabled="page <= 1" @click="page--">Previous</Button>
      <span class="page-info">Page {{ page }}</span>
      <Button size="sm" :disabled="!hasMore" @click="page++">Next</Button>
    </div>

    <!-- Event Detail Modal -->
    <Modal
      v-if="selectedEvent"
      title="Event Details"
      icon="inspect"
      :accent="accent"
      width="700px"
      @close="selectedEvent = null"
    >
      <div class="detail-sections">
        <!-- Event core -->
        <div class="detail-section">
          <div class="detail-heading">Event</div>
          <div class="detail-grid">
            <span class="detail-label">ID</span>
            <span class="detail-value mono">{{ selectedEvent.id }}</span>

            <span class="detail-label">Type</span>
            <span class="detail-value"><Badge :color="TYPE_COLORS[selectedEvent.type as string] ?? '#6c7388'">{{ selectedEvent.type }}</Badge></span>

            <template v-if="selectedEvent.client_id">
              <span class="detail-label">Client ID</span>
              <span class="detail-value">{{ selectedEvent.client_id }}</span>
            </template>

            <span class="detail-label">Created</span>
            <span class="detail-value">{{ formatDate(selectedEvent.created) }}</span>

            <template v-if="selectedEvent.sent">
              <span class="detail-label">Sent</span>
              <span class="detail-value">{{ formatDate(selectedEvent.sent) }}</span>
            </template>

            <template v-if="selectedEvent.received">
              <span class="detail-label">Received</span>
              <span class="detail-value">{{ formatDate(selectedEvent.received) }}</span>
            </template>
          </div>
        </div>

        <!-- Context -->
        <div v-if="detailContext(selectedEvent)" class="detail-section">
          <div class="detail-heading">Context</div>
          <div class="detail-grid">
            <template v-if="detailContext(selectedEvent)!.app_id">
              <span class="detail-label">App</span>
              <span class="detail-value">
                {{ detailContext(selectedEvent)!.app_id }}
                <span v-if="detailContext(selectedEvent)!.app_version" class="muted"> v{{ detailContext(selectedEvent)!.app_version }}</span>
              </span>
            </template>
            <template v-if="detailContext(selectedEvent)!.session_id">
              <span class="detail-label">Session</span>
              <span class="detail-value mono">{{ detailContext(selectedEvent)!.session_id }}</span>
            </template>
            <template v-if="detailContext(selectedEvent)!.user_id">
              <span class="detail-label">User</span>
              <span class="detail-value mono">{{ detailContext(selectedEvent)!.user_id }}</span>
            </template>
          </div>
        </div>

        <!-- Device -->
        <div v-if="detailDevice(detailContext(selectedEvent)) && entries(detailDevice(detailContext(selectedEvent))).length" class="detail-section">
          <div class="detail-heading">Device</div>
          <div class="detail-grid">
            <template v-for="[k, v] in entries(detailDevice(detailContext(selectedEvent)))" :key="k">
              <span class="detail-label">{{ k }}</span>
              <span class="detail-value">{{ v }}</span>
            </template>
          </div>
        </div>

        <!-- Browser -->
        <div v-if="detailBrowser(detailContext(selectedEvent)) && entries(detailBrowser(detailContext(selectedEvent))).length" class="detail-section">
          <div class="detail-heading">Browser</div>
          <div class="detail-grid">
            <template v-for="[k, v] in entries(detailBrowser(detailContext(selectedEvent)))" :key="k">
              <span class="detail-label">{{ k }}</span>
              <span class="detail-value">{{ v }}</span>
            </template>
          </div>
        </div>

        <!-- Geo -->
        <div v-if="detailGeo(detailContext(selectedEvent)) && entries(detailGeo(detailContext(selectedEvent))).length" class="detail-section">
          <div class="detail-heading">Location</div>
          <div class="detail-grid">
            <template v-for="[k, v] in entries(detailGeo(detailContext(selectedEvent)))" :key="k">
              <span class="detail-label">{{ k }}</span>
              <span class="detail-value">{{ v }}</span>
            </template>
          </div>
        </div>

        <!-- Element -->
        <div v-if="detailElement(selectedEvent)" class="detail-section">
          <div class="detail-heading">Element</div>
          <div class="detail-grid">
            <template v-if="detailElement(selectedEvent)!.id">
              <span class="detail-label">ID</span>
              <span class="detail-value">{{ detailElement(selectedEvent)!.id }}</span>
            </template>
            <template v-if="detailElement(selectedEvent)!.type">
              <span class="detail-label">Type</span>
              <span class="detail-value">{{ detailElement(selectedEvent)!.type }}</span>
            </template>
          </div>
        </div>

        <!-- Error -->
        <div v-if="detailError(selectedEvent)" class="detail-section">
          <div class="detail-heading">Error</div>
          <div class="detail-grid">
            <template v-if="detailError(selectedEvent)!.message">
              <span class="detail-label">Message</span>
              <span class="detail-value">{{ detailError(selectedEvent)!.message }}</span>
            </template>
            <template v-if="detailError(selectedEvent)!.type">
              <span class="detail-label">Type</span>
              <span class="detail-value">{{ detailError(selectedEvent)!.type }}</span>
            </template>
            <template v-if="detailError(selectedEvent)!.fatal != null">
              <span class="detail-label">Fatal</span>
              <span class="detail-value"><Badge :color="detailError(selectedEvent)!.fatal ? '#ff5d6c' : '#ffb547'">{{ detailError(selectedEvent)!.fatal ? 'Yes' : 'No' }}</Badge></span>
            </template>
          </div>
          <div v-if="detailError(selectedEvent)!.stack_trace" class="stack-block">
            <pre class="stack-text">{{ detailError(selectedEvent)!.stack_trace }}</pre>
          </div>
        </div>

        <!-- Raw JSON -->
        <div class="detail-section">
          <div class="detail-heading">Raw Data</div>
          <div class="raw-block">
            <pre class="raw-text">{{ JSON.stringify(selectedEvent, null, 2) }}</pre>
          </div>
        </div>
      </div>
    </Modal>
  </PageShell>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 14px;
}

.page-info {
  font-size: 12px;
  color: var(--fg-3);
}

.detail-sections {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-height: 60vh;
  overflow: auto;
}

.detail-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.detail-heading {
  font-size: 10.5px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
}

.detail-grid {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 4px 16px;
  font-size: 13px;
}

.detail-label {
  color: var(--fg-3);
}

.detail-value {
  color: var(--fg-1);
  word-break: break-all;
}

.muted {
  color: var(--fg-3);
}

.stack-block,
.raw-block {
  max-height: 250px;
  overflow: auto;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 10px 12px;
  margin-top: 4px;
}

.stack-text,
.raw-text {
  font-size: 11px;
  font-family: var(--font-mono);
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--fg-1);
  line-height: 1.5;
  margin: 0;
}
</style>
