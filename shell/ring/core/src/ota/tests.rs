use super::*;
use crate::codec::BulkAssembler;

fn source() -> RingIdentity {
    RingIdentity {
        model: "synthetic".into(),
        hardware: "synthetic-hw".into(),
        firmware: "source-fw".into(),
    }
}
fn manager(image: Vec<u8>) -> OtaManager {
    let manifest = OtaManifest {
        manifest_id: "synthetic-manifest".into(),
        official_origin: "https://example.invalid".into(),
        image_path: "/image.bin".into(),
        source: source(),
        target_firmware: "target-fw".into(),
        size_bytes: image.len() as u32,
        sha256: "a".repeat(64),
        evidence_ref: "synthetic-only".into(),
        power_conditions: PowerConditions {
            minimum_battery_percent: 30,
            charging: ChargingRequirement::NotRequired,
            evidence_ref: "synthetic-power-only".into(),
        },
    };
    OtaManager::prepared(
        LoaderProof {
            manifest,
            correlated_block_ack: true,
        },
        image,
        "ota-1".into(),
        &source(),
        Some(30),
        Some(false),
        &"a".repeat(64),
    )
    .unwrap()
}
fn wrote(effects: &[OtaEffect]) -> bool {
    effects.iter().any(|e| matches!(e, OtaEffect::Write { .. }))
}
fn ack(manager: &mut OtaManager, action: u8, block: Option<u16>, now: u64) -> Vec<OtaEffect> {
    manager.write_complete(true, now);
    manager.acknowledged(action, block, 0, now + 1)
}

#[test]
fn no_catalog_input_or_sdk_presence_can_enable_production_ota() {
    assert!(production_catalog().is_empty());
    assert!(OtaManager::prepare("untrusted", &source(), Some(100), None).is_err());
    assert!(
        OtaManager::prepare_image(
            "untrusted",
            vec![1],
            "ota-1".into(),
            &source(),
            Some(100),
            Some(false),
            &"a".repeat(64)
        )
        .is_err()
    );
}
#[test]
fn oudmon_framing_uses_full_action_size_and_crc() {
    let image = vec![0x5a; 1025];
    let mut assembler = BulkAssembler::default();
    let init = candidate_initialize(&image).unwrap();
    let parsed = assembler.push(&init).unwrap();
    assert_eq!(parsed[0].action, 2);
    assert_eq!(parsed[0].payload.len(), 9);
    assert_eq!(parsed[0].payload[1..5], 1025u32.to_le_bytes());
    let last = candidate_block(2, &image[1024..]).unwrap();
    let parsed = assembler.push(&last).unwrap();
    assert_eq!(parsed[0].payload, vec![2, 0, 0x5a]);
    assert_eq!(candidate_start(), vec![0xbc, 1, 0, 0, 0xff, 0xff]);
    assert!(candidate_initialize(&[]).is_err());
    assert!(candidate_block(0, &[1]).is_err());
    assert!(candidate_block(1, &vec![1; 1025]).is_err());
}
#[test]
fn fresh_identity_battery_and_actual_image_digest_are_mandatory() {
    let baseline = manager(vec![1]);
    let proof = baseline.proof.clone();
    for battery in [None, Some(0), Some(29), Some(101)] {
        assert!(
            OtaManager::prepared(
                proof.clone(),
                vec![1],
                "ota-1".into(),
                &source(),
                battery,
                Some(false),
                &"a".repeat(64)
            )
            .is_err()
        );
    }
    assert!(
        OtaManager::prepared(
            proof.clone(),
            vec![1],
            "ota-1".into(),
            &source(),
            Some(100),
            Some(false),
            &"b".repeat(64)
        )
        .is_err()
    );
    let mut wrong = source();
    wrong.hardware = "other".into();
    assert!(
        OtaManager::prepared(
            proof,
            vec![1],
            "ota-1".into(),
            &wrong,
            Some(100),
            Some(false),
            &"a".repeat(64)
        )
        .is_err()
    );
}
#[test]
fn att_completion_and_uncorrelated_status_zero_cannot_advance_a_data_block() {
    let mut updater = manager(vec![1; 1025]);
    let start = updater.confirm(&source(), Some(30), Some(false), 0);
    assert!(matches!(start.first(), Some(OtaEffect::Checkpoint { .. })));
    ack(&mut updater, 1, None, 1);
    ack(&mut updater, 2, None, 3);
    assert_eq!(updater.recovery.phase, OtaPhase::Transferring);
    assert!(updater.write_complete(true, 5).is_empty());
    assert!(!wrote(&updater.acknowledged(3, None, 0, 6)));
    assert_eq!(updater.recovery.acknowledged_blocks, 0);
    let second = updater.acknowledged(3, Some(1), 0, 7);
    assert!(wrote(&second));
    assert_eq!(updater.recovery.pending_block, Some(2));
    assert!(!wrote(&updater.acknowledged(3, Some(1), 0, 8))); // delayed duplicate
    assert_eq!(updater.recovery.acknowledged_blocks, 1);
}
#[test]
fn timeout_disconnect_and_process_restore_never_resend_unknown_bytes() {
    let mut updater = manager(vec![1; 1025]);
    updater.confirm(&source(), Some(100), Some(false), 0);
    let lost = updater.deadline(TIMEOUT_MS);
    assert_eq!(updater.recovery.phase, OtaPhase::Unknown);
    assert!(!wrote(&lost));
    let recovery = updater.recovery();
    assert!(!wrote(&updater.reconcile()));
    let mut restored = manager(vec![1; 1025]);
    let effects = restored.restore(recovery).unwrap();
    assert!(!wrote(&effects));
    assert!(restored.exclusive());
    assert!(!wrote(&restored.confirm(
        &source(),
        Some(100),
        Some(false),
        TIMEOUT_MS + 1
    )));
}
#[test]
fn end_ack_requires_firmware_readback_on_the_same_hardware() {
    let mut updater = manager(vec![1]);
    updater.confirm(&source(), Some(100), Some(false), 0);
    ack(&mut updater, 1, None, 1);
    ack(&mut updater, 2, None, 3);
    ack(&mut updater, 3, Some(1), 5);
    ack(&mut updater, 4, None, 7);
    let verify = ack(&mut updater, 5, None, 9);
    assert!(verify.iter().any(|e| matches!(e, OtaEffect::ReadFirmware)));
    assert_eq!(updater.recovery.phase, OtaPhase::Verifying);
    updater.firmware_read("other-hw", "target-fw");
    assert_eq!(updater.recovery.phase, OtaPhase::Unknown);
    updater.firmware_read("synthetic-hw", "source-fw");
    assert!(updater.exclusive());
    updater.firmware_read("synthetic-hw", "target-fw");
    assert_eq!(updater.recovery.phase, OtaPhase::Complete);
    assert!(!updater.exclusive());
}

#[test]
fn late_ack_and_late_att_completion_stop_without_waiting_for_a_deadline_event() {
    let mut ack_late = manager(vec![1; 1025]);
    ack_late.confirm(&source(), Some(100), Some(false), 0);
    ack_late.write_complete(true, TIMEOUT_MS - 1);
    let effects = ack_late.acknowledged(1, None, 0, TIMEOUT_MS);
    assert_eq!(ack_late.recovery.phase, OtaPhase::Unknown);
    assert_eq!(ack_late.recovery.acknowledged_blocks, 0);
    assert!(!wrote(&effects));
    assert!(!wrote(&ack_late.acknowledged(1, None, 0, TIMEOUT_MS + 1)));

    let mut att_late = manager(vec![1; 1025]);
    att_late.confirm(&source(), Some(100), Some(false), 0);
    att_late.acknowledged(1, None, 0, TIMEOUT_MS - 1);
    let effects = att_late.write_complete(true, TIMEOUT_MS + 1);
    assert_eq!(att_late.recovery.phase, OtaPhase::Unknown);
    assert_eq!(att_late.recovery.pending_action, Some(1));
    assert!(!wrote(&effects));
}
#[test]
fn qualified_power_conditions_are_checked_on_prepare_and_again_before_flash() {
    let mut proof = manager(vec![1]).proof.clone();
    proof.manifest.power_conditions.minimum_battery_percent = 60;
    proof.manifest.power_conditions.charging = ChargingRequirement::Required;
    for (battery, charging) in [
        (Some(59), Some(true)),
        (Some(100), None),
        (Some(100), Some(false)),
    ] {
        assert!(
            OtaManager::prepared(
                proof.clone(),
                vec![1],
                "power".into(),
                &source(),
                battery,
                charging,
                &"a".repeat(64)
            )
            .is_err()
        );
    }
    let mut updater = OtaManager::prepared(
        proof.clone(),
        vec![1],
        "power".into(),
        &source(),
        Some(60),
        Some(true),
        &"a".repeat(64),
    )
    .unwrap();
    assert!(!wrote(&updater.confirm(&source(), Some(60), None, 0)));
    assert_eq!(updater.recovery.phase, OtaPhase::Failed);
    proof.manifest.power_conditions.evidence_ref.clear();
    assert!(proof.manifest.validate().is_err());
}
