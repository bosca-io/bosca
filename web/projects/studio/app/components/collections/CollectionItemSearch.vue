<script setup lang="ts">
import gql from 'graphql-tag'

defineProps<{
  accent?: string
  placeholder?: string
}>()

const emit = defineEmits<{
  select: [item: { id: string; name: string; type: string; typename: string }]
}>()

const { query: gqlQuery } = useGraphQL()

const searchGql = gql`
  query SearchItems($query: String!, $limit: Int!) {
    search {
      search(query: {
        query: $query
        storageSystemName: "Admin Search Index"
        limit: $limit
      }) {
        documents {
          metadata { id name content { type } }
          collection { id name type }
        }
      }
    }
  }
`

const query = ref('')
const results = ref<Array<{ id: string; name: string; type: string; typename: string }>>([])
const searching = ref(false)
const hasSearched = ref(false)
const isFocused = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)
const dropdownStyle = ref<Record<string, string>>({})
let debounceTimer: ReturnType<typeof setTimeout> | undefined
let rafId: number | null = null

const showDropdown = computed(() => isFocused.value && (results.value.length > 0 || searching.value || hasSearched.value))

function onInput() {
  clearTimeout(debounceTimer)
  if (!query.value.trim()) {
    results.value = []
    hasSearched.value = false
    return
  }
  debounceTimer = setTimeout(() => search(), 300)
}

interface SearchDocument {
  metadata?: { id: string; name: string; content?: { type: string } | null } | null
  collection?: { id: string; name: string; type: string } | null
}

async function search() {
  const q = query.value.trim()
  if (!q) return
  searching.value = true
  try {
    const data = await gqlQuery<{
      search: { search: { documents: SearchDocument[] } }
    }>(searchGql, { query: q, limit: 20 })
    const docs = data.search?.search?.documents ?? []
    const items: Array<{ id: string; name: string; type: string; typename: string }> = []
    for (const d of docs) {
      if (d.metadata) {
        items.push({
          id: d.metadata.id,
          name: d.metadata.name,
          type: d.metadata.content?.type ?? 'metadata',
          typename: 'Metadata',
        })
      } else if (d.collection) {
        items.push({
          id: d.collection.id,
          name: d.collection.name,
          type: d.collection.type,
          typename: 'Collection',
        })
      }
    }
    results.value = items
    hasSearched.value = true
  } catch {
    results.value = []
    hasSearched.value = true
  } finally {
    searching.value = false
  }
}

function onSelect(item: { id: string; name: string; type: string; typename: string }) {
  emit('select', item)
  query.value = ''
  results.value = []
  hasSearched.value = false
}

function updateDropdownPosition() {
  const el = inputRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  const viewportHeight = window.innerHeight
  const dropdownMaxHeight = 320
  const gap = 4
  const spaceBelow = viewportHeight - rect.bottom
  const spaceAbove = rect.top
  const openUp = spaceBelow < dropdownMaxHeight + gap && spaceAbove > spaceBelow
  if (openUp) {
    dropdownStyle.value = {
      left: `${rect.left}px`,
      width: `${rect.width}px`,
      bottom: `${viewportHeight - rect.top + gap}px`,
      maxHeight: `${Math.max(120, spaceAbove - gap - 8)}px`,
    }
  } else {
    dropdownStyle.value = {
      left: `${rect.left}px`,
      width: `${rect.width}px`,
      top: `${rect.bottom + gap}px`,
      maxHeight: `${Math.max(120, spaceBelow - gap - 8)}px`,
    }
  }
}

function scheduleReposition() {
  if (rafId !== null) return
  rafId = requestAnimationFrame(() => {
    rafId = null
    updateDropdownPosition()
  })
}

function onFocus() {
  isFocused.value = true
  updateDropdownPosition()
  window.addEventListener('scroll', scheduleReposition, true)
  window.addEventListener('resize', scheduleReposition)
}

function onBlur(e: FocusEvent) {
  const next = e.relatedTarget as HTMLElement | null
  if (next?.closest('.search-results')) return
  isFocused.value = false
  window.removeEventListener('scroll', scheduleReposition, true)
  window.removeEventListener('resize', scheduleReposition)
}

watch([results, searching, hasSearched], () => {
  if (isFocused.value) scheduleReposition()
})

onBeforeUnmount(() => {
  if (rafId !== null) cancelAnimationFrame(rafId)
  window.removeEventListener('scroll', scheduleReposition, true)
  window.removeEventListener('resize', scheduleReposition)
})
</script>

<template>
  <div class="item-search">
    <input
      ref="inputRef"
      v-model="query"
      class="search-input"
      :placeholder="placeholder ?? 'Search items…'"
      @input="onInput"
      @focus="onFocus"
      @blur="onBlur"
    >
    <Teleport to="body">
      <div
        v-if="showDropdown"
        class="search-results"
        :style="dropdownStyle"
      >
        <template v-if="results.length">
          <button
            v-for="r in results"
            :key="r.id"
            type="button"
            class="search-result"
            @mousedown.prevent="onSelect(r)"
          >
            <Icon :name="r.typename === 'Collection' ? 'boxes' : 'file'" :size="13" :color="accent ?? 'var(--fg-3)'" />
            <div class="result-info">
              <span class="result-name">{{ r.name }}</span>
              <span class="result-type">{{ r.typename }} · {{ r.type }}</span>
            </div>
          </button>
        </template>
        <div v-else-if="searching" class="search-status">Searching…</div>
        <div v-else-if="hasSearched" class="search-status">No matches.</div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.item-search {
  min-width: 0;
}

.search-input {
  width: 100%;
  padding: 7px 10px;
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
</style>

<style>
.search-results {
  position: fixed;
  z-index: 10000;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
  padding: 3px;
}

.search-results .search-result {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: var(--r-sm);
  text-align: left;
  background: none;
  border: none;
  cursor: pointer;
  color: inherit;
  font-family: inherit;
  transition: background 0.15s;
}

.search-results .search-result:hover {
  background: var(--bg-2);
}

.search-results .result-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.search-results .result-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-results .result-type {
  font-size: 10.5px;
  color: var(--fg-3);
}

.search-results .search-status {
  padding: 12px;
  text-align: center;
  font-size: 12px;
  color: var(--fg-3);
}
</style>
