<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'
import JsonEditorVue from 'json-editor-vue'
import ProfileFlagAssignments from '~/components/experiments/ProfileFlagAssignments.vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const profileId = computed(() => route.params.id as string)

// ── Queries ──────────────────────────────────────────────────────────

const profileGql = gql`
  query GetProfileById($id: UUID!) {
    profiles {
      profile(id: $id) {
        id
        name
        slug
        type
        visibility
        created
        modified
        deletedAt
        lastLogin
        isPrimary
        organizations {
          id
          profile { name }
        }
        attributes {
          id
          typeId
          attributes
          source
          priority
          confidence
          visibility
          expires
          type {
            id
            name
            formSchema { key }
          }
        }
        principal {
          id
          verified
          profiles { id name }
          credentials { type identifier }
          groups { id name description type }
        }
      }
    }
  }
`

const segmentsGql = gql`
  query GetProfileSegments($profileId: UUID!) {
    segments {
      segmentsByProfile(profileId: $profileId) {
        id name type status
      }
    }
  }
`

const allSegmentsGql = gql`
  query GetAllSegments {
    segments {
      all(offset: 0, limit: 500) {
        id name type status
      }
    }
  }
`

const groupSearchGql = gql`
  query FindSecurityGroups($nameOrDescription: String!) {
    security {
      groups {
        find(nameOrDescription: $nameOrDescription, offset: 0, limit: 50) {
          id name description type
        }
      }
    }
  }
`

const attributeTypesGql = gql`
  query GetProfileAttributeTypes {
    profiles {
      attributeTypes {
        all { id name protected formSchema { key } }
      }
    }
  }
`

// ── Mutations ────────────────────────────────────────────────────────

const editProfileGql = gql`
  mutation EditProfile($id: UUID!, $profile: ProfileInput!) {
    profiles {
      edit(id: $id, profile: $profile) { id name slug visibility }
    }
  }
`

const deleteAttributeGql = gql`
  mutation DeleteProfileAttribute($attributeId: UUID!, $profileId: UUID!) {
    profiles { deleteAttribute(attributeId: $attributeId, id: $profileId) }
  }
`

const addGroupGql = gql`
  mutation AddPrincipalGroup($principalId: UUID!, $groupId: UUID!) {
    security { addPrincipalGroup(principalId: $principalId, groupId: $groupId) }
  }
`

const removeGroupGql = gql`
  mutation RemovePrincipalGroup($principalId: UUID!, $groupId: UUID!) {
    security { removePrincipalGroup(principalId: $principalId, groupId: $groupId) }
  }
`

const addSegmentMemberGql = gql`
  mutation AddSegmentMember($segmentId: UUID!, $profileIds: [UUID!]!) {
    segments { addMembers(segmentId: $segmentId, profileIds: $profileIds) }
  }
`

const removeSegmentMemberGql = gql`
  mutation RemoveSegmentMember($segmentId: UUID!, $profileIds: [UUID!]!) {
    segments { removeMembers(segmentId: $segmentId, profileIds: $profileIds) }
  }
`

const addOrgMemberGql = gql`
  mutation AddOrganizationMember($organizationId: UUID!, $principalId: UUID!) {
    organizations { addMember(id: $organizationId, principalId: $principalId) }
  }
`

const removeOrgMemberGql = gql`
  mutation RemoveOrganizationMember($organizationId: UUID!, $principalId: UUID!) {
    organizations { removeMember(id: $organizationId, principalId: $principalId) }
  }
`

const setPrincipalGql = gql`
  mutation SetPrincipal($id: UUID!, $principalId: UUID!) {
    profiles { setPrincipal(id: $id, principalId: $principalId) { id } }
  }
`

const clearPrincipalGql = gql`
  mutation ClearPrincipal($id: UUID!) {
    profiles { clearPrincipal(id: $id) { id } }
  }
`

const setPrimaryProfileGql = gql`
  mutation SetPrimaryProfile($profileId: UUID!, $principalId: UUID) {
    security { principal { setPrimaryProfile(profileId: $profileId, principalId: $principalId) } }
  }
`

const markDeletedProfileGql = gql`
  mutation MarkProfileDeleted($id: UUID!) {
    profiles { markDeleted(id: $id) { id deletedAt } }
  }
`

const restoreProfileGql = gql`
  mutation RestoreProfile($id: UUID!) {
    profiles { restore(id: $id) { id deletedAt } }
  }
`

const deleteProfileGql = gql`
  mutation DeleteProfile($id: UUID!) {
    profiles { delete(id: $id) }
  }
`

const profilePersonasGql = gql`
  query GetProfilePersonas($profileId: UUID!) {
    profiles { studioPersonas { byProfile(profileId: $profileId) { id name subsystemIds } } }
  }
`

const allPersonasGql = gql`
  query GetAllPersonas {
    profiles { studioPersonas { all { id name subsystemIds enabled } } }
  }
`

const assignPersonaGql = gql`
  mutation AssignPersona($personaId: UUID!, $profileId: UUID!) {
    profiles { studioPersonas { assignToProfile(personaId: $personaId, profileId: $profileId) } }
  }
`

const removePersonaGql = gql`
  mutation RemovePersona($personaId: UUID!, $profileId: UUID!) {
    profiles { studioPersonas { removeFromProfile(personaId: $personaId, profileId: $profileId) } }
  }
`

// ── Types ───────────────────────────────────────────────────────────

interface ProfileAttributeType {
  id: string
  name: string
  protected: boolean
  formSchema: { key: string } | null
}

interface ProfileAttribute {
  id: string
  typeId: string
  attributes: Record<string, unknown>
  source: string
  priority: number
  confidence: number
  visibility: string
  expires: string | null
  type: ProfileAttributeType | null
}

interface ProfileCredential {
  type: string
  identifier: string
}

interface ProfileGroup {
  id: string
  name: string
  description: string
  type: string
}

interface ProfilePrincipal {
  id: string
  verified: boolean
  profiles: Array<{ id: string; name: string }>
  credentials: ProfileCredential[]
  groups: ProfileGroup[]
}

interface ProfileOrganization {
  id: string
  profile: { name: string } | null
}

interface Profile {
  id: string
  name: string
  slug: string | null
  type: string
  visibility: string
  created: string
  modified: string
  deletedAt: string | null
  lastLogin: string | null
  isPrimary: boolean
  organizations: ProfileOrganization[]
  attributes: ProfileAttribute[]
  principal: ProfilePrincipal | null
}

interface Segment {
  id: string
  name: string
  type: string
  status: string
}

interface Persona {
  id: string
  name: string
  subsystemIds: string[]
  enabled?: boolean
}

// ── Data fetching ────────────────────────────────────────────────────

const { data, status, refresh } = useAsyncQuery<{
  profiles: { profile: Profile | null }
}>('profile-detail', profileGql, { id: profileId })

const { data: segmentsData, refresh: refreshSegments } = useAsyncQuery<{
  segments: { segmentsByProfile: Segment[] }
}>('profile-segments', segmentsGql, { profileId })

const { data: allSegmentsData } = useAsyncQuery<{
  segments: { all: Segment[] }
}>('all-segments', allSegmentsGql)

const { data: attrTypesData } = useAsyncQuery<{
  profiles: { attributeTypes: { all: ProfileAttributeType[] } }
}>('profile-attr-types', attributeTypesGql)

const { data: profilePersonasData, refresh: refreshPersonas } = useAsyncQuery<{
  profiles: { studioPersonas: { byProfile: Persona[] } }
}>('profile-personas', profilePersonasGql, { profileId })

const { data: allPersonasData } = useAsyncQuery<{
  profiles: { studioPersonas: { all: Persona[] } }
}>('all-personas', allPersonasGql)

const profile = computed(() => data.value?.profiles?.profile ?? null)
const isLoading = computed(() => status.value === 'pending')
const segments = computed(() => segmentsData.value?.segments?.segmentsByProfile ?? [])
const attributeTypes = computed(() => attrTypesData.value?.profiles?.attributeTypes?.all ?? [])
const profilePersonas = computed(() => profilePersonasData.value?.profiles?.studioPersonas?.byProfile ?? [])
const availablePersonas = computed(() => {
  const assignedIds = new Set(profilePersonas.value.map((p) => p.id))
  return (allPersonasData.value?.profiles?.studioPersonas?.all ?? [])
    .filter((p) => p.enabled && !assignedIds.has(p.id))
    .map((p) => ({ value: p.id, label: p.name }))
})

const avatarUrl = computed(() => {
  const a = profile.value?.attributes?.find((x) => x.type?.id === 'bosca.profiles.avatar')
  return (a?.attributes as Record<string, unknown> | undefined)?.picture as string ?? ''
})

const email = computed(() => {
  const cred = profile.value?.principal?.credentials?.find(
    (c) => c.type === 'PASSWORD' || c.type === 'EMAIL',
  )
  return cred?.identifier ?? ''
})

const otherProfiles = computed(() =>
  (profile.value?.principal?.profiles ?? []).filter((p) => p.id !== profileId.value),
)

const currentGroups = computed(() => profile.value?.principal?.groups ?? [])

async function searchGroups(q: string): Promise<SelectOption[]> {
  try {
    const result = await gqlQuery<{
      security: { groups: { find: ProfileGroup[] } }
    }>(groupSearchGql, { nameOrDescription: q })
    const currentIds = new Set(currentGroups.value.map((group) => group.id))
    return (result.security.groups.find ?? [])
      .filter((group) => !currentIds.has(group.id))
      .map((group) => ({ value: group.id, label: group.description || group.name }))
  } catch (e: unknown) {
    const err = e as { errors?: Array<{ message: string }>; message?: string }
    toast.error(err?.errors?.[0]?.message ?? err?.message ?? 'Failed to search groups')
    return []
  }
}

const availableStaticSegments = computed<SelectOption[]>(() => {
  const allSegs = allSegmentsData.value?.segments?.all ?? []
  const memberIds = new Set(segments.value.map((s) => s.id))
  return allSegs
    .filter((s) => s.type === 'STATIC' && !memberIds.has(s.id))
    .map((s) => ({ value: s.id, label: s.name }))
})

const attributeRows = computed(() => {
  const attrs = profile.value?.attributes ?? []
  return [...attrs]
    .sort((a, b) => (a.priority ?? 0) - (b.priority ?? 0))
    .map((a) => ({
      id: a.id,
      typeId: a.type?.id ?? a.typeId,
      typeName: a.type?.name ?? a.typeId,
      formSchemaKey: a.type?.formSchema?.key ?? null,
      source: a.source,
      priority: a.priority,
      confidence: a.confidence,
      visibility: a.visibility,
      expires: a.expires,
      attributes: a.attributes,
    }))
})

// ── Debugging: per-attribute raw JSON ────────────────────────────────
// Off by default; toggled per attribute to inspect exactly what the
// server returned (including fields hidden behind a rendered form).

const rawJsonOpen = ref<Record<string, boolean>>({})

function toggleRawJson(id: string) {
  rawJsonOpen.value[id] = !rawJsonOpen.value[id]
}

function rawAttributeJson(id: string): string {
  const attr = (profile.value?.attributes ?? []).find((a) => a.id === id)
  return JSON.stringify(attr ?? null, null, 2)
}

// ── UI state ─────────────────────────────────────────────────────────

const editModalOpen = ref(false)
const saving = ref(false)
const editForm = reactive({ name: '', slug: '', visibility: 'USER' })

interface EditableAttribute {
  id: string | undefined
  typeId: string
  typeName: string
  formSchemaKey: string | null
  source: string
  priority: string
  confidence: string
  visibility: string
  expires: string | null
  attributes: Record<string, unknown>
}

const editingAttributes = ref(false)
const editAttributes = ref<EditableAttribute[]>([])
const savingAttributes = ref(false)
const selectedNewAttributeType = ref<string | undefined>(undefined)

const confirmOpen = ref(false)
const confirmTitle = ref('')
const confirmLoading = ref(false)
let confirmAction: (() => Promise<void>) | null = null

const selectedGroupId = ref<string | undefined>(undefined)
const addingGroup = ref(false)

const selectedSegmentId = ref<string | undefined>(undefined)
const addingSegment = ref(false)

const selectedPersonaId = ref<string | undefined>(undefined)
const addingPersona = ref(false)

const principalIdInput = ref('')
const linkingPrincipal = ref(false)
const settingPrimary = ref(false)

const activeTab = ref('Attributes')
const addOrgModalOpen = ref(false)

// ── API Tokens ──────────────────────────────────────────────────────

const apiTokensGql = gql`
  query GetProfileApiTokens($principalId: UUID!, $limit: Int!, $offset: Long!) {
    security {
      apiTokens {
        list: forPrincipal(principalId: $principalId, limit: $limit, offset: $offset) {
          id principalId name description tokenPrefix scopes expiresAt lastUsedAt revokedAt active
        }
      }
    }
  }
`

const apiTokenScopesGql = gql`
  query GetProfileApiTokenScopes {
    security { apiTokens { availableScopes { name description } } }
  }
`

const createApiTokenForPrincipalGql = gql`
  mutation CreateApiTokenForProfilePrincipal($principalId: UUID!, $input: ApiTokenInput!) {
    security {
      apiTokens {
        createForPrincipal(principalId: $principalId, input: $input) {
          apiToken { id name tokenPrefix active }
          rawToken
        }
      }
    }
  }
`

const revokeApiTokenGql = gql`
  mutation RevokeProfileApiToken($id: Long!) { security { apiTokens { revoke(id: $id) } } }
`

const deleteApiTokenGql = gql`
  mutation DeleteProfileApiToken($id: Long!) { security { apiTokens { delete(id: $id) } } }
`

interface ApiToken {
  id: string
  principalId: string
  name: string
  description: string | null
  tokenPrefix: string
  scopes: string[] | null
  expiresAt: string | null
  lastUsedAt: string | null
  revokedAt: string | null
  active: boolean
}

interface ApiTokenScope {
  name: string
  description: string
}

const apiTokens = ref<ApiToken[]>([])
const apiTokensLoading = ref(false)
const apiTokenScopes = ref<ApiTokenScope[]>([])

async function loadApiTokens() {
  const principalId = profile.value?.principal?.id
  if (!principalId) return
  apiTokensLoading.value = true
  try {
    const result = await gqlQuery<{ security: { apiTokens: { list: ApiToken[] } } }>(
      apiTokensGql,
      { principalId, limit: 100, offset: 0 },
    )
    apiTokens.value = result.security.apiTokens.list ?? []
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to load API tokens')
    apiTokens.value = []
  } finally {
    apiTokensLoading.value = false
  }
}

async function loadApiTokenScopes() {
  if (apiTokenScopes.value.length > 0) return
  try {
    const result = await gqlQuery<{
      security: { apiTokens: { availableScopes: ApiTokenScope[] } }
    }>(apiTokenScopesGql)
    apiTokenScopes.value = result.security.apiTokens.availableScopes ?? []
  } catch {
    apiTokenScopes.value = []
  }
}

// Token creation
const apiTokenModalOpen = ref(false)
const apiTokenScopeBuilderOpen = ref(false)
const apiTokenSaving = ref(false)
const apiTokenForm = reactive({
  name: '',
  description: '',
  scopes: [] as string[],
  expiresAt: '',
})
const createdRawToken = ref<string | null>(null)
const createdTokenCopied = ref(false)

function defaultApiTokenExpiration(): string {
  const d = new Date()
  d.setDate(d.getDate() + 90)
  return d.toISOString().slice(0, 16)
}

function openCreateApiToken() {
  apiTokenForm.name = ''
  apiTokenForm.description = ''
  apiTokenForm.scopes = []
  apiTokenForm.expiresAt = defaultApiTokenExpiration()
  apiTokenModalOpen.value = true
}

async function onCreateApiToken() {
  const principalId = profile.value?.principal?.id
  if (!principalId) {
    toast.error('Profile has no linked principal')
    return
  }
  if (!apiTokenForm.name.trim()) {
    toast.error('Name is required')
    return
  }
  apiTokenSaving.value = true
  try {
    const input = {
      name: apiTokenForm.name.trim(),
      description: apiTokenForm.description.trim() || null,
      scopes: apiTokenForm.scopes.length > 0 ? apiTokenForm.scopes : null,
      expiresAt: apiTokenForm.expiresAt
        ? new Date(apiTokenForm.expiresAt).toISOString()
        : null,
    }
    const result = await gqlMutation<{
      security: { apiTokens: { createForPrincipal: { rawToken: string } } }
    }>(createApiTokenForPrincipalGql, { principalId, input })
    createdRawToken.value = result.security.apiTokens.createForPrincipal.rawToken
    createdTokenCopied.value = false
    apiTokenModalOpen.value = false
    await loadApiTokens()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create token')
  } finally {
    apiTokenSaving.value = false
  }
}

async function copyRawToken() {
  if (!createdRawToken.value) return
  try {
    await navigator.clipboard.writeText(createdRawToken.value)
    createdTokenCopied.value = true
    toast.success('Token copied')
  } catch {
    toast.error('Failed to copy token')
  }
}

function onRevokeApiToken(token: ApiToken) {
  openConfirm(`Revoke '${token.name}'? It will stop working immediately.`, async () => {
    try {
      await gqlMutation(revokeApiTokenGql, { id: parseInt(token.id, 10) })
      toast.success('Token revoked')
      await loadApiTokens()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to revoke token')
    }
  })
}

function onDeleteApiToken(token: ApiToken) {
  openConfirm(`Permanently delete '${token.name}'? This cannot be undone.`, async () => {
    try {
      await gqlMutation(deleteApiTokenGql, { id: parseInt(token.id, 10) })
      toast.success('Token deleted')
      await loadApiTokens()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to delete token')
    }
  })
}

function apiTokenStatus(t: ApiToken): { label: string; color: string } {
  if (t.revokedAt) return { label: 'Revoked', color: '#f87171' }
  if (t.expiresAt && new Date(t.expiresAt) < new Date()) {
    return { label: 'Expired', color: '#ffb547' }
  }
  return t.active
    ? { label: 'Active', color: '#34d99a' }
    : { label: 'Inactive', color: '#6c7388' }
}

// ── Devices ─────────────────────────────────────────────────────────

const devicesGql = gql`
  query GetProfileDevices($principalId: UUID!) {
    devices {
      devices(principalId: $principalId) {
        id
        platform
        created
        lastCheckIn
        installationId
        pushTokens { token created }
      }
    }
  }
`

const deleteDeviceGql = gql`
  mutation DeleteDevice($id: UUID!) {
    devices { delete(id: $id) }
  }
`

interface Device {
  id: string
  platform: string
  created: string
  lastCheckIn: string | null
  installationId: string | null
  pushTokens: Array<{ token: string; created: string }>
}

const devicesData = ref<Device[]>([])
const devicesLoading = ref(false)

async function loadDevices() {
  const principalId = profile.value?.principal?.id
  if (!principalId) return
  devicesLoading.value = true
  try {
    const result = await gqlQuery<{ devices: { devices: Device[] } }>(devicesGql, { principalId })
    devicesData.value = result.devices.devices ?? []
  } catch {
    devicesData.value = []
  } finally {
    devicesLoading.value = false
  }
}

watch(() => activeTab.value, (tab) => {
  if (tab === 'Devices' && devicesData.value.length === 0 && profile.value?.principal?.id) {
    loadDevices()
  }
  if (tab === 'API Tokens' && profile.value?.principal?.id) {
    if (apiTokens.value.length === 0) loadApiTokens()
    loadApiTokenScopes()
  }
})

async function deleteDevice(deviceId: string, platform: string) {
  openConfirm(`Delete ${platform} device?`, async () => {
    try {
      await gqlMutation(deleteDeviceGql, { id: deviceId })
      toast.success('Device deleted')
      await loadDevices()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to delete device')
    }
  })
}

// ── Helpers ──────────────────────────────────────────────────────────

function copyToClipboard(text: string) {
  navigator.clipboard.writeText(text).then(
    () => toast.success('Copied to clipboard'),
    () => toast.error('Failed to copy'),
  )
}

function formatDate(d: string | null | undefined): string {
  if (!d) return '—'
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

function formatDateTime(d: string | null | undefined): string {
  if (!d) return '—'
  return new Date(d).toLocaleString(undefined, {
    month: 'short', day: 'numeric', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}

function openConfirm(title: string, action: () => Promise<void>) {
  confirmTitle.value = title
  confirmAction = action
  confirmOpen.value = true
}

async function doConfirm() {
  if (!confirmAction) return
  confirmLoading.value = true
  try {
    await confirmAction()
  } finally {
    confirmLoading.value = false
    confirmOpen.value = false
    confirmAction = null
  }
}

// ── Actions ──────────────────────────────────────────────────────────

function openEdit() {
  if (!profile.value) return
  editForm.name = profile.value.name
  editForm.slug = profile.value.slug ?? ''
  editForm.visibility = profile.value.visibility
  editModalOpen.value = true
}

async function onSaveProfile() {
  if (!profile.value) return
  saving.value = true
  try {
    const attrs = (profile.value.attributes ?? []).map((a) => ({
      id: a.id,
      typeId: a.type?.id ?? a.typeId,
      source: a.source,
      priority: Number(a.priority),
      confidence: Number(a.confidence),
      visibility: a.visibility,
      expiration: a.expires ? new Date(a.expires).toISOString() : null,
      attributes: a.attributes,
    }))

    await gqlMutation(editProfileGql, {
      id: profileId.value,
      profile: {
        name: editForm.name.trim(),
        slug: editForm.slug.trim() || null,
        visibility: editForm.visibility.toUpperCase(),
        attributes: attrs,
      },
    })
    editModalOpen.value = false
    toast.success('Profile updated')
    await refresh()
  } catch (e: unknown) {
    const err = e as { errors?: Array<{ message: string }>; message?: string }
    toast.error(err?.errors?.[0]?.message ?? err?.message ?? 'Failed to update profile')
  } finally {
    saving.value = false
  }
}

async function onAddGroup() {
  if (!profile.value?.principal?.id || !selectedGroupId.value) return
  addingGroup.value = true
  try {
    await gqlMutation(addGroupGql, {
      principalId: profile.value.principal.id,
      groupId: selectedGroupId.value,
    })
    selectedGroupId.value = undefined
    toast.success('Group added')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to add group')
  } finally {
    addingGroup.value = false
  }
}

function onRemoveGroup(groupId: string, groupName: string) {
  openConfirm(`Remove security group "${groupName}" from this profile?`, async () => {
    if (!profile.value?.principal?.id) return
    try {
      await gqlMutation(removeGroupGql, {
        principalId: profile.value.principal.id,
        groupId,
      })
      toast.success('Group removed')
      refresh()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to remove group')
    }
  })
}

async function onAddPersona() {
  if (!selectedPersonaId.value) return
  addingPersona.value = true
  try {
    await gqlMutation(assignPersonaGql, {
      personaId: selectedPersonaId.value,
      profileId: profileId.value,
    })
    selectedPersonaId.value = undefined
    toast.success('Persona assigned')
    refreshPersonas()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to assign persona')
  } finally {
    addingPersona.value = false
  }
}

function onRemovePersona(personaId: string, personaName: string) {
  openConfirm(`Remove persona "${personaName}" from this profile?`, async () => {
    try {
      await gqlMutation(removePersonaGql, {
        personaId,
        profileId: profileId.value,
      })
      toast.success('Persona removed')
      refreshPersonas()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to remove persona')
    }
  })
}

async function onAddSegment() {
  if (!selectedSegmentId.value) return
  addingSegment.value = true
  try {
    await gqlMutation(addSegmentMemberGql, {
      segmentId: selectedSegmentId.value,
      profileIds: [profileId.value],
    })
    selectedSegmentId.value = undefined
    toast.success('Added to segment')
    refreshSegments()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to add to segment')
  } finally {
    addingSegment.value = false
  }
}

function onRemoveSegment(segmentId: string, segmentName: string) {
  openConfirm(`Remove profile from segment "${segmentName}"?`, async () => {
    try {
      await gqlMutation(removeSegmentMemberGql, {
        segmentId,
        profileIds: [profileId.value],
      })
      toast.success('Removed from segment')
      refreshSegments()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to remove from segment')
    }
  })
}

async function onAddOrganization(orgId: string) {
  if (!profile.value?.principal?.id) {
    toast.error('Profile has no linked principal')
    return
  }
  try {
    await gqlMutation(addOrgMemberGql, {
      organizationId: orgId,
      principalId: profile.value.principal.id,
    })
    addOrgModalOpen.value = false
    toast.success('Organization added')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to add organization')
  }
}

function onRemoveOrganization(orgId: string, orgName: string) {
  openConfirm(`Remove profile from organization "${orgName}"?`, async () => {
    if (!profile.value?.principal?.id) return
    try {
      await gqlMutation(removeOrgMemberGql, {
        organizationId: orgId,
        principalId: profile.value.principal.id,
      })
      toast.success('Organization removed')
      refresh()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to remove organization')
    }
  })
}

async function onLinkPrincipal() {
  if (!principalIdInput.value.trim()) return
  linkingPrincipal.value = true
  try {
    await gqlMutation(setPrincipalGql, {
      id: profileId.value,
      principalId: principalIdInput.value.trim(),
    })
    principalIdInput.value = ''
    toast.success('Principal linked')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to link principal')
  } finally {
    linkingPrincipal.value = false
  }
}

function onUnlinkPrincipal() {
  openConfirm('Unlink principal? The user will no longer authenticate as this profile.', async () => {
    try {
      await gqlMutation(clearPrincipalGql, { id: profileId.value })
      toast.success('Principal unlinked')
      refresh()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to unlink principal')
    }
  })
}

async function onSetPrimary() {
  if (!profile.value?.id) return
  settingPrimary.value = true
  try {
    await gqlMutation(setPrimaryProfileGql, {
      profileId: profile.value.id,
      principalId: profile.value.principal?.id ?? null,
    })
    toast.success('Set as primary profile')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to set primary profile')
  } finally {
    settingPrimary.value = false
  }
}

// ── Danger zone (soft delete → restore → full delete) ─────────────────

const profileDeleted = computed(() => !!profile.value?.deletedAt)
const restoringProfile = ref(false)

function onMarkProfileDeleted() {
  openConfirm(
    'Mark this profile deleted? It will be staged for deletion and removed from search. You can restore it any time before fully deleting.',
    async () => {
      try {
        await gqlMutation(markDeletedProfileGql, { id: profileId.value })
        toast.success('Profile marked deleted')
        await refresh()
      } catch (e: unknown) {
        toast.error(e instanceof Error ? e.message : 'Failed to mark profile deleted')
      }
    },
  )
}

async function onRestoreProfile() {
  restoringProfile.value = true
  try {
    await gqlMutation(restoreProfileGql, { id: profileId.value })
    toast.success('Profile restored')
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to restore profile')
  } finally {
    restoringProfile.value = false
  }
}

function onFullyDeleteProfile() {
  openConfirm(
    'Permanently delete this profile and all of its attributes? This cannot be undone.',
    async () => {
      try {
        await gqlMutation(deleteProfileGql, { id: profileId.value })
        toast.success('Profile deleted')
        router.push('/audience/profiles')
      } catch (e: unknown) {
        toast.error(e instanceof Error ? e.message : 'Failed to delete profile')
      }
    },
  )
}

function openAttributeEditor() {
  editAttributes.value = (profile.value?.attributes ?? [])
    .map((a) => ({
      id: a.id,
      typeId: a.type?.id ?? a.typeId,
      typeName: a.type?.name ?? a.typeId,
      formSchemaKey: a.type?.formSchema?.key ?? null,
      source: a.source,
      priority: String(a.priority),
      confidence: String(a.confidence),
      visibility: a.visibility,
      expires: a.expires ? String(a.expires).substring(0, 16) : null,
      attributes: JSON.parse(JSON.stringify(a.attributes ?? {})) as Record<string, unknown>,
    }))
    .sort((a, b) => Number(a.priority ?? 0) - Number(b.priority ?? 0))
  selectedNewAttributeType.value = undefined
  editingAttributes.value = true
}

function cancelAttributeEditor() {
  editingAttributes.value = false
}

function addAttribute() {
  if (!selectedNewAttributeType.value) return
  const type = attributeTypes.value.find((t) => t.id === selectedNewAttributeType.value)
  if (!type) return
  editAttributes.value.push({
    id: undefined,
    typeId: type.id,
    typeName: type.name,
    formSchemaKey: type.formSchema?.key ?? null,
    source: 'manual',
    priority: '0',
    confidence: '100',
    visibility: 'USER',
    expires: null,
    attributes: {},
  })
  selectedNewAttributeType.value = undefined
}

function removeEditAttribute(index: number) {
  editAttributes.value.splice(index, 1)
}

async function saveAttributes() {
  if (!profile.value) return
  savingAttributes.value = true
  try {
    const attrs = editAttributes.value.map((a) => ({
      id: a.id,
      typeId: a.typeId,
      source: a.source,
      priority: Number(a.priority),
      confidence: Number(a.confidence),
      visibility: a.visibility,
      expiration: a.expires ? new Date(a.expires).toISOString() : null,
      attributes: a.attributes,
    }))

    const originalAttrs = profile.value.attributes ?? []
    const currentIds = new Set(editAttributes.value.map((a) => a.id).filter(Boolean))
    const deletedAttrs = originalAttrs.filter((a) => a.id && !currentIds.has(a.id))

    if (deletedAttrs.length > 0) {
      await Promise.all(deletedAttrs.map((a) =>
        gqlMutation(deleteAttributeGql, {
          attributeId: a.id,
          profileId: profileId.value,
        }),
      ))
    }

    await gqlMutation(editProfileGql, {
      id: profileId.value,
      profile: {
        name: profile.value.name,
        slug: profile.value.slug ?? null,
        visibility: profile.value.visibility.toUpperCase(),
        attributes: attrs,
      },
    })
    editingAttributes.value = false
    toast.success('Attributes saved')
    await refresh()
  } catch (e: unknown) {
    const err = e as { errors?: Array<{ message: string }>; message?: string }
    toast.error(err?.errors?.[0]?.message ?? err?.message ?? 'Failed to save attributes')
  } finally {
    savingAttributes.value = false
  }
}

// Organization search for add-org modal
async function searchOrganizations(q: string): Promise<SelectOption[]> {
  const searchGql = `
    query FindOrganizations($query: String!, $filter: String!) {
      search {
        search(query: {
          query: $query
          filter: [$filter]
          offset: 0
          limit: 20
          storageSystemName: "Admin Search Index"
        }) {
          documents {
            profile {
              id
              name
              organizations { id }
            }
          }
        }
      }
    }
  `
  interface OrgSearchDoc {
    profile: {
      id: string
      name: string
      organizations: Array<{ id: string }>
    } | null
  }
  const result = await gqlQuery<{ search: { search: { documents: OrgSearchDoc[] } } }>(searchGql, {
    query: q || '*',
    filter: '_type = "profile" AND contentType = "bosca/v-profile-organization"',
  })
  return (result.search.search.documents ?? [])
    .filter((d) => d.profile?.organizations?.[0]?.id)
    .map((d) => ({
      value: d.profile!.organizations[0]!.id,
      label: d.profile!.name,
    }))
}

const VISIBILITY_COLORS: Record<string, string> = {
  PUBLIC: '#34d99a',
  FRIENDS: '#5ec5ff',
  FRIENDS_OF_FRIENDS: '#9d7cff',
  USER: '#ffb547',
  SYSTEM: '#6c7388',
}

const VISIBILITY_OPTIONS: SelectOption[] = [
  { value: 'PUBLIC', label: 'Public' },
  { value: 'FRIENDS', label: 'Friends' },
  { value: 'FRIENDS_OF_FRIENDS', label: 'Friends of Friends' },
  { value: 'USER', label: 'User' },
  { value: 'SYSTEM', label: 'System' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Audience', 'Profiles', profile?.name ?? '…')"
        :title="profile?.name ?? 'Loading…'"
        :subtitle="profile ? `${profile.type} · ${email || 'No email'}` : ''"
        :tabs="['Attributes', 'Segments', 'Feature Flags', 'Security', 'Devices', 'API Tokens']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #actions>
          <Button
            v-if="profile && !profile.isPrimary"
            size="sm"
            icon="star"
            :disabled="settingPrimary"
            @click="onSetPrimary">
            Set Primary
          </Button>
          <Button size="sm" icon="pencil" @click="openEdit">Edit</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !profile" class="loading-state">Loading…</div>

    <template v-else-if="profile">
      <!-- ── Attributes tab ───────────────────────────────── -->
      <div v-if="activeTab === 'Attributes'" class="detail-layout">
        <div class="main-content">
          <SectionCard :title="`Attributes · ${editingAttributes ? editAttributes.length : attributeRows.length}`">
            <template #right>
              <template v-if="editingAttributes">
                <Button size="sm" @click="cancelAttributeEditor">Cancel</Button>
                <Button
                  size="sm"
                  primary
                  :accent="accent"
                  :disabled="savingAttributes"
                  @click="saveAttributes">
                  {{ savingAttributes ? 'Saving…' : 'Save' }}
                </Button>
              </template>
              <Button
                v-else
                size="sm"
                icon="pencil"
                @click="openAttributeEditor">Edit</Button>
            </template>

            <div v-if="editingAttributes" class="card-body">
              <div v-for="(attr, index) in editAttributes" :key="attr.id ?? index" class="attr-card">
                <div class="attr-card-header">
                  <span class="attr-card-title">{{ attr.typeName || attr.typeId }}</span>
                  <button class="remove-btn" @click="removeEditAttribute(index)">
                    <Icon name="trash" :size="12" color="var(--fg-3)" />
                  </button>
                </div>
                <div class="attr-fields">
                  <TextInput v-model="attr.source" label="Source" />
                  <TextInput v-model="attr.priority" label="Priority" type="number" />
                  <TextInput v-model="attr.confidence" label="Confidence" type="number" />
                  <Select v-model="attr.visibility" label="Visibility" :options="VISIBILITY_OPTIONS" />
                </div>
                <div class="attr-data-section">
                  <span class="attr-data-label">Data</span>
                  <BoscaForm
                    v-if="attr.formSchemaKey"
                    v-model="attr.attributes"
                    :schema-key="attr.formSchemaKey"
                  />
                  <JsonEditorVue
                    v-else
                    v-model="attr.attributes"
                    :main-menu-bar="false"
                    :navigation-bar="false"
                    :status-bar="false"
                    class="json-editor"
                  />
                </div>
              </div>
              <div class="attr-add-row">
                <Select
                  v-model="selectedNewAttributeType"
                  placeholder="Select attribute type…"
                  searchable
                  :options="attributeTypes.map((t) => ({ value: t.id, label: t.protected ? `${t.name} (protected)` : t.name }))"
                  :accent="accent"
                />
                <Button size="sm" :disabled="!selectedNewAttributeType" @click="addAttribute">Add</Button>
              </div>
            </div>

            <template v-else>
              <div v-if="!attributeRows.length" class="card-body empty-state">No attributes</div>
              <div v-else class="card-body">
                <div v-for="attr in attributeRows" :key="attr.id" class="attr-card">
                  <div class="attr-card-header">
                    <span class="attr-card-title">{{ attr.typeName }}</span>
                    <span class="attr-card-actions">
                      <button
                        class="debug-json-btn"
                        :class="{ active: rawJsonOpen[attr.id] }"
                        :title="rawJsonOpen[attr.id] ? 'Hide raw JSON' : 'Show raw JSON (debugging)'"
                        @click="toggleRawJson(attr.id)">
                        <Icon name="braces" :size="12" :color="rawJsonOpen[attr.id] ? 'var(--fg-1)' : 'var(--fg-3)'" />
                      </button>
                      <Badge :color="VISIBILITY_COLORS[attr.visibility] ?? 'var(--fg-3)'">{{ attr.visibility }}</Badge>
                    </span>
                  </div>
                  <div class="attr-meta-row">
                    <span class="attr-meta-item">Source: <strong>{{ attr.source || '—' }}</strong></span>
                    <span class="attr-meta-item">Priority: <strong>{{ attr.priority ?? '—' }}</strong></span>
                    <span class="attr-meta-item">Confidence: <strong>{{ attr.confidence ?? '—' }}</strong></span>
                    <span v-if="attr.expires" class="attr-meta-item">Expires: <strong>{{ formatDate(attr.expires) }}</strong></span>
                  </div>
                  <div v-if="rawJsonOpen[attr.id]" class="attr-data-section">
                    <span class="attr-data-label">Raw JSON</span>
                    <pre class="attr-raw-json">{{ rawAttributeJson(attr.id) }}</pre>
                  </div>
                  <div v-else-if="attr.attributes && Object.keys(attr.attributes).length" class="attr-data-section">
                    <span class="attr-data-label">Data</span>
                    <BoscaForm
                      v-if="attr.formSchemaKey"
                      :model-value="attr.attributes"
                      :schema-key="attr.formSchemaKey"
                      readonly
                    />
                    <JsonEditorVue
                      v-else
                      :model-value="attr.attributes"
                      :main-menu-bar="false"
                      :navigation-bar="false"
                      :status-bar="false"
                      read-only
                      class="json-editor"
                    />
                  </div>
                </div>
              </div>
            </template>
          </SectionCard>
        </div>

        <div class="sidebar">
          <!-- Identity card -->
          <SectionCard>
            <div class="card-body">
              <div class="identity-header">
                <Avatar :name="profile.name" :src="avatarUrl || undefined" :size="56" />
                <div>
                  <div class="identity-name">{{ profile.name }}</div>
                  <div v-if="email" class="identity-email mono">{{ email }}</div>
                </div>
              </div>
              <div class="identity-meta">
                <div class="meta-row">
                  <span class="meta-label">Type</span>
                  <Badge color="var(--fg-3)">{{ profile.type }}</Badge>
                </div>
                <div class="meta-row">
                  <span class="meta-label">Visibility</span>
                  <Badge :color="VISIBILITY_COLORS[profile.visibility] ?? 'var(--fg-3)'">{{ profile.visibility }}</Badge>
                </div>
                <div class="meta-row">
                  <span class="meta-label">Verified</span>
                  <Badge :color="profile.principal?.verified ? 'var(--ok)' : 'var(--warn)'">
                    {{ profile.principal?.verified ? 'Yes' : 'No' }}
                  </Badge>
                </div>
                <div v-if="profile.isPrimary" class="meta-row">
                  <span class="meta-label">Primary</span>
                  <Badge :color="accent">Primary</Badge>
                </div>
                <div v-if="profileDeleted" class="meta-row">
                  <span class="meta-label">Status</span>
                  <Badge color="var(--err)">Marked deleted</Badge>
                </div>
              </div>
            </div>
          </SectionCard>

          <!-- Details -->
          <SectionCard title="Details">
            <div class="card-body">
              <div class="meta-grid">
                <div class="meta-item">
                  <span class="meta-label">ID</span>
                  <button class="id-value-btn" :title="profile.id" @click="copyToClipboard(profile.id)">
                    <span class="meta-value mono id-value">{{ profile.id }}</span>
                    <Icon name="copy" :size="11" color="var(--fg-3)" />
                  </button>
                </div>
                <div v-if="profile.slug" class="meta-item">
                  <span class="meta-label">Slug</span>
                  <span class="meta-value mono">{{ profile.slug }}</span>
                </div>
                <div class="meta-item">
                  <span class="meta-label">Created</span>
                  <span class="meta-value">{{ formatDate(profile.created) }}</span>
                </div>
                <div class="meta-item">
                  <span class="meta-label">Modified</span>
                  <span class="meta-value">{{ formatDate(profile.modified) }}</span>
                </div>
                <div class="meta-item">
                  <span class="meta-label">Last Login</span>
                  <span class="meta-value">{{ formatDateTime(profile.lastLogin) }}</span>
                </div>
              </div>
            </div>
          </SectionCard>

          <!-- Organizations -->
          <SectionCard title="Organizations">
            <template #right>
              <Button
                v-if="profile.principal"
                size="sm"
                icon="plus"
                @click="addOrgModalOpen = true">Add</Button>
            </template>
            <div class="card-body">
              <div v-if="!profile.organizations?.length" class="empty-state">No organizations</div>
              <div v-else class="org-list">
                <div
                  v-for="org in profile.organizations"
                  :key="org.id"
                  class="org-row"
                >
                  <OrgMark :name="org.profile?.name ?? ''" :size="24" />
                  <NuxtLink :to="`/audience/organizations/${org.id}`" class="org-link">
                    {{ org.profile?.name ?? org.id }}
                  </NuxtLink>
                  <span class="spacer" />
                  <button class="remove-btn" @click="onRemoveOrganization(org.id, org.profile?.name ?? '')">
                    <Icon name="x" :size="11" color="var(--fg-3)" />
                  </button>
                </div>
              </div>
            </div>
          </SectionCard>

          <!-- Other Profiles -->
          <SectionCard v-if="otherProfiles.length" title="Other Profiles">
            <div class="card-body">
              <div class="other-profiles">
                <NuxtLink
                  v-for="op in otherProfiles"
                  :key="op.id"
                  :to="`/audience/profiles/${op.id}`"
                  class="other-profile-link"
                >
                  <Avatar :name="op.name" :size="24" />
                  <span>{{ op.name }}</span>
                </NuxtLink>
              </div>
            </div>
          </SectionCard>

          <!-- Danger Zone -->
          <SectionCard title="Danger Zone">
            <div class="card-body danger-zone">
              <p class="danger-hint">
                <template v-if="profileDeleted">
                  This profile is staged for deletion. Restore it to re-enable, or fully delete to remove it permanently.
                </template>
                <template v-else>
                  Mark the profile deleted first (reversible); it can then be permanently deleted.
                </template>
              </p>
              <div class="danger-actions">
                <Button
                  v-if="!profileDeleted"
                  size="sm"
                  icon="trash"
                  class="danger-btn"
                  @click="onMarkProfileDeleted">Mark Deleted</Button>
                <Button
                  v-if="profileDeleted"
                  size="sm"
                  icon="refresh"
                  :disabled="restoringProfile"
                  @click="onRestoreProfile">Restore</Button>
                <Button
                  size="sm"
                  icon="trash"
                  class="danger-btn"
                  :disabled="!profileDeleted"
                  @click="onFullyDeleteProfile">Fully Delete</Button>
              </div>
            </div>
          </SectionCard>
        </div>
      </div>

      <ProfileFlagAssignments
        v-if="activeTab === 'Feature Flags'"
        :key="profileId"
        :principal-id="profile?.principal?.id ?? null"
        :accent="accent"
      />

      <!-- ── Segments tab ─────────────────────────────────── -->
      <div v-if="activeTab === 'Segments'">
        <SectionCard :title="`Segments · ${segments.length}`">
          <div class="card-body">
            <div v-if="!segments.length" class="empty-state">No segments</div>
            <div v-else class="badge-list">
              <div v-for="s in segments" :key="s.id" class="badge-item">
                <NuxtLink :to="`/audience/segments/${s.id}`">
                  <Badge :color="accent">{{ s.name }}</Badge>
                </NuxtLink>
                <Badge color="var(--fg-3)">{{ s.type }}</Badge>
                <button v-if="s.type === 'STATIC'" class="remove-btn" @click="onRemoveSegment(s.id, s.name)">
                  <Icon name="x" :size="11" color="var(--fg-3)" />
                </button>
              </div>
            </div>
            <div class="add-row">
              <Select
                v-model="selectedSegmentId"
                placeholder="Add to segment…"
                :options="availableStaticSegments"
                searchable
                :accent="accent"
              />
              <Button size="sm" :disabled="!selectedSegmentId || addingSegment" @click="onAddSegment">Add</Button>
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- ── Security tab ─────────────────────────────────── -->
      <div v-if="activeTab === 'Security'" class="security-tab">
        <SectionCard title="Principal">
          <div class="card-body">
            <div v-if="profile.principal" class="principal-info">
              <div class="meta-row">
                <span class="meta-label">Principal ID</span>
                <button class="id-value-btn" :title="profile.principal.id" @click="copyToClipboard(profile.principal.id)">
                  <span class="meta-value mono id-value">{{ profile.principal.id }}</span>
                  <Icon name="copy" :size="11" color="var(--fg-3)" />
                </button>
              </div>
              <div class="meta-row">
                <span class="meta-label">Verified</span>
                <Badge :color="profile.principal.verified ? 'var(--ok)' : 'var(--warn)'">
                  {{ profile.principal.verified ? 'Yes' : 'No' }}
                </Badge>
              </div>
              <div v-if="profile.principal.credentials?.length" class="credentials-section">
                <span class="meta-label">Credentials</span>
                <div class="credentials-list">
                  <Badge v-for="(c, i) in profile.principal.credentials" :key="i" color="var(--fg-3)">
                    {{ c.type }}: {{ c.identifier }}
                  </Badge>
                </div>
              </div>
              <Button
                size="sm"
                icon="x"
                class="unlink-btn"
                @click="onUnlinkPrincipal">
                Unlink Principal
              </Button>
            </div>
            <div v-else class="link-principal">
              <TextInput v-model="principalIdInput" placeholder="Principal UUID" />
              <Button size="sm" :disabled="!principalIdInput.trim() || linkingPrincipal" @click="onLinkPrincipal">
                {{ linkingPrincipal ? 'Linking…' : 'Link' }}
              </Button>
            </div>
          </div>
        </SectionCard>

        <SectionCard title="Studio Personas">
          <div v-if="!profilePersonas.length" class="card-body empty-state">No personas assigned</div>
          <div v-else class="group-table">
            <div v-for="p in profilePersonas" :key="p.id" class="group-row">
              <span class="group-name">{{ p.name }}</span>
              <button class="remove-btn" @click="onRemovePersona(p.id, p.name)">
                <Icon name="x" :size="11" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <div class="card-body">
            <div class="add-row">
              <Select
                v-model="selectedPersonaId"
                placeholder="Select persona…"
                :options="availablePersonas"
                :accent="accent"
              />
              <Button size="sm" :disabled="!selectedPersonaId || addingPersona" @click="onAddPersona">Add</Button>
            </div>
          </div>
        </SectionCard>

        <SectionCard title="Security Groups">
          <div v-if="!currentGroups.length" class="card-body empty-state">No groups</div>
          <div v-else class="group-table">
            <div v-for="g in currentGroups" :key="g.id" class="group-row">
              <span class="group-name">{{ g.description || g.name }}</span>
              <Badge color="var(--fg-3)">{{ g.type }}</Badge>
              <button class="remove-btn" @click="onRemoveGroup(g.id, g.description || g.name)">
                <Icon name="x" :size="11" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <div class="card-body">
            <div class="add-row">
              <Select
                v-model="selectedGroupId"
                searchable
                placeholder="Search groups…"
                :on-search="searchGroups"
                :accent="accent"
              />
              <Button size="sm" :disabled="!selectedGroupId || addingGroup" @click="onAddGroup">Add</Button>
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- ── API Tokens tab ────────────────────────────────── -->
      <div v-if="activeTab === 'API Tokens'" class="api-tokens-tab">
        <SectionCard
          title="API Tokens"
          :subtitle="profile.principal
            ? `${apiTokens.length} token${apiTokens.length === 1 ? '' : 's'} for this principal`
            : 'Requires a linked principal'"
        >
          <template #right>
            <div v-if="profile.principal" class="header-actions">
              <Button
                size="sm"
                icon="refresh"
                :disabled="apiTokensLoading"
                @click="loadApiTokens">Refresh</Button>
              <Button
                primary
                icon="plus"
                size="sm"
                :accent="accent"
                @click="openCreateApiToken">Create Token</Button>
            </div>
          </template>

          <div v-if="!profile.principal" class="card-body empty-state">
            No principal linked. API tokens authenticate as a principal — link one on the Security tab first.
          </div>
          <div v-else-if="apiTokensLoading && apiTokens.length === 0" class="card-body empty-state">
            Loading tokens…
          </div>
          <div v-else-if="apiTokens.length === 0" class="card-body empty-state">
            No API tokens yet. Create one for programmatic access to the API.
          </div>
          <div v-else class="token-list">
            <div v-for="t in apiTokens" :key="t.id" class="token-row">
              <div class="token-row-main">
                <div class="token-row-header">
                  <span class="token-row-name">{{ t.name }}</span>
                  <Badge :color="apiTokenStatus(t).color">{{ apiTokenStatus(t).label }}</Badge>
                </div>
                <div v-if="t.description" class="token-row-desc">{{ t.description }}</div>
                <div class="token-row-meta">
                  <span class="token-prefix mono">{{ t.tokenPrefix }}…</span>
                  <span class="token-meta-item">
                    Scopes:
                    <strong v-if="t.scopes?.length">{{ t.scopes.length }}</strong>
                    <strong v-else>Unrestricted</strong>
                  </span>
                  <span class="token-meta-item">
                    Expires:
                    <strong>{{ t.expiresAt ? formatDate(t.expiresAt) : 'Never' }}</strong>
                  </span>
                  <span class="token-meta-item">
                    Last used:
                    <strong>{{ t.lastUsedAt ? formatDateTime(t.lastUsedAt) : 'Never' }}</strong>
                  </span>
                </div>
                <div v-if="t.scopes?.length" class="token-scopes">
                  <Badge v-for="s in t.scopes" :key="s" color="var(--fg-3)">{{ s }}</Badge>
                </div>
              </div>
              <div class="token-row-actions">
                <Button
                  v-if="t.active && !t.revokedAt"
                  size="sm"
                  icon="x"
                  @click="onRevokeApiToken(t)">Revoke</Button>
                <button
                  v-if="t.revokedAt"
                  class="remove-btn"
                  title="Delete token record"
                  @click="onDeleteApiToken(t)">
                  <Icon name="trash" :size="12" color="var(--fg-3)" />
                </button>
              </div>
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- ── Devices tab ──────────────────────────────────── -->
      <div v-if="activeTab === 'Devices'" class="devices-tab">
        <SectionCard title="Devices" :subtitle="`${devicesData.length} registered device${devicesData.length === 1 ? '' : 's'}`">
          <template #right>
            <Button
              size="sm"
              icon="refresh"
              :disabled="devicesLoading"
              @click="loadDevices">Refresh</Button>
          </template>
          <div v-if="!profile.principal" class="card-body empty-state">
            No principal linked. Devices require an authenticated principal.
          </div>
          <div v-else-if="devicesLoading && devicesData.length === 0" class="card-body empty-state">
            Loading devices...
          </div>
          <div v-else-if="devicesData.length === 0" class="card-body empty-state">
            No devices registered for this profile.
          </div>
          <div v-else class="device-list">
            <div v-for="device in devicesData" :key="device.id" class="device-row">
              <div class="device-info">
                <div class="device-header">
                  <Badge :color="accent">{{ device.platform }}</Badge>
                  <span class="device-id mono">{{ device.id.slice(0, 8) }}...</span>
                </div>
                <div class="device-meta">
                  <span>Created: {{ formatDateTime(device.created) }}</span>
                  <span>Last check-in: {{ formatDateTime(device.lastCheckIn) }}</span>
                  <span v-if="device.installationId">Installation: {{ device.installationId.slice(0, 12) }}...</span>
                </div>
                <div v-if="device.pushTokens?.length" class="device-tokens">
                  <span class="device-tokens-label">Push tokens ({{ device.pushTokens.length }})</span>
                  <div v-for="(tok, i) in device.pushTokens" :key="i" class="token-item">
                    <span class="mono token-value">{{ tok.token.slice(0, 24) }}...</span>
                    <span class="token-created">{{ formatDate(tok.created) }}</span>
                  </div>
                </div>
              </div>
              <button class="remove-btn" @click="deleteDevice(device.id, device.platform)">
                <Icon name="trash" :size="12" color="var(--fg-3)" />
              </button>
            </div>
          </div>
        </SectionCard>
      </div>
    </template>

    <div v-else class="empty-centered">
      Profile not found.
    </div>

    <!-- Edit Modal -->
    <Modal
      v-if="editModalOpen"
      title="Edit Profile"
      icon="pencil"
      :accent="accent"
      @close="editModalOpen = false"
    >
      <TextInput v-model="editForm.name" label="Name" />
      <TextInput v-model="editForm.slug" label="Slug" placeholder="Optional" />
      <Select
        v-model="editForm.visibility"
        label="Visibility"
        :options="[
          { value: 'PUBLIC', label: 'Public' },
          { value: 'FRIENDS', label: 'Friends' },
          { value: 'FRIENDS_OF_FRIENDS', label: 'Friends of Friends' },
          { value: 'USER', label: 'User' },
          { value: 'SYSTEM', label: 'System' },
        ]"
      />
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="editModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="saving"
          @click="onSaveProfile">
          {{ saving ? 'Saving…' : 'Save' }}
        </Button>
      </template>
    </Modal>

    <!-- Add Organization Modal -->
    <Modal
      v-if="addOrgModalOpen"
      title="Add Organization"
      icon="building"
      :accent="accent"
      @close="addOrgModalOpen = false"
    >
      <Select
        searchable
        placeholder="Search organizations…"
        :on-search="searchOrganizations"
        :accent="accent"
        @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && v && onAddOrganization(v)"
      />
    </Modal>

    <!-- Confirm Modal -->
    <ConfirmModal
      v-if="confirmOpen"
      :title="confirmTitle"
      confirm-label="Confirm"
      :loading="confirmLoading"
      @close="confirmOpen = false"
      @confirm="doConfirm"
    />

    <!-- Create API Token Modal -->
    <Modal
      v-if="apiTokenModalOpen"
      title="Create API Token"
      icon="key"
      :accent="accent"
      @close="apiTokenModalOpen = false"
    >
      <div class="token-create-form">
        <TextInput
          v-model="apiTokenForm.name"
          label="Name"
          placeholder="e.g. CI Deploy Token"
          autofocus
        />
        <Textarea
          v-model="apiTokenForm.description"
          label="Description"
          placeholder="What is this token for?"
          :rows="2"
        />
        <DateInput
          v-model="apiTokenForm.expiresAt"
          label="Expiration"
          type="datetime-local"
        />
        <div class="field-hint">Defaults to 90 days. Clear for no expiration.</div>
        <div class="scopes-field">
          <div class="scopes-field-label">Scopes</div>
          <div v-if="apiTokenForm.scopes.length" class="scopes-field-tags">
            <Badge v-for="s in apiTokenForm.scopes" :key="s" color="var(--fg-3)">{{ s }}</Badge>
          </div>
          <div v-else class="scopes-field-empty">Unrestricted — all permissions of the principal</div>
          <Button
            size="sm"
            icon="key"
            :accent="accent"
            @click="apiTokenScopeBuilderOpen = true">
            {{ apiTokenForm.scopes.length ? 'Edit Scopes' : 'Configure Scopes' }}
          </Button>
        </div>
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="apiTokenModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!apiTokenForm.name.trim() || apiTokenSaving"
          @click="onCreateApiToken">
          {{ apiTokenSaving ? 'Creating…' : 'Create' }}
        </Button>
      </template>
    </Modal>

    <!-- Scope Builder Modal -->
    <Modal
      v-if="apiTokenScopeBuilderOpen"
      title="Configure Scopes"
      icon="key"
      :accent="accent"
      width="640px"
      @close="apiTokenScopeBuilderOpen = false"
    >
      <div class="scope-builder-body">
        <ScopeBuilder
          v-model="apiTokenForm.scopes"
          :available-scopes="apiTokenScopes"
          :accent="accent"
        />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button
          size="sm"
          primary
          :accent="accent"
          @click="apiTokenScopeBuilderOpen = false">Done</Button>
      </template>
    </Modal>

    <!-- Token Created Success Modal -->
    <Modal
      v-if="createdRawToken"
      title="Token Created"
      icon="check"
      accent="#34d99a"
      width="600px"
      @close="createdRawToken = null"
    >
      <p class="created-warning">
        Copy this token now. You won't be able to see it again.
      </p>
      <small class="mt-2">The username for this token is <b>api_token</b></small>
      <div class="token-display">
        <code class="mono">{{ createdRawToken }}</code>
        <Button
          size="sm"
          :icon="createdTokenCopied ? 'check' : 'copy'"
          @click="copyRawToken">
          {{ createdTokenCopied ? 'Copied' : 'Copy' }}
        </Button>
      </div>
    </Modal>
  </PageShell>
</template>

<style scoped>
.spacer {
  flex: 1;
}

.loading-state {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.card-body {
  padding: 10px 16px 14px;
}

.empty-centered {
  padding: 40px;
  text-align: center;
  font-size: 12.5px;
  color: var(--fg-3);
}

/* ── Attribute cards ── */
.attr-card {
  padding: 14px 0;
  border-top: 1px solid var(--line);
}

.attr-card:first-child {
  border-top: none;
  padding-top: 0;
}

.attr-card:last-child {
  padding-bottom: 0;
}

.attr-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.attr-card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.attr-fields {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr 1fr;
  gap: 10px;
  margin-bottom: 12px;
}

.attr-meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 10px;
}

.attr-meta-item {
  font-size: 12px;
  color: var(--fg-3);
}

.attr-meta-item strong {
  color: var(--fg-1);
  font-weight: 500;
}

.attr-data-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.attr-data-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.json-editor {
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}

.attr-card-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.debug-json-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: none;
  border: none;
  cursor: pointer;
  transition: background 0.1s;
}

.debug-json-btn:hover,
.debug-json-btn.active {
  background: var(--bg-2);
}

.attr-raw-json {
  margin: 0;
  padding: 10px 12px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--fg-1);
  max-height: 360px;
  overflow: auto;
  white-space: pre;
}

.attr-add-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.attr-add-row > :first-child {
  flex: 1;
}

.unlink-btn {
  color: var(--err);
  margin-top: 8px;
}

.id-value-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: none;
  border: none;
  cursor: pointer;
  padding: 2px 4px;
  margin: -2px -4px;
  border-radius: 4px;
  transition: background 0.1s;
  max-width: 180px;
}

.id-value-btn:hover {
  background: var(--bg-2);
}

.id-value {
  font-size: 10.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-layout {
  display: grid;
  grid-template-columns: 1fr 280px;
  gap: 18px;
  align-items: start;
}

.main-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.sidebar {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.empty-state {
  font-size: 12.5px;
  color: var(--fg-3);
}

/* ── Identity card ── */
.identity-header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 10px;
}

.identity-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--fg-0);
}

.identity-email {
  font-size: 11.5px;
  color: var(--fg-3);
}

.identity-meta {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* ── Meta rows ── */
.meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.meta-label {
  font-size: 12px;
  color: var(--fg-3);
  font-weight: 500;
}

.meta-value {
  font-size: 12px;
  color: var(--fg-1);
}

.meta-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.meta-item {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

/* ── Org list ── */
.org-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.org-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}

.org-row + .org-row {
  border-top: 1px solid var(--line);
}

.org-link {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
  text-decoration: none;
}

.org-link:hover {
  text-decoration: underline;
}

/* ── Badge list ── */
.badge-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}

.badge-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/* ── Add row ── */
/* ── Security tab ── */
.security-tab {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.group-table {
  display: flex;
  flex-direction: column;
}

.group-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-top: 1px solid var(--line);
}

.group-row:first-child {
  border-top: none;
}

.group-name {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.add-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  margin-top: 8px;
}

.add-row > :first-child {
  flex: 1;
}

/* ── Remove button ── */
.remove-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: none;
  border: none;
  cursor: pointer;
  transition: background 0.1s;
}

.remove-btn:hover {
  background: color-mix(in oklch, var(--err) 14%, transparent);
}

/* ── Principal ── */
.principal-info {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.credentials-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 4px;
}

.credentials-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.link-principal {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}

.link-principal > :first-child {
  flex: 1;
}

/* ── Other profiles ── */
.other-profiles {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.other-profile-link {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 7px;
  font-size: 12.5px;
  color: var(--fg-1);
  text-decoration: none;
  transition: background 0.1s;
}

.other-profile-link:hover {
  background: var(--bg-2);
}

/* ── Devices tab ── */
.devices-tab {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.device-list {
  display: flex;
  flex-direction: column;
}

.device-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border-top: 1px solid var(--line);
}

.device-row:first-child {
  border-top: none;
}

.device-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.device-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.device-id {
  font-size: 11px;
  color: var(--fg-3);
}

.device-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 12px;
  color: var(--fg-3);
}

.device-tokens {
  margin-top: 4px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.device-tokens-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.token-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
}

.token-value {
  color: var(--fg-2);
}

.token-created {
  color: var(--fg-3);
}

/* ── API Tokens tab ── */
.api-tokens-tab {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.token-list {
  display: flex;
  flex-direction: column;
}

.token-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border-top: 1px solid var(--line);
}

.token-row:first-child {
  border-top: none;
}

.token-row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.token-row-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.token-row-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg-0);
}

.token-row-desc {
  font-size: 12px;
  color: var(--fg-2);
}

.token-row-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 12px;
  color: var(--fg-3);
  align-items: center;
}

.token-row-meta strong {
  color: var(--fg-1);
  font-weight: 500;
}

.token-meta-item {
  font-size: 12px;
  color: var(--fg-3);
}

.token-prefix {
  font-size: 11.5px;
  color: var(--fg-3);
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--bg-2);
}

.token-scopes {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 2px;
}

.token-row-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

/* ── Token create form ── */
.token-create-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field-hint {
  font-size: 11px;
  color: var(--fg-4);
  margin-top: -8px;
}

.scopes-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.scopes-field-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.scopes-field-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.scopes-field-empty {
  font-size: 12px;
  color: var(--fg-4);
}

.scope-builder-body {
  overflow-y: auto;
  max-height: calc(90vh - 140px);
}

.created-warning {
  font-size: 13px;
  color: var(--fg-1);
  margin: 0 0 10px;
}

.token-display {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow-x: auto;
}

.token-display code {
  font-size: 12px;
  color: var(--fg-0);
  word-break: break-all;
  flex: 1;
}

/* ── Danger zone ── */
.danger-zone {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.danger-hint {
  margin: 0;
  font-size: 12px;
  color: var(--fg-3);
  line-height: 1.5;
}

.danger-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.danger-btn {
  color: var(--err);
}
</style>
