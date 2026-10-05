import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { buildSchema, validate } from 'graphql'
import { readFileSync } from 'node:fs'
import ItemRecommendationsCard from './ItemRecommendationsCard.vue'
import RecommendationItemLink from './recommendations/RecommendationItemLink.vue'

const schema = buildSchema(readFileSync('schema.graphqls', 'utf8'))
const useAsyncQuery = vi.fn()
const refresh = vi.fn()
const error = ref<Error | null>(null)

beforeEach(() => {
  error.value = null
  refresh.mockReset().mockResolvedValue(undefined)
  useAsyncQuery.mockReset().mockReturnValue({
    data: ref({ recommendation: {
      recommended: [
        { id: 'rec-1', score: 0.8, reason: 'Related content', metadata: { id: 'study', name: 'A study', attributes: { type: 'Study' } }, collection: null },
        { id: 'rec-2', score: 0.6, reason: null, metadata: null, collection: { id: 'series', name: 'A series', attributes: { type: 'Series' } } },
      ], coEngaged: [],
    } }), status: ref('success'), error, refresh,
  })
  vi.stubGlobal('useGraphQL', () => ({ useAsyncQuery }))
  vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#6366f1' }))
})

function render() {
  return mount(ItemRecommendationsCard, { props: { metadataId: 'source' }, global: {
    components: { RecommendationItemLink },
    stubs: { NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' }, Icon: true, Button: { template: '<button><slot /></button>' } },
  } })
}

describe('ItemRecommendationsCard', () => {
  it('queries anonymously and shows linked types for metadata and collections without dropping either kind', () => {
    const wrapper = render()
    expect(useAsyncQuery).toHaveBeenCalledWith('item-recommendations-card', expect.anything(), expect.anything(), { anonymous: true })
    expect(validate(schema, useAsyncQuery.mock.calls[0]![1])).toEqual([])
    expect(wrapper.get('a[href="/cms/metadata/study"]').text()).toContain('Study')
    expect(wrapper.get('a[href="/cms/collections/series"]').text()).toContain('Series')
    expect(wrapper.findAll('.ir-row')).toHaveLength(2)
    expect(wrapper.text()).toContain('Anonymous visitor preview')
  })

  it('switches to the co-engagement results', async () => {
    const wrapper = render()
    await wrapper.get('button[role="tab"]:last-child').trigger('click')
    expect(wrapper.findAll('.ir-row')).toHaveLength(0)
    expect(wrapper.text()).toContain('No publicly visible recommendations')
  })

  it('reports query failure with a retry instead of presenting an empty recommendation set', async () => {
    error.value = new Error('Serving unavailable')
    const wrapper = render()
    expect(wrapper.text()).toContain("Couldn't load anonymous recommendations: Serving unavailable")
    expect(wrapper.findAll('.ir-row')).toHaveLength(0)
    await wrapper.get('.ir-error button').trigger('click')
    await flushPromises()
    expect(refresh).toHaveBeenCalledOnce()
  })
})
