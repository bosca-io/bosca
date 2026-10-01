<script lang="ts" setup>
/* eslint-disable @typescript-eslint/no-explicit-any */

export type EventScope = 'this' | 'following' | 'all'

export interface EventParticipant {
  profileId: string
  profileName: string
  role: string
  status: string
}

export interface ParticipantChanges {
  added: EventParticipant[]
  removed: string[]  // profileIds
}

export interface EventDraft {
  id?: string
  metadataId: string
  version: number
  title: string
  description: string
  location: string
  allDay: boolean
  startsAt: string
  endsAt: string
  rrule?: string | null
  isRecurring?: boolean
  isException?: boolean
  recurrenceId?: string | null
  readonly?: boolean
  participants?: EventParticipant[]
  originalParticipantIds?: string[]
}

const props = defineProps<{
  open: boolean
  event: EventDraft | null
  saving: boolean
  deleting: boolean
}>()

const emit = defineEmits<{
  save: [event: EventDraft, scope: EventScope]
  remove: [event: EventDraft, scope: EventScope]
  close: []
}>()

const title = ref('')
const description = ref('')
const location = ref('')
const allDay = ref(false)
const startsAt = ref('')
const endsAt = ref('')
const rrule = ref<string | null>(null)

const deleteConfirmOpen = ref(false)
const recurScopeOpen = ref(false)
const recurScopeAction = ref<'save' | 'delete'>('save')

// Participants
interface Participant { profileId: string; profileName: string; role: string; status: string }
const participants = ref<Participant[]>([])
const originalParticipantIds = ref<Set<string>>(new Set())
const participantSearchOpen = ref(false)

const gql = useGraphQL()
const { searchProfiles } = useProfileSearch()

const GET_PARTICIPANTS = `
  query GetEventParticipants($eventId: UUID!) {
    calendars { event(id: $eventId) { participants { profileId role status } } }
  }
`

const GET_PROFILE = `
  query GetProfileName($id: UUID!) {
    profiles { profile(id: $id) { id name slug } }
  }
`
async function loadParticipants() {
  if (!props.event?.id) return
  try {
    const result = await gql.query<any>(GET_PARTICIPANTS, { eventId: props.event.id })
    const raw = result?.calendars?.event?.participants ?? []
    const loaded: Participant[] = raw.map((p: any) => ({
      profileId: p.profileId,
      profileName: p.profileId,
      role: p.role || 'attendee',
      status: p.status || 'pending',
    }))
    originalParticipantIds.value = new Set(loaded.map(p => p.profileId))
    participants.value = loaded

    for (const p of loaded) {
      try {
        const profileResult = await gql.query<any>(GET_PROFILE, { id: p.profileId })
        const profile = profileResult?.profiles?.profile
        if (profile) {
          p.profileName = profile.name || profile.slug || p.profileId
        }
      } catch { /* keep profileId as fallback */ }
    }
    participants.value = [...loaded]
  } catch { participants.value = [] }
}

const lastSearchResults = ref<{ value: string; label: string }[]>([])

async function onProfileSearchWrapped(query: string) {
  const results = await searchProfiles(query)
  lastSearchResults.value = results
  return results
}

function onAddParticipant(profileId: string | string[] | null | undefined) {
  if (typeof profileId !== 'string') return
  if (!profileId) return
  if (participants.value.some(p => p.profileId === profileId)) return
  const label = lastSearchResults.value.find(o => o.value === profileId)?.label || profileId
  participants.value = [...participants.value, { profileId, profileName: label, role: 'attendee', status: 'pending' }]
  participantSearchOpen.value = false
}

function onRemoveParticipant(profileId: string) {
  participants.value = participants.value.filter(p => p.profileId !== profileId)
}

const isNew = computed(() => !props.event?.id)
const isRecurring = computed(() => props.event?.isRecurring === true || !!rrule.value)
const isReadonly = computed(() => props.event?.readonly === true)

function isoToLocal(iso: string): string {
  const d = new Date(iso)
  const y = d.getFullYear()
  const m = (d.getMonth() + 1).toString().padStart(2, '0')
  const day = d.getDate().toString().padStart(2, '0')
  const h = d.getHours().toString().padStart(2, '0')
  const min = d.getMinutes().toString().padStart(2, '0')
  return `${y}-${m}-${day}T${h}:${min}`
}

watch(() => props.event, (evt) => {
  if (!evt) return
  title.value = evt.title
  description.value = evt.description
  location.value = evt.location
  allDay.value = evt.allDay
  startsAt.value = isoToLocal(evt.startsAt)
  endsAt.value = isoToLocal(evt.endsAt)
  rrule.value = evt.rrule ?? null
  participants.value = []
  participantSearchOpen.value = false
  if (evt.id) loadParticipants()
}, { immediate: true })

function onSave() {
  if (isRecurring.value && !isNew.value) {
    recurScopeAction.value = 'save'
    recurScopeOpen.value = true
    return
  }
  doSave('all')
}

function onDelete() {
  if (isRecurring.value) {
    recurScopeAction.value = 'delete'
    recurScopeOpen.value = true
    return
  }
  deleteConfirmOpen.value = true
}

function doSave(scope: EventScope) {
  recurScopeOpen.value = false
  const draft: EventDraft = {
    ...props.event!,
    title: title.value,
    description: description.value,
    location: location.value,
    allDay: allDay.value,
    startsAt: new Date(startsAt.value).toISOString(),
    endsAt: new Date(endsAt.value).toISOString(),
    rrule: rrule.value,
    participants: [...participants.value],
    originalParticipantIds: Array.from(originalParticipantIds.value),
  }
  emit('save', draft, scope)
}

function doDelete(scope: EventScope) {
  recurScopeOpen.value = false
  deleteConfirmOpen.value = false
  emit('remove', props.event!, scope)
}

function confirmDelete() {
  doDelete('all')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-overlay" @click.self="emit('close')">
      <div class="modal-dialog">
        <div class="modal-header">
          <h3 class="modal-title">{{ isNew ? 'New Event' : 'Edit Event' }}</h3>
          <button class="modal-close" @click="emit('close')">
            <Icon name="x" :size="16" />
          </button>
        </div>

        <div class="modal-body">
          <div class="field">
            <label class="field-label">Title</label>
            <input
              v-model="title"
              class="field-input"
              placeholder="Event title"
              :disabled="isReadonly"
              autofocus>
          </div>

          <div class="field">
            <label class="field-label">Description</label>
            <textarea
              v-model="description"
              class="field-textarea"
              placeholder="Optional description"
              rows="2"
              :disabled="isReadonly" />
          </div>

          <div class="field">
            <label class="field-label">Location</label>
            <input
              v-model="location"
              class="field-input"
              placeholder="Optional location"
              :disabled="isReadonly">
          </div>

          <div class="field field--check">
            <Switch v-model="allDay" :disabled="isReadonly" />
            <label class="field-label">All day</label>
          </div>

          <div class="field-row">
            <div class="field">
              <label class="field-label">Starts</label>
              <input
                v-model="startsAt"
                :type="allDay ? 'date' : 'datetime-local'"
                class="field-input"
                :disabled="isReadonly">
            </div>
            <div class="field">
              <label class="field-label">Ends</label>
              <input
                v-model="endsAt"
                :type="allDay ? 'date' : 'datetime-local'"
                class="field-input"
                :disabled="isReadonly">
            </div>
          </div>

          <RRuleEditor v-if="!isReadonly" v-model="rrule" />

          <!-- Participants -->
          <div class="participants-section">
            <div class="section-header">
              <span class="field-label">Participants</span>
              <button v-if="!isReadonly" class="section-add-btn" @click="participantSearchOpen = true">
                <Icon name="plus" :size="12" /> Add
              </button>
            </div>
            <div v-if="participants.length === 0" class="empty-hint">No participants</div>
            <div v-for="p in participants" :key="p.profileId" class="participant-row">
              <span class="participant-name">{{ p.profileName || p.profileId }}</span>
              <span class="participant-role">{{ p.role }}</span>
              <span class="participant-status" :class="'participant-status--' + p.status.toLowerCase()">{{ p.status }}</span>
              <button v-if="!isReadonly" class="participant-remove" @click="onRemoveParticipant(p.profileId)">
                <Icon name="x" :size="12" />
              </button>
            </div>
          </div>

          <!-- Participant search -->
          <div v-if="participantSearchOpen" class="participant-search">
            <Select
              placeholder="Search for a person…"
              searchable
              :on-search="onProfileSearchWrapped"
              @update:model-value="onAddParticipant"
            />
          </div>
        </div>

        <div class="modal-footer">
          <button
            v-if="!isNew && !isReadonly"
            class="footer-btn footer-btn--delete"
            :disabled="deleting"
            @click="onDelete">
            {{ deleting ? 'Deleting…' : 'Delete' }}
          </button>
          <span class="footer-spacer" />
          <button class="footer-btn" @click="emit('close')">Cancel</button>
          <button
            v-if="!isReadonly"
            class="footer-btn footer-btn--primary"
            :disabled="saving || !title.trim()"
            @click="onSave">
            {{ saving ? 'Saving…' : isNew ? 'Create' : 'Save' }}
          </button>
        </div>
      </div>

      <!-- Delete confirmation (non-recurring) -->
      <div v-if="deleteConfirmOpen" class="scope-overlay" @click.self="deleteConfirmOpen = false">
        <div class="scope-dialog">
          <h4 class="scope-title">Delete this event?</h4>
          <p class="scope-text">This action cannot be undone.</p>
          <div class="scope-actions">
            <button class="footer-btn" @click="deleteConfirmOpen = false">Cancel</button>
            <button class="footer-btn footer-btn--delete" @click="confirmDelete">Delete</button>
          </div>
        </div>
      </div>

      <!-- Recurring scope picker -->
      <div v-if="recurScopeOpen" class="scope-overlay" @click.self="recurScopeOpen = false">
        <div class="scope-dialog">
          <h4 class="scope-title">
            {{ recurScopeAction === 'save' ? 'Edit recurring event' : 'Delete recurring event' }}
          </h4>
          <p class="scope-text">This event is part of a series. What would you like to change?</p>
          <div class="scope-options">
            <button class="scope-option" @click="recurScopeAction === 'save' ? doSave('this') : doDelete('this')">
              This event only
            </button>
            <button class="scope-option" @click="recurScopeAction === 'save' ? doSave('following') : doDelete('following')">
              This and following events
            </button>
            <button class="scope-option" @click="recurScopeAction === 'save' ? doSave('all') : doDelete('all')">
              All events in series
            </button>
          </div>
          <div class="scope-actions">
            <button class="footer-btn" @click="recurScopeOpen = false">Cancel</button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed; inset: 0; z-index: 9000;
  background: rgba(0, 0, 0, 0.6);
  display: flex; align-items: center; justify-content: center; padding: 24px;
}

.modal-dialog {
  background: var(--bg-0); border: 1px solid var(--line); border-radius: var(--r-lg);
  width: 100%; max-width: 520px; max-height: 90vh; display: flex; flex-direction: column; overflow: hidden;
}

.modal-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 18px; border-bottom: 1px solid var(--line);
}

.modal-title { font-size: 15px; font-weight: 600; color: var(--fg-0); margin: 0; }

.modal-close {
  background: none; border: none; color: var(--fg-3); cursor: pointer;
  padding: 4px; border-radius: var(--r-sm);
}
.modal-close:hover { color: var(--fg-0); background: var(--bg-2); }

.modal-body {
  padding: 18px; display: flex; flex-direction: column; gap: 12px; overflow-y: auto;
}

.modal-footer {
  display: flex; align-items: center; gap: 8px; padding: 12px 18px;
  border-top: 1px solid var(--line);
}

.footer-spacer { flex: 1; }

.footer-btn {
  padding: 6px 14px; font-size: 13px; border-radius: var(--r-sm);
  border: none; cursor: pointer; background: var(--bg-2); color: var(--fg-2);
}
.footer-btn:hover { background: var(--bg-3); color: var(--fg-0); }
.footer-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.footer-btn--primary { background: var(--brand-2); color: #fff; }
.footer-btn--primary:hover { filter: brightness(1.1); }
.footer-btn--delete { background: none; color: var(--err); }
.footer-btn--delete:hover { background: var(--bg-2); }

.field { display: flex; flex-direction: column; gap: 4px; flex: 1; min-width: 0; }
.field--check { flex-direction: row; align-items: center; gap: 8px; }
.field-label { font-size: 12px; font-weight: 500; color: var(--fg-3); }
.field-input {
  padding: 7px 10px; font-size: 13px; background: var(--bg-2);
  border: 1px solid var(--line); border-radius: var(--r-sm); color: var(--fg-1); outline: none; width: 100%;
}
.field-input:focus { border-color: var(--brand-2); }
.field-input:disabled { opacity: 0.5; cursor: not-allowed; }
.field-textarea {
  padding: 7px 10px; font-size: 13px; background: var(--bg-2);
  border: 1px solid var(--line); border-radius: var(--r-sm); color: var(--fg-1); outline: none;
  resize: vertical; width: 100%; line-height: 1.5;
}
.field-textarea:focus { border-color: var(--brand-2); }
.field-textarea:disabled { opacity: 0.5; }
.field-row { display: flex; gap: 12px; }

.scope-overlay {
  position: fixed; inset: 0; z-index: 9100;
  background: rgba(0, 0, 0, 0.4);
  display: flex; align-items: center; justify-content: center;
}

.scope-dialog {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: var(--r-lg);
  padding: 24px; max-width: 400px; width: 90%;
}

.scope-title { font-size: 15px; font-weight: 600; color: var(--fg-0); margin: 0 0 8px; }
.scope-text { font-size: 13px; color: var(--fg-3); margin: 0 0 16px; }

.scope-options {
  display: flex; flex-direction: column; gap: 4px; margin-bottom: 16px;
}

.scope-option {
  padding: 8px 12px; font-size: 13px; text-align: left;
  background: var(--bg-2); border: 1px solid var(--line); border-radius: var(--r-sm);
  color: var(--fg-1); cursor: pointer;
}
.scope-option:hover { background: var(--bg-3); border-color: var(--brand-2); }

.scope-actions { display: flex; justify-content: flex-end; }

/* Participants */
.participants-section { margin-top: 4px; }
.section-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.section-add-btn {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 11px; color: var(--fg-3); background: none; border: none; cursor: pointer;
}
.section-add-btn:hover { color: var(--fg-1); }
.empty-hint { font-size: 12px; color: var(--fg-3); }

.participant-row {
  display: flex; align-items: center; gap: 8px; padding: 4px 0;
  border-bottom: 1px solid var(--line);
}
.participant-name { flex: 1; font-size: 13px; color: var(--fg-0); }
.participant-role { font-size: 11px; color: var(--fg-3); }
.participant-status { font-size: 11px; font-weight: 500; }
.participant-status--accepted { color: #34d99a; }
.participant-status--declined { color: var(--err); }
.participant-status--pending { color: var(--fg-3); }
.participant-remove {
  width: 20px; height: 20px; background: none; border: none;
  color: var(--fg-3); cursor: pointer; display: flex; align-items: center; justify-content: center;
  border-radius: var(--r-sm);
}
.participant-remove:hover { color: var(--err); background: var(--bg-2); }

.participant-search { margin-top: 8px; }
.search-row { display: flex; gap: 6px; margin-bottom: 6px; }
.search-btn {
  padding: 5px 10px; font-size: 12px; background: var(--bg-2);
  border: 1px solid var(--line); border-radius: var(--r-sm); color: var(--fg-2); cursor: pointer;
}
.search-btn:hover { background: var(--bg-3); }
.search-result {
  display: flex; align-items: center; gap: 6px; padding: 5px 8px;
  font-size: 13px; color: var(--fg-1); cursor: pointer; border-radius: var(--r-sm);
}
.search-result:hover { background: var(--bg-2); }
</style>
