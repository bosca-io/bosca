<script setup lang="ts">
import gql from 'graphql-tag'

const props = withDefaults(defineProps<{
  accent?: string
  languageTag?: string
}>(), {
  accent: '#5ec5ff',
  languageTag: 'en',
})

const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const router = useRouter()
const toast = useToast()

const importUrlGql = gql`
  mutation ImportUrl($metadata: MetadataInput!, $url: String!, $headers: JSON, $ready: Boolean) {
    content {
      metadata {
        importUrl(metadata: $metadata, url: $url, headers: $headers, ready: $ready) {
          id
        }
      }
    }
  }
`

const sourcesGql = gql`
  query GetSourcesForImport {
    content {
      sources {
        all {
          id
          name
        }
      }
    }
  }
`

const open = ref(false)
const activeTab = ref<'url' | 'csv'>('url')

// ── Sources ──────────────────────────────────────────────────────────────────
interface SourceOption { value: string; label: string }
const sourceOptions = ref<SourceOption[]>([{ value: '', label: 'Auto-detect from URL' }])

async function loadSources() {
  try {
    const result = await gqlQuery<{
      content: { sources: { all: Array<{ id: string; name: string }> } }
    }>(sourcesGql)
    sourceOptions.value = [
      { value: '', label: 'Auto-detect from URL' },
      ...result.content.sources.all.map(s => ({ value: s.id, label: s.name })),
    ]
  } catch (e) {
    console.error('Failed to load sources', e)
  }
}

// ── URL tab ─────────────────────────────────────────────────────────────────
const urlName = ref('')
const urlValue = ref('')
const urlContentType = ref('')
const urlHeaders = ref('')
const urlSourceId = ref('')
const urlLoading = ref(false)
const autoReady = ref(true)

function resetUrl() {
  urlName.value = ''
  urlValue.value = ''
  urlContentType.value = ''
  urlHeaders.value = ''
  urlSourceId.value = ''
}

async function onUrlImport() {
  if (!urlName.value.trim() || !urlValue.value.trim()) return
  urlLoading.value = true
  try {
    let parsedHeaders: Record<string, string> | undefined
    if (urlHeaders.value.trim()) {
      try {
        parsedHeaders = JSON.parse(urlHeaders.value.trim())
      } catch {
        toast.error('Invalid headers JSON')
        return
      }
    }
    const metadata: Record<string, unknown> = {
      name: urlName.value.trim(),
      contentType: urlContentType.value.trim() || 'application/octet-stream',
      languageTag: props.languageTag || 'en',
    }
    if (urlSourceId.value) {
      metadata.source = { id: urlSourceId.value }
    }
    const result = await gqlMutation<{
      content: { metadata: { importUrl: { id: string } } }
    }>(importUrlGql, {
      metadata,
      url: urlValue.value.trim(),
      headers: parsedHeaders,
      ready: autoReady.value,
    })
    const newId = result?.content?.metadata?.importUrl?.id
    if (newId) {
      toast.success('Import started — content is downloading in the background.')
      open.value = false
      resetUrl()
      await router.push(`/cms/editor/${newId}`)
    }
  } catch (e: unknown) {
    toast.error(`Import failed: ${e instanceof Error ? e.message : 'Unknown error'}`)
  } finally {
    urlLoading.value = false
  }
}

// ── CSV tab ─────────────────────────────────────────────────────────────────
const csvRows = ref<Array<{ name: string; url: string; contentType: string; source: string }>>([])
const csvImporting = ref(false)
const csvProgress = ref({ current: 0, total: 0 })
const csvErrors = ref<Array<{ name: string; error: string }>>([])
const csvHeaders = ref('')
const csvFileRef = ref<HTMLInputElement>()

function parseCsvLine(line: string): string[] {
  const result: string[] = []
  let current = ''
  let inQuotes = false
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]!
    if (inQuotes) {
      if (ch === '"' && line[i + 1] === '"') {
        current += '"'
        i++
      } else if (ch === '"') {
        inQuotes = false
      } else {
        current += ch
      }
    } else {
      if (ch === '"') {
        inQuotes = true
      } else if (ch === ',') {
        result.push(current)
        current = ''
      } else {
        current += ch
      }
    }
  }
  result.push(current)
  return result
}

function processCsvFile(file: File) {
  if (!file.name.endsWith('.csv')) {
    toast.error('Please select a CSV file')
    return
  }
  const reader = new FileReader()
  reader.onload = (e) => {
    const text = e.target?.result as string
    const lines = text.split(/\r?\n/).filter(l => l.trim())
    if (lines.length < 2) {
      toast.error('CSV must have a header row and at least one data row')
      return
    }
    const header = parseCsvLine(lines[0]!)
    const nameCol = header.findIndex(h => /^(class|name|title)$/i.test(h.trim()))
    const urlCol = header.findIndex(h => /url/i.test(h.trim()))
    const contentTypeCol = header.findIndex(h => /content.?type|mime/i.test(h.trim()))
    const sourceCol = header.findIndex(h => /^source$/i.test(h.trim()))

    if (nameCol === -1 || urlCol === -1) {
      toast.error(`CSV must have a name/class/title column and a URL column. Found: ${header.map(h => h.trim()).join(', ')}`)
      return
    }

    csvRows.value = lines.slice(1)
      .map(l => parseCsvLine(l))
      .filter(row => row[nameCol]?.trim() && row[urlCol]?.trim())
      .map(row => ({
        name: row[nameCol]!.trim(),
        url: row[urlCol]!.trim(),
        contentType: (contentTypeCol !== -1 ? row[contentTypeCol]?.trim() : '') || '',
        source: (sourceCol !== -1 ? row[sourceCol]?.trim() : '') || '',
      }))

    if (csvRows.value.length === 0) {
      toast.error('No valid rows found in CSV')
    }
    csvErrors.value = []
    csvProgress.value = { current: 0, total: csvRows.value.length }
  }
  reader.readAsText(file)
}

function onCsvFileSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  input.value = ''
  processCsvFile(file)
}

function onCsvDrop(e: DragEvent) {
  e.preventDefault()
  const file = e.dataTransfer?.files?.[0]
  if (file) processCsvFile(file)
}

async function onCsvImport() {
  csvImporting.value = true
  csvErrors.value = []
  csvProgress.value = { current: 0, total: csvRows.value.length }

  let parsedHeaders: Record<string, string> | undefined
  if (csvHeaders.value.trim()) {
    try {
      parsedHeaders = JSON.parse(csvHeaders.value.trim())
    } catch {
      toast.error('Invalid headers JSON')
      csvImporting.value = false
      return
    }
  }

  for (const row of csvRows.value) {
    try {
      const metadata: Record<string, unknown> = {
        name: row.name,
        contentType: row.contentType || 'application/octet-stream',
        languageTag: props.languageTag || 'en',
      }
      await gqlMutation(importUrlGql, {
        metadata,
        url: row.url,
        headers: parsedHeaders,
        ready: autoReady.value,
      })
    } catch (e: unknown) {
      csvErrors.value.push({ name: row.name, error: e instanceof Error ? e.message : 'Unknown error' })
    }
    csvProgress.value.current++
  }

  csvImporting.value = false
  const succeeded = csvRows.value.length - csvErrors.value.length
  if (csvErrors.value.length === 0) {
    toast.success(`All ${succeeded} items imported successfully`)
    open.value = false
    resetCsv()
  } else {
    toast.warn(`Imported ${succeeded} of ${csvRows.value.length} items — ${csvErrors.value.length} failed`)
  }
}

function resetCsv() {
  csvRows.value = []
  csvErrors.value = []
  csvProgress.value = { current: 0, total: 0 }
  csvHeaders.value = ''
}

function onOpen() {
  open.value = true
  loadSources()
}

function onClose() {
  open.value = false
  resetUrl()
  resetCsv()
}
</script>

<template>
  <input
    ref="csvFileRef"
    type="file"
    accept=".csv"
    class="hidden-file"
    @change="onCsvFileSelected"
  >
  <Button
    icon="inbox"
    size="sm"
    :accent="accent"
    @click="onOpen">
    Import Content
  </Button>
  <Modal
    v-if="open"
    title="Import Content"
    subtitle="Import content from a URL or CSV file."
    icon="inbox"
    :accent="accent"
    width="580px"
    @close="onClose"
  >
    <!-- Tab bar -->
    <div class="tab-bar">
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'url' }"
        @click="activeTab = 'url'"
      >
        <Icon name="link" :size="13" />
        From URL
      </button>
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'csv' }"
        @click="activeTab = 'csv'"
      >
        <Icon name="file" :size="13" />
        From CSV
      </button>
    </div>

    <!-- URL tab -->
    <template v-if="activeTab === 'url'">
      <TextInput
        v-model="urlName"
        label="Name"
        placeholder="My Video"
        autofocus
      />
      <TextInput
        v-model="urlValue"
        label="Url"
        placeholder="https://example.com/video.mp4"
      />
      <div class="field">
        <label class="field-label">Source <span class="optional-hint">(optional)</span></label>
        <Select
          v-model="urlSourceId"
          :options="sourceOptions"
          placeholder="Auto-detect from URL"
          size="sm"
        />
      </div>
      <TextInput
        v-model="urlContentType"
        label="Content Type"
        placeholder="Auto-detected from URL (e.g. video/mp4)"
      />
      <div class="field">
        <label class="field-label">Headers <span class="optional-hint">(optional)</span></label>
        <textarea
          v-model="urlHeaders"
          class="textarea"
          rows="3"
          placeholder='{"Authorization": "Bearer token"}'
        />
      </div>
    </template>

    <!-- CSV tab -->
    <template v-if="activeTab === 'csv'">
      <div
        v-if="csvRows.length === 0"
        class="csv-dropzone"
        @click="csvFileRef?.click()"
        @dragover.prevent
        @drop="onCsvDrop"
      >
        <Icon name="file" :size="28" color="var(--fg-3)" />
        <span class="csv-drop-label">Drop a CSV file here or click to browse</span>
        <span class="csv-drop-hint">Columns: Name, URL (required) / Content Type, Source (optional)</span>
      </div>

      <template v-else>
        <div class="csv-summary">
          <span class="csv-count">{{ csvRows.length }} item(s) ready to import</span>
          <Button
            size="sm"
            icon="x"
            :disabled="csvImporting"
            @click="csvFileRef?.click()">Change File</Button>
        </div>

        <div class="field">
          <label class="field-label">Headers (optional, applies to all rows)</label>
          <textarea
            v-model="csvHeaders"
            class="textarea"
            rows="2"
            :disabled="csvImporting"
            placeholder='{"Authorization": "Bearer token"}'
          />
        </div>

        <div v-if="csvImporting" class="csv-progress">
          <span>Importing {{ csvProgress.current }} of {{ csvProgress.total }}...</span>
          <div class="progress-bar">
            <div class="progress-fill" :style="{ width: `${(csvProgress.current / csvProgress.total) * 100}%` }" />
          </div>
        </div>

        <div v-if="csvErrors.length > 0" class="csv-errors">
          <span class="csv-errors-title">{{ csvErrors.length }} error(s):</span>
          <div class="csv-errors-list">
            <div v-for="err in csvErrors" :key="err.name" class="csv-error-row">
              <strong>{{ err.name }}:</strong> {{ err.error }}
            </div>
          </div>
        </div>

        <div class="csv-preview">
          <table class="csv-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>URL</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, i) in csvRows" :key="i">
                <td>{{ row.name }}</td>
                <td class="csv-url-cell" :title="row.url">{{ row.url }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </template>

    <template #footer>
      <label class="ready-toggle">
        <input v-model="autoReady" type="checkbox" :disabled="urlLoading || csvImporting" >
        <span>Set ready after import</span>
      </label>
      <span class="footer-spacer" />
      <Button size="sm" :disabled="csvImporting" @click="onClose">Cancel</Button>
      <Button
        v-if="activeTab === 'url'"
        size="sm"
        primary
        :accent="accent"
        icon="inbox"
        :disabled="!urlName.trim() || !urlValue.trim() || urlLoading"
        @click="onUrlImport"
      >
        Import
      </Button>
      <Button
        v-if="activeTab === 'csv'"
        size="sm"
        primary
        :accent="accent"
        icon="inbox"
        :disabled="csvRows.length === 0 || csvImporting"
        @click="onCsvImport"
      >
        Import All
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.hidden-file {
  position: absolute;
  width: 0;
  height: 0;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}

.tab-bar {
  display: flex;
  gap: 2px;
  background: var(--bg-2);
  border-radius: var(--r-sm);
  padding: 3px;
}

.tab-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 7px 12px;
  border: none;
  border-radius: 4px;
  background: none;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--fg-3);
  cursor: pointer;
  transition: background 0.12s, color 0.12s;
}

.tab-btn:hover { color: var(--fg-1); }
.tab-btn.active {
  background: var(--bg-3);
  color: var(--fg-0);
}

.optional-hint {
  font-weight: 400;
  color: var(--fg-4);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.textarea {
  width: 100%;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  color: var(--fg-0);
  font: inherit;
  font-size: 13px;
  padding: 8px 10px;
  resize: vertical;
  box-sizing: border-box;
}

.textarea:focus {
  outline: none;
  border-color: var(--brand-2);
}

.textarea::placeholder {
  color: var(--fg-3);
}

.csv-dropzone {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 16px;
  border: 2px dashed var(--line-2);
  border-radius: var(--r-md);
  cursor: pointer;
  transition: border-color 0.15s;
}

.csv-dropzone:hover {
  border-color: var(--fg-3);
}

.csv-drop-label {
  font-size: 13px;
  color: var(--fg-2);
}

.csv-drop-hint {
  font-size: 11px;
  color: var(--fg-4);
}

.csv-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.csv-count {
  font-size: 12.5px;
  color: var(--fg-2);
}

.csv-progress {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12.5px;
  color: var(--fg-2);
}

.progress-bar {
  height: 4px;
  background: var(--bg-3);
  border-radius: 2px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--brand-accent);
  transition: width 0.2s;
}

.csv-errors {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.csv-errors-title {
  font-size: 12px;
  font-weight: 550;
  color: var(--err);
}

.csv-errors-list {
  max-height: 100px;
  overflow: auto;
  padding: 8px;
  background: color-mix(in oklch, var(--err) 6%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--err) 20%, transparent);
  border-radius: var(--r-sm);
  font-size: 12px;
  color: var(--fg-2);
}

.csv-error-row { padding: 2px 0; }

.csv-preview {
  max-height: 200px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

.csv-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.csv-table th {
  position: sticky;
  top: 0;
  background: var(--bg-2);
  text-align: left;
  padding: 6px 10px;
  font-weight: 550;
  color: var(--fg-2);
  border-bottom: 1px solid var(--line);
}

.csv-table td {
  padding: 4px 10px;
  border-bottom: 1px solid var(--line);
  color: var(--fg-1);
}

.csv-url-cell {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ready-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--fg-2);
  cursor: pointer;
}

.footer-spacer { flex: 1; }
</style>
