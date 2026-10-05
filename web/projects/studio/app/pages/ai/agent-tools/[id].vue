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

const toolId = computed(() => route.params.id as string)
const isNew = computed(() => toolId.value === 'new')

const optionsSelection = `
  models { all { id key name } }
  prompts { all { id key name } }
  agents { all { id key name } }
  mcp { servers { all { id key name } } }
`
const toolGql = gql(`query GetAgentTool($id: UUID!) {
  ai {
    agentTools { tool(id: $id) {
      id key name description configuration
      scriptId mcpServerId graphqlOperation graphqlInputTransform graphqlOutputTransform promptId modelId agentId
      gitRepositoryId gitPath lastSyncError
    } }
    ${optionsSelection}
  }
  scripts { all { id key name } }
}`)
const optionsGql = gql(`query GetAgentToolOptions { ai { ${optionsSelection} } scripts { all { id key name } } }`)

const addGql = gql`mutation AddAgentTool($tool: AgentToolInput!) { ai { agentTools { add(tool: $tool) { id } } } }`
const editGql = gql`
  mutation EditAgentTool($id: UUID!, $tool: AgentToolInput!, $authorName: String, $authorEmail: String) {
    ai { agentTools { edit(id: $id, tool: $tool, authorName: $authorName, authorEmail: $authorEmail) { id } } }
  }
`

interface AiNamedItem { id: string; key: string; name: string }
interface AiTool {
  id: string; key: string; name: string; description: string | null; configuration: unknown
  scriptId: string | null; mcpServerId: string | null
  graphqlOperation: string | null; graphqlInputTransform: string | null; graphqlOutputTransform: string | null
  promptId: string | null; modelId: string | null; agentId: string | null
  gitRepositoryId: string | null; gitPath: string | null; lastSyncError: string | null
}

const { data, refresh } = useAsyncQuery<{ ai: { agentTools: { tool: AiTool | null }; models: { all: AiNamedItem[] }; prompts: { all: AiNamedItem[] }; agents: { all: AiNamedItem[] }; mcp: { servers: { all: AiNamedItem[] } } }; scripts: { all: AiNamedItem[] } }>(
  'ai-agent-tool-detail', isNew.value ? optionsGql : toolGql, isNew.value ? {} : { id: toolId },
)

const tool = computed(() => isNew.value ? null : data.value?.ai?.agentTools?.tool)
const syncError = computed(() => tool.value?.lastSyncError ?? null)
const gitLinked = computed(() => !!(tool.value?.gitRepositoryId && tool.value?.gitPath))
const showLinkModal = ref(false)
function onLinked() { showLinkModal.value = false; refresh() }

const toOptions = (items: AiNamedItem[] | undefined) => (items ?? []).map(i => ({ value: i.id, label: i.name || i.key }))
const modelOptions = computed(() => toOptions(data.value?.ai?.models?.all))
const promptOptions = computed(() => toOptions(data.value?.ai?.prompts?.all))
const agentOptions = computed(() => toOptions(data.value?.ai?.agents?.all))
const mcpServerOptions = computed(() => toOptions(data.value?.ai?.mcp?.servers?.all))
const scriptOptions = computed(() => toOptions(data.value?.scripts?.all))

const IMPL_TYPES = [
  { value: 'code', label: 'Code-backed (registered primitive matching the key)' },
  { value: 'graphqlOperation', label: 'GraphQL operation' },
  { value: 'script', label: 'Script' },
  { value: 'promptModel', label: 'Prompt + Model' },
  { value: 'agent', label: 'Agent' },
  { value: 'mcpServer', label: 'External MCP server' },
]

const key = ref('')
const name = ref('')
const description = ref('')
const configuration = ref('{}')
const implType = ref('code')
const scriptId = ref('')
const mcpServerId = ref('')
const graphqlOperation = ref('')
const graphqlInputTransform = ref('')
const graphqlOutputTransform = ref('')
const promptId = ref('')
const modelId = ref('')
const agentId = ref('')
const saving = ref(false)

function deriveImplType(t: AiTool): string {
  if (t.scriptId) return 'script'
  if (t.mcpServerId) return 'mcpServer'
  if (t.graphqlOperation) return 'graphqlOperation'
  if (t.promptId || t.modelId) return 'promptModel'
  if (t.agentId) return 'agent'
  return 'code'
}

watch(tool, (t) => {
  if (!t) return
  key.value = t.key; name.value = t.name; description.value = t.description ?? ''
  configuration.value = t.configuration ? JSON.stringify(t.configuration, null, 2) : '{}'
  scriptId.value = t.scriptId ?? ''
  mcpServerId.value = t.mcpServerId ?? ''
  graphqlOperation.value = t.graphqlOperation ?? ''
  graphqlInputTransform.value = t.graphqlInputTransform ?? ''
  graphqlOutputTransform.value = t.graphqlOutputTransform ?? ''
  promptId.value = t.promptId ?? ''
  modelId.value = t.modelId ?? ''
  agentId.value = t.agentId ?? ''
  implType.value = deriveImplType(t)
}, { immediate: true })

async function onSave() {
  saving.value = true
  try {
    const input: Record<string, unknown> = {
      key: key.value, name: name.value, description: description.value,
      configuration: JSON.parse(configuration.value || '{}'),
    }
    // Set exactly the selected implementation variant (or none, for code-backed);
    // the backend enforces at-most-one.
    switch (implType.value) {
      case 'code': break
      case 'graphqlOperation':
        input.graphqlOperation = graphqlOperation.value || null
        input.graphqlInputTransform = graphqlInputTransform.value || null
        input.graphqlOutputTransform = graphqlOutputTransform.value || null
        break
      case 'script': input.scriptId = scriptId.value || null; break
      case 'promptModel':
        input.promptId = promptId.value || null
        input.modelId = modelId.value || null
        break
      case 'agent': input.agentId = agentId.value || null; break
      case 'mcpServer': input.mcpServerId = mcpServerId.value || null; break
    }
    if (isNew.value) {
      const r = await gqlMutation<{ ai: { agentTools: { add: { id: string } } } }>(addGql, { tool: input })
      toast.success('Created')
      router.replace(`/ai/agent-tools/${r.ai.agentTools.add.id}`)
    } else {
      const vars: { id: string; tool: typeof input; authorName?: string; authorEmail?: string } = { id: toolId.value, tool: input }
      if (gitLinked.value) {
        vars.authorName = commitAuthor.value.authorName
        vars.authorEmail = commitAuthor.value.authorEmail
      }
      await gqlMutation(editGql, vars)
      toast.success('Saved')
      refresh()
    }
  } catch {
    toast.error('Failed')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader :accent="accent" :breadcrumb="buildBreadcrumb('AI', 'Agent Tools', isNew ? 'New' : name || '…')" :title="isNew ? 'New Agent Tool' : name || 'Loading…'">
        <template #actions>
          <Button
            v-if="!isNew && !gitLinked && tool"
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
            :disabled="saving || !key.trim()"
            @click="onSave">{{ saving ? 'Saving…' : 'Save' }}</Button>
        </template>
      </PageHeader>
    </template>
    <div v-if="syncError" class="sync-error-banner">
      <strong>Git sync error:</strong> {{ syncError }}
    </div>
    <div v-else-if="gitLinked" class="git-linked-banner">
      <Icon name="git" :size="12" :color="accent" />
      Linked to Git: <code class="mono">{{ tool?.gitPath }}</code>
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
        </div>
        <Textarea
          v-model="description"
          label="Description"
          :rows="2"
          class="description-field" />
      </SectionCard>

      <SectionCard title="Implementation" padded>
        <Select
          v-model="implType"
          :options="IMPL_TYPES"
          label="Type"
          :accent="accent" />
        <div class="impl-fields">
          <p v-if="implType === 'code'" class="impl-note">
            Resolved at call time to the registered code primitive whose name equals this tool's key
            (<code class="mono">{{ key || '…' }}</code>).
          </p>

          <div v-else-if="implType === 'graphqlOperation'" class="graphql-fields">
            <CodeEditor v-model="graphqlOperation" language="graphql" :rows="8" />
            <TextInput v-model="graphqlInputTransform" label="Input transform (optional, JSONPath-style)" mono />
            <TextInput v-model="graphqlOutputTransform" label="Output transform (optional, JSONPath-style)" mono />
          </div>

          <Select
            v-else-if="implType === 'script'"
            v-model="scriptId"
            :options="scriptOptions"
            label="Script"
            placeholder="Select a script…"
            :accent="accent"
            searchable />

          <div v-else-if="implType === 'promptModel'" class="form-grid">
            <Select
              v-model="promptId"
              :options="promptOptions"
              label="Prompt"
              placeholder="Select a prompt…"
              :accent="accent"
              searchable />
            <Select
              v-model="modelId"
              :options="modelOptions"
              label="Model"
              placeholder="Select a model…"
              :accent="accent"
              searchable />
          </div>

          <Select
            v-else-if="implType === 'agent'"
            v-model="agentId"
            :options="agentOptions"
            label="Agent"
            placeholder="Select an agent…"
            :accent="accent"
            searchable />

          <Select
            v-else-if="implType === 'mcpServer'"
            v-model="mcpServerId"
            :options="mcpServerOptions"
            label="External MCP server"
            placeholder="Select a server…"
            :accent="accent"
            searchable />
        </div>
      </SectionCard>

      <SectionCard title="Tool Configuration (JSON)" padded>
        <CodeEditor v-model="configuration" language="json" :rows="10" />
      </SectionCard>
    </div>
    <LinkToGitModal
      v-if="showLinkModal && tool"
      entity-type="AGENT_TOOL"
      :entity-id="tool.id"
      :entity-key="tool.key"
      :accent="accent"
      @close="showLinkModal = false"
      @linked="onLinked" />
  </PageShell>
</template>

<style scoped>
.form-layout { display: flex; flex-direction: column; gap: 14px; max-width: 900px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.description-field { margin-top: 14px; }
.impl-fields { margin-top: 14px; }
.graphql-fields { display: flex; flex-direction: column; gap: 12px; }
.impl-note { font-size: 12.5px; color: var(--fg-2); margin: 0; line-height: 1.5; }
.impl-note code { color: var(--fg-1); }
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
