package bosca.ide.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoscaGitRemoteNormalizerTest {
    @Test
    fun `HTTP SSH and SCP remotes share one identity`() {
        assertEquals("git.bosca.example/team/repo", BoscaGitRemoteNormalizer.normalize("https://git.bosca.example/team/repo.git"))
        assertEquals("git.bosca.example/team/repo", BoscaGitRemoteNormalizer.normalize("ssh://git@git.bosca.example/team/repo.git"))
        assertEquals("git.bosca.example/team/repo", BoscaGitRemoteNormalizer.normalize("git@git.bosca.example:team/repo.git"))
    }

    @Test
    fun `empty and local paths do not become remote identities`() {
        assertNull(BoscaGitRemoteNormalizer.normalize(""))
        assertNull(BoscaGitRemoteNormalizer.normalize("../repo"))
    }
}
