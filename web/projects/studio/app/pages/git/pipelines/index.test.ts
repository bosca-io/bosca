import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { print, type DocumentNode } from 'graphql'
import PipelineListPage from './index.vue'
import { GitPipelineInputType, type GitPipelineInputDefinition } from '~/types/graphql'

const query = vi.fn()
const push = vi.fn()
let canExecute = true
let inputs: GitPipelineInputDefinition[] = []

function declaration(name: string, type: GitPipelineInputType, fields: Partial<GitPipelineInputDefinition> = {}): GitPipelineInputDefinition {
  return { name, type, options: [], defaultValue: null, description: null, required: fields.defaultValue == null, ...fields }
}

vi.mock('@bosca/auth-client-browser', () => ({ useAuth: () => ({ profile: ref(null) }) }))
vi.stubGlobal('useRouter', () => ({ push }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#34d99a' }))
vi.stubGlobal('useGraphQL', () => ({ query }))
vi.stubGlobal('useLastGitOwner', () => ({ load: () => ({ id: 'owner-1', label: 'Owner' }), save: vi.fn() }))
vi.stubGlobal('useProfileSearch', () => ({ searchProfiles: vi.fn() }))
vi.stubGlobal('buildBreadcrumb', (...parts: unknown[]) => parts)

const stubs = {
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: { template: '<header><slot name="actions" /></header>' },
  Icon: { template: '<span />' },
  Button: { template: '<button :disabled="disabled"><slot /></button>', props: ['disabled'] },
  Modal: { template: '<section class="run-modal"><slot /><footer><slot name="footer" /></footer></section>' },
  Select: {
    name: 'Select',
    template: '<label>{{ label }}<select :data-label="label" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option value="" /><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>',
    props: ['label', 'modelValue', 'options', 'onSearch'],
    emits: ['update:modelValue'],
  },
  Input: {
    template: '<label>{{ label }}<input :data-label="label" :type="type" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>',
    props: ['label', 'modelValue', 'type'],
    emits: ['update:modelValue'],
  },
}

function mountPage() {
  return mount(PipelineListPage, { global: { stubs, mocks: { buildBreadcrumb: (...parts: unknown[]) => parts } } })
}

function runCalls() {
  return query.mock.calls.filter(([document]) => print(document as DocumentNode).includes('mutation TriggerPipeline'))
}

async function openRun() {
  const wrapper = mountPage()
  await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === 'Run')!.trigger('click')
  await flushPromises()
  return wrapper
}

describe('manual pipeline runs', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    canExecute = true
    inputs = [
      declaration('image', GitPipelineInputType.Choice, { options: ['bosca-server', 'bosca-runner'], description: 'Image to publish' }),
      declaration('version', GitPipelineInputType.String, { description: 'Version to publish' }),
    ]
    query.mockImplementation(async (document: DocumentNode) => {
      const operation = print(document)
      if (operation.includes('ReposForPipelines')) {
        return { git: { repositories: [{ id: 'repo-1', name: 'Bosca', canExecute }] } }
      }
      if (operation.includes('query Pipelines(')) {
        return { git: { pipelines: [{ id: 'image-release', name: 'Bosca Image Release', triggerTypes: ['MANUAL', 'RELEASE'], runs: [] }] } }
      }
      if (operation.includes('PipelineRunRefs')) {
        return { git: { tags: [{ name: '7.4.0' }], branches: [{ name: 'main' }] } }
      }
      if (operation.includes('PipelineRunInputs')) return { git: { pipelineInputs: inputs } }
      if (operation.includes('mutation TriggerPipeline')) return { git: { triggerPipeline: { id: 'run-1' } } }
      throw new Error(`Unexpected operation: ${operation}`)
    })
  })

  it('requires image and version, then submits them with the selected ref', async () => {
    const wrapper = await openRun()
    const run = wrapper.get('.run-modal footer button:last-child')
    expect(run.attributes('disabled')).toBeDefined()
    await wrapper.get('select[data-label="image"]').setValue('bosca-server')
    expect(run.attributes('disabled')).toBeDefined()
    await wrapper.get('input[data-label="version"]').setValue('7.4.1')
    expect(run.attributes('disabled')).toBeUndefined()
    await run.trigger('click')
    await flushPromises()
    expect(runCalls()[0]?.[1]).toEqual({
      pipelineId: 'image-release', ref: 'refs/tags/7.4.0', inputs: [{ name: 'image', value: 'bosca-server' }, { name: 'version', value: '7.4.1' }],
    })
    expect(push).toHaveBeenCalledWith('/git/pipelines/run-1')
    wrapper.unmount()
  })

  it('preserves tag creation order and searches bounded pages for older refs', async () => {
    const original = query.getMockImplementation()!
    const names = ['v2', 'v10', 'v1', ...Array.from({ length: 23 }, (_, i) => `older-${i}`)]
    query.mockImplementation(async (document: DocumentNode, variables: { offset?: number; query?: string }) => {
      if (print(document).includes('PipelineRunRefs')) {
        return { git: {
          tags: (variables.query ? ['legacy'] : variables.offset ? ['older-22'] : names).map(name => ({ name })),
          branches: variables.offset ? [] : [{ name: 'main' }],
        } }
      }
      return original(document, variables)
    })
    const wrapper = await openRun()
    const select = wrapper.findAllComponents({ name: 'Select' }).find(component => component.props('label') === 'Ref')!
    const options = select.props('options')
    expect(options.slice(0, 3).map((option: { value: string }) => option.value)).toEqual(['refs/tags/v2', 'refs/tags/v10', 'refs/tags/v1'])
    expect(options).toHaveLength(26) // 25 tags and one branch.
    expect(query.mock.calls.find(([document]) => print(document).includes('PipelineRunRefs'))?.[1]).toEqual({
      repositoryId: 'repo-1', limit: 26, offset: 0, query: '', includeBranches: true, includeTags: true,
    })
    const next = await select.props('onSearch')('', 25)
    expect(next.options).toEqual([{ value: 'refs/tags/older-22', label: 'older-22 (tag)' }])
    expect(next.previousOffset).toBe(0)
    expect(next.nextOffset).toBeUndefined()
    expect(query).toHaveBeenLastCalledWith(expect.anything(), {
      repositoryId: 'repo-1', limit: 26, offset: 25, query: '', includeBranches: false, includeTags: true,
    })
    const filtered = await select.props('onSearch')('legacy', 0)
    expect(filtered.options).toEqual([{ value: 'refs/tags/legacy', label: 'legacy (tag)' }])
    select.vm.$emit('update:modelValue', filtered.options[0].value)
    await flushPromises()
    expect(query.mock.calls.some(([document, variables]) => print(document).includes('PipelineRunInputs') && variables.ref === 'refs/tags/legacy')).toBe(true)
    wrapper.unmount()
  })

  it('normalizes qualified ref searches and restricts them to the requested namespace', async () => {
    const original = query.getMockImplementation()!
    query.mockImplementation(async (document: DocumentNode, variables: { includeTags?: boolean; includeBranches?: boolean }) => {
      if (print(document).includes('PipelineRunRefs')) return { git: {
        ...(variables.includeTags ? { tags: [{ name: 'release' }] } : {}),
        ...(variables.includeBranches ? { branches: [{ name: 'release' }] } : {}),
      } }
      return original(document, variables)
    })
    const wrapper = await openRun()
    const select = wrapper.findAllComponents({ name: 'Select' }).find(component => component.props('label') === 'Ref')!
    for (const prefix of ['refs/tags/', 'REFS/TAGS/', 'refs/heads/']) {
      const tag = prefix.toLowerCase() === 'refs/tags/'
      const page = await select.props('onSearch')(`${prefix}release`, 0)
      expect(page.options).toEqual([{ value: `refs/${tag ? 'tags' : 'heads'}/release`, label: `release (${tag ? 'tag' : 'branch'})` }])
      expect(query).toHaveBeenLastCalledWith(expect.anything(), {
        repositoryId: 'repo-1', limit: 26, offset: 0, query: 'release', includeBranches: !tag, includeTags: tag,
      })
    }
    wrapper.unmount()
  })

  it('preserves a selected tag when the initial ref request finishes later', async () => {
    const original = query.getMockImplementation()!
    let releaseInitial: ((result: unknown) => void) | undefined
    let refRequests = 0
    query.mockImplementation(async (document: DocumentNode, variables: unknown) => {
      if (print(document).includes('PipelineRunRefs') && ++refRequests === 1) {
        return new Promise(resolve => { releaseInitial = resolve })
      }
      if (print(document).includes('PipelineRunRefs')) {
        return { git: { tags: [{ name: 'chosen-older' }], branches: [] } }
      }
      return original(document, variables)
    })
    const wrapper = mountPage()
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === 'Run')!.trigger('click')
    await flushPromises()
    const select = wrapper.findAllComponents({ name: 'Select' }).find(component => component.props('label') === 'Ref')!
    const page = await select.props('onSearch')('chosen-older', 0)
    select.vm.$emit('update:modelValue', page.options[0].value)
    await flushPromises()
    expect(select.props('modelValue')).toBe('refs/tags/chosen-older')
    releaseInitial?.({ git: { tags: [{ name: '7.4.0' }], branches: [{ name: 'main' }] } })
    await flushPromises()
    expect(select.props('modelValue')).toBe('refs/tags/chosen-older')
    wrapper.unmount()
  })

  it('applies declared defaults and keeps pipelines without inputs runnable', async () => {
    inputs = [
      declaration('enabled', GitPipelineInputType.Boolean, { defaultValue: 'false' }),
      declaration('count', GitPipelineInputType.Number, { defaultValue: '2' }),
    ]
    const wrapper = await openRun()
    expect((wrapper.get('select[data-label="enabled"]').element as HTMLSelectElement).value).toBe('false')
    expect((wrapper.get('input[data-label="count"]').element as HTMLInputElement).value).toBe('2')
    await wrapper.get('.run-modal footer button:last-child').trigger('click')
    await flushPromises()
    expect(runCalls()[0]?.[1].inputs).toEqual([{ name: 'enabled', value: 'false' }, { name: 'count', value: '2' }])
    wrapper.unmount()

    inputs = []
    const plain = await openRun()
    expect(plain.get('.run-modal footer button:last-child').attributes('disabled')).toBeUndefined()
    await plain.get('.run-modal footer button:last-child').trigger('click')
    await flushPromises()
    expect(runCalls()[1]?.[1].inputs).toEqual([])
    plain.unmount()
  })

  it('disables Run when execution permission is absent', async () => {
    canExecute = false
    const wrapper = mountPage()
    await flushPromises()
    const run = wrapper.findAll('button').find(button => button.text() === 'Run')!
    expect(run.attributes('disabled')).toBeDefined()
    await run.trigger('click')
    expect(wrapper.find('.run-modal').exists()).toBe(false)
    expect(runCalls()).toHaveLength(0)
    wrapper.unmount()
  })

  it('blocks dispatch if input declarations cannot be loaded', async () => {
    const original = query.getMockImplementation()!
    query.mockImplementation(async (document: DocumentNode, variables: unknown) => {
      if (print(document).includes('PipelineRunInputs')) throw new Error('Definition unavailable')
      return original(document, variables)
    })
    const wrapper = await openRun()
    expect(wrapper.get('.run-modal').text()).toContain('Definition unavailable')
    expect(wrapper.get('.run-modal footer button:last-child').attributes('disabled')).toBeDefined()
    expect(runCalls()).toHaveLength(0)
    wrapper.unmount()
  })

  it('ignores stale input declarations after the selected ref changes', async () => {
    const original = query.getMockImplementation()!
    let resolveOld: ((value: unknown) => void) | undefined
    query.mockImplementation(async (document: DocumentNode, variables: { ref?: string }) => {
      if (print(document).includes('PipelineRunInputs') && variables.ref === 'refs/tags/7.4.0') {
        return new Promise(resolve => { resolveOld = resolve })
      }
      return original(document, variables)
    })
    const wrapper = await openRun()
    expect(wrapper.get('.run-modal footer button:last-child').attributes('disabled')).toBeDefined()
    await wrapper.get('select[data-label="Ref"]').setValue('refs/heads/main')
    await flushPromises()
    resolveOld?.({ git: { pipelineInputs: [declaration('oldInput', GitPipelineInputType.String)] } })
    await flushPromises()
    expect(wrapper.find('input[data-label="oldInput"]').exists()).toBe(false)
    await wrapper.get('select[data-label="image"]').setValue('bosca-runner')
    await wrapper.get('input[data-label="version"]').setValue('7.4.2')
    await wrapper.get('.run-modal footer button:last-child').trigger('click')
    await flushPromises()
    expect(runCalls()[0]?.[1]).toEqual({
      pipelineId: 'image-release', ref: 'refs/heads/main', inputs: [{ name: 'image', value: 'bosca-runner' }, { name: 'version', value: '7.4.2' }],
    })
    wrapper.unmount()
  })
})
