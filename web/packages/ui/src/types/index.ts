export type { Toast, ToastTone, ProgressHandle } from '../composables/useToast'

export interface BreadcrumbItem {
  label: string
  to?: string
}

export interface GlassTableColumn {
  key: string
  label: string
  width: string
  muted?: boolean
  align?: 'left' | 'center' | 'right'
}

export interface SelectOption {
  value: string
  label: string
  icon?: string
  disabled?: boolean
}

export interface OverflowMenuItem {
  id: string
  label: string
  icon?: string
  danger?: boolean
  disabled?: boolean
  separator?: boolean
}
