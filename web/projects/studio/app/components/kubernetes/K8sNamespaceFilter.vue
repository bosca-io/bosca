<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

// Namespaces are pulled live from the selected cluster — every page
// that mounts this filter is already cluster-aware via useK8sCluster.
// Falling back to mock data here would let stale demo namespaces leak
// into a real cluster's UI, which is exactly the kind of thing that
// makes "filtering by namespace" appear broken.
const { current } = useK8sCluster()
const { data: namespacesData } = useK8sNamespaces(() => current.value?.id)
const namespaces = computed<string[]>(
  () => (namespacesData.value ?? []).map(n => n.name).sort(),
)

const { selected, isEmpty, summary, has, toggle, clear } = useK8sNamespaceFilter()

const open = ref(false)
const search = ref('')
const root = ref<HTMLElement | null>(null)

function onDocumentClick(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) {
    open.value = false
  }
}
onMounted(() => document.addEventListener('click', onDocumentClick, true))
onUnmounted(() => document.removeEventListener('click', onDocumentClick, true))

const filtered = computed(
  () => namespaces.value.filter(n => !search.value || n.toLowerCase().includes(search.value.toLowerCase())),
)
</script>

<template>
  <div ref="root" class="ns-filter">
    <button class="trigger" :class="{ active: !isEmpty, open }" @click="open = !open">
      <Icon name="tag" :size="13" :color="isEmpty ? 'var(--fg-3)' : 'var(--brand-2)'" />
      <span class="summary">{{ summary }}</span>
      <Icon name="chevronDown" :size="12" color="var(--fg-3)" />
    </button>

    <div v-if="open" class="popover" role="listbox">
      <div class="search-wrap">
        <input
          v-model="search"
          class="search"
          type="search"
          placeholder="Filter namespaces…"
          aria-label="Filter namespaces"
        >
      </div>
      <div class="list">
        <button
          v-for="ns in filtered"
          :key="ns"
          class="item"
          :class="{ on: has(ns) }"
          role="option"
          :aria-selected="has(ns)"
          @click="toggle(ns)"
        >
          <span class="check" :class="{ checked: has(ns) }">
            <Icon
              v-if="has(ns)"
              name="check"
              :size="10"
              color="var(--fg-0)" />
          </span>
          <span class="mono">{{ ns }}</span>
        </button>
        <div v-if="filtered.length === 0" class="empty">No matches.</div>
      </div>
      <div class="foot">
        <button class="link" :disabled="isEmpty" @click="clear">Clear all</button>
        <span class="muted">{{ selected.length }} selected</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ns-filter { position: relative; }

.trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-1);
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
}
.trigger:hover { background: var(--bg-3); }
.trigger.active {
  border-color: color-mix(in oklab, var(--brand-2) 50%, transparent);
  background: color-mix(in oklab, var(--brand-2) 8%, var(--bg-2));
}
.trigger.open { border-color: var(--brand-2); }
.summary { font-weight: 500; max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.popover {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 30;
  width: 260px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: 10px;
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.35);
  display: flex;
  flex-direction: column;
  max-height: 380px;
}

.search-wrap { padding: 8px; border-bottom: 1px solid var(--line); }
.search {
  width: 100%;
  padding: 6px 10px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-0);
  font: inherit;
  font-size: 12.5px;
}
.search:focus { outline: none; border-color: var(--brand-2); }

.list {
  flex: 1;
  overflow: auto;
  padding: 4px;
}
.item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 6px 8px;
  border: none;
  background: transparent;
  color: var(--fg-1);
  font: inherit;
  font-size: 12.5px;
  text-align: left;
  border-radius: 6px;
  cursor: pointer;
}
.item:hover { background: var(--bg-2); }
.item.on { background: color-mix(in oklab, var(--brand-2) 10%, transparent); }

.check {
  width: 14px;
  height: 14px;
  border-radius: 3px;
  border: 1px solid var(--line);
  display: grid;
  place-items: center;
  background: var(--bg-2);
  flex-shrink: 0;
}
.check.checked {
  background: var(--brand-2);
  border-color: var(--brand-2);
}

.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }

.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  border-top: 1px solid var(--line);
}
.link {
  background: transparent;
  border: none;
  color: var(--brand-2);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}
.link[disabled] { color: var(--fg-3); cursor: default; }
.muted { color: var(--fg-3); font-size: 11px; }
.empty { padding: 16px; text-align: center; color: var(--fg-3); font-size: 12px; }
</style>
