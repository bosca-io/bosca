<script setup lang="ts">
import gql from 'graphql-tag'
import { profileAssignmentInput, profileAssignmentVariation } from '~/utils/profileFlagAssignments'
import type { ProfileAssignmentFlag } from '~/utils/profileFlagAssignments'

const props = defineProps<{ principalId: string | null; accent: string }>()
const { query, mutation } = useGraphQL()
const toast = useToast()
const flags = ref<ProfileAssignmentFlag[]>([])
const loading = ref(false)
const loadError = ref(false)
const savingId = ref<string | null>(null)
const choices = ref<Record<string, string | undefined>>({})
const search = ref('')
const offset = ref(0)
const pageSize = 50
const hasMore = ref(false)
let requestId = 0

// Prefix variation values so even a variation named "automatic" remains selectable.
const automatic = 'automatic'
const choiceFor = (flag: ProfileAssignmentFlag) => {
  const variation = profileAssignmentVariation(flag, props.principalId ?? '')
  return variation === undefined ? automatic : `variation:${variation}`
}
const fields = gql`
  fragment ProfileAssignmentFlagFields on FeatureFlag {
    id key name description type status defaultVariationKey variations targetingRules
    experiments { targetingRuleId }
  }
`
const listGql = gql`
  query ProfileAssignmentFlags($offset: Long!, $limit: Int!) {
    featureFlags { all(offset: $offset, limit: $limit) { ...ProfileAssignmentFlagFields } }
  }
  ${fields}
`
const flagGql = gql`
  query ProfileAssignmentFlag($id: UUID!) {
    featureFlags { flag(id: $id) { ...ProfileAssignmentFlagFields } }
  }
  ${fields}
`
const editGql = gql`
  mutation SetProfileFlagAssignment($id: UUID!, $flag: FeatureFlagInput!) {
    featureFlags { edit(id: $id, flag: $flag) { ...ProfileAssignmentFlagFields } }
  }
  ${fields}
`

const visibleFlags = computed(() => {
  const term = search.value.trim().toLowerCase()
  return flags.value.filter((flag) => `${flag.name} ${flag.key}`.toLowerCase().includes(term))
})

async function load(pageOffset = offset.value) {
  const currentRequest = ++requestId
  if (!props.principalId) return
  loading.value = true
  loadError.value = false
  try {
    const result = await query<{ featureFlags: { all: ProfileAssignmentFlag[] } }>(listGql, {
      offset: pageOffset, limit: pageSize + 1,
    })
    if (currentRequest !== requestId) return
    hasMore.value = result.featureFlags.all.length > pageSize
    flags.value = result.featureFlags.all.slice(0, pageSize)
    choices.value = Object.fromEntries(flags.value.map((flag) => [flag.id, choiceFor(flag)]))
    offset.value = pageOffset
  } catch {
    if (currentRequest === requestId) loadError.value = true
  } finally {
    if (currentRequest === requestId) loading.value = false
  }
}

async function save(flag: ProfileAssignmentFlag) {
  const principalId = props.principalId
  const choice = choices.value[flag.id]
  if (!principalId || savingId.value || !choice) return
  const currentRequest = requestId
  savingId.value = flag.id
  try {
    // Read fresh configuration so editing a profile does not restore an old flag definition.
    const result = await query<{ featureFlags: { flag: ProfileAssignmentFlag | null } }>(flagGql, { id: flag.id })
    if (currentRequest !== requestId) return
    const latest = result.featureFlags.flag
    if (!latest) throw new Error('Flag unavailable')
    const input = profileAssignmentInput(
      latest, principalId, choice === automatic ? undefined : choice.slice('variation:'.length), crypto.randomUUID(),
    )
    const saved = await mutation<{ featureFlags: { edit: ProfileAssignmentFlag } }>(editGql, { id: flag.id, flag: input })
    if (currentRequest !== requestId) return
    flags.value = flags.value.map((item) => item.id === flag.id ? saved.featureFlags.edit : item)
    choices.value[flag.id] = choiceFor(saved.featureFlags.edit)
    toast.success(choice === automatic ? 'Manual assignment removed' : 'Feature flag assignment saved')
  } catch {
    if (currentRequest === requestId) toast.error('Could not save the assignment. Refresh the flags and try again.')
  } finally {
    savingId.value = null
  }
}

watch(() => props.principalId, () => {
  ++requestId
  flags.value = []
  choices.value = {}
  offset.value = 0
  loading.value = false
  loadError.value = false
  void load(0)
}, { immediate: true })
onBeforeUnmount(() => { ++requestId })
</script>

<template>
  <SectionCard title="Feature Flags" padded>
    <template #right>
      <Button
        v-if="principalId"
        size="sm"
        :disabled="loading || !!savingId"
        @click="load()"
      >Refresh</Button>
    </template>
    <p v-if="!principalId" class="assignment-note">Link a principal to this profile to assign feature flag variations.</p>
    <template v-else>
      <p class="assignment-note">
        Choose a variation for this login across all its profiles and devices. Saving puts its principal
        targeting rule first. Choose “Use targeting rules” to remove the manual choice.
        Disabled flags still serve their default. Changes apply on the next flag evaluation.
      </p>
      <TextInput v-model="search" label="Filter this page" placeholder="Flag name or key…" />
      <p v-if="loading" role="status" class="assignment-note">Loading feature flags…</p>
      <div v-else-if="loadError" role="alert" class="assignment-error">
        Could not load feature flags. <Button size="sm" @click="load()">Retry</Button>
      </div>
      <template v-else>
        <p v-if="!visibleFlags.length" class="assignment-note">{{ search ? 'No matching flags on this page.' : 'No feature flags.' }}</p>
        <div v-for="flag in visibleFlags" :key="flag.id" class="assignment-row">
          <div class="flag-info">
            <NuxtLink :to="`/experiments/flags/${flag.id}`">{{ flag.name }}</NuxtLink>
            <span class="flag-meta">{{ flag.key }} · {{ flag.status.toLowerCase() }}</span>
          </div>
          <Select
            v-model="choices[flag.id]"
            :label="`${flag.name} assignment`"
            :accent="accent"
            :disabled="!!savingId"
            :options="[
              { value: automatic, label: 'Use targeting rules' },
              ...flag.variations.map((variation) => ({ value: `variation:${variation.key}`, label: variation.name || variation.key })),
            ]"
          />
          <Button
            size="sm"
            :disabled="!!savingId || !choices[flag.id] || choices[flag.id] === choiceFor(flag)"
            @click="save(flag)"
          >{{ savingId === flag.id ? 'Saving…' : 'Save' }}</Button>
        </div>
        <div class="assignment-pagination">
          <Button size="sm" :disabled="offset === 0 || !!savingId" @click="load(offset - pageSize)">Previous</Button>
          <span>Page {{ offset / pageSize + 1 }}</span>
          <Button size="sm" :disabled="!hasMore || !!savingId" @click="load(offset + pageSize)">Next</Button>
        </div>
      </template>
    </template>
  </SectionCard>
</template>

<style scoped>
.assignment-note { color: var(--fg-3); font-size: 13px; line-height: 1.6; margin: 0 0 16px; }
.assignment-error { color: var(--err); padding: 16px 0; }
.assignment-row { display: grid; grid-template-columns: minmax(160px, 1fr) minmax(200px, 1fr) auto; gap: 16px; align-items: center; padding: 16px 0; border-bottom: 1px solid var(--line); }
.flag-info { display: flex; flex-direction: column; gap: 4px; min-width: 0; overflow-wrap: anywhere; }
.flag-info a { color: var(--fg-0); font-size: 14px; }
.flag-meta { color: var(--fg-3); font-size: 12px; }
.assignment-pagination { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-top: 16px; color: var(--fg-3); font-size: 12px; }
@media (max-width: 720px) { .assignment-row { grid-template-columns: 1fr; gap: 10px; } }
</style>
