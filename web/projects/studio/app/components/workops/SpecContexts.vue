<script setup lang="ts">
import gql from 'graphql-tag'

interface SpecContext {
  id: string
  specId: string
  contextType: string
  targetId: string
  label: string | null
  attributes: Record<string, unknown> | null
  addedByProfileId: string
  createdAt: string
}

const props = defineProps<{
  specId: string
  contexts: SpecContext[]
  accent: string
}>()

const emit = defineEmits<{
  updated: []
}>()

const { query: gqlQuery, mutation } = useGraphQL()
const toast = useToast()

const resolvedNames = ref<Record<string, string>>({})

async function resolveContextName(ctx: SpecContext): Promise<string | null> {
  try {
    switch (ctx.contextType) {
      case 'METADATA': {
        const r = await gqlQuery<{ content: { metadata: { name: string } | null } }>(gql`
          query($id: UUID!) { content { metadata(id: $id) { name } } }
        `, { id: ctx.targetId })
        return r.content?.metadata?.name ?? null
      }
      case 'COLLECTION': {
        const r = await gqlQuery<{ content: { collections: { collection: { name: string } | null } } }>(gql`
          query($id: UUID!) { content { collections { collection(id: $id) { name } } } }
        `, { id: ctx.targetId })
        return r.content?.collections?.collection?.name ?? null
      }
      case 'PROFILE': {
        const r = await gqlQuery<{ profiles: { profile: { name: string } | null } }>(gql`
          query($id: UUID!) { profiles { profile(id: $id) { name } } }
        `, { id: ctx.targetId })
        return r.profiles?.profile?.name ?? null
      }
      case 'SPEC': {
        const r = await gqlQuery<{ workOps: { specs: { spec: { key: string; metadataId: string } | null } } }>(gql`
          query($id: UUID!) { workOps { specs { spec(id: $id) { key metadataId } } } }
        `, { id: ctx.targetId })
        const spec = r.workOps?.specs?.spec
        if (!spec) return null
        const mr = await gqlQuery<{ content: { metadata: { name: string } | null } }>(gql`
          query($id: UUID!) { content { metadata(id: $id) { name } } }
        `, { id: spec.metadataId })
        return mr.content?.metadata?.name ?? spec.key
      }
      case 'TASK': {
        const r = await gqlQuery<{ workOps: { tasks: { task: { key: string; summary: string } | null } } }>(gql`
          query($id: UUID!) { workOps { tasks { task(id: $id) { key summary } } } }
        `, { id: ctx.targetId })
        const task = r.workOps?.tasks?.task
        return task ? `${task.key} — ${task.summary}` : null
      }
      case 'PROJECT': {
        const r = await gqlQuery<{ workOps: { projects: { project: { name: string } | null } } }>(gql`
          query($id: UUID!) { workOps { projects { project(id: $id) { name } } } }
        `, { id: ctx.targetId })
        return r.workOps?.projects?.project?.name ?? null
      }
      default:
        return null
    }
  } catch {
    return null
  }
}

async function resolveAllNames() {
  const toResolve = props.contexts.filter(c =>
    !c.label && c.contextType !== 'EXTERNAL_URI' && !resolvedNames.value[c.id],
  )
  if (!toResolve.length) return
  const results = await Promise.all(toResolve.map(async (ctx) => {
    const name = await resolveContextName(ctx)
    return { id: ctx.id, name }
  }))
  const updated = { ...resolvedNames.value }
  for (const { id, name } of results) {
    if (name) updated[id] = name
  }
  resolvedNames.value = updated
}

function displayName(ctx: SpecContext): string {
  if (ctx.label) return ctx.label
  return resolvedNames.value[ctx.id] ?? ctx.targetId
}

watch(() => props.contexts, () => resolveAllNames(), { immediate: true })

const saving = ref(false)
const showAdd = ref(false)
const newContextType = ref('EXTERNAL_URI')
const newTargetId = ref('')
const newLabel = ref('')

const CONTEXT_TYPE_OPTIONS = [
  { value: 'GIT_RESOURCE', label: 'Git Resource' },
  { value: 'METADATA', label: 'Content Item' },
  { value: 'COLLECTION', label: 'Collection' },
  { value: 'PROFILE', label: 'Person' },
  { value: 'CHAT_CHANNEL', label: 'Chat Channel' },
  { value: 'AI_SESSION', label: 'AI Session' },
  { value: 'SPEC', label: 'Spec' },
  { value: 'TASK', label: 'Task' },
  { value: 'PROJECT', label: 'Project' },
  { value: 'EXTERNAL_URI', label: 'External Link' },
]

const CONTEXT_ICONS: Record<string, string> = {
  GIT_RESOURCE: 'git-branch',
  METADATA: 'file',
  COLLECTION: 'folder',
  PROFILE: 'user',
  CHAT_CHANNEL: 'message-circle',
  AI_SESSION: 'cpu',
  SPEC: 'file-text',
  TASK: 'check-square',
  PROJECT: 'briefcase',
  EXTERNAL_URI: 'external-link',
}

const CONTEXT_COLORS: Record<string, string> = {
  GIT_RESOURCE: '#f97316',
  METADATA: '#5ec5ff',
  COLLECTION: '#a78bff',
  PROFILE: '#34d99a',
  CHAT_CHANNEL: '#ffb547',
  AI_SESSION: '#ff5d6c',
  SPEC: '#5ec5ff',
  TASK: '#34d99a',
  PROJECT: '#a78bff',
  EXTERNAL_URI: '#6c7388',
}

async function addContext() {
  if (!newTargetId.value.trim()) return
  saving.value = true
  try {
    await mutation(gql`
      mutation AddContext($specId: UUID!, $input: CreateWorkOpsSpecContextInput!) {
        workOps { specs { addContext(specId: $specId, input: $input) { id } } }
      }
    `, {
      specId: props.specId,
      input: {
        contextType: newContextType.value,
        targetId: newTargetId.value.trim(),
        label: newLabel.value.trim() || null,
      },
    })
    newTargetId.value = ''
    newLabel.value = ''
    showAdd.value = false
    toast.success('Context added')
    emit('updated')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to add context')
  } finally {
    saving.value = false
  }
}

async function removeContext(contextId: string) {
  saving.value = true
  try {
    await mutation(gql`
      mutation RemoveContext($specId: UUID!, $contextId: UUID!) {
        workOps { specs { removeContext(specId: $specId, contextId: $contextId) } }
      }
    `, { specId: props.specId, contextId })
    emit('updated')
  } catch {
    toast.error('Failed to remove context')
  } finally {
    saving.value = false
  }
}

function contextLink(ctx: SpecContext): string | null {
  switch (ctx.contextType) {
    case 'SPEC': return `/workops/specs/${ctx.targetId}`
    case 'TASK': return `/workops/tasks/${ctx.targetId}`
    case 'PROJECT': return `/workops/projects/${ctx.targetId}`
    case 'METADATA': return `/cms/editor/${ctx.targetId}`
    case 'PROFILE': return `/audience/profiles/${ctx.targetId}`
    case 'COLLECTION': return `/cms/collections/${ctx.targetId}`
    case 'EXTERNAL_URI': return ctx.targetId
    default: return null
  }
}
</script>

<template>
  <SectionCard title="Contexts" glass class="hoverable-card">
    <template #right>
      <button class="edit-link" @click="showAdd = !showAdd">
        <Icon name="plus" :size="12" color="var(--fg-3)" />
      </button>
    </template>

    <div class="card-body">
      <!-- Grouped by type -->
      <template v-for="typeOpt in CONTEXT_TYPE_OPTIONS" :key="typeOpt.value">
        <template v-if="contexts.filter(c => c.contextType === typeOpt.value).length">
          <div class="context-type-header">
            <Icon :name="CONTEXT_ICONS[typeOpt.value] || 'link'" :size="11" :color="CONTEXT_COLORS[typeOpt.value] || 'var(--fg-3)'" />
            <span>{{ typeOpt.label }}</span>
          </div>
          <div
            v-for="ctx in contexts.filter(c => c.contextType === typeOpt.value)"
            :key="ctx.id"
            class="context-row"
          >
            <div class="context-indicator" :style="{ background: CONTEXT_COLORS[ctx.contextType] || 'var(--fg-3)' }" />
            <div class="context-info">
              <component
                :is="contextLink(ctx) ? 'a' : 'span'"
                :href="ctx.contextType === 'EXTERNAL_URI' ? ctx.targetId : undefined"
                :target="ctx.contextType === 'EXTERNAL_URI' ? '_blank' : undefined"
                class="context-label"
                :class="{ 'context-link': contextLink(ctx) }"
                @click.prevent="contextLink(ctx) && ctx.contextType !== 'EXTERNAL_URI' && $router.push(contextLink(ctx)!)"
              >
                {{ displayName(ctx) }}
              </component>
              <span v-if="ctx.label || resolvedNames[ctx.id]" class="context-target">{{ ctx.targetId }}</span>
            </div>
            <button class="context-remove" @click="removeContext(ctx.id)">
              <Icon name="x" :size="10" color="var(--fg-3)" />
            </button>
          </div>
        </template>
      </template>

      <div v-if="!contexts.length && !showAdd" class="empty-msg">No contexts linked.</div>

      <!-- Add form -->
      <div v-if="showAdd" class="add-context-form">
        <div class="add-context-row">
          <Select
            v-model="newContextType"
            :options="CONTEXT_TYPE_OPTIONS"
            size="sm"
            class="context-type-select"
          />
          <input
            v-model="newTargetId"
            class="context-id-input"
            :placeholder="newContextType === 'EXTERNAL_URI' ? 'https://…' : 'ID or key'"
            @keydown.enter="addContext"
            @keydown.escape="showAdd = false"
          >
        </div>
        <div class="add-context-row">
          <input
            v-model="newLabel"
            class="context-id-input"
            placeholder="Label (optional)"
          >
          <Button
            size="sm"
            primary
            :accent="accent"
            :disabled="!newTargetId.trim() || saving"
            @click="addContext">Add</Button>
          <Button size="sm" @click="showAdd = false">Cancel</Button>
        </div>
      </div>
    </div>
  </SectionCard>
</template>

<style scoped>
.card-body {
  padding: 8px 16px 14px;
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

.edit-link:hover { background: var(--bg-2); }

.context-type-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  font-weight: 600;
  padding: 10px 0 4px;
}

.context-type-header:first-child {
  padding-top: 4px;
}

.context-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 4px 6px 12px;
  border-radius: 4px;
  transition: background 0.1s;
}

.context-row:hover {
  background: color-mix(in oklch, var(--fg-0) 3%, transparent);
}

.context-indicator {
  width: 3px;
  height: 20px;
  border-radius: 2px;
  flex-shrink: 0;
}

.context-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.context-label {
  font-size: 13px;
  color: var(--fg-0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.context-link {
  color: v-bind(accent);
  cursor: pointer;
  text-decoration: none;
}

.context-link:hover {
  text-decoration: underline;
}

.context-target {
  font-size: 11px;
  color: var(--fg-4);
  font-family: var(--font-mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.context-remove {
  opacity: 0;
  padding: 4px;
  border-radius: 4px;
  transition: opacity 0.15s;
  flex-shrink: 0;
}

.context-row:hover .context-remove {
  opacity: 1;
}

.context-remove:hover {
  background: var(--bg-2);
}

.empty-msg {
  color: var(--fg-3);
  font-size: 13px;
  padding: 12px 0;
}

.add-context-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
}

.add-context-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.context-type-select {
  width: 150px;
  flex-shrink: 0;
}

.context-id-input {
  flex: 1;
  font-size: 13px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 7px 12px;
  color: var(--fg-0);
}

.context-id-input:focus {
  outline: none;
  border-color: v-bind(accent);
}
</style>
