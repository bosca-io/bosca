<script setup lang="ts">
import { useAuth } from '@bosca/auth-client-browser'
import type { SelectOption } from '@bosca/ui'
import gql from 'graphql-tag'
import { uploadRawArtifactFile } from '~/utils/rawArtifactUpload'

const NEW_REPOSITORY = '__new_raw_repository__'
const REPOSITORY_NAME = /^[a-zA-Z0-9\-_.@]+$/

interface Namespace {
  id: string
  name: string
}

interface Repository {
  id: string
  namespaceId: string
  name: string
  type: string
  namespace: Namespace | null
}

interface UploadItem {
  id: number
  file: File
  filename: string
  status: 'ready' | 'uploading' | 'uploaded' | 'failed'
}

interface UploadTarget {
  id: string
  name: string
  namespace: Namespace
}

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const authState = import.meta.client ? useAuth() : null
const { stagedFiles, clear: clearStagedFiles } = useRawArtifactUploadStaging()
const artifactsBase = (useRuntimeConfig().public.artifactsUrl ?? '').replace(/\/+$/, '')

const targetsGql = gql`
  query RawArtifactUploadTargets {
    artifactsAdmin {
      namespaces { id name }
      repositories {
        id
        namespaceId
        name
        type
        namespace { id name }
      }
    }
  }
`

const createRepositoryGql = gql`
  mutation CreateRawArtifactRepository($namespaceId: UUID!, $name: String!, $type: ArtifactRegistryType!) {
    artifactsAdmin {
      createRepository(namespaceId: $namespaceId, name: $name, type: $type) {
        id
        namespaceId
        name
        type
      }
    }
  }
`

const { data, status } = useAsyncQuery<{
  artifactsAdmin: { namespaces: Namespace[]; repositories: Repository[] }
}>('raw-artifact-upload-targets', targetsGql, {}, { server: false })

const namespaces = computed(() => data.value?.artifactsAdmin?.namespaces ?? [])
const rawRepositories = computed(() =>
  (data.value?.artifactsAdmin?.repositories ?? []).filter(
    (repository): repository is Repository & { namespace: Namespace } => repository.type === 'raw' && !!repository.namespace,
  ),
)
const loadingTargets = computed(() => status.value === 'pending')

const requestedRepositoryId = Array.isArray(route.query.repositoryId)
  ? route.query.repositoryId[0]
  : route.query.repositoryId
const selectedTarget = ref<string | undefined>(requestedRepositoryId || undefined)
const selectedNamespaceId = ref<string | undefined>(undefined)
const newRepositoryName = ref('')
const version = ref('')
const uploading = ref(false)
const uploadError = ref('')
const createdRepository = ref<UploadTarget | null>(null)
const uploadItems = ref<UploadItem[]>([])
let nextItemId = 1

const repositoryOptions = computed<SelectOption[]>(() => [
  {
    value: NEW_REPOSITORY,
    label: 'Create a new raw repository',
    icon: 'plus',
    disabled: namespaces.value.length === 0,
  },
  ...rawRepositories.value.map(repository => ({
    value: repository.id,
    label: `${repository.namespace.name}/${repository.name}`,
  })),
])

const namespaceOptions = computed<SelectOption[]>(() =>
  namespaces.value.map(namespace => ({ value: namespace.id, label: namespace.name })),
)
const isNewRepository = computed(() => selectedTarget.value === NEW_REPOSITORY)
const selectedRepository = computed(() =>
  rawRepositories.value.find(repository => repository.id === selectedTarget.value) ?? null,
)

const repositoryNameError = computed(() => {
  if (!isNewRepository.value) return ''
  const name = newRepositoryName.value.trim()
  if (!name) return 'Repository name is required.'
  if (name.length > 255) return 'Repository name must be 255 characters or fewer.'
  if (name.includes('..')) return "Repository name must not contain '..'."
  if (!REPOSITORY_NAME.test(name)) {
    return "Use only letters, numbers, '-', '_', '.', and '@'."
  }
  return ''
})

function containsUnsafePathCharacter(value: string): boolean {
  return value.includes('/')
    || value.includes('\\')
    || Array.from(value).some(character => {
      const code = character.charCodeAt(0)
      return code < 32 || code === 127
    })
}

const versionError = computed(() => {
  const value = version.value.trim()
  if (!value) return 'Version is required.'
  if (value === '.' || value === '..' || containsUnsafePathCharacter(value)) {
    return 'Version must be a single safe path segment.'
  }
  return ''
})

const filesError = computed(() => {
  if (uploadItems.value.length === 0) return 'Add at least one file.'
  const names = uploadItems.value.map(item => item.filename.trim())
  if (names.some(name => !name)) return 'Every file needs a filename.'
  if (names.some(name => name === '.' || name === '..' || containsUnsafePathCharacter(name))) {
    return 'Filenames must be single safe path segments.'
  }
  if (new Set(names).size !== names.length) return 'Filenames must be unique within the version.'
  return ''
})

const targetError = computed(() => {
  if (!selectedTarget.value) return 'Select a raw repository or create one.'
  if (isNewRepository.value && !selectedNamespaceId.value) return 'Select a namespace.'
  if (!isNewRepository.value && !loadingTargets.value && !selectedRepository.value) {
    return 'The selected raw repository is not available.'
  }
  return repositoryNameError.value
})

const canUpload = computed(() =>
  !uploading.value
  && !loadingTargets.value
  && !targetError.value
  && !versionError.value
  && !filesError.value,
)
const uploadButtonLabel = computed(() => {
  const count = uploadItems.value.length
  return count === 0 ? 'Upload files' : `Upload ${count} file${count === 1 ? '' : 's'}`
})

watch([selectedNamespaceId, newRepositoryName], () => {
  createdRepository.value = null
})

function addFiles(files: File[]) {
  uploadItems.value.push(...files.map(file => ({
    id: nextItemId++,
    file,
    filename: file.name,
    status: 'ready' as const,
  })))
}

function removeFile(id: number) {
  uploadItems.value = uploadItems.value.filter(item => item.id !== id)
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

async function resolveTarget(): Promise<UploadTarget> {
  if (selectedRepository.value?.namespace) {
    return {
      id: selectedRepository.value.id,
      name: selectedRepository.value.name,
      namespace: selectedRepository.value.namespace,
    }
  }

  const namespace = namespaces.value.find(item => item.id === selectedNamespaceId.value)
  if (!namespace) throw new Error('Select a namespace')
  const name = newRepositoryName.value.trim()
  if (createdRepository.value
    && createdRepository.value.name === name
    && createdRepository.value.namespace.id === namespace.id) {
    return createdRepository.value
  }

  const result = await gqlMutation<{
    artifactsAdmin: { createRepository: { id: string; namespaceId: string; name: string; type: string } }
  }>(createRepositoryGql, {
    namespaceId: namespace.id,
    name,
    type: 'raw',
  })
  const repository = result.artifactsAdmin.createRepository
  createdRepository.value = { id: repository.id, name: repository.name, namespace }
  return createdRepository.value
}

async function upload() {
  if (!canUpload.value) return
  uploading.value = true
  uploadError.value = ''
  uploadItems.value.forEach(item => { item.status = 'ready' })

  try {
    if (!authState) throw new Error('Authentication is unavailable')
    const target = await resolveTarget()
    const authHeaders = await authState.auth.getAuthHeaders()

    for (const item of uploadItems.value) {
      item.status = 'uploading'
      await uploadRawArtifactFile(
        artifactsBase,
        {
          namespace: target.namespace.name,
          repository: target.name,
          version: version.value.trim(),
          filename: item.filename.trim(),
        },
        item.file,
        authHeaders,
      )
      item.status = 'uploaded'
    }

    const count = uploadItems.value.length
    clearStagedFiles()
    toast.success(`${count} raw artifact file${count === 1 ? '' : 's'} uploaded`)
    await router.push(`/artifacts/repositories/${target.id}`)
  } catch (error: unknown) {
    uploadItems.value.forEach(item => {
      if (item.status === 'uploading') item.status = 'failed'
    })
    uploadError.value = error instanceof Error ? error.message : String(error)
    toast.error(`Failed to upload raw artifacts: ${uploadError.value}`)
  } finally {
    uploading.value = false
  }
}

onMounted(() => {
  if (stagedFiles.value.length > 0) addFiles(stagedFiles.value)
  clearStagedFiles()
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Artifacts', 'Repositories', 'Upload Raw Artifacts')"
        title="Upload Raw Artifacts"
        subtitle="Choose the destination and version for the dropped files"
      >
        <template #actions>
          <Button size="sm" @click="router.back()">Cancel</Button>
          <Button
            size="sm"
            primary
            icon="upload"
            :accent="accent"
            :disabled="!canUpload"
            @click="upload"
          >
            {{ uploading ? 'Uploading…' : uploadButtonLabel }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <div class="upload-stack">
      <SectionCard
        title="Destination"
        subtitle="Use an existing raw repository or create one in an existing namespace"
        padded
      >
        <div class="form-stack">
          <Select
            v-model="selectedTarget"
            label="Raw Repository"
            placeholder="Select a raw repository"
            searchable
            :loading="loadingTargets"
            :options="repositoryOptions"
            :accent="accent"
          />

          <div v-if="isNewRepository" class="target-grid">
            <Select
              v-model="selectedNamespaceId"
              label="Namespace"
              placeholder="Select a namespace"
              searchable
              :options="namespaceOptions"
              :accent="accent"
            />
            <TextInput
              v-model="newRepositoryName"
              label="Repository Name"
              placeholder="release-files"
              mono
            />
          </div>
          <p v-if="targetError" class="field-error">{{ targetError }}</p>
        </div>
      </SectionCard>

      <SectionCard
        title="Version"
        subtitle="All files in this upload are stored together under this version"
        padded
      >
        <div class="narrow-field">
          <TextInput
            v-model="version"
            label="Version"
            placeholder="1.0.0"
            mono
          />
          <p v-if="versionError" class="field-error">{{ versionError }}</p>
        </div>
      </SectionCard>

      <SectionCard
        title="Files"
        :subtitle="`${uploadItems.length} file${uploadItems.length === 1 ? '' : 's'} selected`"
        padded
      >
        <RawArtifactDropZone
          compact
          title="Add raw artifact files"
          description="Drop more files here or click to browse"
          :disabled="uploading"
          @files="addFiles"
        />

        <div v-if="uploadItems.length" class="file-list">
          <div v-for="item in uploadItems" :key="item.id" class="file-row">
            <div class="file-meta">
              <Icon name="file" :size="15" color="var(--fg-3)" />
              <span class="source-name">{{ item.file.name }}</span>
              <span>{{ formatSize(item.file.size) }}</span>
              <span v-if="item.status !== 'ready'" class="file-status" :class="item.status">{{ item.status }}</span>
            </div>
            <TextInput
              v-model="item.filename"
              label="Artifact Filename"
              mono
              :disabled="uploading"
            />
            <button
              type="button"
              class="remove-file"
              :disabled="uploading"
              :aria-label="`Remove ${item.file.name}`"
              @click="removeFile(item.id)"
            >
              <Icon name="x" :size="13" color="var(--fg-3)" />
            </button>
          </div>
        </div>
        <p v-if="filesError" class="field-error">{{ filesError }}</p>
        <p v-if="uploadError" class="upload-error">{{ uploadError }}</p>
      </SectionCard>
    </div>
  </PageShell>
</template>

<style scoped>
.upload-stack { display: flex; flex-direction: column; gap: 14px; }
.form-stack { display: flex; max-width: 760px; flex-direction: column; gap: 14px; }
.target-grid { display: grid; grid-template-columns: minmax(220px, 1fr) minmax(220px, 1fr); gap: 14px; }
.narrow-field { display: flex; max-width: 380px; flex-direction: column; gap: 8px; }

.file-list { display: flex; flex-direction: column; gap: 8px; margin-top: 14px; }
.file-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(240px, 1.2fr) 28px;
  align-items: end;
  gap: 14px;
  padding: 12px;
  border: 1px solid color-mix(in oklch, var(--fg-3) 14%, transparent);
  border-radius: var(--r-xs);
}

.file-meta { display: flex; min-width: 0; align-items: center; gap: 7px; color: var(--fg-3); font-size: 11.5px; }
.source-name { overflow: hidden; color: var(--fg-1); font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.file-status { font-size: 10px; font-weight: 600; text-transform: uppercase; }
.file-status.uploading { color: var(--accent, #c084fc); }
.file-status.uploaded { color: var(--ok, #34d99a); }
.file-status.failed { color: var(--danger, #f26d6d); }

.remove-file {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: var(--r-xs);
  cursor: pointer;
}
.remove-file:hover { background: color-mix(in oklch, var(--danger, #f26d6d) 10%, transparent); }
.remove-file:disabled { cursor: not-allowed; opacity: 0.5; }

.field-error,
.upload-error { margin: 9px 0 0; color: var(--danger, #f26d6d); font-size: 11.5px; line-height: 1.5; }
.upload-error { padding: 10px 12px; border-radius: var(--r-xs); background: color-mix(in oklch, var(--danger, #f26d6d) 8%, transparent); }

@media (max-width: 760px) {
  .target-grid,
  .file-row { grid-template-columns: 1fr; }
  .remove-file { justify-self: end; }
}
</style>
