package assistant.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedStringsTest {
    @Test
    fun typedMessagesPreserveNamedValuesAndTheirOrdering() {
        val shared = GeneratedStrings.Access.willSee(name = "Маша", login = "masha", section = "Питание")
        assertEquals("access.willSee", shared.key.webKey)
        assertEquals("access_will_see", shared.key.resourceName)
        assertEquals(listOf("name", "login", "section"), shared.key.parameters)
        assertEquals(listOf("Маша", "masha", "Питание"), shared.arguments)

        val zero = GeneratedStrings.Personal.SleepNight.awakeningCount(0.0, "0", "5 мин")
        assertEquals(4, zero.key.pluralForms)
        assertEquals(0, RussianPlural.index(zero.quantity!!, zero.key.pluralForms))
        assertEquals(listOf("0", "5 мин"), zero.arguments)
    }

}
