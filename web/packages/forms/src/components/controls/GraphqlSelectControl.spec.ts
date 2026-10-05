import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '../../test-helpers'
import { defineComponent } from 'vue'
import GraphqlSelectControl from './GraphqlSelectControl.vue'
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

function mockGraphQL(data: unknown) {
  fetchSpy.mockResolvedValueOnce(new Response(
    JSON.stringify({ data }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  ))
}

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return {
    type: 'field',
    property: 'category',
    control: 'graphql-select',
    graphql: {
      query: '{ categories { id name } }',
      resultPath: 'categories',
      valuePath: 'id',
      labelPath: 'name',
    },
    ...overrides,
  }
}

function mountWithContext(node: FieldNode, value: unknown = '') {
  return mount(GraphqlSelectControl, {
    props: { value, node, readonly: false },
    global: {
      stubs: { Select: SelectStub },
      provide: { [BoscaFormContextKey as symbol]: ctx },
    },
  })
}

beforeEach(() => {
  fetchSpy = vi.spyOn(globalThis, 'fetch')
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('GraphqlSelectControl', () => {
  it('keeps the latest server search when earlier requests finish later', async () => {
    let completeFirst!: (response: Response) => void
    fetchSpy.mockReturnValueOnce(new Promise<Response>(resolve => { completeFirst = resolve }))
    const wrapper = mountWithContext(makeNode({ graphql: {
      query: 'query Search($term: String!) { items(term: $term) { id name } }',
      resultPath: 'items', valuePath: 'id', labelPath: 'name', searchVariable: 'term',
    } }), null)
    const select = wrapper.findComponent(SelectStub)
    const search = select.props('onSearch')
    const first = search('older')
    mockGraphQL({ items: [{ id: '2', name: 'Latest' }, {}] })
    expect(await search('latest')).toEqual([{ value: '2', label: 'Latest' }, { value: '', label: '' }])
    completeFirst(new Response(JSON.stringify({ data: { items: [{ id: '1', name: 'Old' }] } })))
    await first
    expect(select.props('options')).toEqual([{ value: '2', label: 'Latest' }, { value: '', label: '' }])
    expect(JSON.parse((fetchSpy.mock.calls[1][1] as RequestInit).body as string).variables.term).toBe('latest')
    await wrapper.setProps({ node: makeNode({ placeholder: 'Choose', graphql: undefined }) })
    await search('invalid')
    expect(wrapper.text()).toContain('Missing graphql configuration')
    expect(select.props('placeholder')).toBe('Choose')
    select.vm.$emit('update:modelValue', null)
    expect(wrapper.emitted('update:value')).toEqual([['']])
  })

  it('fetches options on mount for non-search mode', async () => {
    mockGraphQL({ categories: [{ id: '1', name: 'News' }, { id: '2', name: 'Blog' }] })

    const wrapper = mountWithContext(makeNode())
    await flushPromises()

    const options = wrapper.findAll('option')
    expect(options.length).toBe(2)
    expect(options[0].text()).toBe('News')
    expect(options[1].text()).toBe('Blog')
  })

  it('sends the correct GraphQL query with variables', async () => {
    mockGraphQL({ categories: [] })

    mountWithContext(makeNode({
      graphql: {
        query: '{ categories { id name } }',
        variables: { limit: 10 },
        resultPath: 'categories',
        valuePath: 'id',
      },
    } as any))
    await flushPromises()

    const body = JSON.parse((fetchSpy.mock.calls[0][1] as RequestInit).body as string)
    expect(body.query).toBe('{ categories { id name } }')
    expect(body.variables.limit).toBe(10)
  })

  it('includes auth token in the request', async () => {
    mockGraphQL({ categories: [] })

    mountWithContext(makeNode())
    await flushPromises()

    const headers = (fetchSpy.mock.calls[0][1] as RequestInit).headers as Record<string, string>
    expect(headers['Authorization']).toBe('Bearer test-token')
  })

  it('shows no options when graphql config is missing', async () => {
    const node = makeNode()
    delete (node as any).graphql
    const wrapper = mountWithContext(node)
    await flushPromises()

    // Config is null so onMounted doesn't call fetchOptions
    expect(wrapper.findAll('option').length).toBe(0)
  })

  it('shows error when resultPath cannot be resolved', async () => {
    mockGraphQL({ wrongPath: [] })

    const wrapper = mountWithContext(makeNode())
    await flushPromises()

    expect(wrapper.text()).toContain('Could not resolve options')
  })

  it('shows error when fetch returns a GraphQL error', async () => {
    fetchSpy.mockResolvedValueOnce(new Response(
      JSON.stringify({ errors: [{ message: 'Access denied' }] }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ))

    const wrapper = mountWithContext(makeNode())
    await flushPromises()

    expect(wrapper.text()).toContain('Access denied')
  })

  it('sets fetchError when no BoscaFormContext is available', async () => {
    const wrapper = mount(GraphqlSelectControl, {
      props: { value: '', node: makeNode(), readonly: false },
      global: {
        stubs: { Select: SelectStub },
      },
    })
    await flushPromises()

    // onMounted calls fetchOptions, but context is null — sets fetchError
    expect(wrapper.text()).toContain('No BoscaForm context')
  })

  it('does not fetch on mount when searchVariable is set (server-search mode)', async () => {
    const node = makeNode({
      graphql: {
        query: '{ search($term: String!) { categories(term: $term) { id name } } }',
        resultPath: 'categories',
        valuePath: 'id',
        labelPath: 'name',
        searchVariable: 'term',
      },
    } as any)

    mountWithContext(node)
    await flushPromises()

    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('defaults labelPath to valuePath when not provided', async () => {
    mockGraphQL({ items: [{ code: 'A' }, { code: 'B' }] })

    const wrapper = mountWithContext(makeNode({
      graphql: {
        query: '{ items { code } }',
        resultPath: 'items',
        valuePath: 'code',
      },
    } as any))
    await flushPromises()

    const options = wrapper.findAll('option')
    expect(options[0].text()).toBe('A')
    expect(options[0].attributes('value')).toBe('A')
  })

  it('emits update:value when selection changes', async () => {
    mockGraphQL({ categories: [{ id: '1', name: 'News' }] })

    const wrapper = mountWithContext(makeNode())
    await flushPromises()

    await wrapper.find('select').setValue('1')
    expect(wrapper.emitted('update:value')).toBeTruthy()
  })

  it('disables select when readonly', async () => {
    mockGraphQL({ categories: [] })

    const wrapper = mount(GraphqlSelectControl, {
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
