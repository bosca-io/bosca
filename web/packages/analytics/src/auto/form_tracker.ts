import { AnalyticEventType } from '../event'
import { logEvent, logEventBeacon } from '../sink'
import { extractAttributes } from './attribute_extractor'
import { getElementIdentifier } from './element_identifier'

/**
 * Configuration for form interaction tracking.
 */
export interface FormTrackerOptions {
  /** Whether to track individual field focus/blur events in addition to submissions */
  trackFieldInteractions: boolean
  /** Whether to track form abandonment (started filling but never submitted) */
  trackAbandonment: boolean
}

const DEFAULT_OPTIONS: FormTrackerOptions = {
  trackFieldInteractions: true,
  trackAbandonment: true,
}

/**
 * Tracks form submissions and field-level interactions to understand how
 * users complete (or abandon) forms. Captures which fields receive focus,
 * how long users spend on each field, and whether the form was ultimately
 * submitted. Field values are never recorded — only field identifiers and
 * timing data — to preserve user privacy.
 */
export class FormTracker {
  private options: FormTrackerOptions
  private submitHandler: ((e: Event) => void) | null = null
  private focusHandler: ((e: FocusEvent) => void) | null = null
  private blurHandler: ((e: FocusEvent) => void) | null = null
  private fieldFocusTimes: Map<string, number> = new Map()
  private interactedForms: Set<string> = new Set()
  private submittedForms: Set<string> = new Set()
  private unloadHandler: (() => void) | null = null

  constructor(options: Partial<FormTrackerOptions> = {}) {
    this.options = { ...DEFAULT_OPTIONS, ...options }
  }

  /** Attaches form submission and field interaction listeners. */
  start() {
    if (typeof document === 'undefined') return

    this.submitHandler = (e: Event) => this.onSubmit(e)
    document.addEventListener('submit', this.submitHandler, { capture: true })

    if (this.options.trackFieldInteractions) {
      this.focusHandler = (e: FocusEvent) => this.onFieldFocus(e)
      this.blurHandler = (e: FocusEvent) => this.onFieldBlur(e)
      document.addEventListener('focusin', this.focusHandler, { passive: true })
      document.addEventListener('focusout', this.blurHandler, { passive: true })
    }

    if (this.options.trackAbandonment) {
      this.unloadHandler = () => this.onUnload()
      window.addEventListener('beforeunload', this.unloadHandler)
    }
  }

  /** Removes all listeners. */
  stop() {
    if (typeof document === 'undefined') return

    if (this.submitHandler) {
      document.removeEventListener('submit', this.submitHandler, { capture: true } as EventListenerOptions)
      this.submitHandler = null
    }
    if (this.focusHandler) {
      document.removeEventListener('focusin', this.focusHandler)
      this.focusHandler = null
    }
    if (this.blurHandler) {
      document.removeEventListener('focusout', this.blurHandler)
      this.blurHandler = null
    }
    if (this.unloadHandler) {
      window.removeEventListener('beforeunload', this.unloadHandler)
      this.unloadHandler = null
    }
  }

  private onSubmit(e: Event) {
    const form = e.target as HTMLFormElement
    if (!form || form.tagName !== 'FORM') return

    const formId = getElementIdentifier(form)
    this.submittedForms.add(formId)

    const { extras: extracted, content } = extractAttributes(form)

    logEvent({
      type: AnalyticEventType.interaction,
      element: {
        id: formId,
        type: 'form_submit',
        extras: {
          form_action: form.action || '',
          form_method: form.method || 'get',
          form_name: form.name || form.id || '',
          path: window.location.pathname,
          ...extracted,
        },
        content: content.length > 0 ? content : undefined,
      },
    }).catch((e) => console.error('failed to track form submit:', e))
  }

  private isFormField(element: Element): boolean {
    const tag = element.tagName.toLowerCase()
    return tag === 'input' || tag === 'textarea' || tag === 'select'
  }

  private onFieldFocus(e: FocusEvent) {
    const target = e.target as Element
    if (!target || !this.isFormField(target)) return

    const fieldId = getElementIdentifier(target)
    this.fieldFocusTimes.set(fieldId, Date.now())

    const form = target.closest('form')
    if (form) {
      this.interactedForms.add(getElementIdentifier(form))
    }

    const { extras: extracted } = extractAttributes(target)

    logEvent({
      type: AnalyticEventType.interaction,
      element: {
        id: fieldId,
        type: 'field_focus',
        extras: {
          field_type: (target as HTMLInputElement).type || target.tagName.toLowerCase(),
          field_name: (target as HTMLInputElement).name || '',
          path: window.location.pathname,
          ...extracted,
        },
      },
    }).catch((e) => console.error('failed to track field focus:', e))
  }

  private onFieldBlur(e: FocusEvent) {
    const target = e.target as Element
    if (!target || !this.isFormField(target)) return

    const fieldId = getElementIdentifier(target)
    const focusTime = this.fieldFocusTimes.get(fieldId)
    const dwellMs = focusTime ? Date.now() - focusTime : 0
    this.fieldFocusTimes.delete(fieldId)

    const { extras: extracted } = extractAttributes(target)

    logEvent({
      type: AnalyticEventType.interaction,
      element: {
        id: fieldId,
        type: 'field_blur',
        extras: {
          field_type: (target as HTMLInputElement).type || target.tagName.toLowerCase(),
          field_name: (target as HTMLInputElement).name || '',
          dwell_ms: String(dwellMs),
          path: window.location.pathname,
          ...extracted,
        },
      },
    }).catch((e) => console.error('failed to track field blur:', e))
  }

  private onUnload() {
    for (const formId of this.interactedForms) {
      if (!this.submittedForms.has(formId)) {
        logEventBeacon({
          type: AnalyticEventType.interaction,
          element: {
            id: formId,
            type: 'form_abandon',
            extras: {
              path: window.location.pathname,
            },
          },
        })
      }
    }
  }
}
