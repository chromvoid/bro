package assistant.mobile.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Scene-owned permission launchers; Bluetooth denial never prevents a microphone-only call. */
@Composable
internal fun CallPermissions(microphone: DictationPermissionBridge, bluetooth: DictationPermissionBridge) {
    val context = LocalContext.current
    val owner = remember { Any() }
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        microphone.result(granted(Manifest.permission.RECORD_AUDIO))
    }
    val route = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), bluetooth::result)
    DisposableEffect(microphone, bluetooth, context) {
        microphone.attach(owner) {
            val permissions = buildList {
                if (!granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            if (permissions.isEmpty()) microphone.result(true) else capture.launch(permissions.toTypedArray())
        }
        bluetooth.attach(owner) {
            if (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)) bluetooth.result(true)
            else route.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        onDispose { microphone.detach(owner); bluetooth.detach(owner) }
    }
}
