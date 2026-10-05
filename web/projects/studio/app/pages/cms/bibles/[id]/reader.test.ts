import { flushPromises, mount } from '@vue/test-utils'
import { print, type DocumentNode } from 'graphql'
import { defineComponent, h } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import BibleReaderPage from './reader.vue'

const gqlQuery = vi.fn(async (document: DocumentNode, _variables?: Record<string, unknown>) => {
  const operation = print(document)
  if (operation.includes('GetBibleReader')) {
    return {
      content: {
        metadata: {
          id: 'bible-1',
          name: 'Bible',
          bible: {
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
            },
          },
        },
      },
    }
  }
  return { content: { metadata: { bible: { find: [] } } } }
})

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
    expect(chapterCall[1]).toEqual({ id: 'bible-1', usfm: 'GEN.1', variant: 'p1' })
    expect(print(chapterCall[0])).toContain('bible(variant: $variant)')

    await wrapper.get('.reference-search').setValue('Genesis 1:1')
    await wrapper.get('.ref-search').trigger('submit')
    await flushPromises()

    const findCall = gqlQuery.mock.calls.find(([document]) => print(document).includes('FindBibleRef'))
    expect(findCall).toBeDefined()
    if (!findCall) throw new Error('Expected the reference search query to run')
    expect(findCall[1]).toEqual({ id: 'bible-1', human: 'Genesis 1:1', variant: 'p1' })
    expect(print(findCall[0])).toContain('bible(variant: $variant)')

    wrapper.unmount()
  })
})
