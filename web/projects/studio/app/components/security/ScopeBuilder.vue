<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

interface ScopeInfo {
  name: string
  description: string
}

const props = defineProps<{
  availableScopes: ScopeInfo[]
  accent?: string
  label?: string
}>()

const model = defineModel<string[]>({ default: () => [] })

const { query: gqlQuery } = useGraphQL()

const platformScopes = computed(() =>
  props.availableScopes.filter(s => !s.name.startsWith('artifacts:')),
)
const artifactScopes = computed(() =>
  props.availableScopes.filter(s => s.name.startsWith('artifacts:')),
)

const hasAnyScopesFromApi = computed(() => props.availableScopes.length > 0)

function isSelected(scope: string): boolean {
  return model.value.includes(scope)
}

function toggle(scope: string) {
  const current = [...model.value]
  const idx = current.indexOf(scope)
  if (idx >= 0) current.splice(idx, 1)
  else current.push(scope)
  model.value = current
}

function remove(scope: string) {
  model.value = model.value.filter(s => s !== scope)
}

// --- Artifact scope builder ---
const namespacesGql = gql`
  query GetNamespacesForScopeBuilder {
    artifactsAdmin {
      namespaces {
        id
        name
        repositories {
          id
          name
          type
          versions {
            id
            version
          }
        }
      }
    }
  }
`

interface Namespace {
  id: string
  name: string
  repositories: Array<{
    id: string
    name: string
    type: string
    versions: Array<{ id: string; version: string }>
  }>
}

const namespaces = ref<Namespace[]>([])
const namespacesLoaded = ref(false)

async function loadNamespaces() {
  if (namespacesLoaded.value) return
  try {
    const result = await gqlQuery<{ artifactsAdmin?: { namespaces?: Namespace[] } }>(namespacesGql)
    namespaces.value = result?.artifactsAdmin?.namespaces ?? []
    namespacesLoaded.value = true
  } catch {
    // Namespaces not available — builder still works with manual entry
  }
}

const showBuilder = ref(false)
const builderType = ref('*')
const builderPath = ref('*')
const builderVersion = ref('*')
const builderActions = ref<string[]>(['pull'])

function openBuilder() {
  loadNamespaces()
  builderType.value = '*'
  builderPath.value = '*'
  builderVersion.value = '*'
  builderActions.value = ['pull']
  showBuilder.value = true
}

const typeOptions: SelectOption[] = [
  { value: '*', label: 'Any type' },
  { value: 'docker', label: 'Docker' },
  { value: 'helm', label: 'Helm' },
  { value: 'maven', label: 'Maven' },
  { value: 'npm', label: 'npm' },
  { value: 'raw', label: 'Raw' },
  { value: 'ml', label: 'ML' },
]

const pathOptions = computed<SelectOption[]>(() => {
  const opts: SelectOption[] = [{ value: '*', label: 'Any path (*)' }]
  for (const ns of namespaces.value) {
    opts.push({ value: `${ns.name}/*`, label: `${ns.name}/* (all repos)` })
    const repos = ns.repositories.filter(r => {
      if (builderType.value === '*') return true
      return r.type === builderType.value
    })
    for (const repo of repos) {
      opts.push({ value: `${ns.name}/${repo.name}`, label: `${ns.name}/${repo.name}` })
    }
  }
  return opts
})

const versionOptions = computed<SelectOption[]>(() => {
  const opts: SelectOption[] = [{ value: '*', label: 'Any version (*)' }]
  if (builderPath.value === '*' || builderPath.value.endsWith('/*')) return opts

  const parts = builderPath.value.split('/')
  if (parts.length < 2) return opts
  const nsName = parts[0]
  const repoName = parts.slice(1).join('/')

  const ns = namespaces.value.find(n => n.name === nsName)
  if (!ns) return opts

  const repo = ns.repositories.find(r => r.name === repoName)
  if (!repo) return opts

  for (const v of repo.versions) {
    opts.push({ value: v.version, label: v.version })
  }
  return opts
})

const builtScope = computed(() => {
  if (builderActions.value.length === 0) return null
  return `artifacts:${builderType.value}:${builderPath.value}:${builderVersion.value}:${builderActions.value.join(',')}`
})

function toggleAction(action: string) {
  const idx = builderActions.value.indexOf(action)
  if (idx >= 0) builderActions.value.splice(idx, 1)
  else builderActions.value.push(action)
}

function addBuiltScope() {
  if (!builtScope.value) return
  if (model.value.includes(builtScope.value)) return
  model.value = [...model.value, builtScope.value]
  showBuilder.value = false
}

// --- Custom scope entry ---
const showCustom = ref(false)
const customScope = ref('')

function addCustom() {
  const s = customScope.value.trim()
  if (!s || model.value.includes(s)) return
  model.value = [...model.value, s]
  customScope.value = ''
  showCustom.value = false
}
</script>

<template>
  <div class="scope-builder">
    <div v-if="label" class="scope-builder-label">{{ label }}</div>

    <div v-if="platformScopes.length" class="scope-group">
      <div class="scope-group-label">Platform</div>
      <div class="scope-chips">
        <button
          v-for="scope in platformScopes"
          :key="scope.name"
          class="scope-chip"
          :class="{ active: isSelected(scope.name) }"
          :title="scope.description"
          @click="toggle(scope.name)"
        >
          {{ scope.name }}
        </button>
      </div>
    </div>

    <div v-if="artifactScopes.length" class="scope-group">
      <div class="scope-group-label">Artifacts (broad)</div>
      <div class="scope-chips">
        <button
          v-for="scope in artifactScopes"
          :key="scope.name"
          class="scope-chip"
          :class="{ active: isSelected(scope.name) }"
          :title="scope.description"
          @click="toggle(scope.name)"
        >
          {{ scope.name }}
        </button>
      </div>
    </div>

    <!-- Fine-grained artifact scopes -->
    <div class="scope-group">
      <div class="scope-group-label-row">
        <div class="scope-group-label">Fine-grained artifact scopes</div>
        <Button size="sm" icon="plus" @click="openBuilder">Add rule</Button>
      </div>

      <div v-if="model.filter(s => s.startsWith('artifacts:') && s.split(':').length === 5).length > 0" class="scope-tags">
        <span
          v-for="scope in model.filter(s => s.startsWith('artifacts:') && s.split(':').length === 5)"
          :key="scope"
          class="scope-tag"
        >
          {{ scope }}
          <button class="scope-tag-remove" @click="remove(scope)">&times;</button>
        </span>
      </div>
      <div v-else class="scope-hint">
        No fine-grained artifact scopes. Use "Add rule" to restrict access to specific registries.
      </div>
    </div>

    <!-- Artifact scope builder -->
    <div v-if="showBuilder" class="builder">
      <div class="builder-header">
        <div class="scope-group-label">Artifact Scope Builder</div>
        <div class="builder-hint mono">artifacts:&lt;type&gt;:&lt;path&gt;:&lt;version&gt;:&lt;actions&gt;</div>
      </div>

      <Select
        v-model="builderType"
        :options="typeOptions"
        label="Registry Type"
        :accent="accent" />

      <Select
        v-model="builderPath"
        :options="pathOptions"
        label="Namespace / Repository"
        searchable
        :accent="accent"
      />

      <Select
        v-model="builderVersion"
        :options="versionOptions"
        label="Version / Tag"
        searchable
        :accent="accent"
      />

      <div class="builder-actions">
        <div class="builder-actions-label">Actions</div>
        <div class="scope-chips">
          <button class="scope-chip" :class="{ active: builderActions.includes('pull') }" @click="toggleAction('pull')">pull</button>
          <button class="scope-chip" :class="{ active: builderActions.includes('push') }" @click="toggleAction('push')">push</button>
          <button class="scope-chip" :class="{ active: builderActions.includes('admin') }" @click="toggleAction('admin')">admin</button>
        </div>
      </div>

      <div v-if="builtScope" class="builder-preview">
        <div class="builder-preview-label">Preview</div>
        <code class="mono">{{ builtScope }}</code>
      </div>
      <div v-else class="scope-hint">Select at least one action.</div>

      <div class="builder-footer">
        <Button size="sm" @click="showBuilder = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!builtScope"
          @click="addBuiltScope">Add Scope</Button>
      </div>
    </div>

    <!-- Selected scopes summary -->
    <div v-if="model.length > 0" class="scope-group">
      <div class="scope-group-label">Selected ({{ model.length }})</div>
      <div class="scope-tags">
        <span v-for="scope in model" :key="scope" class="scope-tag">
          {{ scope }}
          <button class="scope-tag-remove" @click="remove(scope)">&times;</button>
        </span>
      </div>
    </div>

    <!-- Empty state -->
    <div v-if="model.length === 0 && !showBuilder" class="scope-hint">
      <template v-if="!hasAnyScopesFromApi">
        No scopes available from the server. Use the artifact scope builder or custom entry below, or leave empty for unrestricted access.
      </template>
      <template v-else>
        Select scopes above to restrict this token's permissions, or leave empty for unrestricted access.
      </template>
    </div>

    <!-- Custom scope entry -->
    <div class="scope-custom">
      <template v-if="showCustom">
        <div class="scope-custom-row">
          <TextInput
            v-model="customScope"
            placeholder="e.g. artifacts:docker:myns/*:*:pull"
            size="sm"
            mono
            @keyup.enter="addCustom"
          />
          <Button
            size="sm"
            :accent="accent"
            primary
            :disabled="!customScope.trim()"
            @click="addCustom">Add</Button>
          <Button size="sm" @click="showCustom = false">Cancel</Button>
        </div>
      </template>
      <Button
        v-else
        size="sm"
        icon="plus"
        @click="showCustom = true">Add custom scope</Button>
    </div>
  </div>
</template>

<style scoped>
.scope-builder {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.scope-builder-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.scope-group-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.scope-group-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 6px;
}

.scope-group-label-row .scope-group-label {
  margin-bottom: 0;
}

.scope-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.scope-chip {
  font-size: 12px;
  font-family: var(--font-mono);
  padding: 3px 10px;
  border-radius: var(--r-xs);
  border: 1px solid var(--line-2);
  background: var(--bg-2);
  color: var(--fg-2);
  cursor: pointer;
  transition: all 0.12s;
}

.scope-chip:hover {
  border-color: var(--fg-4);
  color: var(--fg-1);
}

.scope-chip.active {
  border-color: v-bind('accent || "var(--brand-2)"');
  background: color-mix(in srgb, v-bind('accent || "var(--brand-2)"') 15%, transparent);
  color: var(--fg-0);
}

.scope-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.scope-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-family: var(--font-mono);
  padding: 2px 8px;
  border-radius: var(--r-xs);
  background: var(--bg-3);
  color: var(--fg-1);
}

.scope-tag-remove {
  font-size: 14px;
  line-height: 1;
  color: var(--fg-4);
  cursor: pointer;
  background: none;
  border: none;
  padding: 0;
}

.scope-tag-remove:hover {
  color: var(--error);
}

.scope-hint {
  font-size: 11px;
  color: var(--fg-4);
  font-style: italic;
}

.scope-custom-row {
  display: flex;
  align-items: flex-end;
  gap: 6px;
}

.scope-custom-row > :first-child {
  flex: 1;
}

/* Artifact scope builder */
.builder {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  background: var(--bg-2);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
}

.builder-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.builder-header .scope-group-label {
  margin-bottom: 0;
}

.builder-hint {
  font-size: 11px;
  color: var(--fg-4);
}

.builder-actions-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
  margin-bottom: 6px;
}

.builder-preview {
  padding: 10px 12px;
  background: var(--bg-3);
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
}

.builder-preview-label {
  font-size: 10px;
  font-weight: 600;
  color: var(--fg-4);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 4px;
}

.builder-preview code {
  font-size: 12px;
  color: var(--fg-0);
}

.builder-footer {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}
</style>
