<script setup lang="ts">
import gql from 'graphql-tag'
import type { HarnessSourceSelection } from '~/utils/kitHarness'

const props = defineProps<{ git?: boolean }>()
const emit = defineEmits<{ close: []; select: [source: HarnessSourceSelection] }>()
const { query } = useGraphQL()
interface Row { id: string; title: string }
const projects = ref<Row[]>([])
const project = ref('')
const kind = ref<'TASK' | 'SPEC' | 'REQUIREMENT'>('TASK')
const selectedSpec = ref<Row | null>(null)
const offset = ref(0)
const rows = ref<Row[]>([])
const count = ref(0)
const loading = ref(false)
const error = ref('')
const pageSize = 20
let revision = 0

onMounted(async () => {
  if (props.git) return
  try {
    const result = await query<{ workOps: { projects: { all: { id: string; name: string; archivedAt: string | null }[] } } }>(gql`
      query ContextProjects { workOps { projects { all { id name archivedAt } } } }
    `)
    projects.value = result.workOps.projects.all.filter(item => !item.archivedAt).map(item => ({ id: item.id, title: item.name }))
  } catch { error.value = 'Projects could not be loaded.' }
})

watch([project, kind], () => { selectedSpec.value = null; offset.value = 0 })
watch(selectedSpec, () => { offset.value = 0 })
watch([project, kind, selectedSpec, offset], async () => {
  const current = ++revision
  rows.value = []; count.value = 0; error.value = ''
  loading.value = false
  if (!project.value) return
  loading.value = true
  try {
    let result: Row[]
    let total: number
    if (kind.value === 'TASK') {
      const data = await query<{ workOps: { tasks: { byProject: { id: string; summary: string }[] } } }>(gql`
        query ContextTasks($project: UUID!, $offset: Long!, $limit: Int!) {
          workOps { tasks { byProject(projectId: $project, offset: $offset, limit: $limit) { id summary } } }
        }
      `, { project: project.value, offset: offset.value, limit: pageSize })
      total = data.workOps.tasks.byProject.length
      result = data.workOps.tasks.byProject.map(item => ({ id: item.id, title: item.summary }))
    } else if (selectedSpec.value && kind.value === 'REQUIREMENT') {
      const data = await query<{ workOps: { requirements: { bySpec: { id: string; metadata: { name: string } | null }[] } } }>(gql`
        query ContextRequirements($spec: UUID!, $offset: Long!, $limit: Int!) {
          workOps { requirements { bySpec(specId: $spec, offset: $offset, limit: $limit) { id metadata { name } } } }
        }
      `, { spec: selectedSpec.value.id, offset: offset.value, limit: pageSize })
      total = data.workOps.requirements.bySpec.length
      result = data.workOps.requirements.bySpec.flatMap(item => item.metadata ? [{ id: item.id, title: item.metadata.name }] : [])
    } else {
      const data = await query<{ workOps: { specs: { byProject: { id: string; metadata: { name: string } | null }[] } } }>(gql`
        query ContextSpecs($project: UUID!, $offset: Long!, $limit: Int!) {
          workOps { specs { byProject(projectId: $project, offset: $offset, limit: $limit) { id metadata { name } } } }
        }
      `, { project: project.value, offset: offset.value, limit: pageSize })
      total = data.workOps.specs.byProject.length
      result = data.workOps.specs.byProject.flatMap(item => item.metadata ? [{ id: item.id, title: item.metadata.name }] : [])
    }
    if (current === revision) { rows.value = result; count.value = total }
  } catch { if (current === revision) error.value = 'Sources could not be loaded. Check your access and try again.' }
  finally { if (current === revision) loading.value = false }
})

function choose(row: Row) {
  if (kind.value === 'REQUIREMENT' && !selectedSpec.value) selectedSpec.value = row
  else emit('select', { kind: kind.value, id: row.id })
}
</script>

<template>
  <Modal :title="git ? 'Add Git file' : 'Add WorkOps context'" @close="emit('close')">
    <GitFilePicker v-if="git" @select="source => emit('select', { kind: 'GIT_FILE', id: source.repositoryId, ref: source.ref, path: source.path })" />
    <div v-else class="source-picker">
      <Select
        v-model="project"
        label="Project"
        :options="projects.map(item => ({ value: item.id, label: item.title }))"
        placeholder="Choose a project" />
      <Select v-model="kind" label="Source" :options="[{ value: 'TASK', label: 'Task' }, { value: 'SPEC', label: 'Specification' }, { value: 'REQUIREMENT', label: 'Requirement' }]" />
      <template v-if="kind === 'REQUIREMENT'">
        <p v-if="!selectedSpec">Choose the specification containing the requirement.</p>
        <Button v-else size="sm" @click="selectedSpec = null">Back to specifications · {{ selectedSpec.title }}</Button>
      </template>
      <p v-if="loading" role="status">Loading sources…</p>
      <p v-else-if="error" role="alert">{{ error }}</p>
      <p v-else-if="project && !rows.length">No sources with visible names on this page.</p>
      <ul v-if="!loading" aria-label="Context source choices">
        <li v-for="row in rows" :key="row.id"><Button size="sm" variant="ghost" @click="choose(row)">{{ row.title }}</Button></li>
      </ul>
      <ListPager
        v-if="project"
        :offset="offset"
        :page-size="pageSize"
        :count="count"
        :has-more="count === pageSize"
        @update:offset="offset = $event" />
    </div>
  </Modal>
</template>

<style scoped>
.source-picker { display: flex; flex-direction: column; gap: 12px; }
.source-picker ul { padding: 0; margin: 0; list-style: none; }
.source-picker li { margin: 4px 0; }
.source-picker p { color: var(--fg-2); font-size: 13px; }
</style>
