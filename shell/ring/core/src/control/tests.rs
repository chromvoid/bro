use super::*;

fn identity() -> RingIdentity {
    RingIdentity {
        model: "synthetic".into(),
        hardware: "synthetic-v1".into(),
        firmware: "synthetic-1".into(),
    }
}
fn pattern(opcode: u8, prefix: &[u8]) -> FramePattern {
    FramePattern {
        opcode,
        prefix: prefix.to_vec(),
        zero_tail: false,
    }
}
fn synthetic_hr() -> SettingsRecipe {
    SettingsRecipe {
        opcode: 0x16,
        read_payload: vec![1],
        read_response: pattern(0x16, &[1]),
        enabled_index: 1,
        enabled_on: 1,
        enabled_off: 2,
        interval_index: Some(2),
        write_template: vec![2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
        write_enabled_index: 1,
        write_interval_index: Some(2),
        preserved_fields: (3..14).map(|index| (index, index)).collect(),
        acknowledgement: FramePattern {
            opcode: 0x16,
            prefix: vec![2],
            zero_tail: true,
        },
        intervals: vec![5, 15, 30],
        autonomous_night: false,
    }
}
fn sample(metric: Metric, opcode: u8) -> ObservationRecipe {
    ObservationRecipe {
        metric,
        pattern: pattern(opcode, &[6, 0]),
        value_index: 2,
        width: 1,
        little_endian: true,
        scale: 1.0,
        unit: "bpm",
        min_raw: 0,
        max_raw: 255,
        min_valid_raw: 20,
        max_valid_raw: 250,
    }
}
pub(crate) fn manager() -> ControlManager {
    let mut manager = ControlManager::new(identity()).unwrap();
    // Test-only dependency injection. This profile cannot be selected by any
    // production identity or deserialized through C/JNI configuration.
    manager.profile.capabilities = CAPABILITY_KINDS
        .iter()
        .map(|kind| Capability {
            kind: *kind,
            status: CapabilityStatus::Verified,
            reason: "synthetic_evidence",
            readable: true,
            writable: true,
            evidence_ref: Some("synthetic-only"),
            supported_intervals_minutes: if *kind == CapabilityKind::HeartRateSchedule {
                vec![5, 15, 30]
            } else {
                vec![]
            },
            safe_stop_verified: false,
            autonomous_night: false,
        })
        .collect();
    manager
        .profile
        .settings
        .insert(SettingKind::HeartRate, synthetic_hr());
    for (kind, payload) in [
        (ActionKind::StartWorkout, vec![1, 42]),
        (ActionKind::StopWorkout, vec![4, 42]),
        (ActionKind::StartLiveHeartRate, vec![6, 1, 0]),
        (ActionKind::StopLiveHeartRate, vec![6, 4, 0]),
    ] {
        manager.profile.actions.insert(
            kind,
            ActionRecipe {
                opcode: 0x77,
                acknowledgement: pattern(0x77, &payload),
                payload,
                state_query: None,
                safe_stop: matches!(
                    kind,
                    ActionKind::StopWorkout | ActionKind::StopLiveHeartRate
                ),
            },
        );
    }
    manager.profile.live = Some(sample(Metric::HeartRate, 0x69));
    manager
        .profile
        .measurements
        .insert(Metric::HeartRate, manual_heart_rate_recipe());
    manager
}
fn request(id: &str, command: ControlCommand) -> ControlRequest {
    ControlRequest {
        operation_id: id.into(),
        command,
    }
}
fn set_hr(id: &str) -> ControlRequest {
    request(
        id,
        ControlCommand::SetSchedule {
            kind: SettingKind::HeartRate,
            enabled: true,
            interval_minutes: Some(5),
        },
    )
}
fn write(effects: &[ControlEffect]) -> CoreFrame {
    effects
        .iter()
        .find_map(|effect| match effect {
            ControlEffect::Write { bytes_hex, .. } => {
                Some(decode_core(&unhex(bytes_hex, 16).unwrap()).unwrap())
            }
            _ => None,
        })
        .unwrap()
}
fn no_write(effects: &[ControlEffect]) -> bool {
    !effects
        .iter()
        .any(|effect| matches!(effect, ControlEffect::Write { .. }))
}
fn receipt(effects: &[ControlEffect]) -> &ControlResult {
    effects
        .iter()
        .find_map(|effect| match effect {
            ControlEffect::Result(result) => Some(result),
            _ => None,
        })
        .unwrap()
}
fn notification(
    manager: &mut ControlManager,
    opcode: u8,
    payload: &[u8],
    now_ms: u64,
) -> Vec<ControlEffect> {
    manager
        .notification(&core_command(opcode, payload).unwrap(), now_ms)
        .1
}
fn read_and_start_write(manager: &mut ControlManager, id: &str) -> Vec<ControlEffect> {
    assert_eq!(write(&manager.request(set_hr(id), 0)).payload[0], 1);
    manager.write_complete(true, 1);
    notification(manager, 0x16, &[1, 1, 30, 0, 55, 170], 2)
}
fn after_ack(manager: &mut ControlManager) -> Vec<ControlEffect> {
    manager.write_complete(true, 3);
    notification(manager, 0x16, &[2], 4)
}

#[test]
fn read_modify_write_preserves_zero_start_interval_and_both_alerts() {
    let mut manager = manager();
    let effects = read_and_start_write(&mut manager, "hr");
    let frame = write(&effects);
    assert_eq!(frame.opcode, 0x16);
    assert_eq!(&frame.payload[..6], &[2, 1, 5, 0, 55, 170]);
    assert!(matches!(effects[0], ControlEffect::Checkpoint(_)));
    let readback = after_ack(&mut manager);
    assert_eq!(write(&readback).payload[0], 1);
    manager.write_complete(true, 5);
    let result = notification(&mut manager, 0x16, &[1, 1, 5, 0, 55, 170], 6);
    assert_eq!(receipt(&result).status, ControlStatus::Verified);
    assert_eq!(receipt(&result).reason, "settings_readback_verified");
    assert_eq!(
        receipt(&result).settings.as_ref().unwrap().interval_minutes,
        Some(5)
    );
    assert!(no_write(&manager.request(set_hr("hr"), 7)));
}

#[test]
fn ack_notification_and_att_callback_are_independent_and_not_settings_proof() {
    let mut manager = manager();
    manager.request(set_hr("ack"), 0);
    assert!(no_write(&notification(
        &mut manager,
        0x16,
        &[1, 1, 30, 188, 57, 174],
        1
    )));
    let mutate = manager.write_complete(true, 2);
    assert_eq!(&write(&mutate).payload[..6], &[2, 1, 5, 188, 57, 174]);
    assert!(no_write(&notification(&mut manager, 0x16, &[2], 3)));
    let readback = manager.write_complete(true, 4);
    assert_eq!(write(&readback).payload[0], 1);
    let timeout = manager.deadline(4 + CONTROL_TIMEOUT_MS);
    assert_eq!(receipt(&timeout).status, ControlStatus::Unknown);
    assert!(no_write(&timeout));
}

#[test]
fn mismatched_readback_remains_unknown_and_reconcile_only_reads() {
    let mut manager = manager();
    read_and_start_write(&mut manager, "mismatch");
    after_ack(&mut manager);
    manager.write_complete(true, 5);
    let mismatch = notification(&mut manager, 0x16, &[1, 1, 5, 0, 55, 0], 6);
    assert_eq!(receipt(&mismatch).status, ControlStatus::Unknown);
    assert_eq!(receipt(&mismatch).reason, "settings_readback_mismatch");
    assert!(no_write(&manager.request(set_hr("mismatch"), 7)));
    let reconcile = manager.reconcile("mismatch", 8);
    assert_eq!(write(&reconcile).payload[0], 1);
    manager.write_complete(true, 9);
    let verified = notification(&mut manager, 0x16, &[1, 1, 5, 0, 55, 170], 10);
    assert_eq!(receipt(&verified).status, ControlStatus::Verified);
    assert!(no_write(&verified));
}

#[test]
fn disconnect_and_write_failure_after_mutation_never_retry() {
    for disconnected in [false, true] {
        let mut manager = manager();
        read_and_start_write(&mut manager, "lost");
        let lost = if disconnected {
            manager.disconnected()
        } else {
            manager.write_complete(false, 3)
        };
        assert_eq!(receipt(&lost).status, ControlStatus::Unknown);
        assert!(no_write(&lost));
        let repeated = manager.request(set_hr("lost"), 4);
        assert_eq!(receipt(&repeated).status, ControlStatus::Unknown);
        assert!(no_write(&repeated));
        let next = manager.request(set_hr("different"), 5);
        assert_eq!(receipt(&next).reason, "unresolved_mutation");
        assert!(no_write(&next));
    }
}

#[test]
fn restored_checkpoint_cannot_replay_mutation_and_preserves_readback_proof() {
    let mut first = manager();
    let effects = read_and_start_write(&mut first, "durable");
    let checkpoint = effects
        .iter()
        .find_map(|effect| match effect {
            ControlEffect::Checkpoint(recovery) => Some(recovery.clone()),
            _ => None,
        })
        .unwrap();
    let mut restored = self::manager();
    let result = restored.restore_unknown(checkpoint);
    assert_eq!(receipt(&result).status, ControlStatus::Unknown);
    assert!(no_write(&result));
    assert!(no_write(&restored.request(set_hr("durable"), 1)));
    assert_eq!(write(&restored.reconcile("durable", 2)).payload[0], 1);
    restored.write_complete(true, 3);
    let verified = notification(&mut restored, 0x16, &[1, 1, 5, 0, 55, 170], 4);
    assert_eq!(receipt(&verified).status, ControlStatus::Verified);
}

#[test]
fn read_failure_is_rejected_without_unknown_mutation_and_interval_is_checked_before_read() {
    let mut manager = manager();
    let unsupported = manager.request(
        request(
            "interval",
            ControlCommand::SetSchedule {
                kind: SettingKind::HeartRate,
                enabled: true,
                interval_minutes: Some(1),
            },
        ),
        0,
    );
    assert_eq!(receipt(&unsupported).reason, "unsupported_interval");
    assert!(no_write(&unsupported));
    manager.request(set_hr("read"), 0);
    let disconnected = manager.disconnected();
    assert_eq!(receipt(&disconnected).status, ControlStatus::Rejected);
    assert!(!manager.has_unresolved_mutation());
}

#[test]
fn unrelated_error_opcode_invalid_checksum_and_late_packets_cannot_confirm() {
    let mut manager = manager();
    read_and_start_write(&mut manager, "frame");
    manager.write_complete(true, 3);
    assert!(
        !manager
            .notification(&core_command(0x96, &[2]).unwrap(), 4)
            .0
    );
    let mut invalid = core_command(0x16, &[2]).unwrap();
    invalid[15] = invalid[15].wrapping_add(1);
    assert!(no_write(&manager.notification(&invalid, 5).1));
    let timeout = notification(&mut manager, 0x16, &[2], 2 + CONTROL_TIMEOUT_MS);
    assert_eq!(receipt(&timeout).status, ControlStatus::Unknown);
    assert!(no_write(&timeout));
}

#[test]
fn already_matching_settings_are_a_verified_no_op() {
    let mut manager = manager();
    manager.request(set_hr("noop"), 0);
    manager.write_complete(true, 1);
    let raw = [1, 1, 5, 188, 55, 170, 231, 90, 19, 1, 2, 3, 4, 5];
    let result = notification(&mut manager, 0x16, &raw, 2);
    assert_eq!(receipt(&result).reason, "settings_already_applied");
    assert_eq!(receipt(&result).status, ControlStatus::Verified);
    assert!(no_write(&result));
    assert!(!manager.has_unresolved_mutation());
    assert!(
        !result
            .iter()
            .any(|effect| matches!(effect, ControlEffect::Checkpoint(_)))
    );
    assert_eq!(
        receipt(&result).settings.as_ref().unwrap().raw_payload_hex,
        hex(&raw)
    );
}

#[test]
fn unknown_sport_start_does_not_retry_but_explicit_proven_safe_stop_is_allowed() {
    let mut manager = manager();
    let start = request(
        "run",
        ControlCommand::StartWorkout {
            kind: WorkoutKind::Run,
        },
    );
    manager.request(start.clone(), 0);
    let timeout = manager.deadline(CONTROL_TIMEOUT_MS);
    assert_eq!(receipt(&timeout).status, ControlStatus::Unknown);
    assert!(no_write(&manager.request(start, CONTROL_TIMEOUT_MS + 1)));
    let stop = manager.request(
        request("stop", ControlCommand::StopWorkout),
        CONTROL_TIMEOUT_MS + 2,
    );
    assert_eq!(&write(&stop).payload[..2], &[4, 42]);
    manager.write_complete(true, CONTROL_TIMEOUT_MS + 3);
    let stopped = notification(&mut manager, 0x77, &[4, 42], CONTROL_TIMEOUT_MS + 4);
    assert_eq!(receipt(&stopped).status, ControlStatus::Verified);
    assert!(!manager.has_unresolved_mutation());
    assert_eq!(
        receipt(&manager.request(
            request(
                "run",
                ControlCommand::StartWorkout {
                    kind: WorkoutKind::Run
                }
            ),
            CONTROL_TIMEOUT_MS + 5
        ))
        .status,
        ControlStatus::Unknown
    );
    let next = manager.request(
        request(
            "next-run",
            ControlCommand::StartWorkout {
                kind: WorkoutKind::Run,
            },
        ),
        CONTROL_TIMEOUT_MS + 6,
    );
    assert_eq!(&write(&next).payload[..2], &[1, 42]);
}

#[test]
fn verified_safe_stop_does_not_clear_unrelated_settings_ambiguity() {
    let mut manager = manager();
    let expected = hex(&core_command(0x16, &[1, 1, 5, 0, 55, 170]).unwrap()[1..15]);
    manager.restore_unknown(ControlRecovery {
        identity: identity(),
        operation_id: "settings".into(),
        command: set_hr("settings").command,
        expected_settings_raw_hex: Some(expected),
    });
    manager.request(request("stop", ControlCommand::StopWorkout), 0);
    manager.write_complete(true, 1);
    let stopped = notification(&mut manager, 0x77, &[4, 42], 2);
    assert_eq!(receipt(&stopped).status, ControlStatus::Verified);
    assert!(manager.has_unresolved_mutation());
    let next = manager.request(
        request(
            "run",
            ControlCommand::StartWorkout {
                kind: WorkoutKind::Run,
            },
        ),
        3,
    );
    assert_eq!(receipt(&next).reason, "unresolved_mutation");
    assert!(no_write(&next));
}

#[test]
fn autonomous_night_requires_explicit_verified_recipe_evidence() {
    let mut manager = manager();
    assert!(
        manager
            .capabilities()
            .iter()
            .all(|cap| !cap.autonomous_night)
    );
    // Synthetic dependency evidence only, not an actual nightly wire recipe.
    for kind in [SettingKind::Spo2, SettingKind::Temperature] {
        let mut recipe = synthetic_hr();
        recipe.autonomous_night = true;
        manager.profile.settings.insert(kind, recipe);
    }
    for cap in manager.capabilities() {
        assert_eq!(
            cap.autonomous_night,
            matches!(
                cap.kind,
                CapabilityKind::Spo2Schedule | CapabilityKind::TemperatureSchedule
            )
        );
    }
    manager.profile.capabilities.iter_mut().for_each(|cap| {
        cap.status = CapabilityStatus::ProtocolUnverified;
        cap.autonomous_night = true;
    });
    assert!(
        manager
            .capabilities()
            .iter()
            .all(|cap| !cap.autonomous_night)
    );
}

#[test]
fn decoded_invalid_values_are_retained_without_accepting_malformed_frames() {
    let mut manager = manager();
    manager.request(request("live", ControlCommand::StartLiveHeartRate), 1);
    manager.write_complete(true, 2);
    notification(&mut manager, 0x77, &[6, 1, 0], 3);
    for (raw, quality) in [(0, "invalid"), (130, "valid"), (255, "invalid")] {
        let effects = notification(&mut manager, 0x69, &[6, 0, raw], 4);
        assert!(matches!(
            &effects[..],
            [ControlEffect::LiveHeartRate(Observation { value, sample_quality, .. })]
                if *value == f64::from(raw) && *sample_quality == quality
        ));
    }
    let mut corrupt = core_command(0x69, &[6, 0, 0]).unwrap();
    corrupt[15] ^= 1;
    assert!(!manager.notification(&corrupt, 5).0);
    assert!(
        !manager
            .notification(&core_command(0x69, &[6, 1, 0]).unwrap(), 6)
            .0
    );
    assert!(
        !manager
            .notification(&core_command(0xe9, &[6, 0, 0]).unwrap(), 7)
            .0
    );

    let mut recipe = sample(Metric::HeartRate, 0x69);
    recipe.max_raw = 250;
    let frame = decode_core(&core_command(0x69, &[6, 0, 255]).unwrap()).unwrap();
    assert!(recipe.valid());
    assert!(recipe.parse(&frame, 8).is_none());
}

#[test]
fn live_notifications_need_a_verified_subscription_and_keep_real_cadence() {
    let mut manager = manager();
    assert!(
        !manager
            .notification(&core_command(0x69, &[6, 0, 130]).unwrap(), 0)
            .0
    );
    manager.request(request("live", ControlCommand::StartLiveHeartRate), 1);
    manager.write_complete(true, 2);
    let start = notification(&mut manager, 0x77, &[6, 1, 0], 3);
    assert_eq!(receipt(&start).status, ControlStatus::Verified);
    for now in [1000, 1750, 4400] {
        let observation = notification(&mut manager, 0x69, &[6, 0, 130], now);
        assert!(
            matches!(&observation[..], [ControlEffect::LiveHeartRate(Observation { value, received_monotonic_ms, .. })] if *value == 130.0 && *received_monotonic_ms == now)
        );
    }
    assert!(
        !manager
            .notification(&core_command(0x69, &[6, 1, 130]).unwrap(), 5000)
            .0
    );
    manager.disconnected();
    assert!(
        !manager
            .notification(&core_command(0x69, &[6, 0, 130]).unwrap(), 6000)
            .0
    );
}

#[test]
fn manual_hr_waits_through_progress_and_finishes_only_with_final_stop_and_att() {
    let mut manager = manager();
    let start = manager.request(
        request(
            "spot",
            ControlCommand::MeasureOnce {
                metric: Metric::HeartRate,
            },
        ),
        100,
    );
    assert!(matches!(start[0], ControlEffect::Checkpoint(_)));
    assert_eq!(&write(&start).payload[..3], &[1, 0, 0]);
    let progress = manager.write_complete(true, 101);
    assert!(matches!(
        &progress[..],
        [ControlEffect::Progress(ControlProgress {
            phase: MeasurementPhase::Measuring,
            started_at_ms: 100,
            deadline_at_ms: 45_100,
            ..
        })]
    ));
    for at in [200, 8_500, 25_100] {
        let progress = notification(&mut manager, 0x69, &[1, 0, 0, 0, 0, 99, 1], at);
        assert!(matches!(&progress[..], [ControlEffect::Progress(_)]));
        assert!(manager.busy());
        assert!(manager.deadline(at).is_empty());
    }
    let stopping = notification(&mut manager, 0x69, &[1, 0, 88], 26_100);
    let stop = write(&stopping);
    assert_eq!(stop.opcode, 0x6a);
    assert_eq!(&stop.payload[..3], &[1, 0, 0]);
    assert!(matches!(
        &stopping[0],
        ControlEffect::Progress(ControlProgress {
            phase: MeasurementPhase::Stopping,
            stop_reason: Some(MeasurementStopReason::Result),
            ..
        })
    ));
    assert!(notification(&mut manager, 0x69, &[1, 0, 92], 26_101).is_empty());
    assert!(notification(&mut manager, 0x6a, &[1, 92], 26_102).is_empty());
    let received = manager.write_complete(true, 26_103);
    assert!(matches!(&received[0], ControlEffect::Observation {
        operation_id, observation: Observation { value, received_monotonic_ms: 26_102, .. }
    } if operation_id == "spot" && *value == 92.0));
    assert_eq!(receipt(&received).status, ControlStatus::Verified);
    assert_eq!(receipt(&received).reason, "measurement_received");
    assert!(!manager.has_unresolved_mutation());
    assert!(no_write(&manager.request(
        request(
            "spot",
            ControlCommand::MeasureOnce {
                metric: Metric::HeartRate,
            }
        ),
        26_104
    )));
    assert!(notification(&mut manager, 0x6a, &[1, 92], 26_105).is_empty());
}

fn manual_request(id: &str) -> ControlRequest {
    request(
        id,
        ControlCommand::MeasureOnce {
            metric: Metric::HeartRate,
        },
    )
}
fn started_manual() -> ControlManager {
    let mut manager = manager();
    manager.request(manual_request("manual"), 0);
    manager.write_complete(true, 1);
    manager
}
fn no_observation(effects: &[ControlEffect]) -> bool {
    !effects
        .iter()
        .any(|effect| matches!(effect, ControlEffect::Observation { .. }))
}

#[test]
fn provisional_result_before_start_att_requests_one_stop_after_att() {
    let mut manager = manager();
    manager.request(manual_request("manual"), 0);
    assert!(notification(&mut manager, 0x69, &[1, 0, 88], 1).is_empty());
    assert!(notification(&mut manager, 0x69, &[1, 0, 89], 2).is_empty());
    let stopping = manager.write_complete(true, 3);
    assert_eq!(write(&stopping).opcode, 0x6a);
    assert!(no_observation(&stopping));
    assert!(notification(&mut manager, 0x69, &[1, 0, 91], 4).is_empty());
    assert!(no_write(&manager.cancel("manual", 5)));
    assert!(manager.write_complete(true, 6).is_empty());
    let cancelled = notification(&mut manager, 0x6a, &[1, 93], 7);
    assert!(no_observation(&cancelled));
    assert_eq!(receipt(&cancelled).reason, "measurement_cancelled");
}

#[test]
fn manual_progress_and_delayed_deadline_never_extend_the_global_budget() {
    let mut manager = started_manual();
    notification(&mut manager, 0x69, &[1, 0, 0], 44_999);
    let stopping = manager.deadline(45_500);
    assert_eq!(write(&stopping).opcode, 0x6a);
    assert!(matches!(
        &stopping[0],
        ControlEffect::Progress(ControlProgress {
            started_at_ms: 0,
            deadline_at_ms: 53_000,
            stop_reason: Some(MeasurementStopReason::Timeout),
            ..
        })
    ));
    manager.write_complete(true, 45_501);
    let expired = manager.deadline(53_000);
    assert_eq!(receipt(&expired).status, ControlStatus::Unknown);
    assert_eq!(receipt(&expired).reason, "measurement_stop_unconfirmed");
    assert!(no_write(&expired));
    assert!(manager.has_unresolved_mutation());
    assert!(no_write(&manager.deadline(90_000)));
}

#[test]
fn missing_start_att_or_late_resume_never_send_an_unfenced_stop() {
    let mut pending_att = manager();
    pending_att.request(manual_request("manual"), 0);
    let expired = pending_att.deadline(45_000);
    assert_eq!(receipt(&expired).status, ControlStatus::Unknown);
    assert!(no_write(&expired));
    let mut suspended = started_manual();
    let expired = suspended.deadline(60_000);
    assert_eq!(receipt(&expired).reason, "measurement_stop_unconfirmed");
    assert!(no_write(&expired));
}

#[test]
fn manual_cancel_is_current_owned_operation_and_waits_for_start_att() {
    let mut manager = manager();
    manager.request(manual_request("manual"), 100);
    assert!(no_write(&manager.cancel("other", 101)));
    assert!(no_write(&manager.cancel("manual", 102)));
    let stopping = manager.write_complete(true, 103);
    assert_eq!(write(&stopping).opcode, 0x6a);
    assert!(matches!(
        &stopping[0],
        ControlEffect::Progress(ControlProgress {
            stop_reason: Some(MeasurementStopReason::Cancelled),
            ..
        })
    ));
    assert!(no_write(&manager.cancel("manual", 104)));
    manager.write_complete(true, 105);
    // There is no qualification for a zero-valued cancelled stop response.
    assert!(notification(&mut manager, 0x6a, &[1, 0], 106).is_empty());
    let expired = manager.deadline(8_103);
    assert_eq!(receipt(&expired).status, ControlStatus::Unknown);
    assert_eq!(receipt(&expired).command, manual_request("manual").command);
    assert!(no_observation(&expired));
    assert!(no_write(&manager.cancel("manual", 8_104)));
}

#[test]
fn manual_error_and_timeout_can_confirm_stop_without_recording_a_measurement() {
    for reason in [
        MeasurementStopReason::DeviceError,
        MeasurementStopReason::Timeout,
    ] {
        let mut manager = started_manual();
        let stopping = if reason == MeasurementStopReason::DeviceError {
            notification(&mut manager, 0x69, &[1, 1, 0], 20_000)
        } else {
            manager.deadline(45_000)
        };
        assert_eq!(write(&stopping).opcode, 0x6a);
        let at = if reason == MeasurementStopReason::DeviceError {
            20_001
        } else {
            45_001
        };
        manager.write_complete(true, at);
        let stopped = notification(&mut manager, 0x6a, &[1, 88], at + 1);
        assert!(no_observation(&stopped));
        assert_eq!(receipt(&stopped).status, ControlStatus::Verified);
        assert_eq!(
            receipt(&stopped).reason,
            if reason == MeasurementStopReason::DeviceError {
                "measurement_device_error"
            } else {
                "measurement_timeout"
            }
        );
    }
}

#[test]
fn manual_stale_wrong_type_invalid_and_generic_sdk_frames_do_not_finish() {
    let mut manager = manager();
    manager.request(manual_request("manual"), 100);
    manager.write_complete(true, 101);
    assert!(no_write(&notification(&mut manager, 0x69, &[1, 0, 88], 99)));
    for payload in [&[3, 0, 88][..], &[10, 0, 88][..]] {
        assert!(
            !manager
                .notification(&core_command(0x69, payload).unwrap(), 102)
                .0
        );
    }
    for value in [0, 29, 221, 255] {
        assert!(no_write(&notification(
            &mut manager,
            0x69,
            &[1, 0, value],
            103
        )));
    }
    let stopping = notification(&mut manager, 0x69, &[1, 0, 88], 104);
    assert_eq!(write(&stopping).opcode, 0x6a);
    manager.write_complete(true, 105);
    for payload in [
        &[1, 0, 88][..],
        &[3, 88][..],
        &[1, 88, 1][..],
        &[1, 0][..],
        &[1, 221][..],
    ] {
        assert!(notification(&mut manager, 0x6a, payload, 106).is_empty());
        assert!(manager.busy());
    }
    let mut bad_crc = core_command(0x6a, &[1, 88]).unwrap();
    bad_crc[15] ^= 1;
    assert!(no_observation(&manager.notification(&bad_crc, 107).1));
    assert!(manager.busy());
    let final_result = notification(&mut manager, 0x6a, &[1, 88], 108);
    assert_eq!(receipt(&final_result).status, ControlStatus::Verified);
}

#[test]
fn unknown_manual_result_survives_disconnect_replay_restore_and_reconcile() {
    let mut manager = manager();
    let start = manager.request(manual_request("manual"), 0);
    let recovery = start
        .iter()
        .find_map(|effect| match effect {
            ControlEffect::Checkpoint(recovery) => Some(recovery.clone()),
            _ => None,
        })
        .unwrap();
    manager.write_complete(true, 1);
    let disconnected = manager.disconnected();
    assert_eq!(receipt(&disconnected).status, ControlStatus::Unknown);
    assert!(manager.has_unresolved_mutation());
    assert!(no_write(&manager.request(manual_request("manual"), 2)));
    assert_eq!(
        receipt(&manager.request(manual_request("another"), 3)).reason,
        "unresolved_mutation"
    );
    assert!(no_write(&manager.reconcile("manual", 4)));
    let mut restored = self::manager();
    assert_eq!(
        receipt(&restored.restore_unknown(recovery)).status,
        ControlStatus::Unknown
    );
    assert!(restored.has_unresolved_mutation());
    assert!(no_write(&restored.cancel("manual", 5)));
    assert!(no_write(&restored.reconcile("manual", 6)));
    assert!(no_write(&restored.request(manual_request("manual"), 7)));
}

#[test]
fn failed_stop_write_retains_the_original_manual_intent_as_unknown() {
    let mut manager = started_manual();
    notification(&mut manager, 0x69, &[1, 0, 88], 20_000);
    let failed = manager.write_complete(false, 20_001);
    assert_eq!(receipt(&failed).status, ControlStatus::Unknown);
    assert_eq!(receipt(&failed).operation_id, "manual");
    assert_eq!(receipt(&failed).command, manual_request("manual").command);
    assert!(manager.has_unresolved_mutation());
    assert!(no_observation(&failed));
}

#[test]
fn stress_schedule_candidate_is_separate_from_hrv_and_has_no_invented_interval() {
    let mut control = manager();
    control
        .profile
        .settings
        .insert(SettingKind::Stress, stress_schedule_candidate());
    let command = ControlCommand::SetSchedule {
        kind: SettingKind::Stress,
        enabled: false,
        interval_minutes: None,
    };
    assert_eq!(
        write(&control.request(request("stress-off", command), 0)).opcode,
        0x36
    );
    control.write_complete(true, 1);
    assert!(
        !control
            .notification(&core_command(0x38, &[1, 0]).unwrap(), 2)
            .0
    );
    let effects = notification(&mut control, 0x36, &[1, 1], 3);
    assert_eq!(&write(&effects).payload[..2], &[2, 0]);
    control.write_complete(true, 4);
    let readback = notification(&mut control, 0x36, &[2], 5);
    assert_eq!(write(&readback).payload[0], 1);
    control.write_complete(true, 6);
    let confirmed = notification(&mut control, 0x36, &[1, 0], 7);
    let setting = receipt(&confirmed).settings.as_ref().unwrap();
    assert_eq!(setting.kind, SettingKind::Stress);
    assert!(!setting.enabled);
    assert_eq!(setting.interval_minutes, None);
    assert_eq!(setting.heart_rate_alerts, None);
}

#[test]
fn manual_stress_candidate_retains_its_format_without_qualifying_a_stop_cycle() {
    let mut control = manager();
    control
        .profile
        .measurements
        .insert(Metric::Stress, stress_measurement_candidate());
    let command = ControlCommand::MeasureOnce {
        metric: Metric::Stress,
    };
    let candidate = stress_measurement_candidate();
    assert_eq!(candidate.opcode, 0x69);
    assert_eq!(candidate.payload, [8, 0x25, 0]);
    assert_eq!(candidate.timeout_ms, 45_000);
    assert!(candidate.stop.is_none());
    let rejected = control.request(request("stress", command), 0);
    assert!(no_write(&rejected));
    assert_eq!(receipt(&rejected).reason, "missing_verified_stop_recipe");
    for payload in [&[10, 0, 200][..], &[12, 0, 0, 0, 200][..], &[8, 1, 200][..]] {
        let frame = decode_core(&core_command(0x69, payload).unwrap()).unwrap();
        assert!(candidate.result.parse(&frame, 1).is_none());
    }
    let frame = decode_core(&core_command(0x69, &[8, 0, 200]).unwrap()).unwrap();
    let observation = candidate.result.parse(&frame, 2).unwrap();
    assert!(matches!(
        observation,
        Observation {
            metric: Metric::Stress, value, unit: "index", sample_quality: "valid", ..
        } if value == 200.0
    ));
    let frame = decode_core(&core_command(0x69, &[8, 0, 0]).unwrap()).unwrap();
    assert!(matches!(
        candidate.result.parse(&frame, 3).unwrap(),
        Observation {
            metric: Metric::Stress, value, unit: "index", sample_quality: "invalid", ..
        } if value == 0.0
    ));
}

#[test]
fn source_hr_candidate_preserves_every_field_except_requested_enable_and_interval() {
    let identity = RingIdentity {
        model: "qring-r20".into(),
        hardware: "RF20_V2.1".into(),
        firmware: "RF20_2.10.07_260131".into(),
    };
    let profile = production_profile(&identity);
    let recipe = profile.settings.get(&SettingKind::HeartRate).unwrap();
    for interval in [0, 17] {
        for start_interval in [0, 188] {
            let raw = [
                1,
                1,
                interval,
                start_interval,
                55,
                170,
                231,
                90,
                19,
                1,
                2,
                3,
                4,
                5,
            ];
            let (payload, expected) = recipe.mutation(raw, false, None).unwrap();
            assert_eq!(payload.len(), 14);
            assert_eq!(&payload[..3], &[2, 2, interval]);
            assert_eq!(&payload[3..], &raw[3..]);
            assert_eq!(expected[0], 1);
            assert_eq!(expected[1], 2);
            assert_eq!(expected[2], interval);
            assert_eq!(&expected[3..], &raw[3..]);
        }
    }
    assert!(recipe.intervals.is_empty());
    assert_eq!(
        recipe.mutation([0; 14], true, Some(5)),
        Err("unsupported_interval")
    );
}

#[test]
fn schedule_readback_preserves_all_fresh_bytes_and_mismatch_recovery_only_reads() {
    let original = [1, 1, 30, 188, 55, 170, 231, 90, 19, 1, 2, 3, 4, 5];
    let expected = [1, 1, 5, 188, 55, 170, 231, 90, 19, 1, 2, 3, 4, 5];
    for changed_index in 1..14 {
        let mut control = manager();
        control.request(set_hr("opaque"), 0);
        control.write_complete(true, 1);
        let mutation = notification(&mut control, 0x16, &original, 2);
        assert_eq!(
            write(&mutation).payload,
            [2, 1, 5, 188, 55, 170, 231, 90, 19, 1, 2, 3, 4, 5]
        );
        let checkpoint = mutation
            .iter()
            .find_map(|effect| match effect {
                ControlEffect::Checkpoint(recovery) => Some(recovery.clone()),
                _ => None,
            })
            .unwrap();
        assert_eq!(checkpoint.expected_settings_raw_hex, Some(hex(&expected)));
        after_ack(&mut control);
        control.write_complete(true, 5);
        let mut changed = expected;
        changed[changed_index] = if changed_index == 1 {
            2 // A valid opposite sampling state, not a malformed read.
        } else {
            changed[changed_index].wrapping_add(1)
        };
        let mismatch = notification(&mut control, 0x16, &changed, 6);
        assert_eq!(receipt(&mismatch).status, ControlStatus::Unknown);
        assert_eq!(receipt(&mismatch).reason, "settings_readback_mismatch");
        assert!(control.has_unresolved_mutation());
        assert!(no_write(&control.request(set_hr("opaque"), 7)));

        // Recovery keeps the complete expectation and can only query it.
        let mut restored = manager();
        let recovery = restored.restore_unknown(checkpoint);
        assert_eq!(receipt(&recovery).status, ControlStatus::Unknown);
        assert!(no_write(&recovery));
        assert!(no_write(&restored.request(set_hr("opaque"), 8)));
        let readback = restored.reconcile("opaque", 9);
        let read = write(&readback);
        assert_eq!(read.opcode, 0x16);
        assert_eq!(read.payload, [1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        restored.write_complete(true, 10);
        let still_changed = notification(&mut restored, 0x16, &changed, 11);
        assert_eq!(receipt(&still_changed).status, ControlStatus::Unknown);
        assert!(no_write(&still_changed));
        assert!(restored.has_unresolved_mutation());
        restored.reconcile("opaque", 12);
        restored.write_complete(true, 13);
        let verified = notification(&mut restored, 0x16, &expected, 14);
        assert_eq!(receipt(&verified).status, ControlStatus::Verified);
        assert_eq!(receipt(&verified).reason, "settings_readback_verified");
        assert!(no_write(&verified));
        assert!(!restored.has_unresolved_mutation());
    }
}

#[test]
fn source_hr_ack_matches_only_the_observed_pattern_without_qualifying_any_write() {
    let known = RingIdentity {
        model: "qring-r20".into(),
        hardware: "RF20_V2.1".into(),
        firmware: "RF20_2.10.07_260131".into(),
    };
    let profile = production_profile(&known);
    let recipe = profile.settings.get(&SettingKind::HeartRate).unwrap();
    let capability = profile
        .capabilities
        .iter()
        .find(|capability| capability.kind == CapabilityKind::HeartRateSchedule)
        .unwrap();
    assert_eq!(capability.status, CapabilityStatus::ProtocolUnverified);
    assert!(capability.readable);
    assert!(!capability.writable);
    assert!(capability.supported_intervals_minutes.is_empty());
    for (payload, matches) in [
        (&[2, 1][..], true),
        (&[2][..], false),
        (&[2, 2][..], false),
        (&[2, 1, 1][..], false),
        (&[1, 1][..], false),
    ] {
        let frame = decode_core(&core_command(0x16, payload).unwrap()).unwrap();
        assert_eq!(recipe.acknowledgement.matches(&frame), matches);
    }
    // Test-only admission exercises the observed ACK without attributing a
    // meaning to byte 1 or allowing it to replace exact fresh readback.
    let mut admitted = manager();
    let mut candidate = recipe.clone();
    candidate.intervals = vec![5];
    admitted
        .profile
        .settings
        .insert(SettingKind::HeartRate, candidate);
    read_and_start_write(&mut admitted, "observed-ack");
    assert!(no_write(&notification(&mut admitted, 0x16, &[2], 3)));
    let ack = notification(&mut admitted, 0x16, &[2, 1], 4);
    assert!(no_write(&ack));
    assert!(
        ack.iter()
            .all(|effect| !matches!(effect, ControlEffect::Result(_)))
    );
    let readback = admitted.write_complete(true, 5);
    assert_eq!(write(&readback).payload[0], 1);
    assert!(
        readback
            .iter()
            .all(|effect| !matches!(effect, ControlEffect::Result(_)))
    );
    admitted.write_complete(true, 6);
    let mismatch = notification(&mut admitted, 0x16, &[1, 1, 5, 0, 55, 170, 0, 1], 7);
    assert_eq!(receipt(&mismatch).status, ControlStatus::Unknown);
    assert_eq!(receipt(&mismatch).reason, "settings_readback_mismatch");
    assert!(admitted.has_unresolved_mutation());

    let mut control = ControlManager::new(known).unwrap();
    for (index, (enabled, interval_minutes)) in [(true, Some(5)), (false, None), (true, None)]
        .into_iter()
        .enumerate()
    {
        let rejected = control.request(
            request(
                &format!("closed-{index}"),
                ControlCommand::SetSchedule {
                    kind: SettingKind::HeartRate,
                    enabled,
                    interval_minutes,
                },
            ),
            0,
        );
        assert_eq!(receipt(&rejected).reason, "write_recipe_unverified");
        assert_eq!(receipt(&rejected).status, ControlStatus::Rejected);
        assert!(no_write(&rejected));
    }
}

#[test]
fn hr_alert_candidate_preserves_full_fresh_settings_and_requires_exact_readback() {
    let mut control = manager();
    let command = ControlCommand::SetHeartRateAlerts {
        low_bpm: 55,
        high_bpm: 170,
    };
    control.request(request("alerts", command), 0);
    control.write_complete(true, 1);
    let original = [1, 2, 0, 0, 0, 0, 231, 90, 0, 1, 2, 3, 4, 5];
    let effects = notification(&mut control, 0x16, &original, 2);
    let frame = write(&effects);
    assert_eq!(
        frame.payload,
        [2, 2, 0, 0, 55, 170, 231, 90, 0, 1, 2, 3, 4, 5]
    );
    control.write_complete(true, 3);
    notification(&mut control, 0x16, &[2], 4);
    control.write_complete(true, 5);
    // Even an unresolved tail field must not be changed by an alert operation.
    let changed = [1, 2, 0, 0, 55, 170, 231, 91, 0, 1, 2, 3, 4, 5];
    let effects = notification(&mut control, 0x16, &changed, 6);
    assert_eq!(receipt(&effects).status, ControlStatus::Unknown);
    assert!(control.has_unresolved_mutation());
    let settings = receipt(&effects).settings.as_ref().unwrap();
    let alerts = settings.heart_rate_alerts.as_ref().unwrap();
    assert_eq!(
        (alerts.low_bpm, alerts.high_bpm, alerts.main_switch_raw),
        (55, 170, 231)
    );
    assert_eq!(alerts.qualification, "source_only");
    assert!(!settings.enabled);
    assert_eq!(settings.interval_minutes, None);
}

#[test]
fn malformed_trusted_recipe_is_rejected_before_any_write() {
    let mut manager = manager();
    manager
        .profile
        .settings
        .get_mut(&SettingKind::HeartRate)
        .unwrap()
        .enabled_index = 99;
    let rejected = manager.request(set_hr("invalid"), 0);
    assert_eq!(receipt(&rejected).reason, "invalid_verified_recipe");
    assert!(no_write(&rejected));
}

#[test]
fn all_manual_sport_operations_require_a_per_mode_physical_recipe() {
    let known = RingIdentity {
        model: "qring-r20".into(),
        hardware: "RF20_V2.1".into(),
        firmware: "RF20_2.10.07_260131".into(),
    };
    let mut control = ControlManager::new(known).unwrap();
    assert_eq!(control.sport_capabilities().len(), 191);
    assert!(control.sport_capabilities().iter().all(|cap| !cap.start
        && !cap.pause
        && !cap.resume
        && !cap.stop
        && !cap.safe_stop_verified));
    for (index, command) in [
        ControlCommand::StartSport {
            plan: SportPlan { requested_id: 7 },
        },
        ControlCommand::PauseSport {
            plan: SportPlan { requested_id: 7 },
        },
        ControlCommand::ResumeSport {
            plan: SportPlan { requested_id: 7 },
        },
        ControlCommand::StopSport {
            plan: SportPlan { requested_id: 7 },
        },
    ]
    .into_iter()
    .enumerate()
    {
        let effects = control.request(request(&format!("manual-{index}"), command), 0);
        assert!(no_write(&effects));
        assert_eq!(receipt(&effects).status, ControlStatus::Rejected);
        assert_eq!(
            receipt(&effects).sport.as_ref().unwrap().actual_wire_mode,
            None
        );
    }
}

#[test]
fn manual_sport_pause_resume_keep_mode_and_unknown_start_is_never_replayed() {
    let mut control = manager();
    let plan = SportPlan { requested_id: 7 };
    for status in [
        SportStatus::Start,
        SportStatus::Pause,
        SportStatus::Resume,
        SportStatus::Stop,
    ] {
        let payload = vec![status.wire(), 7];
        control.profile.sports.insert(
            (7, status),
            SportRecipe {
                actual_wire_mode: 7,
                evidence_ref: "synthetic-physical-sport",
                action: ActionRecipe {
                    opcode: 0x77,
                    acknowledgement: pattern(0x77, &payload),
                    payload,
                    state_query: None,
                    safe_stop: status == SportStatus::Stop,
                },
            },
        );
    }
    for (index, command) in [
        ControlCommand::StartSport { plan },
        ControlCommand::PauseSport { plan },
        ControlCommand::ResumeSport { plan },
    ]
    .into_iter()
    .enumerate()
    {
        let effects = control.request(
            request(&format!("phase-{index}"), command),
            index as u64 * 4,
        );
        assert_eq!(write(&effects).payload[..2], [index as u8 + 1, 7]);
        assert!(
            control
                .write_complete(true, index as u64 * 4 + 1)
                .is_empty()
        );
        let completed = notification(
            &mut control,
            0x77,
            &[index as u8 + 1, 7],
            index as u64 * 4 + 2,
        );
        assert_eq!(
            receipt(&completed).sport.as_ref().unwrap().actual_wire_mode,
            Some(7)
        );
    }
    control.request(
        request("lost-start", ControlCommand::StartSport { plan }),
        20,
    );
    let failed = control.write_complete(false, 21);
    assert_eq!(receipt(&failed).status, ControlStatus::Unknown);
    assert_eq!(
        receipt(&failed).sport.as_ref().unwrap().actual_wire_mode,
        None
    );
    assert!(no_write(&control.request(
        request("lost-start", ControlCommand::StartSport { plan }),
        22
    )));
    let stop = control.request(
        request("explicit-stop", ControlCommand::StopSport { plan }),
        23,
    );
    assert_eq!(write(&stop).payload[..2], [4, 7]);
    control.write_complete(true, 24);
    notification(&mut control, 0x77, &[4, 7], 25);
    assert!(!control.has_unresolved_mutation());
}
