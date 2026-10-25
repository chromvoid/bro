package assistant.mobile.android

import android.content.Context
import android.content.Intent

object NativeRuntimeFactory {
    fun create(context: Context): NativeRuntime = NativeRuntime(context)
    fun openBrowser(context: Context, intent: Intent) = context.startActivity(intent)
}
