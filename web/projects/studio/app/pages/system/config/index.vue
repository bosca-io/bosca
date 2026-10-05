<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const configsGql = gql`
  query GetConfigurations {
    configurations { all { id key description public value permissions { action group { id name } } } }
  }
`

const setConfigGql = gql`
  mutation SetConfiguration($config: ConfigurationInput!) {
    configurations { setConfiguration(configuration: $config) { id key description public value } }
  }
`

const deleteConfigGql = gql`
  mutation DeleteConfiguration($key: String!) {
    configurations { deleteConfiguration(key: $key) }
  }
`

interface ConfigEntry {
  id: string
  key: string
  description: string
  public: boolean
  value: unknown
  permissions: Array<{ action: string; group: { id: string; name: string } }>
}

const { data, status, refresh } = useAsyncQuery<{
  configurations: { all: ConfigEntry[] }
}>('system-configs', configsGql, {}, { server: false })

const configs = computed(() => data.value?.configurations?.all ?? [])
const isLoading = computed(() => status.value === 'pending')

const searchQuery = ref('')
const filteredConfigs = computed(() => {
  const q = searchQuery.value.toLowerCase()
  if (!q) return configs.value
  return configs.value.filter(c =>
    c.key.toLowerCase().includes(q) || c.description.toLowerCase().includes(q),
  )
})

const editingKey = ref<string | null>(null)
const editForm = reactive({ key: '', description: '', value: '', public: false })
const saving = ref(false)
const deleteTarget = ref<string | null>(null)

const valueError = computed(() => {
  if (!editForm.value.trim()) return ''
  try { JSON.parse(editForm.value); return '' } catch (e) {
    return e instanceof Error ? e.message : 'Invalid JSON'
  }
})

function startEdit(config: ConfigEntry) {
  editingKey.value = config.key
  editForm.key = config.key
  editForm.description = config.description
  editForm.value = JSON.stringify(config.value, null, 2)
  editForm.public = config.public
}

function startNew() {
  editingKey.value = '__new__'
  editForm.key = ''
  editForm.description = ''
  editForm.value = ''
  editForm.public = false
}

function cancelEdit() {
  editingKey.value = null
}

async function onSave() {
  let parsedValue: unknown
  try {
    parsedValue = JSON.parse(editForm.value)
  } catch {
    toast.error('Value must be valid JSON')
    return
  }

  saving.value = true
  try {
    await gqlMutation(setConfigGql, {
      config: {
        key: editForm.key,
        description: editForm.description,
        value: parsedValue,
        public: editForm.public,
        permissions: [],
      },
    })
    toast.success('Configuration saved')
    editingKey.value = null
    refresh()
  } catch {
    toast.error('Failed to save configuration')
  } finally {
    saving.value = false
  }
}

async function onConfirmDelete() {
  if (!deleteTarget.value) return
  try {
    await gqlMutation(deleteConfigGql, { key: deleteTarget.value })
    toast.success('Configuration deleted')
    deleteTarget.value = null
    refresh()
  } catch {
    toast.error('Failed to delete')
  }
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'string') return value.length > 60 ? value.slice(0, 60) + '…' : value
  return JSON.stringify(value).slice(0, 60) + (JSON.stringify(value).length > 60 ? '…' : '')
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Configuration')"
        title="Configuration"
        subtitle="Global key-value configuration store"
      >
        <template #actions>
          <Button
            size="sm"
            icon="plus"
            primary
            :accent="accent"
            @click="startNew">New Entry</Button>
        </template>
      </PageHeader>
    </template>

    <div class="config-content">
      <div class="search-bar">
        <input v-model="searchQuery" class="search-input" placeholder="Filter configurations…">
      </div>

      <div v-if="isLoading && !configs.length" class="loading-state">Loading…</div>

      <!-- Edit/Create form -->
      <div v-if="editingKey" class="edit-panel">
        <div class="edit-header">
          <span class="edit-title">{{ editingKey === '__new__' ? 'New Configuration' : `Edit: ${editForm.key}` }}</span>
          <button class="edit-cancel" @click="cancelEdit">
            <Icon name="x" :size="14" />
          </button>
        </div>
        <div class="edit-form">
          <TextInput
            v-model="editForm.key"
            label="Key"
            placeholder="e.g. app.feature.enabled"
            :disabled="editingKey !== '__new__'"
            mono />
          <TextInput v-model="editForm.description" label="Description" placeholder="What this config does" />
          <CodeEditor
            v-model="editForm.value"
            label="Value"
            language="json"
            :rows="8"
            placeholder='e.g. {"enabled": true}' />
          <div v-if="valueError" class="value-error mono">{{ valueError }}</div>
          <Checkbox
            v-model="editForm.public"
            label="Public (accessible without auth)"
            :accent="accent" />
          <div class="edit-actions">
            <Button size="sm" @click="cancelEdit">Cancel</Button>
            <Button
              size="sm"
              primary
              :accent="accent"
              :disabled="!editForm.key.trim() || saving || !!valueError"
              @click="onSave">
              {{ saving ? 'Saving…' : 'Save' }}
            </Button>
          </div>
        </div>
      </div>

      <!-- Config list -->
      <div class="config-list">
        <div
          v-for="cfg in filteredConfigs"
          :key="cfg.key"
          class="config-row"
          @click="startEdit(cfg)"
        >
          <div class="config-key-col">
            <span class="config-key mono">{{ cfg.key }}</span>
            <span class="config-desc">{{ cfg.description }}</span>
          </div>
          <div class="config-value-col">
            <span class="config-value mono">{{ formatValue(cfg.value) }}</span>
          </div>
          <div class="config-badges">
            <Badge v-if="cfg.public" color="#34d99a">Public</Badge>
          </div>
          <button class="config-delete" title="Delete" @click.stop="deleteTarget = cfg.key">
            <Icon name="trash" :size="13" color="var(--fg-4)" />
          </button>
        </div>

        <div v-if="!filteredConfigs.length && !isLoading" class="config-empty">
          {{ searchQuery ? 'No matching configurations' : 'No configurations yet' }}
        </div>
      </div>
    </div>

    <ConfirmModal
      v-if="deleteTarget"
      title="Delete Configuration"
      :subtitle="`Delete '${deleteTarget}'? This cannot be undone.`"
      @close="deleteTarget = null"
      @confirm="onConfirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.config-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.search-bar {
  display: flex;
  align-items: center;
}

.search-input {
  width: 100%;
  max-width: 400px;
  padding: 7px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-2);
  color: var(--fg-1);
  font-size: 13px;
  font-family: inherit;
}

.search-input:focus {
  outline: none;
  border-color: var(--brand-2);
}

.search-input::placeholder {
  color: var(--fg-3);
}

.loading-state {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
}

.edit-panel {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  padding: 16px;
}

.edit-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.edit-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.edit-cancel {
  color: var(--fg-3);
  border-radius: 6px;
  padding: 4px;
}

.edit-cancel:hover {
  background: var(--bg-3);
}

.edit-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.value-error {
  font-size: 11.5px;
  color: var(--err);
  margin-top: -6px;
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.config-list {
  display: flex;
  flex-direction: column;
}

.config-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 14px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 40%, transparent);
  cursor: pointer;
  transition: background 0.15s;
}

.config-row:hover {
  background: color-mix(in oklch, var(--fg-2) 4%, transparent);
}

.config-key-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.config-key {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.config-desc {
  font-size: 11.5px;
  color: var(--fg-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-value-col {
  flex: 1;
  min-width: 0;
}

.config-value {
  font-size: 12px;
  color: var(--fg-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-badges {
  flex-shrink: 0;
}

.config-delete {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  opacity: 0;
  transition: opacity 0.15s, background 0.15s;
}

.config-row:hover .config-delete {
  opacity: 1;
}

.config-delete:hover {
  background: color-mix(in oklch, var(--err) 12%, transparent);
}

.config-empty {
  padding: 40px;
  text-align: center;
  font-size: 13px;
  color: var(--fg-3);
}
</style>
