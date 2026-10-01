<script setup lang="ts">
import gql from 'graphql-tag'

const props = defineProps<{
  sprintId: string
  sprintName: string
  sprintVersion: number
  committedTaskIds: string[]
  addedDuringSprintTaskIds: string[]
  futureSprints: Array<{ id: string; name: string }>
  accent: string
}>()

const emit = defineEmits<{
  close: []
  completed: []
}>()

const { query, mutation } = useGraphQL()
const toast = useToast()

const STATUS_COLORS: Record<string, string> = {
  TODO: '#6c7388',
  IN_PROGRESS: '#a78bff',
  IN_REVIEW: '#ffb547',
  DONE: '#34d99a',
  CANCELLED: '#6c7388',
}

interface TaskInfo {
  id: string
  key: string
  summary: string
  status: { id: string; name: string; category: string }
}

const loading = ref(true)
const saving = ref(false)
const doneTasks = ref<TaskInfo[]>([])
const unfinishedTasks = ref<TaskInfo[]>([])
const destinations = reactive<Record<string, string | null>>({})
const velocityPoints = ref('')

const allTaskIds = computed(() => {
  const set = new Set([...props.committedTaskIds, ...props.addedDuringSprintTaskIds])
  return [...set]
})

const destinationOptions = computed(() => [
  { value: '__backlog__', label: 'Move to Backlog' },
  ...props.futureSprints.map(s => ({ value: s.id, label: s.name })),
])

async function loadTasks() {
  loading.value = true
  try {
    const results = await Promise.all(
      allTaskIds.value.map(async (id) => {
        try {
          const result = await query<{ workOps: { tasks: { task: TaskInfo | null } } }>(gql`
            query GetTaskForClose($id: UUID!) {
              workOps { tasks { task(id: $id) { id key summary status { id name category } } } }
            }
          `, { id })
          return result.workOps?.tasks?.task ?? null
        } catch { return null }
      }),
    )
    const tasks = results.filter((t): t is TaskInfo => t != null)
    doneTasks.value = tasks.filter(t => t.status.category === 'DONE' || t.status.category === 'CANCELLED')
    unfinishedTasks.value = tasks.filter(t => t.status.category !== 'DONE' && t.status.category !== 'CANCELLED')
    for (const t of unfinishedTasks.value) {
      destinations[t.id] = '__backlog__'
    }
  } finally { loading.value = false }
}

async function handleClose() {
  saving.value = true
  try {
    const taskDestinations = unfinishedTasks.value.map(t => ({
      taskId: t.id,
      targetSprintId: destinations[t.id] === '__backlog__' ? null : destinations[t.id],
    }))
    await mutation(gql`
      mutation CloseSprint($input: CloseWorkOpsSprintInput!) {
        workOps { sprints { close(input: $input) { id } } }
      }
    `, {
      input: {
        sprintId: props.sprintId,
        expectedVersion: props.sprintVersion,
        velocityPoints: velocityPoints.value ? parseFloat(velocityPoints.value) : null,
        destinations: taskDestinations.length ? taskDestinations : undefined,
      },
    })
    toast.success('Sprint completed')
    emit('completed')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to close sprint')
  } finally { saving.value = false }
}

loadTasks()
</script>

<template>
  <Modal
    :title="`Complete ${sprintName}`"
    icon="check"
    :accent="accent"
    @close="emit('close')">
    <div v-if="loading" class="close-loading">Loading sprint tasks…</div>
    <div v-else class="close-content">
      <div v-if="doneTasks.length" class="close-section">
        <div class="close-section-header">
          <Icon name="check" :size="14" color="#34d99a" />
          <span>Completed ({{ doneTasks.length }})</span>
        </div>
        <div v-for="t in doneTasks" :key="t.id" class="close-task-row">
          <Badge :color="STATUS_COLORS[t.status.category] ?? '#6c7388'" solid>{{ t.status.name }}</Badge>
          <span class="close-task-key">{{ t.key }}</span>
          <span class="close-task-summary">{{ t.summary }}</span>
        </div>
      </div>

      <div v-if="unfinishedTasks.length" class="close-section">
        <div class="close-section-header warn">
          <Icon name="alert" :size="14" color="var(--warn)" />
          <span>Unfinished ({{ unfinishedTasks.length }})</span>
        </div>
        <p class="close-hint">Choose where to move each unfinished task.</p>
        <div v-for="t in unfinishedTasks" :key="t.id" class="close-task-row close-task-destination">
          <div class="close-task-info">
            <Badge :color="STATUS_COLORS[t.status.category] ?? '#6c7388'">{{ t.status.name }}</Badge>
            <span class="close-task-key">{{ t.key }}</span>
            <span class="close-task-summary">{{ t.summary }}</span>
          </div>
          <Select
            v-model="destinations[t.id]"
            :options="destinationOptions"
            size="sm"
            :accent="accent"
            class="destination-select"
          />
        </div>
      </div>

      <div v-if="!doneTasks.length && !unfinishedTasks.length" class="close-empty">
        No tasks in this sprint.
      </div>

      <div class="velocity-input">
        <TextInput
          v-model="velocityPoints"
          label="Velocity Points"
          placeholder="e.g. 21"
          type="number" />
      </div>
    </div>

    <template #footer>
      <Button @click="emit('close')">Cancel</Button>
      <Button
        primary
        :accent="accent"
        :disabled="saving || loading"
        @click="handleClose">
        {{ saving ? 'Completing…' : 'Complete Sprint' }}
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.close-loading {
  color: var(--fg-3);
  text-align: center;
  padding: 24px 0;
  font-size: 13px;
}

.close-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.close-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.close-section-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--fg-2);
}

.close-section-header.warn {
  color: var(--warn);
}

.close-hint {
  font-size: 12px;
  color: var(--fg-3);
  margin: 0;
}

.close-task-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 13px;
}

.close-task-destination {
  flex-wrap: wrap;
  gap: 8px;
}

.close-task-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.close-task-key {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
  flex-shrink: 0;
}

.close-task-summary {
  color: var(--fg-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.destination-select {
  flex-shrink: 0;
  min-width: 180px;
}

.close-empty {
  color: var(--fg-3);
  text-align: center;
  font-size: 13px;
  padding: 16px 0;
}

.velocity-input {
  padding-top: 8px;
  border-top: 1px solid var(--border-1);
  max-width: 200px;
}
</style>
