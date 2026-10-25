package assistant.mobile.platform.ring

import android.content.Context

/** Runtime-only Android messages; component UI wording comes from generated web resources. */
internal object RingText {
    @Volatile private var application: Context? = null

    fun initialize(context: Context) {
        application = context.applicationContext
    }

    fun text(resource: Int, vararg arguments: Any?): String {
        // Pure protocol JVM tests have no Android Context and assert typed failure codes only.
        val context = application ?: return resource.toString()
        return context.getString(resource, *arguments)
    }
}
