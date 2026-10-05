// @vitest-environment happy-dom

import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import {
  AnalyticEventSink,
  AnalyticEventInterceptor,
  addSink,
  removeSink,
  logEvent,
  logImpression,
  logInteraction,
  logCompletion,
  logError,
  logEventBeacon,
} from './sink'
import { AnalyticEvent, AnalyticEventType, IAnalyticEvent } from './event'

class TestSink extends AnalyticEventSink {
  public events: AnalyticEvent[] = []
  public beaconEvents: IAnalyticEvent[] = []

  protected async onAdd(_original: AnalyticEvent, event: AnalyticEvent): Promise<void> {
    this.events.push(event)
  }

  addBeacon(event: IAnalyticEvent): void {
    this.beaconEvents.push(event)
  }
}

describe('sink', () => {
  let testSink: TestSink

  beforeEach(() => {
    testSink = new TestSink()
    addSink(testSink)
  })

  afterEach(() => {
    removeSink(testSink)
  })

  it('should deliver events to all registered sinks and stop after removal', async () => {
    const secondSink = new TestSink()
    addSink(secondSink)

    await logEvent({ type: AnalyticEventType.interaction, element: { id: 'btn', type: 'button' } })
    expect(testSink.events).toHaveLength(1)
    expect(secondSink.events).toHaveLength(1)

    removeSink(secondSink)
    await logEvent({ type: AnalyticEventType.impression, element: { id: 'card', type: 'div' } })
    expect(testSink.events).toHaveLength(2)
    expect(secondSink.events).toHaveLength(1)
  })

  it('should route all event types through to sinks', async () => {
    await logImpression({ id: 'a', type: 'banner' })
    await logInteraction({ id: 'b', type: 'button' })
    await logCompletion({ id: 'c', type: 'form' })

    expect(testSink.events).toHaveLength(3)
    expect(testSink.events[0].type).toBe(AnalyticEventType.impression)
    expect(testSink.events[1].type).toBe(AnalyticEventType.interaction)
    expect(testSink.events[2].type).toBe(AnalyticEventType.completion)
  })

  it('should pass error info through to sinks via logError', async () => {
    await logError({ message: 'crash', type: 'Fatal', fatal: true, code: 'E1' })
    expect(testSink.events).toHaveLength(1)

    const event = testSink.events[0]
    expect(event.type).toBe(AnalyticEventType.error)
    expect(event.element.type).toBe('error')
    expect(event.error!.message).toBe('crash')
    expect(event.error!.fatal).toBe(true)
  })

  it('should use custom element when provided to logError', async () => {
    await logError({ message: 'fail' }, { id: 'form-1', type: 'form' })
    expect(testSink.events[0].element.id).toBe('form-1')
    expect(testSink.events[0].element.type).toBe('form')
  })

  describe('interceptors', () => {
    it('should pass cloned event through interceptor chain before delivery', async () => {
      let interceptedType = ''
      const interceptor: AnalyticEventInterceptor = {
        async intercept(event: AnalyticEvent): Promise<AnalyticEvent> {
          interceptedType = event.element.type
          return event
        },
      }
      testSink.addInterceptor(interceptor)

      await logEvent({ type: AnalyticEventType.impression, element: { id: 'x', type: 'widget' } })

      expect(interceptedType).toBe('widget')
      expect(testSink.events).toHaveLength(1)
    })

    it('should give interceptor a clone so mutations do not affect original', async () => {
      let origRef: AnalyticEvent | null = null
      let cloneRef: AnalyticEvent | null = null

      class CaptureSink extends AnalyticEventSink {
        protected async onAdd(original: AnalyticEvent, intercepted: AnalyticEvent): Promise<void> {
          origRef = original
          cloneRef = intercepted
        }
      }

      const captureSink = new CaptureSink()
      captureSink.addInterceptor({
        async intercept(event) { return event },
      })
      addSink(captureSink)

      await logEvent({ type: AnalyticEventType.impression, element: { id: 'a', type: 'b' } })

      expect(origRef).not.toBe(cloneRef)
      expect(origRef!.element.id).toBe(cloneRef!.element.id)

      removeSink(captureSink)
    })
  })

  describe('logEventBeacon', () => {
    it('should fall back to normal delivery for sinks without a beacon transport', async () => {
      class AsyncOnlySink extends AnalyticEventSink {
        public events: AnalyticEvent[] = []

        protected async onAdd(_original: AnalyticEvent, event: AnalyticEvent): Promise<void> {
          this.events.push(event)
        }
      }
      const asyncSink = new AsyncOnlySink()
      addSink(asyncSink)

      logEventBeacon({
        type: AnalyticEventType.interaction,
        element: { id: 'link', type: 'click' },
      })
      await Promise.resolve()
      await Promise.resolve()

      expect(asyncSink.events).toHaveLength(1)
      expect(asyncSink.events[0].element.id).toBe('link')
      removeSink(asyncSink)
    })

    it('should deliver to all sinks and isolate failures', () => {
      const errorSink = new TestSink()
      errorSink.addBeacon = () => { throw new Error('beacon failed') }
      addSink(errorSink)

      const event: IAnalyticEvent = {
        type: AnalyticEventType.interaction,
        element: { id: 'btn', type: 'button' },
      }

      // Should not throw despite errorSink failing
      expect(() => logEventBeacon(event)).not.toThrow()
      // testSink still received the beacon
      expect(testSink.beaconEvents).toHaveLength(1)

      removeSink(errorSink)
    })
  })
})
