use super::*;
use crate::codec::{decode_core, unhex};

fn identity() -> RingIdentity {
    RingIdentity {
        model: "synthetic".into(),
        hardware: "synthetic-hw".into(),
        firmware: "synthetic-fw".into(),
    }
}
fn synthetic(kind: DeviceKind) -> DeviceManager {
    let mut manager = DeviceManager::new(identity()).unwrap();
    manager.proofs = vec![SettingProof {
        kind,
        evidence_ref: "synthetic-only",
        writable: true,
    }];
    manager
}
fn request(command: DeviceCommand) -> DeviceRequest {
    DeviceRequest {
        operation_id: "operation-1".into(),
        command,
    }
}
fn written(effects: &[DeviceEffect]) -> Vec<u8> {
    effects
        .iter()
        .find_map(|e| {
            if let DeviceEffect::Write { bytes_hex, .. } = e {
                Some(unhex(bytes_hex, 16).unwrap())
            } else {
                None
            }
        })
        .unwrap()
}
fn receipt(effects: &[DeviceEffect]) -> &DeviceResult {
    effects
        .iter()
        .find_map(|e| {
            if let DeviceEffect::Result(result) = e {
                Some(result)
            } else {
                None
            }
        })
        .unwrap()
}
fn notify(manager: &mut DeviceManager, opcode: u8, payload: &[u8], now: u64) -> Vec<DeviceEffect> {
    let (consumed, effects) = manager.notification(&frame(opcode, payload), now);
    assert!(consumed);
    effects
}

#[test]
fn production_read_proofs_do_not_enable_any_mutation() {
    let known = RingIdentity {
        model: "qring-r20".into(),
        hardware: "RF20_V2.1".into(),
        firmware: "RF20_2.10.07_260131".into(),
    };
    let mut manager = DeviceManager::new(known).unwrap();
    assert!(manager.capabilities().iter().all(|cap| !cap.writable));
    assert!(
        manager
            .capabilities()
            .iter()
            .filter(|cap| cap.readable)
            .all(|cap| matches!(cap.kind, DeviceKind::Profile | DeviceKind::Gestures))
    );
    for action in [
        DeviceAction::FindRing,
        DeviceAction::Bind,
        DeviceAction::Unbind,
        DeviceAction::FactoryReset,
        DeviceAction::PowerOff,
        DeviceAction::TasbihStart,
        DeviceAction::TasbihStop,
    ] {
        let effects = manager.request(request(DeviceCommand::Action { action }), 0);
        assert_eq!(receipt(&effects).status, ControlStatus::Rejected);
        assert!(
            !effects
                .iter()
                .any(|effect| matches!(effect, DeviceEffect::Write { .. }))
        );
    }
    let changed = RingIdentity {
        firmware: "different-fw".into(),
        ..manager.identity().clone()
    };
    assert!(
        capabilities(&changed)
            .iter()
            .all(|cap| !cap.readable && !cap.writable)
    );
}

#[test]
fn zero_profile_and_unmapped_gesture_reads_preserve_semantic_uncertainty() {
    let known = RingIdentity {
        model: "qring-r20".into(),
        hardware: "RF20_V2.1".into(),
        firmware: "RF20_2.10.07_260131".into(),
    };
    for (kind, opcode, payload) in [
        (DeviceKind::Profile, 0x0a, vec![1]),
        (DeviceKind::Gestures, 0x3b, vec![1, 1]),
    ] {
        let mut manager = DeviceManager::new(known.clone()).unwrap();
        manager.request(request(DeviceCommand::Read { kind }), 0);
        manager.write_complete(true, 1);
        let effects = notify(&mut manager, opcode, &payload, 2);
        let result = receipt(&effects);
        assert_eq!(result.status, ControlStatus::Verified);
        assert!(result.snapshot.is_none());
        assert_eq!(result.wire_state.as_ref().unwrap().raw_payload_hex.len(), 1);
        assert_eq!(result.reason, "wire_read_confirmed_semantics_unknown");
    }
}

#[test]
fn dnd_mutation_checkpoints_before_write_and_att_ack_never_verifies() {
    let setting = DeviceSetting::Dnd {
        enabled: false,
        start_minute: 1320,
        end_minute: 420,
    };
    let mut manager = synthetic(DeviceKind::Dnd);
    manager.request(
        request(DeviceCommand::Update {
            setting: setting.clone(),
        }),
        0,
    );
    // Notification may precede the ATT completion; it must wait for both.
    assert!(notify(&mut manager, 0x06, &[1, 1, 22, 0, 7, 0], 1).is_empty());
    let mutation = manager.write_complete(true, 2);
    assert!(
        matches!(mutation.first(), Some(DeviceEffect::Checkpoint(recovery)) if recovery.mutation_started)
    );
    assert_eq!(
        decode_core(&written(&mutation)).unwrap().payload[..6],
        [2, 2, 22, 0, 7, 0]
    );
    assert!(!manager.notification(&frame(0x06, &[2]), 3).0);
    let readback = manager.write_complete(true, 4);
    assert_eq!(decode_core(&written(&readback)).unwrap().payload[0], 1);
    assert!(
        !readback
            .iter()
            .any(|e| matches!(e, DeviceEffect::Result(_)))
    );
    manager.write_complete(true, 5);
    let result = notify(&mut manager, 0x06, &[1, 2, 22, 0, 7, 0], 6);
    assert_eq!(receipt(&result).snapshot, Some(setting));
    assert_eq!(receipt(&result).status, ControlStatus::Verified);
}

#[test]
fn lost_mutation_is_durable_and_reconciliation_cannot_repeat_write() {
    let command = DeviceCommand::Update {
        setting: DeviceSetting::Dnd {
            enabled: false,
            start_minute: 1320,
            end_minute: 420,
        },
    };
    let mut manager = synthetic(DeviceKind::Dnd);
    manager.request(request(command.clone()), 0);
    manager.write_complete(true, 1);
    let mutation = notify(&mut manager, 0x06, &[1, 1, 22, 0, 7, 0], 2);
    let recovery = mutation
        .iter()
        .find_map(|e| {
            if let DeviceEffect::Checkpoint(r) = e {
                Some(r.clone())
            } else {
                None
            }
        })
        .unwrap();
    let unknown = manager.write_complete(false, 3);
    assert_eq!(receipt(&unknown).status, ControlStatus::Unknown);
    let repeated = manager.request(request(command), 4);
    assert_eq!(receipt(&repeated).status, ControlStatus::Unknown);
    assert!(
        !repeated
            .iter()
            .any(|e| matches!(e, DeviceEffect::Write { .. }))
    );
    let mut restored = synthetic(DeviceKind::Dnd);
    assert_eq!(
        receipt(&restored.restore_unknown(recovery)).status,
        ControlStatus::Unknown
    );
    let reconcile = restored.reconcile("operation-1", 5);
    assert_eq!(decode_core(&written(&reconcile)).unwrap().payload[0], 1);
    restored.write_complete(true, 6);
    let mismatch = notify(&mut restored, 0x06, &[1, 1, 22, 0, 7, 0], 7);
    assert_eq!(receipt(&mismatch).status, ControlStatus::Unknown);
    assert!(restored.has_unresolved_mutation());
}

#[test]
fn five_alarm_reads_are_slot_scoped_and_a_duplicate_cannot_advance() {
    let mut manager = synthetic(DeviceKind::Alarms);
    manager.request(
        request(DeviceCommand::Read {
            kind: DeviceKind::Alarms,
        }),
        0,
    );
    for slot in 0..5u8 {
        manager.write_complete(true, slot as u64 * 2 + 1);
        if slot > 0 {
            assert!(
                !manager
                    .notification(&frame(0x24, &[slot - 1, 1, 0x07, 0x30, 1]), 9)
                    .0
            );
        }
        let effects = notify(
            &mut manager,
            0x24,
            &[slot, 1, 0x07, 0x30, 1],
            slot as u64 * 2 + 2,
        );
        if slot < 4 {
            assert_eq!(
                decode_core(&written(&effects)).unwrap().payload[0],
                slot + 1
            );
        } else {
            assert!(
                matches!(receipt(&effects).snapshot.as_ref(), Some(DeviceSetting::Alarms { slots }) if slots.len() == 5)
            );
        }
    }
}

#[test]
fn profile_update_preserves_unmodeled_tail_and_tasbih_has_no_target() {
    let setting = DeviceSetting::Profile {
        sex: Sex::Female,
        age: 25,
        height_cm: 170,
        weight_kg: 60,
        is_24_hour: true,
        units: Units::Metric,
        systolic_mm_hg: 0,
        diastolic_mm_hg: 0,
        heart_rate_warning_bpm: 0,
    };
    let mut raw = [0u8; 14];
    raw[0] = 1;
    raw[10..].copy_from_slice(&[9, 8, 7, 6]);
    let frames = candidate_writes(&setting, &[raw]).unwrap();
    assert_eq!(decode_core(&frames[0]).unwrap().payload[10..], [9, 8, 7, 6]);
    assert!(candidate_writes(&setting, &[]).is_err());
    assert_eq!(
        decode_core(&candidate_action(DeviceAction::TasbihStart).unwrap())
            .unwrap()
            .payload[..6],
        [2, 3, 1, 0, 0, 0]
    );
    assert_eq!(
        decode_core(&candidate_action(DeviceAction::TasbihStop).unwrap())
            .unwrap()
            .payload[..6],
        [2, 3, 2, 0, 0, 0]
    );
    assert!(candidate_reads(DeviceKind::Counter).is_err());
    assert!(candidate_snapshot(DeviceKind::Counter, &[raw]).is_err());
    assert_eq!(
        decode_core(&candidate_sdk_counter_read()).unwrap().payload[..2],
        [1, 1]
    );
    assert!(
        serde_json::from_value::<DeviceSetting>(
            serde_json::json!({"kind":"counter","enabled":true,"target":100})
        )
        .is_err()
    );
    assert_ne!(
        candidate_action(DeviceAction::FactoryReset).unwrap(),
        candidate_action(DeviceAction::PowerOff).unwrap()
    );
    assert!(candidate_action(DeviceAction::Unbind).is_err());
}

#[test]
fn invalid_bcd_unknown_alarm_mode_and_unqualified_hid_mapping_are_not_guessed() {
    let mut data = [[0u8; 14]; 5];
    for (index, p) in data.iter_mut().enumerate() {
        p[0] = index as u8;
        p[1] = 1;
        p[2] = 0x07;
        p[3] = 0x30;
    }
    data[0][2] = 0x1a;
    assert!(candidate_snapshot(DeviceKind::Alarms, &data).is_err());
    data[0][2] = 0x07;
    data[0][1] = 2;
    assert!(candidate_snapshot(DeviceKind::Alarms, &data).is_err());
    assert!(
        candidate_writes(
            &DeviceSetting::Gestures {
                enabled: true,
                mode: GestureMode::Game,
                hand: Hand::Right
            },
            &[]
        )
        .is_err()
    );
    assert!(
        DeviceSetting::Clock {
            year: 2027,
            month: 2,
            day: 29,
            hour: 0,
            minute: 0,
            second: 0,
            language: 10
        }
        .validate()
        .is_err()
    );
}

#[test]
fn notification_framing_bounds_utf8_and_preserves_fragment_order() {
    let text = "🙂".repeat(200);
    let parts = candidate_notifications(1, &text).unwrap();
    assert_eq!(parts.len(), 512usize.div_ceil(11));
    let mut joined = Vec::new();
    for (index, part) in parts.iter().enumerate() {
        let frame = decode_core(part).unwrap();
        assert_eq!(frame.opcode, 0x72);
        assert_eq!(
            frame.payload[..3],
            [1, parts.len() as u8, (index + 1) as u8]
        );
        joined.extend(&frame.payload[3..]);
    }
    joined.truncate(512);
    assert_eq!(String::from_utf8(joined).unwrap(), "🙂".repeat(128));
    assert!(candidate_notifications(10, "message").is_err());
    assert!(candidate_notifications(1, "a\0b").is_err());
}

#[test]
fn action_transport_ack_waits_for_qualified_semantic_receipt() {
    let mut manager = synthetic(DeviceKind::FindRing);
    manager.actions.insert(
        DeviceAction::FindRing,
        DeviceActionRecipe {
            request: frame(0x50, &[0x55, 0xaa]),
            receipt_opcode: 0x50,
            receipt_payload: vec![0x55, 0xaa, 1],
            evidence_ref: "synthetic-physical-receipt",
        },
    );
    let effects = manager.request(
        request(DeviceCommand::Action {
            action: DeviceAction::FindRing,
        }),
        0,
    );
    assert!(matches!(effects.first(), Some(DeviceEffect::Checkpoint(_))));
    assert!(manager.write_complete(true, 1).is_empty());
    assert!(!manager.notification(&frame(0x50, &[0x55, 0xaa]), 2).0);
    let result = notify(&mut manager, 0x50, &[0x55, 0xaa, 1], 3);
    assert_eq!(receipt(&result).status, ControlStatus::Verified);
}

#[test]
fn ephemeral_notification_never_enters_durable_mutation_recovery() {
    let mut manager = synthetic(DeviceKind::Notifications);
    let effects = manager.request(
        request(DeviceCommand::Notification {
            message_type: 1,
            text: "synthetic".into(),
        }),
        0,
    );
    assert!(
        !effects
            .iter()
            .any(|e| matches!(e, DeviceEffect::Checkpoint(_)))
    );
    let result = manager.write_complete(true, 1);
    assert_eq!(receipt(&result).status, ControlStatus::Unknown);
    assert_eq!(receipt(&result).reason, "notification_delivery_unconfirmed");
    assert!(!manager.has_unresolved_mutation());
}

#[test]
fn candidate_counter_notify_keeps_full_opcode_and_little_endian_width() {
    assert_eq!(
        candidate_counter_event(&frame(0x73, &[0x38, 0, 0x34, 0x12])),
        Some(0x1234)
    );
    assert_eq!(
        candidate_counter_event(&frame(0xf3, &[0x38, 0, 0x34, 0x12])),
        None
    );
    assert_eq!(
        candidate_counter_event(&frame(0x73, &[1, 0, 0x34, 0x12])),
        None
    );
}

#[test]
fn native_archive_ack_releases_terminal_cache_but_never_unknown_mutations() {
    let known = RingIdentity {
        model: "qring-r20".into(),
        hardware: "RF20_V2.1".into(),
        firmware: "RF20_2.10.07_260131".into(),
    };
    let mut manager = DeviceManager::new(known).unwrap();
    for index in 0..300 {
        let id = format!("archived-read-{index}");
        manager.request(
            DeviceRequest {
                operation_id: id.clone(),
                command: DeviceCommand::Read {
                    kind: DeviceKind::Profile,
                },
            },
            0,
        );
        manager.write_complete(true, 1);
        notify(&mut manager, 0x0a, &[1], 2);
        manager.receipt_stored(&id);
    }
    assert!(!manager.has_operations());
    let mut unknown = synthetic(DeviceKind::Dnd);
    unknown.request(
        request(DeviceCommand::Update {
            setting: DeviceSetting::Dnd {
                enabled: false,
                start_minute: 0,
                end_minute: 1,
            },
        }),
        0,
    );
    unknown.write_complete(true, 1);
    notify(&mut unknown, 0x06, &[1, 1, 0, 0, 0, 1], 2);
    unknown.write_complete(false, 3);
    unknown.receipt_stored("operation-1");
    assert!(unknown.has_unresolved_mutation());
    assert!(unknown.receipts.contains_key("operation-1"));
}

fn drink_before() -> Vec<[u8; 14]> {
    (0..8u8)
        .map(|slot| {
            let mut payload = [0; 14];
            payload[..5].copy_from_slice(&[slot, 1, 0x07, 0x30, 1]);
            payload[11..].copy_from_slice(&[slot + 1, 0xab, 0xcd]);
            payload
        })
        .collect()
}
#[test]
fn drink_change_writes_only_changed_slot_and_preserves_other_seven_and_unknown_tail() {
    let before = drink_before();
    let DeviceSetting::DrinkReminders { mut slots } =
        candidate_snapshot(DeviceKind::DrinkReminders, &before).unwrap()
    else {
        panic!("drink setting");
    };
    slots[3].minute = 45;
    let setting = DeviceSetting::DrinkReminders {
        slots: slots.clone(),
    };
    let writes = candidate_writes(&setting, &before).unwrap();
    assert_eq!(writes.len(), 1);
    let write = decode_core(&writes[0]).unwrap();
    assert_eq!(write.opcode, 0x27);
    assert_eq!(write.payload[..4], [3, 1, 0x07, 0x45]);
    assert_eq!(write.payload[11..], before[3][11..]);
    assert!(candidate_writes(&setting, &before[..7]).is_err());
    let expected = drink_payloads(&slots, &before).unwrap();
    for index in [0, 1, 2, 4, 5, 6, 7] {
        assert_eq!(expected[index], before[index]);
    }
    let mut manager = synthetic(DeviceKind::DrinkReminders);
    manager.request(request(DeviceCommand::Update { setting }), 0);
    let mut mutation = Vec::new();
    for (index, payload) in before.iter().enumerate() {
        manager.write_complete(true, index as u64 * 2 + 1);
        if index > 0 {
            assert!(!manager.notification(&frame(0x28, &before[index - 1]), 1).0);
        }
        mutation = notify(&mut manager, 0x28, payload, index as u64 * 2 + 2);
    }
    let recovery = mutation
        .iter()
        .find_map(|effect| {
            if let DeviceEffect::Checkpoint(recovery) = effect {
                Some(recovery.clone())
            } else {
                None
            }
        })
        .unwrap();
    assert_eq!(recovery.expected_readback, expected);
    assert_eq!(written(&mutation), writes[0]);
    assert_eq!(
        receipt(&manager.write_complete(false, 20)).status,
        ControlStatus::Unknown
    );
    let mut restored = synthetic(DeviceKind::DrinkReminders);
    restored.restore_unknown(recovery);
    let reconcile = restored.reconcile("operation-1", 21);
    assert_eq!(decode_core(&written(&reconcile)).unwrap().opcode, 0x28);
    let mut result = Vec::new();
    for (index, payload) in expected.iter().enumerate() {
        restored.write_complete(true, index as u64 * 2 + 22);
        let mut returned = *payload;
        if index == 7 {
            returned[13] ^= 1;
        }
        result = notify(&mut restored, 0x28, &returned, index as u64 * 2 + 23);
    }
    assert_eq!(receipt(&result).status, ControlStatus::Unknown);
    assert_eq!(
        receipt(&result).reason,
        "preserved_fields_readback_mismatch"
    );
    assert!(restored.has_unresolved_mutation());
    let reads = restored.reconcile("operation-1", 50);
    assert_eq!(decode_core(&written(&reads)).unwrap().opcode, 0x28);
    for (index, payload) in expected.iter().enumerate() {
        restored.write_complete(true, index as u64 * 2 + 51);
        result = notify(&mut restored, 0x28, payload, index as u64 * 2 + 52);
    }
    assert_eq!(receipt(&result).status, ControlStatus::Verified);
    assert!(!restored.has_unresolved_mutation());
}
#[test]
fn unchanged_drink_snapshot_performs_no_mutation_and_still_reads_back_all_slots() {
    let before = drink_before();
    let setting = candidate_snapshot(DeviceKind::DrinkReminders, &before).unwrap();
    assert!(candidate_writes(&setting, &before).unwrap().is_empty());
    let mut manager = synthetic(DeviceKind::DrinkReminders);
    manager.request(
        request(DeviceCommand::Update {
            setting: setting.clone(),
        }),
        0,
    );
    for (index, payload) in before.iter().enumerate() {
        manager.write_complete(true, index as u64 * 2 + 1);
        let effects = notify(&mut manager, 0x28, payload, index as u64 * 2 + 2);
        assert!(
            !effects
                .iter()
                .any(|effect| matches!(effect, DeviceEffect::Checkpoint(_)))
        );
        assert_eq!(decode_core(&written(&effects)).unwrap().opcode, 0x28);
    }
    for (index, payload) in before.iter().enumerate() {
        manager.write_complete(true, index as u64 * 2 + 20);
        let effects = notify(&mut manager, 0x28, payload, index as u64 * 2 + 21);
        if index == 7 {
            assert_eq!(receipt(&effects).snapshot, Some(setting.clone()));
        }
    }
}
#[test]
fn drink_read_proof_and_unknown_enable_do_not_authorize_writes_or_create_defaults() {
    let mut manager = synthetic(DeviceKind::DrinkReminders);
    manager.proofs[0].writable = false;
    let setting = candidate_snapshot(DeviceKind::DrinkReminders, &drink_before()).unwrap();
    let rejected = manager.request(request(DeviceCommand::Update { setting }), 0);
    assert_eq!(receipt(&rejected).status, ControlStatus::Rejected);
    assert!(
        !rejected
            .iter()
            .any(|effect| matches!(effect, DeviceEffect::Write { .. }))
    );
    let mut before = drink_before();
    before[0][1] = 2;
    assert_eq!(
        candidate_snapshot(DeviceKind::DrinkReminders, &before).unwrap_err(),
        "unknown_alarm_format"
    );
    before[0][1] = 1;
    before[0][2] = 0x1a;
    assert!(candidate_snapshot(DeviceKind::DrinkReminders, &before).is_err());
}
#[test]
fn legacy_recovery_omits_new_preserved_fields_and_cannot_certify_a_new_drink_write() {
    let value = serde_json::json!({"identity":identity(),"request":request(DeviceCommand::Action { action: DeviceAction::FindRing }),"mutation_started":true});
    let old: DeviceRecovery = serde_json::from_value(value.clone()).unwrap();
    assert!(old.expected_readback.is_empty());
    assert_eq!(serde_json::to_value(old).unwrap(), value);
}

#[test]
fn legacy_profile_recovery_cannot_certify_unknown_tail_without_its_original_checkpoint() {
    let setting = DeviceSetting::Profile {
        sex: Sex::Male,
        age: 25,
        height_cm: 170,
        weight_kg: 60,
        is_24_hour: true,
        units: Units::Metric,
        systolic_mm_hg: 0,
        diastolic_mm_hg: 0,
        heart_rate_warning_bpm: 0,
    };
    let recovery = DeviceRecovery {
        identity: identity(),
        request: request(DeviceCommand::Update { setting }),
        mutation_started: true,
        expected_readback: Vec::new(),
    };
    let mut manager = synthetic(DeviceKind::Profile);
    assert_eq!(
        receipt(&manager.restore_unknown(recovery)).status,
        ControlStatus::Unknown
    );
    let read = manager.reconcile("operation-1", 1);
    assert_eq!(decode_core(&written(&read)).unwrap().payload[0], 1);
    manager.write_complete(true, 2);
    let result = notify(&mut manager, 0x0a, &[1, 0, 0, 0, 25, 170, 60], 3);
    assert_eq!(receipt(&result).status, ControlStatus::Unknown);
    assert_eq!(receipt(&result).reason, "preserved_fields_unverifiable");
    assert!(manager.has_unresolved_mutation());
}
