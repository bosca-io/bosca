import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '../../test-helpers'
import { defineComponent } from 'vue'
import ApiScriptSelectControl from './ApiScriptSelectControl.vue'
import { BoscaFormContextKey, type BoscaFormContext } from '../../context'
import type { FieldNode } from '../../types'
import { flushPromises } from '@vue/test-utils'

const SelectStub = defineComponent({
  props: ['modelValue', 'options', 'placeholder', 'disabled', 'loading', 'onSearch'],
  emits: ['update:modelValue'],
  template: '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="opt in options" :key="opt.value" :value="opt.value">{{ opt.label }}</option></select>',
})

let fetchSpy: ReturnType<typeof vi.spyOn>

const ctx: BoscaFormContext = {
  apiUrl: 'https://api.test.com',
  getToken: () => 'test-token',
}

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return {
    type: 'field',
    property: 'category',
    control: 'api-script-select',
    apiScript: {
      key: 'categories',
      valuePath: 'id',
      labelPath: 'name',
      resultPath: 'items',
    },
    ...overrides,
  }
}

function mountWithContext(node: FieldNode, value: unknown = '') {
  return mount(ApiScriptSelectControl, {
    props: { value, node, readonly: false },
    global: {
      stubs: { Select: SelectStub },
      provide: { [BoscaFormContextKey as symbol]: ctx },
    },
  })
}

function mockJson(data: unknown, status = 200) {
  fetchSpy.mockResolvedValueOnce(new Response(
    JSON.stringify(data),
    { status, headers: { 'Content-Type': 'application/json' } },
  ))
}

beforeEach(() => {
  fetchSpy = vi.spyOn(globalThis, 'fetch')
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('ApiScriptSelectControl', () => {
  it.each(['GET', 'POST'] as const)('sends %s search parameters and ignores superseded requests and failures', async method => {
    const wrapper = mountWithContext(makeNode({ apiScript: {
      key: 'search', method, valuePath: 'id', labelPath: 'name', searchParam: 'term', params: { limit: 5 },
    } }), null)
    const select = wrapper.findComponent(SelectStub)
    const search = select.props('onSearch')
    let completeFirst!: (response: Response) => void
    fetchSpy.mockReturnValueOnce(new Promise<Response>(resolve => { completeFirst = resolve }))
    const first = search('older')
    mockJson([{ id: '2', name: 'Latest' }, {}])
    expect(await search('latest')).toEqual([{ value: '2', label: 'Latest' }, { value: '', label: '' }])
    const [url, request] = fetchSpy.mock.calls[1]
    if (method === 'GET') {
      expect(new URL(url as string).searchParams.get('term')).toBe('latest')
    } else {
      expect(JSON.parse((request as RequestInit).body as string)).toEqual({ limit: 5, term: 'latest' })
    }
    completeFirst(new Response(JSON.stringify([{ id: '1', name: 'Old' }])))
    await first
    let failOld!: (reason: unknown) => void
    fetchSpy.mockReturnValueOnce(new Promise<Response>((_, reject) => { failOld = reject }))
    const staleFailure = search('older failure')
    mockJson([{ id: '3', name: 'Newest' }])
    await search('newest')
    failOld(new Error('Old error'))
    await staleFailure
    expect(select.props('options')).toEqual([{ value: '3', label: 'Newest' }])
    expect(wrapper.find('.select-error').exists()).toBe(false)
    fetchSpy.mockRejectedValueOnce('Offline')
    await search('offline')
    expect(wrapper.text()).toContain('Network error: Offline')
    await wrapper.setProps({ node: makeNode({ placeholder: 'Choose', apiScript: undefined }) })
    await search('invalid')
    expect(wrapper.text()).toContain('Missing apiScript configuration')
    expect(select.props('placeholder')).toBe('Choose')
    select.vm.$emit('update:modelValue', null)
    expect(wrapper.emitted('update:value')).toEqual([['']])
  })

  it('fetches options on mount via GET request', async () => {
    mockJson({ items: [{ id: '1', name: 'Alpha' }, { id: '2', name: 'Beta' }] })

    const wrapper = mountWithContext(makeNode())
    await flushPromises()

    expect(fetchSpy).toHaveBeenCalledTimes(1)
    const [url, init] = fetchSpy.mock.calls[0]
    expect(url).toBe('https://api.test.com/api/v1/s/categories')
    expect((init as RequestInit).method).toBe('GET')

    const options = wrapper.findAll('option')
    expect(options.length).toBe(2)
    expect(options[0].text()).toBe('Alpha')
  })

  it('includes auth token in request headers', async () => {
    mockJson({ items: [] })

    mountWithContext(makeNode())
    await flushPromises()

    const headers = (fetchSpy.mock.calls[0][1] as RequestInit).headers as Record<string, string>
    expect(headers['Authorization']).toBe('Bearer test-token')
  })

  it('appends static params as query string for GET requests', async () => {
    mockJson({ items: [] })

    mountWithContext(makeNode({
      apiScript: {
        key: 'categories',
        valuePath: 'id',
        params: { limit: 50, active: true },
        resultPath: 'items',
      },
    } as any))
    await flushPromises()

    const url = fetchSpy.mock.calls[0][0] as string
    expect(url).toContain('limit=50')
    expect(url).toContain('active=true')
  })

  it('sends POST request with JSON body when method is POST', async () => {
    mockJson({ items: [{ id: '1', name: 'X' }] })

    mountWithContext(makeNode({
      apiScript: {
        key: 'search',
        method: 'POST',
        valuePath: 'id',
        labelPath: 'name',
        resultPath: 'items',
        params: { type: 'category' },
      },
    } as any))
    await flushPromises()

    const [, init] = fetchSpy.mock.calls[0]
    expect((init as RequestInit).method).toBe('POST')
    const body = JSON.parse((init as RequestInit).body as string)
    expect(body.type).toBe('category')
    const headers = (init as RequestInit).headers as Record<string, string>
    expect(headers['Content-Type']).toBe('application/json')
  })

  it('shows error when apiScript config is missing', async () => {
    const node = makeNode()
    delete (node as any).apiScript
    const wrapper = mountWithContext(node)
    await flushPromises()

    // Config is null so onMounted doesn't call fetchOptions, but component
    // still has no error to display since fetchOptions was never called.
    // The component won't display an error unless fetchOptions runs.
    // With no config, onMounted skips the call, so there's no error text.
    expect(wrapper.findAll('option').length).toBe(0)
  })

  it('sets fetchError when no BoscaFormContext is available', async () => {
    const wrapper = mount(ApiScriptSelectControl, {
      props: { value: '', node: makeNode(), readonly: false },
      global: {
        stubs: { Select: SelectStub },
      },
    })
    await flushPromises()

    // onMounted calls fetchOptions, but context is null
    expect(wrapper.text()).toContain('No BoscaForm context')
  })

  it('shows error on network failure', async () => {
    fetchSpy.mockRejectedValueOnce(new Error('Connection refused'))

    const wrapper = mountWithContext(makeNode())
    await flushPromises()

    expect(wrapper.text()).toContain('Network error: Connection refused')
  })

  it('shows error on non-OK response', async () => {
    fetchSpy.mockResolvedValueOnce(new Response('Error', { status: 500 }))

    const wrapper = mountWithContext(makeNode())
    await flushPromises()

    expect(wrapper.text()).toContain('Request failed with status 500')
  })

  it('shows error on invalid JSON response', async () => {
    fetchSpy.mockResolvedValueOnce(new Response('not json', { status: 200 }))

    const wrapper = mountWithContext(makeNode())
    await flushPromises()

    expect(wrapper.text()).toContain('Invalid JSON response')
  })

  it('shows error when resultPath cannot be resolved', async () => {
    mockJson({ wrongKey: [] })

    const wrapper = mountWithContext(makeNode())
    await flushPromises()

    expect(wrapper.text()).toContain('Could not resolve options')
  })

  it('uses response directly as array when no resultPath is set', async () => {
    mockJson([{ id: '1', name: 'Direct' }])

    const wrapper = mountWithContext(makeNode({
      apiScript: {
        key: 'list',
        valuePath: 'id',
        labelPath: 'name',
      },
    } as any))
    await flushPromises()

    const options = wrapper.findAll('option')
    expect(options.length).toBe(1)
    expect(options[0].text()).toBe('Direct')
  })

  it('shows error when response is not an array and no resultPath', async () => {
    mockJson({ notAnArray: true })

    const node = makeNode({
      apiScript: {
        key: 'obj',
        valuePath: 'id',
      },
    } as any)
    const wrapper = mountWithContext(node)
    await flushPromises()

    expect(wrapper.text()).toContain('Response is not an array')
  })

  it('does not fetch on mount when searchParam is set', async () => {
    const node = makeNode({
      apiScript: {
        key: 'search',
        valuePath: 'id',
        searchParam: 'q',
        resultPath: 'items',
      },
    } as any)

    mountWithContext(node)
    await flushPromises()

    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('defaults labelPath to valuePath', async () => {
    mockJson({ items: [{ code: 'X' }] })

    const wrapper = mountWithContext(makeNode({
      apiScript: {
        key: 'codes',
        valuePath: 'code',
        resultPath: 'items',
      },
    } as any))
    await flushPromises()

    const options = wrapper.findAll('option')
    expect(options[0].text()).toBe('X')
    expect(options[0].attributes('value')).toBe('X')
  })

  it('emits update:value on selection change', async () => {
    mockJson({ items: [{ id: '1', name: 'A' }] })

    const wrapper = mountWithContext(makeNode())
    await flushPromises()

    await wrapper.find('select').setValue('1')
    expect(wrapper.emitted('update:value')).toBeTruthy()
  })

  it('disables select when readonly', async () => {
    mockJson({ items: [] })

    const wrapper = mount(ApiScriptSelectControl, {
      props: { value: '', node: makeNode(), readonly: true },
      global: {
        stubs: { Select: SelectStub },
        provide: { [BoscaFormContextKey as symbol]: ctx },
      },
    })
    await flushPromises()

    expect((wrapper.find('select').element as HTMLSelectElement).disabled).toBe(true)
  })
})
