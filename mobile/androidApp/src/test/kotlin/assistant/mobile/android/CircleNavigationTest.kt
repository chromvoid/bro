package assistant.mobile.android

import org.junit.Assert.assertNull
import org.junit.Test

class CircleNavigationTest {
    @Test
    fun invalidOrOutOfScopeRoutesAreRejected() {
        listOf("", ".", "..", "a/b", "a\\b", "a?b", "a#b", "a\nb", "x".repeat(129)).forEach {
            assertNull(circleDestination(listOf("circle", "chat", it)))
            assertNull(circleDestination(listOf("circle", "spaces", it, "members")))
            assertNull(circleDestination(listOf("circle", "spaces", it, "challenges")))
            assertNull(circleDestination(listOf("circle", "challenges", it)))
            assertNull(sharedProjectionDestination(it, null))
        }
        listOf(listOf("circle", "shared", "extra"), listOf("circle", "challenges"),
            listOf("circle", "spaces", "one", "challenges", "extra"), listOf("circle", "chat", "one", "extra"),
            listOf("community", "extra")).forEach { assertNull(circleDestination(it)) }
    }
}
