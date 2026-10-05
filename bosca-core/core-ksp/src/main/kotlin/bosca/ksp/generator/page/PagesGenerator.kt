package bosca.ksp.generator.page

import bosca.di.annotation.Generated
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundPageController
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ksp.writeTo

class PagesGenerator(codeGenerator: CodeGenerator, private val prefix: String) : AbstractGenerator<FoundPageController>(codeGenerator) {

    private fun String.sanitize() = replace('/', '_').replace('{', '_').replace('}', '_').replace('.', '_').replace('-', '_')

    override fun generate(items: Collection<FoundPageController>) {
        FileSpec.builder(ClassName("bosca.routes", "${prefix}PageRoutes"))
            .addFunction(
                FunSpec.builder("configure${prefix}PageRoutes")
                    .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                    .addModifiers(KModifier.SUSPEND)
                    .receiver(ClassName("bosca.server", "BoscaApplication"))
                    .addCode(buildString {
                        items.forEach { (page, pageProvider, classDeclaration) ->
                            val annotation = classDeclaration.annotations.firstOrNull { it.shortName.asString() == "PageController" } ?: error("PageController annotation not found")
                            val path = annotation.arguments.firstOrNull { it.name?.asString() == "path" }?.value as String? ?: error("PageController path not found")
                            append("val `${path.sanitize()}` = provide<$page>()\n")
                        }
                        append("|val authenticationProviders = provide<AuthenticationProviders>()\n")
                        append("|routing {\n")
                        items.groupBy { (page, pageProvider, classDeclaration) ->
                            val annotation = classDeclaration.annotations.firstOrNull { it.shortName.asString() == "PageController" } ?: error("PageController annotation not found")
                            val auth = annotation.arguments.firstOrNull { it.name?.asString() == "authentication" }?.value as KSClassDeclaration? ?: error("PageController auth not found")
                            auth.simpleName.asString()
                        }.forEach { (authMethod, controller) ->
                            when (authMethod) {
                                "NONE" -> {}
                                "REQUIRED" -> append("|\tauthenticate(*authenticationProviders.providers) {\n")
                                "OPTIONAL" -> append("|\tauthenticate(*authenticationProviders.providers, optional = true) {\n")
                            }
                            controller.forEach { (page, pageProvider, classDeclaration) ->
                                val annotation = classDeclaration.annotations.firstOrNull { it.shortName.asString() == "PageController" } ?: error("PageController annotation not found")
                                val path = annotation.arguments.firstOrNull { it.name?.asString() == "path" }?.value as String? ?: error("PageController path not found")
                                val method = annotation.arguments.firstOrNull { it.name?.asString() == "method" }?.value as KSClassDeclaration? ?: error("PageController method not found")
                                append("|   ${method.simpleName.asString().lowercase()}(\"${path}\") {\n")
                                append("|       `${path.sanitize()}`.execute(call)\n")
                                append("|   }\n")
                            }
                            when (authMethod) {
                                "NONE" -> {}
                                "REQUIRED", "OPTIONAL" -> append("|}\n")
                            }
                        }
                        append("}\n")
                    }.trimMargin())
                    .build()
            )
            .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"RemoveRedundantBackticks\"").build())
            .addImport("bosca.security.service", "AuthenticationProviders")
            .addImport("bosca.di", "provide")
            .build()
            .writeTo(codeGenerator, true)

    }
}
