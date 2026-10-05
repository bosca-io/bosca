<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const scriptId = computed(() => route.params.id as string)
const isNew = computed(() => scriptId.value === 'new')

const scriptGql = gql`
  query GetScript($id: UUID!) {
    scripts {
      script(id: $id) {
        id
        key
        name
        description
        type
        source
        public
        enabled
        inputSchema
        outputSchema
        configuration
        permissions {
          action
          groupId
          group { id name }
        }
      }
    }
  }
`
const groupsGql = gql`
  query ScriptGroups { security { groups { all(offset: 0, limit: 100) { id name description } } } }
`
const addPermissionGql = gql`
  mutation AddScriptPermission($permission: PermissionInput!) {
    scripts { addPermission(permission: $permission) }
  }
`
const removePermissionGql = gql`
  mutation RemoveScriptPermission($permission: PermissionInput!) {
    scripts { deletePermission(permission: $permission) }
  }
`
const addGql = gql`
  mutation AddScript($script: ScriptInput!) {
    scripts { addScript(script: $script) { id } }
  }
`
const editGql = gql`
  mutation EditScript($id: UUID!, $script: ScriptInput!) {
    scripts { editScript(id: $id, script: $script) { id } }
  }
`
const enableGql = gql`
  mutation EnableScript($id: UUID!) { scripts { enableScript(id: $id) } }
`
const disableGql = gql`
  mutation DisableScript($id: UUID!) { scripts { disableScript(id: $id) } }
`
const executeGql = gql`
  mutation ExecuteScript($id: UUID!, $input: JSON) {
    scripts { executeScript(id: $id, input: $input) }
  }
`
const scriptSourceRefGql = gql`
  query GetScriptSourceRef($scriptId: UUID!) {
    git { scriptSourceRef(scriptId: $scriptId) { repositoryId ref path resolvedCommit } }
  }
`
const setScriptSourceRefGql = gql`
  mutation SetScriptSourceRef($scriptId: UUID!, $repositoryId: UUID!, $ref: String!, $path: String!) {
    git { setScriptSourceRef(scriptId: $scriptId, repositoryId: $repositoryId, ref: $ref, path: $path) { repositoryId ref path resolvedCommit } }
  }
`
const removeScriptSourceRefGql = gql`
  mutation RemoveScriptSourceRef($scriptId: UUID!) {
    git { removeScriptSourceRef(scriptId: $scriptId) }
  }
`

interface ScriptPermission {
  action: string
  groupId: string
  group: { id: string; name: string }
}

interface AiScript {
  id: string
  key: string
  name: string
  description: string
  type: string
  source: string
  public: boolean
  enabled: boolean
  inputSchema: unknown
  outputSchema: unknown
  configuration: unknown
  permissions: ScriptPermission[]
}

const { data, refresh } = useAsyncQuery<{ scripts: { script: AiScript | null } }>(
  'script-detail',
  scriptGql,
  isNew.value ? { id: '00000000-0000-0000-0000-000000000000' } : { id: scriptId },
)

const script = computed(() => isNew.value ? null : data.value?.scripts?.script)

const SCRIPT_TYPES: SelectOption[] = [
  { value: 'GENERAL', label: 'General' },
  { value: 'API', label: 'API' },
  { value: 'TOOL', label: 'Tool' },
  { value: 'TRIGGER', label: 'Trigger' },
  { value: 'EPHEMERAL', label: 'Ephemeral' },
]

const key = ref('')
const name = ref('')
const description = ref('')
const type = ref<string>('GENERAL')
const isPublic = ref(false)
const enabled = ref(true)
const source = ref('')
const inputSchema = ref('{}')
const outputSchema = ref('{}')
const configuration = ref('{}')
const saving = ref(false)
const togglingEnabled = ref(false)

const testOpen = ref(false)
const testInput = ref('{}')
const testResult = ref<string | null>(null)
const testError = ref<string | null>(null)
const executing = ref(false)

interface GitSource { repositoryId: string; ref: string; path: string; resolvedCommit?: string | null }
const gitSource = ref<GitSource | null>(null)
const gitSyncLoading = ref(false)

const SCRIPT_PERMISSION_ACTIONS: SelectOption[] = [
  { value: 'VIEW', label: 'VIEW' },
  { value: 'EDIT', label: 'EDIT' },
  { value: 'EXECUTE', label: 'EXECUTE' },
  { value: 'MANAGE', label: 'MANAGE' },
  { value: 'DELETE', label: 'DELETE' },
]

const PERMISSION_ACTION_COLORS: Record<string, string> = {
  VIEW: '#5ec5ff',
  EDIT: '#4ade80',
  EXECUTE: '#c084fc',
  MANAGE: '#ff5d6c',
  DELETE: '#ffb547',
}

interface SecurityGroup { id: string; name: string; description: string | null }
const allGroups = ref<SecurityGroup[]>([])
const permAddOpen = ref(false)
const permAddGroupId = ref<string | undefined>(undefined)
const permAddAction = ref<string | undefined>('VIEW')
const permAdding = ref(false)
const permDeleteTarget = ref<ScriptPermission | null>(null)
const permDeleteLoading = ref(false)

const permissions = computed<ScriptPermission[]>(() => script.value?.permissions ?? [])
const groupOptions = computed<SelectOption[]>(() =>
  allGroups.value.map(g => ({ value: g.id, label: g.name })),
)
const permissionColumns: GlassTableColumn[] = [
  { key: 'group', label: 'Group', width: 'minmax(140px, 2fr)' },
  { key: 'action', label: 'Action', width: '140px' },
]

const validationError = computed(() => {
  if (!key.value.trim()) return 'Key is required'
  if (!name.value.trim()) return 'Name is required'
  if (!type.value) return 'Type is required'
  if (!source.value.trim()) return 'Source is required'
  return null
})

watch(script, (s) => {
  if (s) {
    key.value = s.key
    name.value = s.name
    description.value = s.description ?? ''
    type.value = s.type
    isPublic.value = s.public
    enabled.value = s.enabled
    source.value = s.source ?? ''
    inputSchema.value = s.inputSchema ? JSON.stringify(s.inputSchema, null, 2) : '{}'
    outputSchema.value = s.outputSchema ? JSON.stringify(s.outputSchema, null, 2) : '{}'
    configuration.value = s.configuration ? JSON.stringify(s.configuration, null, 2) : '{}'
    loadSourceRef()
  }
}, { immediate: true })

async function loadSourceRef() {
  if (isNew.value) return
  try {
    const result = await gqlQuery<{ git: { scriptSourceRef: GitSource | null } }>(scriptSourceRefGql, { scriptId: scriptId.value })
    gitSource.value = result.git?.scriptSourceRef ?? null
    if (gitSource.value) syncGitContent()
  } catch { /* no source ref */ }
}

async function loadGroups() {
  if (allGroups.value.length > 0) return
  try {
    const result = await gqlQuery<{ security: { groups: { all: SecurityGroup[] } } }>(
      groupsGql, {},
    )
    allGroups.value = result.security?.groups?.all ?? []
  } catch { /* leave empty */ }
}

function openAddPermission() {
  permAddGroupId.value = undefined
  permAddAction.value = 'VIEW'
  permAddOpen.value = true
  void loadGroups()
}

async function confirmAddPermission() {
  if (!scriptId.value || !permAddGroupId.value || !permAddAction.value) return
  permAdding.value = true
  try {
    await gqlMutation(addPermissionGql, {
      permission: {
        action: permAddAction.value,
        entityId: scriptId.value,
        groupId: permAddGroupId.value,
      },
    })
    toast.success('Permission added')
    permAddOpen.value = false
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to add permission')
  } finally {
    permAdding.value = false
  }
}

async function confirmRemovePermission() {
  const p = permDeleteTarget.value
  if (!p || !scriptId.value) return
  permDeleteLoading.value = true
  try {
    await gqlMutation(removePermissionGql, {
      permission: { action: p.action, entityId: scriptId.value, groupId: p.groupId },
    })
    toast.success('Permission removed')
    permDeleteTarget.value = null
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to remove permission')
  } finally {
    permDeleteLoading.value = false
  }
}

async function syncGitContent() {
  const src = gitSource.value
  if (!src) return
  gitSyncLoading.value = true
  try {
    const result = await gqlQuery<{ git: { blob: { content: string | null } | null } }>(gql`
      query Blob($repositoryId: UUID!, $ref: String!, $path: String!) {
        git { blob(repositoryId: $repositoryId, ref: $ref, path: $path) { content } }
      }
    `, { repositoryId: src.repositoryId, ref: src.ref, path: src.path })
    const content = result.git?.blob?.content
    if (content != null) source.value = content
  } catch { /* keep existing source */ }
  finally { gitSyncLoading.value = false }
}

async function attachGitSource(src: GitSource) {
  if (isNew.value) { toast.error('Save the script first'); return }
  try {
    const result = await gqlMutation<{ git: { setScriptSourceRef: GitSource } }>(setScriptSourceRefGql, {
      scriptId: scriptId.value, repositoryId: src.repositoryId, ref: src.ref, path: src.path,
    })
    gitSource.value = result.git.setScriptSourceRef
    syncGitContent()
    toast.success('Git source attached')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to attach')
  }
}

async function detachGitSource() {
  if (isNew.value) return
  try {
    await gqlMutation(removeScriptSourceRefGql, { scriptId: scriptId.value })
    gitSource.value = null
    toast.success('Git source detached')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to detach')
  }
}

function parseJsonOrNull(v: string): unknown {
  const trimmed = v.trim()
  if (!trimmed || trimmed === '{}') return null
  return JSON.parse(trimmed)
}

async function onSave() {
  if (validationError.value) {
    toast.error(validationError.value)
    return
  }
  saving.value = true
  try {
    const input = {
      key: key.value,
      name: name.value,
      description: description.value,
      type: type.value,
      source: source.value,
      public: isPublic.value,
      inputSchema: parseJsonOrNull(inputSchema.value),
      outputSchema: parseJsonOrNull(outputSchema.value),
      configuration: parseJsonOrNull(configuration.value),
    }
    if (isNew.value) {
      const result = await gqlMutation<{ scripts: { addScript: { id: string } } }>(
        addGql, { script: input },
      )
      toast.success('Script created')
      router.replace(`/scripts/${result.scripts.addScript.id}`)
    } else {
      await gqlMutation(editGql, { id: scriptId.value, script: input })
      toast.success('Saved')
      refresh()
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : 'Failed to save'
    toast.error(msg)
  } finally {
    saving.value = false
  }
}

async function onExecute() {
  if (isNew.value || !scriptId.value || executing.value) return
  executing.value = true
  testResult.value = null
  testError.value = null
  let parsedInput: unknown = null
  try {
    const trimmed = testInput.value.trim()
    parsedInput = trimmed && trimmed !== '{}' ? JSON.parse(trimmed) : null
  } catch (e) {
    testError.value = `Invalid input JSON: ${e instanceof Error ? e.message : String(e)}`
    executing.value = false
    return
  }
  try {
    const result = await gqlMutation<{ scripts: { executeScript: unknown } }>(
      executeGql,
      { id: scriptId.value, input: parsedInput },
    )
    testResult.value = JSON.stringify(result.scripts.executeScript, null, 2)
  } catch (e) {
    testError.value = e instanceof Error ? e.message : String(e)
  } finally {
    executing.value = false
  }
}

async function onToggleEnabled(next: boolean) {
  if (isNew.value || !scriptId.value || togglingEnabled.value) return
  togglingEnabled.value = true
  const previous = enabled.value
  enabled.value = next
  try {
    await gqlMutation(next ? enableGql : disableGql, { id: scriptId.value })
    toast.success(next ? 'Enabled' : 'Disabled')
    refresh()
  } catch {
    enabled.value = previous
    toast.error('Failed to update')
  } finally {
    togglingEnabled.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Scripts', isNew ? 'New' : name || '…')"
        :title="isNew ? 'New Script' : name || 'Loading…'">
        <template #actions>
          <Button
            size="sm"
            icon="flask"
            :accent="accent"
            :disabled="isNew"
            :title="isNew ? 'Save the script first to test it' : undefined"
            @click="testOpen = true">Test</Button>
          <Button
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="saving || !!validationError"
            :title="validationError ?? undefined"
            @click="onSave">{{ saving ? 'Saving…' : 'Save' }}</Button>
        </template>
      </PageHeader>
    </template>

    <div class="form-layout">
      <SectionCard title="Configuration" padded>
        <div class="form-grid">
          <TextInput
            v-model="key"
            label="Key"
            mono
            :disabled="!isNew" />
          <TextInput v-model="name" label="Name" />
          <Select
            v-model="type"
            label="Type"
            :options="SCRIPT_TYPES"
            :accent="accent" />
          <div class="toggle-row">
            <Switch v-model="isPublic" label="Public" :accent="accent" />
            <Switch
              v-if="!isNew"
              :model-value="enabled"
              label="Enabled"
              :accent="accent"
              @update:model-value="onToggleEnabled" />
          </div>
        </div>
        <Textarea
          v-model="description"
          label="Description"
          :rows="2"
          style="margin-top: 14px" />
      </SectionCard>

      <SectionCard title="Git Source" padded>
        <div v-if="gitSource" class="git-attached">
          <Icon name="git-commit" :size="14" color="var(--brand-2)" />
          <span class="git-attached__path mono">{{ gitSource.path }}</span>
          <span class="git-attached__ref mono">@ {{ gitSource.ref }}</span>
          <Button size="sm" icon="x" @click="detachGitSource">Detach</Button>
        </div>
        <GitFilePicker
          v-else
          content-type="SCRIPT_PROJECT"
          allow-create
          new-file-placeholder="filename.bosca.kts"
          :initial-content="source"
          @select="attachGitSource" />
        <div v-if="gitSyncLoading" class="sync-status">Syncing file content…</div>
      </SectionCard>

      <SectionCard title="Source (Kotlin)" padded>
        <template v-if="gitSource" #right>
          <Button
            size="sm"
            icon="refresh"
            :disabled="gitSyncLoading"
            @click="syncGitContent">{{ gitSyncLoading ? 'Syncing…' : 'Sync' }}</Button>
        </template>
        <CodeEditor
          v-model="source"
          language="text"
          :rows="20"
          :readonly="!!gitSource" />
        <div v-if="gitSource" class="source-hint">Source content managed by git source. Detach to edit manually.</div>
      </SectionCard>

      <SectionCard title="Input Schema (JSON)" padded>
        <CodeEditor v-model="inputSchema" language="json" :rows="10" />
      </SectionCard>

      <SectionCard title="Output Schema (JSON)" padded>
        <CodeEditor v-model="outputSchema" language="json" :rows="10" />
      </SectionCard>

      <SectionCard title="Configuration (JSON)" padded>
        <CodeEditor v-model="configuration" language="json" :rows="10" />
      </SectionCard>

      <SectionCard
        v-if="!isNew"
        title="Permissions"
        :subtitle="`${permissions.length} permission${permissions.length === 1 ? '' : 's'}`"
        padded>
        <template #right>
          <Button
            size="sm"
            icon="plus"
            :accent="accent"
            @click="openAddPermission">Add</Button>
        </template>
        <GlassTable
          :columns="permissionColumns"
          :rows="permissions"
          empty-text="No group permissions. Admins and SA retain implicit access."
          :row-actions="() => [
            { id: 'delete', label: 'Remove', icon: 'x', danger: true },
          ]"
          @row-action="({ action, row }: any) => action === 'delete' ? permDeleteTarget = row : null">
          <template #col-group="{ row }">
            <span class="perm-group">{{ row.group?.name ?? row.groupId }}</span>
          </template>
          <template #col-action="{ row }">
            <Badge :color="PERMISSION_ACTION_COLORS[row.action] ?? 'var(--fg-3)'">
              {{ row.action }}
            </Badge>
          </template>
        </GlassTable>
      </SectionCard>
    </div>

    <Modal
      v-if="permAddOpen"
      title="Add Permission"
      icon="plus"
      :accent="accent"
      @close="permAddOpen = false">
      <div class="perm-add-stack">
        <Select
          v-model="permAddGroupId"
          label="Group"
          :options="groupOptions"
          searchable
          placeholder="Select a group…"
          :accent="accent" />
        <Select
          v-model="permAddAction"
          label="Action"
          :options="SCRIPT_PERMISSION_ACTIONS"
          :accent="accent" />
      </div>
      <template #footer>
        <span class="perm-add-spacer" />
        <Button size="sm" @click="permAddOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!permAddGroupId || !permAddAction || permAdding"
          @click="confirmAddPermission">{{ permAdding ? 'Adding…' : 'Add' }}</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="permDeleteTarget"
      :title="`Remove ${permDeleteTarget.action} from ${permDeleteTarget.group?.name ?? permDeleteTarget.groupId}?`"
      :loading="permDeleteLoading"
      @close="permDeleteTarget = null"
      @confirm="confirmRemovePermission" />

    <Drawer
      v-if="testOpen"
      title="Test Script"
      :subtitle="name"
      icon="play"
      :accent="accent"
      width="640px"
      @close="testOpen = false">
      <div class="test-body">
        <p class="test-hint">
          Provide JSON input and execute the script. For tool scripts the input
          becomes the <code>input</code> JsonObject; for trigger scripts pass
          <code>{ eventName, eventPayload }</code>.
        </p>
        <div>
          <div class="test-label">Input (JSON)</div>
          <CodeEditor v-model="testInput" language="json" :rows="10" />
        </div>
        <Button
          size="sm"
          icon="play"
          primary
          :accent="accent"
          :disabled="executing"
          @click="onExecute">{{ executing ? 'Executing…' : 'Execute' }}</Button>
        <div v-if="testError" class="test-result test-result--error">{{ testError }}</div>
        <div v-if="testResult !== null">
          <div class="test-label">Result</div>
          <CodeEditor
            :model-value="testResult"
            language="json"
            :rows="14"
            readonly />
        </div>
      </div>
    </Drawer>
  </PageShell>
</template>

<style scoped>
.form-layout {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 900px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.perm-group {
  font-weight: 500;
  color: var(--fg-0);
}

.perm-add-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.perm-add-spacer {
  flex: 1;
}

.toggle-row {
  display: flex;
  align-items: center;
  gap: 18px;
}

.test-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.test-hint {
  font-size: 12px;
  color: var(--fg-2);
  margin: 0;
}

.test-hint code {
  font-family: var(--font-mono, Menlo, Consolas, monospace);
  background: var(--bg-2);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 11.5px;
}

.test-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
  margin-bottom: 6px;
}

.test-result {
  font-size: 12.5px;
  color: var(--fg-1);
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px 12px;
  white-space: pre-wrap;
  font-family: var(--font-mono, Menlo, Consolas, monospace);
}

.test-result--error {
  color: var(--err, #f87171);
}

.git-attached {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: color-mix(in oklch, var(--brand-2) 8%, var(--bg-3));
  border: 1px solid color-mix(in oklch, var(--brand-2) 20%, var(--line));
  border-radius: var(--r-sm);
}

.git-attached__path {
  flex: 1;
  font-size: 12px;
  color: var(--fg-0);
}

.git-attached__ref {
  font-size: 11px;
  color: var(--fg-3);
}

.sync-status {
  margin-top: 8px;
  font-size: 12px;
  color: var(--fg-3);
}

.source-hint {
  margin-top: 8px;
  font-size: 11px;
  color: var(--fg-3);
  font-style: italic;
}
</style>
