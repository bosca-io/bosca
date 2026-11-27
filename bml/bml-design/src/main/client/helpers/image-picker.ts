import type { ClientContext } from "@bosca/bml";
import type { SelectSearch } from "@bosca/bml-design/select";
import type { ImageChoice, ImagePage, ImagePickerOpen, ImageSelection } from "@bosca/bml-design/images";
import { imageChoice, imageRequest, imageURL } from "./image-requests";

/** A reusable image chooser composed from the library's dialog, select and file controls. */
export function mountImagePicker(ctx: ClientContext) {
  const dialog = ctx.root.querySelector<HTMLElement>("[data-image-dialog]");
  const choice = ctx.ref<HTMLSelectElement>("imageChoice"), drop = ctx.ref<HTMLElement>("imageDrop");
  const upload = ctx.ref<HTMLButtonElement>("imageUpload"), cancelUpload = ctx.ref<HTMLButtonElement>("imageCancelUpload");
  const apply = ctx.ref<HTMLButtonElement>("imageApply"), cancel = ctx.ref<HTMLButtonElement>("imageCancel");
  const url = ctx.ref<HTMLInputElement>("imageUrl"), alt = ctx.ref<HTMLInputElement>("imageAlt"), title = ctx.ref<HTMLInputElement>("imageTitle");
  const preview = ctx.ref<HTMLElement>("imagePreview"), image = ctx.ref<HTMLImageElement>("imageImage"), caption = ctx.ref<HTMLElement>("imageCaption");
  const status = ctx.ref<HTMLElement>("imageStatus"), fileLabel = ctx.ref<HTMLElement>("imageFile"), progressRoot = ctx.ref<HTMLElement>("imageUploadProgress");
  const progress = progressRoot?.querySelector("progress");
  if (!dialog || !choice || !drop || !upload || !cancelUpload || !apply || !cancel || !url || !alt || !title ||
      !preview || !image || !caption || !status || !fileLabel || !progressRoot || !progress) return;
  let active = false, disposed = false, busy = false, uploading = false;
  let selected: { id?: string; src: string; label: string } | null = null;
  let file: File | null = null, uploaded = false;
  let operation: AbortController | undefined, search: AbortController | undefined;
  let attempt: ImagePickerOpen | undefined;
  const enabled = () => !disposed && !ctx.root.hasAttribute("data-disabled");
  const current = (controller: AbortController) => active && enabled() && !controller.signal.aborted;
  const message = (text: string, failed = false) => {
    status.textContent = text; status.hidden = !text; status.toggleAttribute("data-error", failed);
  };
  const syncControls = () => {
    const locked = !enabled() || busy;
    choice.disabled = locked;
    choice.closest("[data-bml-design-select]")?.toggleAttribute("data-disabled", locked);
    drop.toggleAttribute("data-disabled", locked);
    upload.disabled = locked || !file || uploaded;
    apply.disabled = locked || !selected;
    cancelUpload.hidden = !uploading;
    for (const field of [url, alt, title]) field.disabled = locked;
    const useUrl = ctx.ref<HTMLButtonElement>("imageUseUrl"); if (useUrl) useUrl.disabled = locked;
  };
  const show = (value: { id?: string; src: string; label: string } | null) => {
    selected = value; preview.hidden = !value;
    if (value) { image.src = value.src; caption.textContent = value.label; }
    else { image.removeAttribute("src"); caption.textContent = ""; }
    syncControls();
  };
  const stop = () => { operation?.abort(); search?.abort(); busy = false; uploading = false; syncControls(); };
  const close = (selection?: ImageSelection) => {
    if (!active) return;
    const callback = attempt; attempt = undefined; active = false; stop();
    dialog.dispatchEvent(new Event("dialogclose"));
    if (selection) {
      callback?.select(selection);
      ctx.root.dispatchEvent(new CustomEvent<ImageSelection>("imageselect", { bubbles: true, detail: selection }));
    } else callback?.cancel();
  };
  const resolve = async (id: string) => {
    operation?.abort(); const controller = operation = new AbortController(); busy = true; uploading = false; syncControls(); message("Loading image…");
    try {
      const result = await imageRequest<ImageChoice | null>(ctx.root, { kind: "resolve", id }, controller.signal);
      if (!current(controller)) return;
      if (!result) { show(null); message("This image is unavailable or you do not have access to it.", true); return; }
      show(imageChoice(result)); message("");
    } catch { if (current(controller)) { show(null); message("Could not load the image. Choose it again to retry.", true); } }
    finally { if (operation === controller) { busy = false; syncControls(); } }
  };
  const begin = (request?: ImagePickerOpen) => {
    if (!enabled()) { request?.cancel(); return; }
    if (active) close();
    active = true; attempt = request; stop();
    file = null; uploaded = false; fileLabel.hidden = true; fileLabel.textContent = "";
    progressRoot.hidden = true; progress.value = 0;
    choice.value = ""; choice.dispatchEvent(new Event("change", { bubbles: true }));
    alt.value = request?.initial?.alt ?? ""; title.value = request?.initial?.title ?? "";
    url.value = request?.initial?.src ?? ""; url.setCustomValidity("");
    message(""); show(null);
    if (request?.initial?.id) void resolve(request.initial.id);
    else if (request?.initial?.src && imageURL(request.initial.src)) show({ src: request.initial.src, label: request.initial.title || request.initial.src });
  };
  ctx.on<CustomEvent<ImagePickerOpen>>(ctx.root, "imagepickeropen", event => {
    if (event.target !== ctx.root) return;
    begin(event.detail);
    if (active) dialog.dispatchEvent(new Event("dialogopen"));
  });
  ctx.on(ctx.root, "dialogopened", event => { if (event.target === dialog && !active) begin(); });
  ctx.on(ctx.root, "dialogclosed", event => { if (event.target === dialog && active) close(); });
  ctx.on(ctx.root, "imagepickerclose", event => { if (event.target === ctx.root) close(); });
  ctx.on(cancel, "click", () => close());
  ctx.on(cancelUpload, "click", () => {
    if (!uploading) return;
    operation?.abort(); busy = false; uploading = false; message("Upload cancelled. Retry this file when ready.", true); syncControls();
  });
  ctx.on<CustomEvent<SelectSearch>>(ctx.root, "selectsearch", event => {
    if (!(event.target instanceof Element) || !event.target.contains(choice)) return;
    event.stopPropagation(); search?.abort(); const controller = search = new AbortController();
    const { query, offset, resolve: accept, reject } = event.detail;
    if (!active || !enabled()) { accept([]); return; }
    void imageRequest<ImagePage>(ctx.root, { kind: "search", query, offset }, controller.signal).then(page => {
      if (!current(controller)) { reject(new DOMException("Image query replaced", "AbortError")); return; }
      if (!page || !Array.isArray(page.items)) throw new Error("Invalid image page.");
      accept({ options: page.items.map(value => ({ value: imageChoice(value).id, label: value.label })),
        previousOffset: page.previousOffset, nextOffset: page.nextOffset });
    }).catch(reject);
  });
  ctx.on(choice, "change", () => { if (active && enabled() && choice.value) void resolve(choice.value); });
  ctx.on<CustomEvent<File[]>>(drop, "filesselected", event => {
    if (!active || !enabled() || busy) return;
    const selectedFile = event.detail[0];
    if (!selectedFile) return;
    if (!selectedFile.type.startsWith("image/")) { message("Choose an image file with an identified image content type.", true); return; }
    file = selectedFile; uploaded = false;
    fileLabel.textContent = `${file.name} · ${file.size} bytes`; fileLabel.hidden = false;
    progressRoot.hidden = true; message(""); syncControls();
  });
  ctx.on(upload, "click", async () => {
    if (!active || !enabled() || busy || !file || uploaded) return;
    const source = file, controller = operation = new AbortController(); busy = true; uploading = true; syncControls();
    progress.value = 0; progressRoot.hidden = false; message("Preparing upload…");
    try {
      const result = await imageRequest<ImageChoice>(ctx.root, { kind: "upload", file: source, progress(value, phase) {
        if (!current(controller)) return;
        if (phase === "processing") { progress.removeAttribute("value"); message("Storing image…"); }
        else { progress.value = Math.max(0, Math.min(100, value)); message("Uploading image…"); }
      } }, controller.signal);
      if (!current(controller)) return;
      uploaded = true; show(imageChoice(result)); progress.value = 100; message("Image uploaded.");
    } catch (error) {
      if (operation === controller && active && enabled()) message(controller.signal.aborted ? "Upload cancelled. Retry this file when ready."
        : error instanceof Error ? error.message : "Upload failed. Retry this file.", true);
    } finally { if (operation === controller) { busy = false; uploading = false; syncControls(); } }
  });
  const useURL = () => {
    if (!active || !enabled() || busy) return;
    const value = url.value.trim(); url.setCustomValidity(imageURL(value) ? "" : "Enter a valid image URL.");
    if (!url.reportValidity()) return;
    operation?.abort(); message(""); show({ src: value, label: value });
  };
  const useUrl = ctx.ref<HTMLButtonElement>("imageUseUrl"); if (useUrl) ctx.on(useUrl, "click", useURL);
  ctx.on(apply, "click", async () => {
    if (!active || !enabled() || busy || !selected) return;
    const controller = operation = new AbortController(), captured = selected;
    busy = true; syncControls();
    try {
      const result = captured.id ? await imageRequest<ImageChoice | null>(ctx.root, { kind: "resolve", id: captured.id }, controller.signal) : captured;
      if (!current(controller)) return;
      if (!result) { show(null); message("This image is unavailable or you do not have access to it.", true); return; }
      if (captured.id) imageChoice(result as ImageChoice);
      close({ id: captured.id, src: result.src, alt: alt.value, title: title.value });
    } catch { if (current(controller)) message("Could not confirm image access. Retry the selection.", true); }
    finally { if (operation === controller) { busy = false; syncControls(); } }
  });
  ctx.on(image, "error", () => { if (active && selected) message("Preview unavailable. The image remains selected.", true); });
  ctx.on(ctx.root, "imagesready", () => {
    const id = selected?.id ?? attempt?.initial?.id;
    if (active && id && !busy) void resolve(id);
  });
  ctx.on<KeyboardEvent>(ctx.root, "keydown", event => {
    if (event.key !== "Enter" || event.isComposing || !(event.target instanceof HTMLInputElement) || event.target.type === "file" || event.target.closest("[data-bml-design-select]")) return;
    event.preventDefault(); if (event.target === url) useURL();
  });
  const sync = () => { if (!enabled() && active) close(); syncControls(); };
  const observer = new MutationObserver(sync); observer.observe(ctx.root, { attributes: true, attributeFilter: ["data-disabled"] });
  ctx.onUpdate(sync);
  ctx.onUnmount(() => { close(); disposed = true; stop(); observer.disconnect(); ctx.root.removeAttribute("data-image-picker-ready"); });
  sync(); ctx.root.setAttribute("data-image-picker-ready", "");
  ctx.root.dispatchEvent(new CustomEvent("imagepickerready", { bubbles: true }));
}
