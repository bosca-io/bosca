package bosca.bml.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientModuleImportsTest {
    @Test
    fun `discovers static imports and reexports while ignoring non executable text and runtime imports`() {
        val source = """
            // import './comment.js'
            const text = "import './string.js'";
            const pattern = /import "\.\/regex.js"/;
            import './side-effect.js';
            import { foo as bar } from "./named.js";
            import defaultValue, * as all from './namespace.js';
            export { bar } from './re-export.js';
            export * as named from './all.js';
            export * from './all.js';
            import('./dynamic.js');
            import.meta.resolve('./resolved.js');
            object.import('./member.js');
        """.trimIndent()
        assertEquals(listOf("./side-effect.js", "./named.js", "./namespace.js", "./re-export.js", "./all.js"), clientModuleImports(source))
    }

    @Test
    fun `decodes module string escapes without executing JavaScript`() {
        assertEquals(listOf("./unicode.js", "./hex.js", "./path.js"), clientModuleImports("""
            import "\u002e/unicode.js";
            import '\x2e/hex.js';
            export * from "\u{2e}/path.js";
        """.trimIndent()))
    }
}
