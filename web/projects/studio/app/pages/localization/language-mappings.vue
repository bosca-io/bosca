<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()

const languageMappingsGql = gql`
  query GetLanguageMappings {
    languages {
      all {
        tag
        name
        localName
      }
      resolutionContexts {
        id
        key
        name
        description
        fallbackLanguageTag
        mappings {
          contextId
          sourceLanguageTag
          resolvedLanguageTag
        }
      }
    }
  }
`

interface Language {
  tag: string
  name: string
  localName: string
}

interface LanguageTagMapping {
  contextId: string
  sourceLanguageTag: string
  resolvedLanguageTag: string
}

interface LanguageResolutionContext {
  id: string
  key: string
  name: string
  description: string
  fallbackLanguageTag: string
  mappings: LanguageTagMapping[]
}

interface LanguageGroup {
  key: string
  name: string
  locales: Language[]
  mappings: LanguageTagMapping[]
}

const { data, status, refresh } = useAsyncQuery<{
  languages: {
    all: Language[]
    resolutionContexts: LanguageResolutionContext[]
  }
}>('localization-language-mappings', languageMappingsGql)

const languages = computed(() => {
  const all = data.value?.languages?.all ?? []
  return [...all].sort((a, b) => a.name.localeCompare(b.name))
})

const resolutionContexts = computed(() => {
  const contexts = data.value?.languages?.resolutionContexts ?? []
  return [...contexts].sort((a, b) => a.name.localeCompare(b.name))
})

const resolutionContextOptions = computed<SelectOption[]>(() => resolutionContexts.value.map(context => ({
  value: context.id,
  label: context.name,
})))

const selectedContextId = ref<string | null>(null)

watch(resolutionContexts, (contexts) => {
  if (contexts.some(context => context.id === selectedContextId.value)) return
  selectedContextId.value = contexts[0]?.id ?? null
}, { immediate: true })

const selectedContext = computed(() =>
  resolutionContexts.value.find(context => context.id === selectedContextId.value) ?? null,
)

const mappingSearch = ref('')

watch(selectedContextId, () => {
  mappingSearch.value = ''
})

function baseLanguageTag(tag: string): string {
  return tag.trim().replaceAll('_', '-').split('-')[0]?.toLowerCase() ?? tag.toLowerCase()
}

const expandedLanguageKeys = ref<string[]>([])

watch(selectedContext, (context) => {
  const counts = new Map<string, number>()
  for (const mapping of context?.mappings ?? []) {
    const key = baseLanguageTag(mapping.sourceLanguageTag)
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  const expanded = [...counts.entries()].filter(([, count]) => count > 1).map(([key]) => key)
  expandedLanguageKeys.value = expanded.length > 0 ? expanded : [...counts.keys()].slice(0, 1)
}, { immediate: true })

function isLanguageExpanded(key: string): boolean {
  return mappingSearch.value.trim().length > 0 || expandedLanguageKeys.value.includes(key)
}

function toggleLanguage(key: string) {
  expandedLanguageKeys.value = expandedLanguageKeys.value.includes(key)
    ? expandedLanguageKeys.value.filter(candidate => candidate !== key)
    : [...expandedLanguageKeys.value, key]
}

function languageName(tag: string, fallback?: Language): string {
  const exact = languages.value.find(language => language.tag.toLowerCase() === tag)
  if (exact) return exact.name
  try {
    return new Intl.DisplayNames(['en'], { type: 'language' }).of(tag) ?? fallback?.name ?? tag
  } catch {
    return fallback?.name ?? tag
  }
}

const languageGroups = computed<LanguageGroup[]>(() => {
  const groups = new Map<string, LanguageGroup>()

  for (const language of languages.value) {
    const key = baseLanguageTag(language.tag)
    const group = groups.get(key) ?? {
      key,
      name: languageName(key, language),
      locales: [],
      mappings: [],
    }
    group.locales.push(language)
    groups.set(key, group)
  }

  for (const mapping of selectedContext.value?.mappings ?? []) {
    const key = baseLanguageTag(mapping.sourceLanguageTag)
    const group = groups.get(key) ?? {
      key,
      name: languageName(key),
      locales: [],
      mappings: [],
    }
    group.mappings.push(mapping)
    groups.set(key, group)
  }

  const query = mappingSearch.value.trim().toLowerCase()
  return [...groups.values()]
    .map((group) => {
      group.locales.sort((a, b) => a.tag.localeCompare(b.tag))
      group.mappings.sort((a, b) => a.sourceLanguageTag.localeCompare(b.sourceLanguageTag))
      if (!query) return group

      const groupMatches = group.name.toLowerCase().includes(query)
        || group.key.includes(query)
        || group.locales.some(locale => `${locale.name} ${locale.localName} ${locale.tag}`.toLowerCase().includes(query))
      if (groupMatches) return group

      return {
        ...group,
        mappings: group.mappings.filter(mapping =>
          mapping.sourceLanguageTag.toLowerCase().includes(query)
          || mapping.resolvedLanguageTag.toLowerCase().includes(query),
        ),
      }
    })
    .filter(group => !query || group.mappings.length > 0
      || group.name.toLowerCase().includes(query)
      || group.key.includes(query)
      || group.locales.some(locale => `${locale.name} ${locale.localName} ${locale.tag}`.toLowerCase().includes(query)))
    .sort((a, b) => a.name.localeCompare(b.name) || a.key.localeCompare(b.key))
})

const isLoading = computed(() => status.value === 'pending')
const mappingCount = computed(() => selectedContext.value?.mappings.length ?? 0)

// ── Resolution context settings ─────────────

const editingContext = ref<LanguageResolutionContext | null>(null)
const contextName = ref('')
const contextDescription = ref('')
const contextFallback = ref('')
const contextSaving = ref(false)
const contextError = ref('')

const editResolutionContextGql = gql`
  mutation EditLanguageResolutionContext($id: UUID!, $input: LanguageResolutionContextInput!) {
    languages {
      editResolutionContext(id: $id, input: $input) {
        id
        key
        name
        description
        fallbackLanguageTag
      }
    }
  }
`

function openContextSettings() {
  const context = selectedContext.value
  if (!context) return
  editingContext.value = context
  contextName.value = context.name
  contextDescription.value = context.description
  contextFallback.value = context.fallbackLanguageTag
  contextError.value = ''
}

const canSaveContext = computed(() =>
  editingContext.value !== null
  && contextName.value.trim().length > 0
  && contextFallback.value.trim().length > 0,
)

async function handleEditContext() {
  if (!editingContext.value || !canSaveContext.value || contextSaving.value) return
  contextSaving.value = true
  contextError.value = ''
  try {
    await mutation(editResolutionContextGql, {
      id: editingContext.value.id,
      input: {
        key: editingContext.value.key,
        name: contextName.value.trim(),
        description: contextDescription.value.trim(),
        fallbackLanguageTag: contextFallback.value.trim(),
      },
    })
    editingContext.value = null
    await refresh()
  } catch (e) {
    contextError.value = e instanceof Error ? e.message : 'Failed to update resolution context'
  } finally {
    contextSaving.value = false
  }
}

// ── Language tag mappings ───────────────────

const showMappingEditor = ref(false)
const editingMappingSource = ref<string | null>(null)
const mappingParentName = ref<string | null>(null)
const mappingSource = ref('')
const mappingTarget = ref('')
const mappingSaving = ref(false)
const mappingError = ref('')

const mappingSourceOptions = computed<SelectOption[]>(() => {
  const options = languages.value.map(language => ({
    value: language.tag,
    label: `${language.name} · ${language.tag}`,
  }))
  const source = mappingSource.value.trim()
  if (source && !options.some(option => option.value.toLowerCase() === source.toLowerCase())) {
    options.push({ value: source, label: source })
  }
  return options
})

const setLanguageTagMappingGql = gql`
  mutation SetLanguageTagMapping($contextId: UUID!, $input: LanguageTagMappingInput!) {
    languages {
      setLanguageTagMapping(contextId: $contextId, input: $input) {
        contextId
        sourceLanguageTag
        resolvedLanguageTag
      }
    }
  }
`

function openAddMapping(group?: LanguageGroup) {
  const context = selectedContext.value
  if (!context) return
  const existingSources = new Set(context.mappings.map(mapping => mapping.sourceLanguageTag.toLowerCase()))
  const availableLocale = group?.locales.find(locale => !existingSources.has(locale.tag.toLowerCase()))
  editingMappingSource.value = null
  mappingParentName.value = group?.name ?? null
  mappingSource.value = availableLocale?.tag ?? ''
  mappingTarget.value = context.fallbackLanguageTag
  mappingError.value = ''
  showMappingEditor.value = true
}

function openEditMapping(mapping: LanguageTagMapping) {
  editingMappingSource.value = mapping.sourceLanguageTag
  mappingParentName.value = languageName(baseLanguageTag(mapping.sourceLanguageTag))
  mappingSource.value = mapping.sourceLanguageTag
  mappingTarget.value = mapping.resolvedLanguageTag
  mappingError.value = ''
  showMappingEditor.value = true
}

const canSaveMapping = computed(() =>
  selectedContext.value !== null
  && mappingSource.value.trim().length > 0
  && mappingTarget.value.trim().length > 0,
)

async function handleSaveMapping() {
  const context = selectedContext.value
  if (!context || !canSaveMapping.value || mappingSaving.value) return
  mappingSaving.value = true
  mappingError.value = ''
  try {
    await mutation(setLanguageTagMappingGql, {
      contextId: context.id,
      input: {
        sourceLanguageTag: mappingSource.value.trim(),
        resolvedLanguageTag: mappingTarget.value.trim(),
      },
    })
    showMappingEditor.value = false
    await refresh()
  } catch (e) {
    mappingError.value = e instanceof Error ? e.message : 'Failed to save language mapping'
  } finally {
    mappingSaving.value = false
  }
}

const deletingMapping = ref<LanguageTagMapping | null>(null)
const mappingDeleteInProgress = ref(false)
const mappingDeleteError = ref('')

const deleteLanguageTagMappingGql = gql`
  mutation DeleteLanguageTagMapping($contextId: UUID!, $sourceLanguageTag: String!) {
    languages {
      deleteLanguageTagMapping(contextId: $contextId, sourceLanguageTag: $sourceLanguageTag)
    }
  }
`

function openDeleteMapping(mapping: LanguageTagMapping) {
  deletingMapping.value = mapping
  mappingDeleteError.value = ''
}

async function handleDeleteMapping() {
  const context = selectedContext.value
  if (!context || !deletingMapping.value || mappingDeleteInProgress.value) return
  mappingDeleteInProgress.value = true
  mappingDeleteError.value = ''
  try {
    await mutation(deleteLanguageTagMappingGql, {
      contextId: context.id,
      sourceLanguageTag: deletingMapping.value.sourceLanguageTag,
    })
    deletingMapping.value = null
    await refresh()
  } catch (e) {
    mappingDeleteError.value = e instanceof Error ? e.message : 'Failed to delete language mapping'
  } finally {
    mappingDeleteInProgress.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Localization')"
        title="Language mappings"
        subtitle="Group locale mappings by their parent language"
      >
        <template #actions>
          <div class="page-actions">
            <Select
              v-if="resolutionContexts.length > 0"
              v-model="selectedContextId"
              :options="resolutionContextOptions"
              size="sm"
              :accent="accent"
              aria-label="Language resolution context"
            />
            <Button
              v-if="selectedContext"
              size="sm"
              icon="settings"
              @click="openContextSettings">Settings</Button>
            <Button
              v-if="selectedContext"
              primary
              size="sm"
              icon="plus"
              :accent="accent"
              @click="openAddMapping()">Add Mapping</Button>
          </div>
        </template>
      </PageHeader>
    </template>

    <Modal
      v-if="editingContext"
      title="Resolution context settings"
      :subtitle="editingContext.key"
      icon="settings"
      :accent="accent"
      width="560px"
      @close="editingContext = null"
    >
      <div class="modal-field">
        <div class="field-label">Context key</div>
        <input :value="editingContext.key" class="field-input mono" disabled >
      </div>
      <div class="modal-field">
        <div class="field-label">Name</div>
        <input v-model="contextName" autofocus class="field-input" >
      </div>
      <div class="modal-field">
        <div class="field-label">Description</div>
        <textarea v-model="contextDescription" class="field-input context-description-input" />
      </div>
      <div class="modal-field">
        <div class="field-label">Fallback context tag</div>
        <input v-model="contextFallback" class="field-input mono" placeholder="e.g. eng" >
        <div class="field-help">Used by this content domain when a Bosca locale has no mapping.</div>
      </div>
      <div v-if="contextError" class="error-msg">{{ contextError }}</div>

      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="editingContext = null">Cancel</Button>
        <Button
          primary
          size="sm"
          :accent="accent"
          :disabled="!canSaveContext || contextSaving"
          @click="handleEditContext">
          {{ contextSaving ? 'Saving…' : 'Save settings' }}
        </Button>
      </template>
    </Modal>

    <Modal
      v-if="showMappingEditor"
      :title="editingMappingSource ? 'Edit language mapping' : 'Add language mapping'"
      :subtitle="[mappingParentName, selectedContext?.name].filter(Boolean).join(' · ')"
      icon="languages"
      :accent="accent"
      width="520px"
      @close="showMappingEditor = false"
    >
      <div class="modal-field">
        <div class="field-label">Bosca locale</div>
        <Select
          v-model="mappingSource"
          :options="mappingSourceOptions"
          searchable
          allow-custom
          :accent="accent"
          class="mapping-source-input"
          placeholder="Type or search, e.g. en-US"
          :disabled="editingMappingSource !== null" />
        <div class="field-help">Locale tags are grouped under their base language, such as en-US under English.</div>
      </div>
      <div class="modal-field">
        <div class="field-label">Context language tag</div>
        <input
          v-model="mappingTarget"
          autofocus
          class="field-input mono mapping-target-input"
          placeholder="e.g. eng" >
        <div class="field-help">The tag used by the selected context, such as en for Recommendations or eng for Bibles.</div>
      </div>
      <div v-if="mappingError" class="error-msg">{{ mappingError }}</div>

      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showMappingEditor = false">Cancel</Button>
        <Button
          primary
          size="sm"
          :accent="accent"
          :disabled="!canSaveMapping || mappingSaving"
          @click="handleSaveMapping">
          {{ mappingSaving ? 'Saving…' : editingMappingSource ? 'Save mapping' : 'Add mapping' }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deletingMapping"
      title="Delete language mapping"
      confirm-label="Delete mapping"
      :loading="mappingDeleteInProgress"
      @close="deletingMapping = null"
      @confirm="handleDeleteMapping"
    >
      <p class="confirm-text">
        Delete <strong class="mono">{{ deletingMapping.sourceLanguageTag }}</strong>
        → <strong class="mono">{{ deletingMapping.resolvedLanguageTag }}</strong>?
      </p>
      <div v-if="mappingDeleteError" class="error-msg">{{ mappingDeleteError }}</div>
    </ConfirmModal>

    <template v-if="selectedContext">
      <SectionCard :title="selectedContext.name" glass>
        <template #right>
          <span class="context-key mono">{{ selectedContext.key }}</span>
        </template>
        <div class="context-summary">
          <div class="context-description">
            {{ selectedContext.description || 'No description provided.' }}
          </div>
          <div class="fallback-label">
            <span>Context fallback</span>
            <strong class="mono">{{ selectedContext.fallbackLanguageTag }}</strong>
          </div>
        </div>
        <div class="mapping-filter">
          <SearchInput
            v-model="mappingSearch"
            placeholder="Search languages or mappings…"
            max-width="360px" />
          <span class="mapping-count mono">
            {{ languageGroups.length }} language{{ languageGroups.length === 1 ? '' : 's' }} · {{ mappingCount }} mappings
          </span>
        </div>
      </SectionCard>

      <div class="language-groups">
        <section
          v-for="group in languageGroups"
          :key="group.key"
          class="language-group"
          :data-language-group="group.key"
        >
          <header class="language-group-header">
            <button
              class="language-group-toggle"
              :aria-expanded="isLanguageExpanded(group.key)"
              @click="toggleLanguage(group.key)"
            >
              <span class="language-symbol mono">{{ group.key.toUpperCase() }}</span>
              <span class="language-identity">
                <span class="language-name">{{ group.name }}</span>
                <span class="language-locales">
                  <span v-for="locale in group.locales" :key="locale.tag" class="locale-chip mono">
                    {{ locale.tag }}
                  </span>
                  <span v-if="group.locales.length === 0" class="unregistered-label">No registered locales</span>
                </span>
              </span>
              <span class="mapping-total mono">{{ group.mappings.length }} mapping{{ group.mappings.length === 1 ? '' : 's' }}</span>
              <Icon
                :name="isLanguageExpanded(group.key) ? 'chevron-up' : 'chevron-down'"
                :size="14"
                color="var(--fg-3)" />
            </button>
            <Button size="sm" icon="plus" @click="openAddMapping(group)">Add</Button>
          </header>

          <div v-if="isLanguageExpanded(group.key) && group.mappings.length > 0" class="mapping-list">
            <div
              v-for="mapping in group.mappings"
              :key="mapping.sourceLanguageTag"
              class="mapping-row"
              :data-mapping-source="mapping.sourceLanguageTag"
            >
              <button class="mapping-main" @click="openEditMapping(mapping)">
                <span class="mapping-code mono">{{ mapping.sourceLanguageTag }}</span>
                <Icon name="arrow-right" :size="14" color="var(--fg-3)" />
                <span class="mapping-code resolved mono">{{ mapping.resolvedLanguageTag }}</span>
              </button>
              <button class="row-action" aria-label="Edit mapping" @click.stop="openEditMapping(mapping)">
                <Icon name="pencil" :size="13" color="var(--fg-3)" />
              </button>
              <button class="row-action" aria-label="Delete mapping" @click.stop="openDeleteMapping(mapping)">
                <Icon name="trash" :size="13" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <button v-else-if="isLanguageExpanded(group.key)" class="empty-group" @click="openAddMapping(group)">
            No mappings for {{ group.name }} in {{ selectedContext.name }}. Add one
          </button>
        </section>
      </div>

      <div v-if="!isLoading && languageGroups.length === 0" class="empty-state">
        No languages or mappings match your search.
      </div>
    </template>
    <div v-else-if="!isLoading" class="empty-state">
      No language resolution contexts are installed.
    </div>
  </PageShell>
</template>

<style scoped>
.page-actions { display: flex; align-items: center; gap: 8px; }

.context-key {
  padding: 2px 6px; border-radius: 4px;
  background: color-mix(in oklch, var(--bg-3) 35%, transparent);
  font-size: 10.5px; color: var(--fg-2);
}

.context-summary {
  display: flex; align-items: center; justify-content: space-between; gap: 24px;
  padding: 14px 16px; border-bottom: 1px solid color-mix(in oklch, var(--line) 40%, transparent);
}

.context-description { font-size: 12px; line-height: 1.5; color: var(--fg-3); }

.fallback-label {
  display: flex; flex-direction: column; align-items: flex-end; gap: 2px;
  white-space: nowrap; font-size: 10.5px; color: var(--fg-3);
}

.fallback-label strong { font-size: 12px; color: var(--fg-1); font-weight: 600; }

.mapping-filter {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 10px 16px;
}

.mapping-count { white-space: nowrap; font-size: 10.5px; color: var(--fg-3); }

.language-groups { display: grid; gap: 12px; }

.language-group {
  overflow: hidden; border: 1px solid color-mix(in oklch, var(--line) 48%, transparent);
  border-radius: 10px; background: color-mix(in oklch, var(--bg-1) 78%, transparent);
}

.language-group-header {
  display: flex; align-items: center; gap: 12px; min-height: 58px; padding: 10px 12px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 35%, transparent);
  background: color-mix(in oklch, var(--bg-2) 58%, transparent);
}

.language-group-toggle {
  display: flex; align-items: center; gap: 12px; min-width: 0; flex: 1;
  text-align: left; cursor: pointer;
}

.language-group-toggle:focus-visible { outline: 2px solid v-bind(accent); outline-offset: 3px; border-radius: 5px; }

.language-symbol {
  display: inline-flex; align-items: center; justify-content: center;
  width: 36px; height: 36px; border-radius: 8px;
  background: color-mix(in oklch, v-bind(accent) 12%, var(--bg-3));
  border: 1px solid color-mix(in oklch, v-bind(accent) 28%, transparent);
  color: var(--fg-1); font-size: 10px; font-weight: 700;
}

.language-identity { display: block; min-width: 0; flex: 1; }
.language-name { display: block; font-size: 13.5px; font-weight: 650; color: var(--fg-0); }
.language-locales { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }

.locale-chip {
  padding: 1px 5px; border-radius: 4px;
  background: color-mix(in oklch, var(--bg-3) 42%, transparent);
  font-size: 9.5px; color: var(--fg-2);
}

.unregistered-label { font-size: 10.5px; color: var(--fg-3); }
.mapping-total { font-size: 10.5px; color: var(--fg-3); }
.mapping-list { display: grid; }

.mapping-row {
  display: flex; align-items: center; gap: 4px; min-height: 46px; padding: 7px 12px 7px 52px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 26%, transparent);
}

.mapping-row:last-child { border-bottom: 0; }
.mapping-row:hover { background: color-mix(in oklch, var(--bg-3) 22%, transparent); }

.mapping-main {
  display: flex; align-items: center; gap: 12px; min-width: 0; flex: 1;
  text-align: left; cursor: pointer;
}

.mapping-main:focus-visible { outline: 2px solid v-bind(accent); outline-offset: 2px; border-radius: 4px; }

.mapping-code {
  display: inline-flex; min-width: 80px; padding: 4px 8px; border-radius: 5px;
  background: color-mix(in oklch, var(--bg-3) 35%, transparent);
  color: var(--fg-1); font-size: 11.5px;
}

.mapping-code.resolved {
  background: color-mix(in oklch, v-bind(accent) 10%, var(--bg-3));
  color: var(--fg-0);
}

.row-action {
  padding: 5px; border-radius: 4px; color: var(--fg-3); cursor: pointer;
}

.row-action:hover { background: color-mix(in oklch, var(--bg-3) 42%, transparent); }

.empty-group {
  width: 100%; padding: 16px 60px; text-align: left;
  font-size: 11.5px; color: var(--fg-3); cursor: pointer;
}

.empty-group:hover { color: var(--fg-1); background: color-mix(in oklch, var(--bg-3) 20%, transparent); }
.empty-state { padding: 48px 16px; text-align: center; font-size: 12.5px; color: var(--fg-3); }
.spacer { flex: 1; }
.modal-field { display: flex; flex-direction: column; gap: 6px; }
.field-help { font-size: 11px; line-height: 1.4; color: var(--fg-3); }
.mapping-source-input :deep(.select-root) { width: 100%; }
.mapping-source-input :deep(.select-trigger) { font-family: var(--font-mono); }
.context-description-input { min-height: 84px; resize: vertical; }
.confirm-text { margin: 0; font-size: 13px; line-height: 1.5; color: var(--fg-1); }

@media (max-width: 720px) {
  .page-actions { flex-wrap: wrap; justify-content: flex-end; }
  .context-summary { align-items: flex-start; flex-direction: column; gap: 10px; }
  .fallback-label { align-items: flex-start; }
  .mapping-filter { align-items: flex-start; flex-direction: column; }
  .mapping-total { display: none; }
  .mapping-row { padding-left: 12px; }
  .empty-group { padding-left: 12px; }
}
</style>
