<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'
import type { RecommendationSource } from '~/types/graphql'

definePageMeta({ middleware: 'recommendations-admin' })

const { accent } = useCurrentSubsystem()
const { query, mutation, useAsyncQuery } = useGraphQL()
const { profile: currentProfile } = useAuth()

interface Rec {
  id: string
  score: number
  reason: string | null
  fallback: boolean
  sources: RecommendationSource[]
  metadata: { id: string; name: string; attributes: unknown } | null
  collection: { id: string; name: string; attributes: unknown } | null
  strategy?: { id: string; name: string } | null
}

const MODE_OPTIONS: SelectOption[] = [
  { value: 'profile', label: 'Profile feed' },
  { value: 'trending', label: 'Trending' },
  { value: 'similar', label: 'Similar to content' },
  { value: 'related', label: 'People also viewed' },
  { value: 'recommended', label: 'Recommended for an item' },
  { value: 'placement', label: 'Placement' },
]

const mode = ref('profile')
const VIEWER_OPTIONS: SelectOption[] = [
  { value: 'anonymous', label: 'Anonymous visitor' },
  { value: 'current', label: 'My Studio profile' },
  { value: 'profile', label: 'Selected profile' },
]
const viewer = ref('anonymous')
const hasViewer = computed(() => ['related', 'recommended', 'placement'].includes(mode.value))
const needsProfile = computed(() => mode.value === 'profile' || (hasViewer.value && viewer.value === 'profile'))
const profileId = ref('')
const metadataId = ref('')
const placementSlug = ref('')
const limit = ref(25)

const results = ref<Rec[]>([])
const running = ref(false)
const runError = ref('')
const ran = ref(false)
const resultViewer = ref('')

const RESULT_FIELDS = `
  id score reason fallback sources
  metadata { id name attributes(filter: { attributes: ["type"] }) }
  collection { id name attributes(filter: { attributes: ["type"] }) }
  strategy @skip(if: $anonymous) { id name }
`
const profileGql = gql`query TestProfileRecs($profileId: UUID!, $limit: Int!, $anonymous: Boolean! = false) { recommendation { forYou(profileId: $profileId, offset: 0, limit: $limit) { ${RESULT_FIELDS} } } }`
const trendingGql = gql`query TestTrendingRecs($limit: Int!, $anonymous: Boolean! = false) { recommendation { trending(offset: 0, limit: $limit) { ${RESULT_FIELDS} } } }`
const similarGql = gql`query TestSimilarRecs($metadataId: UUID!, $limit: Int!, $anonymous: Boolean! = false) { recommendation { similar(metadataId: $metadataId, limit: $limit) { ${RESULT_FIELDS} } } }`
const relatedGql = gql`query TestRelatedRecs($metadataId: UUID!, $profileId: UUID, $limit: Int!, $anonymous: Boolean! = false) { recommendation { coEngaged(metadataId: $metadataId, profileId: $profileId, limit: $limit) { ${RESULT_FIELDS} } } }`
const recommendedGql = gql`query TestRecommendedRecs($metadataId: UUID!, $profileId: UUID, $limit: Int!, $anonymous: Boolean! = false) { recommendation { recommended(metadataId: $metadataId, profileId: $profileId, limit: $limit) { ${RESULT_FIELDS} } } }`
const placementGql = gql`query TestPlacementRecs($profileId: UUID, $placementSlug: String!, $limit: Int!, $anonymous: Boolean! = false) { recommendation { placement(profileId: $profileId, placementSlug: $placementSlug, limit: $limit) { ${RESULT_FIELDS} } } }`

async function run() {
  runError.value = ''
  const lim = Math.max(1, Math.min(mode.value === 'profile' || mode.value === 'trending' ? 100 : 50, limit.value))
  // Per-mode required inputs
  if (needsProfile.value && !profileId.value.trim()) { runError.value = 'Search for and select a profile.'; return }
  if (hasViewer.value && viewer.value === 'current' && !currentProfile.value?.id) { runError.value = 'Your primary Studio profile is unavailable.'; return }
  if ((mode.value === 'similar' || mode.value === 'related' || mode.value === 'recommended') && !metadataId.value.trim()) { runError.value = 'Search for and select a content item.'; return }
  if (mode.value === 'placement' && !placementSlug.value.trim()) { runError.value = 'Enter a placement slug.'; return }

  running.value = true
  const anonymous = hasViewer.value && viewer.value === 'anonymous'
  const selectedProfile = needsProfile.value
    ? profileId.value.trim()
    : hasViewer.value && viewer.value === 'current'
      ? currentProfile.value?.id ?? null
      : null
  const queryLabel = MODE_OPTIONS.find(option => option.value === mode.value)?.label
  const viewerLabel = anonymous ? 'Anonymous visitor · public visibility'
    : hasViewer.value && viewer.value === 'current' ? 'My Studio profile · Studio visibility'
      : selectedProfile ? `Profile: ${profileSearchText.value} · Studio visibility` : 'Studio visibility'
  try {
    let recs: Rec[] = []
    if (mode.value === 'profile') {
      const d = await query<{ recommendation: { forYou: Rec[] } }>(profileGql, { profileId: profileId.value.trim(), limit: lim })
      recs = d?.recommendation?.forYou ?? []
    } else if (mode.value === 'trending') {
      const d = await query<{ recommendation: { trending: Rec[] } }>(trendingGql, { limit: lim })
      recs = d?.recommendation?.trending ?? []
    } else if (mode.value === 'similar') {
      const d = await query<{ recommendation: { similar: Rec[] } }>(similarGql, { metadataId: metadataId.value.trim(), limit: lim })
      recs = d?.recommendation?.similar ?? []
    } else if (mode.value === 'related') {
      const d = await query<{ recommendation: { coEngaged: Rec[] } }>(relatedGql, {
        metadataId: metadataId.value.trim(),
        profileId: selectedProfile,
        limit: lim,
        anonymous,
      }, { anonymous })
      recs = d?.recommendation?.coEngaged ?? []
    } else if (mode.value === 'recommended') {
      const d = await query<{ recommendation: { recommended: Rec[] } }>(recommendedGql, {
        metadataId: metadataId.value.trim(),
        profileId: selectedProfile,
        limit: lim,
        anonymous,
      }, { anonymous })
      recs = d?.recommendation?.recommended ?? []
    } else {
      const d = await query<{ recommendation: { placement: Rec[] } }>(placementGql, {
        profileId: selectedProfile,
        placementSlug: placementSlug.value.trim(),
        limit: lim,
        anonymous,
      }, { anonymous })
      recs = d?.recommendation?.placement ?? []
    }
    results.value = recs
    resultViewer.value = `${queryLabel}: ${viewerLabel}`
    ran.value = true
  } catch (e: unknown) {
    runError.value = e instanceof Error ? e.message : 'Failed to run recommendations'
  } finally {
    running.value = false
  }
}

// ── Profile search (typeahead) ──────────────────────────────────────────────────────────────
// Resolve a profile by name to its id via the (Meilisearch-backed) search index, so you never paste a
// UUID. Mirrors the audience → profiles list search.
interface ProfileHit {
  id: string
  name: string
  attributes: Array<{
    typeId: string
    attributes: Record<string, unknown> | null
  }>
}
const PROFILE_FILTER = '_type = "profile" AND contentType = "bosca/v-profile-generic"'
const profileSearchGql = gql`
  query TestProfileSearch($query: String!, $filter: String!) {
    search {
      search(query: { query: $query, filter: [$filter], storageSystemName: "Admin Search Index", limit: 8, offset: 0 }) {
        documents {
          profile {
            id
            name
            attributes {
              typeId
              attributes
            }
          }
        }
      }
    }
  }
`
const profileSearchText = ref('')
const profileMatches = ref<ProfileHit[]>([])
const profileMenuOpen = ref(false)
const profileSearching = ref(false)
let profileSearchTimer: ReturnType<typeof setTimeout> | null = null
// Set when we programmatically fill the field on selection, so the watcher doesn't treat it as a new
// search (which would clear the just-picked id and re-query).
let suppressProfileSearch = false

watch(profileSearchText, (text) => {
  if (suppressProfileSearch) { suppressProfileSearch = false; return }
  profileId.value = '' // typing invalidates the prior selection until a match is picked again
  const q = text.trim()
  if (profileSearchTimer) clearTimeout(profileSearchTimer)
  if (!q) { profileMatches.value = []; profileMenuOpen.value = false; return }
  profileSearchTimer = setTimeout(async () => {
    profileSearching.value = true
    profileMenuOpen.value = true
    try {
      const d = await query<{ search: { search: { documents: Array<{ profile: ProfileHit | null }> } } }>(
        profileSearchGql, { query: q, filter: PROFILE_FILTER },
      )
      profileMatches.value = (d?.search?.search?.documents ?? [])
        .map(x => x.profile)
        .filter((p): p is ProfileHit => p != null)
    } catch {
      profileMatches.value = []
    } finally {
      profileSearching.value = false
    }
  }, 250)
})

function profileEmail(profile: ProfileHit): string {
  const email = profile.attributes
    .find(attribute => attribute.typeId === 'bosca.profiles.email')
    ?.attributes?.email
  return typeof email === 'string' ? email : ''
}

function profileLabel(profile: ProfileHit): string {
  const email = profileEmail(profile)
  return email ? `${profile.name} · ${email}` : profile.name
}

function selectProfile(p: ProfileHit) {
  suppressProfileSearch = true
  profileId.value = p.id
  profileSearchText.value = profileLabel(p)
  profileMatches.value = []
  profileMenuOpen.value = false
}

// ── Metadata search (typeahead) ─────────────────────────────────────────────────────────────
// Resolve a content item by name to its id via the search index, so you never paste a UUID (mirrors the
// profile search above and the content pickers used elsewhere in Studio).
interface MetadataHit {
  id: string
  name: string
  content: { type: string } | null
  attributes: unknown
}
const METADATA_FILTER = '_type = "metadata"'
const metadataSearchGql = gql`
  query TestMetadataSearch($query: String!, $filter: String!) {
    search {
      search(query: { query: $query, filter: [$filter], storageSystemName: "Admin Search Index", limit: 8, offset: 0 }) {
        documents {
          metadata {
            id
            name
            content { type }
            attributes(filter: { attributes: ["type"] })
          }
        }
      }
    }
  }
`
const metadataSearchText = ref('')
const metadataMatches = ref<MetadataHit[]>([])
const metadataMenuOpen = ref(false)
const metadataSearching = ref(false)
let metadataSearchTimer: ReturnType<typeof setTimeout> | null = null
let suppressMetadataSearch = false

watch(metadataSearchText, (text) => {
  if (suppressMetadataSearch) { suppressMetadataSearch = false; return }
  metadataId.value = '' // typing invalidates the prior selection until a match is picked again
  const q = text.trim()
  if (metadataSearchTimer) clearTimeout(metadataSearchTimer)
  if (!q) { metadataMatches.value = []; metadataMenuOpen.value = false; return }
  metadataSearchTimer = setTimeout(async () => {
    metadataSearching.value = true
    metadataMenuOpen.value = true
    try {
      const d = await query<{ search: { search: { documents: Array<{ metadata: MetadataHit | null }> } } }>(
        metadataSearchGql, { query: q, filter: METADATA_FILTER },
      )
      metadataMatches.value = (d?.search?.search?.documents ?? [])
        .map(x => x.metadata)
        .filter((m): m is MetadataHit => m != null)
    } catch {
      metadataMatches.value = []
    } finally {
      metadataSearching.value = false
    }
  }, 250)
})

function metadataEditorialType(metadata: MetadataHit): string | null {
  const attributes = metadata.attributes
  if (!attributes || typeof attributes !== 'object' || Array.isArray(attributes)) return null
  const type = (attributes as Record<string, unknown>).type
  return typeof type === 'string' && type.trim() ? type.trim() : null
}

function selectMetadata(m: MetadataHit) {
  suppressMetadataSearch = true
  metadataId.value = m.id
  metadataSearchText.value = m.name
  metadataMatches.value = []
  metadataMenuOpen.value = false
}

// Evaluate-on-demand: generate fresh recommendations from a strategy, then they're queryable above.
const strategiesGql = gql`
  query TestConsoleStrategies {
    recommendation { strategies { all(offset: 0, limit: 200) { id name } } }
  }
`
const { data: stratData } = useAsyncQuery<{ recommendation: { strategies: { all: { id: string; name: string }[] } } }>(
  'recommendation-test-strategies', strategiesGql,
)
const strategyOptions = computed<SelectOption[]>(() =>
  (stratData.value?.recommendation?.strategies?.all ?? []).map(s => ({ value: s.id, label: s.name })),
)
const evalStrategyId = ref('')
const evaluating = ref(false)
const evalNote = ref('')
const evalError = ref('')

const evaluateGql = gql`
  mutation TestEvaluateStrategy($strategyId: UUID!) {
    recommendation { strategies { evaluate(strategyId: $strategyId) { id lastEvaluated } } }
  }
`
async function evaluate() {
  if (!evalStrategyId.value) { evalError.value = 'Select a strategy.'; return }
  evaluating.value = true
  evalError.value = ''
  evalNote.value = ''
  try {
    await mutation(evaluateGql, { strategyId: evalStrategyId.value })
    evalNote.value = 'Evaluation triggered — fresh recommendations generated. Run a profile feed above to see them.'
  } catch (e: unknown) {
    evalError.value = e instanceof Error ? e.message : 'Failed to evaluate strategy'
  } finally {
    evaluating.value = false
  }
}

const columns: GlassTableColumn[] = [
  { key: 'item', label: 'Item', width: 'minmax(220px, 2fr)' },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'sources', label: 'Sources', width: 'minmax(180px, 1fr)' },
  { key: 'score', label: 'Score', width: '100px', align: 'right' },
  { key: 'strategy', label: 'Strategy', width: '1fr', muted: true },
  { key: 'reason', label: 'Reason', width: '1.5fr', muted: true },
]

const SOURCE_LABELS: Record<RecommendationSource, string> = {
  CONTENT_MODEL: 'ML · Content',
  PERSONALIZED_MODEL: 'ML · Personalized',
  TRENDING: 'Trending',
  CO_ENGAGEMENT: 'Co-engagement',
  COHORT_CO_ENGAGEMENT: 'Cohort co-engagement',
}
function sourceColor(source: RecommendationSource): string {
  return source === 'CONTENT_MODEL' || source === 'PERSONALIZED_MODEL' ? '#8b5cf6' : '#10b981'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Recommendations', 'Test Console')"
        title="Test Console"
        subtitle="Run live recommendation queries and trigger strategy evaluation on demand" />
    </template>

    <SectionCard title="Run a query" padded>
      <div class="controls">
        <div class="control"><Select
          v-model="mode"
          label="Mode"
          :options="MODE_OPTIONS"
          :accent="accent" /></div>
        <div v-if="hasViewer" class="control"><Select
          v-model="viewer"
          label="Viewer"
          :options="VIEWER_OPTIONS"
          :accent="accent" /></div>
        <div v-if="needsProfile" class="control combo">
          <TextInput
            v-model="profileSearchText"
            :icon="profileId ? 'check' : 'search'"
            label="Profile"
            placeholder="Search by name…"
            @blur="profileMenuOpen = false" />
          <div v-if="profileMenuOpen" class="combo-menu">
            <div v-if="profileSearching" class="combo-menu-note">Searching…</div>
            <button
              v-for="p in profileMatches"
              :key="p.id"
              type="button"
              class="combo-option"
              @mousedown.prevent="selectProfile(p)">
              <span class="combo-option-name">{{ p.name }}</span>
              <span v-if="profileEmail(p)" class="combo-option-email">{{ profileEmail(p) }}</span>
            </button>
            <div v-if="!profileSearching && !profileMatches.length" class="combo-menu-note">No matching profiles</div>
          </div>
        </div>
        <div v-if="mode === 'similar' || mode === 'related' || mode === 'recommended'" class="control combo">
          <TextInput
            v-model="metadataSearchText"
            :icon="metadataId ? 'check' : 'search'"
            label="Content item"
            placeholder="Search by name…"
            @blur="metadataMenuOpen = false" />
          <div v-if="metadataMenuOpen" class="combo-menu">
            <div v-if="metadataSearching" class="combo-menu-note">Searching…</div>
            <button
              v-for="m in metadataMatches"
              :key="m.id"
              type="button"
              class="combo-option"
              @mousedown.prevent="selectMetadata(m)">
              <span class="combo-option-copy">
                <span class="combo-option-name">{{ m.name }}</span>
                <span class="combo-option-details">
                  contentType: <code>{{ m.content?.type ?? '—' }}</code>
                  · attributes.type: <code>{{ metadataEditorialType(m) ?? '—' }}</code>
                </span>
              </span>
              <code class="combo-option-id">{{ m.id.slice(0, 8) }}…</code>
            </button>
            <div v-if="!metadataSearching && !metadataMatches.length" class="combo-menu-note">No matching content</div>
          </div>
        </div>
        <div v-if="mode === 'placement'" class="control"><TextInput
          v-model="placementSlug"
          mono
          label="Placement slug"
          placeholder="home_feed" /></div>
        <div class="control narrow"><NumberInput
          v-model="limit"
          label="Limit"
          :min="1"
          :max="mode === 'profile' || mode === 'trending' ? 100 : 50" /></div>
        <div class="control run"><Button
          primary
          icon="play"
          :accent="accent"
          :disabled="running"
          @click="run">{{ running ? 'Running…' : 'Run' }}</Button></div>
      </div>
      <p v-if="hasViewer" class="viewer-note">
        Anonymous visitor uses public visibility and the default language, matching a signed-out API request.
        Profile previews use Studio visibility and may include content unavailable to that profile.
      </p>
      <p v-if="runError" class="form-error">{{ runError }}</p>
    </SectionCard>

    <SectionCard v-if="ran" :title="`Results (${results.length})`" padded>
      <p class="viewer-note">{{ resultViewer }}</p>
      <GlassTable
        :columns="columns"
        :rows="results"
        row-key="id"
        empty-text="No recommendations returned for this query.">
        <template #col-item="{ row }"><RecommendationItemLink :metadata="row.metadata" :collection="row.collection" /></template>
        <template #col-status="{ row }">
          <Badge :color="row.fallback ? '#f59e0b' : '#10b981'">{{ row.fallback ? 'Fallback' : 'Actual' }}</Badge>
        </template>
        <template #col-sources="{ row }">
          <div v-if="row.sources.length" class="source-badges">
            <Badge v-for="source in row.sources" :key="source" :color="sourceColor(source)">
              {{ SOURCE_LABELS[source] }}
            </Badge>
          </div>
          <template v-else>—</template>
        </template>
        <template #col-score="{ row }"><code class="mono">{{ row.score.toFixed(4) }}</code></template>
        <template #col-strategy="{ row }">{{ row.strategy?.name ?? '—' }}</template>
        <template #col-reason="{ row }">{{ row.reason ?? '—' }}</template>
      </GlassTable>
    </SectionCard>

    <SectionCard title="Generate recommendations" subtitle="Evaluate a strategy to produce fresh recommendations" padded>
      <div class="controls">
        <div class="control wide"><Select
          v-model="evalStrategyId"
          label="Strategy"
          placeholder="Select a strategy…"
          :options="strategyOptions"
          :accent="accent" /></div>
        <div class="control run"><Button
          icon="play"
          :accent="accent"
          :disabled="evaluating"
          @click="evaluate">{{ evaluating ? 'Evaluating…' : 'Evaluate now' }}</Button></div>
      </div>
      <p v-if="evalNote" class="ok-note">{{ evalNote }}</p>
      <p v-if="evalError" class="form-error">{{ evalError }}</p>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.viewer-note { color: var(--fg-3); font-size: 12px; line-height: 1.5; margin: 12px 0; }
.controls { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; }
.control { min-width: 180px; flex: 1; }
.control.narrow { min-width: 90px; flex: 0 0 90px; }
.control.wide { min-width: 280px; flex: 2; }
.control.run { flex: 0 0 auto; min-width: 0; }
.form-error { color: var(--err, #ff5c5c); font-size: 12px; margin: 8px 0 0; }
.ok-note { color: var(--fg-2); font-size: 12.5px; margin: 8px 0 0; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
.source-badges { display: flex; flex-wrap: wrap; gap: 4px; }
.combo { position: relative; }
.combo-menu {
  position: absolute;
  z-index: 20;
  left: 0;
  right: 0;
  margin-top: 4px;
  padding: 4px;
  background: var(--bg-2);
  border: 1px solid var(--border-1, rgba(255, 255, 255, 0.1));
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
  max-height: 260px;
  overflow-y: auto;
}
.combo-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 7px 9px;
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  text-align: left;
  color: var(--fg-1);
}
.combo-option:hover { background: var(--bg-3); }
.combo-option-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}
.combo-option-name {
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.combo-option-details {
  overflow: hidden;
  color: var(--fg-3);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.combo-option-details code { font-family: var(--font-mono, ui-monospace, monospace); }
.combo-option-email {
  min-width: 0;
  margin-left: auto;
  overflow: hidden;
  color: var(--fg-3);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.combo-option-id { font-family: var(--font-mono, ui-monospace, monospace); font-size: 11px; color: var(--fg-3); }
.combo-menu-note { padding: 8px 9px; font-size: 12px; color: var(--fg-3); }
</style>
