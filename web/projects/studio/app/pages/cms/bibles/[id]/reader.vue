<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { query: gqlQuery } = useGraphQL()
const metadataId = route.params.id as string
const bibleVariant = typeof route.query.variant === 'string' ? route.query.variant : null

// ── Bible metadata ───────────────────────────────────────────────────────────
const bibleGql = gql`
  query GetBibleReader($id: UUID!, $variant: String) {
    content {
      metadata(id: $id) {
        id
        name
        bible(variant: $variant) {
          name
          nameLocal
          abbreviation
          abbreviationLocal
          description
          styles
          languages {
            nameLocal
            name
            iso
            scriptDirection
          }
          books {
            abbreviation
            nameShort
            nameLong
            reference {
              usfm
              human
              humanShort
            }
            chapters {
              reference {
                usfm
                human
                humanShort
              }
            }
          }
        }
      }
    }
  }
`

interface BibleLanguage {
  nameLocal: string
  name: string
  iso: string
  scriptDirection: string
}

interface BibleRef {
  usfm: string
  human: string
  humanShort: string
}

interface BibleChapterRef {
  reference: BibleRef
}

interface BibleBook {
  abbreviation: string
  nameShort: string
  nameLong: string
  reference: BibleRef
  chapters: BibleChapterRef[]
}

interface StyleDef {
  id: string
  reference?: boolean
  fontWeight?: string
  align?: string
  textIndent?: { size?: number; unit?: string } | null
}

interface BibleData {
  name: string
  nameLocal: string
  abbreviation: string
  abbreviationLocal: string
  description: string
  styles: StyleDef[] | Record<string, StyleDef>
  languages: BibleLanguage[]
  books: BibleBook[]
}

const bible = ref<BibleData | null>(null)
const metadataName = ref('')
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    const result = await gqlQuery<{
      content: {
        metadata: {
          id: string
          name: string
          bible: BibleData | null
        }
      }
    }>(bibleGql, { id: metadataId, variant: bibleVariant })
    metadataName.value = result.content.metadata.name
    bible.value = result.content.metadata.bible
    if (!bible.value) {
      error.value = 'No Bible data found for this metadata item.'
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to load Bible'
  } finally {
    loading.value = false
  }
})

const styleMap = computed(() => {
  const map = new Map<string, StyleDef>()
  if (!bible.value?.styles) return map
  const raw = bible.value.styles
  if (Array.isArray(raw)) {
    for (const s of raw) map.set(s.id, s)
  } else if (typeof raw === 'object') {
    for (const [k, v] of Object.entries(raw)) {
      map.set(k, { ...(v as object), id: k } as StyleDef)
    }
  }
  return map
})

const scriptDirection = computed(() => {
  return bible.value?.languages?.[0]?.scriptDirection === 'RTL' ? 'rtl' : 'ltr'
})

const displayName = computed(() => {
  if (!bible.value) return metadataName.value
  return bible.value.nameLocal || bible.value.name || metadataName.value
})

const subtitle = computed(() => {
  if (!bible.value) return ''
  const parts: string[] = []
  if (bible.value.abbreviation) parts.push(bible.value.abbreviation)
  const lang = bible.value.languages?.[0]
  if (lang) parts.push(lang.nameLocal || lang.name)
  parts.push(`${bible.value.books?.length ?? 0} books`)
  return parts.join(' · ')
})

// ── Book & chapter selection ─────────────────────────────────────────────────
const selectedBookUsfm = ref('')
const selectedChapterUsfm = ref('')

const bookOptions = computed<SelectOption[]>(() => {
  if (!bible.value?.books) return []
  return bible.value.books.map(b => ({
    value: b.reference.usfm,
    label: b.nameShort,
  }))
})

const selectedBook = computed<BibleBook | undefined>(() =>
  bible.value?.books?.find(b => b.reference.usfm === selectedBookUsfm.value),
)

const chapterOptions = computed<SelectOption[]>(() => {
  if (!selectedBook.value) return []
  return selectedBook.value.chapters.map((ch, i) => ({
    value: ch.reference.usfm,
    label: `Chapter ${i + 1}`,
  }))
})

watch([bible], () => {
  if (bible.value?.books?.length) {
    selectedBookUsfm.value = bible.value.books[0]!.reference.usfm
  }
})

watch(selectedBookUsfm, () => {
  if (selectedBook.value?.chapters?.length) {
    selectedChapterUsfm.value = selectedBook.value.chapters[0]!.reference.usfm
  }
})

// ── Chapter content ──────────────────────────────────────────────────────────
const chapterGql = gql`
  query GetBibleChapter($id: UUID!, $usfm: String!, $variant: String) {
    content {
      metadata(id: $id) {
        bible(variant: $variant) {
          chapter(usfm: $usfm) {
            reference {
              usfm
              human
              humanShort
            }
            component
            verses {
              usfm
              human
              humanShort
            }
          }
        }
      }
    }
  }
`

interface ComponentNode {
  _: string
  type?: string
  components?: ComponentNode[]
  style?: { _: 'sr'; id: string; align?: string; textIndent?: { size?: number; unit?: string } | null } | null
  text?: string
  reference?: { usfm: string; human?: string; humanShort?: string }
}

const chapterData = ref<{
  reference: BibleRef
  component: ComponentNode
  verses: BibleRef[]
} | null>(null)
const chapterLoading = ref(false)

const chapterComponents = computed<ComponentNode[]>(() => {
  const comp = chapterData.value?.component
  if (!comp) return []
  if (comp._ === 'cc' && comp.components) return comp.components
  return [comp]
})

watch(selectedChapterUsfm, async (usfm) => {
  if (!usfm) {
    chapterData.value = null
    return
  }
  chapterLoading.value = true
  try {
    const result = await gqlQuery<{
      content: {
        metadata: {
          bible: {
            chapter: {
              reference: BibleRef
              component: ComponentNode
              verses: BibleRef[]
            } | null
          } | null
        }
      }
    }>(chapterGql, { id: metadataId, usfm, variant: bibleVariant })
    chapterData.value = result.content.metadata.bible?.chapter ?? null
  } catch (e: unknown) {
    console.error('Failed to load chapter', e)
    chapterData.value = null
  } finally {
    chapterLoading.value = false
  }
})

// ── Reference search ─────────────────────────────────────────────────────────
const searchRef = ref('')

const findGql = gql`
  query FindBibleRef($id: UUID!, $human: String!, $variant: String) {
    content {
      metadata(id: $id) {
        bible(variant: $variant) {
          find(human: $human) {
            reference {
              usfm
              human
              humanShort
            }
            book {
              abbreviation
              nameShort
              reference {
                usfm
              }
            }
            chapter {
              reference {
                usfm
                human
              }
            }
            component
          }
        }
      }
    }
  }
`

async function onSearchSubmit() {
  const q = searchRef.value.trim()
  if (!q) return
  try {
    const result = await gqlQuery<{
      content: {
        metadata: {
          bible: {
            find: Array<{
              reference: BibleRef
              book: { abbreviation: string; nameShort: string; reference: { usfm: string } }
              chapter: { reference: { usfm: string; human: string } } | null
              component: ComponentNode | null
            }>
          } | null
        }
      }
    }>(findGql, { id: metadataId, human: q, variant: bibleVariant })
    const results = result.content.metadata.bible?.find
    if (results?.length) {
      const first = results[0]!
      selectedBookUsfm.value = first.book.reference.usfm
      if (first.chapter) {
        selectedChapterUsfm.value = first.chapter.reference.usfm
      }
    }
  } catch (e: unknown) {
    console.error('Find failed', e)
  }
}

// ── Navigation ───────────────────────────────────────────────────────────────
function prevChapter() {
  if (!selectedBook.value) return
  const chapters = selectedBook.value.chapters
  const idx = chapters.findIndex(c => c.reference.usfm === selectedChapterUsfm.value)
  if (idx > 0) {
    selectedChapterUsfm.value = chapters[idx - 1]!.reference.usfm
  } else {
    const books = bible.value?.books ?? []
    const bookIdx = books.findIndex(b => b.reference.usfm === selectedBookUsfm.value)
    if (bookIdx > 0) {
      const prevBook = books[bookIdx - 1]!
      selectedBookUsfm.value = prevBook.reference.usfm
      nextTick(() => {
        if (prevBook.chapters.length) {
          selectedChapterUsfm.value = prevBook.chapters[prevBook.chapters.length - 1]!.reference.usfm
        }
      })
    }
  }
}

function nextChapter() {
  if (!selectedBook.value) return
  const chapters = selectedBook.value.chapters
  const idx = chapters.findIndex(c => c.reference.usfm === selectedChapterUsfm.value)
  if (idx < chapters.length - 1) {
    selectedChapterUsfm.value = chapters[idx + 1]!.reference.usfm
  } else {
    const books = bible.value?.books ?? []
    const bookIdx = books.findIndex(b => b.reference.usfm === selectedBookUsfm.value)
    if (bookIdx < books.length - 1) {
      const nBook = books[bookIdx + 1]!
      selectedBookUsfm.value = nBook.reference.usfm
    }
  }
}

const hasPrev = computed(() => {
  if (!selectedBook.value) return false
  const chapters = selectedBook.value.chapters
  const idx = chapters.findIndex(c => c.reference.usfm === selectedChapterUsfm.value)
  if (idx > 0) return true
  const books = bible.value?.books ?? []
  return books.findIndex(b => b.reference.usfm === selectedBookUsfm.value) > 0
})

const hasNext = computed(() => {
  if (!selectedBook.value) return false
  const chapters = selectedBook.value.chapters
  const idx = chapters.findIndex(c => c.reference.usfm === selectedChapterUsfm.value)
  if (idx < chapters.length - 1) return true
  const books = bible.value?.books ?? []
  const bookIdx = books.findIndex(b => b.reference.usfm === selectedBookUsfm.value)
  return bookIdx < books.length - 1
})

const chapterHeading = computed(() => {
  if (!chapterData.value) return ''
  return chapterData.value.reference.human
})

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowLeft' && hasPrev.value) {
    e.preventDefault()
    prevChapter()
  } else if (e.key === 'ArrowRight' && hasNext.value) {
    e.preventDefault()
    nextChapter()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :title="displayName"
        :subtitle="subtitle"
        :breadcrumb="buildBreadcrumb('CMS', 'Bibles', { label: displayName, to: `/cms/bibles/${metadataId}` }, 'Reader')"
        :accent="accent"
      />
    </template>

    <!-- Loading -->
    <div v-if="loading" class="state-msg">Loading Bible…</div>

    <!-- Error -->
    <div v-else-if="error" class="state-msg state-err">{{ error }}</div>

    <!-- Reader -->
    <div v-else-if="bible" class="reader-layout">
      <!-- Sidebar: book list -->
      <aside class="book-sidebar">
        <div class="sidebar-header">Books</div>
        <div class="book-list">
          <button
            v-for="book in bible.books"
            :key="book.reference.usfm"
            class="book-item"
            :class="{ active: book.reference.usfm === selectedBookUsfm }"
            :style="book.reference.usfm === selectedBookUsfm ? { color: accent } : {}"
            @click="selectedBookUsfm = book.reference.usfm"
          >
            <span class="mono book-abbr">{{ book.abbreviation }}</span>
            <span class="book-name">{{ book.nameShort }}</span>
          </button>
        </div>
      </aside>

      <!-- Main reading pane -->
      <div class="reading-pane">
        <!-- Controls bar -->
        <div class="controls-bar">
          <Select
            v-model="selectedBookUsfm"
            :options="bookOptions"
            searchable
            size="sm"
            icon="book"
            placeholder="Book"
            :accent="accent"
          />
          <Select
            v-model="selectedChapterUsfm"
            :options="chapterOptions"
            size="sm"
            placeholder="Chapter"
            :accent="accent"
          />
          <form class="ref-search" @submit.prevent="onSearchSubmit">
            <SearchInput
              v-model="searchRef"
              size="sm"
              placeholder="Go to reference… (e.g. John 3:16)"
              max-width="260px"
            />
          </form>
          <span class="controls-spacer" />
          <Button size="sm" :disabled="!hasPrev" @click="prevChapter">
            <Icon name="chevronLeft" :size="14" color="var(--fg-2)" />
          </Button>
          <Button size="sm" :disabled="!hasNext" @click="nextChapter">
            <Icon name="chevron" :size="14" color="var(--fg-2)" />
          </Button>
        </div>

        <!-- Chapter content -->
        <div v-if="chapterLoading" class="state-msg">Loading chapter…</div>
        <div
          v-else-if="chapterData"
          class="scripture-body"
          :dir="scriptDirection"
        >
          <h2 class="chapter-heading" :style="{ color: accent }">{{ chapterHeading }}</h2>
          <ScriptureRenderer
            :nodes="chapterComponents"
            :styles="styleMap"
            :accent="accent"
          />
          <div class="verse-count">
            {{ chapterData.verses.length }} verse{{ chapterData.verses.length !== 1 ? 's' : '' }}
          </div>
        </div>
        <div v-else class="state-msg">Select a book and chapter to begin reading.</div>

        <!-- Bottom nav -->
        <div v-if="chapterData" class="bottom-nav">
          <Button size="sm" :disabled="!hasPrev" @click="prevChapter">
            <Icon name="chevronLeft" :size="12" color="var(--fg-2)" />
            Previous
          </Button>
          <span class="mono bottom-ref">{{ chapterHeading }}</span>
          <Button size="sm" :disabled="!hasNext" @click="nextChapter">
            Next
            <Icon name="chevron" :size="12" color="var(--fg-2)" />
          </Button>
        </div>
      </div>
    </div>
  </PageShell>
</template>

<style scoped>
.reader-layout {
  /* Shared height for the sidebar header and the controls bar so their
     bottom borders form one continuous line across the sidebar seam. */
  --toolbar-h: 48px;
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 0;
  min-height: 0;
  flex: 1;
}

/* ── Sidebar ──────────────────────────────────────────────────────────────── */
.book-sidebar {
  border-right: 1px solid var(--line);
  background: var(--bg-1);
  border-radius: var(--r-md) 0 0 var(--r-md);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.sidebar-header {
  display: flex;
  align-items: center;
  height: var(--toolbar-h);
  padding: 0 14px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-3);
  border-bottom: 1px solid var(--line);
}

.book-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px;
}

.book-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 10px;
  border-radius: var(--r-xs);
  font-size: 12.5px;
  color: var(--fg-2);
  cursor: pointer;
  background: none;
  border: none;
  text-align: left;
  transition: background 0.1s;
}

.book-item:hover {
  background: var(--bg-3);
}

.book-item.active {
  background: var(--bg-3);
  font-weight: 550;
}

.book-abbr {
  font-size: 10.5px;
  color: var(--fg-4);
  width: 32px;
  flex-shrink: 0;
}

.book-item.active .book-abbr {
  color: inherit;
  opacity: 0.7;
}

.book-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── Reading pane ���────────────────────────────────────────────────────────── */
.reading-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.controls-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: var(--toolbar-h);
  padding: 8px 18px;
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}

.ref-search {
  display: contents;
}

.controls-spacer {
  flex: 1;
}

/* ── Scripture body ───────────────────────────────────────────────────────── */
.scripture-body {
  flex: 1;
  overflow-y: auto;
  padding: 28px 40px 40px;
  max-width: 720px;
  font-size: 16px;
  line-height: 1.85;
  color: var(--fg-0);
}

.chapter-heading {
  font-size: 22px;
  font-weight: 700;
  margin: 0 0 18px;
}

.verse-count {
  margin-top: 24px;
  padding-top: 14px;
  border-top: 1px solid color-mix(in oklch, var(--line) 40%, transparent);
  font-size: 12px;
  color: var(--fg-4);
}

/* ── Bottom nav ───────────────────────────────────────────────────────────── */
.bottom-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 18px;
  border-top: 1px solid var(--line);
}

.bottom-ref {
  font-size: 12px;
  color: var(--fg-3);
}

/* ── State messages ───────────────────────────────────────────────────────── */
.state-msg {
  padding: 60px 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 14px;
}

.state-err {
  color: var(--err);
}
</style>
