/* eslint-disable @typescript-eslint/no-explicit-any */
import type { CalendarRef } from '~/composables/useCalendar'

export interface CalendarEventData {
  id: string
  title: string
  description: string
  location: string
  allDay: boolean
  startsAt: string
  endsAt: string
  rrule: string | null
  originalEventId: string | null
  recurrenceId: string | null
  calendar: { metadataId: string; version: number }
}

export interface EventOccurrence {
  startsAt: string
  endsAt: string
  recurrenceId: string | null
  isRecurring: boolean
  isException: boolean
  event: CalendarEventData
}

export interface CalendarEventInput {
  metadataId: string
  version: number
  title: string
  description?: string
  location?: string
  allDay?: boolean
  startsAt: string
  endsAt: string
  rrule?: string | null
}

export interface OccurrenceInput {
  title: string
  description?: string
  location?: string
  allDay?: boolean
  startsAt: string
  endsAt: string
}

const GET_OCCURRENCES = `
  query GetCalendarOccurrences($calendars: [CalendarRef!]!, $from: DateTime!, $to: DateTime!) {
    calendars {
      occurrences(calendars: $calendars, from: $from, to: $to) {
        startsAt endsAt recurrenceId isRecurring isException
        event {
          id title description location allDay rrule originalEventId recurrenceId
          calendar { metadataId version }
        }
      }
    }
  }
`

const ADD_EVENT = `
  mutation AddCalendarEvent($event: CalendarEventInput!) {
    calendars { addEvent(event: $event) { id title startsAt endsAt rrule } }
  }
`

const EDIT_EVENT = `
  mutation EditCalendarEvent($id: UUID!, $event: CalendarEventInput!) {
    calendars { editEvent(id: $id, event: $event) { id title startsAt endsAt rrule } }
  }
`

const DELETE_EVENT = `
  mutation DeleteCalendarEvent($id: UUID!) {
    calendars { deleteEvent(id: $id) }
  }
`

const EDIT_OCCURRENCE = `
  mutation EditCalendarOccurrence($masterId: UUID!, $recurrenceId: DateTime!, $occurrence: OccurrenceInput!) {
    calendars { editOccurrence(masterId: $masterId, recurrenceId: $recurrenceId, occurrence: $occurrence) { id } }
  }
`

const CANCEL_OCCURRENCE = `
  mutation CancelCalendarOccurrence($masterId: UUID!, $recurrenceId: DateTime!) {
    calendars { cancelOccurrence(masterId: $masterId, recurrenceId: $recurrenceId) }
  }
`

const SPLIT_SERIES = `
  mutation SplitCalendarSeries($masterId: UUID!, $recurrenceId: DateTime!, $event: CalendarEventInput!) {
    calendars { splitSeriesAt(masterId: $masterId, recurrenceId: $recurrenceId, event: $event) { id } }
  }
`

const END_SERIES = `
  mutation EndCalendarSeries($masterId: UUID!, $recurrenceId: DateTime!) {
    calendars { endSeriesAt(masterId: $masterId, recurrenceId: $recurrenceId) { id } }
  }
`

export interface SyntheticEvent {
  id: string
  title: string
  description: string
  startsAt: string
  endsAt: string
  source: 'CAMPAIGN' | 'SCHEDULED_JOB' | 'SCHEDULED_PUBLISH'
  completed: boolean
}

const GET_SYNTHETIC_EVENTS = `
  query GetSyntheticEvents($from: DateTime!, $to: DateTime!) {
    calendars {
      scheduledContentEvents(from: $from, to: $to) { id title description startsAt endsAt source completed }
      scheduledJobEvents(from: $from, to: $to) { id title description startsAt endsAt source completed }
      scheduledPublishEvents(from: $from, to: $to) { id title description startsAt endsAt source completed }
    }
  }
`

export function useCalendarEvents(
  calendarRefs: Ref<CalendarRef[]>,
  from: Ref<string>,
  to: Ref<string>,
) {
  const gql = useGraphQL()
  const toast = useToast()

  const { data, refresh } = gql.useAsyncQuery<any>(
    'calendar-occurrences',
    GET_OCCURRENCES,
    { calendars: calendarRefs, from, to },
  )

  const occurrences = computed<EventOccurrence[]>(
    () => data.value?.calendars?.occurrences ?? [],
  )

  const syntheticEvents = ref<SyntheticEvent[]>([])

  async function refreshSynthetics() {
    try {
      const result = await gql.query<any>(GET_SYNTHETIC_EVENTS, { from: from.value, to: to.value })
      const content = result?.calendars?.scheduledContentEvents ?? []
      const jobs = result?.calendars?.scheduledJobEvents ?? []
      const publishes = result?.calendars?.scheduledPublishEvents ?? []
      syntheticEvents.value = [...content, ...jobs, ...publishes]
    } catch {
      syntheticEvents.value = []
    }
  }

  // Synthetic events have no reactive useAsyncData binding, so track the
  // visible date range explicitly: load them on mount and re-fetch whenever
  // the user navigates to a different month/week/day. Client-only — the
  // initial occurrences fetch already covers SSR for real events.
  if (import.meta.client) {
    watch([from, to], () => { refreshSynthetics() }, { immediate: true })
  }

  async function addEvent(input: CalendarEventInput): Promise<string | null> {
    try {
      const result = await gql.mutation<any>(ADD_EVENT, { event: input })
      const id = result?.calendars?.addEvent?.id ?? null
      await refresh()
      toast.add({ title: 'Event created' })
      return id
    } catch (e: any) {
      toast.add({ title: 'Failed to create event', description: e?.message, color: 'error' })
      throw e
    }
  }

  async function editEvent(id: string, input: CalendarEventInput) {
    try {
      await gql.mutation(EDIT_EVENT, { id, event: input })
      await refresh()
      toast.add({ title: 'Event updated' })
    } catch (e: any) {
      toast.add({ title: 'Failed to update event', description: e?.message, color: 'error' })
      throw e
    }
  }

  async function deleteEvent(id: string) {
    try {
      await gql.mutation(DELETE_EVENT, { id })
      await refresh()
      toast.add({ title: 'Event deleted' })
    } catch (e: any) {
      toast.add({ title: 'Failed to delete event', description: e?.message, color: 'error' })
      throw e
    }
  }

  async function editOccurrence(masterId: string, recurrenceId: string, input: OccurrenceInput) {
    try {
      await gql.mutation(EDIT_OCCURRENCE, { masterId, recurrenceId, occurrence: input })
      await refresh()
      toast.add({ title: 'Occurrence updated' })
    } catch (e: any) {
      toast.add({ title: 'Failed to update occurrence', description: e?.message, color: 'error' })
      throw e
    }
  }

  async function cancelOccurrence(masterId: string, recurrenceId: string) {
    try {
      await gql.mutation(CANCEL_OCCURRENCE, { masterId, recurrenceId })
      await refresh()
      toast.add({ title: 'Occurrence cancelled' })
    } catch (e: any) {
      toast.add({ title: 'Failed to cancel occurrence', description: e?.message, color: 'error' })
      throw e
    }
  }

  async function splitSeriesAt(masterId: string, recurrenceId: string, newEvent: CalendarEventInput) {
    try {
      await gql.mutation(SPLIT_SERIES, { masterId, recurrenceId, event: newEvent })
      await refresh()
      toast.add({ title: 'Series split' })
    } catch (e: any) {
      toast.add({ title: 'Failed to split series', description: e?.message, color: 'error' })
      throw e
    }
  }

  async function endSeriesAt(masterId: string, recurrenceId: string) {
    try {
      await gql.mutation(END_SERIES, { masterId, recurrenceId })
      await refresh()
      toast.add({ title: 'Series ended' })
    } catch (e: any) {
      toast.add({ title: 'Failed to end series', description: e?.message, color: 'error' })
      throw e
    }
  }

  async function moveEvent(eventId: string, newStart: string, newEnd: string) {
    const occ = occurrences.value.find((o) => o.event.id === eventId)
    if (!occ) return
    const evt = occ.event
    await editEvent(eventId, {
      metadataId: evt.calendar.metadataId,
      version: evt.calendar.version,
      title: evt.title,
      description: evt.description,
      location: evt.location,
      allDay: evt.allDay,
      startsAt: newStart,
      endsAt: newEnd,
      rrule: evt.rrule,
    })
  }

  async function refreshAll() {
    await Promise.all([refresh(), refreshSynthetics()])
  }

  return {
    occurrences,
    syntheticEvents,
    addEvent,
    editEvent,
    deleteEvent,
    editOccurrence,
    cancelOccurrence,
    splitSeriesAt,
    endSeriesAt,
    moveEvent,
    refresh: refreshAll,
  }
}
