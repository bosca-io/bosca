/* eslint-disable @typescript-eslint/no-explicit-any */

export interface CalendarData {
  metadataId: string
  version: number
  color: string
  description: string
  metadata: { id: string; name: string }
}

export interface CalendarRef {
  metadataId: string
  version: number
}

const GET_CALENDARS = `
  query GetCalendars {
    calendars {
      all {
        metadataId
        version
        color
        description
        metadata { id name }
      }
    }
  }
`

const ADD_CALENDAR_METADATA = `
  mutation AddCalendarMetadata($metadata: MetadataInput!) {
    content {
      metadata {
        add(metadata: $metadata, setReady: true) { id }
      }
    }
  }
`

const ADD_CALENDAR = `
  mutation AddCalendar($ref: CalendarRef!, $calendar: CalendarInput!) {
    calendars {
      addCalendar(ref: $ref, calendar: $calendar) {
        metadataId version color description
      }
    }
  }
`

const EDIT_CALENDAR = `
  mutation EditCalendar($ref: CalendarRef!, $calendar: CalendarInput!) {
    calendars {
      editCalendar(ref: $ref, calendar: $calendar) {
        metadataId version color description
      }
    }
  }
`

const DELETE_METADATA = `
  mutation DeleteCalendarMetadata($metadataId: UUID!) {
    content { metadata { delete(metadataId: $metadataId) } }
  }
`

const RENAME_METADATA = `
  mutation RenameCalendarMetadata($id: UUID!, $metadata: MetadataInput!) {
    content { metadata { edit(id: $id, metadata: $metadata) { id } } }
  }
`

export function useCalendar() {
  const gql = useGraphQL()
  const toast = useToast()

  const calendars = ref<CalendarData[]>([])

  async function refresh() {
    try {
      const result = await gql.query<any>(GET_CALENDARS)
      calendars.value = result?.calendars?.all ?? []
    } catch (e) {
      console.error('Failed to fetch calendars', e)
    }
  }

  onMounted(() => { refresh() })

  const STORAGE_KEY = 'bosca-calendar-visible'

  function loadVisibility(): Set<string> {
    if (import.meta.server) return new Set()
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      if (stored) return new Set(JSON.parse(stored))
    } catch { /* ignore */ }
    return new Set()
  }

  function saveVisibility(ids: Set<string>) {
    if (import.meta.server) return
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(Array.from(ids)))
    } catch { /* ignore */ }
  }

  const visibleCalendarIds = ref<Set<string>>(new Set())
  let visibilityInitialized = false

  watch(calendars, (cals) => {
    if (cals.length === 0) return
    if (!visibilityInitialized) {
      visibilityInitialized = true
      const stored = loadVisibility()
      if (stored.size > 0) {
        visibleCalendarIds.value = stored
      } else {
        visibleCalendarIds.value = new Set(cals.map((c) => c.metadataId))
        saveVisibility(visibleCalendarIds.value)
      }
    }
  })

  function toggleCalendar(metadataId: string) {
    const next = new Set(visibleCalendarIds.value)
    if (next.has(metadataId)) next.delete(metadataId)
    else next.add(metadataId)
    visibleCalendarIds.value = next
    saveVisibility(next)
  }

  // Events are fetched for every calendar and visibility is applied
  // client-side (see `mappedEvents` in the calendar page), so toggling a
  // calendar on/off is instant and never triggers a network round-trip.
  const allCalendarRefs = computed<CalendarRef[]>(() =>
    calendars.value.map((c) => ({ metadataId: c.metadataId, version: c.version })),
  )

  async function addCalendar(name: string, color: string, description: string = '') {
    try {
      const metaResult = await gql.mutation<any>(ADD_CALENDAR_METADATA, {
        metadata: {
          name,
          languageTag: 'en',
          contentType: 'bosca/v-calendar',
        },
      })
      const metadataId = metaResult?.content?.metadata?.add?.id
      if (!metadataId) throw new Error('Failed to create calendar metadata')

      await gql.mutation(ADD_CALENDAR, {
        ref: { metadataId, version: 1 },
        calendar: { color, description },
      })

      const next = new Set(visibleCalendarIds.value)
      next.add(metadataId)
      visibleCalendarIds.value = next
      saveVisibility(next)
      await refresh()
      toast.add({ title: 'Calendar created' })
    } catch (e: any) {
      toast.add({ title: 'Failed to create calendar', description: e?.message, color: 'error' })
      throw e
    }
  }

  async function editCalendar(calRef: CalendarRef, input: { color: string; description?: string }, newName?: string) {
    try {
      await gql.mutation(EDIT_CALENDAR, { ref: calRef, calendar: input })
    } catch (e: any) {
      toast.add({ title: 'Failed to update calendar', description: e?.message, color: 'error' })
      throw e
    }
    if (newName) {
      try {
        await gql.mutation(RENAME_METADATA, { id: calRef.metadataId, metadata: { name: newName } })
      } catch {
        // rename is best-effort
      }
    }
    await refresh()
    toast.add({ title: 'Calendar updated' })
  }

  async function deleteCalendar(metadataId: string) {
    try {
      await gql.mutation(DELETE_METADATA, { metadataId })
      const next = new Set(visibleCalendarIds.value)
      next.delete(metadataId)
      visibleCalendarIds.value = next
      saveVisibility(next)
      await refresh()
      toast.add({ title: 'Calendar deleted' })
    } catch (e: any) {
      toast.add({ title: 'Failed to delete calendar', description: e?.message, color: 'error' })
      throw e
    }
  }

  return {
    calendars,
    visibleCalendarIds,
    allCalendarRefs,
    toggleCalendar,
    addCalendar,
    editCalendar,
    deleteCalendar,
    refresh,
  }
}
