import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import KitMessageContent from './KitMessageContent.vue'

const imageUrl = '/api/v1/content/metadata/download?id=8055a7a3-36de-49ca-bc2d-bf7ed34f1092'
const imageId = '8055a7a3-36de-49ca-bc2d-bf7ed34f1092'
const NuxtLinkStub = {
  props: ['to'],
  template: '<a :href="to"><slot /></a>',
}
const IconStub = { template: '<span />' }

function mountContent(text: string) {
  return mount(KitMessageContent, {
    props: { text },
    global: {
      stubs: {
        NuxtLink: NuxtLinkStub,
        Icon: IconStub,
      },
    },
  })
}

describe('KitMessageContent', () => {
  it('renders a generated Bosca image while preserving the surrounding response', () => {
    const wrapper = mountContent(
      `Done — I generated a realistic sunset landscape.\n\n![Generated Image](${imageUrl})`,
    )

    expect(wrapper.text()).toContain('Done — I generated a realistic sunset landscape.')
    expect(wrapper.get('img').attributes()).toMatchObject({
      src: imageUrl,
      alt: 'Generated Image',
      loading: 'lazy',
    })
    expect(wrapper.get('.kit-message-image-link').attributes()).toMatchObject({
      href: imageUrl,
      target: '_blank',
      rel: 'noopener noreferrer',
    })
    expect(wrapper.get('.kit-message-image-metadata-link').attributes()).toMatchObject({
      href: `/cms/metadata/${imageId}`,
      'aria-label': 'View metadata for Generated Image',
    })
    expect(wrapper.get('.kit-message-image-metadata-link').text()).toBe('View metadata')
  })

  it('renders multiple known Bosca image URL forms and supplies fallback alt text', () => {
    const secondId = 'f1f53f71-76bf-49cb-8100-17f514572ac0'
    const wrapper = mountContent([
      `![](${imageUrl})`,
      `![Edited image](/content/image/${secondId}.png)`,
    ].join('\n'))

    expect(wrapper.findAll('img').map(image => ({
      alt: image.attributes('alt'),
      src: image.attributes('src'),
    }))).toEqual([
      { alt: 'Generated image', src: imageUrl },
      { alt: 'Edited image', src: `/content/image/${secondId}.png` },
    ])
    expect(wrapper.findAll('.kit-message-image-metadata-link').map(link => link.attributes('href'))).toEqual([
      `/cms/metadata/${imageId}`,
      `/cms/metadata/${secondId}`,
    ])
  })

  it.each([
    '![Remote image](https://example.com/tracker.png)',
    '![Protocol-relative image](//example.com/tracker.png)',
    '![Inline payload](data:image/svg+xml,<svg></svg>)',
    '![Wrong endpoint](/api/v1/content/metadata/download?id=not-a-uuid)',
  ])('keeps an untrusted image reference escaped as text: %s', (text) => {
    const wrapper = mountContent(text)

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('.kit-message-image-metadata-link').exists()).toBe(false)
    expect(wrapper.text()).toBe(text)
  })

  it('shows an explicit fallback when a Bosca image cannot load', async () => {
    const wrapper = mountContent(`![Generated Image](${imageUrl})`)

    await wrapper.get('img').trigger('error')

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('.kit-message-image-error').text()).toContain('Couldn’t load Generated Image.')
    expect(wrapper.get('.kit-message-image-error a').attributes('href')).toBe(imageUrl)
    expect(wrapper.get('.kit-message-image-metadata-link').attributes('href')).toBe(`/cms/metadata/${imageId}`)
  })
})
