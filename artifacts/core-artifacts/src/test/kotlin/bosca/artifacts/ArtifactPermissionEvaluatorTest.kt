package bosca.artifacts

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactPermissionEvaluator.Companion.matchesGlob
import bosca.artifacts.service.ArtifactPermissionEvaluator.Companion.matchesScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactPermissionEvaluatorTest {

    // -- Glob matching --

    @Test
    fun `wildcard matches everything`() {
        assertTrue(matchesGlob("*", "anything"))
        assertTrue(matchesGlob("*", ""))
        assertTrue(matchesGlob("*", "a/b/c"))
    }

    @Test
    fun `exact match`() {
        assertTrue(matchesGlob("v1.0.0", "v1.0.0"))
        assertFalse(matchesGlob("v1.0.0", "v1.0.1"))
    }

    @Test
    fun `glob pattern with trailing wildcard`() {
        assertTrue(matchesGlob("v3.*", "v3.0"))
        assertTrue(matchesGlob("v3.*", "v3.2.1"))
        assertFalse(matchesGlob("v3.*", "v4.0"))
    }

    @Test
    fun `glob pattern with prefix wildcard`() {
        assertTrue(matchesGlob("*-SNAPSHOT", "1.0-SNAPSHOT"))
        assertFalse(matchesGlob("*-SNAPSHOT", "1.0-RELEASE"))
    }

    @Test
    fun `glob pattern with middle wildcard`() {
        assertTrue(matchesGlob("v1.*.0", "v1.2.0"))
        assertTrue(matchesGlob("v1.*.0", "v1.99.0"))
        assertFalse(matchesGlob("v1.*.0", "v1.2.1"))
    }

    @Test
    fun `glob escapes regex special characters`() {
        assertTrue(matchesGlob("1.0.0", "1.0.0"))
        assertFalse(matchesGlob("1.0.0", "1a0b0"))  // dots should not match any char
    }

    // -- Scope matching --

    @Test
    fun `full wildcard scope grants everything`() {
        assertTrue(matchesScope("artifacts:*:*:*:pull", "docker", "acme", "api", "v1.0", ArtifactAction.PULL))
        assertTrue(matchesScope("artifacts:*:*:*:pull", "maven", "com.example", "sdk", "2.0", ArtifactAction.PULL))
        assertTrue(matchesScope("artifacts:*:*:*:pull", "npm", "@scope", "cli", "1.0.0", ArtifactAction.PULL))
    }

    @Test
    fun `full wildcard does not grant wrong action`() {
        assertFalse(matchesScope("artifacts:*:*:*:pull", "docker", "acme", "api", "v1.0", ArtifactAction.PUSH))
    }

    @Test
    fun `admin scope grants all actions`() {
        assertTrue(matchesScope("artifacts:*:*:*:admin", "docker", "acme", "api", "v1.0", ArtifactAction.PULL))
        assertTrue(matchesScope("artifacts:*:*:*:admin", "docker", "acme", "api", "v1.0", ArtifactAction.PUSH))
        assertTrue(matchesScope("artifacts:*:*:*:admin", "docker", "acme", "api", "v1.0", ArtifactAction.ADMIN))
    }

    @Test
    fun `type-specific scope`() {
        assertTrue(matchesScope("artifacts:docker:*:*:pull", "docker", "acme", "api", "v1.0", ArtifactAction.PULL))
        assertFalse(matchesScope("artifacts:docker:*:*:pull", "maven", "acme", "api", "v1.0", ArtifactAction.PULL))
    }

    @Test
    fun `namespace wildcard scope`() {
        assertTrue(matchesScope("artifacts:docker:acme/*:*:pull", "docker", "acme", "api", "v1.0", ArtifactAction.PULL))
        assertTrue(matchesScope("artifacts:docker:acme/*:*:pull", "docker", "acme", "web", "v2.0", ArtifactAction.PULL))
        assertFalse(matchesScope("artifacts:docker:acme/*:*:pull", "docker", "other", "api", "v1.0", ArtifactAction.PULL))
    }

    @Test
    fun `exact namespace and repo scope`() {
        assertTrue(matchesScope("artifacts:docker:acme/api-server:*:pull", "docker", "acme", "api-server", null, ArtifactAction.PULL))
        assertFalse(matchesScope("artifacts:docker:acme/api-server:*:pull", "docker", "acme", "web-server", null, ArtifactAction.PULL))
    }

    @Test
    fun `version-specific scope`() {
        assertTrue(matchesScope("artifacts:docker:acme/api:v2.1.0:pull", "docker", "acme", "api", "v2.1.0", ArtifactAction.PULL))
        assertFalse(matchesScope("artifacts:docker:acme/api:v2.1.0:pull", "docker", "acme", "api", "v2.2.0", ArtifactAction.PULL))
    }

    @Test
    fun `version glob scope`() {
        assertTrue(matchesScope("artifacts:docker:vendor/api-gateway:v3.*:pull", "docker", "vendor", "api-gateway", "v3.2.1", ArtifactAction.PULL))
        assertFalse(matchesScope("artifacts:docker:vendor/api-gateway:v3.*:pull", "docker", "vendor", "api-gateway", "v2.0.0", ArtifactAction.PULL))
    }

    @Test
    fun `multi-action scope`() {
        assertTrue(matchesScope("artifacts:docker:internal/*:*:push,pull", "docker", "internal", "api", "latest", ArtifactAction.PULL))
        assertTrue(matchesScope("artifacts:docker:internal/*:*:push,pull", "docker", "internal", "api", "latest", ArtifactAction.PUSH))
        assertFalse(matchesScope("artifacts:docker:internal/*:*:push,pull", "docker", "internal", "api", "latest", ArtifactAction.ADMIN))
    }

    @Test
    fun `npm scoped package scope`() {
        assertTrue(matchesScope("artifacts:npm:@vendor/sdk:*:pull", "npm", "@vendor", "sdk", "1.0.0", ArtifactAction.PULL))
        assertFalse(matchesScope("artifacts:npm:@vendor/sdk:*:push", "npm", "@vendor", "sdk", "1.0.0", ArtifactAction.PULL))
    }

    @Test
    fun `maven group scope`() {
        assertTrue(matchesScope("artifacts:maven:com.acme/*:*:pull", "maven", "com.acme", "sdk", "1.0", ArtifactAction.PULL))
        assertFalse(matchesScope("artifacts:maven:com.acme/*:*:pull", "maven", "com.other", "sdk", "1.0", ArtifactAction.PULL))
    }

    @Test
    fun `non-artifact scope is rejected`() {
        assertFalse(matchesScope("content:view", "docker", "acme", "api", "v1.0", ArtifactAction.PULL))
    }

    @Test
    fun `malformed scope is rejected`() {
        assertFalse(matchesScope("artifacts:docker:acme:pull", "docker", "acme", "api", "v1.0", ArtifactAction.PULL))
        assertFalse(matchesScope("artifacts:", "docker", "acme", "api", "v1.0", ArtifactAction.PULL))
    }

    // -- pathMatches: namespace-only scope --

    @Test
    fun `namespace-only scope matches all repositories in that namespace`() {
        assertTrue(matchesScope("artifacts:docker:acme:*:pull", "docker", "acme", "any-repo", "v1.0", ArtifactAction.PULL))
        assertTrue(matchesScope("artifacts:docker:acme:*:pull", "docker", "acme", "other-repo", "v1.0", ArtifactAction.PULL))
    }

    @Test
    fun `namespace-only scope does not match different namespace`() {
        assertFalse(matchesScope("artifacts:docker:acme:*:pull", "docker", "other", "any-repo", "v1.0", ArtifactAction.PULL))
    }

    @Test
    fun `null version matches wildcard`() {
        assertTrue(matchesScope("artifacts:docker:acme/api:*:pull", "docker", "acme", "api", null, ArtifactAction.PULL))
    }

    // -- Scope validation --

    @Test
    fun `valid artifact scopes`() {
        assertTrue(ArtifactPermissionEvaluator.isValidArtifactScope("artifacts:docker:acme/api:v1.0:pull"))
        assertTrue(ArtifactPermissionEvaluator.isValidArtifactScope("artifacts:*:*:*:pull,push,admin"))
        assertTrue(ArtifactPermissionEvaluator.isValidArtifactScope("artifacts:npm:@scope/pkg:1.0.*:pull"))
    }

    @Test
    fun `invalid artifact scopes`() {
        assertFalse(ArtifactPermissionEvaluator.isValidArtifactScope("artifacts:invalid_type:acme/api:v1:pull"))
        assertFalse(ArtifactPermissionEvaluator.isValidArtifactScope("artifacts:docker:acme/api:v1:read"))
        assertFalse(ArtifactPermissionEvaluator.isValidArtifactScope("content:view"))
        assertFalse(ArtifactPermissionEvaluator.isValidArtifactScope("artifacts:docker::v1:pull"))
    }

    // -- Commercial licensing scenario --

    @Test
    fun `commercial licensing - org can access purchased artifacts only`() {
        // Org A purchased Docker image v3.* and npm SDK all versions
        val orgScopes = listOf(
            "artifacts:docker:vendor/api-gateway:v3.*:pull",
            "artifacts:npm:@vendor/sdk:*:pull",
        )

        // Can pull purchased Docker image
        assertTrue(orgScopes.any { matchesScope(it, "docker", "vendor", "api-gateway", "v3.2.1", ArtifactAction.PULL) })

        // Cannot pull unpurchased Docker version
        assertFalse(orgScopes.any { matchesScope(it, "docker", "vendor", "api-gateway", "v2.0.0", ArtifactAction.PULL) })

        // Can pull any npm SDK version
        assertTrue(orgScopes.any { matchesScope(it, "npm", "@vendor", "sdk", "2.0.0", ArtifactAction.PULL) })

        // Cannot pull unpurchased Maven artifact
        assertFalse(orgScopes.any { matchesScope(it, "maven", "com.vendor", "sdk", "1.0", ArtifactAction.PULL) })

        // Cannot push to purchased artifacts
        assertFalse(orgScopes.any { matchesScope(it, "docker", "vendor", "api-gateway", "v3.0", ArtifactAction.PUSH) })
    }
}
