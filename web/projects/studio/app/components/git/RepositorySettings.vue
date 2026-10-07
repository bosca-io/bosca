<script setup lang="ts">
const props = defineProps<{ repository: { id: string; name: string; diskSizeBytes: number } }>()
defineEmits<{ refresh: [] }>()
const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { isAdmin } = usePersonas()
const pairEnabled = ref(false)
const options = computed(() => [
  { id: 'webhooks', label: 'Webhooks' },
  { id: 'protection', label: 'Branch Protection' },
  { id: 'permissions', label: 'Permissions' },
  { id: 'utilities', label: 'Utilities' },
  ...(isAdmin.value ? [{ id: 'github', label: 'GitHub Sync' }] : []),
])
const selected = computed(() => options.value.some(option => option.id === route.query.setting)
  ? route.query.setting : options.value[0]?.id)
const selectedLabel = computed(() => options.value.find(option => option.id === selected.value)?.label ?? '')

function select(label: string) {
  const option = options.value.find(option => option.label === label)
  if (option) void router.push({ query: { ...route.query, tab: 'Settings', setting: option.id } })
}
watch(() => props.repository.id, () => { pairEnabled.value = false })
</script>

<template>
  <div class="repository-settings">
    <nav aria-label="Repository settings">
      <Tabs
        :tabs="options.map(option => option.label)"
        :model-value="selectedLabel"
        :accent="accent"
        @update:model-value="select" />
    </nav>
    <div :key="repository.id" class="settings-content">
      <template v-if="selected === 'github' && isAdmin">
        <GitHubRepositorySync :repository-id="repository.id" @enabled="pairEnabled = $event" />
        <GitHubSyncHistory :repository-id="repository.id" :enabled="pairEnabled" />
      </template>
      <RepositoryWebhooks v-else-if="selected === 'webhooks'" :repository-id="repository.id" />
      <RepositoryBranchProtection v-else-if="selected === 'protection'" :repository-id="repository.id" />
      <RepositoryPermissions v-else-if="selected === 'permissions'" :repository-id="repository.id" />
      <RepositoryUtilities v-else-if="selected === 'utilities'" :repository="repository" @refresh="$emit('refresh')" />
    </div>
  </div>
</template>

<style scoped>
.repository-settings, .settings-content { display: flex; flex-direction: column; gap: 24px; }
</style>
