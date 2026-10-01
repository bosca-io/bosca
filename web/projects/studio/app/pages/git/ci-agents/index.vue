<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const statusFilter = ref<string>('')
const showRegister = ref(false)
const registrationToken = ref<string | null>(null)
const saving = ref(false)
const error = ref('')

const form = reactive({
  name: '',
  labels: '',
  mode: 'RUNNER' as string,
})

const agentsGql = gql`
  query PipelineAgents($status: GitAgentStatus) {
    git {
      pipelineAgents(status: $status) {
        id name labels mode status ephemeral
        jobId parentAgentId instanceId
        lastHeartbeat expiresAt created
      }
    }
  }
`

const registerGql = gql`
  mutation RegisterAgent($name: String!, $labels: [String!]!, $mode: GitAgentMode!) {
    git { registerAgent(name: $name, labels: $labels, mode: $mode) { token } }
  }
`

const deregisterGql = gql`
  mutation DeregisterAgent($id: UUID!) {
    git { deregisterAgent(id: $id) }
  }
`

interface PipelineAgent {
  id: string; name: string; labels: string[]; mode: string; status: string
  ephemeral: boolean; jobId: string | null; parentAgentId: string | null
  instanceId: string | null; lastHeartbeat: string | null; expiresAt: string | null
  created: string
}

const { data, status, refresh } = useAsyncData('pipeline-agents', () => {
  const vars: Record<string, unknown> = {}
  if (statusFilter.value) vars.status = statusFilter.value
  return gqlQuery<{ git: { pipelineAgents: PipelineAgent[] } }>(agentsGql, vars)
}, { watch: [statusFilter] })

const agents = computed(() => data.value?.git?.pipelineAgents ?? [])
const isLoading = computed(() => status.value === 'pending')

const onlineCount = computed(() => agents.value.filter(a => a.status === 'ONLINE').length)
const busyCount = computed(() => agents.value.filter(a => a.status === 'BUSY').length)

const STATUS_OPTIONS = [
  { value: '', label: 'All' },
  { value: 'ONLINE', label: 'Online' },
  { value: 'BUSY', label: 'Busy' },
  { value: 'OFFLINE', label: 'Offline' },
  { value: 'DRAINING', label: 'Draining' },
]

const MODE_OPTIONS = [
  { value: 'RUNNER', label: 'Runner' },
  { value: 'ORCHESTRATOR', label: 'Orchestrator' },
]

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Agent', width: 'minmax(180px, 2fr)' },
  { key: 'mode', label: 'Mode', width: '120px' },
  { key: 'labels', label: 'Labels', width: '1fr', muted: true },
  { key: 'status', label: 'Status', width: '100px' },
  { key: 'lastHeartbeat', label: 'Last Heartbeat', width: '140px', muted: true },
]

function statusColor(s: string): string {
  if (s === 'ONLINE') return 'var(--ok)'
  if (s === 'BUSY') return 'var(--brand-2)'
  if (s === 'DRAINING') return 'var(--warn)'
  return 'var(--fg-4)'
}

function statusBadgeBg(s: string): string {
  return `color-mix(in oklch, ${statusColor(s)} 16%, transparent)`
}

function modeIcon(m: string): string {
  return m === 'ORCHESTRATOR' ? 'workflow' : 'gear'
}

function heartbeatAge(d: string | null): string {
  if (!d) return 'never'
  const sec = Math.floor((Date.now() - new Date(d).getTime()) / 1000)
  if (sec < 60) return `${sec}s ago`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ago`
  return `${Math.floor(sec / 3600)}h ago`
}

function getRowActions(_row: PipelineAgent): OverflowMenuItem[] {
  return [
    { id: 'copy', label: 'Copy ID', icon: 'copy' },
    { id: 'sep', label: '', separator: true },
    { id: 'deregister', label: 'Deregister', icon: 'trash', danger: true },
  ]
}

async function onRowAction(action: string, row: PipelineAgent) {
  if (action === 'copy') { navigator.clipboard.writeText(row.id); toast.success('ID copied') }
  else if (action === 'deregister') {
    try {
      await gqlMutation(deregisterGql, { id: row.id })
      toast.success(`Agent '${row.name}' deregistered`)
      refresh()
    } catch { toast.error('Failed to deregister') }
  }
}

function resetForm() {
  form.name = ''; form.labels = ''; form.mode = 'RUNNER'
  error.value = ''; registrationToken.value = null
}

async function handleRegister() {
  if (!form.name.trim()) { error.value = 'Name is required.'; return }
  saving.value = true; error.value = ''
  try {
    const labels = form.labels.split(',').map(l => l.trim()).filter(Boolean)
    const result = await gqlMutation<{
      git: { registerAgent: { token: string } }
    }>(registerGql, { name: form.name, labels, mode: form.mode })
    registrationToken.value = result.git.registerAgent.token
    toast.success('Agent registered')
    refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to register agent'
  } finally { saving.value = false }
}

function copyToken() {
  if (registrationToken.value) {
    navigator.clipboard.writeText(registrationToken.value)
    toast.success('Token copied')
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', 'CI Agents')"
        title="CI Agents"
        :subtitle="`${agents.length} agents · ${onlineCount} online · ${busyCount} busy`"
      >
        <template #actions>
          <Button size="sm" icon="pulse" @click="refresh()">Refresh</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showRegister = true; resetForm()">
            Register Agent
          </Button>
        </template>
      </PageHeader>
    </template>

    <div class="filter-bar">
      <Select
        v-model="statusFilter"
        :options="STATUS_OPTIONS"
        placeholder="Status"
        size="sm"
        :accent="accent" />
      <span style="flex: 1" />
      <span class="mono count-label">{{ agents.length }} agents</span>
    </div>

    <SectionCard title="Build Agents" subtitle="Registered runners and orchestrators that execute pipeline jobs">
      <GlassTable
        :columns="columns"
        :rows="agents"
        :loading="isLoading && agents.length === 0"
        empty-text="No CI agents registered."
        :row-actions="getRowActions"
        arrow
        @row-click="(row: any) => router.push(`/git/ci-agents/${row.id}`)"
        @row-action="({ action, row }) => onRowAction(action, row as PipelineAgent)"
      >
        <template #col-name="{ row }">
          <div class="agent-name-cell">
            <div class="agent-icon" :style="{ background: statusBadgeBg(row.status) }">
              <Icon :name="modeIcon(row.mode)" :size="14" :color="statusColor(row.status)" />
            </div>
            <div>
              <span class="agent-name">{{ row.name }}</span>
              <span v-if="row.ephemeral" class="ephemeral-badge">ephemeral</span>
              <div v-if="row.instanceId" class="instance-id mono">{{ row.instanceId }}</div>
            </div>
          </div>
        </template>
        <template #col-mode="{ row }">
          <span class="mono" style="font-size: 11px">{{ row.mode }}</span>
        </template>
        <template #col-labels="{ row }">
          <div class="label-list">
            <span v-for="l in row.labels" :key="l" class="label-chip">{{ l }}</span>
            <span v-if="!row.labels.length" class="fg-3">—</span>
          </div>
        </template>
        <template #col-status="{ row }">
          <span class="status-badge" :style="{ background: statusBadgeBg(row.status), color: statusColor(row.status) }">
            {{ row.status }}
          </span>
        </template>
        <template #col-lastHeartbeat="{ row }">
          <span class="mono" style="font-size: 11px">{{ heartbeatAge(row.lastHeartbeat) }}</span>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- Register Modal -->
    <Modal
      v-if="showRegister"
      title="Register CI Agent"
      icon="gear"
      :accent="accent"
      @close="showRegister = false">
      <div v-if="registrationToken" class="token-result">
        <p class="token-label">Agent registered. Copy the token below — it will not be shown again.</p>
        <div class="token-box">
          <code class="token-value">{{ registrationToken }}</code>
          <button class="copy-btn" @click="copyToken">
            <Icon name="copy" :size="14" color="var(--fg-2)" />
          </button>
        </div>
      </div>
      <div v-else class="form-stack">
        <TextInput v-model="form.name" label="Agent Name" placeholder="build-runner-01" />
        <TextInput
          v-model="form.labels"
          label="Labels"
          placeholder="linux, x64, docker"
          mono />
        <p class="field-hint">Comma-separated labels for job routing.</p>
        <Select
          v-model="form.mode"
          :options="MODE_OPTIONS"
          label="Mode"
          :accent="accent" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showRegister = false">{{ registrationToken ? 'Done' : 'Cancel' }}</Button>
        <Button
          v-if="!registrationToken"
          primary
          :accent="accent"
          :disabled="saving"
          @click="handleRegister">
          {{ saving ? 'Registering…' : 'Register' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.filter-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }
.count-label { font-size: 11px; color: var(--fg-3); }

.agent-name-cell { display: flex; align-items: center; gap: 10px; }
.agent-icon {
  width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.agent-name { font-weight: 500; color: var(--fg-0); }
.ephemeral-badge {
  font-size: 10px; padding: 1px 5px; background: var(--bg-3);
  border-radius: 3px; color: var(--fg-3); font-weight: 600; margin-left: 6px;
}
.instance-id { font-size: 10.5px; color: var(--fg-3); margin-top: 1px; }

.label-list { display: flex; flex-wrap: wrap; gap: 4px; }
.label-chip {
  font-size: 10.5px; padding: 2px 7px; background: var(--bg-3);
  border-radius: 4px; color: var(--fg-2); font-family: var(--font-mono);
}

.status-badge {
  display: inline-block; font-size: 10.5px; padding: 3px 9px; border-radius: 8px;
  font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; text-align: center;
}

.fg-3 { color: var(--fg-3); }

.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }
.field-hint { font-size: 11px; color: var(--fg-3); margin: -8px 0 0; }

.token-result { display: flex; flex-direction: column; gap: 12px; }
.token-label { font-size: 13px; color: var(--fg-1); margin: 0; }
.token-box {
  display: flex; align-items: center; gap: 8px;
  background: var(--bg-2); border: 1px solid var(--line); border-radius: 8px;
  padding: 12px 14px; overflow: hidden;
}
.token-value {
  flex: 1; font-size: 12px; word-break: break-all;
  color: var(--fg-0); font-family: var(--font-mono);
}
.copy-btn {
  background: none; border: none; padding: 4px; cursor: pointer;
  border-radius: 4px; display: flex; flex-shrink: 0;
}
.copy-btn:hover { background: var(--bg-3); }
</style>
