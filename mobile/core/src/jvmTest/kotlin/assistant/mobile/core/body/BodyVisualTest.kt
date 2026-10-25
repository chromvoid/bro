package assistant.mobile.core.body

import assistant.mobile.core.NativeJson
import assistant.mobile.core.personal.PersonalAnalysisSummary
import assistant.mobile.core.personal.PersonalDisplayUnits
import assistant.mobile.core.personal.PersonalObservation
import assistant.mobile.core.personal.decodePersonalObservation
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyVisualTest {
    @Test
    fun originalProviderIdentityAndTimeSurviveCorrectionsAndJoinRawOnlyArchives() {
        val row = NativeJson.parseToJsonElement("""{"id":"corrected","kind":"body_fat_percent","data":{"value":"25"},"start_at":"2026-10-17T12:00:00Z","end_at":"2026-10-17T12:00:00Z","timezone":"UTC","method":"device","revision":2,"source_id":"source","source_name":"Synthetic scan","origin":{"provider":"ddx","record_id":"changed"},"original":{"start_at":"2026-10-16T10:00:00Z","origin":{"provider":"ddx","record_id":"provider-1"}},"override_revision":3}""").jsonObject
        val record = decodePersonalObservation(row)
        assertEquals("provider-1", record.providerRecordId); assertEquals(3L, record.overrideRevision)
        val archive = PersonalAnalysisSummary("archive", "source", "Synthetic scan", "provider-1", "2026-10-16T10:00:00Z", 1, "2026-10-17T10:00:00Z")
        val scans = bodyAnalyses(listOf(record), listOf(archive, archive.copy(id = "raw", providerRecordId = "raw-only")))
        assertEquals(2, scans.size)
        assertEquals("2026-10-16T10:00:00Z", scans.single { it.records.isNotEmpty() }.measuredAt)
        assertEquals("archive", scans.single { it.records.isNotEmpty() }.archive?.id)
        assertTrue(scans.single { it.archive?.id == "raw" }.values.isEmpty())
        val fromEvidence = bodyAnalyses(listOf(record.copy(providerRecordId = null, originalProviderRecordId = null, originalStartAt = null)), listOf(archive), mapOf(record.id to row))
        assertEquals(1, fromEvidence.size)
    }

    @Test
    fun deltasUsePercentagePointsUnitsAndMatchingMethodsWithoutJudgingATarget() {
        val now = observation("now", value = "24")
        val old = observation("old", value = "25")
        assertEquals("−1 п. п.", bodyDelta(now, old))
        assertEquals("+2.2 lb", bodyDelta(now.copy(kind = "weight", value = "81"), old.copy(kind = "weight", value = "80"), PersonalDisplayUnits.Imperial, "en"))
        assertNull(bodyDelta(now, old.copy(sourceId = "other")))
        assertNull(bodyDelta(now, old.copy(excluded = true)))
        assertNull(dashboardBodyDelta(now, old.copy(method = "derived")))
        assertNull(dashboardBodyDelta(now, old.copy(algorithmVersion = "other")))
    }
}

internal fun observation(id: String, kind: String = "body_fat_percent", value: String? = "25", at: String = "2026-10-17T10:00:00Z") =
    PersonalObservation(id, kind, value, at, at, "UTC", "device", 1, "source", "Synthetic body scan")
