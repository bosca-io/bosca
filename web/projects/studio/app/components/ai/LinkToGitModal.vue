<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'

const props = withDefaults(defineProps<{
  /** Which AGENT_PROJECT entity is being linked. */
  entityType: 'AGENT' | 'AGENT_TOOL' | 'MCP_SERVER' | 'PROMPT' | 'AGENT_RESOURCE'
  /** UUID of the DB row to link + push. */
  entityId: string
  /** Key of the entity. Used to pre-fill the suggested git path. */
  entityKey: string
  accent?: string
}>(), { accent: '#a78bff' })

const emit = defineEmits<{ close: []; linked: [] }>()

const { query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const auth = import.meta.client ? useAuth() : null
const profile = auth?.profile ?? null
const commitAuthor = computed(() => resolveGitCommitAuthor(profile?.value))

// Directory prefix per entity type. Matches `AgentRepoLayout` in core-ai.
const DIRECTORY: Record<typeof props.entityType, string> = {
  AGENT: 'agents',
  AGENT_TOOL: 'tools',
  MCP_SERVER: 'mcp-servers',
  PROMPT: 'prompts',
  AGENT_RESOURCE: 'resources',
}

interface Repo { id: string; name: string }
const repos = ref<Repo[]>([])
const selectedRepoId = ref('')
const gitPath = ref(`${DIRECTORY[props.entityType]}/${props.entityKey}.md`)
const loadingRepos = ref(false)
const running = ref(false)

const repoOptions = computed(() => repos.value.map(r => ({ value: r.id, label: r.name })))

onMounted(async () => {
  loadingRepos.value = true
  try {
    const result = await gqlQuery<{ git: { repositories: Repo[] } }>(gql`
      query AgentProjectRepos {
        git { repositories(contentType: AGENT_PROJECT) { id name } }
      }
    `, {})
    repos.value = result.git?.repositories ?? []
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to load repositories')
  } finally {
    loadingRepos.value = false
  }
})

const linkGql = gql`
  mutation LinkAgentRepoEntity(
    $repositoryId: UUID!
    $entries: [AgentRepoBackfillEntry!]!
    $authorName: String!
    $authorEmail: String!
  ) {
    ai {
      agentRepo {
        backfill(
          repositoryId: $repositoryId
          entries: $entries
          authorName: $authorName
          authorEmail: $authorEmail
        ) {
          ok
          commitSha
          validationErrors { path message }
          errorMessage
        }
      }
    }
  }
`

interface BackfillResult {
  ok: boolean
  commitSha: string | null
  validationErrors: Array<{ path: string; message: string }> | null
  errorMessage: string | null
}

async function run() {
  if (!selectedRepoId.value || !gitPath.value.trim()) return
  running.value = true
  try {
    const result = await gqlMutation<{ ai: { agentRepo: { backfill: BackfillResult } } }>(
      linkGql,
      {
        repositoryId: selectedRepoId.value,
        entries: [
          {
            entityType: props.entityType,
            entityId: props.entityId,
            gitPath: gitPath.value.trim(),
          },
        ],
        authorName: commitAuthor.value.authorName,
        authorEmail: commitAuthor.value.authorEmail,
      },
    )
    const r = result.ai.agentRepo.backfill
    if (r.ok) {
      toast.success(`Linked to Git at ${gitPath.value}`)
      emit('linked')
      emit('close')
    } else if (r.validationErrors?.length) {
      toast.error(`Validation failed: ${r.validationErrors.map(e => `${e.path}: ${e.message}`).join('; ')}`)
    } else {
      toast.error(r.errorMessage ?? 'Failed to link')
    }
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to link')
  } finally {
    running.value = false
  }
}
</script>

<template>
  <Modal
    title="Link to Git repository"
    icon="git-branch"
    :accent="accent"
    @close="emit('close')">
    <div class="form-stack">
      <p class="hint">
        Serializes this entity to the chosen AGENT_PROJECT repository at the path below
        and commits it. The commit is authored as you. After linking, future edits will
        push to the same path; pulls from Git will update the database row.
      </p>
      <Select
        v-model="selectedRepoId"
        label="Repository"
        :options="repoOptions"
        :disabled="loadingRepos || running"
        :placeholder="loadingRepos ? 'Loading…' : 'Pick an AGENT_PROJECT repository'" />
      <TextInput
        v-model="gitPath"
        label="Git path"
        mono
        :disabled="running"
        placeholder="agents/foo.md" />
    </div>
    <template #footer>
      <span class="spacer" />
      <Button size="sm" :disabled="running" @click="emit('close')">Cancel</Button>
      <Button
        size="sm"
        primary
        :accent="accent"
        :disabled="!selectedRepoId || !gitPath.trim() || running"
        @click="run">
        {{ running ? 'Linking…' : 'Link & push' }}
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
.spacer { flex: 1; }
</style>
