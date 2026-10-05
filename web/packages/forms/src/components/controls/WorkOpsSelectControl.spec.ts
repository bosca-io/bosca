import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { mount } from '../../test-helpers'
import { BoscaFormContextKey } from '../../context'
import WorkOpsSelectControl from './WorkOpsSelectControl.vue'

const SelectStub = defineComponent({
  props: ['modelValue', 'options', 'placeholder', 'disabled', 'loading'],
  emits: ['update:modelValue'],
  template: '<div />',
})
const context = { apiUrl: '/graphql', getToken: () => 'workops-token' }
let fetchSpy: ReturnType<typeof vi.spyOn>
enableAutoUnmount(afterEach)
beforeEach(() => { fetchSpy = vi.spyOn(globalThis, 'fetch') })
afterEach(() => vi.restoreAllMocks())

function render(entity?: string, withContext = true, value: unknown = null, placeholder?: string) {
  return mount(WorkOpsSelectControl, {
    props: { value, node: { type: 'field', property: 'task', control: 'workops-select', workOpsEntity: entity, placeholder }, readonly: true },
    global: { stubs: { Select: SelectStub }, provide: withContext ? { [BoscaFormContextKey as symbol]: context } : {} },
  })
}
function respond(data: unknown) {
  fetchSpy.mockResolvedValueOnce(new Response(JSON.stringify({ data }), { status: 200 }))
}

describe('WorkOpsSelectControl', () => {
  it.each([
    ['projects', { workOps: { projects: { all: [{ id: '1', name: 'Project' }, {}] } } }],
    ['taskTypes', { workOps: { tasks: { taskTypes: [{ id: '1', name: 'Project' }, {}] } } }],
    ['priorities', { workOps: { tasks: { priorities: [{ id: '1', name: 'Project' }, {}] } } }],
    ['statuses', { workOps: { statuses: { all: [{ id: '1', name: 'Project' }, {}] } } }],
    ['resolutions', { workOps: { tasks: { resolutions: [{ id: '1', name: 'Project' }, {}] } } }],
  ])('loads authenticated %s options through its configured query', async (entity, data) => {
    respond(data)
    const wrapper = render(entity)
    await flushPromises()
    const select = wrapper.findComponent(SelectStub)
    expect(select.props()).toMatchObject({
      options: [{ value: '1', label: 'Project' }, { value: '', label: '' }],
      modelValue: '', placeholder: 'Select...', disabled: true, loading: false,
    })
    const request = fetchSpy.mock.calls[0][1] as RequestInit
    expect(request.headers).toMatchObject({ Authorization: 'Bearer workops-token' })
    expect(JSON.parse(request.body as string).query).toContain(entity === 'statuses' ? 'statuses' : entity)
    select.vm.$emit('update:modelValue', '1')
    select.vm.$emit('update:modelValue', undefined)
    expect(wrapper.emitted('update:value')).toEqual([['1'], ['']])
  })

  it('shows request and response-shape failures and finishes loading', async () => {
    fetchSpy.mockRejectedValueOnce(new Error('Offline'))
    const failed = render('projects', true, 'old', 'Choose a project')
    await flushPromises()
    expect(failed.text()).toContain('Offline')
    expect(failed.findComponent(SelectStub).props()).toMatchObject({ modelValue: 'old', placeholder: 'Choose a project', loading: false })
    respond({ workOps: { projects: { all: null } } })
    const malformed = render('projects')
    await flushPromises()
    expect(malformed.text()).toContain('Could not resolve options at "workOps.projects.all"')
    expect(malformed.findComponent(SelectStub).props('loading')).toBe(false)
  })

  it.each([undefined, 'unknown'])('rejects the unconfigured entity %s before issuing a request', async entity => {
    const wrapper = render(entity)
    await flushPromises()
    expect(wrapper.text()).toContain('Unknown workOpsEntity')
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('does not fetch without the form authentication context', async () => {
    const wrapper = render('projects', false)
    await flushPromises()
    expect(wrapper.text()).toContain('No BoscaForm context')
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
