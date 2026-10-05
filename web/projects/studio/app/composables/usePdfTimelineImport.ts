/* eslint-disable @typescript-eslint/no-explicit-any */
import type { TimeEventType } from '~/composables/useTimeEvents'

export interface PdfImportProgress {
  status: 'idle' | 'uploading' | 'processing' | 'done' | 'error'
  error?: string
}

const ADD_PDF_METADATA = `
  mutation AddPdfMetadata($metadata: MetadataInput!) {
    content {
      metadata {
        add(metadata: $metadata, setReady: true) {
          id
          content {
            urls {
              upload {
                url
                headers {
                  name
                  value
                }
              }
            }
          }
        }
      }
    }
  }
`

const IMPORT_PDF_AS_TIMELINE_EVENTS = `
  mutation ImportPdfAsTimelineEvents(
    $pdfMetadataId: UUID!,
    $targetMetadataId: UUID!,
    $targetMetadataVersion: Int!,
    $eventTypeId: String!,
    $relationship: String!,
    $durationMs: Long!
  ) {
    timeEvents {
      importPdfAsTimelineEvents(
        pdfMetadataId: $pdfMetadataId,
        targetMetadataId: $targetMetadataId,
        targetMetadataVersion: $targetMetadataVersion,
        eventTypeId: $eventTypeId,
        relationship: $relationship,
        durationMs: $durationMs
      )
    }
  }
`

const MAX_PDF_SIZE = 50 * 1024 * 1024

export function usePdfTimelineImport(
  metadataId: Ref<string>,
  metadataVersion: Ref<number>,
  durationMs: Ref<number>,
  eventTypes: Ref<TimeEventType[]>,
  activeTypeFilter: Ref<string | null>,
) {
  const toast = useToast()
  const gql = useGraphQL()

  const progress = ref<PdfImportProgress>({ status: 'idle' })

  const isImporting = computed(
    () =>
      progress.value.status !== 'idle' &&
      progress.value.status !== 'done' &&
      progress.value.status !== 'error',
  )

  function resolveEventTypeId(): string {
    const typeId = activeTypeFilter.value || eventTypes.value[0]?.id
    if (!typeId) throw new Error('No event types available')
    return typeId
  }

  async function uploadPdf(file: File): Promise<string> {
    const result = await gql.mutation<any>(ADD_PDF_METADATA, {
      metadata: {
        name: file.name,
        languageTag: 'en',
        contentType: 'application/pdf',
        contentLength: file.size,
        searchable: false,
      },
    })

    const metadata = result?.content?.metadata?.add
    if (!metadata) throw new Error('Failed to create metadata for PDF')

    const upload = metadata.content?.urls?.upload
    if (!upload) throw new Error('No upload URL returned for PDF')

    const headers = new Headers()
    for (const hdr of upload.headers) {
      headers.append(hdr.name, hdr.value)
    }

    const formData = new FormData()
    formData.append('file', file)

    const response = await fetch(upload.url, {
      method: 'POST',
      body: formData,
      headers,
    })

    if (!response.ok) {
      const text = await response.text()
      throw new Error(`Failed to upload PDF: ${text}`)
    }

    return metadata.id
  }

  async function importPdf(file: File, relationship: string = 'page') {
    if (isImporting.value) return

    progress.value = { status: 'uploading' }

    try {
      if (file.size > MAX_PDF_SIZE) {
        throw new Error(
          `PDF file is too large (${(file.size / 1024 / 1024).toFixed(1)}MB). Maximum size is 50MB.`,
        )
      }
      const eventTypeId = resolveEventTypeId()

      if (durationMs.value <= 0) {
        throw new Error(
          'Media duration is not available yet. Please wait for the video to load before importing.',
        )
      }

      const pdfMetadataId = await uploadPdf(file)

      progress.value = { status: 'processing' }

      await gql.mutation(IMPORT_PDF_AS_TIMELINE_EVENTS, {
        pdfMetadataId,
        targetMetadataId: metadataId.value,
        targetMetadataVersion: metadataVersion.value,
        eventTypeId,
        relationship,
        durationMs: durationMs.value,
      })

      progress.value = { status: 'done' }
      toast.add({
        title: 'PDF import started',
        description:
          'Pages are being extracted and timeline events will be created in the background. The timeline will refresh automatically when processing completes.',
      })
    } catch (e: unknown) {
      const errorMessage = e instanceof Error ? e.message : String(e)
      progress.value = { status: 'error', error: errorMessage }
      toast.add({
        title: 'PDF import failed',
        description: errorMessage,
        color: 'error',
      })
    }
  }

  return {
    progress,
    isImporting,
    importPdf,
  }
}
