<script setup lang="ts">
const items = [
  { who: 'Ari Chen',   wc: '#ff9b5c', verb: 'published',         what: 'Spring Lookbook',           ctx: 'image · 18 assets',   t: '2m',  tone: 'ok'   },
  { who: 'Mara Field', wc: '#9d7cff', verb: 'requested review on', what: 'Hero · banner',            ctx: 'document',            t: '6m',  tone: 'info' },
  { who: 'jobs/cron',  wc: '#34d99a', verb: 'completed',          what: 'Catalog sync · 2025 SKUs',  ctx: '8,402 rows · 47s',    t: '12m', tone: 'ok'   },
  { who: 'Tomás P.',   wc: '#5ec5ff', verb: 'created',            what: 'Q3 Earnings Recap',         ctx: 'newsroom',            t: '14m', tone: 'info' },
  { who: 'workflow',   wc: '#ffb547', verb: 'flagged SLA on',     what: 'Onboarding Walkthrough',    ctx: '12h overdue',         t: '32m', tone: 'warn' },
  { who: 'Lina K.',    wc: '#ff7ac6', verb: 'localized',          what: '24 strings to fr-FR',       ctx: 'localization',        t: '1h',  tone: 'info' },
  { who: 'jobs/index', wc: '#34d99a', verb: 'reindexed',          what: 'Admin Search Index',        ctx: '128k docs · 3m 12s',  t: '2h',  tone: 'ok'   },
]

function initials(who: string) {
  return who.split(' ').map((s: string) => s[0]).slice(0, 2).join('')
}

function isSystem(who: string) {
  return who.startsWith('jobs') || who === 'workflow'
}

function iconName(who: string) {
  return who === 'workflow' ? 'workflow' : 'gear'
}
</script>

<template>
  <div class="activity-stream">
    <div
      v-for="(i, idx) in items"
      :key="idx"
      class="activity-row"
    >
      <!-- Avatar -->
      <div class="activity-avatar" :style="{ background: i.wc }">
        <Icon
          v-if="isSystem(i.who)"
          :name="iconName(i.who)"
          :size="12"
          color="#fff" />
        <template v-else>{{ initials(i.who) }}</template>
      </div>

      <!-- Text body -->
      <div class="activity-body">
        <div class="activity-text">
          <span class="activity-who">{{ i.who }}</span>{{ ' ' }}<span class="activity-verb">{{ i.verb }}</span>{{ ' ' }}<span class="activity-what">{{ i.what }}</span>
        </div>
        <div class="activity-meta">
          <span>{{ i.ctx }}</span>
          <span class="activity-meta-dot">·</span>
          <span class="mono">{{ i.t }} ago</span>
        </div>
      </div>

      <!-- Tone dot -->
      <span :class="`dot ${i.tone}`" class="activity-tone-dot" />
    </div>
  </div>
</template>

<style scoped>
.activity-stream {
  display: flex;
  flex-direction: column;
}

.activity-row {
  display: flex;
  gap: 10px;
  padding: 8px 4px;
  align-items: flex-start;
}

.activity-avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  flex: 0 0 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 600;
  color: #fff;
}

.activity-body {
  flex: 1;
  min-width: 0;
}

.activity-text {
  font-size: 12.5px;
  line-height: 1.45;
}

.activity-who {
  color: var(--fg-0);
  font-weight: 600;
}

.activity-verb {
  color: var(--fg-3);
}

.activity-what {
  color: var(--fg-1);
}

.activity-meta {
  font-size: 11px;
  color: var(--fg-3);
  margin-top: 2px;
  display: flex;
  gap: 6px;
  align-items: center;
}

.activity-meta-dot {
  color: var(--fg-4);
}

.activity-tone-dot {
  margin-top: 7px;
}
</style>
