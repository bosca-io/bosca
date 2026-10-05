<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{
  name: string
  color: string
  time: string
  text: string
}>()

const initial = computed(() =>
  props.name
    .split(' ')
    .map((part) => part[0])
    .join('')
    .toUpperCase()
    .slice(0, 2),
)
</script>

<template>
  <div class="msg">
    <!-- Avatar -->
    <div
      class="msg-avatar"
      :style="{ background: color }"
    >
      {{ initial }}
    </div>

    <!-- Content -->
    <div class="msg-content">
      <div class="msg-meta">
        <span class="msg-name">{{ name }}</span>
        <span class="msg-time">{{ time }}</span>
      </div>
      <p class="msg-text">{{ text }}</p>
    </div>
  </div>
</template>

<style scoped>
.msg {
  display: flex;
  gap: 10px;
  padding: 10px 0;
}

.msg-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  flex-shrink: 0;
}

.msg-content {
  flex: 1;
  min-width: 0;
}

.msg-meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 3px;
}

.msg-name {
  font-size: 13px;
  font-weight: 580;
  color: var(--fg-1);
}

.msg-time {
  font-size: 11px;
  color: var(--fg-4);
}

.msg-text {
  margin: 0;
  font-size: 13px;
  color: var(--fg-2);
  line-height: 1.5;
}
</style>
