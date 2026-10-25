//! Local core73 events, with source meaning kept separate from physical proof.
//! Fresh identity, connection generation and opaque native source are mandatory.
use crate::codec::{decode_core, hex};
use crate::control::RingIdentity;
use serde::Serialize;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum EventMeaning {
    TasbihCount { count: u16 },
    GameTap,
}
#[derive(Debug, Clone, Serialize)]
pub struct DeviceEvent {
    pub namespace: &'static str,
    pub opcode: u8,
    pub data_type: u8,
    pub profile_id: &'static str,
    pub decoder_id: &'static str,
    pub identity: RingIdentity,
    pub generation: u64,
    pub physical_source_id: String,
    pub received_monotonic_ms: u64,
    pub raw_payload_hex: String,
    pub qualification: &'static str,
    pub source_evidence_ref: &'static str,
    pub physical_evidence_ref: Option<&'static str>,
    pub candidate: EventMeaning,
    pub resolved: Option<EventMeaning>,
}
#[derive(Clone)]
struct EventProof {
    identity: RingIdentity,
    physical_source_id: String,
    data_type: u8,
    evidence_ref: &'static str,
}
// The approved capture contains core73 types0c/3e, no game25/29 or Tasbih38.
// Capability3c and SDK callbacks do not qualify a physical event source.
fn production_proofs() -> Vec<EventProof> {
    Vec::new()
}
pub fn valid_source_id(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}
pub fn candidate_meaning(bytes: &[u8]) -> Option<EventMeaning> {
    let frame = decode_core(bytes).ok()?;
    if frame.opcode != 0x73 {
        return None;
    }
    match frame.payload[0] {
        0x38 => Some(EventMeaning::TasbihCount {
            count: u16::from_le_bytes([frame.payload[2], frame.payload[3]]),
        }),
        0x25 | 0x29 => Some(EventMeaning::GameTap),
        _ => None,
    }
}
pub fn observation(
    identity: &RingIdentity,
    source: &str,
    generation: u64,
    bytes: &[u8],
    at_ms: u64,
) -> Option<DeviceEvent> {
    observe_with_proofs(
        identity,
        source,
        generation,
        bytes,
        at_ms,
        &production_proofs(),
    )
}
fn observe_with_proofs(
    identity: &RingIdentity,
    source: &str,
    generation: u64,
    bytes: &[u8],
    at_ms: u64,
    proofs: &[EventProof],
) -> Option<DeviceEvent> {
    if !valid_source_id(source)
        || identity.model != "qring-r20"
        || identity.hardware != "RF20_V2.1"
        || identity.firmware != "RF20_2.10.07_260131"
    {
        return None;
    }
    let candidate = candidate_meaning(bytes)?;
    let frame = decode_core(bytes).ok()?;
    let proof = proofs.iter().find(|proof| {
        proof.identity == *identity
            && proof.physical_source_id == source
            && proof.data_type == frame.payload[0]
            && !proof.evidence_ref.is_empty()
    });
    Some(DeviceEvent {
        namespace: "core",
        opcode: 0x73,
        data_type: frame.payload[0],
        profile_id: "oudmon-r20",
        decoder_id: "soul1214-core73-device-notify-v1",
        identity: identity.clone(),
        generation,
        physical_source_id: source.to_owned(),
        received_monotonic_ms: at_ms,
        raw_payload_hex: hex(&frame.payload),
        qualification: if proof.is_some() {
            "verified"
        } else {
            "protocol_unverified"
        },
        source_evidence_ref: match frame.payload[0] {
            0x38 => "soul1214:e99-core73-tasbih38",
            _ => "soul1214:e99-core73-game25-29",
        },
        physical_evidence_ref: proof.map(|proof| proof.evidence_ref),
        resolved: proof.map(|_| candidate.clone()),
        candidate,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::codec::core_command;
    fn identity() -> RingIdentity {
        RingIdentity {
            model: "qring-r20".into(),
            hardware: "RF20_V2.1".into(),
            firmware: "RF20_2.10.07_260131".into(),
        }
    }
    #[test]
    fn exact_namespace_checksum_identity_and_physical_source_are_required() {
        let mut frame = core_command(0x73, &[0x38, 0, 0x34, 0x12]).unwrap();
        let source = "a".repeat(64);
        let event = observation(&identity(), &source, 2, &frame, 10).unwrap();
        assert_eq!(event.candidate, EventMeaning::TasbihCount { count: 0x1234 });
        assert!(event.resolved.is_none());
        assert_eq!(event.qualification, "protocol_unverified");
        assert!(observation(&identity(), "address", 2, &frame, 10).is_none());
        let changed = RingIdentity {
            firmware: "different".into(),
            ..identity()
        };
        assert!(observation(&changed, &source, 2, &frame, 10).is_none());
        assert!(candidate_meaning(&core_command(0xf3, &[0x38, 0, 1, 0]).unwrap()).is_none());
        frame[15] ^= 1;
        assert!(candidate_meaning(&frame).is_none());
    }
    #[test]
    fn game_taps_need_a_proof_for_this_exact_event_type_and_source() {
        let source = "b".repeat(64);
        let proof = EventProof {
            identity: identity(),
            physical_source_id: source.clone(),
            data_type: 0x25,
            evidence_ref: "synthetic-only",
        };
        for kind in [0x25, 0x29] {
            let frame = core_command(0x73, &[kind]).unwrap();
            let candidate = observation(&identity(), &source, 4, &frame, 20).unwrap();
            assert_eq!(candidate.candidate, EventMeaning::GameTap);
            assert!(candidate.resolved.is_none());
            let qualified = observe_with_proofs(
                &identity(),
                &source,
                4,
                &frame,
                20,
                std::slice::from_ref(&proof),
            )
            .unwrap();
            assert_eq!(qualified.resolved.is_some(), kind == 0x25);
            assert!(
                observe_with_proofs(
                    &identity(),
                    &"c".repeat(64),
                    4,
                    &frame,
                    20,
                    std::slice::from_ref(&proof)
                )
                .unwrap()
                .resolved
                .is_none()
            );
        }
    }
}
