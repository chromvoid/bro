//! Typed Oudmon device controls. Source-derived codecs are not hardware qualification.
//! Protocol provenance and the empty production proof registry are documented in
//! docs/herzring-device-controls.md; no proprietary implementation is embedded here.
use crate::codec::{core_command, decode_core, hex};
use crate::control::{CapabilityStatus, ControlStatus, RingIdentity};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, VecDeque};

const TIMEOUT_MS: u64 = 8_000;
const MAX_OPERATIONS: usize = 256;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DeviceKind {
    Alarms,
    DrinkReminders,
    Dnd,
    Sedentary,
    PowerSaving,
    Gestures,
    Counter,
    Profile,
    Clock,
    FindRing,
    Bind,
    Unbind,
    FactoryReset,
    PowerOff,
    Notifications,
    VibrationIntensity,
    Restart,
}
#[derive(Debug, Clone, Serialize)]
pub struct DeviceCapability {
    pub kind: DeviceKind,
    pub status: CapabilityStatus,
    pub reason: &'static str,
    pub readable: bool,
    pub writable: bool,
    pub evidence_ref: Option<&'static str>,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AlarmSlot {
    pub slot: u8,
    pub enabled: bool,
    pub hour: u8,
    pub minute: u8,
    pub weekdays: u8,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GestureMode {
    Media,
    Video,
    Ebook,
    Game,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Hand {
    Left,
    Right,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Sex {
    Male,
    Female,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Units {
    Metric,
    Imperial,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum DeviceSetting {
    Alarms {
        slots: [AlarmSlot; 5],
    },
    DrinkReminders {
        slots: [AlarmSlot; 8],
    },
    Dnd {
        enabled: bool,
        start_minute: u16,
        end_minute: u16,
    },
    Sedentary {
        enabled: bool,
        start_minute: u16,
        end_minute: u16,
        weekdays: u8,
        interval_minutes: u8,
    },
    PowerSaving {
        enabled: bool,
    },
    Gestures {
        enabled: bool,
        mode: GestureMode,
        hand: Hand,
    },
    Profile {
        sex: Sex,
        age: u8,
        height_cm: u8,
        weight_kg: u8,
        is_24_hour: bool,
        units: Units,
        systolic_mm_hg: u8,
        diastolic_mm_hg: u8,
        heart_rate_warning_bpm: u8,
    },
    Clock {
        year: u16,
        month: u8,
        day: u8,
        hour: u8,
        minute: u8,
        second: u8,
        language: u8,
    },
}
impl DeviceSetting {
    pub fn kind(&self) -> DeviceKind {
        match self {
            Self::Alarms { .. } => DeviceKind::Alarms,
            Self::DrinkReminders { .. } => DeviceKind::DrinkReminders,
            Self::Dnd { .. } => DeviceKind::Dnd,
            Self::Sedentary { .. } => DeviceKind::Sedentary,
            Self::PowerSaving { .. } => DeviceKind::PowerSaving,
            Self::Gestures { .. } => DeviceKind::Gestures,
            Self::Profile { .. } => DeviceKind::Profile,
            Self::Clock { .. } => DeviceKind::Clock,
        }
    }
    pub fn validate(&self) -> Result<(), &'static str> {
        let valid = match self {
            Self::Alarms { slots } => valid_slots(slots),
            Self::DrinkReminders { slots } => valid_slots(slots),
            Self::Dnd {
                start_minute,
                end_minute,
                ..
            } => *start_minute < 1440 && *end_minute < 1440,
            Self::Sedentary {
                enabled,
                start_minute,
                end_minute,
                weekdays,
                interval_minutes,
            } => {
                *start_minute < 1440
                    && *end_minute < 1440
                    && *weekdays <= 127
                    && (!*enabled || *weekdays != 0)
                    && matches!(*interval_minutes, 30 | 60 | 90)
            }
            Self::PowerSaving { .. } | Self::Gestures { .. } => true,
            Self::Profile {
                age,
                height_cm,
                weight_kg,
                ..
            } => (1..=120).contains(age) && *height_cm != 0 && *weight_kg != 0,
            Self::Clock {
                year,
                month,
                day,
                hour,
                minute,
                second,
                language,
            } => {
                let leap = *year % 4 == 0 && (*year % 100 != 0 || *year % 400 == 0);
                let days = match month {
                    2 if leap => 29,
                    2 => 28,
                    4 | 6 | 9 | 11 => 30,
                    1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
                    _ => 0,
                };
                (2000..=2099).contains(year)
                    && *day > 0
                    && *day <= days
                    && *hour < 24
                    && *minute < 60
                    && *second < 60
                    && *language <= 19
            }
        };
        if valid {
            Ok(())
        } else {
            Err("invalid_setting")
        }
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DeviceAction {
    FindRing,
    Bind,
    Unbind,
    FactoryReset,
    PowerOff,
    TasbihStart,
    TasbihStop,
}
impl DeviceAction {
    fn kind(self) -> DeviceKind {
        match self {
            Self::FindRing => DeviceKind::FindRing,
            Self::Bind => DeviceKind::Bind,
            Self::Unbind => DeviceKind::Unbind,
            Self::FactoryReset => DeviceKind::FactoryReset,
            Self::PowerOff => DeviceKind::PowerOff,
            Self::TasbihStart | Self::TasbihStop => DeviceKind::Counter,
        }
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum DeviceCommand {
    Read {
        kind: DeviceKind,
    },
    Update {
        setting: DeviceSetting,
    },
    Action {
        action: DeviceAction,
    },
    /// Native-only, never an exposed WebView command. Text never enters server telemetry.
    Notification {
        message_type: u8,
        text: String,
    },
}
impl DeviceCommand {
    pub fn kind(&self) -> DeviceKind {
        match self {
            Self::Read { kind } => *kind,
            Self::Update { setting } => setting.kind(),
            Self::Action { action } => action.kind(),
            Self::Notification { .. } => DeviceKind::Notifications,
        }
    }
    pub fn mutation(&self) -> bool {
        matches!(self, Self::Update { .. } | Self::Action { .. })
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeviceRequest {
    pub operation_id: String,
    pub command: DeviceCommand,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeviceRecovery {
    pub identity: RingIdentity,
    pub request: DeviceRequest,
    /// Persisted before the first mutation. Read-only reconciliation never reuses these bytes as writes.
    pub mutation_started: bool,
    /// Full expected read payloads preserve unmodeled fields across a crash.
    /// Old recoveries remain absent, never enriched under the same immutable ID.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub expected_readback: Vec<[u8; 14]>,
}
#[derive(Debug, Clone, Serialize)]
pub struct DeviceResult {
    pub operation_id: String,
    pub command: DeviceCommand,
    pub status: ControlStatus,
    pub reason: &'static str,
    pub snapshot: Option<DeviceSetting>,
    pub wire_state: Option<DeviceWireState>,
}
#[derive(Debug, Clone, Serialize)]
pub struct DeviceWireState {
    pub kind: DeviceKind,
    pub raw_payload_hex: Vec<String>,
    pub semantic_status: &'static str,
}
#[derive(Debug, Clone)]
pub enum DeviceEffect {
    Checkpoint(DeviceRecovery),
    Write { bytes_hex: String, query_id: String },
    Deadline { at_ms: u64 },
    Result(DeviceResult),
}

/// Private compiled evidence; neither capability bits nor bridge JSON can create a proof.
#[derive(Clone)]
struct SettingProof {
    kind: DeviceKind,
    evidence_ref: &'static str,
    writable: bool,
}
#[derive(Clone)]
struct DeviceActionRecipe {
    request: Vec<u8>,
    receipt_opcode: u8,
    receipt_payload: Vec<u8>,
    evidence_ref: &'static str,
}
fn production_actions(_identity: &RingIdentity) -> BTreeMap<DeviceAction, DeviceActionRecipe> {
    BTreeMap::new()
}
fn production_proofs(identity: &RingIdentity) -> Vec<SettingProof> {
    if !known_identity(identity) {
        return Vec::new();
    }
    // These capture pairs establish bounded read transport, not profile nonzero
    // values or semantic HID mapping. Zero/unmapped fields remain explicit wire state.
    vec![
        SettingProof {
            kind: DeviceKind::Profile,
            evidence_ref: "soul-att-rf20-20261005:8500-8503",
            writable: false,
        },
        SettingProof {
            kind: DeviceKind::Gestures,
            evidence_ref: "soul-att-rf20-20261005:8512-8515",
            writable: false,
        },
    ]
}
fn known_identity(identity: &RingIdentity) -> bool {
    identity.model == "qring-r20"
        && identity.hardware == "RF20_V2.1"
        && identity.firmware == "RF20_2.10.07_260131"
}
fn readable(kind: DeviceKind) -> bool {
    matches!(
        kind,
        DeviceKind::Alarms
            | DeviceKind::Dnd
            | DeviceKind::Sedentary
            | DeviceKind::PowerSaving
            | DeviceKind::DrinkReminders
            | DeviceKind::Profile
            | DeviceKind::Gestures
    )
}
pub fn capabilities(identity: &RingIdentity) -> Vec<DeviceCapability> {
    let mut proofs = production_proofs(identity);
    proofs.extend(
        production_actions(identity)
            .iter()
            .map(|(action, recipe)| SettingProof {
                kind: action.kind(),
                evidence_ref: recipe.evidence_ref,
                writable: true,
            }),
    );
    capabilities_with_proofs(identity, &proofs)
}
fn capabilities_with_proofs(
    identity: &RingIdentity,
    proofs: &[SettingProof],
) -> Vec<DeviceCapability> {
    use DeviceKind::*;
    [
        Alarms,
        DrinkReminders,
        Dnd,
        Sedentary,
        PowerSaving,
        Gestures,
        Counter,
        Profile,
        Clock,
        FindRing,
        Bind,
        Unbind,
        FactoryReset,
        PowerOff,
        Notifications,
        VibrationIntensity,
        Restart,
    ]
    .into_iter()
    .map(|kind| {
        let proof = proofs.iter().find(|p| p.kind == kind);
        let unsupported = matches!(kind, VibrationIntensity | Restart);
        DeviceCapability {
            kind,
            status: if proof.is_some_and(|p| p.writable) {
                CapabilityStatus::Verified
            } else if unsupported || !known_identity(identity) {
                CapabilityStatus::Unsupported
            } else {
                CapabilityStatus::ProtocolUnverified
            },
            reason: if proof.is_some_and(|p| p.writable) {
                "qualified_readback_recipe"
            } else if proof.is_some() {
                "write_recipe_unverified"
            } else if unsupported {
                "adapter_unsupported"
            } else if !known_identity(identity) {
                "unknown_identity"
            } else if kind == Gestures {
                "hid_mode_mapping_unverified"
            } else {
                "hardware_recipe_unverified"
            },
            readable: proof.is_some() && readable(kind),
            writable: proof.is_some_and(|p| p.writable) && !unsupported,
            evidence_ref: proof.map(|p| p.evidence_ref),
        }
    })
    .collect()
}

fn valid_slots(slots: &[AlarmSlot]) -> bool {
    slots.iter().enumerate().all(|(index, slot)| {
        slot.slot as usize == index && slot.hour < 24 && slot.minute < 60 && slot.weekdays <= 127
    })
}
fn alarm_snapshot(data: &[[u8; 14]], count: usize) -> Result<Vec<AlarmSlot>, &'static str> {
    if data.len() != count {
        return Err("incomplete_device_read");
    }
    data.iter()
        .enumerate()
        .map(|(index, payload)| {
            if payload[0] as usize != index
                || payload[1] > 1
                || payload[4..11].iter().any(|value| *value > 1)
            {
                return Err("unknown_alarm_format");
            }
            Ok(AlarmSlot {
                slot: payload[0],
                enabled: payload[1] == 1,
                hour: from_bcd(payload[2], 23)?,
                minute: from_bcd(payload[3], 59)?,
                weekdays: payload[4..11]
                    .iter()
                    .enumerate()
                    .fold(0, |bits, (index, value)| bits | value << index),
            })
        })
        .collect()
}
fn drink_payloads(
    slots: &[AlarmSlot; 8],
    before: &[[u8; 14]],
) -> Result<Vec<[u8; 14]>, &'static str> {
    alarm_snapshot(before, 8)?;
    if !valid_slots(slots) {
        return Err("invalid_setting");
    }
    Ok(slots
        .iter()
        .zip(before)
        .map(|(slot, prior)| {
            let mut payload = *prior;
            payload[..4].copy_from_slice(&[
                slot.slot,
                u8::from(slot.enabled),
                bcd(slot.hour),
                bcd(slot.minute),
            ]);
            for day in 0..7 {
                payload[4 + day] = (slot.weekdays >> day) & 1;
            }
            payload
        })
        .collect())
}
/// SDK generic counter-read candidate has no established Tasbih state meaning.
/// It is intentionally absent from the public read dispatcher and snapshot union.
pub fn candidate_sdk_counter_read() -> Vec<u8> {
    frame(0x7b, &[1, 1])
}
fn bcd(value: u8) -> u8 {
    ((value / 10) << 4) | (value % 10)
}
fn from_bcd(value: u8, max: u8) -> Result<u8, &'static str> {
    if value & 15 > 9 || value >> 4 > 9 {
        return Err("invalid_bcd");
    }
    let result = (value >> 4) * 10 + (value & 15);
    if result > max {
        Err("invalid_time")
    } else {
        Ok(result)
    }
}
fn frame(op: u8, payload: &[u8]) -> Vec<u8> {
    core_command(op, payload)
        .expect("bounded device payload")
        .to_vec()
}
/// Candidate request frames. A caller must still pass the compiled identity proof gate.
pub fn candidate_reads(kind: DeviceKind) -> Result<Vec<Vec<u8>>, &'static str> {
    Ok(match kind {
        DeviceKind::Alarms => (0..5).map(|slot| frame(0x24, &[slot])).collect(),
        DeviceKind::DrinkReminders => (0..8).map(|slot| frame(0x28, &[slot])).collect(),
        DeviceKind::Dnd => vec![frame(0x06, &[1])],
        DeviceKind::Sedentary => vec![frame(0x26, &[])],
        DeviceKind::PowerSaving => vec![frame(0x76, &[0])],
        DeviceKind::Profile => vec![frame(0x0a, &[1])],
        DeviceKind::Gestures => vec![frame(0x3b, &[1, 1])],
        _ => return Err("read_recipe_unverified"),
    })
}
/// Full read payloads are retained so unmodeled firmware fields survive a profile update.
pub fn candidate_writes(
    setting: &DeviceSetting,
    before: &[[u8; 14]],
) -> Result<Vec<Vec<u8>>, &'static str> {
    setting.validate()?;
    Ok(match setting {
        DeviceSetting::Alarms { slots } => slots
            .iter()
            .map(|s| {
                let mut p = vec![s.slot, u8::from(s.enabled), bcd(s.hour), bcd(s.minute)];
                p.extend((0..7).map(|day| (s.weekdays >> day) & 1));
                frame(0x23, &p)
            })
            .collect(),
        DeviceSetting::Dnd {
            enabled,
            start_minute,
            end_minute,
        } => vec![frame(
            0x06,
            &[
                2,
                if *enabled { 1 } else { 2 },
                (start_minute / 60) as u8,
                (start_minute % 60) as u8,
                (end_minute / 60) as u8,
                (end_minute % 60) as u8,
            ],
        )],
        DeviceSetting::Sedentary {
            enabled,
            start_minute,
            end_minute,
            weekdays,
            interval_minutes,
        } => vec![frame(
            0x25,
            &[
                bcd((start_minute / 60) as u8),
                bcd((start_minute % 60) as u8),
                bcd((end_minute / 60) as u8),
                bcd((end_minute % 60) as u8),
                if *enabled { *weekdays } else { 0 },
                *interval_minutes,
            ],
        )],
        DeviceSetting::PowerSaving { enabled } => vec![frame(0x76, &[1, u8::from(*enabled)])],
        DeviceSetting::DrinkReminders { slots } => {
            let expected = drink_payloads(slots, before)?;
            expected
                .iter()
                .zip(before)
                .filter(|(after, prior)| after != prior)
                .map(|(after, _)| frame(0x27, after))
                .collect()
        }
        DeviceSetting::Profile {
            sex,
            age,
            height_cm,
            weight_kg,
            is_24_hour,
            units,
            systolic_mm_hg,
            diastolic_mm_hg,
            heart_rate_warning_bpm,
        } => {
            let mut p = *before.first().ok_or("profile_read_required")?;
            if p[0] != 1 {
                return Err("profile_read_required");
            }
            p[..10].copy_from_slice(&[
                2,
                u8::from(!*is_24_hour),
                u8::from(*units == Units::Imperial),
                u8::from(*sex == Sex::Female),
                *age,
                *height_cm,
                *weight_kg,
                *systolic_mm_hg,
                *diastolic_mm_hg,
                *heart_rate_warning_bpm,
            ]);
            vec![frame(0x0a, &p)]
        }
        DeviceSetting::Clock {
            year,
            month,
            day,
            hour,
            minute,
            second,
            language,
        } => vec![frame(
            0x01,
            &[
                bcd((year % 100) as u8),
                bcd(*month),
                bcd(*day),
                bcd(*hour),
                bcd(*minute),
                bcd(*second),
                *language,
            ],
        )],
        DeviceSetting::Gestures { .. } => return Err("hid_mode_mapping_unverified"),
    })
}
pub fn candidate_action(action: DeviceAction) -> Result<Vec<u8>, &'static str> {
    match action {
        DeviceAction::FindRing => Ok(frame(0x50, &[0x55, 0xaa])),
        DeviceAction::FactoryReset => Ok(frame(0xff, &[0x66, 0x66])),
        DeviceAction::PowerOff => Ok(frame(0x08, &[1])),
        DeviceAction::TasbihStart => Ok(frame(0x7b, &[2, 3, 1, 0, 0, 0])),
        DeviceAction::TasbihStop => Ok(frame(0x7b, &[2, 3, 2, 0, 0, 0])),
        DeviceAction::Bind | DeviceAction::Unbind => Err("lifecycle_recipe_unverified"),
    }
}
pub fn candidate_notifications(message_type: u8, text: &str) -> Result<Vec<Vec<u8>>, &'static str> {
    if message_type > 9 || text.contains('\0') {
        return Err("invalid_notification");
    }
    let mut end = text.len().min(512);
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    let bytes = &text.as_bytes()[..end];
    if bytes.is_empty() {
        return Err("empty_notification");
    }
    let total = bytes.len().div_ceil(11) as u8;
    Ok(bytes
        .chunks(11)
        .enumerate()
        .map(|(index, part)| {
            let mut p = vec![message_type, total, (index + 1) as u8];
            p.extend(part);
            p.resize(14, 0);
            frame(0x72, &p)
        })
        .collect())
}
/// Soul DeviceNotify handler identifies tasbih count as core73/dataType38,
/// unsigned little-endian bytes2..3. No actual count capture has qualified it.
pub fn candidate_counter_event(bytes: &[u8]) -> Option<u16> {
    let frame = decode_core(bytes).ok()?;
    if frame.opcode != 0x73 || frame.payload[0] != 0x38 {
        return None;
    }
    Some(u16::from_le_bytes([frame.payload[2], frame.payload[3]]))
}
fn expected_response(
    kind: DeviceKind,
    index: usize,
    bytes: &[u8],
) -> Result<[u8; 14], &'static str> {
    let f = decode_core(bytes).map_err(|_| "invalid_device_response")?;
    let (op, prefix): (u8, Vec<u8>) = match kind {
        DeviceKind::Alarms => (0x24, vec![index as u8]),
        DeviceKind::DrinkReminders => (0x28, vec![index as u8]),
        DeviceKind::Dnd => (0x06, vec![1]),
        DeviceKind::Sedentary => (0x26, vec![]),
        DeviceKind::PowerSaving => (0x76, vec![0]),
        DeviceKind::Profile => (0x0a, vec![1]),
        DeviceKind::Gestures => (0x3b, vec![1, 1]),
        _ => return Err("read_recipe_unverified"),
    };
    if f.opcode != op || !f.payload.starts_with(&prefix) {
        return Err("unrelated_device_response");
    }
    Ok(f.payload)
}
pub fn candidate_snapshot(
    kind: DeviceKind,
    data: &[[u8; 14]],
) -> Result<DeviceSetting, &'static str> {
    let p = data.first().ok_or("incomplete_device_read")?;
    let binary_time = |h: u8, m: u8| {
        if h < 24 && m < 60 {
            Ok(h as u16 * 60 + m as u16)
        } else {
            Err("invalid_time")
        }
    };
    let setting = match kind {
        DeviceKind::Alarms => {
            if data.len() != 5 {
                return Err("incomplete_device_read");
            }
            let mut slots = Vec::new();
            for (index, p) in data.iter().enumerate() {
                if p[0] as usize != index || p[1] > 1 || p[4..11].iter().any(|v| *v > 1) {
                    return Err("unknown_alarm_format");
                }
                slots.push(AlarmSlot {
                    slot: p[0],
                    enabled: p[1] == 1,
                    hour: from_bcd(p[2], 23)?,
                    minute: from_bcd(p[3], 59)?,
                    weekdays: p[4..11]
                        .iter()
                        .enumerate()
                        .fold(0, |bits, (i, v)| bits | v << i),
                });
            }
            DeviceSetting::Alarms {
                slots: slots.try_into().map_err(|_| "incomplete_device_read")?,
            }
        }
        DeviceKind::Dnd => {
            if p[0] != 1 || !matches!(p[1], 1 | 2) {
                return Err("unknown_dnd_format");
            }
            DeviceSetting::Dnd {
                enabled: p[1] == 1,
                start_minute: binary_time(p[2], p[3])?,
                end_minute: binary_time(p[4], p[5])?,
            }
        }
        DeviceKind::Sedentary => DeviceSetting::Sedentary {
            enabled: p[4] != 0,
            start_minute: from_bcd(p[0], 23)? as u16 * 60 + from_bcd(p[1], 59)? as u16,
            end_minute: from_bcd(p[2], 23)? as u16 * 60 + from_bcd(p[3], 59)? as u16,
            weekdays: p[4],
            interval_minutes: p[5],
        },
        DeviceKind::PowerSaving => {
            if p[0] != 0 || p[1] > 1 {
                return Err("unknown_power_saving_format");
            }
            DeviceSetting::PowerSaving { enabled: p[1] == 1 }
        }
        DeviceKind::DrinkReminders => DeviceSetting::DrinkReminders {
            slots: alarm_snapshot(data, 8)?
                .try_into()
                .map_err(|_| "incomplete_device_read")?,
        },
        DeviceKind::Profile => {
            if p[0] != 1 || p[1..4].iter().any(|v| *v > 1) {
                return Err("unknown_profile_format");
            }
            DeviceSetting::Profile {
                is_24_hour: p[1] == 0,
                units: if p[2] == 0 {
                    Units::Metric
                } else {
                    Units::Imperial
                },
                sex: if p[3] == 0 { Sex::Male } else { Sex::Female },
                age: p[4],
                height_cm: p[5],
                weight_kg: p[6],
                systolic_mm_hg: p[7],
                diastolic_mm_hg: p[8],
                heart_rate_warning_bpm: p[9],
            }
        }
        _ => return Err("read_recipe_unverified"),
    };
    setting.validate()?;
    Ok(setting)
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Stage {
    Preflight,
    Writing,
    Readback,
    ActionReceipt,
    TransientWrite,
}
struct Pending {
    recovery: DeviceRecovery,
    stage: Stage,
    queue: VecDeque<Vec<u8>>,
    read_index: usize,
    read_data: Vec<[u8; 14]>,
    write_complete: bool,
    response: Option<[u8; 14]>,
    deadline: u64,
    reconciling: bool,
}
pub struct DeviceManager {
    identity: RingIdentity,
    proofs: Vec<SettingProof>,
    pending: Option<Pending>,
    receipts: BTreeMap<String, DeviceResult>,
    unknown: BTreeMap<String, DeviceRecovery>,
    actions: BTreeMap<DeviceAction, DeviceActionRecipe>,
}
impl DeviceManager {
    pub fn new(identity: RingIdentity) -> Result<Self, &'static str> {
        identity.validate()?;
        let mut proofs = production_proofs(&identity);
        let actions = production_actions(&identity);
        proofs.extend(actions.iter().map(|(action, recipe)| SettingProof {
            kind: action.kind(),
            evidence_ref: recipe.evidence_ref,
            writable: true,
        }));
        Ok(Self {
            identity,
            proofs,
            actions,
            pending: None,
            receipts: BTreeMap::new(),
            unknown: BTreeMap::new(),
        })
    }
    pub fn identity(&self) -> &RingIdentity {
        &self.identity
    }
    pub fn capabilities(&self) -> Vec<DeviceCapability> {
        capabilities_with_proofs(&self.identity, &self.proofs)
    }
    pub fn busy(&self) -> bool {
        self.pending.is_some()
    }
    pub fn has_operations(&self) -> bool {
        !self.receipts.is_empty() || !self.unknown.is_empty() || self.busy()
    }
    pub fn has_unresolved_mutation(&self) -> bool {
        !self.unknown.is_empty()
    }
    /// Native sends this only after the immutable terminal receipt is durably
    /// archived. Its replay lookup is then authoritative before creating a new request.
    pub fn receipt_stored(&mut self, operation_id: &str) {
        if self.unknown.contains_key(operation_id)
            || self
                .pending
                .as_ref()
                .is_some_and(|pending| pending.recovery.request.operation_id == operation_id)
        {
            return;
        }
        self.receipts.remove(operation_id);
    }
    pub fn prior_result(&self, request: &DeviceRequest) -> Option<DeviceResult> {
        self.receipts.get(&request.operation_id).map(|prior| {
            if prior.command == request.command {
                prior.clone()
            } else {
                Self::rejected(request.clone(), "operation_id_conflict")
            }
        })
    }
    fn rejected(request: DeviceRequest, reason: &'static str) -> DeviceResult {
        DeviceResult {
            operation_id: request.operation_id,
            command: request.command,
            status: ControlStatus::Rejected,
            reason,
            snapshot: None,
            wire_state: None,
        }
    }
    pub fn reject(request: DeviceRequest, reason: &'static str) -> DeviceEffect {
        DeviceEffect::Result(Self::rejected(request, reason))
    }
    pub fn capability_rejection(&self, command: &DeviceCommand) -> Option<&'static str> {
        self.capabilities()
            .iter()
            .find(|c| c.kind == command.kind())
            .and_then(|c| {
                if matches!(command, DeviceCommand::Read { .. }) && c.readable
                    || c.status == CapabilityStatus::Verified && c.writable
                {
                    None
                } else {
                    Some(c.reason)
                }
            })
    }
    pub fn request(&mut self, request: DeviceRequest, now_ms: u64) -> Vec<DeviceEffect> {
        if let Some(prior) = self.prior_result(&request) {
            return vec![DeviceEffect::Result(prior)];
        }
        let reason = if request.operation_id.is_empty()
            || request.operation_id.len() > 128
            || request.operation_id.contains('\0')
        {
            Some("invalid_operation_id")
        } else if let Some(reason) = self.capability_rejection(&request.command) {
            Some(reason)
        } else if self.busy() {
            Some("device_busy")
        } else if request.command.mutation() && self.has_unresolved_mutation() {
            Some("command_unknown")
        } else if self.receipts.len() >= MAX_OPERATIONS {
            Some("operation_capacity")
        } else {
            None
        };
        if let Some(reason) = reason {
            return vec![Self::reject(request, reason)];
        }
        if let DeviceCommand::Update { setting } = &request.command
            && let Err(reason) = setting.validate()
        {
            return vec![Self::reject(request, reason)];
        }
        let (stage, frames) = match &request.command {
            DeviceCommand::Action { action } => {
                let Some(recipe) = self.actions.get(action) else {
                    return vec![Self::reject(request, "action_receipt_unverified")];
                };
                if decode_core(&recipe.request).is_err()
                    || recipe.receipt_payload.len() > 14
                    || recipe.evidence_ref.is_empty()
                {
                    return vec![Self::reject(request, "invalid_action_recipe")];
                }
                (Stage::ActionReceipt, vec![recipe.request.clone()])
            }
            DeviceCommand::Notification { message_type, text } => {
                match candidate_notifications(*message_type, text) {
                    Ok(frames) => (Stage::TransientWrite, frames),
                    Err(reason) => return vec![Self::reject(request, reason)],
                }
            }
            _ => match candidate_reads(request.command.kind()) {
                Ok(reads) => (Stage::Preflight, reads),
                Err(reason) => return vec![Self::reject(request, reason)],
            },
        };
        self.receipts.insert(
            request.operation_id.clone(),
            DeviceResult {
                operation_id: request.operation_id.clone(),
                command: request.command.clone(),
                status: ControlStatus::Unknown,
                reason: "in_progress",
                snapshot: None,
                wire_state: None,
            },
        );
        self.pending = Some(Pending {
            recovery: DeviceRecovery {
                identity: self.identity.clone(),
                request,
                mutation_started: false,
                expected_readback: Vec::new(),
            },
            stage,
            queue: frames.into(),
            read_index: 0,
            read_data: Vec::new(),
            write_complete: false,
            response: None,
            deadline: 0,
            reconciling: false,
        });
        self.next(now_ms)
    }
    fn next(&mut self, now_ms: u64) -> Vec<DeviceEffect> {
        let p = self.pending.as_mut().expect("pending operation");
        let Some(bytes) = p.queue.front() else {
            return self.stage_finished(now_ms);
        };
        p.write_complete = false;
        p.response = None;
        p.deadline = now_ms.saturating_add(TIMEOUT_MS);
        let mut effects = Vec::new();
        if matches!(p.stage, Stage::Writing | Stage::ActionReceipt) {
            p.recovery.mutation_started = true;
            effects.push(DeviceEffect::Checkpoint(p.recovery.clone()));
        }
        effects.push(DeviceEffect::Write {
            bytes_hex: hex(bytes),
            query_id: format!(
                "device:{}:{}:{}",
                p.recovery.request.operation_id,
                if matches!(
                    p.stage,
                    Stage::Writing | Stage::ActionReceipt | Stage::TransientWrite
                ) {
                    "write"
                } else {
                    "read"
                },
                p.read_index
            ),
        });
        effects.push(DeviceEffect::Deadline { at_ms: p.deadline });
        effects
    }
    fn advance(&mut self, now_ms: u64) -> Vec<DeviceEffect> {
        let p = self.pending.as_mut().expect("pending operation");
        if !p.write_complete
            || !matches!(p.stage, Stage::Writing | Stage::TransientWrite) && p.response.is_none()
        {
            return Vec::new();
        }
        if let Some(response) = p.response.take() {
            p.read_data.push(response);
            p.read_index += 1;
        }
        p.queue.pop_front();
        self.next(now_ms)
    }
    fn stage_finished(&mut self, now_ms: u64) -> Vec<DeviceEffect> {
        let p = self.pending.as_mut().expect("pending operation");
        if p.stage == Stage::ActionReceipt {
            return self.finish(ControlStatus::Verified, "qualified_action_receipt", None);
        }
        if p.stage == Stage::TransientWrite {
            return self.finish(
                ControlStatus::Unknown,
                "notification_delivery_unconfirmed",
                None,
            );
        }
        if p.stage == Stage::Preflight
            && matches!(p.recovery.request.command, DeviceCommand::Update { .. })
            && !p.reconciling
        {
            if let Err(reason) = candidate_snapshot(p.recovery.request.command.kind(), &p.read_data)
            {
                return self.finish(ControlStatus::Rejected, reason, None);
            }
            let DeviceCommand::Update { setting } = &p.recovery.request.command else {
                unreachable!()
            };
            match candidate_writes(setting, &p.read_data) {
                Ok(writes) => {
                    p.recovery.expected_readback = match setting {
                        DeviceSetting::DrinkReminders { slots } => {
                            match drink_payloads(slots, &p.read_data) {
                                Ok(expected) => expected,
                                Err(reason) => {
                                    return self.finish(ControlStatus::Rejected, reason, None);
                                }
                            }
                        }
                        DeviceSetting::Profile { .. } => writes
                            .iter()
                            .map(|bytes| {
                                let mut payload =
                                    decode_core(bytes).expect("candidate frame").payload;
                                payload[0] = 1;
                                payload
                            })
                            .collect(),
                        _ => Vec::new(),
                    };
                    p.stage = Stage::Writing;
                    p.queue = writes.into();
                    p.read_data.clear();
                    p.read_index = 0;
                    return self.next(now_ms);
                }
                Err(reason) => return self.finish(ControlStatus::Rejected, reason, None),
            }
        }
        if p.stage == Stage::Writing {
            p.stage = Stage::Readback;
            p.queue = candidate_reads(p.recovery.request.command.kind())
                .expect("qualified read")
                .into();
            p.read_data.clear();
            p.read_index = 0;
            return self.next(now_ms);
        }
        let snapshot = match candidate_snapshot(p.recovery.request.command.kind(), &p.read_data) {
            Ok(snapshot) => snapshot,
            Err(reason) if matches!(p.recovery.request.command, DeviceCommand::Read { .. }) => {
                let wire_state = DeviceWireState {
                    kind: p.recovery.request.command.kind(),
                    raw_payload_hex: p.read_data.iter().map(|raw| hex(raw)).collect(),
                    semantic_status: reason,
                };
                let mut effects = self.finish(
                    ControlStatus::Verified,
                    "wire_read_confirmed_semantics_unknown",
                    None,
                );
                if let Some(DeviceEffect::Result(result)) = effects.first_mut() {
                    result.wire_state = Some(wire_state);
                    self.receipts
                        .insert(result.operation_id.clone(), result.clone());
                }
                return effects;
            }
            Err(reason) => return self.fail(reason),
        };
        if let DeviceCommand::Update { setting } = &p.recovery.request.command {
            let required_preserved_payloads = match setting {
                DeviceSetting::DrinkReminders { .. } => 8,
                DeviceSetting::Profile { .. } => 1,
                _ => 0,
            };
            if required_preserved_payloads > 0
                && p.recovery.expected_readback.len() != required_preserved_payloads
            {
                return self.fail("preserved_fields_unverifiable");
            }
            if !p.recovery.expected_readback.is_empty()
                && p.read_data != p.recovery.expected_readback
            {
                return self.fail("preserved_fields_readback_mismatch");
            }
            // Disabled sedentary reminders have a zero day mask on the wire.
            let normalized = match setting {
                DeviceSetting::Sedentary {
                    enabled: false,
                    start_minute,
                    end_minute,
                    interval_minutes,
                    ..
                } => DeviceSetting::Sedentary {
                    enabled: false,
                    start_minute: *start_minute,
                    end_minute: *end_minute,
                    weekdays: 0,
                    interval_minutes: *interval_minutes,
                },
                _ => setting.clone(),
            };
            if snapshot != normalized {
                return self.fail("readback_mismatch");
            }
        }
        self.finish(
            ControlStatus::Verified,
            "readback_confirmed",
            Some(snapshot),
        )
    }
    fn finish(
        &mut self,
        status: ControlStatus,
        reason: &'static str,
        snapshot: Option<DeviceSetting>,
    ) -> Vec<DeviceEffect> {
        let p = self.pending.take().expect("pending operation");
        let r = p.recovery;
        if status == ControlStatus::Unknown && r.mutation_started {
            self.unknown
                .insert(r.request.operation_id.clone(), r.clone());
        } else if status == ControlStatus::Verified {
            self.unknown.remove(&r.request.operation_id);
        }
        let result = DeviceResult {
            operation_id: r.request.operation_id,
            command: r.request.command,
            status,
            reason,
            snapshot,
            wire_state: None,
        };
        self.receipts
            .insert(result.operation_id.clone(), result.clone());
        vec![DeviceEffect::Result(result)]
    }
    fn fail(&mut self, reason: &'static str) -> Vec<DeviceEffect> {
        let unknown = self
            .pending
            .as_ref()
            .is_some_and(|p| p.recovery.mutation_started || p.reconciling);
        self.finish(
            if unknown {
                ControlStatus::Unknown
            } else {
                ControlStatus::Rejected
            },
            reason,
            None,
        )
    }
    pub fn write_complete(&mut self, success: bool, now_ms: u64) -> Vec<DeviceEffect> {
        if !self.busy() {
            return Vec::new();
        }
        if !success {
            return self.fail("write_outcome_unknown");
        }
        self.pending.as_mut().expect("pending").write_complete = true;
        self.advance(now_ms)
    }
    pub fn notification(&mut self, bytes: &[u8], now_ms: u64) -> (bool, Vec<DeviceEffect>) {
        let Some(p) = self.pending.as_mut() else {
            return (false, Vec::new());
        };
        if matches!(p.stage, Stage::Writing | Stage::TransientWrite) {
            return (false, Vec::new());
        }
        if p.stage == Stage::ActionReceipt {
            let DeviceCommand::Action { action } = &p.recovery.request.command else {
                return (false, Vec::new());
            };
            let Some(recipe) = self.actions.get(action) else {
                return (false, Vec::new());
            };
            let Ok(frame) = decode_core(bytes) else {
                return (false, Vec::new());
            };
            if frame.opcode != recipe.receipt_opcode
                || !frame.payload.starts_with(&recipe.receipt_payload)
                || frame.payload[recipe.receipt_payload.len()..]
                    .iter()
                    .any(|v| *v != 0)
            {
                return (false, Vec::new());
            }
            p.response = Some(frame.payload);
            return (true, self.advance(now_ms));
        }
        match expected_response(p.recovery.request.command.kind(), p.read_index, bytes) {
            Ok(payload) => {
                p.response = Some(payload);
                (true, self.advance(now_ms))
            }
            Err(_) => (false, Vec::new()),
        }
    }
    pub fn deadline(&mut self, now_ms: u64) -> Vec<DeviceEffect> {
        if self.pending.as_ref().is_some_and(|p| now_ms >= p.deadline) {
            self.fail("response_lost")
        } else {
            Vec::new()
        }
    }
    pub fn disconnected(&mut self) -> Vec<DeviceEffect> {
        if self.busy() {
            self.fail("disconnected")
        } else {
            Vec::new()
        }
    }
    pub fn restore_unknown(&mut self, recovery: DeviceRecovery) -> Vec<DeviceEffect> {
        let expected_valid = match &recovery.request.command {
            DeviceCommand::Update {
                setting: DeviceSetting::DrinkReminders { .. },
            } => recovery.expected_readback.len() == 8,
            DeviceCommand::Update {
                setting: DeviceSetting::Profile { .. },
            } => recovery.expected_readback.len() <= 1,
            _ => recovery.expected_readback.is_empty(),
        };
        if recovery.identity != self.identity
            || self.busy()
            || !recovery.mutation_started
            || !recovery.request.command.mutation()
            || !expected_valid
        {
            return vec![Self::reject(recovery.request, "invalid_recovery")];
        }
        if let Some(prior) = self.prior_result(&recovery.request) {
            return vec![DeviceEffect::Result(prior)];
        }
        if self.unknown.len() >= MAX_OPERATIONS {
            return vec![Self::reject(recovery.request, "operation_capacity")];
        }
        self.unknown
            .insert(recovery.request.operation_id.clone(), recovery.clone());
        let result = DeviceResult {
            operation_id: recovery.request.operation_id,
            command: recovery.request.command,
            status: ControlStatus::Unknown,
            reason: "process_interrupted",
            snapshot: None,
            wire_state: None,
        };
        self.receipts
            .insert(result.operation_id.clone(), result.clone());
        vec![DeviceEffect::Result(result)]
    }
    pub fn reconcile(&mut self, operation_id: &str, now_ms: u64) -> Vec<DeviceEffect> {
        let Some(recovery) = self.unknown.get(operation_id).cloned() else {
            return Vec::new();
        };
        if self.busy() {
            return vec![Self::reject(recovery.request, "device_busy")];
        }
        if let Some(reason) = self.capability_rejection(&recovery.request.command) {
            return vec![Self::reject(recovery.request, reason)];
        }
        let reads = match candidate_reads(recovery.request.command.kind()) {
            Ok(reads) => reads,
            Err(reason) => return vec![Self::reject(recovery.request, reason)],
        };
        self.pending = Some(Pending {
            recovery,
            stage: Stage::Readback,
            queue: reads.into(),
            read_index: 0,
            read_data: Vec::new(),
            write_complete: false,
            response: None,
            deadline: 0,
            reconciling: true,
        });
        self.next(now_ms)
    }
}

#[cfg(test)]
mod tests;
