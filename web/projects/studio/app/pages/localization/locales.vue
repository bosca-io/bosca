<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()

const projectsGql = gql`
  query GetLocalizationProjects {
    localization {
      projects {
        id
        name
        description
        sourceLanguage
        created
        modified
        languages {
          languageTag
        }
      }
    }
  }
`

interface ProjectSummary {
  id: string
  name: string
  description: string | null
  sourceLanguage: string
  created: string
  modified: string
  languages: { languageTag: string }[]
}

const { data, status, refresh } = useAsyncQuery<{
  localization: { projects: ProjectSummary[] }
}>('localization-projects', projectsGql)

const projects = computed(() => data.value?.localization?.projects ?? [])
const isLoading = computed(() => status.value === 'pending')

const projectColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Project', width: 'minmax(200px, 2fr)' },
  { key: 'sourceLanguage', label: 'Source', width: '100px' },
  { key: 'languages', label: 'Languages', width: '120px' },
  { key: 'modified', label: 'Modified', width: '100px', muted: true },
]

function formatRelative(dateStr: string): string {
  const ms = Date.now() - new Date(dateStr).getTime()
  const mins = Math.floor(ms / 60_000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  const days = Math.floor(hrs / 24)
  if (days < 30) return `${days}d ago`
  return new Date(dateStr).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

const showNewProject = ref(false)
const newProjectName = ref('')
const newProjectDesc = ref('')
const newProjectSource = ref('')
const projectSaving = ref(false)
const projectSaveError = ref('')

const addProjectGql = gql`
  mutation AddLocalizationProject($input: LocalizationProjectInput!) {
    localization {
      addProject(input: $input) {
        id
        name
      }
    }
  }
`

const sourceLanguagesGql = gql`
  query GetLanguagesForProjectModal {
    languages {
      all {
        tag
        name
      }
    }
  }
`

interface LanguageOption { tag: string; name: string }
const availableLanguages = ref<LanguageOption[]>([])

watch(showNewProject, async (open) => {
  if (open && availableLanguages.value.length === 0) {
    try {
      const { query } = useGraphQL()
      const result = await query<{ languages: { all: LanguageOption[] } }>(sourceLanguagesGql)
      availableLanguages.value = result.languages.all.slice().sort((a, b) => a.name.localeCompare(b.name))
      if (!newProjectSource.value) {
        newProjectSource.value = availableLanguages.value.find(l => l.tag === 'en')?.tag ?? availableLanguages.value[0]?.tag ?? ''
      }
    } catch { /* languages will remain empty */ }
  }
})

const canCreateProject = computed(() => newProjectName.value.trim().length > 0 && newProjectSource.value.length > 0)

async function handleCreateProject() {
  if (!canCreateProject.value || projectSaving.value) return
  projectSaving.value = true
  projectSaveError.value = ''
  try {
    await mutation(addProjectGql, {
      input: {
        name: newProjectName.value.trim(),
        description: newProjectDesc.value.trim() || null,
        sourceLanguage: newProjectSource.value,
      },
    })
    showNewProject.value = false
    newProjectName.value = ''
    newProjectDesc.value = ''
    refresh()
  } catch (e) {
    projectSaveError.value = e instanceof Error ? e.message : 'Failed to create project'
  } finally {
    projectSaving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Localization')"
        title="Projects"
        :subtitle="`${projects.length} project${projects.length !== 1 ? 's' : ''}`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showNewProject = true">New Project</Button>
        </template>
      </PageHeader>
    </template>

    <Modal
      v-if="showNewProject"
      title="New project"
      subtitle="Group translatable strings for an app or context"
      icon="plus"
      :accent="accent"
      @close="showNewProject = false"
    >
      <input
        v-model="newProjectName"
        autofocus
        placeholder="Project name"
        class="title-input" >
      <textarea
        v-model="newProjectDesc"
        rows="3"
        placeholder="Optional description…"
        class="desc-input" />
      <div class="modal-field">
        <div class="field-label">Source language</div>
        <select v-model="newProjectSource" class="field-select">
          <option v-if="availableLanguages.length === 0" value="" disabled>Loading…</option>
          <option v-for="l in availableLanguages" :key="l.tag" :value="l.tag">{{ l.name }} ({{ l.tag }})</option>
        </select>
      </div>
      <div v-if="projectSaveError" class="error-msg">{{ projectSaveError }}</div>

      <template #footer>
        <span class="shortcut-hint"><Icon name="key" :size="11" color="var(--fg-3)" /><span class="mono">⌘ ↵</span> to create</span>
        <span class="spacer" />
        <Button size="sm" @click="showNewProject = false">Cancel</Button>
        <Button
          primary
          size="sm"
          :accent="accent"
          icon="plus"
          :disabled="!canCreateProject || projectSaving"
          @click="handleCreateProject">
          {{ projectSaving ? 'Creating…' : 'Create project' }}
        </Button>
      </template>
    </Modal>

    <SectionCard title="Localization projects" glass>
      <GlassTable
        :columns="projectColumns"
        :rows="projects"
        :loading="isLoading && projects.length === 0"
        row-key="id"
        empty-text="No localization projects found."
        @row-click="(row) => navigateTo(`/localization/${row.id}`)"
      >
        <template #col-name="{ row }">
          <div class="project-name-cell">
            <div class="project-icon">
              <Icon name="languages" :size="16" :color="accent" />
            </div>
            <div>
              <div class="project-name">{{ row.name }}</div>
              <div v-if="row.description" class="project-desc">{{ row.description }}</div>
            </div>
          </div>
        </template>
        <template #col-sourceLanguage="{ row }">
          <span class="lang-badge">{{ row.sourceLanguage.toUpperCase() }}</span>
        </template>
        <template #col-languages="{ row }">
          <div class="lang-count-cell">
            <span class="mono tabular lang-count">{{ row.languages.length }}</span>
            <span class="lang-label">target{{ row.languages.length !== 1 ? 's' : '' }}</span>
          </div>
        </template>
        <template #col-modified="{ row }">
          {{ formatRelative(row.modified as string) }}
        </template>
      </GlassTable>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.project-name-cell { display: flex; align-items: center; gap: 12px; white-space: normal; }

.project-icon {
  width: 36px; height: 36px; border-radius: 8px;
  background: color-mix(in oklch, var(--bg-3) 30%, transparent); border: 1px solid color-mix(in oklch, var(--line) 38%, transparent);
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}

.project-name { font-weight: 500; color: var(--fg-0); }

.project-desc {
  font-size: 11.5px; color: var(--fg-3); margin-top: 1px;
  max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

.lang-badge {
  display: inline-block; padding: 3px 8px; border-radius: 4px;
  background: color-mix(in oklch, var(--bg-3) 30%, transparent); border: 1px solid color-mix(in oklch, var(--line) 38%, transparent);
  font-size: 10.5px; font-weight: 700; color: var(--fg-1);
  letter-spacing: 0.04em; font-family: var(--font-mono);
}

.lang-count-cell { display: flex; align-items: baseline; gap: 5px; }
.lang-count { font-size: 13px; font-weight: 600; color: var(--fg-0); }
.lang-label { font-size: 11px; color: var(--fg-3); }

/* ── Form fields ── */
.modal-field { display: flex; flex-direction: column; gap: 6px; }

.title-input {
  padding: 12px 14px; font-size: 16px; font-weight: 500;
  border: 1px solid var(--line); border-radius: var(--r-sm);
  background: var(--bg-2); color: var(--fg-0); outline: none;
}

.title-input:focus { border-color: var(--brand-2); }

.desc-input {
  padding: 10px 14px; font-size: 13.5px;
  border: 1px solid var(--line); border-radius: var(--r-sm);
  background: var(--bg-2); color: var(--fg-1); outline: none;
  resize: vertical; font-family: inherit; line-height: 1.5;
}

.desc-input:focus { border-color: var(--brand-2); }

.field-select {
  width: 100%; padding: 8px 10px; font-size: 13px;
  border: 1px solid var(--line); border-radius: var(--r-sm);
  background: var(--bg-2); color: var(--fg-1); outline: none;
  appearance: none; -webkit-appearance: none;
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'><path fill='%237a8094' d='M0 0h10L5 6z'/></svg>");
  background-repeat: no-repeat; background-position: right 10px center; padding-right: 28px;
}

.field-select:focus { border-color: var(--brand-2); }

.shortcut-hint {
  font-size: 11.5px; color: var(--fg-3);
  display: inline-flex; align-items: center; gap: 5px;
}

.spacer { flex: 1; }
</style>
