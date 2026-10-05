import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { buildSchema, getDirectiveValues, GraphQLSkipDirective, Kind, validate } from 'graphql'
import { readFileSync } from 'node:fs'
import GlassTable from '../../../../../packages/ui/src/components/GlassTable.vue'
import RecommendationItemLink from '../../components/recommendations/RecommendationItemLink.vue'
import TestConsolePage from './test.vue'

const gqlQuery = vi.fn()
const schema = buildSchema(readFileSync('schema.graphqls', 'utf8'))

vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ profile: ref({ id: 'primary-profile', name: 'Primary profile' }) }),
}))

vi.stubGlobal('definePageMeta', vi.fn())
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#ff7ac6' }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)
vi.stubGlobal('useGraphQL', () => ({
  query: gqlQuery,
  mutation: vi.fn(),
  useAsyncQuery: () => ({ data: ref(null) }),
}))

const globalConfig = {
  components: { GlassTable, RecommendationItemLink },
  mocks: {
    buildBreadcrumb: (...parts: string[]) => parts,
  },
  stubs: {
    PageShell: { template: '<main><slot name="header" /><slot /></main>' },
    PageHeader: { template: '<header />' },
    SectionCard: { template: '<section><slot /></section>' },
    Select: {
      props: ['modelValue', 'label', 'options'], emits: ['update:modelValue'],
      template: '<select :aria-label="label" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option></select>',
    },
    TextInput: {
      props: ['modelValue', 'label'],
      emits: ['update:modelValue'],
      template: '<input class="text-input" :aria-label="label" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
    },
    NumberInput: { template: '<input type="number" />' },
    Button: { template: '<button><slot /></button>' },
    NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
    Badge: { template: '<span><slot /></span>' },
  },
}

describe('Recommendation Test Console', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    gqlQuery.mockReset()
    gqlQuery.mockResolvedValue({
      search: {
        search: {
          documents: [{
            metadata: {
              id: 'metadata-1',
              name: 'Source article',
              content: { type: 'application/pdf' },
              attributes: { type: ' Study ' },
            },
            profile: {
              id: 'profile-1',
              name: 'Ada Lovelace',
              attributes: [{
                typeId: 'bosca.profiles.email',
                attributes: { email: 'ada@example.com' },
              }],
            },
          }],
        },
      },
      recommendation: { recommended: [
        { id: 'rec-1', score: 0.8, fallback: false, sources: [], reason: null,
          metadata: { id: 'study', name: 'A study', attributes: { type: 'Study' } }, collection: null },
        { id: 'rec-2', score: 0.6, fallback: false, sources: [], reason: null,
          metadata: null, collection: { id: 'series', name: 'A series', attributes: { type: 'Series' } } },
      ] },
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows the profile email beside the name and keeps it in the selected label', async () => {
    const wrapper = mount(TestConsolePage, { global: globalConfig })

    await wrapper.find<HTMLInputElement>('.text-input').setValue('Ada')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(wrapper.find('.combo-option-name').text()).toBe('Ada Lovelace')
    expect(wrapper.find('.combo-option-email').text()).toBe('ada@example.com')

    await wrapper.find('.combo-option').trigger('mousedown')

    expect(wrapper.find<HTMLInputElement>('.text-input').element.value)
      .toBe('Ada Lovelace · ada@example.com')
  })

  it('shows content and editorial types in content search results', async () => {
    const wrapper = mount(TestConsolePage, { global: globalConfig })
    await wrapper.get('select[aria-label="Mode"]').setValue('recommended')
    await wrapper.get('input[aria-label="Content item"]').setValue('Source')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    const option = wrapper.get('.combo-option')
    expect(option.text()).toContain('Source article')
    expect(option.text()).toContain('contentType: application/pdf')
    expect(option.text()).toContain('attributes.type: Study')
    expect(validate(schema, gqlQuery.mock.calls.at(-1)![0])).toEqual([])
  })

  it('defaults item recommendations to a real anonymous query and skips admin-only fields', async () => {
    const wrapper = mount(TestConsolePage, { global: globalConfig })
    // A profile picked for a feed must not leak into the anonymous item preview.
    await wrapper.get('input[aria-label="Profile"]').setValue('Ada')
    await vi.advanceTimersByTimeAsync(250)
    await wrapper.get('.combo-option').trigger('mousedown')
    await wrapper.get('select[aria-label="Mode"]').setValue('recommended')
    expect(wrapper.get<HTMLSelectElement>('select[aria-label="Viewer"]').element.value).toBe('anonymous')
    expect(wrapper.find('input[aria-label="Profile"]').exists()).toBe(false)
    await wrapper.get('input[aria-label="Content item"]').setValue('Source')
    await vi.advanceTimersByTimeAsync(250)
    await wrapper.get('.combo-option').trigger('mousedown')
    await wrapper.findAll('button').find(button => button.text() === 'Run')!.trigger('click')
    await flushPromises()
    const [document, variables, options] = gqlQuery.mock.calls.at(-1)!
    expect(variables).toEqual({ metadataId: 'metadata-1', profileId: null, limit: 25, anonymous: true })
    expect(options).toEqual({ anonymous: true })
    expect(validate(schema, document)).toEqual([])
    const operation = document.definitions.find((node: { kind: string }) => node.kind === Kind.OPERATION_DEFINITION)
    const fields = operation.selectionSet.selections[0].selectionSet.selections[0].selectionSet.selections
    const strategy = fields.find((field: { name: { value: string } }) => field.name.value === 'strategy')
    expect(getDirectiveValues(GraphQLSkipDirective, strategy, variables)).toEqual({ if: true })
    expect(wrapper.get('a[href="/cms/metadata/study"]').text()).toContain('Study')
    expect(wrapper.get('a[href="/cms/collections/series"]').text()).toContain('Series')
    expect(wrapper.text()).toContain('Anonymous visitor · public visibility')

    await wrapper.get('select[aria-label="Viewer"]').setValue('current')
    await wrapper.findAll('button').find(button => button.text() === 'Run')!.trigger('click')
    await flushPromises()
    expect(gqlQuery.mock.calls.at(-1)!.slice(1)).toEqual([
      { metadataId: 'metadata-1', profileId: 'primary-profile', limit: 25, anonymous: false }, { anonymous: false },
    ])
    expect(wrapper.text()).toContain('My Studio profile · Studio visibility')
    await wrapper.get('select[aria-label="Viewer"]').setValue('profile')
    await wrapper.findAll('button').find(button => button.text() === 'Run')!.trigger('click')
    await flushPromises()
    expect(gqlQuery.mock.calls.at(-1)!.slice(1)).toEqual([
      { metadataId: 'metadata-1', profileId: 'profile-1', limit: 25, anonymous: false }, { anonymous: false },
    ])
  })

  it('requires a selected profile when that viewer mode is chosen', async () => {
    const wrapper = mount(TestConsolePage, { global: globalConfig })
    await wrapper.get('select[aria-label="Mode"]').setValue('recommended')
    await wrapper.get('select[aria-label="Viewer"]').setValue('profile')
    await wrapper.findAll('button').find(button => button.text() === 'Run')!.trigger('click')
    expect(wrapper.text()).toContain('Search for and select a profile.')
    expect(gqlQuery).not.toHaveBeenCalled()
  })
})
