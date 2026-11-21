import type { ClientContext, IslandUpdate } from "@bosca/bml";
import type { BoardMoveRequest } from "@bosca/bml-design/board";
export type { BoardMoveRequest } from "@bosca/bml-design/board";
interface Target { id: string; label: string }
interface Gesture { pointer: number; x: number; y: number; latestX: number; latestY: number; moved: boolean; target: HTMLElement | null }

/** Each preserved card owns its gesture and pending request; applications supply content and valid targets. */
export function mountBoardCard(ctx: ClientContext) {
  const card = ctx.root, handle = ctx.ref<HTMLButtonElement>("moveHandle"), form = ctx.ref<HTMLFormElement>("moveForm");
  const destination = ctx.ref<HTMLSelectElement>("destination"), status = ctx.ref<HTMLElement>("status");
  const error = ctx.ref<HTMLElement>("error"), dialogError = ctx.ref<HTMLElement>("dialogError");
  const modal = card.querySelector<HTMLElement>(":scope > [data-board-move-dialog]");
  if (!handle || !form || !destination || !status || !error || !dialogError || !modal) return;
  const view = card.ownerDocument.defaultView!;
  let disposed = false, pending = false, signature = "", invalid = "", targets: Target[] = [], suppressedUntil = 0;
  let gesture: Gesture | undefined, frame: number | undefined;
  const board = () => card.closest<HTMLElement>("[data-bml-board]");
  const column = () => card.closest<HTMLElement>("[data-board-column]");
  const columns = () => [...board()?.querySelectorAll<HTMLElement>("[data-board-column]") ?? []]
    .filter(value => value.closest("[data-bml-board]") === board());
  const offered = () => targets.filter(value => value.id !== column()?.dataset.boardColumn && columns().some(node => node.dataset.boardColumn === value.id));
  const blocked = () => disposed || !card.hasAttribute("data-movable") || card.hasAttribute("data-disabled") || pending || !!invalid
    || !board() || board()!.hasAttribute("data-board-readonly") || board()!.hasAttribute("data-board-disabled") || offered().length === 0;
  const report = (message: string, failed = false) => {
    status.hidden = !message || failed; status.textContent = failed ? "" : message;
    error.hidden = !message || !failed; error.textContent = failed ? message : "";
    dialogError.hidden = !message || !failed; dialogError.textContent = failed ? message : "";
  };
  const cancel = () => {
    const previous = gesture; gesture = undefined;
    if (previous?.moved) suppressedUntil = Date.now() + 250;
    if (frame !== undefined) view.cancelAnimationFrame(frame);
    frame = undefined;
    if (previous && handle.hasPointerCapture?.(previous.pointer)) handle.releasePointerCapture(previous.pointer);
    previous?.target?.removeAttribute("data-board-drop");
    card.removeAttribute("data-board-dragging");
  };
  const sync = () => {
    if (disposed) return;
    const raw = card.dataset.targets ?? "[]";
    if (raw !== signature) {
      signature = raw;
      const previousInvalid = invalid;
      try {
        const values: unknown = JSON.parse(raw), ids = new Set<string>();
        if (!Array.isArray(values)) throw new Error("Invalid destinations.");
        for (const value of values) {
          if (!value || typeof value !== "object" || typeof value.id !== "string" || !value.id || typeof value.label !== "string" || !value.label || ids.has(value.id)) throw new Error("Invalid destinations.");
          ids.add(value.id);
        }
        targets = values as Target[]; invalid = "";
        if (previousInvalid) report("");
      } catch { targets = []; invalid = "Move destinations are unavailable. Reload the board."; report(invalid, true); }
    }
    const values = [{ value: "", label: "Choose a column" }, ...offered().map(value => ({ value: value.id, label: value.label }))];
    const current = destination.value, options = JSON.stringify(values);
    if (destination.dataset.boardOptions !== options) {
      destination.dataset.boardOptions = options;
      destination.replaceChildren(...values.map(value => {
        const option = card.ownerDocument.createElement("option"); option.textContent = value.label; option.value = value.value; return option;
      }));
      destination.value = values.some(value => value.value === current) ? current : "";
      destination.closest("[data-bml-design-select]")?.dispatchEvent(new CustomEvent("selectoptions", { detail: values }));
      destination.dispatchEvent(new Event("change", { bubbles: true }));
    }
    const disabled = blocked();
    handle.hidden = !card.hasAttribute("data-movable") || board()?.hasAttribute("data-board-readonly") === true;
    const handleContainer = handle.closest<HTMLElement>("[data-board-move-handle]");
    if (handleContainer) handleContainer.hidden = handle.hidden;
    handle.disabled = disabled;
    destination.disabled = disabled;
    destination.closest<HTMLElement>("[data-bml-design-select]")?.toggleAttribute("data-disabled", disabled);
    for (const button of form.querySelectorAll<HTMLButtonElement>('button[type="submit"]')) button.disabled = disabled;
    card.toggleAttribute("data-board-pending", pending);
    card.setAttribute("aria-busy", String(pending));
    if (gesture && disabled) cancel();
    card.toggleAttribute("data-board-dragging", gesture?.moved === true);
    if (gesture?.moved) paint();
  };
  const requestMove = async (id: string) => {
    const target = offered().find(value => value.id === id);
    if (blocked() || !target) { if (!pending) report("Choose an available destination.", true); return; }
    pending = true; sync(); report("Moving…");
    const waits: Promise<unknown>[] = []; let completed = false;
    const request: BoardMoveRequest = { cardId: card.dataset.boardCard ?? "", columnId: id,
      waitUntil(promise) { waits.push(Promise.resolve(promise)); } };
    try {
      const event = new CustomEvent<BoardMoveRequest>("boardmove", { bubbles: true, detail: request });
      card.dispatchEvent(event);
      if (!waits.length) throw new Error("Moving is unavailable.");
      await Promise.all(waits);
      if (disposed) return;
      completed = true;
      report(`Moved to ${target.label}.`);
      modal.dispatchEvent(new CustomEvent("dialogclose"));
    } catch (failure) {
      if (disposed) return;
      const message = failure instanceof Error && failure.message ? failure.message : "The item could not be moved. Try again.";
      report(message, true);
      card.dispatchEvent(new CustomEvent("boarderror", { bubbles: true, detail: { cardId: request.cardId, columnId: id, message } }));
    } finally {
      if (!disposed) {
        pending = false; sync();
        if (completed) (handle.disabled ? card : handle).focus({ preventScroll: true });
      }
    }
  };
  const point = (x: number, y: number) => {
    const target = card.ownerDocument.elementFromPoint(x, y)?.closest<HTMLElement>("[data-board-column]");
    return target && target.closest("[data-bml-board]") === board() ? target : null;
  };
  const paint = () => {
    if (!gesture?.moved) return;
    const target = point(gesture.latestX, gesture.latestY);
    if (gesture.target !== target) gesture.target?.removeAttribute("data-board-drop");
    gesture.target = target;
    target?.setAttribute("data-board-drop", offered().some(value => value.id === target.dataset.boardColumn) ? "allowed" : "blocked");
  };
  const scroll = () => {
    frame = undefined;
    if (!gesture?.moved) return;
    const parent = board(), bounds = parent?.getBoundingClientRect();
    if (parent && bounds) {
      if (gesture.latestX < bounds.left + 32) parent.scrollLeft -= 14;
      else if (gesture.latestX > bounds.right - 32) parent.scrollLeft += 14;
    }
    const items = gesture.target?.querySelector<HTMLElement>("[data-board-column-items]"), area = items?.getBoundingClientRect();
    if (items && area) {
      if (gesture.latestY < area.top + 32) items.scrollTop -= 14;
      else if (gesture.latestY > area.bottom - 32) items.scrollTop += 14;
    }
    paint(); frame = view.requestAnimationFrame(scroll);
  };
  ctx.on<PointerEvent>(handle, "pointerdown", event => {
    if (event.button !== 0 || blocked() || gesture) return;
    handle.focus({ preventScroll: true });
    gesture = { pointer: event.pointerId, x: event.clientX, y: event.clientY, latestX: event.clientX, latestY: event.clientY, moved: false, target: null };
  });
  ctx.on<PointerEvent>(document, "pointermove", event => {
    if (!gesture || gesture.pointer !== event.pointerId) return;
    gesture.latestX = event.clientX; gesture.latestY = event.clientY;
    if (!gesture.moved && Math.hypot(event.clientX - gesture.x, event.clientY - gesture.y) >= 8) {
      gesture.moved = true; card.setAttribute("data-board-dragging", ""); handle.setPointerCapture?.(event.pointerId);
      frame = view.requestAnimationFrame(scroll);
    }
    if (gesture.moved) { event.preventDefault(); paint(); }
  });
  ctx.on<PointerEvent>(document, "pointerup", event => {
    if (!gesture || gesture.pointer !== event.pointerId) return;
    gesture.latestX = event.clientX; gesture.latestY = event.clientY; paint();
    const moved = gesture.moved, target = gesture.target?.dataset.boardColumn;
    cancel();
    if (moved) { event.preventDefault(); if (target) void requestMove(target); handle.focus({ preventScroll: true }); }
  });
  ctx.on<PointerEvent>(document, "pointercancel", event => { if (gesture?.pointer === event.pointerId) cancel(); });
  ctx.on<PointerEvent>(handle, "lostpointercapture", event => {
    if (event.target === handle && gesture?.pointer === event.pointerId) cancel();
  });
  ctx.on<KeyboardEvent>(document, "keydown", event => {
    if (event.key === "Escape" && gesture) { event.preventDefault(); cancel(); report("Move canceled."); handle.focus({ preventScroll: true }); }
  });
  ctx.on<MouseEvent>(handle, "click", event => {
    if (event.detail > 0 && Date.now() < suppressedUntil) { event.preventDefault(); return; }
    if (blocked()) return;
    report(""); sync(); modal.dispatchEvent(new CustomEvent("dialogopen"));
    const panel = modal.querySelector("dialog"); if (panel?.id) handle.setAttribute("aria-controls", panel.id);
  });
  ctx.on(form, "submit", event => { event.preventDefault(); void requestMove(destination.value); });
  ctx.on(modal, "dialogclosed", () => { if (!disposed && !handle.disabled) handle.focus({ preventScroll: true }); });
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(card)) sync(); });
  const observer = new MutationObserver(sync);
  observer.observe(card, { attributes: true, attributeFilter: ["data-targets", "data-movable", "data-disabled"] });
  ctx.onUnmount(() => { disposed = true; cancel(); observer.disconnect(); });
  sync();
}
