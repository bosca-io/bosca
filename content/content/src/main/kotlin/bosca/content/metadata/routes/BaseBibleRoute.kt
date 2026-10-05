package bosca.content.metadata.routes

import bosca.content.find.FindQueryInput
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.routes.Route
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import java.util.Locale
import kotlin.uuid.toKotlinUuid

abstract class BaseBibleRoute<T>(
    protected val metadataService: MetadataService,
    protected val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    protected val bibleService: BibleService
) : Route<T>() {

    protected suspend fun ServerCall.getMetadata(authentication: AuthenticationContext): Metadata? {
        val id: String by pathParameters
        val version = request.queryParameters["version"]?.toIntOrNull()
        val metadata = try {
            val id = UUID.parse(id)
            version?.let { metadataService.getById(id, it) } ?: metadataService.getById(id)
        } catch (e: Exception) {
            null
        }?.takeIf { !it.deleted } ?: return null
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        return metadata
    }

    protected suspend fun ServerCall.getBible(authentication: AuthenticationContext): Bible {
        val metadata = getMetadata(authentication) ?: return getBibleByLanguage(authentication)
        val variant = request.queryParameters["variant"]
        return bibleService.getBible(metadata.id, metadata.version, variant) ?: error("Bible not found")
    }

    protected suspend fun ServerCall.getBibleByLanguage(authentication: AuthenticationContext): Bible {
        val languages = request.queryParameters["language"]?.let { listOf(it) } ?: request.acceptLanguageItems().map { it.value }
        val locales = languages.map {
            if (it == "*") {
                return@map Locale.getDefault()
            }
            Locale.forLanguageTag(it)
        }
        val found = metadataService.find(
            FindQueryInput(
            contentTypes = listOf("bosca/v-bible"),
            languageTags = locales.flatMap { listOf(it.isO3Language, it.language) }
        ))
        if (found.isEmpty()) error("Bible not found for language $languages")
        val metadatas = found.groupBy { it.languageTag }
        for (locale in locales) {
            val metadata = metadatas[locale.isO3Language]?.firstOrNull() ?: metadatas[locale.language]?.firstOrNull() ?: continue
            metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
            val variant = request.queryParameters["variant"]
            return bibleService.getBible(metadata.id, metadata.version, variant) ?: error("Bible not found")
        }
        throw NoSuchElementException()
    }
}