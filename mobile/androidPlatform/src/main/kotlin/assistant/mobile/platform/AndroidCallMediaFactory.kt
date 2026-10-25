package assistant.mobile.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import assistant.mobile.core.calls.CallAudioRoute
import assistant.mobile.core.calls.CallIceConfiguration
import assistant.mobile.core.calls.CallMedia
import assistant.mobile.core.calls.CallMediaConnection
import assistant.mobile.core.calls.CallMediaFactory
import assistant.mobile.core.calls.CallMediaFailure
import assistant.mobile.core.calls.CallMediaListener
import assistant.mobile.core.calls.CallMediaProblem

/** A foreground call owns communication audio from capture through teardown. */
@Suppress("DEPRECATION")
class AndroidCallMediaFactory(
    context: Context,
    private val requestPermission: suspend () -> Boolean,
    private val beforeCapture: () -> Unit = {},
    private val peerFactory: (CallMediaListener) -> AndroidWebRtcPeer = { AndroidWebRtcPeer(context, it) },
) : CallMediaFactory, AutoCloseable {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val appOps = this.context.getSystemService(AppOpsManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val active = mutableSetOf<Media>()

    override fun create(listener: CallMediaListener): CallMedia = Media(listener).also { active.add(it) }

    /** Accessibility uses device names supplied by Android, without another translation source. */
    fun audioRouteNames(): Map<CallAudioRoute, String> = devices()
        .mapNotNull { device -> routeOf(device)?.let { it to device.productName.toString() } }
        .toMap()

    override fun close() { active.toList().forEach(Media::close) }

    private inner class Media(private val listener: CallMediaListener) : CallMedia {
        private var peer: AndroidWebRtcPeer? = null
        private var focus: AudioFocusRequest? = null
        private var oldMode: Int? = null
        private var oldSpeaker = false
        private var oldSco = false
        private var oldDevice: AudioDeviceInfo? = null
        private var devicesRegistered = false
        private var permissions: AppOpsManager.OnOpChangedListener? = null
        private var selected: CallAudioRoute? = null
        private var currentRoute = CallAudioRoute.EARPIECE
        private var muted = false
        private var closed = false
        private val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = updateRoute()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = updateRoute()
        }

        override suspend fun acquire() {
            check(!closed)
            if (peer != null) return
            // The bridge also asks for optional Bluetooth access; microphone decides capture.
            if (!requestPermission() || !microphoneAllowed()) {
                throw CallMediaFailure(CallMediaProblem.DENIED)
            }
            if (closed) throw CallMediaFailure(CallMediaProblem.UNAVAILABLE)
            beforeCapture()
            if (!microphoneAllowed()) throw CallMediaFailure(CallMediaProblem.DENIED)
            try {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener({ change ->
                        if (change != AudioManager.AUDIOFOCUS_GAIN) interrupt()
                    }, main)
                    .build()
                focus = request
                if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    throw CallMediaFailure(CallMediaProblem.AUDIO_FOCUS)
                }
                oldMode = manager.mode
                oldSpeaker = manager.isSpeakerphoneOn
                oldSco = manager.isBluetoothScoOn
                if (Build.VERSION.SDK_INT >= 31) oldDevice = manager.communicationDevice
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                manager.registerAudioDeviceCallback(deviceCallback, main)
                devicesRegistered = true
                watchPermission()
                peer = peerFactory(listener).also { it.setMuted(muted) }
                updateRoute()
            } catch (failure: CallMediaFailure) {
                close()
                throw failure
            } catch (_: SecurityException) {
                close()
                throw CallMediaFailure(CallMediaProblem.DENIED)
            } catch (_: Exception) {
                close()
                throw CallMediaFailure(CallMediaProblem.UNAVAILABLE)
            }
        }

        override fun configure(configuration: CallIceConfiguration) = requirePeer().configure(configuration)
        override suspend fun createOffer(): String = requirePeer().createOffer()
        override suspend fun answerOffer(sdp: String): String = requirePeer().answerOffer(sdp)
        override suspend fun applyAnswer(sdp: String) = requirePeer().applyAnswer(sdp)
        override fun setMuted(muted: Boolean) {
            this.muted = muted
            peer?.setMuted(muted)
        }
        override fun setRoute(route: CallAudioRoute) {
            selected = route
            updateRoute()
        }

        override fun close() {
            if (closed) return
            closed = true
            // Release native capture before restoring audio routing or granting another owner.
            runCatching { peer?.close() }
            peer = null
            if (devicesRegistered) manager.unregisterAudioDeviceCallback(deviceCallback)
            devicesRegistered = false
            permissions?.let { appOps.stopWatchingMode(it) }
            permissions = null
            if (oldMode != null) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 31) {
                        manager.clearCommunicationDevice()
                        oldDevice?.takeIf { previous -> devices().any { it.id == previous.id } }
                            ?.let { manager.setCommunicationDevice(it) }
                    } else {
                        if (!oldSco) manager.stopBluetoothSco()
                        manager.isBluetoothScoOn = oldSco
                        manager.isSpeakerphoneOn = oldSpeaker
                    }
                }
                // A removed endpoint must not prevent leaving communication mode.
                runCatching { manager.mode = oldMode!! }
                oldMode = null
            }
            focus?.let { manager.abandonAudioFocusRequest(it) }
            focus = null
            active.remove(this)
        }

        private fun requirePeer(): AndroidWebRtcPeer {
            check(!closed)
            return checkNotNull(peer)
        }

        private fun interrupt() {
            if (closed) return
            close()
            listener.connection(CallMediaConnection.FAILED)
        }

        private fun watchPermission() {
            val guard = AppOpsManager.OnOpChangedListener { operation, packageName ->
                if (operation == AppOpsManager.OPSTR_RECORD_AUDIO &&
                    (packageName == null || packageName == context.packageName)) {
                    val mode = appOps.checkOpNoThrow(operation, Process.myUid(), context.packageName)
                    if (!microphoneAllowed() || mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) {
                        main.post { interrupt() }
                    }
                }
            }
            permissions = guard
            appOps.startWatchingMode(AppOpsManager.OPSTR_RECORD_AUDIO, context.packageName, guard)
        }

        @SuppressLint("MissingPermission") // Bluetooth devices are excluded until CONNECT is granted.
        private fun updateRoute() {
            if (closed || oldMode == null) return
            val devices = devices()
            val available = devices.mapNotNull(::routeOf).toSet()
            if (available.isEmpty()) return
            val route = selected?.takeIf { it in available }
                ?: if (CallAudioRoute.BLUETOOTH in available) CallAudioRoute.BLUETOOTH
                else if (CallAudioRoute.EARPIECE in available) CallAudioRoute.EARPIECE
                else CallAudioRoute.SPEAKER
            val candidates = devices.filter { routeOf(it) == route }
            // A wired headset follows the private/earpiece route when attached.
            val device = candidates.firstOrNull { it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                ?: candidates.firstOrNull() ?: return
            val changed = runCatching {
                if (Build.VERSION.SDK_INT >= 31) manager.setCommunicationDevice(device)
                else {
                    val bluetooth = route == CallAudioRoute.BLUETOOTH
                    if (bluetooth && !manager.isBluetoothScoOn) manager.startBluetoothSco()
                    if (!bluetooth && manager.isBluetoothScoOn) manager.stopBluetoothSco()
                    manager.isBluetoothScoOn = bluetooth
                    manager.isSpeakerphoneOn = route == CallAudioRoute.SPEAKER
                    true
                }
            }.getOrDefault(false)
            if (changed) currentRoute = route
            listener.route(currentRoute, available)
        }
    }

    private fun microphoneAllowed() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun bluetoothAllowed() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Bluetooth endpoints are removed without CONNECT permission.
    private fun devices(): List<AudioDeviceInfo> = runCatching {
        val found = if (Build.VERSION.SDK_INT >= 31) manager.availableCommunicationDevices
            else manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        found.filter { routeOf(it) != null && (routeOf(it) != CallAudioRoute.BLUETOOTH || bluetoothAllowed()) }
    }.getOrDefault(emptyList())

    private fun routeOf(device: AudioDeviceInfo): CallAudioRoute? = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> CallAudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallAudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> CallAudioRoute.BLUETOOTH
        else -> if (Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            CallAudioRoute.BLUETOOTH else null
    }
}
