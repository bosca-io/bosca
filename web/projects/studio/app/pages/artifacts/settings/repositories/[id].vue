<script setup lang="ts">
import gql from 'graphql-tag'
import ArtifactRepositorySync from '~/components/artifacts/ArtifactRepositorySync.vue'
import ArtifactRepositoryPublication from '~/components/artifacts/ArtifactRepositoryPublication.vue'

definePageMeta({ middleware: 'artifacts-admin' })
const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { isAdmin } = usePersonas()
const { useAsyncQuery } = useGraphQL()
const repositoryId = computed(() => route.params.id as string)
const { data, status, error, refresh } = useAsyncQuery<{
  artifactsAdmin: { repository: { id: string; name: string; type: string; namespace: { name: string } | null } | null }
}>('artifact-repository-settings', gql`
  query ArtifactRepositorySettings($id: UUID!) {
    artifactsAdmin { repository(id: $id) { id name type namespace { name } } }
  }
`, { id: repositoryId }, { server: false })
const repository = computed(() => data.value?.artifactsAdmin.repository)
const repositoryPath = computed(() => repository.value ? `${repository.value.namespace?.name ?? '?'}/${repository.value.name}` : 'Repository')
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader :accent="accent" :breadcrumb="buildBreadcrumb('Artifacts', 'Repositories', repositoryPath, 'Settings')" :title="`${repositoryPath} Settings`">
        <template #actions>
          <Button size="sm" @click="router.push(`/artifacts/repositories/${repositoryId}`)">Back to repository</Button>
        </template>
      </PageHeader>
    </template>
    <div v-if="isAdmin" class="content-stack">
      <p v-if="status === 'pending' && !repository">Loading repository…</p>
      <template v-else-if="error">
        <p role="alert">Could not load repository settings.</p>
        <Button @click="refresh()">Retry</Button>
      </template>
      <template v-else-if="repository">
        <ArtifactRepositorySync v-if="repository.type === 'docker'" :key="repository.id" :repository-id="repository.id" />
        <ArtifactRepositoryPublication v-else-if="repository.type === 'raw'" :key="repository.id" :repository-id="repository.id" />
        <p v-else>GitHub syncing is available for Docker and raw artifact repositories.</p>
      </template>
      <p v-else>Repository not found.</p>
    </div>
  </PageShell>
</template>

<style scoped>
.content-stack { display: flex; flex-direction: column; gap: 14px; }
</style>
