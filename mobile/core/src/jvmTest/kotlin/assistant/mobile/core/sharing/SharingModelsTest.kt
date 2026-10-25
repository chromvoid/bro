package assistant.mobile.core.sharing

import assistant.mobile.core.*
import assistant.mobile.core.journal.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic wire fixtures from settings/sharing.ts, SharedView.vue, circle/api.ts,
 * server/community/sharing.py and tests/integration/test_community.py. */
class SharingModelsTest {
    @Test
    fun grantsUseStrictRevisionAndPermissionsAndScopesHideUnavailableSections() {
        val overview = decodeSharingOverview("""{"sections":[{"id":"r","name":"personal","owner_id":"a","revision":3}],"grants":[],"shared":[{"id":"n","name":"nutrition","owner_id":"b","owner_name":"B","permission":"summary"},{"id":"p","name":"personal","owner_id":"b","owner_name":"B","permission":"detail"}]}""".encodeToByteArray())
        val state = SharingState(AccountNamespace("synthetic", FEATURE_ORIGIN, "a"), overview = assistant.mobile.core.circle.CircleQuery(overview), scopes = setOf("personal.read"))
        assertEquals(listOf("p"), state.opened.map { it.id })
        assertFails { decodeSharingOverview("""{"sections":[{"id":"r","name":"personal","owner_id":"a","revision":"3"}],"grants":[]}""".encodeToByteArray()) }
        assertFailsWith<SerializationException> { decodeListGrants("""{"resource":{"id":"l","name":"L","member_access":"view","revision":1},"grants":[{"user_id":"b","name":"B","permission":"detail"}]}""".encodeToByteArray(), "l") }
        assertFails { decodeSharedProjection(sharingPersonal("detail"), "nutrition") }
    }

    @Test
    fun resourceReceiptMustAdvanceTheOriginalRevisionExactlyOnce() {
        val entry = Submission(AccountNamespace("synthetic", FEATURE_ORIGIN, "a"), "key", "/v1/resources/r/grants", null, "POST",
            """{"user_id":"b","permission":"summary","revision":2}""".encodeToByteArray(), "resource:r", mapOf("r" to "2"), null, SubmissionState.RESOLVED, 0)
        validateSharingSubmissionReceipt(entry, """{"revision":3}""".encodeToByteArray())
        for (body in listOf("{}", """{"revision":"3"}""", """{"revision":2}""", """{"revision":4}""")) {
            assertFails { validateSharingSubmissionReceipt(entry, body.encodeToByteArray()) }
        }
    }
}

internal fun sharingPersonal(permission: String): ByteArray = """{"section":"personal","permission":"$permission","data":{"date":"2026-10-16","metrics":{"weight":{"status":"available","value":"70.25","records":["Private evidence"]},"steps":{"status":"conflict","value":null},"nutrition":{"status":"available","value":"1800"}},"profile":{"height_cm":"170.5","birth_date":"1990-01-02"},"raw":"Private report"}}""".encodeToByteArray()
