package bosca.workops.service

import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.federation.FederationPeer
import bosca.workops.model.federation.FederationPeerKind
import bosca.workops.model.federation.RemoteTaskSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FederationAdapterTest {

    @Test
    fun `registry returns registered adapters and a phase-aware pending fallback`() = runTest {
        val initial = mockk<FederationAdapter>()
        every { initial.kind } returns FederationPeerKind.GITHUB_ISSUES
        val replacement = mockk<FederationAdapter>()
        every { replacement.kind } returns FederationPeerKind.GITHUB_ISSUES
        val registry = FederationAdapterRegistry(mapOf(initial.kind to initial))
        val peer = FederationPeer(
            name = "GitHub",
            kind = FederationPeerKind.GITHUB_ISSUES,
            baseUrl = "https://example.test",
        )

        assertSame(initial, registry.adapterFor(FederationPeerKind.GITHUB_ISSUES))
        assertSame(initial, registry.adapterFor(peer))
        registry.register(replacement)
        assertSame(replacement, registry.adapterFor(FederationPeerKind.GITHUB_ISSUES))
        assertEquals(
            FederationPeerKind.CUSTOM,
            FederationAdapterRegistry().adapterFor(FederationPeerKind.CUSTOM).kind,
        )

        val pending = registry.adapterFor(FederationPeerKind.LINEAR)
        assertEquals(FederationPeerKind.LINEAR, pending.kind)
        assertTrue(pending.pull(peer, sinceEtag = "etag-1").isEmpty())
        val failure = assertFailsWith<PendingPhaseImplementationException> {
            pending.push(
                peer,
                RemoteTaskSnapshot(
                    remoteId = "42",
                    remoteKey = "LIN-42",
                    canonicalUrl = "https://example.test/LIN-42",
                    summary = "Pending task",
                    descriptionMarkdown = null,
                    statusName = null,
                    priorityName = null,
                    assigneeRemoteUserId = null,
                ),
                setOf("summary"),
            )
        }
        assertEquals("FederationAdapter.LINEAR.push", failure.variant)
        assertEquals(24, failure.owningPhase)
    }
}
