/**
 * DOM updates shared by declarative actions and `IslandContext.replace`.
 *
 * A server-rendered element marked with `data-bml-preserve="stable-key"` keeps its DOM identity
 * when the next fragment contains an element with the same key and tag. BML refreshes the root
 * element's server-owned attributes, but retains its child DOM exactly. This is useful for append
 * flows, media, form controls, and client-enhanced regions that should not restart when an island
 * re-renders.
 */

export const BML_PRESERVE_ATTRIBUTE = "data-bml-preserve"
export const BML_RETAINED_ATTRIBUTE = "data-bml-retained"
export const BML_ADDED_ATTRIBUTE = "data-bml-added"
export const BML_UPDATE_EVENT = "bml:update"

export interface IslandUpdate {
  /** Region whose children were updated. */
  readonly target: Element
  /** Newly rendered preservation roots (keys that did not exist before the update). */
  readonly added: readonly HTMLElement[]
  /** Existing preservation roots retained by the update. */
  readonly retained: readonly HTMLElement[]
}

interface PreserveSlot {
  readonly marker: string
  readonly element: HTMLElement
}

/** Parse trusted server HTML, optionally retain matching preservation roots, and replace [target]'s children. */
export function replaceChildrenFromHtml(target: Element, html: string, preserve = true): IslandUpdate {
  const owner = target.ownerDocument
  const parsed = new DOMParser().parseFromString(html, "text/html")
  const currentByKey = new Map<string, HTMLElement>()
  if (preserve) {
    for (const element of preservationRoots(target)) {
      const key = preserveKey(element)
      if (key && !currentByKey.has(key)) currentByKey.set(key, element)
    }
  }

  const slots: PreserveSlot[] = []
  const addedKeys = new Set<string>()
  for (const next of preservationRoots(parsed.body)) {
    const key = preserveKey(next)
    if (!key) continue
    const current = currentByKey.get(key)
    if (!current || !sameElementKind(current, next)) {
      addedKeys.add(key)
      continue
    }

    syncAttributes(current, next)
    current.setAttribute(BML_RETAINED_ATTRIBUTE, "")
    const marker = `bml-preserve:${slots.length}`
    slots.push({ marker, element: current })
    next.replaceWith(parsed.createComment(marker))
  }

  const active = owner.activeElement instanceof HTMLElement ? owner.activeElement : null
  target.replaceChildren(
    ...Array.from(parsed.body.childNodes).map((node) => owner.importNode(node, true)),
  )

  if (slots.length > 0) {
    const byMarker = new Map(slots.map((slot) => [slot.marker, slot.element]))
    const walker = owner.createTreeWalker(target, NodeFilter.SHOW_COMMENT)
    const markers: Comment[] = []
    while (walker.nextNode()) markers.push(walker.currentNode as Comment)
    for (const marker of markers) {
      const preserved = byMarker.get(marker.data)
      if (preserved) marker.replaceWith(preserved)
    }
  }

  // Detaching a focused preserved node can clear focus even when it is reinserted synchronously.
  // Put it back without scrolling so an expanding list stays anchored at the newly inserted rows.
  if (active?.isConnected) active.focus({ preventScroll: true })

  const added = preservationRoots(target).filter((element) => {
    const key = preserveKey(element)
    return key != null && addedKeys.has(key)
  })
  for (const element of added) element.setAttribute(BML_ADDED_ATTRIBUTE, "")
  return { target, added, retained: slots.map((slot) => slot.element) }
}

/** Notify a mounted island after bindings for a DOM update are ready. */
export function notifyIslandUpdate(update: IslandUpdate): void {
  update.target.dispatchEvent(
    new CustomEvent<IslandUpdate>(BML_UPDATE_EVENT, { bubbles: true, detail: update }),
  )
}

/** Only outermost preservation roots participate; retaining a parent already retains its subtree. */
function preservationRoots(scope: ParentNode): HTMLElement[] {
  const elements = Array.from(
    scope.querySelectorAll<HTMLElement>(`[${BML_PRESERVE_ATTRIBUTE}]`),
  )
  const candidates = new Set(elements)
  return elements.filter((element) => {
    let parent = element.parentElement
    while (parent && parent !== scope) {
      if (candidates.has(parent)) return false
      parent = parent.parentElement
    }
    return true
  })
}

function preserveKey(element: HTMLElement): string | null {
  const key = element.getAttribute(BML_PRESERVE_ATTRIBUTE)?.trim()
  return key ? key : null
}

function sameElementKind(current: HTMLElement, next: HTMLElement): boolean {
  return current.localName === next.localName && current.namespaceURI === next.namespaceURI
}

/**
 * The preserved subtree remains client-owned, while its root attributes remain server-owned.
 * Runtime binding/mount markers are exceptions: their listeners and setup are still attached.
 */
function syncAttributes(current: HTMLElement, next: HTMLElement): void {
  const runtimeAttributes = new Map<string, string>()
  for (const name of ["data-bml-action-bound"]) {
    const value = current.getAttribute(name)
    if (value != null) runtimeAttributes.set(name, value)
  }

  for (const attribute of Array.from(current.attributes)) {
    if (!next.hasAttribute(attribute.name) && !runtimeAttributes.has(attribute.name)) {
      current.removeAttribute(attribute.name)
    }
  }
  for (const attribute of Array.from(next.attributes)) {
    current.setAttribute(attribute.name, attribute.value)
  }
  for (const [name, value] of runtimeAttributes) current.setAttribute(name, value)
}
