package bosca.ksp.ext

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeName

fun TypeName.toTypeArgument() = if (this is ParameterizedTypeName) {
    this.typeArguments.first()
} else {
    this
}.let {
    if (it.isNullable) {
        it.copy(nullable = false)
    } else {
        it
    }
}

val TypeName.isJsonElement: Boolean
    get() {
        return when (this) {
            is ClassName -> {
                when (packageName) {
                    "kotlinx.serialization.json" -> when (simpleName) {
                        "JsonElement" -> true
                        else -> false
                    }

                    else -> false
                }
            }

            else -> false
        }
    }

val TypeName.isPrimitive: Boolean
    get() {
        if (isJsonElement) return true
        if (this.toString() == "bosca.graphql.scalars.UploadedFile") return true
        return when (this) {
            is ClassName -> {
                when (packageName) {
                    "java.lang" -> when (simpleName) {
                        "String", "Integer", "Long", "Double", "Short", "Boolean" -> true
                        else -> false
                    }

                    "java.time" -> when (simpleName) {
                        "LocalDate", "LocalDateTime", "OffsetDateTime" -> true
                        else -> false
                    }

                    "java.util" -> when (simpleName) {
                        "UUID" -> true
                        else -> false
                    }

                    "kotlin" -> when (simpleName) {
                        "String", "Int", "Long", "Double", "Short", "Boolean" -> true
                        else -> false
                    }

                    "io.r2dbc.postgresql.codec" -> when (simpleName) {
                        "Json" -> true
                        else -> false
                    }

                    "bosca.serialization" -> when (simpleName) {
                        "UUID" -> true
                        else -> false
                    }

                    else -> false
                }
            }

            else -> false
        }
    }

val TypeName.isCollection
    get() = when (this) {
        is ParameterizedTypeName -> {
            when (rawType.packageName) {
                "java.util", "kotlin.collections" -> when (rawType.simpleName) {
                    "Collection" -> true
                    "List" -> true
                    "Set" -> true
                    else -> false
                }
                else -> false
            }
        }

        else -> false
    }

val TypeName.isFlow
    get() = when (this) {
        is ParameterizedTypeName -> {
            rawType.canonicalName == "kotlinx.coroutines.flow.Flow"
        }

        else -> false
    }

val TypeName.isOffsetDateTime
    get() = this is ClassName && (this.packageName == "java.time" || this.packageName == "bosca.serialization") && this.simpleName == "OffsetDateTime"