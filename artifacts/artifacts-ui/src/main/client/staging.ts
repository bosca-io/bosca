type StagedFile = { bytes: Blob; name: string; type: string; modified: number };
function storage(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open("bosca-atlas-artifact-uploads", 1);
    open.onupgradeneeded = () => open.result.createObjectStore("files");
    open.onerror = () => reject(open.error ?? new Error("File staging is unavailable."));
    open.onsuccess = () => resolve(open.result);
  });
}
export async function stageArtifactFiles(files: Iterable<File>, href: string): Promise<void> {
  const destination = new URL(href, location.origin);
  if (destination.origin !== location.origin || destination.pathname !== "/artifacts/repositories/upload") throw new Error("Invalid upload destination.");
  const key = crypto.randomUUID();
  const database = await storage();
  try {
    const staged: StagedFile[] = Array.from(files, file => ({ bytes: file, name: file.name, type: file.type, modified: file.lastModified }));
    if (!staged.length) return;
    await new Promise<void>((resolve, reject) => {
      const transaction = database.transaction("files", "readwrite");
      transaction.objectStore("files").put(staged, key);
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error ?? new Error("File staging was cancelled."));
    });
    destination.searchParams.set("staging", key);
    location.assign(destination.pathname + destination.search);
  } finally { database.close(); }
}
export async function consumeArtifactFiles(key: string): Promise<File[]> {
  if (!key) return [];
  const database = await storage();
  try {
    return await new Promise<File[]>((resolve, reject) => {
      const transaction = database.transaction("files", "readwrite");
      const store = transaction.objectStore("files");
      const read = store.get(key);
      let files: File[] = [];
      read.onsuccess = () => { files = ((read.result as StagedFile[] | undefined) ?? []).map(item => new File([item.bytes], item.name, { type: item.type, lastModified: item.modified })); store.delete(key); };
      transaction.oncomplete = () => resolve(files);
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error ?? new Error("File staging was cancelled."));
    });
  } finally { database.close(); }
}
