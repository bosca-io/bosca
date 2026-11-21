package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsSpecGitTest {
    private val binding = Json.parseToJsonElement("""{
      "profiles":{"current":[
        {"name":"Secondary profile","isPrimary":false,"attributes":[]},
        {"name":"Human Author","isPrimary":true,"attributes":[{"typeId":"bosca.profiles.email","attributes":{"email":"human@example.test"}}]}
      ]},"workOps":{"specs":{"spec":{"gitRepositoryId":"repository","gitPath":"specs/document.json"}}}
    }""")
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun loaded(operation: String): JsonElement = when (operation) {
        "WorkOpsSpecGit" -> binding
        "WorkOpsSpecGitRepositories" -> Json.parseToJsonElement("""{"git":{"repositories":[]}}""")
        else -> error(operation)
    }

    @Test fun `secondary profile fallback never coerces malformed email attributes into a commit identity`() = runTest {
        for (attributes in listOf("null", "[]", "{}", """{"email":3}""", """{"email":null}""")) {
            val page = WorkOpsSpecGitModel("spec")
            page.retry(context { operation, _ ->
                assertEquals("WorkOpsSpecGit", operation)
                Json.parseToJsonElement("""{"profiles":{"current":[{"name":" Human Author ","isPrimary":false,
                  "attributes":[{"typeId":"unrelated","attributes":{}},{"typeId":"bosca.profiles.email","attributes":$attributes}]}]},
                  "workOps":{"specs":{"spec":{"gitRepositoryId":null,"gitPath":null}}}}""")
            })
            assertFalse(page.feedback.failed, page.feedback.message)
            assertEquals("Human Author", page.authorNameDraft)
            assertEquals("", page.authorEmailDraft)
            assertFalse(page.bound)
            assertEquals("", page.repositoryHref)
        }
    }

    @Test fun `push rechecks binding existence repository and saved document result after validating human details`() = runTest {
        val page = WorkOpsSpecGitModel("spec")
        page.reload(context { operation, _ -> loaded(operation) })
        page.open()
        for (email in listOf("missing-at", "human @example.test")) {
            page.push("Human Author", email, context { operation, _ -> throw AssertionError("Invalid author attempted $operation") })
            assertEquals("Enter the commit author's email.", page.feedback.message)
            assertEquals(email, page.authorEmailDraft)
        }
        page.push("Human Author", "human@example.test", context { operation, _ ->
            assertEquals("WorkOpsSpecGit", operation)
            Json.parseToJsonElement("""{"profiles":{"current":[]},"workOps":{"specs":{"spec":null}}}""")
        })
        assertEquals("The specification is unavailable.", page.feedback.message)
        page.push("Human Author", "human@example.test", context { operation, _ ->
            assertEquals("WorkOpsSpecGit", operation)
            Json.parseToJsonElement(binding.toString().replace("\"gitRepositoryId\":\"repository\"", "\"gitRepositoryId\":\"other\""))
        })
        assertEquals("The Git binding changed. Reload it before pushing.", page.feedback.message)
        page.push("Human Author", "human@example.test", context { operation, _ ->
            if (operation == "WorkOpsSpecGit") binding else Json.parseToJsonElement("""{"workOps":{"specs":{"pushSavedToGit":null}}}""")
        })
        assertEquals("The specification is not linked to a Git file.", page.feedback.message)
        assertTrue(page.pushing)
        page.cancel()
        assertFalse(page.pushing)
    }

    @Test fun `author defaults use the current primary human profile and filtered repository names do not change push permission`() = runTest {
        val model = WorkOpsSpecGitModel("spec")
        model.reload(context { operation, _ -> loaded(operation) })
        assertEquals("Human Author", model.authorNameDraft); assertEquals("human@example.test", model.authorEmailDraft)
        assertEquals("", model.repositoryName); assertEquals("/repositories/repository", model.repositoryHref); assertTrue(model.bound)
        model.open()
        model.push("Human Author", "human@example.test", context { operation, variables -> when (operation) {
            "WorkOpsSpecGit" -> binding
            "WorkOpsPushSavedSpec" -> {
                assertEquals("spec", variables.getValue("id").jsonPrimitive.content)
                assertEquals("Human Author", variables.getValue("authorName").jsonPrimitive.content)
                assertEquals("human@example.test", variables.getValue("authorEmail").jsonPrimitive.content)
                assertFalse(variables.containsKey("content"))
                Json.parseToJsonElement("""{"workOps":{"specs":{"pushSavedToGit":"saved-sha"}}}""")
            }
            else -> error(operation)
        } })
        assertFalse(model.feedback.failed); assertFalse(model.pushing); assertEquals("saved-sha", model.commitSha)
    }

    @Test fun `binding changes and rejected pushes retain explicit author drafts without another destination write`() = runTest {
        val model = WorkOpsSpecGitModel("spec")
        model.reload(context { operation, _ -> loaded(operation) }); model.open()
        model.push("Updated human", "updated@example.test", context { operation, _ ->
            assertEquals("WorkOpsSpecGit", operation)
            Json.parseToJsonElement(binding.toString().replace("specs/document.json", "another/file.md"))
        })
        assertEquals("The Git binding changed. Reload it before pushing.", model.feedback.message)
        assertTrue(model.pushing); assertEquals("Updated human", model.authorNameDraft)
        model.push("Updated human", "updated@example.test", context { operation, _ ->
            if (operation == "WorkOpsSpecGit") binding else { assertEquals("WorkOpsPushSavedSpec", operation); error("Push rejected") }
        })
        assertTrue(model.feedback.failed); assertEquals("updated@example.test", model.authorEmailDraft); assertTrue(model.pushing)
        model.authorEmailDraft = ""
        model.reload(context { operation, _ -> loaded(operation) }); assertEquals("", model.authorEmailDraft, "reload keeps explicitly cleared author fields")
        assertFailsWith<CancellationException> { model.retry(context { _, _ -> throw CancellationException("closed") }) }
    }

    @Test fun `missing profile details never become a synthetic commit identity`() = runTest {
        val model = WorkOpsSpecGitModel("spec")
        model.reload(context { operation, _ ->
            if (operation == "WorkOpsSpecGit") Json.parseToJsonElement("""{"profiles":{"current":[]},"workOps":{"specs":{"spec":{"gitRepositoryId":"repository","gitPath":"specs/document.md"}}}}""") else loaded(operation)
        })
        assertEquals("", model.authorNameDraft); assertEquals("", model.authorEmailDraft)
        model.open()
        model.push("", "", context { operation, _ -> error("Unexpected $operation") })
        assertEquals("Enter the commit author's name.", model.feedback.message); assertTrue(model.pushing)
        model.push("Human Author", "invalid", context { operation, _ -> error("Unexpected $operation") })
        assertEquals("Enter the commit author's email.", model.feedback.message)
    }
}
