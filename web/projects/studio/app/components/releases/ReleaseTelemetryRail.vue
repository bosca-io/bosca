<script setup lang="ts">
import gql from 'graphql-tag'

/**
 * The release dashboard's telemetry rail. Each disjoint promotion family gets its
 * OWN card — Infrastructure (Development → Staging → Production), Google Play, App Store — titled by
 * the family's channel and holding its chain plus a one-line deploy/drift summary. Stops carry a
 * status dot for THIS release (green = in sync, amber = differs, hollow = nothing deployed);
 * selecting a stop switches the card's summary line to that environment. These cards speak
 * DEPLOYMENT only — health has its own card below, and one signal shouldn't live in two places.
 */

const props = defineProps<{ programId: string; releaseId: string }>()

const { useAsyncQuery, query } = useGraphQL()

interface Environment {
  id: string; name: string; displayOrder: number; requiresApproval: boolean
  promotionSourceIds: string[]; targetType: string
}
interface Deployment { projectId: string; status: string }
interface Drift {
  projectId: string; driftType: string
  project: { key: string } | null
  deployedVersion: { name: string } | null
  expectedVersion: { name: string } | null
}
interface EnvState { deployments: Deployment[]; drift: Drift[] }

const envGql = gql`
  query RailEnvironments($programId: UUID!) {
    workOps { multiRepo { environments(programId: $programId) {
      id name displayOrder requiresApproval promotionSourceIds targetType
    } } }
  }
`
const { data: envData } = useAsyncQuery<{ workOps: { multiRepo: { environments: Environment[] } } }>(
  'rail-environments', envGql, { programId: computed(() => props.programId || undefined) }, { server: false })
const chains = computed(() => promotionChains(envData.value?.workOps?.multiRepo?.environments ?? []))
const environments = computed(() => chains.value.flat())

/** The family's channel, from its members' target type — never from environment names. */
function chainLabel(chain: Environment[]): string {
  const targetType = chain[0]?.targetType ?? 'GENERIC'
  if (targetType === 'PLAY_TRACK') return 'Google Play'
  if (targetType === 'TESTFLIGHT' || targetType === 'APP_STORE') return 'App Store'
  return 'Infrastructure'
}

// Each card tracks its own selected stop (keyed by the chain's first environment), defaulting to the
// chain's entry environment — where a release lands first.
const selectedStops = ref<Record<string, string>>({})
function selectedIn(chain: Environment[]): string {
  const key = chain[0]!.id
  const picked = selectedStops.value[key]
  return picked && chain.some(e => e.id === picked) ? picked : key
}

// Per-environment state — every stop needs a dot, not just the selected ones. Drift is already
// channel-filtered server-side, so a Play card never counts a server project as a difference.
const stateByEnv = ref<Record<string, EnvState>>({})
async function loadStates() {
  const entries = await Promise.all(environments.value.map(async (env) => {
    try {
      const res = await query<{ workOps: { multiRepo: { environmentState: Deployment[]; environmentDrift: Drift[] } } }>(gql`
        query RailEnvState($environmentId: UUID!, $releaseId: UUID!) {
          workOps { multiRepo {
            environmentState(environmentId: $environmentId) { projectId status }
            environmentDrift(environmentId: $environmentId, releaseId: $releaseId) {
              projectId driftType
              project { key }
              deployedVersion { name }
              expectedVersion { name }
            }
          } }
        }
      `, { environmentId: env.id, releaseId: props.releaseId })
      return [env.id, {
        deployments: res?.workOps?.multiRepo?.environmentState ?? [],
        drift: res?.workOps?.multiRepo?.environmentDrift ?? [],
      }] as const
    }
    catch { return [env.id, { deployments: [], drift: [] }] as const }
  }))
  stateByEnv.value = Object.fromEntries(entries)
}
watch(() => environments.value.map(e => e.id).join(','), () => { if (import.meta.client) loadStates() }, { immediate: true })
// The parent drives refreshes for events these cards can't see — a rolled-back attempt resetting
// the deployments, the header's Refresh button.
defineExpose({ refresh: loadStates })

/** The stop's dot: amber drift > green live-and-in-sync > hollow nothing. */
function envDot(envId: string): { color: string; hollow: boolean } {
  const s = stateByEnv.value[envId]
  if (!s || !s.deployments.length) return { color: 'var(--fg-3)', hollow: true }
  if (s.drift.length) return { color: '#ffb547', hollow: false }
  return { color: 'var(--ok, #34d399)', hollow: false }
}

interface EnvSummary { total: number; deployed: number; drift: Drift[] }
/** The chain's selected stop, summarized: deployed count · drift vs this release. */
function summaryFor(chain: Environment[]): EnvSummary {
  const s = stateByEnv.value[selectedIn(chain)] ?? { deployments: [], drift: [] }
  return {
    total: s.deployments.length,
    deployed: s.deployments.filter(d => d.status.toUpperCase() === 'DEPLOYED').length,
    drift: s.drift,
  }
}

/** One drift row's story: what the environment runs now vs what this release ships. */
function driftText(d: Drift): string {
  const ships = d.expectedVersion?.name ?? '?'
  return d.deployedVersion
    ? `${d.deployedVersion.name} deployed — this release ships ${ships}`
    : `not deployed — this release ships ${ships}`
}

const chainCards = computed(() => chains.value.map(chain => ({
  chain,
  label: chainLabel(chain),
  summary: summaryFor(chain),
})))
</script>

<template>
  <aside class="rail">
    <div v-if="!environments.length" class="rail-card">
      <p class="rail-card-title">Environments</p>
      <div class="rail-empty">
        None for this program yet — add them on the program (Portfolios → globe).
      </div>
    </div>
    <div
      v-for="{ chain, label, summary } in chainCards"
      v-else
      :key="chain[0]!.id"
      class="rail-card">
      <p class="rail-card-title">{{ label }}</p>
      <div class="env-chain">
        <template v-for="(env, i) in chain" :key="env.id">
          <span v-if="i > 0" class="env-arrow">→</span>
          <button
            type="button"
            class="env-stop"
            :class="{ active: env.id === selectedIn(chain) }"
            @click="selectedStops[chain[0]!.id] = env.id">
            <span
              class="env-dot"
              :style="envDot(env.id).hollow
                ? { border: `1.5px solid ${envDot(env.id).color}`, background: 'transparent' }
                : { background: envDot(env.id).color }" />
            {{ env.name }}
          </button>
        </template>
      </div>
      <p class="env-summary">
        <span v-if="!summary.total && !summary.drift.length">nothing deployed</span>
        <template v-else>
          <span>deployed {{ summary.deployed }}/{{ summary.total }}</span>
          <span class="env-sep">·</span>
          <Popover
            v-if="summary.drift.length"
            trigger="mouseenter"
            placement="bottom"
            :delay="150">
            <template #trigger>
              <span class="env-differ">{{ summary.drift.length }} differ</span>
            </template>
            <div class="drift-pop">
              <p class="drift-pop-title">Differs from this release</p>
              <div
                v-for="d in summary.drift"
                :key="d.projectId"
                class="drift-pop-row">
                <span class="drift-pop-key">{{ d.project?.key ?? '—' }}</span>
                <span class="drift-pop-text">{{ driftText(d) }}</span>
              </div>
            </div>
          </Popover>
          <span v-else style="color: var(--ok, #34d399)">in sync</span>
        </template>
      </p>
    </div>
  </aside>
</template>

<style scoped>
.rail { display: flex; flex-direction: column; gap: 14px; }
.rail-card { padding: 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--bg-1); }
.rail-card-title { margin: 0 0 12px; font-size: 11px; text-transform: uppercase; letter-spacing: 0.08em; font-weight: 600; color: var(--fg-3); }
.rail-empty { font-size: 12.5px; color: var(--fg-3); line-height: 1.5; }
.env-chain { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.env-arrow { color: var(--fg-3); font-size: 11px; }
.env-stop {
  display: inline-flex; align-items: center; gap: 6px; padding: 3px 8px;
  border: 1px solid transparent; border-radius: 6px; background: transparent;
  color: var(--fg-2); font: inherit; font-size: 11.5px; cursor: pointer;
}
.env-stop:hover { color: var(--fg-0); border-color: var(--line); }
.env-stop.active { background: var(--bg-2); color: var(--fg-0); border-color: var(--line); }
.env-dot { width: 8px; height: 8px; border-radius: 50%; flex: 0 0 auto; }
.env-summary {
  display: flex; align-items: center; flex-wrap: wrap; gap: 6px;
  margin: 10px 0 0; font-family: var(--font-mono, monospace); font-size: 11px; color: var(--fg-2);
  font-variant-numeric: tabular-nums;
}
.env-sep { color: var(--fg-3); }
.env-differ {
  color: #ffb547; cursor: default;
  border-bottom: 1px dotted color-mix(in oklch, #ffb547 55%, transparent);
}
.drift-pop { display: flex; flex-direction: column; gap: 6px; min-width: 220px; }
.drift-pop-title {
  margin: 0; font-size: 10.5px; text-transform: uppercase; letter-spacing: 0.07em;
  font-weight: 600; color: var(--fg-3);
}
.drift-pop-row { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.drift-pop-key { font-family: var(--font-mono, monospace); font-size: 11.5px; font-weight: 500; color: var(--fg-0); }
.drift-pop-text { font-size: 11.5px; color: var(--fg-2); text-align: right; }
</style>
