package bosca.ksp.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.annotation.Ignore
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toTypeName

class DatabaseModel(
    definition: KSDeclaration,
    logger: KSPLogger
) {

    val name: String
    val propertyTypes: Map<String, TypeName>
    val columnNames: Map<String, String>
    val nullable: Map<String, Boolean>
    val dbMapper: Map<String, KSType>

    /** Properties whose types are annotated with `@Serializable` and can be auto-mapped to JSONB. */
    val serializable: Map<String, Boolean>

    init {
        name = definition.simpleName.asString()
        val columnNames = mutableMapOf<String, String>()
        val dbMapper = mutableMapOf<String, KSType>()
        val propertyTypes = mutableMapOf<String, TypeName>()
        val nullable = mutableMapOf<String, Boolean>()
        val serializable = mutableMapOf<String, Boolean>()
        if (definition is KSClassDeclaration) {
            definition.getAllProperties().forEach { property ->
                val ignore = property.annotations.find { it.shortName.asString() == Ignore::class.simpleName }
                if (ignore != null) return@forEach
                val type = property.type
                val typeResolved = type.resolve()
                val columnName = property.annotations
                    .firstOrNull { it.shortName.asString() == ColumnName::class.simpleName }
                    ?.arguments
                    ?.first()
                    ?.value
                    ?.toString() ?: property.simpleName.asString()
                // A property-level @DbMapper (e.g. `@property:DbMapper(JsonbMapper::class)`) wins over the
                // type's own class-level @DbMapper, so one jsonb column can opt into a specific mapper
                // without changing the type everywhere it is used.
                val dbMapperType = (property.annotations + typeResolved.declaration.annotations)
                    .firstOrNull { it.shortName.asString() == DbMapper::class.simpleName }
                    ?.arguments
                    ?.first()
                    ?.value
                    ?.let { it as KSType }
                val isSerializable = typeResolved.declaration.annotations
                    .any { it.shortName.asString() == "Serializable" }
                columnNames[property.simpleName.asString()] = columnName
                propertyTypes[property.simpleName.asString()] = type.toTypeName()
                nullable[property.simpleName.asString()] = typeResolved.isMarkedNullable
                dbMapperType?.let {
                    dbMapper[property.simpleName.asString()] = it
                }
                if (isSerializable) {
                    serializable[property.simpleName.asString()] = true
                }
            }
        }
        this.columnNames = columnNames
        this.nullable = nullable
        this.propertyTypes = propertyTypes
        this.dbMapper = dbMapper
        this.serializable = serializable
    }
}