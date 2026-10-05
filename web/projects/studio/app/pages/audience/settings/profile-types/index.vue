<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const listGql = gql`
  query GetProfileAttributeTypes {
    profiles { attributeTypes { all { id name description visibility protected formSchemaId formSchema { id name } } } }
  }
`

const schemasGql = gql`
  query GetInternalFormSchemas {
    formSchemas { byType(type: INTERNAL) { id name } }
  }
`

const addGql = gql`
  mutation AddProfileAttributeType($input: ProfileAttributeTypeInput!) {
    profiles { addAttributeType(attribute: $input) }
  }
`

const editGql = gql`
  mutation EditProfileAttributeType($input: ProfileAttributeTypeInput!) {
    profiles { editAttributeType(attribute: $input) }
  }
`

const deleteGql = gql`
  mutation DeleteProfileAttributeType($id: String!) {
    profiles { deleteAttributeType(attributeId: $id) }
  }
`

interface ProfileAttributeType {
  id: string
  name: string
  description: string | null
  visibility: string
  protected: boolean
  formSchemaId: string | null
  formSchema: { id: string; name: string } | null
}

const { data, status, refresh } = useAsyncQuery<{
  profiles: { attributeTypes: { all: ProfileAttributeType[] } }
}>('audience-profile-types', listGql, {})

const items = computed(() => data.value?.profiles?.attributeTypes?.all ?? [])

const { data: schemasData } = useAsyncQuery<{
  formSchemas: { byType: { id: string; name: string }[] }
}>('audience-profile-type-form-schemas', schemasGql, {})

const formSchemaOptions = computed<SelectOption[]>(() => [
  { value: '', label: 'None' },
  ...(schemasData.value?.formSchemas?.byType ?? []).map(s => ({ value: s.id, label: s.name })),
])

const VISIBILITY_COLORS: Record<string, string> = {
  PUBLIC: '#34d99a',
  SYSTEM: '#f87171',
  USER: '#60a5fa',
  FRIENDS: '#a78bfa',
  FRIENDS_OF_FRIENDS: '#fbbf24',
}

const visibilityOptions = [
  { value: 'PUBLIC', label: 'Public' },
  { value: 'SYSTEM', label: 'System' },
  { value: 'USER', label: 'User' },
  { value: 'FRIENDS', label: 'Friends' },
  { value: 'FRIENDS_OF_FRIENDS', label: 'Friends of Friends' },
]

const columns: GlassTableColumn[] = [
  { key: 'id', label: 'ID', width: 'minmax(120px, 1fr)' },
  { key: 'name', label: 'Name', width: 'minmax(160px, 2fr)' },
  { key: 'formSchema', label: 'Form Schema', width: 'minmax(140px, 1fr)' },
  { key: 'visibility', label: 'Visibility', width: '140px' },
  { key: 'protected', label: 'Protected', width: '100px' },
]

const showModal = ref(false)
const editingItem = ref<ProfileAttributeType | null>(null)
const deleteTarget = ref<ProfileAttributeType | null>(null)
const deleteLoading = ref(false)
const saving = ref(false)

const formId = ref('')
const formName = ref('')
const formDescription = ref('')
const formVisibility = ref('PUBLIC')
const formProtected = ref(false)
const formFormSchemaId = ref('')

function openCreate() {
  editingItem.value = null
  formId.value = ''
  formName.value = ''
  formDescription.value = ''
  formVisibility.value = 'PUBLIC'
  formProtected.value = false
  formFormSchemaId.value = ''
  showModal.value = true
}

function openEdit(item: ProfileAttributeType) {
  editingItem.value = item
  formId.value = item.id
  formName.value = item.name
  formDescription.value = item.description ?? ''
  formVisibility.value = item.visibility
  formProtected.value = item.protected
  formFormSchemaId.value = item.formSchemaId ?? ''
  showModal.value = true
}

function buildInput() {
  return {
    id: formId.value,
    name: formName.value,
    // ProfileAttributeTypeInput.description is non-null in the schema; an empty
    // description is sent as '' — null fails validation.
    description: formDescription.value,
    visibility: formVisibility.value,
    protected: formProtected.value,
    formSchemaId: formFormSchemaId.value || null,
  }
}

async function handleSave() {
  if (!formId.value.trim() || !formName.value.trim()) return
  saving.value = true
  try {
    if (editingItem.value) {
      await gqlMutation(editGql, { input: buildInput() })
      toast.success('Profile type updated')
    } else {
      await gqlMutation(addGql, { input: buildInput() })
      toast.success('Profile type created')
    }
    showModal.value = false
    refresh()
  } catch (e: unknown) {
    const err = e as { errors?: Array<{ message: string }>; message?: string }
    toast.error(err?.errors?.[0]?.message ?? err?.message ?? 'Failed to save profile type')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: deleteTarget.value.id })
    toast.success('Profile type deleted')
    deleteTarget.value = null
    refresh()
  } catch (e: unknown) {
    const err = e as { errors?: Array<{ message: string }>; message?: string }
    toast.error(err?.errors?.[0]?.message ?? err?.message ?? 'Failed to delete profile type')
  } finally {
    deleteLoading.value = false
  }
}

function onRowAction({ action, row }: { action: string; row: ProfileAttributeType }) {
  if (action === 'edit') openEdit(row)
  else if (action === 'delete') deleteTarget.value = row
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Audience', 'Settings', 'Profile Types')"
        title="Profile Types"
        :subtitle="`${items.length} types`">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">New Profile Type</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Profile Attribute Types">
      <GlassTable
        :columns="columns"
        :rows="items"
        :loading="status === 'pending' && items.length === 0"
        empty-text="No profile types configured."
        :row-actions="() => [
          { id: 'edit', label: 'Edit', icon: 'pencil' },
          { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
        ]"
        @row-click="(row: any) => openEdit(row)"
        @row-action="onRowAction"
      >
        <template #col-id="{ row }">
          <span class="mono" style="font-size: 12px; color: var(--fg-2)">{{ row.id }}</span>
        </template>
        <template #col-name="{ row }">
          <div>
            <div style="font-weight: 500; color: var(--fg-0)">{{ row.name }}</div>
            <div v-if="row.description" style="font-size: 11.5px; color: var(--fg-3); margin-top: 1px">{{ row.description }}</div>
          </div>
        </template>
        <template #col-formSchema="{ row }">
          <span v-if="row.formSchema" style="font-size: 12.5px; color: var(--fg-1)">{{ row.formSchema.name }}</span>
          <span v-else style="font-size: 12px; color: var(--fg-3)">—</span>
        </template>
        <template #col-visibility="{ row }">
          <Badge :color="VISIBILITY_COLORS[row.visibility] ?? '#6c7388'">{{ row.visibility }}</Badge>
        </template>
        <template #col-protected="{ row }">
          <Badge :color="row.protected ? '#f87171' : '#6c7388'">{{ row.protected ? 'Yes' : 'No' }}</Badge>
        </template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showModal"
      :title="editingItem ? 'Edit Profile Type' : 'New Profile Type'"
      icon="user"
      :accent="accent"
      @close="showModal = false">
      <div class="profile-type-form">
        <TextInput
          v-model="formId"
          label="ID"
          placeholder="unique-id"
          :disabled="!!editingItem"
          autofocus />
        <TextInput v-model="formName" label="Name" placeholder="Display name" />
        <Textarea
          v-model="formDescription"
          label="Description"
          placeholder="Optional description"
          :rows="2" />
        <Select v-model="formVisibility" :options="visibilityOptions" label="Visibility" />
        <Select v-model="formFormSchemaId" :options="formSchemaOptions" label="Form Schema" />
        <Switch v-model="formProtected" label="Protected" :accent="accent" />
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showModal = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!formId.trim() || !formName.trim() || saving"
          @click="handleSave">
          {{ saving ? 'Saving…' : (editingItem ? 'Update' : 'Create') }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      subtitle="This action cannot be undone."
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.profile-type-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
</style>
