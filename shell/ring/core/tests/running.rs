use qring_r20::{
    codec::{core_command, hex},
    session::{Config, Effect, Input, QUERY_TIMEOUT_MS, Session},
};
use serde_json::{Value, json};

fn config(enabled: bool) -> Config {
    serde_json::from_value(json!({
        "generation": 1, "timezone": "Europe/Moscow", "local_date": "2026-10-05",
        "clock": {"year": 2026, "month": 10, "day": 5, "hour": 12, "minute": 30, "second": 0},
        "android": {"sdk": 36, "model": "Synthetic"}, "history_days": 1,
        "collect_running_steps": enabled,
    }))
    .unwrap()
}
fn dispatch(session: &mut Session, value: Value, now: u64) -> Vec<Effect> {
    session.dispatch(serde_json::from_value::<Input>(value).unwrap(), now)
}
fn startup(session: &mut Session) {
    dispatch(session, json!({"type": "connected", "generation": 1}), 100);
    dispatch(session, json!({"type": "sync", "generation": 1}), 100);
    for kind in ["firmware", "hardware"] {
        dispatch(
            session,
            json!({"type": "version", "generation": 1, "kind": kind, "value": "synthetic"}),
            100,
        );
    }
}
fn ack(session: &mut Session, success: bool, now: u64) -> Vec<Effect> {
    dispatch(
        session,
        json!({"type": "write_complete", "generation": 1, "success": success}),
        now,
    )
}
fn core(
    session: &mut Session,
    op: u8,
    payload: &[u8],
    proof: Option<Value>,
    now: u64,
) -> Vec<Effect> {
    dispatch(
        session,
        json!({"type": "notification", "generation": 1, "channel": "core",
        "bytes_hex": hex(&core_command(op, payload).unwrap()), "running_clock": proof}),
        now,
    )
}
fn initialized(session: &mut Session) -> Vec<Effect> {
    startup(session);
    for (opcode, payload) in [(1, &[][..]), (4, &[][..])] {
        ack(session, true, 100);
        core(session, opcode, payload, None, 100);
    }
    ack(session, true, 100);
    assert_eq!(
        writes(&core(session, 3, &[70, 0], None, 100)),
        ["declared_capabilities:core_3c"]
    );
    ack(session, true, 100);
    core(session, 0x3c, &[], None, 100)
}
fn writes(effects: &[Effect]) -> Vec<&str> {
    effects
        .iter()
        .filter_map(|effect| match effect {
            Effect::Write { query_id, .. } => Some(query_id.as_str()),
            _ => None,
        })
        .collect()
}
fn proof() -> Value {
    json!({
        "request_local_date": "2026-10-05", "response_local_date": "2026-10-05", "timezone": "Europe/Moscow",
        "request_wall_time_ms": 1791192600000u64, "response_wall_time_ms": 1791192600100u64,
        "request_monotonic_ms": 100, "response_monotonic_ms": 200, "clock_unchanged": true,
    })
}
fn running_observation(effects: &[Effect]) -> &Value {
    effects
        .iter()
        .find_map(|effect| match effect {
            Effect::Observation { observation } if observation["metric"] == "running_steps" => {
                Some(observation)
            }
            _ => None,
        })
        .unwrap()
}
fn no_observation(effects: &[Effect]) {
    assert!(
        !effects
            .iter()
            .any(|effect| matches!(effect, Effect::Observation { .. }))
    );
}

#[test]
fn running_collection_is_explicitly_opt_in_and_existing_queries_stay_first() {
    let config_json = json!({
        "generation": 1, "timezone": "Europe/Moscow", "local_date": "2026-10-05",
        "clock": {"year": 2026, "month": 10, "day": 5, "hour": 12, "minute": 30, "second": 0},
        "android": {"sdk": 36, "model": "Synthetic"}, "history_days": 1,
    });
    let legacy: Config = serde_json::from_value(config_json).unwrap();
    assert!(!legacy.collect_running_steps);
    let mut session = Session::new(legacy).unwrap();
    assert_eq!(writes(&initialized(&mut session)), ["steps:0:0"]);
    let mut session = Session::new(config(true)).unwrap();
    let effects = initialized(&mut session);
    assert_eq!(writes(&effects), ["running_steps:0:0"]);
    assert!(effects.iter().any(|effect| matches!(effect, Effect::Write {channel:"core", response:true, bytes_hex, ..} if bytes_hex == "48000000000000000000000000000048")));
}

#[test]
fn running_snapshot_keeps_aggregates_and_zero_without_adding_ordinary_steps() {
    let mut session = Session::new(config(true)).unwrap();
    initialized(&mut session);
    ack(&mut session, true, 100);
    let payload = [1, 2, 3, 0, 4, 5, 0, 6, 7, 0, 8, 9, 1, 2];
    let effects = core(&mut session, 0x48, &payload, Some(proof()), 200);
    let observation = running_observation(&effects);
    assert_eq!(observation["value"].as_f64(), Some(0x0405 as f64));
    assert_eq!(observation["local_date"], "2026-10-05");
    assert_eq!(observation["timezone"], "Europe/Moscow");
    assert_eq!(observation["validation_status"], "source_only");
    assert!(observation["minute_of_day"].is_null());
    assert_eq!(observation["details"]["type"], "running_snapshot");
    assert_eq!(observation["details"]["total_steps"], 0x010203);
    assert_eq!(observation["details"]["calories_raw"], 0x0607);
    assert_eq!(observation["details"]["distance_raw"], 0x0809);
    assert_eq!(observation["details"]["duration_minutes"], 0x0102);
    assert_eq!(observation["details"]["clock_proof"], proof());
    let quantities: Vec<_> = effects
        .iter()
        .filter_map(|effect| match effect {
            Effect::Observation { observation }
                if observation["metric"] == "active_energy"
                    || observation["metric"] == "distance" =>
            {
                Some(observation)
            }
            _ => None,
        })
        .collect();
    assert_eq!(quantities.len(), 2);
    assert_eq!(quantities[0]["value"].as_f64(), Some(1.543));
    assert_eq!(quantities[1]["value"].as_f64(), Some(2057.0));
    for quantity in quantities {
        assert_eq!(quantity["details"]["clock_proof"], proof());
        assert_eq!(quantity["validation_status"], "source_only");
    }
    assert_eq!(writes(&effects), ["steps:0:0"]);
    assert!(!effects.iter().any(|effect| matches!(effect, Effect::Observation {observation} if observation["metric"] == "steps")));
    let mut zero = Session::new(config(true)).unwrap();
    initialized(&mut zero);
    ack(&mut zero, true, 100);
    assert_eq!(
        running_observation(&core(&mut zero, 0x48, &[0; 14], Some(proof()), 200))["value"].as_f64(),
        Some(0.0)
    );
}

#[test]
fn response_waits_for_successful_att_write_and_failed_ack_discards_it() {
    let mut session = Session::new(config(true)).unwrap();
    initialized(&mut session);
    let response = core(&mut session, 0x48, &[0; 14], Some(proof()), 200);
    no_observation(&response);
    assert!(writes(&response).is_empty());
    let acknowledged = ack(&mut session, true, 201);
    assert_eq!(
        running_observation(&acknowledged)["value"].as_f64(),
        Some(0.0)
    );
    assert_eq!(
        acknowledged
            .iter()
            .filter(|effect| matches!(effect, Effect::Observation { .. }))
            .count(),
        3
    );
    assert_eq!(writes(&acknowledged), ["steps:0:0"]);
    let mut failed = Session::new(config(true)).unwrap();
    initialized(&mut failed);
    no_observation(&core(&mut failed, 0x48, &[0; 14], Some(proof()), 200));
    let rejected = ack(&mut failed, false, 201);
    no_observation(&rejected);
    assert_eq!(writes(&rejected), ["steps:0:0"]);
    assert!(rejected.iter().any(|effect| matches!(effect, Effect::Progress {query_id,status:"failed",..} if query_id=="running_steps:0:0")));
}

#[test]
fn optional_running_timeout_does_not_retry_or_starve_other_history() {
    let mut session = Session::new(config(true)).unwrap();
    initialized(&mut session);
    assert!(
        dispatch(
            &mut session,
            json!({"type":"deadline","generation":1}),
            100 + QUERY_TIMEOUT_MS - 1
        )
        .is_empty()
    );
    let timeout = dispatch(
        &mut session,
        json!({"type":"deadline","generation":1}),
        100 + QUERY_TIMEOUT_MS,
    );
    assert_eq!(writes(&timeout), ["steps:0:0"]);
    assert!(timeout.iter().any(|effect| matches!(effect, Effect::Progress {status:"failed", checkpoint, ..} if !checkpoint.completed_queries.iter().any(|key| key.contains("running_steps")))));
    no_observation(&core(
        &mut session,
        0x48,
        &[0; 14],
        Some(proof()),
        100 + QUERY_TIMEOUT_MS + 1,
    ));
}

#[test]
fn late_running_reply_is_rejected_without_waiting_for_deadline_event() {
    let mut session = Session::new(config(true)).unwrap();
    initialized(&mut session);
    ack(&mut session, true, 100);
    let late = core(
        &mut session,
        0x48,
        &[0; 14],
        Some(proof()),
        100 + QUERY_TIMEOUT_MS,
    );
    no_observation(&late);
    assert_eq!(writes(&late), ["steps:0:0"]);
}

#[test]
fn date_zone_clock_and_monotonic_proofs_are_all_required() {
    let mut cases = vec![None];
    for (field, value) in [
        ("request_local_date", json!("2026-10-04")),
        ("response_local_date", json!("2026-10-06")),
        ("timezone", json!("UTC")),
        ("request_monotonic_ms", json!(99)),
        ("response_monotonic_ms", json!(99)),
        ("response_monotonic_ms", json!(201)),
        ("response_wall_time_ms", json!(1791192599999u64)),
        ("response_wall_time_ms", json!(1791192602201u64)),
        ("clock_unchanged", json!(false)),
    ] {
        let mut evidence = proof();
        evidence[field] = value;
        cases.push(Some(evidence));
    }
    for evidence in cases {
        let mut session = Session::new(config(true)).unwrap();
        initialized(&mut session);
        ack(&mut session, true, 100);
        let effects = core(&mut session, 0x48, &[0; 14], evidence, 200);
        no_observation(&effects);
        assert!(effects.iter().any(|effect| matches!(
            effect,
            Effect::Diagnostic {
                code: "running_ambiguous_time",
                ..
            }
        )));
        assert_eq!(writes(&effects), ["steps:0:0"]);
    }
}

#[test]
fn checksum_failure_stale_generation_and_disconnection_do_not_import() {
    let mut session = Session::new(config(true)).unwrap();
    initialized(&mut session);
    let mut corrupt = core_command(0x48, &[0; 14]).unwrap();
    corrupt[15] ^= 1;
    let invalid = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"core","bytes_hex":hex(&corrupt),"running_clock":proof()}),
        200,
    );
    no_observation(&invalid);
    assert!(invalid.iter().any(|effect| matches!(
        effect,
        Effect::Diagnostic {
            code: "invalid_core_frame",
            ..
        }
    )));
    let stale = dispatch(
        &mut session,
        json!({"type":"notification","generation":2,"channel":"core","bytes_hex":hex(&core_command(0x48,&[0;14]).unwrap()),"running_clock":proof()}),
        200,
    );
    no_observation(&stale);
    assert!(stale.iter().any(|effect| matches!(
        effect,
        Effect::Diagnostic {
            code: "stale_generation",
            ..
        }
    )));
    core(&mut session, 0x48, &[0; 14], Some(proof()), 200);
    dispatch(
        &mut session,
        json!({"type":"disconnected","generation":1}),
        201,
    );
    no_observation(&ack(&mut session, true, 202));
    no_observation(&core(&mut session, 0x48, &[0; 14], Some(proof()), 202));
}

#[test]
fn clock_initialization_failure_prevents_running_request() {
    let mut session = Session::new(config(true)).unwrap();
    startup(&mut session);
    for retry in 1..=3 {
        let effects = dispatch(
            &mut session,
            json!({"type":"deadline","generation":1}),
            100 + QUERY_TIMEOUT_MS * retry,
        );
        assert!(!writes(&effects).contains(&"running_steps:0:0"));
        no_observation(&effects);
        if retry == 3 {
            assert!(effects.iter().any(|effect| matches!(
                effect,
                Effect::Error {
                    code: "initialization_failed",
                    ..
                }
            )));
        }
    }
}

#[test]
fn continuation_checkpoint_never_skips_a_fresh_snapshot_but_same_session_does() {
    let mut cfg = config(true);
    cfg.completed_queries = vec![
        "running_steps:0:0".into(),
        "failed:running_steps:0:0".into(),
    ];
    let mut session = Session::new(cfg).unwrap();
    assert_eq!(writes(&initialized(&mut session)), ["running_steps:0:0"]);
    ack(&mut session, true, 100);
    core(&mut session, 0x48, &[0; 14], Some(proof()), 200);
    dispatch(
        &mut session,
        json!({"type":"disconnected","generation":1}),
        201,
    );
    startup(&mut session);
    ack(&mut session, true, 100);
    core(&mut session, 1, &[], None, 100);
    ack(&mut session, true, 100);
    assert_eq!(
        writes(&core(&mut session, 4, &[], None, 100)),
        ["declared_capabilities:core_3c"]
    );
    // Battery is already checkpointed, but declarations are always fresh.
    // Reusing this Session must still skip its completed running snapshot.
    ack(&mut session, true, 100);
    assert_eq!(
        writes(&core(&mut session, 0x3c, &[], None, 100)),
        ["steps:0:0"]
    );
}
