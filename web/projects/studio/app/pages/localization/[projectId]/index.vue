<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'
import { ExportFormat } from '~/types/graphql'
import {
  linkedWorkOpsProjectId,
  NO_WORKOPS_PROJECT,
  withWorkOpsProjectId,
} from '~/utils/localizationProjectAttributes'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query, mutation } = useGraphQL()
const toast = useToast()

const projectId = computed(() => route.params.projectId as string)
const activeTab = ref('Strings')

// ── Project detail ────────────────────────────

const projectGql = gql`
  query GetLocalizationProject($id: UUID!) {
    localization {
      project(id: $id) {
        id
        name
        description
        sourceLanguage
        attributes
        created
        modified
        languages {
          languageTag
          created
        }
        formats {
          format
          created
        }
        documents {
          id
          metadataId
          created
          modified
        }
      }
    }
  }
`

interface ProjectLanguage {
  languageTag: string
  created: string
}

interface ProjectFormat {
  format: string
  created: string
}

interface ProjectDocument {
  id: string
  metadataId: string
  created: string
  modified: string
}

interface ProjectDetail {
  id: string
  name: string
  description: string | null
  sourceLanguage: string
  attributes: unknown
  created: string
  modified: string
  languages: ProjectLanguage[]
  formats: ProjectFormat[]
  documents: ProjectDocument[]
}

const { data, status, refresh: refreshProject } = useAsyncQuery<{
  localization: { project: ProjectDetail | null }
}>('localization-project', projectGql, { id: projectId })

const project = computed(() => data.value?.localization?.project ?? null)
const isLoading = computed(() => status.value === 'pending')

// ── Available languages ──────────────────────

const allLanguagesGql = gql`
  query GetAllLanguagesForProject {
    languages {
      all {
        tag
        name
        localName
      }
    }
  }
`

interface AvailableLanguage {
  tag: string
  name: string
  localName: string
}

const { data: langData } = useAsyncQuery<{
  languages: { all: AvailableLanguage[] }
}>('all-languages-for-project', allLanguagesGql)

const allLanguages = computed(() => langData.value?.languages?.all ?? [])

function languageDisplayName(tag: string): string {
  const lang = allLanguages.value.find(l => l.tag === tag)
  return lang ? lang.name : tag
}

// ── Strings ───────────────────────────────────

const stringsOffset = ref(0)
const stringsLimit = ref(25)

const stringsGql = gql`
  query GetProjectStrings($id: UUID!, $offset: Int!, $limit: Int!) {
    localization {
      project(id: $id) {
        strings(offset: $offset, limit: $limit) {
          id
          key
          context
          tags
          plural
          modified
        }
      }
    }
  }
`

interface LocalizationStringSummary {
  id: string
  key: string
  context: string | null
  tags: string[]
  plural: boolean
  modified: string
}

const { data: stringsData, status: stringsStatus, refresh: refreshStrings } = useAsyncQuery<{
  localization: { project: { strings: LocalizationStringSummary[] } | null }
}>('project-strings', stringsGql, {
  id: projectId,
  offset: stringsOffset,
  limit: stringsLimit,
})

const strings = computed(() => stringsData.value?.localization?.project?.strings ?? [])
const stringsLoading = computed(() => stringsStatus.value === 'pending')
const hasMoreStrings = computed(() => strings.value.length === stringsLimit.value)
const stringsPage = computed(() => Math.floor(stringsOffset.value / stringsLimit.value) + 1)

const stringColumns: GlassTableColumn[] = [
  { key: 'key', label: 'Key', width: 'minmax(180px, 1.4fr)' },
  { key: 'context', label: 'Context', width: 'minmax(120px, 2fr)', muted: true },
  { key: 'tags', label: 'Tags', width: 'minmax(80px, 1fr)' },
  { key: 'modified', label: 'Modified', width: '90px', muted: true },
]

function nextStringsPage() { stringsOffset.value += stringsLimit.value }
function prevStringsPage() { stringsOffset.value = Math.max(0, stringsOffset.value - stringsLimit.value) }

// ── Add string ────────────────────────────────

const showAddString = ref(false)
const newKey = ref('')
const newContext = ref('')
const newPlural = ref(false)
const stringSaving = ref(false)
const stringSaveError = ref('')

const addStringGql = gql`
  mutation AddLocalizationString($input: LocalizationStringInput!) {
    localization {
      addString(input: $input) {
        id
        key
      }
    }
  }
`

async function handleAddString() {
  if (!newKey.value.trim() || stringSaving.value) return
  stringSaving.value = true
  stringSaveError.value = ''
  try {
    await mutation(addStringGql, {
      input: {
        projectId: projectId.value,
        key: newKey.value.trim(),
        context: newContext.value.trim() || null,
        plural: newPlural.value,
      },
    })
    showAddString.value = false
    newKey.value = ''
    newContext.value = ''
    newPlural.value = false
    refreshStrings()
  } catch (e) {
    stringSaveError.value = e instanceof Error ? e.message : 'Failed to add string'
  } finally {
    stringSaving.value = false
  }
}

// ── Language progress ─────────────────────────

interface LanguageProgress {
  languageTag: string
  totalStrings: number
  translatedStrings: number
  approvedStrings: number
  publishedStrings: number
  aiGeneratedStrings: number
  humanTranslatedStrings: number
  percentage: number
}

const progressGql = gql`
  query GetProjectProgress($id: UUID!, $languageTag: String!) {
    localization {
      project(id: $id) {
        progress(languageTag: $languageTag) {
          totalStrings
          translatedStrings
          approvedStrings
          publishedStrings
          aiGeneratedStrings
          humanTranslatedStrings
          percentage
        }
      }
    }
  }
`

const languageProgress = ref<LanguageProgress[]>([])
const progressLoading = ref(false)

async function loadProgress(proj: ProjectDetail) {
  if (proj.languages.length === 0) {
    languageProgress.value = []
    return
  }

  progressLoading.value = true
  try {
    const results = await Promise.all(
      proj.languages.map(async (lang): Promise<LanguageProgress> => {
        try {
          const result = await query<{
            localization: {
              project: { progress: Omit<LanguageProgress, 'languageTag'> }
            }
          }>(progressGql, { id: proj.id, languageTag: lang.languageTag })

          return { languageTag: lang.languageTag, ...result.localization.project.progress }
        } catch {
          return {
            languageTag: lang.languageTag,
            totalStrings: 0, translatedStrings: 0, approvedStrings: 0,
            publishedStrings: 0, aiGeneratedStrings: 0, humanTranslatedStrings: 0,
            percentage: 0,
          }
        }
      }),
    )
    results.sort((a, b) => b.percentage - a.percentage)
    languageProgress.value = results
  } finally {
    progressLoading.value = false
  }
}

watch(project, (proj) => {
  if (proj) loadProgress(proj)
}, { immediate: true })

function barColor(pct: number): string {
  if (pct === 100) return 'var(--fg-3)'
  if (pct >= 80) return 'var(--ok)'
  if (pct >= 50) return accent.value
  return 'var(--warn)'
}

function statusLabel(pct: number): [string, string] {
  if (pct === 100) return ['Complete', '#34d99a']
  if (pct >= 80) return ['Good', '#34d99a']
  if (pct >= 50) return ['In Progress', '#ffb547']
  if (pct > 0) return ['Started', '#5ec5ff']
  return ['Not Started', '#3a4256']
}

// ── Add / remove language ─────────────────────

const showAddLanguage = ref(false)
const newLanguageTag = ref('')
const languageSaving = ref(false)
const languageSaveError = ref('')

const addLanguageGql = gql`
  mutation AddProjectLanguage($projectId: UUID!, $languageTag: String!) {
    localization {
      addProjectLanguage(projectId: $projectId, languageTag: $languageTag) {
        languageTag
      }
    }
  }
`

const removeLanguageGql = gql`
  mutation RemoveProjectLanguage($projectId: UUID!, $languageTag: String!) {
    localization {
      removeProjectLanguage(projectId: $projectId, languageTag: $languageTag)
    }
  }
`

const availableLanguageOptions = computed<SelectOption[]>(() => {
  const existing = new Set(project.value?.languages.map(l => l.languageTag) ?? [])
  return allLanguages.value
    .filter(l => !existing.has(l.tag) && l.tag !== project.value?.sourceLanguage)
    .map(l => ({ value: l.tag, label: `${l.name} (${l.tag})` }))
    .sort((a, b) => a.label.localeCompare(b.label))
})

async function handleAddLanguage() {
  if (!newLanguageTag.value || languageSaving.value) return
  languageSaving.value = true
  languageSaveError.value = ''
  try {
    await mutation(addLanguageGql, {
      projectId: projectId.value,
      languageTag: newLanguageTag.value,
    })
    showAddLanguage.value = false
    newLanguageTag.value = ''
    refreshProject()
  } catch (e) {
    languageSaveError.value = e instanceof Error ? e.message : 'Failed to add language'
  } finally {
    languageSaving.value = false
  }
}

const removingLanguage = ref<string | null>(null)
const removeLanguageLoading = ref(false)

async function handleRemoveLanguage() {
  if (!removingLanguage.value || removeLanguageLoading.value) return
  removeLanguageLoading.value = true
  try {
    await mutation(removeLanguageGql, {
      projectId: projectId.value,
      languageTag: removingLanguage.value,
    })
    removingLanguage.value = null
    refreshProject()
  } catch (e) {
    toast.error(e instanceof Error ? e.message : 'Failed to remove language')
  } finally {
    removeLanguageLoading.value = false
  }
}

// ── Documents ─────────────────────────────────

const showAddDocument = ref(false)
const docSearchQuery = ref('')
const docSearchResults = ref<{ id: string; name: string; type: string; languageTag: string }[]>([])
const docSearching = ref(false)
const docSaving = ref(false)

const searchMetadataGql = gql`
  query SearchMetadataForProject($query: String!, $limit: Int!) {
    search {
      search(query: {
        query: $query
        storageSystemName: "Admin Search Index"
        limit: $limit
      }) {
        metadata {
          id
          name
          type
          languageTag
        }
      }
    }
  }
`

const addDocumentGql = gql`
  mutation AddProjectDocument($input: LocalizationProjectDocumentInput!) {
    localization {
      addProjectDocument(input: $input) {
        id
        metadataId
      }
    }
  }
`

const removeDocumentGql = gql`
  mutation RemoveProjectDocument($projectId: UUID!, $documentId: UUID!) {
    localization {
      removeProjectDocument(projectId: $projectId, documentId: $documentId)
    }
  }
`

const metadataInfoGql = gql`
  query GetDocumentMetadataInfo($id: UUID!) {
    content {
      metadata(id: $id) {
        id
        name
        type
        languageTag
      }
    }
  }
`

interface MetadataInfo {
  id: string
  name: string
  type: string
  languageTag: string
}

const documentMetadata = ref<Map<string, MetadataInfo>>(new Map())
const documentsLoading = ref(false)

async function loadDocumentMetadata(docs: ProjectDocument[]) {
  if (docs.length === 0) {
    documentMetadata.value = new Map()
    return
  }
  documentsLoading.value = true
  try {
    const results = await Promise.all(
      docs.map(async (doc) => {
        try {
          const result = await query<{ content: { metadata: MetadataInfo } }>(metadataInfoGql, { id: doc.metadataId })
          return [doc.metadataId, result.content.metadata] as [string, MetadataInfo]
        } catch {
          return [doc.metadataId, { id: doc.metadataId, name: 'Unknown', type: 'unknown', languageTag: '' }] as [string, MetadataInfo]
        }
      }),
    )
    documentMetadata.value = new Map(results)
  } finally {
    documentsLoading.value = false
  }
}

watch(() => project.value?.documents, (docs) => {
  if (docs) loadDocumentMetadata(docs)
}, { immediate: true })

const documentColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Document', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Type', width: '120px' },
  { key: 'language', label: 'Language', width: '100px' },
  { key: 'created', label: 'Linked', width: '100px', muted: true },
]

const documentRows = computed(() => {
  return (project.value?.documents ?? []).map(doc => {
    const meta = documentMetadata.value.get(doc.metadataId)
    return {
      id: doc.id,
      metadataId: doc.metadataId,
      name: meta?.name ?? 'Loading…',
      type: meta?.type ?? '—',
      language: meta?.languageTag ?? '—',
      created: doc.created,
    }
  })
})

async function handleSearchDocuments() {
  if (!docSearchQuery.value.trim()) return
  docSearching.value = true
  try {
    const result = await query<{
      search: { search: { metadata: MetadataInfo[] } }
    }>(searchMetadataGql, { query: docSearchQuery.value.trim(), limit: 20 })
    const linked = new Set(project.value?.documents.map(d => d.metadataId) ?? [])
    docSearchResults.value = result.search.search.metadata.filter(m => !linked.has(m.id))
  } catch {
    docSearchResults.value = []
  } finally {
    docSearching.value = false
  }
}

async function handleLinkDocument(metadataId: string) {
  docSaving.value = true
  try {
    await mutation(addDocumentGql, {
      input: { projectId: projectId.value, metadataId },
    })
    docSearchResults.value = docSearchResults.value.filter(r => r.id !== metadataId)
    refreshProject()
  } catch (e) {
    toast.error(e instanceof Error ? e.message : 'Failed to link document')
  } finally {
    docSaving.value = false
  }
}

const removingDocId = ref<string | null>(null)
const removeDocLoading = ref(false)

async function handleRemoveDocument() {
  if (!removingDocId.value || removeDocLoading.value) return
  removeDocLoading.value = true
  try {
    await mutation(removeDocumentGql, {
      projectId: projectId.value,
      documentId: removingDocId.value,
    })
    removingDocId.value = null
    refreshProject()
  } catch (e) {
    toast.error(e instanceof Error ? e.message : 'Failed to remove document')
  } finally {
    removeDocLoading.value = false
  }
}

// ── Settings ──────────────────────────────────

const editName = ref('')
const editDescription = ref('')
const editWorkOpsProjectId = ref(NO_WORKOPS_PROJECT)
const settingsSaving = ref(false)
const settingsSaved = ref(false)

interface WorkOpsProjectOption {
  id: string
  key: string
  name: string
  archivedAt: string | null
}

interface LocalizationProjectBinding {
  id: string
  name: string
  attributes: unknown
}

const workOpsProjectsGql = gql`
  query GetWorkOpsProjectsForLocalization {
    workOps {
      projects {
        all {
          id
          key
          name
          archivedAt
        }
      }
    }
    localization {
      projects {
        id
        name
        attributes
      }
    }
  }
`

const { data: workOpsProjectsData, status: workOpsProjectsStatus } = useAsyncQuery<{
  workOps: { projects: { all: WorkOpsProjectOption[] } }
  localization: { projects: LocalizationProjectBinding[] }
}>('localization-workops-projects', workOpsProjectsGql)

const workOpsProjectOptions = computed<SelectOption[]>(() => {
  const bindings = new Map<string, LocalizationProjectBinding>()
  for (const localizationProject of workOpsProjectsData.value?.localization?.projects ?? []) {
    const linkedProjectId = linkedWorkOpsProjectId(localizationProject.attributes)
    if (linkedProjectId && localizationProject.id !== projectId.value) {
      bindings.set(linkedProjectId, localizationProject)
    }
  }

  const options = (workOpsProjectsData.value?.workOps?.projects?.all ?? [])
    .map((workOpsProject): SelectOption => {
      const existingBinding = bindings.get(workOpsProject.id)
      const suffix = existingBinding
        ? ` · Linked to ${existingBinding.name}`
        : workOpsProject.archivedAt ? ' · Archived' : ''
      return {
        value: workOpsProject.id,
        label: `${workOpsProject.key} - ${workOpsProject.name}${suffix}`,
        disabled: existingBinding !== undefined,
      }
    })
    .sort((a, b) => a.label.localeCompare(b.label))

  return [{ value: NO_WORKOPS_PROJECT, label: 'Not linked' }, ...options]
})

watch(project, (proj) => {
  if (proj) {
    editName.value = proj.name
    editDescription.value = proj.description ?? ''
    editWorkOpsProjectId.value = linkedWorkOpsProjectId(proj.attributes) ?? NO_WORKOPS_PROJECT
  }
}, { immediate: true })

const editProjectGql = gql`
  mutation EditLocalizationProject($id: UUID!, $input: LocalizationProjectInput!) {
    localization {
      editProject(id: $id, input: $input) {
        id
        name
        description
      }
    }
  }
`

const settingsDirty = computed(() => {
  if (!project.value) return false
  return editName.value !== project.value.name
    || editDescription.value !== (project.value.description ?? '')
    || editWorkOpsProjectId.value !== (linkedWorkOpsProjectId(project.value.attributes) ?? NO_WORKOPS_PROJECT)
})

async function handleSaveSettings() {
  if (!settingsDirty.value || settingsSaving.value) return
  settingsSaving.value = true
  settingsSaved.value = false
  try {
    await mutation(editProjectGql, {
      id: projectId.value,
      input: {
        name: editName.value.trim(),
        description: editDescription.value.trim() || null,
        sourceLanguage: project.value!.sourceLanguage,
        attributes: withWorkOpsProjectId(
          project.value!.attributes,
          editWorkOpsProjectId.value === NO_WORKOPS_PROJECT ? null : editWorkOpsProjectId.value,
        ),
      },
    })
    settingsSaved.value = true
    refreshProject()
    setTimeout(() => { settingsSaved.value = false }, 2000)
  } catch (e) {
    toast.error(e instanceof Error ? e.message : 'Failed to save')
  } finally {
    settingsSaving.value = false
  }
}

// ── Export formats ────────────────────────────

const exportFormatOptions: SelectOption[] = Object.entries(ExportFormat).map(([label, value]) => ({
  value,
  label: label.replace(/([a-z])([A-Z])/g, '$1 $2'),
}))

const showAddFormat = ref(false)
const newFormat = ref('')
const formatSaving = ref(false)

const addFormatGql = gql`
  mutation AddProjectFormat($projectId: UUID!, $format: ExportFormat!) {
    localization {
      addProjectFormat(projectId: $projectId, format: $format) {
        format
      }
    }
  }
`

const removeFormatGql = gql`
  mutation RemoveProjectFormat($projectId: UUID!, $format: ExportFormat!) {
    localization {
      removeProjectFormat(projectId: $projectId, format: $format)
    }
  }
`

const availableFormatOptions = computed<SelectOption[]>(() => {
  const existing = new Set(project.value?.formats.map(f => f.format) ?? [])
  return exportFormatOptions.filter(o => !existing.has(o.value))
})

async function handleAddFormat() {
  if (!newFormat.value || formatSaving.value) return
  formatSaving.value = true
  try {
    await mutation(addFormatGql, {
      projectId: projectId.value,
      format: newFormat.value,
    })
    showAddFormat.value = false
    newFormat.value = ''
    refreshProject()
  } catch (e) {
    toast.error(e instanceof Error ? e.message : 'Failed to add format')
  } finally {
    formatSaving.value = false
  }
}

async function handleRemoveFormat(format: string) {
  try {
    await mutation(removeFormatGql, {
      projectId: projectId.value,
      format,
    })
    refreshProject()
  } catch (e) {
    toast.error(e instanceof Error ? e.message : 'Failed to remove format')
  }
}

function formatLabel(format: string): string {
  const entry = exportFormatOptions.find(o => o.value === format)
  return entry?.label ?? format
}

// ── Delete project ────────────────────────────

const showDeleteProject = ref(false)
const deleteLoading = ref(false)

const deleteProjectGql = gql`
  mutation DeleteLocalizationProject($id: UUID!) {
    localization {
      deleteProject(id: $id)
    }
  }
`

async function handleDeleteProject() {
  if (deleteLoading.value) return
  deleteLoading.value = true
  try {
    await mutation(deleteProjectGql, { id: projectId.value })
    router.push('/localization/locales')
  } catch (e) {
    toast.error(e instanceof Error ? e.message : 'Failed to delete project')
  } finally {
    deleteLoading.value = false
  }
}

// ── Helpers ───────────────────────────────────

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

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('en-US', {
    month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit',
  })
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Localization', project?.name ?? '…')"
        :title="project?.name ?? 'Loading…'"
        :subtitle="project ? `Source: ${project.sourceLanguage} · ${project.languages.length} target language${project.languages.length !== 1 ? 's' : ''}` : ''"
        :tabs="['Strings', 'Languages', 'Documents', 'Settings']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #actions>
          <Button
            v-if="activeTab === 'Strings'"
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showAddString = true">Add String</Button>
          <Button
            v-if="activeTab === 'Languages'"
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showAddLanguage = true">Add Language</Button>
          <Button
            v-if="activeTab === 'Documents'"
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showAddDocument = true">Link Document</Button>
        </template>
      </PageHeader>
    </template>

    <!-- ── Add String Modal ── -->
    <Modal
      v-if="showAddString"
      title="Add string"
      :subtitle="`Add a translatable string to ${project?.name}`"
      icon="plus"
      :accent="accent"
      width="480px"
      @close="showAddString = false"
    >
      <div class="modal-field">
        <div class="field-label">Key</div>
        <input
          v-model="newKey"
          autofocus
          placeholder="e.g. items_count, welcome_message"
          class="field-input mono" >
      </div>
      <div class="modal-field">
        <div class="field-label">Context</div>
        <textarea
          v-model="newContext"
          rows="2"
          placeholder="Describe where this string appears…"
          class="field-textarea" />
      </div>
      <div class="modal-field">
        <Switch v-model="newPlural" label="Has plural forms" :accent="accent" />
      </div>
      <div v-if="stringSaveError" class="error-msg">{{ stringSaveError }}</div>

      <template #footer>
        <span class="shortcut-hint"><Icon name="key" :size="11" color="var(--fg-3)" /><span class="mono">⌘ ↵</span> to add</span>
        <span class="spacer" />
        <Button size="sm" @click="showAddString = false">Cancel</Button>
        <Button
          primary
          size="sm"
          :accent="accent"
          icon="plus"
          :disabled="!newKey.trim() || stringSaving"
          @click="handleAddString">
          {{ stringSaving ? 'Adding…' : 'Add string' }}
        </Button>
      </template>
    </Modal>

    <!-- ── Add Language Modal ── -->
    <Modal
      v-if="showAddLanguage"
      title="Add target language"
      :subtitle="`Add a language to ${project?.name}`"
      icon="globe"
      :accent="accent"
      width="480px"
      @close="showAddLanguage = false"
    >
      <FormField label="Language">
        <Select
          v-model="newLanguageTag"
          :options="availableLanguageOptions"
          placeholder="Select a language…"
          searchable
          :accent="accent"
        />
      </FormField>
      <div v-if="languageSaveError" class="error-msg">{{ languageSaveError }}</div>

      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showAddLanguage = false">Cancel</Button>
        <Button
          primary
          size="sm"
          :accent="accent"
          icon="plus"
          :disabled="!newLanguageTag || languageSaving"
          @click="handleAddLanguage">
          {{ languageSaving ? 'Adding…' : 'Add language' }}
        </Button>
      </template>
    </Modal>

    <!-- ── Remove Language Confirm ── -->
    <ConfirmModal
      v-if="removingLanguage"
      title="Remove language"
      confirm-label="Remove language"
      :loading="removeLanguageLoading"
      @close="removingLanguage = null"
      @confirm="handleRemoveLanguage"
    >
      <p class="confirm-text">
        Remove <strong>{{ languageDisplayName(removingLanguage) }}</strong> (<span class="mono">{{ removingLanguage }}</span>) from this project? Existing translations for this language will be deleted.
      </p>
    </ConfirmModal>

    <!-- ── Link Document Modal ── -->
    <Modal
      v-if="showAddDocument"
      title="Link document"
      subtitle="Search for metadata to link as a translatable document"
      icon="file"
      :accent="accent"
      width="560px"
      @close="showAddDocument = false; docSearchQuery = ''; docSearchResults = []"
    >
      <div class="doc-search-row">
        <SearchInput
          v-model="docSearchQuery"
          placeholder="Search metadata…"
          :accent="accent"
          @keydown.enter="handleSearchDocuments"
        />
        <Button
          size="sm"
          :accent="accent"
          :disabled="!docSearchQuery.trim() || docSearching"
          @click="handleSearchDocuments">
          {{ docSearching ? 'Searching…' : 'Search' }}
        </Button>
      </div>

      <div v-if="docSearchResults.length > 0" class="doc-results">
        <div v-for="meta in docSearchResults" :key="meta.id" class="doc-result-row">
          <div class="doc-result-info">
            <div class="doc-result-name">{{ meta.name }}</div>
            <div class="doc-result-meta">
              <Badge :color="accent">{{ meta.type }}</Badge>
              <span v-if="meta.languageTag" class="mono doc-result-lang">{{ meta.languageTag }}</span>
            </div>
          </div>
          <Button
            size="sm"
            icon="plus"
            :accent="accent"
            :disabled="docSaving"
            @click="handleLinkDocument(meta.id)">Link</Button>
        </div>
      </div>
      <div v-else-if="docSearchQuery && !docSearching" class="empty-state-inline">No results found.</div>
    </Modal>

    <!-- ── Remove Document Confirm ── -->
    <ConfirmModal
      v-if="removingDocId"
      title="Remove document"
      confirm-label="Remove document"
      :loading="removeDocLoading"
      @close="removingDocId = null"
      @confirm="handleRemoveDocument"
    >
      <p class="confirm-text">Remove this document from the project? The source metadata will not be deleted.</p>
    </ConfirmModal>

    <!-- ── Add Format Modal ── -->
    <Modal
      v-if="showAddFormat"
      title="Add export format"
      subtitle="Configure an output format for this project"
      icon="download"
      :accent="accent"
      width="420px"
      @close="showAddFormat = false"
    >
      <FormField label="Format">
        <Select
          v-model="newFormat"
          :options="availableFormatOptions"
          placeholder="Select a format…"
          searchable
          :accent="accent"
        />
      </FormField>

      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showAddFormat = false">Cancel</Button>
        <Button
          primary
          size="sm"
          :accent="accent"
          icon="plus"
          :disabled="!newFormat || formatSaving"
          @click="handleAddFormat">
          {{ formatSaving ? 'Adding…' : 'Add format' }}
        </Button>
      </template>
    </Modal>

    <!-- ── Delete Project Confirm ── -->
    <ConfirmModal
      v-if="showDeleteProject"
      title="Delete project"
      confirm-label="Delete project"
      :loading="deleteLoading"
      @close="showDeleteProject = false"
      @confirm="handleDeleteProject"
    >
      <p class="confirm-text">
        Permanently delete <strong>{{ project?.name }}</strong>? All strings, translations, documents, and permissions will be removed. This cannot be undone.
      </p>
    </ConfirmModal>

    <div v-if="isLoading && !project" class="loading-state">Loading…</div>

    <template v-else-if="project">
      <!-- ── Strings Tab ── -->
      <template v-if="activeTab === 'Strings'">
        <SectionCard title="Strings" glass>
          <template #right>
            <span class="mono updated-label">page {{ stringsPage }}</span>
          </template>

          <GlassTable
            :columns="stringColumns"
            :rows="strings"
            :loading="stringsLoading && strings.length === 0"
            row-key="id"
            empty-text="No strings in this project."
            @row-click="(row) => navigateTo(`/localization/${projectId}/strings/${row.id}`)"
          >
            <template #col-key="{ row }">
              <span class="mono string-key">{{ row.key }}</span>
              <Badge v-if="row.plural" color="var(--brand-2)" class="plural-badge">Plural</Badge>
            </template>
            <template #col-tags="{ row }">
              <span v-for="tag in (row.tags as string[])" :key="tag" class="tag-chip">{{ tag }}</span>
              <span v-if="(row.tags as string[]).length === 0" class="cell-muted">—</span>
            </template>
            <template #col-modified="{ row }">
              {{ formatRelative(row.modified as string) }}
            </template>
          </GlassTable>

          <Pagination
            v-if="strings.length > 0"
            :page="stringsPage"
            :total-pages="hasMoreStrings ? stringsPage + 1 : stringsPage"
            @prev="prevStringsPage"
            @next="nextStringsPage"
          />
        </SectionCard>
      </template>

      <!-- ── Languages Tab ── -->
      <template v-if="activeTab === 'Languages'">
        <SectionCard title="Translation progress" glass>
          <template #right>
            <span class="mono updated-label">
              {{ languageProgress.length }} language{{ languageProgress.length !== 1 ? 's' : '' }}
            </span>
          </template>

          <div v-if="progressLoading && languageProgress.length === 0" class="loading-state">Loading…</div>

          <div v-else-if="languageProgress.length === 0" class="empty-state">
            No target languages configured. Add a language to start tracking translation progress.
          </div>

          <div v-else>
            <div v-for="lp in languageProgress" :key="lp.languageTag" class="locale-row">
              <div class="locale-badge">{{ lp.languageTag.toUpperCase() }}</div>

              <div class="locale-name-col">
                <div class="locale-name">{{ languageDisplayName(lp.languageTag) }}</div>
                <div class="mono locale-sub">{{ lp.translatedStrings }} / {{ lp.totalStrings }}</div>
              </div>

              <div class="progress-col">
                <ProgressBar :value="lp.percentage" :accent="barColor(lp.percentage)" :sub="`${Math.round(lp.percentage)}%`" />
              </div>

              <Badge :color="statusLabel(lp.percentage)[1]">
                {{ statusLabel(lp.percentage)[0] }}
              </Badge>

              <button class="row-action" title="Remove language" @click="removingLanguage = lp.languageTag">
                <Icon name="trash" :size="13" color="var(--fg-3)" />
              </button>
            </div>
          </div>
        </SectionCard>
      </template>

      <!-- ── Documents Tab ── -->
      <template v-if="activeTab === 'Documents'">
        <SectionCard title="Linked documents" glass>
          <template #right>
            <span class="mono updated-label">
              {{ project.documents.length }} document{{ project.documents.length !== 1 ? 's' : '' }}
            </span>
          </template>

          <GlassTable
            :columns="documentColumns"
            :rows="documentRows"
            :loading="documentsLoading && documentRows.length === 0"
            row-key="id"
            actions-width="40px"
            empty-text="No documents linked. Click 'Link Document' to add metadata for translation."
          >
            <template #col-name="{ row }">
              <span class="doc-name">{{ row.name }}</span>
            </template>
            <template #col-type="{ row }">
              <Badge :color="accent">{{ row.type }}</Badge>
            </template>
            <template #col-language="{ row }">
              <span v-if="row.language !== '—'" class="mono">{{ row.language }}</span>
              <span v-else class="cell-muted">—</span>
            </template>
            <template #col-created="{ row }">
              {{ formatRelative(row.created as string) }}
            </template>
            <template #actions="{ row }">
              <button class="row-action" title="Remove document" @click.stop="removingDocId = row.id">
                <Icon name="trash" :size="13" color="var(--fg-3)" />
              </button>
            </template>
          </GlassTable>
        </SectionCard>
      </template>

      <!-- ── Settings Tab ── -->
      <template v-if="activeTab === 'Settings'">
        <div class="settings-layout">
          <SectionCard title="Project details" glass>
            <div class="settings-form">
              <FormField label="Project name">
                <TextInput v-model="editName" placeholder="Project name" />
              </FormField>

              <FormField label="Description">
                <Textarea v-model="editDescription" placeholder="Describe this project…" :rows="3" />
              </FormField>

              <FormField label="Source language">
                <div class="settings-value">
                  <Badge :color="accent">{{ project.sourceLanguage }}</Badge>
                  <span class="settings-hint">{{ languageDisplayName(project.sourceLanguage) }}</span>
                </div>
              </FormField>

              <FormField label="WorkOps project">
                <Select
                  v-model="editWorkOpsProjectId"
                  :options="workOpsProjectOptions"
                  :loading="workOpsProjectsStatus === 'pending'"
                  placeholder="Select a WorkOps project…"
                  searchable
                  :accent="accent"
                />
                <div class="settings-hint">
                  Links release-note generation for this localization project to a WorkOps project.
                </div>
              </FormField>

              <FormField label="Created">
                <div class="settings-value mono">{{ formatDate(project.created) }}</div>
              </FormField>

              <FormField label="Last modified">
                <div class="settings-value mono">{{ formatDate(project.modified) }}</div>
              </FormField>

              <div class="settings-actions">
                <Button
                  primary
                  size="sm"
                  :accent="accent"
                  :disabled="!settingsDirty || settingsSaving"
                  @click="handleSaveSettings"
                >
                  {{ settingsSaving ? 'Saving…' : settingsSaved ? 'Saved' : 'Save changes' }}
                </Button>
              </div>
            </div>
          </SectionCard>

          <SectionCard title="Export formats" glass>
            <template #right>
              <Button
                size="sm"
                icon="plus"
                :accent="accent"
                @click="showAddFormat = true">Add format</Button>
            </template>

            <div v-if="project.formats.length === 0" class="empty-state-inline">
              No export formats configured. Add formats to enable string export.
            </div>
            <div v-else class="format-list">
              <div v-for="fmt in project.formats" :key="fmt.format" class="format-row">
                <div class="format-info">
                  <span class="format-name">{{ formatLabel(fmt.format) }}</span>
                  <span class="mono format-code">{{ fmt.format }}</span>
                </div>
                <button class="row-action" title="Remove format" @click="handleRemoveFormat(fmt.format)">
                  <Icon name="x" :size="12" color="var(--fg-3)" />
                </button>
              </div>
            </div>
          </SectionCard>

          <SectionCard title="Danger zone" glass>
            <div class="danger-section">
              <div class="danger-info">
                <div class="danger-title">Delete project</div>
                <div class="danger-desc">Permanently delete this project, all strings, translations, and linked documents.</div>
              </div>
              <Button size="sm" @click="showDeleteProject = true">Delete project</Button>
            </div>
          </SectionCard>
        </div>
      </template>
    </template>

    <div v-else class="empty-state">Project not found.</div>
  </PageShell>
</template>

<style scoped>
.updated-label { font-size: 11px; color: var(--fg-3); }

/* ── Form fields ── */
.modal-field { display: flex; flex-direction: column; gap: 6px; }

.shortcut-hint {
  font-size: 11.5px; color: var(--fg-3);
  display: inline-flex; align-items: center; gap: 5px;
}

.spacer { flex: 1; }

.loading-state,
.empty-state {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.empty-state-inline {
  padding: 24px 16px;
  text-align: center;
  color: var(--fg-3);
  font-size: 12.5px;
  line-height: 1.5;
}

.confirm-text { font-size: 13px; color: var(--fg-1); line-height: 1.5; margin: 0; }

/* ── String row content ── */
.cell-muted { color: var(--fg-2); font-size: 12px; }

.string-key {
  font-size: 12.5px;
  color: var(--fg-0);
  font-weight: 500;
}

.plural-badge { flex-shrink: 0; margin-left: 6px; }

.tag-chip {
  display: inline-block;
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 999px;
  background: color-mix(in oklch, var(--bg-3) 30%, transparent);
  border: 1px solid color-mix(in oklch, var(--line) 45%, transparent);
  color: var(--fg-2);
  margin-right: 4px;
}

/* ── Language progress ── */
.locale-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 35%, transparent);
  border-radius: var(--r-xs);
  margin: 0 6px;
  transition: background 0.15s ease;
}

.locale-row:last-child { border-bottom: none; }

.locale-row:hover {
  background: color-mix(in oklch, var(--brand-2) 5%, transparent);
}

.locale-badge {
  width: 44px;
  height: 28px;
  border-radius: 6px;
  background: color-mix(in oklch, var(--bg-3) 25%, transparent);
  border: 1px solid color-mix(in oklch, var(--line) 45%, transparent);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10.5px;
  font-weight: 700;
  color: var(--fg-1);
  letter-spacing: 0.04em;
  font-family: var(--font-mono);
}

.locale-name-col { width: 140px; }
.locale-name { font-size: 13px; font-weight: 500; color: var(--fg-0); }
.locale-sub { font-size: 10.5px; color: var(--fg-3); margin-top: 1px; }

.progress-col {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
}

.row-action {
  padding: 5px;
  border-radius: 4px;
  color: var(--fg-3);
  cursor: pointer;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.15s, background 0.15s;
}

.locale-row:hover .row-action,
.row-action:focus-visible { opacity: 1; }

.row-action:hover {
  background: color-mix(in oklch, var(--bg-3) 30%, transparent);
}

/* ── Documents ── */
.doc-search-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.doc-search-row > :first-child { flex: 1; }

.doc-results {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 300px;
  overflow-y: auto;
}

.doc-result-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: var(--r-xs);
  transition: background 0.1s;
}

.doc-result-row:hover {
  background: color-mix(in oklch, var(--bg-3) 30%, transparent);
}

.doc-result-info { flex: 1; min-width: 0; }
.doc-result-name { font-size: 13px; font-weight: 500; color: var(--fg-0); }
.doc-result-meta { display: flex; align-items: center; gap: 8px; margin-top: 3px; }
.doc-result-lang { font-size: 11px; color: var(--fg-3); }

.doc-name { font-size: 13px; font-weight: 500; color: var(--fg-0); }

/* ── Settings ── */
.settings-layout {
  display: flex;
  flex-direction: column;
  gap: 18px;
  max-width: 680px;
}

.settings-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 16px;
}

.settings-value {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--fg-1);
  min-height: 32px;
}

.settings-hint {
  font-size: 12px;
  color: var(--fg-3);
}

.settings-actions {
  display: flex;
  justify-content: flex-end;
  border-top: 1px solid color-mix(in oklch, var(--line) 35%, transparent);
  margin-top: 2px;
  padding-top: 14px;
}

/* ── Format list ── */
.format-list {
  display: flex;
  flex-direction: column;
  padding: 4px 8px;
}

.format-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 10px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 30%, transparent);
  border-radius: var(--r-xs);
  transition: background 0.1s;
}

.format-row:last-child { border-bottom: none; }

.format-row:hover {
  background: color-mix(in oklch, var(--bg-3) 15%, transparent);
}

.format-row:hover .row-action { opacity: 1; }

.format-info { display: flex; align-items: center; gap: 10px; }
.format-name { font-size: 13px; font-weight: 500; color: var(--fg-0); }
.format-code { font-size: 10.5px; color: var(--fg-3); }

/* ── Danger zone ── */
.danger-section {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
}

.danger-title {
  font-size: 13px;
  font-weight: 550;
  color: var(--err, #ef4444);
}

.danger-desc {
  font-size: 12px;
  color: var(--fg-3);
  margin-top: 2px;
  line-height: 1.4;
}
</style>
