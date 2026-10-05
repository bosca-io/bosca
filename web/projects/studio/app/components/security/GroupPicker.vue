<script setup lang="ts">
import gql from 'graphql-tag'

interface Group {
  id: string
  name: string
  description: string | null
  type: string
}

const props = defineProps<{
  label: string
  hint?: string
  placeholder?: string
  accent?: string
}>()

/**
 * Two-way binding holds group **names** — not IDs — because downstream
 * authorization (gateway proxy, JWT claims) compares against the
 * `groups` claim which Bosca conventionally populates with names. The
 * picker resolves names via search lookup but persists names.
 */
const model = defineModel<string[]>({ default: () => [] })

const { useAsyncQuery } = useGraphQL()

const searchTerm = ref('')

const searchGql = gql`
  query GroupPickerSearch($q: String!) {
    security {
      groups {
        find(nameOrDescription: $q, offset: 0, limit: 50) {
          id
          name
          description
          type
        }
      }
    }
  }
`

const { data: searchData } = useAsyncQuery<{
  security: { groups: { find: Group[] } }
}>(`group-picker-${useId()}`, searchGql, { q: searchTerm })

const searchResults = computed(() => searchData.value?.security?.groups?.find ?? [])

const availableResults = computed(() =>
  searchResults.value.filter(g => !model.value.includes(g.name)),
)

function add(name: string) {
  if (!model.value.includes(name)) {
    model.value = [...model.value, name]
  }
}

function remove(name: string) {
  model.value = model.value.filter(g => g !== name)
}

function labelFor(name: string): string {
  const found = searchResults.value.find(g => g.name === name)
  return found?.description || name
}
</script>

<template>
  <div class="group-picker">
    <label class="picker-label">{{ props.label }}</label>
    <div v-if="model.length === 0" class="empty">No groups selected. Search below to add.</div>
    <div v-else class="chips">
      <span v-for="name in model" :key="name" class="chip">
        <span class="chip-label">{{ labelFor(name) }}</span>
        <code class="chip-name">{{ name }}</code>
        <button
          type="button"
          class="chip-x"
          :aria-label="`Remove ${name}`"
          @click="remove(name)">
          <Icon name="x" :size="11" color="var(--fg-3)" />
        </button>
      </span>
    </div>
    <TextInput
      v-model="searchTerm"
      :placeholder="props.placeholder ?? 'Search groups by name or description…'"
      :accent="props.accent"
    />
    <div v-if="searchTerm && availableResults.length > 0" class="results">
      <button
        v-for="g in availableResults"
        :key="g.id"
        type="button"
        class="result"
        @click="add(g.name)">
        <span class="result-name">{{ g.description || g.name }}</span>
        <code class="result-code">{{ g.name }}</code>
        <Badge color="var(--fg-3)" small>{{ g.type }}</Badge>
      </button>
    </div>
    <p v-if="props.hint" class="hint">{{ props.hint }}</p>
  </div>
</template>

<style scoped>
.group-picker {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

/* TextInput's root is .text-input-root — force it to stretch the
   picker's full width so the search field doesn't shrink to a
   placeholder's intrinsic size. */
.group-picker :deep(.text-input-root) {
  width: 100%;
}

.picker-label {
  font-size: 12px;
  color: var(--fg-2);
  font-weight: 500;
}

.empty {
  font-size: 12px;
  color: var(--fg-3);
  font-style: italic;
  padding: 6px 0;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 6px 0;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px 4px 8px;
  background: var(--bg-2);
  border: 1px solid var(--bg-3);
  border-radius: 6px;
  font-size: 12px;
}

.chip-label {
  color: var(--fg-1);
}

.chip-name {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11px;
  color: var(--fg-3);
}

.chip-x {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: none;
  background: transparent;
  cursor: pointer;
  border-radius: 4px;
  padding: 0;
}

.chip-x:hover {
  background: var(--bg-3);
}

.results {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 220px;
  overflow-y: auto;
  border: 1px solid var(--bg-3);
  border-radius: 6px;
  background: var(--bg-1);
}

.result {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: transparent;
  border: none;
  cursor: pointer;
  text-align: left;
  font-size: 12px;
  color: var(--fg-1);
}

.result:hover {
  background: var(--bg-2);
}

.result-name {
  flex: 1;
}

.result-code {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11px;
  color: var(--fg-3);
}

.hint {
  font-size: 11px;
  color: var(--fg-3);
  margin: 4px 0 0;
}
</style>
