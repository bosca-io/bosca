import type { ClientContext, IslandUpdate } from "@bosca/bml";
import { Chart, registerables, type ChartConfiguration, type ChartType } from "chart.js";
import { color as chartColor } from "chart.js/helpers";
import "./chart.css";

Chart.register(...registerables);
const types = new Set(["bar", "line", "scatter", "bubble", "pie", "doughnut", "radar", "polarArea"]);
const record = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === "object" && !Array.isArray(value);

/** Render Chart.js configuration with instance-local CSS defaults and BML disposal. */
export function mountChart(ctx: ClientContext) {
  const canvas = ctx.ref<HTMLCanvasElement>("canvas"), state = ctx.ref<HTMLElement>("state");
  if (!canvas || !state) return;
  let chart: Chart | undefined, previous = "", palette = "", disposed = false, queued = false;
  const motion = matchMedia("(prefers-reduced-motion: reduce)");
  const appearance = matchMedia("(prefers-color-scheme: dark)");
  const display = (value: unknown): string => value === null ? "No value" : record(value)
    ? Object.entries(value).map(([key, item]) => `${key}: ${display(item)}`).join(", ")
    : Array.isArray(value) ? value.map(display).join(", ") : String(value);
  const updateTable = (data: { labels?: unknown; datasets: Record<string, unknown>[] }) => {
    const rows = ctx.ref<HTMLTableSectionElement>("rows");
    if (!rows) return;
    const contents: HTMLTableRowElement[] = [];
    for (const [datasetIndex, dataset] of data.datasets.entries()) for (const [index, point] of (dataset.data as unknown[]).entries()) {
      const row = document.createElement("tr"), series = document.createElement("th"); series.scope = "row";
      series.textContent = String(dataset.label ?? `Series ${datasetIndex + 1}`); row.append(series);
      for (const text of [Array.isArray(data.labels) && index < data.labels.length ? display(data.labels[index]) : String(index + 1), display(point)]) {
        const cell = document.createElement("td"); cell.textContent = text; row.append(cell);
      }
      const cell = document.createElement("td"), button = document.createElement("button"); button.type = "button";
      cell.dataset.chartActions = ""; button.dataset.chartDataset = String(datasetIndex); button.dataset.chartIndex = String(index);
      button.textContent = "Select"; button.setAttribute("aria-label", `Select ${series.textContent}: ${row.cells[1].textContent}`);
      cell.append(button); row.append(cell); contents.push(row);
    }
    rows.replaceChildren(...contents);
  };
  const select = (datasetIndex: number, index: number) => {
    if (ctx.root.hasAttribute("data-loading") || ctx.root.dataset.error) return;
    const dataset = chart?.data.datasets[datasetIndex];
    if (!dataset || index < 0 || index >= dataset.data.length) return;
    ctx.root.dispatchEvent(new CustomEvent("chartselect", { bubbles: true, detail: {
      datasetIndex, index, label: chart?.data.labels?.[index] ?? null, value: dataset.data[index],
    } }));
  };
  const sync = () => {
    if (disposed) return;
    const css = getComputedStyle(ctx.root), color = (name: string, fallback: string) => css.getPropertyValue(name).trim() || fallback;
    const fg = color("--chart-fg", css.color), grid = color("--chart-grid", color("--line", "#68758e"));
    const font = color("--font-sans", css.fontFamily);
    const colors = ["--accent", "--ok", "--warn", "--info", "--err", "--fg-2"].map((fallback, i) => color(`--chart-series-${i + 1}`, color(fallback, fg)));
    const nextPalette = JSON.stringify([fg, grid, font, colors, motion.matches]);
    const raw = ctx.root.dataset.config ?? "{}";
    const busy = ctx.root.hasAttribute("data-loading"), error = ctx.root.dataset.error ?? "";
    ctx.root.setAttribute("aria-busy", String(busy));
    ctx.root.querySelectorAll<HTMLButtonElement>("button[data-chart-dataset]").forEach(button => { button.disabled = busy || Boolean(error); });
    if (busy || error) { canvas.hidden = true; state.hidden = false; state.textContent = error || "Loading chart…"; return; }
    if (raw === previous && nextPalette === palette && chart) { canvas.hidden = false; state.hidden = true; chart.resize(); return; }
    try {
      const config: unknown = JSON.parse(raw);
      if (!record(config) || !types.has(String(config.type)) || !record(config.data) || !Array.isArray(config.data.datasets))
        throw new Error("Supply a supported Chart.js type and data.datasets array.");
      if (config.options !== undefined && !record(config.options)) throw new Error("Chart options must be an object.");
      if (config.data.labels !== undefined && !Array.isArray(config.data.labels)) throw new Error("Chart labels must be an array.");
      for (const dataset of config.data.datasets) {
        if (!record(dataset) || !Array.isArray(dataset.data)) throw new Error("Each dataset must contain a data array.");
        if (dataset.type !== undefined && !types.has(String(dataset.type))) throw new Error("Unsupported dataset chart type.");
      }
      if (raw !== previous) updateTable(config.data as { datasets: Record<string, unknown>[] });
      if (!config.data.datasets.some(dataset => dataset.data.length)) {
        chart?.destroy(); chart = undefined; canvas.hidden = true; state.hidden = false; state.textContent = "No chart data.";
        previous = raw; palette = nextPalette;
        ctx.root.querySelectorAll<HTMLElement>("[data-chart-actions]").forEach(element => { element.hidden = true; });
        ctx.root.setAttribute("data-chart-ready", ""); return;
      }
      const options = record(config.options) ? config.options : {};
      const radial = ["radar", "polarArea"].includes(String(config.type));
      const circular = ["pie", "doughnut", "polarArea"].includes(String(config.type));
      const scales = record(options.scales) ? options.scales : {};
      if (!["pie", "doughnut"].includes(String(config.type))) for (const key of Object.keys(scales).length ? Object.keys(scales) : radial ? ["r"] : ["x", "y"]) {
        const axis = record(scales[key]) ? scales[key] : {};
        const ticks = record(axis.ticks) ? axis.ticks : {}, axisGrid = record(axis.grid) ? axis.grid : {};
        scales[key] = { ...axis, ticks: { color: fg, ...ticks }, grid: { color: grid, ...axisGrid },
          ...(radial ? { angleLines: { color: grid, ...(record(axis.angleLines) ? axis.angleLines : {}) }, pointLabels: { color: fg, ...(record(axis.pointLabels) ? axis.pointLabels : {}) } } : {}) };
      }
      const plugins = record(options.plugins) ? options.plugins : {};
      const legend = record(plugins.legend) ? plugins.legend : {};
      const labels = record(legend.labels) ? legend.labels : {};
      const datasets = config.data.datasets.map((dataset, i) => ({
        borderColor: circular ? colors : colors[i % colors.length],
        backgroundColor: circular ? colors : ["line", "radar"].includes(String(dataset.type ?? config.type))
          ? chartColor(colors[i % colors.length]).alpha(0.18).rgbString() : colors[i % colors.length],
        borderWidth: 2, ...dataset,
      }));
      const resolved = { ...config, data: { ...config.data, datasets }, options: { ...options,
        color: options.color ?? fg, font: { family: font, ...(record(options.font) ? options.font : {}) },
        plugins: { ...plugins, legend: plugins.legend === false ? false : { ...legend, labels: { color: fg, ...labels } } }, scales,
        responsive: true, maintainAspectRatio: false,
        animation: motion.matches ? false : options.animation ?? false,
        onClick: (_event: unknown, elements: { datasetIndex: number; index: number }[]) => { const point = elements[0]; if (point) select(point.datasetIndex, point.index); },
      } } as unknown as ChartConfiguration<ChartType>;
      canvas.hidden = false; state.hidden = true;
      if (chart && "type" in chart.config && chart.config.type === resolved.type) {
        chart.setActiveElements([]); chart.tooltip?.setActiveElements([], { x: 0, y: 0 });
        chart.data = resolved.data; chart.options = resolved.options ?? {}; chart.update("none");
      }
      else { chart?.destroy(); chart = undefined; chart = new Chart(canvas, resolved); }
      previous = raw; palette = nextPalette;
      ctx.root.setAttribute("data-chart-ready", "");
      ctx.root.querySelectorAll<HTMLElement>("[data-chart-actions]").forEach(element => { element.hidden = false; });
    } catch (error) {
      chart?.destroy(); chart = undefined; canvas.hidden = true; state.hidden = false;
      previous = raw; palette = ""; ctx.ref<HTMLElement>("rows")?.replaceChildren();
      state.textContent = error instanceof Error ? error.message : "Invalid chart configuration.";
      ctx.root.setAttribute("data-chart-ready", "");
      ctx.root.querySelectorAll<HTMLElement>("[data-chart-actions]").forEach(element => { element.hidden = true; });
    }
  };
  const schedule = () => { if (!queued) { queued = true; requestAnimationFrame(() => { queued = false; sync(); }); } };
  // Inherited CSS can depend on any ancestor attribute; ignore this adapter's own status writes.
  const observer = new MutationObserver(records => {
    if (records.some(record => record.target !== ctx.root || !["aria-busy", "data-chart-ready"].includes(record.attributeName ?? ""))) schedule();
  });
  for (let ancestor: HTMLElement | null = ctx.root; ancestor; ancestor = ancestor.parentElement)
    observer.observe(ancestor, { attributes: true });
  ctx.on<Event>(document, "change", schedule);
  ctx.on<Event>(motion, "change", schedule);
  ctx.on<Event>(appearance, "change", schedule);
  ctx.on<Event>(window, "resize", schedule);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(ctx.root)) sync(); });
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    const button = event.target instanceof Element ? event.target.closest<HTMLElement>("[data-chart-dataset]") : null;
    if (button) select(Number(button.dataset.chartDataset), Number(button.dataset.chartIndex));
  });
  ctx.onUpdate(sync);
  ctx.onUnmount(() => { disposed = true; observer.disconnect(); chart?.destroy(); chart = undefined; ctx.root.removeAttribute("data-chart-ready"); });
  sync();
  void document.fonts.ready.then(() => { palette = ""; schedule(); });
}
