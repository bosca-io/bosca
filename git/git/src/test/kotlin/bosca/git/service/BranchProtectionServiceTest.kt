package bosca.git.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BranchProtectionServiceTest {

    @Test
    fun `exact match`() {
        assertTrue(BranchProtectionService.matchesGlob("main", "main"))
        assertFalse(BranchProtectionService.matchesGlob("main", "develop"))
    }

    @Test
    fun `single wildcard matches within segment`() {
        assertTrue(BranchProtectionService.matchesGlob("release-*", "release-1.0"))
        assertTrue(BranchProtectionService.matchesGlob("release-*", "release-"))
        assertFalse(BranchProtectionService.matchesGlob("release-*", "release-1.0/hotfix"))
    }

    @Test
    fun `path wildcard matches single segment`() {
        assertTrue(BranchProtectionService.matchesGlob("release/*", "release/1.0"))
        assertFalse(BranchProtectionService.matchesGlob("release/*", "release/1.0/hotfix"))
    }

    @Test
    fun `double wildcard matches multiple segments`() {
        assertTrue(BranchProtectionService.matchesGlob("feature/**", "feature/foo"))
        assertTrue(BranchProtectionService.matchesGlob("feature/**", "feature/foo/bar"))
        assertTrue(BranchProtectionService.matchesGlob("feature/**", "feature/foo/bar/baz"))
        assertFalse(BranchProtectionService.matchesGlob("feature/**", "bugfix/foo"))
    }

    @Test
    fun `double wildcard can match zero path segments`() {
        assertTrue(BranchProtectionService.matchesGlob("**/main", "main"))
        assertTrue(BranchProtectionService.matchesGlob("**/main", "team/main"))
    }

    @Test
    fun `question mark matches single character`() {
        assertTrue(BranchProtectionService.matchesGlob("v?.0", "v1.0"))
        assertFalse(BranchProtectionService.matchesGlob("v?.0", "v10.0"))
        assertFalse(BranchProtectionService.matchesGlob("v?", "v/"))
    }

    @Test
    fun `special regex characters are escaped`() {
        assertTrue(BranchProtectionService.matchesGlob("release/1.0", "release/1.0"))
        assertFalse(BranchProtectionService.matchesGlob("release/1.0", "release/1X0"))
    }

    @Test
    fun `wildcards match empty text without crossing branch path segments`() {
        assertTrue(BranchProtectionService.matchesGlob("*", ""))
        assertTrue(BranchProtectionService.matchesGlob("release-*", "release-"))
        assertFalse(BranchProtectionService.matchesGlob("*", "team/main"))
    }
}
