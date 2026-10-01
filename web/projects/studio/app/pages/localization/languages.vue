<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()

const languagesGql = gql`
  query GetLanguagesList {
    languages {
      all {
        tag
        name
        localName
      }
    }
  }
`

interface Language {
  tag: string
  name: string
  localName: string
}

const { data, status, refresh } = useAsyncQuery<{
  languages: { all: Language[] }
}>('localization-languages', languagesGql)

const languages = computed(() => {
  const all = data.value?.languages?.all ?? []
  return [...all].sort((a, b) => a.name.localeCompare(b.name))
})

const isLoading = computed(() => status.value === 'pending')

const localeColumns: GlassTableColumn[] = [
  { key: 'tag', label: 'Tag', width: '80px' },
  { key: 'name', label: 'Name', width: 'minmax(140px, 1fr)' },
  { key: 'localName', label: 'Local name', width: '1fr', muted: true },
]

// ── Add locale ──────────────────────────────

const showAddLocale = ref(false)
const newTag = ref('')
const newName = ref('')
const newLocalName = ref('')
const saving = ref(false)
const saveError = ref('')

const addLanguageGql = gql`
  mutation AddLanguage($input: LanguageInput!) {
    languages {
      add(input: $input) { tag name localName }
    }
  }
`

const canSave = computed(() =>
  newTag.value.trim().length > 0
  && newName.value.trim().length > 0
  && newLocalName.value.trim().length > 0,
)

async function handleAddLocale() {
  if (!canSave.value || saving.value) return
  saving.value = true
  saveError.value = ''
  try {
    await mutation(addLanguageGql, {
      input: { tag: newTag.value.trim(), name: newName.value.trim(), localName: newLocalName.value.trim() },
    })
    showAddLocale.value = false
    newTag.value = ''
    newName.value = ''
    newLocalName.value = ''
    refresh()
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : 'Failed to add locale'
  } finally {
    saving.value = false
  }
}

// ── Edit locale ─────────────────────────────

const editingLocale = ref<Language | null>(null)
const editName = ref('')
const editLocalName = ref('')
const editSaving = ref(false)
const editError = ref('')

const editLanguageGql = gql`
  mutation EditLanguage($input: LanguageInput!) {
    languages {
      edit(input: $input) { tag name localName }
    }
  }
`

function openEdit(lang: Language) {
  editingLocale.value = lang
  editName.value = lang.name
  editLocalName.value = lang.localName
  editError.value = ''
}

async function handleEditLocale() {
  if (!editingLocale.value || editSaving.value) return
  editSaving.value = true
  editError.value = ''
  try {
    await mutation(editLanguageGql, {
      input: { tag: editingLocale.value.tag, name: editName.value.trim(), localName: editLocalName.value.trim() },
    })
    editingLocale.value = null
    refresh()
  } catch (e) {
    editError.value = e instanceof Error ? e.message : 'Failed to update'
  } finally {
    editSaving.value = false
  }
}

// ── Delete locale ───────────────────────────

const deletingTag = ref<string | null>(null)
const deleteInProgress = ref(false)
const deleteError = ref('')

const deleteLanguageGql = gql`
  mutation DeleteLanguage($tag: String!) {
    languages {
      delete(tag: $tag)
    }
  }
`

async function handleDeleteLocale() {
  if (!deletingTag.value || deleteInProgress.value) return
  deleteInProgress.value = true
  deleteError.value = ''
  try {
    await mutation(deleteLanguageGql, { tag: deletingTag.value })
    deletingTag.value = null
    refresh()
  } catch (e) {
    deleteError.value = e instanceof Error ? e.message : 'Failed to delete'
  } finally {
    deleteInProgress.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Localization')"
        title="Locales"
        :subtitle="`${languages.length} locale${languages.length !== 1 ? 's' : ''} registered`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showAddLocale = true">Add Locale</Button>
        </template>
      </PageHeader>
    </template>

    <!-- Add locale -->
    <Modal
      v-if="showAddLocale"
      title="Add locale"
      subtitle="Register a new language for translation"
      icon="globe"
      :accent="accent"
      width="480px"
      @close="showAddLocale = false"
    >
      <div class="modal-field">
        <div class="field-label">Language tag (BCP-47)</div>
        <input
          v-model="newTag"
          autofocus
          placeholder="e.g. fr, es-MX, zh-Hans"
          class="field-input" >
      </div>
      <div class="field-grid">
        <div class="modal-field">
          <div class="field-label">English name</div>
          <input v-model="newName" placeholder="e.g. French" class="field-input" >
        </div>
        <div class="modal-field">
          <div class="field-label">Local name</div>
          <input v-model="newLocalName" placeholder="e.g. Français" class="field-input" >
        </div>
      </div>
      <div v-if="saveError" class="error-msg">{{ saveError }}</div>

      <template #footer>
        <span class="shortcut-hint"><Icon name="key" :size="11" color="var(--fg-3)" /><span class="mono">⌘ ↵</span> to add</span>
        <span class="spacer" />
        <Button size="sm" @click="showAddLocale = false">Cancel</Button>
        <Button
          primary
          size="sm"
          :accent="accent"
          icon="plus"
          :disabled="!canSave || saving"
          @click="handleAddLocale">
          {{ saving ? 'Adding…' : 'Add locale' }}
        </Button>
      </template>
    </Modal>

    <!-- Edit locale -->
    <Modal
      v-if="editingLocale"
      title="Edit locale"
      :subtitle="editingLocale.tag"
      icon="pencil"
      :accent="accent"
      width="480px"
      @close="editingLocale = null"
    >
      <div class="field-grid">
        <div class="modal-field">
          <div class="field-label">English name</div>
          <input v-model="editName" autofocus class="field-input" >
        </div>
        <div class="modal-field">
          <div class="field-label">Local name</div>
          <input v-model="editLocalName" class="field-input" >
        </div>
      </div>
      <div v-if="editError" class="error-msg">{{ editError }}</div>

      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="editingLocale = null">Cancel</Button>
        <Button
          primary
          size="sm"
          :accent="accent"
          :disabled="editSaving"
          @click="handleEditLocale">
          {{ editSaving ? 'Saving…' : 'Save' }}
        </Button>
      </template>
    </Modal>

    <!-- Delete locale -->
    <ConfirmModal
      v-if="deletingTag"
      title="Delete locale"
      confirm-label="Delete locale"
      :loading="deleteInProgress"
      @close="deletingTag = null"
      @confirm="handleDeleteLocale"
    >
      <p class="confirm-text">
        Are you sure you want to delete <strong class="mono">{{ deletingTag }}</strong>?
      </p>
      <div v-if="deleteError" class="error-msg">{{ deleteError }}</div>
    </ConfirmModal>

    <SectionCard title="Registered locales" glass>
      <template #right>
        <span class="mono updated-label">{{ languages.length }} total</span>
      </template>

      <GlassTable
        :columns="localeColumns"
        :rows="languages"
        :loading="isLoading && languages.length === 0"
        row-key="tag"
        actions-width="60px"
        empty-text="No locales registered."
        @row-click="(row) => openEdit(row as any)"
      >
        <template #col-tag="{ row }">
          <span class="locale-badge">{{ row.tag.toUpperCase() }}</span>
        </template>
        <template #col-name="{ row }">
          <span class="locale-name">{{ row.name }}</span>
        </template>
        <template #actions="{ row }">
          <button class="row-action" @click.stop="openEdit(row as any)">
            <Icon name="pencil" :size="13" color="var(--fg-3)" />
          </button>
          <button class="row-action" @click.stop="deletingTag = row.tag">
            <Icon name="trash" :size="13" color="var(--fg-3)" />
          </button>
        </template>
      </GlassTable>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.updated-label { font-size: 11px; color: var(--fg-3); }

.locale-badge {
  display: inline-flex; align-items: center; justify-content: center;
  width: 52px; height: 28px; border-radius: 6px;
  background: color-mix(in oklch, var(--bg-3) 30%, transparent);
  border: 1px solid color-mix(in oklch, var(--line) 38%, transparent);
  font-size: 10.5px; font-weight: 700; color: var(--fg-1);
  letter-spacing: 0.04em; font-family: var(--font-mono);
}

.locale-name { font-size: 13.5px; font-weight: 500; color: var(--fg-0); }

.row-action {
  padding: 5px; border-radius: 4px; color: var(--fg-3); cursor: pointer;
}

.row-action:hover {
  background: color-mix(in oklch, var(--bg-3) 30%, transparent);
}

.spacer { flex: 1; }

/* ── Form fields ── */
.modal-field { display: flex; flex-direction: column; gap: 6px; }
.field-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }

.shortcut-hint {
  font-size: 11.5px; color: var(--fg-3);
  display: inline-flex; align-items: center; gap: 5px;
}

.confirm-text { font-size: 13px; color: var(--fg-1); line-height: 1.5; margin: 0; }
</style>
