<script setup lang="ts">
import PipelineRunsTable from '~/components/pipelines/PipelineRunsTable.vue'
import PipelineActiveRunsTable from '~/components/pipelines/PipelineActiveRunsTable.vue'
import PipelineDeadLetterTable from '~/components/pipelines/PipelineDeadLetterTable.vue'
import PipelineRunDetailModal from '~/components/pipelines/PipelineRunDetailModal.vue'

/**
 * Global run history — every triggered pipeline execution across all pipelines,
 * newest first. Each row links to its pipeline's editor. Active (running/suspended)
 * durable runs and terminally-failed (dead-letter) runs each get their own tab,
 * with cancel/replay controls respectively. Opening a run shows its per-node
 * execution timeline.
 */

const { accent } = useCurrentSubsystem()

const table = ref<InstanceType<typeof PipelineRunsTable> | null>(null)
const activeTable = ref<InstanceType<typeof PipelineActiveRunsTable> | null>(null)
const deadLetterTable = ref<InstanceType<typeof PipelineDeadLetterTable> | null>(null)
/** The durable run whose detail timeline is open, or null. Seeded from `?runId=` so a notification's
 *  deep link (e.g. "awaiting approval") lands directly on the run — and its approval controls. */
const detailRunId = ref<string | null>(null)
const route = useRoute()
onMounted(() => {
  const fromLink = route.query.runId
  if (typeof fromLink === 'string' && fromLink.length > 0) detailRunId.value = fromLink
})

/** The three run views, surfaced as tabs so only one table is mounted at a time. */
const tabs = ['Active Runs', 'Dead Letter', 'All Runs']
const activeTab = ref('Active Runs')

/**
 * Reload the currently-visible tab. Only the active tab's table is mounted (v-if),
 * so the other refs are null and their `?.refresh()` calls harmlessly no-op.
 */
function refreshActive() {
  activeTable.value?.refresh()
  deadLetterTable.value?.refresh()
  table.value?.refresh()
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Pipelines', 'Run History')"
        title="Run History"
        subtitle="Every triggered pipeline execution, newest first"
        :tabs="tabs"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #actions>
          <Button
            size="sm"
            icon="pulse"
            @click="refreshActive()"
          >
            Refresh
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard
      v-if="activeTab === 'Active Runs'"
      title="Active Runs"
      subtitle="Runs currently running or suspended (awaiting out-of-band work) — cancel a stuck run here."
    >
      <PipelineActiveRunsTable
        ref="activeTable"
        @view-run="(id: string) => detailRunId = id"
      />
    </SectionCard>

    <SectionCard
      v-else-if="activeTab === 'Dead Letter'"
      title="Dead Letter"
      subtitle="Runs that terminally failed — review the failure and Replay to re-run from the same input against the current pipeline."
    >
      <PipelineDeadLetterTable
        ref="deadLetterTable"
        @view-run="(id: string) => detailRunId = id"
      />
    </SectionCard>

    <SectionCard
      v-else-if="activeTab === 'All Runs'"
      title="All Runs"
      subtitle="Runs across every pipeline — click a pipeline to open its editor."
    >
      <PipelineRunsTable
        ref="table"
        @view-run="(id: string) => detailRunId = id"
      />
    </SectionCard>

    <PipelineRunDetailModal
      v-if="detailRunId"
      :run-id="detailRunId"
      @close="detailRunId = null"
    />
  </PageShell>
</template>
