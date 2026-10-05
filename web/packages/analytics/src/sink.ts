import { AnalyticEvent, AnalyticEventType, type IAnalyticElement, type IAnalyticEvent, type IErrorInfo } from './event'
import { getAnalyticEventFactory } from './factory'

let sinks: AnalyticEventSink[] = []

export function addSink(sink: AnalyticEventSink) {
  sinks.push(sink)
}

export function removeSink(sink: AnalyticEventSink) {
  sinks = sinks.filter(s => s !== sink)
}

export interface AnalyticEventInterceptor {

  intercept(event: AnalyticEvent): Promise<AnalyticEvent>
}

export abstract class AnalyticEventSink {

  private interceptors: AnalyticEventInterceptor[] = []

  addInterceptor(interceptor: AnalyticEventInterceptor) {
    this.interceptors.push(interceptor)
  }

  /**
   * Synchronously sends an event via navigator.sendBeacon for reliable delivery
   * during page unload. Subclasses with a beacon transport should override this;
   * other sinks receive the event through their ordinary async path as a best effort.
   */
  addBeacon(event: IAnalyticEvent): void {
    getAnalyticEventFactory().createEvent(event)
      .then((created) => this.add(created))
      .catch((e) => console.error('failed to send fallback beacon event:', e))
  }

  protected abstract onAdd(original: AnalyticEvent, event: AnalyticEvent): Promise<void>

  async add(event: AnalyticEvent): Promise<void> {
    const original = event
    for (const interceptor of this.interceptors) {
      event = await interceptor.intercept(event.clone())
    }
    await this.onAdd(original, event)
  }
}

export async function logEvent(event: IAnalyticEvent): Promise<void> {
  const ev = await getAnalyticEventFactory().createEvent(event)
  const promises = []
  for (const sink of sinks) {
    promises.push(sink.add(ev))
  }
  await Promise.all(promises)
}

export async function logImpression(element: IAnalyticElement): Promise<void> {
  await logEvent({
    type: AnalyticEventType.impression,
    element: element,
  })
}

export async function logInteraction(element: IAnalyticElement): Promise<void> {
  await logEvent({
    type: AnalyticEventType.interaction,
    element: element,
  })
}

export async function logCompletion(element: IAnalyticElement): Promise<void> {
  await logEvent({
    type: AnalyticEventType.completion,
    element: element,
  })
}

/**
 * Records an error event through the analytics pipeline so that application
 * failures can be tracked, aggregated, and alerted on.
 */
export async function logError(error: IErrorInfo, element?: IAnalyticElement): Promise<void> {
  await logEvent({
    type: AnalyticEventType.error,
    element: element || { id: '', type: 'error' },
    error: error,
  })
}

/**
 * Sends an event through each sink's unload-safe path. Sinks with a beacon
 * transport send synchronously; other sinks fall back to their ordinary async
 * path so they still receive the event when the browser allows it.
 */
export function logEventBeacon(event: IAnalyticEvent): void {
  for (const sink of sinks) {
    try {
      sink.addBeacon(event)
    } catch (e) {
      console.error('failed to send beacon event:', e)
    }
  }
}
