package assistant.mobile.android.glasses

import assistant.mobile.android.R
import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class G2Phase { Idle, Scanning, Connecting, Connected, Sending, Disconnecting }
internal enum class G2Test { Text, Touch, Bounds }
internal data class G2Ui(
    val phase: G2Phase = G2Phase.Idle,
    val devices: List<G2Device> = emptyList(),
    val left: G2Device? = null,
    val right: G2Device? = null,
    val touch: G2TouchState? = null,
    val test: G2Test = G2Test.Text,
    val message: String = "",
)

internal class EvenG2ViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val mutable = MutableStateFlow(G2Ui(message = context.getString(R.string.g2_initial)))
    val state = mutable.asStateFlow()
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var sendJob: Job? = null
    private var session: G2Session? = null
    private var touchDemo: G2TouchDemo? = null
    private var touchStartedAt = 0L

    fun permissions() = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasPermissions() = permissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    fun bluetoothEnabled() = try { manager?.adapter?.isEnabled == true } catch (_: SecurityException) { false }
    fun message(text: String) { mutable.update { it.copy(message = text) } }

    fun scan() {
        if (mutable.value.phase !in setOf(G2Phase.Idle, G2Phase.Scanning)) return
        stopScan()
        if (!hasPermissions()) { message(context.getString(R.string.g2_permission_scan)); return }
        if (!bluetoothEnabled()) { message(context.getString(R.string.g2_bluetooth_on)); return }
        if (Build.VERSION.SDK_INT < 31 && !context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            message(context.getString(R.string.g2_location_on)); return
        }
        mutable.value = G2Ui(phase = G2Phase.Scanning, message = context.getString(R.string.g2_scanning))
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val source = this
                try {
                    val device = G2Device.fromAdvertisement(result.device.address, result.scanRecord?.deviceName ?: result.device.name) ?: return
                    viewModelScope.launch { if (scanCallback === source) found(device) }
                } catch (_: SecurityException) {
                    viewModelScope.launch { if (scanCallback === source) { stopScan(); message(context.getString(R.string.g2_permission_revoked)) } }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                val source = this
                viewModelScope.launch {
                    if (scanCallback === source) { stopScan(); message(context.getString(R.string.g2_scan_failed, errorCode)) }
                }
            }
        }
        scanCallback = callback
        try {
            val adapter = manager?.adapter ?: throw G2Failure(context.getString(R.string.g2_bluetooth_unavailable_device))
            // Some G2 arms already connected by the system do not advertise again.
            (manager.getConnectedDevices(BluetoothProfile.GATT) + adapter.bondedDevices).distinctBy { it.address }.forEach {
                G2Device.fromAdvertisement(it.address, it.name)?.let(::found)
            }
            scanner = adapter.bluetoothLeScanner ?: throw G2Failure(context.getString(R.string.g2_scanner_unavailable))
            scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            scanJob = viewModelScope.launch {
                delay(15000)
                stopScan()
                message(if (mutable.value.devices.isEmpty()) context.getString(R.string.g2_not_found) else context.getString(R.string.g2_choose_pair))
            }
        } catch (_: SecurityException) { stopScan(); message(context.getString(R.string.g2_permission_revoked_settings)) }
        catch (error: Exception) { stopScan(); message(failureMessage(error)) }
    }

    private fun found(device: G2Device) {
        mutable.update { current -> current.copy(devices = (current.devices.filterNot { it.address == device.address } + device).sortedBy { it.name }) }
    }
    fun select(device: G2Device) {
        if (mutable.value.phase !in setOf(G2Phase.Idle, G2Phase.Scanning)) return
        mutable.update { if (device.side == G2Side.Left) it.copy(left = device) else it.copy(right = device) }
    }
    fun stopScan() {
        scanJob?.cancel(); scanJob = null
        val callback = scanCallback
        scanCallback = null
        try { if (callback != null && hasPermissions()) scanner?.stopScan(callback) } catch (_: SecurityException) { /* Permission revoked. */ }
        scanner = null
        mutable.update {
            if (it.phase == G2Phase.Scanning) it.copy(phase = G2Phase.Idle, message = context.getString(R.string.g2_search_stopped)) else it
        }
    }

    fun connect() {
        if (connectionJob != null || mutable.value.phase !in setOf(G2Phase.Idle, G2Phase.Scanning)) return
        val left = mutable.value.left ?: return
        val right = mutable.value.right ?: return
        stopScan()
        if (!hasPermissions()) { message(context.getString(R.string.g2_permission_connect)); return }
        mutable.update { it.copy(phase = G2Phase.Connecting, message = context.getString(R.string.g2_connecting)) }
        connectionJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var current: G2Session? = null
            try {
                val adapter = manager?.adapter ?: throw G2Failure(context.getString(R.string.g2_bluetooth_unavailable))
                current = G2Session(AndroidG2Link(context, adapter.getRemoteDevice(left.address)), AndroidG2Link(context, adapter.getRemoteDevice(right.address)))
                session = current
                withTimeout(45000) { current.connect(System.currentTimeMillis() / 1000) }
                mutable.update { it.copy(phase = G2Phase.Connected, message = context.getString(R.string.g2_connected)) }
                current.maintain { input -> handleInput(current, input) }
            } catch (error: TimeoutCancellationException) { message(context.getString(R.string.g2_connection_timeout)) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message(failureMessage(error)) }
            finally {
                sendJob?.cancel(); sendJob = null
                withContext(NonCancellable) {
                    withTimeoutOrNull(1000) { try { current?.releasePage() } catch (_: Exception) { /* Best effort on a closing link. */ } }
                }
                current?.close()
                session = null
                touchDemo = null
                connectionJob = null
                mutable.update { it.copy(phase = G2Phase.Idle, touch = null, test = G2Test.Text) }
            }
        }
        connectionJob?.start()
    }

    fun sendTest() = showTest(G2Test.Text)
    fun startTouchTest() = showTest(G2Test.Touch)
    fun showBounds() = showTest(G2Test.Bounds)

    private fun showTest(test: G2Test) {
        if (mutable.value.phase != G2Phase.Connected) return
        val current = session ?: return
        val demo = if (test == G2Test.Touch) G2TouchDemo() else null
        touchDemo = null
        touchStartedAt = SystemClock.elapsedRealtime()
        mutable.update { it.copy(phase = G2Phase.Sending, touch = null, test = test, message = context.getString(R.string.g2_sending)) }
        sendJob = viewModelScope.launch {
            try {
                if (test == G2Test.Bounds) current.displayBounds()
                else current.display(demo?.state?.let(::touchText) ?: context.getString(R.string.g2_sample_text))
                touchDemo = demo
                mutable.update { it.copy(phase = G2Phase.Connected, touch = demo?.state, message = when (test) {
                    G2Test.Text -> context.getString(R.string.g2_text_sent)
                    G2Test.Touch -> context.getString(R.string.g2_touch_started)
                    G2Test.Bounds -> context.getString(R.string.g2_bounds_sent)
                }) }
            } catch (error: TimeoutCancellationException) { disconnect(context.getString(R.string.g2_send_timeout)) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { disconnect(failureMessage(error)) }
        }
    }

    private suspend fun handleInput(current: G2Session, input: G2Input) {
        if (input.type in setOf(G2InputType.Exit, G2InputType.AbnormalExit, G2InputType.SystemExit)) {
            touchDemo = null
            mutable.update { it.copy(touch = null, test = G2Test.Text, message = context.getString(R.string.g2_page_closed)) }
            return
        }
        val demo = touchDemo ?: return
        if (input.receivedAtMillis < touchStartedAt || !demo.accept(input)) return
        mutable.update { it.copy(touch = demo.state, message = context.getString(R.string.g2_input_received, inputLabel(demo.state.lastAction))) }
        if (current.updateText(touchText(demo.state)) && touchDemo === demo) {
            mutable.update { it.copy(message = context.getString(R.string.g2_input_sent, inputLabel(demo.state.lastAction))) }
        }
    }
    fun disconnect(reason: String = context.getString(R.string.g2_disconnected)) {
        stopScan()
        message(reason)
        sendJob?.cancel()
        if (connectionJob != null) mutable.update { it.copy(phase = G2Phase.Disconnecting) }
        connectionJob?.cancel()
    }
    fun background() {
        if (scanCallback != null || connectionJob != null) disconnect(context.getString(R.string.g2_background_disconnect))
    }
    private fun failureMessage(error: Exception) = when (error) {
        is SecurityException -> context.getString(R.string.g2_permission_settings)
        is G2Failure -> when (error.arm) {
            G2Side.Left -> context.getString(R.string.g2_left_disconnected)
            G2Side.Right -> context.getString(R.string.g2_right_disconnected)
            null -> error.message ?: context.getString(R.string.g2_connect_failed)
        }
        else -> context.getString(R.string.g2_connection_error, error.javaClass.simpleName)
    }
    private fun touchText(state: G2TouchState) = state.sampleText(context.getString(R.string.g2_touch_sample_text))
    private fun inputLabel(type: G2InputType?) = context.getString(g2InputLabel(type))
    override fun onCleared() { stopScan(); session?.close() }
}
