<script setup lang="ts">
import gql from 'graphql-tag'
import type { RecommendationContext } from '~/types/graphql'

definePageMeta({ middleware: 'recommendations-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const route = useRoute()
const router = useRouter()
const toast = useToast()

const contextId = computed(() => route.params.id as string)

const detailGql = gql`
  query GetRecommendationContext($id: UUID!) {
    recommendation {
      contexts {
        context(id: $id) {
          id
          type
          name
          description
          created
          modified
          revision
          weights {
            similarity { semantic categories labels language mime type collections }
            typePreferences { type weight }
            defaultTypePreference
            content
            coEngagement
            cohortCoEngagement
            learnedNeighbor
            personalization
            rating
          }
          contentFilter {
            metadata {
              includedContentTypePrefixes
              excludedContentTypePrefixes
              includedAttributeTypes
              excludedAttributeTypes
            }
            collections {
              includedTypes
              excludedTypes
              includedAttributeTypes
              excludedAttributeTypes
            }
          }
        }
      }
    }
  }
`

interface ContextData {
  recommendation: { contexts: { context: RecommendationContext | null } }
}

const { data, status, error, refresh } = useAsyncQuery<ContextData>(
  'recommendation-context', detailGql, { id: contextId },
)
const context = computed(() => data.value?.recommendation?.contexts?.context ?? null)
const form = ref(createRecommendationContextForm())
const tab = ref('General')
const history = ref<{ refresh: () => Promise<unknown> } | null>(null)
const hasUnsavedChanges = computed(() => !!context.value && JSON.stringify(form.value)
  !== JSON.stringify(recommendationContextToForm(context.value)))

watch(context, value => {
  if (value) form.value = recommendationContextToForm(value)
}, { immediate: true })

const saving = ref(false)
const actionError = ref('')
const savedNote = ref('')

const editGql = gql`
  mutation EditRecommendationContext($id: UUID!, $context: RecommendationContextInput!) {
    recommendation {
      contexts {
        edit(id: $id, context: $context) { id }
      }
    }
  }
`

async function handleSave() {
  const validationError = validateRecommendationContextForm(form.value)
  if (validationError) {
    actionError.value = validationError
    return
  }

  saving.value = true
  actionError.value = ''
  savedNote.value = ''
  try {
    await mutation(editGql, {
      id: contextId.value,
      context: buildRecommendationContextInput(form.value),
    })
    savedNote.value = `Saved at ${new Date().toLocaleTimeString()}`
    toast.success('Context saved.')
    await refresh()
  } catch (saveError: unknown) {
    actionError.value = saveError instanceof Error ? saveError.message : 'Failed to save recommendation context'
  } finally {
    saving.value = false
  }
}

const showDelete = ref(false)
const deleting = ref(false)
const recomputing = ref(false)
const training = ref(false)
const recomputeGql = gql`
  mutation RecomputeRecommendationContextAssignments {
    recommendation { contexts { recompute } }
  }
`

async function recomputeAssignments() {
  if (!context.value || hasUnsavedChanges.value || saving.value || deleting.value || training.value || recomputing.value) return
  recomputing.value = true
  try {
    await mutation(recomputeGql)
    toast.success('Content assignment recomputation queued for all contexts.')
  } catch (recomputeError: unknown) {
    toast.error(recomputeError instanceof Error ? recomputeError.message : 'Failed to recompute content assignments')
  } finally {
    recomputing.value = false
  }
}

const trainGql = gql`
  mutation TrainRecommendationContextModel($contextId: UUID!) {
    recommendation { contexts { trainModel(contextId: $contextId) { version } } }
  }
`

async function handleTrain() {
  if (!context.value || hasUnsavedChanges.value || saving.value || deleting.value || training.value || recomputing.value) return
  training.value = true
  try {
    await mutation(trainGql, { contextId: contextId.value })
    toast.success('Model training queued for this context.')
    if (tab.value === 'Models') await history.value?.refresh()
    else tab.value = 'Models'
  } catch (trainError: unknown) {
    toast.error(trainError instanceof Error ? trainError.message : 'Failed to start model training')
  } finally {
    training.value = false
  }
}

const deleteGql = gql`
  mutation DeleteRecommendationContext($id: UUID!) {
    recommendation { contexts { delete(id: $id) } }
  }
`

async function handleDelete() {
  deleting.value = true
  actionError.value = ''
  try {
    await mutation(deleteGql, { id: contextId.value })
    toast.success('Context deleted. Use Recompute assignments to update existing content.')
    await router.push('/recommendations/contexts')
  } catch (deleteError: unknown) {
    actionError.value = deleteError instanceof Error ? deleteError.message : 'Failed to delete recommendation context'
    showDelete.value = false
  } finally {
    deleting.value = false
  }
}

function fmtDate(value: string): string {
  return new Date(value).toLocaleString()
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Recommendations', { label: 'Contexts', to: '/recommendations/contexts' }, context?.name ?? 'Context')"
        :title="context?.name ?? 'Recommendation Context'"
        subtitle="Edit recommendation weights, eligibility, and trained models">
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :loading="recomputing"
            :disabled="!context || hasUnsavedChanges || saving || deleting || training || recomputing"
            :title="hasUnsavedChanges ? 'Save your changes before recomputing assignments.' : 'Update metadata and collection assignments across all contexts using saved eligibility rules.'"
            @click="recomputeAssignments">Recompute assignments</Button>
          <Button
            icon="play"
            size="sm"
            :loading="training"
            :disabled="!context || hasUnsavedChanges || saving || deleting || training || recomputing"
            :title="hasUnsavedChanges ? 'Save your changes before training.' : 'Train using the saved context settings.'"
            @click="handleTrain">Train model</Button>
          <Button
            icon="trash"
            size="sm"
            :disabled="!context || context.type === 'default' || saving || deleting || training || recomputing"
            @click="showDelete = true">Delete</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="error" class="query-error">Couldn't load recommendation context — {{ error.message }}</div>
    <div v-else-if="status === 'pending' && !context" class="state">Loading…</div>
    <div v-else-if="!context" class="state">Recommendation context not found.</div>

    <template v-else>
      <Tabs
        v-model="tab"
        class="context-tabs"
        :tabs="['General', 'Eligibility', 'Weights', 'Models']"
        :accent="accent" />
      <p v-if="hasUnsavedChanges" class="action-note">Save your changes before recomputing assignments or training a model.</p>
      <p v-if="tab === 'Eligibility'" class="action-note">
        After saving eligibility changes, use Recompute assignments to update metadata and collection
        assignments across all contexts. Use Train model separately to train with the saved settings.
      </p>
      <RecommendationModelHistory
        v-if="tab === 'Models'"
        :key="contextId"
        ref="history"
        :context-id="contextId" />
      <RecommendationContextEditor
        v-if="tab !== 'Models'"
        v-model="form"
        :section="tab === 'Eligibility' ? 'Eligibility' : tab === 'Weights' ? 'Weights' : 'General'"
        :type-disabled="context.type === 'default'"
        :saving="saving"
        :error="actionError"
        :saved-note="savedNote"
        @cancel="router.push('/recommendations/contexts')"
        @submit="handleSave" />

      <SectionCard v-if="tab === 'General'" title="Activity" padded>
        <dl class="meta">
          <div><dt>Created</dt><dd>{{ fmtDate(context.created) }}</dd></div>
          <div><dt>Modified</dt><dd>{{ fmtDate(context.modified) }}</dd></div>
        </dl>
      </SectionCard>
    </template>

    <ConfirmModal
      v-if="showDelete"
      title="Delete Recommendation Context"
      subtitle="Use Recompute assignments afterward to update existing content."
      confirm-label="Delete"
      :loading="deleting"
      @close="showDelete = false"
      @confirm="handleDelete">
      <p>Delete <strong>{{ context?.name }}</strong>?</p>
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.context-tabs {
  flex-shrink: 0;
}

.action-note {
  margin: 0;
  color: var(--fg-3);
  font-size: 12px;
}

.query-error {
  padding: 9px 11px;
  margin-bottom: 12px;
  border-radius: 4px;
  background: var(--bg-3);
  border-left: 3px solid var(--err, #ff5c5c);
  color: var(--fg-2);
  font-size: 12.5px;
}

.state {
  padding: 32px;
  text-align: center;
  color: var(--fg-3);
}

.meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
  margin: 0;
}

.meta dt {
  margin-bottom: 2px;
  color: var(--fg-3);
  font-size: 11.5px;
}

.meta dd {
  margin: 0;
  color: var(--fg-1);
  font-size: 13px;
}
</style>
