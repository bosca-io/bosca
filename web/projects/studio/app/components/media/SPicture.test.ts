import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SPicture from './SPicture.vue'
import type { Metadata } from '~/types/graphql'

describe('SPicture', () => {
  it('renders placeholder when item is null', () => {
    const wrapper = mount(SPicture, { props: { item: null } })
    expect(wrapper.find('.s-picture-placeholder').exists()).toBe(true)
    expect(wrapper.find('picture').exists()).toBe(false)
  })

  it('renders picture element for metadata with image content type', () => {
    const wrapper = mount(SPicture, {
      props: {
        item: {
          __typename: 'Metadata',
          id: 'img-1',
          slug: 'test-image',
          content: { type: 'image/jpeg' },
          relationships: [],
        } as unknown as Metadata,
      },
    })
    expect(wrapper.find('picture').exists()).toBe(true)
    expect(wrapper.find('img').exists()).toBe(true)
  })

  it('uses slug in image src', () => {
    const wrapper = mount(SPicture, {
      props: {
        item: {
          __typename: 'Metadata',
          id: 'img-1',
          slug: 'my-slug',
          content: { type: 'image/jpeg' },
          relationships: [],
        } as unknown as Metadata,
      },
    })
    const img = wrapper.find('img')
    expect(img.attributes('src')).toContain('my-slug')
  })

  it('falls back to id when no slug', () => {
    const wrapper = mount(SPicture, {
      props: {
        item: {
          __typename: 'Metadata',
          id: 'img-1',
          slug: '',
          content: { type: 'image/jpeg' },
          relationships: [],
        } as unknown as Metadata,
      },
    })
    const img = wrapper.find('img')
    expect(img.attributes('src')).toContain('img-1')
  })

  it('renders webp and jpeg source elements', () => {
    const wrapper = mount(SPicture, {
      props: {
        item: {
          __typename: 'Metadata',
          id: 'img-1',
          slug: 'test',
          content: { type: 'image/png' },
          relationships: [],
        } as unknown as Metadata,
      },
    })
    const sources = wrapper.findAll('source')
    expect(sources.length).toBe(2)
    expect(sources[0]!.attributes('type')).toBe('image/webp')
    expect(sources[1]!.attributes('type')).toBe('image/jpeg')
  })

  it('uses lazy loading when lazy prop is true', () => {
    const wrapper = mount(SPicture, {
      props: {
        lazy: true,
        item: {
          __typename: 'Metadata',
          id: 'img-1',
          slug: 'test',
          content: { type: 'image/jpeg' },
          relationships: [],
        } as unknown as Metadata,
      },
    })
    expect(wrapper.find('img').attributes('loading')).toBe('lazy')
  })

  it('uses eager loading when lazy is false', () => {
    const wrapper = mount(SPicture, {
      props: {
        lazy: false,
        item: {
          __typename: 'Metadata',
          id: 'img-1',
          slug: 'test',
          content: { type: 'image/jpeg' },
          relationships: [],
        } as unknown as Metadata,
      },
    })
    expect(wrapper.find('img').attributes('loading')).toBe('eager')
  })

  it('applies aspect-ratio style when provided', () => {
    const wrapper = mount(SPicture, {
      props: {
        aspectRatio: '16/9',
        item: {
          __typename: 'Metadata',
          id: 'img-1',
          slug: 'test',
          content: { type: 'image/jpeg' },
          relationships: [],
        } as unknown as Metadata,
      },
    })
    const img = wrapper.find('img')
    expect(img.attributes('style')).toContain('aspect-ratio: 16 / 9')
  })

  it('finds image from metadata relationships', () => {
    const wrapper = mount(SPicture, {
      props: {
        item: {
          __typename: 'Metadata',
          id: 'doc-1',
          slug: 'doc',
          content: { type: 'application/pdf' },
          relationships: [
            { relationship: 'image.featured', metadata: { id: 'featured-img', slug: 'featured' }, attributes: {} },
          ],
        } as unknown as Metadata,
      },
    })
    expect(wrapper.find('picture').exists()).toBe(true)
    expect(wrapper.find('img').attributes('src')).toContain('featured')
  })

  it('renders placeholder when no image relationship found', () => {
    const wrapper = mount(SPicture, {
      props: {
        item: {
          __typename: 'Metadata',
          id: 'doc-1',
          slug: 'doc',
          content: { type: 'application/pdf' },
          relationships: [],
        } as unknown as Metadata,
      },
    })
    expect(wrapper.find('.s-picture-placeholder').exists()).toBe(true)
  })

  it('shows placeholder on image error', async () => {
    const wrapper = mount(SPicture, {
      props: {
        item: {
          __typename: 'Metadata',
          id: 'img-1',
          slug: 'broken',
          content: { type: 'image/jpeg' },
          relationships: [],
        } as unknown as Metadata,
      },
    })
    await wrapper.find('img').trigger('error')
    expect(wrapper.find('.s-picture-placeholder').exists()).toBe(true)
  })
})
