import type { ClientContext, IslandUpdate } from "@bosca/bml";
import "./calendar.css";

export interface CalendarEvent {
  id: string; title: string; startsAt: string; endsAt: string;
  allDay?: boolean; calendarId?: string; editable?: boolean;
}
const dayStart = (date: Date) => { const d = new Date(date); d.setHours(0, 0, 0, 0); return d; };
export const addDays = (date: Date, count: number) => { const d = new Date(date); d.setDate(d.getDate() + count); return d; };
export const dateKey = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
export const startOfWeek = (date: Date) => addDays(dayStart(date), -(date.getDay() + 6) % 7);
export const monthDays = (date: Date) => {
  const first = new Date(date.getFullYear(), date.getMonth(), 1);
  return Array.from({ length: 42 }, (_, i) => addDays(startOfWeek(first), i));
};
export interface CalendarMoveRequest {
  eventId: string; startsAt: string; endsAt: string;
  /** Register the application save promise so rejection restores the controlled occurrence. */
  waitUntil(promise: Promise<unknown>): void;
}
const eventDate = (raw: string) => new Date(/^\d{4}-\d{2}-\d{2}$/.test(raw) ? `${raw}T00:00:00` : raw);
export const intersects = (event: CalendarEvent, day: Date) => {
  const start = eventDate(event.startsAt).getTime(), end = eventDate(event.endsAt).getTime();
  return start < addDays(dayStart(day), 1).getTime() && end > dayStart(day).getTime();
};
const time = (d: Date) => `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
const minute = (d: Date) => d.getHours() * 60 + d.getMinutes();
const atMinute = (day: Date, n: number) => { const d = dayStart(day); d.setMinutes(n); return d; };
const wallMinute = (date: Date, day: Date) => Math.round((Date.UTC(date.getFullYear(),date.getMonth(),date.getDate())-Date.UTC(day.getFullYear(),day.getMonth(),day.getDate()))/86400000)*1440+minute(date);
const snap = (n: number) => Math.round(n / 15) * 15;

/** Component-owned calendar lifecycle; applications persist the emitted change requests. */
export function mountCalendar(ctx: ClientContext) {
  const host = ctx.ref<HTMLElement>("calendar"), status = ctx.ref<HTMLElement>("status");
  if (!host || !status) return;
  let events: CalendarEvent[] = [], days: Date[] = [], view = "month", readOnly = false;
  let previous = "", suppressedUntil = 0, saving = false, disposed = false;
  let pendingDay: ReturnType<typeof setTimeout> | undefined;
  let drag: { event: CalendarEvent; node: HTMLElement; pointerId: number; x: number; y: number; day: Date; resize: boolean; moved: boolean; scrollTop: number; start: Date; end: Date } | undefined;
  const emit = (name: string, detail: unknown) => ctx.root.dispatchEvent(new CustomEvent(name, { bubbles: true, detail }));
  const element = <K extends keyof HTMLElementTagNameMap>(tag: K, cls: string, text = "") => {
    const node = document.createElement(tag); node.className = cls; node.textContent = text; return node;
  };
  const button = (cls: string, text: string) => { const node = element("button", cls, text); node.type = "button"; return node; };
  let colors: Record<string, string> = {};
  let restorePreview: (() => void) | undefined;
  let dropTarget: HTMLElement | undefined;
  const chip = (event: CalendarEvent, timed = false, resizable = timed) => {
    const node = button(`calendar-event${timed ? " calendar-timed" : ""}`, `${event.allDay ? "" : time(new Date(event.startsAt)) + " "}${event.title}`);
    node.dataset.event = event.id;
    node.title = `${event.title} · ${event.startsAt} – ${event.endsAt}`;
    node.setAttribute("aria-label", node.title);
    const color = colors[event.calendarId ?? ""];
    if (color && CSS.supports("color", color)) node.style.setProperty("--calendar-color", color);
    if (event.editable && !readOnly) {
      node.dataset.editable = "";
      if (resizable) { const handle = element("span", "calendar-resize"); handle.dataset.resize = ""; node.append(handle); }
    }
    return node;
  };
  const cancel = () => {
    if (drag?.moved) suppressedUntil = Date.now() + 250;
    const hadPreview = Boolean(restorePreview);
    restorePreview?.(); restorePreview = undefined;
    dropTarget?.classList.remove("calendar-drop-target"); dropTarget = undefined;
    drag = undefined;
    if (hadPreview) previous = "";
    ctx.root.removeAttribute("data-drag-pending");
  };
  const requestMove = (eventId: string, start: Date, end: Date, allDay = false) => {
    const waits: Promise<unknown>[] = [];
    saving = true; status.setAttribute("role", "status");
    ctx.root.setAttribute("data-drag-pending", "");
    const detail: CalendarMoveRequest = { eventId,
      startsAt: allDay ? dateKey(start) : start.toISOString(), endsAt: allDay ? dateKey(end) : end.toISOString(),
      waitUntil(promise) { waits.push(Promise.resolve(promise)); },
    };
    emit("calendarmoveevent", detail);
    void Promise.all(waits).then(() => {
      if (disposed) return;
      saving = false; cancel(); render(); status.textContent = "Event saved.";
    }, error => {
      if (disposed) return;
      saving = false; cancel(); render();
      status.textContent = error instanceof Error ? error.message : "The event could not be saved.";
      status.setAttribute("role", "alert");
      emit("calendarerror", {eventId, message: status.textContent});
    });
  };
  const render = () => {
    const signature = [ctx.root.dataset.current, ctx.root.dataset.view, ctx.root.dataset.events, ctx.root.dataset.colors, ctx.root.hasAttribute("data-readonly")].join("|");
    if (signature === previous) return;
    const scroll = host.scrollTop, horizontalScroll = host.scrollLeft;
    const focused = host.contains(document.activeElement) ? document.activeElement as HTMLElement : undefined;
    const focusEvent = focused?.dataset.event, focusDay = focused?.closest<HTMLElement>("[data-day]")?.dataset.day;
    const focusHour = focused?.dataset.hour, focusSelectDay = focused?.dataset.selectDay;
    cancel();
    const current = new Date(ctx.root.dataset.current ?? "");
    // Date-only values are calendar dates, independent of the browser's UTC offset.
    if (/^\d{4}-\d{2}-\d{2}$/.test(ctx.root.dataset.current ?? "")) current.setTime(new Date(`${ctx.root.dataset.current}T00:00:00`).getTime());
    if (!Number.isFinite(current.getTime())) throw new Error("Calendar current must be an ISO date or timestamp.");
    view = ctx.root.dataset.view ?? "month";
    if (!["month", "week", "day"].includes(view)) throw new Error(`Unsupported calendar view: ${view}`);
    events = JSON.parse(ctx.root.dataset.events ?? "[]");
    colors = JSON.parse(ctx.root.dataset.colors ?? "{}");
    const ids = new Set<string>();
    for (const event of events) {
      if (!event.id || ids.has(event.id) || !Number.isFinite(Date.parse(event.startsAt)) || !(Date.parse(event.endsAt) > Date.parse(event.startsAt))) throw new Error("Calendar events require unique IDs and a valid positive time range.");
      ids.add(event.id);
    }
    readOnly = ctx.root.hasAttribute("data-readonly");
    days = view === "month" ? monthDays(current) : Array.from({ length: view === "week" ? 7 : 1 }, (_, i) => addDays(view === "week" ? startOfWeek(current) : dayStart(current), i));
    host.className = `calendar-body calendar-${view}`;
    const grid = element("div", "calendar-grid");
    if (view !== "month") grid.append(element("div", "calendar-heading", ""));
    for (const day of days.slice(0, view === "month" ? 7 : days.length)) {
      const heading = element("div", "calendar-heading", day.toLocaleDateString(undefined, { weekday: "short", ...(view !== "month" ? { day: "numeric" } : {}) }));
      if (dateKey(day) === dateKey(new Date())) heading.dataset.today = "";
      grid.append(heading);
    }
    if (view === "month") for (const day of days) {
      const cell = element("div", "calendar-cell"); cell.dataset.day = dateKey(day);
      if (day.getMonth() !== current.getMonth()) cell.dataset.other = "";
      const select = button("calendar-date", String(day.getDate())); select.dataset.selectDay = dateKey(day); select.setAttribute("aria-label", day.toDateString());
      if (dateKey(day) === dateKey(new Date())) select.setAttribute("aria-current", "date");
      cell.append(select);
      if (!readOnly) {
        const create = button("calendar-add", "+"); create.dataset.newDay = dateKey(day);
        create.setAttribute("aria-label", `New event on ${day.toDateString()}`); cell.append(create);
      }
      const matches = events.filter(event => intersects(event, day));
      for (const event of matches.slice(0, 3)) cell.append(chip(event));
      if (matches.length > 3) { const more = button("calendar-more", `+${matches.length - 3} more`); more.dataset.selectDay = dateKey(day); cell.append(more); }
      grid.append(cell);
    } else {
      grid.append(element("div", "calendar-allday-label", "All day"));
      for (const day of days) {
        const cell = element("div", "calendar-allday"); cell.dataset.day = dateKey(day);
        for (const event of events.filter(event => event.allDay && intersects(event, day))) cell.append(chip(event));
        grid.append(cell);
      }
      const gutter = element("div", "calendar-gutter");
      for (let hour = 0; hour < 24; hour++) gutter.append(element("div", "calendar-hour", `${hour === 0 ? 12 : hour > 12 ? hour - 12 : hour} ${hour < 12 ? "AM" : "PM"}`));
      grid.append(gutter);
      for (const day of days) {
        const column = element("div", "calendar-column"); column.dataset.day = dateKey(day);
        for (let hour = 0; hour < 24; hour++) {
          const slot = button("calendar-slot", ""); slot.dataset.hour = String(hour); slot.setAttribute("aria-label", `${day.toDateString()} ${hour}:00`); column.append(slot);
        }
        for (const event of events.filter(event => !event.allDay && intersects(event, day))) {
          const node = chip(event, true, Date.parse(event.endsAt) <= addDays(day, 1).getTime()), start = new Date(Math.max(Date.parse(event.startsAt), day.getTime())), end = new Date(Math.min(Date.parse(event.endsAt), addDays(day, 1).getTime()));
          node.style.top = `${minute(start) * .8}px`;
          node.style.height = `${Math.max(20, (wallMinute(end, day) - wallMinute(start, day)) * .8)}px`;
          column.append(node);
        }
        grid.append(column);
      }
    }
    host.replaceChildren(grid); host.scrollTop = scroll; host.scrollLeft = horizontalScroll; previous = signature;
    if (focused) {
      const replacement = Array.from(host.querySelectorAll<HTMLElement>("button")).find(node =>
        focusEvent ? node.dataset.event === focusEvent && node.closest<HTMLElement>("[data-day]")?.dataset.day === focusDay :
        focusHour !== undefined ? node.dataset.hour === focusHour && node.closest<HTMLElement>("[data-day]")?.dataset.day === focusDay :
        Boolean(focusSelectDay) && node.dataset.selectDay === focusSelectDay);
      replacement?.focus({ preventScroll: true });
    }
  };
  ctx.on<MouseEvent>(host, "click", event => {
    if (Date.now() < suppressedUntil && event.detail > 0) { suppressedUntil = 0; return; }
    const target = (event.target as Element).closest<HTMLElement>("[data-event], [data-select-day], [data-new-day], [data-hour], .calendar-cell");
    if (!target) return;
    if (target.dataset.newDay) emit("calendarnewevent", { day: target.dataset.newDay });
    else if (target.dataset.event) { const selected = events.find(e => e.id === target.dataset.event); if (selected) emit("calendarselectevent", { event: selected }); }
    else if (target.dataset.hour !== undefined) emit("calendarselectslot", { day: target.closest<HTMLElement>("[data-day]")?.dataset.day, hour: Number(target.dataset.hour) });
    else {
      const detail = { day: target.dataset.selectDay ?? target.dataset.day };
      if (pendingDay !== undefined) clearTimeout(pendingDay);
      if (event.detail === 0) emit("calendarselectday", detail);
      else if (event.detail === 1) pendingDay = setTimeout(() => { pendingDay = undefined; emit("calendarselectday", detail); }, 250);
    }
  });
  ctx.on<MouseEvent>(host, "dblclick", event => {
    const target = (event.target as Element).closest<HTMLElement>(".calendar-cell");
    if (target && !(event.target as Element).closest("[data-event]")) {
      if (pendingDay !== undefined) clearTimeout(pendingDay);
      pendingDay = undefined;
      if (!readOnly) emit("calendarnewevent", { day: target.dataset.day });
    }
  });
  ctx.on<PointerEvent>(host, "pointerdown", event => {
    if (event.button !== 0) return;
    suppressedUntil = 0;
    if (readOnly || saving || drag) return;
    status.setAttribute("role", "status");
    const node = (event.target as Element).closest<HTMLElement>("[data-event][data-editable]");
    const selected = events.find(e => e.id === node?.dataset.event);
    const day = node?.closest<HTMLElement>("[data-day]")?.dataset.day;
    if (!node || !selected || !day) return;
    drag = { event: selected, node, pointerId: event.pointerId, x: event.clientX, y: event.clientY, day: new Date(`${day}T00:00:00`), resize: Boolean((event.target as Element).closest("[data-resize]")), moved: false, scrollTop: host.scrollTop, start: eventDate(selected.startsAt), end: eventDate(selected.endsAt) };
  });
  ctx.on<PointerEvent>(document, "pointermove", event => {
    if (!drag || event.pointerId !== drag.pointerId || (Math.abs(event.clientX - drag.x) < 5 && Math.abs(event.clientY - drag.y) < 5 && !drag.moved)) return;
    event.preventDefault(); drag.moved = true;
    const cell = Array.from(host.querySelectorAll<HTMLElement>(view === "month" ? ".calendar-cell" : drag.event.allDay ? ".calendar-allday" : ".calendar-column")).find(node => { const r = node.getBoundingClientRect(); return event.clientX >= r.left && event.clientX < r.right && event.clientY >= r.top && event.clientY < r.bottom; });
    if (!cell?.dataset.day) return;
    const target = drag.resize ? drag.node.closest<HTMLElement>("[data-day]") ?? cell : cell;
    const day = new Date(`${target.dataset.day}T00:00:00`), originalStart = eventDate(drag.event.startsAt), originalEnd = eventDate(drag.event.endsAt);
    if (!restorePreview) {
      const node = drag.node, parent = node.parentNode, sibling = node.nextSibling;
      const style = node.style.cssText, label = node.firstChild?.textContent ?? "";
      const copies = Array.from(host.querySelectorAll<HTMLElement>("[data-event]")).filter(other => other !== node && other.dataset.event === node.dataset.event);
      copies.forEach(other => other.classList.add("calendar-drag-source"));
      restorePreview = () => {
        if (parent) parent.insertBefore(node, sibling?.parentNode === parent ? sibling : null);
        node.style.cssText = style;
        if (node.firstChild) node.firstChild.textContent = label;
        node.classList.remove("calendar-dragging");
        copies.forEach(other => other.classList.remove("calendar-drag-source"));
      };
    }
    dropTarget?.classList.remove("calendar-drop-target"); dropTarget = target;
    dropTarget.classList.add("calendar-drop-target");
    if (view === "month" || drag.event.allDay) {
      // Shift by calendar days, retaining the occurrence's time and elapsed duration.
      const delta = Math.round((Date.UTC(day.getFullYear(), day.getMonth(), day.getDate()) - Date.UTC(drag.day.getFullYear(), drag.day.getMonth(), drag.day.getDate())) / 86400000);
      drag.start = addDays(originalStart, delta); drag.end = new Date(drag.start.getTime() + originalEnd.getTime() - originalStart.getTime());
    } else {
      const delta = snap((event.clientY - drag.y + host.scrollTop - drag.scrollTop) / .8);
      if (drag.resize) {
        drag.end = new Date(Math.max(originalStart.getTime() + 15 * 60000, Math.min(addDays(drag.day, 1).getTime(), originalEnd.getTime() + delta * 60000)));
      } else {
        const dayDelta = Math.round((Date.UTC(day.getFullYear(),day.getMonth(),day.getDate()) - Date.UTC(drag.day.getFullYear(),drag.day.getMonth(),drag.day.getDate())) / 86400000);
        const shifted = addDays(originalStart, dayDelta);
        if (dateKey(originalStart) === dateKey(drag.day)) drag.start = atMinute(day, Math.max(0, Math.min(1425, minute(originalStart) + delta)));
        else { shifted.setMinutes(shifted.getMinutes() + delta); drag.start = shifted; }
        drag.end = new Date(drag.start.getTime() + originalEnd.getTime() - originalStart.getTime());
      }
    }
    if (drag.node.parentNode !== target) {
      const focused = document.activeElement === drag.node;
      target.insertBefore(drag.node, target.querySelector(".calendar-more"));
      if (focused) drag.node.focus({ preventScroll: true });
    }
    if (view !== "month" && !drag.event.allDay) {
      const visibleStart = new Date(Math.max(drag.start.getTime(), day.getTime()));
      const visibleEnd = Math.min(drag.end.getTime(), addDays(day, 1).getTime());
      drag.node.style.top = `${minute(visibleStart) * .8}px`;
      drag.node.style.height = `${Math.max(20, (wallMinute(new Date(visibleEnd), day) - wallMinute(visibleStart, day)) * .8)}px`;
    }
    if (drag.node.firstChild) drag.node.firstChild.textContent = `${drag.event.allDay ? "" : time(drag.start) + " – " + time(drag.end) + " "}${drag.event.title}`;
    drag.node.classList.add("calendar-dragging");
    status.textContent = `${drag.event.title}: ${drag.start.toLocaleString()} – ${drag.end.toLocaleString()}`;
  });
  ctx.on<PointerEvent>(document, "pointerup", event => {
    if (!drag || event.pointerId !== drag.pointerId) return;
    const completed = drag;
    if (!completed.moved || !restorePreview) { cancel(); return; }
    drag = undefined;
    previous = "";
    suppressedUntil = Date.now() + 250;
    requestMove(completed.event.id, completed.start, completed.end, completed.event.allDay);
  });
  ctx.on<PointerEvent>(document, "pointercancel", event => { if (drag && event.pointerId === drag.pointerId) { cancel(); render(); } });
  ctx.on<KeyboardEvent>(document, "keydown", event => {
    if (event.key === "Escape" && drag) {
      cancel(); render(); status.textContent = "Move cancelled.";
    }
  });
  ctx.on<KeyboardEvent>(host, "keydown", event => {
    // Keyboard edits request the same application action as pointer edits.
    if (!event.altKey || !["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key) || readOnly || saving) return;
    const node = (event.target as Element).closest<HTMLElement>("[data-event][data-editable]");
    const selected = events.find(e => e.id === node?.dataset.event); if (!selected) return;
    event.preventDefault();
    let start = eventDate(selected.startsAt), end = eventDate(selected.endsAt);
    if (selected.allDay) {
      const n=event.key==="ArrowLeft" || event.key==="ArrowUp" ? -1 : 1;
      if (!event.shiftKey || event.key==="ArrowLeft" || event.key==="ArrowRight") start=addDays(start,n);
      end=addDays(end,n);
    } else if (event.key === "ArrowLeft" || event.key === "ArrowRight") { const n = event.key === "ArrowLeft" ? -1 : 1; start = addDays(start, n); end = addDays(end, n); }
    else { const n = event.key === "ArrowUp" ? -15 : 15; if (!event.shiftKey) start = new Date(start.getTime() + n * 60000); end = new Date(end.getTime() + n * 60000); }
    if (end > start) requestMove(selected.id, start, end, selected.allDay);
  });
  ctx.onUpdate(render);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) render();
  });
  ctx.onUnmount(() => { disposed = true; cancel(); if (pendingDay !== undefined) clearTimeout(pendingDay); });
  render();
}
