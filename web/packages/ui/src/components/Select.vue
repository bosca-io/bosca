<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import Icon from './Icon.vue'
/**
 * Stylized select with autocomplete, multi-select tags, option icons,
 * loading state, and async data source support via onSearch callback.
 * For GraphQL-backed selects, pass an onSearch that calls useGraphQL().query()
 * and transforms the response into SelectOption[].
 */

export interface SelectOption {
  value: string
  label: string
  icon?: string
  disabled?: boolean
}

interface InternalSelectOption extends SelectOption {
  custom?: boolean
}

const props = withDefaults(defineProps<{
  options?: SelectOption[]
  placeholder?: string
  label?: string
  searchable?: boolean
  allowCustom?: boolean
  multiple?: boolean
  loading?: boolean
  icon?: string
  size?: 'sm' | 'md'
  accent?: string
  disabled?: boolean
  maxTags?: number
  onSearch?: (query: string) => Promise<SelectOption[]>
  debounce?: number
}>(), {
  placeholder: 'Select…',
  searchable: false,
  allowCustom: false,
  multiple: false,
  loading: false,
  size: 'md',
  accent: '#5ec5ff',
  disabled: false,
  maxTags: 3,
  debounce: 250,
})

const model = defineModel<string | string[] | null>()

const open = ref(false)
const searchQuery = ref('')
const highlightIndex = ref(-1)
const rootEl = ref<HTMLElement>()
const inputEl = ref<HTMLInputElement>()
const listEl = ref<HTMLElement>()
const asyncOptions = ref<InternalSelectOption[]>([])
const asyncLoading = ref(false)
// Async search results are transient — every open or keystroke replaces
// asyncOptions, so a previously picked option can drop out of the current
// result set while the model still holds its value, leaving the trigger
// unable to render a label. Remember every picked option so it always resolves.
const pickedOptions = ref(new Map<string, SelectOption>())
const dropdownEl = ref<HTMLElement>()
let debounceTimer: ReturnType<typeof setTimeout> | null = null

const dropdownStyle = ref<Record<string, string>>({})

function updateDropdownPosition() {
  const trigger = rootEl.value?.querySelector('.select-trigger') as HTMLElement | null
  if (!trigger) return
  const rect = trigger.getBoundingClientRect()
  // Clamp to the viewport so a menu sized to long option labels never runs off the right edge
  // (options ellipsize). At least the trigger's width when the trigger itself fits.
  const viewportMax = window.innerWidth - rect.left - 12
  // Open upward when there isn't room for the menu below and there's more room above, so a
  // select near the bottom of the window isn't clipped by the viewport edge.
  const MENU_MAX = 260
  const spaceBelow = window.innerHeight - rect.bottom
  const openUp = spaceBelow < MENU_MAX && rect.top > spaceBelow
  dropdownStyle.value = {
    position: 'fixed',
    left: `${rect.left}px`,
    minWidth: `${Math.min(rect.width, viewportMax)}px`,
    maxWidth: `${Math.max(rect.width, Math.min(320, viewportMax))}px`,
    ...(openUp
      ? { bottom: `${window.innerHeight - rect.top + 4}px` }
      : { top: `${rect.bottom + 4}px` }),
  }
}

const isAsync = computed(() => !!props.onSearch)
const isLoading = computed(() => props.loading || asyncLoading.value)

const baseOptions = computed<InternalSelectOption[]>(() => {
  if (isAsync.value) return asyncOptions.value ?? []
  return props.options ?? []
})

const selectedSet = computed(() => {
  if (props.multiple) {
    return new Set(Array.isArray(model.value) ? model.value : [])
  }
  return new Set(model.value ? [model.value as string] : [])
})

const selectedOption = computed(() => {
  if (props.multiple) return undefined
  const val = model.value as string | null | undefined
  if (!val) return undefined
  const allOpts = [...baseOptions.value, ...(props.options ?? [])]
  return allOpts.find(o => o.value === val) ?? pickedOptions.value.get(val)
})

const displayLabel = computed(() => selectedOption.value?.label ?? '')

const leadIcon = computed(() => {
  if (props.multiple) return selectedOptions.value.length > 0 ? undefined : props.icon
  return selectedOption.value?.icon ?? props.icon
})

const selectedOptions = computed(() => {
  if (!props.multiple) return []
  const vals = Array.isArray(model.value) ? model.value : []
  const allOpts = [...baseOptions.value, ...(props.options ?? [])]
  const optMap = new Map(allOpts.map(o => [o.value, o]))
  return vals
    .map(v => optMap.get(v) ?? pickedOptions.value.get(v) ?? { value: v, label: v })
})

const filteredOptions = computed(() => {
  const q = searchQuery.value.toLowerCase().trim()
  const options = !q || isAsync.value
    ? baseOptions.value
    : baseOptions.value.filter(o =>
        o.label.toLowerCase().includes(q)
        || o.value.toLowerCase().includes(q),
      )

  const customValue = searchQuery.value.trim()
  const hasExactOption = baseOptions.value.some(option => option.value.toLowerCase() === q)
  if (props.allowCustom && props.searchable && customValue && !hasExactOption) {
    return [...options, { value: customValue, label: `Use “${customValue}”`, custom: true }]
  }
  return options
})

const measuredWidth = ref<number>(0)

function measureWidth() {
  const trigger = rootEl.value?.querySelector('.select-trigger') as HTMLElement | null
  if (!trigger) return

  const probe = document.createElement('span')
  probe.style.cssText = 'visibility:hidden;position:absolute;white-space:nowrap;pointer-events:none;'

  const style = getComputedStyle(trigger)
  probe.style.font = style.font
  probe.style.fontFeatureSettings = style.fontFeatureSettings
  probe.style.letterSpacing = style.letterSpacing

  document.body.appendChild(probe)

  const labels = (props.options ?? []).map(o => o.label)
  labels.push(props.placeholder)

  let widestText = 0
  for (const label of labels) {
    probe.textContent = label
    widestText = Math.max(widestText, probe.offsetWidth)
  }

  document.body.removeChild(probe)

  const hasIcon = !!(props.icon || (props.options ?? []).some(o => o.icon))
  const iconWidth = hasIcon ? 14 + 5 : 0
  const chevronWidth = 13 + 5
  const spacerWidth = 4 + 5
  const px = parseFloat(style.paddingLeft) + parseFloat(style.paddingRight)
  const bx = parseFloat(style.borderLeftWidth) + parseFloat(style.borderRightWidth)

  measuredWidth.value = Math.ceil(widestText + iconWidth + chevronWidth + spacerWidth + px + bx)
}

onMounted(async () => {
  await document.fonts.ready
  measureWidth()
})
watch(() => props.options, () => nextTick(measureWidth))
watch(() => props.placeholder, () => nextTick(measureWidth))

async function executeSearch(q: string) {
  if (!props.onSearch) return
  asyncLoading.value = true
  try {
    asyncOptions.value = await props.onSearch(q)
  }
  finally {
    asyncLoading.value = false
  }
}

function debouncedSearch(q: string) {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => executeSearch(q), props.debounce)
}

function toggleOpen() {
  if (props.disabled || isLoading.value) return
  open.value = !open.value
  if (open.value) {
    searchQuery.value = ''
    highlightIndex.value = -1
    updateDropdownPosition()
    if (isAsync.value) executeSearch('')
    nextTick(() => inputEl.value?.focus())
  }
}

function selectOption(opt: InternalSelectOption) {
  if (opt.disabled) return
  pickedOptions.value.set(opt.value, opt.custom ? { value: opt.value, label: opt.value } : opt)
  if (props.multiple) {
    const current = Array.isArray(model.value) ? [...model.value] : []
    const idx = current.indexOf(opt.value)
    if (idx >= 0) current.splice(idx, 1)
    else current.push(opt.value)
    model.value = current
    searchQuery.value = ''
    nextTick(() => inputEl.value?.focus())
  }
  else {
    model.value = opt.value
    open.value = false
    searchQuery.value = ''
  }
}

function removeTag(value: string) {
  if (!props.multiple) return
  const current = Array.isArray(model.value) ? [...model.value] : []
  const idx = current.indexOf(value)
  if (idx >= 0) current.splice(idx, 1)
  model.value = current
}

function onKeydown(e: KeyboardEvent) {
  const opts = filteredOptions.value
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    highlightIndex.value = Math.min(highlightIndex.value + 1, opts.length - 1)
    scrollToHighlighted()
  }
  else if (e.key === 'ArrowUp') {
    e.preventDefault()
    highlightIndex.value = Math.max(highlightIndex.value - 1, 0)
    scrollToHighlighted()
  }
  else if (e.key === 'Enter') {
    e.preventDefault()
    if (highlightIndex.value >= 0 && highlightIndex.value < opts.length) {
      selectOption(opts[highlightIndex.value]!)
    }
  }
  else if (e.key === 'Escape') {
    open.value = false
  }
  else if (e.key === 'Backspace' && !searchQuery.value && props.multiple) {
    const current = Array.isArray(model.value) ? [...model.value] : []
    if (current.length > 0) {
      current.pop()
      model.value = current
    }
  }
}

function scrollToHighlighted() {
  nextTick(() => {
    const el = listEl.value?.querySelector('[data-highlighted="true"]') as HTMLElement | null
    el?.scrollIntoView({ block: 'nearest' })
  })
}

function onClickOutside(e: MouseEvent) {
  const target = e.target as Node
  if (rootEl.value && !rootEl.value.contains(target) && !(dropdownEl.value && dropdownEl.value.contains(target))) {
    open.value = false
  }
}

watch(open, (isOpen) => {
  if (isOpen) {
    document.addEventListener('mousedown', onClickOutside)
  }
  else {
    document.removeEventListener('mousedown', onClickOutside)
  }
})

watch(searchQuery, (q) => {
  highlightIndex.value = 0
  if (isAsync.value) debouncedSearch(q)
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onClickOutside)
  if (debounceTimer) clearTimeout(debounceTimer)
})
</script>

<template>
  <div class="select-field">
    <label v-if="label" class="select-label">{{ label }}</label>
    <div
      ref="rootEl"
      class="select-root"
      :class="[
        { open, disabled, loading: isLoading },
        `size-${size}`,
      ]"
      :style="measuredWidth ? { minWidth: `min(${measuredWidth}px, 100%)` } : undefined"
    >
    <!-- Trigger -->
    <div
      class="select-trigger"
      @click="toggleOpen"
    >
      <!-- Leading icon: selected option's icon takes priority over default -->
      <Icon
        v-if="leadIcon"
        :name="leadIcon"
        :size="14"
        :color="selectedOption?.icon ? accent : 'var(--fg-3)'"
        class="select-icon-lead"
      />

      <!-- Multi-select tags -->
      <template v-if="multiple && selectedOptions.length > 0">
        <span
          v-for="opt in selectedOptions.slice(0, maxTags)"
          :key="opt.value"
          class="select-tag"
        >
          <Icon
            v-if="opt.icon"
            :name="opt.icon"
            :size="11"
            color="var(--fg-2)"
          />
          <span class="select-tag-label">{{ opt.label }}</span>
          <button
            class="select-tag-remove"
            @click.stop="removeTag(opt.value)"
          >
            <Icon name="x" :size="10" color="var(--fg-3)" />
          </button>
        </span>
        <span
          v-if="selectedOptions.length > maxTags"
          class="select-tag select-tag-overflow"
        >
          +{{ selectedOptions.length - maxTags }}
        </span>
      </template>

      <!-- Search input (replaces display label when open) -->
      <input
        v-if="searchable && open"
        ref="inputEl"
        v-model="searchQuery"
        class="select-search"
        size="1"
        :placeholder="displayLabel || placeholder"
        @keydown="onKeydown"
        @click.stop
      />

      <!-- Display label (when closed or not searchable) -->
      <span
        v-else-if="!multiple || selectedOptions.length === 0"
        class="select-display"
        :class="{ 'is-placeholder': !displayLabel }"
      >
        {{ displayLabel || placeholder }}
      </span>

      <!-- Spacer (not needed when search input is visible — it has flex:1) -->
      <span v-if="!(searchable && open)" class="select-spacer" />

      <!-- Loading spinner -->
      <Icon
        v-if="isLoading"
        name="spinner"
        :size="14"
        color="var(--fg-3)"
        class="select-spinner"
      />

      <!-- Chevron -->
      <Icon
        v-else
        name="chevronDown"
        :size="13"
        color="var(--fg-4)"
        class="select-chevron"
        :class="{ flipped: open }"
      />
    </div>

    <!-- Dropdown (teleported to body to avoid overflow clipping) -->
    <Teleport to="body">
    <Transition name="dropdown">
      <div
        v-if="open"
        ref="dropdownEl"
        class="select-dropdown"
        :style="dropdownStyle"
      >
        <!-- Options list -->
        <div ref="listEl" class="select-list">
          <!-- Async loading indicator inside dropdown -->
          <div v-if="asyncLoading" class="select-async-loading">
            <Icon name="spinner" :size="14" color="var(--fg-4)" class="select-spinner" />
            <span>Searching…</span>
          </div>

          <div
            v-for="(opt, i) in filteredOptions"
            :key="opt.value"
            class="select-option"
            :class="{
              selected: selectedSet.has(opt.value),
              highlighted: i === highlightIndex,
              disabled: opt.disabled,
            }"
            :data-highlighted="i === highlightIndex"
            @click.stop="selectOption(opt)"
            @mouseenter="highlightIndex = i"
          >
            <!-- Multi-select checkbox -->
            <div v-if="multiple" class="select-check">
              <div
                class="select-checkbox"
                :class="{ checked: selectedSet.has(opt.value) }"
                :style="selectedSet.has(opt.value) ? { background: accent, borderColor: accent } : {}"
              >
                <svg v-if="selectedSet.has(opt.value)" width="10" height="10" viewBox="0 0 10 10" fill="none">
                  <path d="M2 5 l2 2 l4-4" stroke="white" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </div>
            </div>

            <!-- Option icon -->
            <Icon
              v-if="opt.custom"
              name="plus"
              :size="14"
              color="var(--fg-3)"
            />
            <Icon
              v-else-if="opt.icon"
              :name="opt.icon"
              :size="14"
              :color="selectedSet.has(opt.value) ? accent : 'var(--fg-3)'"
            />

            <!-- Label -->
            <span class="select-option-label">{{ opt.label }}</span>

            <!-- Single-select checkmark -->
            <svg
              v-if="!multiple && selectedSet.has(opt.value)"
              class="select-option-tick"
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              :stroke="accent"
              stroke-width="2.2"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path d="M5 12 l5 5 L19 7" />
            </svg>
          </div>

          <!-- Empty state -->
          <div v-if="filteredOptions.length === 0 && !asyncLoading" class="select-empty">
            {{ isAsync ? 'No results found' : 'No matches' }}
          </div>
        </div>
      </div>
    </Transition>
    </Teleport>
    </div>
  </div>
</template>

<style scoped>
.select-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.select-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.select-root {
  position: relative;
  display: inline-flex;
}

/* ── Trigger ─────────────────────────────────────────────────────────── */
.select-trigger {
  display: flex;
  align-items: center;
  gap: 5px;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  cursor: pointer;
  color: var(--fg-1);
  transition: border-color 0.15s;
  width: 100%;
  box-sizing: border-box;
  padding: 0 10px;
  font-size: 13px;
  height: 32px;
}

.size-sm .select-trigger {
  font-size: 12.5px;
  height: 30px;
}

.select-trigger:hover {
  border-color: var(--fg-4);
}

.select-root.open .select-trigger {
  border-color: var(--brand-2);
}

.select-root.disabled .select-trigger {
  opacity: 0.5;
  pointer-events: none;
}

.select-root.loading .select-trigger {
  pointer-events: none;
}

/* ── Display / placeholder ───────────────────────────────────────────── */
.select-display {
  white-space: nowrap;
  line-height: normal;
}

.select-display.is-placeholder {
  color: var(--fg-3);
}

.select-display.is-hidden {
  visibility: hidden;
}

.select-icon-lead {
  flex-shrink: 0;
}

.select-spacer {
  flex: 1;
  min-width: 4px;
}

/* ── Search input ────────────────────────────────────────────────────── */
.select-search {
  background: none;
  border: none;
  outline: none;
  font: inherit;
  color: var(--fg-1);
  padding: 0;
  margin: 0;
  min-width: 0;
  flex: 1;
}

.select-search::placeholder {
  color: var(--fg-4);
}

/* ── Chevron ─────────────────────────────────────────────────────────── */
.select-chevron {
  flex-shrink: 0;
  transition: transform 0.15s;
}

.select-chevron.flipped {
  transform: rotate(180deg);
}

/* ── Spinner ─────────────────────────────────────────────────────────── */
.select-spinner {
  flex-shrink: 0;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── Tags (multi-select) ─────────────────────────────────────────────── */
.select-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 1px 6px;
  background: var(--bg-3);
  border: 1px solid var(--line-2);
  border-radius: var(--r-xs);
  font-size: 11.5px;
  color: var(--fg-1);
  line-height: 1.4;
  white-space: nowrap;
  max-width: 120px;
}

.select-tag-label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.select-tag-remove {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 3px;
  flex-shrink: 0;
  transition: background 0.1s;
}

.select-tag-remove:hover {
  background: var(--bg-4);
}

.select-tag-overflow {
  font-weight: 540;
  color: var(--fg-3);
  padding: 1px 5px;
}

/* ── Dropdown ────────────────────────────────────────────────────────── */
.select-dropdown {
  max-width: 320px;
  background: color-mix(in srgb, var(--bg-2) 92%, transparent);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  box-shadow: 0 12px 40px -10px rgba(0, 0, 0, 0.6);
  z-index: 10001;
  overflow: hidden;
}

.select-list {
  max-height: 240px;
  overflow-y: auto;
  padding: 4px;
}

/* ── Async loading ───────────────────────────────────────────────────── */
.select-async-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 12px;
  font-size: 12.5px;
  color: var(--fg-4);
}

/* ── Options ─────────────────────────────────────────────────────────── */
.select-option {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 8px;
  border-radius: var(--r-xs);
  font-size: 13px;
  color: var(--fg-1);
  cursor: pointer;
  transition: background 0.1s;
}

.select-option.highlighted {
  background: var(--bg-3);
}

.select-option.selected {
  font-weight: 520;
}

.select-option.disabled {
  opacity: 0.4;
  pointer-events: none;
}

.select-option-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.select-option-tick {
  flex-shrink: 0;
  margin-left: auto;
}

/* ── Multi-select checkbox ───────────────────────────────────────────── */
.select-check {
  flex-shrink: 0;
}

.select-checkbox {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  border: 1.5px solid var(--line-2);
  background: var(--bg-2);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.1s, border-color 0.1s;
}

.select-checkbox.checked {
  border-color: transparent;
}

/* ── Empty state ─────────────────────────────────────────────────────── */
.select-empty {
  padding: 16px;
  text-align: center;
  font-size: 12.5px;
  color: var(--fg-4);
}

/* ── Dropdown transition ─────────────────────────────────────────────── */
.dropdown-enter-active,
.dropdown-leave-active {
  transition: opacity 0.12s ease, transform 0.12s ease;
}

.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
