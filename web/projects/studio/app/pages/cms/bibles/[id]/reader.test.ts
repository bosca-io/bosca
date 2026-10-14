import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { print, type DocumentNode } from 'graphql'
import { defineComponent, h, nextTick, type PropType } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import BibleReaderPage from './reader.vue'

enableAutoUnmount(afterEach)

function chapterReference(chapter: string) {
  return { usfm: `GEN.${chapter}`, human: `Genesis ${chapter}`, humanShort: `Gen ${chapter}` }
}

let chapterReferences = [chapterReference('1')]

const gqlQuery = vi.fn(async (document: DocumentNode, variables?: Record<string, unknown>) => {
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
              chapters: chapterReferences.map(reference => ({ reference })),
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
              reference: chapterReferences.find(reference => reference.usfm === variables?.usfm),
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
  Select: defineComponent({
    props: {
      modelValue: { type: String, default: '' },
      options: { type: Array as PropType<Array<{ value: string; label: string }>>, default: () => [] },
      placeholder: { type: String, default: '' },
    },
    emits: ['update:modelValue'],
    setup(props, { emit }) {
      return () => h('select', {
        'aria-label': props.placeholder,
        value: props.modelValue,
        onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value),
      }, props.options.map(option => h('option', { value: option.value }, option.label)))
    },
  }),
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
  Button: defineComponent({
    setup(_props, { slots }) {
      return () => h('button', slots.default?.())
    },
  }),
  Icon: true,
  ScriptureRenderer: true,
}

async function mountReader() {
  const wrapper = mount(BibleReaderPage, {
    global: {
      stubs,
      mocks: { buildBreadcrumb: (...parts: string[]) => parts },
    },
  })

  await flushPromises()
  await nextTick()
  await flushPromises()

  return wrapper
}

describe('Bible reader page', () => {
  beforeEach(() => {
    gqlQuery.mockClear()
    chapterReferences = [chapterReference('1')]
  })

  it('uses the selected Bible variant for chapter loading and reference search', async () => {
    const wrapper = await mountReader()
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
  })

  it.each(['intro', '0'])('keeps the %s reference separate from chapter one', async (intro) => {
    chapterReferences = [intro, '1', '2'].map(chapterReference)
    const wrapper = await mountReader()
    const chapterSelect = wrapper.get('select[aria-label="Chapter"]')

    expect(chapterSelect.findAll('option').map(option => ({
      value: option.element.value,
      label: option.text(),
    }))).toEqual([
      { value: `GEN.${intro}`, label: `Genesis ${intro}` },
      { value: 'GEN.1', label: 'Genesis 1' },
      { value: 'GEN.2', label: 'Genesis 2' },
    ])
    expect(wrapper.get('.chapter-heading').text()).toBe(`Genesis ${intro}`)

    await chapterSelect.setValue('GEN.1')
    await flushPromises()

    expect(gqlQuery).toHaveBeenLastCalledWith(expect.anything(), {
      id: 'bible-1', usfm: 'GEN.1', variant: 'p1',
    })
    expect(wrapper.get('.chapter-heading').text()).toBe('Genesis 1')
  })

  it('uses reference labels for books without an intro or with nonconsecutive chapters', async () => {
    chapterReferences = ['1', '3', '10'].map(chapterReference)
    const wrapper = await mountReader()

    expect(wrapper.findAll('select[aria-label="Chapter"] option').map(option => option.text()))
      .toEqual(['Genesis 1', 'Genesis 3', 'Genesis 10'])
  })

  it('navigates from the intro to chapter one and back using their references', async () => {
    chapterReferences = ['intro', '1', '2'].map(chapterReference)
    const wrapper = await mountReader()
    const [previous, next] = wrapper.findAll('.bottom-nav button')
    if (!previous || !next) throw new Error('Expected chapter navigation buttons')

    expect(previous.attributes('disabled')).toBeDefined()
    await next.trigger('click')
    await flushPromises()

    expect(wrapper.get<HTMLSelectElement>('select[aria-label="Chapter"]').element.value).toBe('GEN.1')
    expect(wrapper.get('.chapter-heading').text()).toBe('Genesis 1')

    await previous.trigger('click')
    await flushPromises()

    expect(wrapper.get<HTMLSelectElement>('select[aria-label="Chapter"]').element.value).toBe('GEN.intro')
    expect(wrapper.get('.chapter-heading').text()).toBe('Genesis intro')
  })
})
