/** Raw package repository selected by a published Bosca installer URL. */
export function cliArtifactsRepository(installerUrl: URL): string | undefined {
  const match = installerUrl.pathname.match(/^(.*\/raw\/[^/]+)\/([^/]+)\/[^/]+\/install\.sh$/)
  return match ? installerUrl.origin + match[1] + '/' + match[2] : undefined
}

/** Metadata endpoint paired with a raw repository's file download URL. */
export function cliArtifactsListingUrl(repository: string): string {
  const slash = repository.lastIndexOf('/')
  return repository.slice(0, slash) + '/api' + repository.slice(slash)
}
