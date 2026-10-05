package bosca.security.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiTokenScopeTest {

    @Test
    fun `all scopes have unique names`() {
        val names = ApiTokenScopes.all.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate scope names found")
    }

    @Test
    fun `all scopes have non-blank descriptions`() {
        for (scope in ApiTokenScopes.all) {
            assertTrue(scope.description.isNotBlank(), "scope ${scope.name} has blank description")
        }
    }

    @Test
    fun `isValid returns true for known scopes`() {
        assertTrue(ApiTokenScopes.isValid("content:view"))
        assertTrue(ApiTokenScopes.isValid("storage:write"))
        assertTrue(ApiTokenScopes.isValid("security:manage"))
        assertTrue(ApiTokenScopes.isValid("jobs:execute"))
    }

    @Test
    fun `isValid returns false for unknown scopes`() {
        assertFalse(ApiTokenScopes.isValid("unknown:scope"))
        assertFalse(ApiTokenScopes.isValid(""))
        assertFalse(ApiTokenScopes.isValid("content"))
        assertFalse(ApiTokenScopes.isValid("CONTENT:VIEW"))
    }

    @Test
    fun `isValid accepts fine-grained ml artifact scopes`() {
        // The ML model artifacts type (route prefix /ml) is a valid fine-grained scope type.
        assertTrue(ApiTokenScopes.isValid("artifacts:ml:model/recommender-personalized:*:push"))
        assertTrue(ApiTokenScopes.isValid("artifacts:ml:model/*:*:pull"))
        assertTrue(ApiTokenScopes.isValidArtifactScope("artifacts:ml:model/*:*:push,pull"))
    }

    @Test
    fun `isValidArtifactScope rejects unknown artifact types`() {
        assertFalse(ApiTokenScopes.isValidArtifactScope("artifacts:bogus:model/*:*:push"))
    }

    @Test
    fun `all list includes every ApiTokenScope property on ApiTokenScopes`() {
        val allNames = ApiTokenScopes.all.map { it.name }.toSet()
        val reflectedScopes = ApiTokenScopes::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { ApiTokenScope::class.java.isAssignableFrom(it.type) }
            .map { it.isAccessible = true; (it.get(null) as ApiTokenScope).name }
            .toSet()
        val missing = reflectedScopes - allNames
        assertTrue(missing.isEmpty(), "scopes declared as properties but missing from 'all' list: $missing")
    }

    @Test
    fun `all scopes follow resource-colon-action convention`() {
        for (scope in ApiTokenScopes.all) {
            val parts = scope.name.split(":")
            assertEquals(2, parts.size, "scope ${scope.name} should have exactly one colon")
            assertTrue(parts[0].isNotBlank(), "scope ${scope.name} has blank resource")
            assertTrue(parts[1].isNotBlank(), "scope ${scope.name} has blank action")
        }
    }

    private fun assertEquals(expected: Any, actual: Any, message: String) {
        kotlin.test.assertEquals(expected, actual, message)
    }
}
