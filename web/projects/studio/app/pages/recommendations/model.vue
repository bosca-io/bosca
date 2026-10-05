<script setup lang="ts">
import gql from 'graphql-tag'
import type { RecommendationContext } from '~/types/graphql'

definePageMeta({ middleware: 'recommendations-admin' })
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()
const history = ref<{ refresh: () => Promise<unknown> } | null>(null)

const contextsGql = gql`
  query RecommendationModelContexts {
    recommendation { contexts { all { id name type revision } } }
  }
`
interface ContextData { recommendation: { contexts: { all: RecommendationContext[] } } }
const { data, status, error } = useAsyncQuery<ContextData>('recommendation-model-contexts', contextsGql)
const contexts = computed(() => data.value?.recommendation.contexts.all ?? [])
const selectedContextId = ref('')
const contextOptions = computed(() => contexts.value.map(context => ({ value: context.id, label: context.name })))
watch(contexts, values => {
  if (!values.some(context => context.id === selectedContextId.value)) {
    selectedContextId.value = (values.find(context => context.type === 'default') ?? values[0])?.id ?? ''
  }
}, { immediate: true })

const trainGql = gql`
  mutation TrainRecommendationModel { recommendation { strategies { trainModel } } }
`
const training = ref(false)
async function trainNow() {
  training.value = true
  try {
    await mutation(trainGql)
    toast.success('Training queued for all recommendation contexts.')
    await history.value?.refresh()
  } catch (error: unknown) {
    toast.error(error instanceof Error ? error.message : 'Failed to start training')
  } finally {
    training.value = false
  }
}


const semanticBackfillGql = gql`
  mutation BackfillRecommendationSemanticData($overwriteExisting: Boolean!) {
    recommendation {
      strategies {
        backfillSemanticData(overwriteExisting: $overwriteExisting)
      }
    }
  }
`
const semanticBackfillMode = ref<'missing' | 'all' | null>(null)
async function backfillSemanticData(overwriteExisting: boolean) {
  if (overwriteExisting && !confirm(
    'Refresh semantic data for every ready, recommendable content item? This can create substantial embedding and training work.',
  )) return
  semanticBackfillMode.value = overwriteExisting ? 'all' : 'missing'
  try {
    await mutation(semanticBackfillGql, { overwriteExisting })
    toast.success(overwriteExisting
      ? 'Semantic data refresh queued. Model training will start after updated embeddings are stored.'
      : 'Semantic data backfill queued. Missing embeddings will be added in the background.')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to queue semantic data backfill')
  } finally {
    semanticBackfillMode.value = null
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Recommendations', 'Models')"
        title="Recommendation models"
        subtitle="Training history and serving versions for each context">
        <template #actions>
          <Button
            icon="play"
            :loading="training"
            :disabled="training || !contexts.length"
            @click="trainNow">Train all contexts</Button>
        </template>
      </PageHeader>
    </template>
    <p v-if="error" class="query-error">Couldn't load recommendation contexts: {{ error.message }}</p>
    <SectionCard title="Context" padded>
      <div class="context-picker">
        <Select
          v-model="selectedContextId"
          label="Recommendation context"
          :options="contextOptions"
          :loading="status === 'pending'"
          :accent="accent" />
        <NuxtLink v-if="selectedContextId" :to="`/recommendations/contexts/${selectedContextId}`">Edit context</NuxtLink>
      </div>
      <p class="note">Each version captures the context's saved weights and eligibility. Training finishes before the model is loaded and activated for recommendations.</p>
      <p v-if="status !== 'pending' && !contexts.length" class="note">Create a recommendation context to start training models.</p>
    </SectionCard>
    <RecommendationModelHistory
      v-if="selectedContextId"
      :key="selectedContextId"
      ref="history"
      :context-id="selectedContextId" />
    <SectionCard title="Semantic data" padded>
      <p class="note">Add missing content embeddings or refresh existing embeddings before future training runs.</p>
      <div class="maintenance-actions">
        <Button
          size="sm"
          icon="refresh"
          :disabled="semanticBackfillMode !== null"
          @click="backfillSemanticData(false)">Add missing embeddings</Button>
        <Button size="sm" :disabled="semanticBackfillMode !== null" @click="backfillSemanticData(true)">Refresh all embeddings</Button>
      </div>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.context-picker { display: flex; align-items: end; gap: 16px; flex-wrap: wrap; }
.context-picker > :first-child { min-width: 240px; }
.context-picker a { color: var(--fg-2); font-size: 13px; padding-bottom: 8px; }
.note { color: var(--fg-3); font-size: 13px; line-height: 1.6; margin: 12px 0; }
.query-error { color: var(--err); margin: 10px 0; }
.maintenance-actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
