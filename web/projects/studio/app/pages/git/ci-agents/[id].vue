<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const agentId = computed(() => route.params.id as string)

const agentsGql = gql`
  query PipelineAgentDetail {
    git {
      pipelineAgents {
        id name labels mode status ephemeral
        jobId parentAgentId instanceId
        lastHeartbeat expiresAt created
        currentJob {
          id pipelineRunId name status runnerLabel
          matrixValues dependsOn timeoutMinutes started finished
          steps {
            id name ordinal status uses run image
            condition workingDirectory exitCode started finished
          }
        }
        recentJobs(limit: 10) {
          id pipelineRunId name status runnerLabel started finished
        }
      }
    }
  }
`

interface PipelineAgent {
  id: string; name: string; labels: string[]; mode: string; status: string; ephemeral: boolean
  jobId: string | null; parentAgentId: string | null; instanceId: string | null
  lastHeartbeat: string | null; expiresAt: string | null; created: string
  currentJob: PipelineJob | null
  recentJobs: PipelineJobSummary[]
}

interface PipelineJob {
  id: string; pipelineRunId: string; name: string; status: string; runnerLabel: string
  matrixValues: Record<string, unknown>; dependsOn: string[]; timeoutMinutes: number | null
  started: string | null; finished: string | null
  steps: PipelineStep[]
}

interface PipelineJobSummary {
  id: string; pipelineRunId: string; name: string; status: string; runnerLabel: string
  started: string | null; finished: string | null
}

interface PipelineStep {
  id: string; name: string; ordinal: number; status: string; uses: string | null; run: string | null
  image: string | null; condition: string | null; workingDirectory: string | null
  exitCode: number | null; started: string | null; finished: string | null
}

const { data, status, refresh } = useAsyncQuery<{
  git: { pipelineAgents: PipelineAgent[] }
}>('pipeline-agent-detail', agentsGql)

const agent = computed(() => {
  const all = data.value?.git?.pipelineAgents ?? []
  return all.find((a) => a.id === agentId.value) ?? null
})
const isLoading = computed(() => status.value === 'pending')
const isOrchestrator = computed(() => agent.value?.mode === 'ORCHESTRATOR')

const currentJob = computed(() => agent.value?.currentJob ?? null)
const recentJobs = computed(() => agent.value?.recentJobs ?? [])
const isActive = computed(() => {
  const s = agent.value?.status
  return s === 'ONLINE' || s === 'BUSY'
})

const currentStep = computed(() => {
  if (!currentJob.value?.steps) return null
  const steps = [...currentJob.value.steps].sort((a, b) => a.ordinal - b.ordinal)
  return steps.find((s) => s.status === 'RUNNING') ?? null
})

const currentCommand = computed(() => {
  const step = currentStep.value
  if (!step) return null
  if (step.run) return { type: 'run', value: step.run, name: step.name }
  if (step.uses) return { type: 'uses', value: step.uses, name: step.name }
  return null
})

let pollTimer: ReturnType<typeof setInterval> | null = null

watch(isActive, (active) => {
  if (active && !pollTimer) {
    pollTimer = setInterval(() => refresh(), 5000)
  } else if (!active && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}, { immediate: true })

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

const agentName = ref('')
const agentLabels = ref('')
const savingAgent = ref(false)

watch(agent, (a) => {
  if (a) {
    agentName.value = a.name
    agentLabels.value = a.labels.join(', ')
  }
}, { immediate: true })

async function saveAgent() {
  if (!agent.value) return
  savingAgent.value = true
  try {
    const labels = agentLabels.value.split(',').map((l: string) => l.trim()).filter(Boolean)
    await gqlMutation(gql`
      mutation UpdateAgent($id: UUID!, $name: String!, $labels: [String!]!) {
        git { updateAgent(id: $id, name: $name, labels: $labels) { id } }
      }
    `, { id: agent.value.id, name: agentName.value, labels })
    toast.success('Agent updated')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update agent')
  } finally { savingAgent.value = false }
}

const orchConfig = ref<Record<string, unknown> | null>(null)
const loadingConfig = ref(false)
const savingConfig = ref(false)

const provider = ref('')
const selfDestructTokenScope = ref('')
const region = ref('')
const size = ref('')
const image = ref('')
const runnerProfiles = ref<Record<string, unknown>>({})
const maxConcurrentVms = ref('5')
const maxJobTimeoutMinutes = ref('60')
const maxVmLifetimeMinutes = ref('120')
const alertSinks = ref<Array<{ type: string; url: string }>>([])

watch(agent, async (a) => {
  if (!a || a.mode !== 'ORCHESTRATOR') return
  loadingConfig.value = true
  try {
    const result = await query<{ git: { getOrchestratorConfig: Record<string, unknown> | null } }>(gql`
      mutation GetOrchConfig($agentId: UUID!) {
        git { getOrchestratorConfig(agentId: $agentId) {
          provider
          credentials { selfDestructTokenScope }
          defaults { region size image }
          runnerProfiles
          maxConcurrentVms maxJobTimeoutMinutes maxVmLifetimeMinutes
          alertSinks { type url }
        } }
      }
    `, { agentId: a.id })
    const c = result.git?.getOrchestratorConfig as Record<string, unknown> | null
    if (c) {
      orchConfig.value = c
      provider.value = (c.provider as string) ?? ''
      const creds = c.credentials as Record<string, unknown> | undefined
      selfDestructTokenScope.value = (creds?.selfDestructTokenScope as string) ?? ''
      const defaults = c.defaults as Record<string, unknown> | undefined
      region.value = (defaults?.region as string) ?? ''
      size.value = (defaults?.size as string) ?? ''
      image.value = (defaults?.image as string) ?? ''
      runnerProfiles.value = (c.runnerProfiles as Record<string, unknown>) ?? {}
      maxConcurrentVms.value = String(c.maxConcurrentVms ?? 5)
      maxJobTimeoutMinutes.value = String(c.maxJobTimeoutMinutes ?? 60)
      maxVmLifetimeMinutes.value = String(c.maxVmLifetimeMinutes ?? 120)
      const sinks = (c.alertSinks as Array<{ type: string; url: string }>) ?? []
      alertSinks.value = sinks.map((s) => ({ type: s.type, url: s.url }))
    }
  } catch { /* config may not exist yet */ }
  finally { loadingConfig.value = false }
}, { immediate: true })

async function saveConfig() {
  if (!agent.value) return
  savingConfig.value = true
  try {
    await gqlMutation(gql`
      mutation ConfigureOrchestrator($agentId: UUID!, $config: GitOrchestratorConfigInput!) {
        git { configureOrchestrator(agentId: $agentId, config: $config) { id } }
      }
    `, {
      agentId: agent.value.id,
      config: {
        provider: provider.value,
        credentials: { selfDestructTokenScope: selfDestructTokenScope.value || null },
        defaults: { region: region.value, size: size.value, image: image.value },
        runnerProfiles: runnerProfiles.value,
        maxConcurrentVms: Number(maxConcurrentVms.value),
        maxJobTimeoutMinutes: Number(maxJobTimeoutMinutes.value),
        maxVmLifetimeMinutes: Number(maxVmLifetimeMinutes.value),
        alertSinks: alertSinks.value.filter(s => s.type && s.url),
      },
    })
    toast.success('Configuration saved')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to save configuration')
  } finally { savingConfig.value = false }
}

function addAlertSink() {
  alertSinks.value.push({ type: '', url: '' })
}

function removeAlertSink(index: number) {
  alertSinks.value.splice(index, 1)
}

async function deregister() {
  if (!agent.value) return
  try {
    await gqlMutation(gql`
      mutation DeregisterAgent($id: UUID!) { git { deregisterAgent(id: $id) } }
    `, { id: agent.value.id })
    toast.success('Agent deregistered')
    router.push('/git/ci-agents')
  } catch { toast.error('Failed to deregister') }
}

function statusColor(s: string): string {
  if (s === 'ONLINE') return 'var(--ok)'
  if (s === 'BUSY') return 'var(--brand-2)'
  if (s === 'DRAINING') return 'var(--warn)'
  return 'var(--fg-4)'
}

function jobStatusColor(s: string): string {
  if (s === 'SUCCESS') return 'var(--ok)'
  if (s === 'FAILURE') return 'var(--err)'
  if (s === 'RUNNING') return 'var(--brand-2)'
  if (s === 'CANCELLED') return 'var(--fg-4)'
  if (s === 'QUEUED') return 'var(--warn)'
  if (s === 'SKIPPED') return 'var(--fg-4)'
  return 'var(--fg-3)'
}

function statusBadgeBg(s: string): string {
  return `color-mix(in oklch, ${statusColor(s)} 16%, transparent)`
}

function jobStatusBadgeBg(s: string): string {
  return `color-mix(in oklch, ${jobStatusColor(s)} 16%, transparent)`
}

function statusIcon(s: string): string {
  if (s === 'SUCCESS') return 'check'
  if (s === 'FAILURE') return 'x'
  if (s === 'RUNNING') return 'pulse'
  if (s === 'CANCELLED') return 'x'
  if (s === 'QUEUED') return 'dots'
  if (s === 'SKIPPED') return 'arrowsLeftRight'
  return 'dots'
}

function formatTime(d: string | null): string {
  if (!d) return '—'
  return new Date(d).toLocaleString(undefined, {
    weekday: 'short', month: 'short', day: 'numeric', year: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  })
}

function formatShortTime(d: string | null): string {
  if (!d) return '—'
  return new Date(d).toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  })
}

function heartbeatAge(d: string | null): string {
  if (!d) return 'never'
  const sec = Math.floor((Date.now() - new Date(d).getTime()) / 1000)
  if (sec < 60) return `${sec}s ago`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ago`
  return `${Math.floor(sec / 3600)}h ago`
}

function elapsed(started: string | null, finished: string | null): string {
  if (!started) return '—'
  const end = finished ? new Date(finished).getTime() : Date.now()
  const sec = Math.floor((end - new Date(started).getTime()) / 1000)
  if (sec < 60) return `${sec}s`
  const m = Math.floor(sec / 60)
  const s = sec % 60
  if (m < 60) return `${m}m ${s}s`
  return `${Math.floor(m / 60)}h ${m % 60}m`
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Git', { label: 'CI Agents', to: '/git/ci-agents' }, agent?.name ?? '…')"
        :title="agent?.name ?? 'Agent'"
        :subtitle="agent ? `${agent.mode} · ${agent.status}` : ''"
      >
        <template v-if="agent" #actions>
          <Button
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="savingAgent || !agentName.trim()"
            @click="saveAgent">
            {{ savingAgent ? 'Saving…' : 'Save' }}
          </Button>
          <Button
            v-if="isOrchestrator"
            size="sm"
            icon="save"
            :accent="accent"
            :disabled="savingConfig"
            @click="saveConfig">
            {{ savingConfig ? 'Saving…' : 'Save Config' }}
          </Button>
          <Button size="sm" icon="pulse" @click="refresh()">Refresh</Button>
          <Button size="sm" icon="trash" @click="deregister">Deregister</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !agent" class="empty-state">Loading…</div>
    <div v-else-if="!agent" class="empty-state">Agent not found.</div>

    <template v-else>
      <div class="detail-layout">
        <div class="main-content">
          <!-- Current Command -->
          <SectionCard v-if="currentJob" title="Current Command">
            <div class="current-command">
              <div class="command-header">
                <div class="command-job-info">
                  <span class="command-job-name">{{ currentJob.name }}</span>
                  <NuxtLink
                    :to="`/git/pipelines/${currentJob.pipelineRunId}`"
                    class="command-run-link mono">
                    Run {{ currentJob.pipelineRunId.slice(0, 8) }}
                  </NuxtLink>
                </div>
                <span class="command-elapsed mono">{{ elapsed(currentJob.started, null) }}</span>
              </div>

              <div v-if="currentCommand" class="command-block">
                <div class="command-step-label">
                  <Icon :name="statusIcon('RUNNING')" :size="12" :color="jobStatusColor('RUNNING')" />
                  <span>{{ currentCommand.name }}</span>
                  <span v-if="currentCommand.type === 'uses'" class="command-type-badge">action</span>
                </div>
                <pre class="command-code">{{ currentCommand.value }}</pre>
              </div>

              <div class="step-progress">
                <div
                  v-for="step in [...(currentJob.steps ?? [])].sort((a, b) => a.ordinal - b.ordinal)"
                  :key="step.id"
                  class="step-pip"
                  :style="{ background: jobStatusColor(step.status) }"
                  :title="`${step.name}: ${step.status}`"
                />
              </div>
            </div>
          </SectionCard>

          <SectionCard v-else-if="agent.status === 'ONLINE'" title="Current Command">
            <div class="idle-state">
              <Icon name="dots" :size="16" color="var(--fg-3)" />
              <span>Idle — waiting for jobs</span>
            </div>
          </SectionCard>

          <SectionCard v-else-if="agent.status === 'OFFLINE'" title="Current Command">
            <div class="idle-state">
              <Icon name="x" :size="16" color="var(--fg-4)" />
              <span>Offline</span>
            </div>
          </SectionCard>

          <!-- Recent Jobs -->
          <SectionCard v-if="recentJobs.length" title="Recent Jobs">
            <div class="recent-jobs">
              <div
                v-for="job in recentJobs"
                :key="job.id"
                class="recent-job-row"
                @click="$router.push(`/git/pipelines/${job.pipelineRunId}`)">
                <div class="recent-job-status" :style="{ background: jobStatusBadgeBg(job.status) }">
                  <Icon :name="statusIcon(job.status)" :size="12" :color="jobStatusColor(job.status)" />
                </div>
                <span class="recent-job-name">{{ job.name }}</span>
                <span class="recent-job-label mono">{{ job.runnerLabel }}</span>
                <span class="recent-job-duration mono">{{ elapsed(job.started, job.finished) }}</span>
                <span class="recent-job-time">{{ formatShortTime(job.started) }}</span>
              </div>
            </div>
          </SectionCard>

          <SectionCard title="Agent">
            <div class="form-grid">
              <TextInput v-model="agentName" label="Name" placeholder="build-runner-01" />
              <TextInput
                v-model="agentLabels"
                label="Labels"
                placeholder="linux, x64, docker"
                mono />
            </div>
          </SectionCard>

          <SectionCard title="Status">
            <div class="form-grid">
              <div class="field-ro">
                <span class="field-ro-label">Mode</span>
                <span class="field-ro-value mono">{{ agent.mode }}</span>
              </div>
              <div class="field-ro">
                <span class="field-ro-label">Status</span>
                <span class="status-badge" :style="{ background: statusBadgeBg(agent.status), color: statusColor(agent.status) }">
                  {{ agent.status }}
                </span>
              </div>
              <div class="field-ro">
                <span class="field-ro-label">Last Heartbeat</span>
                <span class="field-ro-value">{{ heartbeatAge(agent.lastHeartbeat) }}</span>
              </div>
              <div class="field-ro">
                <span class="field-ro-label">Created</span>
                <span class="field-ro-value">{{ formatTime(agent.created) }}</span>
              </div>
              <div v-if="agent.instanceId" class="field-ro">
                <span class="field-ro-label">Instance ID</span>
                <span class="field-ro-value mono">{{ agent.instanceId }}</span>
              </div>
              <div v-if="agent.parentAgentId" class="field-ro">
                <span class="field-ro-label">Parent Agent</span>
                <NuxtLink :to="`/git/ci-agents/${agent.parentAgentId}`" class="field-ro-link mono">
                  {{ agent.parentAgentId }}
                </NuxtLink>
              </div>
              <div v-if="agent.expiresAt" class="field-ro">
                <span class="field-ro-label">Expires</span>
                <span class="field-ro-value">{{ formatTime(agent.expiresAt) }}</span>
              </div>
            </div>
          </SectionCard>

          <template v-if="isOrchestrator">
            <div v-if="loadingConfig" class="empty-state">Loading orchestrator configuration…</div>

            <template v-else>
              <SectionCard title="Provider">
                <div class="form-grid">
                  <TextInput
                    v-model="provider"
                    label="Provider"
                    placeholder="digitalocean"
                    mono />
                  <TextInput
                    v-model="selfDestructTokenScope"
                    label="Self-Destruct Token Scope"
                    placeholder="Optional"
                    mono />
                </div>
              </SectionCard>

              <SectionCard title="VM Defaults">
                <div class="form-grid-3">
                  <TextInput
                    v-model="region"
                    label="Region"
                    placeholder="nyc3"
                    mono />
                  <TextInput
                    v-model="size"
                    label="Size"
                    placeholder="s-2vcpu-4gb"
                    mono />
                  <TextInput
                    v-model="image"
                    label="Image"
                    placeholder="ubuntu-24-04-x64"
                    mono />
                </div>
              </SectionCard>

              <SectionCard title="Limits">
                <div class="form-grid-3">
                  <TextInput v-model="maxConcurrentVms" label="Max Concurrent VMs" type="number" />
                  <TextInput v-model="maxJobTimeoutMinutes" label="Max Job Timeout (min)" type="number" />
                  <TextInput v-model="maxVmLifetimeMinutes" label="Max VM Lifetime (min)" type="number" />
                </div>
              </SectionCard>

              <SectionCard title="Runner Profiles">
                <div class="json-editor-wrap">
                  <ClientOnly>
                    <JsonEditorVue
                      v-model="runnerProfiles"
                      :main-menu-bar="false"
                      :navigation-bar="false"
                      class="jse-theme-dark json-editor"
                    />
                  </ClientOnly>
                </div>
              </SectionCard>

              <SectionCard title="Alert Sinks">
                <div class="alert-sinks">
                  <div v-for="(sink, i) in alertSinks" :key="i" class="alert-sink-row">
                    <TextInput
                      v-model="sink.type"
                      placeholder="slack"
                      label="Type"
                      mono />
                    <TextInput v-model="sink.url" placeholder="https://hooks.slack.com/…" label="URL" />
                    <button class="remove-sink-btn" @click="removeAlertSink(i)">
                      <Icon name="x" :size="14" color="var(--fg-3)" />
                    </button>
                  </div>
                  <Button size="sm" icon="plus" @click="addAlertSink">Add Alert Sink</Button>
                </div>
              </SectionCard>
            </template>
          </template>
        </div>

        <div class="sidebar">
          <SectionCard title="Info">
            <div class="info-list">
              <div class="info-item">
                <span class="info-label">Type</span>
                <span class="info-value">{{ agent.mode === 'ORCHESTRATOR' ? 'Orchestrator' : 'Runner' }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">Ephemeral</span>
                <span class="info-value">{{ agent.ephemeral ? 'Yes' : 'No' }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">Labels</span>
                <span class="info-value">{{ agent.labels.length }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">Status</span>
                <span class="info-value" :style="{ color: statusColor(agent.status) }">{{ agent.status }}</span>
              </div>
            </div>
          </SectionCard>
          <SectionCard title="ID">
            <div class="id-box mono">{{ agent.id }}</div>
          </SectionCard>
        </div>
      </div>
    </template>
  </PageShell>
</template>

<style scoped>
.empty-state { text-align: center; padding: 64px 0; color: var(--fg-3); font-size: 13.5px; }

.detail-layout { display: grid; grid-template-columns: 1fr 280px; gap: 18px; align-items: start; }
.main-content { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.sidebar { display: flex; flex-direction: column; gap: 14px; }

.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; padding: 16px; }
.form-grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 14px; padding: 16px; }

.field-ro { display: flex; flex-direction: column; gap: 4px; }
.field-ro-label { font-size: 12px; font-weight: 600; color: var(--fg-2); }
.field-ro-value { font-size: 13px; color: var(--fg-1); }
.field-ro-link { font-size: 13px; color: v-bind(accent); text-decoration: none; }
.field-ro-link:hover { text-decoration: underline; }

.status-badge {
  display: inline-block; font-size: 10.5px; padding: 3px 9px; border-radius: 8px;
  font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; width: fit-content;
}

/* Current Command */
.current-command { padding: 16px; display: flex; flex-direction: column; gap: 12px; }

.command-header { display: flex; align-items: center; justify-content: space-between; }
.command-job-info { display: flex; align-items: center; gap: 10px; }
.command-job-name { font-weight: 600; font-size: 13.5px; color: var(--fg-0); }
.command-run-link {
  font-size: 11px; color: v-bind(accent); text-decoration: none;
  padding: 2px 7px; background: color-mix(in oklch, v-bind(accent) 10%, transparent);
  border-radius: 4px;
}
.command-run-link:hover { text-decoration: underline; }
.command-elapsed { font-size: 12px; color: var(--fg-2); }

.command-block {
  background: var(--bg-0); border: 1px solid var(--line); border-radius: 8px;
  overflow: hidden;
}
.command-step-label {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  border-bottom: 1px solid var(--line); font-size: 12px; color: var(--fg-1); font-weight: 500;
}
.command-type-badge {
  font-size: 9.5px; padding: 1px 6px; background: var(--bg-3);
  border-radius: 3px; color: var(--fg-3); font-weight: 600; text-transform: uppercase;
}
.command-code {
  padding: 10px 12px; margin: 0; font-family: var(--font-mono); font-size: 12px;
  color: var(--fg-0); white-space: pre-wrap; word-break: break-all; line-height: 1.6;
}

.step-progress { display: flex; gap: 3px; }
.step-pip {
  flex: 1; height: 4px; border-radius: 2px; min-width: 8px;
  transition: background 0.2s;
}

.idle-state {
  display: flex; align-items: center; gap: 10px; padding: 20px 16px;
  color: var(--fg-3); font-size: 13px;
}

/* Recent Jobs */
.recent-jobs { display: flex; flex-direction: column; }
.recent-job-row {
  display: flex; align-items: center; gap: 10px; padding: 8px 16px;
  border-bottom: 1px solid var(--line); cursor: pointer; transition: background 0.12s;
}
.recent-job-row:last-child { border-bottom: none; }
.recent-job-row:hover { background: var(--bg-2); }
.recent-job-status {
  width: 24px; height: 24px; border-radius: 6px;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.recent-job-name { flex: 1; font-size: 12.5px; color: var(--fg-0); font-weight: 500; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recent-job-label { font-size: 10.5px; color: var(--fg-3); padding: 2px 6px; background: var(--bg-3); border-radius: 3px; }
.recent-job-duration { font-size: 11px; color: var(--fg-2); width: 60px; text-align: right; }
.recent-job-time { font-size: 11px; color: var(--fg-3); width: 100px; text-align: right; }

.alert-sinks { display: flex; flex-direction: column; gap: 10px; padding: 16px; }
.alert-sink-row { display: flex; align-items: flex-end; gap: 10px; }
.alert-sink-row > :first-child { width: 120px; flex-shrink: 0; }
.alert-sink-row > :nth-child(2) { flex: 1; }
.remove-sink-btn {
  background: none; border: none; padding: 8px; cursor: pointer;
  border-radius: 6px; display: flex; margin-bottom: 2px;
}
.remove-sink-btn:hover { background: color-mix(in oklch, var(--err) 10%, transparent); }

.info-list { display: flex; flex-direction: column; gap: 10px; padding: 14px 16px; }
.info-item { display: flex; justify-content: space-between; align-items: center; }
.info-label { font-size: 11px; color: var(--fg-3); text-transform: uppercase; letter-spacing: 0.05em; }
.info-value { font-size: 12px; color: var(--fg-1); }

.id-box { font-size: 11px; color: var(--fg-2); word-break: break-all; padding: 14px 16px; }

.json-editor-wrap { padding: 16px; }
.json-editor-wrap .json-editor { min-height: 200px; }
</style>
