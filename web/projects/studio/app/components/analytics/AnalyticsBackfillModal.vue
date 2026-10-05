<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'

withDefaults(defineProps<{ accent?: string }>(), { accent: '#a78bff' })

const emit = defineEmits<{ close: [] }>()

const { mutation: gqlMutation, query: gqlQuery } = useGraphQL()
const toast = useToast()

const auth = import.meta.client ? useAuth() : null
const profile = auth?.profile ?? null

// Shared author resolution — same source as the edit page and GitFilePicker so a
// backfill commit looks identical to a manual UI edit commit from the same user.
const commitAuthor = computed(() => resolveGitCommitAuthor(profile?.value))

interface Repo { id: string; name: string }
const repos = ref<Repo[]>([])
const selectedRepoId = ref('')
const loadingRepos = ref(false)
const running = ref(false)

const repoOptions = computed(() => repos.value.map(r => ({ value: r.id, label: r.name })))

watch(() => profile?.value?.id, async (ownerId) => {
  if (!ownerId) return
  loadingRepos.value = true
  try {
    const result = await gqlQuery<{ git: { repositories: Repo[] } }>(gql`
      query AnalyticsBackfillRepos($ownerId: UUID!) {
        git { repositories(ownerId: $ownerId) { id name } }
      }
    `, { ownerId })
    repos.value = result.git?.repositories ?? []
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to load repositories')
  } finally {
    loadingRepos.value = false
  }
}, { immediate: true })

const pushAllGql = gql`
  mutation PushAllAnalyticsQueries($repositoryId: UUID!, $authorName: String!, $authorEmail: String!) {
    analytics { queries { pushAllToGit(repositoryId: $repositoryId, authorName: $authorName, authorEmail: $authorEmail) } }
  }
`

async function run() {
  if (!selectedRepoId.value) return
  running.value = true
  try {
    const result = await gqlMutation<{ analytics: { queries: { pushAllToGit: number } } }>(
      pushAllGql,
      {
        repositoryId: selectedRepoId.value,
        authorName: commitAuthor.value.authorName,
        authorEmail: commitAuthor.value.authorEmail,
      },
    )
    const count = result.analytics.queries.pushAllToGit
    toast.success(count === 0 ? 'No git-backed queries to backfill' : `Backfilled ${count} ${count === 1 ? 'query' : 'queries'}`)
    emit('close')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Backfill failed')
  } finally {
    running.value = false
  }
}
</script>

<template>
  <Modal
    title="Backfill @bosca-query blocks"
    icon="git-branch"
    :accent="accent"
    @close="emit('close')">
    <div class="form-stack">
      <p class="hint">
        Pushes every git-backed analytics query in the chosen repository to its linked file,
        regenerating the <span class="mono">@bosca-query</span> metadata block from the current
        database parameter set. Safe to re-run.
      </p>
      <Select
        v-model="selectedRepoId"
        label="Repository"
        :options="repoOptions"
        :disabled="loadingRepos || running"
        :placeholder="loadingRepos ? 'Loading…' : 'Pick a repository'"
      />
    </div>
    <template #footer>
      <span class="spacer" />
      <Button size="sm" :disabled="running" @click="emit('close')">Cancel</Button>
      <Button
        size="sm"
        primary
        :accent="accent"
        :disabled="!selectedRepoId || running"
        @click="run"
      >
        {{ running ? 'Running…' : 'Run backfill' }}
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.form-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.hint {
  font-size: 12px;
  color: var(--fg-2);
  line-height: 1.5;
}

.mono {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11px;
  background: var(--bg-1);
  padding: 1px 4px;
  border-radius: 3px;
}

.spacer { flex: 1; }
</style>
