import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Retains native table scrolling when its existing region survives an island replacement. */
export function retainTableScroll(ctx: ClientContext) {
  let top = ctx.root.scrollTop;
  let left = ctx.root.scrollLeft;
  ctx.on(ctx.root, "scroll", () => {
    top = ctx.root.scrollTop;
    left = ctx.root.scrollLeft;
  }, { passive: true });
  const restore = (update: IslandUpdate) => {
    if (!update.retained.some(root => root.contains(ctx.root))) return;
    ctx.root.scrollTop = top;
    ctx.root.scrollLeft = left;
  };
  ctx.onUpdate(restore);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) restore(event.detail);
  });
}
