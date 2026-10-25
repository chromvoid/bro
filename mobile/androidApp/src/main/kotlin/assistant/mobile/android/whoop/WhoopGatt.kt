package assistant.mobile.android.whoop

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import assistant.mobile.android.R
import assistant.mobile.design.R as DesignR
import android.content.pm.PackageManager
import android.os.Build
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** The bonded strap's GATT client: fd4b notifications in, commands out, standard battery and versions. */
internal class WhoopGatt(private val context: Context, private val device: BluetoothDevice) : WhoopConnection {
    private data class Event(val kind: String, val status: Int, val bytes: ByteArray = ByteArray(0))
    private val notices = Channel<WhoopNotice>(1024)
    private val events = Channel<Event>(32)
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private val readable = mutableMapOf<String, BluetoothGattCharacteristic>()
    @Volatile private var closed = false
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                val error = WhoopFailure("disconnected", context.getString(DesignR.string.whoop_error_disconnected))
                events.close(error); notices.close(error)
            } else if (newState == BluetoothProfile.STATE_CONNECTED) emit(Event("connect", status))
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = emit(Event("services", status))
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = emit(Event("mtu", status))
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = emit(Event("notify", status))
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = emit(Event("write", status))
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            emit(Event("read", status, value.copyOf()))
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) emit(Event("read", status, c.value?.copyOf() ?: ByteArray(0)))
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = notice(c, value)
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) notice(c, c.value ?: return)
        }
    }
    private fun notice(c: BluetoothGattCharacteristic, value: ByteArray) {
        if (closed) return
        val channel = WhoopProtocol.NOTIFY[c.uuid] ?: return
        if (notices.trySend(WhoopNotice(channel, value.copyOf())).isFailure) {
            val error = WhoopFailure("bluetooth_overflow", context.getString(R.string.whoop_platform_bluetooth_overflow))
            events.close(error); notices.close(error)
        }
    }
    private fun emit(event: Event) {
        if (!closed && events.trySend(event).isFailure) {
            val error = WhoopFailure("bluetooth_overflow", context.getString(R.string.whoop_platform_bluetooth_overflow))
            events.close(error); notices.close(error)
        }
    }
    private fun permission() {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            throw WhoopFailure("permission_denied", context.getString(DesignR.string.whoop_error_permission_denied))
    }
    private suspend fun await(kind: String, allowFailure: Boolean = false): Event = withTimeout(10_000) {
        val event = events.receive()
        if (event.kind != kind) throw WhoopFailure("gatt_event_mismatch", context.getString(R.string.whoop_platform_gatt_event_mismatch, kind, event.kind))
        if (!allowFailure && event.status != BluetoothGatt.GATT_SUCCESS) {
            // An encrypted channel without a bond answers 5 or 15: the strap was paired elsewhere.
            if (event.status == 5 || event.status == 15 || event.status == 137)
                throw WhoopFailure("whoop_not_paired", context.getString(DesignR.string.whoop_error_whoop_not_paired))
            throw WhoopFailure("gatt_error", context.getString(R.string.whoop_platform_gatt_error, kind, event.status.toString()))
        }
        event
    }

    @SuppressLint("MissingPermission")
    override suspend fun open() {
        currentCoroutineContext().ensureActive()
        permission()
        val connection = synchronized(this) {
            if (closed) throw WhoopFailure("disconnected", context.getString(DesignR.string.whoop_error_disconnected))
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?.also { gatt = it } ?: throw WhoopFailure("connect_failed", context.getString(R.string.whoop_platform_connect_failed))
        }
        await("connect")
        if (connection.requestMtu(517)) await("mtu", allowFailure = true)
        connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (!connection.discoverServices()) throw WhoopFailure("services_failed", context.getString(R.string.whoop_platform_services_failed))
        await("services")
        val service = connection.getService(WhoopProtocol.SERVICE) ?: throw WhoopFailure("wrong_device", context.getString(DesignR.string.whoop_error_wrong_device))
        command = service.getCharacteristic(WhoopProtocol.COMMAND)?.takeIf { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 }
            ?: throw WhoopFailure("wrong_device", context.getString(DesignR.string.whoop_error_wrong_device))
        for (uuid in WhoopProtocol.NOTIFY.keys) {
            val notify = service.getCharacteristic(uuid) ?: throw WhoopFailure("wrong_device", context.getString(DesignR.string.whoop_error_wrong_device))
            val descriptor = notify.getDescriptor(CCCD) ?: throw WhoopFailure("wrong_device", context.getString(DesignR.string.whoop_error_wrong_device))
            if (!connection.setCharacteristicNotification(notify, true)) throw WhoopFailure("notify_failed", context.getString(R.string.whoop_platform_notify_failed))
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val accepted = if (Build.VERSION.SDK_INT >= 33) connection.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION") connection.writeDescriptor(descriptor)
            }
            if (!accepted) throw WhoopFailure("notify_failed", context.getString(R.string.whoop_platform_notify_failed_2))
            await("notify")
        }
        connection.getService(INFO)?.let { info ->
            info.getCharacteristic(FIRMWARE)?.let { readable["firmware"] = it }
            info.getCharacteristic(HARDWARE)?.let { readable["hardware"] = it }
        }
        connection.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY)?.let { readable["battery"] = it }
    }

    @SuppressLint("MissingPermission")
    private suspend fun read(kind: String): ByteArray? {
        val c = readable[kind] ?: return null
        permission()
        if (gatt?.readCharacteristic(c) != true) return null
        val event = await("read", allowFailure = true)
        return event.bytes.takeIf { event.status == BluetoothGatt.GATT_SUCCESS }
    }
    override suspend fun version(kind: String): String? = read(kind)?.toString(Charsets.UTF_8)?.trim('\u0000', ' ')?.take(100)
    override suspend fun battery(): Int? = read("battery")?.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }

    @SuppressLint("MissingPermission")
    override suspend fun write(bytes: ByteArray) {
        permission()
        val connection = gatt ?: throw WhoopFailure("disconnected", context.getString(DesignR.string.whoop_error_disconnected))
        val c = command ?: throw WhoopFailure("disconnected", context.getString(DesignR.string.whoop_error_disconnected))
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val accepted = if (Build.VERSION.SDK_INT >= 33) connection.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
        else {
            @Suppress("DEPRECATION")
            c.value = bytes
            c.writeType = type
            @Suppress("DEPRECATION") connection.writeCharacteristic(c)
        }
        if (!accepted) throw WhoopFailure("write_failed", context.getString(R.string.whoop_platform_write_failed))
        await("write")
    }
    override suspend fun receive(): WhoopNotice = notices.receive()

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val connection = gatt; gatt = null
        try {
            if (Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                connection?.disconnect()
        } catch (_: SecurityException) { /* Permission revoked during the call. */ }
        catch (_: RuntimeException) { /* Android may have already retired this client. */ }
        finally {
            try { connection?.close() } catch (_: SecurityException) { /* Release best effort. */ }
            catch (_: RuntimeException) { /* Already retired. */ }
        }
        readable.clear(); events.close(); notices.close()
    }

    companion object {
        private val INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        private val HARDWARE: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
        private val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
