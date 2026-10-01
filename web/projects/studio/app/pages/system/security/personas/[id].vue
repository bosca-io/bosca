<script setup lang="ts">
import gql from 'graphql-tag'
import { SUBSYSTEMS } from '~/composables/useSubsystems'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const personaId = computed(() => route.params.id as string)

const personaGql = gql`
  query GetPersona($id: UUID!) {
    profiles {
      studioPersonas {
        persona(id: $id) { id name description subsystemIds enabled created modified }
      }
    }
  }
`

const updateGql = gql`
  mutation UpdatePersona($input: StudioPersonaInput!) {
    profiles { studioPersonas { update(input: $input) { id name description subsystemIds enabled } } }
  }
`

const deleteGql = gql`
  mutation DeletePersona($id: UUID!) {
    profiles { studioPersonas { delete(id: $id) } }
  }
`

const assignGql = gql`
  mutation AssignPersona($personaId: UUID!, $profileId: UUID!) {
    profiles { studioPersonas { assignToProfile(personaId: $personaId, profileId: $profileId) } }
  }
`

interface Persona {
  id: string
  name: string
  description: string | null
  subsystemIds: string[]
  enabled: boolean
  created: string
  modified: string
}

const { data, refresh } = useAsyncQuery<{
  profiles: { studioPersonas: { persona: Persona | null } }
}>('persona-detail', personaGql, { id: personaId })

const persona = computed(() => data.value?.profiles?.studioPersonas?.persona ?? null)

const editName = ref('')
const editDesc = ref('')
const editSubs = ref(new Set<string>())
const editEnabled = ref(true)
const saving = ref(false)
const showDelete = ref(false)
const deleteLoading = ref(false)
const showAssign = ref(false)
const assignProfileId = ref('')
const assignLoading = ref(false)

watch(persona, (p) => {
  if (!p) return
  editName.value = p.name
  editDesc.value = p.description ?? ''
  editSubs.value = new Set(p.subsystemIds)
  editEnabled.value = p.enabled
}, { immediate: true })

function toggleSub(id: string) {
  const next = new Set(editSubs.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  editSubs.value = next
}

function selectAllSubs() {
  editSubs.value = new Set(SUBSYSTEMS.map(s => s.id))
}

const isDirty = computed(() => {
  if (!persona.value) return false
  return (
    editName.value !== persona.value.name
    || editDesc.value !== (persona.value.description ?? '')
    || editEnabled.value !== persona.value.enabled
    || !setsEqual(editSubs.value, new Set(persona.value.subsystemIds))
  )
})

function setsEqual(a: Set<string>, b: Set<string>): boolean {
  if (a.size !== b.size) return false
  for (const v of a) if (!b.has(v)) return false
  return true
}

async function handleSave() {
  saving.value = true
  try {
    await gqlMutation(updateGql, {
      input: {
        id: personaId.value,
        name: editName.value,
        description: editDesc.value || null,
        subsystemIds: Array.from(editSubs.value),
        enabled: editEnabled.value,
      },
    })
    toast.success('Persona updated')
    refresh()
  } catch {
    toast.error('Failed to update persona')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: personaId.value })
    toast.success('Persona deleted')
    await router.push('/system/security/personas')
  } catch {
    toast.error('Failed to delete persona')
  } finally {
    deleteLoading.value = false
  }
}

async function handleAssign() {
  if (!assignProfileId.value.trim()) return
  assignLoading.value = true
  try {
    await gqlMutation(assignGql, { personaId: personaId.value, profileId: assignProfileId.value.trim() })
    toast.success('Persona assigned to profile')
    showAssign.value = false
    assignProfileId.value = ''
  } catch {
    toast.error('Failed to assign persona')
  } finally {
    assignLoading.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Security', { label: 'Personas', to: '/system/security/personas' }, persona?.name ?? '…')"
        :title="persona?.name ?? 'Persona'"
        :subtitle="persona?.enabled ? 'Active' : 'Disabled'"
      >
        <template #actions>
          <Button
            size="sm"
            icon="users"
            :accent="accent"
            @click="showAssign = true">
            Assign to Profile
          </Button>
          <Button
            size="sm"
            icon="trash"
            danger
            @click="showDelete = true">
            Delete
          </Button>
        </template>
      </PageHeader>
    </template>

    <template v-if="persona">
      <div class="detail-layout">
        <SectionCard title="Details" padded>
          <div class="form-grid">
            <TextInput v-model="editName" label="Name" />
            <Textarea v-model="editDesc" label="Description" :rows="2" />

            <div class="sub-picker">
              <div class="sub-picker-header">
                <span class="sub-picker-label">Subsystems</span>
                <button class="sub-picker-all" @click="selectAllSubs">Select all</button>
              </div>
              <div class="sub-picker-grid">
                <label
                  v-for="s in SUBSYSTEMS"
                  :key="s.id"
                  class="sub-chip"
                  :class="{ active: editSubs.has(s.id) }"
                  :style="{ '--chip-accent': s.accent }"
                >
                  <input
                    type="checkbox"
                    :checked="editSubs.has(s.id)"
                    class="sr-only"
                    @change="toggleSub(s.id)"
                  >
                  <Icon :name="s.icon" :size="12" :color="editSubs.has(s.id) ? s.accent : 'var(--fg-3)'" />
                  {{ s.label }}
                </label>
              </div>
            </div>

            <div class="toggle-row">
              <span class="toggle-label">Enabled</span>
              <Toggle v-model="editEnabled" />
            </div>
          </div>
        </SectionCard>

        <div class="actions-bar">
          <Button
            primary
            size="sm"
            :accent="accent"
            :disabled="!isDirty || saving"
            @click="handleSave"
          >
            {{ saving ? 'Saving…' : 'Save Changes' }}
          </Button>
        </div>
      </div>
    </template>

    <Modal
      v-if="showAssign"
      title="Assign to Profile"
      icon="users"
      :accent="accent"
      @close="showAssign = false"
    >
      <TextInput
        v-model="assignProfileId"
        label="Profile ID"
        placeholder="Enter profile UUID"
        autofocus
      />
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showAssign = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!assignProfileId.trim() || assignLoading"
          @click="handleAssign"
        >
          Assign
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showDelete"
      :title="`Delete '${persona?.name}'?`"
      message="All profile assignments for this persona will be removed."
      :loading="deleteLoading"
      @close="showDelete = false"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.detail-layout {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 1000px;
}

.form-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.sub-picker {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sub-picker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sub-picker-label {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--fg-2);
}

.sub-picker-all {
  font-size: 11.5px;
  font-weight: 500;
  color: var(--brand-accent);
  background: none;
  border: none;
  cursor: pointer;
  padding: 0;
}

.sub-picker-all:hover {
  text-decoration: underline;
}

.sub-picker-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.sub-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-3);
  border: 1px solid color-mix(in oklch, var(--fg-3) 20%, transparent);
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, color 0.15s;
  user-select: none;
}

.sub-chip.active {
  color: var(--fg-0);
  background: color-mix(in oklch, var(--chip-accent) 12%, transparent);
  border-color: color-mix(in oklch, var(--chip-accent) 32%, transparent);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

.toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.toggle-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
}

.actions-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}
</style>
