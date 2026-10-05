import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref, defineComponent, h, Suspense, nextTick, onMounted } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import CollectionDetail from './[id].vue'

vi.mock('sortablejs', () => ({ default: { create: vi.fn(() => ({ destroy: vi.fn() })) } }))
vi.mock('json-editor-vue', () => ({
  default: { name: 'JsonEditorVue', template: '<div class="mock-json" />', props: ['modelValue'] },
}))

const mockMutation = vi.fn().mockResolvedValue({})
const mockRefresh = vi.fn().mockResolvedValue(undefined)
const mockRefreshItems = vi.fn().mockResolvedValue(undefined)
const mockRefreshState = vi.fn()
const mockPush = vi.fn()
let collectionItems: Array<Record<string, unknown>> = []

const workflow = { state: 'published', stateValid: null, pending: null, running: 0, activeJobs: [] }
const collection = {
  __typename: 'Collection',
  id: 'col-1',
  slug: 'anger',
  name: 'Anger',
  languageTag: 'en',
  languageVariant: null,
  attributes: { type: 'Emotions' },
  public: true,
  publicList: true,
  publicSupplementary: false,
  searchable: true,
  ready: true,
  locked: false,
  type: 'STANDARD',
  created: '2026-03-09T01:42:00Z',
  modified: '2026-05-29T19:32:00Z',
  itemsCount: 29,
  labels: [],
  categories: [],
  traits: [],
  ordering: [] as Array<Record<string, unknown>>,
  parentCollections: [],
  metadataRelationships: [],
  languageVariants: [],
  templateMetadata: { id: 'tmpl-1', version: 1, collectionTemplate: { attributes: [] } },
  permissions: [],
  workflow,
}
const templates = {
  all: [
    { metadata: { id: 'tmpl-1', version: 1, name: 'Emotions' } },
    { metadata: { id: 'tmpl-2', version: 3, name: 'Topics' } },
  ],
}
const states = { all: [{ id: 'draft', name: 'Draft', description: '' }, { id: 'published', name: 'Published', description: '' }] }

vi.stubGlobal('useGraphQL', () => ({
  query: vi.fn().mockResolvedValue({}),
  mutation: mockMutation,
  useAsyncQuery: (key: string) => {
    if (key.startsWith('collection-detail-')) {
      return {
        data: ref({ profiles: { current: { id: 'p-1', name: 'Alex' } }, content: { collections: { collection, templates }, states } }),
        status: ref('success'),
        refresh: mockRefresh,
      }
    }
    if (key.startsWith('collection-items-')) {
      return {
        data: ref({ content: { collections: { collection: { itemsCount: 29, items: collectionItems } } } }),
        status: ref('success'),
        refresh: mockRefreshItems,
      }
    }
    return { data: ref(null), status: ref('success'), refresh: vi.fn() }
  },
  useSubscription: vi.fn(),
}))
vi.stubGlobal('useRoute', () => ({ params: { id: 'col-1' }, query: {}, fullPath: '/cms/collections/col-1' }))
vi.stubGlobal('useRouter', () => ({ push: mockPush, replace: vi.fn() }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#ff7ac6' }))
vi.stubGlobal('buildBreadcrumb', (...args: string[]) => args)
vi.stubGlobal('useCollectionSubscription', vi.fn())
vi.stubGlobal('useUploader', () => ({}))
vi.stubGlobal('useWorkflowValidation', () => ({ canPublish: ref(false), validateBeforeTransition: vi.fn().mockResolvedValue(true) }))
vi.stubGlobal('usePreviewableUrl', () => ({ canPreview: ref(false), load: vi.fn() }))

const fakeYdoc = { getText: () => ({ getAttribute: () => 'false', observe: () => {}, unobserve: () => {} }) }
vi.stubGlobal('useCollaborationAndAttributes', () => ({
  ydoc: ref(fakeYdoc),
  ready: ref(true),
  attributes: new Map(),
  parentCollections: ref([]),
  relationships: ref([]),
  rawAttributes: ref({}),
  reloadParentCollections: vi.fn(),
  reloadRelationships: vi.fn(),
  refreshState: mockRefreshState,
  reload: vi.fn(),
}))

// Counts mounts so a test can tell a remount from a re-render.
let editorMounts = 0
const MetadataEditorStub = defineComponent({
  props: ['metadata', 'ydoc', 'editable', 'uploader', 'attributes', 'state', 'rawAttributes', 'emptyText'],
  setup() {
    onMounted(() => { editorMounts++ })
  },
  template: '<div class="mock-editor" />',
})

const stubs = {
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: { template: '<div class="mock-header"><slot name="actions" /></div>', props: ['accent', 'breadcrumb', 'title', 'subtitle'] },
  // `emits` declared like the real Button, otherwise the listener also falls
  // through to the root element and every click fires twice.
  Button: { template: '<button class="mock-btn" @click="$emit(\'click\')"><slot /></button>', props: ['size', 'icon', 'primary', 'accent', 'disabled'], emits: ['click'] },
  Badge: { template: '<span class="mock-badge"><slot /></span>', props: ['color'] },
  Icon: { template: '<i />', props: ['name', 'size', 'color'] },
  TextInput: { template: '<input class="mock-input" />', props: ['modelValue', 'placeholder', 'icon', 'disabled', 'size'] },
  Select: {
    template: '<input class="mock-select" :data-placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
    props: ['modelValue', 'options', 'placeholder', 'size', 'accent', 'searchable', 'onSearch', 'icon'],
  },
  Switch: { template: '<button class="mock-switch" />', props: ['modelValue', 'label', 'accent'] },
  Tabs: {
    template: '<div class="mock-tabs"><button v-for="t in tabs" :key="t" class="mock-tab" @click="$emit(\'update:modelValue\', t)">{{ t }}</button></div>',
    props: ['modelValue', 'tabs', 'accent'],
  },
  SectionCard: { template: '<div class="mock-section" :data-title="title"><slot /></div>', props: ['title', 'glass'] },
  GlassTable: {
    template: `<div class="mock-table">
      <div v-for="row in rows" :key="row.id" class="mock-row">
        <slot name="col-name" :row="row" />
        <button
          v-for="action in rowActions(row)"
          :key="action.id"
          :data-row-action="action.id"
          :data-row-id="row.id"
          @click="$emit('row-action', { action: action.id, row })">{{ action.label }}</button>
      </div>
    </div>`,
    props: ['columns', 'rows', 'loading', 'emptyText', 'rowActions', 'arrow'],
    emits: ['row-action'],
  },
  Pagination: { template: '<div class="mock-pagination" />', props: ['page', 'totalPages'] },
  OverflowMenu: { template: '<div class="mock-overflow"><slot :toggle="() => {}" /></div>', props: ['items'] },
  CollectionLanguageMenu: { template: '<div class="mock-lang" />', props: ['baseTag', 'variantTags', 'currentTag', 'accent'] },
  CollectionItemSearch: { template: '<div class="mock-item-search" />', props: ['accent', 'placeholder'] },
  Modal: { template: '<div class="mock-modal"><slot /><slot name="footer" /></div>', props: ['title', 'icon', 'width', 'accent', 'subtitle'] },
  ConfirmModal: { template: '<div class="mock-confirm" />', props: ['title', 'subtitle', 'loading'] },
  SlugInput: { template: '<input class="mock-slug" />', props: ['modelValue', 'label', 'size', 'onValidate', 'debounce'] },
  SPicture: { template: '<img class="mock-picture" />', props: ['item', 'pictureClass'] },
  MetadataEditor: MetadataEditorStub,
}

// The page awaits its collection query, so it must mount inside Suspense.
async function mountPage() {
  const Host = defineComponent({
    render() {
      return h(Suspense, null, { default: () => h(CollectionDetail as never) })
    },
  })
  const wrapper = mount(Host, {
    global: {
      stubs,
      // Template-scope auto-imports resolve through the render context, so
      // they must be provided as mocks rather than globals.
      mocks: { buildBreadcrumb: (...args: string[]) => args },
    },
  })
  await flush()
  return wrapper
}

async function flush() {
  for (let i = 0; i < 4; i++) {
    await new Promise(r => setTimeout(r, 0))
    await nextTick()
  }
}

function tabNames(wrapper: VueWrapper) {
  return wrapper.findAll('.mock-tab').map(t => t.text())
}

async function clickTab(wrapper: VueWrapper, name: string) {
  const tab = wrapper.findAll('.mock-tab').find(t => t.text() === name)
  expect(tab, `tab "${name}" should exist`).toBeDefined()
  await tab!.trigger('click')
  await nextTick()
}

async function clickButton(wrapper: VueWrapper, label: string) {
  const btn = wrapper.findAll('.mock-btn').find(b => b.text() === label)
  expect(btn, `button "${label}" should exist`).toBeDefined()
  await btn!.trigger('click')
  await flush()
}

describe('Collection detail page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    editorMounts = 0
    collectionItems = []
    collection.ordering = []
  })

  it('offers metadata-page navigation in the overflow menu only for metadata items', async () => {
    collectionItems = [
      {
        __typename: 'Metadata',
        id: 'metadata-1',
        name: 'First item',
        slug: 'first-item',
        modified: '2026-08-24T12:00:00Z',
        attributes: {},
        itemAttributes: {},
        content: { type: 'bosca/v-document' },
        workflow: { state: 'draft', pending: null },
      },
      {
        __typename: 'Collection',
        id: 'collection-2',
        name: 'Child collection',
        slug: 'child-collection',
        modified: '2026-08-24T12:00:00Z',
        attributes: {},
        itemAttributes: {},
        workflow: { state: 'draft', pending: null },
      },
    ]

    const wrapper = await mountPage()
    const metadataActions = wrapper.findAll('[data-row-id="metadata-1"]')
    const collectionActions = wrapper.findAll('[data-row-id="collection-2"]')
    const viewMetadata = metadataActions.find(action => action.attributes('data-row-action') === 'view-metadata')

    expect(viewMetadata?.text()).toBe('View metadata')
    expect(collectionActions.some(action => action.attributes('data-row-action') === 'view-metadata')).toBe(false)

    await viewMetadata!.trigger('click')
    expect(mockPush).toHaveBeenCalledWith('/cms/metadata/metadata-1')
  })

  it('offers Settings instead of an Attributes tab, keeping the other tabs', async () => {
    const wrapper = await mountPage()
    expect(tabNames(wrapper)).toEqual(['Items', 'Parents', 'Ordering', 'Relationships', 'Permissions', 'Settings'])
  })

  it('renders the template attribute editor in the sidebar, bound to the collection and its workflow', async () => {
    const wrapper = await mountPage()
    const editor = wrapper.find('.panel--side .mock-editor')
    expect(editor.exists()).toBe(true)
    const props = (wrapper.findComponent('.mock-editor') as VueWrapper).props() as Record<string, unknown>
    expect((props.metadata as { id: string }).id).toBe('col-1')
    expect(props.state).toEqual(workflow)
    expect(props.attributes).toBeInstanceOf(Map)
    // Points content editors at the Settings tab when the collection has no template.
    expect(props.emptyText).toContain('Settings')
  })

  it('does not show the sidebar option cards until the Settings tab is opened', async () => {
    const wrapper = await mountPage()
    const titlesBefore = wrapper.findAll('.mock-section').map(s => s.attributes('data-title'))
    expect(titlesBefore).not.toContain('Status')

    await clickTab(wrapper, 'Settings')
    const titles = wrapper.findAll('.mock-section').map(s => s.attributes('data-title'))
    expect(titles).toEqual(expect.arrayContaining(['Info', 'Status', 'Template', 'Workflow']))
    // Optional cards only appear when there is something to show.
    expect(titles).not.toContain('Categories')
    expect(titles).not.toContain('Active Jobs')
  })

  it('remounts the attribute editor after saving a template change so the new fields appear', async () => {
    const wrapper = await mountPage()
    expect(editorMounts).toBe(1)

    await clickTab(wrapper, 'Settings')
    await wrapper.find('.mock-select[data-placeholder="Select template…"]').setValue('tmpl-2')
    await clickButton(wrapper, 'Save')

    const setTemplateCall = mockMutation.mock.calls.find(([, vars]) =>
      (vars as { metadataId?: string })?.metadataId === 'tmpl-2')
    expect(setTemplateCall, 'setTemplate mutation should run with the new template').toBeDefined()
    expect(mockRefresh).toHaveBeenCalled()
    expect(mockRefreshState).toHaveBeenCalled()
    expect(editorMounts).toBe(2)
  })

  it('leaves the attribute editor mounted when saving without a template change', async () => {
    const wrapper = await mountPage()
    await clickButton(wrapper, 'Save')
    expect(mockRefresh).toHaveBeenCalled()
    expect(editorMounts).toBe(1)
  })
  describe('Ordering tab', () => {
    const NEWEST = { __typename: 'Ordering', field: null, location: 'ITEM', order: 'DESCENDING', path: ['published'], type: 'DATE_TIME' }
    const OLDEST = { ...NEWEST, order: 'ASCENDING' }
    const MANUAL = { __typename: 'Ordering', field: null, location: 'RELATIONSHIP', order: 'ASCENDING', path: ['sort'], type: 'INT' }

    const sortSelect = (wrapper: VueWrapper) => wrapper.find('.mock-select[data-placeholder="Choose how items are sorted…"]')
    const settingsButton = (wrapper: VueWrapper) => wrapper.find('.mock-btn[title^="Show ordering rule settings"], .mock-btn[title^="Hide ordering rule settings"]')
    const savedOrdering = () => {
      const call = mockMutation.mock.calls.find(([, vars]) => (vars as { input?: { ordering?: unknown } })?.input?.ordering !== undefined)
      expect(call, 'SaveCollection mutation should run').toBeDefined()
      return (call![1] as { input: { ordering: unknown } }).input.ordering
    }

    it('offers the sort dropdown and keeps the rule editor closed when nothing is configured', async () => {
      const wrapper = await mountPage()
      await clickTab(wrapper, 'Ordering')
      const select = sortSelect(wrapper)
      expect(select.exists()).toBe(true)
      expect((select.element as HTMLInputElement).value).toBe('')
      expect(wrapper.find('.ordering-empty').exists()).toBe(true)
      expect(wrapper.findAll('.ordering-rule')).toHaveLength(0)
      expect(wrapper.find('.ordering-add-block').exists()).toBe(false)
      expect(settingsButton(wrapper).exists()).toBe(false)
    })

    it('choosing drag-and-drop sort writes the one rule the Items tab reorder uses, and Save sends it', async () => {
      const wrapper = await mountPage()
      await clickTab(wrapper, 'Ordering')
      await sortSelect(wrapper).setValue('manual')
      expect((sortSelect(wrapper).element as HTMLInputElement).value).toBe('manual')
      // The rule fields stay tucked away behind the settings button.
      expect(wrapper.findAll('.ordering-rule')).toHaveLength(0)
      expect(wrapper.find('.ordering-preset-hint').text()).toContain('Drag items')

      await clickButton(wrapper, 'Save')
      expect(savedOrdering()).toEqual([
        { field: null, location: 'RELATIONSHIP', path: ['sort'], type: 'INT', order: 'ASCENDING' },
      ])
    })

    it('the settings button reveals the preset rule fields for inspection', async () => {
      const wrapper = await mountPage()
      await clickTab(wrapper, 'Ordering')
      await sortSelect(wrapper).setValue('newest')
      const settings = settingsButton(wrapper)
      expect(settings.exists()).toBe(true)
      await settings.trigger('click')
      await nextTick()
      expect(wrapper.findAll('.ordering-rule')).toHaveLength(1)
      expect(wrapper.find('.ordering-add-block').exists()).toBe(true)
      await settingsButton(wrapper).trigger('click')
      await nextTick()
      expect(wrapper.findAll('.ordering-rule')).toHaveLength(0)
    })

    it('reads a saved publish-date rule back as its preset', async () => {
      collection.ordering = [OLDEST]
      const wrapper = await mountPage()
      await clickTab(wrapper, 'Ordering')
      expect((sortSelect(wrapper).element as HTMLInputElement).value).toBe('oldest')
      expect(wrapper.findAll('.ordering-rule')).toHaveLength(0)
      expect(wrapper.find('.ordering-empty').exists()).toBe(false)
    })

    it('shows Custom with the rule editor open when the saved rules do not match a preset', async () => {
      collection.ordering = [NEWEST, MANUAL]
      const wrapper = await mountPage()
      await clickTab(wrapper, 'Ordering')
      expect((sortSelect(wrapper).element as HTMLInputElement).value).toBe('custom')
      expect(wrapper.findAll('.ordering-rule')).toHaveLength(2)
      expect(settingsButton(wrapper).exists()).toBe(false)
      expect(wrapper.find('.ordering-add-block').exists()).toBe(true)
    })

    it('choosing Custom opens one blank rule, which Save ignores until it is filled in', async () => {
      collection.ordering = [NEWEST]
      const wrapper = await mountPage()
      await clickTab(wrapper, 'Ordering')
      await sortSelect(wrapper).setValue('custom')
      const rules = wrapper.findAll('.ordering-rule')
      expect(rules).toHaveLength(1)
      expect(rules[0]!.classes()).toContain('ordering-rule--invalid')
      expect(wrapper.find('.ordering-warning').exists()).toBe(true)

      await clickButton(wrapper, 'Save')
      expect(savedOrdering()).toEqual([])
    })

    it('choosing a preset replaces whatever rules were there', async () => {
      collection.ordering = [NEWEST, MANUAL]
      const wrapper = await mountPage()
      await clickTab(wrapper, 'Ordering')
      await sortSelect(wrapper).setValue('oldest')
      expect(wrapper.findAll('.ordering-rule')).toHaveLength(0)

      await clickButton(wrapper, 'Save')
      expect(savedOrdering()).toEqual([
        { field: null, location: 'ITEM', path: ['published'], type: 'DATE_TIME', order: 'ASCENDING' },
      ])
    })
  })
})
