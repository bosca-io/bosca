package bosca.workops.model.spec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequirementContentInputTest {
    @Test
    fun `body retains markdown and appends trimmed criteria`() {
        val input = RequirementContentInput("Title", "Body\n", listOf(" First ", "Second"))
        input.validate()
        assertEquals("Body\n\n## Acceptance criteria\n\n- First\n- Second\n", input.body())
    }

    @Test
    fun `reserved heading is rejected in every parsed Markdown form`() {
        for (heading in listOf("## Acceptance criteria ##", "Acceptance criteria\n-------------------",
            "   ## Acceptance criteria", "## **Acceptance** criteria", "## Acceptance `criteria`",
            "## Acceptance &#99;riteria", "## ~~Acceptance~~ criteria", "## [Acceptance](https://example.test) criteria", "## aCcEpTaNcE CrItErIa")) {
            val input = RequirementContentInput("Title", "Body\n\n$heading\n\n- Obsolete", listOf("Current criterion"))
            assertFailsWith<IllegalArgumentException>(heading) { input.validate() }
        }
    }

    @Test
    fun `heading examples in code and quotes and other levels remain valid`() {
        for (body in listOf("```markdown\n## Acceptance criteria\n```", "    ## Acceptance criteria",
            "> ## Acceptance criteria", "# Acceptance criteria", "### Acceptance criteria", "## Other section", "##", "Body")) {
            val input = RequirementContentInput("Title", body, listOf("Current criterion"))
            input.validate()
            assertEquals(body + "\n\n## Acceptance criteria\n\n- Current criterion\n", input.body())
        }
    }

    @Test
    fun `invalid bodies and ambiguous criteria are rejected`() {
        val valid = RequirementContentInput("Title", "Body", listOf("Pass"))
        for (input in listOf(valid.copy(name = " "), valid.copy(markdownContent = ""),
            valid.copy(acceptanceCriteria = emptyList()), valid.copy(acceptanceCriteria = listOf(" ")),
            valid.copy(acceptanceCriteria = listOf("a\nb")), valid.copy(acceptanceCriteria = listOf("a\rb")),
            valid.copy(acceptanceCriteria = listOf(" Pass ", "Pass")),
            valid.copy(markdownContent = "Body\n## Acceptance criteria\n- Duplicate"))) {
            assertFailsWith<IllegalArgumentException> { input.validate() }
        }
    }
}
