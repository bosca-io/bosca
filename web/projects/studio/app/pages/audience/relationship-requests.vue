<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()
const route = useRoute()
const requestedId = computed(() => typeof route.query.requestId === 'string' ? route.query.requestId : null)

interface RelationshipRequest {
  id: string
  type: string
  status: string
  created: string
  requester: { id: string; name: string }
}

const requestsQuery = gql`
  query IncomingRelationshipRequests {
    profiles {
      current {
        id
        name
        incomingRelationshipRequests(limit: 100) {
          id
          type
          status
          created
          requester { id name }
        }
      }
    }
  }
`

const approveMutation = gql`
  mutation ApproveRelationshipRequest($requestId: UUID!) {
    profiles { approveRelationshipRequest(requestId: $requestId) { id status } }
  }
`

const declineMutation = gql`
  mutation DeclineRelationshipRequest($requestId: UUID!) {
    profiles { declineRelationshipRequest(requestId: $requestId) { id status } }
  }
`

const { data, refresh, status } = useAsyncQuery<{
  profiles: { current: Array<{ id: string; incomingRelationshipRequests: RelationshipRequest[] }> | null }
}>('incoming-relationship-requests', requestsQuery, undefined, { server: false })

const rows = computed(() => (data.value?.profiles.current ?? []).flatMap(profile =>
  profile.incomingRelationshipRequests.map(request => ({ profileId: profile.id, request })),
).sort((a, b) => Number(b.request.id === requestedId.value) - Number(a.request.id === requestedId.value)))

async function respond(request: RelationshipRequest, approve: boolean) {
  try {
    await mutation(approve ? approveMutation : declineMutation, { requestId: request.id })
    if (approve) {
      await navigateTo(`/audience/profiles/${request.requester.id}`)
      return
    }
    await refresh()
  }
  catch (error: unknown) {
    toast.error(error instanceof Error
      ? error.message
      : `Failed to ${approve ? 'approve' : 'decline'} relationship request`)
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader title="Relationship requests" subtitle="Review incoming profile relationship requests" :accent="accent" />
    </template>
    <SectionCard title="Pending requests">
      <div v-if="status === 'pending'" class="empty">Loading requests…</div>
      <div v-else-if="rows.length === 0" class="empty">You have no pending relationship requests.</div>
      <div v-else class="rows">
        <div
          v-for="row in rows"
          :key="`${row.profileId}-${row.request.id}`"
          class="row"
          :class="{ requested: row.request.id === requestedId }">
          <div>
            <div class="name">{{ row.request.requester.name }}</div>
            <div class="detail">{{ row.request.type }}</div>
          </div>
          <div class="actions">
            <button class="secondary" @click="respond(row.request, false)">Decline</button>
            <button class="primary" @click="respond(row.request, true)">Approve</button>
          </div>
        </div>
      </div>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.rows { display: grid; }
.row { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 1rem; border-bottom: 1px solid var(--border); }
.row.requested { background: color-mix(in srgb, v-bind(accent) 8%, transparent); }
.name { font-weight: 600; }
.detail, .empty { color: var(--fg-3); }
.empty { padding: 1rem; }
.actions { display: flex; gap: .5rem; }
button { border-radius: 6px; padding: .45rem .8rem; cursor: pointer; }
.secondary { background: transparent; border: 1px solid var(--border); color: var(--fg-1); }
.primary { background: v-bind(accent); border: 1px solid v-bind(accent); color: white; }
</style>
