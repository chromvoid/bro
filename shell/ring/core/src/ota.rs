//! Source-derived Oudmon DFU framing and a conservative transaction reducer.
//! This is unrelated to Nordic DFU. Transport checksums authenticate neither an
//! image nor a target. The Android official catalog supplies verified image bytes.
use crate::codec::{bulk_command, crc16_modbus, hex, unhex};
use crate::control::RingIdentity;
use serde::{Deserialize, Serialize};

pub const MAX_IMAGE_BYTES: usize = 12_288_000;
pub const BLOCK_BYTES: usize = 1024;
const TIMEOUT_MS: u64 = 15_000;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct OtaManifest {
    pub manifest_id: String,
    pub official_origin: String,
    pub image_path: String,
    pub source: RingIdentity,
    pub target_firmware: String,
    pub size_bytes: u32,
    pub sha256: String,
    pub evidence_ref: String,
    pub power_conditions: PowerConditions,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChargingRequirement {
    Required,
    NotRequired,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PowerConditions {
    pub minimum_battery_percent: u8,
    pub charging: ChargingRequirement,
    pub evidence_ref: String,
}
impl PowerConditions {
    fn validate(&self) -> Result<(), &'static str> {
        if !(1..=100).contains(&self.minimum_battery_percent)
            || self.evidence_ref.trim().is_empty()
            || self.evidence_ref.len() > 256
            || self.evidence_ref.contains('\0')
        {
            return Err("power_conditions_unqualified");
        }
        Ok(())
    }
    fn satisfied(&self, battery: Option<u8>, charging: Option<bool>) -> bool {
        // 30% is an additional software guard, not target-specific hardware proof.
        battery.is_some_and(|value| (self.minimum_battery_percent.max(30)..=100).contains(&value))
            && (self.charging == ChargingRequirement::NotRequired || charging == Some(true))
    }
}
impl OtaManifest {
    pub fn validate(&self) -> Result<(), &'static str> {
        self.source.validate()?;
        self.power_conditions.validate()?;
        if self.manifest_id.is_empty()
            || self.manifest_id.len() > 128
            || !self.official_origin.starts_with("https://")
            || self
                .official_origin
                .chars()
                .any(|c| matches!(c, '?' | '#' | '@' | '\\'))
            || self.official_origin[8..].is_empty()
            || self.official_origin[8..].contains('/')
            || !self.image_path.starts_with('/')
            || self
                .image_path
                .chars()
                .any(|c| matches!(c, '?' | '#' | '\\'))
            || self.image_path.contains("..")
            || self.target_firmware.is_empty()
            || self.target_firmware.len() > 256
            || self.target_firmware.contains('\0')
            || self.target_firmware == self.source.firmware
            || self.size_bytes == 0
            || self.size_bytes as usize > MAX_IMAGE_BYTES
            || self.sha256.len() != 64
            || !self
                .sha256
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            || self.evidence_ref.is_empty()
            || self.evidence_ref.len() > 256
        {
            return Err("invalid_manifest");
        }
        Ok(())
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum OtaPhase {
    Prepared,
    Starting,
    Initializing,
    Transferring,
    Checking,
    Finalizing,
    Verifying,
    Complete,
    Unknown,
    Failed,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct OtaRecovery {
    pub operation_id: String,
    pub manifest: OtaManifest,
    pub phase: OtaPhase,
    pub acknowledged_blocks: u16,
    pub pending_action: Option<u8>,
    pub pending_block: Option<u16>,
}
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum OtaEffect {
    Checkpoint {
        recovery: OtaRecovery,
    },
    Write {
        bytes: Vec<u8>,
        action: u8,
        block: Option<u16>,
    },
    Deadline {
        at_ms: u64,
    },
    ReadFirmware,
    State {
        recovery: OtaRecovery,
        reason: &'static str,
    },
}
/// Candidate loader frames are useful for synthetic CI fixtures only. A compiled
/// proof of the actual loader, image origin/target and response correlation is required.
pub fn candidate_start() -> Vec<u8> {
    bulk_command(1, &[]).expect("empty frame")
}
pub fn candidate_initialize(image: &[u8]) -> Result<Vec<u8>, &'static str> {
    validate_image_size(image)?;
    let mut p = vec![1];
    p.extend((image.len() as u32).to_le_bytes());
    p.extend(crc16_modbus(image).to_le_bytes());
    p.extend(
        image
            .iter()
            .fold(0u16, |sum, b| sum.wrapping_add(*b as u16))
            .to_le_bytes(),
    );
    Ok(bulk_command(2, &p).expect("nine-byte initialization"))
}
#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum OtaFrameRequest {
    Start,
    Initialize {
        size_bytes: u32,
        image_crc16: u16,
        image_sum16: u16,
    },
    Block {
        index: u16,
        bytes_hex: String,
    },
    Check,
    End,
}
impl OtaFrameRequest {
    /// Internal JNI framing only. The native updater separately checks the actual
    /// image digest, target, origin, fresh battery/identity and one-use confirmation.
    pub fn encode(self) -> Result<String, &'static str> {
        let bytes = match self {
            Self::Start => candidate_start(),
            Self::Check => candidate_check(),
            Self::End => candidate_end(),
            Self::Initialize {
                size_bytes,
                image_crc16,
                image_sum16,
            } => {
                if size_bytes == 0 || size_bytes as usize > MAX_IMAGE_BYTES {
                    return Err("invalid_image_size");
                }
                let mut payload = vec![1];
                payload.extend(size_bytes.to_le_bytes());
                payload.extend(image_crc16.to_le_bytes());
                payload.extend(image_sum16.to_le_bytes());
                bulk_command(2, &payload).expect("nine-byte initialization")
            }
            Self::Block { index, bytes_hex } => {
                let data = unhex(&bytes_hex, BLOCK_BYTES).map_err(|_| "invalid_ota_block")?;
                candidate_block(index, &data)?
            }
        };
        Ok(hex(&bytes))
    }
}
pub fn candidate_block(index: u16, bytes: &[u8]) -> Result<Vec<u8>, &'static str> {
    if index == 0 || bytes.is_empty() || bytes.len() > BLOCK_BYTES {
        return Err("invalid_ota_block");
    }
    let mut p = index.to_le_bytes().to_vec();
    p.extend(bytes);
    Ok(bulk_command(3, &p).expect("bounded OTA block"))
}
pub fn candidate_check() -> Vec<u8> {
    bulk_command(4, &[]).expect("empty frame")
}
pub fn candidate_end() -> Vec<u8> {
    bulk_command(5, &[]).expect("empty frame")
}
fn validate_image_size(image: &[u8]) -> Result<(), &'static str> {
    if image.is_empty() || image.len() > MAX_IMAGE_BYTES {
        Err("invalid_image_size")
    } else {
        Ok(())
    }
}

/// No actual X6 loader/image/ACK correlation proof has been collected. This cannot
/// be changed by a web manifest, discovered support bit or an SDK class name.
fn production_proofs() -> Vec<LoaderProof> {
    Vec::new()
}
pub fn production_manifest(manifest_id: &str) -> Option<OtaManifest> {
    production_proofs()
        .into_iter()
        .find(|p| p.manifest.manifest_id == manifest_id)
        .map(|p| p.manifest)
}
pub fn production_catalog() -> Vec<OtaManifest> {
    production_proofs()
        .into_iter()
        .map(|p| p.manifest)
        .collect()
}

/// The proof is intentionally private: trust is compiled, never caller-declared.
#[derive(Clone)]
struct LoaderProof {
    manifest: OtaManifest,
    correlated_block_ack: bool,
}
pub struct OtaManager {
    proof: LoaderProof,
    image: Vec<u8>,
    recovery: OtaRecovery,
    deadline: Option<u64>,
    write_complete: bool,
    acknowledged: bool,
}
impl OtaManager {
    pub fn prepare(
        manifest_id: &str,
        _identity: &RingIdentity,
        _battery: Option<u8>,
        _charging: Option<bool>,
    ) -> Result<Self, &'static str> {
        if production_manifest(manifest_id).is_none() {
            Err("official_image_and_loader_unqualified")
        } else {
            Err("verified_image_bytes_required")
        }
    }
    /// Internal native boundary: Android verifies SHA-256 over the actual bounded
    /// bytes downloaded from the compiled public origin, never over page metadata.
    pub fn prepare_image(
        manifest_id: &str,
        image: Vec<u8>,
        operation_id: String,
        identity: &RingIdentity,
        battery: Option<u8>,
        charging: Option<bool>,
        actual_sha256: &str,
    ) -> Result<Self, &'static str> {
        let proof = production_proofs()
            .into_iter()
            .find(|p| p.manifest.manifest_id == manifest_id)
            .ok_or("official_image_and_loader_unqualified")?;
        Self::prepared(
            proof,
            image,
            operation_id,
            identity,
            battery,
            charging,
            actual_sha256,
        )
    }
    fn prepared(
        proof: LoaderProof,
        image: Vec<u8>,
        operation_id: String,
        identity: &RingIdentity,
        battery: Option<u8>,
        charging: Option<bool>,
        actual_sha256: &str,
    ) -> Result<Self, &'static str> {
        proof.manifest.validate()?;
        validate_image_size(&image)?;
        if !proof.correlated_block_ack {
            return Err("loader_ack_correlation_unverified");
        }
        if identity != &proof.manifest.source {
            return Err("ota_identity_mismatch");
        }
        if !proof.manifest.power_conditions.satisfied(battery, charging) {
            return Err("ota_power_conditions_unmet");
        }
        if image.len() != proof.manifest.size_bytes as usize
            || actual_sha256 != proof.manifest.sha256
        {
            return Err("image_integrity_mismatch");
        }
        if operation_id.is_empty() || operation_id.len() > 128 {
            return Err("invalid_operation_id");
        }
        let recovery = OtaRecovery {
            operation_id,
            manifest: proof.manifest.clone(),
            phase: OtaPhase::Prepared,
            acknowledged_blocks: 0,
            pending_action: None,
            pending_block: None,
        };
        Ok(Self {
            proof,
            image,
            recovery,
            deadline: None,
            write_complete: false,
            acknowledged: false,
        })
    }
    pub fn recovery(&self) -> OtaRecovery {
        self.recovery.clone()
    }
    pub fn exclusive(&self) -> bool {
        !matches!(self.recovery.phase, OtaPhase::Prepared | OtaPhase::Complete)
            && (self.recovery.phase != OtaPhase::Failed || self.recovery.pending_action.is_some())
    }
    /// Native token validation and a second fresh battery/identity read precede this.
    pub fn confirm(
        &mut self,
        identity: &RingIdentity,
        battery: Option<u8>,
        charging: Option<bool>,
        now_ms: u64,
    ) -> Vec<OtaEffect> {
        if self.recovery.phase != OtaPhase::Prepared {
            return self.state("confirmation_already_used");
        }
        if identity != &self.proof.manifest.source
            || !self
                .proof
                .manifest
                .power_conditions
                .satisfied(battery, charging)
        {
            self.recovery.phase = OtaPhase::Failed;
            return self.state("ota_preconditions_changed");
        }
        self.recovery.phase = OtaPhase::Starting;
        self.send(1, None, candidate_start(), now_ms)
    }
    fn send(
        &mut self,
        action: u8,
        block: Option<u16>,
        bytes: Vec<u8>,
        now_ms: u64,
    ) -> Vec<OtaEffect> {
        self.recovery.pending_action = Some(action);
        self.recovery.pending_block = block;
        self.write_complete = false;
        self.acknowledged = false;
        let deadline = now_ms.saturating_add(TIMEOUT_MS);
        self.deadline = Some(deadline);
        vec![
            OtaEffect::Checkpoint {
                recovery: self.recovery.clone(),
            },
            OtaEffect::Write {
                bytes,
                action,
                block,
            },
            OtaEffect::Deadline { at_ms: deadline },
        ]
    }
    pub fn write_complete(&mut self, success: bool, now_ms: u64) -> Vec<OtaEffect> {
        if self.deadline.is_none() {
            return self.state("unexpected_ota_write_receipt");
        }
        if self.deadline.is_some_and(|deadline| now_ms >= deadline) {
            return self.unknown("ota_response_lost");
        }
        if !success {
            return self.unknown("ota_write_outcome_unknown");
        }
        self.write_complete = true;
        self.advance(now_ms)
    }
    /// A raw status-zero ACK alone is insufficient. A qualified native decoder
    /// must supply the echoed block index established by an actual loader proof.
    pub fn acknowledged(
        &mut self,
        action: u8,
        block: Option<u16>,
        status: u8,
        now_ms: u64,
    ) -> Vec<OtaEffect> {
        if self.deadline.is_some_and(|deadline| now_ms >= deadline) {
            return self.unknown("ota_response_lost");
        }
        if self.deadline.is_none()
            || self.recovery.pending_action != Some(action)
            || self.recovery.pending_block != block
        {
            return self.state("unrelated_ota_receipt");
        }
        if status != 0 {
            self.deadline = None;
            self.recovery.phase = OtaPhase::Failed;
            return self.state(match status {
                1 => "loader_size_error",
                2 => "loader_content_error",
                3 => "loader_status_error",
                4 => "loader_format_error",
                5 => "loader_internal_error",
                6 => "loader_low_battery",
                _ => "loader_unknown_error",
            });
        }
        self.acknowledged = true;
        self.advance(now_ms)
    }
    fn advance(&mut self, now_ms: u64) -> Vec<OtaEffect> {
        if !self.write_complete || !self.acknowledged {
            return Vec::new();
        }
        let action = self.recovery.pending_action.take();
        let block = self.recovery.pending_block.take();
        self.deadline = None;
        match action {
            Some(1) => {
                self.recovery.phase = OtaPhase::Initializing;
                self.send(
                    2,
                    None,
                    candidate_initialize(&self.image).expect("validated image"),
                    now_ms,
                )
            }
            Some(2) | Some(3) => {
                if let Some(block) = block {
                    self.recovery.acknowledged_blocks = block;
                }
                let offset = self.recovery.acknowledged_blocks as usize * BLOCK_BYTES;
                if offset >= self.image.len() {
                    self.recovery.phase = OtaPhase::Checking;
                    self.send(4, None, candidate_check(), now_ms)
                } else {
                    self.recovery.phase = OtaPhase::Transferring;
                    let index = self.recovery.acknowledged_blocks + 1;
                    let bytes = candidate_block(
                        index,
                        &self.image[offset..(offset + BLOCK_BYTES).min(self.image.len())],
                    )
                    .expect("validated block");
                    self.send(3, Some(index), bytes, now_ms)
                }
            }
            Some(4) => {
                self.recovery.phase = OtaPhase::Finalizing;
                self.send(5, None, candidate_end(), now_ms)
            }
            Some(5) => {
                self.recovery.phase = OtaPhase::Verifying;
                vec![
                    OtaEffect::Checkpoint {
                        recovery: self.recovery.clone(),
                    },
                    OtaEffect::ReadFirmware,
                ]
            }
            _ => self.unknown("ota_state_inconsistent"),
        }
    }
    pub fn deadline(&mut self, now_ms: u64) -> Vec<OtaEffect> {
        if self.deadline.is_some_and(|d| now_ms >= d) {
            self.unknown("ota_response_lost")
        } else {
            Vec::new()
        }
    }
    pub fn disconnected(&mut self) -> Vec<OtaEffect> {
        if self.exclusive() {
            self.unknown("ota_disconnected")
        } else {
            Vec::new()
        }
    }
    fn unknown(&mut self, reason: &'static str) -> Vec<OtaEffect> {
        self.deadline = None;
        self.recovery.phase = OtaPhase::Unknown;
        vec![
            OtaEffect::Checkpoint {
                recovery: self.recovery.clone(),
            },
            OtaEffect::State {
                recovery: self.recovery.clone(),
                reason,
            },
        ]
    }
    fn state(&self, reason: &'static str) -> Vec<OtaEffect> {
        vec![OtaEffect::State {
            recovery: self.recovery.clone(),
            reason,
        }]
    }
    /// Reconciliation is read-only. No loader offset or safe replay command was
    /// proved in the SDK; an unknown transfer never resumes from guessed progress.
    pub fn reconcile(&mut self) -> Vec<OtaEffect> {
        if matches!(
            self.recovery.phase,
            OtaPhase::Unknown | OtaPhase::Verifying | OtaPhase::Failed
        ) && self.exclusive()
        {
            vec![OtaEffect::ReadFirmware]
        } else {
            self.state("ota_reconciliation_unavailable")
        }
    }
    pub fn firmware_read(
        &mut self,
        source_hardware: &str,
        actual_firmware: &str,
    ) -> Vec<OtaEffect> {
        if !matches!(
            self.recovery.phase,
            OtaPhase::Unknown | OtaPhase::Verifying | OtaPhase::Failed
        ) || !self.exclusive()
        {
            return self.state("unexpected_firmware_read");
        }
        if source_hardware != self.proof.manifest.source.hardware {
            return self.unknown("ota_target_identity_changed");
        }
        if actual_firmware != self.proof.manifest.target_firmware {
            return self.unknown("ota_firmware_not_confirmed");
        }
        self.recovery.phase = OtaPhase::Complete;
        self.recovery.pending_action = None;
        self.recovery.pending_block = None;
        vec![
            OtaEffect::Checkpoint {
                recovery: self.recovery.clone(),
            },
            OtaEffect::State {
                recovery: self.recovery.clone(),
                reason: "firmware_readback_confirmed",
            },
        ]
    }
    /// Recovery requires the same compiled manifest and image digest. It preserves
    /// uncertainty and invalidates every confirmation; it cannot send a write.
    pub fn restore(&mut self, recovery: OtaRecovery) -> Result<Vec<OtaEffect>, &'static str> {
        if recovery.manifest != self.proof.manifest
            || recovery.operation_id != self.recovery.operation_id
            || recovery.acknowledged_blocks as usize > self.image.len().div_ceil(BLOCK_BYTES)
        {
            return Err("invalid_ota_recovery");
        }
        self.recovery = recovery;
        if self.recovery.phase == OtaPhase::Complete {
            return Ok(self.state("firmware_readback_confirmed"));
        }
        Ok(self.unknown("ota_process_interrupted"))
    }
}

#[cfg(test)]
mod tests;
