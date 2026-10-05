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

const agentId = computed(() => route.params.id as string)
const isNew = computed(() => agentId.value === 'new')

const agentGql = gql`
  query GetAgent($id: UUID!) {
    ai {
      agents {
        agent(id: $id) { id key name description modelId promptId configuration subAgents { id name } tools { id name } gitRepositoryId gitPath lastSyncError }
        all { id key name }
      }
      models { all { id key name } }
      prompts { all { id key name } }
      agentTools { all { id key name } }
    }
  }
`

const optionsGql = gql`
  query GetAgentOptions {
    ai {
      agents { all { id key name } }
      models { all { id key name } }
      prompts { all { id key name } }
      agentTools { all { id key name } }
    }
  }
`

const addGql = gql`
  mutation AddAgent($agent: AgentInput!) { ai { agents { add(agent: $agent) { id } } } }
`
const editGql = gql`
  mutation EditAgent($id: UUID!, $agent: AgentInput!, $authorName: String, $authorEmail: String) {
    ai { agents { edit(id: $id, agent: $agent, authorName: $authorName, authorEmail: $authorEmail) { id } } }
  }
`
const setToolsGql = gql`
  mutation SetAgentTools($agentId: UUID!, $toolIds: [UUID!]!, $authorName: String, $authorEmail: String) {
    ai { agents { setTools(agentId: $agentId, toolIds: $toolIds, authorName: $authorName, authorEmail: $authorEmail) }
    }
  }
`
const setSubAgentsGql = gql`
  mutation SetSubAgents($agentId: UUID!, $subAgentIds: [UUID!]!, $authorName: String, $authorEmail: String) {
    ai { agents { setSubAgents(agentId: $agentId, subAgentIds: $subAgentIds, authorName: $authorName, authorEmail: $authorEmail) }
    }
  }
`

interface AiNamedItem { id: string; key: string; name: string }

const { data, refresh } = useAsyncQuery<{ ai: { agents: { agent?: { id: string; key: string; name: string; description: string | null; modelId: string | null; promptId: string | null; configuration: unknown; subAgents: AiNamedItem[]; tools: AiNamedItem[]; gitRepositoryId: string | null; gitPath: string | null; lastSyncError: string | null }; all?: AiNamedItem[] }; models: { all: AiNamedItem[] }; prompts: { all: AiNamedItem[] }; agentTools: { all: AiNamedItem[] } } }>(
  'ai-agent-detail', isNew.value ? optionsGql : agentGql,
  isNew.value ? {} : { id: agentId },
)

const agent = computed(() => isNew.value ? null : data.value?.ai?.agents?.agent)
const hasSidebar = computed(() => !isNew.value && !!agent.value)
const syncError = computed(() => agent.value?.lastSyncError ?? null)
const gitLinked = computed(() => !!(agent.value?.gitRepositoryId && agent.value?.gitPath))
const showLinkModal = ref(false)
function onLinked() { showLinkModal.value = false; refresh() }
const models = computed(() => (data.value?.ai?.models?.all ?? []).map((m: AiNamedItem) => ({ value: m.id, label: m.name || m.key })))
const prompts = computed(() => (data.value?.ai?.prompts?.all ?? []).map((p: AiNamedItem) => ({ value: p.id, label: p.name || p.key })))
const allTools = computed(() => data.value?.ai?.agentTools?.all ?? [])
const allAgents = computed(() => data.value?.ai?.agents?.all ?? [])
// Single-select pickers offer only items not already attached, so the dropdown
// stays short and we don't need to detect "added already" inside the handler.
const availableTools = computed(() => {
  const attached = new Set(toolIds.value)
  return allTools.value
    .filter((t: AiNamedItem) => !attached.has(t.id))
    .map((t: AiNamedItem) => ({ value: t.id, label: t.name || t.key }))
})
const availableSubAgents = computed(() => {
  const attached = new Set(subAgentIds.value)
  const selfId = agent.value?.id
  return allAgents.value
    .filter((a: AiNamedItem) => a.id !== selfId && !attached.has(a.id))
    .map((a: AiNamedItem) => ({ value: a.id, label: a.name || a.key }))
})
const toolPickerValue = ref('')
const subAgentPickerValue = ref('')
const key = ref('')
const name = ref('')
const description = ref('')
const modelId = ref('')
const promptId = ref('')
const configuration = ref('{}')
const toolIds = ref<string[]>([])
const subAgentIds = ref<string[]>([])
const savingTools = ref(false)
const savingSubAgents = ref(false)
const saving = ref(false)

watch(agent, (a) => {
  if (a) {
    key.value = a.key; name.value = a.name; description.value = a.description ?? ''
    modelId.value = a.modelId ?? ''; promptId.value = a.promptId ?? ''
    configuration.value = a.configuration ? JSON.stringify(a.configuration, null, 2) : '{}'
    toolIds.value = a.tools.map(t => t.id)
    subAgentIds.value = a.subAgents.map(s => s.id)
  }
}, { immediate: true })

async function commitToolIds(ids: string[]) {
  if (!agent.value) return
  const previous = toolIds.value
  toolIds.value = ids
  savingTools.value = true
  try {
    const vars: { agentId: string; toolIds: string[]; authorName?: string; authorEmail?: string } = {
      agentId: agent.value.id, toolIds: ids,
    }
    if (gitLinked.value) {
      vars.authorName = commitAuthor.value.authorName
      vars.authorEmail = commitAuthor.value.authorEmail
    }
    await gqlMutation(setToolsGql, vars)
    await refresh()
  } catch {
    toast.error('Failed to update tools')
    toolIds.value = previous
    await refresh()
  } finally {
    savingTools.value = false
  }
}

async function onAddTool(next: string | string[] | null | undefined) {
  const id = Array.isArray(next) ? next[0] : next
  if (!id || toolIds.value.includes(id)) return
  toolPickerValue.value = ''
  await commitToolIds([...toolIds.value, id])
}

async function onRemoveTool(id: string) {
  await commitToolIds(toolIds.value.filter(t => t !== id))
}

async function commitSubAgentIds(ids: string[]) {
  if (!agent.value) return
  const previous = subAgentIds.value
  subAgentIds.value = ids
  savingSubAgents.value = true
  try {
    const vars: { agentId: string; subAgentIds: string[]; authorName?: string; authorEmail?: string } = {
      agentId: agent.value.id, subAgentIds: ids,
    }
    if (gitLinked.value) {
      vars.authorName = commitAuthor.value.authorName
      vars.authorEmail = commitAuthor.value.authorEmail
    }
    await gqlMutation(setSubAgentsGql, vars)
    await refresh()
  } catch {
    toast.error('Failed to update sub-agents')
    subAgentIds.value = previous
    await refresh()
  } finally {
    savingSubAgents.value = false
  }
}

async function onAddSubAgent(next: string | string[] | null | undefined) {
  const id = Array.isArray(next) ? next[0] : next
  if (!id || subAgentIds.value.includes(id)) return
  subAgentPickerValue.value = ''
  await commitSubAgentIds([...subAgentIds.value, id])
}

async function onRemoveSubAgent(id: string) {
  await commitSubAgentIds(subAgentIds.value.filter(s => s !== id))
}

async function onSave() {
  saving.value = true
  try {
    const input = {
      key: key.value, name: name.value, description: description.value,
      modelId: modelId.value || null, promptId: promptId.value || null,
      configuration: JSON.parse(configuration.value),
    }
    if (isNew.value) {
      const result = await gqlMutation<{ ai: { agents: { add: { id: string } } } }>(addGql, { agent: input })
      toast.success('Agent created')
      router.replace(`/ai/agents/${result.ai.agents.add.id}`)
    } else {
      const vars: { id: string; agent: typeof input; authorName?: string; authorEmail?: string } = {
        id: agentId.value, agent: input,
      }
      if (gitLinked.value) {
        vars.authorName = commitAuthor.value.authorName
        vars.authorEmail = commitAuthor.value.authorEmail
      }
      await gqlMutation(editGql, vars)
      toast.success('Agent saved')
      refresh()
    }
  } catch {
    toast.error('Failed to save')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader :accent="accent" :breadcrumb="buildBreadcrumb('AI', 'Agents', isNew ? 'New' : name || '…')" :title="isNew ? 'New Agent' : name || 'Loading…'">
        <template #actions>
          <Button
            v-if="!isNew && !gitLinked && agent"
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
            @click="onSave">
            {{ saving ? 'Saving…' : 'Save' }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="syncError" class="sync-error-banner">
      <strong>Git sync error:</strong> {{ syncError }}
    </div>
    <div v-else-if="gitLinked" class="git-linked-banner">
      <Icon name="git" :size="12" :color="accent" />
      Linked to Git: <code class="mono">{{ agent?.gitPath }}</code>
    </div>

    <div class="detail-layout" :class="{ 'detail-layout--with-sidebar': hasSidebar }">
      <div class="main-content">
        <SectionCard title="Configuration" padded>
          <div class="form-grid">
            <TextInput
              v-model="key"
              label="Key"
              placeholder="agent-key"
              mono
              :disabled="!isNew" />
            <TextInput v-model="name" label="Name" placeholder="Agent name" />
          </div>
          <Textarea
            v-model="description"
            label="Description"
            :rows="2"
            placeholder="What does this agent do?"
            class="description-field" />
        </SectionCard>

        <SectionCard title="Model & Prompt" padded>
          <div class="form-grid">
            <Select
              v-model="modelId"
              :options="models"
              placeholder="Select model…"
              label="Model" />
            <Select
              v-model="promptId"
              :options="prompts"
              placeholder="Select prompt…"
              label="Prompt" />
          </div>
        </SectionCard>

        <SectionCard title="Configuration (JSON)" padded>
          <CodeEditor v-model="configuration" language="json" :rows="12" />
        </SectionCard>
      </div>

      <div v-if="hasSidebar" class="sidebar">
        <SectionCard v-if="agent" title="Sub-Agents" padded>
          <div v-if="agent.subAgents.length" class="link-list">
            <div
              v-for="sa in agent.subAgents"
              :key="sa.id"
              class="link-item"
              @click="router.push(`/ai/agents/${sa.id}`)">
              <Icon name="wand" :size="12" :color="accent" />
              <span class="link-item-label">{{ sa.name }}</span>
              <button
                class="link-item-remove"
                :disabled="savingSubAgents"
                :aria-label="`Remove ${sa.name}`"
                @click.stop="onRemoveSubAgent(sa.id)">
                <Icon name="x" :size="11" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <p v-else class="sidebar-empty">No sub-agents attached.</p>
          <Select
            v-if="availableSubAgents.length"
            v-model="subAgentPickerValue"
            :options="availableSubAgents"
            searchable
            :accent="accent"
            :loading="savingSubAgents"
            placeholder="Add a sub-agent…"
            class="add-picker"
            @update:model-value="onAddSubAgent" />
        </SectionCard>
        <SectionCard v-if="agent" title="Tools" padded>
          <div v-if="agent.tools.length" class="link-list">
            <div
              v-for="t in agent.tools"
              :key="t.id"
              class="link-item"
              @click="router.push(`/ai/agent-tools/${t.id}`)">
              <Icon name="gear" :size="12" color="var(--fg-3)" />
              <span class="link-item-label">{{ t.name }}</span>
              <button
                class="link-item-remove"
                :disabled="savingTools"
                :aria-label="`Remove ${t.name}`"
                @click.stop="onRemoveTool(t.id)">
                <Icon name="x" :size="11" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <p v-else class="sidebar-empty">No tools attached.</p>
          <Select
            v-if="availableTools.length"
            v-model="toolPickerValue"
            :options="availableTools"
            searchable
            :accent="accent"
            :loading="savingTools"
            placeholder="Add a tool…"
            class="add-picker"
            @update:model-value="onAddTool" />
        </SectionCard>
      </div>
    </div>
    <LinkToGitModal
      v-if="showLinkModal && agent"
      entity-type="AGENT"
      :entity-id="agent.id"
      :entity-key="agent.key"
      :accent="accent"
      @close="showLinkModal = false"
      @linked="onLinked" />
  </PageShell>
</template>

<style scoped>
.detail-layout { display: flex; flex-direction: column; gap: 14px; max-width: 900px; }
.detail-layout--with-sidebar { display: grid; grid-template-columns: 1fr 280px; gap: 18px; align-items: start; max-width: none; }
.main-content { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.sidebar { display: flex; flex-direction: column; gap: 14px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.description-field { margin-top: 14px; }
.link-list { display: flex; flex-direction: column; gap: 4px; }
.link-item { display: flex; align-items: center; gap: 6px; padding: 4px 8px; border-radius: var(--r-sm); font-size: 12.5px; color: var(--fg-1); cursor: pointer; transition: background 0.15s; min-width: 0; }
.link-item:hover { background: color-mix(in oklch, var(--brand-2) 6%, transparent); }
.link-item-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.link-item-remove { display: flex; align-items: center; justify-content: center; width: 20px; height: 20px; padding: 0; background: none; border: 0; border-radius: var(--r-sm); cursor: pointer; opacity: 0.55; transition: opacity 0.12s, background 0.12s; }
.link-item:hover .link-item-remove { opacity: 1; }
.link-item-remove:hover { background: color-mix(in oklch, var(--err) 12%, transparent); opacity: 1; }
.link-item-remove:disabled { cursor: not-allowed; opacity: 0.3; }
.sidebar-empty { font-size: 12px; color: var(--fg-3); margin: 0 0 8px; padding: 0 2px; }
.add-picker { margin-top: 8px; }
.sync-error-banner {
  background: color-mix(in oklch, var(--err) 12%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--err) 30%, transparent);
  color: var(--fg-1);
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 13px;
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
}
.git-linked-banner code { color: var(--fg-1); }
</style>
