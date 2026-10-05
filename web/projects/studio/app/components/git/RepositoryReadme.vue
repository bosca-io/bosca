<script setup lang="ts">
import gql from 'graphql-tag'
import type { RepositoryTreeEntry } from '~/utils/gitReadme'
import { selectRepositoryReadme } from '~/utils/gitReadme'

interface ReadmeBlob {
  content: string | null
  isBinary: boolean
}

const props = defineProps<{
  repositoryId: string
  gitRef: string
  entries: RepositoryTreeEntry[]
}>()

const emit = defineEmits<{
  open: [path: string]
}>()

const { query } = useGraphQL()
const readmeEntry = computed(() => selectRepositoryReadme(props.entries))
const readmeContent = ref<string | null>(null)
const readmeLoading = ref(false)
const readmeError = ref<string | null>(null)
let requestSequence = 0

watch(
  [() => props.repositoryId, () => props.gitRef, readmeEntry],
  async ([repositoryId, gitRef, entry]) => {
    const request = ++requestSequence
    readmeContent.value = null
    readmeError.value = null
    if (!repositoryId || !gitRef || !entry) {
      readmeLoading.value = false
      return
    }

    readmeLoading.value = true
    try {
      const result = await query<{ git: { blob: ReadmeBlob | null } }>(gql`
        query RepositoryReadme($repositoryId: UUID!, $ref: String!, $path: String!) {
          git {
            blob(repositoryId: $repositoryId, ref: $ref, path: $path) {
              content
              isBinary
            }
          }
        }
      `, { repositoryId, ref: gitRef, path: entry.path })
      if (request !== requestSequence) return
      const blob = result.git?.blob
      readmeContent.value = !blob?.isBinary && blob?.content?.trim() ? blob.content : null
    } catch (error) {
      if (request !== requestSequence) return
      readmeError.value = error instanceof Error ? error.message : 'README could not be loaded'
    } finally {
      if (request === requestSequence) readmeLoading.value = false
    }
  },
  { immediate: true },
)
</script>

<template>
  <section v-if="readmeEntry && (readmeLoading || readmeError || readmeContent)" class="readme-card">
    <button class="readme-header" type="button" @click="emit('open', readmeEntry.path)">
      <Icon name="bookOpen" :size="14" color="var(--fg-2)" />
      <span>{{ readmeEntry.name }}</span>
    </button>
    <div v-if="readmeLoading" class="readme-state">Loading README…</div>
    <div v-else-if="readmeError" class="readme-state readme-error">{{ readmeError }}</div>
    <!-- Repository Markdown is untrusted; CommonMarkdown renders it with raw HTML
         and MDC component syntax disabled. -->
    <article v-else-if="readmeContent" class="readme-content">
      <CommonMarkdown
        :value="readmeContent"
        :cache-key="`repository-readme-${repositoryId}-${gitRef}-${readmeEntry.path}`" />
    </article>
  </section>
</template>

<style scoped>
.readme-card {
  margin-top: 16px;
  overflow: hidden;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 10px;
}

.readme-header {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 16px;
  color: var(--fg-1);
  font-size: 12.5px;
  font-weight: 600;
  text-align: left;
  border-bottom: 1px solid var(--line);
}

.readme-header:hover { background: var(--bg-2); }

.readme-state { padding: 28px 20px; color: var(--fg-3); font-size: 12.5px; text-align: center; }
.readme-error { color: var(--err); }

/* Prose rules live in CommonMarkdown; this only sets the README's scale and padding. */
.readme-content {
  padding: 24px 28px 32px;
  color: var(--fg-1);
  font-size: 14px;
  line-height: 1.65;
}

@media (max-width: 700px) {
  .readme-content { padding: 18px 16px 24px; }
}
</style>
