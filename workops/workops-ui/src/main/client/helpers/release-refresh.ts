import type { IslandContext, IslandUpdate } from "@bosca/bml";

/** Coordinates preserved WorkOps release sections with their overview and their own polling. */
export function workOpsReleaseRefresh(ctx: IslandContext, options: {
  active?: () => boolean;
  intervalMs?: number;
  settleMs?: number;
  pauseForDialog?: boolean;
  initialRevision?: string;
  contextual?: boolean;
} = {}) {
  const context = ctx.root.closest<HTMLElement>("[data-workops-release-context]");
  let revision = options.initialRevision ?? context?.dataset.workopsReleaseContext;
  let disposed = false, busy = false, queued = false, forced = false;
  let scheduled: ReturnType<typeof setTimeout> | undefined;
  const report = (message: string) => { const target = ctx.ref<HTMLElement>("pollError"); if (target) target.textContent = message; };
  const drain = async () => {
    if (disposed || busy || !queued) return;
    if (!forced && options.active && !options.active()) { queued = false; return; }
    if (document.hidden ||
      (options.pauseForDialog && ctx.root.querySelector("dialog[open]")) ||
      ctx.root.querySelector("[data-bml-action-pending]")) return;
    queued = false; forced = false; busy = true;
    try {
      if (options.contextual) await ctx.dispatch("model", "refreshContext", [revision ?? ""]);
      else await ctx.dispatch("model", "refresh");
      if (!disposed) report("");
    }
    catch (error) { if (!disposed) report(error instanceof Error ? error.message : "Release section refresh failed."); }
    finally { busy = false; if (queued && !disposed) schedule(); }
  };
  const schedule = () => {
    if (disposed || scheduled !== undefined) return;
    scheduled = setTimeout(() => { scheduled = undefined; void drain(); }, 0);
  };
  const updated = () => {
    const next = context?.dataset.workopsReleaseContext;
    if (context && next !== revision) { revision = next; queued = true; forced = true; }
    if (queued) schedule();
  };
  ctx.onUpdate(updated);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(ctx.root)) updated(); });
  ctx.on(document, "visibilitychange", () => { if (!document.hidden && queued) schedule(); });
  const timer = options.active ? setInterval(() => {
    if (!busy && !disposed && options.active?.()) { queued = true; void drain(); }
  }, options.intervalMs ?? 6000) : undefined;
  const settle = options.settleMs === undefined ? undefined : setTimeout(() => { queued = true; forced = true; void drain(); }, options.settleMs);
  ctx.onUnmount(() => {
    disposed = true; queued = false;
    if (timer !== undefined) clearInterval(timer);
    if (settle !== undefined) clearTimeout(settle);
    if (scheduled !== undefined) clearTimeout(scheduled);
  });
  updated();
}
