import { snapToQuarter } from '~/utils/calendar'

export interface DragPreview {
  eventId: string
  mode: 'move' | 'resize'
  dayIndex: number
  startMinute: number
  endMinute: number
}

export interface DragResult {
  eventId: string
  startsAt: Date
  endsAt: Date
}

export function useCalendarDrag(
  slotHeight: number = 48,
  onComplete?: (result: DragResult) => void,
) {
  const dragging = ref(false)
  const dragPreview = ref<DragPreview | null>(null)

  let startX = 0
  let startY = 0
  let originDayIndex = 0
  let originStartMinute = 0
  let originEndMinute = 0
  let activeEventId = ''
  let activeMode: 'move' | 'resize' = 'move'
  let containerTop = 0

  function minuteFromY(y: number): number {
    const raw = (y / slotHeight) * 60
    return snapToQuarter(Math.max(0, Math.min(1440, Math.round(raw))))
  }

  function onPointerDown(
    e: PointerEvent,
    eventId: string,
    dayIndex: number,
    startMinute: number,
    endMinute: number,
    mode: 'move' | 'resize' = 'move',
    containerEl?: HTMLElement,
  ) {
    e.preventDefault()
    e.stopPropagation()

    activeEventId = eventId
    activeMode = mode
    originDayIndex = dayIndex
    originStartMinute = startMinute
    originEndMinute = endMinute
    startX = e.clientX
    startY = e.clientY
    containerTop = containerEl?.getBoundingClientRect().top ?? 0

    dragging.value = false
    dragPreview.value = null

    document.addEventListener('pointermove', onPointerMove)
    document.addEventListener('pointerup', onPointerUp)
  }

  function onPointerMove(e: PointerEvent) {
    const dx = e.clientX - startX
    const dy = e.clientY - startY

    if (!dragging.value && Math.abs(dx) < 3 && Math.abs(dy) < 3) return
    dragging.value = true

    if (activeMode === 'move') {
      const minuteDelta = snapToQuarter(Math.round((dy / slotHeight) * 60))
      const newStart = Math.max(0, originStartMinute + minuteDelta)
      const duration = originEndMinute - originStartMinute
      const newEnd = Math.min(1440, newStart + duration)

      dragPreview.value = {
        eventId: activeEventId,
        mode: 'move',
        dayIndex: originDayIndex,
        startMinute: newStart,
        endMinute: newEnd,
      }
    } else {
      const currentMinute = minuteFromY(e.clientY - containerTop)
      const newEnd = Math.max(originStartMinute + 15, currentMinute)

      dragPreview.value = {
        eventId: activeEventId,
        mode: 'resize',
        dayIndex: originDayIndex,
        startMinute: originStartMinute,
        endMinute: newEnd,
      }
    }
  }

  function onPointerUp() {
    document.removeEventListener('pointermove', onPointerMove)
    document.removeEventListener('pointerup', onPointerUp)

    if (dragging.value && dragPreview.value && onComplete) {
      const preview = dragPreview.value
      const baseDate = new Date()
      baseDate.setHours(0, 0, 0, 0)

      const startsAt = new Date(baseDate)
      startsAt.setMinutes(preview.startMinute)

      const endsAt = new Date(baseDate)
      endsAt.setMinutes(preview.endMinute)

      onComplete({
        eventId: preview.eventId,
        startsAt,
        endsAt,
      })
    }

    dragging.value = false
    dragPreview.value = null
  }

  function cancel() {
    document.removeEventListener('pointermove', onPointerMove)
    document.removeEventListener('pointerup', onPointerUp)
    dragging.value = false
    dragPreview.value = null
  }

  onUnmounted(() => {
    document.removeEventListener('pointermove', onPointerMove)
    document.removeEventListener('pointerup', onPointerUp)
  })

  return {
    dragging,
    dragPreview,
    onPointerDown,
    cancel,
  }
}
