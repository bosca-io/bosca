package bosca.bml.ide

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.ASTNode
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext

/**
 * Autocomplete for BML: tag names after `<` (special/control tags, declared components, common HTML)
 * and attribute names inside a tag (the attributes of the enclosing tag). The PSI is flat, so the
 * enclosing tag is found by walking the completion position's previous siblings back to the tag name.
 */
class BmlCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, psiElement().withElementType(BmlTokens.TAG_NAME), TagNameProvider)
        extend(CompletionType.BASIC, psiElement().withElementType(BmlTokens.ATTR_NAME), AttributeProvider)
        extend(CompletionType.BASIC, psiElement().withElementType(BmlTokens.ATTR_VALUE), AttributeValueProvider)
    }

    private object AttributeValueProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(params: CompletionParameters, ctx: ProcessingContext, result: CompletionResultSet) {
            val host = generateSequence(params.position as PsiElement?) { it.parent }
                .firstOrNull { it.node.elementType == BmlElementTypes.ATTR_VALUE_HOST }
                ?: return
            val attribute = (host as? BmlInjectionHost)?.attributeName() ?: return
            val tag = enclosingTagName(host) ?: return
            if (attribute == "render") {
                if (tag != "island") return
                ISLAND_RENDER_MODES.forEach {
                    result.addElement(LookupElementBuilder.create(it).withTypeText("render mode"))
                }
                return
            }
            if (attribute == "cache" && tag in setOf("page", "route")) {
                result.addElement(LookupElementBuilder.create("shared").withTypeText("cache policy"))
                return
            }
            if (attribute != "scope") return
            if (tag == "component") {
                COMPONENT_SCOPES.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("component scope")) }
                return
            }
            if (tag !in setOf("script", "inject") || "server" !in enclosingAttributeNames(host)) return
            LIVE_STATE_SCOPES.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("state scope")) }
        }
    }

    private object TagNameProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(params: CompletionParameters, ctx: ProcessingContext, result: CompletionResultSet) {
            val project = params.position.project
            SPECIAL_TAGS.forEach {
                result.addElement(LookupElementBuilder.create(it).bold().withTypeText("bml"))
            }
            BmlComponentDecls.all(project).map { it.tag }.distinct().forEach {
                result.addElement(LookupElementBuilder.create(it).withTypeText("component"))
            }
            HTML_TAGS.forEach {
                result.addElement(LookupElementBuilder.create(it).withTypeText("html"))
            }
        }
    }

    private object AttributeProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(params: CompletionParameters, ctx: ProcessingContext, result: CompletionResultSet) {
            val tag = enclosingTagName(params.position) ?: return
            // A custom component: offer its declared props, both as a literal (`label=`) and bound (`:label=`).
            val props = BmlComponentDecls.propsOf(params.position.project, tag)
            if (props.isNotEmpty()) {
                props.forEach {
                    result.addElement(LookupElementBuilder.create(it).withTypeText("prop"))
                    result.addElement(LookupElementBuilder.create(":$it").withTypeText("prop (bound)"))
                }
                return
            }
            val attrs = if (tag == "script") {
                val existing = enclosingAttributeNames(params.position)
                when {
                    "server" in existing -> SERVER_SCRIPT_ATTRS
                    "client" in existing -> CLIENT_SCRIPT_ATTRS
                    else -> SCRIPT_KIND_ATTRS
                }
            } else {
                ATTRS_BY_TAG[tag] ?: COMMON_ATTRS
            }
            attrs.forEach { result.addElement(LookupElementBuilder.create(it)) }
        }

    }

    private companion object {
        private fun enclosingAttributeNames(position: PsiElement): Set<String> {
            val names = LinkedHashSet<String>()
            var node: ASTNode? = position.node.treePrev
            while (node != null) {
                when (node.elementType) {
                    BmlTokens.ATTR_NAME -> names.add(node.text)
                    BmlTokens.TAG_NAME, BmlTokens.TAG_KEYWORD, BmlElementTypes.TAG_NAME_REF -> return names
                    BmlTokens.ANGLE -> return emptySet()
                }
                node = node.treePrev
            }
            return emptySet()
        }

        /** Walk previous siblings of the flat PSI back to the open tag's name (null if not inside one). */
        private fun enclosingTagName(position: PsiElement): String? {
            var node: ASTNode? = position.node.treePrev
            while (node != null) {
                when (node.elementType) {
                    BmlTokens.TAG_NAME, BmlTokens.TAG_KEYWORD, BmlElementTypes.TAG_NAME_REF -> return node.text
                    BmlTokens.ANGLE -> return null
                }
                node = node.treePrev
            }
            return null
        }

        val SPECIAL_TAGS = listOf(
            "page", "route", "template", "component", "slot", "island", "fallback", "prop", "data", "inject",
            "for", "if", "else", "else-if", "contract", "script", "style",
        )
        val HTML_TAGS = listOf(
            "div", "span", "p", "a", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6",
            "section", "article", "header", "footer", "nav", "main", "aside", "button", "form",
            "input", "label", "select", "option", "textarea", "img", "strong", "em", "small",
            "br", "hr", "table", "thead", "tbody", "tr", "td", "th", "head", "body", "html",
            "title", "meta", "link", "pre", "code", "blockquote",
        )
        val COMMON_ATTRS = listOf("class", "id", "style")
        val SCRIPT_KIND_ATTRS = listOf("server", "client")
        val SERVER_SCRIPT_ATTRS = listOf("provides", "scope", "clear-on-sign-out")
        val CLIENT_SCRIPT_ATTRS = listOf("scoped")
        val LIVE_STATE_SCOPES = listOf("page", "client-session", "client-local", "server-session")
        val COMPONENT_SCOPES = listOf("page", "site")
        val ISLAND_RENDER_MODES = listOf("request", "prerender", "deferred")
        val ATTRS_BY_TAG = mapOf(
            "prop" to listOf("name", "type", "required", "default"),
            "component" to listOf("tag", "scope"),
            "island" to listOf("name", "client", "render", "hydrate", "key"),
            "page" to listOf(
                "route", "title", "requireAuth", "contentType", "cache", "maxAge", "staleWhileRevalidate",
            ),
            "route" to listOf(
                "path", "title", "requireAuth", "contentType", "cache", "maxAge", "staleWhileRevalidate",
            ),
            "data" to listOf("provides"),
            "inject" to listOf("name", "type", "provider", "init", "server", "scope", "clear-on-sign-out"),
            "slot" to listOf("name"),
            "style" to listOf("scoped"),
            "template" to listOf("name"),
        )
    }
}
