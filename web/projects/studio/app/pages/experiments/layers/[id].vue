<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const layerId = computed(() => route.params.id as string)

const layerGql = gql`
  query GetExclusionLayer($id: UUID!) {
    exclusionLayers { layer(id: $id) {
      id name description created
      experiments { id name status }
    } }
  }
`

const editGql = gql`
  mutation EditExclusionLayer($id: UUID!, $name: String!, $description: String!) {
    exclusionLayers { edit(id: $id, layer: { name: $name, description: $description }) { id } }
  }
`

interface Layer {
  id: string; name: string; description: string | null; created: string
  experiments: Array<{ id: string; name: string; status: string }>
}

const { data, status, refresh } = useAsyncQuery<{
  exclusionLayers: { layer: Layer | null }
}>('exclusion-layer-detail', layerGql, { id: layerId })

const layer = computed(() => data.value?.exclusionLayers?.layer ?? null)
const isLoading = computed(() => status.value === 'pending')

const name = ref('')
const description = ref('')
const saving = ref(false)

watch(layer, (l) => {
  if (l) { name.value = l.name; description.value = l.description ?? '' }
}, { immediate: true })

async function onSave() {
  saving.value = true
  try {
    await gqlMutation(editGql, { id: layerId.value, name: name.value, description: description.value })
    toast.success('Layer saved')
    refresh()
  } catch {
    toast.error('Failed to save')
  } finally {
    saving.value = false
  }
}

const STATUS_COLORS: Record<string, string> = {
  RUNNING: '#34d99a', DRAFT: '#6c7388', PAUSED: '#ffb547', COMPLETED: '#5ec5ff', ARCHIVED: '#6c7388',
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader :accent="accent" :breadcrumb="buildBreadcrumb('Experiments', 'Exclusion Layers', layer?.name ?? '…')" :title="layer?.name ?? 'Loading…'">
        <template #actions>
          <Button
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="saving"
            @click="onSave">Save</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !layer" style="padding: 40px; text-align: center; color: var(--fg-3)">Loading…</div>

    <template v-else-if="layer">
      <div class="detail-layout">
        <div class="main-content">
          <SectionCard title="Configuration">
            <TextInput v-model="name" label="Name" />
            <Textarea
              v-model="description"
              label="Description"
              :rows="2"
              style="margin-top: 14px" />
          </SectionCard>

          <SectionCard title="Attached Experiments">
            <div v-if="layer.experiments.length" class="experiment-list">
              <div
                v-for="exp in layer.experiments"
                :key="exp.id"
                class="experiment-row"
                @click="router.push(`/experiments/exp/${exp.id}`)">
                <Icon name="beaker" :size="14" :color="accent" />
                <span style="flex: 1; font-size: 13px; font-weight: 500; color: var(--fg-0)">{{ exp.name }}</span>
                <Badge :color="STATUS_COLORS[exp.status] ?? '#6c7388'">{{ exp.status }}</Badge>
              </div>
            </div>
            <div v-else style="font-size: 13px; color: var(--fg-3)">No experiments attached to this layer.</div>
          </SectionCard>
        </div>

        <div class="sidebar">
          <SectionCard title="Details">
            <div style="display: flex; flex-direction: column; gap: 10px">
              <div style="display: flex; justify-content: space-between"><span style="font-size: 12px; color: var(--fg-3)">Created</span><span style="font-size: 12px; color: var(--fg-1)">{{ new Date(layer.created).toLocaleDateString() }}</span></div>
              <div style="display: flex; justify-content: space-between"><span style="font-size: 12px; color: var(--fg-3)">Experiments</span><span class="mono" style="font-size: 12px; color: var(--fg-1)">{{ layer.experiments.length }}</span></div>
            </div>
          </SectionCard>
        </div>
      </div>
    </template>
  </PageShell>
</template>

<style scoped>
.detail-layout { display: grid; grid-template-columns: 1fr 280px; gap: 18px; align-items: start; }
.main-content { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.sidebar { display: flex; flex-direction: column; gap: 14px; }
.experiment-list { display: flex; flex-direction: column; gap: 4px; }
.experiment-row { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: var(--r-sm); cursor: pointer; transition: background 0.15s; }
.experiment-row:hover { background: color-mix(in oklch, var(--brand-2) 6%, transparent); }
</style>
