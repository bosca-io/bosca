package bosca.content.tools.installer

import bosca.content.tools.model.TemplateAttributeToolInput
import bosca.content.tools.service.TemplateAttributeToolService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import kotlinx.serialization.json.JsonObject

class ToolsPackageInstaller(
    private val service: TemplateAttributeToolService
) : PackageInstaller {
    override val version: String = "1.0.0"
    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val current = service.getAll().associateBy { it.key }
        listOf(
            TemplateAttributeToolInput(
                name = "Description",
                description = "A tool for generating descriptions",
                key = "generate-description",
                query = $$"""
                    query GetGeneratedDescription($metadataId: UUID!, $version: Int!) {
                      content {
                        metadata(id: $metadataId, version: $version) {
                          ai {
                            description
                          }
                        }
                      }
                    }
                """.trimIndent(),
                resultPath = "content.metadata.ai.description",
                configuration = JsonObject(emptyMap()),
            ),
            TemplateAttributeToolInput(
                name = "Discussion Questions",
                description = "A tool for generating discussion questions",
                key = "generate-discussion-questions",
                query = $$"""
                    query GenerateDiscussionQuestions($metadataId: UUID!, $version: Int!) {
                      content {
                        metadata(
                          id: $metadataId,
                          version: $version,
                        ) {
                          ai {
                            content(type: "discussion.questions")
                          }
                        }
                      }
                    }
                """.trimIndent(),
                resultPath = "content.metadata.ai.content.content[0]",
                configuration = JsonObject(emptyMap()),
            ),
            TemplateAttributeToolInput(
                name = "Topics",
                description = "A tool for generating topics",
                key = "generate-topics",
                query = $$"""
                    query GenerateTopics($metadataId: UUID!, $version: Int!) {
                        content {
                            metadata(
                                id: $metadataId,
                                version: $version,
                            ) {
                                ai {
                                    topics {
                                        id
                                        name
                                    }
                                }
                            }
                        }
                    }
                """.trimIndent(),
                resultPath = "content.metadata.ai.topics",
                configuration = JsonObject(emptyMap()),
            ),
            TemplateAttributeToolInput(
                name = "Reading Time",
                description = "A tool for calculating reading time",
                key = "reading-time",
                query = $$"""
                    query CalculateReadingTime($metadataId: UUID!, $version: Int!) {
                        content {
                            metadata(id: $metadataId, version: $version) {
                                ai {
                                    readingTimeInMinutes
                                }
                            }
                        }
                    }
                """.trimIndent(),
                resultPath = "content.metadata.ai.readingTimeInMinutes",
                configuration = JsonObject(emptyMap()),
            ),
            TemplateAttributeToolInput(
                name = "MP3 (Male Voice)",
                description = "A tool for generating MP3 files",
                key = "generate-mp3-male",
                query = $$"""
                    mutation GenerateMp3FromDocument($metadataId: UUID!, $version: Int!) {
                      content {
                        metadata {
                          ai {
                            generateMp3FromDocument(
                              id: $metadataId,
                              version: $version,
                              modelKey: "model.google.chirp.hd.algieba",
                              relationship: "audio.featured",
                              configuration: {
                                excludeContainers: [
                                  "BIBLE_REFERENCES",
                                  "CTA_TITLE",
                                  "CTA",
                                  "CTA_ITEMS",
                                  "KEY_TAKEAWAY",
                                  "KEY_QUOTE",
                                  "KEY_QUOTE_CREDIT",
                                  "KEY_POINTS",
                                  "SCRIPTURE",
                                  "ILLUSTRATION"
                                ],
                                includeTitle: true,
                                includeTtsMarkup: true
                              }
                            ) {
                              id
                              name
                              attributes
                              content { type }
                            }
                          }
                        }
                      }
                    }
                """.trimIndent(),
                resultPath = "content.metadata.ai.generateMp3FromDocument",
                configuration = JsonObject(emptyMap()),
            ),
            TemplateAttributeToolInput(
                name = "MP3 (Female Voice)",
                description = "A tool for generating MP3 files",
                key = "generate-mp3-female",
                query = $$"""
                    mutation GenerateMp3FromDocument($metadataId: UUID!, $version: Int!) {
                      content {
                        metadata {
                          ai {
                            generateMp3FromDocument(
                              id: $metadataId,
                              version: $version,
                              modelKey: "model.google.chirp.hd.zephyr",
                              relationship: "audio.featured",
                              configuration: {
                                excludeContainers: [
                                  "BIBLE_REFERENCES",
                                  "CTA_TITLE",
                                  "CTA",
                                  "CTA_ITEMS",
                                  "KEY_TAKEAWAY",
                                  "KEY_QUOTE",
                                  "KEY_QUOTE_CREDIT",
                                  "KEY_POINTS",
                                  "SCRIPTURE",
                                  "ILLUSTRATION"
                                ],
                                includeTitle: true,
                                includeTtsMarkup: true
                              }
                            ) {
                              id
                              name
                              attributes
                              content { type }
                            }
                          }
                        }
                      }
                    }
                """.trimIndent(),
                resultPath = "content.metadata.ai.generateMp3FromDocument",
                configuration = JsonObject(emptyMap()),
            ),
            TemplateAttributeToolInput(
                name = "Image",
                description = "A tool for generating image files",
                key = "generate-image",
                query = $$"""
                    mutation GenerateImageFromDocument($metadataId: UUID!, $version: Int!) {
                      content {
                        metadata {
                          ai {
                            generateImageFromDocument(
                              id: $metadataId,
                              version: $version,
                              modelKey: "model.google.genai.image",
                              promptKey: "prompt.google.genai.image",
                              relationship: "image.featured",
                              configuration: {
                                includeTitle: true,
                                includeTtsMarkup: false
                              }
                            ) {
                              id
                              name
                              attributes
                              content { type }
                            }
                          }
                        }
                      }
                    }
                """.trimIndent(),
                resultPath = "content.metadata.ai.generateImageFromDocument",
                configuration = JsonObject(emptyMap()),
            )
        ).forEach {
            val c = current[it.key]
            if (c == null) {
                service.add(it)
            } else {
                service.edit(c.id, it)
            }
        }
    }
}
