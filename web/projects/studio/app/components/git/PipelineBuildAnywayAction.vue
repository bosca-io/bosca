<script setup lang="ts">
import gql from 'graphql-tag'
import type { PipelineRequirementGateJob } from '~/utils/gitPipelineRequirements'
import { canRunPipelineJobAnyway } from '~/utils/gitPipelineRequirements'

interface BuildAnywayJob extends PipelineRequirementGateJob {
  id: string
  name: string
}

const props = defineProps<{
  job: BuildAnywayJob
  runStatus: string
  disabled?: boolean
  disabledReason?: string
}>()

const emit = defineEmits<{
  busy: [busy: boolean]
  completed: []
}>()

const { query } = useGraphQL()
const toast = useToast()
const modalOpen = ref(false)
const reason = ref('')
const submitting = ref(false)
const eligible = computed(() => canRunPipelineJobAnyway(props.job, props.runStatus))
const reasonTooLong = computed(() => reason.value.length > 1000)

const runAnywayGql = gql`
  mutation RunPipelineJobAnyway($jobId: UUID!, $reason: String) {
    git {
      runPipelineJobAnyway(jobId: $jobId, reason: $reason) {
        id
        status
        attempt
        requirementsSatisfiedAt
        requirementsBypassedAt
        requirementsBypassedBy
        requirementsBypassReason
      }
    }
  }
`

function open() {
  if (!eligible.value || props.disabled) return
  reason.value = ''
  modalOpen.value = true
}

function close() {
  if (!submitting.value) modalOpen.value = false
}

async function buildAnyway() {
  if (!eligible.value || props.disabled || submitting.value || reasonTooLong.value) return
  submitting.value = true
  emit('busy', true)
  try {
    await query(runAnywayGql, {
      jobId: props.job.id,
      reason: reason.value.trim() || null,
    })
    modalOpen.value = false
    toast.success(`${props.job.name} queued to build anyway`)
    emit('completed')
  } catch (error) {
    toast.error(error instanceof Error && error.message
      ? error.message
      : `Failed to run ${props.job.name}`)
  } finally {
    submitting.value = false
    emit('busy', false)
  }
}
</script>

<template>
  <template v-if="eligible">
    <Button
      size="sm"
      icon="play"
      :disabled="disabled || submitting"
      :title="disabledReason"
      class="build-anyway-trigger"
      @click="open">Build anyway</Button>

    <Modal
      v-if="modalOpen"
      :title="`Build ${job.name} anyway?`"
      subtitle="This is an explicit exception for this pipeline job."
      icon="play"
      width="500px"
      @close="close">
      <p class="override-explanation">
        External artifact and upstream-pipeline requirements will be ignored for this attempt.
        Same-run job dependencies, conditions, and approvals still apply.
      </p>
      <label class="reason-label" for="build-anyway-reason">Reason (optional)</label>
      <textarea
        id="build-anyway-reason"
        v-model="reason"
        class="reason-input"
        rows="3"
        :disabled="submitting"
        placeholder="Why is it safe to build without this requirement?" />
      <div class="reason-count" :class="{ invalid: reasonTooLong }">
        {{ reason.length }} / 1000
      </div>
      <template #footer>
        <span class="footer-spacer" />
        <Button size="sm" :disabled="submitting" @click="close">Cancel</Button>
        <Button
          size="sm"
          icon="play"
          :disabled="submitting || reasonTooLong"
          class="build-anyway-confirm"
          @click="buildAnyway">
          {{ submitting ? 'Queuing…' : 'Build anyway' }}
        </Button>
      </template>
    </Modal>
  </template>
</template>

<style scoped>
.override-explanation { margin: 0; color: var(--fg-1); font-size: 12.5px; line-height: 1.55; }
.reason-label { color: var(--fg-2); font-size: 12px; font-weight: 550; }
.reason-input {
  width: 100%;
  min-height: 84px;
  padding: 8px 12px;
  resize: vertical;
  color: var(--fg-0);
  font: inherit;
  font-size: 13px;
  line-height: 1.5;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  outline: none;
}
.reason-input:focus { border-color: var(--brand-2); }
.reason-input::placeholder { color: var(--fg-3); }
.reason-count { margin-top: -8px; color: var(--fg-3); font-size: 10.5px; text-align: right; }
.reason-count.invalid { color: var(--err); }
.footer-spacer { flex: 1; }
</style>
