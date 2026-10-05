import { mount } from '@vue/test-utils'
import { print, type DocumentNode } from 'graphql'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import BiblesPage from './index.vue'

const push = vi.fn()
const refresh = vi.fn()
const mutation = vi.fn()

let collectionDocument: DocumentNode
let collectionVariables: Record<string, unknown>

vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ auth: { getAuthHeaders: vi.fn() } }),
}))

const metadata = {
  __typename: 'Metadata',
  id: 'metadata-1',
  version: 3,
  name: 'Imported Bible',
  attributes: { published: '2026-08-01T00:00:00Z' },
  modified: '2026-08-02T00:00:00Z',
  content: { type: 'bosca/v-bible' },
  categories: [],
  ready: true,
  bibles: [
    {
      variant: 'reader',
      enabled: true,
      defaultVariant: true,
      name: 'Reader Edition',
      nameLocal: 'Reader Edition',
      abbreviation: 'RE',
      abbreviationLocal: 'RE',
      languages: [{ name: 'English', nameLocal: 'English', iso: 'eng' }],
    },
    {
      variant: 'study',
      enabled: true,
      defaultVariant: false,
      name: 'Study Edition',
      nameLocal: 'Study Edition',
      abbreviation: 'SE',
      abbreviationLocal: 'SE',
      languages: [{ name: 'English', nameLocal: 'English', iso: 'eng' }],
    },
  ],
  public: true,
  publicContent: true,
  publicSupplementary: false,
  locked: false,
  searchable: true,
  workflow: { state: 'published', pending: null },
}

vi.stubGlobal('useGraphQL', () => ({
  mutation,
  useAsyncQuery: (
    _key: string,
    document: DocumentNode,
    variables: Record<string, unknown>,
  ) => {
    collectionDocument = document
    collectionVariables = variables
    return {
      data: ref({
        content: {
          slug: {
            __typename: 'Collection',
            itemsCount: 1,
            items: [metadata],
          },
        },
      }),
      status: ref('success'),
      refresh,
    }
  },
  useSubscription: vi.fn(),
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#34d99a' }))
vi.stubGlobal('useRouter', () => ({ push }))
vi.stubGlobal('useToast', () => ({
  error: vi.fn(),
  info: vi.fn(),
  success: vi.fn(),
  showProgress: vi.fn(),
}))
vi.stubGlobal('useLanguage', () => ({
  current: ref({ tag: 'en' }),
  languages: [{ tag: 'en', name: 'English' }],
  setLanguageTag: vi.fn(),
}))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const stubs = {
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: {
    props: ['subtitle'],
    template: '<header :data-subtitle="subtitle"><slot name="actions" /></header>',
  },
  Select: { template: '<select />' },
  Button: { template: '<button><slot /></button>' },
  Icon: { template: '<span />' },
  SectionCard: { template: '<section><slot name="right" /><slot /></section>' },
  GlassTable: {
    props: ['rows'],
    emits: ['row-click'],
    template: `
      <div>
        <div v-for="row in rows" :key="row.rowKey" class="test-row">
          <button class="open-row" @click="$emit('row-click', row)">Open</button>
          <slot name="col-name" :row="row" />
          <slot name="col-language" :row="row" />
          <slot name="col-variant" :row="row" />
        </div>
      </div>
    `,
  },
  Badge: { template: '<span><slot /></span>' },
  OverflowMenu: { template: '<div />' },
  Pagination: { template: '<div />' },
  AddBibleModal: { template: '<div />' },
  BibleVariantsModal: { template: '<div />' },
  ConfirmModal: { template: '<div />' },
}

function mountPage() {
  return mount(BiblesPage, {
    global: {
      stubs,
      mocks: { buildBreadcrumb: (...parts: string[]) => parts },
    },
  })
}

describe('Bibles Page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('queries paginated items from the Bibles collection and expands bibles', () => {
    mountPage()

    const query = print(collectionDocument)
    expect(query).toContain('slug(slug: "bibles")')
    expect(query).toMatch(/items\(/)
    expect(query).toMatch(/itemsCount\(/)
    expect(query).toContain('languageResolutionContext: "bibles"')
    expect(query).toMatch(/bibles \{/)
    expect(query).not.toMatch(/search\s*\{/)
    expect((collectionVariables.languageTag as { value: string }).value).toBe('en')
  })

  it('renders one row for each expanded Bible variant', () => {
    const wrapper = mountPage()

    expect(wrapper.findAll('.test-row')).toHaveLength(2)
    expect(wrapper.text()).toContain('Reader Edition')
    expect(wrapper.text()).toContain('Study Edition')
    expect(wrapper.text()).toContain('reader')
    expect(wrapper.text()).toContain('study')
    expect(wrapper.text()).toContain('Default')
  })

  it('opens the selected variant in the Bible reader', async () => {
    const wrapper = mountPage()

    await wrapper.findAll('.open-row')[1]!.trigger('click')

    expect(push).toHaveBeenCalledWith({
      path: '/cms/bibles/metadata-1/reader',
      query: { variant: 'study' },
    })
  })
})
