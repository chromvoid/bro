package assistant.mobile.core.challenges

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeJson
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal const val CHALLENGE_ID = "66666666-6666-4666-8666-666666666666"
internal const val CHALLENGE_SPACE = "44444444-4444-4444-8444-444444444444"
internal const val CHALLENGE_ME = "11111111-1111-4111-8111-111111111111"
internal const val CHALLENGE_PEER = "55555555-5555-4555-8555-555555555555"
internal fun challengeFixture(name: String): ByteArray = requireNotNull(
    ChallengeModelsTest::class.java.classLoader.getResourceAsStream("challenges/$name.json"),
).use { it.readBytes() }

/** Synthetic projections of web circle/api.test.ts and tests/integration/test_challenges_multiuser.py. */
class ChallengeModelsTest {
    @Test
    fun serverRankingKeepsTiesAndAllowsOnlyPublicAggregatesAndOwnTerms() {
        val board = decodeChallengeBoard(challengeFixture("server-board"), CHALLENGE_ID)
        assertEquals(Participation.Active, board.participation)
        assertEquals(ChallengeTerms("2000", "UTC"), board.terms)
        assertEquals(listOf(1, 1), board.ranking.map { it.place })
        assertEquals(listOf(2, 2), board.ranking.map { it.days })
        // Additive private-looking fields cannot become part of a ranking row or own terms.
        val root = NativeJson.parseToJsonElement(challengeFixture("server-board").decodeToString()).jsonObject
        val extra = JsonObject(root + ("ranking" to JsonArray(root.getValue("ranking").jsonArray.map { row ->
            JsonObject(row.jsonObject + mapOf("calorie_target" to JsonPrimitive(9000), "energy_kcal" to JsonPrimitive(8999), "timezone" to JsonPrimitive("Private/Zone")))
        })))
        assertEquals(board, decodeChallengeBoard(extra.toString().encodeToByteArray(), CHALLENGE_ID))
    }

    @Test
    fun receiptsValidateTheExactActionWithoutInventingLookup() {
        val namespace = AccountNamespace("synthetic", "https://synthetic.invalid", CHALLENGE_ME)
        val entry = Submission(namespace, "ffffffff-ffff-4fff-8fff-ffffffffffff", "$CHALLENGES_PATH/$CHALLENGE_ID/join", null, "POST",
            "{}".encodeToByteArray(), "challenge:$CHALLENGE_ID", emptyMap(), null, SubmissionState.RESOLVED, 1)
        validateChallengeSubmissionReceipt(entry, "{\"joined\":true}".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { validateChallengeSubmissionReceipt(entry, "{\"joined\":false}".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { validateChallengeSubmissionReceipt(entry, "{\"joined\":\"true\"}".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { validateChallengeSubmissionReceipt(entry, "{}".encodeToByteArray()) }
        validateChallengeSubmissionReceipt(entry.copy(endpoint = "$CHALLENGES_PATH/$CHALLENGE_ID/leave"), "{\"joined\":false}".encodeToByteArray())
        val create = entry.copy(endpoint = CHALLENGES_PATH, body = createChallengeBody(CHALLENGE_SPACE, ChallengeDraft("Days", "2026-10-01", "2026-10-14")))
        validateChallengeSubmissionReceipt(create, "{\"id\":\"$CHALLENGE_ID\"}".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { validateChallengeSubmissionReceipt(create, "{}".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { validateChallengeSubmissionReceipt(create, "{\"id\":123}".encodeToByteArray()) }
    }
}
