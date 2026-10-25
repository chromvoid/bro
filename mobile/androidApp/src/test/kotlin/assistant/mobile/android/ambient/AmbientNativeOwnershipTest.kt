package assistant.mobile.android.ambient

import assistant.mobile.core.AccountNamespace
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmbientNativeOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private val namespace = AccountNamespace("synthetic-instance", "https://synthetic.invalid", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val native = AmbientBinding(namespace.origin, namespace.userId, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "2099-01-01T00:00:00Z")

    @Test fun persistedProofSurvivesReopeningButAuthorizesOnlyItsNativeDeviceAndNamespace() {
        val file = File(temporary.root, "native-owner.json")
        assertNull(AmbientNativeOwnershipStore(file).load())
        AmbientNativeOwnershipStore(file).save(AmbientNativeOwnership(namespace, native))
        val reopened = requireNotNull(AmbientNativeOwnershipStore(file).load())
        assertTrue(reopened.permitsCleanup(namespace, native))
        assertFalse(reopened.permitsCleanup(namespace.copy(instance = "another-instance"), native))
        assertFalse(reopened.permitsCleanup(namespace, native.copy(deviceId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc")))
        assertFalse(reopened.permitsCleanup(namespace, native.copy(expiresAt = "2099-01-02T00:00:00Z")))
        AmbientNativeOwnershipStore(file).clear()
        assertNull(AmbientNativeOwnershipStore(file).load())
    }
}
