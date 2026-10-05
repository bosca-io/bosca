package bosca.ksp.generator.route

import bosca.di.annotation.Generated
import bosca.ksp.generator.AbstractGenerator
import bosca.ksp.visitors.FoundRouteController
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ksp.writeTo

class RoutesGenerator(codeGenerator: CodeGenerator, private val prefix: String) : AbstractGenerator<FoundRouteController>(codeGenerator) {

    private fun String.sanitize() = replace('/', '_').replace('{', '_').replace('}', '_').replace('.', '_').replace('-', '_')

    override fun generate(items: Collection<FoundRouteController>) {
        FileSpec.builder(ClassName("bosca.routes", "${prefix}Routes"))
            .addFunction(
                FunSpec.builder("configure${prefix}Routes")
                    .addAnnotation(AnnotationSpec.builder(Generated::class).build())
                    .addModifiers(KModifier.SUSPEND)
                    .receiver(ClassName("bosca.server", "BoscaApplication"))
                    .addParameter(
                        com.squareup.kotlinpoet.ParameterSpec.builder("routePrefix", String::class)
                            .defaultValue("%S", "")
                            .build()
                    )
                    .addCode(buildString {
                        items.forEach { (route, routeProvider, classDeclaration) ->
                            val annotation = classDeclaration.annotations.firstOrNull { it.shortName.asString() == "RouteController" } ?: error("RouteController annotation not found")
                            val path = annotation.arguments.firstOrNull { it.name?.asString() == "path" }?.value as String? ?: error("RouteController path not found")
                            val method = annotation.arguments.firstOrNull { it.name?.asString() == "method" }?.value as KSClassDeclaration? ?: error("RouteController method not found")
                            append("val `${path.sanitize()}_${method.simpleName.asString()}` = provide<$route>()\n")
                        }
                        append("|val authenticationProviders = provide<AuthenticationProviders>()\n")
                        append("|routing {\n")
                        append("|val routeBlock: bosca.server.routing.Router.() -> Unit = {\n")
                        items.groupBy { (route, routeProvider, classDeclaration) ->
                            val annotation = classDeclaration.annotations.firstOrNull { it.shortName.asString() == "RouteController" } ?: error("RouteController annotation not found")
                            val auth = annotation.arguments.firstOrNull { it.name?.asString() == "authentication" }?.value as KSClassDeclaration? ?: error("RouteController auth not found")
                            auth.simpleName.asString()
                        }.forEach { (authMethod, controller) ->
                            when (authMethod) {
                                "NONE" -> {}
                                "REQUIRED" -> append("|\tauthenticate(*authenticationProviders.providers) {\n")
                                "OPTIONAL" -> append("|\tauthenticate(*authenticationProviders.providers, optional = true) {\n")
                            }
                            // Router uses first-match-wins: literal segments must precede parameters
                            // at the same position, regardless of KSP's declaration discovery order.
                            controller.sortedByDescending { (_, _, declaration) ->
                                val annotation = declaration.annotations.first { it.shortName.asString() == "RouteController" }
                                val path = annotation.arguments.first { it.name?.asString() == "path" }.value as String
                                path.split('/').filter { it.isNotEmpty() }.joinToString("") {
                                    when {
                                        it.contains("...") -> "0"
                                        it.startsWith('{') -> "1"
                                        else -> "2"
                                    }
                                }
                            }.forEach { (route, routeProvider, classDeclaration) ->
                                val annotation = classDeclaration.annotations.firstOrNull { it.shortName.asString() == "RouteController" } ?: error("RouteController annotation not found")
                                val path = annotation.arguments.firstOrNull { it.name?.asString() == "path" }?.value as String? ?: error("RouteController path not found")
                                val method = annotation.arguments.firstOrNull { it.name?.asString() == "method" }?.value as KSClassDeclaration? ?: error("RouteController method not found")

                                if (classDeclaration.superTypes.any { it.resolve().declaration.qualifiedName?.asString() == "bosca.routes.SSERoute" }) {
                                    append("|   sse(\"${path}\") {\n")
                                    append("|       `${path.sanitize()}_${method.simpleName.asString()}`.execute(this)\n")
                                    append("|   }\n")
                                } else {
                                    append("|   ${method.simpleName.asString().lowercase()}(\"${path}\") {\n")
                                    append("|       `${path.sanitize()}_${method.simpleName.asString()}`.execute(call)\n")
                                    append("|   }\n")
                                }
                            }
                            when (authMethod) {
                                "NONE" -> {}
                                "REQUIRED", "OPTIONAL" -> append("|}\n")
                            }
                        }
                        append("|}\n")
                        append("|if (routePrefix.isNotEmpty()) route(routePrefix, routeBlock) else routeBlock()\n")
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
