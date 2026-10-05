<script lang="ts" setup>
/* eslint-disable @typescript-eslint/no-explicit-any */

defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  select: [attachment: { type: string; id: string; name: string }]
  close: []
}>()

const gql = useGraphQL()
const toast = useToast()

const mode = ref<'menu' | 'metadata' | 'collection'>('menu')
const query = ref('')
const results = ref<any[]>([])
const searching = ref(false)
const uploading = ref(false)
const isDragging = ref(false)
const fileInputEl = ref<HTMLInputElement>()

let searchTimer: ReturnType<typeof setTimeout> | null = null

const SEARCH_METADATA = `
  query SearchMetadataAttach($query: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          metadata {
            id name slug content { type }
            relationships(filter: ["image.featured", "image.avatar", "image.preview"]) {
              relationship
              metadata { id slug }
            }
          }
        }
      }
    }
  }
`

const SEARCH_COLLECTIONS = `
  query SearchCollectionsAttach($query: String!, $limit: Int!, $offset: Int!) {
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

const ADD_METADATA = `
  mutation AddMetadataAttach($metadata: MetadataInput!) {
    content { metadata { add(metadata: $metadata, setReady: true) {
      id name
      content { urls { upload { url headers { name value } } } }
    } } }
  }
`

async function doSearch() {
  if (!query.value.trim()) { results.value = []; searching.value = false; return }
  searching.value = true
  try {
    if (mode.value === 'metadata') {
      const result = await gql.query<any>(SEARCH_METADATA, { query: query.value, limit: 20, offset: 0 })
      results.value = (result?.search?.search?.documents ?? [])
        .filter((d: any) => d.metadata)
        .map((d: any) => {
          const rel = d.metadata.relationships?.[0]
          return {
            id: d.metadata.id,
            name: d.metadata.name,
            imageSlug: rel?.metadata?.slug ?? null,
            contentType: d.metadata.content?.type ?? '',
          }
        })
    } else {
      const result = await gql.query<any>(SEARCH_COLLECTIONS, { query: query.value, limit: 20, offset: 0 })
      results.value = (result?.search?.search?.documents ?? [])
        .filter((d: any) => d.collection)
        .map((d: any) => ({ id: d.collection.id, name: d.collection.name }))
    }
  } catch {
    results.value = []
  }
  searching.value = false
}

watch(query, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(doSearch, 250)
})

async function uploadFile(file: File) {
  uploading.value = true
  try {
    const result = await gql.mutation<any>(ADD_METADATA, {
      metadata: {
        name: file.name,
        languageTag: 'en',
        contentType: file.type || 'application/octet-stream',
        contentLength: file.size,
      },
    })
    const metadata = result?.content?.metadata?.add
    if (!metadata) throw new Error('Failed to create metadata')
    const upload = metadata.content?.urls?.upload
    if (upload) {
      const headers = new Headers()
      for (const hdr of upload.headers) headers.append(hdr.name, hdr.value)
      const formData = new FormData()
      formData.append('file', file)
      await fetch(upload.url, { method: 'POST', body: formData, headers })
    }
    toast.success(`Uploaded ${file.name}`)
    emit('select', { type: 'metadata', id: metadata.id, name: metadata.name || file.name })
    resetAndClose()
  } catch (e: any) {
    toast.error(e?.message || 'Upload failed')
  }
  uploading.value = false
}

function onSelect(item: { id: string; name: string }) {
  emit('select', { type: mode.value, id: item.id, name: item.name })
  resetAndClose()
}

function onFileInput(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (file) uploadFile(file)
  ;(e.target as HTMLInputElement).value = ''
}

function onDrop(e: DragEvent) {
  isDragging.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) uploadFile(file)
}

function openMode(m: 'metadata' | 'collection') {
  mode.value = m
  query.value = ''
  results.value = []
}

function resetAndClose() {
  mode.value = 'menu'
  query.value = ''
  results.value = []
  emit('close')
}

onUnmounted(() => { if (searchTimer) clearTimeout(searchTimer) })
</script>

<template>
  <div v-if="visible" class="attach-bar">
    <!-- Menu -->
    <template v-if="mode === 'menu'">
      <button class="attach-option" @click="openMode('metadata')">
        <Icon name="upload" :size="13" /> Metadata / Upload
      </button>
      <button class="attach-option" @click="openMode('collection')">
        <Icon name="boxes" :size="13" /> Collection
      </button>
    </template>

    <!-- Metadata search + upload -->
    <template v-else-if="mode === 'metadata'">
      <div class="attach-header">
        <button class="attach-back" @click="mode = 'menu'">
          <Icon name="arrowLeft" :size="12" /> Back
        </button>
        <span class="attach-title">Metadata / Upload</span>
      </div>
      <input
        v-model="query"
        class="attach-search"
        placeholder="Search metadata…"
        autofocus
      >
      <div v-if="searching" class="attach-status">Searching…</div>
      <div v-if="results.length" class="attach-results">
        <button
          v-for="r in results"
          :key="r.id"
          class="attach-result"
          @click="onSelect(r)">
          <img
            v-if="r.imageSlug"
            :src="`/content/image/${r.imageSlug}`"
            class="attach-thumb"
            alt=""
            @error="($event.target as HTMLImageElement).style.display = 'none'">
          <Icon
            v-else
            name="file"
            :size="13"
            color="var(--fg-3)" />
          <span class="attach-result-name">{{ r.name }}</span>
        </button>
      </div>
      <div
        class="attach-dropzone"
        :class="{ 'attach-dropzone--active': isDragging }"
        @dragover.prevent="isDragging = true"
        @dragleave="isDragging = false"
        @drop.prevent="onDrop"
        @click="fileInputEl?.click()"
      >
        <template v-if="uploading">Uploading…</template>
        <template v-else>Drop file or click to upload</template>
      </div>
      <input
        ref="fileInputEl"
        type="file"
        hidden
        @change="onFileInput">
    </template>

    <!-- Collection search -->
    <template v-else-if="mode === 'collection'">
      <div class="attach-header">
        <button class="attach-back" @click="mode = 'menu'">
          <Icon name="arrowLeft" :size="12" /> Back
        </button>
        <span class="attach-title">Collection</span>
      </div>
      <input
        v-model="query"
        class="attach-search"
        placeholder="Search collections…"
        autofocus
      >
      <div v-if="searching" class="attach-status">Searching…</div>
      <div v-if="results.length" class="attach-results">
        <button
          v-for="r in results"
          :key="r.id"
          class="attach-result"
          @click="onSelect(r)">
          <Icon name="boxes" :size="13" color="var(--fg-3)" />
          <span class="attach-result-name">{{ r.name }}</span>
        </button>
      </div>
      <div v-else-if="query && !searching" class="attach-status">No results.</div>
    </template>
  </div>
</template>

<style scoped>
.attach-bar {
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-1);
  padding: 4px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 300px;
  overflow-y: auto;
}

.attach-option {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 10px; font-size: 13px; color: var(--fg-1);
  background: none; border: none; border-radius: var(--r-sm);
  cursor: pointer; text-align: left; white-space: nowrap;
}
.attach-option:hover { background: var(--bg-2); }

.attach-header {
  display: flex; align-items: center; gap: 6px; padding: 4px 6px;
}
.attach-back {
  display: flex; align-items: center; gap: 3px;
  font-size: 11px; color: var(--fg-3); background: none; border: none;
  cursor: pointer; padding: 2px 4px; border-radius: var(--r-xs);
}
.attach-back:hover { color: var(--fg-1); background: var(--bg-2); }
.attach-title { font-size: 12px; font-weight: 600; color: var(--fg-2); }

.attach-search {
  padding: 6px 8px; font-size: 12px; background: var(--bg-2);
  border: 1px solid var(--line); border-radius: var(--r-xs);
  color: var(--fg-1); outline: none; margin: 2px 4px;
}
.attach-search:focus { border-color: var(--brand-2); }

.attach-status { font-size: 11px; color: var(--fg-3); padding: 4px 8px; }

.attach-results {
  display: flex; flex-direction: column; gap: 1px; max-height: 160px; overflow-y: auto;
}
.attach-result {
  display: flex; align-items: center; gap: 6px; padding: 5px 8px;
  font-size: 12px; color: var(--fg-1); background: none; border: none;
  border-radius: var(--r-xs); cursor: pointer; text-align: left;
}
.attach-result:hover { background: var(--bg-2); }
.attach-result-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.attach-thumb {
  width: 24px; height: 24px; border-radius: var(--r-xs);
  object-fit: cover; flex-shrink: 0; background: var(--bg-3);
}

.attach-dropzone {
  margin: 4px; padding: 12px; border: 1px dashed var(--line);
  border-radius: var(--r-xs); text-align: center;
  font-size: 11px; color: var(--fg-3); cursor: pointer;
}
.attach-dropzone:hover { border-color: var(--brand-2); }
.attach-dropzone--active { border-color: var(--brand-2); background: color-mix(in oklch, var(--brand-2) 5%, transparent); }
</style>
