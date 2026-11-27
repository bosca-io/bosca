import type { ClientContext } from "@bosca/bml";

/** Resolve editor references within Atlas while retaining the originating WorkOps location. */
export function workOpsReferenceLinks(ctx: ClientContext) {
  ctx.on<CustomEvent<{ id?: string; entityType?: string }>>(ctx.root, "documentmentionactivate", event => {
    const { id, entityType } = event.detail;
    if (!id || !entityType || !["metadata", "collection", "profile"].includes(entityType)) return;
    event.stopPropagation();
    const current = new URL(location.href);
    const returnTo = current.pathname.startsWith("/workops/references/")
      ? current.searchParams.get("returnTo") || "/workops/tasks" : current.pathname + current.search;
    const query = new URLSearchParams({ returnTo });
    location.assign(`/workops/references/${encodeURIComponent(entityType)}/${encodeURIComponent(id)}?${query}`);
  });
}
