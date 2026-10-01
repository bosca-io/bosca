package bosca.profile.profile.service

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.withRequestCache
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.repository.ProfileRepository
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.slug.service.SlugService
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalDI::class)
class ProfileServiceImplTest2 {

    private val repository = mockk<ProfileRepository>()
    private val securityService = mockk<ObjectProvider<SecurityService>>()
    private val securityServiceInstance = mockk<SecurityService>(relaxed = true)
    private val organizationService = mockk<ObjectProvider<OrganizationService>>()
    private val organizationServiceInstance = mockk<OrganizationService>(relaxed = true)
    private val attributeService = mockk<ProfileAttributeService>(relaxed = true)
    private val slugService = mockk<SlugService>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val profileQueue = mockk<JobQueue>(relaxed = true)

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var service: ProfileServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(testJson) }
        provides<JobQueue>("profileQueue") { profileQueue }
        provides<Json> { testJson }

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
        
        coEvery { securityService.get() } returns securityServiceInstance
        coEvery { organizationService.get() } returns organizationServiceInstance

        service = ProfileServiceImpl(
            repository,
            securityService,
            organizationService,
            attributeService,
            slugService
        )
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `add should create profile and slug`() = runTest {
        withRequestCache {
            val input = ProfileInput(name = "Test Profile", visibility = ProfileVisibility.PUBLIC, attributes = emptyList())
            val type = ProfileType.GENERIC
            val principalId = UUID.random()
            val savedProfile = Profile(id = UUID.random(), name = "Test Profile", type = type, visibility = ProfileVisibility.PUBLIC, principal = principalId)
            
            coEvery { repository.add(any()) } returns savedProfile
            coEvery { slugService.add(any()) } returns mockk()
            coEvery { attributeService.addAttributes(any(), any()) } returns emptyList()
            
            val result = service.add(input, type, principalId)
            
            assertEquals(savedProfile, result)
            coVerify { 
                repository.add(match { it.name == "Test Profile" && it.principal == principalId })
                slugService.add(match { it.slug == "test-profile" && it.profileId == savedProfile.id })
            }
        }
    }

    @Test
    fun `getById should return profile`() = runTest {
        withRequestCache {
            val id = UUID.random()
            val profile = Profile(id = id, name = "p", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            
            coEvery { repository.getById(id) } returns profile
            
            val result = service.getById(id)
            
            assertEquals(profile, result)
        }
    }

    @Test
    fun `getByPrincipal should return profiles`() = runTest {
        withRequestCache {
            val principalId = UUID.random()
            val profile = Profile(id = UUID.random(), name = "p", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC, principal = principalId)
            
            coEvery { repository.getByPrincipal(principalId) } returns listOf(profile)
            
            val result = service.getByPrincipal(principalId)
            
            assertEquals(listOf(profile), result)
        }
    }
    
    @Test
    fun `delete should remove profile`() = runTest {
        withRequestCache {
            val id = UUID.random()
            val profile = Profile(id = id, name = "p", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            
            coEvery { repository.getById(id) } returns profile
            coEvery { repository.deleteById(id) } returns Unit
            
            service.delete(id)
            
            coVerify { repository.deleteById(id) }
        }
    }
    
    @Test
    fun `edit should update profile`() = runTest {
        withRequestCache {
            val id = UUID.random()
            val input = ProfileInput(name = "Updated", visibility = ProfileVisibility.USER, attributes = emptyList())
            val existing = Profile(id = id, name = "Original", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            val updated = Profile(id = id, name = "Updated", type = ProfileType.GENERIC, visibility = ProfileVisibility.USER)
            
            coEvery { repository.getById(id) } returns existing
            coEvery { repository.update(any()) } returns updated
            coEvery { slugService.add(any()) } returns mockk()
            coEvery { attributeService.addAttributes(any(), any()) } returns emptyList()
            
            val result = service.edit(id, input)
            
            assertEquals(updated, result)
            coVerify { repository.update(match { it.name == "Updated" && it.visibility == ProfileVisibility.USER }) }
        }
    }
    @Test
    fun `getAll should return profiles`() = runTest {
        withRequestCache {
            val profile = Profile(id = UUID.random(), name = "p", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            coEvery { repository.getAll(0, 10) } returns listOf(profile)
            
            val result = service.getAll(0, 10)
            
            assertEquals(listOf(profile), result)
        }
    }
    
    @Test
    fun `getAllByType should return profiles`() = runTest {
        withRequestCache {
            val profile = Profile(id = UUID.random(), name = "p", type = ProfileType.GENERIC, visibility = ProfileVisibility.PUBLIC)
            coEvery { repository.getAllByType(0, 10, ProfileType.GENERIC) } returns listOf(profile)
            
            val result = service.getAllByType(0, 10, ProfileType.GENERIC)
            
            assertEquals(listOf(profile), result)
        }
    }
    
    @Test
    fun `addAttributeType should delegate to attributeService`() = runTest {
        withRequestCache {
            val input = mockk<bosca.profile.attribute.model.ProfileAttributeTypeInput>()
            coEvery { attributeService.addAttributeType(input) } returns mockk()
            
            service.addAttributeType(input)
            
            coVerify { attributeService.addAttributeType(input) }
        }
    }
}
