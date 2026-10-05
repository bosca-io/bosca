<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'
import type { GlassTableColumn, OverflowMenuItem, SelectOption } from '@bosca/ui'
import {
  DBL_BUNDLE_ACCEPT,
  importDblBundle,
  isDblBundle,
} from '~/utils/bibleBundleUpload'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const router = useRouter()
const toast = useToast()
const { auth } = useAuth()
const showAddBible = ref(false)

const STATUS: Record<string, [string, string]> = {
  draft: ['Draft', '#6c7388'],
  review: ['Review', '#5ec5ff'],
  translate: ['Translation', '#ffb547'],
  scheduled: ['Scheduled', '#a78bff'],
  published: ['Published', '#34d99a'],
  archived: ['Archived', '#3a4256'],
}

const VISIBILITY: Record<string, { label: string; color: string }> = {
  public: { label: 'Public', color: '#34d99a' },
  private: { label: 'Private', color: '#6c7388' },
  locked: { label: 'Locked', color: '#ff5d6c' },
}

// ── Language ──────────────────────────────────────────────────────────────────
const language = useLanguage()
const selectedLanguage = computed({
  get: () => language.current.value.tag === 'en' && !language.languages.find(l => l.tag === 'en') ? '' : language.current.value.tag,
  set: (tag: string) => { if (tag) language.setLanguageTag(tag) },
})
const languageOptions = computed<SelectOption[]>(() =>
  language.languages
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name))
    .map(l => ({ value: l.tag, label: `${l.name} (${l.tag})` })),
)

// ── Collection pagination ────────────────────────────────────────────────────
const offset = ref(0)
const limit = ref(10)
const languageTag = computed(() => selectedLanguage.value || null)

watch(selectedLanguage, () => {
  offset.value = 0
})

// ── Bibles collection query ──────────────────────────────────────────────────
const biblesGql = gql`
  query GetBibleCollectionItems($languageTag: String, $limit: Int!, $offset: Long!) {
    content {
      slug(slug: "bibles") {
        ... on Collection {
          __typename
          itemsCount(
            contentTypes: ["bosca/v-bible", "bosca/x-bible"]
            languageTag: $languageTag
            languageResolutionContext: "bibles"
          )
          items(
            contentTypes: ["bosca/v-bible", "bosca/x-bible"]
            languageTag: $languageTag
            languageResolutionContext: "bibles"
            limit: $limit
            offset: $offset
          ) {
            ... on Metadata {
              __typename
              id
              name
              version
              attributes
              modified
              content {
                type
              }
              categories {
                id
                name
              }
              ready
              bibles {
                variant
                enabled
                defaultVariant
                name
                nameLocal
                abbreviation
                abbreviationLocal
                languages {
                  nameLocal
                  name
                  iso
                }
              }
              public
              publicContent
              publicSupplementary
              locked
              searchable
              workflow {
                state
                pending
              }
            }
          }
        }
      }
    }
  }
`

interface BibleLanguage {
  nameLocal: string | null
  name: string | null
  iso: string | null
}

interface BibleVariant {
  variant: string
  enabled: boolean
  defaultVariant: boolean
  name: string | null
  nameLocal: string | null
  abbreviation: string | null
  abbreviationLocal: string | null
  languages: BibleLanguage[]
}

interface BibleMetadata {
  __typename: 'Metadata'
  id: string
  version: number
  name: string
  attributes: Record<string, unknown> | null
  modified: string
  content: { type: string } | null
  categories: Array<{ id: string; name: string }> | null
  ready: boolean
  bibles: BibleVariant[]
  public: boolean
  publicContent: boolean
  publicSupplementary: boolean
  locked: boolean
  searchable: boolean
  workflow: { state: string; pending: string | null } | null
}

type BibleRow = BibleMetadata & {
  rowKey: string
  bible: BibleVariant
}

const { data, status, refresh } = useAsyncQuery<{
  content: {
    slug: {
      __typename: 'Collection'
      itemsCount: number
      items: BibleMetadata[]
    } | null
  }
}>('bibles-collection-items', biblesGql, {
  languageTag,
  limit,
  offset,
})

const metadataItems = computed<BibleMetadata[]>(() =>
  data.value?.content?.slug?.items?.filter(item => item.__typename === 'Metadata') ?? [],
)

const items = computed<BibleRow[]>(() =>
  metadataItems.value.flatMap(metadata => metadata.bibles.map(bible => ({
    ...metadata,
    bible,
    rowKey: `${metadata.id}:${metadata.version}:${bible.variant}`,
  }))),
)

const totalItems = computed(() => data.value?.content?.slug?.itemsCount ?? 0)
const totalPages = computed(() => Math.ceil(totalItems.value / limit.value))
const currentPage = computed(() => Math.floor(offset.value / limit.value) + 1)
const isLoading = computed(() => status.value === 'pending')

function goToPage(page: number) {
  const clamped = Math.max(1, Math.min(page, totalPages.value))
  offset.value = (clamped - 1) * limit.value
}

// ── Subscription ─────────────────────────────────────────────────────────────
const { useSubscription } = useGraphQL()

const subscriptionGql = gql`
  subscription BibleChanges {
    metadata {
      type
      id
    }
  }
`

useSubscription<{ metadata: { type: string; id: string } }>(
  subscriptionGql,
  undefined,
  (sub) => {
    const changedId = sub.metadata?.id
    if (changedId && items.value.some(m => m.id === changedId)) {
      refresh()
    }
  },
)

// ── Helpers ──────────────────────────────────────────────────────────────────
function getStatus(m: BibleRow): [string, string] {
  const state = m.workflow?.pending ?? m.workflow?.state ?? 'draft'
  return STATUS[state] ?? STATUS.draft!
}

function getVisibility(m: BibleRow): { label: string; color: string } {
  if (m.locked) return VISIBILITY.locked!
  if (m.public) return VISIBILITY.public!
  return VISIBILITY.private!
}

function getBibleDisplayName(m: BibleRow): string {
  return m.bible.nameLocal || m.bible.name || m.name
}

function getBibleSubName(m: BibleRow): string {
  if (m.bible.nameLocal && m.bible.name && m.bible.nameLocal !== m.bible.name) {
    return m.bible.name
  }
  return ''
}

function getBibleLanguage(m: BibleRow): string {
  const lang = m.bible.languages?.[0]
  if (!lang) return '--'
  const local = lang.nameLocal || lang.name || ''
  const iso = lang.iso ? ` (${lang.iso})` : ''
  return local + iso
}

function getBibleLanguageSub(m: BibleRow): string {
  const lang = m.bible.languages?.[0]
  if (!lang) return ''
  if (lang.nameLocal && lang.name && lang.nameLocal !== lang.name) {
    return lang.name
  }
  return ''
}

function formatPublishDate(m: BibleRow): string {
  const published = m.attributes?.published
  if (!published) return '--'
  let dt: Date
  if (typeof published === 'string') {
    const n = parseInt(published)
    dt = isNaN(n) ? new Date(Date.parse(published)) : new Date(n)
  } else {
    dt = new Date(published as string | number)
  }
  try {
    return dt.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  } catch {
    return '--'
  }
}

function formatTimeSince(dateStr: string): string {
  const ms = Date.now() - new Date(dateStr).getTime()
  const mins = Math.floor(ms / 60_000)
  if (mins < 1) return 'now'
  if (mins < 60) return `${mins}m`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h`
  const days = Math.floor(hrs / 24)
  return `${days}d`
}

// ── DBL bundle import ────────────────────────────────────────────────────────
const dropzoneRef = ref<HTMLElement>()
const bundleInputRef = ref<HTMLInputElement>()
const isDragging = ref(false)
const isImporting = ref(false)

function onDragEnter(e: DragEvent) {
  e.preventDefault()
  isDragging.value = true
}
function onDragOver(e: DragEvent) {
  e.preventDefault()
}
function onDragLeave(e: DragEvent) {
  if (dropzoneRef.value && !dropzoneRef.value.contains(e.relatedTarget as Node)) {
    isDragging.value = false
  }
}
function openBundlePicker() {
  if (!isImporting.value) bundleInputRef.value?.click()
}

async function uploadBundle(file: File) {
  if (isImporting.value) return
  if (!isDblBundle(file)) {
    toast.error('Please select a DBL ZIP bundle.')
    return
  }

  isImporting.value = true
  const progress = toast.showProgress(`Uploading ${file.name}…`)
  try {
    await importDblBundle(file, await auth.getAuthHeaders())
    progress.complete('DBL bundle uploaded and queued for processing')
    await refresh()
  } catch (err: unknown) {
    progress.dismiss()
    const message = err instanceof Error ? err.message : 'DBL bundle import failed'
    toast.error(message)
  } finally {
    isImporting.value = false
  }
}

async function onBundleSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  try {
    if (file) await uploadBundle(file)
  } finally {
    input.value = ''
  }
}

async function onDrop(e: DragEvent) {
  e.preventDefault()
  isDragging.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) {
    await uploadBundle(file)
  }
}

// ── Row actions ──────────────────────────────────────────────────────────────
const deleteTarget = ref<BibleRow | null>(null)
const deleteLoading = ref(false)
const variantsTarget = ref<BibleRow | null>(null)

const reprocessGql = gql`
  mutation ReprocessBible($id: UUID!, $version: Int!) {
    content {
      metadata {
        reprocessBible(metadataId: $id, metadataVersion: $version)
      }
    }
  }
`

const deleteGql = gql`
  mutation DeleteBible($metadataId: UUID!) {
    content { metadata { delete(metadataId: $metadataId) } }
  }
`

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'copy', label: 'Copy Bible ID', icon: 'copy' },
    { id: 'sep1', label: '', separator: true },
    { id: 'metadata', label: 'View metadata', icon: 'list' },
    { id: 'reader', label: 'Open Bible reader', icon: 'eye' },
    { id: 'variants', label: 'Manage variants', icon: 'settings' },
    { id: 'sep2', label: '', separator: true },
    { id: 'reprocess', label: 'Reprocess Bible', icon: 'refresh' },
    { id: 'sep3', label: '', separator: true },
    { id: 'delete', label: 'Delete Bible', icon: 'trash', danger: true },
  ]
}

function openReader(row: BibleRow) {
  router.push({
    path: `/cms/bibles/${row.id}/reader`,
    query: { variant: row.bible.variant },
  })
}

async function onRowAction(id: string, row: BibleRow) {
  if (id === 'copy') {
    navigator.clipboard.writeText(row.id)
    toast.success('Bible ID copied to clipboard')
  } else if (id === 'metadata') {
    router.push(`/cms/metadata/${row.id}`)
  } else if (id === 'reader') {
    openReader(row)
  } else if (id === 'variants') {
    variantsTarget.value = row
  } else if (id === 'reprocess') {
    try {
      toast.info('Reprocessing Bible…')
      await gqlMutation(reprocessGql, { id: row.id, version: row.version })
      toast.success('Bible reprocessed')
      await refresh()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : 'Reprocess failed')
    }
  } else if (id === 'delete') {
    deleteTarget.value = row
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { metadataId: deleteTarget.value.id })
    deleteTarget.value = null
    await refresh()
  } catch (e) {
    console.error('Failed to delete bible', e)
  } finally {
    deleteLoading.value = false
  }
}

// ── Table columns ────────────────────────────────────────────────────────────
const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'language', label: 'Language', width: '1fr' },
  { key: 'variant', label: 'Variant', width: '150px' },
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'visibility', label: 'Visibility', width: '120px' },
  { key: 'published', label: 'Published', width: '120px', muted: true },
  { key: 'modified', label: 'Modified', width: '80px', muted: true },
]

async function onBibleCreated(id: string) {
  showAddBible.value = false
  await refresh()
  router.push(`/cms/metadata/${id}`)
}

async function onVariantsUpdated() {
  await refresh()
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        title="Bibles"
        :subtitle="`${totalItems.toLocaleString()} Bible item${totalItems !== 1 ? 's' : ''}`"
        :breadcrumb="buildBreadcrumb('CMS', 'Bibles')"
        :accent="accent"
      >
        <template #actions>
          <Select
            v-model="selectedLanguage"
            :options="languageOptions"
            placeholder="All Languages"
            searchable
            size="sm"
            icon="globe"
          />
          <Button
            icon="upload"
            size="sm"
            :accent="accent"
            :disabled="isImporting"
            @click="openBundlePicker">
            {{ isImporting ? 'Importing…' : 'Import DBL Bundle' }}
          </Button>
          <input
            ref="bundleInputRef"
            class="bundle-file-input"
            type="file"
            :accept="DBL_BUNDLE_ACCEPT"
            @change="onBundleSelected"
          >
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showAddBible = true">Add Translation</Button>
        </template>
      </PageHeader>
    </template>

    <div
      ref="dropzoneRef"
      class="page-content"
      @dragenter="onDragEnter"
      @dragover="onDragOver"
      @dragleave="onDragLeave"
      @drop="onDrop"
    >
      <!-- Drop overlay -->
      <div v-if="isDragging" class="drop-overlay">
        <div class="drop-prompt">
          <Icon name="inbox" :size="36" color="var(--brand-accent)" />
          <span class="drop-label">Drop a DBL ZIP bundle here</span>
        </div>
      </div>

      <div class="result-bar">
        <span class="result-count">
          {{ items.length.toLocaleString() }} variant{{ items.length !== 1 ? 's' : '' }} shown from
          {{ metadataItems.length.toLocaleString() }} collection item{{ metadataItems.length !== 1 ? 's' : '' }} on this page
        </span>
      </div>

      <!-- Table -->
      <SectionCard title="Bible Variants" glass>
        <template #right>
          <span v-if="totalPages > 0" class="mono page-info">
            page {{ currentPage }} of {{ totalPages.toLocaleString() }}
          </span>
        </template>

        <GlassTable
          :columns="columns"
          :rows="items"
          :loading="isLoading && items.length === 0"
          row-key="rowKey"
          :arrow="false"
          empty-text="No Bible variants found in the Bibles collection."
          @row-click="(row: BibleRow) => openReader(row)"
        >
          <template #col-name="{ row }">
            <div class="name-cell">
              <span
                class="type-icon"
                :style="{
                  background: `linear-gradient(135deg, ${accent} 0%, color-mix(in oklch, ${accent} 50%, #000) 100%)`,
                }"
              >
                <Icon name="book" :size="14" color="#fff" />
              </span>
              <div class="name-text">
                <div class="item-name">{{ getBibleDisplayName(row as BibleRow) }}</div>
                <div v-if="getBibleSubName(row as BibleRow)" class="item-sub">{{ getBibleSubName(row as BibleRow) }}</div>
              </div>
            </div>
          </template>
          <template #col-language="{ row }">
            <div class="lang-cell">
              <div class="lang-primary">{{ getBibleLanguage(row as BibleRow) }}</div>
              <div v-if="getBibleLanguageSub(row as BibleRow)" class="lang-sub">{{ getBibleLanguageSub(row as BibleRow) }}</div>
            </div>
          </template>
          <template #col-variant="{ row }">
            <div class="variant-cell">
              <span class="mono">{{ (row as BibleRow).bible.abbreviationLocal || (row as BibleRow).bible.abbreviation || (row as BibleRow).bible.variant }}</span>
              <span class="variant-key mono">{{ (row as BibleRow).bible.variant }}</span>
              <Badge v-if="(row as BibleRow).bible.defaultVariant" color="#34d99a">Default</Badge>
            </div>
          </template>
          <template #col-status="{ row }">
            <Badge :color="getStatus(row as BibleRow)[1]">
              {{ getStatus(row as BibleRow)[0] }}
            </Badge>
          </template>
          <template #col-visibility="{ row }">
            <Badge :color="getVisibility(row as BibleRow).color">
              {{ getVisibility(row as BibleRow).label }}
            </Badge>
          </template>
          <template #col-published="{ row }">
            {{ formatPublishDate(row as BibleRow) }}
          </template>
          <template #col-modified="{ row }">
            <span class="mod-value">{{ formatTimeSince(row.modified as string) }}</span>
          </template>
          <template #actions="{ row }">
            <OverflowMenu
              :items="getRowActions()"
              @select="(id: string) => onRowAction(id, row as BibleRow)"
            >
              <template #default="{ toggle }">
                <button class="action-btn" @click.stop="toggle">
                  <Icon name="more" :size="16" color="var(--fg-3)" />
                </button>
              </template>
            </OverflowMenu>
          </template>
        </GlassTable>

        <Pagination
          v-if="totalPages > 1"
          :page="currentPage"
          :total-pages="totalPages"
          @prev="goToPage(currentPage - 1)"
          @next="goToPage(currentPage + 1)"
        />
      </SectionCard>
    </div>
  </PageShell>

  <!-- Add bible modal -->
  <AddBibleModal
    v-if="showAddBible"
    :accent="accent"
    :language-tag="selectedLanguage"
    @close="showAddBible = false"
    @created="onBibleCreated"
  />

  <BibleVariantsModal
    v-if="variantsTarget"
    :metadata-id="variantsTarget.id"
    :metadata-version="variantsTarget.version"
    :accent="accent"
    @close="variantsTarget = null"
    @updated="onVariantsUpdated"
  />

  <!-- Delete confirmation -->
  <ConfirmModal
    v-if="deleteTarget"
    :title="`Delete '${getBibleDisplayName(deleteTarget)}'?`"
    subtitle="This will soft-delete the bible. It can be restored later."
    :loading="deleteLoading"
    @close="deleteTarget = null"
    @confirm="confirmDelete"
  />
</template>

<style scoped>
.page-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  position: relative;
}

.bundle-file-input {
  display: none;
}

/* ── Drop overlay ─────────────────────────────────────────────────────────── */
.drop-overlay {
  position: absolute;
  inset: 0;
  z-index: 50;
  background: color-mix(in oklch, var(--brand-accent) 8%, rgba(0, 0, 0, 0.4));
  border-radius: var(--r-lg);
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.drop-prompt {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 32px 48px;
  background: var(--bg-1);
  border: 2px dashed var(--brand-accent);
  border-radius: var(--r-lg);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.35);
}

.drop-label {
  font-size: 16px;
  font-weight: 600;
  color: var(--fg-1);
}

/* ── Result summary ───────────────────────────────────────────────────────── */
.result-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
}

.result-count {
  font-size: 12px;
  color: var(--fg-4);
  white-space: nowrap;
}

.page-info {
  font-size: 11px;
  color: var(--fg-3);
}

/* ── Name cell ────────────────────────────────────────────────────────────── */
.name-cell {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.type-icon {
  width: 28px;
  height: 34px;
  border-radius: 3px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 3px 10px -4px rgba(0, 0, 0, 0.4);
}

.name-text {
  min-width: 0;
}

.item-name {
  font-weight: 500;
  color: var(--fg-0);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.item-sub {
  font-size: 12px;
  color: var(--fg-3);
  margin-top: 1px;
}

/* ── Language cell ────────────────────────────────────────────────────────── */
.lang-cell {
  min-width: 0;
}

.lang-primary {
  font-size: 13px;
  color: var(--fg-1);
}

.lang-sub {
  font-size: 12px;
  color: var(--fg-3);
  margin-top: 1px;
}

.variant-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.variant-key {
  color: var(--fg-4);
  font-size: 11px;
}

.mod-value {
  font-size: 12px;
  color: var(--fg-2);
}

/* ── Action button ────────────────────────────────────────────────────────── */
.action-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-sm);
  background: none;
  border: none;
  cursor: pointer;
}

.action-btn:hover {
  background: var(--bg-3);
}
</style>
