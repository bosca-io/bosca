<script setup lang="ts">
/**
 * Standardized search input with focus highlight and placeholder styling.
 * Wraps v-model for seamless two-way binding in list/filter views.
 *
 * `size` mirrors the convention used by Select and Button. The default `md` is
 * the full-width list-filter bar; `sm` is the compact variant that lines up
 * (same height, radius, and surface tokens) with sm Selects and Buttons when
 * placed together in a controls toolbar.
 */
const model = defineModel<string>({ default: '' })

withDefaults(defineProps<{
  placeholder?: string
  maxWidth?: string
  size?: 'sm' | 'md'
}>(), {
  placeholder: 'Search…',
  maxWidth: '340px',
  size: 'md',
})
</script>

<template>
  <input
    v-model="model"
    class="search-input"
    :class="`size-${size}`"
    type="text"
    :placeholder
    :style="{ maxWidth }"
  />
</template>

<style scoped>
.search-input {
  width: 100%;
  height: 36px;
  padding: 0 12px;
  font-size: 13px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--fg-0);
  outline: none;
}

/* Compact variant — matches sm Select / Button so the controls line up. */
.search-input.size-sm {
  height: 30px;
  padding: 0 10px;
  font-size: 12.5px;
  background: var(--bg-2);
  border-color: var(--line-2);
  border-radius: var(--r-sm);
}

.search-input::placeholder {
  color: var(--fg-3);
}

.search-input:focus {
  border-color: var(--brand-2);
}
</style>
