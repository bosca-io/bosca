package bosca.ai.installer

import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import bosca.ai.prompts.model.PromptInput
import bosca.ai.prompts.service.PromptService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

class PromptInstaller(
    private val service: PromptService,
    private val json: Json
) : PackageInstaller {
    override val version: String = "1.0.1"

    @Serializable
    private data class DocumentReadingTime(
        val totalWordCount: Int,
        val readingTimeInMinutes: Int
    )

    @Serializable
    private data class DocumentDescription(
        val description: String
    )

    @Serializable
    private data class DocumentTopics(
        val topics: List<UUID> = emptyList()
    )

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        listOf(
            PromptInput(
                key = "prompt.metadata.reading.time",
                name = "Reading Time",
                description = "Generate Reading Time",
                systemPrompt = """
                    You are a utility designed to estimate the reading time of given text. Based on the text provided, compute an estimated reading time in minutes. Assume the average reading speed is 200 words per minute. Your output should include the following details in JSON format only:

                    1. Total word count in the text.
                    2. Reading time in minutes (rounded to the nearest whole number).

                    Important: Ensure the JSON output is formatted cleanly and contains no additional text or explanation.
                """.trimIndent(),
                userPrompt = """
                    Text:
                    {document}
                """.trimIndent(),
                inputType = "text/plain",
                outputType = "application/json",
                schema = StandardJsonSchemaGenerator.generate(
                    json,
                    "DocumentReadingTime",
                    DocumentReadingTime.serializer(),
                    emptyMap(),
                    emptySet()
                ).schema
            ),
            PromptInput(
                key = "prompt.metadata.description",
                name = "Description",
                description = "Generate Description",
                systemPrompt = """
                    Based on the text provided, summarize the text into a concise description with no more than 30 words that can be used for HTML Meta Description.  

                    Important: Ensure the JSON output is formatted cleanly and contains no additional text or explanation.
                """.trimIndent(),
                userPrompt = """
                    Text:
                    {document}
                """.trimIndent(),
                inputType = "text/plain",
                outputType = "application/json",
                schema = StandardJsonSchemaGenerator.generate(
                    json,
                    "DocumentDescription",
                    DocumentDescription.serializer(),
                    emptyMap(),
                    emptySet()
                ).schema
            ),
            PromptInput(
                key = "prompt.metadata.discussion.questions",
                name = "Discussion Questions",
                description = "Generate Discussion Questions",
                systemPrompt = """
                    You are an AI assistant designed to analyze text documents and generate discussion questions. Your task 
                    is to receive a text document from the user, analyze its content, and create 10 thought-provoking discussion 
                    questions. The questions should encourage critical thinking, diverse perspectives, and deeper engagement 
                    with the material. Aim for a balanced mix of question types, including content comprehension, critical 
                    analysis, personal connection, broader implications, and hypothetical scenarios. Format the output as a 
                    numbered list of 10 questions.
                    
                    After carefully reading and considering the document's content, themes, arguments, and potential implications, 
                    generate 10 discussion questions suitable for a group setting. These questions should encourage critical 
                    thinking, diverse perspectives, and deeper engagement with the material. Aim for a mix of questions that address:
                    
                    * **Content Comprehension:** Ensuring understanding of key points.
                    * **Critical Analysis:** Examining the document's strengths, weaknesses, and biases.
                    * **Personal Connection:** Relating the document to individual experiences or perspectives.
                    * **Broader Implications:** Considering the document's relevance to larger issues or contexts.
                    * **Hypothetical Scenarios:** Exploring "what if" scenarios related to the document's themes.
                    
                    Please make the questions as succinct and clear as possible.  Do not reference the word "document" in the questions.
                    
                    The questions should rendered using an html ordered list.  The output must only be the ordered list 
                    with the questions and nothing else.
                """.trimIndent(),
                userPrompt = """
                    Please analyze the following document:
                    {document}
                """.trimIndent(),
                inputType = "text/plain",
                outputType = "text/html",
                schema = JsonNull
            ),
            PromptInput(
                key = "prompt.metadata.topics",
                name = "Topics",
                description = "Generate Topics",
                systemPrompt = """
                    You are an AI assistant trained to analyze text and identify relevant topics from a predefined list of topics. 
                    Your task is to process the given text, extract meaningful topics that are represented or implied within the text, 
                    and match them with a list of available topics provided in JSON format.
                    
                    For each topic matched:
                    1. Include the topic's `id` and `name` from the JSON array in your response.
                    
                    Input Format:
                    1. **Text** – The raw input text to analyze.
                    2. **Available Topics (JSON)** – A list containing topic objects in the JSON format `[{id: "Topic ID", name: "Topic Name"}, ...]`.
                    
                    Key Requirements:
                    - Analyze the input text thoroughly and identify both explicit and implicit topics that are semantically close to the available topics.
                    - Ensure that only topics which are reasonably relevant to the text are included in the response.
                    - Responses must strictly follow the JSON structure with no additional text or explanation.
                """.trimIndent(),
                userPrompt = """
                    Text:
                    {document}
                    
                    Available Topics:
                    {topics}
                """.trimIndent(),
                inputType = "text/plain",
                outputType = "application/json",
                schema = StandardJsonSchemaGenerator.generate(
                    json,
                    "DocumentTopics",
                    DocumentTopics.serializer(),
                    emptyMap(),
                    emptySet()
                ).schema
            ),
            PromptInput(
                key = "prompt.google.genai.image",
                name = "Generate Image",
                description = "Generate an image from the document",
                systemPrompt = """
                    You are an artist and a graphic designer. You are asked to create an image that represents the provided text.
                """.trimIndent(),
                userPrompt = """
                    Text:
                    {document}
                """.trimIndent(),
                inputType = "text/plain",
                outputType = "image/png",
                schema = JsonNull
            ),
        ).forEach {
            val current = service.getByKey(it.key)
            if (current != null) {
                service.edit(current.id, it)
            } else {
                service.add(it)
            }
        }
    }
}