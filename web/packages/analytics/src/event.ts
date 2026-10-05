export enum AnalyticEventType {
  session = 'session',
  /** Includes scrolling, whose depth milestones are quality telemetry rather than discrete engagements. */
  interaction = 'interaction',
  /** Visibility, not engagement, unless the event's element type is `page`. */
  impression = 'impression',
  completion = 'completion',
  installation = 'installation',
  assignment = 'assignment',
  error = 'error',
  heartbeat = 'heartbeat',
}

export interface IContentElement {
  id: string
  type: string
  index?: number | undefined
  /** Content consumed in percentage points (0–100); omit when consumption is unknown. */
  percent?: number | undefined
}

export interface IAnalyticElement {
  id: string
  type: string
  content?: IContentElement[] | undefined
  extras?: { [key: string]: string } | undefined
}

/**
 * Snapshot of the page a user was on when an analytics event was emitted.
 *
 * Carried as a top-level field on the event so server-side queries can
 * filter by page without parsing JSON. Populated automatically at emit time
 * by the analytics factory from `window.location` and `document.title`;
 * callers may also pass it explicitly to override.
 */
export interface IPage {
  path?: string | undefined
  url?: string | undefined
  title?: string | undefined
}

export interface IErrorInfo {
  message: string
  type?: string | undefined
  stack_trace?: string | undefined
  fatal?: boolean | undefined
  code?: string | undefined
}

export interface IAnalyticEvent {
  type: AnalyticEventType
  element: IAnalyticElement
  page?: IPage | undefined
  error?: IErrorInfo | undefined
}

export abstract class ContentElement {
  abstract get id(): string
  abstract get type(): string
  abstract get index(): number | undefined
  abstract get percent(): number | undefined

  abstract clone(): ContentElement
}

export abstract class AnalyticElement {
  abstract get id(): string
  abstract get type(): string
  abstract get content(): ContentElement[]
  abstract get extras(): { [key: string]: string }
  
  abstract clone(): AnalyticElement
}

export abstract class AnalyticEvent {

  abstract get type(): AnalyticEventType
  abstract get name(): string
  abstract get created(): Date
  abstract get element(): AnalyticElement
  abstract get error(): IErrorInfo | undefined
  /**
   * Snapshot of the page when the event was created. Populated at emit time
   * by the factory; null when no page context is available (non-browser
   * runtime, or page not yet known).
   */
  abstract get page(): IPage | undefined

  abstract toParameters(): any
  abstract clone(): AnalyticEvent
}
