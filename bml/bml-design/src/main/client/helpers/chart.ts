import type { ClientContext, IslandUpdate } from "@bosca/bml";
import { init, type EChartsType, type EChartsOption } from "echarts";
import "./chart.css";
import { bindChartMaps, mapNavigation } from "./chart-maps";

const record = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === "object" && !Array.isArray(value);
const objects = (value: unknown): Record<string, unknown>[] => Array.isArray(value) ? value.filter(record) : record(value) ? [value] : [];
const display = (value: unknown): string => value == null ? "No value" : record(value)
  ? Object.entries(value).map(([key, item]) => `${key}: ${display(item)}`).join(", ")
  : Array.isArray(value) ? value.map(display).join(", ") : String(value);

type Point = { datasetIndex: number; index: number; series: string; label: unknown; value: unknown };

/** Read the same inline ECharts points as the server's text alternative. */
export function chartPoints(option: Record<string, unknown>): Point[] {
  return objects(option.series).flatMap((series, datasetIndex) => {
    const axis = objects(option.xAxis)[Number(series.xAxisIndex ?? 0)] ?? objects(option.yAxis)[0];
    const labels = Array.isArray(axis?.data) ? axis.data : [];
    return (Array.isArray(series.data) ? series.data : []).map((point, index) => ({
      datasetIndex, index, series: String(series.name ?? `Series ${datasetIndex + 1}`),
      label: record(point) ? point.name ?? labels[index] ?? index + 1 : labels[index] ?? index + 1,
      value: record(point) && "value" in point ? point.value : point,
    }));
  });
}

/** Render native ECharts options with instance-local CSS defaults and BML disposal. */
export function mountChart(ctx: ClientContext) {
  const surface = ctx.ref<HTMLElement>("surface"), state = ctx.ref<HTMLElement>("state");
  if (!surface || !state) return;
  let chart: EChartsType | undefined, previous = "", previousMaps = "", palette = "", disposed = false, queued = false;
  let points: Point[] = [], colorContext: CanvasRenderingContext2D | null | undefined;
  let navigation: ReturnType<typeof mapNavigation> = [];
  const motion = matchMedia("(prefers-reduced-motion: reduce)"), appearance = matchMedia("(prefers-color-scheme: dark)");
  // Normalize modern CSS colors to sRGB for ECharts' color parser and canvas gradients.
  const resolveColor = (value: string): string => {
    if (value === "none" || value === "inherit" || value === "auto") return value;
    if (!CSS.supports("color", value)) throw new Error(`Invalid chart color: ${value}`);
    const probe = document.createElement("span"); probe.hidden = true; probe.style.color = value; ctx.root.append(probe);
    const resolved = getComputedStyle(probe).color; probe.remove();
    if (colorContext === undefined) {
      const canvas = document.createElement("canvas"); canvas.width = canvas.height = 1;
      colorContext = canvas.getContext("2d", { willReadFrequently: true });
    }
    if (!colorContext) throw new Error("Chart colors require a canvas context.");
    colorContext.clearRect(0, 0, 1, 1); colorContext.fillStyle = resolved; colorContext.fillRect(0, 0, 1, 1);
    const [red, green, blue, alpha] = colorContext.getImageData(0, 0, 1, 1).data;
    return `rgba(${red}, ${green}, ${blue}, ${alpha / 255})`;
  };
  const colorsIn = (value: unknown, colorValue = false): unknown => {
    if (typeof value === "string" && colorValue) return resolveColor(value);
    if (Array.isArray(value)) return value.map(item => colorsIn(item, colorValue));
    if (!record(value)) return value;
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, colorsIn(item, /color$/i.test(key))]));
  };
  const updateTable = () => {
    const rows = ctx.ref<HTMLTableSectionElement>("rows"); if (!rows) return;
    rows.replaceChildren(...points.map(point => {
      const row = document.createElement("tr"), series = document.createElement("th"); series.scope = "row"; series.textContent = point.series; row.append(series);
      for (const text of [display(point.label), display(point.value)]) { const cell = document.createElement("td"); cell.textContent = text; row.append(cell); }
      const cell = document.createElement("td"), button = document.createElement("button"); button.type = "button";
      cell.dataset.chartActions = ""; button.dataset.chartDataset = String(point.datasetIndex); button.dataset.chartIndex = String(point.index);
      button.textContent = "Select"; button.setAttribute("aria-label", `Select ${point.series}: ${display(point.label)}`);
      cell.append(button); row.append(cell); return row;
    }));
  };
  const select = (detail: Omit<Point, "series">) => {
    if (ctx.root.hasAttribute("data-loading") || ctx.root.dataset.error || surface.hidden) return;
    ctx.root.dispatchEvent(new CustomEvent("chartselect", { bubbles: true, detail }));
  };
  const destroy = () => { chart?.dispose(); chart = undefined; };
  const resizeChart = () => {
    // ECharts redraws map paths on resize, replacing targets of active pointer gestures.
    if (chart && (chart.getWidth() !== surface.clientWidth || chart.getHeight() !== surface.clientHeight)) chart.resize();
  };
  const sync = () => {
    if (disposed) return;
    const raw = ctx.root.dataset.config ?? "{}", rawMaps = ctx.root.dataset.maps ?? "{}", busy = ctx.root.hasAttribute("data-loading"), error = ctx.root.dataset.error ?? "";
    ctx.root.setAttribute("aria-busy", String(busy));
    ctx.root.querySelectorAll<HTMLButtonElement>("button[data-chart-dataset]").forEach(button => { button.disabled = busy || Boolean(error); });
    ctx.ref<HTMLElement>("mapControls")?.querySelectorAll<HTMLButtonElement>("button").forEach(button => { button.disabled = busy || Boolean(error) || button !== ctx.ref("mapReset") && !navigation.some(target => target.zoom); });
    if (busy || error) { surface.hidden = true; state.hidden = false; state.textContent = error || "Loading chart…"; return; }
    try {
      const option: unknown = JSON.parse(raw);
      if (!record(option) || option.series !== undefined && !record(option.series) && !Array.isArray(option.series)
        || record(option) && Array.isArray(option.series) && !option.series.every(record))
        throw new Error("Supply native ECharts options with a series object or array.");
      const series = objects(option.series);
      if (series.some(item => typeof item.type !== "string" || item.data !== undefined && !Array.isArray(item.data)))
        throw new Error("Each ECharts series needs a type and an optional data array.");
      points = chartPoints(option);
      if (raw !== previous || !chart) updateTable();
      const css = getComputedStyle(ctx.root), token = (name: string, fallback: string) => css.getPropertyValue(name).trim() || fallback;
      const fg = resolveColor(token("--chart-fg", css.color)), grid = resolveColor(token("--chart-grid", token("--line", "#68758e")));
      const font = token("--font-sans", css.fontFamily);
      const colors = ["--accent", "--ok", "--warn", "--info", "--err", "--fg-2"].map((fallback, i) => resolveColor(token(`--chart-series-${i + 1}`, token(fallback, fg))));
      const area = resolveColor(token("--bg-2", "#242a35"));
      const nextPalette = JSON.stringify([fg, grid, area, font, colors, motion.matches]);
      if (raw === previous && rawMaps === previousMaps && nextPalette === palette && chart) { surface.hidden = false; state.hidden = true; resizeChart(); return; }
      const mapped = bindChartMaps(option, JSON.parse(rawMaps));
      navigation = mapNavigation(mapped);
      const controls = ctx.ref<HTMLElement>("mapControls");
      if (controls) controls.hidden = !navigation.length;
      for (const ref of ["mapZoomIn", "mapZoomOut"]) { const button = ctx.ref<HTMLButtonElement>(ref); if (button) button.disabled = !navigation.some(target => target.zoom); }
      if (navigation.length) {
        surface.tabIndex = 0;
        surface.setAttribute("aria-description", "Arrow keys pan the map. Plus and minus zoom. Home resets the map. Use View data to select a region.");
      } else { surface.removeAttribute("tabindex"); surface.removeAttribute("aria-description"); }
      const hasData = navigation.length > 0 || points.length > 0 || objects(option.dataset).some(dataset =>
        Array.isArray(dataset.source) ? dataset.source.length > 0 : record(dataset.source) && Object.keys(dataset.source).length > 0);
      if (!hasData) {
        destroy(); surface.hidden = true; state.hidden = false; state.textContent = "No chart data.";
        previous = raw; previousMaps = rawMaps; palette = nextPalette; ctx.root.setAttribute("data-chart-ready", "");
        ctx.root.querySelectorAll<HTMLElement>("[data-chart-actions]").forEach(element => { element.hidden = true; }); return;
      }
      const axis = { axisLabel: { color: fg }, nameTextStyle: { color: fg }, axisLine: { lineStyle: { color: grid } }, splitLine: { lineStyle: { color: grid } } };
      const theme = {
        color: colors, textStyle: { color: fg, fontFamily: font },
        categoryAxis: axis, valueAxis: axis, logAxis: axis, timeAxis: axis,
        legend: { textStyle: { color: fg } }, title: { textStyle: { color: fg }, subtextStyle: { color: fg } },
        map: { itemStyle: { areaColor: area, borderColor: grid }, label: { color: fg },
          emphasis: { label: { color: fg } }, select: { label: { color: fg } } },
        geo: { itemStyle: { areaColor: area, borderColor: grid }, label: { color: fg } },
        visualMap: { textStyle: { color: fg } },
        radar: { axisName: { color: fg }, axisLine: { lineStyle: { color: grid } }, splitLine: { lineStyle: { color: grid } } },
      };
      surface.hidden = false; state.hidden = true;
      const created = !chart;
      if (!chart) {
        chart = init(surface, theme, { renderer: "canvas" });
        chart.on("click", event => {
          if (event.componentType === "series" && typeof event.seriesIndex === "number" && typeof event.dataIndex === "number")
            { const point = points.find(point => point.datasetIndex === event.seriesIndex && point.index === event.dataIndex);
              select({ datasetIndex: event.seriesIndex, index: event.dataIndex, label: point?.label ?? event.name ?? null, value: point ? point.value : typeof event.value === "number" && !Number.isFinite(event.value) ? null : event.value }); }
        });
      } else if (nextPalette !== palette) chart.setTheme(theme);
      const resolved = colorsIn(mapped) as EChartsOption;
      // Native tooltip markup stays in the canvas; application strings remain text.
      resolved.tooltip = objects(resolved.tooltip).map(item => ({ ...item, renderMode: "richText" }));
      resolved.animation = motion.matches ? false : resolved.animation ?? false;
      chart.setOption(resolved, { notMerge: created || raw !== previous || rawMaps !== previousMaps });
      chart.resize(); previous = raw; previousMaps = rawMaps; palette = nextPalette;
      ctx.root.setAttribute("data-chart-ready", "");
      ctx.root.querySelectorAll<HTMLElement>("[data-chart-actions]").forEach(element => { element.hidden = false; });
    } catch (error) {
      destroy(); surface.hidden = true; state.hidden = false; previous = raw; palette = "";
      navigation = [];
      const controls = ctx.ref<HTMLElement>("mapControls"); if (controls) controls.hidden = true;
      surface.removeAttribute("tabindex"); surface.removeAttribute("aria-description");
      // Keep the server-readable data available when drawing fails.
      state.textContent = error instanceof Error ? error.message : "Invalid chart configuration.";
      ctx.root.setAttribute("data-chart-ready", "");
      ctx.root.querySelectorAll<HTMLElement>("[data-chart-actions]").forEach(element => { element.hidden = true; });
    }
  };
  const roam = (zoom?: number, dx?: number, dy?: number) => {
    if (!chart || surface.hidden) return;
    for (const target of navigation) {
      if (zoom !== undefined ? !target.zoom : !target.pan) continue;
      chart.dispatchAction({ type: "geoRoam", ...(target.kind === "geo" ? { geoIndex: target.index } : { seriesIndex: target.index }),
        ...(zoom !== undefined ? { zoom, originX: chart.getWidth() / 2, originY: chart.getHeight() / 2 } : { dx, dy }) });
    }
  };
  const reset = () => { if (!chart || surface.hidden) return; previous = ""; sync(); };
  for (const [ref, action] of [["mapZoomIn", () => roam(1.25)], ["mapZoomOut", () => roam(0.8)], ["mapReset", reset]] as const) {
    const button = ctx.ref<HTMLButtonElement>(ref); if (button) ctx.on<MouseEvent>(button, "click", action);
  }
  ctx.on<KeyboardEvent>(surface, "keydown", event => {
    if (!navigation.length || event.altKey || event.ctrlKey || event.metaKey || event.target !== surface) return;
    const pan = { ArrowLeft: [-30, 0], ArrowRight: [30, 0], ArrowUp: [0, -30], ArrowDown: [0, 30] }[event.key];
    if (pan && navigation.some(target => target.pan)) { event.preventDefault(); roam(undefined, pan[0], pan[1]); }
    else if (["+", "=", "-"].includes(event.key) && navigation.some(target => target.zoom)) { event.preventDefault(); roam(event.key === "-" ? 0.8 : 1.25); }
    else if (event.key === "Home") { event.preventDefault(); reset(); }
  });
  const schedule = () => { if (!disposed && !queued) { queued = true; requestAnimationFrame(() => { queued = false; sync(); }); } };
  const observer = new MutationObserver(records => {
    if (records.some(record => record.target !== ctx.root || !["aria-busy", "data-chart-ready"].includes(record.attributeName ?? ""))) schedule();
  });
  for (let ancestor: HTMLElement | null = ctx.root; ancestor; ancestor = ancestor.parentElement) observer.observe(ancestor, { attributes: true });
  const resize = new ResizeObserver(schedule); resize.observe(ctx.ref<HTMLElement>("frame") ?? surface);
  ctx.on<Event>(document, "change", schedule); ctx.on<Event>(motion, "change", schedule);
  ctx.on<Event>(appearance, "change", schedule); ctx.on<Event>(window, "resize", schedule);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(ctx.root)) sync(); });
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    const button = event.target instanceof Element ? event.target.closest<HTMLElement>("[data-chart-dataset]") : null;
    if (button) {
      const point = points.find(point => point.datasetIndex === Number(button.dataset.chartDataset) && point.index === Number(button.dataset.chartIndex));
      if (point) {
        if (navigation.length && !surface.hidden) chart?.dispatchAction({ type: "select", seriesIndex: point.datasetIndex, dataIndex: point.index });
        const { datasetIndex, index, label, value } = point; select({ datasetIndex, index, label, value });
      }
    }
  });
  ctx.onUpdate(sync);
  ctx.onUnmount(() => { disposed = true; observer.disconnect(); resize.disconnect(); destroy(); ctx.root.removeAttribute("data-chart-ready"); });
  sync(); void document.fonts.ready.then(() => { palette = ""; schedule(); });
}
