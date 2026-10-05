<script setup lang="ts">
const { accent } = useCurrentSubsystem()
useGraphQL()
const toast = useToast()

const sqlQuery = ref('')
const executing = ref(false)
const results = ref<Record<string, unknown>[] | null>(null)
const executeError = ref('')

const resultColumns = computed(() => {
  if (!results.value?.length) return []
  return Object.keys(results.value[0]!)
})

async function onExecute() {
  executing.value = true; executeError.value = ''; results.value = null
  toast.info('Explorer requires a saved query ID to execute. Use the Queries page to create and run queries.')
  executing.value = false
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Analytics', 'Explorer')"
        title="Explorer"
        subtitle="Ad-hoc analytics exploration">
        <template #actions>
          <Button
            size="sm"
            icon="pulse"
            :disabled="executing"
            @click="onExecute">Execute</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Query">
      <CodeEditor
        v-model="sqlQuery"
        language="sql"
        :rows="16"
        placeholder="Write a SQL query to explore your analytics data…" />
    </SectionCard>

    <SectionCard v-if="results" title="Results" style="margin-top: 14px">
      <div v-if="executeError" class="error-msg">{{ executeError }}</div>
      <div v-else-if="results.length" class="results-table">
        <div class="results-header" :style="{ gridTemplateColumns: resultColumns.map(() => '1fr').join(' ') }">
          <span v-for="col in resultColumns" :key="col">{{ col }}</span>
        </div>
        <div
          v-for="(row, i) in results"
          :key="i"
          class="results-row"
          :style="{ gridTemplateColumns: resultColumns.map(() => '1fr').join(' ') }">
          <span v-for="col in resultColumns" :key="col" class="mono">{{ row[col] }}</span>
        </div>
      </div>
      <div v-else style="padding: 20px; text-align: center; color: var(--fg-3); font-size: 13px">No results.</div>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.results-table { display: flex; flex-direction: column; max-height: 500px; overflow: auto; }
.results-header, .results-row { display: grid; gap: 4px; padding: 8px 0; }
.results-header { font-size: 10.5px; color: var(--fg-3); font-weight: 600; text-transform: uppercase; letter-spacing: 0.08em; border-bottom: 1px solid color-mix(in oklch, var(--line) 42%, transparent); position: sticky; top: 0; background: var(--bg-1); }
.results-row { font-size: 12.5px; border-bottom: 1px solid color-mix(in oklch, var(--line) 35%, transparent); }
</style>
