<script setup lang="ts">
import gql from 'graphql-tag'

/**
 * The release's artifacts: every publication registered for the bundled versions —
 * the docker image, the per-environment helm-values files, store bundles — with their registry
 * namespace, environment scope, and lifecycle status. Empty until release builds have run; the
 * publications appear as Associate Artifact registers them and flip to PUBLISHED right after.
 */

const props = defineProps<{
  releaseId: string
  components: Array<{ projectId: string; versionId: string; project: { key: string; name: string } | null; version: { name: string } | null }>
  /** Whether a release run is live — publications register mid-run without any status transition, so
   *  this section polls while true instead of waiting for a parent refresh that may never come. */
  live?: boolean
}>()

const { query } = useGraphQL()

interface Publication {
  id: string; versionId: string; artifactType: string; coordinates: string
  namespace: string | null; environments: string[]; status: string
}

interface DeclaredArtifact {
  projectId: string; type: string; namespace: string; coordinate: string; environments: string[]
}

// What the release WILL publish — its projects' CI declarations, coordinates resolved to versions.
const declared = ref<DeclaredArtifact[]>([])
async function loadDeclared() {
  try {
    const res = await query<{ workOps: { crossProject: { releaseDeclaredArtifacts: DeclaredArtifact[] } } }>(gql`
      query ReleaseDeclaredArtifacts($releaseId: UUID!) {
        workOps { crossProject { releaseDeclaredArtifacts(releaseId: $releaseId) { projectId type namespace coordinate environments } } }
      }
    `, { releaseId: props.releaseId })
    declared.value = res?.workOps?.crossProject?.releaseDeclaredArtifacts ?? []
  }
  catch { declared.value = [] }
}

const publications = ref<Publication[]>([])
async function load() {
  const results = await Promise.all(props.components.map(async (c) => {
    try {
      const res = await query<{ workOps: { multiRepo: { artifacts: Publication[] } } }>(gql`
        query ReleaseArtifacts($versionId: UUID!) {
          workOps { multiRepo { artifacts(versionId: $versionId) {
            id versionId artifactType coordinates namespace environments status
          } } }
        }
      `, { versionId: c.versionId })
      return res?.workOps?.multiRepo?.artifacts ?? []
    }
    catch { return [] }
  }))
  publications.value = results.flat()
}
function reload() {
  load()
  loadDeclared()
}
// The parent drives refreshes for page-level events this section can't see itself — a rolled-back
// attempt deleting the publications, the header's Refresh button.
defineExpose({ refresh: reload })

// Reload on identity changes, not reference luck: the releaseId (navigation, including landing here
// straight from Create) and the bundled version set (projects added/removed) each drive a refetch.
watch(() => props.releaseId, () => { if (import.meta.client) reload() }, { immediate: true })
watch(() => props.components.map(c => c.versionId).sort().join(','), () => {
  if (import.meta.client) reload()
})

// Freshly-created releases can land here mid-write — one settle retry covers the creation race
// without polling forever on genuinely empty releases.
onMounted(() => {
  setTimeout(() => {
    if (!publications.value.length && !declared.value.length) reload()
  }, 2000)
})

let livePoll: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  livePoll = setInterval(() => {
    if (typeof document !== 'undefined' && document.hidden) return
    if (props.live) load()
  }, 6000)
})
onUnmounted(() => { if (livePoll) clearInterval(livePoll) })

/** Registered publications first; declared artifacts not yet registered follow as EXPECTED rows. */
const rows = computed(() => {
  const registered = publications.value.map((p) => {
    const component = props.components.find(c => c.versionId === p.versionId)
    return {
      id: p.id,
      project: component?.project?.key ?? '—',
      coordinates: p.coordinates,
      namespace: p.namespace,
      artifactType: p.artifactType,
      environments: p.environments,
      status: p.status,
    }
  })
  const registeredCoords = new Set(registered.map(r => `${r.namespace ?? ''}|${r.coordinates}`))
  const expected = declared.value
    .filter(d => !registeredCoords.has(`${d.namespace}|${d.coordinate}`))
    .map(d => ({
      id: `${d.namespace}|${d.coordinate}`,
      project: props.components.find(c => c.projectId === d.projectId)?.project?.key ?? '—',
      coordinates: d.coordinate,
      namespace: d.namespace as string | null,
      artifactType: d.type,
      environments: d.environments,
      status: 'EXPECTED',
    }))
  return [...registered, ...expected]
})

const STATUS_COLORS: Record<string, string> = {
  EXPECTED: 'var(--fg-3)',
  PUBLISHED: 'var(--ok, #34d399)',
  PENDING: '#94a3b8',
  FAILED: 'var(--err, #f87171)',
  YANKED: '#ffb547',
}
</script>

<template>
  <div class="rail-card">
    <p class="rail-card-title">Artifacts</p>
    <div v-if="rows.length" class="artifact-list">
      <div v-for="p in rows" :key="p.id" class="art-row">
        <span class="art-coord" :title="`${p.namespace ? `${p.namespace}/` : ''}${p.coordinates}`">
          {{ p.coordinates }}
        </span>
        <span class="art-envs">
          <span v-for="env in p.environments" :key="env" class="art-env">{{ env }}</span>
        </span>
        <span
          v-if="p.status === 'EXPECTED'"
          class="art-dot"
          title="Declared by CI — not built yet" />
        <span
          v-else-if="p.status !== 'PUBLISHED'"
          class="art-status"
          :style="{ color: STATUS_COLORS[p.status] }">{{ p.status }}</span>
        <Icon
          v-else
          name="check"
          :size="12"
          color="var(--ok, #34d399)" />
      </div>
      <p class="rail-hint">Declared by the release's CI pipelines; statuses update as builds publish.</p>
    </div>
    <div v-else class="rail-empty">
      Nothing declared — attach repositories whose CI pipelines declare artifacts, and they show here before any build runs.
    </div>
  </div>
</template>

<style scoped>
.rail-card { padding: 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--bg-1); }
.rail-card-title { margin: 0 0 12px; font-size: 11px; text-transform: uppercase; letter-spacing: 0.08em; font-weight: 600; color: var(--fg-3); }
.rail-empty { font-size: 12.5px; color: var(--fg-3); line-height: 1.5; }
.rail-hint { margin: 10px 0 0; font-size: 11px; color: var(--fg-3); }
.artifact-list { display: flex; flex-direction: column; gap: 3px; }
.art-row { display: flex; align-items: center; gap: 8px; }
.art-coord {
  font-family: var(--font-mono, monospace); font-size: 11.5px; color: var(--fg-1);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; flex: 1;
}
.art-envs { display: flex; gap: 4px; flex: 0 0 auto; }
.art-env {
  font-size: 9.5px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--fg-3);
  border: 1px solid var(--line); border-radius: 4px; padding: 0 4px;
}
.art-status { font-family: var(--font-mono, monospace); font-size: 10px; font-weight: 600; letter-spacing: 0.04em; flex: 0 0 auto; }
.art-dot {
  width: 9px; height: 9px; border-radius: 50%; flex: 0 0 auto;
  border: 1.5px solid var(--fg-3); background: transparent;
}
</style>
