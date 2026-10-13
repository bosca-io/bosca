package bosca.git.server

import bosca.git.server.graphql.OwnerSearchController
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchDocument
import bosca.search.model.SearchQuery
import bosca.search.model.SearchResult
import bosca.search.service.SearchService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class OwnerSearchControllerTest {
    @Test
    fun `owner search only returns profiles allowed by the domain evaluator`() = runTest {
        val search = mockk<SearchService>()
        val permissions = mockk<ProfilePermissionEvaluator>()
        val allowed =
            Profile(
                UUID.random(),
                ProfileType.GENERIC,
                name = "Same name",
                visibility = ProfileVisibility.PUBLIC,
            )
        val hidden = allowed.copy(id = UUID.random(), visibility = ProfileVisibility.SYSTEM)
        val query = SearchQuery("Same name", offset = 0, limit = 20)
        val result =
            SearchResult(
                listOf(
                    SearchDocument(profile = allowed),
                    SearchDocument(profile = hidden),
                    SearchDocument(),
                ),
                emptyList(),
                3,
                IndexStorageSystem(name = "AdminSearchIndex"),
            )
        coEvery { search.search(query) } returns result
        val controller = OwnerSearchController(search, permissions)
        for (authentication in listOf(null, mockk<AuthenticationContext>())) {
            coEvery {
                permissions.filterAllowed(
                    authentication,
                    listOf(allowed, hidden),
                    PermissionAction.VIEW,
                )
            } returns listOf(allowed)
            assertEquals(
                result.copy(documents = listOf(SearchDocument(profile = allowed))),
                controller.search(authentication, query),
            )
            coVerify {
                permissions.filterAllowed(
                    authentication,
                    listOf(allowed, hidden),
                    PermissionAction.VIEW,
                )
            }
        }
    }
}
