package bosca.atlas.ui.server

import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass

/** Runs the actual composed Atlas BML host against an isolated HTTP GraphQL fixture. */
class AtlasWorkOpsUiRoutesTest {
    @Before fun resetRequests() = requests.clear()

    @Test fun `imported WorkOps routes require authentication and retain their login return`() {
        val approval = get("/workops/approvals/00000000-0000-0000-0000-000000000001", authenticated = false)
        assertEquals(302, approval.statusCode())
        assertEquals("/workops/approvals/00000000-0000-0000-0000-000000000001", URLDecoder.decode(approval.headers().firstValue("Location").orElse("").substringAfter("redirect="), Charsets.UTF_8))
        for (path in listOf("/workops/tasks?bql=project%20%3D%20TEST", "/workops/tasks/TEST-1", "/workops/requirements/TEST-REQ-1", "/workops/specs", "/workops/specs/TEST-SPEC-1", "/workops/portfolios", "/workops/programs", "/workops/projects", "/workops/boards?projectId=project&boardId=board", "/workops/milestones?programId=program", "/workops/settings/milestones?programId=program", "/workops/filters", "/workops/inbox?tab=preferences") + listOf("task-types", "statuses", "priorities", "labels", "link-types", "resolutions", "automation", "workflows", "sla", "components", "permissions", "notifications").map { "/workops/settings/$it?offset=25" }) {
            val response = get(path, authenticated = false)
            assertEquals(302, response.statusCode())
            val location = response.headers().firstValue("Location").orElse("")
            assertTrue(location.startsWith("/login?redirect="), location)
            assertEquals(path, URLDecoder.decode(location.substringAfter("redirect="), Charsets.UTF_8))
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun `composed route renders escaped authorized task data with Atlas navigation and packaged assets`() {
        val response = get("/workops/tasks?bql=project%20%3D%20TEST&offset=25")
        assertEquals(200, response.statusCode(), response.body())
        val html = response.body()
        assertTrue("Tasks — Bosca Atlas" in html)
        assertTrue("class=\"workops-document\"" in html)
        assertTrue("data-current-subsystem=\"workops\"" in html)
        assertTrue("href=\"/repositories\"" in html)
        assertTrue("href=\"/workops/tasks/task-1\"" in html)
        assertTrue("Task &lt;script&gt;" in html, html)
        assertFalse("Task <script>" in html)
        assertTrue(requests.all { it.second == "Bearer workops-session" }, requests.toString())
        assertEquals(listOf("WorkOpsValidateBql", "WorkOpsTaskFilters", "WorkOpsTaskSearch"), requests.map { it.first })
        val assets = Regex("(?:src|href)=\"(/_bml/[^\"]+)\"").findAll(html)
            .map { it.groupValues[1].replace("&amp;", "&") }.toList()
        assertTrue(assets.any { it.substringBefore('?').endsWith(".js") }, html)
        assertTrue(assets.any { it.substringBefore('?').endsWith(".css") }, html)
        for (asset in assets) assertEquals(200, get(asset).statusCode(), asset)
        val light = get("/workops/tasks", appearance = "light").body()
        assertTrue("data-theme=\"light\"" in light)
    }

    @Test fun `invalid BQL retains its value and does not claim an empty result or run the search`() {
        val response = get("/workops/tasks?bql=broken")
        assertEquals(200, response.statusCode(), response.body())
        assertTrue("Expected a value" in response.body())
        assertTrue("data-value=\"broken\"" in response.body())
        assertFalse("No tasks found" in response.body())
        assertFalse(requests.any { it.first == "WorkOpsTaskSearch" })
    }

    @Test fun `new task entry loads the project stored schema and composes shared form controls`() {
        val response = get("/workops/tasks?new=1&project=project")
        assertEquals(200, response.statusCode(), response.body())
        assertTrue("data-bml-schema-form" in response.body(), response.body())
        assertTrue("data-force-open" in response.body())
        assertTrue("TEST · Test project" in response.body())
        assertTrue("data-schema-property=\"summary\"" in response.body())
        assertTrue("data-schema-control=\"select\"" in response.body())
        assertTrue(requests.any { it.first == "WorkOpsTaskCreateForm" })
        assertTrue(requests.all { it.second == "Bearer workops-session" })
    }

    @Test fun `both milestone routes compose shared controls under the caller credentials`() {
        for (path in listOf("/workops/milestones?programId=program", "/workops/settings/milestones?programId=program")) {
            val response = get(path)
            assertEquals(200, response.statusCode(), response.body())
            assertTrue("data-workops-milestones" in response.body(), response.body())
            assertTrue("Review &lt;release&gt;" in response.body(), response.body())
            assertTrue("Feb 3, 2027" in response.body(), response.body())
            assertTrue("New milestone" in response.body(), response.body())
            assertTrue(requests.all { it.second == "Bearer workops-session" })
        }
    }

    @Test fun `hierarchy routes use owning lookups and escape names in the imported shell`() {
        for (kind in listOf("portfolio", "program", "project")) {
            val response = get("/workops/${kind}s")
            assertEquals(200, response.statusCode(), response.body())
            assertTrue("data-planning-kind=\"$kind\"" in response.body(), response.body())
            assertTrue("Plan &lt;$kind&gt;" in response.body(), response.body())
            assertTrue("Profile unavailable" in response.body(), response.body())
            assertTrue(requests.all { it.second == "Bearer workops-session" })
        }
    }

    @Test fun `board directory imports neutral board controls and authorized caller task data`() {
        val response = get("/workops/boards?projectId=project&boardId=board")
        assertEquals(200, response.statusCode(), response.body())
        val html = response.body()
        assertTrue("data-workops-board-directory" in html, html)
        assertTrue("data-bml-board" in html, html)
        assertTrue("data-board-column=\"todo\"" in html, html)
        assertTrue("Board &lt;planning&gt;" in html, html)
        assertTrue("Task &lt;card&gt;" in html, html)
        assertTrue("New board" in html, html)
        assertTrue(requests.all { it.second == "Bearer workops-session" })
    }

    @Test fun `board configuration composes mapped columns and the single parent settings form`() {
        val columns = get("/workops/boards/board?tab=columns")
        assertEquals(200, columns.statusCode(), columns.body())
        assertTrue("data-workops-column=\"todo\"" in columns.body(), columns.body())
        assertTrue("Add column" in columns.body(), columns.body())
        assertTrue("To do" in columns.body(), columns.body())
        val settings = get("/workops/boards/board?tab=settings")
        assertEquals(200, settings.statusCode(), settings.body())
        assertTrue("Save board settings" in settings.body(), settings.body())
        assertFalse(Regex("<select[^>]*name=\"projectIds\"").containsMatchIn(settings.body()), settings.body())
        assertTrue(requests.all { it.second == "Bearer workops-session" })
        assertFalse(requests.any { it.first == "WorkOpsBoardCards" })
        val board = get("/workops/boards/board?tab=board")
        assertEquals(200, board.statusCode(), board.body())
        assertTrue("data-workops-board-instance=\"detail\"" in board.body(), board.body())
        assertTrue("Task &lt;card&gt;" in board.body(), board.body())
        val anonymous = get("/workops/boards/board?tab=columns", authenticated = false)
        assertEquals(302, anonymous.statusCode())
    }

    @Test fun `sprint hub detail and board tab use the shared workspace with caller authorized data`() {
        for ((path, mode) in listOf("/workops/sprints?projectId=project&boardId=board" to "list", "/workops/boards/board?tab=sprints" to "board", "/workops/sprints/sprint?tab=tasks" to "detail")) {
            val response = get(path)
            assertEquals(200, response.statusCode(), response.body())
            assertTrue("data-sprint-mode=\"$mode\"" in response.body(), response.body())
            assertTrue("Sprint &lt;iteration&gt;" in response.body(), response.body())
            val anonymous = get(path, authenticated = false)
            assertEquals(302, anonymous.statusCode())
            assertEquals(path, URLDecoder.decode(anonymous.headers().firstValue("Location").orElse("").substringAfter("redirect="), Charsets.UTF_8))
        }
        val detail = get("/workops/sprints/sprint?tab=tasks")
        assertTrue("data-workops-sprint-task=\"member\"" in detail.body(), detail.body())
        assertTrue("Sprint task &lt;safe&gt;" in detail.body(), detail.body())
        assertTrue("Profile unavailable" in detail.body(), detail.body())
        val progress = get("/workops/sprints/sprint?tab=progress")
        assertEquals(200, progress.statusCode(), progress.body())
        assertTrue("Visible task completion" in progress.body(), progress.body())
        assertTrue(requests.all { it.second == "Bearer workops-session" })
    }

    @Test fun `all project tabs import shared controls and escape caller scoped project data`() {
        val markers = mapOf("overview" to "Recent task statuses", "board" to "data-bml-board", "versions" to "Version &lt;release&gt;",
            "components" to "API &lt;component&gt;", "dependencies" to "Dependency &lt;provider&gt;", "labels" to "label-safe",
            "repositories" to "Repository &lt;source&gt;", "analytics" to "app&lt;application&gt;")
        for ((tab, marker) in markers) {
            val path = "/workops/projects/project?tab=$tab"
            val response = get(path)
            assertEquals(200, response.statusCode(), response.body())
            assertTrue("data-workops-project-details" in response.body(), response.body())
            assertTrue("Project &lt;planning&gt;" in response.body(), response.body())
            assertTrue(marker in response.body(), response.body())
            assertFalse("Project <planning>" in response.body(), response.body())
            val anonymous = get(path, authenticated = false)
            assertEquals(302, anonymous.statusCode())
            assertEquals(path, URLDecoder.decode(anonymous.headers().firstValue("Location").orElse("").substringAfter("redirect="), Charsets.UTF_8))
        }
        val overview = get("/workops/projects/project")
        assertTrue("Profile unavailable" in overview.body(), overview.body())
        assertTrue("Project task &lt;safe&gt;" in overview.body(), overview.body())
        val dependencies = get("/workops/projects/project?tab=dependencies")
        assertTrue("Project unavailable" in dependencies.body(), dependencies.body())
        assertTrue(requests.all { it.second == "Bearer workops-session" })
    }

    @Test fun `delivery versions use authorized projects and shared catalogs with escaped dates and sequence rows`() {
        val path = "/workops/releases/versions?projectId=project"
        val response = get(path)
        assertEquals(200, response.statusCode(), response.body())
        for (marker in listOf("workops-catalog-page", "Project &lt;delivery&gt;", "Version &lt;delivery&gt;", "Start date", "Sequence", "Refresh versions")) {
            assertTrue(marker in response.body(), response.body())
        }
        assertFalse("Version <delivery>" in response.body(), response.body())
        assertTrue("workops-project-versions-delivery-version" in response.body(), response.body())
        val missing = get("/workops/releases/versions?projectId=private")
        assertTrue("Choose an available project." in missing.body(), missing.body())
        assertFalse("data-workops-project-catalog" in missing.body(), missing.body())
        val anonymous = get(path, authenticated = false)
        assertEquals(302, anonymous.statusCode())
        assertEquals(path, URLDecoder.decode(anonymous.headers().firstValue("Location").orElse("").substringAfter("redirect="), Charsets.UTF_8))
        assertTrue(requests.all { it.second == "Bearer workops-session" })
    }

    @Test fun `global environment types compose shared controls and keep anonymous return paths`() {
        val path = "/workops/settings/environment-types"
        val response = get(path)
        assertEquals(200, response.statusCode(), response.body())
        for (marker in listOf("data-workops-environment-types", "Testing &lt;stage&gt;", "Environment details", "New environment type")) {
            assertTrue(marker in response.body(), response.body())
        }
        assertFalse("Testing <stage>" in response.body(), response.body())
        val anonymous = get(path, authenticated = false)
        assertEquals(302, anonymous.statusCode())
        assertEquals(path, URLDecoder.decode(anonymous.headers().firstValue("Location").orElse("").substringAfter("redirect="), Charsets.UTF_8))
        assertTrue(requests.all { it.second == "Bearer workops-session" })
    }

    @Test fun `program environments render authorized context without requiring the administrator group catalog`() {
        val path = "/workops/releases/environments?programId=program"
        val response = get(path)
        assertEquals(200, response.statusCode(), response.body())
        for (marker in listOf("data-workops-environments", "Program &lt;deployment&gt;", "Preview &lt;target&gt;", "preview-key", "Environment unavailable", "Approval required")) {
            assertTrue(marker in response.body(), response.body())
        }
        assertFalse("Preview <target>" in response.body(), response.body())
        assertFalse(requests.any { it.first == "WorkOpsEnvironmentGroups" })
        val missing = get("/workops/releases/environments?programId=private")
        assertTrue("Choose an available program." in missing.body(), missing.body())
        assertFalse("data-workops-environment=\"environment\"" in missing.body(), missing.body())
        val anonymous = get(path, authenticated = false)
        assertEquals(302, anonymous.statusCode())
        assertEquals(path, URLDecoder.decode(anonymous.headers().firstValue("Location").orElse("").substringAfter("redirect="), Charsets.UTF_8))
        assertTrue(requests.all { it.second == "Bearer workops-session" })
    }

    @Test fun `release directory composes the builder and native CI statuses with authenticated return paths`() {
        val path = "/workops/releases?programId=program"
        val response = get(path)
        assertEquals(200, response.statusCode(), response.body())
        for (marker in listOf("data-workops-releases", "data-workops-release-build", "Release &lt;bundle&gt;", "Deployed", "/workops/releases/release")) {
            assertTrue(marker in response.body(), response.body())
        }
        assertFalse("Release <bundle>" in response.body(), response.body())
        val anonymous = get(path, authenticated = false)
        assertEquals(302, anonymous.statusCode())
        assertEquals(path, URLDecoder.decode(anonymous.headers().firstValue("Location").orElse("").substringAfter("redirect="), Charsets.UTF_8))
        assertTrue(requests.all { it.second == "Bearer workops-session" })
    }

    @Test fun `release detail composes isolated editing and authorized bundle labels without leaking hidden projects`() {
        val path = "/workops/releases/release"
        val response = get(path)
        assertEquals(200, response.statusCode(), response.body())
        for (marker in listOf("data-workops-release-overview", "data-workops-release-edit", "data-workops-release-ci", "data-workops-release-environments", "No environments are configured for this program.", "data-workops-release-artifacts", "data-workops-release-notes", "container:&lt;version&gt;", "Play &lt;notes&gt;", "/pipelines/native-run", "Release &lt;bundle&gt;", "Project &lt;delivery&gt;", "Version &lt;delivery&gt;", "Project unavailable", "Version unavailable", "Provider &lt;dependency&gt;")) {
            assertTrue(marker in response.body(), response.body())
        }
        assertFalse("Release <bundle>" in response.body(), response.body())
        assertFalse("Project <delivery>" in response.body(), response.body())
        assertEquals(4, requests.count { it.first == "WorkOpsDeliveryVersions" })
        val anonymous = get(path, authenticated = false)
        assertEquals(302, anonymous.statusCode())
        assertEquals(path, URLDecoder.decode(anonymous.headers().firstValue("Location").orElse("").substringAfter("redirect="), Charsets.UTF_8))
        assertEquals(404, get("/workops/releases/missing").statusCode())
        assertTrue(requests.all { it.second == "Bearer workops-session" })
    }

    private fun get(path: String, authenticated: Boolean = true, appearance: String = "dark"): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (authenticated) request.header("Cookie", "_workops_test=workops-session; git_ui_appearance=$appearance")
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val client = HttpClient.newHttpClient()
        private val requests = ConcurrentLinkedQueue<Pair<String, String?>>()
        private lateinit var upstream: HttpServer
        private lateinit var engine: NettyServerEngine
        private var port = 0

        @JvmStatic @BeforeClass fun start() {
            upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            upstream.createContext("/graphql") { exchange ->
                val input = Json.parseToJsonElement(exchange.requestBody.use { it.readAllBytes().decodeToString() }).jsonObject
                val operation = input.getValue("operationName").jsonPrimitive.content
                requests.add(operation to exchange.requestHeaders.getFirst("Authorization"))
                val source = input["variables"]?.jsonObject?.get("source")?.jsonPrimitive?.content.orEmpty()
                val data = when (operation) {
                    "WorkOpsValidateBql" -> if (source == "broken")
                        """{"workOps":{"savedFilters":{"validateBql":{"errors":[{"message":"Expected a value","start":0,"end":6,"hint":null}]}}}}"""
                    else """{"workOps":{"savedFilters":{"validateBql":{"errors":[]}}}}"""
                    "WorkOpsTaskFilters" -> """{"workOps":{"savedFilters":{"mine":[]}}}"""
                    "WorkOpsTaskSearch" -> """{"workOps":{"savedFilters":{"searchTasks":{"rows":[{"id":"task-1","key":"TEST-1","summary":"Task <script>","status":{"id":"status","name":"To Do","category":"TODO"},"priority":{"id":"priority","name":"Medium","displayOrder":2,"colorHex":"#91a6f6"},"taskType":{"id":"type","name":"Task"},"assignee":null,"dueDate":null}],"freeTextTerms":[]}}}}"""
                    "WorkOpsTaskCreateChoices" -> """{"workOps":{"projects":{"all":[{"id":"project","key":"TEST","name":"Test project","taskCreationFormSchemaKey":"workops.create-task"}]},"tasks":{"taskTypes":[],"priorities":[],"resolutions":[]},"statuses":{"all":[]}}}"""
                    "WorkOpsTaskCreateForm" -> """{"formSchemas":{"byKey":{"id":"form","key":"workops.create-task","name":"Create task","type":"WORK_OPS","version":1,"schema":{"type":"object","properties":{"projectId":{"type":"string"},"summary":{"type":"string"}},"required":["projectId","summary"]},"uiSchema":{"version":1,"layout":[{"type":"field","property":"projectId","control":"workops-select","workOpsEntity":"projects"},{"type":"field","property":"summary","control":"text-input"}]}}}}"""
                    "WorkOpsMilestonePrograms" -> """{"workOps":{"programs":{"all":[{"id":"program","key":"PLAN","name":"Planning program","archivedAt":null}]}}}"""
                    "WorkOpsMilestones" -> """{"workOps":{"milestones":{"byProgram":[{"id":"milestone","name":"Review <release>","description":"A commitment","targetDate":"2027-02-03T00:00:00Z","state":"OPEN","closedAt":null,"version":3}]}}}"""
                    "WorkOpsPlanningPortfolioChoices" -> """{"workOps":{"portfolios":{"all":[{"id":"portfolio","key":"PLAN","name":"Plan <portfolio>","archivedAt":null}]}}}"""
                    "WorkOpsPlanningProgramChoices" -> """{"workOps":{"programs":{"all":[{"id":"program","key":"PROGRAM","name":"Plan <program>","archivedAt":null}]}}}"""
                    "WorkOpsPlanningPortfolios" -> """{"workOps":{"portfolios":{"all":[{"id":"portfolio","key":"PLAN","name":"Plan <portfolio>","description":null,"ownerProfileId":"private-owner","owner":null,"archivedAt":null,"modifiedAt":"2026-11-14T00:00:00Z","version":3}]},"programs":{"all":[{"id":"program","portfolio":{"id":"portfolio"}}]}}}"""
                    "WorkOpsPlanningPrograms" -> """{"workOps":{"programs":{"all":[{"id":"program","key":"PROGRAM","name":"Plan <program>","description":null,"ownerProfileId":"private-owner","owner":null,"portfolio":{"id":"portfolio"},"archivedAt":null,"startDate":null,"targetDate":null,"modifiedAt":"2026-11-14T00:00:00Z","version":3}]}}}"""
                    "WorkOpsPlanningProjects" -> """{"workOps":{"projects":{"all":[{"id":"project","key":"PROJECT","name":"Plan <project>","description":null,"ownerProfileId":"private-owner","owner":null,"program":{"id":"program"},"archivedAt":null,"modifiedAt":"2026-11-14T00:00:00Z","version":3,"taskCreationFormSchemaKey":"custom-intake","defaultTaskTypeSchemeId":"custom-scheme"}]}}}"""
                    "WorkOpsBoardProjects" -> """{"workOps":{"projects":{"all":[{"id":"project","key":"PLAN","name":"Planning project","archivedAt":null}]}}}"""
                    "WorkOpsBoardsForProject" -> """{"workOps":{"boards":{"byProject":[{"id":"board","name":"Board <planning>","type":"KANBAN","projectId":"project","programId":null,"portfolioId":null,"projects":[{"id":"project"}]}],"byProjects":[]}}}"""
                    "WorkOpsBoardView" -> """{"workOps":{"boards":{"board":{"id":"board","name":"Board <planning>","type":"KANBAN","swimlaneStrategy":"NONE","projectId":"project","programId":null,"portfolioId":null,"projects":[{"id":"project"}],"columns":[{"id":"todo","name":"To do","displayOrder":1,"statusIds":["todo-status"],"wipLimit":1}]}},"projects":{"all":[{"id":"project","key":"PLAN","name":"Planning project","archivedAt":null,"program":{"id":"program","portfolio":{"id":"portfolio"}}}]},"tasks":{"resolutions":[]}}}"""
                    "WorkOpsBoardViewSprints" -> """{"workOps":{"sprints":{"byBoard":[]}}}"""
                    "WorkOpsBoardCards" -> """{"workOps":{"savedFilters":{"searchTasks":{"rows":[{"id":"card","key":"PLAN-1","summary":"Task <card>","version":1,"assigneeProfileId":null,"assignee":null,"project":{"id":"project"},"priority":{"name":"Medium"},"status":{"id":"todo-status","name":"To do","category":"TODO"},"transitions":[]}]}}}}"""
                    "WorkOpsBoardConfiguration" -> """{"workOps":{"boards":{"board":{"id":"board","name":"Board <planning>","type":"KANBAN","swimlaneStrategy":"NONE","version":5,"projectId":"project","programId":null,"portfolioId":null,"projects":[{"id":"project"}],"columns":[{"id":"todo","name":"To do","displayOrder":1,"statusIds":["todo-status"],"wipLimit":1}]}},"projects":{"all":[{"id":"project","key":"PLAN","name":"Planning project","archivedAt":null}]}}}"""
                    "WorkOpsBoardColumnStatuses" -> """{"workOps":{"statuses":{"all":[{"id":"todo-status","name":"To do","category":"TODO"}]}}}"""
                    "WorkOpsSprintBoard" -> """{"workOps":{"boards":{"board":{"id":"board","name":"Board <planning>","type":"SCRUM"}}}}"""
                    "WorkOpsSprintInfo", "WorkOpsSprintsForBoard" -> {
                        val sprint = """{"id":"sprint","name":"Sprint <iteration>","state":"FUTURE","goal":"A sprint goal","startDate":null,"endDate":null,"completeDate":null,"committedTaskIds":["member"],"addedDuringSprintTaskIds":[],"velocityPoints":null,"boardId":"board","version":5,"createdAt":"2026-11-01T00:00:00Z","modifiedAt":"2026-11-02T00:00:00Z","board":{"id":"board","name":"Board <planning>","type":"SCRUM"}}"""
                        if (operation == "WorkOpsSprintInfo") """{"workOps":{"sprints":{"sprint":$sprint}}}"""
                        else """{"workOps":{"sprints":{"byBoard":[$sprint]}}}"""
                    }
                    "WorkOpsSprintTaskInfo" -> """{"workOps":{"tasks":{"task":{"id":"member","key":"PLAN-1","summary":"Sprint task <safe>","version":3,"sprintId":"sprint","assigneeProfileId":"private-profile","assignee":null,"status":{"id":"todo-status","name":"To do","category":"TODO"},"priority":{"name":"Medium","colorHex":"#334455"}}}}}"""
                    "WorkOpsProjectDetailInfo" -> """{"workOps":{"projects":{"project":{"id":"project","key":"PLAN","name":"Project <planning>","description":"Project details","ownerProfileId":"private-profile","owner":null,"program":{"id":"program"},"archivedAt":null,"createdAt":"2026-11-01T00:00:00Z","modifiedAt":"2026-11-02T00:00:00Z","version":7}},"programs":{"all":[{"id":"program","key":"PLAN","name":"Planning program","archivedAt":null}]}}}"""
                    "WorkOpsReleasePrograms" -> """{"workOps":{"programs":{"all":[{"id":"program","key":"REL","name":"Release program"}]}}}"""
                    "WorkOpsReleaseDetail" -> if (input["variables"]?.jsonObject?.get("id")?.jsonPrimitive?.content == "missing") """{"workOps":{"crossProject":{"release":null}}}""" else """{"workOps":{"crossProject":{"release":{"id":"release","programId":"program","name":"Release <bundle>","description":"Release notes","releaseDate":"2026-12-15T00:00:00Z","releasedAt":null,"version":3}}}}"""
                    "WorkOpsReleaseComponents" -> """{"workOps":{"crossProject":{"versionsForRelease":[{"releaseId":"release","projectId":"project","versionId":"delivery-version","deploymentOrder":1,"deploymentStatus":"DEPLOYED","deployedAt":"2026-12-01T00:00:00Z","rollbackVersionId":null},{"releaseId":"release","projectId":"private-project","versionId":"private-version","deploymentOrder":null,"deploymentStatus":"PENDING","deployedAt":null,"rollbackVersionId":null}]}}}"""
                    "WorkOpsReleaseReadiness" -> """{"workOps":{"crossProject":{"releaseReadiness":["Provider <dependency>"]}}}"""
                    "WorkOpsReleaseAccess" -> """{"workOps":{"crossProject":{"releaseActionAccess":{"canManage":true}}}}"""
                    "WorkOpsReleaseEnvironments" -> """{"workOps":{"multiRepo":{"environments":[]}}}"""
                    "WorkOpsReleaseDeclaredArtifacts" -> """{"workOps":{"crossProject":{"releaseDeclaredArtifacts":[{"projectId":"project","type":"DOCKER","namespace":"images","coordinate":"container:<version>","environments":["production"]}]}}}"""
                    "WorkOpsReleaseVersionArtifacts" -> """{"workOps":{"multiRepo":{"artifacts":[]}}}"""
                    "WorkOpsReleaseVersionNotes" -> """{"workOps":{"multiRepo":{"versionReleaseNotes":[{"id":"note","versionId":"delivery-version","sourceLocale":"en-US","generatedAt":null,"manuallyEdited":true,"variants":[{"locale":"en-US","playReleaseNotes":"Play <notes>","appStoreWhatsNew":"Store notes","testFlightWhatToTest":"Test notes"}]}]}}}"""
                    "WorkOpsReleasePlans" -> """{"workOps":{"crossProject":{"releasePlans":[]}}}"""
                    "WorkOpsReleaseExecutionAccess" -> """{"workOps":{"crossProject":{"releaseActionAccess":{"canManage":true,"repositories":[{"repositoryId":"repository","canEdit":true,"canExecute":true}],"environments":[]}}}}"""
                    "WorkOpsReleaseRunSummaries" -> """{"workOps":{"crossProject":{"releaseRuns":[{"runId":"native-run","status":"SUCCESS","startedAt":"2026-12-01T00:00:00Z","finishedAt":null,"durationMs":null}]}}}"""
                    "WorkOpsReleaseNativeRun" -> """{"git":{"pipelineRun":{"id":"native-run","pipelineId":"pipeline","repositoryId":"repository","ref":"refs/heads/main","triggerType":"RELEASE","status":"SUCCESS","number":1,"parameters":{},"created":"2026-12-01T00:00:00Z","started":null,"finished":null,"durationSeconds":null,"jobs":[]}}}"""
                    "WorkOpsReleaseList" -> """{"workOps":{"crossProject":{"releases":[{"id":"release","programId":"program","name":"Release <bundle>","description":"Release notes","releaseDate":"2026-12-15T00:00:00Z","releasedAt":null,"version":3}]}}}"""
                    "WorkOpsReleaseRunStatuses" -> """{"workOps":{"crossProject":{"releaseRuns":[{"runId":"native-run","status":"SUCCESS","startedAt":"2026-12-01T00:00:00Z"}]}}}"""
                    "WorkOpsEnvironmentPrograms" -> """{"workOps":{"programs":{"all":[{"id":"program","key":"DEP","name":"Program <deployment>"}]}}}"""
                    "WorkOpsProgramEnvironments" -> """{"workOps":{"multiRepo":{"environments":[{"id":"environment","key":"preview-key","name":"Preview <target>","description":"Deployment target","displayOrder":1,"requiresApproval":true,"autoPromote":false,"typeId":"testing","promotionSourceIds":["private-source"],"version":3,"permissions":[]}],"environmentTypes":[{"id":"testing","name":"Testing","description":null,"displayOrder":2,"version":3}]}}}"""
                    "WorkOpsEnvironmentTypes" -> """{"workOps":{"multiRepo":{"environmentTypes":[{"id":"testing","name":"Testing <stage>","description":"Environment details","displayOrder":2,"version":3}]}}}"""
                    "WorkOpsVersionProjects" -> """{"workOps":{"projects":{"all":[{"id":"project","key":"DEL","name":"Project <delivery>"}]}}}"""
                    "WorkOpsDeliveryVersions" -> """{"workOps":{"versions":{"byProject":[{"id":"delivery-version","name":"Version <delivery>","description":"Delivery notes","released":false,"archived":false,"startDate":"2026-12-01T00:00:00Z","releaseDate":"2026-12-15T00:00:00Z","sequenceNumber":4,"version":3}]}}}"""
                    "WorkOpsProjectCatalogs" -> """{"workOps":{"versions":{"byProject":[{"id":"version","name":"Version <release>","description":"Version details","released":false,"archived":false,"startDate":null,"releaseDate":null,"sequenceNumber":1,"version":3}]},"components":{"byProject":[{"id":"component","name":"API <component>","description":"Component details","assigneeMode":"PROJECT_DEFAULT","version":2}]},"labels":{"byProject":[{"id":"label","name":"label-safe","colorHex":"#123456","version":1}]}}}"""
                    "WorkOpsProjectRecentTasks" -> """{"workOps":{"savedFilters":{"searchTasks":{"rows":[{"id":"recent","key":"PLAN-2","summary":"Project task <safe>","version":3,"sprintId":null,"assigneeProfileId":"private-profile","assignee":null,"status":{"id":"todo-status","name":"To do","category":"TODO"},"priority":{"name":"Medium","colorHex":"#334455"},"modifiedAt":"2026-11-02T00:00:00Z"}]}}}}"""
                    "WorkOpsProjectDependencies" -> """{"workOps":{"projects":{"all":[{"id":"provider","key":"API","name":"Dependency <provider>","archivedAt":null}]},"multiRepo":{"dependencies":[{"id":"dependency","consumerProjectId":"project","providerProjectId":"provider","providerVersionConstraint":"6.0.*","dependencyType":"BUILD","status":"CURRENT","version":3}],"consumers":[{"id":"consumer","consumerProjectId":"private-consumer","providerProjectId":"project","providerVersionConstraint":"*","dependencyType":"RUNTIME","status":"OUTDATED","version":2}]}}}"""
                    "WorkOpsProjectRepositories" -> """{"workOps":{"projects":{"project":{"id":"project","repositories":[{"id":"association","repositoryId":"repository"}]}}},"git":{"repositories":[{"id":"repository","name":"Repository <source>","slug":"source","archived":false}]},"profiles":{"current":[]}}"""
                    "WorkOpsProjectTelemetry" -> """{"workOps":{"projects":{"project":{"id":"project","analyticsApplications":[{"id":"application","applicationId":"app<application>"}],"analyticsServices":[{"id":"service","service":"api<service>"}]}}}}"""
                    else -> error("Unexpected fixture operation: $operation")
                }
                val bytes = "{\"data\":$data}".encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            upstream.start()
            val config = ApplicationConfig.load("bosca:\n  server:\n    drain-timeout-ms: 0\n".byteInputStream())
            val app = BoscaApplication(config, ownsServices = false)
            app.installAtlasUiServer("http://127.0.0.1:${upstream.address.port}/graphql", "_workops_test")
            app.freezeMiddleware()
            engine = NettyServerEngine(app, 0)
            thread(isDaemon = true, name = "workops-composed-ui-test") { engine.start() }
            repeat(200) {
                engine.boundPort?.let { port = it; return }
                Thread.sleep(25)
            }
            error("Atlas WorkOps UI fixture did not bind")
        }

        @JvmStatic @AfterClass fun stop() {
            if (::engine.isInitialized) engine.stopWithoutHalting()
            if (::upstream.isInitialized) upstream.stop(0)
        }
    }
}
