package bosca.ksp.ext

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * Validates the TypeName extension properties used by KSP generators to classify
 * and transform KotlinPoet type representations. These extensions drive code generation
 * decisions such as parameter binding strategy and serialization handling.
 */
class TypeNameExtTest {

    // -- toTypeArgument --

    /** Verifies that [toTypeArgument] extracts the first type argument from a parameterized type. */
    @Test
    fun `toTypeArgument extracts first type argument from parameterized type`() {
        val stringType = ClassName("kotlin", "String")
        val listOfString = ClassName("kotlin.collections", "List").parameterizedBy(stringType)
        val result = listOfString.toTypeArgument()
        assertEquals(stringType, result)
    }

    /** Verifies that [toTypeArgument] removes nullability from the extracted type argument. */
    @Test
    fun `toTypeArgument removes nullability from type argument`() {
        val nullableString = ClassName("kotlin", "String").copy(nullable = true)
        val listOfNullableString = ClassName("kotlin.collections", "List").parameterizedBy(nullableString)
        val result = listOfNullableString.toTypeArgument()
        assertFalse(result.isNullable)
        assertEquals(ClassName("kotlin", "String"), result)
    }

    /** Verifies that [toTypeArgument] returns the type itself when it is not parameterized. */
    @Test
    fun `toTypeArgument returns self for non-parameterized type`() {
        val stringType = ClassName("kotlin", "String")
        val result = stringType.toTypeArgument()
        assertEquals(stringType, result)
    }

    /** Verifies that [toTypeArgument] strips nullability even from non-parameterized types. */
    @Test
    fun `toTypeArgument removes nullability from non-parameterized type`() {
        val nullableString = ClassName("kotlin", "String").copy(nullable = true)
        val result = nullableString.toTypeArgument()
        assertFalse(result.isNullable)
    }

    // -- isJsonElement --

    /** Verifies that [isJsonElement] returns true for the kotlinx JsonElement type. */
    @Test
    fun `isJsonElement returns true for kotlinx serialization JsonElement`() {
        val jsonElement = ClassName("kotlinx.serialization.json", "JsonElement")
        assertTrue(jsonElement.isJsonElement)
    }

    /** Verifies that [isJsonElement] returns false for unrelated types in the same package. */
    @Test
    fun `isJsonElement returns false for other types in same package`() {
        val jsonObject = ClassName("kotlinx.serialization.json", "JsonObject")
        assertFalse(jsonObject.isJsonElement)
    }

    /** Verifies that [isJsonElement] returns false for a type named JsonElement in a different package. */
    @Test
    fun `isJsonElement returns false for JsonElement in wrong package`() {
        val wrong = ClassName("com.example", "JsonElement")
        assertFalse(wrong.isJsonElement)
    }

    /** Verifies that [isJsonElement] returns false for parameterized types. */
    @Test
    fun `isJsonElement returns false for parameterized type`() {
        val paramType = ClassName("kotlin.collections", "List")
            .parameterizedBy(ClassName("kotlin", "String"))
        assertFalse(paramType.isJsonElement)
    }

    // -- isPrimitive --

    /** Verifies that all Kotlin primitive types are recognized as primitive. */
    @Test
    fun `isPrimitive returns true for kotlin primitive types`() {
        val kotlinPrimitives = listOf("String", "Int", "Long", "Double", "Short", "Boolean", "ByteArray")
        for (name in kotlinPrimitives) {
            assertTrue("kotlin.$name should be primitive", ClassName("kotlin", name).isPrimitive)
        }
    }

    /** Verifies that the stdlib kotlin.uuid.Uuid is recognized as primitive. */
    @Test
    fun `isPrimitive returns true for kotlin uuid Uuid`() {
        assertTrue(ClassName("kotlin.uuid", "Uuid").isPrimitive)
    }

    /** Verifies that all java.lang boxed types are recognized as primitive. */
    @Test
    fun `isPrimitive returns true for java lang types`() {
        val javaTypes = listOf("String", "Integer", "Long", "Double", "Short", "Boolean")
        for (name in javaTypes) {
            assertTrue("java.lang.$name should be primitive", ClassName("java.lang", name).isPrimitive)
        }
    }

    /** Verifies that java.time date types are recognized as primitive. */
    @Test
    fun `isPrimitive returns true for java time types`() {
        val timeTypes = listOf("LocalDate", "LocalDateTime", "OffsetDateTime")
        for (name in timeTypes) {
            assertTrue("java.time.$name should be primitive", ClassName("java.time", name).isPrimitive)
        }
    }

    /** Verifies that java.util.UUID is recognized as primitive. */
    @Test
    fun `isPrimitive returns true for java util UUID`() {
        assertTrue(ClassName("java.util", "UUID").isPrimitive)
    }

    /** Verifies that bosca.serialization UUID and OffsetDateTime are recognized as primitive. */
    @Test
    fun `isPrimitive returns true for bosca serialization types`() {
        assertTrue(ClassName("bosca.serialization", "UUID").isPrimitive)
        assertTrue(ClassName("bosca.serialization", "OffsetDateTime").isPrimitive)
    }

    /** Verifies that the R2DBC Json codec type is recognized as primitive. */
    @Test
    fun `isPrimitive returns true for r2dbc Json`() {
        assertTrue(ClassName("io.r2dbc.postgresql.codec", "Json").isPrimitive)
    }

    /** Verifies that JsonElement is recognized as primitive (via isJsonElement delegation). */
    @Test
    fun `isPrimitive returns true for JsonElement`() {
        assertTrue(ClassName("kotlinx.serialization.json", "JsonElement").isPrimitive)
    }

    /** Verifies that UploadedFile is recognized as primitive via its toString representation. */
    @Test
    fun `isPrimitive returns true for UploadedFile`() {
        val uploadedFile = ClassName("bosca.graphql.scalars", "UploadedFile")
        assertTrue(uploadedFile.isPrimitive)
    }

    /** Verifies that an unrecognized type is not treated as primitive. */
    @Test
    fun `isPrimitive returns false for unknown type`() {
        assertFalse(ClassName("com.example", "MyClass").isPrimitive)
    }

    /** Verifies that a parameterized collection type is not treated as primitive. */
    @Test
    fun `isPrimitive returns false for parameterized type`() {
        val listType = ClassName("kotlin.collections", "List")
            .parameterizedBy(ClassName("kotlin", "String"))
        assertFalse(listType.isPrimitive)
    }

    /** Verifies that an unrecognized type within a known package is not primitive. */
    @Test
    fun `isPrimitive returns false for unknown type in known package`() {
        assertFalse(ClassName("kotlin", "Byte").isPrimitive)
        assertFalse(ClassName("java.lang", "Character").isPrimitive)
        assertFalse(ClassName("java.time", "Instant").isPrimitive)
        assertFalse(ClassName("java.util", "Date").isPrimitive)
    }

    // -- isCollection --

    /** Verifies that kotlin.collections List, Set, and Collection are recognized as collections. */
    @Test
    fun `isCollection returns true for kotlin collections`() {
        val stringType = ClassName("kotlin", "String")
        val collectionTypes = listOf("Collection", "List", "Set")
        for (name in collectionTypes) {
            val type = ClassName("kotlin.collections", name).parameterizedBy(stringType)
            assertTrue("kotlin.collections.$name should be collection", type.isCollection)
        }
    }

    /** Verifies that java.util List, Set, and Collection are recognized as collections. */
    @Test
    fun `isCollection returns true for java util collections`() {
        val stringType = ClassName("kotlin", "String")
        val collectionTypes = listOf("Collection", "List", "Set")
        for (name in collectionTypes) {
            val type = ClassName("java.util", name).parameterizedBy(stringType)
            assertTrue("java.util.$name should be collection", type.isCollection)
        }
    }

    /** Verifies that Map and other non-collection parameterized types are not collections. */
    @Test
    fun `isCollection returns false for non-collection parameterized types`() {
        val mapType = ClassName("kotlin.collections", "Map")
            .parameterizedBy(ClassName("kotlin", "String"), ClassName("kotlin", "Int"))
        assertFalse(mapType.isCollection)
    }

    /** Verifies that a plain ClassName (non-parameterized) is not a collection. */
    @Test
    fun `isCollection returns false for non-parameterized type`() {
        assertFalse(ClassName("kotlin.collections", "List").isCollection)
    }

    /** Verifies that a parameterized List from an unrecognized package is not a collection. */
    @Test
    fun `isCollection returns false for wrong package`() {
        val customList = ClassName("com.example", "List")
            .parameterizedBy(ClassName("kotlin", "String"))
        assertFalse(customList.isCollection)
    }

    // -- isFlow --

    /** Verifies that kotlinx.coroutines.flow.Flow is recognized as a Flow type. */
    @Test
    fun `isFlow returns true for kotlinx coroutines Flow`() {
        val flowType = ClassName("kotlinx.coroutines.flow", "Flow")
            .parameterizedBy(ClassName("kotlin", "String"))
        assertTrue(flowType.isFlow)
    }

    /** Verifies that a non-Flow parameterized type is not recognized as Flow. */
    @Test
    fun `isFlow returns false for non-Flow parameterized type`() {
        val listType = ClassName("kotlin.collections", "List")
            .parameterizedBy(ClassName("kotlin", "String"))
        assertFalse(listType.isFlow)
    }

    /** Verifies that a plain ClassName is not recognized as Flow. */
    @Test
    fun `isFlow returns false for non-parameterized type`() {
        assertFalse(ClassName("kotlinx.coroutines.flow", "Flow").isFlow)
    }

    /** Verifies that a Flow-named type from an unrecognized package is not recognized. */
    @Test
    fun `isFlow returns false for wrong package`() {
        val wrongFlow = ClassName("com.example", "Flow")
            .parameterizedBy(ClassName("kotlin", "String"))
        assertFalse(wrongFlow.isFlow)
    }

    // -- isOffsetDateTime --

    /** Verifies that java.time.OffsetDateTime is recognized. */
    @Test
    fun `isOffsetDateTime returns true for java time OffsetDateTime`() {
        assertTrue(ClassName("java.time", "OffsetDateTime").isOffsetDateTime)
    }

    /** Verifies that bosca.serialization.OffsetDateTime is recognized. */
    @Test
    fun `isOffsetDateTime returns true for bosca serialization OffsetDateTime`() {
        assertTrue(ClassName("bosca.serialization", "OffsetDateTime").isOffsetDateTime)
    }

    /** Verifies that OffsetDateTime in an unrecognized package is not matched. */
    @Test
    fun `isOffsetDateTime returns false for OffsetDateTime in wrong package`() {
        assertFalse(ClassName("com.example", "OffsetDateTime").isOffsetDateTime)
    }

    /** Verifies that other types in java.time are not matched as OffsetDateTime. */
    @Test
    fun `isOffsetDateTime returns false for other java time types`() {
        assertFalse(ClassName("java.time", "LocalDateTime").isOffsetDateTime)
    }

    /** Verifies that parameterized types are not matched as OffsetDateTime. */
    @Test
    fun `isOffsetDateTime returns false for parameterized type`() {
        val paramType = ClassName("kotlin.collections", "List")
            .parameterizedBy(ClassName("java.time", "OffsetDateTime"))
        assertFalse(paramType.isOffsetDateTime)
    }
}
