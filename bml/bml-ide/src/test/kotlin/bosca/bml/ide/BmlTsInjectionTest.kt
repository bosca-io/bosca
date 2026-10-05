package bosca.bml.ide

import com.intellij.lang.Language
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * GROUND TRUTH for the `<script client>` TypeScript behavior (needs the Ultimate SDK's bundled
 * JavaScript plugin): the site global (`declare global { var auth: … }` in src/main/client)
 * must resolve from the injected fragment WITH members, object literals passed to its methods
 * must contextually type (e.g., `auth.signUp({ identifier, password, … })` properties red),
 * and the injection prefix's reference-lib directives must supply ES2016+ members (includes).
 */
class BmlTsInjectionTest : BasePlatformTestCase() {

    private val siteTs = """
        export interface SignUpProfile { name: string; visibility: string; attributes: string[] }
        export interface SignUpRequest { identifier: string; password: string; profile: SignUpProfile }
        export class BoscaAuth {
          signUp(req: SignUpRequest): Promise<string> { return Promise.resolve(req.identifier) }
          signInWithPassword(id: string, pw: string): Promise<void> { return Promise.resolve() }
        }
        const auth = new BoscaAuth()
        declare global {
          var auth: BoscaAuth
        }
        window.auth = auth
    """.trimIndent()

    private val signupBml = """
        <page route="/signup">
          <form onsubmit="createAccount(event)"><input name="identifier"/></form>
          <script client>
            const identifier = "a"
            const password = "b"
            const name = "c"
            const flags = ["x"].map((m) => m.trim()).includes("x")
            async function createAccount(ev: Event) {
              ev.preventDefault()
              const principal = await auth.signUp({ identifier, password, profile: { name, visibility: "PUBLIC", attributes: [] } })
              await auth.signInWithPassword(identifier, password)
            }
          </script>
        </page>
    """.trimIndent()

    fun testTypeScriptLanguagePresent() {
        assertNotNull("Ultimate SDK must provide TypeScript", Language.findLanguageByID("TypeScript"))
    }

    fun testFeatureFlagsResolveOnlyThroughTheRuntimeModuleImport() {
        maskLineMarkers()
        myFixture.addFileToProject(
            "node_modules/@bosca/bml/package.json",
            """{"name":"@bosca/bml","version":"1.0.0","exports":{".":{"types":"./src/index.ts","import":"./src/index.ts"}}}""",
        )
        myFixture.addFileToProject(
            "node_modules/@bosca/bml/src/index.ts",
            """
            export interface FeatureFlagEvaluation { flagKey: string; variationKey: string }
            export declare const featureFlags: {
              evaluateAll(): Promise<FeatureFlagEvaluation[]>
            }
            """.trimIndent(),
        )
        val imported = myFixture.addFileToProject(
            "src/main/bml/pages/imported-flags.bml",
            """<page route="/"><script client>import { featureFlags } from "@bosca/bml"
                |void featureFlags.evaluateAll()</script></page>""".trimMargin(),
        )
        myFixture.configureFromExistingVirtualFile(imported.virtualFile)
        val importedProblems = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertTrue(
            "the runtime module export should type the authored import: " +
                importedProblems.joinToString { "'${it.text}': ${it.description}" },
            importedProblems.none {
                it.severity == HighlightSeverity.ERROR && it.text in setOf("featureFlags", "evaluateAll")
            },
        )

        val bare = myFixture.addFileToProject(
            "src/main/bml/pages/bare-flags.bml",
            """<page route="/bare"><script client>void featureFlags.evaluateAll()</script></page>""",
        )
        myFixture.configureFromExistingVirtualFile(bare.virtualFile)
        val bareHost = PsiTreeUtil.findChildrenOfType(myFixture.file, BmlInjectionHost::class.java)
            .first { it.node.elementType == BmlElementTypes.SCRIPT_CLIENT_HOST }
        val bareFragment = InjectedLanguageManager.getInstance(project)
            .getInjectedPsiFiles(bareHost)!!.first().first
        val bareOffset = bareFragment.text.indexOf("featureFlags")
        assertNull(
            "bare featureFlags must remain unresolved without an authored import",
            bareFragment.findReferenceAt(bareOffset + 1)?.resolve(),
        )
    }

    fun testAuthTypedThroughNodeModulesPackage() {
        // A real BML site's shape: BoscaAuth comes from a node_modules package via an exports map
        // ("./core" → TS source), imported by site.ts, published via declare global.
        if (Language.findLanguageByID("TypeScript") == null) return
        maskLineMarkers()
        myFixture.addFileToProject(
            "node_modules/@bosca/auth-client-browser/package.json",
            """{"name": "@bosca/auth-client-browser", "version": "1.0.0",
                "exports": {"./core": {"types": "./src/core.ts", "import": "./src/core.ts"}}}""",
        )
        myFixture.addFileToProject("node_modules/@bosca/auth-client-browser/src/core.ts", siteTs.substringBefore("const auth"))
        myFixture.addFileToProject(
            "src/main/client/site.ts",
            """
            import { BoscaAuth } from "@bosca/auth-client-browser/core"
            const auth = new BoscaAuth()
            declare global {
              var auth: BoscaAuth
            }
            window.auth = auth
            """.trimIndent(),
        )
        val bml = myFixture.addFileToProject("src/main/bml/pages/signup2.bml", signupBml)
        myFixture.configureFromExistingVirtualFile(bml.virtualFile)
        val problems = myFixture.doHighlighting(HighlightSeverity.WARNING)
        for (p in problems) println("=== PKG PROBLEM [${p.severity}] '${p.text}' @${p.startOffset}: ${p.description}")
        val errors = problems.filter {
            it.severity == HighlightSeverity.ERROR &&
                it.text in setOf("identifier", "password", "profile", "name", "visibility", "attributes", "includes", "auth", "signUp")
        }
        assertTrue(
            "no errors with node_modules-typed auth, got: " + errors.joinToString { "'${it.text}': ${it.description}" },
            errors.isEmpty(),
        )
    }

    fun testSignInWithRedirectThroughBarrelReexport() {
        // A representative exact failing shape: the auth package's ./core is a BARREL (`export * from`),
        // the class lives in client.ts, and the method's parameter interface lives in types.ts
        // behind an `import type`. `provider`/`redirectUrl` in the object literal flagged red.
        if (Language.findLanguageByID("TypeScript") == null) return
        maskLineMarkers()
        myFixture.addFileToProject(
            "node_modules/@bosca/auth-client-browser/package.json",
            """{"name": "@bosca/auth-client-browser", "version": "1.0.0",
                "exports": {"./core": {"types": "./src/core.ts", "import": "./src/core.ts"}}}""",
        )
        myFixture.addFileToProject(
            "node_modules/@bosca/auth-client-browser/src/types.ts",
            """
            export type ThirdPartyType = 'GOOGLE' | 'FACEBOOK' | 'APPLE'
            export interface OAuthRedirectOptions {
              provider: ThirdPartyType
              redirectUrl?: string
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "node_modules/@bosca/auth-client-browser/src/client.ts",
            """
            import type { OAuthRedirectOptions } from './types'
            export class BoscaAuth {
              signInWithRedirect(options: OAuthRedirectOptions): void {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "node_modules/@bosca/auth-client-browser/src/core.ts",
            "export * from './types'\nexport * from './client'\n",
        )
        myFixture.addFileToProject(
            "src/main/client/site.ts",
            """
            import { BoscaAuth } from "@bosca/auth-client-browser/core"
            const auth = new BoscaAuth()
            declare global {
              var auth: BoscaAuth
            }
            window.auth = auth
            """.trimIndent(),
        )
        // The LIVE library source also sits in the project, tsconfig-scoped, with the SAME types
        // (bosca-workspace: web/packages/auth is the live checkout, node_modules holds the published
        // copy). If contextual typing resolves into the config-scoped copy, IntelliJ flags
        // "Corresponding file is not included in tsconfig.json" on the literal properties.
        myFixture.addFileToProject(
            "web/packages/auth/tsconfig.json",
            """{"compilerOptions": {"target": "ES2020", "moduleResolution": "bundler"}, "include": ["src/**/*.ts"]}""",
        )
        myFixture.addFileToProject(
            "web/packages/auth/src/types.ts",
            """
            export type ThirdPartyType = 'GOOGLE' | 'FACEBOOK' | 'APPLE'
            export interface OAuthRedirectOptions {
              provider: ThirdPartyType
              redirectUrl?: string
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "web/packages/auth/src/client.ts",
            """
            import type { OAuthRedirectOptions } from './types'
            export class BoscaAuth {
              signInWithRedirect(options: OAuthRedirectOptions): void {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "web/packages/auth/src/core.ts",
            "export * from './types'\nexport * from './client'\n",
        )

        // A SECOND page defining the SAME names (real sites do: sign-in + signup both declare
        // showAuthError/redirectTarget/continueWithGoogle) — module fragments must not bleed.
        myFixture.addFileToProject(
            "src/main/bml/pages/other.bml",
            """
            <page route="/other">
              <button onclick="continueWithGoogle()">Google</button>
              <script client>
                const returnUrl = new URL("/other", location.origin)
                function continueWithGoogle() {
                  auth.signInWithRedirect({provider: "APPLE", redirectUrl: returnUrl.toString()})
                }
              </script>
            </page>
            """.trimIndent(),
        )
        val bml = myFixture.addFileToProject(
            "src/main/bml/pages/account.bml",
            """
            <page route="/account">
              <button onclick="continueWithGoogle()">Google</button>
              <script client>
                const returnUrl = new URL("/account", location.origin)
                function continueWithGoogle() {
                  auth.signInWithRedirect({provider: "GOOGLE", redirectUrl: returnUrl.toString()})
                }
              </script>
            </page>
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(bml.virtualFile)
        val problems = myFixture.doHighlighting(HighlightSeverity.WARNING)
        for (p in problems) println("=== REDIRECT PROBLEM [${p.severity}] '${p.text}' @${p.startOffset}: ${p.description}")
        val errors = problems.filter {
            it.severity == HighlightSeverity.ERROR && it.text in setOf("provider", "redirectUrl", "auth", "signInWithRedirect")
        }
        assertTrue(
            "no errors on the redirect options literal, got: " + errors.joinToString { "'${it.text}': ${it.description}" },
            errors.isEmpty(),
        )
    }

    fun testTsconfigMembershipErrorFilteredInsideFragments() {
        if (Language.findLanguageByID("TypeScript") == null) return
        myFixture.configureByText(
            BmlFileType,
            """<page route="/"><script client>const x = 1</script></page>""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, BmlInjectionHost::class.java)
            .first { it.node.elementType == BmlElementTypes.SCRIPT_CLIENT_HOST }
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)!!.first().first
        val element = injected.findElementAt(injected.text.indexOf("x")) ?: error("no element")

        // HighlightInfo.Builder.create() consults the registered HighlightInfoFilters — so the
        // registered filter's effect is observable right here: the tsconfig-membership error can't
        // even be CREATED against a BML-injected element, while real diagnostics can.
        fun create(target: com.intellij.psi.PsiElement, description: String) =
            com.intellij.codeInsight.daemon.impl.HighlightInfo
                .newHighlightInfo(com.intellij.codeInsight.daemon.impl.HighlightInfoType.ERROR)
                .range(target)
                .descriptionAndTooltip(description)
                .create()

        assertNull(
            "tsconfig-membership noise is rejected inside BML fragments",
            create(element, "Corresponding file is not included in tsconfig.json"),
        )
        assertNotNull(
            "real diagnostics pass through in fragments",
            create(element, "Unresolved variable foo"),
        )
        // Outside BML (a plain .ts file) the tsconfig error is legitimate and must survive.
        val ts = myFixture.addFileToProject("plain.ts", "const y = 2")
        val tsElement = ts.findElementAt(ts.text.indexOf("y")) ?: error("no ts element")
        assertNotNull(
            "tsconfig errors in real .ts files are kept",
            create(tsElement, "Corresponding file is not included in tsconfig.json"),
        )
    }

    private fun maskLineMarkers() {
        // The JS plugin's overriding/implementing line markers crash on injected fragments with a
        // large prefix (platform range-check bug; a real IDE just logs it invisibly). Line markers
        // aren't what these tests cover — mask them so highlighting completes.
        @Suppress("UNCHECKED_CAST")
        com.intellij.testFramework.ExtensionTestUtil.maskExtensions(
            com.intellij.openapi.extensions.ExtensionPointName<com.intellij.lang.LanguageExtensionPoint<com.intellij.codeInsight.daemon.LineMarkerProvider>>(
                "com.intellij.codeInsight.lineMarkerProvider",
            ),
            emptyList(),
            testRootDisposable,
        )
    }

    fun testScriptClientHighlightingGroundTruth() {
        if (Language.findLanguageByID("TypeScript") == null) return
        maskLineMarkers()
        myFixture.addFileToProject("src/main/client/site.ts", siteTs)
        val bml = myFixture.addFileToProject("src/main/bml/pages/signup.bml", signupBml)
        myFixture.configureFromExistingVirtualFile(bml.virtualFile)

        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, BmlInjectionHost::class.java)
            .first { it.node.elementType == BmlElementTypes.SCRIPT_CLIENT_HOST }
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)?.firstOrNull()?.first
        println("=== INJECTED LANG: ${injected?.language?.id} ===")
        println("=== INJECTED TEXT ===\n${injected?.text}\n=== END TEXT ===")

        val problems = myFixture.doHighlighting(HighlightSeverity.WARNING)
        for (p in problems) {
            println("=== PROBLEM [${p.severity}] '${p.text}' @${p.startOffset}: ${p.description}")
        }
        val errorsOnLiteralProps = problems.filter {
            it.severity == HighlightSeverity.ERROR &&
                it.text in setOf("identifier", "password", "profile", "name", "visibility", "attributes", "includes", "auth", "signUp")
        }
        assertTrue(
            "no errors on the signUp literal properties / auth members, got: " +
                errorsOnLiteralProps.joinToString { "'${it.text}': ${it.description}" },
            errorsOnLiteralProps.isEmpty(),
        )
    }
}
