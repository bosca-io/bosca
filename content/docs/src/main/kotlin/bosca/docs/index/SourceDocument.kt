package bosca.docs.index

import kotlinx.serialization.Serializable

@Serializable
data class SourceDocument(
    val qualifiedName: String,
    val simpleName: String,
    val kind: String,
    val category: String,
    val module: String,
    val pkg: String,
    val filePath: String,
    val kdoc: String = "",
    val annotations: List<AnnotationInfo> = emptyList(),
    val supertypes: List<String> = emptyList(),
    val methods: List<MethodInfo> = emptyList(),
    val properties: List<PropertyInfo> = emptyList(),
    val enumValues: List<String> = emptyList(),
    val accessPattern: String = "",
)

@Serializable
data class AnnotationInfo(
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
)

@Serializable
data class MethodInfo(
    val name: String,
    val signature: String,
    val returnType: String = "",
    val parameters: List<ParameterInfo> = emptyList(),
    val kdoc: String = "",
    val isSuspend: Boolean = false,
    val annotations: List<AnnotationInfo> = emptyList(),
)

@Serializable
data class ParameterInfo(
    val name: String,
    val type: String,
    val defaultValue: String? = null,
)

@Serializable
data class PropertyInfo(
    val name: String,
    val type: String,
    val kdoc: String = "",
    val isMutable: Boolean = false,
    val annotations: List<AnnotationInfo> = emptyList(),
    val defaultValue: String? = null,
)
