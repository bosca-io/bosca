package bosca.docs.index

/**
 * Parses Kotlin source files into structured [SourceDocument] entries
 * for AI-optimized documentation indexing.
 *
 * Uses text-based parsing (no compiler dependency) focused on the patterns
 * used in the Bosca framework: services, repositories, controllers, models, jobs, and routes.
 */
class KotlinSourceParser {

    fun parse(filePath: String, source: String): List<SourceDocument> {
        return parseAll(filePath, source).filter { doc ->
            doc.category in INCLUDED_CATEGORIES || doc.simpleName in INCLUDED_TYPES
        }
    }

    fun parseAll(filePath: String, source: String): List<SourceDocument> {
        val pkg = extractPackage(source)
        val module = deriveModule(filePath)
        val imports = extractImports(source)
        val typeResolver = TypeResolver(pkg, imports)
        val declarations = extractDeclarations(source)
        return declarations.map { decl ->
            val category = detectCategory(decl)
            val qualifiedName = if (pkg.isNotEmpty()) "$pkg.${decl.name}" else decl.name
            val accessPattern = if (category == "service" && decl.kind == "interface") {
                "provide<${decl.name}>()"
            } else ""
            val resolvedDecl = resolveTypes(decl, typeResolver)
            SourceDocument(
                qualifiedName = qualifiedName,
                simpleName = resolvedDecl.name,
                kind = resolvedDecl.kind,
                category = category,
                module = module,
                pkg = pkg,
                filePath = filePath,
                kdoc = resolvedDecl.kdoc,
                annotations = resolvedDecl.annotations,
                supertypes = resolvedDecl.supertypes,
                methods = resolvedDecl.methods,
                properties = resolvedDecl.properties,
                enumValues = resolvedDecl.enumValues,
                accessPattern = accessPattern,
            )
        }
    }

    fun resolveTransitiveDeps(allDocs: List<SourceDocument>): List<SourceDocument> {
        val docsByQualifiedName = allDocs.associateBy { it.qualifiedName }

        // Seed with directly included types
        val included = mutableSetOf<String>()
        for (doc in allDocs) {
            if (doc.category in INCLUDED_CATEGORIES || doc.simpleName in INCLUDED_TYPES) {
                included.add(doc.qualifiedName)
            }
        }

        // Iteratively resolve transitive deps
        var changed = true
        while (changed) {
            changed = false
            val referencedTypes = mutableSetOf<String>()
            for (name in included) {
                val doc = docsByQualifiedName[name] ?: continue
                collectReferencedTypes(doc, referencedTypes)
            }
            for (ref in referencedTypes) {
                if (ref !in included && ref in docsByQualifiedName) {
                    included.add(ref)
                    changed = true
                }
            }
        }

        return allDocs.filter { it.qualifiedName in included }
    }

    private fun collectReferencedTypes(doc: SourceDocument, out: MutableSet<String>) {
        for (supertype in doc.supertypes) {
            extractBoscaTypes(supertype, out)
        }
        for (method in doc.methods) {
            extractBoscaTypes(method.returnType, out)
            for (param in method.parameters) {
                extractBoscaTypes(param.type, out)
            }
        }
        for (prop in doc.properties) {
            extractBoscaTypes(prop.type, out)
        }
    }

    private fun extractBoscaTypes(typeStr: String, out: MutableSet<String>) {
        if (typeStr.isBlank()) return
        // Strip nullability and extract all qualified bosca type references
        val cleaned = typeStr.replace("?", "")
        BOSCA_TYPE_PATTERN.findAll(cleaned).forEach { match ->
            out.add(match.value)
        }
    }

    private fun extractPackage(source: String): String {
        val match = PACKAGE_PATTERN.find(source)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun extractImports(source: String): Map<String, String> {
        val imports = mutableMapOf<String, String>()
        IMPORT_PATTERN.findAll(source).forEach { match ->
            val fqn = match.groupValues[1].trim()
            if (!fqn.endsWith(".*")) {
                val simpleName = fqn.substringAfterLast('.')
                imports[simpleName] = fqn
            }
        }
        return imports
    }

    private fun resolveTypes(decl: ParsedDeclaration, resolver: TypeResolver): ParsedDeclaration {
        return decl.copy(
            supertypes = decl.supertypes.map { resolver.resolve(it) },
            methods = decl.methods.map { method ->
                val resolvedParams = method.parameters.map { p ->
                    p.copy(type = resolver.resolve(p.type))
                }
                val resolvedReturnType = resolver.resolve(method.returnType)
                method.copy(
                    returnType = resolvedReturnType,
                    parameters = resolvedParams,
                    signature = buildSignatureString(method.isSuspend, method.name, resolvedParams, resolvedReturnType),
                )
            },
            properties = decl.properties.map { p ->
                p.copy(type = resolver.resolve(p.type))
            },
        )
    }

    private class TypeResolver(
        private val pkg: String,
        private val imports: Map<String, String>,
    ) {
        fun resolve(type: String): String {
            if (type.isEmpty()) return type
            val trimmed = type.trim()

            // Handle nullable
            if (trimmed.endsWith("?")) {
                return resolve(trimmed.removeSuffix("?")) + "?"
            }

            // Handle generic types: List<Foo> -> List<resolved.Foo>
            val genericMatch = Regex("^(\\w+)<(.+)>$").find(trimmed)
            if (genericMatch != null) {
                val outer = resolveSingle(genericMatch.groupValues[1])
                val innerTypes = splitTypeArgs(genericMatch.groupValues[2])
                val resolvedInner = innerTypes.joinToString(", ") { resolve(it.trim()) }
                return "$outer<$resolvedInner>"
            }

            // Handle function types: (A, B) -> C
            val funTypeMatch = Regex("^\\((.*)\\)\\s*->\\s*(.+)$").find(trimmed)
            if (funTypeMatch != null) {
                val paramTypes = splitTypeArgs(funTypeMatch.groupValues[1])
                val resolvedParams = paramTypes.joinToString(", ") { resolve(it.trim()) }
                val resolvedReturn = resolve(funTypeMatch.groupValues[2].trim())
                return "($resolvedParams) -> $resolvedReturn"
            }

            return resolveSingle(trimmed)
        }

        private fun resolveSingle(name: String): String {
            // Already fully qualified
            if (name.contains('.')) return name
            // Kotlin built-in types stay as-is
            if (name in BUILTIN_TYPES) return name
            // Check imports
            imports[name]?.let { return it }
            // Same package
            if (pkg.isNotEmpty()) return "$pkg.$name"
            return name
        }

        private fun splitTypeArgs(args: String): List<String> {
            val result = mutableListOf<String>()
            var depth = 0
            val current = StringBuilder()
            for (ch in args) {
                when {
                    ch == '<' || ch == '(' -> { depth++; current.append(ch) }
                    ch == '>' || ch == ')' -> { depth--; current.append(ch) }
                    ch == ',' && depth == 0 -> { result.add(current.toString()); current.clear() }
                    else -> current.append(ch)
                }
            }
            if (current.isNotEmpty()) result.add(current.toString())
            return result
        }

        companion object {
            private val BUILTIN_TYPES = setOf(
                "String", "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char",
                "Unit", "Nothing", "Any", "Number",
                "List", "Set", "Map", "Collection", "Iterable", "Sequence", "Array",
                "MutableList", "MutableSet", "MutableMap", "MutableCollection",
                "Pair", "Triple",
                "Comparable", "Enum", "Annotation",
                "Lazy", "Result",
                "UInt", "ULong", "UShort", "UByte",
                "IntArray", "LongArray", "ShortArray", "ByteArray",
                "FloatArray", "DoubleArray", "BooleanArray", "CharArray",
            )
        }
    }

    private fun deriveModule(filePath: String): String {
        val normalized = filePath.replace("\\", "/")
        // Pattern: backend/framework/{module}/src/main/kotlin/...
        val frameworkMatch = Regex("backend/framework/([^/]+)/").find(normalized)
        if (frameworkMatch != null) return frameworkMatch.groupValues[1]
        // Pattern: backend/kit/src/main/kotlin/...
        if (normalized.contains("backend/kit/")) return "kit"
        // Fallback: first path component
        return normalized.split("/").firstOrNull() ?: "unknown"
    }

    private fun extractDeclarations(source: String): List<ParsedDeclaration> {
        val results = mutableListOf<ParsedDeclaration>()
        val lines = source.lines()
        var i = 0
        while (i < lines.size) {
            // Collect KDoc if present
            val kdocResult = collectKDoc(lines, i)
            val kdoc = kdocResult.first
            i = kdocResult.second

            // Collect annotations
            val annotationsResult = collectAnnotations(lines, i)
            val annotations = annotationsResult.first
            i = annotationsResult.second

            if (i >= lines.size) break
            val line = lines[i].trim()

            // Try to match a declaration
            val declMatch = DECLARATION_PATTERN.find(line)
            if (declMatch != null) {
                val visibility = declMatch.groupValues[1]
                if (visibility == "private" || visibility == "internal") {
                    // Skip private/internal declarations - find end of block and continue
                    i = skipBlock(lines, i) + 1
                    continue
                }
                val modifiers = declMatch.groupValues[2].trim()
                val keyword = declMatch.groupValues[3]
                val name = declMatch.groupValues[4]

                val kind = when {
                    keyword == "enum" || modifiers.contains("enum") -> "enum"
                    keyword == "data" || modifiers.contains("data") -> "data-class"
                    keyword == "interface" -> "interface"
                    keyword == "object" -> "object"
                    keyword == "class" || keyword == "abstract" -> if (modifiers.contains("abstract") || keyword == "abstract") "abstract-class" else "class"
                    else -> "class"
                }

                // Extract supertypes from the declaration line and continuation lines
                val fullDeclLine = collectFullDeclarationLine(lines, i)
                val supertypes = extractSupertypes(fullDeclLine)

                // Extract constructor parameters for data classes
                val constructorProps = if (kind == "data-class" || kind == "class") {
                    extractConstructorProperties(fullDeclLine)
                } else emptyList()

                // Find the body of this declaration
                val bodyEnd = skipBlock(lines, i)
                val bodyLines = if (bodyEnd > i) lines.subList(i, bodyEnd + 1) else listOf(lines[i])

                // Extract methods and properties from the body
                val methods = extractMethods(bodyLines)
                val properties = if (kind != "data-class") {
                    extractProperties(bodyLines)
                } else emptyList()

                // Extract enum values
                val enumValues = if (kind == "enum") extractEnumValues(bodyLines) else emptyList()

                results.add(
                    ParsedDeclaration(
                        name = name,
                        kind = kind,
                        kdoc = kdoc,
                        annotations = annotations,
                        supertypes = supertypes,
                        methods = methods,
                        properties = constructorProps + properties,
                        enumValues = enumValues,
                    )
                )
                i = bodyEnd + 1
            } else {
                i++
            }
        }
        return results
    }

    private fun collectKDoc(lines: List<String>, startIndex: Int): Pair<String, Int> {
        var i = startIndex
        // Skip blank lines
        while (i < lines.size && lines[i].isBlank()) i++
        if (i >= lines.size) return "" to i

        val trimmed = lines[i].trim()
        if (!trimmed.startsWith("/**")) return "" to i

        val kdocLines = mutableListOf<String>()
        if (trimmed.contains("*/") && trimmed != "/**") {
            // Single-line KDoc: /** ... */
            kdocLines.add(trimmed.removePrefix("/**").removeSuffix("*/").trim())
            return kdocLines.joinToString("\n") to (i + 1)
        }

        // Multi-line KDoc
        kdocLines.add(trimmed.removePrefix("/**").trim())
        i++
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("*/")) {
                kdocLines.add(line.removeSuffix("*/").removePrefix("*").trim())
                i++
                break
            }
            kdocLines.add(line.removePrefix("*").trim())
            i++
        }
        return kdocLines.filter { it.isNotEmpty() }.joinToString("\n") to i
    }

    private fun collectAnnotations(lines: List<String>, startIndex: Int): Pair<List<AnnotationInfo>, Int> {
        var i = startIndex
        val annotations = mutableListOf<AnnotationInfo>()

        while (i < lines.size) {
            val trimmed = lines[i].trim()
            // Skip blank lines between annotations
            if (trimmed.isEmpty()) {
                i++
                continue
            }
            // Skip @file: annotations
            if (trimmed.startsWith("@file:")) {
                i++
                continue
            }
            if (!trimmed.startsWith("@")) break

            // Could be multiple annotations on one line
            val annotationMatches = ANNOTATION_PATTERN.findAll(trimmed)
            for (match in annotationMatches) {
                val name = match.groupValues[1]
                val argsStr = match.groupValues[2]
                val args = if (argsStr.isNotEmpty()) parseAnnotationArgs(argsStr) else emptyMap()
                annotations.add(AnnotationInfo(name = name, arguments = args))
            }
            i++
        }
        return annotations to i
    }

    private fun parseAnnotationArgs(argsStr: String): Map<String, String> {
        val args = mutableMapOf<String, String>()
        // Handle simple cases: @Ann("value") or @Ann(key = "value", key2 = value)
        val inner = argsStr.removeSurrounding("(", ")")
        if (inner.isBlank()) return args

        // If no '=' it's a positional argument
        if (!inner.contains('=')) {
            args["value"] = inner.trim().removeSurrounding("\"")
            return args
        }

        // Split on commas that aren't inside strings or parens
        var depth = 0
        var inString = false
        var escape = false
        val parts = mutableListOf<String>()
        val current = StringBuilder()

        for (ch in inner) {
            when {
                escape -> {
                    current.append(ch)
                    escape = false
                }
                ch == '\\' -> {
                    current.append(ch)
                    escape = true
                }
                ch == '"' -> {
                    current.append(ch)
                    inString = !inString
                }
                !inString && ch == '(' -> {
                    current.append(ch)
                    depth++
                }
                !inString && ch == ')' -> {
                    current.append(ch)
                    depth--
                }
                !inString && depth == 0 && ch == ',' -> {
                    parts.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString().trim())

        for (part in parts) {
            val eqIndex = part.indexOf('=')
            if (eqIndex > 0) {
                val key = part.substring(0, eqIndex).trim()
                val value = part.substring(eqIndex + 1).trim().removeSurrounding("\"")
                args[key] = value
            } else {
                args["value"] = part.trim().removeSurrounding("\"")
            }
        }
        return args
    }

    private fun collectFullDeclarationLine(lines: List<String>, startIndex: Int): String {
        val sb = StringBuilder()
        var i = startIndex
        var depth = 0
        var foundOpenBrace = false
        while (i < lines.size) {
            val line = lines[i]
            sb.append(line.trim()).append(" ")
            for (ch in line) {
                when (ch) {
                    '(' -> depth++
                    ')' -> depth--
                    '{' -> foundOpenBrace = true
                }
            }
            if (foundOpenBrace || (depth <= 0 && i > startIndex)) break
            i++
        }
        return sb.toString().trim()
    }

    private fun extractSupertypes(declLine: String): List<String> {
        // Look for ": SuperType, OtherType" after class/interface name
        val colonMatch = Regex("(?:class|interface|object)\\s+\\w+(?:<[^>]*>)?(?:\\([^)]*\\))?\\s*:\\s*(.+?)\\s*\\{").find(declLine)
            ?: Regex("(?:class|interface|object)\\s+\\w+(?:<[^>]*>)?(?:\\([^)]*\\))?\\s*:\\s*(.+)").find(declLine)
            ?: return emptyList()

        val supertypeStr = colonMatch.groupValues[1].trim().removeSuffix("{").trim()
        return splitTypeList(supertypeStr).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun splitTypeList(types: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (ch in types) {
            when {
                ch == '<' || ch == '(' -> {
                    depth++
                    current.append(ch)
                }
                ch == '>' || ch == ')' -> {
                    depth--
                    current.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    result.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString().trim())
        return result
    }

    private fun extractConstructorProperties(declLine: String): List<PropertyInfo> {
        // Extract parameters from primary constructor: class Foo(val x: Int, var y: String = "")
        val ctorMatch = Regex("(?:class|data class)\\s+\\w+(?:<[^>]*>)?\\s*\\((.+?)\\)").find(declLine)
            ?: return emptyList()
        val paramsStr = ctorMatch.groupValues[1]
        return parsePropertyList(paramsStr)
    }

    private fun parsePropertyList(paramsStr: String): List<PropertyInfo> {
        val properties = mutableListOf<PropertyInfo>()
        val params = splitParameters(paramsStr)
        for (param in params) {
            val trimmed = param.trim()
            if (trimmed.isEmpty()) continue

            // Strip annotations from the parameter
            val withoutAnnotations = trimmed.replace(Regex("@\\w+(?:\\([^)]*\\))?\\s*"), "").trim()

            // Skip private/internal constructor properties
            if (withoutAnnotations.startsWith("private ") || withoutAnnotations.startsWith("internal ")) continue

            val isMutable = withoutAnnotations.startsWith("var ")
            val isVal = withoutAnnotations.startsWith("val ")
            val cleaned = withoutAnnotations.removePrefix("val ").removePrefix("var ").removePrefix("override val ").removePrefix("override var ").trim()

            val colonIndex = cleaned.indexOf(':')
            if (colonIndex <= 0) continue
            val name = cleaned.substring(0, colonIndex).trim()
            val rest = cleaned.substring(colonIndex + 1).trim()

            // Split type from default value
            val eqIndex = findDefaultValueSeparator(rest)
            val type = if (eqIndex >= 0) rest.substring(0, eqIndex).trim() else rest
            val default = if (eqIndex >= 0) rest.substring(eqIndex + 1).trim() else null

            // Collect annotations from original param
            val annotations = ANNOTATION_PATTERN.findAll(trimmed).map { m ->
                AnnotationInfo(name = m.groupValues[1], arguments = if (m.groupValues[2].isNotEmpty()) parseAnnotationArgs(m.groupValues[2]) else emptyMap())
            }.toList()

            properties.add(
                PropertyInfo(
                    name = name,
                    type = type,
                    isMutable = isMutable,
                    annotations = annotations,
                    defaultValue = default,
                )
            )
        }
        return properties
    }

    private fun findDefaultValueSeparator(str: String): Int {
        var depth = 0
        for (i in str.indices) {
            when (str[i]) {
                '<', '(' -> depth++
                '>', ')' -> depth--
                '=' -> if (depth == 0) return i
            }
        }
        return -1
    }

    private fun splitParameters(params: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var inString = false
        val current = StringBuilder()
        for (ch in params) {
            when {
                ch == '"' -> {
                    inString = !inString
                    current.append(ch)
                }
                !inString && (ch == '<' || ch == '(') -> {
                    depth++
                    current.append(ch)
                }
                !inString && (ch == '>' || ch == ')') -> {
                    depth--
                    current.append(ch)
                }
                !inString && ch == ',' && depth == 0 -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    private fun extractMethods(bodyLines: List<String>): List<MethodInfo> {
        val methods = mutableListOf<MethodInfo>()
        var i = 0
        while (i < bodyLines.size) {
            // Collect KDoc
            val kdocResult = collectKDoc(bodyLines, i)
            val kdoc = kdocResult.first
            i = kdocResult.second

            // Collect annotations
            val annotationsResult = collectAnnotations(bodyLines, i)
            val annotations = annotationsResult.first
            i = annotationsResult.second

            if (i >= bodyLines.size) break
            val line = bodyLines[i].trim()

            val funMatch = FUN_PATTERN.find(line)
            if (funMatch != null) {
                val visibility = funMatch.groupValues[1]
                if (visibility == "private" || visibility == "internal") {
                    i = skipBlock(bodyLines, i) + 1
                    continue
                }
                val isSuspend = funMatch.groupValues[2].contains("suspend")
                val isOverride = funMatch.groupValues[2].contains("override")
                val name = funMatch.groupValues[3]

                // Collect full signature spanning multiple lines
                val fullSig = collectFullSignature(bodyLines, i)
                val parameters = extractParameters(fullSig)
                val returnType = extractReturnType(fullSig)
                val signatureStr = buildSignatureString(isSuspend, name, parameters, returnType)

                methods.add(
                    MethodInfo(
                        name = name,
                        signature = signatureStr,
                        returnType = returnType,
                        parameters = parameters,
                        kdoc = kdoc,
                        isSuspend = isSuspend,
                        annotations = annotations,
                    )
                )
                i = skipBlock(bodyLines, i) + 1
            } else {
                i++
            }
        }
        return methods
    }

    private fun collectFullSignature(lines: List<String>, startIndex: Int): String {
        val sb = StringBuilder()
        var i = startIndex
        var parenDepth = 0
        var started = false
        while (i < lines.size) {
            val line = lines[i].trim()
            sb.append(line).append(" ")
            for (ch in line) {
                when (ch) {
                    '(' -> {
                        parenDepth++
                        started = true
                    }
                    ')' -> parenDepth--
                    '{', '=' -> if (started && parenDepth <= 0) return sb.toString()
                }
            }
            if (started && parenDepth <= 0) break
            // If no parens found and line ends with something meaningful, stop
            if (!started && (line.endsWith("{") || line.endsWith("=") || !line.endsWith(","))) break
            i++
        }
        return sb.toString().trim()
    }

    private fun extractParameters(signature: String): List<ParameterInfo> {
        val parensMatch = Regex("\\((.*)\\)").find(signature) ?: return emptyList()
        val paramsStr = parensMatch.groupValues[1].trim()
        if (paramsStr.isEmpty()) return emptyList()

        val params = splitParameters(paramsStr)
        return params.mapNotNull { param ->
            val trimmed = param.trim().replace(Regex("@\\w+(?:\\([^)]*\\))?\\s*"), "").trim()
            val colonIndex = trimmed.indexOf(':')
            if (colonIndex <= 0) return@mapNotNull null
            val name = trimmed.substring(0, colonIndex).trim()
            val rest = trimmed.substring(colonIndex + 1).trim()
            val eqIndex = findDefaultValueSeparator(rest)
            val type = if (eqIndex >= 0) rest.substring(0, eqIndex).trim() else rest
            val default = if (eqIndex >= 0) rest.substring(eqIndex + 1).trim() else null
            ParameterInfo(name = name, type = type, defaultValue = default)
        }
    }

    private fun extractReturnType(signature: String): String {
        // Return type appears after the closing paren: fun foo(): ReturnType
        val afterParens = Regex("\\)\\s*:(.+?)(?:\\{|=|$)").find(signature)
            ?: return ""
        return afterParens.groupValues[1].trim()
    }

    private fun buildSignatureString(isSuspend: Boolean, name: String, params: List<ParameterInfo>, returnType: String): String {
        val sb = StringBuilder()
        if (isSuspend) sb.append("suspend ")
        sb.append("fun $name(")
        sb.append(params.joinToString(", ") { p ->
            val base = "${p.name}: ${p.type}"
            if (p.defaultValue != null) "$base = ${p.defaultValue}" else base
        })
        sb.append(")")
        if (returnType.isNotEmpty()) sb.append(": $returnType")
        return sb.toString()
    }

    private fun extractProperties(bodyLines: List<String>): List<PropertyInfo> {
        val properties = mutableListOf<PropertyInfo>()
        var i = 0
        while (i < bodyLines.size) {
            val kdocResult = collectKDoc(bodyLines, i)
            val kdoc = kdocResult.first
            i = kdocResult.second

            val annotationsResult = collectAnnotations(bodyLines, i)
            val annotations = annotationsResult.first
            i = annotationsResult.second

            if (i >= bodyLines.size) break
            val line = bodyLines[i].trim()

            val propMatch = PROPERTY_PATTERN.find(line)
            if (propMatch != null) {
                val visibility = propMatch.groupValues[1]
                if (visibility == "private" || visibility == "internal") {
                    i++
                    continue
                }
                val isMutable = propMatch.groupValues[3] == "var"
                val name = propMatch.groupValues[4]
                val type = propMatch.groupValues[5].trim()
                    .replace(Regex("\\s*[={].*"), "").trim()

                properties.add(
                    PropertyInfo(
                        name = name,
                        type = type,
                        kdoc = kdoc,
                        isMutable = isMutable,
                        annotations = annotations,
                    )
                )
            }
            i++
        }
        return properties
    }

    private fun extractEnumValues(bodyLines: List<String>): List<String> {
        val values = mutableListOf<String>()
        for (line in bodyLines) {
            val trimmed = line.trim()
            // Enum values are uppercase identifiers, optionally followed by ( or ,
            val match = Regex("^([A-Z][A-Z0-9_]+)\\s*[,(;]?").find(trimmed)
            if (match != null) {
                values.add(match.groupValues[1])
            }
        }
        return values
    }

    private fun skipBlock(lines: List<String>, startIndex: Int): Int {
        val firstLine = lines[startIndex].trim()
        // Single-line declaration with no body (e.g., interface methods, abstract functions)
        // Only bail if parens are balanced (not a multi-line constructor/signature)
        val openParens = firstLine.count { it == '(' }
        val closeParens = firstLine.count { it == ')' }
        if (!firstLine.contains('{') && openParens <= closeParens && !firstLine.endsWith(",") && !firstLine.endsWith("=")) {
            return startIndex
        }
        var braceDepth = 0
        var parenDepth = 0
        var i = startIndex
        var foundBrace = false
        while (i < lines.size) {
            for (ch in lines[i]) {
                when (ch) {
                    '(' -> parenDepth++
                    ')' -> parenDepth--
                    '{' -> {
                        braceDepth++
                        foundBrace = true
                    }
                    '}' -> braceDepth--
                }
            }
            if (foundBrace && braceDepth <= 0) return i
            // Past the start line with no brace found yet and not inside parens - multi-line signature without body
            if (!foundBrace && parenDepth <= 0 && i > startIndex && !lines[i].trim().let { it.endsWith(",") || it.endsWith("(") || it.endsWith("=") }) {
                return i
            }
            i++
        }
        return (lines.size - 1).coerceAtLeast(startIndex)
    }

    private fun detectCategory(decl: ParsedDeclaration): String {
        val annotationNames = decl.annotations.map { it.name }
        return when {
            annotationNames.any { it == "ServiceImplementation" } -> "service-impl"
            annotationNames.any { it == "Repository" } -> "repository"
            annotationNames.any { it == "TypeController" } -> "controller"
            annotationNames.any { it == "JobDefinition" } -> "job"
            annotationNames.any { it == "RouteController" || it == "PageController" } -> "route"
            decl.supertypes.any { it.contains("Service") } && decl.kind == "interface" -> "service"
            decl.kind == "data-class" || annotationNames.any { it == "Serializable" } -> "model"
            decl.kind == "enum" -> "model"
            else -> "model"
        }
    }

    private data class ParsedDeclaration(
        val name: String,
        val kind: String,
        val kdoc: String,
        val annotations: List<AnnotationInfo>,
        val supertypes: List<String>,
        val methods: List<MethodInfo>,
        val properties: List<PropertyInfo>,
        val enumValues: List<String> = emptyList(),
    )

    companion object {
        private val INCLUDED_CATEGORIES = setOf("service", "model")

        private val INCLUDED_TYPES = setOf(
            "Transitioner",
        )

        private val BOSCA_TYPE_PATTERN = Regex("bosca\\.[a-z][a-zA-Z0-9_.]*[A-Z][a-zA-Z0-9]*")

        private val PACKAGE_PATTERN = Regex("^package\\s+(.+?)\\s*$", RegexOption.MULTILINE)
        private val IMPORT_PATTERN = Regex("^import\\s+(.+?)\\s*$", RegexOption.MULTILINE)

        private val DECLARATION_PATTERN = Regex(
            "^(public|internal|protected|private)?\\s*" +
                "((?:(?:abstract|sealed|open|data|enum|value|inner|inline|annotation|suspend|actual|expect|override)\\s+)*)" +
                "(class|interface|object|enum|abstract)\\s+" +
                "(\\w+)"
        )

        private val FUN_PATTERN = Regex(
            "^(public|internal|protected|private)?\\s*" +
                "((?:(?:suspend|override|open|abstract|inline|operator|infix|tailrec|actual|expect|external)\\s+)*)" +
                "fun\\s+(?:<[^>]+>\\s+)?" +
                "(\\w+)\\s*\\("
        )

        private val PROPERTY_PATTERN = Regex(
            "^(public|internal|protected|private)?\\s*" +
                "((?:(?:override|open|abstract|lateinit|const|actual|expect)\\s+)*)" +
                "(val|var)\\s+(\\w+)\\s*:\\s*(.+)"
        )

        private val ANNOTATION_PATTERN = Regex("@(\\w+)(\\([^)]*\\))?")
    }
}
