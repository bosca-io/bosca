<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const auth = import.meta.client ? useAuth() : null
const profile = auth?.profile ?? null
const commitAuthor = computed(() => resolveGitCommitAuthor(profile?.value))

const promptId = computed(() => route.params.id as string)
const isNew = computed(() => promptId.value === 'new')

const promptGql = gql`
  query GetPrompt($id: UUID!) {
    ai { prompts { prompt(id: $id) { id key name description inputType outputType systemPrompt userPrompt schema gitRepositoryId gitPath lastSyncError } } }
  }
`

const addGql = gql`
  mutation AddPrompt($prompt: PromptInput!) { ai { prompts { add(prompt: $prompt) { id } } } }
`
const editGql = gql`
  mutation EditPrompt($id: UUID!, $prompt: PromptInput!, $authorName: String, $authorEmail: String) {
    ai { prompts { edit(id: $id, prompt: $prompt, authorName: $authorName, authorEmail: $authorEmail) { id } } }
  }
`

interface AiPrompt { id: string; key: string; name: string; description: string | null; inputType: string | null; outputType: string | null; systemPrompt: string | null; userPrompt: string | null; schema: unknown; gitRepositoryId: string | null; gitPath: string | null; lastSyncError: string | null }
const { data, refresh } = useAsyncQuery<{ ai: { prompts: { prompt: AiPrompt | null } } }>(
  'ai-prompt-detail', promptGql, isNew.value ? { id: '00000000-0000-0000-0000-000000000000' } : { id: promptId },
)

const prompt = computed(() => isNew.value ? null : data.value?.ai?.prompts?.prompt)
const syncError = computed(() => prompt.value?.lastSyncError ?? null)
const gitLinked = computed(() => !!(prompt.value?.gitRepositoryId && prompt.value?.gitPath))
const showLinkModal = ref(false)
function onLinked() { showLinkModal.value = false; refresh() }

const key = ref('')
const name = ref('')
const description = ref('')
const inputType = ref('')
const outputType = ref('')
const systemPrompt = ref('')
const userPrompt = ref('')
const schema = ref('{}')
const saving = ref(false)

watch(prompt, (p) => {
  if (p) {
    key.value = p.key; name.value = p.name; description.value = p.description ?? ''
    inputType.value = p.inputType ?? ''; outputType.value = p.outputType ?? ''
    systemPrompt.value = p.systemPrompt ?? ''; userPrompt.value = p.userPrompt ?? ''
    schema.value = p.schema ? JSON.stringify(p.schema, null, 2) : '{}'
  }
}, { immediate: true })

async function onSave() {
  saving.value = true
  try {
    const input = {
      key: key.value, name: name.value, description: description.value,
      inputType: inputType.value || null, outputType: outputType.value || null,
      systemPrompt: systemPrompt.value || null, userPrompt: userPrompt.value || null,
      schema: JSON.parse(schema.value),
    }
    if (isNew.value) {
      const result = await gqlMutation<{ ai: { prompts: { add: { id: string } } } }>(addGql, { prompt: input })
      toast.success('Prompt created')
      router.replace(`/ai/prompts/${result.ai.prompts.add.id}`)
    } else {
      const vars: { id: string; prompt: typeof input; authorName?: string; authorEmail?: string } = {
        id: promptId.value, prompt: input,
      }
      if (gitLinked.value) {
        vars.authorName = commitAuthor.value.authorName
        vars.authorEmail = commitAuthor.value.authorEmail
      }
      await gqlMutation(editGql, vars)
      toast.success('Prompt saved')
      refresh()
    }
  } catch { toast.error('Failed to save') }
  finally { saving.value = false }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader :accent="accent" :breadcrumb="buildBreadcrumb('AI', 'Prompts', isNew ? 'New' : name || '…')" :title="isNew ? 'New Prompt' : name || 'Loading…'">
        <template #actions>
          <Button
            v-if="!isNew && !gitLinked && prompt"
            size="sm"
            icon="git-branch"
            :accent="accent"
            @click="showLinkModal = true">
            Link to Git
          </Button>
          <Button
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="saving || !key.trim() || !name.trim()"
            @click="onSave">{{ saving ? 'Saving…' : 'Save' }}</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="syncError" class="sync-error-banner">
      <strong>Git sync error:</strong> {{ syncError }}
    </div>
    <div v-else-if="gitLinked" class="git-linked-banner">
      <Icon name="git" :size="12" :color="accent" />
      Linked to Git: <code class="mono">{{ prompt?.gitPath }}</code>
    </div>

    <div class="form-layout">
      <SectionCard title="Configuration" padded>
        <div class="form-grid">
          <TextInput
            v-model="key"
            label="Key"
            mono
            :disabled="!isNew" />
          <TextInput v-model="name" label="Name" />
          <TextInput v-model="inputType" label="Input Type" />
          <TextInput v-model="outputType" label="Output Type" />
        </div>
        <Textarea
          v-model="description"
          label="Description"
          :rows="2"
          class="description-field" />
      </SectionCard>

      <SectionCard title="System Prompt" padded>
        <Textarea
          v-model="systemPrompt"
          :rows="10"
          mono
          placeholder="System prompt text…" />
      </SectionCard>

      <SectionCard title="User Prompt" padded>
        <Textarea
          v-model="userPrompt"
          :rows="8"
          mono
          placeholder="User prompt template…" />
      </SectionCard>

      <SectionCard title="Schema (JSON)" padded>
        <CodeEditor v-model="schema" language="json" :rows="10" />
      </SectionCard>
    </div>
    <LinkToGitModal
      v-if="showLinkModal && prompt"
      entity-type="PROMPT"
      :entity-id="prompt.id"
      :entity-key="prompt.key"
      :accent="accent"
      @close="showLinkModal = false"
      @linked="onLinked" />
  </PageShell>
</template>

<style scoped>
.form-layout { display: flex; flex-direction: column; gap: 14px; max-width: 900px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.description-field { margin-top: 14px; }
.sync-error-banner {
  background: color-mix(in oklch, var(--err) 12%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--err) 30%, transparent);
  color: var(--fg-1);
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 13px;
  max-width: 900px;
}
.git-linked-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  background: color-mix(in oklch, var(--brand-2) 6%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
  font-size: 12.5px;
  color: var(--fg-2);
  max-width: 900px;
}
.git-linked-banner code { color: var(--fg-1); }
</style>
