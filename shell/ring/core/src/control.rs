//! Device controls use a trusted, exact firmware evidence registry. SDK command
//! candidates are not authority to write. No caller can supply a verified wire
//! recipe, and a lost mutation response is never retried automatically.
use crate::codec::{CoreFrame, core_command, decode_core, hex, unhex};
use crate::sports::{SportCapability, SportPlan, SportResult, SportStatus};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const CONTROL_TIMEOUT_MS: u64 = 8_000;
const MANUAL_HEART_RATE_TIMEOUT_MS: u64 = 45_000;
const MAX_OPERATIONS: usize = 256;

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct RingIdentity {
    pub model: String,
    pub hardware: String,
    pub firmware: String,
}
impl RingIdentity {
    pub fn validate(&self) -> Result<(), &'static str> {
        for value in [&self.model, &self.hardware, &self.firmware] {
            if value.is_empty() || value.len() > 256 || value.contains('\0') {
                return Err("invalid_identity");
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SettingKind {
    HeartRate,
    Spo2,
    Temperature,
    Hrv,
    Stress,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Metric {
    HeartRate,
    Spo2,
    Temperature,
    Hrv,
    Stress,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum WorkoutKind {
    Run,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum CapabilityKind {
    HeartRateSchedule,
    HeartRateAlerts,
    Spo2Schedule,
    TemperatureSchedule,
    HrvSchedule,
    StressSchedule,
    Workout,
    LiveHeartRate,
    ManualHeartRate,
    ManualSpo2,
    ManualTemperature,
    ManualHrv,
    ManualStress,
}
const CAPABILITY_KINDS: [CapabilityKind; 13] = [
    CapabilityKind::HeartRateSchedule,
    CapabilityKind::HeartRateAlerts,
    CapabilityKind::Spo2Schedule,
    CapabilityKind::TemperatureSchedule,
    CapabilityKind::HrvSchedule,
    CapabilityKind::StressSchedule,
    CapabilityKind::Workout,
    CapabilityKind::LiveHeartRate,
    CapabilityKind::ManualHeartRate,
    CapabilityKind::ManualSpo2,
    CapabilityKind::ManualTemperature,
    CapabilityKind::ManualHrv,
    CapabilityKind::ManualStress,
];
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum CapabilityStatus {
    Verified,
    ProtocolUnverified,
    Unsupported,
}
#[derive(Debug, Clone, Serialize)]
pub struct Capability {
    pub kind: CapabilityKind,
    pub status: CapabilityStatus,
    pub reason: &'static str,
    /// A captured query/response does not qualify mutation or autonomous sampling.
    pub readable: bool,
    pub writable: bool,
    pub evidence_ref: Option<&'static str>,
    pub supported_intervals_minutes: Vec<u16>,
    /// Native recovery may offer stop only with firmware evidence for it.
    pub safe_stop_verified: bool,
    /// The verified recipe enables a device-owned night mode without phone
    /// timers or a continuously connected transport.
    pub autonomous_night: bool,
}
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum ControlCommand {
    ReadSettings {
        kind: SettingKind,
    },
    SetSchedule {
        kind: SettingKind,
        enabled: bool,
        #[serde(default)]
        interval_minutes: Option<u16>,
    },
    SetHeartRateAlerts {
        low_bpm: u8,
        high_bpm: u8,
    },
    StartWorkout {
        kind: WorkoutKind,
    },
    StopWorkout,
    StartSport {
        plan: SportPlan,
    },
    PauseSport {
        plan: SportPlan,
    },
    ResumeSport {
        plan: SportPlan,
    },
    StopSport {
        plan: SportPlan,
    },
    StartLiveHeartRate,
    StopLiveHeartRate,
    MeasureOnce {
        metric: Metric,
    },
}
impl ControlCommand {
    fn capability(&self) -> CapabilityKind {
        match self {
            Self::SetHeartRateAlerts { .. } => CapabilityKind::HeartRateAlerts,
            Self::ReadSettings { kind } | Self::SetSchedule { kind, .. } => match kind {
                SettingKind::HeartRate => CapabilityKind::HeartRateSchedule,
                SettingKind::Spo2 => CapabilityKind::Spo2Schedule,
                SettingKind::Temperature => CapabilityKind::TemperatureSchedule,
                SettingKind::Hrv => CapabilityKind::HrvSchedule,
                SettingKind::Stress => CapabilityKind::StressSchedule,
            },
            Self::StartWorkout { .. }
            | Self::StopWorkout
            | Self::StartSport { .. }
            | Self::PauseSport { .. }
            | Self::ResumeSport { .. }
            | Self::StopSport { .. } => CapabilityKind::Workout,
            Self::StartLiveHeartRate | Self::StopLiveHeartRate => CapabilityKind::LiveHeartRate,
            Self::MeasureOnce { metric } => match metric {
                Metric::HeartRate => CapabilityKind::ManualHeartRate,
                Metric::Spo2 => CapabilityKind::ManualSpo2,
                Metric::Temperature => CapabilityKind::ManualTemperature,
                Metric::Hrv => CapabilityKind::ManualHrv,
                Metric::Stress => CapabilityKind::ManualStress,
            },
        }
    }
}
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ControlRequest {
    pub operation_id: String,
    pub command: ControlCommand,
}
/// Durable intent, written by the native owner before delivering a mutation to
/// GATT. Restoring it can only restore an unknown result and read-only checks.
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ControlRecovery {
    pub identity: RingIdentity,
    pub operation_id: String,
    pub command: ControlCommand,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expected_settings_raw_hex: Option<String>,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ControlStatus {
    Verified,
    Rejected,
    Unknown,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SettingsSnapshot {
    pub kind: SettingKind,
    pub enabled: bool,
    pub interval_minutes: Option<u16>,
    pub raw_payload_hex: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub heart_rate_alerts: Option<HeartRateAlerts>,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct HeartRateAlerts {
    pub low_bpm: u8,
    pub high_bpm: u8,
    /// Soul's actual caller does not assign the SDK overload's boolean meaning.
    pub main_switch_raw: u8,
    pub qualification: &'static str,
}
#[derive(Debug, Clone, Serialize)]
pub struct Observation {
    pub metric: Metric,
    pub value: f64,
    pub unit: &'static str,
    /// This is receipt time, never a fabricated device sample timestamp.
    pub received_monotonic_ms: u64,
    pub timestamp_basis: &'static str,
    pub sample_quality: &'static str,
}
#[derive(Debug, Clone, Serialize)]
pub struct ControlResult {
    pub operation_id: String,
    pub command: ControlCommand,
    pub status: ControlStatus,
    pub reason: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub settings: Option<SettingsSnapshot>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sport: Option<SportResult>,
}
#[derive(Debug, Clone)]
pub enum ControlEffect {
    Checkpoint(ControlRecovery),
    Write {
        bytes_hex: String,
        query_id: String,
    },
    Deadline {
        at_ms: u64,
    },
    Result(ControlResult),
    Observation {
        operation_id: String,
        observation: Observation,
    },
    Progress(ControlProgress),
    LiveHeartRate(Observation),
    Diagnostic {
        code: &'static str,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum MeasurementPhase {
    Starting,
    Measuring,
    Stopping,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum MeasurementStopReason {
    Result,
    Cancelled,
    DeviceError,
    Timeout,
}
#[derive(Debug, Clone, Serialize)]
pub struct ControlProgress {
    pub operation_id: String,
    pub command: ControlCommand,
    pub phase: MeasurementPhase,
    pub started_at_ms: u64,
    pub deadline_at_ms: u64,
    pub stop_reason: Option<MeasurementStopReason>,
}

// Recipes below are private trusted dependencies. Future hardware evidence adds
// a static exact-identity profile here, never a native config or runtime switch.
#[derive(Debug, Clone)]
struct FramePattern {
    opcode: u8,
    prefix: Vec<u8>,
    zero_tail: bool,
}
impl FramePattern {
    fn valid(&self) -> bool {
        !self.prefix.is_empty() && self.prefix.len() <= 14
    }
    fn matches(&self, frame: &CoreFrame) -> bool {
        frame.opcode == self.opcode
            && frame.payload.starts_with(&self.prefix)
            && (!self.zero_tail || frame.payload[self.prefix.len()..].iter().all(|b| *b == 0))
    }
}
#[derive(Debug, Clone)]
struct SettingsRecipe {
    opcode: u8,
    read_payload: Vec<u8>,
    read_response: FramePattern,
    enabled_index: usize,
    enabled_on: u8,
    enabled_off: u8,
    interval_index: Option<usize>,
    write_template: Vec<u8>,
    write_enabled_index: usize,
    write_interval_index: Option<usize>,
    /// (read index, write index). HR preserves every field beyond sampling
    /// enable/interval, including opaque bytes; SDK defaults must not modify them.
    preserved_fields: Vec<(usize, usize)>,
    acknowledgement: FramePattern,
    intervals: Vec<u16>,
    autonomous_night: bool,
}
impl SettingsRecipe {
    fn snapshot(&self, kind: SettingKind, raw: [u8; 14]) -> Result<SettingsSnapshot, &'static str> {
        let enabled = match raw[self.enabled_index] {
            value if value == self.enabled_on => true,
            value if value == self.enabled_off => false,
            _ => return Err("invalid_settings_response"),
        };
        Ok(SettingsSnapshot {
            kind,
            enabled,
            interval_minutes: self.interval_index.and_then(|index| {
                if raw[index] == 0 {
                    None // Zero is an unresolved firmware/default mode, never a zero-minute schedule.
                } else {
                    Some(u16::from(raw[index]))
                }
            }),
            raw_payload_hex: hex(&raw),
            heart_rate_alerts: (kind == SettingKind::HeartRate && self.opcode == 0x16).then_some(
                HeartRateAlerts {
                    low_bpm: raw[4],
                    high_bpm: raw[5],
                    main_switch_raw: raw[6],
                    qualification: "source_only",
                },
            ),
        })
    }
    fn mutation(
        &self,
        raw: [u8; 14],
        enabled: bool,
        interval: Option<u16>,
    ) -> Result<(Vec<u8>, [u8; 14]), &'static str> {
        if let Some(interval) = interval
            && (!self.intervals.contains(&interval)
                || interval > 255
                || self.write_interval_index.is_none())
        {
            return Err("unsupported_interval");
        }
        let mut payload = self.write_template.clone();
        let mut expected = raw;
        for (read_index, write_index) in &self.preserved_fields {
            payload[*write_index] = raw[*read_index];
        }
        let enabled = if enabled {
            self.enabled_on
        } else {
            self.enabled_off
        };
        payload[self.write_enabled_index] = enabled;
        expected[self.enabled_index] = enabled;
        if let Some(index) = self.write_interval_index {
            if let Some(interval) = interval {
                payload[index] = interval as u8;
                if let Some(read_index) = self.interval_index {
                    expected[read_index] = interval as u8;
                }
            } else if let Some(read_index) = self.interval_index {
                payload[index] = raw[read_index];
            }
        }
        Ok((payload, expected))
    }
    fn confirms(&self, expected: &[u8; 14], actual: &[u8; 14]) -> bool {
        if self.opcode == 0x16 && self.enabled_index == 1 && self.interval_index == Some(2) {
            // The HR schedule setter preserves the entire fresh read. A change
            // to an opaque field is still an unconfirmed mutation.
            return expected == actual;
        }
        expected[self.enabled_index] == actual[self.enabled_index]
            && self
                .interval_index
                .is_none_or(|index| expected[index] == actual[index])
            && self
                .preserved_fields
                .iter()
                .all(|(index, _)| expected[*index] == actual[*index])
    }
    fn alerts_mutation(
        &self,
        raw: [u8; 14],
        low_bpm: u8,
        high_bpm: u8,
    ) -> Result<(Vec<u8>, [u8; 14]), &'static str> {
        if self.opcode != 0x16 || self.enabled_index != 1 || self.interval_index != Some(2) {
            return Err("unsupported_alerts_recipe");
        }
        // Preserve every other freshly read field, including unresolved start,
        // main-switch and tail bytes. Changing alerts never enables sampling.
        let mut expected = raw;
        expected[4] = low_bpm;
        expected[5] = high_bpm;
        let mut payload = expected.to_vec();
        payload[0] = 2;
        Ok((payload, expected))
    }
}
#[derive(Debug, Clone)]
struct StateQuery {
    opcode: u8,
    payload: Vec<u8>,
    desired: FramePattern,
    opposite: FramePattern,
}
#[derive(Debug, Clone)]
struct ActionRecipe {
    opcode: u8,
    payload: Vec<u8>,
    acknowledgement: FramePattern,
    state_query: Option<StateQuery>,
    /// Only a firmware-proven idempotent stop may run while another mutation
    /// has an unknown outcome. This is evidence, not a caller permission bit.
    safe_stop: bool,
}
#[derive(Debug, Clone)]
struct ObservationRecipe {
    metric: Metric,
    pattern: FramePattern,
    value_index: usize,
    /// The exact width/scale comes from firmware evidence; no layout guessing.
    width: usize,
    little_endian: bool,
    scale: f64,
    unit: &'static str,
    /// Wire acceptance bounds, including documented invalid-result sentinels.
    min_raw: u16,
    max_raw: u16,
    /// Numeric validity is separate from wire acceptance. An accepted packet
    /// may carry an invalid value that must remain in the actual sample stream.
    min_valid_raw: u16,
    max_valid_raw: u16,
}
impl ObservationRecipe {
    fn valid(&self) -> bool {
        self.pattern.valid()
            && self.value_index < 14
            && matches!(self.width, 1 | 2)
            && self.value_index + self.width <= 14
            && self.scale.is_finite()
            && self.scale > 0.0
            && self.min_raw <= self.max_raw
            && self.min_valid_raw <= self.max_valid_raw
            && self.min_valid_raw >= self.min_raw
            && self.max_valid_raw <= self.max_raw
            && (f64::from(self.max_raw) * self.scale).is_finite()
    }
    fn parse(&self, frame: &CoreFrame, now_ms: u64) -> Option<Observation> {
        if !self.pattern.matches(frame) {
            return None;
        }
        let raw = match self.width {
            1 => u16::from(frame.payload[self.value_index]),
            2 if self.value_index < 13 => {
                let bytes = [
                    frame.payload[self.value_index],
                    frame.payload[self.value_index + 1],
                ];
                if self.little_endian {
                    u16::from_le_bytes(bytes)
                } else {
                    u16::from_be_bytes(bytes)
                }
            }
            _ => return None,
        };
        if raw < self.min_raw || raw > self.max_raw {
            return None;
        }
        Some(Observation {
            metric: self.metric,
            value: f64::from(raw) * self.scale,
            unit: self.unit,
            received_monotonic_ms: now_ms,
            timestamp_basis: "received_monotonic",
            sample_quality: if raw < self.min_valid_raw || raw > self.max_valid_raw {
                "invalid"
            } else {
                "valid"
            },
        })
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum ActionKind {
    StartWorkout,
    StopWorkout,
    StartLiveHeartRate,
    StopLiveHeartRate,
}
fn action_kind(command: &ControlCommand) -> Option<ActionKind> {
    match command {
        ControlCommand::StartWorkout { .. } => Some(ActionKind::StartWorkout),
        ControlCommand::StopWorkout => Some(ActionKind::StopWorkout),
        ControlCommand::StartLiveHeartRate => Some(ActionKind::StartLiveHeartRate),
        ControlCommand::StopLiveHeartRate => Some(ActionKind::StopLiveHeartRate),
        _ => None,
    }
}
fn sport_command(command: &ControlCommand) -> Option<(SportStatus, SportPlan)> {
    match command {
        ControlCommand::StartSport { plan } => Some((SportStatus::Start, *plan)),
        ControlCommand::PauseSport { plan } => Some((SportStatus::Pause, *plan)),
        ControlCommand::ResumeSport { plan } => Some((SportStatus::Resume, *plan)),
        ControlCommand::StopSport { plan } => Some((SportStatus::Stop, *plan)),
        _ => None,
    }
}
#[derive(Debug, Clone)]
struct SportRecipe {
    action: ActionRecipe,
    actual_wire_mode: u8,
    evidence_ref: &'static str,
}
#[derive(Debug, Clone)]
struct MeasurementRecipe {
    opcode: u8,
    payload: Vec<u8>,
    result: ObservationRecipe,
    timeout_ms: u64,
    // Source-only candidates have no qualified final-stop format. They cannot
    // become executable merely by changing a capability flag.
    stop: Option<MeasurementStopRecipe>,
}
#[derive(Debug, Clone)]
struct MeasurementStopRecipe {
    opcode: u8,
    payload: Vec<u8>,
    result_type: u8,
    result: ObservationRecipe,
}
impl MeasurementStopRecipe {
    fn valid(&self) -> bool {
        !self.payload.is_empty()
            && self.payload.len() <= 14
            && self.result.valid()
            && self.result.pattern.opcode == self.opcode
            && self.result.pattern.prefix == [self.result_type]
            && self.result.value_index == 1
            && self.result.width == 1
    }
    fn parse(&self, frame: &CoreFrame, now_ms: u64) -> Option<Observation> {
        // The observed RF20 stop response is [type, value, 0...], rather
        // than the generic SDK's [type, error, value]. No layout heuristic.
        if frame.payload[2..].iter().any(|byte| *byte != 0) {
            return None;
        }
        self.result
            .parse(frame, now_ms)
            .filter(|value| value.sample_quality == "valid")
    }
}
impl MeasurementRecipe {
    fn valid(&self) -> bool {
        !self.payload.is_empty()
            && self.payload.len() <= 14
            && self.result.valid()
            && self.result.pattern.opcode == self.opcode
            && self.result.pattern.prefix.len() == 2
            && self.result.pattern.prefix[1] == 0
            && self.timeout_ms > 0
            && self.timeout_ms <= 90_000
            && self.stop.as_ref().is_none_or(|stop| {
                stop.valid()
                    && stop.result.metric == self.result.metric
                    && stop.result_type == self.result.pattern.prefix[0]
            })
    }
}
fn manual_heart_rate_recipe() -> MeasurementRecipe {
    let result = ObservationRecipe {
        metric: Metric::HeartRate,
        pattern: FramePattern {
            opcode: 0x69,
            prefix: vec![1, 0],
            zero_tail: false,
        },
        value_index: 2,
        width: 1,
        little_endian: true,
        scale: 1.0,
        unit: "bpm",
        min_raw: 0,
        max_raw: 255,
        min_valid_raw: 30,
        max_valid_raw: 220,
    };
    MeasurementRecipe {
        opcode: 0x69,
        payload: vec![1, 0, 0],
        timeout_ms: MANUAL_HEART_RATE_TIMEOUT_MS,
        stop: Some(MeasurementStopRecipe {
            opcode: 0x6a,
            payload: vec![1, 0, 0],
            result_type: 1,
            result: ObservationRecipe {
                pattern: FramePattern {
                    opcode: 0x6a,
                    prefix: vec![1],
                    zero_tail: false,
                },
                value_index: 1,
                ..result.clone()
            },
        }),
        result,
    }
}
// Soul 1.0.1214's Oudmon stress codecs are candidates only. The compiled
// capability gates stay unreadable/unwritable until exact hardware evidence
// qualifies the response, acknowledgement and physical behavior separately.
fn stress_schedule_candidate() -> SettingsRecipe {
    SettingsRecipe {
        opcode: 0x36,
        read_payload: vec![1],
        read_response: FramePattern {
            opcode: 0x36,
            prefix: vec![1],
            zero_tail: false,
        },
        enabled_index: 1,
        enabled_on: 1,
        enabled_off: 0,
        interval_index: None,
        write_template: vec![2, 1],
        write_enabled_index: 1,
        write_interval_index: None,
        preserved_fields: Vec::new(),
        acknowledgement: FramePattern {
            opcode: 0x36,
            prefix: vec![2],
            zero_tail: true,
        },
        intervals: Vec::new(),
        autonomous_night: false,
    }
}
fn stress_measurement_candidate() -> MeasurementRecipe {
    MeasurementRecipe {
        opcode: 0x69,
        payload: vec![8, 0x25, 0],
        timeout_ms: 45_000,
        stop: None,
        result: ObservationRecipe {
            metric: Metric::Stress,
            pattern: FramePattern {
                opcode: 0x69,
                prefix: vec![8, 0],
                zero_tail: false,
            },
            value_index: 2,
            width: 1,
            little_endian: true,
            scale: 1.0,
            unit: "index",
            min_raw: 0,
            max_raw: 255,
            min_valid_raw: 1,
            max_valid_raw: 255,
        },
    }
}
#[derive(Debug, Clone)]
struct Profile {
    capabilities: Vec<Capability>,
    settings: BTreeMap<SettingKind, SettingsRecipe>,
    actions: BTreeMap<ActionKind, ActionRecipe>,
    sports: BTreeMap<(u16, SportStatus), SportRecipe>,
    measurements: BTreeMap<Metric, MeasurementRecipe>,
    live: Option<ObservationRecipe>,
}
impl Profile {
    fn capabilities(&self) -> Vec<Capability> {
        self.capabilities
            .iter()
            .map(|capability| {
                let mut capability = capability.clone();
                if !self.valid() {
                    capability.readable = false;
                    capability.writable = false;
                }
                let verified = self.valid() && capability.status == CapabilityStatus::Verified;
                let stop = match capability.kind {
                    CapabilityKind::Workout => Some(ActionKind::StopWorkout),
                    CapabilityKind::LiveHeartRate => Some(ActionKind::StopLiveHeartRate),
                    _ => None,
                };
                let setting = match capability.kind {
                    CapabilityKind::HeartRateSchedule => Some(SettingKind::HeartRate),
                    CapabilityKind::Spo2Schedule => Some(SettingKind::Spo2),
                    CapabilityKind::TemperatureSchedule => Some(SettingKind::Temperature),
                    CapabilityKind::HrvSchedule => Some(SettingKind::Hrv),
                    CapabilityKind::StressSchedule => Some(SettingKind::Stress),
                    _ => None,
                };
                capability.safe_stop_verified = verified
                    && stop
                        .and_then(|kind| self.actions.get(&kind))
                        .is_some_and(|recipe| recipe.safe_stop);
                capability.autonomous_night = verified
                    && setting
                        .and_then(|kind| self.settings.get(&kind))
                        .is_some_and(|recipe| recipe.autonomous_night);
                capability
            })
            .collect()
    }
    fn valid(&self) -> bool {
        self.settings.values().all(|recipe| {
            recipe.read_payload.len() <= 14
                && recipe.read_response.valid()
                && recipe.acknowledgement.valid()
                && recipe.enabled_index < 14
                && recipe.enabled_on != recipe.enabled_off
                && recipe.interval_index.is_none_or(|index| index < 14)
                && !recipe.write_template.is_empty()
                && recipe.write_template.len() <= 14
                && recipe.write_enabled_index < recipe.write_template.len()
                && recipe
                    .write_interval_index
                    .is_none_or(|index| index < recipe.write_template.len())
                && recipe.preserved_fields.iter().all(|(read, write)| {
                    *read < 14
                        && *write < recipe.write_template.len()
                        && *write != recipe.write_enabled_index
                        && Some(*write) != recipe.write_interval_index
                })
        }) && self.sports.iter().all(|((requested_id, status), recipe)| {
            SportPlan {
                requested_id: *requested_id,
            }
            .entry()
            .is_ok_and(|entry| entry.candidate_wire_mode == Some(recipe.actual_wire_mode))
                && recipe.action.opcode == 0x77
                && recipe.action.payload == [status.wire(), recipe.actual_wire_mode]
                && !recipe.evidence_ref.is_empty()
                && recipe.action.acknowledgement.valid()
                && recipe.action.state_query.as_ref().is_none_or(|query| {
                    query.payload.len() <= 14 && query.desired.valid() && query.opposite.valid()
                })
        }) && self.actions.values().all(|recipe| {
            recipe.payload.len() <= 14
                && recipe.acknowledgement.valid()
                && recipe.state_query.as_ref().is_none_or(|query| {
                    query.payload.len() <= 14 && query.desired.valid() && query.opposite.valid()
                })
        }) && self
            .measurements
            .iter()
            .all(|(metric, recipe)| recipe.result.metric == *metric && recipe.valid())
            && self
                .live
                .as_ref()
                .is_none_or(|recipe| recipe.metric == Metric::HeartRate && recipe.valid())
    }
}
fn production_profile(identity: &RingIdentity) -> Profile {
    let known = identity.model == "qring-r20"
        && identity.hardware == "RF20_V2.1"
        && identity.firmware == "RF20_2.10.07_260131";
    let mut profile = Profile {
        capabilities: CAPABILITY_KINDS
            .iter()
            .map(|kind| Capability {
                kind: *kind,
                status: if known {
                    CapabilityStatus::ProtocolUnverified
                } else {
                    CapabilityStatus::Unsupported
                },
                reason: if known {
                    "protocol_unverified"
                } else {
                    "unknown_identity"
                },
                readable: false,
                writable: false,
                evidence_ref: None,
                supported_intervals_minutes: Vec::new(),
                safe_stop_verified: false,
                autonomous_night: false,
            })
            .collect(),
        settings: BTreeMap::new(),
        actions: BTreeMap::new(),
        sports: BTreeMap::new(),
        measurements: BTreeMap::new(),
        live: None,
    };
    if known {
        profile
            .measurements
            .insert(Metric::HeartRate, manual_heart_rate_recipe());
        if let Some(capability) = profile
            .capabilities
            .iter_mut()
            .find(|capability| capability.kind == CapabilityKind::ManualHeartRate)
        {
            capability.status = CapabilityStatus::Verified;
            capability.writable = true;
            capability.reason = "qualified_manual_measurement";
            capability.evidence_ref = Some("soul-rf20-manual-heart-rate-20261010");
            // The completed positive-result cycle does not prove idempotent
            // cancellation/recovery of an earlier unknown measurement.
            capability.safe_stop_verified = false;
        }
        profile
            .settings
            .insert(SettingKind::Stress, stress_schedule_candidate());
        profile
            .measurements
            .insert(Metric::Stress, stress_measurement_candidate());
        // Actual Soul ATT query/notification pairs, tied to this exact HW/FW.
        // Transport capture contains no proof of physical/autonomous write behavior.
        for (kind, opcode, enabled_off, interval_index, evidence_ref) in [
            (
                SettingKind::HeartRate,
                0x16,
                2,
                Some(2),
                "soul-att-rf20-20261005:8490-8495",
            ),
            (
                SettingKind::Hrv,
                0x38,
                0,
                None,
                "soul-att-rf20-20261005:8496-8499",
            ),
            (
                SettingKind::Spo2,
                0x2c,
                2,
                Some(2),
                "soul-att-rf20-20261005:8504-8507",
            ),
        ] {
            profile.settings.insert(
                kind,
                SettingsRecipe {
                    opcode,
                    read_payload: vec![1],
                    read_response: FramePattern {
                        opcode,
                        prefix: vec![1],
                        zero_tail: false,
                    },
                    enabled_index: 1,
                    enabled_on: 1,
                    enabled_off,
                    interval_index,
                    // Candidate encoding is retained, but writable=false is the authoritative gate.
                    write_template: if kind == SettingKind::HeartRate {
                        vec![2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
                    } else {
                        vec![2, 1, 0, 0, 0, 0, 0]
                    },
                    write_enabled_index: 1,
                    write_interval_index: interval_index,
                    preserved_fields: if kind == SettingKind::HeartRate {
                        (3..14).map(|index| (index, index)).collect()
                    } else {
                        Vec::new()
                    },
                    acknowledgement: FramePattern {
                        opcode,
                        // Exact observed enabled-HR ACK only. Byte 1 has no
                        // qualified meaning; disabled ACKs and writes remain
                        // unqualified, and ACK alone cannot verify settings.
                        prefix: if kind == SettingKind::HeartRate {
                            vec![2, 1]
                        } else {
                            vec![2]
                        },
                        zero_tail: true,
                    },
                    intervals: Vec::new(),
                    autonomous_night: false,
                },
            );
            let capability_kind = ControlCommand::ReadSettings { kind }.capability();
            if let Some(capability) = profile
                .capabilities
                .iter_mut()
                .find(|c| c.kind == capability_kind)
            {
                capability.readable = true;
                capability.writable = false;
                capability.evidence_ref = Some(evidence_ref);
                capability.reason = "write_recipe_unverified";
            }
        }
        if let Some(capability) = profile
            .capabilities
            .iter_mut()
            .find(|c| c.kind == CapabilityKind::HeartRateAlerts)
        {
            capability.readable = true;
            capability.reason = "write_recipe_unverified";
            capability.evidence_ref = Some("soul-att-rf20-20261005:8490-8495");
        }
    }
    profile
}
pub fn capabilities(identity: &RingIdentity) -> Vec<Capability> {
    production_profile(identity).capabilities()
}

#[derive(Debug, Clone)]
enum Phase {
    ReadSettings {
        kind: SettingKind,
        recipe: SettingsRecipe,
        target: Option<SettingsTarget>,
    },
    MutationAck {
        pattern: FramePattern,
        followup: Option<Check>,
    },
    Check(Check),
    Measurement {
        recipe: MeasurementRecipe,
        started_at_ms: u64,
        stop_reason: Option<MeasurementStopReason>,
    },
    MeasurementStop {
        recipe: MeasurementStopRecipe,
        started_at_ms: u64,
        stop_reason: MeasurementStopReason,
        final_deadline_ms: u64,
    },
}
#[derive(Debug, Clone, Copy)]
enum SettingsTarget {
    Schedule(bool, Option<u16>),
    Alerts(u8, u8),
}
#[derive(Debug, Clone)]
enum Check {
    Settings {
        kind: SettingKind,
        recipe: SettingsRecipe,
        expected: [u8; 14],
        exact: bool,
    },
    State(StateQuery),
}
#[derive(Debug, Clone)]
enum Response {
    Settings([u8; 14]),
    Ack,
    State(bool),
    Measurement(Observation),
    MeasurementError,
}
struct Pending {
    operation_id: String,
    phase: Phase,
    deadline: u64,
    issued_at_ms: u64,
    write_complete: bool,
    response: Option<Response>,
    mutated: bool,
}
struct Record {
    result: ControlResult,
    check: Option<Check>,
    blocks_mutations: bool,
}
pub struct ControlManager {
    identity: RingIdentity,
    profile: Profile,
    records: BTreeMap<String, Record>,
    pending: Option<Pending>,
    live_active: bool,
}
impl ControlManager {
    pub fn new(identity: RingIdentity) -> Result<Self, &'static str> {
        identity.validate()?;
        let profile = production_profile(&identity);
        Ok(Self {
            identity,
            profile,
            records: BTreeMap::new(),
            pending: None,
            live_active: false,
        })
    }
    pub fn identity(&self) -> &RingIdentity {
        &self.identity
    }
    pub fn capabilities(&self) -> Vec<Capability> {
        self.profile.capabilities()
    }
    pub fn sport_capabilities(&self) -> Vec<SportCapability> {
        crate::sports::source_catalog()
            .iter()
            .map(|entry| {
                let proof = |status| {
                    self.profile
                        .sports
                        .get(&(entry.requested_id, status))
                        .filter(|_| self.profile.valid())
                };
                let stop = proof(SportStatus::Stop);
                let any = [
                    SportStatus::Start,
                    SportStatus::Pause,
                    SportStatus::Resume,
                    SportStatus::Stop,
                ]
                .into_iter()
                .find_map(proof);
                SportCapability {
                    requested_id: entry.requested_id,
                    start: proof(SportStatus::Start).is_some(),
                    pause: proof(SportStatus::Pause).is_some(),
                    resume: proof(SportStatus::Resume).is_some(),
                    stop: stop.is_some(),
                    safe_stop_verified: stop.is_some_and(|recipe| recipe.action.safe_stop),
                    reason: if any.is_some() {
                        "qualified_sport_recipe"
                    } else if entry.candidate_wire_mode.is_none() {
                        "source_wire_mapping_unknown"
                    } else {
                        "sport_mode_protocol_unverified"
                    },
                    evidence_ref: any.map(|recipe| recipe.evidence_ref),
                }
            })
            .collect()
    }
    fn command_recipe(&self, command: &ControlCommand) -> Option<&ActionRecipe> {
        if let Some((status, plan)) = sport_command(command) {
            self.profile
                .sports
                .get(&(plan.requested_id, status))
                .map(|recipe| &recipe.action)
        } else {
            action_kind(command).and_then(|kind| self.profile.actions.get(&kind))
        }
    }
    pub fn busy(&self) -> bool {
        self.pending.is_some()
    }
    pub fn has_operations(&self) -> bool {
        !self.records.is_empty()
    }
    pub fn has_unresolved_mutation(&self) -> bool {
        self.records.values().any(|record| record.blocks_mutations)
    }
    pub fn capability_rejection(&self, command: &ControlCommand) -> Option<&'static str> {
        if let Some((status, plan)) = sport_command(command) {
            if let Err(reason) = plan.entry() {
                return Some(reason);
            }
            return if !self.profile.valid() {
                Some("invalid_verified_recipe")
            } else if self
                .profile
                .sports
                .contains_key(&(plan.requested_id, status))
            {
                None
            } else {
                Some("sport_mode_protocol_unverified")
            };
        }
        match self
            .profile
            .capabilities
            .iter()
            .find(|cap| cap.kind == command.capability())
        {
            Some(cap) if matches!(command, ControlCommand::ReadSettings { .. }) && cap.readable => {
                None
            }
            Some(cap) if cap.status == CapabilityStatus::Verified && cap.writable => None,
            Some(cap) => Some(cap.reason),
            None => Some("unsupported"),
        }
    }
    pub fn prior_result(&self, request: &ControlRequest) -> Option<ControlResult> {
        self.records.get(&request.operation_id).map(|record| {
            if record.result.command == request.command {
                record.result.clone()
            } else {
                result(
                    request.operation_id.clone(),
                    request.command.clone(),
                    ControlStatus::Rejected,
                    "operation_id_reused",
                    None,
                )
            }
        })
    }
    pub fn restore_unknown(&mut self, recovery: ControlRecovery) -> Vec<ControlEffect> {
        if !self.profile.valid() || matches!(&recovery.command, ControlCommand::ReadSettings { .. })
        {
            return vec![ControlEffect::Diagnostic {
                code: "invalid_control_recovery",
            }];
        }
        if self.busy() || recovery.identity != self.identity {
            return vec![ControlEffect::Diagnostic {
                code: "invalid_control_recovery_identity",
            }];
        }
        if recovery.operation_id.is_empty()
            || recovery.operation_id.len() > 128
            || recovery.operation_id.contains('\0')
        {
            return vec![ControlEffect::Diagnostic {
                code: "invalid_operation_id",
            }];
        }
        if let Some(record) = self.records.get(&recovery.operation_id) {
            return if record.result.command == recovery.command {
                vec![ControlEffect::Result(record.result.clone())]
            } else {
                vec![ControlEffect::Diagnostic {
                    code: "operation_id_reused",
                }]
            };
        }
        if self.records.len() >= MAX_OPERATIONS {
            return vec![ControlEffect::Diagnostic {
                code: "operation_capacity",
            }];
        }
        let raw = match recovery.expected_settings_raw_hex {
            Some(raw) => match unhex(&raw, 14)
                .ok()
                .and_then(|raw| <[u8; 14]>::try_from(raw).ok())
            {
                Some(raw) => Some(raw),
                None => {
                    return vec![ControlEffect::Diagnostic {
                        code: "invalid_control_recovery",
                    }];
                }
            },
            None => None,
        };
        let check = match &recovery.command {
            ControlCommand::SetSchedule { .. } | ControlCommand::SetHeartRateAlerts { .. } => {
                let kind = match &recovery.command {
                    ControlCommand::SetSchedule { kind, .. } => *kind,
                    _ => SettingKind::HeartRate,
                };
                self.profile
                    .settings
                    .get(&kind)
                    .cloned()
                    .zip(raw)
                    .map(|(recipe, expected)| Check::Settings {
                        kind,
                        recipe,
                        expected,
                        exact: matches!(
                            &recovery.command,
                            ControlCommand::SetHeartRateAlerts { .. }
                        ),
                    })
            }
            command => self
                .command_recipe(command)
                .and_then(|recipe| recipe.state_query.clone())
                .map(Check::State),
        };
        let receipt = result(
            recovery.operation_id.clone(),
            recovery.command,
            ControlStatus::Unknown,
            "restored_unknown",
            None,
        );
        self.records.insert(
            recovery.operation_id,
            Record {
                result: receipt.clone(),
                check,
                blocks_mutations: true,
            },
        );
        vec![ControlEffect::Result(receipt)]
    }
    pub fn request(&mut self, request: ControlRequest, now_ms: u64) -> Vec<ControlEffect> {
        let id = request.operation_id;
        let command = request.command;
        if let Some(record) = self.records.get(&id) {
            if record.result.command == command {
                return vec![ControlEffect::Result(record.result.clone())];
            }
            return vec![ControlEffect::Result(result(
                id,
                command,
                ControlStatus::Rejected,
                "operation_id_reused",
                None,
            ))];
        }
        let reject = if id.is_empty() || id.len() > 128 || id.contains('\0') {
            Some("invalid_operation_id")
        } else if self.records.len() >= MAX_OPERATIONS {
            Some("operation_capacity")
        } else if !self.profile.valid() {
            Some("invalid_verified_recipe")
        } else if let Some(reason) = self.capability_rejection(&command) {
            Some(reason)
        } else if self.busy() {
            Some("control_busy")
        } else if self.has_unresolved_mutation()
            && !matches!(&command, ControlCommand::ReadSettings { .. })
            && !self.command_recipe(&command).is_some_and(|recipe| {
                recipe.safe_stop
                    && matches!(
                        &command,
                        ControlCommand::StopWorkout
                            | ControlCommand::StopLiveHeartRate
                            | ControlCommand::StopSport { .. }
                    )
            })
        {
            Some("unresolved_mutation")
        } else {
            None
        };
        if let Some(reason) = reject {
            return vec![ControlEffect::Result(result(
                id,
                command,
                ControlStatus::Rejected,
                reason,
                None,
            ))];
        }
        let mut effects = Vec::new();
        self.records.insert(
            id.clone(),
            Record {
                result: result(
                    id.clone(),
                    command.clone(),
                    ControlStatus::Unknown,
                    "in_progress",
                    None,
                ),
                check: None,
                blocks_mutations: !matches!(&command, ControlCommand::ReadSettings { .. }),
            },
        );
        match command {
            ControlCommand::ReadSettings { .. }
            | ControlCommand::SetSchedule { .. }
            | ControlCommand::SetHeartRateAlerts { .. } => {
                let kind = match &command {
                    ControlCommand::ReadSettings { kind }
                    | ControlCommand::SetSchedule { kind, .. } => *kind,
                    _ => SettingKind::HeartRate,
                };
                let Some(recipe) = self.profile.settings.get(&kind).cloned() else {
                    return self.finish(
                        &id,
                        ControlStatus::Rejected,
                        "missing_verified_recipe",
                        None,
                    );
                };
                let target = match command {
                    ControlCommand::SetSchedule {
                        enabled,
                        interval_minutes,
                        ..
                    } => Some(SettingsTarget::Schedule(enabled, interval_minutes)),
                    ControlCommand::SetHeartRateAlerts { low_bpm, high_bpm } => {
                        Some(SettingsTarget::Alerts(low_bpm, high_bpm))
                    }
                    _ => None,
                };
                if let Some(SettingsTarget::Schedule(_, Some(interval))) = target
                    && (!recipe.intervals.contains(&interval)
                        || recipe.write_interval_index.is_none())
                {
                    return self.finish(&id, ControlStatus::Rejected, "unsupported_interval", None);
                }
                let opcode = recipe.opcode;
                let payload = recipe.read_payload.clone();
                self.begin(
                    id,
                    Phase::ReadSettings {
                        kind,
                        recipe,
                        target,
                    },
                    (opcode, &payload),
                    false,
                    now_ms,
                    &mut effects,
                );
            }
            ControlCommand::MeasureOnce { metric } => {
                let Some(recipe) = self.profile.measurements.get(&metric).cloned() else {
                    return self.finish(
                        &id,
                        ControlStatus::Rejected,
                        "missing_verified_recipe",
                        None,
                    );
                };
                if recipe.stop.is_none() {
                    return self.finish(
                        &id,
                        ControlStatus::Rejected,
                        "missing_verified_stop_recipe",
                        None,
                    );
                }
                let wire = (recipe.opcode, recipe.payload.clone());
                self.begin(
                    id,
                    Phase::Measurement {
                        recipe,
                        started_at_ms: now_ms,
                        stop_reason: None,
                    },
                    (wire.0, &wire.1),
                    true,
                    now_ms,
                    &mut effects,
                );
            }
            _ => {
                let Some(recipe) = self.command_recipe(&command).cloned() else {
                    return self.finish(
                        &id,
                        ControlStatus::Rejected,
                        "missing_verified_recipe",
                        None,
                    );
                };
                let check = recipe.state_query.map(Check::State);
                if let Some(record) = self.records.get_mut(&id) {
                    record.check = check.clone();
                }
                self.begin(
                    id,
                    Phase::MutationAck {
                        pattern: recipe.acknowledgement,
                        followup: check,
                    },
                    (recipe.opcode, &recipe.payload),
                    true,
                    now_ms,
                    &mut effects,
                );
            }
        }
        effects
    }
    /// Cancellation belongs to the current admitted measurement only. It is
    /// not an idempotent stop for a restored or previously unknown operation.
    pub fn cancel(&mut self, operation_id: &str, now_ms: u64) -> Vec<ControlEffect> {
        let Some(pending) = self.pending.as_mut() else {
            return vec![ControlEffect::Diagnostic {
                code: "measurement_not_active",
            }];
        };
        if pending.operation_id != operation_id {
            return vec![ControlEffect::Diagnostic {
                code: "stale_measurement_operation",
            }];
        }
        if matches!(&pending.phase, Phase::MeasurementStop { .. }) && now_ms >= pending.deadline {
            return self.deadline(now_ms);
        }
        match &mut pending.phase {
            Phase::Measurement { stop_reason, .. } => {
                *stop_reason = Some(MeasurementStopReason::Cancelled);
                // A pending start ATT must complete before another GATT write.
                if pending.write_complete {
                    self.begin_measurement_stop(MeasurementStopReason::Cancelled, now_ms)
                } else {
                    self.measurement_progress()
                }
            }
            Phase::MeasurementStop { stop_reason, .. } => {
                *stop_reason = MeasurementStopReason::Cancelled;
                self.measurement_progress()
            }
            _ => vec![ControlEffect::Diagnostic {
                code: "measurement_not_active",
            }],
        }
    }
    /// Reconciliation can only issue the recorded read query. It never repeats
    /// a measurement, settings write or sports start/stop.
    pub fn reconcile(&mut self, operation_id: &str, now_ms: u64) -> Vec<ControlEffect> {
        if self.busy() {
            return vec![ControlEffect::Diagnostic {
                code: "control_busy",
            }];
        }
        let Some(record) = self.records.get(operation_id) else {
            return vec![ControlEffect::Diagnostic {
                code: "unknown_operation_id",
            }];
        };
        if record.result.status != ControlStatus::Unknown {
            return vec![ControlEffect::Result(record.result.clone())];
        }
        let Some(check) = record.check.clone() else {
            return vec![ControlEffect::Result(record.result.clone())];
        };
        let mut effects = Vec::new();
        self.begin_check(operation_id.to_owned(), check, now_ms, &mut effects);
        effects
    }
    pub fn write_complete(&mut self, success: bool, now_ms: u64) -> Vec<ControlEffect> {
        let Some(pending) = self.pending.as_mut() else {
            return vec![ControlEffect::Diagnostic {
                code: "unexpected_control_write_complete",
            }];
        };
        if now_ms >= pending.deadline {
            return self.deadline(now_ms);
        }
        if !success {
            return self.fail("control_write_failed");
        }
        pending.write_complete = true;
        self.advance(now_ms)
    }
    /// Returns consumed=false for unrelated frames, including source-only live
    /// candidates. Matching always checks the original opcode without masking.
    pub fn notification(&mut self, bytes: &[u8], now_ms: u64) -> (bool, Vec<ControlEffect>) {
        let frame = match decode_core(bytes) {
            Ok(frame) => frame,
            Err(_) if self.busy() => {
                return (
                    true,
                    vec![ControlEffect::Diagnostic {
                        code: "invalid_control_frame",
                    }],
                );
            }
            Err(_) => return (false, Vec::new()),
        };
        if let Some(pending) = self.pending.as_ref() {
            if matches!(
                &pending.phase,
                Phase::Measurement { .. } | Phase::MeasurementStop { .. }
            ) && now_ms < pending.issued_at_ms
            {
                return (
                    true,
                    vec![ControlEffect::Diagnostic {
                        code: "stale_control_frame",
                    }],
                );
            }
            if now_ms >= pending.deadline {
                return (true, self.deadline(now_ms));
            }
            let response = match &pending.phase {
                Phase::ReadSettings { recipe, .. }
                | Phase::Check(Check::Settings { recipe, .. })
                    if recipe.read_response.matches(&frame) =>
                {
                    Some(Response::Settings(frame.payload))
                }
                Phase::MutationAck { pattern, .. } if pattern.matches(&frame) => {
                    Some(Response::Ack)
                }
                Phase::Check(Check::State(query)) if query.desired.matches(&frame) => {
                    Some(Response::State(true))
                }
                Phase::Check(Check::State(query)) if query.opposite.matches(&frame) => {
                    Some(Response::State(false))
                }
                Phase::Measurement { recipe, .. }
                    if frame.opcode == recipe.opcode
                        && frame.payload[0] == recipe.result.pattern.prefix[0] =>
                {
                    if frame.payload[1] != 0 {
                        Some(Response::MeasurementError)
                    } else if let Some(observation) = recipe
                        .result
                        .parse(&frame, now_ms)
                        .filter(|value| value.sample_quality == "valid")
                    {
                        Some(Response::Measurement(observation))
                    } else {
                        // Zero and invalid values are progress, not samples.
                        return (true, self.measurement_progress());
                    }
                }
                Phase::MeasurementStop { recipe, .. } => {
                    if frame.opcode == 0x69 && frame.payload[0] == recipe.result_type {
                        // The ring can deliver a late provisional value after
                        // stop. It can never replace the final 0x6A response.
                        return (true, Vec::new());
                    }
                    recipe.parse(&frame, now_ms).map(Response::Measurement)
                }
                _ => None,
            };
            if let Some(response) = response {
                if let Some(pending) = self.pending.as_mut() {
                    pending.response = Some(response);
                }
                return (true, self.advance(now_ms));
            }
        }
        if self.live_active
            && let Some(observation) = self
                .profile
                .live
                .as_ref()
                .and_then(|recipe| recipe.parse(&frame, now_ms))
        {
            return (true, vec![ControlEffect::LiveHeartRate(observation)]);
        }
        (false, Vec::new())
    }
    pub fn deadline(&mut self, now_ms: u64) -> Vec<ControlEffect> {
        if self
            .pending
            .as_ref()
            .is_some_and(|pending| now_ms >= pending.deadline)
        {
            match self.pending.as_ref().map(|pending| &pending.phase) {
                Some(Phase::Measurement { .. })
                    if self
                        .pending
                        .as_ref()
                        .is_some_and(|pending| pending.write_complete) =>
                {
                    // Attempt the owned stop once. Absence of its qualified
                    // response remains durable uncertainty, not a rejection.
                    self.begin_measurement_stop(MeasurementStopReason::Timeout, now_ms)
                }
                Some(Phase::MeasurementStop { .. }) => self.fail("measurement_stop_unconfirmed"),
                _ => self.fail("control_response_timeout"),
            }
        } else {
            Vec::new()
        }
    }
    pub fn disconnected(&mut self) -> Vec<ControlEffect> {
        self.live_active = false;
        if self.busy() {
            self.fail("control_disconnected")
        } else {
            Vec::new()
        }
    }
    fn begin(
        &mut self,
        operation_id: String,
        phase: Phase,
        wire: (u8, &[u8]),
        mutated: bool,
        now_ms: u64,
        effects: &mut Vec<ControlEffect>,
    ) {
        let (opcode, payload) = wire;
        let bytes = match core_command(opcode, payload) {
            Ok(bytes) => bytes,
            Err(_) => {
                effects.extend(self.finish(
                    &operation_id,
                    if mutated {
                        ControlStatus::Unknown
                    } else {
                        ControlStatus::Rejected
                    },
                    "invalid_verified_recipe",
                    None,
                ));
                return;
            }
        };
        if mutated
            && matches!(
                &phase,
                Phase::MutationAck { .. } | Phase::Measurement { .. }
            )
            && let Some(record) = self.records.get(&operation_id)
        {
            let expected_settings_raw_hex = match &record.check {
                Some(Check::Settings { expected, .. }) => Some(hex(expected)),
                _ => None,
            };
            effects.push(ControlEffect::Checkpoint(ControlRecovery {
                identity: self.identity.clone(),
                operation_id: operation_id.clone(),
                command: record.result.command.clone(),
                expected_settings_raw_hex,
            }));
        }
        let at_ms = match &phase {
            Phase::Measurement { recipe, .. } => now_ms.saturating_add(recipe.timeout_ms),
            Phase::MeasurementStop {
                final_deadline_ms, ..
            } => now_ms
                .saturating_add(CONTROL_TIMEOUT_MS)
                .min(*final_deadline_ms),
            _ => now_ms.saturating_add(CONTROL_TIMEOUT_MS),
        };
        self.pending = Some(Pending {
            operation_id: operation_id.clone(),
            phase,
            deadline: at_ms,
            issued_at_ms: now_ms,
            write_complete: false,
            response: None,
            mutated,
        });
        effects.extend(self.measurement_progress());
        effects.push(ControlEffect::Write {
            bytes_hex: hex(&bytes),
            query_id: format!("control:{operation_id}"),
        });
        effects.push(ControlEffect::Deadline { at_ms });
    }
    fn measurement_progress(&self) -> Vec<ControlEffect> {
        let Some(pending) = self.pending.as_ref() else {
            return Vec::new();
        };
        let (phase, started_at_ms, stop_reason) = match &pending.phase {
            Phase::Measurement {
                started_at_ms,
                stop_reason,
                ..
            } => (
                if pending.write_complete {
                    MeasurementPhase::Measuring
                } else {
                    MeasurementPhase::Starting
                },
                *started_at_ms,
                *stop_reason,
            ),
            Phase::MeasurementStop {
                started_at_ms,
                stop_reason,
                ..
            } => (
                MeasurementPhase::Stopping,
                *started_at_ms,
                Some(*stop_reason),
            ),
            _ => return Vec::new(),
        };
        let Some(record) = self.records.get(&pending.operation_id) else {
            return Vec::new();
        };
        vec![ControlEffect::Progress(ControlProgress {
            operation_id: pending.operation_id.clone(),
            command: record.result.command.clone(),
            phase,
            started_at_ms,
            deadline_at_ms: pending.deadline,
            stop_reason,
        })]
    }
    fn begin_measurement_stop(
        &mut self,
        reason: MeasurementStopReason,
        now_ms: u64,
    ) -> Vec<ControlEffect> {
        let Some(pending) = self.pending.take() else {
            return Vec::new();
        };
        let Phase::Measurement {
            recipe,
            started_at_ms,
            stop_reason,
        } = pending.phase.clone()
        else {
            self.pending = Some(pending);
            return Vec::new();
        };
        let reason = stop_reason.unwrap_or(reason);
        let final_deadline_ms = pending.deadline.saturating_add(CONTROL_TIMEOUT_MS);
        if now_ms >= final_deadline_ms {
            return self.finish(
                &pending.operation_id,
                ControlStatus::Unknown,
                "measurement_stop_unconfirmed",
                None,
            );
        }
        let Some(stop) = recipe.stop else {
            return self.finish(
                &pending.operation_id,
                ControlStatus::Unknown,
                "missing_verified_stop_recipe",
                None,
            );
        };
        let mut effects = Vec::new();
        let wire = (stop.opcode, stop.payload.clone());
        self.begin(
            pending.operation_id,
            Phase::MeasurementStop {
                recipe: stop,
                started_at_ms,
                stop_reason: reason,
                final_deadline_ms,
            },
            (wire.0, &wire.1),
            true,
            now_ms,
            &mut effects,
        );
        effects
    }
    fn begin_check(
        &mut self,
        id: String,
        check: Check,
        now_ms: u64,
        effects: &mut Vec<ControlEffect>,
    ) {
        let (opcode, payload) = match &check {
            Check::Settings { recipe, .. } => (recipe.opcode, recipe.read_payload.clone()),
            Check::State(query) => (query.opcode, query.payload.clone()),
        };
        self.begin(
            id,
            Phase::Check(check),
            (opcode, &payload),
            true,
            now_ms,
            effects,
        );
    }
    fn advance(&mut self, now_ms: u64) -> Vec<ControlEffect> {
        if let Some(pending) = self.pending.as_ref()
            && pending.write_complete
            && let Phase::Measurement { stop_reason, .. } = &pending.phase
        {
            let reason = stop_reason.or(match &pending.response {
                Some(Response::Measurement(_)) => Some(MeasurementStopReason::Result),
                Some(Response::MeasurementError) => Some(MeasurementStopReason::DeviceError),
                _ => None,
            });
            return if let Some(reason) = reason {
                self.begin_measurement_stop(reason, now_ms)
            } else {
                self.measurement_progress()
            };
        }
        if !self
            .pending
            .as_ref()
            .is_some_and(|pending| pending.write_complete && pending.response.is_some())
        {
            return Vec::new();
        }
        let Some(pending) = self.pending.take() else {
            return Vec::new();
        };
        let id = pending.operation_id;
        let mut effects = Vec::new();
        match (pending.phase, pending.response) {
            (
                Phase::ReadSettings {
                    kind,
                    recipe,
                    target,
                },
                Some(Response::Settings(raw)),
            ) => {
                let snapshot = match recipe.snapshot(kind, raw) {
                    Ok(snapshot) => snapshot,
                    Err(reason) => return self.finish(&id, ControlStatus::Rejected, reason, None),
                };
                let Some(target) = target else {
                    return self.finish(
                        &id,
                        ControlStatus::Verified,
                        "settings_read",
                        Some(snapshot),
                    );
                };
                let exact = matches!(target, SettingsTarget::Alerts(..));
                let mutation = match target {
                    SettingsTarget::Schedule(enabled, interval) => {
                        recipe.mutation(raw, enabled, interval)
                    }
                    SettingsTarget::Alerts(low, high) => recipe.alerts_mutation(raw, low, high),
                };
                let (payload, expected) = match mutation {
                    Ok(mutation) => mutation,
                    Err(reason) => {
                        return self.finish(&id, ControlStatus::Rejected, reason, Some(snapshot));
                    }
                };
                // A verified no-op needs no write and leaves all alerts intact.
                if (exact && expected == raw) || (!exact && recipe.confirms(&expected, &raw)) {
                    return self.finish(
                        &id,
                        ControlStatus::Verified,
                        "settings_already_applied",
                        Some(snapshot),
                    );
                }
                let check = Check::Settings {
                    kind,
                    recipe: recipe.clone(),
                    expected,
                    exact,
                };
                if let Some(record) = self.records.get_mut(&id) {
                    record.check = Some(check.clone());
                }
                self.begin(
                    id,
                    Phase::MutationAck {
                        pattern: recipe.acknowledgement,
                        followup: Some(check),
                    },
                    (recipe.opcode, &payload),
                    true,
                    now_ms,
                    &mut effects,
                );
            }
            (
                Phase::MutationAck {
                    followup: Some(check),
                    ..
                },
                Some(Response::Ack),
            ) => self.begin_check(id, check, now_ms, &mut effects),
            (Phase::MutationAck { followup: None, .. }, Some(Response::Ack)) => {
                self.apply_confirmed_state(&id);
                effects.extend(self.finish(
                    &id,
                    ControlStatus::Verified,
                    "device_acknowledged",
                    None,
                ));
            }
            (
                Phase::Check(Check::Settings {
                    kind,
                    recipe,
                    expected,
                    exact,
                }),
                Some(Response::Settings(raw)),
            ) => {
                let snapshot = match recipe.snapshot(kind, raw) {
                    Ok(snapshot) => snapshot,
                    Err(reason) => return self.finish(&id, ControlStatus::Unknown, reason, None),
                };
                if (exact && expected == raw) || (!exact && recipe.confirms(&expected, &raw)) {
                    effects.extend(self.finish(
                        &id,
                        ControlStatus::Verified,
                        "settings_readback_verified",
                        Some(snapshot),
                    ));
                } else {
                    effects.extend(self.finish(
                        &id,
                        ControlStatus::Unknown,
                        "settings_readback_mismatch",
                        Some(snapshot),
                    ));
                }
            }
            (Phase::Check(Check::State(_)), Some(Response::State(true))) => {
                self.apply_confirmed_state(&id);
                effects.extend(self.finish(
                    &id,
                    ControlStatus::Verified,
                    "device_state_verified",
                    None,
                ));
            }
            (Phase::Check(Check::State(_)), Some(Response::State(false))) => {
                effects.extend(self.finish(
                    &id,
                    ControlStatus::Rejected,
                    "desired_state_not_present",
                    None,
                ))
            }
            (
                Phase::MeasurementStop { stop_reason, .. },
                Some(Response::Measurement(observation)),
            ) => {
                if stop_reason == MeasurementStopReason::Result {
                    effects.push(ControlEffect::Observation {
                        operation_id: id.clone(),
                        observation,
                    });
                }
                effects.extend(self.finish(
                    &id,
                    ControlStatus::Verified,
                    match stop_reason {
                        MeasurementStopReason::Result => "measurement_received",
                        MeasurementStopReason::Cancelled => "measurement_cancelled",
                        MeasurementStopReason::DeviceError => "measurement_device_error",
                        MeasurementStopReason::Timeout => "measurement_timeout",
                    },
                    None,
                ));
            }
            _ => effects.extend(self.finish(
                &id,
                ControlStatus::Unknown,
                "invalid_control_transition",
                None,
            )),
        }
        effects
    }
    fn apply_confirmed_state(&mut self, id: &str) {
        if let Some(record) = self.records.get(id) {
            match record.result.command {
                ControlCommand::StartLiveHeartRate => self.live_active = true,
                ControlCommand::StopLiveHeartRate => self.live_active = false,
                _ => {}
            }
        }
        let stopping_command = self.records.get(id).and_then(|record| {
            self.command_recipe(&record.result.command)
                .filter(|recipe| recipe.safe_stop)
                .filter(|_| {
                    matches!(
                        &record.result.command,
                        ControlCommand::StopWorkout
                            | ControlCommand::StopLiveHeartRate
                            | ControlCommand::StopSport { .. }
                    )
                })
                .map(|_| record.result.command.clone())
        });
        if let Some(stopping_command) = stopping_command {
            for record in self.records.values_mut() {
                let same_mode = match (&stopping_command, &record.result.command) {
                    (ControlCommand::StopSport { plan }, command) => sport_command(command)
                        .is_some_and(|(_, other)| {
                            plan.entry()
                                .ok()
                                .and_then(|entry| entry.candidate_wire_mode)
                                .is_some_and(|mode| {
                                    other
                                        .entry()
                                        .ok()
                                        .and_then(|entry| entry.candidate_wire_mode)
                                        == Some(mode)
                                })
                        }),
                    (
                        ControlCommand::StopWorkout,
                        ControlCommand::StartWorkout { .. } | ControlCommand::StopWorkout,
                    ) => true,
                    (
                        ControlCommand::StopLiveHeartRate,
                        ControlCommand::StartLiveHeartRate | ControlCommand::StopLiveHeartRate,
                    ) => true,
                    _ => false,
                };
                if same_mode {
                    record.blocks_mutations = false;
                }
            }
        }
    }
    fn fail(&mut self, reason: &'static str) -> Vec<ControlEffect> {
        let Some(pending) = self.pending.take() else {
            return Vec::new();
        };
        self.finish(
            &pending.operation_id,
            if pending.mutated {
                ControlStatus::Unknown
            } else {
                ControlStatus::Rejected
            },
            reason,
            None,
        )
    }
    fn finish(
        &mut self,
        id: &str,
        status: ControlStatus,
        reason: &'static str,
        settings: Option<SettingsSnapshot>,
    ) -> Vec<ControlEffect> {
        let confirmed_sport = self
            .records
            .get(id)
            .and_then(|record| sport_command(&record.result.command))
            .and_then(|(requested_status, plan)| {
                self.profile
                    .sports
                    .get(&(plan.requested_id, requested_status))
                    .map(|recipe| (recipe.actual_wire_mode, recipe.evidence_ref))
            });
        let Some(record) = self.records.get_mut(id) else {
            return vec![ControlEffect::Diagnostic {
                code: "missing_control_operation",
            }];
        };
        record.result.status = status;
        record.result.reason = reason;
        record.result.settings = settings;
        if let Some(sport) = record.result.sport.as_mut() {
            sport.actual_wire_mode = if status == ControlStatus::Verified {
                confirmed_sport.map(|(mode, _)| mode)
            } else {
                None
            };
            sport.evidence_ref = if status == ControlStatus::Verified {
                confirmed_sport.map(|(_, evidence)| evidence)
            } else {
                None
            };
        }
        record.blocks_mutations = status == ControlStatus::Unknown
            && !matches!(&record.result.command, ControlCommand::ReadSettings { .. });
        vec![ControlEffect::Result(record.result.clone())]
    }
}
fn result(
    operation_id: String,
    command: ControlCommand,
    status: ControlStatus,
    reason: &'static str,
    settings: Option<SettingsSnapshot>,
) -> ControlResult {
    let sport = sport_command(&command).and_then(|(requested_status, plan)| {
        plan.entry().ok().map(|entry| SportResult {
            requested_id: plan.requested_id,
            candidate_wire_mode: entry.candidate_wire_mode,
            actual_wire_mode: None,
            requested_status,
            evidence_ref: None,
        })
    });
    ControlResult {
        operation_id,
        command,
        status,
        reason,
        settings,
        sport,
    }
}

#[cfg(test)]
mod tests;

#[cfg(test)]
pub(crate) use tests::manager as verified_test_manager;
