package bosca.meilisearch.admin.configuration

import bosca.di.annotation.Providers

/**
 * DI provider registrations for the Meilisearch admin module.
 * The [bosca.meilisearch.client.MeilisearchClient] and [bosca.search.configuration.MeilisearchConfiguration]
 * singletons are already provided by the search module's configuration and are
 * available for injection without additional registration.
 */
@Providers
class MeilisearchAdminConfiguration
