import type { TweakValues } from '~~/shared/types'

const STORAGE_KEY = 'bosca:tweaks'

const TWEAK_DEFAULTS: TweakValues = {
  theme: 'dark',
  brand1: '#06b6d4',
  brand2: '#3b82f6',
  brandAccent: '#7c5cff',
  showCollab: true,
  collabWindow: { x: -1, y: -1, width: 340, height: 520 },
}

function loadTweaks(): TweakValues {
  if (!import.meta.client) return { ...TWEAK_DEFAULTS }
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...TWEAK_DEFAULTS }
    return { ...TWEAK_DEFAULTS, ...JSON.parse(raw) }
  } catch {
    return { ...TWEAK_DEFAULTS }
  }
}

function saveTweaks(tweaks: TweakValues) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(tweaks))
  } catch {
    // Storage full or unavailable
  }
}

let hydrated = false

export function useTweaks() {
  const tweaks = useState<TweakValues>('tweaks', () => ({ ...TWEAK_DEFAULTS }))

  if (import.meta.client && !hydrated) {
    hydrated = true
    const saved = loadTweaks()
    tweaks.value = saved
  }

  function setTweak(keyOrEdits: string | Partial<TweakValues>, val?: TweakValues[keyof TweakValues]) {
    if (typeof keyOrEdits === 'object' && keyOrEdits !== null) {
      tweaks.value = { ...tweaks.value, ...keyOrEdits }
    } else {
      tweaks.value = { ...tweaks.value, [keyOrEdits]: val }
    }
  }

  watch(
    () => [tweaks.value.theme, tweaks.value.brand1, tweaks.value.brand2, tweaks.value.brandAccent],
    () => {
      if (!import.meta.client) return
      document.documentElement.dataset.theme = tweaks.value.theme
      document.documentElement.style.setProperty('--brand-1', tweaks.value.brand1)
      document.documentElement.style.setProperty('--brand-2', tweaks.value.brand2)
      document.documentElement.style.setProperty('--brand-accent', tweaks.value.brandAccent)
    },
    { immediate: true },
  )

  if (import.meta.client) {
    watch(tweaks, (val) => saveTweaks(val), { deep: true })
  }

  return { tweaks, setTweak }
}
