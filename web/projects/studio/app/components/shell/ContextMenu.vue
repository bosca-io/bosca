<script setup lang="ts">
import type { ContextMenuItem } from '~/composables/useContextMenu'

const { state, close, select } = useContextMenu()

const menuRef = ref<HTMLElement>()
const expandedId = ref<string | null>(null)
let hoverTimer: ReturnType<typeof setTimeout> | null = null

const position = computed(() => {
  if (!state.open) return { top: '0px', left: '0px' }
  return { top: `${state.y}px`, left: `${state.x}px` }
})

function hasChildren(item: Readonly<ContextMenuItem>) {
  return item.children && item.children.length > 0
}

function onItemEnter(item: Readonly<ContextMenuItem>) {
  if (hoverTimer) clearTimeout(hoverTimer)
  if (hasChildren(item)) {
    hoverTimer = setTimeout(() => { expandedId.value = item.id }, 80)
  } else {
    expandedId.value = null
  }
}

function onItemLeave() {
  if (hoverTimer) clearTimeout(hoverTimer)
  hoverTimer = setTimeout(() => { expandedId.value = null }, 150)
}

function onSubEnter() {
  if (hoverTimer) clearTimeout(hoverTimer)
}

function onSubLeave() {
  if (hoverTimer) clearTimeout(hoverTimer)
  hoverTimer = setTimeout(() => { expandedId.value = null }, 150)
}

function onClickOutside(e: MouseEvent) {
  if (menuRef.value && !menuRef.value.contains(e.target as Node)) {
    close()
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

watch(() => state.open, (v) => {
  expandedId.value = null
  if (v) {
    document.addEventListener('click', onClickOutside, true)
    document.addEventListener('keydown', onKeydown)
  } else {
    document.removeEventListener('click', onClickOutside, true)
    document.removeEventListener('keydown', onKeydown)
  }
})

onUnmounted(() => {
  document.removeEventListener('click', onClickOutside, true)
  document.removeEventListener('keydown', onKeydown)
  if (hoverTimer) clearTimeout(hoverTimer)
})

function clampStyle(el: HTMLElement) {
  const rect = el.getBoundingClientRect()
  const pad = 8
  if (rect.right > window.innerWidth - pad) {
    el.style.left = `${window.innerWidth - rect.width - pad}px`
  }
  if (rect.bottom > window.innerHeight - pad) {
    el.style.top = `${window.innerHeight - rect.height - pad}px`
  }
}

function onEnter(el: Element) {
  nextTick(() => clampStyle(el as HTMLElement))
}

function clampSubmenu(el: Element) {
  nextTick(() => {
    const sub = el as HTMLElement
    const rect = sub.getBoundingClientRect()
    const pad = 8
    if (rect.right > window.innerWidth - pad) {
      sub.classList.add('ctx-sub-left')
    }
    if (rect.bottom > window.innerHeight - pad) {
      sub.style.top = 'auto'
      sub.style.bottom = '0px'
    }
  })
}
</script>

<template>
  <Teleport to="body">
    <Transition name="ctx" @enter="onEnter">
      <div
        v-if="state.open"
        ref="menuRef"
        class="ctx-menu"
        :style="position"
        @contextmenu.prevent
      >
        <template v-for="(group, gi) in state.groups" :key="gi">
          <div v-if="gi > 0" class="ctx-sep" />
          <button
            v-if="(group.label || group.avatar) && group.id"
            class="ctx-group-label ctx-group-label--clickable"
            type="button"
            @click.stop="select(group.id)"
          >
            <span v-if="group.avatar" class="ctx-avatar">{{ group.avatar }}</span>
            <span v-if="group.label || group.subtitle" class="ctx-group-text">
              <span v-if="group.label">{{ group.label }}</span>
              <span v-if="group.subtitle" class="ctx-group-subtitle">{{ group.subtitle }}</span>
            </span>
          </button>
          <div v-else-if="group.label || group.avatar" class="ctx-group-label">
            <span v-if="group.avatar" class="ctx-avatar">{{ group.avatar }}</span>
            <span v-if="group.label || group.subtitle" class="ctx-group-text">
              <span v-if="group.label">{{ group.label }}</span>
              <span v-if="group.subtitle" class="ctx-group-subtitle">{{ group.subtitle }}</span>
            </span>
          </div>
          <template v-for="item in group.items" :key="item.id">
            <div
              v-if="hasChildren(item)"
              class="ctx-item-wrap"
              @mouseenter="onItemEnter(item)"
              @mouseleave="onItemLeave"
            >
              <button class="ctx-item" :class="{ expanded: expandedId === item.id }">
                <Icon
                  v-if="item.icon"
                  :name="item.icon"
                  :size="14"
                  :color="item.iconColor ?? 'var(--fg-3)'" />
                <span>{{ item.label }}</span>
                <Icon
                  name="chevron"
                  :size="12"
                  color="var(--fg-3)"
                  class="ctx-chevron" />
              </button>
              <Transition name="ctx-sub" @enter="clampSubmenu">
                <div
                  v-if="expandedId === item.id"
                  class="ctx-submenu"
                  @mouseenter="onSubEnter"
                  @mouseleave="onSubLeave"
                >
                  <button
                    v-for="child in item.children"
                    :key="child.id"
                    class="ctx-item"
                    :class="{ disabled: child.disabled, danger: child.danger }"
                    :disabled="child.disabled"
                    @click.stop="select(child.id)"
                  >
                    <Icon
                      v-if="child.icon"
                      :name="child.icon"
                      :size="14"
                      :color="child.iconColor ?? 'var(--fg-3)'" />
                    <span>{{ child.label }}</span>
                  </button>
                </div>
              </Transition>
            </div>
            <button
              v-else
              class="ctx-item"
              :class="{ disabled: item.disabled, danger: item.danger }"
              :disabled="item.disabled"
              @click.stop="select(item.id)"
              @mouseenter="onItemEnter(item)"
            >
              <Icon
                v-if="item.icon"
                :name="item.icon"
                :size="14"
                :color="item.iconColor ?? 'var(--fg-3)'" />
              <span>{{ item.label }}</span>
            </button>
          </template>
        </template>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.ctx-menu {
  position: fixed;
  min-width: 200px;
  max-width: 260px;
  background: color-mix(in srgb, var(--bg-1) 82%, transparent);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  box-shadow: 0 12px 40px -10px rgba(0, 0, 0, 0.45);
  padding: 4px;
  display: flex;
  flex-direction: column;
  z-index: 1100;
}

.ctx-group-label {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px 4px;
}

button.ctx-group-label {
  background: none;
  border: none;
  width: 100%;
  text-align: left;
  border-radius: 4px;
}

.ctx-group-label--clickable {
  cursor: pointer;
}

.ctx-group-label--clickable:hover {
  background: var(--bg-3);
}

.ctx-group-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.ctx-group-text > span:first-child {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.ctx-group-subtitle {
  font-size: 11px;
  color: var(--fg-3);
  font-weight: 400;
}

.ctx-avatar {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--brand-grad);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 8px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 0;
  text-transform: none;
  flex-shrink: 0;
}

.ctx-item-wrap {
  position: relative;
}

.ctx-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 4px;
  font-size: 12.5px;
  color: var(--fg-1);
  cursor: pointer;
  background: none;
  border: none;
  text-align: left;
  width: 100%;
}

.ctx-item:hover:not(.disabled),
.ctx-item.expanded {
  background: var(--bg-3);
}

.ctx-item.disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.ctx-item.danger {
  color: var(--err);
}

.ctx-item.danger:hover:not(.disabled) {
  background: color-mix(in oklch, var(--err) 10%, transparent);
}

.ctx-chevron {
  margin-left: auto;
}

.ctx-submenu {
  position: absolute;
  top: 0;
  left: 100%;
  margin-left: 4px;
  min-width: 180px;
  max-width: 240px;
  background: color-mix(in srgb, var(--bg-1) 96%, transparent);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  box-shadow: 0 12px 40px -10px rgba(0, 0, 0, 0.45);
  padding: 4px;
  display: flex;
  flex-direction: column;
  z-index: 1101;
}

.ctx-sub-left {
  left: auto;
  right: 100%;
  margin-left: 0;
  margin-right: 4px;
}

.ctx-sep {
  height: 1px;
  background: var(--line);
  margin: 6px 6px;
}

.ctx-enter-active,
.ctx-leave-active {
  transition: opacity 0.1s ease, transform 0.1s ease;
}

.ctx-enter-from,
.ctx-leave-to {
  opacity: 0;
  transform: scale(0.96);
}

.ctx-sub-enter-active,
.ctx-sub-leave-active {
  transition: opacity 0.1s ease;
}

.ctx-sub-enter-from,
.ctx-sub-leave-to {
  opacity: 0;
}
</style>
