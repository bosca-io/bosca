<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const modelId = computed(() => route.params.id as string)
const isNew = computed(() => modelId.value === 'new')

const modelGql = gql`
  query GetModel($id: UUID!) { ai { models { model(id: $id) { id key name type description configuration } } } }
`
const addGql = gql`
  mutation AddModel($model: ModelInput!) { ai { models { add(model: $model) { id } } } }
`
const editGql = gql`
  mutation EditModel($id: UUID!, $model: ModelInput!) { ai { models { edit(id: $id, model: $model) { id } } } }
`

interface AiModel { id: string; key: string; name: string; type: string | null; description: string | null; configuration: unknown }
const { data, refresh } = useAsyncQuery<{ ai: { models: { model: AiModel | null } } }>(
  'ai-model-detail', modelGql,
  isNew.value ? { id: '00000000-0000-0000-0000-000000000000' } : { id: modelId },
)

const model = computed(() => isNew.value ? null : data.value?.ai?.models?.model)

const key = ref('')
const name = ref('')
const description = ref('')
const type = ref('')
const configuration = ref('{}')
const saving = ref(false)

watch(model, (m) => {
  if (m) {
    key.value = m.key; name.value = m.name; description.value = m.description ?? ''; type.value = m.type ?? ''
    configuration.value = m.configuration ? JSON.stringify(m.configuration, null, 2) : '{}'
  }
}, { immediate: true })

async function onSave() {
  saving.value = true
  try {
    const input = { key: key.value, name: name.value, description: description.value, type: type.value, configuration: JSON.parse(configuration.value) }
    if (isNew.value) {
      const result = await gqlMutation<{ ai: { models: { add: { id: string } } } }>(addGql, { model: input })
      toast.success('Model created'); router.replace(`/ai/models/${result.ai.models.add.id}`)
    } else {
      await gqlMutation(editGql, { id: modelId.value, model: input }); toast.success('Saved'); refresh()
    }
  } catch { toast.error('Failed to save') } finally { saving.value = false }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader :accent="accent" :breadcrumb="buildBreadcrumb('AI', 'Models', isNew ? 'New' : name || '…')" :title="isNew ? 'New Model' : name || 'Loading…'">
        <template #actions>
          <Button
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="saving || !key.trim()"
            @click="onSave">{{ saving ? 'Saving…' : 'Save' }}</Button>
        </template>
      </PageHeader>
    </template>
    <div class="form-layout">
      <SectionCard title="Configuration" padded>
        <div class="form-grid">
          <TextInput
            v-model="key"
            label="Key"
            mono
            :disabled="!isNew" />
          <TextInput v-model="name" label="Name" />
          <TextInput v-model="type" label="Type" />
        </div>
        <Textarea
          v-model="description"
          label="Description"
          :rows="2"
          class="description-field" />
      </SectionCard>
      <SectionCard title="Model Configuration (JSON)" padded>
        <CodeEditor v-model="configuration" language="json" :rows="14" />
      </SectionCard>
    </div>
  </PageShell>
</template>

<style scoped>
.form-layout { display: flex; flex-direction: column; gap: 14px; max-width: 900px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.description-field { margin-top: 14px; }
</style>
