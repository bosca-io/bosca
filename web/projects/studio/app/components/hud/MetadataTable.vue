<script setup lang="ts">
 
const props = withDefaults(defineProps<{ jumpTo?: (path: string) => void }>(), { jumpTo: undefined })

const TYPE_META: Record<string, { icon: string; color: string }> = {
  document: { icon: 'file',     color: '#5ec5ff' },
  video:    { icon: 'video',    color: '#ff7ac6' },
  image:    { icon: 'image',    color: '#a78bff' },
  audio:    { icon: 'audio',    color: '#ffb547' },
  guide:    { icon: 'route',    color: '#34d99a' },
  data:     { icon: 'database', color: '#5ec5ff' },
  bible:    { icon: 'book',     color: '#7c5cff' },
}

const STATUS: Record<string, { label: string; color: string }> = {
  draft:     { label: 'Draft',       color: '#6c7388' },
  review:    { label: 'In Review',   color: '#5ec5ff' },
  translate: { label: 'Translation', color: '#ffb547' },
  scheduled: { label: 'Scheduled',   color: '#a78bff' },
  published: { label: 'Published',   color: '#34d99a' },
}

const SAMPLE_ROWS = [
  { id: 'md_8x29q', name: 'Hero · banner',          type: 'document', cat: 'Marketing · Home',   status: 'review',    vis: ['public','search'], by: 'Mara F.',   when: '2m ago'  },
  { id: 'md_pf04l', name: 'Q3 Earnings Recap',       type: 'document', cat: 'Newsroom · Posts',  status: 'draft',     vis: ['private'],         by: 'Ari C.',    when: '14m ago' },
  { id: 'md_v0721', name: 'Product Demo · Cut 4',    type: 'video',    cat: 'Marketing · Reels',  status: 'scheduled', vis: ['public','search'], by: 'Tomás P.',  when: '38m ago' },
  { id: 'md_a91xr', name: 'Onboarding Walkthrough',  type: 'guide',    cat: 'Help Center',        status: 'review',    vis: ['public'],          by: 'Mara F.',   when: '1h ago'  },
  { id: 'md_dt33k', name: 'Catalog · 2025 SKUs',     type: 'data',     cat: 'Internal · Ops',     status: 'published', vis: ['private','search'], by: 'jobs/cron', when: '2h ago'  },
  { id: 'md_kx88e', name: 'Spring Lookbook',         type: 'image',    cat: 'Marketing · Assets', status: 'published', vis: ['public','search'], by: 'Lina K.',   when: '3h ago'  },
  { id: 'md_bb02f', name: 'NT/OT Cross References',  type: 'bible',    cat: 'Bibles · NIV',       status: 'translate', vis: ['public'],          by: 'jobs/sync', when: '5h ago'  },
]

function openItem() {
  if (props.jumpTo) {
    props.jumpTo('/cms/metadata')
  } else {
    navigateTo('/cms/metadata')
  }
}
</script>

<template>
  <div>
    <!-- Header row -->
    <div class="meta-header-row">
      <span class="meta-col-heading">Name</span>
      <span class="meta-col-heading">Category</span>
      <span class="meta-col-heading">Type</span>
      <span class="meta-col-heading">Status</span>
      <span class="meta-col-heading">Modified</span>
      <span />
    </div>

    <!-- Data rows -->
    <div
      v-for="row in SAMPLE_ROWS"
      :key="row.id"
      class="meta-row"
      @click="openItem"
    >
      <!-- Name + id -->
      <div class="meta-name-cell">
        <span
          class="meta-type-icon"
          :style="{
            background: `color-mix(in oklch, ${TYPE_META[row.type]?.color ?? '#888'} 18%, var(--bg-2))`,
            border: `1px solid color-mix(in oklch, ${TYPE_META[row.type]?.color ?? '#888'} 30%, transparent)`,
          }"
        >
          <Icon :name="TYPE_META[row.type]?.icon ?? 'file'" :size="14" :color="TYPE_META[row.type]?.color ?? '#888'" />
        </span>
        <div class="meta-name-text">
          <div class="meta-name">{{ row.name }}</div>
          <div class="meta-id">{{ row.id }}</div>
        </div>
      </div>

      <!-- Category -->
      <span class="meta-category">{{ row.cat }}</span>

      <!-- Type -->
      <span class="meta-type">{{ row.type }}</span>

      <!-- Status -->
      <div class="meta-status-cell">
        <span
          class="meta-status-dot"
          :style="{ background: STATUS[row.status]?.color ?? '#888' }"
        />
        <span class="meta-status-label">{{ STATUS[row.status]?.label ?? row.status }}</span>
      </div>

      <!-- Modified: by · when -->
      <div class="meta-modified">
        {{ row.by }} · {{ row.when }}
      </div>

      <!-- More button -->
      <button class="meta-more-btn" @click.stop>
        ···
      </button>
    </div>
  </div>
</template>

<style scoped>
.meta-header-row {
  display: grid;
  grid-template-columns: 1.7fr 0.9fr 0.7fr 0.8fr 0.8fr 36px;
  padding: 0 2px 6px;
  border-bottom: 1px solid var(--line);
}

.meta-col-heading {
  font-size: 11.5px;
  font-weight: 500;
  color: var(--fg-3);
}

.meta-row {
  display: grid;
  grid-template-columns: 1.7fr 0.9fr 0.7fr 0.8fr 0.8fr 36px;
  align-items: center;
  padding: 6px 2px;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
}

.meta-row:hover {
  background: var(--bg-2);
}

.meta-name-cell {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.meta-type-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.meta-name-text {
  min-width: 0;
}

.meta-name {
  font-size: 13px;
  color: var(--fg-1);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.meta-id {
  font-size: 11px;
  color: var(--fg-4);
  font-family: monospace;
}

.meta-category {
  font-size: 12.5px;
  color: var(--fg-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.meta-type {
  font-size: 12px;
  color: var(--fg-2);
}

.meta-status-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.meta-status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.meta-status-label {
  font-size: 12.5px;
  color: var(--fg-2);
}

.meta-modified {
  font-size: 12px;
  color: var(--fg-3);
  white-space: nowrap;
}

.meta-more-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 5px;
  color: var(--fg-3);
  font-size: 16px;
  line-height: 1;
}
</style>
