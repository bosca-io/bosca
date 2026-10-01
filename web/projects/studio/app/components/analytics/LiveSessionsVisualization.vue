<script setup lang="ts">
/**
 * Self-contained LIVE_SESSIONS_MAP visualization. Given a saved visualization's `configuration`
 * (which optionally carries an `appId` — empty streams every application — and an `appVersion`
 * filter), it opens the `liveSessions` subscription itself via {@link useLiveSessions} and renders
 * the streaming point map — so a LIVE_SESSIONS_MAP viz can be dropped onto any dashboard and simply
 * streams, with no query behind it.
 *
 * The subscription lives HERE (not in `@bosca/ui-analytics`) because it needs Studio's graphql-transport-ws
 * + auth layer; the package's `AnalyticsVisualization` stays a pure renderer fed the current session rows.
 */
const props = withDefaults(defineProps<{
  name?: string
  configuration?: Record<string, unknown> | null
  height?: number
  frameless?: boolean
  showStatus?: boolean
}>(), {
  name: 'Live Sessions',
  configuration: null,
  height: undefined,
  frameless: false,
  showStatus: true,
})

const appId = computed(() => (props.configuration?.appId as string) || '')
const appVersion = computed(() => (props.configuration?.appVersion as string) || '')

const { rows, sessionCount, status } = useLiveSessions(
  () => appId.value || null,
  () => appVersion.value || null,
)

const statusLabel = computed(() => {
  switch (status.value) {
    case 'open': return 'Live'
    case 'connecting': return 'Connecting…'
    case 'error': return 'Error'
    case 'closed': return 'Reconnecting…'
    default: return 'Idle'
  }
})

interface LiveSessionPoint {
  id?: unknown
  sessionId?: unknown
  appId?: unknown
  appVersion?: unknown
}

function pointValue(value: unknown): string {
  return value == null || value === '' ? 'Unknown' : String(value)
}

function sessionId(point: LiveSessionPoint): string {
  return pointValue(point.sessionId ?? point.id)
}

function sessionRoute(point: LiveSessionPoint) {
  const query: Record<string, string> = { sessionId: sessionId(point) }
  if (point.appId != null && point.appId !== '') query.appId = String(point.appId)
  return { path: '/analytics/events', query }
}

function viewSession(point: LiveSessionPoint) {
  if (sessionId(point) === 'Unknown') return
  navigateTo(sessionRoute(point))
}
</script>

<template>
  <div class="live-viz">
    <div v-if="showStatus" class="live-viz-status" :data-status="status">
      <span class="live-viz-dot" />{{ statusLabel }} · {{ sessionCount }} active<template v-if="!appId"> · All applications</template>
    </div>
    <div class="live-viz-map">
      <ClientOnly>
        <AnalyticsVisualization
          :name="name"
          type="LIVE_SESSIONS_MAP"
          :configuration="configuration ?? {}"
          :data="rows"
          :height="height"
          :frameless="frameless"
          @point-click="viewSession">
          <template #pointTooltip="{ point }">
            <div v-if="point" class="session-tooltip">
              <div class="session-tooltip-heading">
                <span class="session-tooltip-dot" />
                Active session
              </div>
              <dl class="session-tooltip-details">
                <template v-if="point.appId">
                  <dt>Application</dt>
                  <dd>{{ pointValue(point.appId) }}</dd>
                </template>
                <dt>Version</dt>
                <dd>{{ pointValue(point.appVersion) }}</dd>
                <dt>Session</dt>
                <dd class="session-tooltip-id">{{ sessionId(point) }}</dd>
              </dl>
              <div class="session-tooltip-action">Click dot to view session <span aria-hidden="true">→</span></div>
            </div>
          </template>
        </AnalyticsVisualization>
      </ClientOnly>
    </div>
  </div>
</template>

<style scoped>
.live-viz { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.live-viz-status {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 11px; color: var(--fg-2); font-variant-numeric: tabular-nums;
  /* 14px inline aligns the status with the map body (AnalyticsVisualization pads .viz-body 14px),
     so it doesn't hug the tile edge on dashboards. */
  margin: 2px 14px 6px;
}
.live-viz-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--fg-3); flex: 0 0 auto; }
.live-viz-status[data-status='open'] .live-viz-dot {
  background: var(--ok, #34d399);
  box-shadow: 0 0 0 3px color-mix(in oklch, var(--ok, #34d399) 25%, transparent);
}
.live-viz-status[data-status='error'] .live-viz-dot { background: var(--err, #f87171); }
.live-viz-status[data-status='connecting'] .live-viz-dot,
.live-viz-status[data-status='closed'] .live-viz-dot { background: #ffb547; }
.live-viz-map { flex: 1; min-height: 0; }
.session-tooltip { min-width: 220px; color: var(--fg-1, #ccc); }
.session-tooltip-heading {
  display: flex; align-items: center; gap: 7px; margin-bottom: 9px;
  font-size: 12px; font-weight: 650; color: var(--fg-0, #fff);
}
.session-tooltip-dot {
  width: 8px; height: 8px; border-radius: 50%; flex: 0 0 auto;
  background: var(--brand-2, #5ec5ff);
}
.session-tooltip-details {
  display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 5px 12px;
  margin: 0; font-size: 11.5px;
}
.session-tooltip-details dt { color: var(--fg-3, #6c7388); }
.session-tooltip-details dd { margin: 0; color: var(--fg-1, #ccc); text-align: right; }
.session-tooltip-id {
  max-width: 170px; overflow: hidden; text-overflow: ellipsis;
  font-family: var(--font-mono, monospace); white-space: nowrap;
}
.session-tooltip-action {
  margin-top: 10px; padding-top: 8px; border-top: 1px solid var(--line, #2a2d35);
  font-size: 11px; font-weight: 600; color: var(--brand-2, #5ec5ff); text-align: right;
}
</style>
