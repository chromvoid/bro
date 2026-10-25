package assistant.mobile.core.circle

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal const val CIRCLE_ME = "11111111-1111-4111-8111-111111111111"
internal const val CIRCLE_PEER = "55555555-5555-4555-8555-555555555555"
internal const val CIRCLE_DM = "22222222-2222-4222-8222-222222222222"
internal const val CIRCLE_CHAT = "33333333-3333-4333-8333-333333333333"
internal const val CIRCLE_SPACE = "44444444-4444-4444-8444-444444444444"
internal fun circleFixture(name: String): ByteArray = requireNotNull(
    CircleModelsTest::class.java.classLoader.getResourceAsStream("circle/$name.json"),
).use { it.readBytes() }

/** Synthetic API projections from web/src/sections/circle/api.ts and api.test.ts,
 * server/community/channels.py and service.py, and tests/integration/test_community.py.
 * Server-only additive fields are intentional; these fixtures contain no account data.
 */
class CircleModelsTest {
    @Test
    fun roleCapabilitiesMatchEveryWebRole() {
        assertEquals(CircleAbilities(true, true, true, true), abilities(CircleRole.Owner))
        assertEquals(CircleAbilities(true, false, false, true), abilities(CircleRole.Admin))
        assertEquals(CircleAbilities(false, false, false, false), abilities(CircleRole.Member))
        val owner = CircleMember("o", CircleRole.Owner, "Owner")
        val admin = CircleMember("a", CircleRole.Admin, "Admin")
        val member = CircleMember("m", CircleRole.Member, "Member")
        val otherAdmin = admin.copy(userId = "b")
        for (target in listOf(owner, admin, member, otherAdmin)) assertTrue(mayRemove(owner, target))
        assertTrue(mayRemove(admin, member))
        assertFalse(mayRemove(admin, owner))
        assertFalse(mayRemove(admin, otherAdmin))
        assertTrue(mayRemove(member, member))
        assertFalse(mayRemove(member, admin))
        assertTrue(soleOwner(listOf(owner, admin, member), owner.userId))
        assertFalse(soleOwner(listOf(owner, owner.copy(userId = "other")), owner.userId))
        assertFalse(soleOwner(listOf(owner, member), member.userId))
    }

    @Test
    fun receiptValidationBindsHumanAndAssistantContractsToOriginalAdmissionKey() {
        val key = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        val entry = Submission(AccountNamespace("test", "https://synthetic.invalid", CIRCLE_ME), key,
            CircleController.messagesPath(CIRCLE_DM), null, "POST", """{"text":"Synthetic"}""".encodeToByteArray(),
            "channel:$CIRCLE_DM", emptyMap(), CircleController.draftTarget(CIRCLE_DM), SubmissionState.RESOLVED, 100)
        validateCircleSubmissionReceipt(entry, """{"message_id":"m1","state":"sent","extra":true}""".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { validateCircleSubmissionReceipt(entry, """{"message_id":"m1","state":"queued"}""".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { validateCircleSubmissionReceipt(entry, """{"state":"sent"}""".encodeToByteArray()) }
        val assistant = entry.copy(body = """{"text":"Synthetic","assistant":true}""".encodeToByteArray())
        val receipt = """{"submission_key":"channel:$key","message_id":"m1","admitted_at":"2026-10-17T12:00:00Z","state":"queued"}"""
        validateCircleSubmissionReceipt(assistant, receipt.encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { validateCircleSubmissionReceipt(assistant, receipt.replace("channel:$key", "channel:another-key").encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { validateCircleSubmissionReceipt(assistant, receipt.replace("queued", "unknown").encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { validateCircleSubmissionReceipt(entry.copy(body = """{"text":"Synthetic","image_id":"private-photo"}""".encodeToByteArray()), """{"message_id":"m1","state":"sent"}""".encodeToByteArray()) }
    }
}
