/**
 * Generates a stable, human-readable identifier for a DOM element by building
 * a CSS-like selector path from the element to the document root. Used to
 * consistently identify elements across page loads for heat map aggregation
 * and interaction replay.
 *
 * Priority order for identifying an element at each level:
 * 1. `data-ba-id` attribute (explicit instrumentation)
 * 2. `id` attribute
 * 3. Tag name with nth-of-type index
 */
export function getElementIdentifier(element: Element): string {
  const parts: string[] = []
  let current: Element | null = element

  while (current && current !== document.documentElement) {
    const analyticsId = current.getAttribute('data-ba-id')
    if (analyticsId) {
      parts.unshift(`[data-ba-id="${analyticsId}"]`)
      break
    }

    if (current.id) {
      parts.unshift(`#${current.id}`)
      break
    }

    const tag = current.tagName.toLowerCase()
    const parentEl: Element | null = current.parentElement
    if (parentEl) {
      let sameTagCount = 0
      let indexAmongSameTag = 0
      const children = parentEl.children
      for (let i = 0; i < children.length; i++) {
        if (children[i]!.tagName === current.tagName) {
          sameTagCount++
          if (children[i]! === current) indexAmongSameTag = sameTagCount
        }
      }
      if (sameTagCount > 1) {
        parts.unshift(`${tag}:nth-of-type(${indexAmongSameTag})`)
      } else {
        parts.unshift(tag)
      }
    } else {
      parts.unshift(tag)
    }

    current = parentEl
  }

  return parts.join(' > ')
}

/**
 * Returns a short, descriptive type string for a DOM element based on its
 * tag name and relevant attributes. Provides semantic meaning for analytics
 * events so downstream consumers can filter and group by element category
 * (e.g. "button", "link", "input:email", "image").
 */
export function getElementType(element: Element): string {
  const explicitType = element.getAttribute('data-ba-element-type')?.trim()
  if (explicitType) return explicitType

  const tag = element.tagName.toLowerCase()

  if (tag === 'a') return 'link'
  if (tag === 'button' || (element as HTMLButtonElement).type === 'submit') return 'button'
  if (tag === 'input') {
    const inputType = (element as HTMLInputElement).type || 'text'
    return `input:${inputType}`
  }
  if (tag === 'select') return 'select'
  if (tag === 'textarea') return 'textarea'
  if (tag === 'img') return 'image'
  if (tag === 'video') return 'video'
  if (tag === 'audio') return 'audio'
  if (tag === 'form') return 'form'

  return tag
}

/**
 * Extracts human-readable text from an element for display in analytics
 * dashboards. Truncates to a maximum length to avoid bloating event payloads
 * with large text content.
 */
export function getElementText(element: Element, maxLength: number = 100): string {
  if (element.hasAttribute('data-ba-no-text')) return ''

  const label = element.getAttribute('aria-label')
  if (label) return label.length > maxLength ? label.substring(0, maxLength) : label

  const title = element.getAttribute('title')
  if (title) return title.length > maxLength ? title.substring(0, maxLength) : title

  const alt = element.getAttribute('alt')
  if (alt) return alt.length > maxLength ? alt.substring(0, maxLength) : alt

  const text = (element.textContent || '').trim()
  return text.length > maxLength ? text.substring(0, maxLength) : text
}
