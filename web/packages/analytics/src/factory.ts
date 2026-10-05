import { AnalyticElement, AnalyticEvent, AnalyticEventType, ContentElement, type IAnalyticElement, type IAnalyticEvent, type IContentElement, type IErrorInfo, type IPage } from './event'


export interface AnalyticEventFactory {

  createEvent(event: IAnalyticEvent): Promise<AnalyticEvent>
}

/**
 * Captures the current page snapshot at emit time.
 *
 * The snapshot must be taken **here** — inside `createEvent`, on the same
 * call stack as the originating tracker — and not at subscriber setup time.
 * SPA navigation means `window.location` changes between events, and the
 * snapshot must reflect the URL at the moment the event was triggered, not
 * the URL when the analytics SDK was initialized.
 *
 * Returns `undefined` outside a browser context (e.g. server-side rendering,
 * test environments without `window`) so that the resulting `Event` simply
 * has no `page` field, which is what the backend expects for legacy clients.
 */
function snapshotCurrentPage(): IPage | undefined {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return undefined
  }
  return {
    path: window.location?.pathname,
    url: window.location?.href,
    title: document.title,
  }
}

/**
 * Returns a snapshot of the current page context (pathname, full URL, and
 * document title) at the time of the call. Returns `undefined` outside a
 * browser context (e.g. SSR or test environments without `window`).
 *
 * This is the same snapshot logic used internally by the event factory to
 * populate `AnalyticEvent.page`. Consumers can use it when they need to
 * read the current page context independently — for instance, to attach
 * page metadata to a non-analytics subsystem or to capture a snapshot
 * before a SPA navigation fires.
 */
export function getCurrentPage(): IPage | undefined {
  return snapshotCurrentPage()
}

let factory: AnalyticEventFactory = {
  async createEvent(event) {
    // Page snapshot priority: explicit page on the input event wins (so
    // tests and special-case callers can override), then the live page
    // snapshot from window/document.
    const page = event.page ?? snapshotCurrentPage()
    return new DefaultAnalyticEvent(event,
      new DefaultAnalyticElement(
        event.element,
        event.element ? (event.element.extras || {}) : {},
        event.element && event.element.content ? event.element.content.map((c) => new DefaultContentElement(c)) : [],
      ),
      page,
    )
  },
}

export function getAnalyticEventFactory(): AnalyticEventFactory {
  return factory
}

export function setAnalyticEventFactory(newFactory: AnalyticEventFactory) {
  factory = newFactory
}

export class DefaultContentElement extends ContentElement {

  readonly id: string
  readonly type: string
  readonly index: number | undefined
  readonly percent: number | undefined

  constructor(element: IContentElement) {
    super()
    this.id = element.id
    this.type = element.type
    this.index = element.index
    this.percent = element.percent
  }

  clone(): ContentElement {
    return new DefaultContentElement({ id: this.id, type: this.type, index: this.index, percent: this.percent })
  }
}

export class DefaultAnalyticElement extends AnalyticElement {
  private readonly element: IAnalyticElement
  readonly extras: { [key: string]: string }
  readonly content: ContentElement[]

  constructor(element: IAnalyticElement, extras: { [key: string]: string }, content: ContentElement[]) {
    super()
    this.element = element
    this.extras = extras
    this.content = content
  }

  get id(): string {
    return this.element.id
  }

  get type(): string {
    return this.element.type
  }

  clone(): AnalyticElement {
    return new DefaultAnalyticElement(this.element, this.extras, this.content.map((c) => c.clone()))
  }
}

export class DefaultAnalyticEvent extends AnalyticEvent {

  private readonly event: IAnalyticEvent
  private readonly _created = new Date()
  private readonly _page: IPage | undefined
  readonly element: AnalyticElement

  constructor(event: IAnalyticEvent, element: AnalyticElement, page?: IPage) {
    super()
    this.event = event
    this.element = element
    this._page = page
  }

  get type(): AnalyticEventType {
    return this.event.type
  }

  get created(): Date {
    return this._created
  }

  get name(): string {
    return this.event.type.toString()
  }

  get error(): IErrorInfo | undefined {
    return this.event.error
  }

  get page(): IPage | undefined {
    return this._page
  }

  toParameters(): any {
    const parameters: { [key: string ]: any } = {
      type: this.type.toString(),
      element_id: this.element.id,
      element_type: this.element.type,
      created: this.created.toISOString(),
    }
    if (this.element.extras) {
      for (const key in this.element.extras) {
        parameters['extra_' + key] = this.element.extras[key]
      }
    }
    if (this.element.content) {
      let ix = 0
      for (const content of this.element.content) {
        parameters['content_id_' + ix] = content.id
        parameters['content_id_type_' + ix] = content.type
        if (content.index !== undefined) {
          parameters['content_id_index_' + ix] = content.index

        }
        if (content.percent) {
          parameters['content_id_percent_' + ix] = content.percent
        }
        ix++
      }
    }
    if (this.error) {
      parameters['error_message'] = this.error.message
      if (this.error.type !== undefined) parameters['error_type'] = this.error.type
      if (this.error.fatal !== undefined) parameters['error_fatal'] = this.error.fatal
      if (this.error.stack_trace !== undefined) parameters['error_stack_trace'] = this.error.stack_trace
      if (this.error.code !== undefined) parameters['error_code'] = this.error.code
    }
    return parameters
  }

  clone(): AnalyticEvent {
    return new DefaultAnalyticEvent(this.event, this.element.clone(), this._page)
  }
}

