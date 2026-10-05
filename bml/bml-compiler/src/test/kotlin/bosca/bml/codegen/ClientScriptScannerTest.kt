package bosca.bml.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientScriptScannerTest {
    private fun targets(source: String): List<String> = ClientScriptScanner(source).renderFragmentTargets()

    @Test
    fun `finds literal renderFragment targets in executable code`() {
        assertEquals(
            listOf("cart-panel", "account-card", "badge"),
            targets(
                """
                const a = await renderFragment("cart-panel", {})
                const b = await renderFragment( 'account-card' )
                const c = renderFragment(`badge`)
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `ignores calls mentioned in comments and strings`() {
        assertEquals(
            emptyList(),
            targets(
                """
                // TODO: renderFragment("admin-panel")
                /* renderFragment("admin-panel") */
                const note = "renderFragment('admin-panel')"
                const doc = `see renderFragment("admin-panel")`
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `regex literals neither hide later calls nor contribute their text as targets`() {
        assertEquals(
            listOf("cart-panel", "badge", "after-condition", "after-member"),
            targets(
                """
                const quote = /["']/;
                const fake = /renderFragment("unused")/;
                const ratio = total / 2;
                renderFragment("cart-panel");
                function badgeFor(value: string) {
                    return /["']/.test(value) ? renderFragment("badge") : "";
                }
                if (ready) /["']/.test(value);
                renderFragment("after-condition");
                const memberRatio = config.return / (renderFragment("after-member"), 2) / 3;
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `regex after a statement block is distinct from division after expressions`() {
        assertEquals(
            listOf("after-block", "after-object", "after-function", "after-async-function", "after-class"),
            targets(
                """
                if (true) {} /["']/.test(value); renderFragment("after-block");
                if (true) {} /renderFragment("fake")/.test(value);
                const quotient = {} / (renderFragment("after-object"), 2) / 3;
                const functionQuotient = function() {} / (renderFragment("after-function"), 2) / 3;
                const asyncQuotient = async function() {} / (renderFragment("after-async-function"), 2) / 3;
                const classQuotient = class {} / (renderFragment("after-class"), 2) / 3;
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `regex after declarations and control blocks does not hide later calls`() {
        assertEquals(
            listOf(
                "after-function", "after-typed-function", "after-type-literal", "after-async-function",
                "after-class", "after-switch", "after-catch",
            ),
            targets(
                """
                function declared() {} /["']/.test(value); renderFragment("after-function");
                function typed(): string {} /["']/.test(value); renderFragment("after-typed-function");
                function typedLiteral(): { value: string; extra: number } {} /["']/.test(value); renderFragment("after-type-literal");
                async function declaredAsync() {} /["']/.test(value); renderFragment("after-async-function");
                class Declared {} /["']/.test(value); renderFragment("after-class");
                switch (value) {} /["']/.test(value); renderFragment("after-switch");
                try {} catch (error) {} /["']/.test(value); renderFragment("after-catch");
                function ignored() {} /renderFragment("fake")/.test(value);
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `template expressions are code but dynamic template tags are not literal targets`() {
        assertEquals(
            listOf("inner-card", "expression-division"),
            targets(
                """
                const html = `${'$'}{await renderFragment("inner-card")}`
                const quotient = `${'$'}{function() {} / (renderFragment("expression-division"), 2) / 3}`
                renderFragment(`card-${'$'}{kind}`)
                renderFragment(tagName)
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `masking preserves positions and line breaks`() {
        val source = "a(\"x\") // c\nb"
        val masked = ClientScriptScanner(source).maskNonCode()
        assertEquals(source.length, masked.length)
        // Literal contents and comment text become spaces; quotes, code, and the line break remain.
        assertEquals("a(\" \")     \nb", masked)
    }
}
