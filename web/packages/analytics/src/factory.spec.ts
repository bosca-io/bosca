// @vitest-environment happy-dom

import { describe, it, expect } from 'vitest'
import {
  getAnalyticEventFactory,
  setAnalyticEventFactory,
  getCurrentPage,
  AnalyticEventFactory,
} from './factory'
import { AnalyticEventType } from './event'

describe('AnalyticEventFactory', () => {
  it('should serialize content, extras, and error into flat parameter keys', async () => {
    const factory = getAnalyticEventFactory()
    const event = await factory.createEvent({
      type: AnalyticEventType.impression,
      element: {
        id: 'card',
        type: 'widget',
        content: [
          { id: 'c1', type: 'img', index: 0, percent: 0.75 },
          { id: 'c2', type: 'text' },
        ],
        extras: { source: 'feed', campaign: 'q4' },
      },
    })

    const params = event.toParameters()
    expect(event.name).toBe('impression')

    // Core fields
    expect(params.type).toBe('impression')
    expect(params.element_id).toBe('card')
    expect(params.element_type).toBe('widget')
    expect(params.created).toBeDefined()

    // Extras get 'extra_' prefix
    expect(params.extra_source).toBe('feed')
    expect(params.extra_campaign).toBe('q4')

    // Content gets indexed keys
    expect(params.content_id_0).toBe('c1')
    expect(params['content_id_type_0']).toBe('img')
    expect(params['content_id_index_0']).toBe(0)
    expect(params['content_id_percent_0']).toBe(0.75)

    // Second content element — no index/percent means no keys
    expect(params.content_id_1).toBe('c2')
    expect(params['content_id_index_1']).toBeUndefined()
    expect(params['content_id_percent_1']).toBeUndefined()
  })

  it('should serialize error fields into parameter keys', async () => {
    const factory = getAnalyticEventFactory()
    const event = await factory.createEvent({
      type: AnalyticEventType.error,
      element: { id: '', type: 'error' },
      error: { message: 'fail', type: 'Error', fatal: false, stack_trace: 'at x()', code: 'X1' },
    })

    const params = event.toParameters()
    expect(params.error_message).toBe('fail')
    expect(params.error_type).toBe('Error')
    expect(params.error_fatal).toBe(false)
    expect(params.error_stack_trace).toBe('at x()')
    expect(params.error_code).toBe('X1')
  })

  it('should produce independent clones that share no references', async () => {
    const factory = getAnalyticEventFactory()
    const event = await factory.createEvent({
      type: AnalyticEventType.impression,
      element: {
        id: 'e1',
        type: 't1',
        content: [{ id: 'c', type: 'img', index: 1 }],
        extras: { k: 'v' },
      },
      error: { message: 'err' },
    })

    const cloned = event.clone()

    // Same data
    expect(cloned.element.id).toBe('e1')
    expect(cloned.error!.message).toBe('err')
    expect(cloned.element.content[0].id).toBe('c')

    // Different object references
    expect(cloned).not.toBe(event)
    expect(cloned.element).not.toBe(event.element)
    expect(cloned.element.content[0]).not.toBe(event.element.content[0])
  })

  describe('page snapshot', () => {
    it('captures pathname, href, and document.title at emit time', async () => {
      // vitest browser mode runs in a real browser, so window/document exist.
      const originalTitle = document.title
      document.title = 'Page Snapshot Test'
      try {
        const factory = getAnalyticEventFactory()
        const event = await factory.createEvent({
          type: AnalyticEventType.impression,
          element: { id: 'x', type: 'y' },
        })
        expect(event.page).toBeDefined()
        expect(event.page!.path).toBe(window.location.pathname)
        expect(event.page!.url).toBe(window.location.href)
        expect(event.page!.title).toBe('Page Snapshot Test')
      } finally {
        document.title = originalTitle
      }
    })

    it('explicit page on the input event overrides the live snapshot', async () => {
      // Trackers and tests can preset a page when they need a deterministic
      // value (e.g. recording a page on the previous URL after a SPA nav).
      const factory = getAnalyticEventFactory()
      const event = await factory.createEvent({
        type: AnalyticEventType.interaction,
        element: { id: 'x', type: 'y' },
        page: { path: '/explicit', url: 'https://example.test/explicit', title: 'Explicit' },
      })
      expect(event.page!.path).toBe('/explicit')
      expect(event.page!.url).toBe('https://example.test/explicit')
      expect(event.page!.title).toBe('Explicit')
    })

    it('clones preserve the page snapshot', async () => {
      const factory = getAnalyticEventFactory()
      const event = await factory.createEvent({
        type: AnalyticEventType.interaction,
        element: { id: 'x', type: 'y' },
        page: { path: '/p', url: 'https://example.test/p', title: 'P' },
      })
      const cloned = event.clone()
      expect(cloned.page).toEqual(event.page)
    })
  })

  describe('getCurrentPage', () => {
    it('returns pathname, href, and document.title from the live DOM', () => {
      const originalTitle = document.title
      document.title = 'getCurrentPage Test'
      try {
        const page = getCurrentPage()
        expect(page).toBeDefined()
        expect(page!.path).toBe(window.location.pathname)
        expect(page!.url).toBe(window.location.href)
        expect(page!.title).toBe('getCurrentPage Test')
      } finally {
        document.title = originalTitle
      }
    })

    it('returns the same snapshot as the factory would attach', async () => {
      const factory = getAnalyticEventFactory()
      const event = await factory.createEvent({
        type: AnalyticEventType.impression,
        element: { id: 'x', type: 'y' },
      })
      const page = getCurrentPage()
      expect(page!.path).toBe(event.page!.path)
      expect(page!.url).toBe(event.page!.url)
    })
  })

  it('should allow replacing the global factory', async () => {
    const original = getAnalyticEventFactory()
    let customCalled = false

    const custom: AnalyticEventFactory = {
      async createEvent(ev) {
        customCalled = true
        return original.createEvent(ev)
      },
    }

    setAnalyticEventFactory(custom)
    expect(getAnalyticEventFactory()).toBe(custom)

    await custom.createEvent({
      type: AnalyticEventType.impression,
      element: { id: 'x', type: 'y' },
    })
    expect(customCalled).toBe(true)

    setAnalyticEventFactory(original)
  })
})
