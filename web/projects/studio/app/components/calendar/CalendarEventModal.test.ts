import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import CalendarEventModal from './CalendarEventModal.vue'
import type { EventDraft } from './CalendarEventModal.vue'

vi.stubGlobal('useGraphQL', () => ({
  query: vi.fn().mockResolvedValue({ calendars: { event: { participants: [] } } }),
  mutation: vi.fn().mockResolvedValue({}),
}))

vi.stubGlobal('useProfileSearch', () => ({
  searchProfiles: vi.fn().mockResolvedValue([]),
}))

const stubs = {
  Icon: { template: '<span />', props: ['name', 'size'] },
  Switch: { template: '<button />', props: ['modelValue', 'disabled'] },
  Select: { template: '<div />', props: ['options', 'placeholder', 'searchable', 'modelValue'] },
  RRuleEditor: { template: '<div class="mock-rrule" />', props: ['modelValue'] },
}

function makeDraft(overrides: Partial<EventDraft> = {}): EventDraft {
  return {
    id: 'evt-1',
    metadataId: 'cal-1',
    version: 1,
    title: 'Test Event',
    description: 'A test',
    location: 'Room 1',
    allDay: false,
    startsAt: '2026-05-15T10:00:00.000Z',
    endsAt: '2026-05-15T11:00:00.000Z',
    rrule: null,
    isRecurring: false,
    ...overrides,
  }
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

afterEach(() => {
  cleanupBody()
})

describe('CalendarEventModal', () => {
  it('renders when open', () => {
    mount(CalendarEventModal, {
      props: { open: true, event: makeDraft(), saving: false, deleting: false },
      global: { stubs },
    })
    expect(queryBody('.modal-dialog')).not.toBeNull()
  })

  it('does not render when closed', () => {
    mount(CalendarEventModal, {
      props: { open: false, event: null, saving: false, deleting: false },
      global: { stubs },
    })
    expect(queryBody('.modal-dialog')).toBeNull()
  })

  it('shows "New Event" title for new events', () => {
    mount(CalendarEventModal, {
      props: { open: true, event: makeDraft({ id: undefined }), saving: false, deleting: false },
      global: { stubs },
    })
    expect(queryBody('.modal-title')?.textContent).toBe('New Event')
  })

  it('shows "Edit Event" title for existing events', () => {
    mount(CalendarEventModal, {
      props: { open: true, event: makeDraft(), saving: false, deleting: false },
      global: { stubs },
    })
    expect(queryBody('.modal-title')?.textContent).toBe('Edit Event')
  })

  it('renders title, description, location fields', () => {
    mount(CalendarEventModal, {
      props: { open: true, event: makeDraft(), saving: false, deleting: false },
      global: { stubs },
    })
    const inputs = queryAllBody('.field-input')
    expect(inputs.length).toBeGreaterThanOrEqual(3)
  })

  it('renders all-day switch', () => {
    mount(CalendarEventModal, {
      props: { open: true, event: makeDraft(), saving: false, deleting: false },
      global: { stubs },
    })
    const switches = queryAllBody('button')
    expect(switches.length).toBeGreaterThanOrEqual(1)
  })

  it('renders RRULE editor', () => {
    mount(CalendarEventModal, {
      props: { open: true, event: makeDraft(), saving: false, deleting: false },
      global: { stubs },
    })
    expect(queryBody('.mock-rrule')).not.toBeNull()
  })

  it('shows delete button for existing events', () => {
    mount(CalendarEventModal, {
      props: { open: true, event: makeDraft(), saving: false, deleting: false },
      global: { stubs },
    })
    expect(queryBody('.footer-btn--delete')).not.toBeNull()
  })

  it('hides delete button for new events', () => {
    mount(CalendarEventModal, {
      props: { open: true, event: makeDraft({ id: undefined }), saving: false, deleting: false },
      global: { stubs },
    })
    expect(queryBody('.footer-btn--delete')).toBeNull()
  })

  it('shows "Create" for new events', () => {
    mount(CalendarEventModal, {
      props: { open: true, event: makeDraft({ id: undefined }), saving: false, deleting: false },
      global: { stubs },
    })
    expect(queryBody('.footer-btn--primary')?.textContent?.trim()).toBe('Create')
  })

  it('shows "Save" for existing events', () => {
    mount(CalendarEventModal, {
      props: { open: true, event: makeDraft(), saving: false, deleting: false },
      global: { stubs },
    })
    expect(queryBody('.footer-btn--primary')?.textContent?.trim()).toBe('Save')
  })

  it('disables fields for readonly events', () => {
    mount(CalendarEventModal, {
      props: { open: true, event: makeDraft({ readonly: true }), saving: false, deleting: false },
      global: { stubs },
    })
    const inputs = queryAllBody('.field-input')
    expect((inputs[0] as HTMLInputElement)?.disabled).toBe(true)
  })
})
