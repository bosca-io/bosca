<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()

const projectId = computed(() => route.params.projectId as string)
const stringId = computed(() => route.params.stringId as string)

const activeLanguage = ref('')
const editingText = ref('')
const saving = ref(false)
const saveError = ref('')
const saveSuccess = ref(false)

const stringGql = gql`
  query GetLocalizationString($projectId: UUID!, $stringId: UUID!) {
    localization {
      project(id: $projectId) {
        id
        name
        sourceLanguage
        languages {
          languageTag
        }
      }
      string(id: $stringId) {
        id
        key
        context
        plural
        tags
        placeholders
        metadataId
        modified
        contextMetadata {
          metadataId
          stringId
          created
        }
        translations {
          id
          languageTag
          text
          state
          origin
          originDetail
          reviewedAt
          modified
          history {
            id
            fromState
            toState
            newText
            created
            origin
          }
        }
      }
    }
  }
`

interface TranslationHistoryEntry {
  id: string
  fromState: string | null
  toState: string
  newText: string
  created: string
  origin: string
}

interface Translation {
  id: string
  languageTag: string
  text: string
  state: string
  origin: string
  originDetail: string | null
  reviewedAt: string | null
  modified: string
  history: TranslationHistoryEntry[]
}

interface ContextMetadata {
  metadataId: string
  stringId: string
  created: string
}

interface StringDetail {
  id: string
  key: string
  context: string | null
  plural: boolean
  tags: string[]
  placeholders: Array<{ name: string; type?: string; example?: string }> | null
  metadataId: string | null
  modified: string
  contextMetadata: ContextMetadata[]
  translations: Translation[]
}

interface ProjectInfo {
  id: string
  name: string
  sourceLanguage: string
  languages: { languageTag: string }[]
}

const { data, status, refresh } = useAsyncQuery<{
  localization: {
    project: ProjectInfo | null
    string: StringDetail | null
  }
}>('localization-string', stringGql, { projectId, stringId })

const project = computed(() => data.value?.localization?.project ?? null)
const str = computed(() => data.value?.localization?.string ?? null)
const isLoading = computed(() => status.value === 'pending')

const targetLanguages = computed(() => project.value?.languages?.map(l => l.languageTag) ?? [])

watch([str, targetLanguages], () => {
  if (!activeLanguage.value && targetLanguages.value.length > 0) {
    activeLanguage.value = targetLanguages.value[0]!
  }
  syncEditingText()
})

function syncEditingText() {
  const t = currentTranslation.value
  editingText.value = t?.text ?? ''
}

const currentTranslation = computed(() =>
  str.value?.translations.find(t => t.languageTag === activeLanguage.value) ?? null,
)

function selectLanguage(tag: string) {
  activeLanguage.value = tag
  syncEditingText()
  saveSuccess.value = false
  saveError.value = ''
  pluralSuccess.value = false
  pluralError.value = ''
}

// ── Save translation ──────────────────────────

const setTranslationGql = gql`
  mutation SetTranslation($input: LocalizationTranslationInput!) {
    localization {
      setTranslation(input: $input) {
        id
        text
        state
      }
    }
  }
`

async function handleSave() {
  if (!str.value || !activeLanguage.value || saving.value) return
  saving.value = true
  saveError.value = ''
  saveSuccess.value = false
  try {
    await mutation(setTranslationGql, {
      input: {
        stringId: str.value.id,
        languageTag: activeLanguage.value,
        text: editingText.value,
      },
    })
    saveSuccess.value = true
    refresh()
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : 'Failed to save'
  } finally {
    saving.value = false
  }
}

// ── Transition translation state ──────────────

const transitionGql = gql`
  mutation TransitionTranslation($id: UUID!, $toState: TranslationState!) {
    localization {
      transitionTranslation(id: $id, toState: $toState) {
        id
        state
      }
    }
  }
`

async function handleTransition(toState: string) {
  const t = currentTranslation.value
  if (!t || saving.value) return
  saving.value = true
  saveError.value = ''
  try {
    await mutation(transitionGql, { id: t.id, toState })
    refresh()
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : 'Transition failed'
  } finally {
    saving.value = false
  }
}

// ── Plural forms (CLDR category editing) ──────
// A plural string stores one row per CLDR category per language. The categories a language
// actually uses come from the browser's own CLDR (Intl.PluralRules) — Polish edits one/few/many,
// Arabic all six, Korean just other — with example counts so translators see what each covers.

const PLURAL_CATEGORY_ORDER = ['ZERO', 'ONE', 'TWO', 'FEW', 'MANY', 'OTHER'] as const

interface PluralTranslation {
  id: string
  pluralCategory: string
  text: string
  state: string
  origin: string
  originDetail: string | null
  modified: string
}

const pluralFormsGql = gql`
  query GetPluralForms($stringId: UUID!, $tag: String!) {
    localization {
      string(id: $stringId) {
        id
        pluralTranslations(languageTag: $tag) {
          id
          pluralCategory
          text
          state
          origin
          originDetail
          modified
        }
      }
    }
  }
`

const pluralForms = ref<PluralTranslation[]>([])
const sourcePluralForms = ref<PluralTranslation[]>([])
const pluralEdits = ref<Record<string, string>>({})
const pluralSaving = ref(false)
const pluralError = ref('')
const pluralSuccess = ref(false)

const activeCategories = computed<string[]>(() => {
  if (!activeLanguage.value) return []
  let cldr: string[]
  try {
    cldr = new Intl.PluralRules(activeLanguage.value).resolvedOptions().pluralCategories
      .map(c => c.toUpperCase())
  } catch {
    cldr = [...PLURAL_CATEGORY_ORDER]
  }
  // Canonical order; keep any category that already has a stored row visible even if the
  // language's rules don't use it (data must never be invisible).
  return PLURAL_CATEGORY_ORDER.filter(
    c => cldr.includes(c) || pluralForms.value.some(f => f.pluralCategory === c),
  )
})

/** Example counts that select [category] under [tag]'s rules — what this form actually covers. */
function categoryExamples(tag: string, category: string): string {
  try {
    const rules = new Intl.PluralRules(tag)
    const candidates = [...Array(121).keys(), 122, 1000, 1000000, 2000000]
    const matches: number[] = []
    for (const n of candidates) {
      if (rules.select(n).toUpperCase() === category) {
        matches.push(n)
        if (matches.length >= 4) break
      }
    }
    return matches.join(', ')
  } catch {
    return ''
  }
}

function formFor(category: string): PluralTranslation | null {
  return pluralForms.value.find(f => f.pluralCategory === category) ?? null
}

async function fetchPluralForms(tag: string): Promise<PluralTranslation[]> {
  const { query: gqlQuery } = useGraphQL()
  const result = await gqlQuery<{
    localization: { string: { pluralTranslations: PluralTranslation[] } | null }
  }>(pluralFormsGql, { stringId: stringId.value, tag })
  return result.localization.string?.pluralTranslations ?? []
}

async function loadPluralForms() {
  if (!str.value?.plural || !activeLanguage.value) {
    pluralForms.value = []
    sourcePluralForms.value = []
    return
  }
  try {
    pluralForms.value = await fetchPluralForms(activeLanguage.value)
    const source = project.value?.sourceLanguage
    sourcePluralForms.value = source && source !== activeLanguage.value ? await fetchPluralForms(source) : []
    const edits: Record<string, string> = {}
    for (const category of PLURAL_CATEGORY_ORDER) {
      edits[category] = formFor(category)?.text ?? ''
    }
    pluralEdits.value = edits
  } catch (e) {
    pluralError.value = e instanceof Error ? e.message : 'Failed to load plural forms'
  }
}

watch([str, activeLanguage], loadPluralForms, { immediate: true })

const pluralHasChanges = computed(() =>
  activeCategories.value.some(c => (pluralEdits.value[c] ?? '') !== (formFor(c)?.text ?? '')),
)

const setPluralTranslationGql = gql`
  mutation SetPluralTranslation($input: LocalizationPluralTranslationInput!) {
    localization {
      setPluralTranslation(input: $input) {
        id
        text
        state
      }
    }
  }
`

async function handleSavePlurals() {
  if (!str.value || !activeLanguage.value || pluralSaving.value) return
  pluralSaving.value = true
  pluralError.value = ''
  pluralSuccess.value = false
  try {
    for (const category of activeCategories.value) {
      const text = (pluralEdits.value[category] ?? '').trim()
      const existing = formFor(category)
      // Save new non-empty forms and edits to existing ones; an emptied existing form is left
      // alone (removal is a deliberate action, not an accidental clear-and-save).
      if (text.length === 0 || text === existing?.text) continue
      await mutation(setPluralTranslationGql, {
        input: {
          stringId: str.value.id,
          languageTag: activeLanguage.value,
          pluralCategory: category,
          text,
        },
      })
    }
    pluralSuccess.value = true
    await loadPluralForms()
  } catch (e) {
    pluralError.value = e instanceof Error ? e.message : 'Failed to save plural forms'
  } finally {
    pluralSaving.value = false
  }
}

const transitionPluralGql = gql`
  mutation TransitionPluralTranslation($id: UUID!, $toState: TranslationState!) {
    localization {
      transitionPluralTranslation(id: $id, toState: $toState) {
        id
        state
      }
    }
  }
`

async function handlePluralTransition(formId: string, toState: string) {
  if (pluralSaving.value) return
  pluralSaving.value = true
  pluralError.value = ''
  try {
    await mutation(transitionPluralGql, { id: formId, toState })
    await loadPluralForms()
  } catch (e) {
    pluralError.value = e instanceof Error ? e.message : 'Transition failed'
  } finally {
    pluralSaving.value = false
  }
}

// ── State display helpers ─────────────────────

const STATE_COLORS: Record<string, string> = {
  DRAFT: '#5ec5ff',
  AI_GENERATED: '#a78bff',
  IN_REVIEW: '#ffb547',
  APPROVED: '#34d99a',
  PUBLISHED: '#34d99a',
  REJECTED: '#ff5d6c',
  ARCHIVED: '#6c7388',
}

const STATE_LABELS: Record<string, string> = {
  DRAFT: 'Draft',
  AI_GENERATED: 'AI Generated',
  IN_REVIEW: 'In Review',
  APPROVED: 'Approved',
  PUBLISHED: 'Published',
  REJECTED: 'Rejected',
  ARCHIVED: 'Archived',
}

interface WorkflowAction {
  toState: string
  label: string
  color: string
}

function workflowActions(state: string): WorkflowAction[] {
  switch (state) {
    case 'DRAFT':
    case 'AI_GENERATED':
      return [{ toState: 'IN_REVIEW', label: 'Submit for review', color: '#ffb547' }]
    case 'IN_REVIEW':
      return [
        { toState: 'APPROVED', label: 'Approve', color: '#34d99a' },
        { toState: 'REJECTED', label: 'Reject', color: '#ff5d6c' },
      ]
    case 'APPROVED':
      return [{ toState: 'PUBLISHED', label: 'Publish', color: '#34d99a' }]
    case 'PUBLISHED':
      return [{ toState: 'ARCHIVED', label: 'Archive', color: '#6c7388' }]
    case 'REJECTED':
    case 'ARCHIVED':
      return [{ toState: 'DRAFT', label: 'Restore to draft', color: '#5ec5ff' }]
    default:
      return []
  }
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('en-US', {
    month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit',
  })
}

const hasChanges = computed(() => editingText.value !== (currentTranslation.value?.text ?? ''))

// ── Edit string details ─────────────────────

const editingContext = ref('')
const contextDirty = ref(false)
const contextSaving = ref(false)
const contextError = ref('')

watch(str, (s) => {
  if (s && !contextDirty.value) {
    editingContext.value = s.context ?? ''
  }
})

function onContextInput() {
  contextDirty.value = true
}

const editStringGql = gql`
  mutation EditString($id: UUID!, $input: LocalizationStringInput!) {
    localization {
      editString(id: $id, input: $input) {
        id
        context
      }
    }
  }
`

async function handleSaveContext() {
  if (!str.value || contextSaving.value) return
  contextSaving.value = true
  contextError.value = ''
  try {
    await mutation(editStringGql, {
      id: str.value.id,
      input: {
        projectId: projectId.value,
        key: str.value.key,
        context: editingContext.value.trim() || null,
      },
    })
    contextDirty.value = false
    refresh()
  } catch (e) {
    contextError.value = e instanceof Error ? e.message : 'Failed to save'
  } finally {
    contextSaving.value = false
  }
}

// ── Context metadata ────────────────────────

const metadataSaving = ref(false)
const metadataError = ref('')

interface MetadataInfo {
  id: string
  name: string
  contentType: string
  thumbnailUrl: string | null
}

const attachedMetadata = ref<MetadataInfo[]>([])

const metadataInfoGql = gql`
  query GetMetadataInfo($id: UUID!) {
    content {
      metadata(id: $id) {
        id
        name
        content {
          type
          urls {
            download {
              url
            }
          }
        }
        supplementary {
          key
          name
          content {
            type
            urls {
              download {
                url
              }
            }
          }
        }
        relationships {
          relationship
          metadata {
            id
            name
            content {
              type
              urls {
                download {
                  url
                }
              }
            }
          }
        }
      }
    }
  }
`

interface SupplementaryItem {
  key: string
  name: string
  content: { type: string; urls: { download: { url: string } | null } | null }
}

interface RelationshipItem {
  relationship: string
  metadata: {
    id: string
    name: string
    content: { type: string; urls: { download: { url: string } | null } | null } | null
  }
}

function findThumbnailUrl(
  content: { type: string; urls: { download: { url: string } | null } | null } | null,
  supplementary: SupplementaryItem[],
  relationships: RelationshipItem[],
): string | null {
  if (content?.type?.startsWith('image/')) {
    return content.urls?.download?.url ?? null
  }

  const imageRelKeys = ['thumbnail', 'image.featured', 'image', 'preview', 'cover']
  const imageRel = relationships.find(
    r => imageRelKeys.includes(r.relationship) && r.metadata.content?.type?.startsWith('image/'),
  )
  if (imageRel) {
    return imageRel.metadata.content?.urls?.download?.url ?? null
  }

  const anyImageRel = relationships.find(
    r => r.metadata.content?.type?.startsWith('image/'),
  )
  if (anyImageRel) {
    return anyImageRel.metadata.content?.urls?.download?.url ?? null
  }

  const thumbSup = supplementary.find(
    s => (s.key === 'thumbnail' || s.key === 'preview') && s.content.type.startsWith('image/'),
  )
  if (thumbSup) {
    return thumbSup.content.urls?.download?.url ?? null
  }

  const anySup = supplementary.find(s => s.content.type.startsWith('image/'))
  if (anySup) {
    return anySup.content.urls?.download?.url ?? null
  }

  return null
}

watch(str, async (s) => {
  if (!s || s.contextMetadata.length === 0) {
    attachedMetadata.value = []
    return
  }
  const { query: gqlQuery } = useGraphQL()
  const results = await Promise.all(
    s.contextMetadata.map(async (cm): Promise<MetadataInfo> => {
      try {
        const result = await gqlQuery<{
          content: {
            metadata: {
              id: string
              name: string
              content: { type: string; urls: { download: { url: string } | null } | null } | null
              supplementary: SupplementaryItem[]
              relationships: RelationshipItem[]
            }
          }
        }>(metadataInfoGql, { id: cm.metadataId })
        const m = result.content.metadata
        const contentType = m.content?.type ?? ''
        return {
          id: m.id,
          name: m.name,
          contentType,
          thumbnailUrl: findThumbnailUrl(m.content, m.supplementary, m.relationships),
        }
      } catch {
        return { id: cm.metadataId, name: cm.metadataId, contentType: 'unknown', thumbnailUrl: null }
      }
    }),
  )
  attachedMetadata.value = results
}, { immediate: true })

// ── Search for metadata to attach ───────────

const searchQuery = ref('')
const searchResults = ref<MetadataInfo[]>([])
const searching = ref(false)
const showSearch = ref(false)

const searchGql = gql`
  query SearchMetadata($query: String!, $limit: Int!) {
    search {
      search(query: {
        query: $query
        storageSystemName: "Admin Search Index"
        limit: $limit
      }) {
        documents {
          metadata {
            id
            name
            content {
              type
              urls {
                download {
                  url
                }
              }
            }
            supplementary {
              key
              name
              content {
                type
                urls {
                  download {
                    url
                  }
                }
              }
            }
            relationships {
              relationship
              metadata {
                id
                name
                content {
                  type
                  urls {
                    download {
                      url
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
`

let searchTimeout: ReturnType<typeof setTimeout> | null = null

function onSearchInput() {
  if (searchTimeout) clearTimeout(searchTimeout)
  if (!searchQuery.value.trim()) {
    searchResults.value = []
    return
  }
  searchTimeout = setTimeout(runSearch, 300)
}

async function runSearch() {
  if (!searchQuery.value.trim()) return
  searching.value = true
  try {
    const { query: gqlQuery } = useGraphQL()
    const result = await gqlQuery<{
      search: {
        search: {
          documents: Array<{
            metadata: {
              id: string
              name: string
              content: { type: string; urls: { download: { url: string } | null } | null } | null
              supplementary: SupplementaryItem[]
              relationships: RelationshipItem[]
            } | null
          }>
        }
      }
    }>(searchGql, { query: searchQuery.value, limit: 8 })

    const alreadyAttached = new Set(str.value?.contextMetadata.map(cm => cm.metadataId) ?? [])
    searchResults.value = result.search.search.documents
      .map(d => d.metadata)
      .filter((m): m is NonNullable<typeof m> => m != null && !alreadyAttached.has(m.id))
      .map(m => ({
        id: m.id,
        name: m.name,
        contentType: m.content?.type ?? '',
        thumbnailUrl: findThumbnailUrl(m.content, m.supplementary, m.relationships),
      }))
  } catch {
    searchResults.value = []
  } finally {
    searching.value = false
  }
}

const addMetadataGql = gql`
  mutation AddStringMetadata($stringId: UUID!, $metadataId: UUID!) {
    localization {
      addStringMetadata(stringId: $stringId, metadataId: $metadataId) {
        metadataId
      }
    }
  }
`

const removeMetadataGql = gql`
  mutation RemoveStringMetadata($stringId: UUID!, $metadataId: UUID!) {
    localization {
      removeStringMetadata(stringId: $stringId, metadataId: $metadataId)
    }
  }
`

async function handleAttach(metadataId: string) {
  if (!str.value || metadataSaving.value) return
  metadataSaving.value = true
  metadataError.value = ''
  try {
    await mutation(addMetadataGql, { stringId: str.value.id, metadataId })
    searchResults.value = searchResults.value.filter(r => r.id !== metadataId)
    refresh()
  } catch (e) {
    metadataError.value = e instanceof Error ? e.message : 'Failed to attach'
  } finally {
    metadataSaving.value = false
  }
}

async function handleRemoveMetadata(metadataId: string) {
  if (!str.value || metadataSaving.value) return
  metadataSaving.value = true
  metadataError.value = ''
  try {
    await mutation(removeMetadataGql, { stringId: str.value.id, metadataId })
    refresh()
  } catch (e) {
    metadataError.value = e instanceof Error ? e.message : 'Failed to remove'
  } finally {
    metadataSaving.value = false
  }
}

// ── Drag & drop screenshots as context media ─

const uploadMetadataGql = gql`
  mutation AddContextMediaUpload($metadata: MetadataInput!) {
    content {
      metadata {
        add(metadata: $metadata, setReady: true) {
          id
          content {
            urls {
              upload {
                url
                headers { name value }
              }
            }
          }
        }
      }
    }
  }
`

const dragActive = ref(false)
const uploadingFiles = ref(0)
// dragenter/dragleave fire for every element the cursor crosses; only depth 0 means the
// drag actually left the page.
let dragDepth = 0

function isFileDrag(e: DragEvent): boolean {
  return Array.from(e.dataTransfer?.types ?? []).includes('Files')
}

function onDocDragEnter(e: DragEvent) {
  if (!isFileDrag(e) || !str.value) return
  e.preventDefault()
  dragDepth++
  dragActive.value = true
}

function onDocDragOver(e: DragEvent) {
  if (!isFileDrag(e)) return
  // Without preventDefault the browser refuses the drop and navigates to the file instead.
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}

function onDocDragLeave(e: DragEvent) {
  if (!isFileDrag(e)) return
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) dragActive.value = false
}

async function onDocDrop(e: DragEvent) {
  if (!isFileDrag(e)) return
  e.preventDefault()
  dragDepth = 0
  dragActive.value = false
  const target = str.value
  if (!target) return
  const images = Array.from(e.dataTransfer?.files ?? []).filter(f => f.type.startsWith('image/'))
  if (images.length === 0) {
    metadataError.value = 'Only image files can be dropped here.'
    return
  }
  metadataError.value = ''
  uploadingFiles.value = images.length
  try {
    for (const file of images) {
      await uploadContextImage(target.id, file)
      uploadingFiles.value--
    }
  } catch (err) {
    metadataError.value = err instanceof Error ? err.message : 'Upload failed'
  } finally {
    uploadingFiles.value = 0
    refresh()
  }
}

async function uploadContextImage(stringId: string, file: File) {
  const result = await mutation<{
    content: {
      metadata: {
        add: {
          id: string
          content: {
            urls: {
              upload: { url: string; headers: Array<{ name: string; value: string }> } | null
            } | null
          } | null
        }
      }
    }
  }>(uploadMetadataGql, {
    metadata: {
      name: file.name,
      languageTag: project.value?.sourceLanguage ?? 'en',
      contentType: file.type,
      contentLength: file.size,
    },
  })

  const metadata = result.content.metadata.add
  const upload = metadata.content?.urls?.upload
  if (!upload) throw new Error(`No upload URL returned for ${file.name}`)

  const headers = new Headers()
  for (const hdr of upload.headers) headers.append(hdr.name, hdr.value)
  const formData = new FormData()
  formData.append('file', file)
  const response = await fetch(upload.url, { method: 'POST', body: formData, headers })
  if (!response.ok) throw new Error(`Upload failed for ${file.name} (${response.status})`)

  await mutation(addMetadataGql, { stringId, metadataId: metadata.id })
}

// ── Image preview lightbox ─

const previewMedia = ref<MetadataInfo | null>(null)

function handleMediaClick(m: MetadataInfo) {
  if (m.thumbnailUrl) {
    previewMedia.value = m
  } else {
    // Nothing to preview (no image content or related image) — go to the item itself.
    navigateTo(`/cms/metadata/${m.id}`)
  }
}

function onDocKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && previewMedia.value) previewMedia.value = null
}

onMounted(() => {
  document.addEventListener('dragenter', onDocDragEnter)
  document.addEventListener('dragover', onDocDragOver)
  document.addEventListener('dragleave', onDocDragLeave)
  document.addEventListener('drop', onDocDrop)
  document.addEventListener('keydown', onDocKeydown)
})

onUnmounted(() => {
  document.removeEventListener('dragenter', onDocDragEnter)
  document.removeEventListener('dragover', onDocDragOver)
  document.removeEventListener('dragleave', onDocDragLeave)
  document.removeEventListener('drop', onDocDrop)
  document.removeEventListener('keydown', onDocKeydown)
})

function contentIcon(type: string): string {
  if (type.startsWith('image/')) return 'image'
  if (type.startsWith('video/')) return 'video'
  if (type.startsWith('audio/')) return 'audio'
  return 'file'
}

// ── Delete string ───────────────────────────

const confirmDelete = ref(false)
const deleting = ref(false)

const deleteStringGql = gql`
  mutation DeleteLocalizationString($id: UUID!) {
    localization {
      deleteString(id: $id)
    }
  }
`

async function handleDelete() {
  if (!str.value || deleting.value) return
  deleting.value = true
  try {
    await mutation(deleteStringGql, { id: str.value.id })
    navigateTo(`/localization/${projectId.value}`)
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : 'Failed to delete'
    confirmDelete.value = false
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Localization', { label: project?.name ?? '…', to: `/localization/${projectId}` }, str?.key ?? '…')"
        :title="str?.key ?? 'Loading…'"
        :subtitle="str ? `${str.translations.length} translation${str.translations.length !== 1 ? 's' : ''}` : ''"
      >
        <template #actions>
          <Button icon="trash" size="sm" @click="confirmDelete = true">Delete</Button>
        </template>
      </PageHeader>
    </template>

    <ConfirmModal
      v-if="confirmDelete"
      title="Delete string"
      subtitle="This will delete all translations for this string."
      confirm-label="Delete string"
      :loading="deleting"
      @close="confirmDelete = false"
      @confirm="handleDelete"
    >
      <p class="confirm-text">
        Are you sure you want to delete <strong class="mono">{{ str?.key }}</strong>? This cannot be undone.
      </p>
    </ConfirmModal>

    <!-- pointer-events: none — the overlay must never intercept the drag it announces,
         or its own appearance under the cursor would fire a spurious dragleave/drop target. -->
    <div v-if="dragActive" class="drop-overlay">
      <div class="drop-overlay-card">
        <Icon name="image" :size="28" :color="accent" />
        <div>
          <div class="drop-overlay-title">Drop screenshots to attach</div>
          <div class="drop-overlay-sub">Images are uploaded and added as context media for this string</div>
        </div>
      </div>
    </div>

    <div v-if="previewMedia" class="media-lightbox" @click="previewMedia = null">
      <img
        v-if="previewMedia.thumbnailUrl"
        :src="previewMedia.thumbnailUrl"
        :alt="previewMedia.name"
        class="media-lightbox-img"
        @click.stop
      >
      <div class="media-lightbox-caption" @click.stop>
        <span class="media-lightbox-name">{{ previewMedia.name }}</span>
        <NuxtLink :to="`/cms/metadata/${previewMedia.id}`" class="media-lightbox-link">Open in CMS</NuxtLink>
        <button class="media-lightbox-close" @click="previewMedia = null">
          <Icon name="x" :size="14" color="var(--fg-2)" />
        </button>
      </div>
    </div>

    <div v-if="isLoading && !str" class="loading-state">Loading…</div>

    <template v-else-if="str">
      <!-- String details -->
      <SectionCard title="String details" glass>
        <div class="detail-grid">
          <div class="detail-item">
            <div class="detail-label">Key</div>
            <div class="mono detail-value">{{ str.key }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">Source language</div>
            <div class="detail-value">{{ project?.sourceLanguage ?? '—' }}</div>
          </div>
          <div v-if="str.plural" class="detail-item">
            <div class="detail-label">Plural</div>
            <Badge color="var(--brand-2)">Yes</Badge>
          </div>
          <div v-if="str.tags.length > 0" class="detail-item">
            <div class="detail-label">Tags</div>
            <div class="tags-row">
              <span v-for="tag in str.tags" :key="tag" class="tag-chip">{{ tag }}</span>
            </div>
          </div>
          <div v-if="str.placeholders && str.placeholders.length > 0" class="detail-item full">
            <div class="detail-label">Placeholders</div>
            <div class="placeholders-row">
              <span v-for="ph in str.placeholders" :key="ph.name" class="placeholder-chip">
                <span class="mono">{{ '{' + ph.name + '}' }}</span>
                <span v-if="ph.type" class="ph-type">{{ ph.type }}</span>
              </span>
            </div>
          </div>
          <div class="detail-item full">
            <div class="detail-label">Context</div>
            <textarea
              v-model="editingContext"
              rows="2"
              class="context-input"
              placeholder="Describe the intent of this string for translators…"
              @input="onContextInput"
            />
            <div class="context-save-row">
              <div v-if="contextError" class="save-error">{{ contextError }}</div>
              <span class="spacer" />
              <Button
                v-if="contextDirty"
                primary
                size="sm"
                :accent="accent"
                :disabled="contextSaving"
                @click="handleSaveContext"
              >{{ contextSaving ? 'Saving…' : 'Save context' }}</Button>
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- Context media -->
      <SectionCard title="Context media" class="metadata-section" glass>
        <template #right>
          <span class="mono updated-label">Visual context for translators</span>
        </template>

        <!-- Attached items -->
        <div v-if="attachedMetadata.length > 0" class="media-grid">
          <div
            v-for="m in attachedMetadata"
            :key="m.id"
            class="media-card"
            @click="handleMediaClick(m)">
            <div v-if="m.thumbnailUrl" class="media-thumb">
              <img :src="m.thumbnailUrl" :alt="m.name" >
            </div>
            <div v-else class="media-thumb media-thumb-placeholder">
              <Icon :name="contentIcon(m.contentType)" :size="24" color="var(--fg-3)" />
            </div>
            <div class="media-info">
              <div class="media-name">{{ m.name }}</div>
              <div class="media-type">{{ m.contentType }}</div>
            </div>
            <button class="media-remove" @click.stop="handleRemoveMetadata(m.id)">
              <Icon name="x" :size="12" color="var(--fg-3)" />
            </button>
          </div>
        </div>
        <div v-else class="metadata-empty">No context media attached. Drop screenshots anywhere on this page to add them.</div>

        <div v-if="uploadingFiles > 0" class="media-uploading">
          Uploading {{ uploadingFiles }} image{{ uploadingFiles === 1 ? '' : 's' }}…
        </div>

        <!-- Search to attach -->
        <div class="media-search-section">
          <div class="media-search-bar">
            <Icon name="search" :size="14" color="var(--fg-3)" />
            <input
              v-model="searchQuery"
              class="media-search-input"
              placeholder="Search content to attach…"
              @input="onSearchInput"
              @focus="showSearch = true"
            >
          </div>

          <div v-if="showSearch && searchQuery.trim() && (searchResults.length > 0 || searching)" class="search-results">
            <div v-if="searching" class="search-loading">Searching…</div>
            <div
              v-for="r in searchResults"
              :key="r.id"
              class="search-result"
              @click="handleAttach(r.id)"
            >
              <div v-if="r.thumbnailUrl" class="result-thumb">
                <img :src="r.thumbnailUrl" :alt="r.name" >
              </div>
              <div v-else class="result-thumb result-thumb-placeholder">
                <Icon :name="contentIcon(r.contentType)" :size="16" color="var(--fg-3)" />
              </div>
              <div class="result-info">
                <div class="result-name">{{ r.name }}</div>
                <div class="result-type">{{ r.contentType }}</div>
              </div>
              <Icon name="plus" :size="14" :color="accent" />
            </div>
          </div>
        </div>

        <div v-if="metadataError" class="save-error metadata-error">{{ metadataError }}</div>
      </SectionCard>

      <!-- Translation editor -->
      <SectionCard title="Translations" class="translations-section" glass>
        <!-- Language tabs -->
        <div class="lang-tabs">
          <button
            v-for="tag in targetLanguages"
            :key="tag"
            class="lang-tab"
            :class="{ active: tag === activeLanguage }"
            :style="tag === activeLanguage ? {
              borderBottomColor: accent,
              color: 'var(--fg-0)',
            } : {}"
            @click="selectLanguage(tag)"
          >
            {{ tag.toUpperCase() }}
            <span
              v-if="str.translations.find(t => t.languageTag === tag)"
              class="lang-dot"
              :style="{ background: STATE_COLORS[str.translations.find(t => t.languageTag === tag)!.state] ?? 'var(--fg-3)' }"
            />
          </button>
          <div v-if="targetLanguages.length === 0" class="no-languages">No target languages configured.</div>
        </div>

        <!-- Active translation -->
        <div v-if="activeLanguage" class="translation-editor">
          <!-- Plural strings: one form per CLDR category this language uses -->
          <template v-if="str.plural">
            <div
              v-if="sourcePluralForms.length > 0 && activeLanguage !== project?.sourceLanguage"
              class="source-reference"
            >
              <div class="history-title">Source ({{ project?.sourceLanguage }})</div>
              <div v-for="f in sourcePluralForms" :key="f.id" class="source-form">
                <span class="cat-chip">{{ f.pluralCategory }}</span>
                <span class="mono source-text">{{ f.text }}</span>
              </div>
            </div>

            <div v-for="category in activeCategories" :key="category" class="plural-form">
              <div class="plural-form-head">
                <span class="cat-chip" :style="{ borderColor: `color-mix(in oklch, ${accent} 40%, transparent)` }">
                  {{ category }}
                </span>
                <span class="cat-examples">e.g. {{ categoryExamples(activeLanguage, category) }}</span>
                <span class="spacer" />
                <template v-if="formFor(category)">
                  <span
                    class="state-pill"
                    :style="{
                      background: `color-mix(in oklch, ${STATE_COLORS[formFor(category)!.state] ?? 'var(--fg-3)'} 14%, transparent)`,
                      color: STATE_COLORS[formFor(category)!.state] ?? 'var(--fg-3)',
                      border: `1px solid color-mix(in oklch, ${STATE_COLORS[formFor(category)!.state] ?? 'var(--fg-3)'} 28%, transparent)`,
                    }"
                  >{{ STATE_LABELS[formFor(category)!.state] ?? formFor(category)!.state }}</span>
                  <button
                    v-for="action in workflowActions(formFor(category)!.state)"
                    :key="action.toState"
                    class="action-btn"
                    :style="{
                      color: action.color,
                      border: `1px solid color-mix(in oklch, ${action.color} 36%, transparent)`,
                    }"
                    :disabled="pluralSaving"
                    @click="handlePluralTransition(formFor(category)!.id, action.toState)"
                  >{{ action.label }}</button>
                </template>
                <span v-else class="cat-missing">not translated</span>
              </div>
              <textarea
                v-model="pluralEdits[category]"
                rows="2"
                class="translation-textarea plural-textarea"
                :placeholder="`${category} form for ${activeLanguage}… (placeholders may be omitted: the count selects the form)`"
              />
            </div>

            <div class="save-bar">
              <div v-if="pluralError" class="save-error">{{ pluralError }}</div>
              <div v-if="pluralSuccess" class="save-success">Saved</div>
              <span class="spacer" />
              <Button
                primary
                size="sm"
                :accent="accent"
                :disabled="!pluralHasChanges || pluralSaving"
                @click="handleSavePlurals"
              >{{ pluralSaving ? 'Saving…' : 'Save plural forms' }}</Button>
            </div>
          </template>

          <!-- Plain strings: the single-text editor -->
          <template v-else>
            <!-- State bar -->
            <div v-if="currentTranslation" class="state-bar">
              <span
                class="state-pill"
                :style="{
                  background: `color-mix(in oklch, ${STATE_COLORS[currentTranslation.state] ?? 'var(--fg-3)'} 14%, transparent)`,
                  color: STATE_COLORS[currentTranslation.state] ?? 'var(--fg-3)',
                  border: `1px solid color-mix(in oklch, ${STATE_COLORS[currentTranslation.state] ?? 'var(--fg-3)'} 28%, transparent)`,
                }"
              >{{ STATE_LABELS[currentTranslation.state] ?? currentTranslation.state }}</span>

              <span class="state-origin">
                {{ currentTranslation.origin.toLowerCase() }}
                <template v-if="currentTranslation.originDetail"> · {{ currentTranslation.originDetail }}</template>
              </span>

              <span class="spacer" />

              <button
                v-for="action in workflowActions(currentTranslation.state)"
                :key="action.toState"
                class="action-btn"
                :style="{
                  color: action.color,
                  border: `1px solid color-mix(in oklch, ${action.color} 36%, transparent)`,
                }"
                :disabled="saving"
                @click="handleTransition(action.toState)"
              >{{ action.label }}</button>
            </div>

            <!-- Text area -->
            <textarea
              v-model="editingText"
              rows="5"
              class="translation-textarea"
              :placeholder="`Translation for ${activeLanguage}…`"
            />

            <!-- Save bar -->
            <div class="save-bar">
              <div v-if="saveError" class="save-error">{{ saveError }}</div>
              <div v-if="saveSuccess" class="save-success">Saved</div>
              <span class="spacer" />
              <Button
                primary
                size="sm"
                :accent="accent"
                :disabled="!hasChanges || saving"
                @click="handleSave"
              >{{ saving ? 'Saving…' : 'Save translation' }}</Button>
            </div>

            <!-- History -->
            <div v-if="currentTranslation && currentTranslation.history.length > 0" class="history-section">
              <div class="history-title">History</div>
              <div
                v-for="h in currentTranslation.history"
                :key="h.id"
                class="history-entry"
              >
                <div class="history-meta">
                  <span v-if="h.fromState" class="history-transition">
                    {{ STATE_LABELS[h.fromState] ?? h.fromState }} → {{ STATE_LABELS[h.toState] ?? h.toState }}
                  </span>
                  <span v-else class="history-transition">Created as {{ STATE_LABELS[h.toState] ?? h.toState }}</span>
                  <span class="history-date">{{ formatDate(h.created) }}</span>
                </div>
                <div class="mono history-text">{{ h.newText }}</div>
              </div>
            </div>
          </template>
        </div>
      </SectionCard>
    </template>

    <div v-else class="empty-state">String not found.</div>
  </PageShell>
</template>

<style scoped>
.loading-state,
.empty-state {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

/* ── String details ── */
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  padding: 16px 18px;
}

.detail-item.full { grid-column: 1 / -1; }
.detail-label {
  font-size: 10.5px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-weight: 600;
  margin-bottom: 4px;
}

.detail-value {
  font-size: 13px;
  color: var(--fg-0);
}

.tags-row { display: flex; flex-wrap: wrap; gap: 5px; }

.tag-chip {
  display: inline-block;
  font-size: 10.5px;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  color: var(--fg-2);
}

.placeholders-row { display: flex; flex-wrap: wrap; gap: 6px; }

.placeholder-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  color: var(--fg-1);
}

.ph-type { font-size: 10px; color: var(--fg-3); }

.context-input {
  width: 100%;
  padding: 8px 12px;
  font-size: 13px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-2);
  color: var(--fg-0);
  outline: none;
  resize: vertical;
  font-family: inherit;
  line-height: 1.5;
}

.context-input:focus { border-color: var(--brand-2); }

.context-save-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}

/* ── Context media ── */
.metadata-section {
  margin-top: 16px;
  /* The attach-search dropdown must paint OVER the Translations card that follows: glass
     cards create their own stacking contexts, so the dropdown's z-index only competes inside
     this card — the card itself has to outrank its later sibling. */
  position: relative;
  z-index: 30;
}
.updated-label { font-size: 11px; color: var(--fg-3); }

.media-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px;
  padding: 14px 18px;
}

.media-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-2);
  cursor: pointer;
}

.media-card:hover {
  background: var(--bg-3);
}

.media-lightbox {
  position: fixed;
  inset: 0;
  z-index: 300;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 32px;
  background: color-mix(in srgb, black 78%, transparent);
  backdrop-filter: blur(4px);
}

.media-lightbox-img {
  max-width: min(1200px, 92vw);
  max-height: 82vh;
  object-fit: contain;
  border-radius: 8px;
  background: var(--bg-1);
}

.media-lightbox-caption {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 8px 14px;
  border-radius: var(--r-sm);
  background: var(--bg-2);
  font-size: 12px;
}

.media-lightbox-name {
  color: var(--fg-1);
  max-width: 480px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.media-lightbox-link {
  color: var(--brand-2);
  text-decoration: none;
}

.media-lightbox-link:hover {
  text-decoration: underline;
}

.media-lightbox-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 50%;
  background: var(--bg-3);
  cursor: pointer;
}

.media-thumb {
  width: 48px;
  height: 48px;
  border-radius: 4px;
  overflow: hidden;
  flex-shrink: 0;
}

.media-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.media-thumb-placeholder {
  background: var(--bg-3);
  display: flex;
  align-items: center;
  justify-content: center;
}

.media-info { flex: 1; min-width: 0; }

.media-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.media-type { font-size: 10.5px; color: var(--fg-3); }

.media-remove {
  padding: 4px;
  cursor: pointer;
  border-radius: 4px;
  flex-shrink: 0;
}

.media-remove:hover { background: var(--bg-3); }

.metadata-empty {
  padding: 16px 18px;
  font-size: 12px;
  color: var(--fg-3);
}

.media-uploading {
  padding: 8px 18px;
  font-size: 12px;
  color: var(--fg-2);
}

.drop-overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--bg-1) 55%, transparent);
  backdrop-filter: blur(2px);
  pointer-events: none;
}

.drop-overlay-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 20px 28px;
  border: 1.5px dashed var(--brand-2);
  border-radius: 12px;
  background: var(--bg-2);
}

.drop-overlay-title {
  font-size: 14px;
  font-weight: 600;
}

.drop-overlay-sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--fg-3);
}

/* ── Media search ── */
.media-search-section {
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  position: relative;
}

.media-search-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-2);
}

.media-search-bar:focus-within { border-color: var(--brand-2); }

.media-search-input {
  flex: 1;
  font-size: 13px;
  background: transparent;
  border: none;
  color: var(--fg-0);
  outline: none;
}

.media-search-input::placeholder { color: var(--fg-3); }

.search-results {
  position: absolute;
  left: 18px;
  right: 18px;
  top: 100%;
  z-index: 20;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  box-shadow: 0 8px 24px -8px rgba(0, 0, 0, 0.4);
  max-height: 280px;
  overflow-y: auto;
}

.search-loading {
  padding: 12px;
  font-size: 12px;
  color: var(--fg-3);
  text-align: center;
}

.search-result {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  cursor: pointer;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 40%, transparent);
}

.search-result:hover { background: var(--bg-2); }
.search-result:last-child { border-bottom: none; }

.result-thumb {
  width: 32px;
  height: 32px;
  border-radius: 4px;
  overflow: hidden;
  flex-shrink: 0;
}

.result-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.result-thumb-placeholder {
  background: var(--bg-3);
  display: flex;
  align-items: center;
  justify-content: center;
}

.result-info { flex: 1; min-width: 0; }

.result-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-type { font-size: 10px; color: var(--fg-3); }

.metadata-error { padding: 4px 18px; }

.confirm-text { font-size: 13px; color: var(--fg-1); line-height: 1.5; margin: 0; }

/* ── Translation editor ── */
.translations-section { margin-top: 16px; }

.lang-tabs {
  display: flex;
  gap: 2px;
  padding: 0 18px;
  border-bottom: 1px solid var(--line);
  overflow-x: auto;
}

.lang-tab {
  padding: 10px 12px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--fg-3);
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  letter-spacing: 0.04em;
  font-family: var(--font-mono);
  margin-bottom: -1px;
}

.lang-tab.active { font-weight: 700; }

.lang-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.no-languages {
  padding: 10px 0;
  font-size: 12px;
  color: var(--fg-3);
}

.translation-editor { padding: 16px 18px; }

.state-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.state-pill {
  font-size: 10.5px;
  padding: 3px 9px;
  border-radius: 999px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.state-origin {
  font-size: 11px;
  color: var(--fg-3);
}

.spacer { flex: 1; }

.action-btn {
  font-size: 11.5px;
  padding: 4px 10px;
  border-radius: var(--r-sm);
  background: transparent;
  font-weight: 600;
  cursor: pointer;
}

.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.translation-textarea {
  width: 100%;
  padding: 12px 14px;
  font-size: 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-2);
  color: var(--fg-0);
  outline: none;
  resize: vertical;
  font-family: inherit;
  line-height: 1.5;
}

.translation-textarea:focus { border-color: var(--brand-2); }

.save-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

.save-error { font-size: 12px; color: var(--err); }
.save-success { font-size: 12px; color: var(--ok); }

/* ── History ── */
.history-section {
  margin-top: 20px;
  border-top: 1px solid var(--line);
  padding-top: 14px;
}

.history-title {
  font-size: 10.5px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-weight: 600;
  margin-bottom: 10px;
}

.history-entry {
  padding: 8px 0;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 50%, transparent);
}

.history-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}

.history-transition {
  font-size: 11.5px;
  color: var(--fg-2);
  font-weight: 500;
}

.history-date {
  font-size: 10.5px;
  color: var(--fg-3);
}

.history-text {
  font-size: 12px;
  color: var(--fg-1);
  white-space: pre-wrap;
}

/* ── Plural forms ── */
.source-reference {
  margin-bottom: 14px;
  padding: 10px 12px;
  border: 1px solid var(--stroke-1);
  border-radius: 8px;
  background: color-mix(in oklch, var(--bg-2) 60%, transparent);
}

.source-form {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 3px 0;
}

.source-text {
  font-size: 12px;
  color: var(--fg-2);
}

.plural-form {
  margin-bottom: 14px;
}

.plural-form-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.cat-chip {
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: var(--fg-1);
  border: 1px solid var(--stroke-1);
  border-radius: 5px;
  padding: 2px 7px;
}

.cat-examples {
  font-size: 11px;
  color: var(--fg-3);
}

.cat-missing {
  font-size: 11px;
  color: var(--fg-3);
  font-style: italic;
}

.plural-textarea {
  min-height: 0;
}
</style>
