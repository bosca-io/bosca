import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { buildASTSchema, Kind, parse, print, validate, type DocumentNode } from 'graphql'
import SyncSettings from './ArtifactRepositorySync.vue'

// Check operations against the current backend SDL while retaining the rest of
// Studio's schema (including the shared encrypted secret operations).
const artifactSchema = parse(readFileSync(resolve('../../../artifacts/artifacts-admin/src/main/resources/graphql/artifacts-admin.graphqls'), 'utf8'))
const definitions = artifactSchema.definitions.filter(definition => definition.kind !== Kind.OBJECT_TYPE_EXTENSION)
const names = new Set(definitions.flatMap(definition => 'name' in definition ? [definition.name?.value] : []))
const studioSchema = parse(readFileSync(resolve('schema.graphqls'), 'utf8'))
const schema = buildASTSchema({
  kind: Kind.DOCUMENT,
  definitions: [...studioSchema.definitions.filter(definition => !('name' in definition && names.has(definition.name?.value))), ...definitions],
})
enableAutoUnmount(afterEach)

const query = vi.fn()
const mutation = vi.fn()
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#64748b' }))
vi.stubGlobal('useGraphQL', () => ({
  query: (document: DocumentNode, variables: unknown) => {
    expect(validate(schema, document).map(error => error.message)).toEqual([])
    return query(document, variables)
  },
  mutation: (document: DocumentNode, variables: unknown) => {
    expect(validate(schema, document).map(error => error.message)).toEqual([])
    return mutation(document, variables)
  },
}))
const stubs = {
  SectionCard: { props: ['title'], template: '<section><h2>{{ title }}</h2><slot name="right" /><slot /></section>' },
  Button: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  TextInput: {
    props: ['modelValue', 'label', 'disabled', 'type'], emits: ['update:modelValue'],
    template: '<label>{{ label }}<input :aria-label="label" :value="modelValue" :disabled="disabled" :type="type" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>',
  },
  Select: {
    props: ['modelValue', 'label', 'disabled', 'options'], emits: ['update:modelValue'],
    template: '<label>{{ label }}<input :aria-label="label" :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>',
  },
  Modal: { template: '<div class="modal"><slot /></div>' },
  NuxtLink: { props: ['to'], template: '<a><slot /></a>' },
  ConfirmModal: {
    props: ['title', 'subtitle', 'loading'], emits: ['close', 'confirm'],
    template: '<div class="confirm"><h3>{{ title }}</h3><p>{{ subtitle }}</p><slot /><button @click="$emit(\'close\')">Cancel</button><button :disabled="loading" @click="$emit(\'confirm\')">Confirm delete</button></div>',
  },
}
const destination = {
  id: 'destination-1', key: 'public', remoteRepository: 'bosca-io/bosca/server',
  username: 'publisher', tokenSecretName: 'GHCR_TOKEN', enabled: true, version: 4,
}
const failed = {
  id: 'sync-1', destinationId: destination.id, tagName: 'v1', manifestDigest: 'sha256:abc',
  attempts: 2, synced: null, error: 'GHCR returned HTTP 503', modified: '2026-10-07T12:00:00Z',
}
function button(wrapper: VueWrapper, label: string) {
  const result = wrapper.findAll('button').find(item => item.text() === label)
  if (!result) throw new Error('Missing button: ' + label)
  return result
}
function mountSettings() {
  return mount(SyncSettings, { props: { repositoryId: 'artifact-1' }, global: { stubs } })
}
async function openCreate(wrapper: VueWrapper) {
  await button(wrapper, 'Add destination').trigger('click')
  await wrapper.get('input[aria-label="Destination name"]').setValue('mirror')
  await wrapper.get('input[aria-label="GHCR image path"]').setValue('bosca-io/bosca/runner')
  await wrapper.get('input[aria-label="GitHub username"]').setValue('publisher')
  await wrapper.get('input[aria-label="Token secret name"]').setValue('GHCR_TOKEN')
}
beforeEach(() => {
  vi.clearAllMocks()
  query.mockImplementation((document: DocumentNode) => Promise.resolve(
    print(document).includes('ArtifactSyncSettings')
      ? { artifactsAdmin: { syncDestinations: [destination] }, pipelines: { secrets: [{ name: 'GHCR_TOKEN' }] } }
      : print(document).includes('ArtifactPushTags')
        ? { artifactsAdmin: { repository: { tagCount: 2, tags: [{ name: 'v1', manifestDigest: 'sha256:abc' }, { name: 'latest', manifestDigest: 'sha256:def' }] } } }
      : { artifactsAdmin: { syncs: [failed] } },
  ))
  mutation.mockResolvedValue({ artifactsAdmin: { createSyncDestination: { ...destination, id: 'new', key: 'mirror' } } })
})

describe('Artifact GHCR settings', () => {
  it('pushes an existing selected tag to one destination and links its durable run', async () => {
    mutation.mockResolvedValue({ artifactsAdmin: { pushImage: 'run-1' } })
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Push image').trigger('click')
    await flushPromises()
    expect(query.mock.calls.at(-1)?.[1]).toEqual({ repositoryId: 'artifact-1', limit: 15, offset: 0 })
    expect(wrapper.text()).toContain('ghcr.io/bosca-io/bosca/server:v1')
    await wrapper.get('input[aria-label="Source tag"]').setValue('latest')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(print(mutation.mock.calls[0]?.[0])).toContain('pushImage')
    expect(mutation.mock.calls[0]?.[1]).toEqual({ destinationId: destination.id, tagName: 'latest' })
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.text()).toContain('Image push queued.')
    expect(wrapper.findComponent(stubs.NuxtLink).props('to')).toEqual({ path: '/pipelines/runs', query: { runId: 'run-1' } })
    expect(query.mock.calls.at(-1)?.[1]).toEqual({ repositoryId: 'artifact-1', limit: 16, offset: 0 })
  })

  it('blocks pushes for disabled destinations and missing secrets', async () => {
    query.mockImplementation((document: DocumentNode) => Promise.resolve(
      print(document).includes('ArtifactSyncSettings')
        ? { artifactsAdmin: { syncDestinations: [{ ...destination, enabled: false }, { ...destination, id: 'missing-secret', key: 'missing', tokenSecretName: 'missing' }] }, pipelines: { secrets: [{ name: 'GHCR_TOKEN' }] } }
        : { artifactsAdmin: { syncs: [] } },
    ))
    const wrapper = mountSettings()
    await flushPromises()
    const buttons = wrapper.findAll('button').filter(item => item.text() === 'Push image')
    expect(buttons).toHaveLength(2)
    for (const push of buttons) {
      expect(push.attributes('disabled')).toBeDefined()
      await push.trigger('click')
    }
    expect(wrapper.find('form').exists()).toBe(false)
    expect(mutation).not.toHaveBeenCalled()
  })

  it('pages source tags and blocks pushing when no tags exist', async () => {
    const initialQuery = query.getMockImplementation()
    query.mockImplementation((document: DocumentNode, variables: { offset: number }) =>
      print(document).includes('ArtifactPushTags')
        ? Promise.resolve({ artifactsAdmin: { repository: { tagCount: 16, tags: variables.offset ? [{ name: 'last', manifestDigest: 'sha256:last' }] : Array.from({ length: 15 }, (_, i) => ({ name: 'v' + i, manifestDigest: 'sha256:' + i })) } } })
        : initialQuery?.(document, variables),
    )
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Push image').trigger('click')
    await flushPromises()
    await button(wrapper, 'Next tags').trigger('click')
    await flushPromises()
    expect(query.mock.calls.at(-1)?.[1]).toEqual({ repositoryId: 'artifact-1', limit: 15, offset: 15 })
    expect(wrapper.get('input[aria-label="Source tag"]').element).toHaveProperty('value', 'last')
    expect(button(wrapper, 'Next tags').attributes('disabled')).toBeDefined()
    await button(wrapper, 'Cancel').trigger('click')
    query.mockResolvedValue({ artifactsAdmin: { repository: { tagCount: 0, tags: [] } } })
    await button(wrapper, 'Push image').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('No image tags on this page.')
    expect(button(wrapper, 'Push selected tag').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(mutation).not.toHaveBeenCalled()
  })

  it('shows tag loading failures and retains the chosen tag after a push failure', async () => {
    const wrapper = mountSettings()
    await flushPromises()
    query.mockRejectedValueOnce(new Error('Cannot load tags'))
    await button(wrapper, 'Push image').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('Cannot load tags')
    expect(button(wrapper, 'Push selected tag').attributes('disabled')).toBeDefined()
    await button(wrapper, 'Retry loading tags').trigger('click')
    await flushPromises()
    mutation.mockRejectedValueOnce(new Error('Docker tag not found'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('Docker tag not found')
    expect(wrapper.get('input[aria-label="Source tag"]').element).toHaveProperty('value', 'v1')
    expect(wrapper.text()).not.toContain('Image push queued.')
  })

  it('prevents duplicate pushes and closing the dialog while queuing', async () => {
    let finish: (value: unknown) => void = () => {}
    mutation.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Push image').trigger('click')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(mutation).toHaveBeenCalledTimes(1)
    expect(button(wrapper, 'Cancel').attributes('disabled')).toBeDefined()
    wrapper.findComponent(stubs.Modal).vm.$emit('close')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(true)
    finish({ artifactsAdmin: { pushImage: 'run-1' } })
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('requires confirmation and allows cancelling destination deletion', async () => {
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Delete destination').trigger('click')
    expect(wrapper.get('.confirm').text()).toContain("Delete destination 'public'?")
    expect(wrapper.get('.confirm').text()).toContain('Source artifacts, images already pushed to GHCR, and the token secret are kept.')
    expect(mutation).not.toHaveBeenCalled()
    await button(wrapper, 'Cancel').trigger('click')
    expect(wrapper.find('.confirm').exists()).toBe(false)
    expect(wrapper.findAll('.destination')).toHaveLength(1)
    expect(mutation).not.toHaveBeenCalled()
  })

  it('deletes only the selected destination using its version and refreshes sync status', async () => {
    mutation.mockResolvedValue({ artifactsAdmin: { deleteSyncDestination: true } })
    query.mockImplementation((document: DocumentNode) => Promise.resolve(
      print(document).includes('ArtifactSyncSettings')
        ? { artifactsAdmin: { syncDestinations: [destination, { ...destination, id: 'other', key: 'backup' }] }, pipelines: { secrets: [{ name: 'GHCR_TOKEN' }] } }
        : { artifactsAdmin: { syncs: [failed] } },
    ))
    const wrapper = mountSettings()
    await flushPromises()
    query.mockResolvedValue({ artifactsAdmin: { syncs: [] } })
    await wrapper.get('.destination button:last-child').trigger('click')
    await button(wrapper, 'Confirm delete').trigger('click')
    await flushPromises()
    expect(print(mutation.mock.calls[0]?.[0])).toContain('deleteSyncDestination')
    expect(mutation.mock.calls[0]?.[1]).toEqual({ id: destination.id, version: destination.version })
    expect(wrapper.find('.confirm').exists()).toBe(false)
    expect(wrapper.findAll('.destination')).toHaveLength(1)
    expect(wrapper.get('.destination').text()).toContain('backup')
    expect(wrapper.findAll('.sync-row')).toHaveLength(0)
    expect(wrapper.text()).toContain('Sync destination deleted.')
    expect(query.mock.calls.at(-1)?.[1]).toEqual({ repositoryId: 'artifact-1', limit: 16, offset: 0 })
  })

  it('retains the destination and confirmation when deletion fails', async () => {
    mutation.mockRejectedValue(new Error('Sync destination changed; reload before deleting'))
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Delete destination').trigger('click')
    await button(wrapper, 'Confirm delete').trigger('click')
    await flushPromises()
    expect(wrapper.get('.confirm [role="alert"]').text()).toContain('reload before deleting')
    expect(wrapper.findAll('.destination')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('Sync destination deleted.')
    expect(button(wrapper, 'Confirm delete').attributes('disabled')).toBeUndefined()
  })

  it('prevents duplicate deletion and closing the confirmation while deleting', async () => {
    let finish: (value: unknown) => void = () => {}
    mutation.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Delete destination').trigger('click')
    await button(wrapper, 'Confirm delete').trigger('click')
    expect(button(wrapper, 'Confirm delete').attributes('disabled')).toBeDefined()
    await button(wrapper, 'Cancel').trigger('click')
    expect(wrapper.find('.confirm').exists()).toBe(true)
    wrapper.findComponent(stubs.ConfirmModal).vm.$emit('confirm')
    expect(mutation).toHaveBeenCalledTimes(1)
    finish({ artifactsAdmin: { deleteSyncDestination: true } })
    await flushPromises()
    expect(wrapper.find('.confirm').exists()).toBe(false)
  })

  it('loads configuration and sync status for the open artifact and creates a disabled destination', async () => {
    const wrapper = mountSettings()
    await flushPromises()
    expect(query.mock.calls.map(call => call[1])).toEqual([
      { repositoryId: 'artifact-1' }, { repositoryId: 'artifact-1', limit: 16, offset: 0 },
    ])
    expect(wrapper.text()).toContain('ghcr.io/bosca-io/bosca/server:v1')
    await openCreate(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({ input: {
      repositoryId: 'artifact-1', key: 'mirror', remoteRepository: 'bosca-io/bosca/runner',
      username: 'publisher', tokenSecretName: 'GHCR_TOKEN', enabled: false,
    } })
    expect(wrapper.text()).toContain('Sync destination saved.')
  })

  it('disables a destination and rotates credentials using its optimistic version', async () => {
    mutation.mockResolvedValue({ artifactsAdmin: { updateSyncDestination: { ...destination, enabled: false, version: 5 } } })
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Edit destination').trigger('click')
    expect(wrapper.get('input[aria-label="GHCR image path"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('input[aria-label="Destination name"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('input[aria-label="GitHub username"]').setValue('new-publisher')
    await wrapper.get('input[type="checkbox"]').setValue(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({
      id: destination.id, version: 4, enabled: false, username: 'new-publisher', tokenSecretName: 'GHCR_TOKEN',
      key: destination.key, remoteRepository: destination.remoteRepository,
    })
    expect(wrapper.text()).toContain('Disabled')
  })

  it('renames a destination without clearing its sync status', async () => {
    mutation.mockResolvedValue({ artifactsAdmin: { updateSyncDestination: { ...destination, key: 'renamed', version: 5 } } })
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Edit destination').trigger('click')
    await wrapper.get('input[aria-label="Destination name"]').setValue(' renamed ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({
      id: destination.id, version: 4, enabled: true, username: destination.username,
      tokenSecretName: destination.tokenSecretName, key: 'renamed', remoteRepository: destination.remoteRepository,
    })
    expect(wrapper.get('.destination').text()).toContain('renamed')
    expect(wrapper.get('.sync-row').text()).toContain('renamed · v1')
    expect(query).toHaveBeenCalledTimes(2)
  })

  it('edits the image path and refreshes its previous sync status after saving', async () => {
    mutation.mockResolvedValue({ artifactsAdmin: { updateSyncDestination: { ...destination, key: 'renamed', remoteRepository: 'acme/new-image', version: 5 } } })
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Edit destination').trigger('click')
    await wrapper.get('input[aria-label="Destination name"]').setValue('renamed')
    await wrapper.get('input[aria-label="GHCR image path"]').setValue(' acme/new-image ')
    expect(wrapper.text()).toContain('Changing the image path clears sync status for the previous path.')
    query.mockResolvedValue({ artifactsAdmin: { syncs: [] } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toMatchObject({
      id: destination.id, version: 4, key: 'renamed', remoteRepository: 'acme/new-image',
    })
    expect(wrapper.get('.destination').text()).toContain('ghcr.io/acme/new-image')
    expect(wrapper.findAll('.sync-row')).toHaveLength(0)
    expect(query.mock.calls.at(-1)?.[1]).toEqual({ repositoryId: 'artifact-1', limit: 16, offset: 0 })
    expect(wrapper.text()).toContain('Sync destination saved.')
  })

  it('retains destination edits and previous status when an update fails', async () => {
    mutation.mockRejectedValue(new Error('Invalid sync destination key'))
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Edit destination').trigger('click')
    await wrapper.get('input[aria-label="Destination name"]').setValue('invalid name')
    await wrapper.get('input[aria-label="GHCR image path"]').setValue('acme/renamed')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('input[aria-label="Destination name"]').element).toHaveProperty('value', 'invalid name')
    expect(wrapper.get('input[aria-label="GHCR image path"]').element).toHaveProperty('value', 'acme/renamed')
    expect(wrapper.get('[role="alert"]').text()).toContain('Invalid sync destination key')
    expect(wrapper.get('.destination').text()).toContain(destination.remoteRepository)
    expect(wrapper.findAll('.sync-row')).toHaveLength(1)
    expect(query).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('Sync destination saved.')
  })

  it('requires an existing secret to enable sync and can securely create it inline', async () => {
    const wrapper = mountSettings()
    await flushPromises()
    await openCreate(wrapper)
    await wrapper.get('input[aria-label="Token secret name"]').setValue('NEW_TOKEN')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('Add the token secret before enabling')
    expect(mutation).not.toHaveBeenCalled()

    await button(wrapper, 'Add token secret').trigger('click')
    expect(wrapper.text()).toContain('permission to push packages')
    await wrapper.get('input[aria-label="GitHub token"]').setValue('private-token-value')
    mutation.mockResolvedValueOnce({ pipelines: { setSecret: { name: 'NEW_TOKEN' } } })
    await wrapper.get('form.secret-form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({ name: 'NEW_TOKEN', value: 'private-token-value' })
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('private-token-value')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[1]?.[1].input).toMatchObject({ enabled: true, tokenSecretName: 'NEW_TOKEN', repositoryId: 'artifact-1' })
    expect(JSON.stringify(mutation.mock.calls[1]?.[1])).not.toContain('private-token-value')
  })

  it('retains edits and shows save failures without claiming success', async () => {
    mutation.mockRejectedValue(new Error('Version conflict'))
    const wrapper = mountSettings()
    await flushPromises()
    await openCreate(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('Version conflict')
    expect(wrapper.get('input[aria-label="Destination name"]').element).toHaveProperty('value', 'mirror')
    expect(wrapper.text()).not.toContain('Sync destination saved.')
  })

  it('queues failed syncs, refreshes status, and prevents retries for disabled destinations', async () => {
    mutation.mockResolvedValue({ artifactsAdmin: { retrySync: { id: failed.id } } })
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Retry sync').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({ id: failed.id })
    expect(wrapper.text()).toContain('Sync queued for retry.')
    expect(query).toHaveBeenCalledTimes(3)
    query.mockImplementation((document: DocumentNode) => Promise.resolve(
      print(document).includes('ArtifactSyncSettings')
        ? { artifactsAdmin: { syncDestinations: [{ ...destination, enabled: false }] }, pipelines: { secrets: [{ name: 'GHCR_TOKEN' }] } }
        : { artifactsAdmin: { syncs: [failed] } },
    ))
    await button(wrapper, 'Reload destinations').trigger('click')
    await flushPromises()
    expect(button(wrapper, 'Retry sync').attributes('disabled')).toBeDefined()
  })

  it('pages sync status using offset pagination and refreshes completed results', async () => {
    query.mockImplementation((document: DocumentNode, variables: { offset: number }) => Promise.resolve(
      print(document).includes('ArtifactSyncSettings')
        ? { artifactsAdmin: { syncDestinations: [destination] }, pipelines: { secrets: [] } }
        : { artifactsAdmin: { syncs: variables.offset ? [{ ...failed, id: 'last', error: null, synced: '2026-10-07T13:00:00Z' }] : Array.from({ length: 16 }, (_, i) => ({ ...failed, id: 'sync-' + i })) } },
    ))
    const wrapper = mountSettings()
    await flushPromises()
    expect(wrapper.findAll('.sync-row')).toHaveLength(15)
    await button(wrapper, 'Next').trigger('click')
    await flushPromises()
    expect(query.mock.calls.at(-1)?.[1]).toEqual({ repositoryId: 'artifact-1', limit: 16, offset: 15 })
    expect(wrapper.text()).toContain('Synced')
    expect(wrapper.text()).toContain('Page 2')
    expect(button(wrapper, 'Next').attributes('disabled')).toBeDefined()
    await button(wrapper, 'Previous').trigger('click')
    await flushPromises()
    expect(query.mock.calls.at(-1)?.[1].offset).toBe(0)
  })

  it('reports independent loading failures and blocks writes until settings load', async () => {
    query.mockRejectedValue(new Error('Access denied'))
    const wrapper = mountSettings()
    await flushPromises()
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(2)
    expect(button(wrapper, 'Add destination').attributes('disabled')).toBeDefined()
    expect(mutation).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('No sync destinations')
  })
})
