import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import RecommendationItemLink from './RecommendationItemLink.vue'

const global = { stubs: { NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }

describe('RecommendationItemLink', () => {
  it.each([
    ['metadata', '/cms/metadata/item', 'Metadata'],
    ['collection', '/cms/collections/item', 'Collection'],
  ])('links a %s recommendation to its item page and displays its editorial type', (field, link, kind) => {
    const wrapper = mount(RecommendationItemLink, {
      props: { [field]: { id: 'item', name: 'A recommended study', attributes: { type: ' Study ' } } }, global,
    })
    expect(wrapper.get('a').attributes('href')).toBe(link)
    expect(wrapper.text()).toContain('A recommended study')
    expect(wrapper.text()).toContain(`${kind} · Study`)
  })

  it.each([null, [], 'study', { type: '' }, { type: 12 }])('handles missing or malformed editorial types: %s', attributes => {
    const wrapper = mount(RecommendationItemLink, { props: { metadata: { id: 'item', name: 'Name', attributes } }, global })
    expect(wrapper.text()).toContain('No editorial type')
  })

  it('does not invent a link when the recommendation has no item', () => {
    const wrapper = mount(RecommendationItemLink, { global })
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.text()).toContain('Unavailable item')
  })
})
