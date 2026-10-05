<script setup lang="ts">
defineProps<{
  sender: { name: string; color?: string }
  timestamp: string
  text: string
  attachments?: Array<{ type: string; id: string; name: string }>
  reactions?: Array<{ emoji: string; count: number; active: boolean }>
  replyCount?: number
  pending?: boolean
  failed?: boolean
}>()

const emit = defineEmits<{
  reply: []
  react: [emoji: string]
  retry: []
}>()

function initials(name: string): string {
  return name.split(' ').map(w => w[0]).join('').toUpperCase().slice(0, 2)
}

const pickerOpen = ref(false)
const pickerX = ref(0)
const pickerY = ref(0)

function onOpenPicker(e: MouseEvent) {
  const rect = (e.target as HTMLElement).getBoundingClientRect()
  const pickerHeight = 78
  const pickerWidth = 204

  let x = rect.left
  let y = rect.top - pickerHeight - 4

  if (y < 8) y = rect.bottom + 4
  if (x + pickerWidth > window.innerWidth - 8) x = window.innerWidth - pickerWidth - 8

  pickerX.value = x
  pickerY.value = y
  pickerOpen.value = true
}

function onPickEmoji(emoji: string) {
  pickerOpen.value = false
  emit('react', emoji)
}

function formatTime(ts: string): string {
  try {
    const d = new Date(ts)
    return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  } catch {
    return ts
  }
}
</script>

<template>
  <div class="chat-msg" :class="{ pending, failed }">
    <div class="msg-avatar" :style="{ background: sender.color ?? 'var(--brand-2)' }">
      {{ initials(sender.name) }}
    </div>
    <div class="msg-body">
      <div class="msg-header">
        <span class="msg-author">{{ sender.name }}</span>
        <span class="msg-time">{{ formatTime(timestamp) }}</span>
        <span v-if="pending" class="msg-status">sending…</span>
        <span v-if="failed" class="msg-status msg-failed">
          failed
          <button class="retry-btn" @click="emit('retry')">retry</button>
        </span>

        <span class="msg-actions">
          <button title="React" @click.stop="onOpenPicker">😀</button>
          <button title="Reply" @click="emit('reply')">↩</button>
        </span>
      </div>
      <div v-if="text" class="msg-text">{{ text }}</div>

      <!-- Attachment cards -->
      <div v-if="attachments?.length" class="msg-attachments">
        <a
          v-for="a in attachments"
          :key="a.id"
          class="attachment-card"
          :href="`/cms/${a.type === 'collection' ? 'collections' : 'metadata'}/${a.id}`"
        >
          <div class="attachment-accent" />
          <div class="attachment-body">
            <div class="attachment-type">{{ a.type === 'collection' ? 'Collection' : 'Content' }}</div>
            <div class="attachment-title">{{ a.name }}</div>
          </div>
          <img
            v-if="a.type === 'metadata'"
            :src="`/content/image/${a.id}`"
            class="attachment-thumb"
            alt=""
            @error="($event.target as HTMLImageElement).style.display = 'none'"
          >
        </a>
      </div>

      <!-- Reactions -->
      <div v-if="reactions?.length" class="msg-reactions">
        <button
          v-for="r in reactions"
          :key="r.emoji"
          class="reaction-chip"
          :class="{ active: r.active }"
          @click="emit('react', r.emoji)"
        >
          {{ r.emoji }}<span class="reaction-count">{{ r.count }}</span>
        </button>
      </div>


      <ReactionPicker
        v-if="pickerOpen"
        :x="pickerX"
        :y="pickerY"
        @select="onPickEmoji"
        @close="pickerOpen = false"
      />

      <button v-if="replyCount" class="reply-link" @click="emit('reply')">
        {{ replyCount }} repl{{ replyCount === 1 ? 'y' : 'ies' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.chat-msg {
  display: flex;
  gap: 8px;
}

.chat-msg.pending {
  opacity: 0.6;
}

.msg-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  flex: 0 0 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
}

.msg-body {
  flex: 1;
  min-width: 0;
}

.msg-header {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.msg-author {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--fg-0);
}

.msg-time {
  font-size: 10.5px;
  color: var(--fg-3);
}

.msg-status {
  font-size: 10.5px;
  color: var(--fg-3);
  font-style: italic;
}

.msg-failed {
  color: var(--err);
  font-style: normal;
}

.retry-btn {
  font-size: 10.5px;
  color: var(--brand-2);
  text-decoration: underline;
  margin-left: 4px;
}

.msg-text {
  font-size: 13px;
  color: var(--fg-1);
  line-height: 1.4;
  margin-top: 1px;
  white-space: pre-wrap;
}

.msg-attachments {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 4px;
}

.attachment-card {
  display: flex;
  align-items: stretch;
  max-width: 320px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow: hidden;
  text-decoration: none;
  transition: background 0.15s;
}

.attachment-card:hover {
  background: var(--bg-2);
}

.attachment-accent {
  width: 3px;
  background: var(--brand-2);
  flex-shrink: 0;
}

.attachment-body {
  flex: 1;
  padding: 8px 10px;
  min-width: 0;
}

.attachment-type {
  font-size: 10px;
  font-weight: 600;
  color: var(--brand-2);
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.attachment-title {
  font-size: 13px;
  color: var(--fg-0);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-top: 1px;
}

.attachment-thumb {
  width: 56px;
  height: 56px;
  object-fit: cover;
  flex-shrink: 0;
}

.msg-reactions {
  display: flex;
  gap: 4px;
  margin-top: 4px;
  flex-wrap: wrap;
}

.reaction-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 6px;
  border-radius: 999px;
  background: var(--bg-3);
  border: 1px solid var(--line);
  font-size: 12px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.reaction-chip.active {
  background: color-mix(in oklch, var(--brand-2) 14%, transparent);
  border-color: color-mix(in oklch, var(--brand-2) 30%, transparent);
}

.reaction-chip:hover {
  border-color: var(--fg-4);
}

.reaction-count {
  font-size: 11px;
  color: var(--fg-2);
  font-weight: 600;
}

.msg-actions {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.1s;
}

.chat-msg:hover .msg-actions {
  opacity: 1;
}

.msg-actions button {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: none;
  border: none;
  border-radius: var(--r-xs);
  cursor: pointer;
  font-size: 12px;
  color: var(--fg-3);
}

.msg-actions button:hover {
  background: var(--bg-2);
  color: var(--fg-1);
}

.reply-link {
  font-size: 11.5px;
  color: var(--brand-2);
  margin-top: 4px;
  font-weight: 500;
}

.reply-link:hover {
  text-decoration: underline;
}
</style>
