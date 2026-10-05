package bosca.ksp.generator.graphql

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the serializer generation and argument code emission logic in
 * [ControllerDispatcherGenerator]. These functions drive how every GraphQL
 * argument is deserialized in the generated dispatcher classes, so correctness
 * here is critical to the entire API surface (150+ dispatchers).
 */
class ControllerDispatcherGeneratorTest {

    // ── serializerFor: plain types ──

    @Test
    fun `serializerFor plain type produces type serializer call`() {
        val type = ClassName("com.example", "MyInput")
        val (format, args) = ControllerDispatcherGenerator.serializerFor(type)
        assertEquals("%T.serializer()", format)
        assertEquals(1, args.size)
        assertEquals(type, args[0])
    }

    @Test
    fun `serializerFor strips nullability from plain type`() {
        val type = ClassName("com.example", "MyInput").copy(nullable = true)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(type)
        assertEquals("%T.serializer()", format)
        assertEquals(ClassName("com.example", "MyInput"), args[0])
        assertFalse("Arg type should not be nullable", (args[0] as TypeName).isNullable)
    }

    @Test
    fun `serializerFor enum type produces type serializer call`() {
        val type = ClassName("com.example", "CommunityGroupType")
        val (format, args) = ControllerDispatcherGenerator.serializerFor(type)
        assertEquals("%T.serializer()", format)
        assertEquals(type, args[0])
    }

    // ── serializerFor: single-level collections ──

    @Test
    fun `serializerFor List of custom type produces ListSerializer`() {
        val element = ClassName("com.example", "Item")
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(element)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(listType)
        assertEquals("ListSerializer(%T.serializer())", format)
        assertEquals(1, args.size)
        assertEquals(element, args[0])
    }

    @Test
    fun `serializerFor List of String produces ListSerializer with String`() {
        val stringType = ClassName("kotlin", "String")
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(stringType)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(listType)
        assertEquals("ListSerializer(%T.serializer())", format)
        assertEquals(stringType, args[0])
    }

    @Test
    fun `serializerFor List of Int produces ListSerializer with Int`() {
        val intType = ClassName("kotlin", "Int")
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(intType)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(listType)
        assertEquals("ListSerializer(%T.serializer())", format)
        assertEquals(intType, args[0])
    }

    @Test
    fun `serializerFor List of Long produces ListSerializer with Long`() {
        val longType = ClassName("kotlin", "Long")
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(longType)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(listType)
        assertEquals("ListSerializer(%T.serializer())", format)
        assertEquals(longType, args[0])
    }

    @Test
    fun `serializerFor List of UUID produces ListSerializer with UUID`() {
        val uuidType = ClassName("bosca.serialization", "UUID")
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(uuidType)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(listType)
        assertEquals("ListSerializer(%T.serializer())", format)
        assertEquals(uuidType, args[0])
    }

    @Test
    fun `serializerFor Set collection type produces ListSerializer`() {
        val element = ClassName("com.example", "Item")
        val setType = ClassName("kotlin.collections", "Set").parameterizedBy(element)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(setType)
        assertEquals("ListSerializer(%T.serializer())", format)
        assertEquals(element, args[0])
    }

    @Test
    fun `serializerFor java util List produces ListSerializer`() {
        val element = ClassName("kotlin", "String")
        val javaList = ClassName("java.util", "List").parameterizedBy(element)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(javaList)
        assertEquals("ListSerializer(%T.serializer())", format)
        assertEquals(element, args[0])
    }

    @Test
    fun `serializerFor java util Collection produces ListSerializer`() {
        val element = ClassName("kotlin", "String")
        val javaCollection = ClassName("java.util", "Collection").parameterizedBy(element)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(javaCollection)
        assertEquals("ListSerializer(%T.serializer())", format)
        assertEquals(element, args[0])
    }

    @Test
    fun `serializerFor List strips nullable element type`() {
        val nullableString = ClassName("kotlin", "String").copy(nullable = true)
        val listType = ClassName("kotlin.collections", "List").parameterizedBy(nullableString)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(listType)
        assertEquals("ListSerializer(%T.serializer())", format)
        assertFalse("Element type should not be nullable", (args[0] as TypeName).isNullable)
    }

    // ── serializerFor: nested collections ──

    @Test
    fun `serializerFor List of List of String produces nested ListSerializer`() {
        val stringType = ClassName("kotlin", "String")
        val listOfString = ClassName("kotlin.collections", "List").parameterizedBy(stringType)
        val listOfListOfString = ClassName("kotlin.collections", "List").parameterizedBy(listOfString)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(listOfListOfString)
        assertEquals("ListSerializer(ListSerializer(%T.serializer()))", format)
        assertEquals(1, args.size)
        assertEquals(stringType, args[0])
    }

    @Test
    fun `serializerFor List of List of custom type produces nested ListSerializer`() {
        val itemType = ClassName("com.example", "Item")
        val innerList = ClassName("kotlin.collections", "List").parameterizedBy(itemType)
        val outerList = ClassName("kotlin.collections", "List").parameterizedBy(innerList)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(outerList)
        assertEquals("ListSerializer(ListSerializer(%T.serializer()))", format)
        assertEquals(1, args.size)
        assertEquals(itemType, args[0])
    }

    @Test
    fun `serializerFor three levels of nesting produces correct result`() {
        val intType = ClassName("kotlin", "Int")
        val innerList = ClassName("kotlin.collections", "List").parameterizedBy(intType)
        val middleList = ClassName("kotlin.collections", "List").parameterizedBy(innerList)
        val outerList = ClassName("kotlin.collections", "List").parameterizedBy(middleList)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(outerList)
        assertEquals("ListSerializer(ListSerializer(ListSerializer(%T.serializer())))", format)
        assertEquals(1, args.size)
        assertEquals(intType, args[0])
    }

    // ── serializerFor: OffsetDateTime ──

    @Test
    fun `serializerFor java time OffsetDateTime produces special serializer`() {
        val type = ClassName("java.time", "OffsetDateTime")
        val (format, args) = ControllerDispatcherGenerator.serializerFor(type)
        assertEquals("OffsetDateTimeSerializer()", format)
        assertTrue(args.isEmpty())
    }

    @Test
    fun `serializerFor bosca OffsetDateTime produces special serializer`() {
        val type = ClassName("bosca.serialization", "OffsetDateTime")
        val (format, args) = ControllerDispatcherGenerator.serializerFor(type)
        assertEquals("OffsetDateTimeSerializer()", format)
        assertTrue(args.isEmpty())
    }

    @Test
    fun `serializerFor List of OffsetDateTime produces ListSerializer with special inner`() {
        val odt = ClassName("java.time", "OffsetDateTime")
        val listOfOdt = ClassName("kotlin.collections", "List").parameterizedBy(odt)
        val (format, args) = ControllerDispatcherGenerator.serializerFor(listOfOdt)
        assertEquals("ListSerializer(OffsetDateTimeSerializer())", format)
        assertTrue("OffsetDateTime serializer needs no type args", args.isEmpty())
    }

    // ── appendArgument: JsonElement branch ──

    @Test
    fun `appendArgument for JsonElement produces anyToJsonElement pattern`() {
        val type = ClassName("kotlinx.serialization.json", "JsonElement")
        val (code, args) = appendArgument(type, "data")
        assertTrue("Should get argument", code.contains("environment.getArgument"))
        assertTrue("Should call anyToJsonElement", code.contains("anyToJsonElement"))
        assertFalse("Should not use decodeFromJsonElement", code.contains("decodeFromJsonElement"))
        assertEquals(4, args.size)
        assertTrue("All args should be parameter name", args.all { it == "data" })
        verifyPlaceholderAlignment(code, args)
    }

    // ── appendArgument: primitive branch ──

    @Test
    fun `appendArgument for primitive String produces getArgument call`() {
        val type = ClassName("kotlin", "String")
        val (code, args) = appendArgument(type, "name")
        assertTrue("Should use getArgument", code.contains("environment.getArgument"))
        assertFalse("Should not use decodeFromJsonElement", code.contains("decodeFromJsonElement"))
        assertEquals(3, args.size)
        assertEquals("name", args[0])
        assertEquals(type, args[1])
        assertEquals("name", args[2])
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for primitive Int produces getArgument call`() {
        val type = ClassName("kotlin", "Int")
        val (code, args) = appendArgument(type, "ordinal")
        assertTrue(code.contains("environment.getArgument"))
        assertEquals(3, args.size)
        assertEquals(type, args[1])
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for primitive Long produces getArgument call`() {
        val type = ClassName("kotlin", "Long")
        val (code, args) = appendArgument(type, "stepId")
        assertEquals(3, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for primitive Double produces getArgument call`() {
        val type = ClassName("kotlin", "Double")
        val (code, args) = appendArgument(type, "value")
        assertEquals(3, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for primitive Boolean produces getArgument call`() {
        val type = ClassName("kotlin", "Boolean")
        val (code, args) = appendArgument(type, "active")
        assertEquals(3, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for primitive UUID produces getArgument call`() {
        val type = ClassName("bosca.serialization", "UUID")
        val (code, args) = appendArgument(type, "id")
        assertEquals(3, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for primitive java util UUID produces getArgument call`() {
        val type = ClassName("java.util", "UUID")
        val (code, args) = appendArgument(type, "id")
        assertEquals(3, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for OffsetDateTime is primitive path`() {
        val type = ClassName("java.time", "OffsetDateTime")
        val (code, args) = appendArgument(type, "timestamp")
        assertTrue("Should use getArgument (primitive path)", code.contains("environment.getArgument"))
        assertFalse("Should not use decodeFromJsonElement", code.contains("decodeFromJsonElement"))
        assertEquals(3, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for UploadedFile is primitive path`() {
        val type = ClassName("bosca.graphql.scalars", "UploadedFile")
        val (code, args) = appendArgument(type, "file")
        assertEquals(3, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for nullable primitive strips nullability from type arg`() {
        val type = ClassName("kotlin", "String").copy(nullable = true)
        val (code, args) = appendArgument(type, "name")
        assertEquals(3, args.size)
        assertFalse("Type arg should not be nullable", (args[1] as TypeName).isNullable)
        verifyPlaceholderAlignment(code, args)
    }

    // ── appendArgument: non-nullable custom type (complex branch) ──

    @Test
    fun `appendArgument for non-nullable custom type has correct code structure`() {
        val type = ClassName("com.example", "MyInput")
        val (code, args) = appendArgument(type, "settings")
        assertTrue("Should contain getArgument", code.contains("getArgument"))
        assertTrue("Should contain anyToJsonElement", code.contains("anyToJsonElement"))
        assertTrue("Should contain decodeFromJsonElement", code.contains("decodeFromJsonElement"))
        assertTrue("Should contain serializer()", code.contains(".serializer()"))
        assertFalse("Should NOT contain JsonNull for non-nullable", code.contains("JsonNull"))
        assertEquals(7, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for non-nullable custom type has correct arg types`() {
        val type = ClassName("com.example", "MyInput")
        val (_, args) = appendArgument(type, "settings")
        // First 5 and last are parameter names
        assertEquals("settings", args[0])
        assertEquals("settings", args[1])
        assertEquals("settings", args[2])
        assertEquals("settings", args[3])
        assertEquals("settings", args[4])
        // 6th is the type for %T
        assertEquals(type, args[5])
        // 7th is the final parameter name
        assertEquals("settings", args[6])
    }

    // ── appendArgument: nullable custom type (complex branch) ──

    @Test
    fun `appendArgument for nullable custom type has correct code structure`() {
        val type = ClassName("com.example", "MyInput").copy(nullable = true)
        val (code, args) = appendArgument(type, "settings")
        assertTrue("Should contain JsonNull check", code.contains("JsonNull"))
        assertTrue("Should contain decodeFromJsonElement", code.contains("decodeFromJsonElement"))
        assertEquals(8, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for nullable custom type has correct arg types`() {
        val type = ClassName("com.example", "MyInput").copy(nullable = true)
        val (_, args) = appendArgument(type, "settings")
        // First 5 are parameter names (map, S, jsonElement, map, val=)
        for (i in 0..4) assertEquals("Arg $i should be paramName", "settings", args[i])
        // 6th is extra nullable check paramName
        assertEquals("settings", args[5])
        // 7th is the type for %T (non-nullable)
        assertEquals(ClassName("com.example", "MyInput"), args[6])
        assertFalse("Type arg should not be nullable", (args[6] as TypeName).isNullable)
        // 8th is final paramName
        assertEquals("settings", args[7])
    }

    // ── appendArgument: non-nullable List (complex branch) ──

    @Test
    fun `appendArgument for non-nullable List of custom type has correct structure`() {
        val element = ClassName("com.example", "Item")
        val type = ClassName("kotlin.collections", "List").parameterizedBy(element)
        val (code, args) = appendArgument(type, "items")
        assertTrue("Should contain ListSerializer", code.contains("ListSerializer"))
        assertTrue("Should contain decodeFromJsonElement", code.contains("decodeFromJsonElement"))
        assertFalse("Should NOT contain JsonNull", code.contains("JsonNull"))
        assertEquals(7, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for non-nullable List has element type in args`() {
        val element = ClassName("com.example", "Item")
        val type = ClassName("kotlin.collections", "List").parameterizedBy(element)
        val (_, args) = appendArgument(type, "items")
        // Args 0-4 are paramNames, 5 is element type, 6 is final paramName
        assertEquals(element, args[5])
    }

    @Test
    fun `appendArgument for non-nullable List of String`() {
        val stringType = ClassName("kotlin", "String")
        val type = ClassName("kotlin.collections", "List").parameterizedBy(stringType)
        val (code, args) = appendArgument(type, "names")
        assertTrue(code.contains("ListSerializer"))
        assertEquals(7, args.size)
        assertEquals(stringType, args[5])
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for non-nullable List of Int`() {
        val intType = ClassName("kotlin", "Int")
        val type = ClassName("kotlin.collections", "List").parameterizedBy(intType)
        val (code, args) = appendArgument(type, "ids")
        assertEquals(7, args.size)
        assertEquals(intType, args[5])
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for non-nullable List of enum type`() {
        val enumType = ClassName("com.example", "PermissionAction")
        val type = ClassName("kotlin.collections", "List").parameterizedBy(enumType)
        val (code, args) = appendArgument(type, "actions")
        assertEquals(7, args.size)
        assertEquals(enumType, args[5])
        verifyPlaceholderAlignment(code, args)
    }

    // ── appendArgument: nullable List (complex branch) ──

    @Test
    fun `appendArgument for nullable List has correct structure`() {
        val element = ClassName("com.example", "Item")
        val type = ClassName("kotlin.collections", "List").parameterizedBy(element).copy(nullable = true)
        val (code, args) = appendArgument(type, "items")
        assertTrue("Should contain JsonNull check", code.contains("JsonNull"))
        assertTrue("Should contain ListSerializer", code.contains("ListSerializer"))
        assertEquals(8, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for nullable List of String`() {
        val stringType = ClassName("kotlin", "String")
        val type = ClassName("kotlin.collections", "List").parameterizedBy(stringType).copy(nullable = true)
        val (code, args) = appendArgument(type, "tags")
        assertEquals(8, args.size)
        // 7th arg (index 6) is element type
        assertEquals(stringType, args[6])
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for nullable List of Int`() {
        val intType = ClassName("kotlin", "Int")
        val type = ClassName("kotlin.collections", "List").parameterizedBy(intType).copy(nullable = true)
        val (code, args) = appendArgument(type, "uids")
        assertEquals(8, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    // ── appendArgument: nested List (the bug this fix addresses) ──

    @Test
    fun `appendArgument for non-nullable nested List of List of String`() {
        val stringType = ClassName("kotlin", "String")
        val innerList = ClassName("kotlin.collections", "List").parameterizedBy(stringType)
        val type = ClassName("kotlin.collections", "List").parameterizedBy(innerList)
        val (code, args) = appendArgument(type, "pairs")
        assertTrue("Should contain nested ListSerializer", code.contains("ListSerializer(ListSerializer("))
        assertFalse("Should NOT contain JsonNull", code.contains("JsonNull"))
        // Nested list: only the innermost element type appears as a %T arg
        assertEquals(7, args.size)
        assertEquals(stringType, args[5])
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for nullable nested List of List of String`() {
        val stringType = ClassName("kotlin", "String")
        val innerList = ClassName("kotlin.collections", "List").parameterizedBy(stringType)
        val type = ClassName("kotlin.collections", "List").parameterizedBy(innerList).copy(nullable = true)
        val (code, args) = appendArgument(type, "pairs")
        assertTrue("Should contain nested ListSerializer", code.contains("ListSerializer(ListSerializer("))
        assertTrue("Should contain JsonNull check", code.contains("JsonNull"))
        assertEquals(8, args.size)
        assertEquals(stringType, args[6])
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for List of List of custom type`() {
        val itemType = ClassName("com.example", "Item")
        val innerList = ClassName("kotlin.collections", "List").parameterizedBy(itemType)
        val type = ClassName("kotlin.collections", "List").parameterizedBy(innerList)
        val (code, args) = appendArgument(type, "groups")
        assertTrue(code.contains("ListSerializer(ListSerializer("))
        assertEquals(7, args.size)
        assertEquals(itemType, args[5])
        verifyPlaceholderAlignment(code, args)
    }

    // ── appendArgument: List of OffsetDateTime (special inner serializer) ──

    @Test
    fun `appendArgument for non-nullable List of OffsetDateTime`() {
        val odt = ClassName("java.time", "OffsetDateTime")
        val type = ClassName("kotlin.collections", "List").parameterizedBy(odt)
        val (code, args) = appendArgument(type, "timestamps")
        assertTrue("Should contain ListSerializer", code.contains("ListSerializer"))
        assertTrue("Should contain OffsetDateTimeSerializer", code.contains("OffsetDateTimeSerializer"))
        assertFalse("Should NOT contain %T.serializer() for OffsetDateTime", code.contains("%T.serializer()"))
        // OffsetDateTime serializer has no type args, so args = paramName x5 + paramName (final) = 6
        assertEquals(6, args.size)
        assertTrue("All args should be paramName", args.all { it == "timestamps" })
        verifyPlaceholderAlignment(code, args)
    }

    @Test
    fun `appendArgument for nullable List of OffsetDateTime`() {
        val odt = ClassName("java.time", "OffsetDateTime")
        val type = ClassName("kotlin.collections", "List").parameterizedBy(odt).copy(nullable = true)
        val (code, args) = appendArgument(type, "timestamps")
        assertTrue(code.contains("JsonNull"))
        assertTrue(code.contains("OffsetDateTimeSerializer"))
        // Nullable: paramName x5 + nullable paramName + paramName (final) = 7
        assertEquals(7, args.size)
        verifyPlaceholderAlignment(code, args)
    }

    // ── appendArgument: code content verification ──

    @Test
    fun `appendArgument code uses L placeholders for variable names`() {
        val type = ClassName("com.example", "MyInput")
        val (code, _) = appendArgument(type, "mySettings")
        assertTrue("Map variable uses %L placeholder", code.contains("%LMap"))
        assertTrue("JsonElement variable uses %L placeholder", code.contains("%LjsonElement"))
    }

    @Test
    fun `appendArgument for complex type always starts with getArgument and anyToJsonElement`() {
        val type = ClassName("com.example", "MyInput")
        val (code, _) = appendArgument(type, "input")
        val lines = code.lines().filter { it.isNotBlank() }
        assertTrue("First line should get argument", lines[0].contains("getArgument"))
        assertTrue("Second line should convert to json element", lines[1].contains("anyToJsonElement"))
        assertTrue("Third line should decode", lines[2].contains("decodeFromJsonElement"))
    }

    // ── Helpers ──

    /**
     * Convenience wrapper that creates the StringBuilder and args list, calls
     * appendArgument, and returns the resulting code and args.
     */
    private fun appendArgument(type: TypeName, parameterName: String): Pair<String, List<Any>> {
        val sb = StringBuilder()
        val args = mutableListOf<Any>()
        ControllerDispatcherGenerator.appendArgument(sb, type, parameterName, args)
        return sb.toString() to args
    }

    /**
     * Counts KotlinPoet placeholders (%L, %T, %S) in the format string and verifies
     * the total matches the number of args provided. This catches misalignment that
     * would cause runtime [IllegalArgumentException] from KotlinPoet.
     */
    private fun verifyPlaceholderAlignment(format: String, args: List<Any>) {
        val placeholderCount = Regex("%[LTS]").findAll(format).count()
        assertEquals(
            "Placeholder count ($placeholderCount) must match args count (${args.size}) in:\n$format",
            placeholderCount,
            args.size
        )
    }
}
