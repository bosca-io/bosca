export interface ContextMenuItem {
  id: string
  label: string
  icon?: string
  iconColor?: string
  disabled?: boolean
  danger?: boolean
  children?: readonly ContextMenuItem[]
}

export interface ContextMenuGroup {
  id?: string
  label?: string
  subtitle?: string
  avatar?: string
  items: ContextMenuItem[]
}

interface ContextMenuState {
  open: boolean
  x: number
  y: number
  groups: ContextMenuGroup[]
}

const state = reactive<ContextMenuState>({
  open: false,
  x: 0,
  y: 0,
  groups: [],
})

type SelectHandler = (id: string) => void
let currentHandler: SelectHandler | null = null

/**
 * Provides a global context menu that any component can trigger with
 * its own set of grouped items. The menu renders at cursor position
 * and routes selection back through the handler provided at open time.
 */
export function useContextMenu() {
  function open(e: MouseEvent, groups: ContextMenuGroup[], onSelect: SelectHandler) {
    e.preventDefault()
    state.x = e.clientX
    state.y = e.clientY
    state.groups = groups
    currentHandler = onSelect
    state.open = true
  }

  function close() {
    state.open = false
    currentHandler = null
  }

  function select(id: string) {
    currentHandler?.(id)
    close()
  }

  return {
    state: readonly(state),
    open,
    close,
    select,
  }
}
