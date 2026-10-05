import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import CommonMarkdown from './CommonMarkdown.vue'

const MdcStub = {
  name: 'MDC',
  props: ['value', 'parserOptions', 'cacheKey', 'tag'],
  template: '<article class="mdc-stub">{{ value }}</article>',
}

function mountMarkdown(props: { value: string; cacheKey?: string }) {
  return mount(CommonMarkdown, { props, global: { stubs: { MDC: MdcStub } } })
}

describe('CommonMarkdown', () => {
  it('renders the source through MDC inside a styled prose boundary', () => {
    const wrapper = mountMarkdown({ value: '## Why\n\n- one\n- two' })
    expect(wrapper.get('div.markdown').get('.mdc-stub').text()).toBe('## Why\n\n- one\n- two')
    // MDC must not add its own wrapper element, so the prose rules apply to its children.
    expect(wrapper.getComponent(MdcStub).props('tag')).toBe(false)
  })

  it('disables raw HTML and MDC component syntax for untrusted sources', () => {
    const wrapper = mountMarkdown({ value: '<script>alert(1)</script> ::danger' })
    expect(wrapper.getComponent(MdcStub).props('parserOptions')).toMatchObject({
      remark: { plugins: { 'remark-mdc': false } },
      rehype: { options: { allowDangerousHtml: false }, plugins: { 'rehype-raw': false } },
    })
  })

  it('passes an explicit cache key through to MDC', () => {
    const wrapper = mountMarkdown({ value: '# Title', cacheKey: 'pull-request-description-1-2026-08-18' })
    expect(wrapper.getComponent(MdcStub).props('cacheKey')).toBe('pull-request-description-1-2026-08-18')
  })

  it('derives a stable cache key from the source when none is given', () => {
    const first = mountMarkdown({ value: '# Title' }).getComponent(MdcStub).props('cacheKey')
    const same = mountMarkdown({ value: '# Title' }).getComponent(MdcStub).props('cacheKey')
    const different = mountMarkdown({ value: '# Other' }).getComponent(MdcStub).props('cacheKey')
    expect(first).toBe(same)
    expect(first).not.toBe(different)
  })
})
