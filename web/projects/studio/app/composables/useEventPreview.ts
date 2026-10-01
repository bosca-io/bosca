 
import type { TimeEvent } from '~/composables/useTimeEvents'

export type EventPreview = { type: 'image'; src: string } | { type: 'text'; text: string }

export function getEventPreview(event: TimeEvent): EventPreview | null {
  const attrs = event.attributes
  if (!attrs || typeof attrs !== 'object') return null

  const typeAttrs = event.type?.attributes || []

  for (const def of typeAttrs) {
    if (def.ui === 'IMAGE' || def.type === 'METADATA') {
      const val = attrs[def.key]
      if (!val) continue
      const metadataId = typeof val === 'string' ? val : val?.id
      if (!metadataId) continue
      const jpegKey = typeof val === 'object' ? val?.attributes?.jpeg?.large : null
      const src = jpegKey
        ? `/content/image/${metadataId}?key=${jpegKey}`
        : `/content/image/${metadataId}`
      return { type: 'image', src }
    }
  }

  for (const def of typeAttrs) {
    if (def.type === 'STRING') {
      const val = attrs[def.key]
      if (val != null && String(val).trim()) {
        return { type: 'text', text: String(val) }
      }
    }
  }

  return null
}

export function useEventHoverPreview() {
  const hoverEvent = ref<TimeEvent | null>(null)
  const hoverPreview = ref<EventPreview | null>(null)
  const mouseX = ref(0)
  const mouseY = ref(0)
  let timer: ReturnType<typeof setTimeout> | null = null

  function onEnter(e: MouseEvent, event: TimeEvent) {
    mouseX.value = e.clientX
    mouseY.value = e.clientY
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      hoverEvent.value = event
      hoverPreview.value = getEventPreview(event)
    }, 300)
  }

  function onMove(e: MouseEvent) {
    mouseX.value = e.clientX
    mouseY.value = e.clientY
  }

  function onLeave() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    hoverEvent.value = null
    hoverPreview.value = null
  }

  function dismiss() {
    onLeave()
  }

  onUnmounted(() => {
    if (timer) clearTimeout(timer)
  })

  return { hoverEvent, hoverPreview, mouseX, mouseY, onEnter, onMove, onLeave, dismiss }
}
