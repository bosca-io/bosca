import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MetadataParentCollectionsList from './MetadataParentCollectionsList.vue'

const stubs = {
  NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  Icon: { props: ['name', 'size', 'color'], template: '<span />' },
  Badge: { props: ['color'], template: '<span><slot /></span>' },
}

describe('MetadataParentCollectionsList', () => {
  it('links each parent collection to its collection page', async () => {
    const wrapper = mount(MetadataParentCollectionsList, {
      props: {
        accent: '#ff7ac6',
        collections: [{ id: 'collection-1', name: 'Featured', workflow: { state: 'published' } }],
      },
      global: { stubs },
    })

    const link = wrapper.get('.parent-collection-link')
    expect(link.attributes('href')).toBe('/cms/collections/collection-1')
    expect(link.attributes('aria-label')).toBe('Open collection Featured')
    expect(link.text()).toContain('Featured')
    expect(link.text()).toContain('published')

    await wrapper.get('.parent-collection-remove').trigger('click')
    expect(wrapper.emitted('remove')).toEqual([['collection-1']])
  })
})
