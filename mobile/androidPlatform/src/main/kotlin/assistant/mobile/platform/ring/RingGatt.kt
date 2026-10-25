package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.time.Instant
import android.os.SystemClock

class RingGatt(private val context: Context, private val device: BluetoothDevice) {
    init { RingText.initialize(context) }
    data class Notice(val channel: String, val bytes: ByteArray,
        val observedAt: Instant = Instant.now(), val observedElapsed: Long = SystemClock.elapsedRealtime(),
        val observedElapsedNanos: Long = observedElapsed * 1_000_000L,
        val temperatureRequestId: String? = null)
    private data class Event(val kind: String, val status: Int, val bytes: ByteArray = ByteArray(0))
    val notices = Channel<Notice>(128)
    private val events = Channel<Event>(32)
    private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    @Volatile private var temperatureRequestId: String? = null
    @Volatile private var connectionLost = false
    @Volatile var connected = false
        private set
    @Volatile var maximumWriteBytes: Int = 20
        private set
    private val writes = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val versions = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                temperatureRequestId = null
                connectionLost = true
                connected = false
                val error = RingFailure("disconnected", RingText.text(R.string.ring_native_29d689633aca, status))
                events.close(error); notices.close(error)
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = !connectionLost
                emit(Event("connect", status))
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = emit(Event("services", status))
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu in 23..517) maximumWriteBytes = mtu - 3
            emit(Event("mtu", status))
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = emit(Event("notify", status))
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = emit(Event("write", status))
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) = emit(Event("read", status, value.copyOf()))
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
        val channel = when (c.uuid) { CORE_NOTIFY -> "core"; BULK_NOTIFY -> "bulk"; else -> return }
        val nanos = SystemClock.elapsedRealtimeNanos()
        if (notices.trySend(Notice(channel, value.copyOf(), Instant.now(), nanos / 1_000_000L, nanos,
            temperatureRequestId)).isFailure) {
            val error = RingFailure("bluetooth_overflow", RingText.text(R.string.ring_native_8a8928bfadba))
            events.close(error); notices.close(error)
        }
    }
    fun temperatureRequest(id: String?) { temperatureRequestId = if (closed || !connected) null else id }
    private fun emit(event: Event) {
        if (!closed && events.trySend(event).isFailure) {
            val error = RingFailure("bluetooth_overflow", RingText.text(R.string.ring_native_8a8928bfadba))
            events.close(error); notices.close(error)
        }
    }
    private fun permission() {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            throw RingFailure("permission_denied", RingText.text(R.string.ring_native_527ed8d4926c))
    }
    private suspend fun await(kind: String, allowFailure: Boolean = false): Event = withTimeout(8_000) {
        val event = events.receive()
        if (event.kind != kind)
            throw RingFailure("gatt_event_mismatch", RingText.text(R.string.ring_native_25197f44ee48, kind, event.kind))
        if (!allowFailure && event.status != BluetoothGatt.GATT_SUCCESS)
            throw RingFailure("gatt_error", RingText.text(R.string.ring_native_ca623ba5a98d, kind, event.status))
        event
    }
    @SuppressLint("MissingPermission")
    @Synchronized private fun connect(): BluetoothGatt {
        if (closed) throw RingFailure("disconnected", RingText.text(R.string.ring_native_83404560bb9c))
        permission()
        val connection = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw RingFailure("connect_failed", RingText.text(R.string.ring_native_2362e8fe09d8))
        gatt = connection
        return connection
    }
    @SuppressLint("MissingPermission")
    suspend fun open() {
        currentCoroutineContext().ensureActive()
        val connection = connect()
        await("connect")
        // Larger notifications help history transfers; core packets also work at the default MTU23.
        if (connection.requestMtu(247)) await("mtu", allowFailure = true)
        if (!connection.discoverServices()) throw RingFailure("services_failed", RingText.text(R.string.ring_native_04303ed08f28))
        await("services")
        val core = connection.getService(CORE_SERVICE) ?: throw RingFailure("wrong_device", RingText.text(R.string.ring_native_e8ed7eb4329b))
        val bulk = connection.getService(BULK_SERVICE) ?: throw RingFailure("wrong_device", RingText.text(R.string.ring_native_c739324dac45))
        writes["core"] = core.getCharacteristic(CORE_WRITE) ?: throw RingFailure("wrong_device", RingText.text(R.string.ring_native_0788f14113b3))
        writes["bulk"] = bulk.getCharacteristic(BULK_WRITE) ?: throw RingFailure("wrong_device", RingText.text(R.string.ring_native_e299badd305c))
        if (writes["core"]!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
            writes["bulk"]!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0)
            throw RingFailure("wrong_device", RingText.text(R.string.ring_native_c038b7c033fc))
        listOf(core.getCharacteristic(CORE_NOTIFY), bulk.getCharacteristic(BULK_NOTIFY)).forEach { c ->
            val notify = c ?: throw RingFailure("wrong_device", RingText.text(R.string.ring_native_7a825ee12d2f))
            val descriptor = notify.getDescriptor(CCCD) ?: throw RingFailure("wrong_device", RingText.text(R.string.ring_native_4d08d53965e8))
            val value = when {
                notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                notify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> throw RingFailure("wrong_device", RingText.text(R.string.ring_native_38ca8ca046c1))
            }
            permission()
            if (!connection.setCharacteristicNotification(notify, true)) throw RingFailure("notify_failed", RingText.text(R.string.ring_native_a5c548219cbc))
            val accepted = if (Build.VERSION.SDK_INT >= 33) connection.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION") connection.writeDescriptor(descriptor)
            }
            if (!accepted) throw RingFailure("notify_failed", RingText.text(R.string.ring_native_398433d2d5ec))
            await("notify")
        }
        connection.getService(INFO_SERVICE)?.let { service ->
            service.getCharacteristic(FIRMWARE)?.let { versions["firmware"] = it }
            service.getCharacteristic(HARDWARE)?.let { versions["hardware"] = it }
        }
    }
    @SuppressLint("MissingPermission")
    suspend fun readVersion(kind: String): String? {
        val c = versions[kind] ?: return null; permission()
        if (gatt?.readCharacteristic(c) != true) return null
        val event = await("read", allowFailure = true)
        return if (event.status == BluetoothGatt.GATT_SUCCESS) event.bytes.toString(Charsets.UTF_8).trim('\u0000').take(100) else null
    }
    @SuppressLint("MissingPermission")
    suspend fun write(channel: String, bytes: ByteArray, response: Boolean) {
        permission()
        val connection = gatt ?: throw RingFailure("disconnected", RingText.text(R.string.ring_native_a9d47aadd10b))
        val c = writes[channel] ?: throw RingFailure("invalid_channel", RingText.text(R.string.ring_native_ee57075420fd))
        require(response == (channel == "core"))
        val type = if (response) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val accepted = if (Build.VERSION.SDK_INT >= 33) connection.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
        else {
            @Suppress("DEPRECATION")
            c.value = bytes
            c.writeType = type
            @Suppress("DEPRECATION") connection.writeCharacteristic(c)
        }
        if (!accepted) throw RingFailure("write_failed", RingText.text(R.string.ring_native_0bbdb6fd23eb))
        await("write") // Stack completion is independent from the protocol response.
    }
    @Synchronized fun close() {
        if (closed) return
        closed = true
        connected = false
        temperatureRequestId = null
        val connection = gatt; gatt = null
        try {
            if (Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                connection?.disconnect()
        } catch (_: SecurityException) { /* Permission revoked during the call. */ }
        catch (_: RuntimeException) { /* Android may have already retired this GATT client. */ }
        finally {
            try { connection?.close() } catch (_: SecurityException) { /* Release client best effort. */ }
            catch (_: RuntimeException) { /* Already retired by Android. */ }
        }
        writes.clear(); versions.clear(); events.close(); notices.close()
    }
    companion object {
        val CORE_SERVICE: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
        val CORE_WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val CORE_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val BULK_SERVICE: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
        val BULK_WRITE: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
        val BULK_NOTIFY: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
        private val INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        private val HARDWARE = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
