import type * as Y from "yjs";

type Revision = Record<string, number>;
const revisionsName = "editorRevisions", dirtyOrigin = "update-document-changes", saveOrigin = "save-document";
const dirtyKey = "|__dirty__|";

function parseRevision(value: unknown): Revision | null {
  if (typeof value !== "string") return null;
  try {
    const parsed: unknown = JSON.parse(value);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
    if (Object.values(parsed).some(number => typeof number !== "number" || !Number.isSafeInteger(number) || number < 0)) return null;
    return parsed as Revision;
  } catch { return null; }
}

/** Tracks Content/Studio's existing Yjs dirty markers and per-editor saved revisions. */
export function documentRevisions(document: Y.Doc, changed: () => void = () => {}) {
  // Register roots before applying a snapshot so remote types have the expected constructors.
  document.getXmlFragment("default");
  const changes = document.getText("changes"), saved = document.getText("saved");
  const revisions = document.getMap<number>(revisionsName);
  const metadataMaps = new Set<unknown>([document.getMap("attrs"), document.getMap("collections"), document.getMap("metadatas")]);
  let initializing = true, disposed = false;
  const revision = (): Revision => Object.fromEntries([...revisions.entries()]
    .filter((entry): entry is [string, number] => Number.isSafeInteger(entry[1]) && entry[1] >= 0)
    .sort(([left], [right]) => left.localeCompare(right)));
  const newerThan = (savedRevision: Revision) => Object.entries(revision())
    .some(([client, number]) => number > (savedRevision[client] ?? 0));
  const dirty = () => changes.getAttribute("changes") === "true";
  const setDirty = (value: boolean) => {
    if (dirty() !== value) changes.setAttribute("changes", value ? "true" : "false");
  };
  const observe = (transaction: Y.Transaction) => {
    if (initializing || disposed) return;
    let editorChanged = false, savedChanged = false, cleanMarkerChanged = false;
    for (const [type, keys] of transaction.changed) {
      const root: unknown = type;
      if (root === changes) { cleanMarkerChanged ||= keys.has("changes") && !dirty(); continue; }
      if (root === saved) { savedChanged ||= keys.has("saved"); continue; }
      if (root === revisions) continue;
      if (metadataMaps.has(root) && keys.size > 0 && [...keys].every(key => key === dirtyKey)) continue;
      editorChanged = true;
    }
    if (editorChanged && transaction.local && transaction.origin !== saveOrigin) {
      document.transact(() => {
        const client = String(document.clientID);
        revisions.set(client, (revisions.get(client) ?? 0) + 1);
        setDirty(true);
      }, dirtyOrigin);
      changed(); return;
    }
    const savedRevision = parseRevision(saved.getAttribute("saved"));
    if (savedChanged && savedRevision) setDirty(newerThan(savedRevision));
    else if (editorChanged) setDirty(true);
    else if (cleanMarkerChanged && savedRevision) setDirty(newerThan(savedRevision));
    if (editorChanged || savedChanged || cleanMarkerChanged) changed();
  };
  document.on("afterTransaction", observe);
  return {
    get dirty() { return dirty(); },
    get ready() { return !initializing; },
    capture(): string { return JSON.stringify(revision()); },
    /** Call after provider sync and editor schema normalization, preserving the server's dirty flag. */
    markReady() { if (!disposed && initializing) { initializing = false; changed(); } },
    /** A successful save acknowledges exactly its captured revision, including during newer edits. */
    markSaved(captured: string) {
      if (disposed) return;
      const savedRevision = parseRevision(captured);
      if (!savedRevision) throw new Error("Invalid document save revision.");
      document.transact(() => {
        saved.setAttribute("saved", captured);
        setDirty(newerThan(savedRevision));
      }, saveOrigin);
    },
    destroy() { disposed = true; document.off("afterTransaction", observe); },
  };
}
