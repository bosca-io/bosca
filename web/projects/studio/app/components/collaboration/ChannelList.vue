<script setup lang="ts">
defineProps<{
  channels: Array<{ id: string; name: string; unreadCount: number }>
  activeChannelId?: string
}>()

const emit = defineEmits<{
  select: [channelId: string]
}>()
</script>

<template>
  <div class="channel-list">
    <button
      v-for="c in channels"
      :key="c.id"
      class="channel-btn"
      :class="{ active: c.id === activeChannelId }"
      @click="emit('select', c.id)"
    >
      <span class="channel-hash">#</span>
      <span class="channel-name">{{ c.name }}</span>
      <span v-if="c.unreadCount > 0" class="channel-badge">{{ c.unreadCount }}</span>
    </button>
  </div>
</template>

<style scoped>
.channel-list {
  padding: 8px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 30%, transparent);
}

.channel-btn {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--fg-2);
  transition: background 0.15s, color 0.15s;
}

.channel-btn:hover {
  background: color-mix(in oklch, var(--fg-2) 8%, transparent);
}

.channel-btn.active {
  background: color-mix(in oklch, var(--fg-2) 12%, transparent);
  color: var(--fg-0);
}

.channel-hash {
  color: var(--fg-3);
}

.channel-name {
  flex: 1;
  text-align: left;
}

.channel-badge {
  font-size: 10.5px;
  padding: 1px 7px;
  border-radius: 999px;
  background: color-mix(in oklch, var(--fg-2) 18%, transparent);
  color: var(--fg-1);
  font-weight: 600;
  border: 1px solid color-mix(in oklch, var(--fg-2) 25%, transparent);
}
</style>
