<script setup lang="ts">
import gql from 'graphql-tag'

export interface BoardViewColumn {
  id: string
  name: string
  displayOrder: number
  wipLimit: number | null
  statusIds: string[]
  statuses: Array<{ id: string; name: string; category: string }>
}

export interface BoardViewBoard {
  id: string
  name: string
  type: string
  swimlaneStrategy: string
  columns: BoardViewColumn[]
  version: number
}

interface CardTask {
  id: string
  key: string
  summary: string
  status: { id: string; name: string; category: string }
  priority: { id: string; name: string; displayOrder: number }
  assignee: { id: string; name: string } | null
  transitions: Array<{ id: string; toStateId: string; name: string }>
  version: number
}

const props = defineProps<{
  board: BoardViewBoard
  projectKeys: string[]
  projectId: string
  accent: string
  quickAdd?: boolean
  sprintFilterIds?: string[]
  activeSprint?: { name: string; endDate: string | null } | null
}>()

const emit = defineEmits<{
  cardClick: [cardId: string]
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

const PRIORITY_COLORS: Record<number, string> = {
  1: '#ff5d6c',
  2: '#ff8a4d',
  3: '#ffb547',
  4: '#5ec5ff',
  5: '#6c7388',
}

const sortedColumns = computed(() =>
  [...props.board.columns].sort((a, b) => a.displayOrder - b.displayOrder),
)

const cards = ref<CardTask[]>([])
const cardsLoading = ref(false)
const saving = ref(false)
const stateToStatus = ref<Map<string, string>>(new Map())

async function loadWorkflowStates() {
  try {
    const result = await query<{
      workOps: { workflows: { all: Array<{ states: Array<{ id: string; statusId: string }> }> } }
    }>(gql`
      query { workOps { workflows { all { states { id statusId } } } } }
    `)
    const map = new Map<string, string>()
    for (const wf of result.workOps?.workflows?.all ?? []) {
      for (const s of wf.states) map.set(s.id, s.statusId)
    }
    stateToStatus.value = map
  } catch { /* ignore */ }
}

async function fetchCards(): Promise<CardTask[]> {
  const [result] = await Promise.all([
    query<{
      workOps: { savedFilters: { searchTasks: { rows: CardTask[] } } }
    }>(gql`
      query BoardCards($source: String!, $limit: Int!) {
        workOps {
          savedFilters {
            searchTasks(source: $source, limit: $limit, offset: 0) {
              rows {
                id key summary
                status { id name category }
                priority { id name displayOrder }
                assignee { id name }
                transitions { id toStateId name }
                version
              }
            }
          }
        }
      }
    `, {
      source: props.projectKeys.length === 1
        ? `project = "${props.projectKeys[0]}" ORDER BY priority ASC`
        : `project IN (${props.projectKeys.map(k => `"${k}"`).join(', ')}) ORDER BY priority ASC`,
      limit: 200,
    }),
    stateToStatus.value.size === 0 ? loadWorkflowStates() : Promise.resolve(),
  ])
  return result.workOps?.savedFilters?.searchTasks?.rows ?? []
}

function mergeCards(fresh: CardTask[]) {
  const existing = new Map(cards.value.map(c => [c.id, c]))
  for (const fc of fresh) {
    const ec = existing.get(fc.id)
    if (ec) {
      ec.key = fc.key
      ec.summary = fc.summary
      ec.status = fc.status
      ec.priority = fc.priority
      ec.assignee = fc.assignee
      ec.transitions = fc.transitions
      ec.version = fc.version
      existing.delete(fc.id)
    } else {
      cards.value.push(fc)
    }
  }
  const removedIds = new Set(existing.keys())
  if (removedIds.size) {
    cards.value = cards.value.filter(c => !removedIds.has(c.id))
  }
}

async function loadCards() {
  if (!props.board || !props.projectKeys.length) { cards.value = []; return }
  const isInitial = cards.value.length === 0
  if (isInitial) cardsLoading.value = true
  try {
    const fresh = await fetchCards()
    if (isInitial) {
      cards.value = fresh
    } else {
      mergeCards(fresh)
    }
  } finally {
    cardsLoading.value = false
  }
}

watch(() => [props.board.id, ...props.projectKeys], () => loadCards(), { immediate: true })

const sprintDaysRemaining = computed(() => {
  if (!props.activeSprint?.endDate) return null
  const diff = Math.floor((new Date(props.activeSprint.endDate).getTime() - Date.now()) / 86_400_000)
  if (diff < 0) return { label: `${Math.abs(diff)}d overdue`, color: 'var(--err)' }
  if (diff === 0) return { label: 'Ends today', color: 'var(--warn)' }
  if (diff <= 3) return { label: `${diff}d left`, color: 'var(--warn)' }
  return { label: `${diff}d left`, color: 'var(--fg-3)' }
})

const filteredCards = computed(() => {
  if (!props.sprintFilterIds?.length) return cards.value
  const allowedIds = new Set(props.sprintFilterIds)
  return cards.value.filter(card => allowedIds.has(card.id))
})

function cardsForColumn(col: BoardViewColumn): CardTask[] {
  return filteredCards.value.filter(card => col.statusIds.includes(card.status.id))
}

// ─── Drag & Drop ────────────────────────────────────────────────────────────
const dragCard = ref<CardTask | null>(null)
const dragOverColId = ref('')

function resolveStatusId(toStateId: string): string | undefined {
  return stateToStatus.value.get(toStateId)
}

function canDropOnColumn(col: BoardViewColumn): boolean {
  if (!dragCard.value) return false
  if (col.statusIds.includes(dragCard.value.status.id)) return false
  return dragCard.value.transitions.some((t) => {
    const statusId = resolveStatusId(t.toStateId)
    return statusId != null && col.statusIds.includes(statusId)
  })
}

function findTransitionForColumn(card: CardTask, col: BoardViewColumn): string | null {
  for (const t of card.transitions) {
    const statusId = resolveStatusId(t.toStateId)
    if (statusId && col.statusIds.includes(statusId)) return t.id
  }
  return null
}

async function transitionCard(card: CardTask, transitionId: string, targetCol: BoardViewColumn) {
  const prevStatus = { ...card.status }
  const prevVersion = card.version
  const targetStatus = targetCol.statuses[0]
  if (targetStatus) {
    card.status = { ...targetStatus }
    card.version = card.version + 1
  }
  saving.value = true
  try {
    await mutation(gql`
      mutation TransitionTask($id: UUID!, $transitionId: UUID!, $expectedVersion: Long!) {
        workOps { tasks { transition(id: $id, transitionId: $transitionId, expectedVersion: $expectedVersion) { id } } }
      }
    `, { id: card.id, transitionId, expectedVersion: prevVersion })
    loadCards()
  } catch {
    card.status = prevStatus
    card.version = prevVersion
  } finally { saving.value = false }
}

function onDragStart(event: DragEvent, card: CardTask) {
  dragCard.value = card
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', card.id)
  }
}

function onDragOver(event: DragEvent, col: BoardViewColumn) {
  if (dragCard.value && canDropOnColumn(col)) {
    event.preventDefault()
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  }
}

function onDragEnter(col: BoardViewColumn) {
  dragOverColId.value = col.id
}

function onDragLeave(event: DragEvent, col: BoardViewColumn) {
  const related = event.relatedTarget as HTMLElement | null
  const colEl = (event.currentTarget as HTMLElement)
  if (related && colEl.contains(related)) return
  if (dragOverColId.value === col.id) dragOverColId.value = ''
}

function onDragEnd() {
  dragCard.value = null
  dragOverColId.value = ''
}

function onDrop(event: DragEvent, col: BoardViewColumn) {
  event.preventDefault()
  dragOverColId.value = ''
  const card = dragCard.value
  dragCard.value = null
  if (!card) return
  if (col.statusIds.includes(card.status.id)) return
  const transitionId = findTransitionForColumn(card, col)
  if (transitionId) transitionCard(card, transitionId, col)
}

function dropClass(col: BoardViewColumn): string {
  if (!dragCard.value) return ''
  if (dragOverColId.value !== col.id) return ''
  if (canDropOnColumn(col)) return 'drop-valid'
  return 'drop-invalid'
}

// ─── Quick Add ──────────────────────────────────────────────────────────────
const quickAddCol = ref<string | null>(null)
const quickAddSummary = ref('')

async function quickAddTask(_col: BoardViewColumn) {
  if (!quickAddSummary.value.trim()) return
  saving.value = true
  try {
    await mutation(gql`
      mutation CreateTask($input: CreateWorkOpsTaskInput!) {
        workOps { tasks { create(input: $input) { id } } }
      }
    `, {
      input: {
        summary: quickAddSummary.value.trim(),
        projectId: props.projectId,
      },
    })
    quickAddSummary.value = ''
    quickAddCol.value = null
    toast.success('Task created')
    await loadCards()
  } catch { toast.error('Failed to create task') }
  finally { saving.value = false }
}

defineExpose({ refresh: loadCards })
</script>

<template>
  <!-- Active Sprint Bar -->
  <div v-if="activeSprint" class="sprint-bar">
    <Icon name="refresh" :size="14" :color="props.accent" />
    <span class="sprint-bar-name">{{ activeSprint.name }}</span>
    <span v-if="sprintDaysRemaining" class="sprint-bar-days" :style="{ color: sprintDaysRemaining.color }">
      {{ sprintDaysRemaining.label }}
    </span>
    <span v-if="sprintFilterIds?.length" class="sprint-bar-count">
      {{ filteredCards.length }} tasks
    </span>
  </div>

  <!-- Stats Bar -->
  <div v-if="!cardsLoading && cards.length && sortedColumns.length" class="board-stats">
    <div class="stats-bar">
      <div
        v-for="col in sortedColumns"
        :key="col.id"
        class="stats-segment"
        :style="{
          flex: cardsForColumn(col).length || 0.2,
          background: STATUS_COLORS[col.statuses[0]?.category ?? ''] || '#6c7388',
        }"
        :title="`${col.name}: ${cardsForColumn(col).length}`"
      />
    </div>
    <div class="stats-legend">
      <span class="stats-total">{{ cards.length }} tasks</span>
      <span v-for="col in sortedColumns" :key="col.id" class="stats-item">
        <span class="stats-dot" :style="{ background: STATUS_COLORS[col.statuses[0]?.category ?? ''] || '#6c7388' }" />
        {{ col.name }} ({{ cardsForColumn(col).length }})
      </span>
    </div>
  </div>

  <!-- Board -->
  <div v-if="!cardsLoading && sortedColumns.length" class="board">
    <div
      v-for="col in sortedColumns"
      :key="col.id"
      :class="['board-column', dropClass(col)]"
      @dragover="onDragOver($event, col)"
      @dragenter="onDragEnter(col)"
      @dragleave="onDragLeave($event, col)"
      @drop="onDrop($event, col)"
    >
      <div class="col-header">
        <span class="col-name">{{ col.name }}</span>
        <span class="col-count">{{ cardsForColumn(col).length }}</span>
        <span v-if="col.wipLimit" class="col-wip" :class="{ exceeded: cardsForColumn(col).length > col.wipLimit }">
          / {{ col.wipLimit }}
        </span>
      </div>
      <div class="col-cards">
        <div
          v-for="card in cardsForColumn(col)"
          :key="card.id"
          class="board-card"
          draggable="true"
          @dragstart="onDragStart($event, card)"
          @dragend="onDragEnd"
          @click="emit('cardClick', card.id)"
        >
          <div class="card-top">
            <span class="mono card-key">{{ card.key }}</span>
            <Badge :color="PRIORITY_COLORS[card.priority?.displayOrder] || '#6c7388'" class="card-prio">
              {{ card.priority?.name }}
            </Badge>
          </div>
          <div class="card-summary">{{ card.summary }}</div>
          <div v-if="card.assignee" class="card-assignee">
            <Avatar :name="card.assignee.name" :idx="0" :size="18" />
            <span>{{ card.assignee.name }}</span>
          </div>
        </div>

        <!-- Quick Add -->
        <template v-if="quickAdd">
          <div v-if="quickAddCol === col.id" class="quick-add-form">
            <input
              v-model="quickAddSummary"
              class="quick-add-input"
              placeholder="Task summary…"
              autofocus
              @keydown.enter="quickAddTask(col)"
              @keydown.escape="quickAddCol = null"
            >
            <div class="quick-add-actions">
              <Button
                size="sm"
                primary
                :accent="accent"
                :disabled="!quickAddSummary.trim() || saving"
                @click="quickAddTask(col)">Add</Button>
              <Button size="sm" @click="quickAddCol = null">Cancel</Button>
            </div>
          </div>
          <button v-else class="quick-add-btn" @click="quickAddCol = col.id">
            <Icon name="plus" :size="12" color="var(--fg-3)" /> Add task
          </button>
        </template>

        <div v-if="!cardsForColumn(col).length && !quickAdd" class="col-empty">No tasks</div>
        <div v-if="!cardsForColumn(col).length && quickAdd && quickAddCol !== col.id" class="col-empty">No tasks</div>
      </div>
    </div>
  </div>

  <div v-else-if="cardsLoading" class="board-loading">Loading board…</div>

  <div v-else-if="!sortedColumns.length" class="board-empty">
    <Icon name="columns" :size="32" color="var(--fg-4)" />
    <p>This board has no columns configured.</p>
    <slot name="empty-action" />
  </div>
</template>

<style scoped>
.board-loading,
.board-empty {
  color: var(--fg-3);
  text-align: center;
  padding: 60px 0;
  font-size: 14px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

/* Stats */
.sprint-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 12px;
  background: var(--bg-1);
  border: 1px solid var(--border-1);
  border-radius: 8px;
  font-size: 13px;
}

.sprint-bar-name {
  font-weight: 600;
  color: var(--fg-0);
}

.sprint-bar-days {
  font-size: 12px;
  font-weight: 500;
}

.sprint-bar-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--fg-3);
}

.board-stats {
  margin-bottom: 16px;
}

.stats-bar {
  display: flex;
  height: 6px;
  border-radius: 3px;
  overflow: hidden;
  gap: 2px;
}

.stats-segment {
  border-radius: 3px;
  transition: flex 0.3s;
}

.stats-legend {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 8px;
  flex-wrap: wrap;
}

.stats-total {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.stats-item {
  font-size: 11px;
  color: var(--fg-3);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.stats-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

/* Board */
.board {
  display: flex;
  gap: 12px;
  overflow-x: auto;
  padding-bottom: 16px;
  min-height: 400px;
}

.board-column {
  flex: 0 0 280px;
  background: var(--bg-1);
  border: 2px solid var(--line);
  border-radius: var(--r-md);
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 200px);
  transition: border-color 0.15s, background 0.15s;
}

.board-column.drop-valid {
  border-color: v-bind(accent);
  background: color-mix(in oklch, v-bind(accent) 6%, var(--bg-1));
}

.board-column.drop-invalid {
  border-color: var(--err);
  background: color-mix(in oklch, var(--err) 4%, var(--bg-1));
}

.col-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
}

.col-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--fg-0);
  flex: 1;
}

.col-count {
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-2);
  background: var(--bg-3);
  padding: 1px 6px;
  border-radius: 8px;
}

.col-wip {
  font-size: 11px;
  color: var(--fg-3);
}

.col-wip.exceeded {
  color: var(--err);
  font-weight: 600;
}

.col-cards {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.col-empty {
  font-size: 12px;
  color: var(--fg-4);
  text-align: center;
  padding: 20px 0;
}

/* Cards */
.board-card {
  background: var(--bg-0);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  cursor: grab;
  display: flex;
  flex-direction: column;
  gap: 6px;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.board-card:hover {
  border-color: v-bind(accent);
}

.board-card:active {
  cursor: grabbing;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}

.card-top {
  display: flex;
  align-items: center;
  gap: 6px;
}

.card-key {
  font-size: 10.5px;
  color: var(--fg-3);
}

.card-prio {
  margin-left: auto;
}

.card-summary {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--fg-0);
  line-height: 1.4;
}

.card-assignee {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--fg-2);
}

/* Quick Add */
.quick-add-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--fg-3);
  cursor: pointer;
  transition: background 0.1s, color 0.1s;
}

.quick-add-btn:hover {
  background: var(--bg-2);
  color: var(--fg-1);
}

.quick-add-form {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.quick-add-input {
  width: 100%;
  font-size: 12.5px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--bg-0);
  color: var(--fg-0);
}

.quick-add-input:focus {
  outline: none;
  border-color: v-bind(accent);
}

.quick-add-actions {
  display: flex;
  gap: 6px;
}
</style>
