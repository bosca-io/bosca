export interface Context {
  app_id: string
  app_version: string
  client_id: string
  device: Device
  geo: Geo
  browser: Browser | null
  session_id: string
  user_id?: string | null | undefined
}

export interface Device {
  installation_id: string
  manufacturer: string
  model: string
  platform: string
  primary_locale: string
  system_name: string
  timezone: string
  type: string
  version: string
}

export interface Geo {
  city: string
  region: string
  country: string
}

export interface Browser {
  agent: string
}

export interface ErrorInfo {
  message: string
  type?: string | null
  stack_trace?: string | null
  fatal: boolean
  code?: string | null
}

export interface Event {
  client_id: string
  type: string
  created: number
  created_micros: number
  element: EventElement
  /**
   * Snapshot of the page when the event was emitted. Optional for backward
   * compatibility with the server (which accepts events without it) and for
   * non-browser SDKs that have no page context.
   */
  page?: Page | null
  error?: ErrorInfo | null
}

export interface Page {
  path?: string | null
  url?: string | null
  title?: string | null
}

export interface EventElement {
  id: string,
  type: string 
  content: EventContent[]
  extras: { [key: string]: string }
}

export interface EventContent {
  id: string
  type: string
  index: number | null
  percent: number | null
}

export interface Events {
  context: Context
  sent: number
  sent_micros: number
  events: Event[]
}
