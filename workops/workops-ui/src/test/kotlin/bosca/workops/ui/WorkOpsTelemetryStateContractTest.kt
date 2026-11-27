package bosca.workops.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorkOpsTelemetryStateContractTest {
    private val source = """{"projectId":"project","projectLabel":"Application","identifier":"io.app","versionId":"version","versionName":"1.0"}"""
    private val sources = """{"id":"release","applications":[$source],"services":[$source],"unavailableProjects":2,
      "loaded":true,"feedback":{"message":"One source unavailable","failed":true}}"""
    private val counter = """{"value":5,"samples":[{"time":"2026-11-17T10:00:00Z","value":5}]}"""

    @Test fun `health state keeps application and service freshness independently`() {
        val model = assertWorkOpsSerialization(WorkOpsReleaseHealthModel.serializer(), """{"id":"release"}""", """{
          "id":"release","readContext":"revision-3","sources":$sources,
          "applications":[{"identifier":"io.app","projects":["project"],"sessions":$counter,"sessionsLoaded":true,
            "sessionsChecked":"2026-11-17T10:00:00Z","openGroups":3,"fatalGroups":1,"latestError":"2026-11-17T09:00:00Z",
            "errorsLoaded":true,"errorsChecked":"2026-11-17T10:01:00Z","sessionFeedback":{"message":"Session failed","failed":true},
            "errorFeedback":{"message":"Errors failed","failed":true}}],
          "services":[{"identifier":"api","projects":["project"],"success":$counter,"client":$counter,"server":$counter,
            "loaded":true,"checked":"2026-11-17T10:00:00Z","feedback":{"message":"HTTP failed","failed":true}}]
        }""")
        assertEquals("error", model.applications.single().tone)
        assertEquals(15L, model.services.single().total)
        assertEquals(2, model.sources.unavailableProjects)
    }

    @Test fun `live state keeps the explicit all-releases filter and stream revision`() {
        val model = assertWorkOpsSerialization(WorkOpsReleaseLiveModel.serializer(), """{"id":"release"}""", """{
          "id":"release","readContext":"revision-3","sources":$sources,"thisReleaseOnly":false,"expanded":true,"streamRevision":7
        }""")
        assertFalse(model.thisReleaseOnly)
        assertEquals(7L, model.streamRevision)
        assertEquals("io.app", model.sources.applications.single().identifier)
    }

    @Test fun `store health state retains target identities zero rollout and vendor observation bytes`() {
        val model = assertWorkOpsSerialization(WorkOpsReleaseStoreHealthModel.serializer(), """{"id":"release"}""", """{
          "id":"release","readContext":"revision-3","rows":[{
            "state":{"projectId":"project","versionId":"version","environmentId":"environment","environmentKey":"production",
              "store":"GOOGLE_PLAY","applicationId":"io.app","appVersion":"1.0","buildNumber":"42","track":"production","rolloutPercentage":0.0,
              "releaseState":"halted","reviewState":"WAITING_FOR_REVIEW","betaReviewState":"APPROVED","buildProcessingState":"VALID","phasedReleaseState":"ACTIVE",
              "testFlightGroups":["Internal"],"testFlightCrashFeedback":[{"id":"feedback","comment":"Authored feedback","email":null,
                "deviceModel":"Device","osVersion":"1","createdAt":"2026-11-17T10:00:00Z"}]},
            "projectLabel":"Application","observations":[{"store":"GOOGLE_PLAY","applicationId":"io.app","appVersion":"1.0",
              "type":"store.vitals","time":"2026-11-17T10:00:00Z","payload":{"crashRate":0.0,"anrRate":2,"unknown":{"retained":true}}}]
          }],"loaded":true,"checked":"2026-11-17T10:00:00Z","feedback":{"message":"Refresh failed","failed":true}
        }""")
        assertEquals("0.00%", model.rows.single().rolloutLabel)
        assertEquals("0.000%", model.rows.single().crashRateLabel)
    }

    @Test fun `store reviews preserve vendor text without treating unknown ratings as zero`() {
        val model = assertWorkOpsSerialization(WorkOpsStoreReview.serializer(), """{"payload":{}}""", """{
          "payload":{"rating":null,"body":"<authored review>","unknown":{"retained":true}}
        }""")
        assertEquals("Unavailable", model.ratingLabel)
        assertEquals("<authored review>", model.body)
    }
}
