package bosca.bml.ide

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * GROUND TRUTH for the "listOf won't resolve in <script server>" report: add a real kotlin-stdlib to
 * the fixture module and call resolve() on the injected `listOf` exactly as Cmd-click does. If it
 * resolves here, injection CAN resolve libraries (and the IDE issue is environmental); if it doesn't,
 * this is a faithful local repro to fix against — no forum hearsay, no user round-trips.
 */
class BmlInjectedResolutionTest : BasePlatformTestCase() {

    private val withStdlib = object : LightProjectDescriptor() {
        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)
            STDLIB?.let { (dir, jar) -> PsiTestUtil.addLibrary(model, "kotlin-stdlib", dir, jar) }
        }
    }

    override fun getProjectDescriptor(): LightProjectDescriptor = withStdlib

    private companion object {
        // Resolution tests need a real kotlin-stdlib on the fixture module. Locate it in the Gradle cache
        // (any version); if absent (e.g. a clean CI box) the resolution tests skip rather than fail.
        private val STDLIB: Pair<String, String>? = run {
            val base = java.io.File(
                System.getProperty("user.home"),
                ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib",
            )
            base.walkTopDown()
                .firstOrNull { it.isFile && it.name.matches(Regex("kotlin-stdlib-[0-9.]+\\.jar")) }
                ?.let { it.parentFile.absolutePath to it.name }
        }
    }

    fun testInjectedListOfDoesNotResolve_K2Limitation() {
        if (STDLIB == null) return
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><script server>val xs = listOf("a", "b")</script></page>""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, BmlInjectionHost::class.java)
            .first { it.node.elementType == BmlElementTypes.SCRIPT_SERVER_HOST }
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)!!.first().first
        val text = injected.text
        val idx = text.indexOf("listOf")
        val ref = injected.findReferenceAt(idx + 1)
        val resolved = ref?.resolve()

        println("=== INJECTED FRAGMENT ===\n$text")
        println("=== listOf: ref=${ref?.javaClass?.simpleName} resolved=$resolved file=${resolved?.containingFile?.name} ===")

        assertNotNull("the injected fragment must exist", injected)
        // Documents the K2 platform wall that BmlFragmentResolver works around: the injected fragment
        // itself resolves NOTHING (no library scope). If this ever starts resolving, K2 injection was
        // fixed upstream and the BmlFragmentResolver goto/completion path can be revisited.
        assertNull("K2: injected Kotlin does NOT resolve library symbols (the wall we work around)", resolved)
    }

    /**
     * The make-or-break spike: does a Kotlin CODE FRAGMENT with an explicit context element (a real
     * file in the module) resolve `listOf`, where the injected file does not? If yes, a fragment/
     * in-memory-file approach (context = the .bml's module) is the viable replacement for injection.
     */
    fun testContextCodeFragmentResolves() {
        if (STDLIB == null) return
        val ctxFile = myFixture.addFileToProject("Ctx.kt", "package p\nclass Ctx { fun anchor() {} }")
        val anchor = ctxFile as org.jetbrains.kotlin.psi.KtFile
        val context = anchor.declarations.first() // the Ctx class — an element in the module
        val fragment = org.jetbrains.kotlin.psi.KtBlockCodeFragment(
            project, "fragment.kt", "val xs = listOf(\"a\", \"b\")", null, context,
        )
        val idx = fragment.text.indexOf("listOf")
        val ref = fragment.findReferenceAt(idx + 1)
        val resolved = ref?.resolve()
        println("=== CODE FRAGMENT: ref=${ref?.javaClass?.simpleName} resolved=$resolved file=${resolved?.containingFile?.name} ===")
        assertNotNull("listOf must resolve in a context-anchored code fragment", resolved)
    }

    /**
     * Can the fragment anchor directly on a `.bml` element (its module is what we want), or must the
     * context be a real Kotlin file? If this resolves, the feature is simple: build a fragment anchored
     * on the host BML element under the caret.
     */
    fun testCodeFragmentAnchoredOnBmlElementResolves() {
        if (STDLIB == null) return
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><script server>val xs = listOf("a", "b")</script></page>""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, BmlInjectionHost::class.java)
            .first { it.node.elementType == BmlElementTypes.SCRIPT_SERVER_HOST }
        val fragment = org.jetbrains.kotlin.psi.KtBlockCodeFragment(
            project, "fragment.kt", "val xs = listOf(\"a\", \"b\")", null, host,
        )
        val idx = fragment.text.indexOf("listOf")
        val resolved = fragment.findReferenceAt(idx + 1)?.resolve()
        println("=== ANCHORED-ON-BML: resolved=$resolved file=${resolved?.containingFile?.name} ===")
        assertNull("anchoring on the .bml element does NOT give library scope", resolved)
    }

    /**
     * Implementation shape: can I reuse the EXACT injected fragment text (the `suspend fun __bml(...)`
     * wrapper) inside a .kt-anchored KtBlockCodeFragment, so the offset under the caret maps 1:1? If
     * `listOf` resolves here, the goto handler can take the injected file's text + offset verbatim and
     * just re-resolve it in an anchored fragment.
     */
    fun testReuseInjectedTextInAnchoredFragmentResolves() {
        if (STDLIB == null) return
        val ctxFile = myFixture.addFileToProject("Ctx2.kt", "package p\nclass Anchor") as org.jetbrains.kotlin.psi.KtFile
        val anchor = ctxFile.declarations.first()
        val fullText = "suspend fun __bml() {\n" +
            "val greeting: String = TODO()\n" +
            "val fruits: List<String> = TODO()\n" +
            "val xs = listOf(\"a\", \"b\", greeting)\n" +
            "}"
        val fragment = org.jetbrains.kotlin.psi.KtBlockCodeFragment(project, "bml.kt", fullText, null, anchor)
        val listOfIdx = fragment.text.indexOf("listOf")
        val fruitsIdx = fragment.text.lastIndexOf("greeting") // the USE site of greeting
        val listOfResolved = fragment.findReferenceAt(listOfIdx + 1)?.resolve()
        val greetingResolved = fragment.findReferenceAt(fruitsIdx + 1)?.resolve()
        println("=== REUSE-TEXT: listOf=$listOfResolved (${listOfResolved?.containingFile?.name}) greeting=$greetingResolved ===")
        assertNotNull("listOf resolves in a .kt-anchored fragment reusing the wrapper text", listOfResolved)
        assertNotNull("a local provides val (greeting) also resolves in the same fragment", greetingResolved)
    }

    /** End-to-end: BmlFragmentResolver against a REAL injected file resolves the embedded `listOf`. */
    fun testResolverResolvesAgainstRealInjectedFile() {
        if (STDLIB == null) return
        myFixture.addFileToProject("Anchor3.kt", "package p\nclass Anchor3") // a Kotlin anchor in the module
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><script server>val xs = listOf("a", "b")</script></page>""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, BmlInjectionHost::class.java)
            .first { it.node.elementType == BmlElementTypes.SCRIPT_SERVER_HOST }
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)!!.first().first
        val off = injected.text.indexOf("listOf")
        val target = BmlFragmentResolver.resolve(host, injected.text, off + 1)
        println("=== RESOLVER: target=$target file=${target?.containingFile?.name} ===")
        assertNotNull("BmlFragmentResolver resolves injected listOf against a real injected file", target)
    }

    /** Cmd-click on a `<for>` variable's member resolves through the re-opened loop's inferred type. */
    fun testForVariableMemberResolves() {
        if (STDLIB == null) return
        myFixture.addFileToProject("Anchor5.kt", "package p\nclass Anchor5")
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><for item in listOf("a")><li>{ item.length }</li></for></page>""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, BmlInjectionHost::class.java)
            .first { it.text.contains("item.length") }
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)!!.first().first
        val off = injected.text.indexOf("item.length") + "item.".length + 1 // inside `length`
        val target = BmlFragmentResolver.resolve(host, injected.text, off)
        println("=== FORVAR-MEMBER: target=$target file=${target?.containingFile?.name} ===")
        assertNotNull("`item.length` resolves via the re-opened for-loop (String.length)", target)
    }

    /** Cmd-click on the `<for>` variable itself lands on the `<for item in …>` header in the .bml. */
    fun testForVariableResolvesToItsHeader() {
        if (STDLIB == null) return
        myFixture.addFileToProject("Anchor6.kt", "package p\nclass Anchor6")
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><for item in listOf("a")><li>{ item.length }</li></for></page>""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, BmlInjectionHost::class.java)
            .first { it.text.contains("item.length") }
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)!!.first().first
        val off = injected.text.lastIndexOf("item.length") + 1 // inside the `item` USE
        val target = BmlFragmentResolver.resolve(host, injected.text, off)
        println("=== FORVAR-DECL: target=$target file=${target?.containingFile?.name} ===")
        assertNotNull("`item` resolves", target)
        assertEquals("…to the for header in the .bml", "BML", target!!.containingFile.language.id)
    }

    /** Member completion: `item.<caret>` inside a for over List<String> offers String members. */
    fun testMemberCompletionOnForVariable() {
        if (STDLIB == null) return
        myFixture.addFileToProject("Anchor7.kt", "package p\nclass Anchor7")
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><for item in listOf("a")><li>{ item.<caret> }</li></for></page>""",
        )
        val lookups = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        println("=== MEMBER-COMPLETION: ${lookups.take(30)} ===")
        assertTrue("String members offered (got: ${lookups.take(20)})", "length" in lookups)
    }

    /** Cmd-click on a `form.<field>` action argument lands on the field's `name="…"` in the .bml. */
    fun testFormFieldResolvesToItsInput() {
        if (STDLIB == null) return
        myFixture.addFileToProject("Anchor8.kt", "package p\nclass Anchor8")
        myFixture.configureByText(
            BmlFileType,
            "<page route=\"/\"><form @submit=\"m.create(form.email, ctx)\"><input name=\"email\"/></form></page>",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, BmlInjectionHost::class.java)
            .first { it.text.contains("form.email") }
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)!!.first().first
        val off = injected.text.indexOf("form.email") + "form.".length + 1 // inside `email`
        val target = BmlFragmentResolver.resolve(host, injected.text, off)
        println("=== FORMFIELD: target=$target text='${target?.text}' file=${target?.containingFile?.name} ===")
        assertNotNull("form.email resolves", target)
        assertEquals("…to the input's name attribute in the .bml", "BML", target!!.containingFile.language.id)
    }

    /** Member completion after `form.` offers the enclosing form's fields. */
    fun testFormFieldCompletion() {
        if (STDLIB == null) return
        myFixture.addFileToProject("Anchor9.kt", "package p\nclass Anchor9")
        myFixture.configureByText(
            BmlFileType,
            "<page route=\"/\"><form @submit=\"m.create(form.<caret>)\">" +
                "<input type=\"text\" name=\"email\"/><input type=\"checkbox\" name=\"aiEnabled\"/></form></page>",
        )
        val lookups = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        println("=== FORM-COMPLETION: ${lookups.take(30)} ===")
        assertTrue("field names offered (got: ${lookups.take(20)})", "email" in lookups && "aiEnabled" in lookups)
    }

    /** The goto handler returns a real declaration target for the injected `listOf` under the caret. */
    fun testGotoHandlerReturnsTarget() {
        if (STDLIB == null) return
        myFixture.addFileToProject("Anchor4.kt", "package p\nclass Anchor4")
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><script server>val xs = listOf("a", "b")</script></page>""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, BmlInjectionHost::class.java)
            .first { it.node.elementType == BmlElementTypes.SCRIPT_SERVER_HOST }
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)!!.first().first
        val leaf = injected.findElementAt(injected.text.indexOf("listOf") + 1)
        val targets = BmlKotlinGotoHandler().getGotoDeclarationTargets(leaf, 0, null)
        println("=== GOTO HANDLER: targets=${targets?.size} first=${targets?.firstOrNull()?.containingFile?.name} ===")
        assertNotNull("goto handler returns targets for injected listOf", targets)
        assertTrue("at least one target", targets!!.isNotEmpty())
    }
}
