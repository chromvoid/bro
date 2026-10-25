use qring_r20::{
    codec::{bulk_command, core_command, hex},
    session::{Config, Effect, Input, QUERY_TIMEOUT_MS, Session},
};
use serde_json::json;

fn config(checkpoint: Vec<&str>) -> Config {
    serde_json::from_value(json!({"generation":1,"timezone":"Europe/Moscow","local_date":"2026-10-05",
        "clock":{"year":2026,"month":10,"day":5,"hour":12,"minute":30,"second":0,"language":10},
        "android":{"sdk":36,"model":"Synthetic-long-model"},"history_days":1,"completed_queries":checkpoint})).unwrap()
}
fn event(value: serde_json::Value) -> Input {
    serde_json::from_value(value).unwrap()
}
fn dispatch(session: &mut Session, value: serde_json::Value) -> Vec<Effect> {
    session.dispatch(event(value), 100)
}
fn startup(session: &mut Session) -> Vec<Effect> {
    dispatch(session, json!({"type":"connected","generation":1}));
    let reads = dispatch(session, json!({"type":"sync","generation":1}));
    assert_eq!(
        reads
            .iter()
            .filter(|e| matches!(e, Effect::ReadVersion { .. }))
            .count(),
        2
    );
    dispatch(
        session,
        json!({"type":"version","generation":1,"kind":"firmware","value":"Synthetic"}),
    );
    dispatch(
        session,
        json!({"type":"version","generation":1,"kind":"hardware","value":"Synthetic"}),
    )
}
fn reply_core(session: &mut Session, op: u8, payload: &[u8]) -> Vec<Effect> {
    dispatch(
        session,
        json!({"type":"write_complete","generation":1,"success":true}),
    );
    dispatch(
        session,
        json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&core_command(op,payload).unwrap())}),
    )
}
fn reply_bulk(session: &mut Session, op: u8, payload: &[u8]) -> Vec<Effect> {
    dispatch(
        session,
        json!({"type":"write_complete","generation":1,"success":true}),
    );
    dispatch(
        session,
        json!({"type":"notification","generation":1,"channel":"bulk","bytes_hex":hex(&bulk_command(op,payload).unwrap())}),
    )
}
fn written(effects: &[Effect]) -> (&str, &str) {
    effects
        .iter()
        .find_map(|e| {
            if let Effect::Write {
                query_id,
                bytes_hex,
                ..
            } = e
            {
                Some((query_id.as_str(), bytes_hex.as_str()))
            } else {
                None
            }
        })
        .unwrap()
}
fn progress_json(effects: &[Effect], query: &str, expected_status: &str) -> serde_json::Value {
    let effect = effects
        .iter()
        .find(|effect| match effect {
            Effect::Progress {
                query_id, status, ..
            } => query_id == query && *status == expected_status,
            _ => false,
        })
        .unwrap();
    serde_json::to_value(effect).unwrap()
}
fn has_declaration(effects: &[Effect]) -> bool {
    effects
        .iter()
        .any(|effect| matches!(effect, Effect::DeclaredCapabilities { .. }))
}
fn initialized(session: &mut Session) -> Vec<Effect> {
    startup(session);
    reply_core(session, 1, &[]);
    reply_core(session, 4, &[]);
    reply_core(session, 3, &[70, 0]);
    reply_core(session, 0x3c, &[])
}

fn scalar_config() -> Config {
    config(vec![
        "steps:0:0",
        "sleep:255:0",
        "heart_rate:0:done",
        "spo2:0:done",
        "temperature:0:done",
        "spo2_legacy:255:0",
        "temperature_legacy:0:0",
    ])
}

fn sport_config() -> Config {
    let mut config = scalar_config();
    config
        .completed_queries
        .extend(["hrv:0:0".into(), "stress:0:0".into()]);
    config.collect_workouts = true;
    config.workout_cursor = 100;
    config
}

fn sport_frames() -> Vec<[u8; 14]> {
    let mut body = [0; 39];
    for (index, value) in [200u32, 600, 17, 321, 2345, 999, 0].iter().enumerate() {
        body[index * 4..index * 4 + 4].copy_from_slice(&value.to_le_bytes());
    }
    let mut header = [0; 14];
    header[1] = 4;
    let mut result = vec![header];
    for (index, chunk) in body.chunks(13).enumerate() {
        let mut frame = [0; 14];
        frame[0] = (index + 1) as u8;
        frame[1..].copy_from_slice(chunk);
        result.push(frame);
    }
    result
}

#[test]
fn sport_history_request_cursor_and_full_stream_are_committed_only_after_att_ack() {
    let mut session = Session::new(sport_config()).unwrap();
    let initial = initialized(&mut session);
    assert_eq!(written(&initial).0, "workouts:100:0");
    let wire = qring_r20::codec::unhex(written(&initial).1, 16).unwrap();
    assert_eq!(wire[0], 0x13);
    assert_eq!(&wire[1..5], &100u32.to_le_bytes());
    for frame in sport_frames()
        .into_iter()
        .chain([[0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]])
    {
        let effects = dispatch(
            &mut session,
            json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&core_command(0x13,&frame).unwrap())}),
        );
        assert!(
            !effects
                .iter()
                .any(|effect| matches!(effect, Effect::Observation { .. }))
        );
    }
    let accepted = dispatch(
        &mut session,
        json!({"type":"write_complete","generation":1,"success":true}),
    );
    let raw = accepted
        .iter()
        .find_map(|effect| match effect {
            Effect::Observation { observation } => Some(observation),
            _ => None,
        })
        .unwrap();
    assert_eq!(raw["metric"], "workouts");
    assert!(raw["unit"].is_null());
    assert!(raw["value"].is_null());
    assert_eq!(raw["validation_status"], "source_only");
    assert_eq!(raw["details"]["raw_start_time"], 200);
    let checkpoint = accepted
        .iter()
        .find_map(|effect| match effect {
            Effect::Progress {
                checkpoint,
                status: "complete",
                ..
            } => Some(checkpoint),
            _ => None,
        })
        .unwrap();
    assert_eq!(checkpoint.workout_cursor, 200);
    assert!(
        progress_json(&accepted, "workouts:100:0", "complete")
            .get("failure_code")
            .is_none()
    );
}

#[test]
fn sport_empty_and_silence_are_distinct_and_neither_advances_the_cursor() {
    let mut empty = Session::new(sport_config()).unwrap();
    initialized(&mut empty);
    let effects = reply_core(&mut empty, 0x13, &[0xff]);
    assert!(
        effects
            .iter()
            .any(|effect| matches!(effect, Effect::Progress {
        status: "empty", validation_status: "source_only", checkpoint, ..
    } if checkpoint.workout_cursor == 100))
    );
    assert!(
        progress_json(&effects, "workouts:100:0", "empty")
            .get("failure_code")
            .is_none()
    );
    let mut silent = Session::new(sport_config()).unwrap();
    initialized(&mut silent);
    let effects = silent.dispatch(
        event(json!({"type":"deadline","generation":1})),
        100 + qring_r20::session::WORKOUT_HISTORY_TIMEOUT_MS,
    );
    assert!(
        effects
            .iter()
            .any(|effect| matches!(effect, Effect::Progress {
        status: "failed", checkpoint, ..
    } if checkpoint.workout_cursor == 100))
    );
    assert!(
        !effects
            .iter()
            .any(|effect| matches!(effect, Effect::Observation { .. }))
    );
    assert!(
        !effects
            .iter()
            .any(|effect| matches!(effect, Effect::Write { .. }))
    );
}

#[test]
fn unmatched_high_bit_workout_reply_keeps_cursor_and_reports_only_final_timeout() {
    let mut session = Session::new(sport_config()).unwrap();
    let initial = initialized(&mut session);
    let pending = progress_json(&initial, "workouts:100:0", "pending");
    assert!(pending.get("failure_code").is_none());
    let unexpected = reply_core(&mut session, 0x93, &[1]);
    assert!(unexpected.iter().any(|effect| matches!(
        effect,
        Effect::Diagnostic {
            code: "unexpected_opcode",
            ..
        }
    )));
    assert!(unexpected.iter().all(|effect| !matches!(
        effect,
        Effect::Observation { .. } | Effect::Progress { .. } | Effect::Write { .. }
    )));
    let failed = session.dispatch(
        event(json!({"type":"deadline","generation":1})),
        100 + qring_r20::session::WORKOUT_HISTORY_TIMEOUT_MS,
    );
    let progress = progress_json(&failed, "workouts:100:0", "failed");
    assert_eq!(progress["metric"], "workouts");
    assert_eq!(progress["failure_code"], "response_timeout");
    assert_eq!(progress["validation_status"], "source_only");
    assert_eq!(progress["checkpoint"]["workout_cursor"], 100);
    let checkpoint = progress["checkpoint"]["completed_queries"]
        .as_array()
        .unwrap();
    assert!(checkpoint.contains(&json!("failed:workouts:100:0")));
    assert!(!checkpoint.contains(&json!("workouts:100:0")));
    assert!(
        failed
            .iter()
            .all(|effect| !matches!(effect, Effect::Observation { .. } | Effect::Write { .. }))
    );
}

#[test]
fn exhausted_write_failure_keeps_its_query_cause_and_does_not_leak_to_later_success() {
    let mut session = Session::new(config(vec![])).unwrap();
    initialized(&mut session);
    let retried = session.dispatch(
        event(json!({"type":"deadline","generation":1})),
        100 + QUERY_TIMEOUT_MS,
    );
    assert_eq!(written(&retried).0, "steps:0:0");
    assert!(retried.iter().any(|effect| matches!(
        effect,
        Effect::Diagnostic {
            code: "response_timeout",
            ..
        }
    )));
    let retried = session.dispatch(
        event(json!({"type":"write_complete","generation":1,"success":false})),
        101 + QUERY_TIMEOUT_MS,
    );
    assert_eq!(written(&retried).0, "steps:0:0");
    assert!(
        !retried
            .iter()
            .any(|effect| matches!(effect, Effect::Progress { .. }))
    );
    let failed = session.dispatch(
        event(json!({"type":"write_complete","generation":1,"success":false})),
        102 + QUERY_TIMEOUT_MS,
    );
    let progress = progress_json(&failed, "steps:0:0", "failed");
    assert_eq!(progress["metric"], "steps");
    assert_eq!(progress["failure_code"], "write_failed");
    assert!(
        progress["checkpoint"]["completed_queries"]
            .as_array()
            .unwrap()
            .contains(&json!("failed:steps:0:0"))
    );
    assert_eq!(written(&failed).0, "sleep:255:0");
    assert!(
        progress_json(&failed, "sleep:255:0", "pending")
            .get("failure_code")
            .is_none()
    );
    let empty = reply_bulk(&mut session, 0x27, &[0]);
    assert!(
        progress_json(&empty, "sleep:255:0", "empty")
            .get("failure_code")
            .is_none()
    );
}

#[test]
fn sport_checksum_corruption_and_incomplete_stream_cannot_publish_or_move_cursor() {
    let mut session = Session::new(sport_config()).unwrap();
    initialized(&mut session);
    let mut wire = core_command(0x13, &sport_frames()[0]).unwrap();
    wire[15] ^= 1;
    let effects = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&wire)}),
    );
    assert!(effects.iter().any(|effect| matches!(
        effect,
        Effect::Diagnostic {
            code: "invalid_core_frame",
            ..
        }
    )));
    reply_core(&mut session, 0x13, &sport_frames()[0]);
    let interrupted = reply_core(&mut session, 0x13, &[0xff]);
    assert!(interrupted.iter().any(|effect| matches!(
        effect,
        Effect::Diagnostic {
            code: "invalid_workout_history",
            ..
        }
    )));
    assert!(
        !interrupted
            .iter()
            .any(|effect| matches!(effect, Effect::Observation { .. }))
    );
}

#[test]
fn scalar_history_uses_core_requests_and_waits_for_att_ack_before_emitting() {
    let mut session = Session::new(scalar_config()).unwrap();
    let initial = initialized(&mut session);
    assert_eq!(written(&initial).0, "hrv:0:0");
    let request = qring_r20::codec::unhex(written(&initial).1, 16).unwrap();
    assert_eq!(request[0], 0x39);
    assert_eq!(request[1], 0);
    let mut header = [0; 14];
    header[..3].copy_from_slice(&[0, 3, 60]);
    let mut first = [0; 14];
    first[..4].copy_from_slice(&[1, 0, 80, 255]);
    let mut last = [0; 14];
    last[0] = 2;
    for payload in [&header, &first, &last] {
        let effects = dispatch(
            &mut session,
            json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&core_command(0x39,payload).unwrap())}),
        );
        assert!(
            !effects
                .iter()
                .any(|effect| matches!(effect, Effect::Observation { .. }))
        );
        assert!(
            !effects
                .iter()
                .any(|effect| matches!(effect, Effect::Write { .. }))
        );
    }
    let accepted = dispatch(
        &mut session,
        json!({"type":"write_complete","generation":1,"success":true}),
    );
    assert!(accepted.iter().any(
        |effect| matches!(effect, Effect::Observation { observation }
        if observation["metric"] == "hrv" && observation["value"].as_f64() == Some(255.0)
        && observation["validation_status"] == "source_only")
    ));
    assert_eq!(written(&accepted).0, "stress:0:0");
    assert!(accepted.iter().any(
        |effect| matches!(effect, Effect::Progress { checkpoint, .. }
        if checkpoint.completed_queries.contains(&"hrv:0:0".into()))
    ));
}

#[test]
fn scalar_empty_is_source_only_and_resumable_without_skipping_stress() {
    let mut session = Session::new(scalar_config()).unwrap();
    initialized(&mut session);
    let empty = reply_core(&mut session, 0x39, &[0xff]);
    assert_eq!(written(&empty).0, "stress:0:0");
    assert!(empty.iter().any(|effect| matches!(
        effect,
        Effect::Progress {
            status: "empty",
            validation_status: "source_only",
            ..
        }
    )));
    assert!(
        !empty
            .iter()
            .any(|effect| matches!(effect, Effect::Observation { .. }))
    );
    let mut cfg = scalar_config();
    cfg.completed_queries.push("hrv:0:0".into());
    let mut resumed = Session::new(cfg).unwrap();
    assert_eq!(written(&initialized(&mut resumed)).0, "stress:0:0");
}

#[test]
fn scalar_core_checksum_and_day_mismatch_never_emit_partial_measurements() {
    let mut session = Session::new(scalar_config()).unwrap();
    initialized(&mut session);
    let mut header = core_command(0x39, &[0, 3, 60]).unwrap();
    header[15] ^= 1;
    let corrupt = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&header)}),
    );
    assert!(corrupt.iter().any(|effect| matches!(
        effect,
        Effect::Diagnostic {
            code: "invalid_core_frame",
            ..
        }
    )));
    reply_core(&mut session, 0x39, &[0, 3, 60]);
    let mismatch = reply_core(&mut session, 0x39, &[1, 1, 80]);
    assert!(mismatch.iter().any(|effect| matches!(
        effect,
        Effect::Diagnostic {
            code: "invalid_scalar_history",
            ..
        }
    )));
    assert!(
        !mismatch
            .iter()
            .any(|effect| matches!(effect, Effect::Observation { .. }))
    );
}

#[test]
fn vendor_order_and_checked_typed_clock_model() {
    let mut session = Session::new(config(vec![])).unwrap();
    let clock = startup(&mut session);
    assert_eq!(written(&clock).0, "clock");
    assert!(written(&clock).1.starts_with("012610051230000a"));
    let bind = reply_core(&mut session, 1, &[]);
    assert_eq!(written(&bind).0, "client_bind");
    let bind_bytes = qring_r20::codec::unhex(written(&bind).1, 16).unwrap();
    assert_eq!(&bind_bytes[1..3], &[2, 10]);
    assert_eq!(&bind_bytes[3..15], b"Synthetic-lo");
    let battery = reply_core(&mut session, 4, &[]);
    assert_eq!(
        written(&battery),
        ("battery", "03000000000000000000000000000003")
    );
    let declarations = reply_core(&mut session, 3, &[70, 0]);
    assert_eq!(
        written(&declarations),
        (
            "declared_capabilities:core_3c",
            "3c00000000000000000000000000003c"
        )
    );
    assert_eq!(written(&reply_core(&mut session, 0x3c, &[])).0, "steps:0:0");
}

#[test]
fn actual_declarations_require_att_ack_and_keep_actual_response_time() {
    let mut session = Session::new(config(vec![])).unwrap();
    startup(&mut session);
    let response = session.dispatch(
        event(json!({"type":"notification","generation":1,"channel":"core","bytes_hex":"01010000020000000001002000003055"})),
        200,
    );
    assert!(!has_declaration(&response));
    let accepted = session.dispatch(
        event(json!({"type":"write_complete","generation":1,"success":true})),
        201,
    );
    assert!(accepted.iter().any(|effect| matches!(effect,
        Effect::DeclaredCapabilities { declaration, received_monotonic_ms: 200 }
            if declaration.opcode == 1 && declaration.flags["hrv"] && declaration.validation_status == "declared_only"
    )));
    reply_core(&mut session, 4, &[]);
    reply_core(&mut session, 3, &[70, 0]);
    let response = session.dispatch(
        event(json!({"type":"notification","generation":1,"channel":"core","bytes_hex":"3c00ac2730000000100000000000004f"})),
        300,
    );
    assert!(!has_declaration(&response));
    let accepted = session.dispatch(
        event(json!({"type":"write_complete","generation":1,"success":true})),
        301,
    );
    assert!(accepted.iter().any(|effect| matches!(effect,
        Effect::DeclaredCapabilities { declaration, received_monotonic_ms: 300 }
            if declaration.opcode == 0x3c && declaration.flags["gesture"]
    )));
    assert!(
        !accepted
            .iter()
            .any(|effect| matches!(effect, Effect::Observation { .. }))
    );
    assert_eq!(written(&accepted).0, "steps:0:0");
}

#[test]
fn declarations_are_collected_again_and_never_checkpointed_as_history() {
    let mut session = Session::new(config(vec![
        "battery",
        "declared_capabilities:core_3c",
        "failed:declared_capabilities:core_3c",
        "steps:0:0",
    ]))
    .unwrap();
    startup(&mut session);
    reply_core(&mut session, 1, &[]);
    let queued = reply_core(&mut session, 4, &[]);
    assert_eq!(written(&queued).0, "declared_capabilities:core_3c");
    let accepted = reply_core(&mut session, 0x3c, &[0, 128]);
    let completed = accepted
        .iter()
        .find_map(|effect| match effect {
            Effect::Progress {
                query_id,
                checkpoint,
                status: "complete",
                ..
            } if query_id == "declared_capabilities:core_3c" => Some(checkpoint),
            _ => None,
        })
        .unwrap();
    assert!(
        completed
            .completed_queries
            .iter()
            .all(|key| !key.contains("declared_capabilities"))
    );
    assert_eq!(written(&accepted).0, "sleep:255:0");
}

#[test]
fn stale_corrupt_wrong_opcode_and_disconnected_declarations_are_rejected() {
    let mut session = Session::new(config(vec![])).unwrap();
    startup(&mut session);
    let frame = "01010000020000000001002000003055";
    for (generation, bytes, now) in [
        (2, frame, 100),
        (1, frame, 99),
        (1, "01010000020000000001002000003054", 100),
        (1, "010100000200000000010020000030", 100),
        (1, "3c00ac2730000000100000000000004f", 100),
    ] {
        let rejected = session.dispatch(
            event(json!({"type":"notification","generation":generation,"channel":"core","bytes_hex":bytes})),
            now,
        );
        assert!(
            rejected
                .iter()
                .any(|effect| matches!(effect, Effect::Diagnostic { .. }))
        );
        assert!(!has_declaration(&rejected));
    }
    dispatch(&mut session, json!({"type":"disconnected","generation":1}));
    let rejected = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"core","bytes_hex":frame}),
    );
    assert!(!has_declaration(&rejected));
}

#[test]
fn expired_declaration_response_or_att_ack_never_publishes() {
    for opcode in [0x01, 0x3c] {
        for expired_ack in [false, true] {
            let mut session = Session::new(config(vec![])).unwrap();
            startup(&mut session);
            if opcode == 0x3c {
                reply_core(&mut session, 1, &[]);
                reply_core(&mut session, 4, &[]);
                reply_core(&mut session, 3, &[70, 0]);
            }
            let notification = json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&core_command(opcode,&[0xff;14]).unwrap())});
            let expired = if expired_ack {
                let held = session.dispatch(event(notification), 200);
                assert!(!has_declaration(&held));
                session.dispatch(
                    event(json!({"type":"write_complete","generation":1,"success":true})),
                    100 + QUERY_TIMEOUT_MS,
                )
            } else {
                dispatch(
                    &mut session,
                    json!({"type":"write_complete","generation":1,"success":true}),
                );
                session.dispatch(event(notification), 100 + QUERY_TIMEOUT_MS)
            };
            assert!(!has_declaration(&expired));
            if opcode == 0x01 {
                assert_eq!(written(&expired).0, "clock");
                let repeated = session.dispatch(
                    event(json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&core_command(1,&[0xff;14]).unwrap())})),
                    101 + QUERY_TIMEOUT_MS,
                );
                assert!(!has_declaration(&repeated));
                let initialized = session.dispatch(
                    event(json!({"type":"write_complete","generation":1,"success":true})),
                    102 + QUERY_TIMEOUT_MS,
                );
                assert!(!has_declaration(&initialized));
                assert_eq!(written(&initialized).0, "client_bind");
            } else {
                assert!(!expired.iter().any(|effect| matches!(effect,
                    Effect::Write { bytes_hex, .. } if bytes_hex.starts_with("3c")
                )));
                assert_eq!(written(&expired).0, "steps:0:0");
                let replay = dispatch(
                    &mut session,
                    json!({"type":"notification","generation":1,"channel":"core","bytes_hex":"3c00ac2730000000100000000000004f"}),
                );
                assert!(!has_declaration(&replay));
            }
        }
    }
}

#[test]
fn declared_flags_do_not_promote_compiled_control_qualification() {
    let identity =
        json!({"model":"qring-r20","hardware":"RF20_V2.1","firmware":"RF20_2.10.07_260131"});
    let mut session = Session::new(config(vec![])).unwrap();
    let before = dispatch(
        &mut session,
        json!({"type":"control_capabilities","generation":1,"identity":identity}),
    );
    startup(&mut session);
    reply_core(&mut session, 1, &[0xff; 14]);
    reply_core(&mut session, 4, &[]);
    reply_core(&mut session, 3, &[70, 0]);
    reply_core(&mut session, 0x3c, &[0xff; 14]);
    let after = dispatch(
        &mut session,
        json!({"type":"control_capabilities","generation":1,"identity":identity}),
    );
    assert_eq!(
        serde_json::to_value(before).unwrap(),
        serde_json::to_value(after).unwrap()
    );
}

#[test]
fn write_ack_and_protocol_response_are_independent() {
    let mut session = Session::new(config(vec![])).unwrap();
    startup(&mut session);
    let response = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&core_command(1,&[]).unwrap())}),
    );
    assert!(!response.iter().any(|e| matches!(e, Effect::Write { .. })));
    let after_ack = dispatch(
        &mut session,
        json!({"type":"write_complete","generation":1,"success":true}),
    );
    assert_eq!(written(&after_ack).0, "client_bind");
}

#[test]
fn stale_generation_disconnected_and_bad_packets_do_not_advance() {
    let mut session = Session::new(config(vec![])).unwrap();
    assert!(matches!(
        dispatch(&mut session, json!({"type":"sync","generation":1}))[0],
        Effect::Error {
            code: "disconnected",
            ..
        }
    ));
    startup(&mut session);
    assert!(matches!(
        dispatch(
            &mut session,
            json!({"type":"write_complete","generation":2,"success":true})
        )[0],
        Effect::Diagnostic {
            code: "stale_generation",
            ..
        }
    ));
    assert!(matches!(
        dispatch(
            &mut session,
            json!({"type":"notification","generation":1,"channel":"core","bytes_hex":"0000"})
        )[0],
        Effect::Diagnostic {
            code: "invalid_core_frame",
            ..
        }
    ));
    let disconnect = dispatch(&mut session, json!({"type":"disconnected","generation":1}));
    assert!(disconnect.iter().any(|e| matches!(
        e,
        Effect::Progress {
            status: "interrupted",
            ..
        }
    )));
    assert!(
        progress_json(&disconnect, "clock", "interrupted")
            .get("failure_code")
            .is_none()
    );
}

#[test]
fn early_deadline_is_ignored_and_initialization_failure_blocks_history() {
    let mut session = Session::new(config(vec![])).unwrap();
    startup(&mut session);
    assert!(
        session
            .dispatch(event(json!({"type":"deadline","generation":1})), 101)
            .is_empty()
    );
    for retry in 1..=2 {
        let effects = session.dispatch(
            event(json!({"type":"deadline","generation":1})),
            100 + QUERY_TIMEOUT_MS * retry,
        );
        assert_eq!(written(&effects).0, "clock");
    }
    let effects = session.dispatch(
        event(json!({"type":"deadline","generation":1})),
        100 + QUERY_TIMEOUT_MS * 3,
    );
    assert!(effects.iter().any(|e| matches!(
        e,
        Effect::Error {
            code: "initialization_failed",
            ..
        }
    )));
    assert!(!effects.iter().any(|e| matches!(e, Effect::Write { .. })));
}

#[test]
fn empty_is_not_zero_and_terminal_checkpoints_are_distinct() {
    let mut session = Session::new(config(vec![])).unwrap();
    assert_eq!(written(&initialized(&mut session)).0, "steps:0:0");
    assert_eq!(
        written(&reply_core(&mut session, 0x43, &[0xff])).0,
        "sleep:255:0"
    );
    let empty = reply_bulk(&mut session, 0x27, &[0]);
    assert!(
        !empty
            .iter()
            .any(|e| matches!(e, Effect::Observation { .. }))
    );
    let hr = reply_bulk(&mut session, 0x75, &[0, 5, 0, 0]);
    assert!(hr.iter().any(|e| matches!(e,Effect::Progress{status:"empty",checkpoint,..} if checkpoint.completed_queries.contains(&"heart_rate:0:done".into()))));
}

#[test]
fn hourly_steps_emit_only_after_complete_checksum_checked_wire_records() {
    let mut session = Session::new(config(vec![])).unwrap();
    assert_eq!(written(&initialized(&mut session)).0, "steps:0:0");
    let payload = |slot, packet, value: u16| {
        let mut bytes = [0; 14];
        bytes[..6].copy_from_slice(&[0x26, 0x10, 0x05, slot, packet, 3]);
        bytes[8..10].copy_from_slice(&value.to_le_bytes());
        bytes
    };
    let first = reply_core(&mut session, 0x43, &payload(4, 0, 10));
    assert!(
        !first
            .iter()
            .any(|e| matches!(e, Effect::Observation { .. }))
    );
    assert!(!first.iter().any(|e| matches!(e, Effect::Progress { .. })));
    let mut corrupted = core_command(0x43, &payload(5, 1, 20)).unwrap();
    corrupted[15] ^= 1;
    let rejected = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&corrupted)}),
    );
    assert!(rejected.iter().any(|e| matches!(
        e,
        Effect::Diagnostic {
            code: "invalid_core_frame",
            ..
        }
    )));
    assert!(
        !rejected
            .iter()
            .any(|e| matches!(e, Effect::Observation { .. }))
    );
    let second = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&core_command(0x43,&payload(5,1,20)).unwrap())}),
    );
    assert!(
        !second
            .iter()
            .any(|e| matches!(e, Effect::Observation { .. }))
    );
    let complete = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&core_command(0x43,&payload(8,2,30)).unwrap())}),
    );
    let observations: Vec<_> = complete
        .iter()
        .filter_map(|e| match e {
            Effect::Observation { observation } => Some(observation),
            _ => None,
        })
        .collect();
    assert_eq!(observations.len(), 2);
    for (record, (minute, index)) in observations.iter().zip([(60, 4), (120, 8)]) {
        assert_eq!(record["minute_of_day"], minute);
        assert_eq!(record["sample_index"], index);
        assert_eq!(record["value"].as_f64(), Some(30.0));
        assert_eq!(record["details"]["interval_minutes"], 60);
        assert_eq!(record["details"]["duration_minutes"], 60);
        assert_eq!(record["validation_status"], "source_only");
    }
    assert!(complete.iter().any(|e| matches!(
        e,
        Effect::Progress {
            status: "complete",
            query_id,
            ..
        } if query_id == "steps:0:0"
    )));
    assert_eq!(written(&complete).0, "sleep:255:0");
}

#[test]
fn resume_after_a_completed_page_requests_next_page() {
    let mut session = Session::new(config(vec![
        "steps:0:0",
        "sleep:255:0",
        "heart_rate:0:0",
        "heart_rate:0:samples:3",
        "heart_rate:0:interval:5",
    ]))
    .unwrap();
    let effects = initialized(&mut session);
    assert_eq!(written(&effects).0, "heart_rate:0:1");
    assert!(written(&effects).1.ends_with("0001"));
    let mut done = Session::new(config(vec![
        "steps:0:0",
        "sleep:255:0",
        "heart_rate:0:0",
        "heart_rate:0:done",
    ]))
    .unwrap();
    assert_eq!(written(&initialized(&mut done)).0, "spo2:0:0");
}

#[test]
fn page_mismatch_and_fragment_crc_error_never_complete_query() {
    let mut session = Session::new(config(vec!["steps:0:0", "sleep:255:0"])).unwrap();
    initialized(&mut session);
    let mismatch = reply_bulk(&mut session, 0x75, &[1, 5, 1, 0, 72]);
    assert!(mismatch.iter().any(|e| matches!(
        e,
        Effect::Diagnostic {
            code: "history_page_mismatch",
            ..
        }
    )));
    assert!(
        !mismatch
            .iter()
            .any(|e| matches!(e, Effect::Observation { .. } | Effect::Write { .. }))
    );
    let mut broken = bulk_command(0x75, &[0, 5, 1, 0, 72]).unwrap();
    broken[6] ^= 1;
    assert!(
        dispatch(
            &mut session,
            json!({"type":"notification","generation":1,"channel":"bulk","bytes_hex":hex(&broken)})
        )
        .iter()
        .any(|e| matches!(
            e,
            Effect::Diagnostic {
                code: "invalid_bulk_frame",
                ..
            }
        ))
    );
}

#[test]
fn failed_history_query_does_not_starve_later_metrics() {
    let mut session = Session::new(config(vec![])).unwrap();
    initialized(&mut session);
    for retry in 1..=2 {
        let effects = session.dispatch(
            event(json!({"type":"deadline","generation":1})),
            100 + QUERY_TIMEOUT_MS * retry,
        );
        assert!(effects.iter().any(|e| matches!(
            e,
            Effect::Diagnostic {
                code: "response_timeout",
                ..
            }
        )));
        assert!(!effects.iter().any(|e| matches!(e, Effect::Error { .. })));
    }
    let effects = session.dispatch(
        event(json!({"type":"deadline","generation":1})),
        100 + QUERY_TIMEOUT_MS * 3,
    );
    assert_eq!(written(&effects).0, "sleep:255:0");
    assert!(effects.iter().any(
        |e| matches!(e,Effect::Progress{query_id,status:"failed",..} if query_id=="steps:0:0")
    ));
    let sleep = reply_bulk(&mut session, 0x27, &[0]);
    assert_eq!(written(&sleep).0, "heart_rate:0:0");
}

#[test]
fn legacy_spo2_fallback_is_queried_and_parsed_without_fake_average() {
    let mut session = Session::new(config(vec![
        "steps:0:0",
        "sleep:255:0",
        "heart_rate:0:done",
        "spo2:0:done",
        "temperature:0:done",
    ]))
    .unwrap();
    assert_eq!(written(&initialized(&mut session)).0, "spo2_legacy:255:0");
    let mut payload = vec![0; 49];
    payload[1] = 98;
    payload[2] = 95;
    let effects = reply_bulk(&mut session, 0x2a, &payload);
    assert!(effects.iter().any(|e| matches!(e,Effect::Observation{observation} if observation["metric"]=="spo2" && observation["value"].is_null() && observation["details"]["minimum"].as_f64()==Some(95.0) && observation["details"]["maximum"].as_f64()==Some(98.0))));
    assert_eq!(written(&effects).0, "temperature_legacy:0:0");
}

fn temperature_session() -> Session {
    let mut session = Session::new(config(vec![
        "steps:0:0",
        "sleep:255:0",
        "heart_rate:0:done",
        "spo2:0:done",
        "temperature:0:done",
        "spo2_legacy:255:0",
    ]))
    .unwrap();
    assert_eq!(
        written(&initialized(&mut session)).0,
        "temperature_legacy:0:0"
    );
    dispatch(
        &mut session,
        json!({"type":"write_complete","generation":1,"success":true}),
    );
    session
}
fn temperature_context(request: &str, parsed: &str) -> serde_json::Value {
    json!({"version":"bro-soul1230-temperature-capture-v1",
        "sync_started_at":"2026-10-09T20:59:59Z","parsed_at":parsed,
        "timezone":"Europe/Moscow","request_id":request})
}
fn temperature_fragment(
    session: &mut Session,
    bytes: &[u8],
    capture: serde_json::Value,
    now_ms: u64,
) -> Vec<Effect> {
    session.dispatch(
        event(
            json!({"type":"notification","generation":1,"channel":"bulk",
            "bytes_hex":hex(bytes),"temperature_time_context":capture}),
        ),
        now_ms,
    )
}
fn raw_temperature(effects: &[Effect]) -> &serde_json::Value {
    effects
        .iter()
        .find_map(|effect| match effect {
            Effect::Observation { observation }
                if observation["details"]["type"] == "raw_temperature" =>
            {
                Some(observation)
            }
            _ => None,
        })
        .unwrap()
}

#[test]
fn temperature_capture_uses_final_parse_time_and_preserves_full_frame() {
    let mut session = temperature_session();
    let frame = bulk_command(0x25, &[0, 30, 100, 129, 0, 0]).unwrap();
    let first = temperature_context("request-a", "2026-10-09T21:00:00Z");
    let last = temperature_context("request-a", "2026-10-09T21:00:01Z");
    let partial = temperature_fragment(&mut session, &frame[..4], first, 101);
    assert!(partial.is_empty());
    let effects = temperature_fragment(&mut session, &frame[4..], last.clone(), 102);
    let raw = raw_temperature(&effects);
    assert_eq!(raw["details"]["soul_time_context"], last);
    assert_eq!(raw["details"]["payload_hex"], hex(&frame));
    assert!(raw["value"].is_null());
    assert!(raw["unit"].is_null());
    assert_eq!(raw["quality"], "temperature_layout_unconfirmed");
}

#[test]
fn temperature_fragments_without_one_current_request_remain_raw() {
    let frame = bulk_command(0x25, &[0, 30, 100, 129, 0, 0]).unwrap();
    for (first, last) in [
        (
            serde_json::Value::Null,
            temperature_context("new", "2026-10-09T21:00:01Z"),
        ),
        (
            temperature_context("old", "2026-10-09T21:00:00Z"),
            temperature_context("new", "2026-10-09T21:00:01Z"),
        ),
        (
            temperature_context("same", "2026-10-09T21:00:00Z"),
            serde_json::Value::Null,
        ),
    ] {
        let mut session = temperature_session();
        temperature_fragment(&mut session, &frame[..4], first, 101);
        let effects = temperature_fragment(&mut session, &frame[4..], last, 102);
        let raw = raw_temperature(&effects);
        assert!(raw["details"]["soul_time_context"].is_null());
        assert_eq!(raw["details"]["payload_hex"], hex(&frame));
    }
}

#[test]
fn temperature_retry_expiry_and_stale_generation_never_gain_a_new_anchor() {
    let frame = bulk_command(0x25, &[0, 30, 100, 129, 0, 0]).unwrap();
    let capture = temperature_context("request-a", "2026-10-09T21:00:01Z");
    let mut session = temperature_session();
    let expired = temperature_fragment(
        &mut session,
        &frame,
        capture.clone(),
        101 + QUERY_TIMEOUT_MS,
    );
    assert!(raw_temperature(&expired)["details"]["soul_time_context"].is_null());
    let mut session = temperature_session();
    session.dispatch(
        event(json!({"type":"deadline","generation":1})),
        100 + QUERY_TIMEOUT_MS,
    );
    let retried = temperature_fragment(
        &mut session,
        &frame,
        capture.clone(),
        101 + QUERY_TIMEOUT_MS,
    );
    assert!(raw_temperature(&retried)["details"]["soul_time_context"].is_null());
    let mut session = temperature_session();
    let stale = session.dispatch(
        event(
            json!({"type":"notification","generation":2,"channel":"bulk",
            "bytes_hex":hex(&frame),"temperature_time_context":capture}),
        ),
        101,
    );
    assert!(
        stale
            .iter()
            .all(|effect| !matches!(effect, Effect::Observation { .. }))
    );
    dispatch(&mut session, json!({"type":"disconnected","generation":1}));
    let disconnected = temperature_fragment(
        &mut session,
        &frame,
        temperature_context("request-a", "2026-10-09T21:00:01Z"),
        102,
    );
    assert!(
        disconnected
            .iter()
            .all(|effect| !matches!(effect, Effect::Observation { .. }))
    );
}

#[test]
fn temperature_capture_at_the_exact_deadline_preserves_raw_without_timing_proof() {
    let mut session = temperature_session();
    let frame = bulk_command(0x25, &[0, 30, 100, 129, 0, 0]).unwrap();
    let capture = temperature_context("request-a", "2026-10-09T21:00:01Z");
    let effects = temperature_fragment(&mut session, &frame, capture, 100 + QUERY_TIMEOUT_MS);
    let raw = raw_temperature(&effects);
    assert_eq!(raw["details"]["payload_hex"], hex(&frame));
    assert!(raw["details"]["soul_time_context"].is_null());
}

#[test]
fn temperature_callback_before_successful_write_completion_stays_raw() {
    let mut session = Session::new(config(vec![
        "steps:0:0",
        "sleep:255:0",
        "heart_rate:0:done",
        "spo2:0:done",
        "temperature:0:done",
        "spo2_legacy:255:0",
    ]))
    .unwrap();
    initialized(&mut session);
    let frame = bulk_command(0x25, &[0, 30, 100, 129, 0, 0]).unwrap();
    let effects = temperature_fragment(
        &mut session,
        &frame,
        temperature_context("before-ATT-complete", "2026-10-09T21:00:01Z"),
        101,
    );
    assert!(raw_temperature(&effects)["details"]["soul_time_context"].is_null());
    let failed = dispatch(
        &mut session,
        json!({"type":"write_complete","generation":1,"success":false}),
    );
    assert!(
        failed
            .iter()
            .all(|effect| !matches!(effect, Effect::Observation { .. }))
    );
}

#[test]
fn temperature_transport_context_is_typed_and_bounded_without_changing_raw_admission() {
    let frame = bulk_command(0x25, &[0, 30, 100, 129, 0, 0]).unwrap();
    for (field, value) in [
        ("request_id", json!("x".repeat(201))),
        ("version", json!("unknown")),
        ("timezone", json!("x".repeat(101))),
        ("parsed_at", json!("x".repeat(101))),
    ] {
        let mut session = temperature_session();
        let mut capture = temperature_context("request-a", "2026-10-09T21:00:01Z");
        capture[field] = value;
        let effects = temperature_fragment(&mut session, &frame, capture, 101);
        assert!(raw_temperature(&effects)["details"]["soul_time_context"].is_null());
    }
    let mut capture = temperature_context("request-a", "2026-10-09T21:00:01Z");
    capture["extra"] = json!("ignored");
    assert!(
        serde_json::from_value::<Input>(json!({"type":"notification","generation":1,
        "channel":"bulk","bytes_hex":hex(&frame),"temperature_time_context":capture}))
        .is_err()
    );
}

#[test]
fn invalid_calendar_and_large_checkpoint_are_rejected() {
    let mut bad = config(vec![]);
    bad.clock.day = 32;
    assert!(Session::new(bad).is_err());
    let mut bad = config(vec![]);
    bad.completed_queries = vec!["x".repeat(129)];
    assert!(Session::new(bad).is_err());
}

#[test]
fn bounded_worker_continuation_skips_failed_queries_until_cycle_finishes() {
    let mut first = Session::new(config(vec![])).unwrap();
    initialized(&mut first);
    for tick in 1..=3 {
        first.dispatch(
            event(json!({"type":"deadline","generation":1})),
            100 + QUERY_TIMEOUT_MS * tick,
        );
    }
    reply_bulk(&mut first, 0x27, &[0]);
    let mut last = Vec::new();
    for tick in 1..=3 {
        last = first.dispatch(
            event(json!({"type":"deadline","generation":1})),
            100 + QUERY_TIMEOUT_MS * tick,
        );
    }
    let checkpoint = last
        .iter()
        .find_map(|effect| match effect {
            Effect::Progress {
                status: "failed",
                checkpoint,
                ..
            } => Some(checkpoint.completed_queries.clone()),
            _ => None,
        })
        .unwrap();
    assert!(checkpoint.contains(&"failed:steps:0:0".into()));
    assert!(checkpoint.contains(&"failed:heart_rate:0:0".into()));
    dispatch(&mut first, json!({"type":"disconnected","generation":1}));
    let checkpoint_refs = checkpoint.iter().map(String::as_str).collect();
    let mut continuation = Session::new(config(checkpoint_refs)).unwrap();
    startup(&mut continuation);
    reply_core(&mut continuation, 1, &[]);
    reply_core(&mut continuation, 4, &[]);
    let resumed = reply_core(&mut continuation, 0x3c, &[]);
    assert_eq!(written(&resumed).0, "spo2:0:0");
    let oxygen = reply_bulk(&mut continuation, 0x5f, &[0, 5, 1, 0, 98]);
    assert!(oxygen.iter().any(|effect| matches!(effect,Effect::Observation{observation} if observation["metric"]=="spo2" && observation["value"].as_f64()==Some(98.0))));
    reply_bulk(&mut continuation, 0x74, &[0]);
    reply_bulk(&mut continuation, 0x2a, &[]);
    reply_bulk(&mut continuation, 0x25, &[0, 30]);
    reply_core(&mut continuation, 0x39, &[0xff]);
    let finished = reply_core(&mut continuation, 0x37, &[0xff]);
    assert!(finished.iter().any(|effect| matches!(effect,Effect::Completed{status:"partial",failed_queries} if failed_queries.contains(&"steps:0:0".into()) && failed_queries.contains(&"heart_rate:0:0".into()))), "{finished:?}");
}

#[test]
fn cumulative_paged_samples_keep_their_time_when_a_write_fails() {
    let mut session = Session::new(config(vec!["steps:0:0", "sleep:255:0"])).unwrap();
    initialized(&mut session);
    let first = reply_bulk(&mut session, 0x75, &[0, 5, 2, 0, 70, 0, 72]);
    assert_eq!(written(&first).0, "heart_rate:0:1");
    let early = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"bulk","bytes_hex":hex(&bulk_command(0x75,&[0,5,2,1,74]).unwrap())}),
    );
    assert!(early.iter().any(|effect| matches!(effect,Effect::Observation{observation} if observation["sample_index"]==3 && observation["minute_of_day"]==15)));
    dispatch(
        &mut session,
        json!({"type":"write_complete","generation":1,"success":false}),
    );
    let retried = reply_bulk(&mut session, 0x75, &[0, 5, 2, 1, 74]);
    assert!(retried.iter().any(|effect| matches!(effect,Effect::Observation{observation} if observation["sample_index"]==3 && observation["minute_of_day"]==15)));
    assert!(retried.iter().any(|effect| matches!(effect,Effect::Progress{status:"complete",checkpoint,..} if checkpoint.completed_queries.contains(&"heart_rate:0:samples:4".into()))));
}

#[test]
fn supplemental_nap_after_sleep_does_not_complete_a_heart_rate_query() {
    let mut session = Session::new(config(vec!["steps:0:0"])).unwrap();
    initialized(&mut session);
    let sleep = reply_bulk(&mut session, 0x27, &[0]);
    assert_eq!(written(&sleep).0, "heart_rate:0:0");
    let nap = vec![1, 0, 6, 0x0c, 3, 0x48, 3, 5, 60];
    let supplemental = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"bulk","bytes_hex":hex(&bulk_command(0x3e,&nap).unwrap())}),
    );
    assert!(supplemental.iter().any(|effect| matches!(effect,Effect::Observation{observation} if observation["metric"]=="sleep" && observation["opcode"]==0x3e && observation["details"]["start_minute"]==780)));
    assert!(
        !supplemental
            .iter()
            .any(|effect| matches!(effect, Effect::Write { .. }))
    );
    assert_eq!(
        written(&reply_bulk(&mut session, 0x75, &[0, 5, 0, 0])).0,
        "heart_rate_legacy:0:0"
    );
}

#[test]
fn coalesced_sleep_and_fragmented_nap_keeps_bulk_leftover_between_queries() {
    let mut session = Session::new(config(vec!["steps:0:0"])).unwrap();
    initialized(&mut session);
    dispatch(
        &mut session,
        json!({"type":"write_complete","generation":1,"success":true}),
    );
    let nap = bulk_command(0x3e, &[1, 0, 6, 0x0c, 3, 0x48, 3, 5, 60]).unwrap();
    let mut bytes = bulk_command(0x27, &[0]).unwrap();
    bytes.extend_from_slice(&nap[..2]);
    let next = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"bulk","bytes_hex":hex(&bytes)}),
    );
    assert_eq!(written(&next).0, "heart_rate:0:0");
    let remaining = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"bulk","bytes_hex":hex(&nap[2..])}),
    );
    assert!(remaining.iter().any(
        |effect| matches!(effect,Effect::Observation{observation} if observation["opcode"]==0x3e)
    ));
    assert!(
        !remaining
            .iter()
            .any(|effect| matches!(effect, Effect::Write { .. } | Effect::Error { .. }))
    );
}

#[test]
fn interval_change_across_pages_does_not_change_sample_times() {
    let mut session = Session::new(config(vec!["steps:0:0", "sleep:255:0"])).unwrap();
    initialized(&mut session);
    reply_bulk(&mut session, 0x75, &[0, 5, 2, 0, 70, 71]);
    let changed = reply_bulk(&mut session, 0x75, &[0, 10, 2, 1, 72]);
    assert!(changed.iter().any(|effect| matches!(
        effect,
        Effect::Diagnostic {
            code: "interval_changed",
            ..
        }
    )));
    assert!(
        !changed
            .iter()
            .any(|effect| matches!(effect, Effect::Observation { .. } | Effect::Write { .. }))
    );
    let correct = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"bulk","bytes_hex":hex(&bulk_command(0x75,&[0,5,2,1,72]).unwrap())}),
    );
    assert!(correct.iter().any(|effect| matches!(effect,Effect::Observation{observation} if observation["sample_index"]==2 && observation["minute_of_day"]==10)));
}

#[test]
fn step_date_must_match_the_requested_day() {
    let mut session = Session::new(config(vec![])).unwrap();
    initialized(&mut session);
    let previous_day = [0x26, 0x10, 0x04, 0, 0, 1, 0, 0, 5, 0, 0, 0, 0, 0];
    let mismatched = reply_core(&mut session, 0x43, &previous_day);
    assert!(mismatched.iter().any(|effect| matches!(
        effect,
        Effect::Diagnostic {
            code: "history_day_mismatch",
            ..
        }
    )));
    assert!(
        !mismatched
            .iter()
            .any(|effect| matches!(effect, Effect::Observation { .. } | Effect::Write { .. }))
    );
}

fn synthetic_legacy_frames(day: u8) -> Vec<[u8; 14]> {
    let cfg = config(vec![]);
    let context = qring_r20::history::HistoryContext {
        local_date: cfg.local_date,
        timezone: cfg.timezone,
    };
    let mut frames = vec![[0u8; 14]; 24];
    frames[0][1] = 24;
    frames[0][2] = 5;
    frames[1][0] = 1;
    frames[1][1..5].copy_from_slice(
        &qring_r20::legacy_hr::request_time(&context, day)
            .unwrap()
            .to_le_bytes(),
    );
    frames[1][7] = 76; // synthetic slot2, no captured sample values.
    for (index, frame) in frames.iter_mut().enumerate().skip(2) {
        frame[0] = index as u8;
    }
    frames
}

fn reply_legacy_hr(session: &mut Session, day: u8) -> Vec<Effect> {
    dispatch(
        session,
        json!({"type":"write_complete","generation":1,"success":true}),
    );
    let mut last = Vec::new();
    for frame in synthetic_legacy_frames(day) {
        last = dispatch(
            session,
            json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&core_command(0x15,&frame).unwrap())}),
        );
    }
    last
}

#[test]
fn empty_bulk_hr_reads_legacy_day_without_changing_core_api_or_zero_values() {
    let mut session = Session::new(config(vec!["steps:0:0", "sleep:255:0"])).unwrap();
    initialized(&mut session);
    let fallback = reply_bulk(&mut session, 0x75, &[0, 5, 0, 0]);
    assert_eq!(written(&fallback).0, "heart_rate_legacy:0:0");
    let request = qring_r20::codec::unhex(written(&fallback).1, 16).unwrap();
    assert_eq!(request[0], 0x15);
    assert!(request[5..15].iter().all(|value| *value == 0));
    assert_eq!(
        qring_r20::codec::decode_core(&request).unwrap().opcode,
        0x15
    );
    let completed = reply_legacy_hr(&mut session, 0);
    assert_eq!(written(&completed).0, "spo2:0:0");
    assert!(completed.iter().any(|effect| matches!(effect,Effect::Observation{observation} if observation["metric"]=="heart_rate" && observation["value"].as_f64()==Some(76.0) && observation["sample_index"]==2 && observation["minute_of_day"]==10 && observation["source"]=="oudmon_legacy_hr" && observation["validation_status"]=="source_only")));
    assert!(!completed.iter().any(|effect| matches!(effect,Effect::Observation{observation} if observation["value"].as_f64()==Some(0.0))));
}

#[test]
fn nonempty_bulk_hr_never_also_reads_legacy_or_cross_counts_a_day() {
    let mut session = Session::new(config(vec!["steps:0:0", "sleep:255:0"])).unwrap();
    initialized(&mut session);
    let completed = reply_bulk(&mut session, 0x75, &[0, 5, 1, 0, 76]);
    assert_eq!(written(&completed).0, "spo2:0:0");
    assert!(completed.iter().all(|effect| !matches!(effect,Effect::Write{query_id,..} if query_id.starts_with("heart_rate_legacy:"))));
}

#[test]
fn continuation_remembers_empty_bulk_until_legacy_has_completed() {
    let checkpoint = vec![
        "battery",
        "steps:0:0",
        "sleep:255:0",
        "heart_rate:0:done",
        "heart_rate:0:0",
        "heart_rate:0:empty",
    ];
    let mut session = Session::new(config(checkpoint.clone())).unwrap();
    startup(&mut session);
    reply_core(&mut session, 1, &[]);
    reply_core(&mut session, 4, &[]);
    assert_eq!(
        written(&reply_core(&mut session, 0x3c, &[])).0,
        "heart_rate_legacy:0:0"
    );
    let mut done = checkpoint;
    done.push("heart_rate_legacy:0:0");
    let mut session = Session::new(config(done)).unwrap();
    startup(&mut session);
    reply_core(&mut session, 1, &[]);
    reply_core(&mut session, 4, &[]);
    assert_eq!(written(&reply_core(&mut session, 0x3c, &[])).0, "spo2:0:0");
}

#[test]
fn checksum_and_stale_generation_never_complete_or_import_legacy_hr() {
    let mut session = Session::new(config(vec!["steps:0:0", "sleep:255:0"])).unwrap();
    initialized(&mut session);
    reply_bulk(&mut session, 0x75, &[0, 5, 0, 0]);
    let frames = synthetic_legacy_frames(0);
    let mut invalid = core_command(0x15, &frames[0]).unwrap();
    invalid[15] ^= 1;
    let rejected = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&invalid)}),
    );
    assert!(rejected.iter().any(|effect| matches!(
        effect,
        Effect::Diagnostic {
            code: "invalid_core_frame",
            ..
        }
    )));
    let stale = dispatch(
        &mut session,
        json!({"type":"notification","generation":2,"channel":"core","bytes_hex":hex(&core_command(0x15,&frames[0]).unwrap())}),
    );
    assert!(stale.iter().any(|effect| matches!(
        effect,
        Effect::Diagnostic {
            code: "stale_generation",
            ..
        }
    )));
    assert!(
        !stale
            .iter()
            .any(|effect| matches!(effect, Effect::Observation { .. } | Effect::Write { .. }))
    );
    assert!(reply_legacy_hr(&mut session,0).iter().any(|effect|matches!(effect,Effect::Observation{observation} if observation["value"].as_f64()==Some(76.0))));
}

#[test]
fn confirmed_empty_legacy_reply_finishes_without_retries_after_both_response_and_ack() {
    for ack_first in [false, true] {
        let mut session = Session::new(config(vec!["steps:0:0", "sleep:255:0"])).unwrap();
        initialized(&mut session);
        reply_bulk(&mut session, 0x75, &[0, 5, 0, 0]);
        if ack_first {
            dispatch(
                &mut session,
                json!({"type":"write_complete","generation":1,"success":true}),
            );
        }
        let mut effects = dispatch(
            &mut session,
            json!({"type":"notification","generation":1,"channel":"core","bytes_hex":"15ff0000000000000000000000000014"}),
        );
        assert!(!effects.iter().any(|effect| matches!(
            effect,
            Effect::Observation { .. } | Effect::Diagnostic { .. }
        )));
        if !ack_first {
            assert!(
                !effects
                    .iter()
                    .any(|effect| matches!(effect, Effect::Write { .. }))
            );
            effects = dispatch(
                &mut session,
                json!({"type":"write_complete","generation":1,"success":true}),
            );
        }
        assert_eq!(written(&effects).0, "spo2:0:0");
        assert!(effects.iter().any(|effect|matches!(effect,Effect::Progress{query_id,status:"empty",validation_status:"empty_capture_only",checkpoint,..} if query_id=="heart_rate_legacy:0:0" && checkpoint.completed_queries.contains(query_id))));
        assert!(!effects.iter().any(|effect|matches!(effect,Effect::Write{query_id,..} if query_id=="heart_rate_legacy:0:0")));
    }
}

#[test]
fn device_events_require_fresh_identity_native_source_and_current_generation() {
    let mut session = Session::new(config(vec![])).unwrap();
    let identity =
        json!({"model":"qring-r20","hardware":"RF20_V2.1","firmware":"RF20_2.10.07_260131"});
    let source = "a".repeat(64);
    let notification = json!({"type":"notification","generation":1,"channel":"core",
        "bytes_hex":hex(&core_command(0x73, &[0x38, 0, 0x34, 0x12]).unwrap())});
    dispatch(&mut session, json!({"type":"connected","generation":1}));
    dispatch(
        &mut session,
        json!({"type":"device_capabilities","generation":1,"identity":identity}),
    );
    let invalid = dispatch(
        &mut session,
        json!({"type":"device_event_source","generation":1,"physical_source_id":source}),
    );
    assert!(invalid.iter().any(|effect| matches!(
        effect,
        Effect::Error {
            code: "event_source_unavailable",
            ..
        }
    )));
    assert!(
        !dispatch(&mut session, notification.clone())
            .iter()
            .any(|effect| matches!(effect, Effect::DeviceEvent { .. }))
    );
    dispatch(
        &mut session,
        json!({"type":"device_identity","generation":1,"identity":identity}),
    );
    dispatch(
        &mut session,
        json!({"type":"device_event_source","generation":1,"physical_source_id":source}),
    );
    let effects = dispatch(&mut session, notification.clone());
    assert!(effects.iter().any(|effect| matches!(effect, Effect::DeviceEvent { event } if event.generation == 1 && event.resolved.is_none())));
    let changed = dispatch(
        &mut session,
        json!({"type":"device_event_source","generation":1,"physical_source_id":"b".repeat(64)}),
    );
    assert!(changed.iter().any(|effect| matches!(
        effect,
        Effect::Error {
            code: "event_source_changed",
            ..
        }
    )));
    let mut stale = notification.clone();
    stale["generation"] = json!(2);
    assert!(
        !dispatch(&mut session, stale)
            .iter()
            .any(|effect| matches!(effect, Effect::DeviceEvent { .. }))
    );
    dispatch(&mut session, json!({"type":"disconnected","generation":1}));
    assert!(
        !dispatch(&mut session, notification)
            .iter()
            .any(|effect| matches!(effect, Effect::DeviceEvent { .. }))
    );
}
