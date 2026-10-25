package assistant.mobile.android.glasses

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.android.R

/** No account, network, WebView, cookie or bridge participates in the local G2 connection. */
@Composable
internal fun EvenG2Route(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().first()
    }
    val model = remember(activity) { ViewModelProvider(activity)[EvenG2ViewModel::class.java] }
    val state by model.state.collectAsStateWithLifecycle()
    val bluetoothDisabled = stringResource(R.string.g2_bluetooth_disabled)
    val bluetoothNotSupported = stringResource(R.string.g2_bluetooth_not_supported)
    val permissionSettings = stringResource(R.string.g2_permission_settings)
    val permissionDenied = stringResource(R.string.g2_permission_denied)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.background() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.disconnect()
        }
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (model.hasPermissions() && model.bluetoothEnabled()) model.scan()
        else model.message(bluetoothDisabled)
    }
    val requestBluetooth = {
        try { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        catch (_: ActivityNotFoundException) { model.message(bluetoothNotSupported) }
        catch (_: SecurityException) { model.message(permissionSettings) }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when {
            !model.hasPermissions() -> model.message(permissionDenied)
            !model.bluetoothEnabled() -> requestBluetooth()
            else -> model.scan()
        }
    }
    val search = {
        when {
            !model.hasPermissions() -> permissions.launch(model.permissions())
            !model.bluetoothEnabled() -> requestBluetooth()
            else -> model.scan()
        }
    }
    BackHandler(onBack = onBack)
    EvenG2Screen(
        state = state,
        onSearch = search,
        onStopScan = model::stopScan,
        onSelect = model::select,
        onConnect = model::connect,
        onShowBounds = model::showBounds,
        onStartTouchTest = model::startTouchTest,
        onSendTest = model::sendTest,
        onDisconnect = { model.disconnect() },
        onBack = onBack,
        onSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        },
    )
}
