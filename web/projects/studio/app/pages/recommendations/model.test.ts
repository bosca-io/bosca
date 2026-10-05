import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import ModelPage from './model.vue'

const mutation = vi.fn()
const refresh = vi.fn()
const query = vi.fn()
let wrapper: ReturnType<typeof mount>
beforeEach(() => {
  mutation.mockReset().mockResolvedValue({})
  refresh.mockReset().mockResolvedValue(undefined)
  query.mockReset().mockReturnValue({
    data: ref({ recommendation: { contexts: { all: [
      { id: 'reading', type: 'reading', name: 'Reading', revision: 3 },
      { id: 'default', type: 'default', name: 'Default', revision: 12 },
    ] } } }), status: ref('success'), error: ref(null),
  })
  vi.stubGlobal('useGraphQL', () => ({ mutation, useAsyncQuery: query }))
  vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#6366f1' }))
  vi.stubGlobal('buildBreadcrumb', () => [])
  wrapper = mount(ModelPage, { global: { mocks: { buildBreadcrumb: () => [] }, stubs: {
    PageShell: { template: '<main><slot name="header" /><slot /></main>' },
    PageHeader: { props: ['title'], template: '<header>{{ title }}<slot name="actions" /></header>' },
    SectionCard: { props: ['title'], template: '<section>{{ title }}<slot /></section>' },
    NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
    Button: { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
    Select: { props: ['modelValue', 'options'], emits: ['update:modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option></select>' },
    RecommendationModelHistory: { props: ['contextId'], setup() { return { refresh } }, template: '<div data-test="history">Trained versions: {{ contextId }}</div>' },
  } } })
})
afterEach(() => wrapper?.unmount())

describe('Recommendation models page', () => {
  it('shows context model history and switches context instead of editing global strategy pins', async () => {
    expect(wrapper.get('[data-test="history"]').text()).toContain('default')
    expect(wrapper.get('a').attributes('href')).toBe('/recommendations/contexts/default')
    await wrapper.get('select').setValue('reading')
    expect(wrapper.get('[data-test="history"]').text()).toContain('reading')
    expect(wrapper.get('a').attributes('href')).toBe('/recommendations/contexts/reading')
    expect(wrapper.text()).not.toContain('Personalized model activation')
  })

  it('retains training and semantic maintenance actions', async () => {
    await wrapper.findAll('button').find(button => button.text() === 'Train all contexts')!.trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledTimes(1)
    expect(refresh).toHaveBeenCalledTimes(1)
    await wrapper.findAll('button').find(button => button.text() === 'Add missing embeddings')!.trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenLastCalledWith(expect.anything(), { overwriteExisting: false })
  })
})
