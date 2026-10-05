<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const jobId = computed(() => route.params.id as string)
const isNew = computed(() => jobId.value === 'new')

const detailGql = gql`
  query GetScheduledJobDetail($id: UUID!) {
    scheduler {
      job(id: $id) {
        id name description jobName jobParameters cronExpression
        enabled allowConcurrent catchUp maxCatchUp
        lastRunAt nextRunAt
      }
      availableJobDefinitions { id name displayName queueName parameterSchema }
    }
  }
`
const defsOnlyGql = gql`
  query GetJobDefinitions {
    scheduler { availableJobDefinitions { id name displayName queueName parameterSchema } }
  }
`
const validateGql = gql`
  query ValidateCron($expression: String!) {
    scheduler { validateCronExpression(expression: $expression) { valid error nextRuns } }
  }
`
const createGql = gql`
  mutation CreateScheduledJob($input: ScheduledJobInput!) {
    scheduler { create(input: $input) { id } }
  }
`
const editGql = gql`
  mutation EditScheduledJob($id: UUID!, $input: ScheduledJobInput!) {
    scheduler { edit(id: $id, input: $input) { id } }
  }
`
const deleteGql = gql`mutation DeleteScheduledJob($id: UUID!) { scheduler { delete(id: $id) } }`
const triggerGql = gql`mutation TriggerScheduledJob($id: UUID!) { scheduler { trigger(id: $id) { id } } }`

interface JobDefinitionInfo {
  id: string
  name: string
  displayName: string
  queueName: string
  parameterSchema: unknown
}
interface ScheduledJob {
  id: string
  name: string
  description: string | null
  jobName: string
  jobParameters: unknown
  cronExpression: string | null
  enabled: boolean
  allowConcurrent: boolean
  catchUp: boolean
  maxCatchUp: number
  lastRunAt: string | null
  nextRunAt: string | null
}

const { data, refresh } = useAsyncQuery<{
  scheduler: { job?: ScheduledJob | null; availableJobDefinitions: JobDefinitionInfo[] }
}>(
  isNew.value ? 'scheduled-job-new' : 'scheduled-job-detail',
  isNew.value ? defsOnlyGql : detailGql,
  isNew.value ? {} : { id: jobId },
)

const job = computed(() => (isNew.value ? null : data.value?.scheduler?.job ?? null))
const definitions = computed(() => data.value?.scheduler?.availableJobDefinitions ?? [])
const definitionOptions = computed(() =>
  definitions.value.map((d) => ({ value: d.name, label: d.displayName || d.name })),
)

const name = ref('')
const description = ref('')
const jobName = ref('')
const cronExpression = ref('')
const enabled = ref(true)
const allowConcurrent = ref(false)
const catchUp = ref(false)
const maxCatchUp = ref<number | null>(null)
const jobParameters = ref('{}')

const saving = ref(false)
const validating = ref(false)
const triggering = ref(false)
const deleting = ref(false)
const showDeleteModal = ref(false)

interface CronCheck { valid: boolean; error: string | null; nextRuns: string[] }
const cronCheck = ref<CronCheck | null>(null)

watch(job, (j) => {
  if (!j) return
  name.value = j.name
  description.value = j.description ?? ''
  jobName.value = j.jobName
  cronExpression.value = j.cronExpression ?? ''
  enabled.value = j.enabled
  allowConcurrent.value = j.allowConcurrent
  catchUp.value = j.catchUp
  maxCatchUp.value = j.maxCatchUp
  jobParameters.value = j.jobParameters ? JSON.stringify(j.jobParameters, null, 2) : '{}'
}, { immediate: true })

const selectedDefinition = computed(() => definitions.value.find((d) => d.name === jobName.value) ?? null)
const parameterSchemaText = computed(() => {
  const s = selectedDefinition.value?.parameterSchema
  return s ? JSON.stringify(s, null, 2) : ''
})

watch(cronExpression, () => { cronCheck.value = null })

function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

function formatPreview(d: string): string {
  return new Date(d).toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  })
}

async function validateCron() {
  if (!cronExpression.value.trim()) {
    cronCheck.value = { valid: false, error: 'Cron expression is empty', nextRuns: [] }
    return
  }
  validating.value = true
  try {
    const res = await gqlQuery<{ scheduler: { validateCronExpression: CronCheck } }>(
      validateGql, { expression: cronExpression.value },
    )
    cronCheck.value = res.scheduler.validateCronExpression
  } catch (e) {
    toast.error(`Validate failed: ${errorMessage(e)}`)
  } finally {
    validating.value = false
  }
}

function parseParameters(): unknown {
  const text = jobParameters.value.trim() || '{}'
  return JSON.parse(text)
}

const canSave = computed(() => !!name.value.trim() && !!jobName.value && !saving.value)

async function onSave() {
  let parameters: unknown
  try {
    parameters = parseParameters()
  } catch (e) {
    toast.error(`Job parameters must be valid JSON: ${errorMessage(e)}`)
    return
  }

  const input: Record<string, unknown> = {
    name: name.value.trim(),
    description: description.value.trim() || null,
    jobName: jobName.value,
    jobParameters: parameters,
    cronExpression: cronExpression.value.trim() || null,
    enabled: enabled.value,
    allowConcurrent: allowConcurrent.value,
    catchUp: catchUp.value,
  }
  if (catchUp.value && maxCatchUp.value != null) input.maxCatchUp = maxCatchUp.value

  saving.value = true
  try {
    if (isNew.value) {
      const result = await gqlMutation<{ scheduler: { create: { id: string } } }>(
        createGql, { input },
      )
      toast.success('Job created')
      router.replace(`/system/scheduler/${result.scheduler.create.id}`)
    } else {
      await gqlMutation(editGql, { id: jobId.value, input })
      toast.success('Job saved')
      refresh()
    }
  } catch (e) {
    toast.error(`Failed to save: ${errorMessage(e)}`)
  } finally {
    saving.value = false
  }
}

async function onTrigger() {
  if (isNew.value) return
  triggering.value = true
  try {
    await gqlMutation(triggerGql, { id: jobId.value })
    toast.success('Triggered')
  } catch (e) {
    toast.error(`Failed to trigger: ${errorMessage(e)}`)
  } finally {
    triggering.value = false
  }
}

async function onDelete() {
  if (isNew.value) return
  deleting.value = true
  try {
    await gqlMutation(deleteGql, { id: jobId.value })
    toast.success('Deleted')
    router.replace('/system/scheduler')
  } catch (e) {
    toast.error(`Failed to delete: ${errorMessage(e)}`)
    deleting.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Scheduler', isNew ? 'New' : name || '…')"
        :title="isNew ? 'New Scheduled Job' : (name || 'Loading…')">
        <template #actions>
          <template v-if="!isNew">
            <Button
              size="sm"
              icon="pulse"
              :disabled="triggering"
              @click="onTrigger">
              {{ triggering ? 'Triggering…' : 'Trigger now' }}
            </Button>
            <Button
              size="sm"
              icon="trash"
              :disabled="deleting"
              @click="showDeleteModal = true">Delete</Button>
          </template>
          <Button
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="!canSave"
            @click="onSave">{{ saving ? 'Saving…' : 'Save' }}</Button>
        </template>
      </PageHeader>
    </template>

    <div class="form-layout">
      <SectionCard title="Job" padded>
        <div class="form-grid">
          <TextInput v-model="name" label="Name" placeholder="Nightly index rebuild" />
          <Select
            v-model="jobName"
            :options="definitionOptions"
            label="Job Definition"
            placeholder="Select a registered job…"
            searchable />
        </div>
        <Textarea
          v-model="description"
          label="Description"
          :rows="2"
          style="margin-top: 14px" />
        <div v-if="selectedDefinition" class="hint">
          Queue: <span class="mono">{{ selectedDefinition.queueName }}</span>
        </div>
      </SectionCard>

      <SectionCard title="Schedule" padded>
        <div class="cron-row">
          <TextInput
            v-model="cronExpression"
            label="Cron Expression"
            placeholder="0 0 * * *"
            mono />
          <Button size="sm" :disabled="validating" @click="validateCron">
            {{ validating ? 'Validating…' : 'Validate' }}
          </Button>
        </div>
        <div class="hint">
          Leave empty for jobs that are only triggered manually or by events.
        </div>
        <div v-if="cronCheck" class="cron-result">
          <div :class="['cron-status', cronCheck.valid ? 'ok' : 'err']">
            <span :class="['dot', cronCheck.valid ? 'ok' : 'err']" />
            <span>{{ cronCheck.valid ? 'Valid cron expression' : (cronCheck.error || 'Invalid cron expression') }}</span>
          </div>
          <div v-if="cronCheck.valid && cronCheck.nextRuns.length" class="next-runs">
            <div class="next-runs-label">Next runs:</div>
            <ul>
              <li v-for="(d, i) in cronCheck.nextRuns" :key="i" class="mono">{{ formatPreview(d) }}</li>
            </ul>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="Execution" padded>
        <div class="flags">
          <Switch v-model="enabled" label="Enabled" :accent="accent" />
          <Switch v-model="allowConcurrent" label="Allow concurrent runs" :accent="accent" />
          <Switch v-model="catchUp" label="Catch up on missed runs" :accent="accent" />
        </div>
        <div v-if="catchUp" class="max-catchup">
          <NumberInput
            v-model="maxCatchUp"
            label="Max catch-up runs"
            :min="1"
            placeholder="System default" />
        </div>
      </SectionCard>

      <SectionCard title="Job Parameters (JSON)" padded>
        <CodeEditor v-model="jobParameters" language="json" :rows="12" />
        <details v-if="parameterSchemaText" class="schema-details">
          <summary>Parameter schema</summary>
          <pre class="schema-pre">{{ parameterSchemaText }}</pre>
        </details>
      </SectionCard>
    </div>

    <ConfirmModal
      v-if="showDeleteModal"
      :title="`Delete '${name}'?`"
      :loading="deleting"
      @close="showDeleteModal = false"
      @confirm="onDelete" />
  </PageShell>
</template>

<style scoped>
.form-layout { display: flex; flex-direction: column; gap: 14px; max-width: 900px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.hint { margin-top: 10px; font-size: 12px; color: var(--fg-3); }
.cron-row { display: grid; grid-template-columns: 1fr auto; gap: 10px; align-items: end; }
.cron-result { margin-top: 12px; padding: 10px 12px; border-radius: 6px; background: var(--bg-2); }
.cron-status { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.cron-status.ok { color: var(--fg-1); }
.cron-status.err { color: var(--fg-1); }
.next-runs { margin-top: 8px; font-size: 12px; color: var(--fg-2); }
.next-runs-label { color: var(--fg-3); margin-bottom: 4px; }
.next-runs ul { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 2px; }
.flags { display: flex; flex-direction: column; gap: 12px; }
.max-catchup { margin-top: 14px; max-width: 240px; }
.schema-details { margin-top: 12px; }
.schema-details summary { cursor: pointer; font-size: 12px; color: var(--fg-3); }
.schema-pre { margin-top: 8px; padding: 10px; background: var(--bg-2); border-radius: 6px; font-size: 12px; overflow: auto; max-height: 240px; }
</style>
