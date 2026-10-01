<script setup lang="ts">
import PipelineRunsTable from '~/components/pipelines/PipelineRunsTable.vue'
import PipelineNodeMetricsTable from '~/components/pipelines/PipelineNodeMetricsTable.vue'
import PipelineRunDetailModal from '~/components/pipelines/PipelineRunDetailModal.vue'

/**
 * Run history + per-node metrics for one pipeline in a modal. The global (all-pipelines) history
 * lives on the /pipelines/runs page. Opening a run shows its per-node timeline.
 */

defineProps<{
  pipelineId: string
  pipelineName?: string
}>()
const emit = defineEmits<{ close: [] }>()

const { accent } = useCurrentSubsystem()

const table = ref<InstanceType<typeof PipelineRunsTable> | null>(null)
const metricsTable = ref<InstanceType<typeof PipelineNodeMetricsTable> | null>(null)
const detailRunId = ref<string | null>(null)

function refresh() {
  table.value?.refresh()
  metricsTable.value?.refresh()
}
</script>

<template>
  <Modal
    title="Run History"
    :subtitle="pipelineName ? `Runs of ${pipelineName}, newest first` : 'Runs of this pipeline, newest first'"
    icon="history"
    width="min(90vw, 920px)"
    @close="emit('close')"
  >
    <div class="runs-body">
      <section class="metrics-section">
        <h4 class="section-title">
          Node metrics
        </h4>
        <PipelineNodeMetricsTable
          ref="metricsTable"
          :pipeline-id="pipelineId"
        />
      </section>
      <section>
        <h4 class="section-title">
          Run history
        </h4>
        <PipelineRunsTable
          ref="table"
          :pipeline-id="pipelineId"
          @view-run="(id: string) => detailRunId = id"
        />
      </section>
    </div>

    <template #footer>
      <Button
        size="xs"
        icon="pulse"
        :accent="accent"
        @click="refresh()"
      >
        Refresh
      </Button>
      <Button
        size="xs"
        @click="emit('close')"
      >
        Close
      </Button>
    </template>

    <PipelineRunDetailModal
      v-if="detailRunId"
      :run-id="detailRunId"
      @close="detailRunId = null"
    />
  </Modal>
</template>

<style scoped>
.runs-body {
  /* Long histories scroll inside the modal instead of growing past the viewport. */
  max-height: min(60vh, 640px);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.section-title {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--text-muted, #94a3b8);
}
</style>
