package assistant.mobile.platform

import android.content.Context

/** Only device appearance is stored in PR01; there is no account or domain storage. */
class ThemePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun read(): String = preferences.getString("theme", "System") ?: "System"

    fun write(value: String) {
        require(value in setOf("System", "Light", "Dark"))
        preferences.edit().putString("theme", value).apply()
    }
}
