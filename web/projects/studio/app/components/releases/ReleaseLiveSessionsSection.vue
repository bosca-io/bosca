<script setup lang="ts">
import gql from 'graphql-tag'
import type { ReleaseLiveSource } from '~/composables/useReleaseLiveSessions'

/**
 * Live sessions map for a release. Plots where real sessions are right now
 * across ALL of the release's analytics applications at once, each project colored its own color, so
 * operators watch the rollout land geographically. Apps are resolved from each project's linked analytics
 * application(s) (`project.analyticsApplications`). The version toggle defaults to just this release's
 * version (watch the new build arrive) and widens to all versions. Compact in the rail; expands to a modal.
 */
interface ProjectRef { key: string; name: string }
interface ReleaseComponent {
  projectId: string
  version: { name: string } | null
  project: ProjectRef | null
}

const props = defineProps<{ components: ReleaseComponent[] }>()

const { accent } = useCurrentSubsystem()
const { query } = useGraphQL()

// Resolve each project's linked analytics application(s) directly by projectId — a real WorkOps↔analytics
// link, so nothing is guessed. A project can carry several apps; a release, several projects.
interface AppLink { projectKey: string; version: string | null; appId: string }
const appLinks = ref<AppLink[]>([])
// idle → loading → ready | error. `ready && !appLinks.length` is the ONLY state that means "genuinely
// no application linked"; keeping it distinct from loading/error stops the empty message from lying.
const loadState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const loadError = ref<string | null>(null)

async function loadApps() {
  if (!props.components.length) { appLinks.value = []; loadState.value = 'ready'; return }
  loadState.value = 'loading'
  loadError.value = null
  try {
    // A project owns analytics APPLICATIONS (sessions.<appId>) — the live map keys off these. Fail loud:
    // any query error must surface, not silently collapse into a misleading "nothing linked" message.
    const results = await Promise.all(props.components.map(async (c) => {
      const res = await query<{ workOps: { projects: { project: { analyticsApplications: { applicationId: string }[] } | null } } }>(gql`
        query ReleaseProjectAnalyticsApps($id: UUID!) {
          workOps { projects { project(id: $id) { analyticsApplications { applicationId } } } }
        }
      `, { id: c.projectId })
      const apps = res?.workOps?.projects?.project?.analyticsApplications ?? []
      return apps.map(a => ({ projectKey: c.project?.key ?? '—', version: c.version?.name ?? null, appId: a.applicationId }))
    }))
    appLinks.value = results.flat()
    loadState.value = 'ready'
  }
  catch (e) {
    console.error('[ReleaseLiveSessions] failed to resolve analytics applications', e)
    loadError.value = e instanceof Error ? e.message : 'Failed to load analytics applications'
    loadState.value = 'error'
  }
}
watch(() => props.components.map(c => c.projectId).join(','), () => { if (import.meta.client) loadApps() }, { immediate: true })

// Toggle: this release's version only (default) vs. every version.
const thisReleaseOnly = ref(true)
const sources = computed<ReleaseLiveSource[]>(() =>
  appLinks.value.map(l => ({ appId: l.appId, appVersion: thisReleaseOnly.value ? l.version : null, project: l.projectKey })),
)

const { rows, sessionCount, status } = useReleaseLiveSessions(sources)

// Generic point map colored by the release project (each project its own color).
const vizConfig = { latitudeColumn: 'lat', longitudeColumn: 'lon', idColumn: 'sessionId', seriesColumn: 'project' }
const expanded = ref(false)

const statusLabel = computed(() => {
  switch (status.value) {
    case 'open': return 'Live'
    case 'connecting': return 'Connecting…'
    case 'error': return 'Error'
    case 'closed': return 'Reconnecting…'
    default: return 'Idle'
  }
})
</script>

<template>
  <div class="rail-card">
    <div class="ls-head">
      <p class="rail-card-title">Live Sessions</p>
      <button
        v-if="appLinks.length"
        class="ls-expand"
        title="Expand"
        @click="expanded = true">
        <Icon name="maximize" :size="13" color="var(--fg-3)" />
      </button>
    </div>

    <div v-if="loadState === 'idle' || loadState === 'loading'" class="ls-empty">
      Loading…
    </div>

    <div v-else-if="loadState === 'error'" class="ls-empty ls-error">
      Couldn't load analytics applications: {{ loadError }}
    </div>

    <div v-else-if="!appLinks.length" class="ls-empty">
      No analytics <strong>application</strong> is linked to this release's projects. Add one under the
      project's <em>Analytics Applications</em> (a <code>sessions.&lt;appId&gt;</code> identifier — not an
      Analytics Service) to watch its live sessions here.
    </div>

    <template v-else>
      <div class="ls-meta">
        <label class="ls-toggle">
          <Switch v-model="thisReleaseOnly" :accent="accent" />
          <span>{{ thisReleaseOnly ? 'This release' : 'All versions' }}</span>
        </label>
        <span class="ls-count" :data-status="status"><span class="ls-dot" />{{ sessionCount }}</span>
      </div>
      <div class="ls-map">
        <ClientOnly>
          <AnalyticsVisualization
            name="Live Sessions"
            type="GEO_POINT_MAP"
            :configuration="vizConfig"
            :data="rows"
            :height="180"
            frameless />
        </ClientOnly>
      </div>
    </template>

    <Modal
      v-if="expanded"
      title="Live Sessions"
      icon="globe"
      :accent="accent"
      width="900px"
      @close="expanded = false">
      <div class="ls-modal">
        <div class="ls-meta ls-meta-modal">
          <label class="ls-toggle">
            <Switch v-model="thisReleaseOnly" :accent="accent" />
            <span>{{ thisReleaseOnly ? 'This release only' : 'All versions' }}</span>
          </label>
          <span class="ls-count" :data-status="status"><span class="ls-dot" />{{ statusLabel }} · {{ sessionCount }} active</span>
        </div>
        <ClientOnly>
          <AnalyticsVisualization
            name="Live Sessions"
            type="GEO_POINT_MAP"
            :configuration="vizConfig"
            :data="rows"
            :height="520"
            frameless />
        </ClientOnly>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.rail-card { padding: 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--bg-1); }
.ls-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.rail-card-title { margin: 0; font-size: 11px; text-transform: uppercase; letter-spacing: 0.08em; font-weight: 600; color: var(--fg-3); }
.ls-expand {
  background: none; border: 1px solid var(--line); border-radius: 6px; cursor: pointer;
  width: 26px; height: 26px; display: flex; align-items: center; justify-content: center;
  transition: background 0.15s, border-color 0.15s;
}
.ls-expand:hover { background: var(--bg-2); border-color: var(--fg-3); }
.ls-empty { font-size: 12px; color: var(--fg-3); line-height: 1.5; }
.ls-empty code { font-family: var(--font-mono, monospace); font-size: 11px; color: var(--fg-2); }
.ls-error { color: var(--err, #f87171); }
.ls-meta { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.ls-meta-modal { margin-bottom: 14px; }
.ls-toggle { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: var(--fg-2); cursor: pointer; }
.ls-count { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--fg-2); font-variant-numeric: tabular-nums; }
.ls-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--fg-3); flex: 0 0 auto; }
.ls-count[data-status='open'] .ls-dot {
  background: var(--ok, #34d399);
  box-shadow: 0 0 0 3px color-mix(in oklch, var(--ok, #34d399) 25%, transparent);
}
.ls-count[data-status='error'] .ls-dot { background: var(--err, #f87171); }
.ls-count[data-status='connecting'] .ls-dot,
.ls-count[data-status='closed'] .ls-dot { background: #ffb547; }
.ls-map { min-height: 180px; }
.ls-modal { min-width: 0; }
</style>
