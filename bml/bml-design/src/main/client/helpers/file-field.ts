import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Keeps the Forms data-URL string/null value while reusing file selection and progress controls. */
export function fileField(ctx: ClientContext) {
  const value = ctx.root.querySelector<HTMLInputElement>("[data-file-field-value]");
  const preview = ctx.ref<HTMLImageElement>("filePreview"), drop = ctx.ref<HTMLElement>("fileDrop");
  const status = ctx.ref<HTMLElement>("fileStatus"), progress = ctx.ref<HTMLProgressElement>("fileProgress");
  const clear = ctx.ref<HTMLButtonElement>("fileClear"), cancel = ctx.ref<HTMLButtonElement>("fileCancel");
  if (!value || !preview || !drop || !status || !progress || !clear || !cancel) return;
  let reader: FileReader | undefined, disposed = false, serverValue = ctx.root.dataset.value;
  const readOnly = () => ctx.root.hasAttribute("data-readonly");
  const render = () => {
    const stored: unknown = JSON.parse(value.value);
    preview.hidden = !ctx.root.hasAttribute("data-image") || typeof stored !== "string" || !stored;
    if (!preview.hidden) preview.src = stored as string; else preview.removeAttribute("src");
    clear.disabled = readOnly() || stored === null;
    drop.toggleAttribute("data-disabled", readOnly());
  };
  const busy = (reading: boolean) => {
    ctx.root.toggleAttribute("data-file-field-busy", reading);
    progress.hidden = !reading; cancel.hidden = !reading;
    clear.disabled = reading || readOnly() || value.value === "null";
    ctx.root.dispatchEvent(new CustomEvent<boolean>("filefieldbusy", { bubbles: true, detail: reading }));
  };
  const publish = (next: string | null) => {
    value.value = JSON.stringify(next);
    value.dispatchEvent(new Event("input", { bubbles: true }));
    render();
  };
  const abort = () => { const previous = reader; reader = undefined; previous?.abort(); busy(false); };
  ctx.on<CustomEvent<File[]>>(drop, "filesselected", event => {
    if (readOnly() || !event.detail[0]) return;
    abort();
    const file = event.detail[0], current = new FileReader();
    reader = current;
    status.textContent = `Reading ${file.name}…`;
    progress.value = 0; busy(true);
    current.onprogress = event => { if (reader === current && event.lengthComputable) progress.value = event.loaded / event.total * 100; };
    current.onload = () => {
      if (disposed || reader !== current || readOnly()) return;
      reader = undefined; busy(false);
      if (typeof current.result !== "string") { status.textContent = "Could not read the file. Try again."; return; }
      status.textContent = file.name;
      publish(current.result);
    };
    current.onerror = () => {
      if (disposed || reader !== current) return;
      reader = undefined; busy(false);
      status.textContent = "Could not read the file. Choose it again to retry.";
    };
    current.readAsDataURL(file);
  });
  ctx.on(cancel, "click", () => { abort(); status.textContent = "Reading cancelled. The previous file is retained."; });
  ctx.on(clear, "click", () => { if (!readOnly()) { abort(); status.textContent = ""; publish(null); } });
  const sync = () => {
    if (readOnly()) abort();
    if (serverValue !== ctx.root.dataset.value) {
      abort(); serverValue = ctx.root.dataset.value; value.value = serverValue ?? "null"; status.textContent = "";
    }
    render();
  };
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => { if (event.detail.target.contains(ctx.root)) sync(); });
  const observer = new MutationObserver(sync);
  observer.observe(ctx.root, { attributes: true, attributeFilter: ["data-readonly", "data-value"] });
  ctx.onUpdate(sync);
  ctx.onUnmount(() => { disposed = true; abort(); observer.disconnect(); });
  sync();
}
