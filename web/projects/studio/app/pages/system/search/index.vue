<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

// ── Queries ──────────────────────────────────────────────────────────

const configGql = gql`
  query GetSearchConfiguration {
    configurations {
      configuration(key: "search") {
        id
        key
        value
      }
    }
  }
`

const previewMetadataGql = gql`
  query PreviewMetadata($id: UUID!) {
    search {
      configuration {
        previewMetadata(id: $id)
      }
    }
  }
`

// ── Data fetching ────────────────────────────────────────────────────

const { data, status, refresh } = useAsyncQuery<{
  configurations: { configuration: { id: string; key: string; value: unknown } | null }
}>('search-config', configGql, {})

const configValue = computed(() => data.value?.configurations?.configuration?.value ?? null)

const editableConfig = ref<unknown>(null)
const isLoading = computed(() => status.value === 'pending')

watch(configValue, (val) => {
  if (val !== null) {
    editableConfig.value = JSON.parse(JSON.stringify(val))
  }
}, { immediate: true })

// ── Save Configuration ──────────────────────────────────────────────

const saving = ref(false)

async function saveConfiguration() {
  saving.value = true
  try {
    await gqlMutation(
      gql`mutation SetSearchConfiguration($configuration: ConfigurationInput!) {
        configurations { setConfiguration(configuration: $configuration) { id value } }
      }`,
      {
        configuration: {
          key: 'search',
          value: editableConfig.value,
          description: 'Search Transformation Configuration',
          public: false,
        },
      },
    )
    toast.success('Search configuration saved')
    await refresh()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : 'Failed to save configuration'
    toast.error(msg)
  } finally {
    saving.value = false
  }
}

// ── Reindex ─────────────────────────────────────────────────────────

const reindexing = ref(false)

async function triggerReindex(deleteFirst: boolean) {
  reindexing.value = true
  try {
    await gqlMutation(
      gql`mutation Reindex($storageName: String!, $deleteFirst: Boolean!) {
        storageSystems { reindex(storageName: $storageName, deleteFirst: $deleteFirst) }
      }`,
      { storageName: 'Admin Search Index', deleteFirst },
    )
    toast.success(deleteFirst ? 'Full reindex started' : 'Reindex started')
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : 'Reindex failed'
    toast.error(msg)
  } finally {
    reindexing.value = false
  }
}

// ── Preview ─────────────────────────────────────────────────────────

const previewId = ref('')
const previewing = ref(false)
const previewResult = ref<unknown>(null)
const previewError = ref('')

async function runPreview() {
  if (!previewId.value.trim()) return
  previewing.value = true
  previewResult.value = null
  previewError.value = ''
  try {
    const result = await gqlQuery<{ search: { configuration: { previewMetadata: unknown } } }>(
      previewMetadataGql,
      { id: previewId.value.trim() },
    )
    previewResult.value = result.search.configuration.previewMetadata
  } catch (e: unknown) {
    previewError.value = e instanceof Error ? e.message : 'Preview failed'
  } finally {
    previewing.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Search')"
        title="Search"
        subtitle="Search indexing configuration and reindex controls"
      >
        <template #actions>
          <Button
            size="sm"
            icon="refresh"
            :disabled="reindexing"
            @click="triggerReindex(false)">
            Reindex
          </Button>
          <Button
            size="sm"
            icon="refresh"
            :disabled="reindexing"
            @click="triggerReindex(true)">
            Full Reindex
          </Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !configValue" class="loading-state">Loading...</div>

    <template v-else>
      <!-- Configuration Editor -->
      <SectionCard title="Search Configuration" subtitle="JSONata expressions and settings for search indexing.">
        <template #right>
          <Button
            size="sm"
            primary
            :accent="accent"
            :disabled="saving"
            @click="saveConfiguration">
            {{ saving ? 'Saving...' : 'Save' }}
          </Button>
        </template>
        <div class="config-editor">
          <JsonEditorVue
            v-if="editableConfig !== null"
            v-model="editableConfig"
            :main-menu-bar="true"
            :navigation-bar="false"
            :status-bar="true"
            class="json-editor"
          />
          <div v-else class="empty-state">No search configuration found. Save to create one.</div>
        </div>
      </SectionCard>

      <!-- Preview -->
      <SectionCard title="Preview Index" subtitle="Preview how a metadata item will be indexed.">
        <div class="preview-section">
          <div class="preview-input-row">
            <TextInput
              v-model="previewId"
              placeholder="Metadata ID (UUID)"
              @keyup.enter="runPreview"
            />
            <Button size="sm" :disabled="!previewId.trim() || previewing" @click="runPreview">
              {{ previewing ? 'Loading...' : 'Preview' }}
            </Button>
          </div>

          <div v-if="previewError" class="preview-error">{{ previewError }}</div>

          <div v-if="previewResult !== null" class="preview-output">
            <JsonEditorVue
              :model-value="previewResult"
              :main-menu-bar="false"
              :navigation-bar="false"
              :status-bar="false"
              read-only
              class="json-editor"
            />
          </div>
        </div>
      </SectionCard>
    </template>
  </PageShell>
</template>

<style scoped>
.loading-state {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.config-editor {
  padding: 12px 16px 16px;
}

.json-editor {
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
  min-height: 300px;
}

.empty-state {
  font-size: 12.5px;
  color: var(--fg-3);
  padding: 24px;
  text-align: center;
}

.preview-section {
  padding: 12px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.preview-input-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.preview-input-row > :first-child {
  flex: 1;
}

.preview-error {
  padding: 12px;
  border-radius: var(--r-sm);
  background: color-mix(in oklch, var(--err) 10%, transparent);
  color: var(--err);
  font-size: 12.5px;
  font-family: monospace;
}

.preview-output {
  min-height: 200px;
}
</style>
