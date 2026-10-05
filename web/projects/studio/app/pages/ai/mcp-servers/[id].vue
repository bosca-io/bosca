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

const serverId = computed(() => route.params.id as string)
const isNew = computed(() => serverId.value === 'new')

const serverGql = gql`
  query GetMcpServer($id: UUID!) {
    ai { mcp { servers { server(id: $id) { id key name description transportType enabled configuration gitRepositoryId gitPath lastSyncError } } } }
  }
`
const addGql = gql`
  mutation AddMcpServer($server: McpServerRegistrationInput!) { ai { mcp { servers { add(server: $server) { id } } } } }
`
const editGql = gql`
  mutation EditMcpServer($id: UUID!, $server: McpServerRegistrationInput!, $authorName: String, $authorEmail: String) {
    ai { mcp { servers { edit(id: $id, server: $server, authorName: $authorName, authorEmail: $authorEmail) { id } } } }
  }
`

interface McpServer { id: string; key: string; name: string; description: string | null; transportType: string; enabled: boolean; configuration: unknown; gitRepositoryId: string | null; gitPath: string | null; lastSyncError: string | null }
const { data, refresh } = useAsyncQuery<{ ai: { mcp: { servers: { server: McpServer | null } } } }>(
  'ai-mcp-server-detail', serverGql,
  isNew.value ? { id: '00000000-0000-0000-0000-000000000000' } : { id: serverId },
)

const server = computed(() => isNew.value ? null : data.value?.ai?.mcp?.servers?.server)
const syncError = computed(() => server.value?.lastSyncError ?? null)
const gitLinked = computed(() => !!(server.value?.gitRepositoryId && server.value?.gitPath))
const showLinkModal = ref(false)
function onLinked() { showLinkModal.value = false; refresh() }

const key = ref('')
const name = ref('')
const description = ref('')
const transportType = ref('STREAMABLE_HTTP')
const enabled = ref(true)
const configuration = ref('{}')
const saving = ref(false)

watch(server, (s) => {
  if (s) {
    key.value = s.key; name.value = s.name; description.value = s.description ?? ''
    transportType.value = s.transportType; enabled.value = s.enabled
    configuration.value = s.configuration ? JSON.stringify(s.configuration, null, 2) : '{}'
  }
}, { immediate: true })

async function onSave() {
  saving.value = true
  try {
    const input = {
      key: key.value, name: name.value, description: description.value,
      transportType: transportType.value, enabled: enabled.value,
      configuration: JSON.parse(configuration.value),
    }
    if (isNew.value) {
      const result = await gqlMutation<{ ai: { mcp: { servers: { add: { id: string } } } } }>(addGql, { server: input })
      toast.success('Server created')
      router.replace(`/ai/mcp-servers/${result.ai.mcp.servers.add.id}`)
    } else {
      const vars: { id: string; server: typeof input; authorName?: string; authorEmail?: string } = {
        id: serverId.value, server: input,
      }
      if (gitLinked.value) {
        vars.authorName = commitAuthor.value.authorName
        vars.authorEmail = commitAuthor.value.authorEmail
      }
      await gqlMutation(editGql, vars)
      toast.success('Server saved')
      refresh()
    }
  } catch { toast.error('Failed to save') }
  finally { saving.value = false }
}

const TRANSPORT_OPTIONS = [
  { value: 'SSE', label: 'SSE' },
  { value: 'STDIO', label: 'STDIO' },
  { value: 'STREAMABLE_HTTP', label: 'Streamable HTTP' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader :accent="accent" :breadcrumb="buildBreadcrumb('AI', 'MCP Servers', isNew ? 'New' : name || '…')" :title="isNew ? 'New MCP Server' : name || 'Loading…'">
        <template #actions>
          <Button
            v-if="!isNew && !gitLinked && server"
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
      Linked to Git: <code class="mono">{{ server?.gitPath }}</code>
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
          <Select v-model="transportType" :options="TRANSPORT_OPTIONS" label="Transport Type" />
          <div class="switch-cell">
            <Switch v-model="enabled" label="Enabled" :accent="accent" />
          </div>
        </div>
        <Textarea
          v-model="description"
          label="Description"
          :rows="2"
          class="description-field" />
      </SectionCard>

      <SectionCard title="Server Configuration (JSON)" padded>
        <CodeEditor v-model="configuration" language="json" :rows="14" />
      </SectionCard>
    </div>
    <LinkToGitModal
      v-if="showLinkModal && server"
      entity-type="MCP_SERVER"
      :entity-id="server.id"
      :entity-key="server.key"
      :accent="accent"
      @close="showLinkModal = false"
      @linked="onLinked" />
  </PageShell>
</template>

<style scoped>
.form-layout { display: flex; flex-direction: column; gap: 14px; max-width: 900px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.switch-cell { display: flex; align-items: flex-end; padding-bottom: 2px; }
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
