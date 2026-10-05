import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import CollectionItemSearch from './CollectionItemSearch.vue'

const mockQuery = vi.fn()

vi.stubGlobal('useGraphQL', () => ({
  query: mockQuery,
}))

const stubs = {
  Icon: { template: '<i />' },
}

beforeEach(() => vi.clearAllMocks())

describe('CollectionItemSearch', () => {
  it('renders search input', () => {
    const wrapper = mount(CollectionItemSearch, { global: { stubs } })
    expect(wrapper.find('.search-input').exists()).toBe(true)
  })

  it('uses custom placeholder', () => {
    const wrapper = mount(CollectionItemSearch, {
      props: { placeholder: 'Find items…' },
      global: { stubs },
    })
    expect(wrapper.find('.search-input').attributes('placeholder')).toBe('Find items…')
  })

  it('starts with empty results', () => {
    const wrapper = mount(CollectionItemSearch, { global: { stubs } })
    expect(wrapper.findAll('.search-result')).toHaveLength(0)
  })

  it('uses default placeholder when none provided', () => {
    const wrapper = mount(CollectionItemSearch, { global: { stubs } })
    expect(wrapper.find('.search-input').attributes('placeholder')).toBe('Search items…')
  })
})
