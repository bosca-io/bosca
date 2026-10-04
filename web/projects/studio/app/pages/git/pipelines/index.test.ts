import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { print, type DocumentNode } from 'graphql'
import PipelineListPage from './index.vue'

const query = vi.fn()
const push = vi.fn()
let canExecute = true
let inputs = {
  image: { type: 'choice', options: ['bosca-server', 'bosca-runner'], description: 'Image to publish' },
  version: { type: 'string', description: 'Version to publish' },
} as Record<string, { type: string; options?: string[]; default?: string; description?: string }>

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
    template: '<label>{{ label }}<select :data-label="label" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option value="" /><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>',
    props: ['label', 'modelValue', 'options'],
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
    inputs = {
      image: { type: 'choice', options: ['bosca-server', 'bosca-runner'], description: 'Image to publish' },
      version: { type: 'string', description: 'Version to publish' },
    }
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
      pipelineId: 'image-release', ref: 'refs/tags/7.4.0', inputs: { image: 'bosca-server', version: '7.4.1' },
    })
    expect(push).toHaveBeenCalledWith('/git/pipelines/run-1')
    wrapper.unmount()
  })

  it('applies declared defaults and keeps pipelines without inputs runnable', async () => {
    inputs = { enabled: { type: 'boolean', default: 'false' }, count: { type: 'number', default: '2' } }
    const wrapper = await openRun()
    expect((wrapper.get('select[data-label="enabled"]').element as HTMLSelectElement).value).toBe('false')
    expect((wrapper.get('input[data-label="count"]').element as HTMLInputElement).value).toBe('2')
    await wrapper.get('.run-modal footer button:last-child').trigger('click')
    await flushPromises()
    expect(runCalls()[0]?.[1].inputs).toEqual({ enabled: 'false', count: '2' })
    wrapper.unmount()

    inputs = {}
    const plain = await openRun()
    expect(plain.get('.run-modal footer button:last-child').attributes('disabled')).toBeUndefined()
    await plain.get('.run-modal footer button:last-child').trigger('click')
    await flushPromises()
    expect(runCalls()[1]?.[1].inputs).toEqual({})
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
    resolveOld?.({ git: { pipelineInputs: { oldInput: { type: 'string' } } } })
    await flushPromises()
    expect(wrapper.find('input[data-label="oldInput"]').exists()).toBe(false)
    await wrapper.get('select[data-label="image"]').setValue('bosca-runner')
    await wrapper.get('input[data-label="version"]').setValue('7.4.2')
    await wrapper.get('.run-modal footer button:last-child').trigger('click')
    await flushPromises()
    expect(runCalls()[0]?.[1]).toEqual({
      pipelineId: 'image-release', ref: 'refs/heads/main', inputs: { image: 'bosca-runner', version: '7.4.2' },
    })
    wrapper.unmount()
  })
})
