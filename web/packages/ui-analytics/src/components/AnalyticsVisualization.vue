<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { AreaChart, BarChart, LineChart, DonutChart, BubbleChart, TopoJSONMap } from 'vue-chrts'
import { WorldMapTopoJSON } from '@unovis/ts/maps'
import { geoMercator } from 'd3-geo'
import { format, subDays } from 'date-fns'
import { createVisualization } from '../configurations/VisualizationFactory'
import { DatePickerConfiguration } from '../configurations/DatePickerConfiguration'
import type { BarLineScatterConfiguration } from '../configurations/BarLineScatterConfiguration'
import type { PieDoughnutConfiguration } from '../configurations/PieDoughnutConfiguration'
import type { NumberConfiguration } from '../configurations/NumberConfiguration'
import type { StatConfiguration } from '../configurations/StatConfiguration'
import type { LabelConfiguration } from '../configurations/LabelConfiguration'
import type { TopoJsonMapConfiguration } from '../configurations/TopoJsonMapConfiguration'
import type { GeoPoint, GeoPointMapConfiguration } from '../configurations/GeoPointMapConfiguration'
import { GEO_POINT_MAP_BASE_AREA_COLOR } from '../configurations/GeoPointMapConfiguration'
import type { VisualizationType, QueryExecutionParameter } from '../types'
import DateRangePicker from './DateRangePicker.vue'
import type { DateRange } from './DateRangePicker.vue'

const props = withDefaults(defineProps<{
  name: string
  type: VisualizationType
  configuration?: Record<string, unknown>
  data?: Record<string, unknown>[]
  loading?: boolean
  error?: string
  height?: number
  frameless?: boolean
  /** Effective execution parameters from the hosting dashboard; binds DATEPICKER to them. */
  parameters?: QueryExecutionParameter[]
}>(), {
  frameless: false,
})

const emit = defineEmits<{
  paramChange: [params: QueryExecutionParameter[]]
  pointClick: [point: GeoPoint, event: MouseEvent]
}>()

const slots = defineSlots<{
  pointTooltip?(props: { point: GeoPoint | undefined }): unknown
}>()

const datePickerConfig = computed(() => {
  if (props.type !== 'DATEPICKER') return null
  const cfg = new DatePickerConfiguration()
  if (props.configuration) cfg.loadConfiguration(props.configuration)
  return cfg
})

const today = new Date()
today.setHours(0, 0, 0, 0)

// Bind the picker to the dashboard's start/end parameters so it shows the range the
// queries actually ran with; fall back to the last 7 days when no parameter resolves.
const paramRange = computed(() => datePickerConfig.value?.resolveRange(props.parameters))

function boundDateRange(): DateRange {
  return {
    start: paramRange.value?.start ?? subDays(today, 7),
    end: paramRange.value?.end ?? today,
  }
}

const dateRange = ref<DateRange>(boundDateRange())

watch(paramRange, () => {
  const next = boundDateRange()
  if (next.start.getTime() !== dateRange.value.start.getTime() || next.end.getTime() !== dateRange.value.end.getTime()) {
    dateRange.value = next
  }
})

function onDateRangeChange(range: DateRange) {
  dateRange.value = range
  if (!datePickerConfig.value) return
  emit('paramChange', [
    { parameter: datePickerConfig.value.startDateParam, value: { value: range.start.toISOString(), now: false } },
    { parameter: datePickerConfig.value.endDateParam, value: { value: range.end.toISOString(), now: false } },
  ])
}

const container = ref<HTMLElement | null>(null)
const containerHeight = ref(0)
let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  if (container.value) {
    resizeObserver = new ResizeObserver(entries => {
      containerHeight.value = entries[0]?.contentRect.height ?? 0
    })
    resizeObserver.observe(container.value)
  }
})

onUnmounted(() => {
  resizeObserver?.disconnect()
})

const factory = computed(() => {
  return createVisualization(props.type, props.configuration ?? undefined, props.data ?? undefined)
})

const isReady = computed(() => factory.value.isReady)
const chartData = computed(() => factory.value.getData())
const categories = computed(() => factory.value.getCategories())
const xLabel = computed(() => factory.value.xLabel)
const yLabel = computed(() => factory.value.yLabel)
const xAxisFormatter = computed(() => factory.value.xAxisFormatter)
const yAxisFormatter = computed(() => factory.value.yAxisFormatter)
const tooltipTitleFormatter = computed(() => factory.value.tooltipTitleFormatter)
const valueLabel = computed(() => isReady.value ? factory.value.valueLabel : undefined)
const bubbleProps = computed(() => factory.value.getBubbleProps())
const donutData = computed(() => factory.value.getDonutData())

const labelConfig = computed(() => {
  if (props.type === 'LABEL') {
    return (factory.value as LabelConfiguration).getConfiguration()
  }
  return { label: '', fontSize: '14px', fontWeight: '400', alignment: 'left' }
})

const tableColumns = computed(() => {
  if (props.type !== 'TABLE') return []
  const records = props.data
  if (!records || records.length === 0) return []
  const settings = factory.value.fieldSettings
  const allColumns = Object.keys(records[0]!).map(key => ({
    accessorKey: key,
    label: settings[key]?.label || key,
    format: settings[key]?.format,
  }))
  return factory.value.getColumns(allColumns)
})

const chartHeight = computed(() => props.height ?? (containerHeight.value > 0 ? containerHeight.value - 10 : 220))

const statConfig = computed(() => {
  if (props.type !== 'STAT') return null
  return factory.value as StatConfiguration
})
const statValue = computed(() => statConfig.value?.getStatValue() ?? '')
const statSparklineData = computed(() => statConfig.value?.getSparklineData() ?? [])
const statCategories = computed(() => {
  if (!statConfig.value?.valueKey) return {}
  return { [statConfig.value.valueKey]: { name: statConfig.value.valueKey, color: 'var(--brand-2, #5ec5ff)' } }
})

const mapConfig = computed(() => {
  if (props.type !== 'TOPO_JSON_MAP') return null
  return factory.value as TopoJsonMapConfiguration
})
const mapData = computed(() => mapConfig.value?.getMapData() ?? { areas: [] })
const mapAreaColor = computed(() => mapConfig.value?.getAreaColor())
const mapProjection = geoMercator().center([0, 0])

const geoPointConfig = computed(() => {
  if (props.type !== 'GEO_POINT_MAP' && props.type !== 'LIVE_SESSIONS_MAP') return null
  return factory.value as GeoPointMapConfiguration
})
const geoPointData = computed(() => geoPointConfig.value?.getMapData() ?? { points: [] })
const geoPointColor = computed(() => geoPointConfig.value?.getPointColor())
const geoPointRadius = computed(() => geoPointConfig.value?.pointRadius ?? 5)
const geoPointCategories = computed(() => geoPointConfig.value?.getCategories() ?? {})
const geoPointHideLegend = computed(() => Object.keys(geoPointCategories.value).length === 0)
const baseAreaColor = GEO_POINT_MAP_BASE_AREA_COLOR

function formatTableCell(value: unknown, fmt?: string): string {
  if (value instanceof Date) {
    try { return format(value, fmt || 'yyyy-MM-dd HH:mm:ss') } catch { return format(value, 'yyyy-MM-dd HH:mm:ss') }
  }
  if (typeof value === 'number' && fmt) {
    if (fmt === 'currency') return value.toLocaleString(undefined, { style: 'currency', currency: 'USD' })
    if (fmt === 'percent') return value.toLocaleString(undefined, { style: 'percent' })
  }
  return value == null ? '--' : String(value)
}

function resolveGeoPoint(value: unknown): GeoPoint | undefined {
  if (!value || typeof value !== 'object') return undefined

  const rendered = value as Record<string, unknown>
  const geometry = rendered.geometry
  const properties = rendered.properties

  // Unovis converts each source point to a GeoJSON feature before invoking tooltip and click
  // callbacks. Keep this adapter at the renderer boundary so consumers continue to receive the
  // original row (including appVersion), not the feature wrapper.
  if (
    geometry && typeof geometry === 'object'
    && (geometry as Record<string, unknown>).type === 'Point'
    && properties && typeof properties === 'object'
  ) {
    return properties as GeoPoint
  }

  return value as GeoPoint
}

function onGeoPointClick(point: unknown, event: MouseEvent) {
  const resolved = resolveGeoPoint(point)
  if (resolved) emit('pointClick', resolved, event)
}
</script>

<template>
  <div class="viz-container">
    <!-- Label type renders inline -->
    <div v-if="type === 'LABEL'" class="viz-label" :style="{ fontSize: labelConfig.fontSize, fontWeight: labelConfig.fontWeight, textAlign: labelConfig.alignment as any }">
      {{ labelConfig.label }}
    </div>

    <!-- All other types get a card frame -->
    <template v-else>
      <div v-if="!frameless" class="viz-header">
        <span class="viz-title">{{ name }}</span>
      </div>

      <div ref="container" class="viz-body">
        <!-- Loading -->
        <div v-if="loading" class="viz-state">
          <span class="viz-spinner" />
        </div>

        <!-- Error -->
        <div v-else-if="error" class="viz-state viz-error">{{ error }}</div>

        <!-- Number -->
        <div v-else-if="type === 'NUMBER'" class="viz-number">
          {{ (factory as NumberConfiguration).getNumberValue() }}
        </div>

        <!-- Stat (KPI + sparkline) -->
        <div v-else-if="type === 'STAT' && isReady" class="viz-stat">
          <div class="viz-stat__value">{{ statValue }}</div>
          <div class="viz-stat__sparkline">
            <AreaChart
              v-if="statConfig?.sparklineType === 'area' && statSparklineData.length"
              :data="statSparklineData"
              :index="statConfig.labelKey as any"
              :categories="statCategories as any"
              :height="48"
              :hide-x-axis="true"
              :hide-y-axis="true"
              :hide-legend="true"
              :hide-tooltip="true"
            />
            <BarChart
              v-else-if="statConfig?.sparklineType === 'bar' && statSparklineData.length"
              :data="statSparklineData"
              :y-axis="[statConfig.valueKey!]"
              :categories="statCategories as any"
              :height="48"
              :hide-x-axis="true"
              :hide-y-axis="true"
              :hide-legend="true"
              :hide-tooltip="true"
            />
            <LineChart
              v-else-if="statConfig?.sparklineType === 'line' && statSparklineData.length"
              :data="statSparklineData"
              :index="statConfig.labelKey as any"
              :categories="statCategories as any"
              :height="48"
              :hide-x-axis="true"
              :hide-y-axis="true"
              :hide-legend="true"
              :hide-tooltip="true"
            />
          </div>
        </div>

        <!-- Stat not ready -->
        <div v-else-if="type === 'STAT'" class="viz-state">
          <span style="color: var(--fg-3)">Configure value field</span>
        </div>

        <!-- Bar -->
        <BarChart
          v-else-if="type === 'BAR' && isReady && chartData.length"
          :data="chartData"
          :categories="categories as any"
          :x-axis="(factory as BarLineScatterConfiguration).xAxisKey as any"
          :y-axis="(factory as BarLineScatterConfiguration).yAxisKeys as any"
          :x-label="xLabel"
          :y-label="yLabel"
          :x-formatter="xAxisFormatter"
          :y-formatter="yAxisFormatter"
          :value-label="valueLabel"
          :group-padding="2"
          :bar-padding="0.2"
          :radius="4"
          :height="chartHeight"
          :tooltip-title-formatter="tooltipTitleFormatter"
          :y-grid-line="true"
          class="viz-chart"
        />

        <!-- Not ready -->
        <div v-else-if="type === 'BAR'" class="viz-state">
          <span style="color: var(--fg-3)">Configure X and Y axes</span>
        </div>

        <!-- Line -->
        <LineChart
          v-else-if="type === 'LINE' && isReady && chartData.length"
          :data="chartData"
          :categories="categories as any"
          :x-axis="(factory as BarLineScatterConfiguration).xAxisKey as any"
          :y-axis="(factory as BarLineScatterConfiguration).yAxisKeys as any"
          :x-label="xLabel"
          :y-label="yLabel"
          :value-label="valueLabel"
          :x-formatter="xAxisFormatter"
          :y-formatter="yAxisFormatter"
          :height="chartHeight"
          :tooltip-title-formatter="tooltipTitleFormatter"
          class="viz-chart"
        />

        <!-- Line not ready -->
        <div v-else-if="type === 'LINE'" class="viz-state">
          <span style="color: var(--fg-3)">Configure X and Y axes</span>
        </div>

        <!-- Pie / Doughnut -->
        <DonutChart
          v-else-if="(type === 'PIE' || type === 'DOUGHNUT') && isReady && donutData.length"
          :data="donutData"
          :categories="categories as any"
          :index="(factory as PieDoughnutConfiguration).labelKey as any"
          :category="(factory as PieDoughnutConfiguration).valueKey as any"
          :value-formatter="xAxisFormatter"
          class="viz-chart"
        />

        <!-- Pie/Doughnut not ready -->
        <div v-else-if="type === 'PIE' || type === 'DOUGHNUT'" class="viz-state">
          <span style="color: var(--fg-3)">Configure label and value fields</span>
        </div>

        <!-- Scatter -->
        <BubbleChart
          v-else-if="type === 'SCATTER' && isReady && chartData.length"
          :data="chartData"
          :x-accessor="(bubbleProps as any).xAccessor"
          :y-accessor="(bubbleProps as any).yAccessor"
          :size-accessor="(bubbleProps as any).sizeAccessor"
          :x-formatter="xAxisFormatter"
          :y-formatter="yAxisFormatter"
          :value-label="valueLabel"
          :height="chartHeight"
          class="viz-chart"
        />

        <!-- Table -->
        <div v-else-if="type === 'TABLE'" class="viz-table-wrap">
          <div v-if="!tableColumns.length" class="viz-state">No data to display</div>
          <table class="viz-table" v-else>
            <thead>
              <tr>
                <th v-for="col in tableColumns" :key="(col as any).accessorKey">{{ (col as any).label }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, i) in chartData" :key="i">
                <td v-for="col in tableColumns" :key="(col as any).accessorKey" class="mono">
                  {{ formatTableCell(row[(col as any).accessorKey], (col as any).format) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- DatePicker -->
        <div v-else-if="type === 'DATEPICKER'" class="viz-datepicker">
          <DateRangePicker
            :model-value="dateRange"
            @update:model-value="onDateRangeChange"
          />
        </div>

        <!-- TopoJSON Map -->
        <TopoJSONMap
          v-else-if="type === 'TOPO_JSON_MAP' && isReady"
          :data="mapData as any"
          map-feature-key="countries"
          :topo-json="WorldMapTopoJSON"
          :projection="mapProjection"
          :area-color="mapAreaColor as any"
          :zoom-factor="1.1"
          :height="chartHeight"
          class="viz-chart"
        />

        <!-- Map not ready -->
        <div v-else-if="type === 'TOPO_JSON_MAP'" class="viz-state">
          <span style="color: var(--fg-3)">Configure region and value columns</span>
        </div>

        <!-- Geo point map (markers, e.g. live sessions) -->
        <TopoJSONMap
          v-else-if="type === 'GEO_POINT_MAP' && isReady"
          :data="geoPointData as any"
          map-feature-key="countries"
          :topo-json="WorldMapTopoJSON"
          :projection="mapProjection"
          :area-color="baseAreaColor"
          :point-color="geoPointColor as any"
          :point-size="geoPointRadius"
          :categories="geoPointCategories as any"
          :hide-legend="geoPointHideLegend"
          :zoom-factor="1.1"
          :height="chartHeight"
          @point-click="onGeoPointClick"
          class="viz-chart"
        >
          <template v-if="slots.pointTooltip" #tooltip="{ values }">
            <slot name="pointTooltip" :point="resolveGeoPoint(values)" />
          </template>
        </TopoJSONMap>

        <!-- Geo point map not ready -->
        <div v-else-if="type === 'GEO_POINT_MAP'" class="viz-state">
          <span style="color: var(--fg-3)">Configure latitude and longitude columns</span>
        </div>

        <!-- Live sessions map (streaming markers; always ready — no appId means all applications) -->
        <TopoJSONMap
          v-else-if="type === 'LIVE_SESSIONS_MAP'"
          :data="geoPointData as any"
          map-feature-key="countries"
          :topo-json="WorldMapTopoJSON"
          :projection="mapProjection"
          :area-color="baseAreaColor"
          :point-color="geoPointColor as any"
          :point-size="geoPointRadius"
          :categories="geoPointCategories as any"
          :hide-legend="geoPointHideLegend"
          point-cursor="pointer"
          :zoom-factor="1.1"
          :height="chartHeight"
          @point-click="onGeoPointClick"
          class="viz-chart viz-chart--live"
        >
          <template v-if="slots.pointTooltip" #tooltip="{ values }">
            <slot name="pointTooltip" :point="resolveGeoPoint(values)" />
          </template>
        </TopoJSONMap>

        <!-- Unsupported -->
        <div v-else class="viz-state">
          <span style="color: var(--fg-3)">Unsupported: {{ type }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.viz-container {
  --vis-axis-grid-color: var(--line, #2a2d35);
  --vis-axis-tick-color: var(--line, #2a2d35);
  --vis-axis-tick-label-color: var(--fg-3, #6c7388);
  /* Unovis only activates its dark tooltip palette for a `body.theme-dark` class, while Studio's
     theme uses tokens. Supplying the host tokens here keeps tooltips legible in either theme. */
  --vis-tooltip-background-color: var(--bg-2, #22252d);
  --vis-tooltip-border-color: var(--line, #2a2d35);
  --vis-tooltip-text-color: var(--fg-1, #ccc);
  /* vue-chrts gives tooltip titles their own black fallback instead of inheriting Unovis's text
     color. Define every tooltip text role so graph and map hover content follows Studio's theme. */
  --vis-tooltip-title-color: var(--fg-0, #fff);
  --vis-tooltip-label-color: var(--fg-2, #a9afbd);
  --vis-tooltip-value-color: var(--fg-1, #ccc);
  --vis-tooltip-title-border-bottom: 1px solid var(--line, #2a2d35);
  --vis-tooltip-box-shadow: 0 8px 24px rgb(0 0 0 / 28%);
  /* World-map land + borders. Unovis fills each feature from these vars when it has no per-datum color
     (the case for the point/live base map), so the land was defaulting to Unovis's light gray. Set both
     the light and dark variants to the (theme-aware, opaque) tokens so the land is a muted slate. */
  --vis-map-feature-color: var(--map-land, #242a38);
  --vis-dark-map-feature-color: var(--map-land, #242a38);
  --vis-map-boundary-color: var(--map-boundary, #39415a);
  --vis-dark-map-boundary-color: var(--map-boundary, #39415a);
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.viz-header {
  padding: 10px 14px;
  border-bottom: 1px solid var(--line, #2a2d35);
}

.viz-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--fg-1, #ccc);
}

.viz-body {
  flex: 1;
  min-height: 0;
  padding: 14px;
  overflow: hidden;
}

.viz-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 13px;
}

.viz-error {
  color: var(--err, #ff5d6c);
}

.viz-spinner {
  width: 20px;
  height: 20px;
  border: 2px solid var(--line, #2a2d35);
  border-top-color: var(--brand-2, #5ec5ff);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.viz-number {
  font-size: 28px;
  font-weight: 600;
  color: var(--fg-0, #fff);
  padding: 8px 0;
}

.viz-stat {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
  padding: 4px 0;
}

.viz-stat__value {
  font-size: 24px;
  font-weight: 600;
  color: var(--fg-0, #fff);
  white-space: nowrap;
}

.viz-stat__sparkline {
  width: 112px;
  height: 48px;
  flex-shrink: 0;
}

.viz-chart {
  width: 100%;
  height: 100%;
}

/* Live session dots breathe gently so the map reads as streaming. Unovis draws each marker as a
   d3-symbol <path> centered on its own local origin (the parent <g> carries the geo translate),
   so a scale animation pulses in place. The emotion-generated class is `css-<hash>-point-shape`;
   matching on the stable `point-shape` label suffix survives Unovis upgrades. */
.viz-chart--live :deep(path[class*='point-shape']) {
  animation: live-dot-pulse 2.4s ease-in-out infinite;
}

@keyframes live-dot-pulse {
  0%, 100% { transform: scale(1); opacity: 0.7; }
  50% { transform: scale(1.35); opacity: 1; }
}

@media (prefers-reduced-motion: reduce) {
  .viz-chart--live :deep(path[class*='point-shape']) {
    animation: none;
  }
}

.viz-datepicker {
  display: flex;
  align-items: center;
  height: 100%;
  padding: 0 4px;
}

.viz-label {
  display: flex;
  align-items: center;
  height: 100%;
  padding: 8px 14px;
  color: var(--fg-1, #ccc);
}

.viz-table-wrap {
  overflow: auto;
  max-height: 100%;
}

.viz-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.viz-table th {
  text-align: left;
  font-size: 10.5px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3, #6c7388);
  padding: 6px 10px;
  border-bottom: 1px solid var(--line, #2a2d35);
  position: sticky;
  top: 0;
  background: var(--bg-1, #1a1c22);
}

.viz-table td {
  padding: 6px 10px;
  color: var(--fg-1, #ccc);
  border-bottom: 1px solid color-mix(in oklch, var(--line, #2a2d35) 40%, transparent);
}

.viz-table tr:last-child td {
  border-bottom: none;
}
</style>
