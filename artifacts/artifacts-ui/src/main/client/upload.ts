import type { ClientContext } from "@bosca/bml";
import { consumeArtifactFiles } from "./staging";

type Target = Record<string, string>;
type ResolveTarget = (repositoryId: string, namespaceId: string, name: string) => Promise<Target>;
type UploadFile = { file: File; row: HTMLElement; filename: HTMLInputElement; status: HTMLElement; progress: HTMLProgressElement; completedTarget?: string };

/** Reuses resolved repositories when a partial upload returns to earlier coordinates. */
export function artifactTargetResolver(resolve: ResolveTarget): ResolveTarget {
  const targets = new Map<string, Target>();
  return async (repositoryId, namespaceId, name) => {
    const key = JSON.stringify([repositoryId, namespaceId, name]);
    const cached = targets.get(key);
    if (cached) return cached;
    const target = await resolve(repositoryId, namespaceId, name);
    targets.set(key, target);
    return target;
  };
}

export function safeArtifactSegment(value: string): boolean {
  return value.trim().length > 0 && value !== "." && value !== ".." && !/[\/\\\x00-\x1f\x7f-\x9f]/.test(value);
}
export function safeArtifactRepositoryName(value: string): boolean {
  return value.length >= 1 && value.length <= 255 && value !== "." && /^[A-Za-z0-9@_.-]+$/.test(value) && !value.includes("..");
}
export function rawUploadPath(namespace: string, repository: string, version: string, filename: string): string {
  if (![namespace, repository, version, filename].every(safeArtifactSegment)) throw new Error("Coordinates and filenames must be single safe path segments.");
  return "/_artifacts/raw/" + [namespace, repository, version, filename].map(encodeURIComponent).join("/");
}

export function mountArtifactUpload(ctx: ClientContext, resolve: ResolveTarget): void {
  const resolveTarget = artifactTargetResolver(resolve);
  const form = ctx.ref<HTMLFormElement>("uploadForm");
  const picker = ctx.ref<HTMLInputElement>("fileInput");
  const filesRoot = ctx.ref<HTMLElement>("files");
  const template = ctx.ref<HTMLElement>("fileTemplate");
  const message = ctx.ref<HTMLElement>("message");
  const upload = ctx.ref<HTMLButtonElement>("upload");
  const cancel = ctx.ref<HTMLButtonElement>("cancel");
  const drop = ctx.ref<HTMLElement>("dropZone");
  if (!form || !picker || !filesRoot || !template || !message || !upload || !cancel || !drop) return;
  const showMessage = (text: string, tone: "info" | "success" | "error") => {
    message.textContent = text;
    message.dataset.tone = tone;
    message.hidden = !text;
    message.setAttribute("role", tone === "error" ? "alert" : "status");
  };
  let controller: AbortController | undefined;
  const files: UploadFile[] = [];
  const add = (incoming: Iterable<File> | null) => {
    if (!incoming || controller) return;
    for (const file of Array.from(incoming)) {
      const row = template.firstElementChild?.cloneNode(true) as HTMLElement | undefined;
      if (!row) continue;
      const filename = row.querySelector<HTMLInputElement>("input");
      const status = row.querySelector<HTMLElement>("[data-file-status]");
      const progress = row.querySelector<HTMLProgressElement>("progress");
      const remove = row.querySelector<HTMLButtonElement>("[data-file-remove]");
      const info = row.querySelector<HTMLElement>("[data-file-info]");
      if (!filename || !status || !progress || !remove || !info) continue;
      filename.value = file.name;
      info.textContent = `${file.size} bytes · ${file.type || "application/octet-stream"}`;
      const item = { file, row, filename, status, progress };
      files.push(item);
      ctx.on(remove, "click", () => { if (!controller) { files.splice(files.indexOf(item), 1); row.remove(); } });
      filesRoot.append(row);
    }
    picker.value = "";
  };
  const staging = new URLSearchParams(location.search).get("staging");
  if (staging) {
    upload.disabled = true;
    void consumeArtifactFiles(staging).then(add).catch(error => { showMessage(error instanceof Error ? error.message : "Choose the files again.", "error"); }).finally(() => { upload.disabled = false; });
  }
  ctx.on(picker, "change", () => add(picker.files));
  ctx.on<DragEvent>(drop, "dragover", event => { event.preventDefault(); drop.setAttribute("data-dragging", ""); });
  ctx.on(drop, "dragleave", () => drop.removeAttribute("data-dragging"));
  ctx.on<DragEvent>(drop, "drop", event => { event.preventDefault(); drop.removeAttribute("data-dragging"); add(event.dataTransfer?.files ?? null); });
  ctx.on(cancel, "click", () => controller?.abort());
  ctx.on(form, "submit", async event => {
    event.preventDefault();
    if (controller) return;
    controller = new AbortController();
    const signal = controller.signal;
    upload.disabled = true;
    cancel.hidden = false;
    showMessage("Preparing upload…", "info");
    const data = new FormData(form);
    const version = String(data.get("version") ?? "").trim();
    try {
      const names = files.map(item => item.filename.value.trim());
      if (!safeArtifactSegment(version)) throw new Error("Version must be a single safe path segment.");
      if (!names.length || names.some(name => !safeArtifactSegment(name))) throw new Error("Add files with single safe filenames.");
      if (new Set(names).size !== names.length) throw new Error("Filenames must be unique within the version.");
      const repositoryId = String(data.get("repositoryId") ?? "");
      const namespaceId = String(data.get("namespaceId") ?? "");
      const name = String(data.get("name") ?? "").trim();
      if (!repositoryId) throw new Error("Choose a raw repository.");
      if (repositoryId === "new") {
        if (!namespaceId) throw new Error("Choose a namespace.");
        if (!safeArtifactRepositoryName(name)) throw new Error("Enter a repository name of up to 255 letters, numbers, @, _, dots, or hyphens, without consecutive dots.");
      }
      await atlasAuth.initialize(false);
      const headers = await atlasAuth.getAuthHeaders();
      signal.throwIfAborted();
      const target = await resolveTarget(repositoryId, namespaceId, name);
      signal.throwIfAborted();
      let failed = false;
      for (const [index, item] of files.entries()) {
        const path = rawUploadPath(target.namespace ?? "", target.name ?? "", version, names[index] ?? "");
        if (item.completedTarget === path) continue;
        signal.throwIfAborted();
        item.status.textContent = "Uploading…";
        item.progress.value = 0;
        try {
          await uploadFile(path, item.file, headers, signal, value => { item.progress.value = value; });
          item.completedTarget = path;
          item.status.textContent = "Uploaded";
        } catch (error) {
          if (signal.aborted) { item.status.textContent = "Cancelled"; throw error; }
          item.status.textContent = error instanceof Error ? error.message : "Upload failed";
          failed = true;
        }
      }
      if (failed) throw new Error("Some files failed. Retry to upload the remaining files.");
      showMessage("All files uploaded.", "success");
      location.assign("/artifacts/repositories/" + encodeURIComponent(target.id ?? ""));
    } catch (error) {
      showMessage(signal.aborted ? "Upload cancelled. Uploaded files are kept; retry to upload the remaining files." : error instanceof Error ? error.message : "Upload failed.", "error");
    } finally { controller = undefined; upload.disabled = false; cancel.hidden = true; }
  });
  ctx.onUnmount(() => controller?.abort());
}

export function uploadFile(path: string, file: File, headers: Record<string, string>, signal: AbortSignal, progress: (percent: number) => void): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    const abort = () => request.abort();
    const finish = (error?: Error) => { signal.removeEventListener("abort", abort); error ? reject(error) : resolve(); };
    request.open("PUT", path);
    for (const [name, value] of Object.entries(headers)) request.setRequestHeader(name, value);
    request.setRequestHeader("Content-Type", file.type || "application/octet-stream");
    request.upload.onprogress = event => { if (event.lengthComputable) progress(100 * event.loaded / event.total); };
    request.onload = () => request.status >= 200 && request.status < 300 ? (progress(100), finish()) : finish(new Error(request.responseText.trim() || `Upload failed (${request.status})`));
    request.onerror = () => finish(new Error("The registry could not be reached."));
    request.onabort = () => finish(new Error("Upload cancelled."));
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) { finish(new Error("Upload cancelled.")); return; }
    request.send(file);
  });
}
