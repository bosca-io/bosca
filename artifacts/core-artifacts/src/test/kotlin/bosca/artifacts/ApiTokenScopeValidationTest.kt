package bosca.artifacts

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
import bosca.security.service.ApiTokenScopes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that the [ApiTokenScopes.isValid] method correctly validates both
 * static artifact scopes and dynamic fine-grained artifact scopes.
 */
class ApiTokenScopeValidationTest {

    @Test
    fun `base artifact scopes are valid`() {
        assertTrue(ApiTokenScopes.isValid("artifacts:pull"))
        assertTrue(ApiTokenScopes.isValid("artifacts:push"))
        assertTrue(ApiTokenScopes.isValid("artifacts:admin"))
    }

    @Test
    fun `fine-grained artifact scopes are valid`() {
        assertTrue(ApiTokenScopes.isValid("artifacts:docker:acme/api:v1.0:pull"))
        assertTrue(ApiTokenScopes.isValid("artifacts:*:*:*:pull,push"))
        assertTrue(ApiTokenScopes.isValid("artifacts:npm:@scope/pkg:*:pull"))
        assertTrue(ApiTokenScopes.isValid("artifacts:maven:com.acme/sdk:1.0.*:pull"))
    }

    @Test
    fun `invalid artifact scopes are rejected`() {
        assertFalse(ApiTokenScopes.isValid("artifacts:invalid_type:acme/api:v1:pull"))
        assertFalse(ApiTokenScopes.isValid("artifacts:docker:acme/api:v1:read"))
        assertFalse(ApiTokenScopes.isValid("artifacts:docker::v1:pull"))
    }

    @Test
    fun `existing non-artifact scopes still work`() {
        assertTrue(ApiTokenScopes.isValid("content:view"))
        assertTrue(ApiTokenScopes.isValid("security:manage"))
        assertTrue(ApiTokenScopes.isValid("storage:read"))
    }

    @Test
    fun `completely invalid scopes are rejected`() {
        assertFalse(ApiTokenScopes.isValid("not:a:real:scope:at:all"))
        assertFalse(ApiTokenScopes.isValid(""))
        assertFalse(ApiTokenScopes.isValid("random"))
    }

    @Test
    fun `validArtifactTypes covers all ArtifactType enum entries`() {
        val enumValues = ArtifactType.entries.map { it.value }.toSet()
        for (value in enumValues) {
            assertTrue(
                ApiTokenScopes.isValid("artifacts:$value:ns/repo:*:pull"),
                "ArtifactType '$value' should be accepted as a valid artifact scope type"
            )
        }
    }

    @Test
    fun `validArtifactActions covers all ArtifactAction enum entries`() {
        val enumValues = ArtifactAction.entries.map { it.value }.toSet()
        for (value in enumValues) {
            assertTrue(
                ApiTokenScopes.isValid("artifacts:docker:ns/repo:*:$value"),
                "ArtifactAction '$value' should be accepted as a valid artifact scope action"
            )
        }
    }
}
