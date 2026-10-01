<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'
import type { RecommendationPlacementInput } from '~/types/graphql'

definePageMeta({ middleware: 'recommendations-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const route = useRoute()
const router = useRouter()

const placementId = computed(() => route.params.id as string)

interface PlacementDetail {
  id: string
  name: string
  description: string
  slug: string
  maxItems: number
  configuration: unknown
  created: string
  modified: string
  strategies: { id: string; name: string }[]
}

const detailGql = gql`
  query GetRecommendationPlacement($id: UUID!) {
    recommendation {
      placements {
        placement(id: $id) {
          id name description slug maxItems configuration created modified
          strategies { id name }
        }
      }
    }
  }
`
const { data, status, error, refresh } = useAsyncQuery<{ recommendation: { placements: { placement: PlacementDetail | null } } }>(
  'recommendation-placement', detailGql, { id: placementId },
)
const placement = computed(() => data.value?.recommendation?.placements?.placement ?? null)

const strategiesGql = gql`
  query RecommendationPlacementEditStrategies {
    recommendation { strategies { all(offset: 0, limit: 200) { id name } } }
  }
`
const { data: stratData } = useAsyncQuery<{ recommendation: { strategies: { all: { id: string; name: string }[] } } }>(
  'recommendation-placement-edit-strategies', strategiesGql,
)
const strategyOptions = computed<SelectOption[]>(() =>
  (stratData.value?.recommendation?.strategies?.all ?? []).map(s => ({ value: s.id, label: s.name })),
)

const form = reactive({
  name: '',
  slug: '',
  description: '',
  maxItems: 10,
  configuration: '',
  strategyIds: [] as string[],
})

watch(placement, (p) => {
  if (!p) return
  form.name = p.name
  form.slug = p.slug
  form.description = p.description ?? ''
  form.maxItems = p.maxItems
  form.configuration = p.configuration != null ? JSON.stringify(p.configuration, null, 2) : ''
  form.strategyIds = p.strategies.map(s => s.id)
}, { immediate: true })

const saving = ref(false)
const actionError = ref('')
const savedAt = ref('')

const editGql = gql`
  mutation EditRecommendationPlacement($id: UUID!, $placement: RecommendationPlacementInput!, $strategyIds: [UUID!]!) {
    recommendation { placements { edit(id: $id, placement: $placement, strategyIds: $strategyIds) { id } } }
  }
`
async function handleSave() {
  if (!form.name.trim()) { actionError.value = 'Name is required.'; return }
  if (!form.slug.trim()) { actionError.value = 'Slug is required.'; return }
  let configuration: unknown
  if (form.configuration.trim()) {
    try {
      configuration = JSON.parse(form.configuration)
    } catch {
      actionError.value = 'Configuration must be valid JSON.'
      return
    }
  }
  saving.value = true
  actionError.value = ''
  try {
    const input: RecommendationPlacementInput = {
      name: form.name.trim(),
      slug: form.slug.trim(),
      description: form.description.trim() || undefined,
      maxItems: form.maxItems,
      configuration,
    }
    await mutation(editGql, { id: placementId.value, placement: input, strategyIds: form.strategyIds })
    savedAt.value = new Date().toLocaleTimeString()
    await refresh()
  } catch (e: unknown) {
    actionError.value = e instanceof Error ? e.message : 'Failed to save placement'
  } finally {
    saving.value = false
  }
}

const showDelete = ref(false)
const deleting = ref(false)
const deleteGql = gql`
  mutation DeleteRecommendationPlacement($id: UUID!) {
    recommendation { placements { delete(id: $id) } }
  }
`
async function handleDelete() {
  deleting.value = true
  try {
    await mutation(deleteGql, { id: placementId.value })
    router.push('/recommendations/placements')
  } catch (e: unknown) {
    actionError.value = e instanceof Error ? e.message : 'Failed to delete placement'
    deleting.value = false
    showDelete.value = false
  }
}

function fmtDate(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Recommendations', { label: 'Placements', to: '/recommendations/placements' }, placement?.name ?? 'Placement')"
        :title="placement?.name ?? 'Placement'"
        subtitle="Edit the display location and the strategies that feed it">
        <template #actions>
          <Button
            icon="trash"
            size="sm"
            :disabled="!placement"
            @click="showDelete = true">Delete</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="error" class="query-error">Couldn't load placement — {{ error.message }}</div>
    <div v-else-if="status === 'pending' && !placement" class="state">Loading…</div>
    <div v-else-if="!placement" class="state">Placement not found.</div>

    <template v-else>
      <SectionCard title="Details" padded>
        <div class="form-stack">
          <TextInput v-model="form.name" label="Name" />
          <TextInput v-model="form.slug" label="Slug" mono />
          <Textarea v-model="form.description" label="Description" :rows="2" />
          <NumberInput v-model="form.maxItems" label="Max items" :min="1" />
          <Select
            v-model="form.strategyIds"
            label="Strategies"
            multiple
            placeholder="Associate strategies (ordered by priority)"
            :options="strategyOptions"
            :accent="accent" />
          <Textarea
            v-model="form.configuration"
            label="Display configuration (JSON)"
            mono
            :rows="5"
            placeholder="{ }" />
          <p v-if="actionError" class="form-error">{{ actionError }}</p>
          <div class="save-row">
            <span v-if="savedAt" class="saved-note">Saved at {{ savedAt }}</span>
            <Button
              primary
              :accent="accent"
              :disabled="saving"
              @click="handleSave">{{ saving ? 'Saving…' : 'Save changes' }}</Button>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="Activity" padded>
        <dl class="meta">
          <div><dt>Created</dt><dd>{{ fmtDate(placement.created) }}</dd></div>
          <div><dt>Modified</dt><dd>{{ fmtDate(placement.modified) }}</dd></div>
        </dl>
      </SectionCard>
    </template>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Placement"
      subtitle="Removes the placement and its strategy associations. This cannot be undone."
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Delete <strong>{{ placement?.name }}</strong>?</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.query-error {
  background: var(--bg-3);
  border-left: 3px solid var(--err, #ff5c5c);
  padding: 8px 10px;
  font-size: 12.5px;
  color: var(--fg-2);
  border-radius: 4px;
  margin-bottom: 12px;
}
.state { padding: 32px; text-align: center; color: var(--fg-3); }
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-error { color: var(--err, #ff5c5c); font-size: 12px; margin: 0; }
.save-row { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.saved-note { font-size: 12px; color: var(--fg-3); }
.meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; margin: 0; }
.meta dt { font-size: 11.5px; color: var(--fg-3); margin-bottom: 2px; }
.meta dd { font-size: 13px; color: var(--fg-1); margin: 0; }
</style>
