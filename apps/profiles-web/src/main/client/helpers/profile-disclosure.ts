import type { ClientContext } from "@bosca/bml"

/** Coordinates native profile menus without scanning or initializing other instances. */
export function profileDisclosure(ctx: ClientContext) {
  const panel = ctx.root as HTMLDetailsElement
  ctx.on(panel, "toggle", event => {
    if (event.target === panel && panel.open) {
      document.dispatchEvent(new CustomEvent("profiles:menu-open", { detail: panel }))
    }
  })
  ctx.on<CustomEvent<HTMLDetailsElement>>(document, "profiles:menu-open", event => {
    if (event.detail !== panel) panel.open = false
  })
  ctx.on(document, "click", event => {
    if (event.target instanceof Node && !panel.contains(event.target)) panel.open = false
  })
  ctx.on<KeyboardEvent>(panel, "keydown", event => {
    if (event.key === "Escape" && panel.open) {
      event.preventDefault()
      event.stopPropagation()
      panel.open = false
      panel.querySelector("summary")?.focus()
    }
  })
}
