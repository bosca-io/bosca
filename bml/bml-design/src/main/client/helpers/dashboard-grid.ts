import type { ClientContext, IslandUpdate } from "@bosca/bml";
import "./dashboard-grid.css";

type Item = Record<string, unknown> & { id: string; x: number; y: number; w: number; h: number };
type Gesture = { id: string; kind: string; pointer: number; x: number; y: number; step: number; row: number; before: Item[]; preview: Item[] };
const overlaps = (a: Item, b: Item) => a.id !== b.id && a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
const clamp = (value: number, low: number, high: number) => Math.max(low, Math.min(high, value));

/** Studio's row/column layout semantics, applied directly to projected BML widgets. */
export function mountDashboardGrid(ctx: ClientContext) {
  const field = ctx.ref<HTMLTextAreaElement>("value"), cells = ctx.ref<HTMLElement>("cells"), feedback = ctx.ref<HTMLElement>("feedback");
  if (!field || !cells || !feedback) return;
  const widgets = new Map([...cells.children].filter((el): el is HTMLElement => el instanceof HTMLElement && el.hasAttribute("data-dashboard-item"))
    .map(el => [el.dataset.dashboardItem ?? "", el]));
  let layout: Item[] = [], gesture: Gesture | undefined, lockedIds = new Set<string>();
  let previous = "", options = "", invalid = "", columns = 12, rowHeight = 60, stackBelow = 480;
  let readonly = false, disabled = false, pending = false, disposed = false, form: HTMLFormElement | null = null;
  const report = () => {
    feedback.textContent = invalid || ctx.root.dataset.error || ""; feedback.hidden = !feedback.textContent;
    field.setCustomValidity(invalid); field.setAttribute("aria-invalid", String(Boolean(feedback.textContent)));
    field.setAttribute("aria-describedby", `${ctx.root.dataset.gridId}-instructions`);
    field.hidden = !invalid; const label = ctx.root.querySelector<HTMLElement>(".layout-label"); if (label) label.hidden = !invalid;
  };
  const stacked = () => cells.getBoundingClientRect().width < stackBelow;
  const blocked = (id: string) => readonly || disabled || pending || Boolean(invalid) || lockedIds.has(id) || stacked();
  const paint = (items = layout) => {
    ctx.root.toggleAttribute("data-stacked", stacked());
    cells.style.setProperty("--dashboard-columns", String(columns));
    cells.style.setProperty("--dashboard-cell-height", `${rowHeight}px`);
    for (const item of items) {
      const widget = widgets.get(item.id);
      if (!widget) continue;
      widget.style.setProperty("--dashboard-column", `${item.x + 1} / span ${item.w}`);
      widget.style.setProperty("--dashboard-row", `${item.y + 1} / span ${item.h}`);
      widget.toggleAttribute("data-dashboard-active", gesture?.id === item.id);
      const handles = widget.querySelector<HTMLElement>(".dashboard-handles");
      if (handles) handles.hidden = readonly || disabled || Boolean(invalid) || lockedIds.has(item.id) || stacked();
      widget.querySelectorAll<HTMLButtonElement>("[data-dashboard-handle]").forEach(button => {
        button.disabled = blocked(item.id);
        button.setAttribute("aria-describedby", `${ctx.root.dataset.gridId}-instructions`);
      });
    }
  };
  const cancel = () => {
    const old = gesture; gesture = undefined;
    if (old && cells.hasPointerCapture(old.pointer)) cells.releasePointerCapture(old.pointer);
    paint();
  };
  const load = (raw: string) => {
    cancel(); field.value = raw; field.defaultValue = raw;
    try {
      const parsed: unknown = JSON.parse(raw);
      if (!Array.isArray(parsed)) throw new Error("Supply a layout array with id, x, y, w and h for every widget.");
      const ids = new Set<string>();
      for (const value of parsed) {
        if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Layout items must be objects.");
        const item = value as Item;
        if (typeof item.id !== "string" || !item.id || ids.has(item.id) || !widgets.has(item.id)) throw new Error("Layout IDs must be unique and match dashboard-item itemId values.");
        ids.add(item.id);
        if (![item.x, item.y, item.w, item.h].every(Number.isSafeInteger) || item.x < 0 || item.y < 0 || item.w < 1 || item.h < 1
          || item.x + item.w > columns || item.y + item.h > 10000) throw new Error("Widget coordinates must be whole cells inside the columns and first 10000 rows.");
      }
      if (ids.size !== widgets.size || cells.children.length !== widgets.size) throw new Error("Provide one direct dashboard-item per layout ID, with no duplicate itemId values.");
      const items = parsed as Item[];
      if (items.some((item, i) => items.slice(i + 1).some(other => overlaps(item, other)))) throw new Error("Layout widgets must not overlap.");
      layout = items; invalid = "";
    } catch (error) { invalid = error instanceof Error ? error.message : "Invalid layout."; }
    ctx.root.toggleAttribute("data-grid-ready", !invalid); paint(); report();
  };
  const resolve = (before: Item[], moved: Item): Item[] | undefined => {
    const locked = before.filter(item => item.id !== moved.id && lockedIds.has(item.id));
    if (locked.some(item => overlaps(item, moved))) return;
    const placed = [...locked, moved];
    for (const item of before.filter(item => item.id !== moved.id && !lockedIds.has(item.id)).sort((a, b) => a.y - b.y || a.x - b.x)) {
      const candidate = { ...item };
      for (;;) {
        const collisions = placed.filter(other => overlaps(candidate, other));
        if (!collisions.length) break;
        candidate.y = Math.max(...collisions.map(other => other.y + other.h));
        if (candidate.y + candidate.h > 10000) return;
      }
      placed.push(candidate);
    }
    return before.map(item => placed.find(candidate => candidate.id === item.id) ?? item);
  };
  const commit = (next: Item[], id: string, kind: string) => {
    if (JSON.stringify(next) === JSON.stringify(layout)) return;
    layout = next; field.value = JSON.stringify(layout); paint();
    field.dispatchEvent(new Event("input", { bubbles: true })); field.dispatchEvent(new Event("change", { bubbles: true }));
    ctx.root.dispatchEvent(new CustomEvent("layoutchange", { bubbles: true, detail: { layout: structuredClone(layout), itemId: id, kind } }));
  };
  const moved = (before: Item[], id: string, kind: string, dx: number, dy: number) => {
    const original = before.find(item => item.id === id);
    if (!original) return;
    const item = { ...original };
    if (kind === "move") { item.x = clamp(item.x + dx, 0, columns - item.w); item.y = clamp(item.y + dy, 0, 10000 - item.h); }
    else { item.w = clamp(item.w + dx, 1, columns - item.x); item.h = clamp(item.h + dy, 1, 10000 - item.y); }
    return resolve(before, item);
  };
  const handle = (target: EventTarget | null) => {
    if (!(target instanceof Element) || target.closest("[data-bml-dashboard-grid]") !== ctx.root) return;
    const button = target.closest<HTMLButtonElement>("[data-dashboard-handle]"), widget = button?.closest<HTMLElement>("[data-dashboard-item]");
    const id = widget?.dataset.dashboardItem;
    if (button && id && widgets.get(id) === widget && !blocked(id)) return { button, id, kind: button.dataset.dashboardHandle ?? "move" };
  };
  ctx.on<KeyboardEvent>(ctx.root, "keydown", event => {
    if (event.key === "Escape" && gesture) { event.preventDefault(); cancel(); return; }
    const found = handle(event.target);
    if (!found || !["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)) return;
    event.preventDefault(); cancel();
    const next = moved(layout, found.id, found.kind, event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0,
      event.key === "ArrowDown" ? 1 : event.key === "ArrowUp" ? -1 : 0);
    if (next) commit(next, found.id, found.kind);
  });
  ctx.on<PointerEvent>(ctx.root, "pointerdown", event => {
    const found = handle(event.target);
    if (!found || event.button !== 0 || gesture) return;
    event.preventDefault(); found.button.focus({ preventScroll: true });
    const gap = parseFloat(getComputedStyle(cells).columnGap) || 0;
    gesture = { id: found.id, kind: found.kind, pointer: event.pointerId, x: event.clientX, y: event.clientY,
      step: (cells.clientWidth + gap) / columns, row: rowHeight + gap, before: layout, preview: layout };
    cells.setPointerCapture(event.pointerId); paint();
  });
  const preview = (event: PointerEvent) => {
    if (!gesture || event.pointerId !== gesture.pointer) return;
    const next = moved(gesture.before, gesture.id, gesture.kind, Math.round((event.clientX - gesture.x) / gesture.step), Math.round((event.clientY - gesture.y) / gesture.row));
    // A blocked destination restores the last committed layout, including locked widgets.
    gesture.preview = next ?? gesture.before; paint(gesture.preview);
  };
  ctx.on<PointerEvent>(ctx.root, "pointermove", preview);
  ctx.on<PointerEvent>(ctx.root, "pointerup", event => {
    if (!gesture || event.pointerId !== gesture.pointer) return;
    preview(event); const finished = gesture; cancel(); commit(finished.preview, finished.id, finished.kind);
  });
  ctx.on<PointerEvent>(ctx.root, "pointercancel", cancel);
  ctx.on<PointerEvent>(cells, "lostpointercapture", () => { if (gesture) cancel(); });
  const pendingObserver = new MutationObserver(() => sync());
  const sync = () => {
    if (disposed) return;
    if (field.form !== form) { pendingObserver.disconnect(); form = field.form; if (form) pendingObserver.observe(form, { attributes: true, attributeFilter: ["data-bml-action-pending"] }); }
    pending = form?.hasAttribute("data-bml-action-pending") ?? false;
    readonly = ctx.root.hasAttribute("data-readonly"); disabled = ctx.root.hasAttribute("data-disabled");
    field.name = ctx.root.dataset.name ?? "layout"; field.disabled = disabled; field.readOnly = readonly || disabled || pending;
    const config = [ctx.root.dataset.columns, ctx.root.dataset.cellHeight, ctx.root.dataset.stackBelow, ctx.root.dataset.lockedIds].join("|");
    const raw = ctx.root.dataset.value ?? "[]";
    if (config !== options || raw !== previous) {
      const changed = config !== options; options = config;
      try {
        columns = Number(ctx.root.dataset.columns); rowHeight = Number(ctx.root.dataset.cellHeight); stackBelow = Number(ctx.root.dataset.stackBelow);
        if (!Number.isSafeInteger(columns) || columns < 1 || columns > 100 || !Number.isFinite(rowHeight) || rowHeight < 1 || !Number.isFinite(stackBelow) || stackBelow < 0)
          throw new Error("Use 1–100 columns, a positive cellHeight and a nonnegative stackBelow width.");
        const locks: unknown = JSON.parse(ctx.root.dataset.lockedIds ?? "[]");
        if (!Array.isArray(locks) || !locks.every(id => typeof id === "string" && widgets.has(id))) throw new Error("lockedIds must be a JSON array of widget IDs.");
        lockedIds = new Set(locks);
        // Presentation changes validate the local draft; supplied value changes install a new reset default.
        if (raw !== previous) { previous = raw; load(raw); }
        else if (changed) { const draft = field.value; load(draft); field.defaultValue = previous; }
      } catch (error) { cancel(); invalid = error instanceof Error ? error.message : "Invalid grid options."; ctx.root.removeAttribute("data-grid-ready"); }
    }
    if (gesture && blocked(gesture.id)) cancel();
    paint(); report();
  };
  let width = cells.clientWidth;
  const resize = new ResizeObserver(() => { if (width !== cells.clientWidth) { width = cells.clientWidth; if (gesture) cancel(); paint(); } }); resize.observe(cells);
  ctx.on<Event>(document, "reset", event => { if (event.target === field.form) queueMicrotask(() => { if (!disposed && !event.defaultPrevented) { load(previous); sync(); } }); }, true);
  ctx.on<Event>(field, "invalid", event => { event.preventDefault(); field.hidden = false; field.focus(); });
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(ctx.root)) sync(); });
  ctx.onUnmount(() => { disposed = true; cancel(); pendingObserver.disconnect(); resize.disconnect(); ctx.root.removeAttribute("data-grid-ready"); });
  sync();
}
