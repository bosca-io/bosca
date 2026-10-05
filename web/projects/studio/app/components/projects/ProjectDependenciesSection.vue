<script setup lang="ts">
import gql from 'graphql-tag'

/**
 * A project's declared dependencies: "this project depends on that one." The
 * declaration drives release build order (providers build before consumers) and the
 * compatibility/build-readiness machinery. Also shows the inverse — who depends on THIS project.
 */

const props = defineProps<{ projectId: string; accent?: string }>()

const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface Declaration {
  id: string; consumerProjectId: string; providerProjectId: string
  providerVersionConstraint: string; dependencyType: string; status: string; version: number
}
interface ProjectRef { id: string; key: string; name: string }

const listGql = gql`
  query ProjectDependencies($projectId: UUID!) {
    workOps { multiRepo {
      dependencies(projectId: $projectId) { id consumerProjectId providerProjectId providerVersionConstraint dependencyType status version }
      consumers(projectId: $projectId) { id consumerProjectId providerProjectId providerVersionConstraint dependencyType status version }
    } }
  }
`
const { data, refresh } = useAsyncQuery<{
  workOps: { multiRepo: { dependencies: Declaration[]; consumers: Declaration[] } }
}>('project-dependencies', listGql, { projectId: computed(() => props.projectId) })
const dependencies = computed(() => data.value?.workOps?.multiRepo?.dependencies ?? [])
const consumers = computed(() => data.value?.workOps?.multiRepo?.consumers ?? [])

// Project names for both directions + the provider picker.
const projectsGql = gql`query { workOps { projects { all { id key name } } } }`
const { data: projectsData } = useAsyncQuery<{ workOps: { projects: { all: ProjectRef[] } } }>('workops-projects-refs', projectsGql)
const projectsById = computed(() => new Map((projectsData.value?.workOps?.projects?.all ?? []).map(p => [p.id, p])))
function projectLabel(id: string): string {
  const p = projectsById.value.get(id)
  return p ? `${p.key} — ${p.name}` : id.slice(0, 8)
}

// ── Declare ───────────────────────────────────────────────────────────────────
const showAdd = ref(false)
const providerId = ref('')
const dependencyType = ref('BUILD')
const constraint = ref('*')
const saving = ref(false)

const providerOptions = computed(() =>
  (projectsData.value?.workOps?.projects?.all ?? [])
    .filter(p => p.id !== props.projectId && !dependencies.value.some(d => d.providerProjectId === p.id))
    .map(p => ({ value: p.id, label: `${p.key} — ${p.name}` })),
)
const typeOptions = [
  { value: 'BUILD', label: 'Build — must build (and release) first' },
  { value: 'RUNTIME', label: 'Runtime — needed when running' },
  { value: 'CONTRACT', label: 'Contract — API compatibility only' },
]

async function declare() {
  if (!providerId.value) return
  saving.value = true
  try {
    await mutation(gql`
      mutation DeclareDependency($input: CreateWorkOpsDependencyDeclarationInput!) {
        workOps { multiRepo { declareDependency(input: $input) { id } } }
      }
    `, {
      input: {
        consumerProjectId: props.projectId,
        providerProjectId: providerId.value,
        providerVersionConstraint: constraint.value.trim() || '*',
        dependencyType: dependencyType.value,
      },
    })
    toast.success('Dependency declared')
    showAdd.value = false
    providerId.value = ''
    constraint.value = '*'
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to declare the dependency')
  } finally {
    saving.value = false
  }
}

// ── Remove ───────────────────────────────────────────────────────────────────
const removing = ref('')
async function remove(dep: Declaration) {
  removing.value = dep.id
  try {
    await mutation(gql`
      mutation RemoveDependency($id: UUID!) {
        workOps { multiRepo { removeDependency(id: $id) } }
      }
    `, { id: dep.id })
    toast.success('Dependency removed')
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to remove the dependency')
  } finally {
    removing.value = ''
  }
}

const STATUS_COLORS: Record<string, string> = {
  CURRENT: 'var(--ok, #34d399)',
  OUTDATED: '#ffb547',
  INCOMPATIBLE: 'var(--err, #f87171)',
}
</script>

<template>
  <div class="deps-layout">
    <SectionCard
      title="Depends on"
      subtitle="Providers build and deploy before this project in a release — declared once, enforced by pipeline requirements"
      padded>
      <template #right>
        <Button
          primary
          size="sm"
          icon="plus"
          :accent="accent"
          @click="showAdd = true">Declare dependency</Button>
      </template>
      <div v-if="dependencies.length" class="entity-list">
        <div v-for="d in dependencies" :key="d.id" class="entity-row">
          <Icon name="git-merge" :size="15" :color="accent" />
          <div class="entity-info">
            <span class="entity-name">{{ projectLabel(d.providerProjectId) }}</span>
            <span class="entity-desc mono">{{ d.providerVersionConstraint }}</span>
          </div>
          <Badge :color="accent">{{ d.dependencyType }}</Badge>
          <Badge :color="STATUS_COLORS[d.status]">{{ d.status }}</Badge>
          <Button
            size="sm"
            icon="trash"
            :disabled="removing === d.id"
            @click="remove(d)" />
        </div>
      </div>
      <div v-else class="empty-msg">No dependencies declared — this project builds in plain deployment order.</div>
    </SectionCard>

    <SectionCard title="Depended on by" subtitle="Projects that build after this one in a shared release" padded>
      <div v-if="consumers.length" class="entity-list">
        <div v-for="d in consumers" :key="d.id" class="entity-row">
          <Icon name="git-branch" :size="15" color="var(--fg-3)" />
          <div class="entity-info">
            <span class="entity-name">{{ projectLabel(d.consumerProjectId) }}</span>
            <span class="entity-desc mono">{{ d.providerVersionConstraint }}</span>
          </div>
          <Badge color="var(--fg-3)">{{ d.dependencyType }}</Badge>
        </div>
      </div>
      <div v-else class="empty-msg">Nothing depends on this project.</div>
    </SectionCard>

    <Modal
      v-if="showAdd"
      title="Declare dependency"
      icon="git-merge"
      :accent="accent"
      width="480px"
      @close="showAdd = false">
      <div class="add-form">
        <label class="form-label">This project depends on</label>
        <Select
          v-model="providerId"
          :options="providerOptions"
          placeholder="Provider project"
          :accent="accent" />
        <label class="form-label">Type</label>
        <Select v-model="dependencyType" :options="typeOptions" :accent="accent" />
        <label class="form-label">Version constraint</label>
        <TextInput v-model="constraint" placeholder="* (any version)" />
        <p class="hint">
          A release containing this project needs a provider version satisfying the constraint — in the
          same release, or already released. <span class="mono">*</span> accepts any released version;
          <span class="mono">6.0.*</span> is a prefix; <span class="mono">match</span> pins the provider
          to this project's own release version (Server 10.0.0 requires Workspace 10.0.0).
        </p>
      </div>
      <template #footer>
        <Button :disabled="saving" @click="showAdd = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving || !providerId"
          @click="declare">Declare</Button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.deps-layout { display: flex; flex-direction: column; gap: 16px; }
.entity-list { display: flex; flex-direction: column; gap: 6px; }
.entity-row {
  display: flex; align-items: center; gap: 10px; padding: 9px 12px;
  border: 1px solid var(--line); border-radius: 8px; background: var(--bg-1);
}
.entity-info { flex: 1; min-width: 0; display: flex; align-items: baseline; gap: 10px; }
.entity-name { color: var(--fg-0); font-size: 13px; font-weight: 500; }
.entity-desc { color: var(--fg-3); font-size: 11.5px; }
.empty-msg { padding: 18px 4px; font-size: 13px; color: var(--fg-3); }
.add-form { display: flex; flex-direction: column; gap: 8px; }
.form-label { font-size: 11.5px; color: var(--fg-3); margin-top: 6px; }
.hint { margin: 8px 0 0; font-size: 11.5px; color: var(--fg-3); line-height: 1.5; }
</style>
