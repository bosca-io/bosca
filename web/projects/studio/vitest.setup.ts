import { vi } from 'vitest'
import {
  ref,
  computed,
  reactive,
  watch,
  watchEffect,
  nextTick,
  onMounted,
  onUnmounted,
  onBeforeUnmount,
  toRaw,
  toRef,
  toRefs,
  unref,
  isRef,
  shallowRef,
  defineComponent,
} from 'vue'

vi.stubGlobal('ref', ref)
vi.stubGlobal('computed', computed)
vi.stubGlobal('reactive', reactive)
vi.stubGlobal('watch', watch)
vi.stubGlobal('watchEffect', watchEffect)
vi.stubGlobal('nextTick', nextTick)
vi.stubGlobal('onMounted', onMounted)
vi.stubGlobal('onUnmounted', onUnmounted)
vi.stubGlobal('onBeforeUnmount', onBeforeUnmount)
vi.stubGlobal('toRaw', toRaw)
vi.stubGlobal('toRef', toRef)
vi.stubGlobal('toRefs', toRefs)
vi.stubGlobal('unref', unref)
vi.stubGlobal('isRef', isRef)
vi.stubGlobal('shallowRef', shallowRef)
vi.stubGlobal('defineComponent', defineComponent)

vi.stubGlobal('useRoute', () => ({ path: '/', params: {}, query: {} }))
vi.stubGlobal('useRouter', () => ({ push: vi.fn(), replace: vi.fn() }))
vi.stubGlobal('useToast', () => ({
  success: vi.fn(),
  error: vi.fn(),
  warn: vi.fn(),
  info: vi.fn(),
  show: vi.fn(),
  showProgress: vi.fn(() => ({ update: vi.fn(), complete: vi.fn(), dismiss: vi.fn() })),
  showPersistent: vi.fn(),
  dismiss: vi.fn(),
  add: vi.fn(),
}))
vi.stubGlobal('useRuntimeConfig', () => ({ public: { imageBaseUrl: '' } }))
vi.stubGlobal('navigateTo', vi.fn())
vi.stubGlobal('definePageMeta', vi.fn())
vi.stubGlobal('onScopeDispose', (_fn: () => void) => {})
