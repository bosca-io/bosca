import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { mount } from '../../test-helpers'
import { BoscaFormContextKey } from '../../context'
import ProfileSelectControl from './ProfileSelectControl.vue'

const SelectStub = defineComponent({
  props: ['modelValue', 'options', 'placeholder', 'disabled', 'loading', 'onSearch'],
  emits: ['update:modelValue'],
  template: '<div />',
})
const context = { apiUrl: '/graphql', getToken: () => 'profile-token' }
let fetchSpy: ReturnType<typeof vi.spyOn>
enableAutoUnmount(afterEach)
beforeEach(() => { fetchSpy = vi.spyOn(globalThis, 'fetch') })
afterEach(() => vi.restoreAllMocks())

function respond(data: unknown) {
  fetchSpy.mockResolvedValueOnce(new Response(JSON.stringify({ data }), { status: 200 }))
}
function render(withContext = true, value: unknown = undefined, placeholder?: string) {
  return mount(ProfileSelectControl, {
    props: { value, node: { type: 'field', property: 'profile', control: 'profile-select', placeholder }, readonly: true },
    global: { stubs: { Select: SelectStub }, provide: withContext ? { [BoscaFormContextKey as symbol]: context } : {} },
  })
}

describe('ProfileSelectControl', () => {
  it('loads authenticated profile options and uses name, slug, then ID as labels', async () => {
    respond({ search: { search: { documents: [
      { profile: { id: '1', name: 'Ada', slug: 'ada' } },
      { profile: { id: '2', name: '', slug: 'grace' } },
      { profile: { id: '3' } },
      { profile: null },
    ] } } })
    const wrapper = render()
    await flushPromises()
    const select = wrapper.findComponent(SelectStub)
    expect(select.props()).toMatchObject({
      options: [{ value: '1', label: 'Ada' }, { value: '2', label: 'grace' }, { value: '3', label: '3' }],
      modelValue: '', placeholder: 'Search profiles…', disabled: true, loading: false,
    })
    const request = fetchSpy.mock.calls[0][1] as RequestInit
    expect(request.headers).toMatchObject({ Authorization: 'Bearer profile-token' })
    expect(JSON.parse(request.body as string).variables).toEqual({ query: '', limit: 20, offset: 0 })
    respond({ search: { search: { documents: [{ profile: { id: '4', name: 'Linus' } }] } } })
    await select.props('onSearch')('Linus')
    expect(JSON.parse((fetchSpy.mock.calls[1][1] as RequestInit).body as string).variables.query).toBe('Linus')
    expect(select.props('options')).toEqual([{ value: '4', label: 'Linus' }])
    select.vm.$emit('update:modelValue', '4')
    select.vm.$emit('update:modelValue', null)
    expect(wrapper.emitted('update:value')).toEqual([['4'], ['']])
  })

  it('retains loaded options on request errors or missing data and clears absent documents', async () => {
    respond({ search: { search: { documents: [{ profile: { id: '1', name: 'Ada' } }] } } })
    const wrapper = render(true, '1', 'Choose a profile')
    await flushPromises()
    const select = wrapper.findComponent(SelectStub)
    expect(select.props()).toMatchObject({ modelValue: '1', placeholder: 'Choose a profile' })
    fetchSpy.mockRejectedValueOnce(new Error('Offline'))
    expect(await select.props('onSearch')('Ada')).toEqual([{ value: '1', label: 'Ada' }])
    respond(null)
    expect(await select.props('onSearch')('Ada')).toEqual([{ value: '1', label: 'Ada' }])
    for (const data of [{}, { search: {} }, { search: { search: {} } }]) {
      respond(data)
      expect(await select.props('onSearch')('Ada')).toEqual([])
    }
    expect(select.props('loading')).toBe(false)
  })

  it('does not issue an unauthenticated search without form context', async () => {
    const wrapper = render(false)
    await flushPromises()
    expect(await wrapper.findComponent(SelectStub).props('onSearch')('Ada')).toEqual([])
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
