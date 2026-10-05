/* eslint-disable @typescript-eslint/no-explicit-any */

export interface TimeEventTypeAttribute {
  key: string
  name: string
  description: string
  type: string
  ui: string
  list: boolean
  configuration: any
  supplementaryKey: string | null
}

export interface TimeEventType {
  id: string
  name: string
  description: string
  schema: any
  configuration: any
  attributes: TimeEventTypeAttribute[]
}

export interface TimeEvent {
  id: string
  metadataId: string
  metadataVersion: number
  type: TimeEventType
  startOffsetMs: number
  endOffsetMs: number | null
  sort: number
  attributes: any
  created: string
  modified: string
  durationMs: number | null
}

export interface TimeEventInput {
  type: string
  startOffsetMs: number
  endOffsetMs?: number | null
  sort?: number | null
  attributes?: any
}

export interface TypeGroup {
  type: TimeEventType
  events: TimeEvent[]
}

const GET_TIME_EVENT_TYPES = `
  query GetTimeEventTypesFromComposable {
    timeEventTypes {
      id
      name
      description
      schema
      configuration
      attributes {
        key
        name
        description
        type
        ui
        list
        configuration
        supplementaryKey
      }
    }
  }
`

const GET_TIME_EVENTS = `
  query GetTimeEvents($id: UUID!) {
    content {
      metadata(id: $id) {
        timeEvents {
          id
          metadataId
          metadataVersion
          type {
            id
            name
            description
            schema
            configuration
            attributes {
              key
              name
              description
              type
              ui
              list
              configuration
              supplementaryKey
            }
          }
          startOffsetMs
          endOffsetMs
          sort
          attributes
          created
          modified
          durationMs
        }
      }
    }
  }
`

const ADD_TIME_EVENT = `
  mutation AddTimeEvent($metadataId: UUID!, $metadataVersion: Int!, $timeEvent: TimeEventInput!) {
    timeEvents {
      add(metadataId: $metadataId, metadataVersion: $metadataVersion, timeEvent: $timeEvent) {
        id
        startOffsetMs
        endOffsetMs
        sort
        attributes
        type { id name }
      }
    }
  }
`

const EDIT_TIME_EVENT = `
  mutation EditTimeEvent($id: UUID!, $timeEvent: TimeEventInput!) {
    timeEvents {
      edit(id: $id, timeEvent: $timeEvent) {
        id
        startOffsetMs
        endOffsetMs
        sort
        attributes
        type { id name }
      }
    }
  }
`

const DELETE_TIME_EVENT = `
  mutation DeleteTimeEvent($id: UUID!) {
    timeEvents {
      delete(id: $id)
    }
  }
`

const DELETE_TIME_EVENTS = `
  mutation DeleteTimeEvents($ids: [UUID!]!) {
    timeEvents {
      deleteAll(ids: $ids)
    }
  }
`

const EDIT_METADATA_RELATIONSHIP_ATTRIBUTES = `
  mutation EditMetadataRelationshipAttributes($timeEventId: UUID!, $metadataId: UUID!, $relationship: String!, $attributes: JSON!) {
    timeEvents {
      editMetadataRelationshipAttributes(timeEventId: $timeEventId, metadataId: $metadataId, relationship: $relationship, attributes: $attributes)
    }
  }
`

export function useTimeEvents(metadataId: Ref<string>, metadataVersion: Ref<number>) {
  const toast = useToast()
  const gql = useGraphQL()

  const { data: typesData, refresh: refreshTypes } = gql.useAsyncQuery<any>(
    'time-event-types',
    GET_TIME_EVENT_TYPES,
  )

  const { data: eventsData, refresh: refreshEvents } = gql.useAsyncQuery<any>(
    `time-events-${metadataId.value}`,
    GET_TIME_EVENTS,
    { id: metadataId },
  )

  const selectedEventId = ref<string | null>(null)
  const activeTypeFilter = ref<string | null>(null)
  const currentTimeMs = ref(0)
  const durationMs = ref(0)

  const eventTypes = computed<TimeEventType[]>(
    () => typesData.value?.timeEventTypes || [],
  )

  const events = computed<TimeEvent[]>(
    () => eventsData.value?.content?.metadata?.timeEvents || [],
  )

  const eventsByType = computed<Map<string, TimeEvent[]>>(() => {
    const map = new Map<string, TimeEvent[]>()
    for (const evt of events.value) {
      const typeId = evt.type.id
      if (!map.has(typeId)) map.set(typeId, [])
      map.get(typeId)!.push(evt)
    }
    for (const [, evts] of map) {
      evts.sort((a, b) => a.startOffsetMs - b.startOffsetMs)
    }
    return map
  })

  const visibleTypeGroups = computed<TypeGroup[]>(() => {
    return eventTypes.value
      .filter((t) => !activeTypeFilter.value || t.id === activeTypeFilter.value)
      .map((t) => ({
        type: t,
        events: eventsByType.value.get(t.id) || [],
      }))
  })

  const selectedEvent = computed<TimeEvent | null>(() => {
    if (!selectedEventId.value) return null
    return events.value.find((e) => e.id === selectedEventId.value) || null
  })

  async function addEvent(input: TimeEventInput, options?: { silent?: boolean }) {
    try {
      await gql.mutation(ADD_TIME_EVENT, {
        metadataId: metadataId.value,
        metadataVersion: metadataVersion.value,
        timeEvent: input,
      })
      if (!options?.silent) {
        await refreshEvents()
        toast.add({ title: 'Event added' })
      }
    } catch (e: any) {
      if (!options?.silent) {
        toast.add({ title: 'Error adding event', description: e?.message, color: 'error' })
      }
      throw e
    }
  }

  async function editEvent(id: string, input: TimeEventInput, options?: { silent?: boolean }) {
    try {
      await gql.mutation(EDIT_TIME_EVENT, { id, timeEvent: input })
      if (!options?.silent) {
        await refreshEvents()
        toast.add({ title: 'Event updated' })
      }
    } catch (e: any) {
      if (!options?.silent) {
        toast.add({ title: 'Error updating event', description: e?.message, color: 'error' })
      }
      throw e
    }
  }

  async function deleteEvent(id: string) {
    try {
      await gql.mutation(DELETE_TIME_EVENT, { id })
      if (selectedEventId.value === id) {
        selectedEventId.value = null
      }
      await refreshEvents()
      toast.add({ title: 'Event deleted' })
    } catch (e: any) {
      toast.add({ title: 'Error deleting event', description: e?.message, color: 'error' })
    }
  }

  async function deleteEvents(ids: string[]): Promise<number> {
    const result = await gql.mutation<any>(DELETE_TIME_EVENTS, { ids })
    const deleted = result?.timeEvents?.deleteAll ?? 0
    if (selectedEventId.value && ids.includes(selectedEventId.value)) {
      selectedEventId.value = null
    }
    await refreshEvents()
    return deleted
  }

  async function editMetadataRelationshipAttributes(
    timeEventId: string,
    relMetadataId: string,
    relationship: string,
    attributes: any,
  ) {
    try {
      await gql.mutation(EDIT_METADATA_RELATIONSHIP_ATTRIBUTES, {
        timeEventId,
        metadataId: relMetadataId,
        relationship,
        attributes,
      })
    } catch (e: any) {
      toast.add({
        title: 'Error updating relationship attributes',
        description: e?.message,
        color: 'error',
      })
      throw e
    }
  }

  async function refresh() {
    await Promise.all([refreshTypes(), refreshEvents()])
  }

  return {
    eventTypes,
    events,
    eventsByType,
    visibleTypeGroups,
    selectedEvent,
    selectedEventId,
    activeTypeFilter,
    currentTimeMs,
    durationMs,
    addEvent,
    editEvent,
    deleteEvent,
    deleteEvents,
    editMetadataRelationshipAttributes,
    refresh,
  }
}
