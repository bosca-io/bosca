import { flushPromises, mount } from '@vue/test-utils'
import { print, type DocumentNode } from 'graphql'
import { defineComponent, h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import BibleReaderPage from './reader.vue'
import { extractAttributes, getElementType } from '@bosca/analytics-client-browser'

const defaultQuery = async (document: DocumentNode, _variables?: Record<string, unknown>) => {
  const operation = print(document)
  if (operation.includes('GetBibleReader')) {
    return {
      content: {
        metadata: {
          id: 'bible-1',
          name: 'Bible',
          bible: {
            metadataId: 'bible-1', version: 2, variant: 'p1', audio: [],
            name: 'Bible',
            nameLocal: 'Bible',
            abbreviation: 'B',
            abbreviationLocal: 'B',
            description: '',
            styles: [],
            languages: [{ name: 'English', nameLocal: 'English', iso: 'eng', scriptDirection: 'LTR' }],
            books: [{
              abbreviation: 'Gen',
              nameShort: 'Genesis',
              nameLong: 'Genesis',
              reference: { usfm: 'GEN', human: 'Genesis', humanShort: 'Gen' },
              chapters: [{ reference: { usfm: 'GEN.1', human: 'Genesis 1', humanShort: 'Gen 1' } }],
            }],
          },
        },
      },
    }
  }
  if (operation.includes('GetBibleChapter')) {
    return {
      content: {
        metadata: {
          bible: {
            chapter: {
              reference: { usfm: 'GEN.1', human: 'Genesis 1', humanShort: 'Gen 1' },
              component: { _: 'cc', type: 'DIV', components: [] },
              verses: [],
              attribution: { copyright: 'Provider copyright', citation: 'Provider citation', informationUrl: 'https://example.com/bible' },
            },
          },
        },
      },
    }
  }
  return { content: { metadata: { bible: { find: [] } } } }
}
const gqlQuery = vi.fn(defaultQuery)

vi.stubGlobal('useRoute', () => ({ params: { id: 'bible-1' }, query: { variant: 'p1' } }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#ff7ac6' }))
vi.stubGlobal('useGraphQL', () => ({ query: gqlQuery }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const stubs = {
  PageShell: defineComponent({
    setup(_props, { slots }) {
      return () => h('div', [slots.header?.(), slots.default?.()])
    },
  }),
  PageHeader: true,
  Select: true,
  SearchInput: defineComponent({
    props: { modelValue: { type: String, default: '' } },
    emits: ['update:modelValue'],
    setup(props, { emit }) {
      return () => h('input', {
        class: 'reference-search',
        value: props.modelValue,
        onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value),
      })
    },
  }),
  Button: true,
  Icon: true,
  ScriptureRenderer: true,
}

describe('Bible reader page', () => {
  beforeEach(() => { vi.clearAllMocks(); gqlQuery.mockReset().mockImplementation(defaultQuery) })

  it('reports the displayed chapter with its exact Bible identity', async () => {
    const wrapper = mount(BibleReaderPage, { global: { stubs, mocks: { buildBreadcrumb: (...parts: string[]) => parts } } })
    await flushPromises()
    const heading = wrapper.get('[data-ba-track="bible-text"]').element
    expect(getElementType(heading)).toBe('bible-text')
    expect(extractAttributes(heading).extras).toMatchObject({
      bible_id: 'bible-1', bible_version: '2', bible_variant: 'p1', bible_usfm: 'GEN.1',
    })
    expect(heading.hasAttribute('data-ba-no-text')).toBe(true)
    expect(wrapper.text()).toContain('Provider copyright')
    expect(wrapper.text()).toContain('Provider citation')
    wrapper.unmount()
  })

  it('does not report a chapter that failed to load', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    gqlQuery.mockImplementation(async (document, variables) => {
      if (print(document).includes('GetBibleChapter')) throw new Error('Unavailable')
      return defaultQuery(document, variables)
    })
    const wrapper = mount(BibleReaderPage, { global: { stubs, mocks: { buildBreadcrumb: (...parts: string[]) => parts } } })
    await flushPromises()
    expect(wrapper.find('[data-ba-track="bible-text"]').exists()).toBe(false)
    expect(wrapper.find('.scripture-body').exists()).toBe(false)
    wrapper.unmount()
    error.mockRestore()
  })

  it('ignores a chapter response after the user selects another chapter', async () => {
    let finish!: (value: Awaited<ReturnType<typeof defaultQuery>>) => void
    gqlQuery.mockImplementation(async (document, variables) => {
      if (print(document).includes('GetBibleChapter')) {
        if (variables?.usfm === 'GEN.1') return new Promise(resolve => { finish = resolve })
        const result = await defaultQuery(document, variables)
        const chapter = result.content.metadata.bible.chapter
        if (chapter) chapter.reference.usfm = 'GEN.2'
        return result
      }
      return defaultQuery(document, variables)
    })
    const wrapper = mount(BibleReaderPage, { global: { stubs, mocks: { buildBreadcrumb: (...parts: string[]) => parts } } })
    await flushPromises()
    expect(wrapper.find('[data-ba-track="bible-text"]').exists()).toBe(false)
    wrapper.findAllComponents({ name: 'Select' })[1]!.vm.$emit('update:modelValue', 'GEN.2')
    await flushPromises()
    expect(wrapper.get('[data-ba-track="bible-text"]').attributes('data-ba-bible-usfm')).toBe('GEN.2')
    finish(await defaultQuery(gqlQuery.mock.calls.find(([document]) => print(document).includes('GetBibleChapter'))![0]))
    await flushPromises()
    expect(wrapper.get('[data-ba-track="bible-text"]').attributes('data-ba-bible-usfm')).toBe('GEN.2')
    wrapper.unmount()
  })

  it('uses the selected Bible variant for chapter loading and reference search', async () => {
    gqlQuery.mockClear()
    const wrapper = mount(BibleReaderPage, {
      global: {
        stubs,
        mocks: { buildBreadcrumb: (...parts: string[]) => parts },
      },
    })

    await flushPromises()
    await nextTick()
    await flushPromises()

    const chapterCall = gqlQuery.mock.calls.find(([document]) => print(document).includes('GetBibleChapter'))
    expect(chapterCall).toBeDefined()
    if (!chapterCall) throw new Error('Expected the chapter query to run')
    expect(chapterCall[1]).toEqual({ id: 'bible-1', version: 2, usfm: 'GEN.1', variant: 'p1' })
    expect(print(chapterCall[0])).toContain('bible(variant: $variant)')

    await wrapper.get('.reference-search').setValue('Genesis 1:1')
    await wrapper.get('.ref-search').trigger('submit')
    await flushPromises()

    const findCall = gqlQuery.mock.calls.find(([document]) => print(document).includes('FindBibleRef'))
    expect(findCall).toBeDefined()
    if (!findCall) throw new Error('Expected the reference search query to run')
    expect(findCall[1]).toEqual({ id: 'bible-1', version: 2, human: 'Genesis 1:1', variant: 'p1' })
    expect(print(findCall[0])).toContain('bible(variant: $variant)')

    wrapper.unmount()
  })
})
