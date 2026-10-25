package assistant.mobile.android

import android.content.Context
import android.content.Intent

/** Instrumentation seam exists only in debug bytecode, never as an app setting or intent extra. */
object NativeRuntimeFactory {
    var testFactory: ((Context) -> NativeRuntime)? = null
    var browserObserver: ((Intent) -> Boolean)? = null
    fun create(context: Context): NativeRuntime = testFactory?.invoke(context) ?: NativeRuntime(context)
    fun openBrowser(context: Context, intent: Intent) {
        if (browserObserver?.invoke(intent) != true) context.startActivity(intent)
    }
}
