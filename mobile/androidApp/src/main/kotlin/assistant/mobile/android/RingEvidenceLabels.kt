package assistant.mobile.android

import androidx.compose.runtime.Composable
import assistant.mobile.core.GeneratedStrings

/** Fixed protocol keys retain their evidence labels from the shared web locale. */
@Composable
internal fun ringStoredName(key: String): String = when (key) {
    "heart_rate" -> localized(GeneratedStrings.Ring.Stored.Names.heart_rate)
    "spo2" -> localized(GeneratedStrings.Ring.Stored.Names.spo2)
    "temperature" -> localized(GeneratedStrings.Ring.Stored.Names.temperature)
    "hrv" -> localized(GeneratedStrings.Ring.Stored.Names.hrv)
    "stress" -> localized(GeneratedStrings.Ring.Stored.Names.stress)
    "notifications" -> localized(GeneratedStrings.Ring.Stored.Names.notifications)
    "ecg" -> localized(GeneratedStrings.Ring.Stored.Names.ecg)
    "prayer" -> localized(GeneratedStrings.Ring.Stored.Names.prayer)
    "moslin" -> localized(GeneratedStrings.Ring.Stored.Names.moslin)
    "lover" -> localized(GeneratedStrings.Ring.Stored.Names.lover)
    "plate" -> localized(GeneratedStrings.Ring.Stored.Names.plate)
    "feature" -> localized(GeneratedStrings.Ring.Stored.Names.feature)
    "rtk_mc" -> localized(GeneratedStrings.Ring.Stored.Names.rtk_mc)
    "e_card" -> localized(GeneratedStrings.Ring.Stored.Names.e_card)
    "one_key" -> localized(GeneratedStrings.Ring.Stored.Names.one_key)
    "menstruation" -> localized(GeneratedStrings.Ring.Stored.Names.menstruation)
    "wallpaper" -> localized(GeneratedStrings.Ring.Stored.Names.wallpaper)
    "weather" -> localized(GeneratedStrings.Ring.Stored.Names.weather)
    "wechat" -> localized(GeneratedStrings.Ring.Stored.Names.wechat)
    "contacts" -> localized(GeneratedStrings.Ring.Stored.Names.contacts)
    "gesture" -> localized(GeneratedStrings.Ring.Stored.Names.gesture)
    "music" -> localized(GeneratedStrings.Ring.Stored.Names.music)
    "video" -> localized(GeneratedStrings.Ring.Stored.Names.video)
    "ebook" -> localized(GeneratedStrings.Ring.Stored.Names.ebook)
    "game" -> localized(GeneratedStrings.Ring.Stored.Names.game)
    "camera" -> localized(GeneratedStrings.Ring.Stored.Names.camera)
    "phone_call" -> localized(GeneratedStrings.Ring.Stored.Names.phone_call)
    "touch" -> localized(GeneratedStrings.Ring.Stored.Names.touch)
    "interval_spo2" -> localized(GeneratedStrings.Ring.Stored.Names.interval_spo2)
    "interval_temperature" -> localized(GeneratedStrings.Ring.Stored.Names.interval_temperature)
    "blood_pressure" -> localized(GeneratedStrings.Ring.Stored.Names.blood_pressure)
    "manual_heart" -> localized(GeneratedStrings.Ring.Stored.Names.manual_heart)
    "new_sleep" -> localized(GeneratedStrings.Ring.Stored.Names.new_sleep)
    "ble_pair" -> localized(GeneratedStrings.Ring.Stored.Names.ble_pair)
    "app_revision" -> localized(GeneratedStrings.Ring.Stored.Names.app_revision)
    "location" -> localized(GeneratedStrings.Ring.Stored.Names.location)
    "heart_rate_schedule" -> localized(GeneratedStrings.Ring.Stored.Names.heart_rate_schedule)
    "heart_rate_alerts" -> localized(GeneratedStrings.Ring.Stored.Names.heart_rate_alerts)
    "spo2_schedule" -> localized(GeneratedStrings.Ring.Stored.Names.spo2_schedule)
    "temperature_schedule" -> localized(GeneratedStrings.Ring.Stored.Names.temperature_schedule)
    "hrv_schedule" -> localized(GeneratedStrings.Ring.Stored.Names.hrv_schedule)
    "stress_schedule" -> localized(GeneratedStrings.Ring.Stored.Names.stress_schedule)
    "workout" -> localized(GeneratedStrings.Ring.Stored.Names.workout)
    "live_heart_rate" -> localized(GeneratedStrings.Ring.Stored.Names.live_heart_rate)
    "manual_heart_rate" -> localized(GeneratedStrings.Ring.Stored.Names.manual_heart_rate)
    "manual_spo2" -> localized(GeneratedStrings.Ring.Stored.Names.manual_spo2)
    "manual_temperature" -> localized(GeneratedStrings.Ring.Stored.Names.manual_temperature)
    "manual_hrv" -> localized(GeneratedStrings.Ring.Stored.Names.manual_hrv)
    "manual_stress" -> localized(GeneratedStrings.Ring.Stored.Names.manual_stress)
    "alarms" -> localized(GeneratedStrings.Ring.Stored.Names.alarms)
    "drink_reminders" -> localized(GeneratedStrings.Ring.Stored.Names.drink_reminders)
    "dnd" -> localized(GeneratedStrings.Ring.Stored.Names.dnd)
    "sedentary" -> localized(GeneratedStrings.Ring.Stored.Names.sedentary)
    "power_saving" -> localized(GeneratedStrings.Ring.Stored.Names.power_saving)
    "gestures" -> localized(GeneratedStrings.Ring.Stored.Names.gestures)
    "counter" -> localized(GeneratedStrings.Ring.Stored.Names.counter)
    "profile" -> localized(GeneratedStrings.Ring.Stored.Names.profile)
    "clock" -> localized(GeneratedStrings.Ring.Stored.Names.clock)
    "find_ring" -> localized(GeneratedStrings.Ring.Stored.Names.find_ring)
    "bind" -> localized(GeneratedStrings.Ring.Stored.Names.bind)
    "unbind" -> localized(GeneratedStrings.Ring.Stored.Names.unbind)
    "factory_reset" -> localized(GeneratedStrings.Ring.Stored.Names.factory_reset)
    "power_off" -> localized(GeneratedStrings.Ring.Stored.Names.power_off)
    "vibration_intensity" -> localized(GeneratedStrings.Ring.Stored.Names.vibration_intensity)
    "restart" -> localized(GeneratedStrings.Ring.Stored.Names.restart)
    else -> key
}

@Composable
internal fun ringImportStatus(key: String): String = when (key) {
    "available" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.available)
    "unverified" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.unverified)
    "missing" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.missing)
    "unsupported" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.unsupported)
    "no_observation" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.no_observation)
    "unsupported_evidenced" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.unsupported_evidenced)
    "source_unavailable" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.source_unavailable)
    "protocol_unverified" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.protocol_unverified)
    "semantic_unknown" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.semantic_unknown)
    "ambiguous_layout" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.ambiguous_layout)
    "workout_time_basis_unconfirmed" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.workout_time_basis_unconfirmed)
    "interval_increment" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.interval_increment)
    "day_total" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.day_total)
    "counter" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.counter)
    "day_counter_snapshot" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.day_counter_snapshot)
    "workout_report" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.workout_report)
    "point" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.point)
    "interval_mean" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.interval_mean)
    "sample_window_mean" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.sample_window_mean)
    "range" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.range)
    "sleep_report" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.sleep_report)
    "reported_unknown" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.reported_unknown)
    "reported_range" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.reported_range)
    "reported_window" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.reported_window)
    "partial" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.partial)
    "unknown" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.unknown)
    "connected" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.connected)
    "disabled" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.disabled)
    "unavailable" -> localized(GeneratedStrings.Ring.Collector.ImportStatus.unavailable)
    else -> key
}

@Composable
internal fun ringCaptureStatus(key: String): String = when (key) {
    "complete" -> localized(GeneratedStrings.Ring.Collector.CaptureStatus.complete)
    "empty" -> localized(GeneratedStrings.Ring.Collector.CaptureStatus.empty)
    "failed" -> localized(GeneratedStrings.Ring.Collector.CaptureStatus.failed)
    else -> key
}

@Composable
internal fun ringCaptureScope(key: String): String = when (key) {
    "day" -> localized(GeneratedStrings.Ring.Collector.CaptureScopes.day)
    "history" -> localized(GeneratedStrings.Ring.Collector.CaptureScopes.history)
    "live" -> localized(GeneratedStrings.Ring.Collector.CaptureScopes.live)
    "workout" -> localized(GeneratedStrings.Ring.Collector.CaptureScopes.workout)
    "spot" -> localized(GeneratedStrings.Ring.Collector.CaptureScopes.spot)
    else -> key
}

@Composable
internal fun ringCaptureContext(key: String): String = when (key) {
    "background" -> localized(GeneratedStrings.Ring.Collector.CaptureContexts.background)
    "running" -> localized(GeneratedStrings.Ring.Collector.CaptureContexts.running)
    "workout" -> localized(GeneratedStrings.Ring.Collector.CaptureContexts.workout)
    "recovery" -> localized(GeneratedStrings.Ring.Collector.CaptureContexts.recovery)
    "manual" -> localized(GeneratedStrings.Ring.Collector.CaptureContexts.manual)
    else -> key
}
