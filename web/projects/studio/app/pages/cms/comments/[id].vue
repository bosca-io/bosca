<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

// A comment is only shown to non-authors when its visibility is PUBLIC and its
// status is APPROVED, so moderators need to control visibility, not just status.
const VISIBILITY_OPTIONS: SelectOption[] = [
  { label: 'Public', value: 'PUBLIC' },
  { label: 'Friends of Friends', value: 'FRIENDS_OF_FRIENDS' },
  { label: 'Friends', value: 'FRIENDS' },
  { label: 'User', value: 'USER' },
  { label: 'System', value: 'SYSTEM' },
]

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const route = useRoute()
const router = useRouter()
const metadataId = computed(() => route.params.id as string)

// The thread renders recursively (CommentThreadNode), so we fetch replies a fixed
// number of levels deep — generous enough for any realistic moderation thread.
// A moderator (content MANAGE) sees every status via the same metadata.comments
// field readers use, so blocked/pending comments are actionable.
const REPLY_DEPTH = 5
const COMMENT_FIELDS = `
  id
  content
  status
  created
  likes
  likedByMe
  pinned
  visibility
  profile { id name }
`
function buildSelection(depth: number): string {
  let inner = ''
  for (let i = 0; i < depth; i++) {
    inner = `replies(limit: 100, offset: 0) { comments { ${COMMENT_FIELDS} ${inner} } }`
  }
  return `${COMMENT_FIELDS} ${inner}`
}
const threadGql = gql(`
  query MetadataCommentThread($id: UUID!, $limit: Int!, $offset: Int!) {
    content {
      metadata(id: $id) {
        id
        name
        version
        comments(limit: $limit, offset: $offset) {
          count
          comments { ${buildSelection(REPLY_DEPTH)} }
        }
        pinned: comments(limit: 100, offset: 0, pinned: true) {
          comments { ${buildSelection(REPLY_DEPTH)} }
        }
      }
    }
  }
`)

const limit = ref(50)
const offset = ref(0)

interface CommentRow {
  id: number
  content: string
  status: string
  created: string
  likes: number
  pinned?: boolean
  visibility: string
  profile: { id: string; name: string } | null
  replies?: { comments: CommentRow[] }
}
interface MetadataResult {
  id: string
  name: string
  version: number
  comments: { count: number; comments: CommentRow[] }
  pinned: { comments: CommentRow[] }
}

const { data, status, refresh } = useAsyncQuery<{ content: { metadata: MetadataResult | null } }>(
  'metadata-comment-thread', threadGql, { id: metadataId, limit, offset },
)

const metadata = computed(() => data.value?.content?.metadata ?? null)
const roots = computed<CommentRow[]>(() => metadata.value?.comments?.comments ?? [])
const count = computed(() => metadata.value?.comments?.count ?? 0)
const version = computed(() => metadata.value?.version ?? 1)

// Pinned comments come from the server's pinned filter and are shown at the top, in
// addition to their original chronological position in the main list (the server returns
// the main list newest-first, so pinned items stay where they were).
const pinnedRoots = computed<CommentRow[]>(() => metadata.value?.pinned?.comments ?? [])

// Add a top-level comment. This page is admin-gated, so the caller is a manager
// and the server honors the visibility chosen here (PUBLIC by default so the
// comment is shown to everyone once approved).
const newComment = ref('')
const newVisibility = ref('PUBLIC')
const adding = ref(false)
const error = ref('')
// Impersonation: this page is admin-gated, so the caller is a manager and may post
// as another profile. Empty = post as yourself. The server records you as the
// impersonator for audit and requires MANAGE (enforced server-side).
const { searchProfiles } = useProfileSearch()
const impersonateId = ref('')
const addGql = gql`
  mutation AddMetadataComment($comment: CommentInput!, $metadataId: UUID!, $metadataVersion: Int!) {
    content { metadata { comments {
      addComment(comment: $comment, metadataId: $metadataId, metadataVersion: $metadataVersion) { id }
    } } }
  }
`
async function addComment() {
  const text = newComment.value.trim()
  if (!text) return
  adding.value = true
  error.value = ''
  try {
    const comment: Record<string, unknown> = { content: text, visibility: newVisibility.value }
    if (impersonateId.value) comment.impersonateId = impersonateId.value
    await mutation(addGql, {
      comment,
      metadataId: metadataId.value,
      metadataVersion: version.value,
    })
    newComment.value = ''
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to add comment'
  } finally {
    adding.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :title="metadata?.name ?? 'Comments'"
        :subtitle="`${count} top-level comment${count !== 1 ? 's' : ''}`"
        :breadcrumb="buildBreadcrumb('CMS', 'Comments', metadata?.name ?? 'Content')"
        :accent="accent"
      >
        <template #actions>
          <Button icon="arrow-left" size="sm" @click="router.push('/cms/comments')">Back</Button>
          <Button icon="pencil" size="sm" @click="router.push(`/cms/editor/${metadataId}`)">Open editor</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="error" class="query-error">{{ error }}</div>

    <SectionCard title="Comments" glass padded>
      <p v-if="status === 'pending' && roots.length === 0" class="state">Loading…</p>
      <p v-else-if="roots.length === 0" class="state">No comments on this content.</p>
      <template v-else>
        <template v-if="pinnedRoots.length">
          <div class="section-label">Pinned</div>
          <ul class="thread">
            <CommentThreadNode
              v-for="c in pinnedRoots"
              :key="`pin-${c.id}`"
              :comment="c"
              :metadata-id="metadataId"
              :version="version"
              :accent="accent"
              is-root
              @changed="refresh"
            />
          </ul>
          <div class="section-divider" />
        </template>
        <ul class="thread">
          <CommentThreadNode
            v-for="c in roots"
            :key="c.id"
            :comment="c"
            :metadata-id="metadataId"
            :version="version"
            :accent="accent"
            is-root
            @changed="refresh"
          />
        </ul>
      </template>
    </SectionCard>

    <SectionCard title="Add a comment" glass padded>
      <div class="post-as">
        <label class="post-as-label">Post as</label>
        <div class="post-as-select">
          <Select
            :model-value="impersonateId"
            :on-search="searchProfiles"
            searchable
            placeholder="Yourself — search a profile to impersonate…"
            @update:model-value="impersonateId = ($event as string) ?? ''"
          />
        </div>
        <Button
          v-if="impersonateId"
          size="sm"
          icon="x"
          @click="impersonateId = ''">Clear</Button>
      </div>
      <Textarea v-model="newComment" :rows="3" placeholder="Write a comment…" />
      <div class="add-actions">
        <div class="visibility-field">
          <label class="visibility-label">Visibility</label>
          <Select
            :model-value="newVisibility"
            :options="VISIBILITY_OPTIONS"
            size="sm"
            :accent="accent"
            @update:model-value="newVisibility = ($event as string) ?? 'PUBLIC'"
          />
        </div>
        <span class="add-spacer" />
        <Button
          primary
          :accent="accent"
          :disabled="!newComment.trim() || adding"
          @click="addComment">
          {{ adding ? 'Adding…' : 'Add comment' }}
        </Button>
      </div>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.state { color: var(--fg-3); font-size: 13px; padding: 14px 0; margin: 0; }
.query-error { color: var(--err, #ef4444); font-size: 13px; margin-bottom: 10px; }
.thread { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.section-label { font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; color: var(--fg-4); margin: 2px 0 2px; }
.section-divider { height: 1px; background: var(--line); margin: 8px 0; }
.add-actions { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.add-spacer { flex: 1; }
.visibility-field { display: flex; align-items: center; gap: 8px; }
.visibility-label { font-size: 12px; color: var(--fg-3); white-space: nowrap; }
.post-as { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.post-as-label { font-size: 12px; color: var(--fg-3); white-space: nowrap; }
.post-as-select { flex: 1; max-width: 360px; }
</style>
