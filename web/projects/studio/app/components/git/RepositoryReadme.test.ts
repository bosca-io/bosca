import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RepositoryReadme from './RepositoryReadme.vue'
import CommonMarkdown from '../common/CommonMarkdown.vue'

const query = vi.fn()

const MdcStub = {
  name: 'MDC',
  props: ['value', 'parserOptions'],
  template: '<article class="mdc-stub">{{ value }}</article>',
}

function mountReadme(entries = [{ name: 'README.md', path: 'README.md', type: 'BLOB' }]) {
  return mount(RepositoryReadme, {
    props: { repositoryId: 'repo-1', gitRef: 'main', entries },
    global: {
      // The README delegates rendering to the shared CommonMarkdown component;
      // register the real one so the safe parser options are asserted end-to-end.
      components: { CommonMarkdown },
      stubs: {
        Icon: true,
        MDC: MdcStub,
      },
    },
  })
}

describe('RepositoryReadme', () => {
  beforeEach(() => {
    query.mockReset()
    vi.stubGlobal('useGraphQL', () => ({ query }))
  })

  it('loads and renders the root README for the selected ref with unsafe parser features disabled', async () => {
    query.mockResolvedValue({ git: { blob: { content: '# Hello', isBinary: false } } })

    const wrapper = mountReadme()
    await flushPromises()

    expect(query).toHaveBeenCalledWith(expect.anything(), {
      repositoryId: 'repo-1',
      ref: 'main',
      path: 'README.md',
    })
    expect(wrapper.get('article.readme-content').get('.mdc-stub').text()).toBe('# Hello')
    expect(wrapper.getComponent(MdcStub).props('parserOptions')).toMatchObject({
      remark: { plugins: { 'remark-mdc': false } },
      rehype: { options: { allowDangerousHtml: false }, plugins: { 'rehype-raw': false } },
    })
  })

  it('reloads when the selected ref changes', async () => {
    query
      .mockResolvedValueOnce({ git: { blob: { content: '# Main', isBinary: false } } })
      .mockResolvedValueOnce({ git: { blob: { content: '# Release', isBinary: false } } })
    const wrapper = mountReadme()
    await flushPromises()

    await wrapper.setProps({ gitRef: 'release' })
    await flushPromises()

    expect(query).toHaveBeenLastCalledWith(expect.anything(), {
      repositoryId: 'repo-1',
      ref: 'release',
      path: 'README.md',
    })
    expect(wrapper.get('.mdc-stub').text()).toBe('# Release')
  })

  it('does not query or render when no supported root README exists', async () => {
    const wrapper = mountReadme([{ name: 'README.rst', path: 'README.rst', type: 'BLOB' }])
    await flushPromises()

    expect(query).not.toHaveBeenCalled()
    expect(wrapper.html()).toBe('<!--v-if-->')
  })

  it('does not render binary README content', async () => {
    query.mockResolvedValue({ git: { blob: { content: 'binary', isBinary: true } } })

    const wrapper = mountReadme()
    await flushPromises()

    expect(wrapper.find('.mdc-stub').exists()).toBe(false)
    expect(wrapper.find('.readme-card').exists()).toBe(false)
  })
})
