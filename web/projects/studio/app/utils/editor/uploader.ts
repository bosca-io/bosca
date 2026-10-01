import type { Metadata, MetadataInput } from '~/types/graphql'

export type UploadPhase = 'uploading' | 'processing'

export interface UploadProgressCallback {
  (_progress: number, _phase: UploadPhase): void
}

export interface SubscribeToProgress {
  (_metadataId: string, _onProgress: (_bytesUploaded: number) => void): () => void
}

export class Uploader {
  private readonly getParentCollectionId: (_file: File) => Promise<string | undefined>
  private readonly addMetadata: (_metadata: MetadataInput) => Promise<Metadata>
  private readonly setReady: (_metadataId: string) => Promise<void>
  private readonly subscribeToProgress: SubscribeToProgress

  constructor(
    getParentCollectionId: (_file: File) => Promise<string | undefined>,
    addMetadata: (_metadata: MetadataInput) => Promise<Metadata>,
    setReady: (_metadataId: string) => Promise<void>,
    subscribeToProgress: SubscribeToProgress
  ) {
    this.getParentCollectionId = getParentCollectionId
    this.addMetadata = addMetadata
    this.setReady = setReady
    this.subscribeToProgress = subscribeToProgress
  }

  async upload(file: File, languageTag: string | null, onProgress?: UploadProgressCallback) {
    const metadata = await this.addMetadata({
      parentCollectionId: await this.getParentCollectionId(file),
      name: file.name,
      languageTag: languageTag || 'en',
      contentType: file.type,
      searchable: !(file.type.startsWith('image/') || file.type === 'application/pdf'),
      syncVariantCollections: true,
      syncVariantRelationships: true
    })
    const upload = metadata.content?.urls?.upload
    if (!upload) {
      throw new Error('Failed to upload file contents: no upload url found')
    }
    const url = upload.url
    const headers: Record<string, string> = {}
    for (const hdr of upload.headers) {
      headers[hdr.name] = hdr.value
    }
    const data = new FormData()
    data.append('file', file)

    // Subscribe to server-side storage progress before starting the upload
    const unsubscribe = this.subscribeToProgress(metadata.id, (bytesUploaded) => {
      if (onProgress && file.size > 0) {
        const percent = Math.round((bytesUploaded / file.size) * 100)
        onProgress(percent, 'processing')
      }
    })

    try {
      if (onProgress && typeof XMLHttpRequest !== 'undefined') {
        await this.uploadWithProgress(url, headers, data, onProgress)
      } else {
        const headerObj = new Headers()
        for (const [name, value] of Object.entries(headers)) {
          headerObj.append(name, value)
        }
        const response = await fetch(url, {
          method: 'POST',
          body: data,
          headers: headerObj
        })
        const result = await response.text()
        if (!response.ok) {
          console.error('Failed to upload file contents: ', result, response)
          throw new Error('Failed to upload file contents: ' + result)
        }
      }
    } finally {
      unsubscribe()
    }

    await this.setReady(metadata.id)
    return metadata.id
  }

  private uploadWithProgress(
    url: string,
    headers: Record<string, string>,
    data: FormData,
    onProgress: UploadProgressCallback
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      xhr.open('POST', url)
      for (const [name, value] of Object.entries(headers)) {
        xhr.setRequestHeader(name, value)
      }
      xhr.upload.addEventListener('progress', (event) => {
        if (event.lengthComputable) {
          const percent = Math.round((event.loaded / event.total) * 100)
          onProgress(percent, 'uploading')
        }
      })
      xhr.upload.addEventListener('load', () => {
        onProgress(0, 'processing')
      })
      xhr.addEventListener('load', () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve()
        } else {
          console.error('Failed to upload file contents: ', xhr.responseText, xhr.status)
          reject(new Error('Failed to upload file contents: ' + xhr.responseText))
        }
      })
      xhr.addEventListener('error', () => {
        reject(new Error('Failed to upload file contents: network error'))
      })
      xhr.send(data)
    })
  }
}
