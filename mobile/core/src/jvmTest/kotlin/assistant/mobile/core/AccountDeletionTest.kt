package assistant.mobile.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val DELETION_ORIGIN = "https://synthetic.invalid"
private const val DELETION_ID = "88888888-8888-4888-8888-888888888888"
private const val DELETION_KEY = "99999999-9999-4999-8999-999999999999"
private const val DELETION_SPACE = "66666666-6666-4666-8666-666666666666"
private const val DELETION_SUCCESSOR = "77777777-7777-4777-8777-777777777777"
private const val RECEIPT_TOKEN = "synthetic-receipt-token"
private const val CONFIRMATION_PASSWORD = "synthetic-memory-only-password"
private val DELETION_NOW = Instant.parse("2026-10-16T12:00:00Z").toEpochMilliseconds()

private val deletionOptions = """{"spaces":[{"id":"$DELETION_SPACE","name":"Synthetic space","successors":[{"id":"$DELETION_SUCCESSOR","name":"Synthetic successor"}]}],"last_admin":true,"warning":"Synthetic recovery warning"}"""
private val deletionReceipt = """{"deletion_id":"$DELETION_ID","status":"pending","receipt_token":"$RECEIPT_TOKEN","status_url":"/v1/account-deletions/$DELETION_ID"}"""

private class DeletionSessionSecrets(var record: StoredSession) : SessionSecretStore {
    var deleted = false
    override suspend fun load(instance: String, origin: String): StoredSession? = if (deleted) null else record
    override suspend fun save(session: StoredSession) { record = session; deleted = false }
    override suspend fun delete(instance: String, origin: String) { deleted = true }
}

private class MemoryDeletionSecrets : DeletionSecretStore {
    var record: StoredAccountDeletion? = null
    var failPending = false
    var failReceipt = false
    var failLoad = false
    val snapshots = mutableListOf<String>()
    override suspend fun load(instance: String, origin: String): StoredAccountDeletion? {
        check(!failLoad)
        return record
    }
    override suspend fun save(record: StoredAccountDeletion) {
        check(!(record.pending != null && failPending || record.receipt != null && failReceipt))
        this.record = record
        snapshots += NativeJson.encodeToString(record)
    }
    override suspend fun delete(instance: String, origin: String) { record = null }
}

private fun deletionSession(): StoredSession {
    val reply = decodeNativeSession(contractBytes("native-session.json"))
    return StoredSession(
        AccountNamespace("synthetic-instance", DELETION_ORIGIN, reply.user.id), reply.credential,
        reply.user.copy(scopes = reply.scopes), reply.device.id, reply.session.id, reply.expiresAt,
    )
}

private fun deletionBody(content: OutgoingContent) =
    NativeJson.parseToJsonElement((content as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

class AccountDeletionTest {
    @Test
    fun unreadableOrCorruptProofBlocksNewKeysUntilValidatedRestoreSucceeds() = runTest {
        for (corrupt in listOf(false, true)) {
            val sessionRecord = deletionSession()
            val sessionSecrets = DeletionSessionSecrets(sessionRecord)
            val original = StoredAccountDeletion(sessionRecord.namespace, pending = PendingAccountDeletion(
                sessionRecord.namespace, sessionRecord.credential, DELETION_KEY,
                mapOf(DELETION_SPACE to DELETION_SUCCESSOR),
            ))
            val secrets = MemoryDeletionSecrets().apply {
                record = if (corrupt) original.copy(namespace = original.namespace.copy(origin = "https://other.invalid"))
                    else original
                failLoad = !corrupt
            }
            val sessions = SessionManager("synthetic-instance", DELETION_ORIGIN, sessionSecrets, {}, { DELETION_NOW })
            var requests = 0
            var freshKeys = 0
            val http = NativeHttpClient(MockEngine { request ->
                requests++
                assertEquals("DELETE", request.method.value)
                assertEquals(DELETION_KEY, request.headers["Idempotency-Key"])
                val body = deletionBody(request.body)
                assertEquals("", body.getValue("password").jsonPrimitive.content)
                assertEquals(DELETION_SUCCESSOR, body.getValue("space_successors").jsonObject.getValue(DELETION_SPACE).jsonPrimitive.content)
                respond(deletionReceipt, HttpStatusCode.Accepted)
            }, DELETION_ORIGIN, sessions, { DELETION_NOW })
            try {
                sessions.restore()
                val controller = AccountDeletionController(sessions, http, secrets, {}, {
                    freshKeys++
                    DELETION_KEY
                })
                controller.restore()
                assertIs<HttpOutcome.StorageUnavailable>(controller.state.value.failure)
                controller.loadOptions()
                assertIs<HttpOutcome.StorageUnavailable>(controller.state.value.failure)
                controller.submit(CONFIRMATION_PASSWORD)
                assertIs<HttpOutcome.StorageUnavailable>(controller.state.value.failure)
                assertEquals(0, requests)
                assertEquals(0, freshKeys)
                assertTrue(secrets.snapshots.isEmpty())
                assertEquals(DELETION_KEY, secrets.record!!.pending!!.key)

                secrets.failLoad = false
                secrets.record = original
                controller.restore()
                assertTrue(controller.state.value.hasRecovery)
                controller.recover()
                assertEquals(1, requests)
                assertEquals(0, freshKeys)
                assertEquals(DeletionStatus.PENDING, controller.state.value.status)
            } finally { http.close() }
        }
    }

    @Test
    fun completedReceiptSurvivesFailedLocalPurgeUntilNextRestoreClearsAccountData() = runTest {
        val sessionSecrets = DeletionSessionSecrets(deletionSession())
        val secrets = MemoryDeletionSecrets()
        val sessions = SessionManager("synthetic-instance", DELETION_ORIGIN, sessionSecrets, {}, { DELETION_NOW })
        var purges = 0
        var unavailable = true
        val purge: suspend (AccountNamespace) -> Unit = {
            purges++
            check(!unavailable)
        }
        val http = NativeHttpClient(MockEngine { request ->
            when {
                request.url.encodedPath == "/v1/me/account-deletion-options" -> respond(deletionOptions)
                request.method.value == "DELETE" -> respond(deletionReceipt, HttpStatusCode.Accepted)
                else -> respond("""{"deletion_id":"$DELETION_ID","status":"completed"}""")
            }
        }, DELETION_ORIGIN, sessions, { DELETION_NOW })
        try {
            sessions.restore()
            var controller = AccountDeletionController(sessions, http, secrets, purge, { DELETION_KEY })
            controller.loadOptions()
            controller.submit(CONFIRMATION_PASSWORD)
            controller.refreshStatus()
            assertEquals(DeletionStatus.COMPLETED, controller.state.value.status)
            assertEquals(DeletionStatus.COMPLETED, secrets.record!!.receipt!!.status)
            assertEquals(1, purges)

            unavailable = false
            controller = AccountDeletionController(sessions, http, secrets, purge)
            controller.restore()
            assertEquals(2, purges)
            assertEquals(DeletionStatus.COMPLETED, controller.state.value.status)
            assertNull(controller.state.value.failure)
            assertNull(secrets.record)
        } finally { http.close() }
    }

    @Test
    fun admissionProtectsPasswordFreeProofBeforeHttpAndReplacesItWithReceiptBeforePurging() = runTest {
        val sessionSecrets = DeletionSessionSecrets(deletionSession())
        val secrets = MemoryDeletionSecrets()
        val sessions = SessionManager("synthetic-instance", DELETION_ORIGIN, sessionSecrets, {}, { DELETION_NOW })
        var deletes = 0
        val http = NativeHttpClient(MockEngine { request ->
            if (request.method.value == "GET") respond(deletionOptions)
            else {
                deletes++
                assertEquals(DELETION_KEY, request.headers["Idempotency-Key"])
                assertEquals("Bearer ${sessionSecrets.record.credential}", request.headers["Authorization"])
                assertEquals(DELETION_KEY, secrets.record!!.pending!!.key)
                assertTrue(secrets.snapshots.none { CONFIRMATION_PASSWORD in it })
                val body = deletionBody(request.body)
                assertEquals(CONFIRMATION_PASSWORD, body.getValue("password").jsonPrimitive.content)
                assertEquals(DELETION_SUCCESSOR, body.getValue("space_successors").jsonObject.getValue(DELETION_SPACE).jsonPrimitive.content)
                respond(deletionReceipt, HttpStatusCode.Accepted)
            }
        }, DELETION_ORIGIN, sessions, { DELETION_NOW })
        val purged = mutableListOf<AccountNamespace>()
        val controller = AccountDeletionController(sessions, http, secrets, {
            assertNull(sessions.lease())
            assertNull(secrets.record!!.pending)
            assertEquals(RECEIPT_TOKEN, secrets.record!!.receipt!!.receiptToken)
            purged += it
        }, { DELETION_KEY })
        try {
            sessions.restore()
            controller.loadOptions()
            assertTrue(controller.state.value.options!!.lastAdmin)
            controller.selectSuccessor(DELETION_SPACE, DELETION_SUCCESSOR)
            controller.submit(CONFIRMATION_PASSWORD)
            assertEquals(1, deletes)
            assertEquals(listOf(sessionSecrets.record.namespace), purged)
            assertEquals(DeletionStatus.PENDING, controller.state.value.status)
            assertFalse(controller.state.value.hasRecovery)
            assertNull(controller.state.value.failure)
            assertNull(sessions.lease())
            assertTrue(sessionSecrets.deleted)
            assertTrue(secrets.snapshots.none { CONFIRMATION_PASSWORD in it })
            assertFalse(secrets.snapshots.last().contains(sessionSecrets.record.credential))
            assertFalse(secrets.record.toString().contains(RECEIPT_TOKEN))
        } finally { http.close() }
    }

    @Test
    fun lostAdmissionRecoversAfterSessionExpiryWithSameKeyChoicesAndEmptyPassword() = runTest {
        val sessionSecrets = DeletionSessionSecrets(deletionSession())
        val secrets = MemoryDeletionSecrets()
        val sessions = SessionManager("synthetic-instance", DELETION_ORIGIN, sessionSecrets, {}, { DELETION_NOW })
        var deletes = 0
        var statusGets = 0
        val http = NativeHttpClient(MockEngine { request ->
            when {
                request.url.encodedPath == "/v1/me/account-deletion-options" -> respond(deletionOptions)
                request.method.value == "DELETE" -> {
                    deletes++
                    assertEquals(DELETION_KEY, request.headers["Idempotency-Key"])
                    assertEquals("Bearer ${sessionSecrets.record.credential}", request.headers["Authorization"])
                    assertFalse(request.url.toString().contains(sessionSecrets.record.credential))
                    val body = deletionBody(request.body)
                    assertEquals(if (deletes == 1) CONFIRMATION_PASSWORD else "", body.getValue("password").jsonPrimitive.content)
                    assertEquals(DELETION_SUCCESSOR, body.getValue("space_successors").jsonObject.getValue(DELETION_SPACE).jsonPrimitive.content)
                    if (deletes == 1) error("Synthetic lost response")
                    respond(deletionReceipt, HttpStatusCode.Accepted)
                }
                else -> {
                    statusGets++
                    assertEquals("/v1/account-deletions/$DELETION_ID", request.url.encodedPath)
                    assertEquals("Bearer $RECEIPT_TOKEN", request.headers["Authorization"])
                    assertNull(request.headers["X-Expected-User"])
                    assertNull(request.headers["Cookie"])
                    assertTrue(request.url.parameters.isEmpty())
                    respond("""{"deletion_id":"$DELETION_ID","status":"completed","created_at":"2026-10-16T12:00:00Z","completed_at":"2026-10-16T12:01:00Z"}""")
                }
            }
        }, DELETION_ORIGIN, sessions, { DELETION_NOW })
        try {
            sessions.restore()
            var controller = AccountDeletionController(sessions, http, secrets, {}, { DELETION_KEY })
            controller.loadOptions()
            controller.selectSuccessor(DELETION_SPACE, DELETION_SUCCESSOR)
            controller.submit(CONFIRMATION_PASSWORD)
            assertIs<HttpOutcome.NetworkFailure>(controller.state.value.failure)
            assertTrue(controller.state.value.hasRecovery)
            sessions.expire(sessions.lease()!!)
            controller = AccountDeletionController(sessions, http, secrets, {})
            controller.restore()
            assertTrue(controller.state.value.hasRecovery)
            controller.recover()
            assertEquals(2, deletes)
            assertEquals(DeletionStatus.PENDING, controller.state.value.status)
            controller.refreshStatus()
            assertEquals(1, statusGets)
            assertEquals(DeletionStatus.COMPLETED, controller.state.value.status)
            assertNull(secrets.record)
            controller.acknowledgeCompleted()
            assertNull(controller.state.value.status)
            assertTrue(secrets.snapshots.none { CONFIRMATION_PASSWORD in it })
        } finally { http.close() }
    }

    @Test
    fun explicitReconfirmationAfterUncertainSendKeepsOriginalKeyAndSuccessionChoices() = runTest {
        val sessionSecrets = DeletionSessionSecrets(deletionSession())
        val secrets = MemoryDeletionSecrets()
        val sessions = SessionManager("synthetic-instance", DELETION_ORIGIN, sessionSecrets, {}, { DELETION_NOW })
        var deletes = 0
        val http = NativeHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/v1/me" && request.method.value == "GET")
                respond(NativeJson.encodeToString(sessionSecrets.record.identity))
            else if (request.method.value == "GET") respond(deletionOptions)
            else {
                deletes++
                assertEquals(DELETION_KEY, request.headers["Idempotency-Key"])
                val body = deletionBody(request.body)
                assertEquals(DELETION_SUCCESSOR, body.getValue("space_successors").jsonObject.getValue(DELETION_SPACE).jsonPrimitive.content)
                when (deletes) {
                    1 -> error("Synthetic request never arrived")
                    2 -> {
                        assertEquals("", body.getValue("password").jsonPrimitive.content)
                        respond("", HttpStatusCode.Forbidden)
                    }
                    else -> {
                        assertEquals(CONFIRMATION_PASSWORD, body.getValue("password").jsonPrimitive.content)
                        respond(deletionReceipt, HttpStatusCode.Accepted)
                    }
                }
            }
        }, DELETION_ORIGIN, sessions, { DELETION_NOW })
        try {
            sessions.restore()
            val controller = AccountDeletionController(sessions, http, secrets, {}, { DELETION_KEY })
            controller.loadOptions()
            controller.selectSuccessor(DELETION_SPACE, DELETION_SUCCESSOR)
            controller.submit(CONFIRMATION_PASSWORD)
            controller.recover()
            assertTrue(controller.state.value.hasRecovery)
            controller.selectSuccessor(DELETION_SPACE, null)
            controller.submit(CONFIRMATION_PASSWORD)
            assertEquals(3, deletes)
            assertEquals(DeletionStatus.PENDING, controller.state.value.status)
            assertTrue(secrets.snapshots.none { CONFIRMATION_PASSWORD in it })
        } finally { http.close() }
    }

    @Test
    fun receiptStorageFailureKeepsOriginalProofForPasswordFreeRecovery() = runTest {
        val sessionSecrets = DeletionSessionSecrets(deletionSession())
        val secrets = MemoryDeletionSecrets().apply { failReceipt = true }
        val sessions = SessionManager("synthetic-instance", DELETION_ORIGIN, sessionSecrets, {}, { DELETION_NOW })
        var deletes = 0
        val http = NativeHttpClient(MockEngine { request ->
            if (request.method.value == "GET") respond(deletionOptions)
            else {
                deletes++
                if (deletes == 2) assertEquals("", deletionBody(request.body).getValue("password").jsonPrimitive.content)
                respond(deletionReceipt, HttpStatusCode.Accepted)
            }
        }, DELETION_ORIGIN, sessions, { DELETION_NOW })
        try {
            sessions.restore()
            val controller = AccountDeletionController(sessions, http, secrets, {}, { DELETION_KEY })
            controller.loadOptions()
            controller.submit(CONFIRMATION_PASSWORD)
            assertIs<HttpOutcome.StorageUnavailable>(controller.state.value.failure)
            assertEquals(DELETION_KEY, secrets.record!!.pending!!.key)
            secrets.failReceipt = false
            sessions.expire(sessions.lease()!!)
            controller.recover()
            assertEquals(2, deletes)
            assertEquals(DeletionStatus.PENDING, controller.state.value.status)
        } finally { http.close() }
    }

    @Test
    fun anotherAccountCannotSeeOrRecoverPreviousAccountsDeletionProof() = runTest {
        val recordA = deletionSession()
        val sessionSecrets = DeletionSessionSecrets(recordA.copy(
            namespace = recordA.namespace.copy(userId = DELETION_SUCCESSOR),
            identity = recordA.identity.copy(id = DELETION_SUCCESSOR),
        ))
        val secrets = MemoryDeletionSecrets().apply {
            record = StoredAccountDeletion(recordA.namespace, pending = PendingAccountDeletion(
                recordA.namespace, recordA.credential, DELETION_KEY, emptyMap(),
            ))
        }
        val sessions = SessionManager("synthetic-instance", DELETION_ORIGIN, sessionSecrets, {}, { DELETION_NOW })
        var requests = 0
        val http = NativeHttpClient(MockEngine { requests++; respond(deletionReceipt) }, DELETION_ORIGIN, sessions, { DELETION_NOW })
        try {
            sessions.restore()
            val controller = AccountDeletionController(sessions, http, secrets, {})
            controller.restore()
            assertFalse(controller.state.value.hasRecovery)
            assertNull(controller.state.value.options)
            assertNull(controller.state.value.status)
            controller.recover()
            assertEquals(0, requests)
            assertIs<HttpOutcome.RejectedRequest>(controller.state.value.failure)
        } finally { http.close() }
    }

    @Test
    fun optionsFromPreviousLeaseCannotAuthorizeDeletionOfNewAccount() = runTest {
        val recordA = deletionSession()
        val sessionSecrets = DeletionSessionSecrets(recordA)
        val sessions = SessionManager("synthetic-instance", DELETION_ORIGIN, sessionSecrets, {}, { DELETION_NOW })
        var deletes = 0
        val http = NativeHttpClient(MockEngine { request ->
            if (request.method.value == "DELETE") deletes++
            respond(deletionOptions)
        }, DELETION_ORIGIN, sessions, { DELETION_NOW })
        try {
            sessions.restore()
            val controller = AccountDeletionController(sessions, http, MemoryDeletionSecrets(), {})
            controller.loadOptions()
            sessionSecrets.record = recordA.copy(
                namespace = recordA.namespace.copy(userId = DELETION_SUCCESSOR),
                identity = recordA.identity.copy(id = DELETION_SUCCESSOR),
            )
            sessions.restore()
            controller.submit(CONFIRMATION_PASSWORD)
            assertEquals(0, deletes)
            assertIs<HttpOutcome.StaleSession>(controller.state.value.failure)
        } finally { http.close() }
    }

    @Test
    fun lateAdmissionFromPreviousAccountPurgesOnlyItsNamespaceAndPreservesNewSession() = runTest {
        val recordA = deletionSession()
        val sessionSecrets = DeletionSessionSecrets(recordA)
        val secrets = MemoryDeletionSecrets()
        val sessions = SessionManager("synthetic-instance", DELETION_ORIGIN, sessionSecrets, {}, { DELETION_NOW })
        val sent = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http = NativeHttpClient(MockEngine { request ->
            if (request.method.value == "GET") respond(deletionOptions)
            else {
                sent.complete(Unit)
                release.await()
                respond(deletionReceipt, HttpStatusCode.Accepted)
            }
        }, DELETION_ORIGIN, sessions, { DELETION_NOW })
        val purged = mutableListOf<AccountNamespace>()
        try {
            sessions.restore()
            val controller = AccountDeletionController(sessions, http, secrets, { purged += it })
            controller.loadOptions()
            val send = async { controller.submit(CONFIRMATION_PASSWORD) }
            sent.await()
            sessionSecrets.record = recordA.copy(
                namespace = recordA.namespace.copy(userId = DELETION_SUCCESSOR),
                identity = recordA.identity.copy(id = DELETION_SUCCESSOR),
            )
            sessions.restore()
            val leaseB = sessions.lease()!!
            release.complete(Unit)
            send.await()
            assertTrue(sessions.isCurrent(leaseB))
            assertEquals(listOf(recordA.namespace), purged)
            controller.restore()
            assertNull(controller.state.value.status)
            assertNull(controller.state.value.namespace)
            assertFalse(controller.state.value.hasRecovery)
        } finally { http.close() }
    }

    @Test
    fun untrustedReceiptRouteNeverReceivesAnyCredential() = runTest {
        val sessionSecrets = DeletionSessionSecrets(deletionSession())
        val secrets = MemoryDeletionSecrets()
        val sessions = SessionManager("synthetic-instance", DELETION_ORIGIN, sessionSecrets, {}, { DELETION_NOW })
        var requests = 0
        val http = NativeHttpClient(MockEngine { request ->
            requests++
            if (request.method.value == "GET") respond(deletionOptions)
            else respond(deletionReceipt.replace("/v1/account-deletions/$DELETION_ID", "https://other.invalid/status"), HttpStatusCode.Accepted)
        }, DELETION_ORIGIN, sessions, { DELETION_NOW })
        try {
            sessions.restore()
            val controller = AccountDeletionController(sessions, http, secrets, {}, { DELETION_KEY })
            controller.loadOptions()
            controller.submit(CONFIRMATION_PASSWORD)
            assertIs<HttpOutcome.InvalidResponse>(controller.state.value.failure)
            assertTrue(controller.state.value.hasRecovery)
            controller.refreshStatus()
            assertEquals(2, requests)
            assertIs<HttpOutcome.RejectedRequest>(controller.state.value.failure)
        } finally { http.close() }
    }

}
