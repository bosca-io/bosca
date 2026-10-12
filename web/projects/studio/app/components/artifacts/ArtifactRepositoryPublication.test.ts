import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { buildASTSchema, Kind, parse, print, validate, type DocumentNode } from 'graphql'
import SyncSettings from './ArtifactRepositoryPublication.vue'

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
  id: 'destination-1', key: 'github', owner: 'bosca-io', githubRepository: 'bosca', githubRepositoryId: 123,
  tagPrefix: 'cli-v', tokenSecretName: 'GITHUB_TOKEN', enabled: true, version: 4,
}
const publication = {
  id: 'publication-1', destinationId: destination.id, tagName: 'cli-v1.2.3', commitSha: 'a'.repeat(40),
  attempts: 2, published: '2026-10-09T12:00:00Z', verified: null, error: 'Verification failed',
  files: [{ filename: 'SHA256SUMS', digest: 'sha256:abc', size: 64 }],
}
function button(wrapper: VueWrapper, label: string) {
  const result = wrapper.findAll('button').find(item => item.text() === label)
  if (!result) throw new Error('Missing button: ' + label)
  return result
}
function mountSettings() {
  return mount(SyncSettings, { props: { repositoryId: 'artifact-1' }, global: { stubs } })
}
beforeEach(() => {
  vi.clearAllMocks()
  query.mockImplementation((document: DocumentNode) => Promise.resolve(
    print(document).includes('ArtifactPublicationSettings')
      ? { artifactsAdmin: { publicationDestinations: [destination] }, pipelines: { secrets: [{ name: 'GITHUB_TOKEN' }, { name: 'NEW_TOKEN' }] } }
      : print(document).includes('ArtifactPublicationVersions')
        ? { artifactsAdmin: { repository: { versionCount: 1, versions: [{ id: 'version-1', version: '1.2.3' }] } } }
        : { artifactsAdmin: { publications: [publication] } },
  ))
  mutation.mockResolvedValue({ artifactsAdmin: { createPublicationDestination: { ...destination, id: 'new' } } })
})
describe('Raw artifact GitHub release settings', () => {
  it('creates an opt-in CLI destination with its tag prefix and secret reference', async () => {
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Add destination').trigger('click')
    for (const [label, value] of Object.entries({ 'Destination name': 'github', 'GitHub owner': 'bosca-io', 'GitHub repository': 'bosca', 'GitHub repository ID': '123', 'Tag prefix': 'cli-v', 'Token secret name': 'GITHUB_TOKEN' })) {
      await wrapper.get(`input[aria-label="${label}"]`).setValue(value)
    }
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({ input: {
      repositoryId: 'artifact-1', key: 'github', owner: 'bosca-io', githubRepository: 'bosca', githubRepositoryId: 123,
      tagPrefix: 'cli-v', tokenSecretName: 'GITHUB_TOKEN', enabled: false,
    } })
    expect(wrapper.text()).toContain('Release destination saved.')
    expect(wrapper.find('form').exists()).toBe(false)
  })
  it('keeps the target fixed and updates activation and credentials with optimistic locking', async () => {
    mutation.mockResolvedValue({ artifactsAdmin: { updatePublicationDestination: { ...destination, enabled: false, version: 5 } } })
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Edit destination').trigger('click')
    for (const label of ['Destination name', 'GitHub owner', 'GitHub repository', 'GitHub repository ID', 'Tag prefix']) {
      expect(wrapper.get(`input[aria-label="${label}"]`).attributes('disabled')).toBeDefined()
    }
    await wrapper.get('input[aria-label="Token secret name"]').setValue('NEW_TOKEN')
    await wrapper.get('input[type="checkbox"]').setValue(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1]).toEqual({ id: destination.id, version: 4, enabled: false, tokenSecretName: 'NEW_TOKEN' })
  })
  it('preserves independent publication and verification status and the fixed file manifest', async () => {
    const wrapper = mountSettings()
    await flushPromises()
    expect(query.mock.calls.map(call => call[1])).toEqual([
      { repositoryId: 'artifact-1' }, { repositoryId: 'artifact-1', limit: 15, offset: 0 },
      { versionId: 'version-1', limit: 16, offset: 0 },
    ])
    expect(wrapper.text()).toContain('github · cli-v1.2.3')
    expect(wrapper.text()).toContain('Published:')
    expect(wrapper.text()).toContain('Verification failed')
    expect(wrapper.text()).toContain('SHA256SUMS')
    query.mockResolvedValue({ artifactsAdmin: { publications: [{ ...publication, error: null, verified: '2026-10-09T13:00:00Z' }] } })
    await button(wrapper, 'Refresh status').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Verified')
    expect(wrapper.text()).not.toContain('Verification failed')
  })
  it('pages versions and publication results using offsets', async () => {
    const initialQuery = query.getMockImplementation()
    query.mockImplementation((document: DocumentNode, variables: { offset: number }) => {
      if (print(document).includes('ArtifactPublicationVersions')) return Promise.resolve({ artifactsAdmin: { repository: {
        versionCount: 16, versions: [{ id: variables.offset ? 'version-16' : 'version-1', version: variables.offset ? '1.2.2' : '1.2.3' }],
      } } })
      if (print(document).includes('ArtifactPublicationHistory')) return Promise.resolve({ artifactsAdmin: { publications: variables.offset ? [publication] : Array.from({ length: 16 }, (_, i) => ({ ...publication, id: `publication-${i}` })) } })
      return initialQuery?.(document, variables)
    })
    const wrapper = mountSettings()
    await flushPromises()
    expect(wrapper.findAll('.publication')).toHaveLength(15)
    await button(wrapper, 'Next publications').trigger('click')
    await flushPromises()
    expect(query.mock.calls.at(-1)?.[1]).toEqual({ versionId: 'version-1', limit: 16, offset: 15 })
    await button(wrapper, 'Next versions').trigger('click')
    await flushPromises()
    expect(query.mock.calls.at(-1)?.[1]).toEqual({ versionId: 'version-16', limit: 16, offset: 0 })
  })
  it('blocks missing secrets and retains edits on a failed save', async () => {
    const wrapper = mountSettings()
    await flushPromises()
    await button(wrapper, 'Edit destination').trigger('click')
    await wrapper.get('input[aria-label="Token secret name"]').setValue('MISSING')
    await wrapper.get('form').trigger('submit')
    expect(mutation).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Add the token secret')
    await wrapper.get('input[aria-label="Token secret name"]').setValue('NEW_TOKEN')
    mutation.mockRejectedValue(new Error('Version conflict'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('Version conflict')
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('Release destination saved.')
  })
  it('shows loading failures without claiming the repository has no destinations or versions', async () => {
    query.mockRejectedValue(new Error('Access denied'))
    const wrapper = mountSettings()
    await flushPromises()
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(2)
    expect(button(wrapper, 'Add destination').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('No release destinations configured.')
    expect(wrapper.text()).not.toContain('No versions published.')
  })
})
