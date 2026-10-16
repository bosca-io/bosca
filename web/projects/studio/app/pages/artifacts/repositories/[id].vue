<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { isAdmin } = usePersonas()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const { stage: stageRawArtifactFiles } = useRawArtifactUploadStaging()

// Artifact registry endpoints live on a separate domain from the studio API, so
// the copyable download URLs are absolute, built against this base (trailing slash trimmed).
const artifactsBase = (useRuntimeConfig().public.artifactsUrl ?? '').replace(/\/+$/, '')

const repoId = computed(() => route.params.id as string)

const repoGql = gql`
  query GetArtifactRepository($id: UUID!, $versionsLimit: Int!, $versionsOffset: Long!, $tagsLimit: Int!, $tagsOffset: Long!) {
    artifactsAdmin {
      repository(id: $id) {
        id
        namespaceId
        name
        type
        created
        modified
        namespace {
          id
          name
        }
        versionCount
        versions(limit: $versionsLimit, offset: $versionsOffset) {
          id
          repositoryId
          version
          metadata
          created
          blobs {
            digest
            role
            filename
            mediaType
            size
          }
        }
        tagCount
        tags(limit: $tagsLimit, offset: $tagsOffset) {
          id
          repositoryId
          name
          manifestDigest
          created
          modified
        }
      }
    }
  }
`

const deleteVersionGql = gql`
  mutation DeleteArtifactVersion($id: UUID!) {
    artifactsAdmin { deleteVersion(id: $id) }
  }
`

const deleteTagGql = gql`
  mutation DeleteArtifactTag($repositoryId: UUID!, $name: String!) {
    artifactsAdmin { deleteTag(repositoryId: $repositoryId, name: $name) }
  }
`

const deleteRepoGql = gql`
  mutation DeleteArtifactRepository($id: UUID!) {
    artifactsAdmin { deleteRepository(id: $id) }
  }
`

interface Blob { digest: string; role: string; filename: string | null; mediaType: string; size: number }
interface Version { id: string; repositoryId: string; version: string; metadata: unknown; created: string; blobs: Blob[] }
interface BlobDownload { url: string; filename: string }
interface BlobRow extends Blob { download: BlobDownload | null }
interface VersionRow extends Version { blobs: BlobRow[] }
interface Tag { id: string; repositoryId: string; name: string; manifestDigest: string; created: string; modified: string }
interface Repo {
  id: string; namespaceId: string; name: string; type: string; created: string; modified: string
  namespace: { id: string; name: string } | null
  versionCount: number; versions: Version[]
  tagCount: number; tags: Tag[]
}

const versionsLimit = ref(15)
const versionsOffset = ref(0)
const tagsLimit = ref(15)
const tagsOffset = ref(0)

const { data, status, refresh } = useAsyncQuery<{
  artifactsAdmin: { repository: Repo | null }
}>('artifact-repo-detail', repoGql, {
  id: repoId,
  versionsLimit,
  versionsOffset,
  tagsLimit,
  tagsOffset,
}, { server: false })

const repo = computed(() => data.value?.artifactsAdmin?.repository)
const versions = computed(() => repo.value?.versions ?? [])
const tags = computed(() => repo.value?.tags ?? [])
const isLoading = computed(() => status.value === 'pending')
const repoPath = computed(() => repo.value ? `${repo.value.namespace?.name ?? '?'}/${repo.value.name}` : '…')

const versionCount = computed(() => repo.value?.versionCount ?? 0)
const versionTotalPages = computed(() => Math.ceil(versionCount.value / versionsLimit.value))
const versionPage = computed(() => Math.floor(versionsOffset.value / versionsLimit.value) + 1)

function goToVersionPage(page: number) {
  const clamped = Math.max(1, Math.min(page, versionTotalPages.value))
  versionsOffset.value = (clamped - 1) * versionsLimit.value
}

const tagCount = computed(() => repo.value?.tagCount ?? 0)
const tagTotalPages = computed(() => Math.ceil(tagCount.value / tagsLimit.value))
const tagPage = computed(() => Math.floor(tagsOffset.value / tagsLimit.value) + 1)

function goToTagPage(page: number) {
  const clamped = Math.max(1, Math.min(page, tagTotalPages.value))
  tagsOffset.value = (clamped - 1) * tagsLimit.value
}

// Deleting the last version/tag on the final page leaves its offset past the end;
// snap back to the new last page.
watch(versionCount, count => {
  if (count > 0 && versionsOffset.value >= count) goToVersionPage(versionTotalPages.value)
})
watch(tagCount, count => {
  if (count > 0 && tagsOffset.value >= count) goToTagPage(tagTotalPages.value)
})

const expandedVersions = ref<Set<string>>(new Set())
function toggleVersion(id: string) {
  const s = new Set(expandedVersions.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  expandedVersions.value = s
}

const deleteVersionTarget = ref<Version | null>(null)
const deleteVersionLoading = ref(false)

async function confirmDeleteVersion() {
  if (!deleteVersionTarget.value) return
  deleteVersionLoading.value = true
  try {
    await gqlMutation(deleteVersionGql, { id: deleteVersionTarget.value.id })
    deleteVersionTarget.value = null
    toast.success('Version deleted')
    refresh()
  } catch { toast.error('Failed to delete version') }
  finally { deleteVersionLoading.value = false }
}

const deleteTagTarget = ref<Tag | null>(null)
const deleteTagLoading = ref(false)

async function confirmDeleteTag() {
  if (!deleteTagTarget.value || !repo.value) return
  deleteTagLoading.value = true
  try {
    await gqlMutation(deleteTagGql, { repositoryId: repo.value.id, name: deleteTagTarget.value.name })
    deleteTagTarget.value = null
    toast.success('Tag deleted')
    refresh()
  } catch { toast.error('Failed to delete tag') }
  finally { deleteTagLoading.value = false }
}

const showDeleteRepo = ref(false)
const deleteRepoLoading = ref(false)

async function confirmDeleteRepo() {
  deleteRepoLoading.value = true
  try {
    await gqlMutation(deleteRepoGql, { id: repoId.value })
    toast.success('Repository deleted')
    if (repo.value?.namespace) router.push(`/artifacts/namespaces/${repo.value.namespace.id}`)
    else router.push('/artifacts/repositories')
  } catch { toast.error('Failed to delete repository') }
  finally { deleteRepoLoading.value = false }
}

function formatDate(d: string) {
  if (!d) return '—'
  return new Date(d).toLocaleString()
}

function formatSize(bytes: number | null) {
  if (bytes == null) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

function truncateDigest(d: string) {
  if (!d) return '—'
  return d.length > 19 ? d.substring(0, 19) + '…' : d
}

/**
 * Builds the absolute registry URL for a single blob, or returns null when the
 * blob has no individually addressable URL for its repository type.
 *
 * Each artifact type exposes a different registry endpoint, and not every blob
 * has a route: maven/raw address every blob by filename, npm only the
 * `tarball`-role blob, and helm only the `chart`-role blob. Returning null for
 * the rest keeps us from offering a URL that would 404.
 *
 * The URL is absolute against `artifactsBase` (the registry's public host) so it
 * is usable as-is once copied — the user pulls it with their package manager or
 * an authenticated request, rather than the browser navigating to it. Copying
 * (instead of a direct download link) avoids the cross-origin auth and
 * `Content-Disposition` problems of fetching a private blob straight from a `<a>`.
 */
function blobDownload(v: Version, blob: Blob): BlobDownload | null {
  const r = repo.value
  if (!r || !r.namespace) return null
  const ns = r.namespace.name
  const enc = encodeURIComponent
  let path: string | null = null
  let filename = blob.filename ?? ''
  switch (r.type) {
    case 'maven': {
      // repo.name is stored as `groupId.artifactId`; the maven layout turns
      // every dot (both inside the groupId and at the artifactId boundary) into
      // a slash, so a plain dot→slash replacement reproduces the exact path.
      if (!blob.filename) return null
      const layout = r.name.split('.').map(enc).join('/')
      path = `/maven/${enc(ns)}/${layout}/${enc(v.version)}/${enc(blob.filename)}`
      break
    }
    case 'npm': {
      if (blob.role !== 'tarball') return null
      filename = blob.filename || `${r.name}-${v.version}.tgz`
      // namespace.name is the literal `_unscoped` for unscoped packages, or the
      // `@scope` (with the `@`) for scoped ones; the `@` must stay literal so the
      // backend route matches, hence it is not percent-encoded.
      path = ns === '_unscoped'
        ? `/npm/${enc(r.name)}/-/${enc(filename)}`
        : `/npm/@${enc(ns.startsWith('@') ? ns.slice(1) : ns)}/${enc(r.name)}/-/${enc(filename)}`
      break
    }
    case 'raw': {
      if (!blob.filename) return null
      path = `/raw/${enc(ns)}/${enc(r.name)}/${enc(v.version)}/${enc(blob.filename)}`
      break
    }
    case 'helm': {
      if (blob.role !== 'chart') return null
      filename = `${r.name}-${v.version}.tgz`
      path = `/helm/${enc(ns)}/charts/${enc(r.name)}/${enc(v.version)}`
      break
    }
    case 'ml': {
      // An ml version holds exactly one tar blob in the "model" role; the GET
      // route serves it without a per-file path segment.
      if (blob.role !== 'model') return null
      filename = blob.filename || `${r.name}-${v.version}.tar.gz`
      path = `/ml/${enc(ns)}/${enc(r.name)}/${enc(v.version)}`
      break
    }
    default:
      return null
  }
  return { url: `${artifactsBase}${path}`, filename }
}

/** Copies a blob's registry URL to the clipboard for use with a package manager or authenticated request. */
async function copyBlobUrl(url: string) {
  try {
    await navigator.clipboard.writeText(url)
    toast.success('Download URL copied')
  } catch {
    toast.error('Failed to copy URL')
  }
}

const versionRows = computed<VersionRow[]>(() =>
  versions.value.map(v => ({
    ...v,
    blobs: v.blobs.map(blob => ({ ...blob, download: blobDownload(v, blob) })),
  })),
)

const typeColors: Record<string, string> = {
  docker: '#5ec5ff',
  helm: '#0f7fff',
  maven: '#ffb547',
  npm: '#34d99a',
  raw: '#c084fc',
  ml: '#f472b6',
}

async function startRawUpload(files: File[]) {
  const repository = repo.value
  if (!repository || repository.type !== 'raw') return
  stageRawArtifactFiles(files)
  await router.push({
    path: '/artifacts/repositories/upload',
    query: { repositoryId: repository.id },
  })
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Artifacts', 'Repositories', repoPath)"
        :title="repoPath"
        :subtitle="repo ? `${versionCount} versions · ${tagCount} tags` : undefined"
      >
        <template #actions>
          <Button
            v-if="isAdmin && repo && ['docker', 'raw'].includes(repo.type)"
            size="sm"
            icon="settings"
            @click="router.push(`/artifacts/settings/repositories/${repo.id}`)">Settings</Button>
          <Button size="sm" icon="trash" @click="showDeleteRepo = true">Delete</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !repo" class="loading-placeholder">
      <Icon name="refresh" :size="16" color="var(--fg-4)" />
      <span>Loading repository…</span>
    </div>

    <div v-else-if="repo" class="content-stack">
      <SectionCard title="Details">
        <div class="detail-grid">
          <div class="detail-item">
            <span class="detail-label">Namespace</span>
            <a class="detail-link" @click="router.push(`/artifacts/namespaces/${repo.namespace?.id}`)">{{ repo.namespace?.name }}</a>
          </div>
          <div class="detail-item">
            <span class="detail-label">Repository</span>
            <span class="detail-value mono">{{ repo.name }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">Type</span>
            <span class="type-badge" :style="{ color: typeColors[repo.type] || 'var(--fg-2)', background: `color-mix(in oklch, ${typeColors[repo.type] || 'var(--fg-3)'} 12%, transparent)` }">{{ repo.type }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">ID</span>
            <span class="detail-value mono muted">{{ repo.id }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">Created</span>
            <span class="detail-value">{{ formatDate(repo.created) }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">Modified</span>
            <span class="detail-value">{{ formatDate(repo.modified) }}</span>
          </div>
        </div>
      </SectionCard>

      <SectionCard
        v-if="repo.type === 'raw'"
        title="Upload Raw Artifacts"
        subtitle="Drop files to choose their version and artifact filenames"
        padded
      >
        <RawArtifactDropZone @files="startRawUpload" />
      </SectionCard>

      <!-- Tags (Docker only) -->
      <SectionCard v-if="repo.type === 'docker'" :title="`Tags (${tagCount})`">
        <div v-if="tags.length === 0" class="empty-text">No tags.</div>
        <div v-else class="tag-list">
          <div v-for="tag in tags" :key="tag.id" class="tag-row">
            <div class="tag-info">
              <span class="tag-name">{{ tag.name }}</span>
              <span class="tag-digest mono">{{ truncateDigest(tag.manifestDigest) }}</span>
              <span class="tag-date">{{ formatDate(tag.modified || tag.created) }}</span>
            </div>
            <button class="icon-btn danger" @click="deleteTagTarget = tag">
              <Icon name="trash" :size="12" color="var(--err)" />
            </button>
          </div>
        </div>
        <Pagination
          v-if="tagTotalPages > 1"
          :page="tagPage"
          :total-pages="tagTotalPages"
          @prev="goToTagPage(tagPage - 1)"
          @next="goToTagPage(tagPage + 1)"
        />
      </SectionCard>

      <!-- Versions -->
      <SectionCard :title="`Versions (${versionCount})`">
        <div v-if="versions.length === 0" class="empty-text">No versions published.</div>
        <div v-else class="version-list">
          <div v-for="v in versionRows" :key="v.id" class="version-card">
            <div class="version-header" @click="toggleVersion(v.id)">
              <div class="version-info">
                <Icon :name="expandedVersions.has(v.id) ? 'chevron-down' : 'chevron-right'" :size="12" color="var(--fg-3)" />
                <span class="version-label mono">{{ v.version }}</span>
                <span class="version-blobs">{{ v.blobs.length }} blob{{ v.blobs.length !== 1 ? 's' : '' }}</span>
                <span class="version-date">{{ formatDate(v.created) }}</span>
              </div>
              <button class="icon-btn danger" @click.stop="deleteVersionTarget = v">
                <Icon name="trash" :size="12" color="var(--err)" />
              </button>
            </div>

            <div v-if="expandedVersions.has(v.id) && v.blobs.length > 0" class="blob-table-wrap">
              <table class="blob-table">
                <thead>
                  <tr>
                    <th>Role</th>
                    <th>Digest</th>
                    <th>Size</th>
                    <th>Filename</th>
                    <th>Media Type</th>
                    <th class="dl-col"><span class="sr-only">Copy URL</span></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="blob in v.blobs" :key="blob.digest + blob.role">
                    <td><span class="role-badge">{{ blob.role }}</span></td>
                    <td class="mono">{{ truncateDigest(blob.digest) }}</td>
                    <td>{{ formatSize(blob.size) }}</td>
                    <td class="muted">{{ blob.filename || '—' }}</td>
                    <td class="mono muted">{{ blob.mediaType }}</td>
                    <td class="dl-cell">
                      <button
                        v-if="blob.download"
                        type="button"
                        class="dl-link"
                        :title="`Copy URL for ${blob.download.filename}`"
                        :aria-label="`Copy URL for ${blob.download.filename}`"
                        @click.stop="copyBlobUrl(blob.download.url)"
                      >
                        <Icon name="copy" :size="13" color="var(--fg-3)" />
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
        <Pagination
          v-if="versionTotalPages > 1"
          :page="versionPage"
          :total-pages="versionTotalPages"
          @prev="goToVersionPage(versionPage - 1)"
          @next="goToVersionPage(versionPage + 1)"
        />
      </SectionCard>
    </div>

    <div v-else class="loading-placeholder">
      <Icon name="alert" :size="16" color="var(--fg-4)" />
      <span>Repository not found.</span>
    </div>

    <ConfirmModal
      v-if="deleteVersionTarget"
      :title="`Delete version '${deleteVersionTarget.version}'?`"
      subtitle="All associated blobs will be permanently removed."
      :loading="deleteVersionLoading"
      @close="deleteVersionTarget = null"
      @confirm="confirmDeleteVersion" />
    <ConfirmModal
      v-if="deleteTagTarget"
      :title="`Delete tag '${deleteTagTarget.name}'?`"
      subtitle="The underlying manifest will remain until explicitly deleted."
      confirm-label="Remove Tag"
      :loading="deleteTagLoading"
      @close="deleteTagTarget = null"
      @confirm="confirmDeleteTag" />
    <ConfirmModal
      v-if="showDeleteRepo"
      :title="`Delete '${repoPath}'?`"
      subtitle="All versions, tags, and blobs will be permanently removed."
      :loading="deleteRepoLoading"
      @close="showDeleteRepo = false"
      @confirm="confirmDeleteRepo" />
  </PageShell>
</template>

<style scoped>
.content-stack { display: flex; flex-direction: column; gap: 14px; }
.loading-placeholder {
  display: flex; flex-direction: column; align-items: center; gap: 12px;
  padding: 64px; font-size: 13px; color: var(--fg-3);
}
.detail-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 14px; padding: 16px; }
.detail-item { display: flex; flex-direction: column; gap: 3px; }
.detail-label { font-size: 11px; font-weight: 500; color: var(--fg-3); text-transform: uppercase; letter-spacing: 0.05em; }
.detail-value { font-size: 13px; color: var(--fg-1); }
.detail-value.mono, .mono { font-family: var(--font-mono, monospace); font-size: 12px; }
.detail-value.muted, .muted { color: var(--fg-3); }
.detail-link {
  font-size: 13px; color: var(--fg-0); font-weight: 500; cursor: pointer;
  text-decoration: none;
}
.detail-link:hover { text-decoration: underline; }

.type-badge {
  display: inline-block; font-size: 11px; font-weight: 600; padding: 2px 8px;
  border-radius: var(--r-xs); text-transform: capitalize; width: fit-content;
}

.empty-text { font-size: 13px; color: var(--fg-3); padding: 16px; }

/* Tags */
.tag-list { display: flex; flex-direction: column; gap: 2px; padding: 8px; }
.tag-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 10px; border-radius: var(--r-sm);
  transition: background 0.15s;
}
.tag-row:hover { background: color-mix(in oklch, var(--fg-3) 6%, transparent); }
.tag-info { display: flex; align-items: center; gap: 14px; min-width: 0; }
.tag-name { font-size: 13px; font-weight: 600; color: var(--fg-0); }
.tag-digest { font-size: 11.5px; color: var(--fg-3); }
.tag-date { font-size: 11.5px; color: var(--fg-4); }

/* Versions */
.version-list { display: flex; flex-direction: column; gap: 4px; padding: 12px; }
.version-card {
  border: 1px solid var(--line); border-radius: var(--r-sm); overflow: hidden;
}
.version-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 12px; cursor: pointer; transition: background 0.15s;
}
.version-header:hover { background: color-mix(in oklch, var(--fg-3) 5%, transparent); }
.version-info { display: flex; align-items: center; gap: 10px; }
.version-label { font-size: 13px; font-weight: 600; color: var(--fg-0); }
.version-blobs { font-size: 11.5px; color: var(--fg-3); }
.version-date { font-size: 11.5px; color: var(--fg-4); }

/* Blob table */
.blob-table-wrap {
  border-top: 1px solid var(--line); padding: 0;
  background: color-mix(in oklch, var(--bg-2) 50%, transparent);
}
.blob-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.blob-table th {
  text-align: left; padding: 6px 12px; font-size: 11px; font-weight: 500;
  color: var(--fg-3); text-transform: uppercase; letter-spacing: 0.04em;
  border-bottom: 1px solid var(--line);
}
.blob-table td { padding: 6px 12px; color: var(--fg-1); border-bottom: 1px solid color-mix(in oklch, var(--line) 50%, transparent); }
.blob-table tr:last-child td { border-bottom: none; }
.role-badge {
  font-size: 10.5px; font-weight: 600; padding: 1px 6px; border-radius: var(--r-xs);
  color: var(--fg-2); background: color-mix(in oklch, var(--fg-3) 10%, transparent);
}

/* Blob URL copy action */
.blob-table th.dl-col, .blob-table td.dl-cell { width: 1%; text-align: right; white-space: nowrap; }
.dl-link {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px; padding: 0; border: 0; border-radius: var(--r-xs);
  background: none; color: inherit; transition: background 0.15s; cursor: pointer;
}
.dl-link:hover { background: color-mix(in oklch, var(--fg-3) 14%, transparent); }
.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0;
}

/* Icon button */
.icon-btn {
  width: 26px; height: 26px; border-radius: var(--r-xs);
  display: flex; align-items: center; justify-content: center;
  transition: background 0.15s; cursor: pointer; flex-shrink: 0;
}
.icon-btn.danger:hover { background: color-mix(in oklch, var(--err) 14%, transparent); }
</style>
