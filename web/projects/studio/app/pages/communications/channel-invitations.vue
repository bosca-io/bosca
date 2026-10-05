<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()
const route = useRoute()
const requestedId = computed(() => typeof route.query.invitationId === 'string' ? route.query.invitationId : null)

interface Invitation {
  id: string
  status: string
  created: string
  channel: { id: string; name: string; type: string } | null
  inviter: { id: string; name: string }
}

const invitationsQuery = gql`
  query PendingChannelInvitations {
    profiles {
      current {
        id
        name
        chat {
          incomingInvitations(limit: 100) {
            id
            status
            created
            channel { id name type }
            inviter { id name }
          }
        }
      }
    }
  }
`

const acceptMutation = gql`
  mutation AcceptChannelInvitation($invitationId: UUID!) {
    chat { acceptChannelInvitation(invitationId: $invitationId) { id status } }
  }
`

const declineMutation = gql`
  mutation DeclineChannelInvitation($invitationId: UUID!) {
    chat { declineChannelInvitation(invitationId: $invitationId) { id status } }
  }
`

const { data, refresh, status } = useAsyncQuery<{
  profiles: { current: Array<{ id: string; chat: { incomingInvitations: Invitation[] } | null }> | null }
}>('pending-channel-invitations', invitationsQuery, undefined, { server: false })

const rows = computed(() => (data.value?.profiles.current ?? []).flatMap(profile =>
  (profile.chat?.incomingInvitations ?? []).map(invitation => ({ invitation })),
).sort((a, b) => Number(b.invitation.id === requestedId.value) - Number(a.invitation.id === requestedId.value)))

async function respond(invitation: Invitation, accept: boolean) {
  try {
    await mutation(accept ? acceptMutation : declineMutation, { invitationId: invitation.id })
    if (accept && invitation.channel) {
      await navigateTo(`/communications/channels?channelId=${invitation.channel.id}`)
      return
    }
    await refresh()
  }
  catch (error: unknown) {
    toast.error(error instanceof Error
      ? error.message
      : `Failed to ${accept ? 'accept' : 'decline'} invitation`)
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader title="Channel invitations" subtitle="Review invitations to join chat channels" :accent="accent" />
    </template>
    <SectionCard title="Pending invitations">
      <div v-if="status === 'pending'" class="empty">Loading invitations…</div>
      <div v-else-if="rows.length === 0" class="empty">You have no pending channel invitations.</div>
      <div v-else class="rows">
        <div
          v-for="row in rows"
          :key="row.invitation.id"
          class="row"
          :class="{ requested: row.invitation.id === requestedId }">
          <div>
            <div class="name">{{ row.invitation.channel?.name || 'Channel invitation' }}</div>
            <div class="detail">Invited by {{ row.invitation.inviter.name }}</div>
          </div>
          <div class="actions">
            <button class="secondary" @click="respond(row.invitation, false)">Decline</button>
            <button class="primary" @click="respond(row.invitation, true)">Accept</button>
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
