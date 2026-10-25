package assistant.mobile.android.whoop

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Android-independent seam for the collector's scan, pairing and history paths. */
internal interface WhoopBluetooth {
    fun permissionNames(): Array<String>
    fun permissionState(): String
    fun bluetoothEnabled(): Boolean
    suspend fun scan(): List<WhoopDevice>
    suspend fun bond(address: String)
    fun connection(address: String): WhoopConnection
}

internal interface WhoopConnection : WhoopLink {
    suspend fun open()
    suspend fun battery(): Int?
    suspend fun version(kind: String): String?
    fun close()
}

internal class AndroidWhoopBluetooth(private val context: Context) : WhoopBluetooth {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun permissionNames(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun permissionState(): String =
        if (permissionNames().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) "granted" else "required"

    override fun bluetoothEnabled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) false
        else adapter?.isEnabled == true
    } catch (_: SecurityException) { false }

    @SuppressLint("MissingPermission")
    override suspend fun scan(): List<WhoopDevice> {
        if (permissionState() != "granted") throw WhoopFailure("permission_denied", context.getString(DesignR.string.whoop_error_permission_denied))
        if (!bluetoothEnabled()) throw WhoopFailure("bluetooth_off", context.getString(DesignR.string.whoop_error_bluetooth_off))
        val scanner = adapter?.bluetoothLeScanner
            ?: throw WhoopFailure("bluetooth_unavailable", context.getString(DesignR.string.whoop_error_bluetooth_unavailable))
        val found = linkedMapOf<String, WhoopDevice>()
        val scanError = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                val services = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                if (!name.startsWith("WHOOP", ignoreCase = true) && WhoopProtocol.SERVICE !in services) return
                synchronized(found) {
                    found[result.device.address] = WhoopDevice(result.device.address, name.ifEmpty { "WHOOP" },
                        result.device.bondState == BluetoothDevice.BOND_BONDED, result.rssi)
                }
            }
            override fun onScanFailed(errorCode: Int) { scanError.complete(errorCode) }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            val failure = withTimeoutOrNull(8_000) { scanError.await() }
            if (failure != null) throw WhoopFailure("scan_failed", context.getString(DesignR.string.whoop_error_scan_failed))
            return synchronized(found) { found.values.toList() }
        } finally { try { scanner.stopScan(callback) } catch (_: SecurityException) { /* Permission revoked. */ } }
    }

    /** Android owns the dialog; polling also handles Bluetooth broadcasts Android did not deliver. */
    @SuppressLint("MissingPermission")
    override suspend fun bond(address: String) {
        val device = adapter?.getRemoteDevice(address)
            ?: throw WhoopFailure("bluetooth_unavailable", context.getString(DesignR.string.whoop_error_bluetooth_unavailable))
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        val bonded = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val changed = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changed?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                    BluetoothDevice.BOND_NONE -> bonded.complete(false)
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        // Only the system Bluetooth app sends this protected broadcast.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        try {
            if (!device.createBond() && device.bondState != BluetoothDevice.BOND_BONDING)
                throw WhoopFailure("whoop_pairing_failed", context.getString(DesignR.string.whoop_error_whoop_pairing_failed))
            val outcome = withTimeoutOrNull(90_000) {
                var started = false
                while (!bonded.isCompleted) {
                    when (device.bondState) {
                        BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                        BluetoothDevice.BOND_BONDING -> started = true
                        BluetoothDevice.BOND_NONE -> if (started) bonded.complete(false)
                    }
                    if (!bonded.isCompleted) delay(500)
                }
                bonded.await()
            }
            if (outcome != true) throw WhoopFailure("whoop_pairing_failed", context.getString(DesignR.string.whoop_error_whoop_pairing_failed))
        } finally { context.unregisterReceiver(receiver) }
    }

    @SuppressLint("MissingPermission")
    override fun connection(address: String): WhoopConnection {
        val device = adapter?.getRemoteDevice(address)
            ?: throw WhoopFailure("bluetooth_unavailable", context.getString(DesignR.string.whoop_error_bluetooth_unavailable))
        if (device.bondState != BluetoothDevice.BOND_BONDED)
            throw WhoopFailure("whoop_not_paired", context.getString(DesignR.string.whoop_error_whoop_not_paired))
        return WhoopGatt(context, device)
    }
}
