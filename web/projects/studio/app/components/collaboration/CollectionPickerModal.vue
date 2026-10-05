<script lang="ts" setup>
/* eslint-disable @typescript-eslint/no-explicit-any */

const emit = defineEmits<{
  select: [collection: { id: string; name: string }]
  close: []
}>()

const gql = useGraphQL()

const query = ref('')
const results = ref<{ id: string; name: string }[]>([])
const searching = ref(false)

const SEARCH_COLLECTIONS = `
  query SearchCollections($query: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: ["_type = \\"collection\\""]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          collection { id name }
        }
      }
    }
  }
`

let searchTimer: ReturnType<typeof setTimeout> | null = null

async function doSearch() {
  if (!query.value.trim()) { results.value = []; searching.value = false; return }
  searching.value = true
  try {
    const result = await gql.query<any>(SEARCH_COLLECTIONS, { query: query.value, limit: 20, offset: 0 })
    results.value = (result?.search?.search?.documents ?? [])
      .filter((d: any) => d.collection)
      .map((d: any) => ({ id: d.collection.id, name: d.collection.name }))
  } catch {
    results.value = []
  }
  searching.value = false
}

watch(query, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(doSearch, 250)
})

onUnmounted(() => { if (searchTimer) clearTimeout(searchTimer) })
</script>

<template>
  <Teleport to="body">
    <div class="picker-overlay" @click.self="emit('close')">
      <div class="picker-dialog">
        <div class="picker-header">
          <h3 class="picker-title">Select Collection</h3>
          <button class="picker-close" @click="emit('close')">
            <Icon name="x" :size="16" />
          </button>
        </div>

        <div class="picker-body">
          <input
            v-model="query"
            class="search-input"
            placeholder="Search collections…"
            autofocus
          >

          <div v-if="searching" class="searching-text">Searching…</div>
          <div v-else-if="results.length" class="result-list">
            <button
              v-for="r in results"
              :key="r.id"
              class="result-row"
              @click="emit('select', { id: r.id, name: r.name })"
            >
              <Icon name="boxes" :size="14" color="var(--fg-3)" />
              <span class="result-name">{{ r.name }}</span>
            </button>
          </div>

          <div v-else-if="query && !searching" class="empty-text">No collections found.</div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.picker-overlay { position: fixed; inset: 0; z-index: 9000; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; }
.picker-dialog { background: var(--bg-0); border: 1px solid var(--line); border-radius: var(--r-lg); width: 420px; max-height: 60vh; display: flex; flex-direction: column; overflow: hidden; }
.picker-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; border-bottom: 1px solid var(--line); }
.picker-title { font-size: 15px; font-weight: 600; color: var(--fg-0); margin: 0; }
.picker-close { background: none; border: none; color: var(--fg-3); cursor: pointer; padding: 4px; border-radius: var(--r-sm); }
.picker-close:hover { color: var(--fg-0); background: var(--bg-2); }
.picker-body { padding: 14px 18px; display: flex; flex-direction: column; gap: 12px; overflow-y: auto; }
.search-input { width: 100%; padding: 8px 12px; font-size: 13px; background: var(--bg-2); border: 1px solid var(--line); border-radius: var(--r-sm); color: var(--fg-1); outline: none; box-sizing: border-box; }
.search-input:focus { border-color: var(--brand-2); }
.searching-text { font-size: 12px; color: var(--fg-3); text-align: center; padding: 8px; }
.result-list { display: flex; flex-direction: column; gap: 2px; max-height: 250px; overflow-y: auto; }
.result-row { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: var(--r-sm); cursor: pointer; text-align: left; background: none; border: none; color: var(--fg-1); width: 100%; }
.result-row:hover { background: var(--bg-2); }
.result-name { flex: 1; font-size: 13px; font-weight: 500; }
.empty-text { font-size: 13px; color: var(--fg-3); text-align: center; padding: 16px; }
</style>
