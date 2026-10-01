<script setup lang="ts">
import gql from 'graphql-tag'

interface TaskGeneration {
  id: string
  specId: string
  metadataVersion: number
  source: string
  agentSessionId: string | null
  generatedTaskIds: string[]
  createdAt: string
  createdByPrincipalId: string
}

const props = defineProps<{
  specId: string
  generations: TaskGeneration[]
  accent: string
}>()

const emit = defineEmits<{
  updated: []
}>()

const { mutation, query: gqlQuery } = useGraphQL()
const toast = useToast()

const generating = ref(false)
const taskNames = ref<Record<string, string>>({})

const SOURCE_LABELS: Record<string, string> = {
  CLAUDE_CODE: 'Claude Code',
  MANUAL: 'Manual',
  KIT: 'KIT',
}

const SOURCE_COLORS: Record<string, string> = {
  CLAUDE_CODE: '#a78bff',
  MANUAL: '#5ec5ff',
  KIT: '#ffb547',
}

async function resolveTaskNames() {
  const resolved: Record<string, string> = { ...taskNames.value }
  const allIds = props.generations.flatMap(g => g.generatedTaskIds)
  const toResolve = allIds.filter(id => !resolved[id])
  for (const id of toResolve.slice(0, 20)) {
    try {
      const result = await gqlQuery<{ workOps: { tasks: { task: { key: string; summary: string } | null } } }>(gql`
        query($id: UUID!) { workOps { tasks { task(id: $id) { key summary } } } }
      `, { id })
      const t = result.workOps?.tasks?.task
      if (t) resolved[id] = `${t.key} — ${t.summary}`
    } catch { /* skip */ }
  }
  taskNames.value = resolved
}

onMounted(() => { resolveTaskNames() })
watch(() => props.generations, () => { resolveTaskNames() }, { deep: true })

async function triggerGeneration() {
  generating.value = true
  try {
    await mutation(gql`
      mutation GenerateTasks($specId: UUID!, $metadataVersion: Int!, $source: WorkOpsGenerationSource!) {
        workOps { specs { generateTasks(specId: $specId, metadataVersion: $metadataVersion, source: $source) { id generatedTaskIds } } }
      }
    `, {
      specId: props.specId,
      metadataVersion: 0,
      source: 'MANUAL',
    })
    toast.success('Tasks generated')
    emit('updated')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to generate tasks')
  } finally {
    generating.value = false
  }
}

function relativeTime(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(ms / 60_000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  const days = Math.floor(hrs / 24)
  return `${days}d ago`
}
</script>

<template>
  <SectionCard title="Task Generations" glass>
    <template #right>
      <Button
        size="sm"
        :accent="accent"
        :disabled="generating"
        @click="triggerGeneration">
        {{ generating ? 'Generating…' : 'Generate Tasks' }}
      </Button>
    </template>

    <div class="card-body">
      <div v-for="gen in generations" :key="gen.id" class="gen-item">
        <div class="gen-header">
          <Badge :color="SOURCE_COLORS[gen.source] || '#6c7388'">
            {{ SOURCE_LABELS[gen.source] || gen.source }}
          </Badge>
          <span class="gen-count">{{ gen.generatedTaskIds.length }} task{{ gen.generatedTaskIds.length === 1 ? '' : 's' }}</span>
          <span class="gen-time">{{ relativeTime(gen.createdAt) }}</span>
        </div>
        <div v-if="gen.generatedTaskIds.length" class="gen-tasks">
          <NuxtLink
            v-for="taskId in gen.generatedTaskIds"
            :key="taskId"
            :to="`/workops/tasks/${taskId}`"
            class="gen-task-link"
          >
            {{ taskNames[taskId] || taskId.slice(0, 8) }}
          </NuxtLink>
        </div>
      </div>

      <div v-if="!generations.length" class="empty-msg">
        No tasks generated yet. Generate tasks from this spec's requirements.
      </div>
    </div>
  </SectionCard>
</template>

<style scoped>
.card-body {
  padding: 8px 16px 14px;
}

.gen-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
}

.gen-item:last-child { border-bottom: none; }

.gen-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.gen-count {
  font-size: 12px;
  color: var(--fg-1);
  font-weight: 500;
}

.gen-time {
  font-size: 11px;
  color: var(--fg-3);
  margin-left: auto;
}

.gen-tasks {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
  padding-left: 8px;
  border-left: 2px solid var(--line);
}

.gen-task-link {
  font-size: 12.5px;
  color: v-bind(accent);
  text-decoration: none;
  padding: 3px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gen-task-link:hover {
  text-decoration: underline;
}

.empty-msg {
  color: var(--fg-3);
  font-size: 13px;
  padding: 12px 0;
}
</style>
