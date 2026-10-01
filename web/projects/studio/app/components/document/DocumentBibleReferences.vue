<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'

const references = defineModel('references', {
  type: Array as () => string[],
  required: true,
  default: () => []
})
const metadata = defineModel('metadata', {
  type: String,
  required: false,
  default: undefined
})

function removeReference(reference: string) {
  references.value = references.value.filter(r => r !== reference)
}

const { auth } = useAuth()
const { query: gqlQuery } = useGraphQL()
const { accent } = useCurrentSubsystem()
const bibles = ref<Array<{ label: string; value: string }>>([])
// Bound to the search Select purely as a transient selection: each pick is
// appended to `references` and then reset to null so the box returns to its
// placeholder, ready for the next reference.
const searchSelection = ref<string | null>(null)

/**
 * A node in the Bible chapter component tree. The backend serializes a
 * discriminated union via the `_` field (cc = container, t = text,
 * vs = verse start). ScriptureRenderer walks this tree recursively.
 */
interface ComponentNode {
  _: string
  type?: string
  components?: ComponentNode[]
  style?: { _: 'sr'; id: string; align?: string; textIndent?: { size?: number; unit?: string } | null } | null
  text?: string
  reference?: { usfm: string; human?: string; humanShort?: string }
}

interface StyleDef {
  id: string
  reference?: boolean
  fontWeight?: string
  align?: string
  textIndent?: { size?: number; unit?: string } | null
}

interface BibleChapter {
  usfm: string
  human: string
  // Each entry is the root component tree for one matched reference in this chapter.
  content?: ComponentNode[]
}

const chapters = ref<BibleChapter[]>([])
const styleMap = ref<Map<string, StyleDef>>(new Map())

const stylesGql = gql`
  query GetBibleReferenceStyles($id: UUID!) {
    content { metadata(id: $id) { bible { styles } } }
  }
`

// Scripture style definitions live on the Bible metadata, not in the /contents
// REST payload, so they must be loaded separately to render formatting.
async function loadStyles() {
  if (!metadata.value) { styleMap.value = new Map(); return }
  try {
    const result = await gqlQuery<{
      content: { metadata: { bible: { styles: StyleDef[] | Record<string, StyleDef> | null } | null } }
    }>(stylesGql, { id: metadata.value })
    const raw = result?.content?.metadata?.bible?.styles
    const map = new Map<string, StyleDef>()
    if (Array.isArray(raw)) {
      for (const s of raw) map.set(s.id, s)
    } else if (raw && typeof raw === 'object') {
      for (const [k, v] of Object.entries(raw)) {
        map.set(k, { ...(v as object), id: k } as StyleDef)
      }
    }
    styleMap.value = map
  } catch {
    styleMap.value = new Map()
  }
}

// A chapter's `content` is an array of root nodes (typically a single `cc`
// container). Unwrap container roots to the node list ScriptureRenderer expects.
function chapterNodes(chapter: BibleChapter): ComponentNode[] {
  const nodes: ComponentNode[] = []
  for (const root of chapter.content ?? []) {
    if (root?._ === 'cc' && root.components) nodes.push(...root.components)
    else if (root) nodes.push(root)
  }
  return nodes
}

async function loadBibles() {
  try {
    const result = await $fetch<Array<{ name: string; metadataId: string }>>('/api/v1/content/metadata/bibles', {
      credentials: 'include',
      headers: { Authorization: 'Bearer ' + (auth.token ?? '') }
    })
    bibles.value = result?.map((item) => ({
      label: item.name,
      value: item.metadataId
    })) || []
  } catch {
    bibles.value = []
  }
}

async function onSearch(q: string): Promise<Array<{ label: string; value: string }>> {
  if (!metadata.value || !q) return []
  try {
    interface BibleFindResult {
      human: string
      usfm: string
      chapter?: {
        reference?: { human: string; usfm: string }
        verses?: Array<{ human: string; usfm: string }>
      }
    }
    const result = await $fetch<BibleFindResult[]>(
      `/api/v1/content/metadata/bible/${metadata.value}/find?human=${encodeURIComponent(q)}&content=false`,
      { credentials: 'include', headers: { Authorization: 'Bearer ' + (auth.token ?? '') } }
    )
    const results: Array<{ label: string; value: string }> = []
    let humans = ''
    let usfm = ''
    for (const ref of result || []) {
      if (!ref.chapter) continue
      if (humans.length > 0) humans += ', '
      humans += ref.human
      if (usfm.length > 0) usfm += '+'
      usfm += ref.usfm
    }
    if (humans && usfm) {
      results.push({ label: `${humans} (as entered)`, value: usfm })
    }
    for (const ref of result || []) {
      if (!ref.chapter || !ref.chapter.reference?.human) continue
      results.push({
        label: `${ref.chapter.reference?.human} (whole chapter)`,
        value: ref.chapter.reference?.usfm ?? ''
      })
      if (ref.chapter.verses?.length) {
        results.push(...ref.chapter.verses.map((v) => ({ label: v.human, value: v.usfm })))
      }
    }
    return results
  } catch {
    return []
  }
}

function onAddReference(value: string | string[] | null | undefined) {
  if (typeof value === 'string' && value && !references.value.includes(value)) {
    references.value = [...references.value, value]
  }
  // Reset so the search box clears back to its placeholder for the next lookup.
  searchSelection.value = null
}

async function loadChapters() {
  if (!metadata.value) { chapters.value = []; return }
  const loaded: BibleChapter[] = []
  for (const ref of references.value) {
    try {
      const result = await $fetch<BibleChapter[]>(
        `/api/v1/content/metadata/bible/${metadata.value}/contents?usfm=${ref}`,
        { credentials: 'include', headers: { Authorization: 'Bearer ' + (auth.token ?? '') } }
      )
      if (result?.[0]) loaded.push(result[0])
    } catch { /* skip */ }
  }
  chapters.value = loaded
}

watch(references, loadChapters, { deep: true })
watch(metadata, () => { loadStyles(); loadChapters() })
onMounted(() => { loadBibles(); loadStyles() })
</script>

<template>
  <div class="bible-refs">
    <div class="bible-refs-controls">
      <Select
        :model-value="metadata"
        :options="bibles"
        placeholder="Select a Bible"
        @update:model-value="metadata = $event as string"
      />
      <Select
        class="ref-search-select"
        :model-value="searchSelection"
        placeholder="Search references…"
        searchable
        :on-search="onSearch"
        :debounce="250"
        @update:model-value="onAddReference"
      />
    </div>

    <div
      v-for="chapter in chapters"
      :key="chapter.usfm"
      class="bible-chapter"
    >
      <div class="bible-chapter-header">
        <span class="bible-chapter-title">{{ chapter.human }}</span>
        <button class="bible-remove-btn" @click="removeReference(chapter.usfm)">
          <Icon name="x" :size="13" color="var(--err)" />
        </button>
      </div>
      <div class="bible-chapter-content">
        <ScriptureRenderer
          :nodes="chapterNodes(chapter)"
          :styles="styleMap"
          :accent="accent"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.bible-refs {
  padding: 8px;
}

.bible-refs-controls {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.ref-search-select {
  flex: 1;
}

.ref-search-select :deep(.select-root),
.ref-search-select :deep(.select-trigger) {
  width: 100%;
}

.bible-chapter {
  border: 1px solid var(--line);
  border-radius: 8px;
  margin-top: 8px;
  overflow: hidden;
}

.bible-chapter-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--bg-3);
  border-bottom: 1px solid var(--line);
}

.bible-chapter-title {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-0);
}

.bible-remove-btn {
  background: none;
  border: none;
  cursor: pointer;
  padding: 2px;
}

.bible-chapter-content {
  padding: 10px 12px;
  font-size: 13px;
  color: var(--fg-1);
  line-height: 1.6;
}
</style>
