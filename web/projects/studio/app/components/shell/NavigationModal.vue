<script setup lang="ts">
import { groupSubsystems } from '~/composables/useSubsystems'
import type { Subsystem } from '~~/shared/types'

defineProps<{
  accent: string
}>()

const emit = defineEmits<{
  close: []
  navigate: [subsystemId: string]
}>()

// Admins always see every subsystem; non-admins are scoped to their personas;
// feature-disabled modules (e.g. commerce when ecommerce is off) are removed for
// everyone. Centralized in usePersonas so all navigation entry points agree.
const { visibleSubsystems } = usePersonas()
const { id: currentId } = useCurrentSubsystem()

// Snapshot of recently visited subsystem ids, captured once on open.
const recentIds = import.meta.client ? useLastSubsystem().recents() : []

const query = ref('')
const cursor = ref(0)
const inputRef = ref<HTMLInputElement | null>(null)

function matchesQuery(s: Subsystem): boolean {
  const q = query.value.trim().toLowerCase()
  if (!q) return true
  return s.label.toLowerCase().includes(q) || s.sub.toLowerCase().includes(q)
}

interface Block {
  id: string
  label: string
  items: Subsystem[]
}

const blocks = computed<Block[]>(() => {
  const result: Block[] = []

  // "Recent" row — only when not filtering, and never the subsystem you're in.
  if (!query.value.trim()) {
    const byId = new Map(visibleSubsystems.value.map(s => [s.id, s]))
    const recent = recentIds
      .filter(id => id !== currentId.value)
      .map(id => byId.get(id))
      .filter((s): s is Subsystem => !!s)
      .slice(0, 3)
    if (recent.length > 0) result.push({ id: 'recent', label: 'Recent', items: recent })
  }

  // Categorised groups, filtered by the active query.
  for (const g of groupSubsystems(visibleSubsystems.value)) {
    const items = g.items.filter(matchesQuery)
    if (items.length > 0) result.push({ id: g.id, label: g.label, items })
  }

  return result
})

// Flat, display-ordered list used for keyboard navigation.
const flatItems = computed<Subsystem[]>(() => blocks.value.flatMap(b => b.items))

function blockStartIndex(blockIndex: number): number {
  let n = 0
  for (let i = 0; i < blockIndex; i++) n += blocks.value[i]!.items.length
  return n
}

function activate(s: Subsystem) {
  emit('navigate', s.id)
  emit('close')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    emit('close')
  } else if (e.key === 'ArrowDown') {
    e.preventDefault()
    cursor.value = Math.min(flatItems.value.length - 1, cursor.value + 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    cursor.value = Math.max(0, cursor.value - 1)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const s = flatItems.value[cursor.value]
    if (s) activate(s)
  }
}

// Reset the highlight whenever the result set changes.
watch(query, () => { cursor.value = 0 })

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  setTimeout(() => inputRef.value?.focus(), 30)
})
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Teleport to="body">
    <Transition name="nav-modal" appear>
      <div
        class="nav-modal-backdrop"
        @click="emit('close')"
      >
        <div class="nav-modal-box" @click.stop>
          <div class="nav-modal-header">
            <div class="nav-modal-brand">
              <svg
                width="20"
                height="20"
                viewBox="0 0 512 512"
                fill="none"
                aria-label="Bosca">
                <path d="M256 262L491 138L256 21L21 138L256 262Z" fill="#00c16a" />
                <path d="M491 138L256 262V498L491 372V138Z" fill="#00dc82" />
                <path d="M21 138L256 262V498L21 372V138Z" fill="#00a155" />
              </svg>
              <span class="nav-modal-brand-text">
                <span class="nav-modal-brand-name">Bosca</span>
                <span class="nav-modal-brand-separator" />
                <span class="nav-modal-brand-studio">Studio</span>
              </span>
            </div>
            <button class="nav-modal-close" @click="emit('close')">
              <Icon name="x" :size="14" color="var(--fg-3)" />
            </button>
          </div>

          <div class="nav-modal-search">
            <Icon name="search" :size="15" color="var(--fg-3)" />
            <input
              ref="inputRef"
              v-model="query"
              class="nav-modal-search-input"
              placeholder="Filter subsystems…"
              aria-label="Filter subsystems"
            >
            <span class="nav-modal-esc mono">esc</span>
          </div>

          <div class="nav-modal-body">
            <div
              v-for="(block, bi) in blocks"
              :key="block.id"
              class="nav-block"
            >
              <div class="nav-block-label">{{ block.label }}</div>
              <div class="nav-modal-grid">
                <button
                  v-for="(s, ri) in block.items"
                  :key="`${block.id}-${s.id}`"
                  class="nav-item"
                  :class="{
                    cursor: (blockStartIndex(bi) + ri) === cursor,
                    active: s.id === currentId,
                  }"
                  :style="{ '--item-accent': s.accent }"
                  @mouseenter="cursor = blockStartIndex(bi) + ri"
                  @click="activate(s)"
                >
                  <span
                    class="nav-item-icon"
                    :style="{
                      background: `color-mix(in oklch, ${s.accent} 14%, transparent)`,
                      border: `1px solid color-mix(in oklch, ${s.accent} 24%, transparent)`,
                    }"
                  >
                    <Icon :name="s.icon" :size="14" :color="s.accent" />
                  </span>
                  <span class="nav-item-text">
                    <span class="nav-item-label">{{ s.label }}</span>
                    <span class="nav-item-desc">{{ s.sub }}</span>
                  </span>
                </button>
              </div>
            </div>

            <div v-if="flatItems.length === 0" class="nav-modal-empty">
              No subsystems match <span class="nav-modal-empty-query">"{{ query }}"</span>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.nav-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.nav-modal-box {
  width: min(860px, 100%);
  /* Cap to the visible viewport (minus the backdrop's 24px padding on each
     axis) so the box never overflows below the fold — the inner body scrolls
     instead. dvh tracks dynamic browser chrome; the 90dvh cap keeps a little
     breathing room on tall screens. */
  max-height: min(90dvh, calc(100dvh - 48px));
  background: var(--bg-0);
  border: 1px solid color-mix(in oklch, var(--fg-3) 18%, transparent);
  border-radius: var(--r-lg);
  box-shadow:
    0 24px 80px -20px rgba(0, 0, 0, 0.4),
    inset 0 0 0 1px color-mix(in oklch, var(--fg-4) 8%, transparent),
    inset 0 1px 0 0 color-mix(in oklch, #fff 7%, transparent);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.nav-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 35%, transparent);
}

.nav-modal-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.nav-modal-brand-text {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.nav-modal-brand-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
  letter-spacing: -0.01em;
}

.nav-modal-brand-separator {
  width: 1px;
  height: 16px;
  background: var(--fg-3);
  align-self: center;
}

.nav-modal-brand-studio {
  font-size: 13px;
  color: var(--fg-1);
  font-weight: 500;
}

.nav-modal-close {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-sm);
  color: var(--fg-3);
  transition: background 0.15s;
}

.nav-modal-close:hover {
  background: color-mix(in oklch, var(--fg-3) 12%, transparent);
}

.nav-modal-search {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 20px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 35%, transparent);
}

.nav-modal-search-input {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  font-size: 14px;
  color: var(--fg-0);
  font-family: inherit;
  letter-spacing: -0.005em;
}

.nav-modal-search-input::placeholder {
  color: var(--fg-3);
}

.nav-modal-esc {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  color: var(--fg-3);
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
}

.nav-modal-body {
  padding: 8px;
  overflow: auto;
}

.nav-block + .nav-block {
  margin-top: 4px;
}

.nav-block-label {
  font-size: 10px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.12em;
  font-weight: 700;
  padding: 8px 16px 4px;
}

.nav-modal-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 10px;
  transition: background 0.15s;
  width: 100%;
  text-align: left;
}

.nav-item.cursor {
  background: color-mix(in oklch, var(--item-accent) 10%, transparent);
}

.nav-item.active {
  box-shadow: inset 0 0 0 1px color-mix(in oklch, var(--item-accent) 40%, transparent);
}

.nav-item-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 32px;
  margin-top: 1px;
}

.nav-item-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.nav-item-label {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg-0);
  letter-spacing: -0.01em;
}

.nav-item-desc {
  font-size: 12px;
  color: var(--fg-3);
  line-height: 1.4;
}

.nav-modal-empty {
  padding: 40px 20px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.nav-modal-empty-query {
  color: var(--fg-1);
  font-weight: 500;
}

.nav-modal-enter-active,
.nav-modal-leave-active {
  transition: opacity 0.2s ease;
}

.nav-modal-enter-active .nav-modal-box,
.nav-modal-leave-active .nav-modal-box {
  transition: transform 0.2s ease, opacity 0.2s ease;
}

.nav-modal-enter-from,
.nav-modal-leave-to {
  opacity: 0;
}

.nav-modal-enter-from .nav-modal-box,
.nav-modal-leave-to .nav-modal-box {
  transform: scale(0.96);
  opacity: 0;
}
</style>
