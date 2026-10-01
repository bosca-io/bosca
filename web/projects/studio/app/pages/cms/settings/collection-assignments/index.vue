<script setup lang="ts">
/**
 * Auto-assign collection rules. Each rule maps a content attribute (key =
 * value) to a target collection slug; the backend assigns matching content to
 * that collection automatically. Rules are stored in the
 * `auto.assign.collection.collection` and `auto.assign.collection.metadata`
 * configurations.
 */
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

interface AssignmentRule {
  key: string
  value: string
  slug: string
  _id: number
}

interface ConfigurationData {
  id: string
  key: string
  description: string | null
  public: boolean
  value: { attributes?: Array<{ key: string; value: string; slug: string }> } | null
  permissions: Array<{ action: string; group: { id: string; name: string } }>
}

const COLLECTION_KEY = 'auto.assign.collection.collection'
const METADATA_KEY = 'auto.assign.collection.metadata'

const configurationGql = gql`
  query GetAutoAssignConfigurations($collectionKey: String!, $metadataKey: String!) {
    configurations {
      collection: configuration(key: $collectionKey) {
        id key description public value
        permissions { action group { id name } }
      }
      metadata: configuration(key: $metadataKey) {
        id key description public value
        permissions { action group { id name } }
      }
    }
  }
`

const setConfigurationGql = gql`
  mutation SetAutoAssignConfiguration($configuration: ConfigurationInput!) {
    configurations { setConfiguration(configuration: $configuration) { id } }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  configurations: { collection: ConfigurationData | null; metadata: ConfigurationData | null }
}>('cms-collection-assignments', configurationGql, {
  collectionKey: COLLECTION_KEY,
  metadataKey: METADATA_KEY,
})

let nextId = 0

const collectionRules = ref<AssignmentRule[]>([])
const metadataRules = ref<AssignmentRule[]>([])

watch(data, (d) => {
  const toRules = (config: ConfigurationData | null): AssignmentRule[] =>
    (config?.value?.attributes ?? []).map(a => ({ ...a, _id: nextId++ }))
  collectionRules.value = toRules(d?.configurations?.collection ?? null)
  metadataRules.value = toRules(d?.configurations?.metadata ?? null)
}, { immediate: true })

// ── Collection slug search ───────────────────────────────────────────────────
const collectionSearchGql = gql`
  query SearchAssignmentCollections($query: String!, $filter: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: [$filter]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          collection { id name slug }
        }
      }
    }
  }
`

async function searchCollections(q: string): Promise<SelectOption[]> {
  const result = await gqlQuery<{
    search: { search: { documents: Array<{ collection: { id: string; name: string; slug: string | null } | null }> } }
  }>(collectionSearchGql, { query: q, filter: '_type = "collection"', limit: 20, offset: 0 })
  return (result?.search?.search?.documents ?? [])
    .map(d => d.collection)
    .filter((c): c is { id: string; name: string; slug: string } => c != null && !!c.slug)
    .map(c => ({ value: c.slug, label: `${c.name} (${c.slug})` }))
}

// ── Rule editing ─────────────────────────────────────────────────────────────
function addRule(rules: AssignmentRule[]) {
  rules.push({ key: '', value: '', slug: '', _id: nextId++ })
}

function removeRule(rules: AssignmentRule[], rule: AssignmentRule) {
  const index = rules.indexOf(rule)
  if (index !== -1) rules.splice(index, 1)
}

const savingKey = ref<string | null>(null)

async function saveRules(key: string, rules: AssignmentRule[], config: ConfigurationData | null) {
  if (savingKey.value) return
  savingKey.value = key
  try {
    await gqlMutation(setConfigurationGql, {
      configuration: {
        key,
        description: config?.description || `Auto assign configuration for ${key.split('.').pop()}`,
        value: { attributes: rules.map(({ _id, ...rule }) => rule) },
        public: config?.public || false,
        permissions: config?.permissions?.map(p => ({ action: p.action, groupId: p.group.id })) ?? [],
      },
    })
    toast.success('Configuration saved')
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to save configuration')
  } finally {
    savingKey.value = null
  }
}

const sections = computed(() => [
  {
    title: 'Collections',
    subtitle: 'Assign collections to a parent collection by attribute',
    key: COLLECTION_KEY,
    rules: collectionRules.value,
    config: data.value?.configurations?.collection ?? null,
  },
  {
    title: 'Metadata',
    subtitle: 'Assign metadata to a collection by attribute',
    key: METADATA_KEY,
    rules: metadataRules.value,
    config: data.value?.configurations?.metadata ?? null,
  },
])
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Settings', 'Collection Assignments')"
        title="Collection Assignments"
        subtitle="Automatically assign content to collections by attribute"
      />
    </template>

    <div v-if="status === 'pending' && !data" class="loading-state">Loading…</div>

    <template v-else>
      <SectionCard
        v-for="section in sections"
        :key="section.key"
        :title="section.title"
      >
        <div class="rules">
          <p class="rules-hint">{{ section.subtitle }}. Content whose attribute matches a rule is added to the rule's collection.</p>

          <div v-if="!section.rules.length" class="empty-msg">No rules configured.</div>

          <div v-for="rule in section.rules" :key="rule._id" class="rule-row">
            <TextInput
              v-model="rule.key"
              label="Attribute Key"
              placeholder="e.g. type"
              size="sm" />
            <TextInput
              v-model="rule.value"
              label="Attribute Value"
              placeholder="e.g. sermon"
              size="sm" />
            <div class="rule-slug">
              <span class="rule-label">Collection</span>
              <Select
                v-model="rule.slug"
                searchable
                :on-search="searchCollections"
                :placeholder="rule.slug || 'Search collections…'"
                size="sm"
                icon="search"
                :accent="accent" />
            </div>
            <button class="rule-remove" title="Remove rule" @click="removeRule(section.rules, rule)">
              <Icon name="trash" :size="13" color="var(--fg-3)" />
            </button>
          </div>

          <div class="rules-footer">
            <Button size="sm" icon="plus" @click="addRule(section.rules)">Add Rule</Button>
            <span class="spacer" />
            <Button
              size="sm"
              icon="save"
              primary
              :accent="accent"
              :disabled="savingKey === section.key"
              @click="saveRules(section.key, section.rules, section.config)">
              {{ savingKey === section.key ? 'Saving…' : 'Save' }}
            </Button>
          </div>
        </div>
      </SectionCard>
    </template>
  </PageShell>
</template>

<style scoped>
.rules {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
}

.rules-hint {
  font-size: 12px;
  color: var(--fg-3);
  margin: 0;
}

.rule-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1.4fr 32px;
  gap: 10px;
  align-items: end;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
}

.rule-row:last-of-type { border-bottom: none; padding-bottom: 0; }

.rule-slug {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.rule-label {
  font-size: 11px;
  font-weight: 550;
  color: var(--fg-3);
}

.rule-remove {
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-xs);
  background: none;
  border: none;
  cursor: pointer;
  margin-bottom: 1px;
}

.rule-remove:hover { background: color-mix(in oklch, var(--err) 12%, transparent); }

.rules-footer {
  display: flex;
  align-items: center;
  gap: 10px;
}

.spacer { flex: 1; }

.empty-msg {
  font-size: 12.5px;
  color: var(--fg-3);
  padding: 8px 0;
}

.loading-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--fg-3);
  font-size: 13px;
}
</style>
