<script lang="ts" setup>
export interface CalendarItem {
  key: string
  metadataId: string
  version: number
  name: string
  color: string
  canEdit: boolean
}

defineProps<{
  calendars: CalendarItem[]
  visible: Set<string>
}>()

const emit = defineEmits<{
  toggle: [key: string]
  add: []
  edit: [calendar: CalendarItem]
}>()
</script>

<template>
  <aside class="cal-sidebar">
    <div class="sidebar-header">
      <span class="sidebar-title">Calendars</span>
      <button class="sidebar-add" title="Add calendar" @click="emit('add')">
        <Icon name="plus" :size="14" />
      </button>
    </div>

    <div v-if="calendars.length === 0" class="sidebar-empty">
      No calendars yet.
    </div>

    <ul v-else class="cal-list">
      <li
        v-for="cal in calendars"
        :key="cal.key"
        class="cal-item"
        @click="emit('toggle', cal.key)"
      >
        <span
          class="cal-checkbox"
          :style="{
            backgroundColor: visible.has(cal.key) ? cal.color : 'transparent',
            borderColor: cal.color,
          }"
        />
        <span class="cal-name" :class="{ 'cal-name--muted': !visible.has(cal.key) }">
          {{ cal.name }}
        </span>
        <button
          v-if="cal.canEdit"
          class="cal-edit"
          title="Edit calendar"
          @click.stop="emit('edit', cal)"
        >
          <Icon name="pencil" :size="12" />
        </button>
      </li>
    </ul>
  </aside>
</template>

<style scoped>
.cal-sidebar {
  width: 200px;
  flex-shrink: 0;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sidebar-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.sidebar-add {
  width: 24px;
  height: 24px;
  background: none;
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  border-radius: var(--r-sm);
  display: flex;
  align-items: center;
  justify-content: center;
}

.sidebar-add:hover {
  color: var(--fg-0);
  background: var(--bg-2);
}

.sidebar-empty {
  font-size: 12px;
  color: var(--fg-3);
}

.cal-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.cal-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  border-radius: var(--r-sm);
  cursor: pointer;
}

.cal-item:hover {
  background: var(--bg-2);
}

.cal-checkbox {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  border: 2px solid;
  flex-shrink: 0;
}

.cal-name {
  flex: 1;
  font-size: 13px;
  color: var(--fg-0);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cal-name--muted {
  color: var(--fg-3);
}

.cal-edit {
  width: 22px;
  height: 22px;
  background: none;
  border: none;
  color: var(--fg-3);
  cursor: pointer;
  border-radius: var(--r-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
}

.cal-item:hover .cal-edit {
  opacity: 1;
}

.cal-edit:hover {
  color: var(--fg-0);
  background: var(--bg-3);
}
</style>
