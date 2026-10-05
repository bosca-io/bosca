<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const PROFILE_PAGE_SIZE = 25
const PROFILE_FILTER = '_type = "profile" AND contentType = "bosca/v-profile-generic"'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const experimentId = computed(() => route.params.id as string)
const searchQuery = ref('')
const offset = ref(0)
const selectedPrincipalIds = ref<string[]>([])
const savedPrincipalIds = ref<string[]>([])
const accountCache = reactive(new Map<string, Principal>())
const saving = ref(false)

const experimentGql = gql`
  query GetExperimentAccountExclusions($id: UUID!) {
    experiments {
      experiment(id: $id) {
        id name description hypothesis status
        startDate endDate targetSampleSize
        featureFlagId targetingRuleId controlVariationKey excludedPrincipalIds
        activationFilter {
          eventType elementType elementId pagePath pagePathPrefixes itemExtraKey itemExtraValue
        }
        exclusionLayer { id }
        analysisMethod
        bayesianPrior { betaPriorAlpha betaPriorBeta normalPriorMean normalPriorVariance }
        rolloutPolicy {
          mode treatmentVariationKey
          steps { weightPercent afterDuration }
          incrementPercent minConfidence
          guardrailThreshold guardrailMinRegressionPercent haltOnGuardrail
        }
      }
    }
  }
`

const searchProfilesGql = gql`
  query SearchProfilesForExperimentExclusions($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          profile {
            id name
            attributes { typeId attributes }
            principal {
              id verified primaryProfileId
              credentials { type identifier }
            }
          }
        }
        estimatedHits
      }
    }
  }
`

const accountByIdGql = gql`
  query GetAccountForExperimentExclusion($id: UUID!) {
    security {
      principals {
        principal(id: $id) {
          id verified primaryProfileId
          credentials { type identifier }
          profiles {
            id name
            attributes { typeId attributes }
          }
        }
      }
    }
  }
`

const editGql = gql`
  mutation EditExperimentAccountExclusions($id: UUID!, $experiment: ExperimentInput!) {
    experiments { edit(id: $id, experiment: $experiment) { id excludedPrincipalIds } }
  }
`

interface ProfileAttribute {
  typeId: string
  attributes: Record<string, unknown> | null
}

interface PrincipalProfile {
  id: string
  name: string
  attributes: ProfileAttribute[]
}

interface Principal {
  id: string
  verified: boolean
  primaryProfileId: string | null
  credentials: Array<{ type: string; identifier: string }>
  profiles: PrincipalProfile[]
}

interface SearchProfile extends PrincipalProfile {
  principal: Omit<Principal, 'profiles'> | null
}

interface ExperimentEditSnapshot {
  id: string
  name: string
  description: string | null
  hypothesis: string | null
  status: string
  startDate: string | null
  endDate: string | null
  targetSampleSize: number | null
  featureFlagId: string
  targetingRuleId: string | null
  controlVariationKey: string
  excludedPrincipalIds: string[]
  activationFilter: {
    eventType: string | null
    elementType: string | null
    elementId: string | null
    pagePath: string | null
    pagePathPrefixes: string[]
    itemExtraKey: string | null
    itemExtraValue: string | null
  } | null
  exclusionLayer: { id: string } | null
  analysisMethod: string | null
  bayesianPrior: {
    betaPriorAlpha: number | null
    betaPriorBeta: number | null
    normalPriorMean: number | null
    normalPriorVariance: number | null
  } | null
  rolloutPolicy: {
    mode: string
    treatmentVariationKey: string | null
    steps: Array<{ weightPercent: number; afterDuration: string | null }>
    incrementPercent: number | null
    minConfidence: number | null
    guardrailThreshold: number | null
    guardrailMinRegressionPercent: number | null
    haltOnGuardrail: boolean | null
  } | null
}

const {
  data: experimentData,
  status: experimentStatus,
  error: experimentError,
  refresh: refreshExperiment,
} = useAsyncQuery<{
  experiments: { experiment: ExperimentEditSnapshot | null }
}>('experiment-account-exclusions', experimentGql, { id: experimentId }, { server: false })

const {
  data: profileData,
  status: profileStatus,
  error: profileError,
  refresh: refreshProfiles,
} = useAsyncQuery<{
  search: {
    search: {
      documents: Array<{ profile: SearchProfile | null }>
      estimatedHits: number
    }
  }
}>('experiment-account-exclusion-search', searchProfilesGql, {
  query: searchQuery,
  filter: PROFILE_FILTER,
  limit: PROFILE_PAGE_SIZE,
  offset,
}, { server: false })

const experiment = computed(() => experimentData.value?.experiments?.experiment ?? null)
const profileResults = computed(() => profileData.value?.search?.search?.documents ?? [])
const accounts = computed<Principal[]>(() => {
  const values = new Map<string, Principal>()
  for (const document of profileResults.value) {
    const profile = document.profile
    const principal = profile?.principal
    if (!profile || !principal) continue
    const existing = values.get(principal.id)
    if (existing) {
      if (!existing.profiles.some(candidate => candidate.id === profile.id)) existing.profiles.push(profile)
    } else {
      values.set(principal.id, { ...principal, profiles: [profile] })
    }
  }
  return [...values.values()]
})
const totalHits = computed(() => profileData.value?.search?.search?.estimatedHits ?? 0)
const currentPage = computed(() => Math.floor(offset.value / PROFILE_PAGE_SIZE) + 1)
const totalPages = computed(() => Math.ceil(totalHits.value / PROFILE_PAGE_SIZE))
const isExperimentLoading = computed(() => experimentStatus.value === 'pending')
const areAccountsLoading = computed(() => profileStatus.value === 'pending')

watch(searchQuery, () => {
  offset.value = 0
})

let hydratedExperimentId: string | null = null
watch(experiment, (value) => {
  if (!value || value.id === hydratedExperimentId) return
  hydratedExperimentId = value.id
  selectedPrincipalIds.value = [...value.excludedPrincipalIds]
  savedPrincipalIds.value = [...value.excludedPrincipalIds]
}, { immediate: true })

watch(accounts, (values) => {
  for (const account of values) accountCache.set(account.id, account)
}, { immediate: true })

watch(selectedPrincipalIds, (ids) => {
  void hydrateAccounts(ids)
}, { immediate: true })

const selectedAccounts = computed(() => selectedPrincipalIds.value.map(id =>
  accountCache.get(id) ?? {
    id,
    verified: false,
    primaryProfileId: null,
    credentials: [],
    profiles: [],
  },
))

const isDirty = computed(() => {
  const selected = [...new Set(selectedPrincipalIds.value)].sort()
  const saved = [...new Set(savedPrincipalIds.value)].sort()
  return selected.length !== saved.length || selected.some((id, index) => id !== saved[index])
})

const selectedColumns: GlassTableColumn[] = [
  { key: 'account', label: 'Account', width: 'minmax(190px, 1.2fr)' },
  { key: 'email', label: 'Email', width: 'minmax(220px, 1.4fr)' },
  { key: 'identifier', label: 'Other identifiers', width: 'minmax(150px, 1fr)', muted: true },
]

const accountColumns: GlassTableColumn[] = [
  { key: 'selected', label: '', width: '42px' },
  ...selectedColumns,
  { key: 'status', label: 'Status', width: '100px' },
]

async function hydrateAccounts(ids: string[]) {
  const unresolved = ids.filter(id => !accountCache.has(id))
  await Promise.all(unresolved.map(async (id) => {
    try {
      const result = await gqlQuery<{
        security: { principals: { principal: Principal | null } }
      }>(accountByIdGql, { id })
      const account = result.security.principals.principal
      if (account) accountCache.set(account.id, account)
    } catch (error: unknown) {
      toast.error(error instanceof Error ? error.message : 'Failed to load an excluded account')
    }
  }))
}

function accountName(account: Principal): string {
  const primary = account.profiles.find(profile => profile.id === account.primaryProfileId)
  return primary?.name ?? account.profiles[0]?.name ?? 'Account'
}

function accountEmails(account: Principal): string[] {
  const values = new Set<string>()
  for (const credential of account.credentials) {
    if (credential.identifier.includes('@')) values.add(credential.identifier)
  }
  for (const profile of account.profiles) {
    for (const attribute of profile.attributes) {
      if (attribute.typeId !== 'bosca.profiles.email') continue
      const email = attribute.attributes?.email
      if (typeof email === 'string' && email.trim()) values.add(email.trim())
    }
  }
  return [...values]
}

function otherIdentifiers(account: Principal): string[] {
  return [...new Set(account.credentials
    .map(credential => credential.identifier)
    .filter(identifier => !identifier.includes('@')))]
}

function isSelected(id: string): boolean {
  return selectedPrincipalIds.value.includes(id)
}

function setSelected(id: string, selected: boolean) {
  if (selected) {
    if (!isSelected(id)) selectedPrincipalIds.value = [...selectedPrincipalIds.value, id]
    return
  }
  selectedPrincipalIds.value = selectedPrincipalIds.value.filter(value => value !== id)
}

function toggleAccount(account: Principal) {
  setSelected(account.id, !isSelected(account.id))
}

function goToPage(page: number) {
  if (page < 1 || page > totalPages.value) return
  offset.value = (page - 1) * PROFILE_PAGE_SIZE
}

function experimentInput(value: ExperimentEditSnapshot, excludedPrincipalIds: string[]) {
  return {
    featureFlagId: value.featureFlagId,
    name: value.name,
    description: value.description,
    hypothesis: value.hypothesis,
    targetingRuleId: value.targetingRuleId,
    controlVariationKey: value.controlVariationKey,
    excludedPrincipalIds,
    activationFilter: value.activationFilter,
    exclusionLayerId: value.exclusionLayer?.id ?? null,
    startDate: value.startDate,
    endDate: value.endDate,
    targetSampleSize: value.targetSampleSize,
    rolloutPolicy: value.rolloutPolicy,
    analysisMethod: value.analysisMethod,
    bayesianPrior: value.bayesianPrior,
  }
}

async function saveExclusions() {
  const value = experiment.value
  if (!value || !isDirty.value || saving.value) return
  saving.value = true
  try {
    const excludedPrincipalIds = [...new Set(selectedPrincipalIds.value)]
    await gqlMutation(editGql, {
      id: value.id,
      experiment: experimentInput(value, excludedPrincipalIds),
    })
    selectedPrincipalIds.value = excludedPrincipalIds
    savedPrincipalIds.value = excludedPrincipalIds
    toast.success('Excluded accounts updated')
    await refreshExperiment()
  } catch (error: unknown) {
    toast.error(error instanceof Error ? error.message : 'Failed to update excluded accounts')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Experiments', 'Experiments', experiment?.name ?? '…', 'Excluded Accounts')"
        title="Excluded Accounts"
        :subtitle="experiment?.name ?? ''"
      >
        <template #actions>
          <Button size="sm" icon="arrow-left" @click="router.push(`/experiments/exp/${experimentId}`)">
            Back to experiment
          </Button>
          <Button
            size="sm"
            primary
            icon="save"
            :accent="accent"
            :disabled="!isDirty || saving || !experiment"
            @click="saveExclusions"
          >{{ saving ? 'Saving…' : 'Save exclusions' }}</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isExperimentLoading && !experiment" class="page-state">Loading experiment…</div>

    <div v-else-if="experimentError || !experiment" class="page-state page-state-error">
      <p>{{ experimentError?.message ?? 'Experiment not found.' }}</p>
      <Button size="sm" icon="refresh" @click="refreshExperiment()">Retry</Button>
    </div>

    <template v-else>
      <div class="info-callout">
        Excluded accounts keep their assigned variation, but their assignments and analytics events are omitted from future calculations. Aggregate the experiment again after saving.
      </div>

      <SectionCard :title="`Excluded accounts (${selectedPrincipalIds.length.toLocaleString()})`" glass>
        <GlassTable
          :columns="selectedColumns"
          :rows="selectedAccounts"
          row-key="id"
          empty-text="No accounts are excluded from this experiment."
        >
          <template #col-account="{ row }">
            <div class="account-cell">
              <Avatar :name="accountName(row as Principal)" :size="30" />
              <div class="account-copy">
                <span class="account-name">{{ accountName(row as Principal) }}</span>
                <span class="account-id mono">{{ (row as Principal).id }}</span>
              </div>
            </div>
          </template>
          <template #col-email="{ row }">
            <div v-if="accountEmails(row as Principal).length" class="value-list">
              <span v-for="email in accountEmails(row as Principal)" :key="email">{{ email }}</span>
            </div>
            <span v-else class="missing-value">No email</span>
          </template>
          <template #col-identifier="{ row }">
            <span>{{ otherIdentifiers(row as Principal).join(', ') || '—' }}</span>
          </template>
          <template #actions="{ row }">
            <Button size="xs" @click.stop="setSelected((row as Principal).id, false)">Remove</Button>
          </template>
        </GlassTable>
      </SectionCard>

      <div class="search-bar">
        <SearchInput
          v-model="searchQuery"
          max-width="520px"
          placeholder="Search profiles by name…"
        />
        <span class="search-hint">Email addresses are shown so profiles with the same name are easy to distinguish.</span>
      </div>

      <SectionCard title="Find accounts" glass>
        <template #right>
          <span class="mono page-label">page {{ currentPage.toLocaleString() }}</span>
        </template>

        <div v-if="profileError" class="inline-error">
          <span>{{ profileError.message }}</span>
          <Button size="xs" icon="refresh" @click="refreshProfiles()">Retry</Button>
        </div>

        <GlassTable
          v-else
          :columns="accountColumns"
          :rows="accounts"
          :loading="areAccountsLoading && accounts.length === 0"
          row-key="id"
          empty-text="No accounts match this search."
          @row-click="(row: Principal) => toggleAccount(row)"
        >
          <template #col-selected="{ row }">
            <div class="checkbox-cell" @click.stop>
              <Checkbox
                :model-value="isSelected((row as Principal).id)"
                :accent="accent"
                @update:model-value="(selected: boolean) => setSelected((row as Principal).id, selected)"
              />
            </div>
          </template>
          <template #col-account="{ row }">
            <div class="account-cell">
              <Avatar :name="accountName(row as Principal)" :size="30" />
              <div class="account-copy">
                <span class="account-name">{{ accountName(row as Principal) }}</span>
                <span class="account-id mono">{{ (row as Principal).id }}</span>
              </div>
            </div>
          </template>
          <template #col-email="{ row }">
            <div v-if="accountEmails(row as Principal).length" class="value-list">
              <span v-for="email in accountEmails(row as Principal)" :key="email">{{ email }}</span>
            </div>
            <span v-else class="missing-value">No email</span>
          </template>
          <template #col-identifier="{ row }">
            <span>{{ otherIdentifiers(row as Principal).join(', ') || '—' }}</span>
          </template>
          <template #col-status="{ row }">
            <Badge :color="(row as Principal).verified ? '#34d99a' : '#ffb547'">
              {{ (row as Principal).verified ? 'Verified' : 'Unverified' }}
            </Badge>
          </template>
        </GlassTable>

        <Pagination
          v-if="totalPages > 1"
          :page="currentPage"
          :total-pages="totalPages"
          @prev="goToPage(currentPage - 1)"
          @next="goToPage(currentPage + 1)"
        />
      </SectionCard>
    </template>
  </PageShell>
</template>

<style scoped>
.page-state {
  padding: 40px;
  color: var(--fg-3);
  text-align: center;
}

.page-state-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  color: var(--err);
}

.page-state-error p {
  margin: 0;
}

.info-callout {
  padding: 12px 14px;
  border: 1px solid color-mix(in srgb, #5ec5ff 22%, var(--line));
  border-radius: var(--r-sm);
  background: color-mix(in srgb, #5ec5ff 6%, var(--bg-1));
  color: var(--fg-2);
  font-size: 12.5px;
  line-height: 1.5;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.search-hint,
.page-label {
  color: var(--fg-3);
  font-size: 12px;
}

.inline-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  color: var(--err);
  font-size: 13px;
}

.account-cell {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.account-copy,
.value-list {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.account-name {
  color: var(--fg-0);
  font-size: 13px;
  font-weight: 500;
}

.account-id {
  overflow: hidden;
  color: var(--fg-3);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.value-list {
  gap: 2px;
  color: var(--fg-1);
  font-size: 12.5px;
}

.missing-value {
  color: var(--fg-4);
  font-size: 12px;
}

.checkbox-cell {
  display: flex;
  align-items: center;
  justify-content: center;
}

@media (max-width: 900px) {
  .search-bar {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
