package assistant.mobile.android.ambient

/**
 * When an account change is worth telling the person about. The page rebinds the recorder on every
 * sign-in, sign-out, reload and app start; an idle recorder with nothing queued has lost nothing,
 * so «session ended» shows only when a recording stopped or audio still waits for its session.
 */
object AmbientAccountPolicy {
    const val NOT_AUTHENTICATED = "not_authenticated"

    /** The account behind the recorder changed or signed out. */
    fun afterChange(interrupted: Boolean, queuedBatches: Int): String? =
        if (interrupted || queuedBatches > 0) NOT_AUTHENTICATED else null

    /** The page confirmed the same account: an idle recorder's stale session error goes away. */
    fun afterConfirmed(current: String?, recording: Boolean): String? =
        if (current == NOT_AUTHENTICATED && !recording) null else current

    /** A stored binding whose session has ended: an error only while it still owns queued audio. */
    fun afterExpiry(queuedBatches: Int): String? =
        if (queuedBatches > 0) NOT_AUTHENTICATED else null
}
