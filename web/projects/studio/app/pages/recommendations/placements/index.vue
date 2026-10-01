<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'
import type { RecommendationPlacementInput } from '~/types/graphql'

definePageMeta({ middleware: 'recommendations-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const router = useRouter()

interface PlacementRow {
  id: string
  name: string
  slug: string
  maxItems: number
  strategies: { id: string; name: string }[]
}

const listGql = gql`
  query RecommendationPlacements {
    recommendation {
      placements {
        all { id name slug maxItems strategies { id name } }
      }
    }
  }
`
const { data, status, error: loadError, refresh } = useAsyncQuery<{ recommendation: { placements: { all: PlacementRow[] } } }>(
  'recommendation-placements', listGql,
)
const placements = computed(() => data.value?.recommendation?.placements?.all ?? [])

// Strategies for association.
const strategiesGql = gql`
  query RecommendationPlacementStrategies {
    recommendation { strategies { all(offset: 0, limit: 200) { id name } } }
  }
`
const { data: stratData } = useAsyncQuery<{ recommendation: { strategies: { all: { id: string; name: string }[] } } }>(
  'recommendation-placement-strategies', strategiesGql,
)
const strategyOptions = computed<SelectOption[]>(() =>
  (stratData.value?.recommendation?.strategies?.all ?? []).map(s => ({ value: s.id, label: s.name })),
)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Placement', width: 'minmax(200px, 2fr)' },
  { key: 'slug', label: 'Slug', width: '1fr', muted: true },
  { key: 'maxItems', label: 'Max items', width: '100px', align: 'right', muted: true },
  { key: 'strategies', label: 'Strategies', width: '1fr', muted: true },
]

// Create
const showCreate = ref(false)
const saving = ref(false)
const formError = ref('')
const form = reactive({
  name: '',
  slug: '',
  description: '',
  maxItems: 10,
  configuration: '',
  strategyIds: [] as string[],
})

function openCreate() {
  form.name = ''
  form.slug = ''
  form.description = ''
  form.maxItems = 10
  form.configuration = ''
  form.strategyIds = []
  formError.value = ''
  showCreate.value = true
}

const addGql = gql`
  mutation AddRecommendationPlacement($placement: RecommendationPlacementInput!, $strategyIds: [UUID!]!) {
    recommendation { placements { add(placement: $placement, strategyIds: $strategyIds) { id } } }
  }
`
async function handleCreate() {
  if (!form.name.trim()) { formError.value = 'Name is required.'; return }
  if (!form.slug.trim()) { formError.value = 'Slug is required.'; return }
  let configuration: unknown
  if (form.configuration.trim()) {
    try {
      configuration = JSON.parse(form.configuration)
    } catch {
      formError.value = 'Configuration must be valid JSON.'
      return
    }
  }
  saving.value = true
  formError.value = ''
  try {
    const placement: RecommendationPlacementInput = {
      name: form.name.trim(),
      slug: form.slug.trim(),
      description: form.description.trim() || undefined,
      maxItems: form.maxItems,
      configuration,
    }
    const result = await mutation<{ recommendation: { placements: { add: { id: string } } } }>(
      addGql, { placement, strategyIds: form.strategyIds },
    )
    showCreate.value = false
    await refresh()
    const id = result?.recommendation?.placements?.add?.id
    if (id) router.push(`/recommendations/placements/${id}`)
  } catch (e: unknown) {
    formError.value = e instanceof Error ? e.message : 'Failed to create placement'
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
        :breadcrumb="buildBreadcrumb('Recommendations', 'Placements')"
        title="Placements"
        subtitle="Named locations in your apps where recommendations appear">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Placement</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="loadError" class="query-error">Couldn't load placements — {{ loadError.message }}</div>

    <GlassTable
      :columns="columns"
      :rows="placements"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No placements yet. Create one to wire strategies into a display location."
      arrow
      @row-click="(row) => router.push(`/recommendations/placements/${row.id}`)">
      <template #col-name="{ row }">{{ row.name }}</template>
      <template #col-slug="{ row }"><code class="mono">{{ row.slug }}</code></template>
      <template #col-maxItems="{ row }">{{ row.maxItems }}</template>
      <template #col-strategies="{ row }">
        {{ row.strategies.length ? `${row.strategies.length} strateg${row.strategies.length === 1 ? 'y' : 'ies'}` : 'None' }}
      </template>
    </GlassTable>

    <Modal
      v-if="showCreate"
      title="New Placement"
      icon="layers"
      :accent="accent"
      width="560px"
      @close="showCreate = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="e.g. Home feed" />
        <TextInput
          v-model="form.slug"
          label="Slug"
          mono
          placeholder="home_feed" />
        <Textarea
          v-model="form.description"
          label="Description"
          :rows="2"
          placeholder="Where this placement appears (optional)" />
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
          :rows="3"
          placeholder="{ }" />
        <p v-if="formError" class="form-error">{{ formError }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleCreate">{{ saving ? 'Creating…' : 'Create' }}</Button>
      </template>
    </Modal>
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
.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-error { color: var(--err, #ff5c5c); font-size: 12px; margin: 0; }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12px; }
</style>
