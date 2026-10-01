package bosca.search.installer

import bosca.configuration.model.ConfigurationInput
import bosca.configuration.service.ConfigurationService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.search.model.SearchTransformConfiguration
import bosca.search.model.SearchTransformExpressions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

class SearchTransformInstaller(
    private val configurationService: ConfigurationService,
    private val json: Json
) : PackageInstaller {
    override val version: String = "1.0.4"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val configuration = configurationService.getByKey("search")
        if (configuration == null) {
            configurationService.setConfiguration(
                ConfigurationInput(
                    key = "search",
                    description = "Search Transformation Configuration",
                    value = json.encodeToJsonElement(
                        SearchTransformConfiguration(
                            expressions = SearchTransformExpressions(
                                metadata = """
                                    ${'$'}merge([
                                      {
                                        "id": ${'$'}string(metadata.id),
                                        "contentId": ${'$'}string(metadata.id),
                                        "slug": slug,
                                        "languageTag": metadata.languageTag,
                                        "name": metadata.name,
                                        "description": attributes.description ? attributes.description : metadata.attributes.description,
                                        "labels": metadata.labels,
                                        "type": metadata.attributes.type,
                                        "_type": "metadata",
                                        "contentType": metadata.contentType,
                                        "published": attributes.published ? attributes.published : ${'$'}toMillis(metadata.created),
                                        "created": ${'$'}toMillis(metadata.created),
                                        "modified": metadata.modified ? ${'$'}toMillis(metadata.modified) : 0,
                                        "categories": categories.{"id": ${'$'}string(id), "name": name},
                                        "content": content,
                                        "bibleBooks": bibleBooks,
                                        "bibleUsfms": bibleUsfms,
                                        "hasDocument": $$.attributes.hasDocument and $$.metadata.contentType != "image/jpeg",
                                        "hasGuide": $$.attributes.hasGuide,
                                        "hasData": $$.attributes.hasData
                                      }
                                    ])
                                """.trimIndent(),
                                collection = """
                                    variants[variant = null or (${'$'}${'$'}.isAdmin or variant.workflowStateId = 'published' or variant.workflowStateId = 'advertised')].(${'$'}merge([
                                      {
                                        "id": ${'$'}string(${'$'}${'$'}.collection.id ?? '00000000-0000-0000-0000-000000000000') & (variant.languageTag ? "-" & variant.languageTag : ""),
                                        "contentId": ${'$'}string(${'$'}${'$'}.collection.id),
                                        "slug": variant.slug ? variant.slug : ${'$'}${'$'}.slug,
                                        "languageTag": variant.languageTag ? variant.languageTag : ${'$'}${'$'}.collection.languageTag,
                                        "name": variant.name ? variant.name : ${'$'}${'$'}.collection.name,
                                        "description": variant.description ? variant.description : (${'$'}${'$'}.collection.description ? ${'$'}${'$'}.collection.description : ${'$'}${'$'}.collection.attributes.description),
                                        "labels": ${'$'}${'$'}.collection.labels,
                                        "_type": "collection",
                                        "type": ${'$'}${'$'}.collection.attributes.type,
                                        "contentType": "bosca/v-collection",
                                        "published": attributes.published ? attributes.published : ${'$'}toMillis(${'$'}${'$'}.collection.created),
                                        "created": ${'$'}toMillis(${'$'}${'$'}.collection.created),
                                        "modified": ${'$'}toMillis(${'$'}${'$'}.collection.modified),
                                        "categories": ${'$'}${'$'}.categories.{"id": ${'$'}string(id), "name": name},
                                        "content": ""
                                      }
                                    ]))
                                """.trimIndent(),
                                profile = """
                                    ${'$'}merge([
                                      {
                                        "id": ${'$'}string(profile.id),
                                        "contentId": ${'$'}string(profile.id),
                                        "slug": slug,
                                        "languageTag": "",
                                        "name": profile.name,
                                        "description": profile.name,
                                        "_type": "profile",
                                        "contentType": profile.type = "organization" or profile.type = "ORGANIZATION" ? "bosca/v-profile-organization" : "bosca/v-profile-generic",
                                        "published": ${'$'}toMillis(profile.created),
                                        "created": ${'$'}toMillis(profile.created),
                                        "modified": 0,
                                        "categories": [],
                                        "content": "",
                                        "memberCount": memberCount,
                                        "country": organization.attributes.country ? organization.attributes.country : attributes[typeId="bosca.profiles.country"].attributes.country
                                      }
                                    ])
                                """.trimIndent()
                            )
                        )
                    ),
                    public = false,
                    permissions = emptyList()
                )
            )
        }
    }
}
