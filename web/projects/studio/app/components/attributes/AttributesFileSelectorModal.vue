<script lang="ts" setup>
import gql from 'graphql-tag'

interface FileAsset {
  id: string
  name: string
  contentType: string
  attributes: Record<string, unknown>
}

const props = defineProps<{
  onSelected: (_item: FileAsset) => void
  searchFilter?: string | null
  allowYouTube?: boolean
}>()

const emit = defineEmits<{ close: [] }>()

const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const language = useLanguage()
const toast = useToast()
const searchTerm = ref('')
const results = ref<FileAsset[]>([])
const loading = ref(false)
const searchInputRef = ref<HTMLInputElement>()
const youtubeInput = ref('')
const addingYouTube = ref(false)

const searchGql = gql`
  query SearchFileAssets($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          metadata {
            id
            name
            attributes
            content { type }
          }
        }
        estimatedHits
      }
    }
  }
`

const addYouTubeGql = gql`
  mutation AddYouTubeVideo($metadata: MetadataInput!, $setReady: Boolean) {
    content {
      metadata {
        add(metadata: $metadata, setReady: $setReady) {
          id
          name
          attributes
          content { type }
        }
      }
    }
  }
`

let searchTimeout: ReturnType<typeof setTimeout> | undefined

watch(searchTerm, () => {
  clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => doSearch(), 300)
})

onUnmounted(() => clearTimeout(searchTimeout))

onMounted(() => {
  doSearch()
  nextTick(() => searchInputRef.value?.focus?.())
})

interface FileSearchDocument {
  metadata: { id: string; name: string; attributes: Record<string, unknown> | null; content: { type: string } | null } | null
}

interface FileSearchResult {
  search: { search: { documents: FileSearchDocument[]; estimatedHits: number } }
}

async function doSearch() {
  loading.value = true
  try {
    const filter = props.searchFilter
      ? `_type = "metadata" AND ${props.searchFilter}`
      : '_type = "metadata"'
    const result = await gqlQuery<FileSearchResult>(searchGql, {
      query: searchTerm.value,
      filter,
      limit: 50,
      offset: 0,
    })
    results.value = (result?.search?.search?.documents ?? [])
      .map((d) => d.metadata)
      .filter((m): m is NonNullable<FileSearchDocument['metadata']> => m != null)
      .map((m) => ({
        id: m.id,
        name: m.name,
        contentType: m.content?.type || '',
        attributes: m.attributes ?? {},
      }))
  } catch (e) {
    console.error('Asset search failed', e)
    results.value = []
  } finally {
    loading.value = false
  }
}

function onSelect(item: FileAsset) {
  props.onSelected(item)
  emit('close')
}

function extractYouTubeId(value: string): string | null {
  const input = value.trim()
  if (/^[\w-]{11}$/.test(input)) return input
  try {
    const url = new URL(input.includes('://') ? input : `https://${input}`)
    const host = url.hostname.replace(/^www\./, '')
    const path = url.pathname.split('/').filter(Boolean)
    let id: string | null | undefined
    if (host === 'youtu.be') {
      id = path[0]
    } else if (['youtube.com', 'm.youtube.com', 'youtube-nocookie.com'].includes(host)) {
      if (path[0] === 'watch') {
        id = url.searchParams.get('v')
      } else if (['embed', 'shorts', 'live'].includes(path[0] ?? '')) {
        id = path[1]
      }
    }
    return id && /^[\w-]{11}$/.test(id) ? id : null
  } catch {
    return null
  }
}

async function onAddYouTube() {
  if (!props.allowYouTube || addingYouTube.value) return
  const id = extractYouTubeId(youtubeInput.value)
  if (!id) {
    toast.error('Enter a valid YouTube URL or ID')
    return
  }
  addingYouTube.value = true
  try {
    const result = await gqlMutation<{
      content: { metadata: { add: FileSearchDocument['metadata'] } }
    }>(addYouTubeGql, {
      metadata: {
        name: `YouTube Video (${id})`,
        contentType: 'bosca/x-youtube-video',
        languageTag: language.current.value?.tag || 'en',
        attributes: { type: 'YouTube Video', 'youtube.id': id },
      },
      setReady: true,
    })
    const item = result?.content?.metadata?.add
    if (!item) throw new Error('Unable to create YouTube metadata')
    onSelect({
      id: item.id,
      name: item.name,
      contentType: item.content?.type || '',
      attributes: item.attributes ?? {},
    })
  } catch (e) {
    toast.error(e instanceof Error ? e.message : 'Unable to create YouTube metadata')
  } finally {
    addingYouTube.value = false
  }
}

function assetIcon(contentType: string): string {
  if (contentType.startsWith('video/') || contentType === 'bosca/x-youtube-video') return 'video'
  if (contentType.startsWith('audio/')) return 'audio'
  if (contentType.startsWith('image/')) return 'image'
  return 'file'
}
</script>

<template>
  <Modal
    title="Select Asset"
    subtitle="Choose an existing asset from the content library"
    icon="file"
    width="720px"
    @close="emit('close')"
  >
    <div class="selector-body">
      <div class="search-bar">
        <TextInput
          ref="searchInputRef"
          v-model="searchTerm"
          placeholder="Search assets…"
          icon="search"
        />
      </div>

      <form v-if="allowYouTube" class="youtube-form" @submit.prevent="onAddYouTube">
        <TextInput
          v-model="youtubeInput"
          label="YouTube URL or ID"
          placeholder="YouTube URL or ID"
          :disabled="addingYouTube"
        />
        <Button
          type="submit"
          primary
          :icon="addingYouTube ? 'spinner' : 'plus'"
          :disabled="addingYouTube || !youtubeInput.trim()"
        >
          {{ addingYouTube ? 'Adding…' : 'Add YouTube Video' }}
        </Button>
      </form>

      <div v-if="loading" class="selector-loading">Searching…</div>

      <div v-else-if="results.length === 0" class="selector-empty">
        {{ searchTerm ? 'No assets found' : 'No assets available' }}
      </div>

      <div v-else class="asset-list">
        <button
          v-for="item in results"
          :key="item.id"
          class="asset-row"
          @click="onSelect(item)"
        >
          <Icon :name="assetIcon(item.contentType)" :size="14" color="var(--fg-3)" />
          <span class="asset-name">{{ item.name }}</span>
          <Badge v-if="item.contentType" color="var(--fg-3)">{{ item.contentType }}</Badge>
        </button>
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.selector-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-bar { padding: 0; }

.youtube-form {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.youtube-form :deep(.text-input-root) {
  flex: 1;
  min-width: 200px;
}

.selector-loading,
.selector-empty {
  text-align: center;
  padding: 32px;
  color: var(--fg-3);
  font-size: 13px;
}

.asset-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 400px;
  overflow-y: auto;
  padding: 2px;
}

.asset-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.12s;
}

.asset-row:hover {
  border-color: var(--brand-2);
}

.asset-name {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
