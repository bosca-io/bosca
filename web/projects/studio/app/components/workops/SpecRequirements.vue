<script setup lang="ts">
import gql from 'graphql-tag'

const props = defineProps<{
  specId: string
  accent: string
}>()

const emit = defineEmits<{
  updated: []
}>()

const { query: gqlQuery, mutation } = useGraphQL()
const toast = useToast()
const { searchProfiles } = useProfileSearch()

const saving = ref(false)
const showNewReq = ref(false)
const newReqName = ref('')
const editingReqId = ref<string | null>(null)

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

// ─── Requirements ────────────────────────────────────────────────────────────

interface Requirement {
  id: string
  key: string
  metadataId: string
  status: { id: string; name: string; category: string }
  priority: { id: string; name: string; displayOrder: number }
  assignee: { id: string; name: string } | null
  assigneeProfileId: string | null
  sortOrder: number
  labelIds: string[]
  createdAt: string
  modifiedAt: string
  version: number
}

const requirements = ref<Requirement[]>([])
const reqNames = ref<Record<string, string>>({})

const requirementsGql = gql`
  query GetRequirements($specId: UUID!, $offset: Long!, $limit: Int!) {
    workOps { requirements { bySpec(specId: $specId, offset: $offset, limit: $limit) {
      id key metadataId
      status { id name category }
      priority { id name displayOrder }
      assignee { id name }
      assigneeProfileId
      sortOrder labelIds createdAt modifiedAt version
    } } }
  }
`

async function loadRequirements() {
  try {
    const result = await gqlQuery<{
      workOps: { requirements: { bySpec: Requirement[] } }
    }>(requirementsGql, { specId: props.specId, offset: 0, limit: 100 })
    requirements.value = (result.workOps?.requirements?.bySpec ?? [])
      .sort((a, b) => a.sortOrder - b.sortOrder)
    await resolveReqNames()
  } catch {
    requirements.value = []
  }
}

async function resolveReqNames() {
  const resolved: Record<string, string> = { ...reqNames.value }
  for (const r of requirements.value) {
    if (resolved[r.metadataId]) continue
    try {
      const result = await gqlQuery<{ content: { metadata: { name: string } | null } }>(gql`
        query($id: UUID!) { content { metadata(id: $id) { name } } }
      `, { id: r.metadataId })
      if (result.content?.metadata?.name) resolved[r.metadataId] = result.content.metadata.name
    } catch { /* skip */ }
  }
  reqNames.value = resolved
}

onMounted(() => { loadRequirements() })
watch(() => props.specId, () => { loadRequirements() })

// ─── Progress ────────────────────────────────────────────────────────────────

const doneCount = computed(() => requirements.value.filter(r => r.status.category === 'DONE').length)
const totalCount = computed(() => requirements.value.length)
const progressPct = computed(() => totalCount.value === 0 ? 0 : Math.round((doneCount.value / totalCount.value) * 100))

// ─── Create ──────────────────────────────────────────────────────────────────

async function createRequirement() {
  if (!newReqName.value.trim()) return
  saving.value = true
  try {
    const metaResult = await mutation<{ content: { metadata: { add: { id: string } } } }>(gql`
      mutation($metadata: MetadataInput!) {
        content { metadata { add(metadata: $metadata) { id } } }
      }
    `, { metadata: { name: newReqName.value.trim(), contentType: 'bosca/v-document', languageTag: 'en' } })

    await mutation(gql`
      mutation($input: CreateWorkOpsRequirementInput!) {
        workOps { requirements { create(input: $input) { id } } }
      }
    `, {
      input: {
        metadataId: metaResult.content.metadata.add.id,
        parentType: 'SPEC',
        parentId: props.specId,
        sortOrder: requirements.value.length,
      },
    })
    newReqName.value = ''
    showNewReq.value = false
    await loadRequirements()
    emit('updated')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create requirement')
  } finally {
    saving.value = false
  }
}

// ─── Update assignee ─────────────────────────────────────────────────────────

async function updateAssignee(req: Requirement, profileId: string) {
  saving.value = true
  try {
    await mutation(gql`
      mutation($id: UUID!, $input: UpdateWorkOpsRequirementInput!) {
        workOps { requirements { update(id: $id, input: $input) { id } } }
      }
    `, {
      id: req.id,
      input: {
        assigneeProfileId: profileId || null,
        clearAssignee: !profileId,
        expectedVersion: req.version,
      },
    })
    editingReqId.value = null
    await loadRequirements()
    emit('updated')
  } catch { toast.error('Failed to update assignee') }
  finally { saving.value = false }
}

// ─── Delete ──────────────────────────────────────────────────────────────────

async function deleteRequirement(req: Requirement) {
  saving.value = true
  try {
    await mutation(gql`
      mutation($id: UUID!, $expectedVersion: Long!) {
        workOps { requirements { softDelete(id: $id, expectedVersion: $expectedVersion) { id } } }
      }
    `, { id: req.id, expectedVersion: req.version })
    await loadRequirements()
    emit('updated')
  } catch { toast.error('Failed to delete requirement') }
  finally { saving.value = false }
}

defineExpose({ refresh: loadRequirements })
</script>

<template>
  <SectionCard title="Requirements" glass class="hoverable-card">
    <template #right>
      <div class="req-header-right">
        <div v-if="totalCount > 0" class="req-progress-badge">
          <div class="req-progress-track">
            <div class="req-progress-fill" :style="{ width: `${progressPct}%` }" />
          </div>
          <span class="req-progress-text">{{ doneCount }}/{{ totalCount }}</span>
        </div>
        <button class="edit-link" @click="showNewReq = true">
          <Icon name="plus" :size="12" color="var(--fg-3)" />
        </button>
      </div>
    </template>

    <div class="card-body">
      <div v-for="req in requirements" :key="req.id" class="req-row">
        <div class="req-check" :class="{ done: req.status.category === 'DONE' }">
          <Icon
            v-if="req.status.category === 'DONE'"
            name="check"
            :size="10"
            color="#34d99a" />
        </div>
        <NuxtLink :to="`/workops/requirements/${req.id}`" class="req-content">
          <Icon
            name="file"
            :size="12"
            color="var(--fg-3)"
            class="req-doc-icon" />
          <span class="req-key">{{ req.key }}</span>
          <span class="req-name" :class="{ 'req-done': req.status.category === 'DONE' }">
            {{ reqNames[req.metadataId] || 'Untitled' }}
          </span>
        </NuxtLink>
        <Badge v-if="req.priority" :color="PRIORITY_COLORS[req.priority.displayOrder] || '#6c7388'" class="req-badge">
          {{ req.priority.name }}
        </Badge>
        <Badge :color="STATUS_COLORS[req.status.category] || '#6c7388'" class="req-badge">
          {{ req.status.name }}
        </Badge>
        <template v-if="editingReqId === req.id">
          <Select
            :model-value="req.assigneeProfileId ?? ''"
            :on-search="searchProfiles"
            searchable
            placeholder="Assignee…"
            size="sm"
            class="req-assignee-select"
            @update:model-value="updateAssignee(req, $event as string)"
          />
        </template>
        <template v-else>
          <div class="req-assignee" @click="editingReqId = req.id">
            <Avatar
              v-if="req.assignee"
              :name="req.assignee.name"
              :idx="0"
              :size="16" />
            <span v-else class="req-unassigned">—</span>
          </div>
        </template>
        <button class="req-delete" @click="deleteRequirement(req)">
          <Icon name="trash" :size="11" color="var(--fg-3)" />
        </button>
      </div>

      <div v-if="!requirements.length && !showNewReq" class="empty-msg" @click="showNewReq = true">
        No requirements yet. Click to add one.
      </div>

      <div v-if="showNewReq" class="new-req-row">
        <input
          v-model="newReqName"
          class="new-req-input"
          placeholder="Describe the requirement…"
          autofocus
          @keydown.enter="createRequirement"
          @keydown.escape="showNewReq = false"
        >
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newReqName.trim() || saving"
          @click="createRequirement">
          Add
        </Button>
        <Button size="sm" @click="showNewReq = false">Cancel</Button>
      </div>
    </div>
  </SectionCard>
</template>

<style scoped>
.card-body {
  padding: 8px 16px 14px;
}

.req-header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.req-progress-badge {
  display: flex;
  align-items: center;
  gap: 6px;
}

.req-progress-track {
  width: 48px;
  height: 4px;
  background: var(--bg-3);
  border-radius: 2px;
  overflow: hidden;
}

.req-progress-fill {
  height: 100%;
  background: #34d99a;
  border-radius: 2px;
  transition: width 0.3s ease;
}

.req-progress-text {
  font-size: 11px;
  color: var(--fg-3);
  font-family: var(--font-mono);
}

.hoverable-card .edit-link {
  opacity: 0;
  transition: opacity 0.15s;
}

.hoverable-card:hover .edit-link {
  opacity: 1;
}

.edit-link {
  display: inline-flex;
  align-items: center;
  padding: 4px 8px;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.15s;
}

.edit-link:hover {
  background: var(--bg-2);
}

.req-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--line);
  transition: background 0.1s;
}

.req-row:last-child { border-bottom: none; }

.req-row:hover {
  background: color-mix(in oklch, var(--fg-0) 3%, transparent);
}

.req-check {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 2px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: border-color 0.15s;
}

.req-check.done {
  border-color: #34d99a;
  background: color-mix(in oklch, #34d99a 15%, transparent);
}

.req-content {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  text-decoration: none;
  color: inherit;
}

.req-content:hover .req-name {
  color: v-bind(accent);
}

.req-key {
  font-family: var(--font-mono);
  font-size: 10px;
  padding: 1px 5px;
  background: var(--bg-3);
  border-radius: 3px;
  color: var(--fg-3);
  flex-shrink: 0;
}

.req-name {
  font-size: 13px;
  color: var(--fg-0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.req-done {
  text-decoration: line-through;
  color: var(--fg-3);
}

.req-badge {
  flex-shrink: 0;
}

.req-assignee {
  cursor: pointer;
  padding: 2px;
  border-radius: 4px;
  transition: background 0.15s;
  flex-shrink: 0;
}

.req-assignee:hover {
  background: var(--bg-2);
}

.req-unassigned {
  font-size: 12px;
  color: var(--fg-4);
}

.req-assignee-select {
  width: 140px;
  flex-shrink: 0;
}

.req-delete {
  opacity: 0;
  padding: 4px;
  transition: opacity 0.15s;
  flex-shrink: 0;
}

.req-row:hover .req-delete {
  opacity: 1;
}

.empty-msg {
  color: var(--fg-3);
  font-size: 13px;
  padding: 16px 0;
  cursor: pointer;
  font-style: italic;
}

.empty-msg:hover {
  color: var(--fg-2);
}

.new-req-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
}

.new-req-input {
  flex: 1;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 8px 12px;
  color: var(--fg-0);
}

.new-req-input:focus {
  outline: none;
  border-color: v-bind(accent);
}
</style>
