import type { ClientContext } from "@bosca/bml";

/** Git owns preview, uncommitted-change protection and post-commit navigation around the shared field. */
export function mountFileEditor(
  ctx: ClientContext,
  preview: (id: string, ref: string, path: string, source: string) => Promise<string>,
) {
  const surface = ctx.ref<HTMLElement>("codeField");
  if (!surface) return;
  const content = () => surface.querySelector<HTMLTextAreaElement>("textarea[name=content]")?.value ?? "";
  const original = content();
  const originalPath = ctx.ref<HTMLInputElement>("path")?.value ?? "";
  let container = surface.closest<HTMLElement>("form") ?? ctx.root;
  let dirty = false, wrapEnabled = false, previewRevision = 0, disposed = false;
  const updateDirty = () => {
    dirty = content() !== original || (ctx.ref<HTMLInputElement>("path")?.value ?? "") !== originalPath;
    container.dataset.dirty = String(dirty);
  };
  ctx.on<Event>(ctx.root, "input", event => {
    if (event.target === ctx.ref<HTMLInputElement>("path")) {
      const path = ctx.ref<HTMLInputElement>("path")?.value ?? "";
      surface.dataset.path = path;
      updateDirty();
    } else if (event.target instanceof HTMLTextAreaElement && surface.contains(event.target)) updateDirty();
  });
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    const button = event.target instanceof Element ? event.target.closest<HTMLButtonElement>("[data-bml-ref=wrap]") : null;
    if (!button) return;
    wrapEnabled = !wrapEnabled;
    surface.toggleAttribute("data-wrapped", wrapEnabled);
    button.setAttribute("aria-pressed", String(wrapEnabled));
  });
  ctx.on<MouseEvent>(ctx.root, "click", async event => {
    const button = event.target instanceof Element ? event.target.closest<HTMLButtonElement>("[data-bml-ref=preview]") : null;
    const previewSurface = ctx.ref<HTMLElement>("previewSurface"), status = ctx.ref<HTMLElement>("previewStatus");
    if (!button || !previewSurface) return;
    const showing = button.getAttribute("aria-pressed") !== "true";
    button.setAttribute("aria-pressed", String(showing));
    surface.hidden = showing;
    previewSurface.hidden = !showing;
    const revision = ++previewRevision;
    if (!showing) { if (status) status.hidden = true; return; }
    if (status) { status.textContent = "Rendering preview…"; status.hidden = false; }
    try {
      const rendered = await preview(container.dataset.repository ?? "", container.dataset.ref ?? "", ctx.ref<HTMLInputElement>("path")?.value ?? "", content());
      if (disposed || revision !== previewRevision) return;
      previewSurface.innerHTML = rendered;
      if (status) status.hidden = true;
    } catch (error) {
      if (!disposed && revision === previewRevision && status) status.textContent = error instanceof Error ? error.message : "Preview failed.";
    }
  });
  ctx.on<BeforeUnloadEvent>(window, "beforeunload", event => {
    if (dirty) { event.preventDefault(); event.returnValue = ""; }
  });
  ctx.on<MouseEvent>(document, "click", event => {
    const anchor = event.target instanceof Element ? event.target.closest<HTMLAnchorElement>("a[href]") : null;
    if (!dirty || !anchor || anchor.target === "_blank" || anchor.hasAttribute("download")) return;
    if (!confirm("Discard your uncommitted changes?")) { event.preventDefault(); event.stopImmediatePropagation(); }
    else dirty = false;
  }, true);
  ctx.onUpdate(() => {
    previewRevision++;
    container = surface.closest<HTMLElement>("form") ?? ctx.root;
    surface.toggleAttribute("data-wrapped", wrapEnabled);
    surface.dataset.path = ctx.ref<HTMLInputElement>("path")?.value ?? "";
    ctx.ref<HTMLButtonElement>("wrap")?.setAttribute("aria-pressed", String(wrapEnabled));
    if (container.dataset.nextHref) { dirty = false; location.assign(container.dataset.nextHref); }
  });
  ctx.onUnmount(() => { disposed = true; previewRevision++; });
}
