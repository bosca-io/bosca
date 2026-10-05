<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'

const props = withDefaults(defineProps<{
  contentType?: string
  allowCreate?: boolean
  initialContent?: string
  newFilePlaceholder?: string
}>(), {
  contentType: undefined,
  allowCreate: false,
  initialContent: '',
  newFilePlaceholder: 'filename',
})

const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const { searchProfiles } = useProfileSearch()
const toast = useToast()

interface GitSource {
  repositoryId: string
  ref: string
  path: string
}

const emit = defineEmits<{ select: [source: GitSource] }>()

const auth = import.meta.client ? useAuth() : null
const profile = auth?.profile ?? null

// Shared author resolver — same source as the analytics edit page and backfill modal.
const commitAuthor = computed(() => resolveGitCommitAuthor(profile?.value))

interface Repo { id: string; name: string; defaultBranch: string }
interface Branch { name: string }
interface TreeEntry { name: string; path: string; type: string; size: number | null }

const selectedOwner = ref('')
const repos = ref<Repo[]>([])
const branches = ref<Branch[]>([])
const treeEntries = ref<TreeEntry[]>([])
const currentPath = ref('')
const loading = ref(false)

const selectedRepoId = ref('')
const selectedRef = ref('')
const selectedFile = ref('')

const showCreate = ref(false)
const newFileName = ref('')
const creating = ref(false)

const initialOwnerOption = computed(() => {
  const p = profile?.value
  if (!p?.id) return []
  return [{ value: p.id, label: p.name || p.slug || p.id }]
})

const repoOptions = computed(() => repos.value.map(r => ({ value: r.id, label: r.name })))
const branchOptions = computed(() => branches.value.map(b => ({ value: b.name, label: b.name })))

const pathSegments = computed(() => {
  if (!currentPath.value) return []
  return currentPath.value.split('/').map((seg, i, arr) => ({
    name: seg,
    path: arr.slice(0, i + 1).join('/'),
  }))
})

watch(() => profile?.value?.id, (id) => {
  if (id && !selectedOwner.value) selectedOwner.value = id
}, { immediate: true })

watch(selectedOwner, async (ownerId) => {
  selectedRepoId.value = ''
  repos.value = []
  if (!ownerId) return
  try {
    const result = await gqlQuery<{ git: { repositories: Repo[] } }>(gql`
      query Repos($ownerId: UUID!, $contentType: GitRepositoryContentType) {
        git { repositories(ownerId: $ownerId, contentType: $contentType) { id name defaultBranch } }
      }
    `, { ownerId, contentType: props.contentType ?? null })
    repos.value = result.git?.repositories ?? []
  } catch { /* ignore */ }
}, { immediate: true })

watch(selectedRepoId, async (repoId) => {
  branches.value = []
  treeEntries.value = []
  currentPath.value = ''
  selectedFile.value = ''
  if (!repoId) return

  const repo = repos.value.find(r => r.id === repoId)
  if (repo && !selectedRef.value) selectedRef.value = repo.defaultBranch

  try {
    const result = await gqlQuery<{ git: { branches: Branch[] } }>(gql`
      query Branches($repositoryId: UUID!) {
        git { branches(repositoryId: $repositoryId) { name } }
      }
    `, { repositoryId: repoId })
    branches.value = result.git?.branches ?? []
  } catch { /* ignore */ }

  loadTree()
})

watch(selectedRef, () => {
  if (selectedRepoId.value) loadTree()
})

async function loadTree() {
  if (!selectedRepoId.value || !selectedRef.value) return
  loading.value = true
  try {
    const result = await gqlQuery<{ git: { tree: TreeEntry[] } }>(gql`
      query Tree($repositoryId: UUID!, $ref: String, $path: String) {
        git { tree(repositoryId: $repositoryId, ref: $ref, path: $path) { name path type size } }
      }
    `, { repositoryId: selectedRepoId.value, ref: selectedRef.value, path: currentPath.value || null })
    const entries = result.git?.tree ?? []
    entries.sort((a, b) => {
      if (a.type === 'TREE' && b.type !== 'TREE') return -1
      if (a.type !== 'TREE' && b.type === 'TREE') return 1
      return a.name.localeCompare(b.name)
    })
    treeEntries.value = entries
  } catch { treeEntries.value = [] }
  finally { loading.value = false }
}

function onEntryClick(entry: TreeEntry) {
  if (entry.type === 'TREE') {
    currentPath.value = entry.path
    loadTree()
  } else {
    selectedFile.value = entry.path
    emit('select', {
      repositoryId: selectedRepoId.value,
      ref: selectedRef.value,
      path: entry.path,
    })
  }
}

function navigateUp() {
  const parts = currentPath.value.split('/')
  parts.pop()
  currentPath.value = parts.join('/')
  loadTree()
}

function navigateToRoot() {
  currentPath.value = ''
  loadTree()
}

function navigateToPath(path: string) {
  currentPath.value = path
  loadTree()
}

function joinPath(dir: string, name: string): string {
  const clean = name.replace(/^\/+/, '').replace(/\/+$/, '')
  return dir ? `${dir}/${clean}` : clean
}

async function createFile() {
  const name = newFileName.value.trim()
  if (!name) { toast.error('File name required'); return }
  if (!selectedRepoId.value || !selectedRef.value) return
  const path = joinPath(currentPath.value, name)
  if (treeEntries.value.some(e => e.path === path)) {
    toast.error('File already exists at that path')
    return
  }
  creating.value = true
  try {
    await gqlMutation(gql`
      mutation CommitFile($input: CommitFileInput!) {
        git { commitFile(input: $input) { commitSha branch path } }
      }
    `, {
      input: {
        repositoryId: selectedRepoId.value,
        branch: selectedRef.value,
        path,
        content: props.initialContent ?? '',
        message: `Create ${path}`,
        authorName: commitAuthor.value.authorName,
        authorEmail: commitAuthor.value.authorEmail,
      },
    })
    showCreate.value = false
    newFileName.value = ''
    await loadTree()
    emit('select', { repositoryId: selectedRepoId.value, ref: selectedRef.value, path })
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create file')
  } finally {
    creating.value = false
  }
}

</script>

<template>
  <div class="git-picker">
    <div class="git-picker__controls">
      <Select
        v-model="selectedOwner"
        :options="initialOwnerOption"
        :on-search="searchProfiles"
        searchable
        placeholder="Owner…"
        size="sm" />
      <Select
        v-model="selectedRepoId"
        :options="repoOptions"
        placeholder="Repository…"
        size="sm"
        searchable />
      <Select
        v-if="branchOptions.length"
        v-model="selectedRef"
        :options="branchOptions"
        placeholder="Branch…"
        size="sm"
        searchable />
      <button
        v-if="selectedRepoId && selectedRef"
        class="git-picker__refresh"
        title="Refresh"
        @click="loadTree">
        <Icon name="refresh" :size="14" color="var(--fg-3)" />
      </button>
      <button
        v-if="allowCreate && selectedRepoId && selectedRef"
        class="git-picker__refresh"
        title="New file"
        @click="showCreate = !showCreate">
        <Icon name="plus" :size="14" color="var(--fg-3)" />
      </button>
    </div>

    <div v-if="showCreate && selectedRepoId && selectedRef" class="git-picker__create">
      <span class="git-picker__create-prefix mono">{{ currentPath ? currentPath + '/' : '' }}</span>
      <input
        v-model="newFileName"
        class="git-picker__create-input mono"
        :placeholder="newFilePlaceholder"
        :disabled="creating"
        @keyup.enter="createFile" >
      <Button size="sm" :disabled="creating || !newFileName.trim()" @click="createFile">{{ creating ? 'Creating…' : 'Create' }}</Button>
      <Button size="sm" :disabled="creating" @click="showCreate = false">Cancel</Button>
    </div>

    <div v-if="selectedRepoId" class="git-picker__browser">
      <div v-if="currentPath" class="git-picker__breadcrumb">
        <button class="git-picker__crumb" @click="navigateToRoot">/</button>
        <template v-for="seg in pathSegments" :key="seg.path">
          <span class="git-picker__crumb-sep">/</span>
          <button class="git-picker__crumb" @click="navigateToPath(seg.path)">{{ seg.name }}</button>
        </template>
      </div>

      <div class="git-picker__tree">
        <button v-if="currentPath" class="git-picker__entry" @click="navigateUp">
          <Icon name="folder" :size="12" color="var(--fg-3)" />
          <span>..</span>
        </button>
        <button
          v-for="entry in treeEntries"
          :key="entry.path"
          class="git-picker__entry"
          @click="onEntryClick(entry)"
        >
          <Icon :name="entry.type === 'TREE' ? 'folder' : 'file'" :size="12" :color="entry.type === 'TREE' ? 'var(--brand-2)' : 'var(--fg-3)'" />
          <span>{{ entry.name }}</span>
          <span v-if="entry.size != null" class="git-picker__size mono">{{ entry.size }}</span>
        </button>
        <div v-if="loading" class="git-picker__empty">Loading…</div>
        <div v-else-if="!treeEntries.length && selectedRepoId" class="git-picker__empty">Empty directory</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.git-picker {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.git-picker__controls {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.git-picker__refresh {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.git-picker__refresh:hover {
  background: var(--bg-3);
  border-color: var(--fg-4);
}

.git-picker__browser {
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.git-picker__breadcrumb {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 6px 10px;
  background: var(--bg-2);
  border-bottom: 1px solid var(--line);
  font-size: 11px;
}

.git-picker__crumb {
  color: var(--brand-2);
  background: none;
  border: none;
  cursor: pointer;
  font-size: 11px;
  font-family: var(--font-mono);
}

.git-picker__crumb:hover {
  text-decoration: underline;
}

.git-picker__crumb-sep {
  color: var(--fg-4);
}

.git-picker__tree {
  max-height: 200px;
  overflow: auto;
}

.git-picker__entry {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 10px;
  background: none;
  border: none;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 40%, transparent);
  cursor: pointer;
  font-size: 12px;
  color: var(--fg-1);
  text-align: left;
}

.git-picker__entry:last-child {
  border-bottom: none;
}

.git-picker__entry:hover {
  background: var(--bg-2);
}

.git-picker__size {
  margin-left: auto;
  font-size: 10px;
  color: var(--fg-4);
}

.git-picker__empty {
  padding: 12px;
  text-align: center;
  font-size: 12px;
  color: var(--fg-3);
}

.git-picker__create {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

.git-picker__create-prefix {
  font-size: 11px;
  color: var(--fg-3);
  white-space: nowrap;
}

.git-picker__create-input {
  flex: 1;
  min-width: 0;
  padding: 4px 6px;
  font-size: 12px;
  background: var(--bg-1);
  color: var(--fg-0);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

.git-picker__create-input:focus {
  outline: none;
  border-color: var(--brand-2);
}
</style>
