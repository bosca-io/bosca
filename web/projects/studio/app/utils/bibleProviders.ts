import gql from 'graphql-tag'
import type { Bible, BibleAttribution, BibleCatalog, BibleLanguage, BibleProviderConfiguration, BibleSource } from '~/types/graphql'

export type ProviderConfiguration = Pick<BibleProviderConfiguration, 'id' | 'name' | 'provider' | 'enabled' | 'hasApiKey'>
export type ProviderAttribution = Pick<BibleAttribution, 'copyright' | 'citation' | 'informationUrl'>
export type ProviderCatalog = Pick<BibleCatalog, 'id' | 'kind' | 'name' | 'nameLocal' | 'abbreviation' | 'description'> & {
  languages: Pick<BibleLanguage, 'name' | 'iso'>[]
  attribution: ProviderAttribution
}
export type ProviderSource = Pick<BibleSource, 'id' | 'configurationId' | 'name' | 'kind' | 'metadataId' | 'metadataVersion' | 'variant' | 'version' | 'allowedUses'> & { attribution: ProviderAttribution }
export type AudioTarget = Pick<Bible, 'metadataId' | 'version' | 'variant' | 'name' | 'enabled'>
export interface BibleProviderResult<T> { content: { bibleProviders: T } }

const providerLabels: Record<string, string> = { API_BIBLE: 'API.Bible' }

/** Returns the display name for a Bible provider identifier, falling back to the identifier itself. */
export function providerLabel(provider: string): string {
  return providerLabels[provider] ?? provider
}

/** Returns a stable selection key that identifies one Bible version and variant as an audio target. */
export function audioTargetKey(target: Pick<AudioTarget, 'metadataId' | 'version' | 'variant'>): string {
  return `${target.metadataId}:${target.version}:${target.variant}`
}

export const configurationsQuery = gql`
  query GetBibleProviderConfigurations {
    content { bibleProviders { providers configurations { id name provider enabled hasApiKey } } }
  }
`
export const sourcesQuery = gql`
  query GetBibleProviderSources($configurationId: UUID!) {
    content { bibleProviders { sources(configurationId: $configurationId) {
      id configurationId name kind metadataId metadataVersion variant version allowedUses
      attribution { copyright citation informationUrl }
    } } }
  }
`
export const catalogQuery = gql`
  query GetBibleProviderCatalog($configurationId: UUID!, $kind: BibleSourceKind!, $query: String, $offset: Int!, $limit: Int!) {
    content { bibleProviders { catalog(configurationId: $configurationId, kind: $kind, query: $query, offset: $offset, limit: $limit) {
      id kind name nameLocal abbreviation description languages { name iso }
      attribution { copyright citation informationUrl }
    } } }
  }
`
export const audioTargetsQuery = gql`
  query GetBibleProviderAudioTargets($configurationId: UUID!, $providerBibleId: String!) {
    content { bibleProviders { audioTargets(configurationId: $configurationId, providerBibleId: $providerBibleId) {
      metadataId version variant name enabled
    } } }
  }
`
export const saveConfigurationMutation = gql`
  mutation SaveBibleProviderConfiguration($input: BibleProviderConfigurationInput!) {
    content { bibleProviders { saveConfiguration(input: $input) { id name provider enabled hasApiKey } } }
  }
`
export const testConfigurationMutation = gql`
  mutation TestBibleProviderConfiguration($configurationId: UUID!) {
    content { bibleProviders { test(configurationId: $configurationId) } }
  }
`
export const addBibleMutation = gql`
  mutation AddProviderBible($input: AddProviderBibleInput!) {
    content { bibleProviders { addBible(input: $input) { metadataId version variant } } }
  }
`
export const addAudioMutation = gql`
  mutation AddProviderAudio($bibleId: UUID!, $version: Int!, $variant: String!, $input: AddProviderAudioInput!) {
    content { bibleProviders { addAudio(bibleId: $bibleId, version: $version, variant: $variant, input: $input) { id } } }
  }
`
export const refreshSourceMutation = gql`
  mutation RefreshBibleProviderSource($sourceId: UUID!) {
    content { bibleProviders { refresh(sourceId: $sourceId) } }
  }
`
export const removeSourceMutation = gql`
  mutation RemoveBibleProviderSource($sourceId: UUID!, $expectedVersion: Long!) {
    content { bibleProviders { removeSource(sourceId: $sourceId, expectedVersion: $expectedVersion) } }
  }
`
export const removeConfigurationMutation = gql`
  mutation RemoveBibleProviderConfiguration($configurationId: UUID!, $purgeSources: Boolean!) {
    content { bibleProviders { removeConfiguration(configurationId: $configurationId, purgeSources: $purgeSources) } }
  }
`
