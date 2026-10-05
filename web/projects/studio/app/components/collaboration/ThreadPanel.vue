<script setup lang="ts">
import ChatMessage from './ChatMessage.vue'
import ComposeArea, { type ChatAttachment } from './ComposeArea.vue'

defineProps<{
  parentMessage: {
    sender: { name: string; color?: string }
    timestamp: string
    text: string
  }
  replies: Array<{
    id: string
    sender: { name: string; color?: string }
    timestamp: string
    text: string
    attachments?: Array<{ type: string; id: string; name: string }>
    reactions?: Array<{ emoji: string; count: number; active: boolean }>
  }>
}>()

const emit = defineEmits<{
  close: []
  send: [text: string, attachments: ChatAttachment[]]
  openPicker: [type: string]
  react: [sequence: string, emoji: string]
}>()

const composeRef = ref<InstanceType<typeof ComposeArea> | null>(null)

defineExpose({
  addAttachment(attachment: ChatAttachment) {
    composeRef.value?.addAttachment(attachment)
  },
})
</script>

<template>
  <div class="thread-panel">
    <div class="thread-header">
      <span class="thread-title">Thread</span>
      <button class="thread-close" @click="emit('close')">
        <Icon name="x" :size="14" color="var(--fg-3)" />
      </button>
    </div>

    <div class="thread-parent">
      <ChatMessage
        :sender="parentMessage.sender"
        :timestamp="parentMessage.timestamp"
        :text="parentMessage.text"
      />
    </div>

    <div v-if="replies.length" class="thread-reply-count">
      {{ replies.length }} repl{{ replies.length === 1 ? 'y' : 'ies' }}
    </div>

    <div class="thread-replies">
      <ChatMessage
        v-for="r in replies"
        :key="r.id"
        :sender="r.sender"
        :timestamp="r.timestamp"
        :text="r.text"
        :attachments="r.attachments"
        :reactions="r.reactions"
        @react="emit('react', r.id, $event)"
      />
    </div>

    <ComposeArea
      ref="composeRef"
      placeholder="Reply in thread…"
      :on-open-picker="(type: string) => emit('openPicker', type)"
      @send="(text: string, atts: ChatAttachment[]) => emit('send', text, atts)" />
  </div>
</template>

<style scoped>
.thread-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  background: var(--bg-1);
  border-left: 1px solid var(--line);
}

.thread-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 40%, transparent);
}

.thread-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-0);
}

.thread-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  transition: background 0.15s;
}

.thread-close:hover {
  background: var(--bg-3);
}

.thread-parent {
  padding: 14px 12px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 30%, transparent);
}

.thread-reply-count {
  padding: 8px 14px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--fg-3);
  border-bottom: 1px solid color-mix(in oklch, var(--line) 30%, transparent);
}

.thread-replies {
  flex: 1;
  overflow: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
