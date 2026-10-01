<script setup lang="ts">
import ChatMessage from '~/components/collaboration/ChatMessage.vue'
import ComposeArea from '~/components/collaboration/ComposeArea.vue'
import type { CollabWindowState } from '~~/shared/types'

interface CollabAttachment {
  type: string
  id: string
  name: string
}

interface ComposeAreaRef {
  addAttachment: (attachment: CollabAttachment) => void
}

interface CollabMessage {
  sequence: number
  senderName: string
  senderId: string
  timestamp: string
  text: string
  attachments: CollabAttachment[]
  reactions: unknown[]
  parentSequence: number | null
}

const props = defineProps<{
  variant?: 'hud' | 'glass'
}>()

const isGlass = computed(() => (props.variant ?? 'glass') === 'glass')

const open = ref(true)
const channelDropdownOpen = ref(false)

const { tweaks, setTweak } = useTweaks()

const {
  channels,
  activeChannelId,
  messages,
  channelMeta,
  isLoading,
  selectChannel,
  sendMessage,
  toggleReaction,
  senderColor,
  allMessages,
} = useCollabDock()

const activeThreadSeq = ref<number | null>(null)
const dockMetadataPickerOpen = ref(false)
const dockCollectionPickerOpen = ref(false)
const dockComposeRef = ref<ComposeAreaRef | null>(null)
const dockThreadComposeRef = ref<ComposeAreaRef | null>(null)

function sendThreadMessage(text: string, attachments: CollabAttachment[]) {
  sendMessage(text, attachments, activeThreadSeq.value)
}

function sendMainMessage(text: string, attachments: CollabAttachment[]) {
  sendMessage(text, attachments)
}

function onDockOpenPicker(type: string) {
  if (type === 'metadata') dockMetadataPickerOpen.value = true
  else if (type === 'collection') dockCollectionPickerOpen.value = true
}

function onDockMetadataSelected(meta: { id: string; name: string }) {
  dockMetadataPickerOpen.value = false
  const target = activeThreadSeq.value != null ? dockThreadComposeRef.value : dockComposeRef.value
  target?.addAttachment({ type: 'metadata', id: meta.id, name: meta.name })
}

function onDockCollectionSelected(coll: { id: string; name: string }) {
  dockCollectionPickerOpen.value = false
  const target = activeThreadSeq.value != null ? dockThreadComposeRef.value : dockComposeRef.value
  target?.addAttachment({ type: 'collection', id: coll.id, name: coll.name })
}

const activeThreadParent = computed(() => {
  if (activeThreadSeq.value == null) return null
  return messages.value.find((m: CollabMessage) => m.sequence === activeThreadSeq.value) ?? null
})

const activeThreadReplies = computed(() => {
  if (activeThreadSeq.value == null) return []
  return allMessages.value.filter((m: CollabMessage) => m.parentSequence === activeThreadSeq.value)
})

function openThread(seq: number) {
  activeThreadSeq.value = seq
}

function closeThread() {
  activeThreadSeq.value = null
}

const messagesEl = ref<HTMLElement>()
const collapsedUnread = ref(0)
const lastMessageCount = ref(0)

watch(messages, (msgs) => {
  if (open.value) {
    lastMessageCount.value = msgs.length
    nextTick(() => {
      if (messagesEl.value) {
        messagesEl.value.scrollTop = messagesEl.value.scrollHeight
      }
    })
  } else {
    const newCount = msgs.length - lastMessageCount.value
    if (newCount > 0) {
      collapsedUnread.value += newCount
      lastMessageCount.value = msgs.length
    }
  }
})

watch(open, (isOpen) => {
  if (isOpen) {
    collapsedUnread.value = 0
    lastMessageCount.value = messages.value.length
  }
})

function onSelectChannel(id: string) {
  selectChannel(id)
}

const channelTriggerEl = ref<HTMLElement>()
const dropdownRect = reactive({ top: 0, left: 0, width: 0 })

function toggleChannelDropdown() {
  if (!channelDropdownOpen.value && channelTriggerEl.value) {
    const rect = channelTriggerEl.value.getBoundingClientRect()
    dropdownRect.top = rect.bottom + 4
    dropdownRect.left = rect.left
    dropdownRect.width = rect.width
  }
  channelDropdownOpen.value = !channelDropdownOpen.value
}

const dropdownStyle = computed(() => ({
  top: `${dropdownRect.top}px`,
  left: `${dropdownRect.left}px`,
  width: `${dropdownRect.width}px`,
}))

function close() {
  setTweak('showCollab', false)
}

const MIN_WIDTH = 280
const MIN_HEIGHT = 320
const COLLAPSED_WIDTH = 130
const COLLAPSED_HEIGHT = 44

const winState = computed(() => tweaks.value.collabWindow)

const pos = reactive({
  x: winState.value.x,
  y: winState.value.y,
  width: winState.value.width,
  height: winState.value.height,
})

function initPosition() {
  if (pos.x < 0 || pos.y < 0) {
    pos.x = window.innerWidth - pos.width - 24
    pos.y = window.innerHeight - pos.height - 24
  }
}

function persistPosition() {
  const state: CollabWindowState = { x: pos.x, y: pos.y, width: pos.width, height: pos.height }
  setTweak('collabWindow', state)
}

function clampToViewport() {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const w = open.value ? pos.width : COLLAPSED_WIDTH
  const h = open.value ? pos.height : COLLAPSED_HEIGHT
  pos.x = Math.max(0, Math.min(pos.x, vw - w))
  pos.y = Math.max(0, Math.min(pos.y, vh - h))
}

onMounted(() => {
  initPosition()
  clampToViewport()
  window.addEventListener('resize', clampToViewport)
})

onUnmounted(() => {
  window.removeEventListener('resize', clampToViewport)
})

// --- Drag ---
const dragging = ref(false)
let dragOffset = { x: 0, y: 0 }

function onDragStart(e: PointerEvent) {
  if ((e.target as HTMLElement).closest('button')) return
  dragging.value = true
  dragOffset = { x: e.clientX - pos.x, y: e.clientY - pos.y }
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}

function onDragMove(e: PointerEvent) {
  if (!dragging.value) return
  pos.x = e.clientX - dragOffset.x
  pos.y = e.clientY - dragOffset.y
  clampToViewport()
}

function onDragEnd() {
  if (!dragging.value) return
  dragging.value = false
  persistPosition()
}

// --- Resize ---
const resizing = ref(false)
let resizeStart = { x: 0, y: 0, w: 0, h: 0 }

function onResizeStart(e: PointerEvent) {
  resizing.value = true
  resizeStart = { x: e.clientX, y: e.clientY, w: pos.width, h: pos.height }
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  e.preventDefault()
}

function onResizeMove(e: PointerEvent) {
  if (!resizing.value) return
  pos.width = Math.max(MIN_WIDTH, resizeStart.w + (e.clientX - resizeStart.x))
  pos.height = Math.max(MIN_HEIGHT, resizeStart.h + (e.clientY - resizeStart.y))
  clampToViewport()
}

function onResizeEnd() {
  if (!resizing.value) return
  resizing.value = false
  persistPosition()
}

const windowStyle = computed(() => ({
  left: `${pos.x}px`,
  top: `${pos.y}px`,
  width: open.value ? `${pos.width}px` : `${COLLAPSED_WIDTH}px`,
  height: open.value ? `${pos.height}px` : `${COLLAPSED_HEIGHT}px`,
}))
</script>

<template>
  <div
    class="collab-window"
    :class="{
      'collab-window--glass': isGlass,
      'collab-window--dragging': dragging,
      'collab-window--resizing': resizing,
    }"
    :style="windowStyle"
  >
    <div
      class="collab-capsule"
      :class="{ 'collab-capsule--glass': isGlass }"
    >

      <!-- Header / collapsed bar -->
      <div
        class="collab-header"
        :class="{ 'collab-header--open': open }"
        @pointerdown="onDragStart"
        @pointermove="onDragMove"
        @pointerup="onDragEnd"
      >
        <button
          :title="open ? 'Collapse' : 'Expand'"
          class="collab-toggle-btn"
          @click="open = !open"
        >
          <Icon
            name="chevron"
            :size="14"
            color="var(--fg-2)"
            :style="{ transform: open ? 'none' : 'rotate(180deg)' }" />
        </button>

        <template v-if="open">
          <span class="collab-title">Collaboration</span>
          <span class="spacer" />
          <button class="collab-close-btn" title="Close" @click="close">
            <Icon name="x" :size="12" color="var(--fg-3)" />
          </button>
        </template>

        <template v-else>
          <span v-if="collapsedUnread > 0" class="collab-unread-badge">
            {{ collapsedUnread }} unread
          </span>
          <span class="spacer" />
          <Icon name="message" :size="14" color="var(--fg-2)" />
        </template>
      </div>

      <!-- Open state -->
      <template v-if="open">
        <div class="collab-channel-bar">
          <button ref="channelTriggerEl" class="collab-channel-trigger" @click="toggleChannelDropdown">
            <span class="collab-channel-hash">#</span>
            <span class="collab-channel-active-name">{{ channelMeta.name || 'Select channel' }}</span>
            <Icon
              name="chevronDown"
              :size="12"
              color="var(--fg-3)"
              :style="{ transform: channelDropdownOpen ? 'rotate(180deg)' : 'none', transition: 'transform .15s' }" />
          </button>
          <Teleport to="body">
            <div v-if="channelDropdownOpen" class="collab-channel-dropdown" :style="dropdownStyle">
              <button
                v-for="c in channels"
                :key="c.id"
                class="collab-channel-option"
                :class="{ 'collab-channel-option--active': c.id === activeChannelId }"
                @click="onSelectChannel(c.id); channelDropdownOpen = false"
              >
                <span class="collab-channel-hash">#</span>
                <span class="collab-channel-option-name">{{ c.name }}</span>
                <span v-if="c.unreadCount > 0" class="collab-channel-option-badge">{{ c.unreadCount }}</span>
              </button>
            </div>
          </Teleport>
        </div>

        <!-- Thread view -->
        <template v-if="activeThreadParent">
          <div class="collab-thread-header">
            <button class="collab-thread-back" @click="closeThread">
              <Icon name="arrowLeft" :size="14" /> Back
            </button>
            <span class="collab-thread-label">Thread</span>
          </div>
          <div class="collab-messages">
            <ChatMessage
              :sender="{ name: activeThreadParent.senderName, color: senderColor(activeThreadParent.senderName) }"
              :timestamp="activeThreadParent.timestamp"
              :text="activeThreadParent.text"
              :attachments="activeThreadParent.attachments"
              :reactions="activeThreadParent.reactions"
              @react="toggleReaction(activeThreadParent.sequence, $event)"
            />
            <div v-if="activeThreadReplies.length" class="collab-thread-divider">
              {{ activeThreadReplies.length }} repl{{ activeThreadReplies.length === 1 ? 'y' : 'ies' }}
            </div>
            <ChatMessage
              v-for="r in activeThreadReplies"
              :key="r.sequence"
              :sender="{ name: r.senderName, color: senderColor(r.senderName) }"
              :timestamp="r.timestamp"
              :text="r.text"
              :attachments="r.attachments"
              :reactions="r.reactions"
              @react="toggleReaction(r.sequence, $event)"
            />
          </div>
          <ComposeArea
            v-if="activeChannelId"
            ref="dockThreadComposeRef"
            placeholder="Reply in thread…"
            @send="sendThreadMessage"
            @open-picker="onDockOpenPicker"
          />
        </template>

        <!-- Main messages view -->
        <template v-else>
          <div ref="messagesEl" class="collab-messages">
            <div v-if="isLoading && !messages.length" class="collab-empty">
              Loading...
            </div>
            <div v-else-if="!channels.length" class="collab-empty">
              No channels available
            </div>
            <div v-else-if="!messages.length && activeChannelId" class="collab-empty">
              No messages yet
            </div>
            <template v-else>
              <ChatMessage
                v-for="msg in messages"
                :key="msg.sequence"
                :sender="{ name: msg.senderName, color: senderColor(msg.senderName) }"
                :timestamp="msg.timestamp"
                :text="msg.text"
                :attachments="msg.attachments"
                :reactions="msg.reactions"
                :reply-count="msg.replyCount"
                @react="toggleReaction(msg.sequence, $event)"
                @reply="openThread(msg.sequence)"
              />
            </template>
          </div>

          <ComposeArea
            v-if="activeChannelId"
            ref="dockComposeRef"
            placeholder="Write a message…"
            @send="sendMainMessage"
            @open-picker="onDockOpenPicker"
          />
        </template>
      </template>

    </div>

    <!-- Resize handle (bottom-right corner) -->
    <div
      v-if="open"
      class="collab-resize-handle"
      @pointerdown="onResizeStart"
      @pointermove="onResizeMove"
      @pointerup="onResizeEnd"
    />

    <MetadataPickerModal
      v-if="dockMetadataPickerOpen"
      @select="onDockMetadataSelected"
      @close="dockMetadataPickerOpen = false"
    />

    <CollectionPickerModal
      v-if="dockCollectionPickerOpen"
      @select="onDockCollectionSelected"
      @close="dockCollectionPickerOpen = false"
    />
  </div>
</template>

<style scoped>
.collab-window {
  position: fixed;
  z-index: 900;
  display: flex;
  flex-direction: column;
  transition: width .2s, height .2s;
  padding: 0;
}

.collab-window--dragging,
.collab-window--resizing {
  transition: none;
  user-select: none;
}

.collab-capsule {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  border-radius: 14px;
  background: var(--bg-1);
  border: 1px solid var(--line);
}

.collab-capsule--glass {
  background: var(--capsule-bg);
  backdrop-filter: blur(22px) saturate(1.2);
  -webkit-backdrop-filter: blur(22px) saturate(1.2);
  border: 1px solid var(--capsule-border);
  box-shadow: var(--capsule-shadow);
}

.collab-header {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  height: 44px;
  padding: 0 10px;
  cursor: grab;
}

.collab-header--open {
  padding: 0 14px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 40%, transparent);
}

.collab-window--dragging .collab-header {
  cursor: grabbing;
}

.collab-toggle-btn {
  color: var(--fg-2);
  display: flex;
  align-items: center;
  justify-content: center;
  height: 28px;
  border-radius: 7px;
}

.collab-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-1);
}

.spacer {
  flex: 1;
}

.collab-close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: transparent;
  border: none;
  cursor: pointer;
  opacity: 0.5;
  transition: opacity .15s, background .15s;
}

.collab-close-btn:hover {
  opacity: 1;
  background: color-mix(in oklch, var(--fg-3) 15%, transparent);
}


.collab-unread-badge {
  font-size: 10px;
  font-weight: 600;
  color: var(--brand-2);
  white-space: nowrap;
}

.collab-channel-bar {
  flex: 0 0 auto;
  padding: 6px 10px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 30%, transparent);
}

.collab-channel-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 5px 8px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--fg-1);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: background .15s;
}

.collab-channel-trigger:hover {
  background: color-mix(in oklch, var(--fg-2) 8%, transparent);
}

.collab-channel-hash {
  color: var(--fg-3);
}

.collab-channel-active-name {
  flex: 1;
  text-align: left;
  font-weight: 500;
  font-size: 13px;
}

.collab-channel-dropdown {
  position: fixed;
  z-index: 950;
  padding: 4px;
  border-radius: 10px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35);
}

.collab-channel-option {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  border-radius: 7px;
  font-size: 12.5px;
  color: var(--fg-2);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: background .15s, color .15s;
}

.collab-channel-option:hover {
  background: color-mix(in oklch, var(--fg-2) 8%, transparent);
}

.collab-channel-option--active {
  background: color-mix(in oklch, var(--fg-2) 12%, transparent);
  color: var(--fg-0);
}

.collab-channel-option-name {
  flex: 1;
  text-align: left;
}

.collab-channel-option-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
  background: color-mix(in oklch, var(--fg-2) 18%, transparent);
  color: var(--fg-1);
  font-weight: 600;
}

.collab-messages {
  flex: 1;
  overflow: auto;
  padding: 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.collab-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--fg-3);
}

.collab-resize-handle {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 16px;
  height: 16px;
  cursor: nwse-resize;
  z-index: 10;
  border-radius: 0 0 14px 0;
}

.collab-resize-handle::after {
  content: '';
  position: absolute;
  bottom: 4px;
  right: 4px;
  width: 8px;
  height: 8px;
  border-right: 2px solid color-mix(in oklch, var(--fg-3) 40%, transparent);
  border-bottom: 2px solid color-mix(in oklch, var(--fg-3) 40%, transparent);
  border-radius: 0 0 2px 0;
}

:deep(.msg-avatar) {
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
}

.collab-thread-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 30%, transparent);
}

.collab-thread-back {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--fg-3);
  background: none;
  border: none;
  cursor: pointer;
  padding: 3px 6px;
  border-radius: var(--r-sm);
}

.collab-thread-back:hover {
  color: var(--fg-0);
  background: color-mix(in oklch, var(--fg-2) 8%, transparent);
}

.collab-thread-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
}

.collab-thread-divider {
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-3);
  padding: 6px 0;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 30%, transparent);
  margin-bottom: 4px;
}
</style>
