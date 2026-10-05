package bosca.ksp.generator.db

import bosca.ksp.ext.toTypeArgument
import bosca.ksp.model.DatabaseModel
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toTypeName

enum class CollectionType {
    LIST, SET, FLOW, SINGLE, NONE
}

class ReturnType(returnType: KSTypeReference, collectionType: KSType, logger: KSPLogger) {

    val annotations = mutableListOf<AnnotationSpec>()
    val type: KSDeclaration
    val typeName: TypeName = returnType.toTypeName()
    val rawTypeName: TypeName = typeName.toTypeArgument()
    val model: DatabaseModel?
    val resultCollectionType: CollectionType
    val nullable: Boolean

    init {
        val resolvedReturnType = returnType.resolve()
        val originalReturnTypeClass = resolvedReturnType.declaration
        nullable = resolvedReturnType.isMarkedNullable

        if (originalReturnTypeClass !is KSClassDeclaration) {
            resultCollectionType = CollectionType.NONE
            type = originalReturnTypeClass
            model = null
        } else {
            type = when (originalReturnTypeClass.packageName.asString()) {
                "kotlinx.coroutines.flow" -> {
                    resultCollectionType = CollectionType.FLOW
                    annotations += AnnotationSpec.builder(ClassName("kotlin", "OptIn")).addMember("ExperimentalCoroutinesApi::class").build()
//                    flowOp = "flatMapConcat"
//                    typeReturnCode = ".asFlow()"
//                    resultFlowOp = ""
                    resolvedReturnType.arguments.first().type?.resolve()?.declaration ?: error("missing type")
                }

                "kotlin.collections",
                "java.util" -> {
                    if (collectionType.isAssignableFrom(resolvedReturnType)) {
                        val type = resolvedReturnType.arguments.first().type?.resolve()?.declaration ?: error("missing type")
                        annotations += AnnotationSpec.builder(ClassName("kotlin", "OptIn")).addMember("ExperimentalCoroutinesApi::class").build()
                        resultCollectionType = when (type.simpleName.asString()) {
                            "Set" -> CollectionType.SET
                            else -> CollectionType.LIST
                        }
                        type
                    } else {
//                        flowOp = "map"
//                        typeReturnCode = ".awaitFirstOrNull()"
//                        resultFlowOp = ".firstOrNull()$nullableCheck"
//                        originalReturnTypeClass
                        TODO()
                    }
                }

                else -> {
                    resultCollectionType = CollectionType.SINGLE
                    originalReturnTypeClass
                }
            }

            model = DatabaseModel(type, logger)
        }
    }


    val isCollection = resultCollectionType == CollectionType.LIST || resultCollectionType == CollectionType.SET
}