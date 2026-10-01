export interface NavItem {
  id: string
  label: string
  icon: string
  count?: number
}

export interface NavGroup {
  group: string
  items: NavItem[]
  admin?: boolean
}

export interface Subsystem {
  id: string
  label: string
  sub: string
  icon: string
  accent: string
  nav: NavGroup[]
  /**
   * Name of the server feature flag (`server { features { … } }`) this subsystem
   * requires. When set and the deployment has that flag off, the subsystem is
   * hidden from navigation and its routes are blocked. Omit for always-available
   * subsystems.
   */
  requiresFeature?: string
}

export interface CollabWindowState {
  x: number
  y: number
  width: number
  height: number
}

export interface TweakValues {
  theme: string
  brand1: string
  brand2: string
  brandAccent: string
  showCollab: boolean
  collabWindow: CollabWindowState
}
