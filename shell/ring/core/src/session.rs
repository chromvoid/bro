use crate::codec::{
    BulkAssembler, MAX_NOTIFICATION_BYTES, bulk_command, core_command, decode_core, hex, unhex,
};
use crate::control::{
    Capability, ControlEffect, ControlManager, ControlProgress, ControlRecovery, ControlRequest,
    ControlResult, ControlStatus, Observation as ControlObservation, RingIdentity,
};
use crate::declared_capabilities::{Declaration, parse as parse_declaration};
use crate::device::{
    DeviceCapability, DeviceEffect, DeviceManager, DeviceRecovery, DeviceRequest, DeviceResult,
};
use crate::history::{
    CoreHistoryAssembler, CoreScalarHistoryAssembler, HistoryContext, HistoryPage,
    ObservationDetails, parse_bulk, place_interval_page,
};
use crate::legacy_hr::{LegacyHeartRateAssembler, request_time as legacy_hr_request_time};
use crate::sport_history::SportHistoryAssembler;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{BTreeSet, VecDeque};

pub const QUERY_TIMEOUT_MS: u64 = 8_000;
pub const WORKOUT_HISTORY_TIMEOUT_MS: u64 = 30_000;
pub const MAX_COMPLETED_QUERIES: usize = 8192;
const MAX_RETRIES: u8 = 2;

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Clock {
    pub year: u16,
    pub month: u8,
    pub day: u8,
    pub hour: u8,
    pub minute: u8,
    pub second: u8,
    #[serde(default = "russian_language")]
    pub language: u8,
}
fn russian_language() -> u8 {
    10
}
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidClient {
    pub sdk: u32,
    pub model: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    pub generation: u64,
    pub timezone: String,
    pub local_date: String,
    pub clock: Clock,
    pub android: AndroidClient,
    #[serde(default = "all_history_days")]
    pub history_days: u8,
    #[serde(default)]
    pub completed_queries: Vec<String>,
    /// Enabled only after the native collector negotiates activity-report support.
    #[serde(default)]
    pub collect_running_steps: bool,
    #[serde(default)]
    pub collect_workouts: bool,
    #[serde(default)]
    pub workout_cursor: u32,
}
fn all_history_days() -> u8 {
    30
}

/// Platform-validated wall-clock evidence for a date-less aggregate response.
/// Rust has no IANA database; the Android transport supplies these snapshots
/// at the actual GATT write and notification callback, not when dequeuing them.
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct RunningClockProof {
    pub request_local_date: String,
    pub response_local_date: String,
    pub timezone: String,
    pub request_wall_time_ms: u64,
    pub response_wall_time_ms: u64,
    pub request_monotonic_ms: u64,
    pub response_monotonic_ms: u64,
    pub clock_unchanged: bool,
}

/// Android captures these instants at the first BC25 write and the actual
/// callback parse. This is interpretation provenance, never sensor qualification.
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct TemperatureTimeContext {
    pub version: String,
    pub sync_started_at: String,
    pub parsed_at: String,
    pub timezone: String,
    pub request_id: String,
}
impl TemperatureTimeContext {
    fn bounded(&self) -> bool {
        self.version == "bro-soul1230-temperature-capture-v1"
            && !self.request_id.is_empty()
            && self.request_id.len() <= 200
            && !self.timezone.is_empty()
            && self.timezone.len() <= 100
            && !self.sync_started_at.is_empty()
            && self.sync_started_at.len() <= 100
            && !self.parsed_at.is_empty()
            && self.parsed_at.len() <= 100
    }
    fn same_request(&self, other: &Self) -> bool {
        self.version == other.version
            && self.request_id == other.request_id
            && self.sync_started_at == other.sync_started_at
            && self.timezone == other.timezone
    }
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum Input {
    DeviceCapabilities {
        generation: u64,
        identity: RingIdentity,
    },
    DeviceIdentity {
        generation: u64,
        identity: RingIdentity,
    },
    /// Native supplies an opaque current-GATT source, never a WebView event.
    DeviceEventSource {
        generation: u64,
        physical_source_id: String,
    },
    DeviceRequest {
        generation: u64,
        request: DeviceRequest,
    },
    DeviceReconcile {
        generation: u64,
        operation_id: String,
    },
    DeviceRestore {
        generation: u64,
        recovery: DeviceRecovery,
    },
    DeviceReceiptStored {
        generation: u64,
        operation_id: String,
    },
    ControlCapabilities {
        generation: u64,
        identity: RingIdentity,
    },
    /// Native transport supplies this only after reading both Device Information
    /// characteristics on the current connection. Cached UI metadata is separate.
    ControlIdentity {
        generation: u64,
        identity: RingIdentity,
    },
    ControlRequest {
        generation: u64,
        request: ControlRequest,
    },
    ControlCancel {
        generation: u64,
        operation_id: String,
    },
    ControlReconcile {
        generation: u64,
        operation_id: String,
    },
    ControlRestore {
        generation: u64,
        recovery: ControlRecovery,
    },
    Connected {
        generation: u64,
    },
    Disconnected {
        generation: u64,
    },
    Sync {
        generation: u64,
    },
    Notification {
        generation: u64,
        channel: String,
        bytes_hex: String,
        #[serde(default)]
        running_clock: Option<RunningClockProof>,
        #[serde(default)]
        temperature_time_context: Option<TemperatureTimeContext>,
    },
    WriteComplete {
        generation: u64,
        success: bool,
    },
    Version {
        generation: u64,
        kind: String,
        value: String,
    },
    Deadline {
        generation: u64,
    },
}
impl Input {
    fn generation(&self) -> u64 {
        match self {
            Self::DeviceCapabilities { generation, .. }
            | Self::DeviceIdentity { generation, .. }
            | Self::DeviceEventSource { generation, .. }
            | Self::DeviceRequest { generation, .. }
            | Self::DeviceReconcile { generation, .. }
            | Self::DeviceRestore { generation, .. }
            | Self::DeviceReceiptStored { generation, .. }
            | Self::ControlCapabilities { generation, .. }
            | Self::ControlIdentity { generation, .. }
            | Self::ControlRequest { generation, .. }
            | Self::ControlCancel { generation, .. }
            | Self::ControlReconcile { generation, .. }
            | Self::ControlRestore { generation, .. }
            | Self::Connected { generation }
            | Self::Disconnected { generation }
            | Self::Sync { generation }
            | Self::Notification { generation, .. }
            | Self::WriteComplete { generation, .. }
            | Self::Version { generation, .. }
            | Self::Deadline { generation } => *generation,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct Checkpoint {
    pub completed_queries: Vec<String>,
    pub workout_cursor: u32,
}
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Effect {
    DeviceCapabilities {
        identity: RingIdentity,
        capabilities: Vec<DeviceCapability>,
    },
    DeviceResult {
        #[serde(flatten)]
        result: DeviceResult,
    },
    DeviceCheckpoint {
        recovery: DeviceRecovery,
    },
    DeviceEvent {
        event: crate::device_events::DeviceEvent,
    },
    ControlCapabilities {
        identity: RingIdentity,
        capabilities: Vec<Capability>,
        source_catalog: Vec<crate::sports::SportCatalogEntry>,
        sport_capabilities: Vec<crate::sports::SportCapability>,
    },
    ControlResult {
        #[serde(flatten)]
        result: ControlResult,
    },
    ControlCheckpoint {
        recovery: ControlRecovery,
    },
    ControlObservation {
        operation_id: String,
        observation: ControlObservation,
    },
    ControlProgress {
        #[serde(flatten)]
        progress: ControlProgress,
    },
    LiveHeartRate {
        bpm: f64,
        quality: &'static str,
        received_monotonic_ms: u64,
        sample_quality: &'static str,
    },
    Write {
        channel: &'static str,
        bytes_hex: String,
        response: bool,
        query_id: String,
    },
    ReadVersion {
        kind: &'static str,
    },
    Deadline {
        at_ms: u64,
        generation: u64,
    },
    Battery {
        percent: u8,
        charging: bool,
    },
    DeclaredCapabilities {
        declaration: Declaration,
        received_monotonic_ms: u64,
    },
    Version {
        kind: String,
        value: String,
    },
    Observation {
        observation: Value,
    },
    Progress {
        query_id: String,
        metric: String,
        day: u8,
        page: u8,
        status: &'static str,
        #[serde(skip_serializing_if = "Option::is_none")]
        failure_code: Option<&'static str>,
        validation_status: &'static str,
        checkpoint: Checkpoint,
    },
    Completed {
        status: &'static str,
        failed_queries: Vec<String>,
    },
    Error {
        code: &'static str,
        message: String,
    },
    Diagnostic {
        code: &'static str,
        message: String,
    },
}

#[derive(Debug, Clone)]
struct Query {
    id: String,
    metric: &'static str,
    op: u8,
    payload: Vec<u8>,
    bulk: bool,
    day: u8,
    page: u8,
}
impl Query {
    fn core(id: String, metric: &'static str, op: u8, payload: Vec<u8>, day: u8) -> Self {
        Self {
            id,
            metric,
            op,
            payload,
            bulk: false,
            day,
            page: 0,
        }
    }
    fn bulk(metric: &'static str, op: u8, day: u8, page: u8) -> Self {
        Self {
            id: format!("{metric}:{day}:{page}"),
            metric,
            op,
            payload: vec![day, page],
            bulk: true,
            day,
            page,
        }
    }
    fn write(&self) -> Effect {
        let bytes = if self.bulk {
            bulk_command(self.op, &self.payload).map(|b| hex(&b))
        } else {
            core_command(self.op, &self.payload).map(|b| hex(&b))
        };
        // All queries are built from bounded, typed values; retain error handling even here.
        match bytes {
            Ok(bytes_hex) => Effect::Write {
                channel: if self.bulk { "bulk" } else { "core" },
                bytes_hex,
                response: !self.bulk,
                query_id: self.id.clone(),
            },
            Err(e) => error("internal_query", e.to_string()),
        }
    }
}

struct Pending {
    query: Query,
    deadline: u64,
    retries: u8,
    write_complete: bool,
    finished: bool,
    empty: bool,
    next_page: Option<u8>,
    next_interval_count: Option<u16>,
    next_interval_minutes: Option<u16>,
    next_workout_cursor: Option<u32>,
    started_at_ms: u64,
    held_observations: Vec<Value>,
    held_declaration: Option<(Declaration, u64)>,
}

pub struct Session {
    config: Config,
    connected: bool,
    syncing: bool,
    version_firmware: bool,
    version_hardware: bool,
    version_deadline: Option<u64>,
    queue: VecDeque<Query>,
    pending: Option<Pending>,
    completed: BTreeSet<String>,
    failed: BTreeSet<String>,
    bulk: BulkAssembler,
    bulk_temperature_context: Option<TemperatureTimeContext>,
    steps: Option<CoreHistoryAssembler>,
    legacy_hr: Option<LegacyHeartRateAssembler>,
    scalar_history: Option<CoreScalarHistoryAssembler>,
    sport_history: Option<SportHistoryAssembler>,
    workout_cursor: u32,
    context: HistoryContext,
    sleep_requested: bool,
    clock_initialized: bool,
    running_requested: bool,
    control: Option<ControlManager>,
    control_identity_verified: bool,
    device: Option<DeviceManager>,
    device_identity_verified: bool,
    device_event_source: Option<String>,
}

fn error(code: &'static str, message: impl Into<String>) -> Effect {
    Effect::Diagnostic {
        code,
        message: message.into(),
    }
}
fn fatal(code: &'static str, message: impl Into<String>) -> Effect {
    Effect::Error {
        code,
        message: message.into(),
    }
}

impl Session {
    pub fn new(config: Config) -> Result<Self, String> {
        validate_config(&config)?;
        let completed = config
            .completed_queries
            .iter()
            .filter(|key| {
                !matches!(
                    key.as_str(),
                    "running_steps:0:0"
                        | "failed:running_steps:0:0"
                        | "declared_capabilities:core_3c"
                        | "failed:declared_capabilities:core_3c"
                )
            })
            .cloned()
            .collect();
        let failed = config
            .completed_queries
            .iter()
            .filter_map(|key| key.strip_prefix("failed:").map(str::to_owned))
            .filter(|key| {
                !matches!(
                    key.as_str(),
                    "running_steps:0:0" | "declared_capabilities:core_3c"
                )
            })
            .collect();
        let context = HistoryContext {
            local_date: config.local_date.clone(),
            timezone: config.timezone.clone(),
        };
        Ok(Self {
            workout_cursor: config.workout_cursor,
            config,
            connected: false,
            syncing: false,
            version_firmware: false,
            version_hardware: false,
            version_deadline: None,
            queue: VecDeque::new(),
            pending: None,
            completed,
            failed,
            bulk: BulkAssembler::default(),
            bulk_temperature_context: None,
            steps: None,
            legacy_hr: None,
            scalar_history: None,
            sport_history: None,
            context,
            sleep_requested: false,
            clock_initialized: false,
            running_requested: false,
            control: None,
            control_identity_verified: false,
            device: None,
            device_identity_verified: false,
            device_event_source: None,
        })
    }

    pub fn dispatch(&mut self, input: Input, now_ms: u64) -> Vec<Effect> {
        if input.generation() != self.config.generation {
            return vec![error(
                "stale_generation",
                "Event belongs to an earlier connection",
            )];
        }
        let mut effects = Vec::new();
        match input {
            Input::DeviceCapabilities { identity, .. } => {
                if let Err(reason) = identity.validate() {
                    return vec![fatal(reason, "Invalid device identity")];
                }
                let changed = self
                    .device
                    .as_ref()
                    .is_some_and(|manager| manager.identity() != &identity);
                if changed
                    && self
                        .device
                        .as_ref()
                        .is_some_and(DeviceManager::has_operations)
                {
                    return vec![fatal(
                        "device_identity_changed",
                        "A changed device identity requires a new session",
                    )];
                }
                if self.device.is_none() || changed {
                    self.device_identity_verified = false;
                    self.device_event_source = None;
                    self.device = match DeviceManager::new(identity.clone()) {
                        Ok(manager) => Some(manager),
                        Err(reason) => return vec![fatal(reason, "Invalid device identity")],
                    };
                }
                effects.push(Effect::DeviceCapabilities {
                    identity,
                    capabilities: self.device.as_ref().expect("device").capabilities(),
                });
            }
            Input::DeviceIdentity { identity, .. } => {
                if !self.connected {
                    return vec![fatal(
                        "disconnected",
                        "Read identity on the current GATT connection",
                    )];
                }
                effects = self.dispatch(
                    Input::DeviceCapabilities {
                        generation: self.config.generation,
                        identity,
                    },
                    now_ms,
                );
                self.device_identity_verified = effects
                    .iter()
                    .any(|effect| matches!(effect, Effect::DeviceCapabilities { .. }));
            }
            Input::DeviceEventSource {
                physical_source_id, ..
            } => {
                if !self.connected
                    || !self.device_identity_verified
                    || !crate::device_events::valid_source_id(&physical_source_id)
                {
                    return vec![fatal(
                        "event_source_unavailable",
                        "Fresh identity and current native GATT source required",
                    )];
                }
                if self
                    .device_event_source
                    .as_ref()
                    .is_some_and(|prior| prior != &physical_source_id)
                {
                    return vec![fatal(
                        "event_source_changed",
                        "A changed physical source requires a new session",
                    )];
                }
                self.device_event_source = Some(physical_source_id);
            }
            Input::DeviceRequest { request, .. } => {
                if let Some(prior) = self
                    .device
                    .as_ref()
                    .and_then(|manager| manager.prior_result(&request))
                {
                    return vec![Effect::DeviceResult { result: prior }];
                }
                let reject = self
                    .device
                    .as_ref()
                    .and_then(|manager| manager.capability_rejection(&request.command))
                    .or_else(|| {
                        if !self.connected {
                            Some("disconnected")
                        } else if self.syncing {
                            Some("history_busy")
                        } else if self.control.as_ref().is_some_and(|manager| {
                            manager.busy() || manager.has_unresolved_mutation()
                        }) {
                            Some("control_busy")
                        } else if self.device.is_none() {
                            Some("identity_unavailable")
                        } else if !self.device_identity_verified {
                            Some("identity_not_verified")
                        } else {
                            None
                        }
                    });
                let device = if let Some(reason) = reject {
                    vec![DeviceManager::reject(request, reason)]
                } else {
                    self.device
                        .as_mut()
                        .expect("device")
                        .request(request, now_ms)
                };
                effects.extend(device_effects(device, self.config.generation));
            }
            Input::DeviceReconcile { operation_id, .. } => {
                if !self.connected
                    || self.syncing
                    || !self.device_identity_verified
                    || self.control.as_ref().is_some_and(ControlManager::busy)
                {
                    return vec![fatal(
                        "device_unavailable",
                        "Fresh identity and exclusive connection required for reconciliation",
                    )];
                }
                if let Some(manager) = self.device.as_mut() {
                    effects.extend(device_effects(
                        manager.reconcile(&operation_id, now_ms),
                        self.config.generation,
                    ));
                } else {
                    effects.push(fatal(
                        "identity_unavailable",
                        "Read device identity before reconciling",
                    ));
                }
            }
            Input::DeviceRestore { recovery, .. } => {
                if let Some(manager) = self.device.as_mut() {
                    effects.extend(device_effects(
                        manager.restore_unknown(recovery),
                        self.config.generation,
                    ));
                } else {
                    effects.push(fatal(
                        "identity_unavailable",
                        "Read device identity before restoring device operations",
                    ));
                }
            }
            Input::DeviceReceiptStored { operation_id, .. } => {
                if let Some(manager) = self.device.as_mut() {
                    manager.receipt_stored(&operation_id);
                }
            }
            Input::ControlCapabilities { identity, .. } => {
                if let Err(reason) = identity.validate() {
                    return vec![fatal(reason, "Device identity is incomplete or invalid")];
                }
                let changed = self
                    .control
                    .as_ref()
                    .is_some_and(|manager| manager.identity() != &identity);
                if changed
                    && self
                        .control
                        .as_ref()
                        .is_some_and(|manager| manager.busy() || manager.has_operations())
                {
                    return vec![fatal(
                        "control_identity_changed",
                        "Start a new session for a changed device identity",
                    )];
                }
                if self.control.is_none() || changed {
                    self.control_identity_verified = false;
                    self.control = match ControlManager::new(identity.clone()) {
                        Ok(manager) => Some(manager),
                        Err(reason) => return vec![fatal(reason, "Invalid device identity")],
                    };
                }
                if let Some(manager) = self.control.as_ref() {
                    effects.push(Effect::ControlCapabilities {
                        identity,
                        capabilities: manager.capabilities(),
                        source_catalog: crate::sports::source_catalog(),
                        sport_capabilities: manager.sport_capabilities(),
                    });
                }
            }
            Input::ControlIdentity { identity, .. } => {
                if !self.connected {
                    return vec![fatal(
                        "disconnected",
                        "Current GATT connection is required for device identity evidence",
                    )];
                }
                // Reuse exact registry selection, but only this fresh native
                // GATT event can establish authority for a verified recipe.
                effects = self.dispatch(
                    Input::ControlCapabilities {
                        generation: self.config.generation,
                        identity,
                    },
                    now_ms,
                );
                self.control_identity_verified = effects
                    .iter()
                    .any(|effect| matches!(effect, Effect::ControlCapabilities { .. }));
            }
            Input::ControlRequest { request, .. } => {
                // A receipt remains the result even after a lost connection;
                // environment failures cannot turn UNKNOWN into a rejection.
                if let Some(result) = self
                    .control
                    .as_ref()
                    .and_then(|manager| manager.prior_result(&request))
                {
                    return vec![Effect::ControlResult { result }];
                }
                let capability_rejection = self
                    .control
                    .as_ref()
                    .and_then(|manager| manager.capability_rejection(&request.command));
                let reject = if capability_rejection.is_some() {
                    capability_rejection
                } else if !self.connected {
                    Some("disconnected")
                } else if self.syncing {
                    Some("history_busy")
                } else if self
                    .device
                    .as_ref()
                    .is_some_and(|manager| manager.busy() || manager.has_unresolved_mutation())
                {
                    Some("device_busy")
                } else if self.control.is_none() {
                    Some("identity_unavailable")
                } else if !self.control_identity_verified {
                    Some("identity_not_verified")
                } else {
                    None
                };
                if let Some(reason) = reject {
                    effects.push(Effect::ControlResult {
                        result: ControlResult {
                            operation_id: request.operation_id,
                            command: request.command,
                            status: ControlStatus::Rejected,
                            reason,
                            settings: None,
                            sport: None,
                        },
                    });
                } else if let Some(manager) = self.control.as_mut() {
                    let control = manager.request(request, now_ms);
                    effects.extend(control_effects(control, self.config.generation));
                }
            }
            Input::ControlCancel { operation_id, .. } => {
                if !self.connected || !self.control_identity_verified {
                    effects.push(error(
                        "measurement_not_active",
                        "No owned measurement is connected",
                    ));
                } else if let Some(manager) = self.control.as_mut() {
                    effects.extend(control_effects(
                        manager.cancel(&operation_id, now_ms),
                        self.config.generation,
                    ));
                } else {
                    effects.push(error(
                        "measurement_not_active",
                        "No owned measurement is active",
                    ));
                }
            }
            Input::ControlReconcile { operation_id, .. } => {
                if !self.connected
                    || self.syncing
                    || self.device.as_ref().is_some_and(DeviceManager::busy)
                {
                    return vec![fatal(
                        "control_unavailable",
                        "Connect and finish history synchronization before reconciling",
                    )];
                }
                if !self.control_identity_verified {
                    return vec![fatal(
                        "identity_not_verified",
                        "Read the current GATT device identity before reconciling",
                    )];
                }
                if let Some(manager) = self.control.as_mut() {
                    let control = manager.reconcile(&operation_id, now_ms);
                    effects.extend(control_effects(control, self.config.generation));
                } else {
                    effects.push(fatal(
                        "identity_unavailable",
                        "Read the device identity before reconciling",
                    ));
                }
            }
            Input::ControlRestore { recovery, .. } => {
                if let Some(manager) = self.control.as_mut() {
                    effects.extend(control_effects(
                        manager.restore_unknown(recovery),
                        self.config.generation,
                    ));
                } else {
                    effects.push(fatal(
                        "identity_unavailable",
                        "Read the device identity before restoring controls",
                    ));
                }
            }
            Input::Connected { .. } => {
                if !self.connected {
                    self.control_identity_verified = false;
                    self.device_identity_verified = false;
                    self.device_event_source = None;
                }
                self.connected = true;
            }
            Input::Disconnected { .. } => {
                self.control_identity_verified = false;
                self.device_identity_verified = false;
                self.device_event_source = None;
                if let Some(manager) = self.device.as_mut() {
                    effects.extend(device_effects(
                        manager.disconnected(),
                        self.config.generation,
                    ));
                }
                if let Some(manager) = self.control.as_mut() {
                    effects.extend(control_effects(
                        manager.disconnected(),
                        self.config.generation,
                    ));
                }
                self.connected = false;
                self.syncing = false;
                self.version_deadline = None;
                self.queue.clear();
                self.bulk.reset();
                self.bulk_temperature_context = None;
                self.sleep_requested = false;
                self.clock_initialized = false;
                self.steps = None;
                self.legacy_hr = None;
                self.scalar_history = None;
                self.sport_history = None;
                if let Some(pending) = self.pending.take() {
                    effects.push(self.progress(&pending.query, "interrupted", None));
                }
            }
            Input::Sync { .. } => {
                if !self.connected {
                    return vec![fatal("disconnected", "Connect before syncing")];
                }
                if self.syncing {
                    return vec![fatal("busy", "A sync is already in progress")];
                }
                if self.control.as_ref().is_some_and(ControlManager::busy) {
                    return vec![fatal("control_busy", "A device control is in progress")];
                }
                if self
                    .device
                    .as_ref()
                    .is_some_and(|manager| manager.busy() || manager.has_unresolved_mutation())
                {
                    return vec![fatal(
                        "device_busy",
                        "Reconcile the device operation before syncing",
                    )];
                }
                self.syncing = true;
                self.version_firmware = false;
                self.version_hardware = false;
                self.clock_initialized = false;
                let deadline = now_ms.saturating_add(QUERY_TIMEOUT_MS);
                self.version_deadline = Some(deadline);
                effects.extend([
                    Effect::ReadVersion { kind: "firmware" },
                    Effect::ReadVersion { kind: "hardware" },
                    Effect::Deadline {
                        at_ms: deadline,
                        generation: self.config.generation,
                    },
                ]);
            }
            Input::Version { kind, value, .. } => {
                if !self.syncing || self.version_deadline.is_none() {
                    return vec![error("unexpected_version", "No version read is pending")];
                }
                if value.len() > 256 || value.contains('\0') {
                    return vec![error(
                        "invalid_version",
                        "Version value is too long or contains NUL",
                    )];
                }
                match kind.as_str() {
                    "firmware" => self.version_firmware = true,
                    "hardware" => self.version_hardware = true,
                    _ => {
                        return vec![error(
                            "invalid_version_kind",
                            "Expected firmware or hardware",
                        )];
                    }
                }
                if value.trim().is_empty() {
                    effects.push(error(
                        "version_unavailable",
                        format!("{kind} characteristic unavailable"),
                    ));
                } else {
                    effects.push(Effect::Version {
                        kind,
                        value: value.trim().to_owned(),
                    });
                }
                if self.version_firmware && self.version_hardware {
                    self.start_queries(now_ms, &mut effects);
                }
            }
            Input::WriteComplete { success, .. } => {
                if let Some(manager) = self.device.as_mut().filter(|manager| manager.busy()) {
                    return device_effects(
                        manager.write_complete(success, now_ms),
                        self.config.generation,
                    );
                }
                if let Some(manager) = self.control.as_mut().filter(|manager| manager.busy()) {
                    return control_effects(
                        manager.write_complete(success, now_ms),
                        self.config.generation,
                    );
                }
                if let Some(pending) = self.pending.as_mut() {
                    if matches!(pending.query.op, 0x01 | 0x3c) && now_ms >= pending.deadline {
                        self.retry_or_fail("response_timeout", now_ms, &mut effects);
                        return effects;
                    }
                    if success {
                        pending.write_complete = true;
                        self.advance_if_ready(now_ms, &mut effects);
                    } else {
                        self.retry_or_fail("write_failed", now_ms, &mut effects);
                    }
                } else {
                    effects.push(error(
                        "unexpected_write_complete",
                        "No protocol write is pending",
                    ));
                }
            }
            Input::Notification {
                channel,
                bytes_hex,
                running_clock,
                temperature_time_context,
                ..
            } => {
                let bytes = match unhex(&bytes_hex, MAX_NOTIFICATION_BYTES) {
                    Ok(bytes) => bytes,
                    Err(e) => return vec![error("invalid_notification", e.to_string())],
                };
                if channel == "core" {
                    if self.connected
                        && self.device_identity_verified
                        && let (Some(manager), Some(source)) =
                            (&self.device, &self.device_event_source)
                        && let Some(event) = crate::device_events::observation(
                            manager.identity(),
                            source,
                            self.config.generation,
                            &bytes,
                            now_ms,
                        )
                    {
                        effects.push(Effect::DeviceEvent { event });
                    }
                    if let Some(manager) = self.device.as_mut() {
                        let (consumed, device) = manager.notification(&bytes, now_ms);
                        effects.extend(device_effects(device, self.config.generation));
                        if consumed {
                            return effects;
                        }
                    }
                    if let Some(manager) = self.control.as_mut() {
                        let (consumed, control) = manager.notification(&bytes, now_ms);
                        effects.extend(control_effects(control, self.config.generation));
                        if consumed {
                            return effects;
                        }
                    }
                    self.handle_core(&bytes, running_clock.as_ref(), now_ms, &mut effects);
                } else if channel == "bulk" {
                    self.handle_bulk(&bytes, temperature_time_context, now_ms, &mut effects);
                } else {
                    effects.push(error("invalid_channel", "Expected core or bulk"));
                }
            }
            Input::Deadline { .. } => {
                if let Some(manager) = self.device.as_mut().filter(|manager| manager.busy()) {
                    return device_effects(manager.deadline(now_ms), self.config.generation);
                }
                if let Some(manager) = self.control.as_mut().filter(|manager| manager.busy()) {
                    return control_effects(manager.deadline(now_ms), self.config.generation);
                }
                if self
                    .version_deadline
                    .is_some_and(|deadline| now_ms >= deadline)
                {
                    effects.push(error(
                        "version_timeout",
                        "Version reads did not both finish",
                    ));
                    // Preserve the vendor order but allow devices without Device Information.
                    self.start_queries(now_ms, &mut effects);
                } else if self
                    .pending
                    .as_ref()
                    .is_some_and(|pending| now_ms >= pending.deadline)
                {
                    self.retry_or_fail("response_timeout", now_ms, &mut effects);
                }
            }
        }
        effects
    }

    fn start_queries(&mut self, now_ms: u64, effects: &mut Vec<Effect>) {
        self.version_deadline = None;
        self.queue.clear();
        self.bulk.reset();
        self.bulk_temperature_context = None;
        self.sleep_requested = false;
        let c = &self.config.clock;
        self.queue.push_back(Query::core(
            "clock".into(),
            "initialization",
            1,
            vec![
                bcd((c.year % 100) as u8),
                bcd(c.month),
                bcd(c.day),
                bcd(c.hour),
                bcd(c.minute),
                bcd(c.second),
                c.language,
            ],
            0,
        ));
        let model = bounded_model(&self.config.android.model);
        let os = match self.config.android.sdk {
            26 | 27 => 8,
            28 => 9,
            _ => 10,
        };
        let mut bind = vec![2, os];
        bind.extend_from_slice(model.as_bytes());
        self.queue.push_back(Query::core(
            "client_bind".into(),
            "initialization",
            4,
            bind,
            0,
        ));
        self.queue
            .push_back(Query::core("battery".into(), "battery", 3, vec![], 0));
        // A declaration belongs to this connection, even when history resumes
        // from a checkpoint. It is never a cached command qualification.
        self.failed.remove("declared_capabilities:core_3c");
        self.queue.push_back(Query::core(
            "declared_capabilities:core_3c".into(),
            "declared_capabilities",
            0x3c,
            vec![],
            0,
        ));
        if self.config.collect_running_steps && !self.running_requested {
            self.queue.push_back(Query::core(
                "running_steps:0:0".into(),
                "running_steps",
                0x48,
                vec![],
                0,
            ));
        }
        self.queue.push_back(Query::core(
            "steps:0:0".into(),
            "steps",
            0x43,
            vec![0, 15, 0, 95],
            0,
        ));
        self.queue.push_back(Query {
            id: "sleep:255:0".into(),
            metric: "sleep",
            op: 0x27,
            payload: vec![0xff, 1],
            bulk: true,
            day: 0xff,
            page: 0,
        });
        for day in 0..self.config.history_days {
            if day > 0 {
                self.queue.push_back(Query::core(
                    format!("steps:{day}:0"),
                    "steps",
                    0x43,
                    vec![day, 15, 0, 95],
                    day,
                ));
            }
            if day >= 7 {
                continue;
            }
            for (metric, opcode) in [("heart_rate", 0x75), ("spo2", 0x5f), ("temperature", 0x74)] {
                if self.completed.contains(&format!("{metric}:{day}:done")) {
                    if metric == "heart_rate"
                        && self.completed.contains(&format!("heart_rate:{day}:empty"))
                    {
                        self.queue_legacy_hr(day, false, effects);
                    }
                    continue;
                }
                if self
                    .failed
                    .iter()
                    .any(|key| key.starts_with(&format!("{metric}:{day}:")))
                {
                    continue;
                }
                if let Some(mut page) = (0u8..=254)
                    .find(|page| !self.completed.contains(&format!("{metric}:{day}:{page}")))
                {
                    if page > 0
                        && (self.interval_count(metric, day).is_none()
                            || self.interval_minutes(metric, day).is_none())
                    {
                        // A pre-ABI1 or incomplete checkpoint cannot prove the
                        // time of page N: re-read from zero rather than guessing.
                        self.completed
                            .retain(|key| !key.starts_with(&format!("{metric}:{day}:")));
                        page = 0;
                    }
                    self.queue.push_back(Query::bulk(metric, opcode, day, page));
                }
            }
            if day == 0 {
                self.queue.push_back(Query {
                    id: "spo2_legacy:255:0".into(),
                    metric: "spo2",
                    op: 0x2a,
                    payload: vec![0xff],
                    bulk: true,
                    day: 0xff,
                    page: 0,
                });
                self.queue.push_back(Query {
                    id: "temperature_legacy:0:0".into(),
                    metric: "temperature",
                    op: 0x25,
                    payload: vec![0],
                    bulk: true,
                    day: 0,
                    page: 0,
                });
            }
        }
        // Soul requests these through CommandHandle (core), not the bulk file
        // service. Keep their own resumable IDs and never guess a bulk layout.
        for day in 0..self.config.history_days.min(7) {
            for (metric, opcode) in [("hrv", 0x39), ("stress", 0x37)] {
                self.queue.push_back(Query::core(
                    format!("{metric}:{day}:0"),
                    metric,
                    opcode,
                    vec![day],
                    day,
                ));
            }
        }
        if self.config.collect_workouts {
            self.queue.push_back(Query::core(
                format!("workouts:{}:0", self.workout_cursor),
                "workouts",
                0x13,
                self.workout_cursor.to_le_bytes().to_vec(),
                0,
            ));
        }
        self.next_query(now_ms, effects);
    }

    fn next_query(&mut self, now_ms: u64, effects: &mut Vec<Effect>) {
        self.steps = None;
        self.legacy_hr = None;
        self.scalar_history = None;
        self.sport_history = None;
        while let Some(query) = self.queue.pop_front() {
            if query.op == 0x48 {
                if self.running_requested {
                    continue;
                }
                self.running_requested = true;
                if !self.clock_initialized {
                    effects.push(error(
                        "running_clock_uninitialized",
                        "Running snapshot requires an acknowledged clock initialization",
                    ));
                    continue;
                }
            }
            if query.op != 0x48
                && query.metric != "initialization"
                && query.op != 0x3c
                && self.failed.contains(&query.id)
            {
                continue;
            }
            if query.metric != "initialization"
                && !matches!(query.op, 0x3c | 0x48 | 0x75 | 0x5f | 0x74)
                && self.completed.contains(&query.id)
            {
                continue;
            }
            let deadline = now_ms.saturating_add(if query.op == 0x13 {
                WORKOUT_HISTORY_TIMEOUT_MS
            } else {
                QUERY_TIMEOUT_MS
            });
            if query.op == 0x43 {
                self.steps = Some(CoreHistoryAssembler::new(0x43, self.context.clone()));
            }
            if query.op == 0x15 {
                match LegacyHeartRateAssembler::new(self.context.clone(), query.day) {
                    Ok(assembler) => self.legacy_hr = Some(assembler),
                    Err(e) => {
                        self.failed.insert(query.id.clone());
                        self.completed.insert(format!("failed:{}", query.id));
                        effects.push(error("invalid_legacy_hr_context", e.to_string()));
                        effects.push(self.progress(
                            &query,
                            "failed",
                            Some("invalid_legacy_hr_context"),
                        ));
                        continue;
                    }
                }
            }
            if matches!(query.op, 0x39 | 0x37) {
                self.scalar_history =
                    CoreScalarHistoryAssembler::new(query.op, self.context.clone(), query.day).ok();
            }
            if query.op == 0x13 {
                self.sport_history = Some(SportHistoryAssembler::new(self.workout_cursor));
            }
            if query.op == 0x27 {
                self.sleep_requested = true;
            }
            effects.push(query.write());
            effects.push(self.progress(&query, "pending", None));
            effects.push(Effect::Deadline {
                at_ms: deadline,
                generation: self.config.generation,
            });
            self.pending = Some(Pending {
                query,
                deadline,
                retries: 0,
                write_complete: false,
                finished: false,
                empty: false,
                next_page: None,
                next_interval_count: None,
                next_interval_minutes: None,
                next_workout_cursor: None,
                started_at_ms: now_ms,
                held_observations: Vec::new(),
                held_declaration: None,
            });
            return;
        }
        self.pending = None;
        self.syncing = false;
        self.sleep_requested = false;
        effects.push(Effect::Completed {
            status: if self.failed.is_empty() {
                "complete"
            } else {
                "partial"
            },
            failed_queries: self.failed.iter().cloned().collect(),
        });
    }

    fn handle_core(
        &mut self,
        bytes: &[u8],
        running_clock: Option<&RunningClockProof>,
        now_ms: u64,
        effects: &mut Vec<Effect>,
    ) {
        let frame = match decode_core(bytes) {
            Ok(frame) => frame,
            Err(e) => {
                effects.push(error("invalid_core_frame", e.to_string()));
                return;
            }
        };
        let Some(pending) = self.pending.as_ref() else {
            effects.push(error("unsolicited_core", "No core query pending"));
            return;
        };
        if pending.query.bulk || pending.query.op != frame.opcode || pending.finished {
            effects.push(error(
                "unexpected_opcode",
                format!("Unexpected core opcode {:02x}", frame.opcode),
            ));
            return;
        }
        // A response received after the query's budget is never a snapshot of
        // that request, even if the Android deadline callback is still queued.
        if matches!(frame.opcode, 0x01 | 0x3c | 0x48 | 0x13) && now_ms >= pending.deadline {
            self.retry_or_fail("response_timeout", now_ms, effects);
            return;
        }
        if matches!(frame.opcode, 0x01 | 0x3c) && now_ms < pending.started_at_ms {
            effects.push(error(
                "stale_declaration",
                "Response predates the current query",
            ));
            return;
        }
        match frame.opcode {
            0x01 | 0x3c => {
                if let Some(declaration) = parse_declaration(frame.opcode, &frame.payload)
                    && let Some(pending) = self.pending.as_mut()
                    && pending.retries == 0
                {
                    pending.held_declaration = Some((declaration, now_ms));
                }
                self.mark_finished(false, None);
            }
            4 => self.mark_finished(false, None),
            3 => {
                if frame.payload[0] > 100 || frame.payload[1] > 1 {
                    effects.push(error("invalid_battery", "Invalid battery fields"));
                    return;
                }
                effects.push(Effect::Battery {
                    percent: frame.payload[0],
                    charging: frame.payload[1] == 1,
                });
                self.mark_finished(false, None);
            }
            0x43 => {
                if let Some(steps) = self.steps.as_mut() {
                    match steps.push(&frame.payload) {
                        Ok(Some(page)) => {
                            if !page.empty
                                && self
                                    .pending
                                    .as_ref()
                                    .is_some_and(|p| p.query.day != page.day)
                            {
                                effects.push(error(
                                    "history_day_mismatch",
                                    "Step date differs from the pending query",
                                ));
                                return;
                            }
                            self.accept_page(page, None, effects);
                        }
                        Ok(None) => {}
                        Err(e) => {
                            effects.push(error("invalid_history", e.to_string()));
                            return;
                        }
                    }
                }
            }
            0x48 => {
                let Some(proof) =
                    running_clock.filter(|proof| self.valid_running_clock(proof, now_ms))
                else {
                    self.retry_or_fail("running_ambiguous_time", now_ms, effects);
                    return;
                };
                let page = match CoreHistoryAssembler::new(0x48, self.context.clone())
                    .push(&frame.payload)
                {
                    Ok(Some(page)) => page,
                    Ok(None) => {
                        self.retry_or_fail("invalid_running_snapshot", now_ms, effects);
                        return;
                    }
                    Err(_) => {
                        self.retry_or_fail("invalid_running_snapshot", now_ms, effects);
                        return;
                    }
                };
                for observation in page.observations {
                    if let Ok(mut observation) = serde_json::to_value(observation) {
                        observation["details"]["clock_proof"] =
                            serde_json::to_value(proof).unwrap_or(Value::Null);
                        if let Some(pending) = self.pending.as_mut() {
                            pending.held_observations.push(observation);
                        }
                    }
                }
                self.mark_finished(false, None);
            }
            0x15 => {
                if let Some(assembler) = self.legacy_hr.as_mut() {
                    match assembler.push(&frame.payload) {
                        Ok(Some(page)) => {
                            if assembler.ignored_tail_nonzero() {
                                effects.push(error("legacy_hr_trailing_samples", "Ignored nonzero bytes after the SDK's first 288 sample positions"));
                            }
                            self.accept_page(page, None, effects);
                        }
                        Ok(None) => {}
                        Err(e) => {
                            effects.push(error("invalid_legacy_hr", e.to_string()));
                            return;
                        }
                    }
                }
            }
            0x39 | 0x37 => {
                if let Some(assembler) = self.scalar_history.as_mut() {
                    match assembler.push(&frame.payload) {
                        Ok(Some(page)) => {
                            for observation in page.observations {
                                if let Ok(observation) = serde_json::to_value(observation)
                                    && let Some(pending) = self.pending.as_mut()
                                {
                                    pending.held_observations.push(observation);
                                }
                            }
                            self.mark_finished(page.empty, None);
                        }
                        Ok(None) => {}
                        Err(e) => {
                            effects.push(error("invalid_scalar_history", e.to_string()));
                            return;
                        }
                    }
                }
            }
            0x13 => {
                if let Some(assembler) = self.sport_history.as_mut() {
                    match assembler.push(&frame.payload) {
                        Ok(Some(page)) => {
                            for (index, record) in page.records.into_iter().enumerate() {
                                let observation = match crate::history::candidate(
                                    crate::history::Metric::Workouts,
                                    0x13,
                                    0,
                                    index as u32,
                                    &self.context,
                                    ObservationDetails::RawDeviceWorkout { record },
                                ) {
                                    Ok(mut observation) => {
                                        observation.source = "oudmon_sdk_core".into();
                                        observation.quality = "protocol_unverified".into();
                                        serde_json::to_value(observation).ok()
                                    }
                                    Err(_) => None,
                                };
                                if let Some(observation) = observation
                                    && let Some(pending) = self.pending.as_mut()
                                {
                                    pending.held_observations.push(observation);
                                }
                            }
                            // The cursor is applied only after the pending ATT
                            // acknowledgement. Android commits it with records.
                            if let Some(pending) = self.pending.as_mut() {
                                pending.next_workout_cursor = Some(page.cursor);
                            }
                            self.mark_finished(page.empty, None);
                        }
                        Ok(None) => {}
                        Err(e) => {
                            effects.push(error("invalid_workout_history", e.to_string()));
                            return;
                        }
                    }
                }
            }
            _ => {
                effects.push(error(
                    "unsupported_opcode",
                    "Core response is not implemented",
                ));
                return;
            }
        }
        self.advance_if_ready(now_ms, effects);
    }

    fn handle_bulk(
        &mut self,
        bytes: &[u8],
        temperature_time_context: Option<TemperatureTimeContext>,
        now_ms: u64,
        effects: &mut Vec<Effect>,
    ) {
        let capture = temperature_time_context.filter(|capture| {
            capture.bounded()
                && self.pending.as_ref().is_some_and(|pending| {
                    pending.query.bulk
                        && pending.query.op == 0x25
                        && pending.retries == 0
                        && pending.write_complete
                        && !pending.finished
                        && now_ms >= pending.started_at_ms
                        && now_ms < pending.deadline
                })
        });
        // A partial frame that predates this attempt must never gain its anchor
        // from a later fragment. Every fragment needs the same callback request.
        if self.bulk.buffered_len() == 0 {
            self.bulk_temperature_context = capture;
        } else {
            self.bulk_temperature_context = match (&self.bulk_temperature_context, capture) {
                (Some(previous), Some(current)) if previous.same_request(&current) => Some(current),
                _ => None,
            };
        }
        let frame_capture = self.bulk_temperature_context.clone();
        let frames = match self.bulk.push(bytes) {
            Ok(frames) => frames,
            Err(e) => {
                self.bulk_temperature_context = None;
                effects.push(error("invalid_bulk_frame", e.to_string()));
                return;
            }
        };
        for frame in frames {
            // The captured R20 sends nap action 3E in addition to a requested
            // action 27. It can arrive after the next query has already started.
            // Parse supplemental sleep within this cycle without completing or
            // acknowledging the unrelated currently pending query.
            if frame.action == 0x3e && self.sleep_requested && self.syncing {
                match parse_bulk(frame.action, &frame.payload, &self.context) {
                    Ok(page) => {
                        for observation in page.observations {
                            if let Ok(observation) = serde_json::to_value(observation) {
                                effects.push(Effect::Observation { observation });
                            }
                        }
                        let supplemental = Query {
                            id: "sleep_nap:255:0".into(),
                            metric: "sleep",
                            op: 0x3e,
                            payload: vec![],
                            bulk: true,
                            day: 0xff,
                            page: 0,
                        };
                        self.completed.insert(supplemental.id.clone());
                        effects.push(self.progress(
                            &supplemental,
                            if page.empty { "empty" } else { "complete" },
                            None,
                        ));
                    }
                    Err(e) => effects.push(error("invalid_supplemental_sleep", e.to_string())),
                }
                continue;
            }
            let Some(pending) = self.pending.as_ref() else {
                effects.push(error("unsolicited_bulk", "No bulk query pending"));
                continue;
            };
            if !pending.query.bulk || pending.query.op != frame.action || pending.finished {
                effects.push(error(
                    "unexpected_action",
                    format!("Unexpected bulk action {:02x}", frame.action),
                ));
                continue;
            }
            match parse_bulk(frame.action, &frame.payload, &self.context) {
                Ok(page) => {
                    if matches!(frame.action, 0x75 | 0x5f | 0x74)
                        && frame.payload != [0]
                        && (page.day != pending.query.day || page.page != pending.query.page)
                    {
                        effects.push(error(
                            "history_page_mismatch",
                            "Response day/page differs from pending query",
                        ));
                        continue;
                    }
                    self.accept_page(page, frame_capture.as_ref(), effects);
                }
                Err(e) => {
                    effects.push(error("invalid_history", e.to_string()));
                    continue;
                }
            }
            self.advance_if_ready(now_ms, effects);
        }
    }

    fn accept_page(
        &mut self,
        mut page: HistoryPage,
        temperature_time_context: Option<&TemperatureTimeContext>,
        effects: &mut Vec<Effect>,
    ) {
        if let Some(pending) = self.pending.as_ref()
            && matches!(pending.query.op, 0x75 | 0x5f | 0x74)
            && !page.empty
        {
            let interval =
                page.observations
                    .first()
                    .and_then(|observation| match observation.details {
                        ObservationDetails::Interval {
                            interval_minutes, ..
                        } => Some(interval_minutes),
                        _ => None,
                    });
            if interval.is_none()
                || (pending.query.page > 0
                    && interval != self.interval_minutes(pending.query.metric, pending.query.day))
            {
                effects.push(error(
                    "interval_changed",
                    "Cannot establish a consistent interval across pages",
                ));
                return;
            }
            let base = if pending.query.page == 0 {
                Some(0)
            } else {
                self.interval_count(pending.query.metric, pending.query.day)
            };
            match base.and_then(|base| place_interval_page(&mut page, base).ok()) {
                Some(count) => {
                    if let Some(pending) = self.pending.as_mut() {
                        pending.next_interval_count = Some(count);
                        pending.next_interval_minutes = interval;
                    }
                }
                None => {
                    effects.push(error(
                        "interval_position_unknown",
                        "Cannot establish the cumulative interval sample position",
                    ));
                    return;
                }
            }
        }
        for observation in page.observations {
            match serde_json::to_value(observation) {
                Ok(mut observation) => {
                    if observation["details"]["type"] == "raw_temperature"
                        && let Some(capture) = temperature_time_context
                    {
                        observation["details"]["soul_time_context"] =
                            serde_json::to_value(capture).unwrap_or(Value::Null);
                    }
                    effects.push(Effect::Observation { observation });
                }
                Err(e) => effects.push(error("invalid_observation", e.to_string())),
            }
        }
        self.mark_finished(page.empty, page.next_page);
    }
    fn mark_finished(&mut self, empty: bool, next_page: Option<u8>) {
        if let Some(pending) = self.pending.as_mut() {
            pending.finished = true;
            pending.empty = empty;
            pending.next_page = next_page;
        }
    }
    fn valid_running_clock(&self, proof: &RunningClockProof, now_ms: u64) -> bool {
        let Some(pending) = &self.pending else {
            return false;
        };
        if !self.clock_initialized
            || !proof.clock_unchanged
            || proof.request_local_date != self.context.local_date
            || proof.response_local_date != proof.request_local_date
            || proof.timezone != self.context.timezone
            || proof.request_monotonic_ms < pending.started_at_ms
            || proof.response_monotonic_ms < proof.request_monotonic_ms
            || proof.response_monotonic_ms > now_ms
            || proof.response_monotonic_ms >= pending.deadline
        {
            return false;
        }
        let Some(wall_elapsed) = proof
            .response_wall_time_ms
            .checked_sub(proof.request_wall_time_ms)
        else {
            return false;
        };
        let elapsed = proof.response_monotonic_ms - proof.request_monotonic_ms;
        // Calendar assignment additionally occurs in Android with ZoneId. A
        // large wall-time adjustment must not be hidden by matching date text.
        wall_elapsed.abs_diff(elapsed) <= 2_000
    }
    fn advance_if_ready(&mut self, now_ms: u64, effects: &mut Vec<Effect>) {
        if !self
            .pending
            .as_ref()
            .is_some_and(|p| p.finished && p.write_complete)
        {
            return;
        }
        let Some(pending) = self.pending.take() else {
            return;
        };
        if pending.query.id == "clock" {
            self.clock_initialized = true;
        }
        if let Some(cursor) = pending.next_workout_cursor {
            self.workout_cursor = self.workout_cursor.max(cursor);
        }
        for observation in pending.held_observations {
            effects.push(Effect::Observation { observation });
        }
        if let Some((declaration, received_monotonic_ms)) = pending.held_declaration {
            effects.push(Effect::DeclaredCapabilities {
                declaration,
                received_monotonic_ms,
            });
        }
        if let Some(count) = pending.next_interval_count {
            let prefix = format!("{}:{}:samples:", pending.query.metric, pending.query.day);
            self.completed.retain(|key| !key.starts_with(&prefix));
            self.completed.insert(format!("{prefix}{count}"));
        }
        if let Some(minutes) = pending.next_interval_minutes {
            let prefix = format!("{}:{}:interval:", pending.query.metric, pending.query.day);
            self.completed.retain(|key| !key.starts_with(&prefix));
            self.completed.insert(format!("{prefix}{minutes}"));
        }
        if pending.query.metric != "initialization"
            && !matches!(pending.query.op, 0x3c | 0x48)
            && self.completed.len() < MAX_COMPLETED_QUERIES
        {
            self.completed.insert(pending.query.id.clone());
        }
        if matches!(pending.query.op, 0x75 | 0x5f | 0x74)
            && pending.next_page.is_none()
            && self.completed.len() < MAX_COMPLETED_QUERIES
        {
            self.completed.insert(format!(
                "{}:{}:done",
                pending.query.metric, pending.query.day
            ));
        }
        if pending.query.op == 0x75 && pending.query.page == 0 && pending.empty {
            self.completed
                .insert(format!("heart_rate:{}:empty", pending.query.day));
        }
        effects.push(self.progress(
            &pending.query,
            if pending.empty { "empty" } else { "complete" },
            None,
        ));
        if let Some(next_page) = pending.next_page {
            if next_page <= pending.query.page {
                effects.push(error(
                    "invalid_next_page",
                    "History pagination did not advance",
                ));
            } else {
                self.queue.push_front(Query::bulk(
                    pending.query.metric,
                    pending.query.op,
                    pending.query.day,
                    next_page,
                ));
            }
        }
        if pending.query.op == 0x75 && pending.query.page == 0 && pending.empty {
            self.queue_legacy_hr(pending.query.day, true, effects);
        }
        self.next_query(now_ms, effects);
    }
    fn retry_or_fail(&mut self, code: &'static str, now_ms: u64, effects: &mut Vec<Effect>) {
        let Some(mut pending) = self.pending.take() else {
            return;
        };
        self.bulk.reset();
        self.bulk_temperature_context = None;
        if pending.query.op == 0x43 {
            self.steps = Some(CoreHistoryAssembler::new(0x43, self.context.clone()));
        }
        if pending.query.op == 0x15 {
            self.legacy_hr =
                LegacyHeartRateAssembler::new(self.context.clone(), pending.query.day).ok();
        }
        if matches!(pending.query.op, 0x39 | 0x37) {
            self.scalar_history = CoreScalarHistoryAssembler::new(
                pending.query.op,
                self.context.clone(),
                pending.query.day,
            )
            .ok();
        }
        if pending.query.op == 0x13 {
            self.sport_history = Some(SportHistoryAssembler::new(self.workout_cursor));
        }
        // A new declaration query contains no request ID and is not retried.
        // Existing clock retries may initialize the link, but their ambiguous
        // responses never publish a declaration (see handle_core).
        if !matches!(pending.query.op, 0x3c | 0x48 | 0x13) && pending.retries < MAX_RETRIES {
            pending.retries += 1;
            pending.deadline = now_ms.saturating_add(QUERY_TIMEOUT_MS);
            pending.write_complete = false;
            pending.finished = false;
            pending.next_page = None;
            pending.next_interval_count = None;
            pending.next_interval_minutes = None;
            pending.held_observations.clear();
            pending.held_declaration = None;
            effects.push(error(
                code,
                format!("Retry {} for {}", pending.retries, pending.query.id),
            ));
            effects.push(pending.query.write());
            effects.push(Effect::Deadline {
                at_ms: pending.deadline,
                generation: self.config.generation,
            });
            self.pending = Some(pending);
        } else {
            effects.push(error(code, format!("Query {} failed", pending.query.id)));
            self.failed.insert(pending.query.id.clone());
            if pending.query.metric != "initialization" && !matches!(pending.query.op, 0x3c | 0x48)
            {
                self.completed
                    .insert(format!("failed:{}", pending.query.id));
            }
            effects.push(self.progress(&pending.query, "failed", Some(code)));
            if pending.query.metric == "initialization" {
                self.syncing = false;
                self.queue.clear();
                effects.push(fatal(
                    "initialization_failed",
                    "History was not read because initialization failed",
                ));
            } else {
                self.next_query(now_ms, effects);
            }
        }
    }
    fn progress(
        &self,
        query: &Query,
        status: &'static str,
        failure_code: Option<&'static str>,
    ) -> Effect {
        Effect::Progress {
            query_id: query.id.clone(),
            metric: query.metric.into(),
            day: query.day,
            page: query.page,
            status,
            failure_code,
            validation_status: if matches!(query.op, 0x01 | 0x3c) {
                "declared_only"
            } else if query.op == 3 {
                "live_capture_confirmed"
            } else if status == "empty"
                && matches!(query.op, 0x15 | 0x27 | 0x3e | 0x75 | 0x5f | 0x74)
            {
                "empty_capture_only"
            } else {
                "source_only"
            },
            checkpoint: Checkpoint {
                completed_queries: self.completed.iter().cloned().collect(),
                workout_cursor: self.workout_cursor,
            },
        }
    }
    fn queue_legacy_hr(&mut self, day: u8, front: bool, effects: &mut Vec<Effect>) {
        let id = format!("heart_rate_legacy:{day}:0");
        if self.completed.contains(&id) || self.failed.contains(&id) {
            return;
        }
        let time = match legacy_hr_request_time(&self.context, day) {
            Ok(time) => time,
            Err(e) => {
                self.failed.insert(id.clone());
                self.completed.insert(format!("failed:{id}"));
                effects.push(error("invalid_legacy_hr_context", e.to_string()));
                return;
            }
        };
        let query = Query::core(id, "heart_rate", 0x15, time.to_le_bytes().to_vec(), day);
        if front {
            self.queue.push_front(query);
        } else {
            self.queue.push_back(query);
        }
    }
    fn interval_minutes(&self, metric: &str, day: u8) -> Option<u16> {
        let prefix = format!("{metric}:{day}:interval:");
        self.completed
            .iter()
            .find_map(|key| {
                key.strip_prefix(&prefix)
                    .and_then(|value| value.parse::<u16>().ok())
            })
            .filter(|interval| *interval > 0 && *interval <= 1440)
    }
    fn interval_count(&self, metric: &str, day: u8) -> Option<u16> {
        let prefix = format!("{metric}:{day}:samples:");
        self.completed
            .iter()
            .filter_map(|key| {
                key.strip_prefix(&prefix)
                    .and_then(|value| value.parse::<u16>().ok())
            })
            .max()
            .filter(|count| *count <= 1440)
    }
}

fn device_effects(effects: Vec<DeviceEffect>, generation: u64) -> Vec<Effect> {
    effects
        .into_iter()
        .map(|effect| match effect {
            DeviceEffect::Checkpoint(recovery) => Effect::DeviceCheckpoint { recovery },
            DeviceEffect::Result(result) => Effect::DeviceResult { result },
            DeviceEffect::Write {
                bytes_hex,
                query_id,
            } => Effect::Write {
                channel: "core",
                bytes_hex,
                response: true,
                query_id,
            },
            DeviceEffect::Deadline { at_ms } => Effect::Deadline { at_ms, generation },
        })
        .collect()
}

fn control_effects(effects: Vec<ControlEffect>, generation: u64) -> Vec<Effect> {
    effects
        .into_iter()
        .map(|effect| match effect {
            ControlEffect::Checkpoint(recovery) => Effect::ControlCheckpoint { recovery },
            ControlEffect::Write {
                bytes_hex,
                query_id,
            } => Effect::Write {
                channel: "core",
                bytes_hex,
                response: true,
                query_id,
            },
            ControlEffect::Deadline { at_ms } => Effect::Deadline { at_ms, generation },
            ControlEffect::Result(result) => Effect::ControlResult { result },
            ControlEffect::Observation {
                operation_id,
                observation,
            } => Effect::ControlObservation {
                operation_id,
                observation,
            },
            ControlEffect::Progress(progress) => Effect::ControlProgress { progress },
            ControlEffect::LiveHeartRate(observation) => Effect::LiveHeartRate {
                bpm: observation.value,
                quality: "protocol_verified",
                received_monotonic_ms: observation.received_monotonic_ms,
                sample_quality: observation.sample_quality,
            },
            ControlEffect::Diagnostic { code } => error(
                code,
                "Device control response did not advance the operation",
            ),
        })
        .collect()
}

fn bcd(number: u8) -> u8 {
    (number / 10) * 16 + number % 10
}
fn bounded_model(model: &str) -> &str {
    let mut end = model.len().min(12);
    while !model.is_char_boundary(end) {
        end -= 1;
    }
    &model[..end]
}
fn validate_config(config: &Config) -> Result<(), String> {
    if config.generation == 0 {
        return Err("generation must be nonzero".into());
    }
    if config.timezone.is_empty() || config.timezone.len() > 64 {
        return Err("timezone must be 1..64 bytes".into());
    }
    if !(1..=30).contains(&config.history_days) {
        return Err("history_days must be 1..30".into());
    }
    if config.android.model.is_empty()
        || config.android.model.len() > 256
        || !(26..=255).contains(&config.android.sdk)
    {
        return Err("invalid Android client configuration".into());
    }
    let clock = &config.clock;
    let leap = clock.year.is_multiple_of(4)
        && (!clock.year.is_multiple_of(100) || clock.year.is_multiple_of(400));
    let max_day = match clock.month {
        2 => {
            if leap {
                29
            } else {
                28
            }
        }
        4 | 6 | 9 | 11 => 30,
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        _ => 0,
    };
    if !(2000..=2099).contains(&clock.year)
        || clock.day == 0
        || clock.day > max_day
        || clock.hour > 23
        || clock.minute > 59
        || clock.second > 59
        || clock.language > 19
    {
        return Err("invalid clock calendar".into());
    }
    if config.local_date != format!("{:04}-{:02}-{:02}", clock.year, clock.month, clock.day) {
        return Err("local_date must match the clock snapshot".into());
    }
    if config.completed_queries.len() > MAX_COMPLETED_QUERIES
        || config.completed_queries.iter().any(|q| q.len() > 128)
    {
        return Err("checkpoint exceeds limits".into());
    }
    crate::history::validate_context(&HistoryContext {
        local_date: config.local_date.clone(),
        timezone: config.timezone.clone(),
    })
    .map_err(|e| e.to_string())?;
    Ok(())
}

#[cfg(test)]
mod control_tests;
