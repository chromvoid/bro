package assistant.mobile.android.glasses

import assistant.mobile.android.R
import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** One in-flight GATT operation per arm. Cancellation closes the session's two links. */
internal class AndroidG2Link(private val context: Context, private val device: BluetoothDevice) : G2Link {
    private data class Event(val kind: String, val status: Int, val mtu: Int = 23)
    private val events = Channel<Event>(32)
    private val incoming = Channel<G2Notification>(64)
    override val notifications = incoming.receiveAsFlow()
    private val disconnected = CompletableDeferred<Unit>()
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    override var mtu = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            notification(characteristic, value)
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) notification(characteristic, characteristic.value ?: return)
        }
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnected.complete(Unit)
                events.close(G2Failure(context.getString(R.string.g2_connection_lost, status)))
            } else if (newState == BluetoothProfile.STATE_CONNECTED) emit(Event("connect", status))
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = emit(Event("services", status))
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = emit(Event("mtu", status, mtu))
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = emit(Event("notify", status))
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = emit(Event("write", status))
    }

    private fun notification(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        if (characteristic.uuid != UUID.fromString(G2Protocol.NOTIFY_UUID)) return
        // ACK traffic is handled separately from Android's GATT operation completions.
        if (bytes.size < 8 || bytes[6].toInt() and 255 != 0xe0 || bytes[7].toInt() !in listOf(1, 6)) return
        if (incoming.trySend(G2Notification(bytes.copyOf(), SystemClock.elapsedRealtime())).isFailure) {
            events.close(G2Failure(context.getString(R.string.g2_notification_queue_full)))
            disconnected.complete(Unit)
        }
    }

    private fun emit(event: Event) {
        if (events.trySend(event).isFailure) {
            events.close(G2Failure(context.getString(R.string.g2_bluetooth_queue_full)))
            disconnected.complete(Unit)
        }
    }
    private fun requirePermission() {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw G2Failure(context.getString(R.string.g2_permission_nearby_settings))
        }
    }
    private suspend fun await(kind: String): Event = withTimeout(8000) {
        val event = events.receive()
        if (event.kind != kind || event.status != BluetoothGatt.GATT_SUCCESS) {
            throw G2Failure(context.getString(R.string.g2_bluetooth_error, kind, event.status))
        }
        event
    }

    override suspend fun open() {
        try {
            requirePermission()
            val connection = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw G2Failure(context.getString(R.string.g2_connect_start_failed))
            gatt = connection
            await("connect")
            requirePermission()
            if (!connection.requestMtu(247)) throw G2Failure(context.getString(R.string.g2_mtu_failed))
            mtu = await("mtu").mtu
            // Stock authentication packets are unfragmented and need at least 39 ATT bytes.
            if (mtu < 64) throw G2Failure(context.getString(R.string.g2_mtu_too_small, mtu))
            requirePermission()
            if (!connection.discoverServices()) throw G2Failure(context.getString(R.string.g2_services_failed))
            await("services")
            val characteristics = connection.services.flatMap { it.characteristics }
            writeCharacteristic = characteristics.singleOrNull { it.uuid == UUID.fromString(G2Protocol.WRITE_UUID) }
                ?.takeIf { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 }
                ?: throw G2Failure(context.getString(R.string.g2_write_missing))
            val notify = characteristics.singleOrNull { it.uuid == UUID.fromString(G2Protocol.NOTIFY_UUID) }
                ?: throw G2Failure(context.getString(R.string.g2_notify_missing))
            val descriptor = notify.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                ?: throw G2Failure(context.getString(R.string.g2_descriptor_missing))
            val enable = when {
                notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                notify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> throw G2Failure(context.getString(R.string.g2_notify_unsupported))
            }
            requirePermission()
            if (!connection.setCharacteristicNotification(notify, true)) throw G2Failure(context.getString(R.string.g2_notify_enable_failed))
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                connection.writeDescriptor(descriptor, enable) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = enable
                @Suppress("DEPRECATION")
                connection.writeDescriptor(descriptor)
            }
            if (!accepted) throw G2Failure(context.getString(R.string.g2_notify_subscribe_failed))
            await("notify")
        } catch (_: SecurityException) {
            throw G2Failure(context.getString(R.string.g2_permission_revoked_settings))
        }
    }

    override suspend fun write(bytes: ByteArray) {
        try {
            requirePermission()
            val connection = gatt ?: throw G2Failure(context.getString(R.string.g2_disconnected))
            val characteristic = writeCharacteristic ?: throw G2Failure(context.getString(R.string.g2_write_not_ready))
            require(bytes.size <= mtu - 3) { "Packet exceeds negotiated MTU" }
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                connection.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                connection.writeCharacteristic(characteristic)
            }
            if (!accepted) throw G2Failure(context.getString(R.string.g2_write_failed))
            await("write") // Stack completion only; this does not prove that the display rendered it.
        } catch (_: SecurityException) {
            throw G2Failure(context.getString(R.string.g2_permission_revoked_settings))
        }
    }

    override suspend fun awaitDisconnect() = disconnected.await()

    override fun close() {
        val connection = gatt
        gatt = null
        writeCharacteristic = null
        try {
            if (Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                connection?.disconnect()
            }
        } catch (_: SecurityException) {
            // Permission can be revoked while a session is active; still release the GATT client.
        } finally {
            try { connection?.close() } catch (_: SecurityException) { /* Permission revoked. */ }
            events.close()
            incoming.close()
            disconnected.complete(Unit)
        }
    }
}
