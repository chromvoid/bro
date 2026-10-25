package assistant.mobile.android.glasses

import java.util.Locale

internal data class G2TouchState(val page: Int = 0, val taps: Int = 0, val lastAction: G2InputType? = null)

/** The stock sample uses ASCII digits independently of the phone's display locale. */
internal fun G2TouchState.sampleText(pattern: String): String = String.format(Locale.ROOT, pattern, page + 1, taps)

/** Demo state and duplicate filtering are independent of Android and the wire codec. */
internal class G2TouchDemo {
    var state = G2TouchState()
        private set
    private val lastAccepted = mutableMapOf<G2InputType, Long>()

    fun accept(input: G2Input): Boolean {
        val type = input.type
        if (type !in listOf(G2InputType.Click, G2InputType.Up, G2InputType.Down, G2InputType.DoubleClick)) return false
        val previous = lastAccepted[type]
        if (previous != null && input.receivedAtMillis - previous < 350) return false
        lastAccepted[type] = input.receivedAtMillis
        state = when (type) {
            G2InputType.Click -> state.copy(taps = state.taps + 1)
            G2InputType.Up -> state.copy(page = (state.page + 2) % 3)
            G2InputType.Down -> state.copy(page = (state.page + 1) % 3)
            G2InputType.DoubleClick -> state.copy(page = 0)
            else -> state
        }.copy(lastAction = type)
        return true
    }
}
