<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

// A comment is shown to non-authors only when its visibility is PUBLIC and its
// status is APPROVED. Moderators change visibility here (default PUBLIC).
const VISIBILITY_OPTIONS: SelectOption[] = [
  { label: 'Public', value: 'PUBLIC' },
  { label: 'Friends of Friends', value: 'FRIENDS_OF_FRIENDS' },
  { label: 'Friends', value: 'FRIENDS' },
  { label: 'User', value: 'USER' },
  { label: 'System', value: 'SYSTEM' },
]

/**
 * One comment in the moderation thread, rendered recursively so replies can be
 * moderated and replied to at any depth (the backend allows a reply's parent to
 * be any comment in the thread). Pinning is offered on top-level comments only,
 * since `comments(pinned: true)` and pinned-first ordering apply to the roots.
 *
 * Mutations live here (one node owns its own busy/error state); after any change
 * the node emits `changed`, which the page bubbles up to re-fetch the thread.
 */
interface CommentRow {
  id: number
  content: string
  status: string
  created: string
  likes: number
  likedByMe?: boolean
  pinned?: boolean
  visibility: string
  profile: { id: string; name: string } | null
  replies?: { comments: CommentRow[] }
}

const props = defineProps<{
  comment: CommentRow
  metadataId: string
  version: number
  accent: string
  isRoot?: boolean
}>()
const emit = defineEmits<{ (e: 'changed'): void }>()

const { mutation } = useGraphQL()
const { searchProfiles } = useProfileSearch()

const STATUS_COLOR: Record<string, string> = {
  PENDING: '#6c7388',
  PENDING_APPROVAL: '#f97316',
  BLOCKED: '#ef4444',
  APPROVED: '#34d99a',
}
function statusLabel(s: string): string {
  return s.split('_').map(w => w.charAt(0) + w.slice(1).toLowerCase()).join(' ')
}
function fmtDate(iso: string): string {
  return new Date(iso).toLocaleString('en-US', {
    month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit',
  })
}

const setStatusGql = gql`
  mutation SetCommentStatus($commentId: Long!, $metadataId: UUID!, $metadataVersion: Int!, $status: CommentStatus!) {
    content { metadata { comments {
      setCommentStatus(commentId: $commentId, metadataId: $metadataId, metadataVersion: $metadataVersion, status: $status)
    } } }
  }
`
const deleteGql = gql`
  mutation DeleteComment($commentId: Long!, $metadataId: UUID!, $metadataVersion: Int!) {
    content { metadata { comments {
      deleteComment(commentId: $commentId, metadataId: $metadataId, metadataVersion: $metadataVersion)
    } } }
  }
`
const setPinnedGql = gql`
  mutation SetCommentPinned($commentId: Long!, $metadataId: UUID!, $metadataVersion: Int!, $pinned: Boolean!) {
    content { metadata { comments {
      setCommentPinned(commentId: $commentId, metadataId: $metadataId, metadataVersion: $metadataVersion, pinned: $pinned)
    } } }
  }
`
const setVisibilityGql = gql`
  mutation SetCommentVisibility($commentId: Long!, $metadataId: UUID!, $metadataVersion: Int!, $visibility: ProfileVisibility!) {
    content { metadata { comments {
      setCommentVisibility(commentId: $commentId, metadataId: $metadataId, metadataVersion: $metadataVersion, visibility: $visibility)
    } } }
  }
`
const addReplyGql = gql`
  mutation AddReply($comment: CommentInput!, $metadataId: UUID!, $metadataVersion: Int!) {
    content { metadata { comments {
      addComment(comment: $comment, metadataId: $metadataId, metadataVersion: $metadataVersion) { id }
    } } }
  }
`
const addLikeGql = gql`
  mutation AddCommentLike($commentId: Long!, $metadataId: UUID!, $metadataVersion: Int!) {
    content { metadata { comments {
      addCommentLike(commentId: $commentId, metadataId: $metadataId, metadataVersion: $metadataVersion)
    } } }
  }
`
const deleteLikeGql = gql`
  mutation DeleteCommentLike($commentId: Long!, $metadataId: UUID!, $metadataVersion: Int!) {
    content { metadata { comments {
      deleteCommentLike(commentId: $commentId, metadataId: $metadataId, metadataVersion: $metadataVersion)
    } } }
  }
`

const busy = ref(false)
const error = ref('')

async function run(action: () => Promise<unknown>, failure: string) {
  busy.value = true
  error.value = ''
  try {
    await action()
    emit('changed')
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : failure
  } finally {
    busy.value = false
  }
}

function baseVars() {
  return { metadataId: props.metadataId, metadataVersion: props.version }
}

function setStatus(status: string) {
  return run(
    () => mutation(setStatusGql, { commentId: props.comment.id, ...baseVars(), status }),
    'Failed to update comment',
  )
}
// Deleting is destructive (and hides any replies with it), so the trash button
// only opens the confirmation modal; the mutation runs on confirm. Errors from
// run() surface in the node's error line, so the modal always closes after.
const confirmingDelete = ref(false)
const deleteExcerpt = computed(() => {
  const text = props.comment.content
  return text.length > 140 ? `${text.slice(0, 140)}…` : text
})
async function remove() {
  await run(
    () => mutation(deleteGql, { commentId: props.comment.id, ...baseVars() }),
    'Failed to delete comment',
  )
  confirmingDelete.value = false
}
function setPinned(pinned: boolean) {
  return run(
    () => mutation(setPinnedGql, { commentId: props.comment.id, ...baseVars(), pinned }),
    'Failed to update pin',
  )
}
function setVisibility(v: string | string[] | null | undefined) {
  if (typeof v !== 'string' || v === props.comment.visibility) return
  return run(
    () => mutation(setVisibilityGql, { commentId: props.comment.id, ...baseVars(), visibility: v }),
    'Failed to update visibility',
  )
}
function toggleLike() {
  return run(
    () => mutation(props.comment.likedByMe ? deleteLikeGql : addLikeGql, { commentId: props.comment.id, ...baseVars() }),
    'Failed to update like',
  )
}

const replyOpen = ref(false)
const replyText = ref('')
const replyImpersonateId = ref('')
const replyVisibility = ref('PUBLIC')
async function submitReply() {
  const text = replyText.value.trim()
  if (!text) return
  const comment: Record<string, unknown> = { content: text, parentId: props.comment.id, visibility: replyVisibility.value }
  if (replyImpersonateId.value) comment.impersonateId = replyImpersonateId.value
  await run(
    () => mutation(addReplyGql, { comment, ...baseVars() }),
    'Failed to add reply',
  )
  replyText.value = ''
  replyImpersonateId.value = ''
  replyVisibility.value = 'PUBLIC'
  replyOpen.value = false
}
</script>

<template>
  <li class="comment" :class="{ reply: !isRoot }">
    <div class="comment-head">
      <span class="author">{{ comment.profile?.name ?? 'Unknown' }}</span>
      <Badge v-if="isRoot && comment.pinned" :color="accent">Pinned</Badge>
      <Badge :color="STATUS_COLOR[comment.status] ?? '#6c7388'">{{ statusLabel(comment.status) }}</Badge>
      <span class="when">{{ fmtDate(comment.created) }}</span>
      <button
        class="like"
        :class="{ liked: comment.likedByMe }"
        :disabled="busy"
        :title="comment.likedByMe ? 'Unlike' : 'Like'"
        @click="toggleLike"
      >♥<span v-if="comment.likes" class="like-count">{{ comment.likes }}</span></button>
      <span class="spacer" />
      <div class="actions">
        <Tooltip content="Approve">
          <Button
            size="xs"
            icon="check"
            :accent="accent"
            :disabled="busy || comment.status === 'APPROVED'"
            @click="setStatus('APPROVED')" />
        </Tooltip>
        <Tooltip content="Block">
          <Button
            size="xs"
            icon="x"
            :disabled="busy || comment.status === 'BLOCKED'"
            @click="setStatus('BLOCKED')" />
        </Tooltip>
        <Tooltip v-if="isRoot" :content="comment.pinned ? 'Unpin' : 'Pin'">
          <Button
            size="xs"
            icon="pin"
            :disabled="busy"
            @click="setPinned(!comment.pinned)" />
        </Tooltip>
        <Tooltip content="Reply">
          <Button size="xs" icon="reply" @click="replyOpen = !replyOpen" />
        </Tooltip>
        <Tooltip content="Delete">
          <Button
            size="xs"
            icon="trash"
            style="margin-right: 32px"
            :disabled="busy"
            @click="confirmingDelete = true" />
        </Tooltip>
        <Tooltip content="Who can see this comment">
          <Select
            class="visibility-select"
            :model-value="comment.visibility"
            :options="VISIBILITY_OPTIONS"
            size="sm"
            :accent="accent"
            :disabled="busy"
            @update:model-value="setVisibility" />
        </Tooltip>
      </div>
    </div>
    <p class="body">{{ comment.content }}</p>
    <div v-if="error" class="node-error">{{ error }}</div>

    <div v-if="replyOpen" class="reply-box">
      <div class="post-as">
        <label class="post-as-label">Post as</label>
        <div class="post-as-select">
          <Select
            :model-value="replyImpersonateId"
            :on-search="searchProfiles"
            searchable
            placeholder="Yourself — search a profile to impersonate…"
            @update:model-value="replyImpersonateId = ($event as string) ?? ''"
          />
        </div>
        <Button
          v-if="replyImpersonateId"
          size="xs"
          icon="x"
          @click="replyImpersonateId = ''">Clear</Button>
      </div>
      <Textarea v-model="replyText" :rows="2" placeholder="Write a reply…" />
      <div class="reply-actions">
        <div class="visibility-field">
          <label class="visibility-label">Visibility</label>
          <Select
            :model-value="replyVisibility"
            :options="VISIBILITY_OPTIONS"
            size="sm"
            :accent="accent"
            @update:model-value="replyVisibility = ($event as string) ?? 'PUBLIC'" />
        </div>
        <span class="reply-spacer" />
        <Button size="sm" @click="replyOpen = false">Cancel</Button>
        <Button
          primary
          size="sm"
          :accent="accent"
          :disabled="!replyText.trim() || busy"
          @click="submitReply">Reply</Button>
      </div>
    </div>

    <ConfirmModal
      v-if="confirmingDelete"
      title="Delete Comment"
      subtitle="Removes the comment from the thread."
      confirm-label="Delete"
      :loading="busy"
      @close="confirmingDelete = false"
      @confirm="remove">
      <p class="confirm-text">
        Delete this comment by <strong>{{ comment.profile?.name ?? 'Unknown' }}</strong>?<template v-if="comment.replies?.comments.length">
          Replies to it will no longer appear in the thread.</template>
      </p>
      <blockquote class="confirm-quote">{{ deleteExcerpt }}</blockquote>
    </ConfirmModal>

    <ul v-if="comment.replies && comment.replies.comments.length" class="replies">
      <CommentThreadNode
        v-for="r in comment.replies.comments"
        :key="r.id"
        :comment="r"
        :metadata-id="metadataId"
        :version="version"
        :accent="accent"
        @changed="$emit('changed')"
      />
    </ul>
  </li>
</template>

<style scoped>
.comment { padding: 14px 4px; border-bottom: 1px solid var(--line); }
.comment:last-child { border-bottom: none; }
.comment-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.author { font-weight: 600; color: var(--fg-0); font-size: 13px; }
.when { font-size: 11.5px; color: var(--fg-4); }
.spacer { flex: 1; }
.like { display: inline-flex; align-items: center; gap: 3px; font-size: 11.5px; color: var(--fg-4); cursor: pointer; background: none; border: none; padding: 2px 5px; border-radius: 5px; line-height: 1; }
.like:hover:not(:disabled) { color: var(--fg-2); background: var(--bg-3); }
.like.liked { color: #ef4444; }
.like:disabled { cursor: default; opacity: 0.6; }
.like-count { font-variant-numeric: tabular-nums; }
.body { margin: 0 0 10px; color: var(--fg-1); font-size: 13.5px; line-height: 1.55; white-space: pre-wrap; }
.node-error { color: var(--err, #ef4444); font-size: 12px; margin-bottom: 8px; }
.actions { display: flex; align-items: center; gap: 4px; }
.visibility-select { width: 150px; }
.reply-box { margin-top: 10px; }
.post-as { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.post-as-label { font-size: 12px; color: var(--fg-3); white-space: nowrap; }
.post-as-select { flex: 1; max-width: 360px; }
.reply-actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.reply-spacer { flex: 1; }
.visibility-field { display: flex; align-items: center; gap: 8px; }
.visibility-label { font-size: 12px; color: var(--fg-3); white-space: nowrap; }
.confirm-text { margin: 0 0 10px; font-size: 13px; color: var(--fg-1); line-height: 1.5; }
.confirm-quote { margin: 0; padding: 6px 10px; border-left: 2px solid var(--line-2); color: var(--fg-3); font-size: 12.5px; line-height: 1.5; overflow-wrap: anywhere; }
.replies { list-style: none; margin: 10px 0 0; padding: 0 0 0 18px; border-left: 2px solid var(--line); display: flex; flex-direction: column; }
.reply { padding: 10px 4px; }
</style>
