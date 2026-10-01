<script setup lang="ts">
import gql from 'graphql-tag'
import type { RecommendationContext } from '~/types/graphql'

definePageMeta({ middleware: 'recommendations-admin' })

const { accent } = useCurrentSubsystem()
const { mutation } = useGraphQL()
const router = useRouter()
const toast = useToast()

const form = ref(createRecommendationContextForm())
const saving = ref(false)
const actionError = ref('')

const addGql = gql`
  mutation AddRecommendationContext($context: RecommendationContextInput!) {
    recommendation {
      contexts {
        add(context: $context) { id }
      }
    }
  }
`

interface AddContextData {
  recommendation: { contexts: { add: Pick<RecommendationContext, 'id'> } }
}

async function handleCreate() {
  const validationError = validateRecommendationContextForm(form.value)
  if (validationError) {
    actionError.value = validationError
    return
  }

  saving.value = true
  actionError.value = ''
  try {
    const result = await mutation<AddContextData>(addGql, {
      context: buildRecommendationContextInput(form.value),
    })
    toast.success('Context created. Use Train model when you are ready to train.')
    await router.push(`/recommendations/contexts/${result.recommendation.contexts.add.id}`)
  } catch (error: unknown) {
    actionError.value = error instanceof Error ? error.message : 'Failed to create recommendation context'
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
        :breadcrumb="buildBreadcrumb('Recommendations', { label: 'Contexts', to: '/recommendations/contexts' }, 'New Context')"
        title="New Recommendation Context"
        subtitle="Define the content eligible for a specific recommendation experience" />
    </template>

    <RecommendationContextEditor
      v-model="form"
      :saving="saving"
      :error="actionError"
      submit-label="Create context"
      @cancel="router.push('/recommendations/contexts')"
      @submit="handleCreate" />
  </PageShell>
</template>
