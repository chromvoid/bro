package assistant.mobile.platform.ring

object RingDiagnosticsAccess {
    fun requireCaller(callerUid: Int, applicationUid: Int) {
        // ADB is explicitly authorized by the device owner. An app with DUMP alone is insufficient.
        if (callerUid != 0 && callerUid != 2000 && callerUid != applicationUid)
            throw SecurityException("Ring diagnostics are available only to ADB and this application")
    }
}
