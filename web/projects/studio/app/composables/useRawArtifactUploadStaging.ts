import { shallowRef } from 'vue'

// A browser File cannot be serialized into a route query. This client-populated module ref keeps
// the files alive for the single in-app navigation from a repository page to the upload form.
// A refresh intentionally clears it; the upload form always includes its own file picker.
const stagedFiles = shallowRef<File[]>([])

export function useRawArtifactUploadStaging() {
  function stage(files: File[]) {
    if (import.meta.client) stagedFiles.value = [...files]
  }

  function clear() {
    stagedFiles.value = []
  }

  return { stagedFiles, stage, clear }
}
