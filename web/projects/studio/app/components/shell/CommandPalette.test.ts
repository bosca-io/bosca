import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref, computed, nextTick } from 'vue'
import CommandPalette from './CommandPalette.vue'
import { SUBSYSTEMS } from '~/composables/useSubsystems'
import type { OmniSearchHit, OmniSearchSource } from '~/composables/useOmniSearch'

const omni = {
  content: ref<OmniSearchHit[]>([]),
  people: ref<OmniSearchHit[]>([]),
  workops: ref<OmniSearchHit[]>([]),
  searching: ref(false),
  failedSources: ref<OmniSearchSource[]>([]),
  search: vi.fn().mockResolvedValue(undefined),
  clear: vi.fn(),
}

// CommandPalette consumes `visibleSubsystems`/`visibleSubsystemIds` from
// usePersonas. Derive them here from the test-controlled `isAdmin` +
// `allowedSubsystemIds` exactly as the real composable does (with all server
// features enabled), so existing persona-scoping assertions keep their meaning;
// feature-gating itself is unit-tested in usePersonas.test.ts.
const personasBase = {
  allowedSubsystemIds: ref(new Set<string>()),
  isAdmin: ref(true),
}
const visibleSubsystems = computed(() =>
  personasBase.isAdmin.value || personasBase.allowedSubsystemIds.value.size === 0
    ? SUBSYSTEMS
    : SUBSYSTEMS.filter(s => personasBase.allowedSubsystemIds.value.has(s.id)),
)
const visibleSubsystemIds = computed(() => new Set(visibleSubsystems.value.map(s => s.id)))
const personas = { ...personasBase, visibleSubsystems, visibleSubsystemIds }

vi.stubGlobal('useOmniSearch', () => omni)
vi.stubGlobal('usePersonas', () => personas)

const stubs = {
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
}

function mountPalette(props: Partial<{ open: boolean; subsystem: string }> = {}) {
  return mount(CommandPalette, {
    props: { open: true, subsystem: 'cms', ...props },
    global: { stubs },
  })
}

function queryBody(selector: string) {
  return document.body.querySelector(selector)
}

function queryAllBody(selector: string) {
  return document.body.querySelectorAll(selector)
}

function cleanupBody() {
  while (document.body.firstChild) {
    document.body.removeChild(document.body.firstChild)
  }
}

function groupLabels(): string[] {
  return Array.from(queryAllBody('.palette-group-label')).map(el => el.textContent ?? '')
}

async function typeQuery(value: string) {
  const input = queryBody('.palette-search-input') as HTMLInputElement
  input.value = value
  input.dispatchEvent(new Event('input'))
  await nextTick()
}

async function flushSearchDebounce() {
  vi.advanceTimersByTime(250)
  await nextTick()
}

function contentHit(overrides: Partial<OmniSearchHit> = {}): OmniSearchHit {
  return {
    kind: 'metadata',
    id: 'm1',
    label: 'Hero Banner',
    hint: 'CMS · Content',
    path: '/cms/metadata/m1',
    ...overrides,
  }
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  omni.content.value = []
  omni.people.value = []
  omni.workops.value = []
  omni.searching.value = false
  omni.failedSources.value = []
  omni.search.mockClear()
  omni.clear.mockClear()
  personas.allowedSubsystemIds.value = new Set<string>()
  personas.isAdmin.value = true
})

afterEach(() => {
  vi.useRealTimers()
  cleanupBody()
})

describe('CommandPalette', () => {
  it('does not render when closed', () => {
    mountPalette({ open: false })
    expect(queryBody('.palette-panel')).toBeNull()
  })

  it('shows quick actions and navigation groups for an empty query', () => {
    mountPalette()
    const labels = groupLabels()
    expect(labels).toContain('Quick actions')
    expect(labels).toContain('Go to · this subsystem')
    expect(labels).toContain('Go to · other')
    expect(labels).not.toContain('Content')
  })

  it('renders quick actions as a tile grid for an empty query', () => {
    mountPalette()
    expect(queryBody('.palette-action-grid')).not.toBeNull()
    const tiles = Array.from(queryAllBody('.palette-tile-label')).map(el => el.textContent)
    expect(tiles.length).toBeGreaterThanOrEqual(18)
    expect(tiles).toContain('New document')
    expect(tiles).toContain('New guide')
    expect(tiles).toContain('New data')
    expect(tiles).toContain('New task')
    expect(tiles).toContain('New pipeline')
  })

  it('renders quick actions as list rows while filtering', async () => {
    const wrapper = mountPalette()
    await typeQuery('new form')
    await nextTick()

    expect(queryBody('.palette-action-grid')).toBeNull()
    const row = Array.from(queryAllBody('.palette-item'))
      .find(el => el.querySelector('.palette-item-label')?.textContent === 'New form') as HTMLButtonElement
    expect(row).toBeTruthy()
    row.click()
    expect(wrapper.emitted('navigate')).toEqual([['/forms/builder/new']])
  })

  it('debounces typing into a single omni search with the trimmed query', async () => {
    mountPalette()
    await typeQuery('he')
    await typeQuery(' hero ')
    expect(omni.search).not.toHaveBeenCalled()

    await flushSearchDebounce()
    expect(omni.search).toHaveBeenCalledTimes(1)
    expect(omni.search).toHaveBeenCalledWith('hero', { entities: true, workops: true })
  })

  it('clears results instead of searching for queries under two characters', async () => {
    mountPalette()
    await typeQuery('h')
    await flushSearchDebounce()
    expect(omni.search).not.toHaveBeenCalled()
    expect(omni.clear).toHaveBeenCalled()
  })

  it('renders remote content, people, and workops groups', async () => {
    mountPalette()
    omni.content.value = [contentHit()]
    omni.people.value = [contentHit({ kind: 'profile', id: 'p1', label: 'Ari Chen', hint: '@ari', path: '/audience/profiles/p1' })]
    omni.workops.value = [contentHit({ kind: 'task', id: 't1', label: 'Fix banner', hint: 'TASK-1 · In Progress', path: '/workops/tasks/t1' })]
    await typeQuery('zzz-no-local-match')
    await nextTick()

    const labels = groupLabels()
    expect(labels).toContain('Content')
    expect(labels).toContain('People')
    expect(labels).toContain('Work Ops')

    const itemText = Array.from(queryAllBody('.palette-item')).map(el => el.textContent ?? '')
    expect(itemText.some(t => t.includes('Hero Banner'))).toBe(true)
    expect(itemText.some(t => t.includes('Ari Chen'))).toBe(true)
    expect(itemText.some(t => t.includes('Fix banner'))).toBe(true)
  })

  it('emits navigate with the hit path when a remote row is clicked', async () => {
    const wrapper = mountPalette()
    omni.content.value = [contentHit()]
    await typeQuery('zzz-no-local-match')
    await nextTick()

    const row = Array.from(queryAllBody('.palette-item'))
      .find(el => el.textContent?.includes('Hero Banner')) as HTMLButtonElement
    row.click()
    expect(wrapper.emitted('navigate')).toEqual([['/cms/metadata/m1']])
  })

  it('emits navigate for the highlighted row on Enter', async () => {
    const wrapper = mountPalette()
    omni.content.value = [contentHit()]
    await typeQuery('zzz-no-local-match')
    await nextTick()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    expect(wrapper.emitted('navigate')).toEqual([['/cms/metadata/m1']])
  })

  it('emits navigate with the create deep-link when a quick action tile is activated', () => {
    const wrapper = mountPalette()
    const newDocument = Array.from(queryAllBody('.palette-tile'))
      .find(el => el.querySelector('.palette-tile-label')?.textContent === 'New document') as HTMLButtonElement
    newDocument.click()
    expect(wrapper.emitted('navigate')).toEqual([['/cms/documents?new=1']])
  })

  it('opens new campaigns from the Communications route', () => {
    const wrapper = mountPalette({ subsystem: 'communications' })
    const newCampaign = Array.from(queryAllBody('.palette-tile'))
      .find(el => el.querySelector('.palette-tile-label')?.textContent === 'New campaign') as HTMLButtonElement

    newCampaign.click()

    expect(wrapper.emitted('navigate')).toEqual([['/communications/campaigns/new']])
  })

  it('navigates the grid by tile with ArrowRight and by row with ArrowDown', () => {
    const wrapper = mountPalette()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    expect(wrapper.emitted('navigate')).toEqual([['/cms/guides?new=1']])
  })

  it('moves down one grid row (four tiles) with ArrowDown', () => {
    const wrapper = mountPalette()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    // Index 4 in the admin action grid is 'New task'
    expect(wrapper.emitted('navigate')).toEqual([['/workops/tasks?new=1']])
  })

  it('drops out of the grid into the nav list and climbs back in', () => {
    const wrapper = mountPalette()

    // 20 admin actions → 5 rows of 4; the 5th ArrowDown leaves the grid
    for (let i = 0; i < 5; i++) {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }))
    }
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    expect(wrapper.emitted('jump')).toEqual([['cms', 'collections']])

    // ArrowUp from the first list row returns to the last grid tile
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp' }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    expect(wrapper.emitted('navigate')).toEqual([['/scripts/new']])
  })

  it('emits jump with subsystem and view when a nav row is activated', () => {
    const wrapper = mountPalette()
    const navRow = Array.from(queryAllBody('.palette-item'))
      .find(el => el.querySelector('.palette-item-label')?.textContent === 'Collections') as HTMLButtonElement
    navRow.click()
    expect(wrapper.emitted('jump')).toEqual([['cms', 'collections']])
  })

  it('hides quick actions for subsystems outside the user personas', () => {
    personas.isAdmin.value = false
    personas.allowedSubsystemIds.value = new Set(['cms'])
    mountPalette()

    const labels = Array.from(queryAllBody('.palette-tile-label')).map(el => el.textContent)
    expect(labels).toContain('New document')
    expect(labels).toContain('New guide')
    expect(labels).toContain('New data')
    expect(labels).toContain('New collection')
    expect(labels).not.toContain('New segment')
    expect(labels).not.toContain('New form')
    expect(labels).not.toContain('New experiment')
    expect(labels).not.toContain('New task')
    expect(labels).not.toContain('New pipeline')
  })

  it('emits close when the overlay is clicked', async () => {
    const wrapper = mountPalette()
    ;(queryBody('.palette-overlay') as HTMLElement).click()
    await nextTick()
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('shows a searching state instead of "no results" while a search is in flight', async () => {
    mountPalette()
    omni.searching.value = true
    await typeQuery('zzz-no-local-match')
    await nextTick()

    expect(queryBody('.palette-no-results')?.textContent).toContain('Searching…')
  })

  it('shows a warning when a search source failed', async () => {
    mountPalette()
    omni.failedSources.value = ['workops']
    await typeQuery('zzz-no-local-match')
    await nextTick()

    expect(queryBody('.palette-source-warning')?.textContent).toContain('unavailable')
  })

  it('scopes search sources and result groups to the user personas', async () => {
    mountPalette()
    personas.isAdmin.value = false
    personas.allowedSubsystemIds.value = new Set(['cms'])
    omni.workops.value = [contentHit({ kind: 'task', id: 't1', label: 'Hidden task', path: '/workops/tasks/t1' })]

    await typeQuery('zzz-no-local-match')
    await flushSearchDebounce()

    expect(omni.search).toHaveBeenCalledWith('zzz-no-local-match', { entities: true, workops: false })
    expect(groupLabels()).not.toContain('Work Ops')
  })

  it('clears pending results when the palette reopens', async () => {
    const wrapper = mountPalette()
    await typeQuery('hero')
    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true })
    await nextTick()

    const input = queryBody('.palette-search-input') as HTMLInputElement
    expect(input.value).toBe('')
    expect(omni.clear).toHaveBeenCalled()
  })
})
