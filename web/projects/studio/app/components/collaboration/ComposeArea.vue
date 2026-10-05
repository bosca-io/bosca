<script setup lang="ts">
import { ref, nextTick, computed } from 'vue'

const props = defineProps<{
  placeholder?: string
  disabled?: boolean
  members?: Array<{ id: string; name: string }>
   
  onOpenPicker?: (type: string) => void
}>()

export interface ChatAttachment {
  type: 'metadata' | 'collection'
  id: string
  name: string
}

const emit = defineEmits<{
  send: [text: string, attachments: ChatAttachment[]]
  typing: []
  openPicker: [pickerType: string]
}>()

const text = ref('')
const attachMenuOpen = ref(false)
const attachments = ref<ChatAttachment[]>([])

defineExpose({
  addAttachment(attachment: ChatAttachment) {
    if (!attachments.value.some(a => a.id === attachment.id)) {
      attachments.value.push(attachment)
    }
  },
})

function onAttachOption(type: string) {
  attachMenuOpen.value = false
  if (props.onOpenPicker) {
    props.onOpenPicker(type)
  } else {
    emit('openPicker', type)
  }
}

function removeAttachment(id: string) {
  attachments.value = attachments.value.filter(a => a.id !== id)
}

const composeAreaEl = ref<HTMLElement>()

function onDocClick(e: MouseEvent) {
  if (composeAreaEl.value && !composeAreaEl.value.contains(e.target as Node)) {
    attachMenuOpen.value = false
  }
}
onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => document.removeEventListener('click', onDocClick))
const inputEl = ref<HTMLTextAreaElement>()

const mentionVisible = ref(false)
const mentionQuery = ref('')
const mentionStartPos = ref(-1)

const slashVisible = ref(false)
const slashQuery = ref('')

const memberList = computed(() => props.members ?? [])
const canSend = computed(() => (!!text.value.trim() || attachments.value.length > 0) && !props.disabled)

function onKeydown(e: KeyboardEvent) {
  if (mentionVisible.value || slashVisible.value) return
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

const canSendAttachmentOnly = computed(() => attachments.value.length > 0)

function send() {
  const msg = text.value.trim()
  if (!msg && !canSendAttachmentOnly.value) return
  emit('send', msg || '', [...attachments.value])
  text.value = ''
  attachments.value = []
  mentionVisible.value = false
  slashVisible.value = false
  nextTick(() => {
    if (inputEl.value) {
      inputEl.value.style.height = 'auto'
    }
  })
}

function autoResize() {
  if (!inputEl.value) return
  inputEl.value.style.height = 'auto'
  inputEl.value.style.height = Math.min(inputEl.value.scrollHeight, 120) + 'px'
}

function onInput() {
  autoResize()
  emit('typing')
  detectSlash()
  if (!slashVisible.value) detectMention()
}

function detectSlash() {
  const val = text.value
  if (val.startsWith('/')) {
    slashVisible.value = true
    slashQuery.value = val
  } else {
    slashVisible.value = false
  }
}

function detectMention() {
  const el = inputEl.value
  if (!el) return

  const cursorPos = el.selectionStart ?? 0
  const textBefore = text.value.slice(0, cursorPos)
  const atIndex = textBefore.lastIndexOf('@')

  if (atIndex >= 0 && (atIndex === 0 || textBefore[atIndex - 1] === ' ')) {
    const query = textBefore.slice(atIndex + 1)
    if (!query.includes(' ')) {
      mentionVisible.value = true
      mentionQuery.value = query
      mentionStartPos.value = atIndex
      return
    }
  }

  mentionVisible.value = false
}

function onMentionSelect(member: { id: string; name: string }) {
  const before = text.value.slice(0, mentionStartPos.value)
  const after = text.value.slice((inputEl.value?.selectionStart ?? mentionStartPos.value + mentionQuery.value.length + 1))
  text.value = `${before}@${member.name} ${after}`
  mentionVisible.value = false

  nextTick(() => {
    const pos = before.length + member.name.length + 2
    inputEl.value?.setSelectionRange(pos, pos)
    inputEl.value?.focus()
  })
}

function onSlashSelect(command: string) {
  text.value = command + ' '
  slashVisible.value = false
  nextTick(() => {
    inputEl.value?.focus()
    const pos = text.value.length
    inputEl.value?.setSelectionRange(pos, pos)
  })
}
</script>

<template>
  <div ref="composeAreaEl" class="compose-area">
    <!-- Attach menu (above compose box, left-aligned) -->
    <div v-if="attachMenuOpen" class="attach-menu">
      <button class="attach-option" @click="onAttachOption('metadata')">
        <Icon name="upload" :size="13" /> Metadata / Upload
      </button>
      <button class="attach-option" @click="onAttachOption('collection')">
        <Icon name="boxes" :size="13" /> Collection
      </button>
    </div>

    <div class="compose-box">
      <SlashCommandPicker
        :query="slashQuery"
        :visible="slashVisible"
        @select="onSlashSelect"
        @close="slashVisible = false"
      />
      <MentionPicker
        :query="mentionQuery"
        :members="memberList"
        :visible="mentionVisible"
        @select="onMentionSelect"
        @close="mentionVisible = false"
      />

      <!-- Attach menu -->
      <div class="attach-wrapper">
        <button
          class="compose-action"
          title="Attach"
          :disabled="disabled"
          @click.stop="attachMenuOpen = !attachMenuOpen">
          <Icon name="plus" :size="18" />
        </button>
      </div>

      <!-- Attachments preview -->
      <div v-if="attachments.length" class="attachment-list">
        <div v-for="a in attachments" :key="a.id" class="attachment-chip">
          <Icon :name="a.type === 'collection' ? 'boxes' : 'file'" :size="12" />
          <span class="attachment-name">{{ a.name }}</span>
          <button class="attachment-remove" @click="removeAttachment(a.id)">
            <Icon name="x" :size="10" />
          </button>
        </div>
      </div>

      <textarea
        ref="inputEl"
        v-model="text"
        class="compose-input"
        :placeholder="placeholder ?? 'Write a message…'"
        rows="1"
        :disabled
        @keydown="onKeydown"
        @input="onInput"
      />

      <button
        class="compose-send"
        :class="{ active: canSend }"
        :disabled="!canSend"
        title="Send (Enter)"
        @click="send">
        <svg
          width="18"
          height="18"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round">
          <path d="M22 2 L11 13" />
          <path d="M22 2 L15 22 L11 13 L2 9 Z" />
        </svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
.compose-area {
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.compose-box {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 6px;
  display: flex;
  align-items: flex-end;
  gap: 4px;
  background: var(--bg-2);
  transition: border-color 0.15s;
  position: relative;
}

.compose-box:focus-within {
  border-color: var(--brand-2);
}

.compose-action {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: none;
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: color 0.15s, background 0.15s;
}

.compose-action:hover {
  color: var(--fg-1);
  background: var(--bg-3);
}

.compose-action:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.compose-input {
  flex: 1;
  background: none;
  border: none;
  outline: none;
  font-size: 13.5px;
  color: var(--fg-0);
  resize: none;
  line-height: 1.5;
  min-height: 32px;
  max-height: 120px;
  font-family: inherit;
  padding: 5px 4px;
}

.compose-input::placeholder {
  color: var(--fg-3);
}

.compose-send {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--bg-3);
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: background 0.15s, color 0.15s;
}

.compose-send.active {
  background: var(--brand-2);
  color: #fff;
}

.compose-send:disabled {
  cursor: default;
}

.compose-send.active:hover {
  filter: brightness(1.15);
}

.attach-wrapper {
}

.attach-menu {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.35);
  white-space: nowrap;
  padding: 3px;
  align-self: flex-start;
}

.attach-option {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  font-size: 13px;
  color: var(--fg-1);
  background: none;
  border: none;
  border-radius: var(--r-sm);
  cursor: pointer;
  text-align: left;
}

.attach-option:hover {
  background: var(--bg-2);
}

.attachment-list {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  padding: 4px 0;
}

.attachment-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  background: var(--bg-3);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  font-size: 11.5px;
  color: var(--fg-1);
}

.attachment-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attachment-remove {
  width: 14px;
  height: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: none;
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  border-radius: 3px;
}

.attachment-remove:hover {
  color: var(--err);
  background: var(--bg-2);
}
</style>
