use qring_r20::{
    control::{CapabilityKind, CapabilityStatus, RingIdentity, capabilities},
    session::{Config, Effect, Input, Session},
};
use serde_json::{Value, json};

fn identity() -> RingIdentity {
    RingIdentity {
        model: "qring-r20".into(),
        hardware: "RF20_V2.1".into(),
        firmware: "RF20_2.10.07_260131".into(),
    }
}
fn session() -> Session {
    let config: Config = serde_json::from_value(json!({
        "generation": 1, "timezone": "Europe/Moscow", "local_date": "2026-10-09",
        "clock": {"year":2026,"month":10,"day":9,"hour":8,"minute":0,"second":0},
        "android": {"sdk":36,"model":"Synthetic"}, "history_days":1,
    }))
    .unwrap();
    Session::new(config).unwrap()
}
fn dispatch(session: &mut Session, value: Value) -> Vec<Effect> {
    session.dispatch(serde_json::from_value::<Input>(value).unwrap(), 0)
}

#[test]
fn production_registry_requires_exact_model_hardware_and_firmware() {
    let known = capabilities(&identity());
    assert_eq!(known.len(), 13);
    assert!(
        known
            .iter()
            .all(|cap| cap.supported_intervals_minutes.is_empty()
                && !cap.autonomous_night
                && !cap.safe_stop_verified)
    );
    let qualified = known
        .iter()
        .filter(|cap| cap.status == CapabilityStatus::Verified)
        .collect::<Vec<_>>();
    assert_eq!(qualified.len(), 1);
    assert_eq!(qualified[0].kind, CapabilityKind::ManualHeartRate);
    assert!(qualified[0].writable);
    assert!(!qualified[0].readable);
    assert_eq!(
        qualified[0].evidence_ref,
        Some("soul-rf20-manual-heart-rate-20261010")
    );
    assert!(
        known
            .iter()
            .filter(|cap| cap.kind != CapabilityKind::ManualHeartRate)
            .all(|cap| cap.status == CapabilityStatus::ProtocolUnverified && !cap.writable)
    );
    assert_eq!(known.iter().filter(|cap| cap.readable).count(), 4);
    for kind in ["model", "hardware", "firmware"] {
        let mut other = identity();
        match kind {
            "model" => other.model.push(' '),
            "hardware" => other.hardware.push(' '),
            _ => other.firmware.push(' '),
        }
        assert!(
            capabilities(&other)
                .iter()
                .all(|cap| cap.status == CapabilityStatus::Unsupported)
        );
    }
}

#[test]
fn every_unqualified_mutation_is_rejected_through_native_json_without_ble_writes() {
    let mut session = session();
    dispatch(&mut session, json!({"type":"connected","generation":1}));
    let caps = dispatch(
        &mut session,
        json!({"type":"control_capabilities","generation":1,"identity":identity()}),
    );
    let caps = serde_json::to_value(caps).unwrap();
    assert_eq!(caps[0]["type"], "control_capabilities");
    assert_eq!(caps[0]["identity"]["model"], "qring-r20");
    assert!(
        caps[0]["capabilities"]
            .as_array()
            .unwrap()
            .iter()
            .all(|cap| cap["autonomous_night"] == false)
    );
    let commands = [
        json!({"type":"set_schedule","kind":"heart_rate","enabled":true,"interval_minutes":5}),
        json!({"type":"set_schedule","kind":"spo2","enabled":true}),
        json!({"type":"set_schedule","kind":"temperature","enabled":true,"interval_minutes":1}),
        json!({"type":"set_schedule","kind":"hrv","enabled":true}),
        json!({"type":"set_schedule","kind":"stress","enabled":true}),
        json!({"type":"set_heart_rate_alerts","low_bpm":55,"high_bpm":170}),
        json!({"type":"start_workout","kind":"run"}),
        json!({"type":"stop_workout"}),
        json!({"type":"start_live_heart_rate"}),
        json!({"type":"stop_live_heart_rate"}),
        json!({"type":"measure_once","metric":"spo2"}),
        json!({"type":"measure_once","metric":"temperature"}),
        json!({"type":"measure_once","metric":"hrv"}),
        json!({"type":"measure_once","metric":"stress"}),
    ];
    for (index, command) in commands.into_iter().enumerate() {
        let expected = if command["type"] == "set_heart_rate_alerts"
            || (command["type"] == "set_schedule"
                && matches!(
                    command["kind"].as_str(),
                    Some("heart_rate" | "spo2" | "hrv")
                )) {
            "write_recipe_unverified"
        } else {
            "protocol_unverified"
        };
        let effects = dispatch(
            &mut session,
            json!({"type":"control_request","generation":1,"request":{"operation_id":format!("blocked-{index}"),"command":command}}),
        );
        assert!(
            !effects
                .iter()
                .any(|effect| matches!(effect, Effect::Write { .. }))
        );
        let effects = serde_json::to_value(effects).unwrap();
        assert_eq!(effects[0]["type"], "control_result");
        assert_eq!(effects[0]["status"], "rejected");
        assert_eq!(effects[0]["reason"], expected);
    }
}

#[test]
fn qualified_manual_hr_json_has_owned_progress_and_one_final_point() {
    let mut session = session();
    dispatch(&mut session, json!({"type":"connected","generation":1}));
    dispatch(
        &mut session,
        json!({"type":"control_capabilities","generation":1,"identity":identity()}),
    );
    let request = json!({"type":"control_request","generation":1,"request":{
        "operation_id":"synthetic-manual","command":{"type":"measure_once","metric":"heart_rate"}
    }});
    let cached = serde_json::to_value(dispatch(&mut session, request.clone())).unwrap();
    assert_eq!(cached[0]["reason"], "identity_not_verified");
    dispatch(
        &mut session,
        json!({"type":"control_identity","generation":1,"identity":identity()}),
    );
    let started = serde_json::to_value(dispatch(&mut session, request)).unwrap();
    assert_eq!(started[0]["type"], "control_checkpoint");
    let progress = started
        .as_array()
        .unwrap()
        .iter()
        .find(|effect| effect["type"] == "control_progress")
        .unwrap();
    assert_eq!(progress["operation_id"], "synthetic-manual");
    assert_eq!(
        progress["command"],
        json!({"type":"measure_once","metric":"heart_rate"})
    );
    assert_eq!(progress["phase"], "starting");
    assert_eq!(progress["started_at_ms"], 0);
    assert_eq!(progress["deadline_at_ms"], 45_000);
    assert!(progress["stop_reason"].is_null());
    session.dispatch(
        Input::WriteComplete {
            generation: 1,
            success: true,
        },
        1,
    );
    let notification = |opcode, payload: &[u8]| Input::Notification {
        generation: 1,
        channel: "core".into(),
        bytes_hex: qring_r20::codec::hex(&qring_r20::codec::core_command(opcode, payload).unwrap()),
        running_clock: None,
        temperature_time_context: None,
    };
    let zero = session.dispatch(notification(0x69, &[1, 0, 0]), 20_000);
    assert!(
        zero.iter()
            .all(|effect| matches!(effect, Effect::ControlProgress { .. }))
    );
    let stopping =
        serde_json::to_value(session.dispatch(notification(0x69, &[1, 0, 88]), 26_000)).unwrap();
    assert_eq!(stopping[0]["type"], "control_progress");
    assert_eq!(stopping[0]["phase"], "stopping");
    assert_eq!(stopping[0]["stop_reason"], "result");
    session.dispatch(
        Input::WriteComplete {
            generation: 1,
            success: true,
        },
        26_001,
    );
    let final_result =
        serde_json::to_value(session.dispatch(notification(0x6a, &[1, 92]), 26_002)).unwrap();
    assert_eq!(final_result[0]["type"], "control_observation");
    assert_eq!(final_result[0]["operation_id"], "synthetic-manual");
    assert_eq!(final_result[0]["observation"]["value"], 92.0);
    assert_eq!(
        final_result[0]["observation"]["received_monotonic_ms"],
        26_002
    );
    assert_eq!(final_result[1]["type"], "control_result");
    assert_eq!(final_result[1]["operation_id"], "synthetic-manual");
    assert_eq!(final_result[1]["reason"], "measurement_received");
}

#[test]
fn stress_source_candidates_do_not_inherit_hrv_read_or_manual_qualification() {
    let mut session = session();
    dispatch(&mut session, json!({"type":"connected","generation":1}));
    let effects = dispatch(
        &mut session,
        json!({"type":"control_identity","generation":1,"identity":identity()}),
    );
    let value = serde_json::to_value(effects).unwrap();
    let capabilities = value[0]["capabilities"].as_array().unwrap();
    for kind in ["stress_schedule", "manual_stress"] {
        let cap = capabilities.iter().find(|cap| cap["kind"] == kind).unwrap();
        assert_eq!(cap["readable"], false);
        assert_eq!(cap["writable"], false);
        assert_eq!(cap["status"], "protocol_unverified");
    }
    assert_eq!(
        capabilities
            .iter()
            .find(|cap| cap["kind"] == "hrv_schedule")
            .unwrap()["readable"],
        true
    );
    let effects = dispatch(
        &mut session,
        json!({"type":"control_request","generation":1,"request":{"operation_id":"stress-read","command":{"type":"read_settings","kind":"stress"}}}),
    );
    assert!(
        !effects
            .iter()
            .any(|effect| matches!(effect, Effect::Write { .. }))
    );
    assert_eq!(
        serde_json::to_value(effects).unwrap()[0]["reason"],
        "protocol_unverified"
    );
}

#[test]
fn captured_read_requires_fresh_gatt_identity_and_does_not_qualify_writes() {
    let mut session = session();
    dispatch(&mut session, json!({"type":"connected","generation":1}));
    dispatch(
        &mut session,
        json!({"type":"control_capabilities","generation":1,"identity":identity()}),
    );
    let read = json!({"type":"control_request","generation":1,"request":{"operation_id":"read","command":{"type":"read_settings","kind":"heart_rate"}}});
    let cached = dispatch(&mut session, read.clone());
    assert_eq!(
        serde_json::to_value(cached).unwrap()[0]["reason"],
        "identity_not_verified"
    );
    dispatch(
        &mut session,
        json!({"type":"control_identity","generation":1,"identity":identity()}),
    );
    let effects = dispatch(&mut session, read);
    let writes = effects
        .iter()
        .filter_map(|effect| {
            if let Effect::Write { bytes_hex, .. } = effect {
                Some(bytes_hex.as_str())
            } else {
                None
            }
        })
        .collect::<Vec<_>>();
    assert_eq!(writes, vec!["16010000000000000000000000000017"]);
    dispatch(
        &mut session,
        json!({"type":"write_complete","generation":1,"success":true}),
    );
    let synthetic = qring_r20::codec::core_command(0x16, &[1, 1, 15, 5, 0, 0]).unwrap();
    let reply = dispatch(
        &mut session,
        json!({"type":"notification","generation":1,"channel":"core","bytes_hex":qring_r20::codec::hex(&synthetic)}),
    );
    let reply = serde_json::to_value(reply).unwrap();
    assert_eq!(reply[0]["type"], "control_result");
    assert_eq!(reply[0]["status"], "verified");
    assert_eq!(reply[0]["settings"]["interval_minutes"], 15);
    let write = dispatch(
        &mut session,
        json!({"type":"control_request","generation":1,"request":{"operation_id":"write","command":{"type":"set_schedule","kind":"heart_rate","enabled":true,"interval_minutes":15}}}),
    );
    assert!(
        !write
            .iter()
            .any(|effect| matches!(effect, Effect::Write { .. }))
    );
}

#[test]
fn native_callers_cannot_inject_verified_profiles_opcodes_or_receipts() {
    for extra in [
        json!({"verified":true}),
        json!({"opcode":22}),
        json!({"profile":{}}),
    ] {
        let mut input = json!({"type":"control_capabilities","generation":1,"identity":identity()});
        input
            .as_object_mut()
            .unwrap()
            .extend(extra.as_object().unwrap().clone());
        assert!(serde_json::from_value::<Input>(input).is_err());
    }
    assert!(serde_json::from_value::<Input>(json!({"type":"control_request","generation":1,"request":{"operation_id":"unsafe","command":{"type":"set_schedule","kind":"heart_rate","enabled":true,"interval_minutes":5,"opcode":22}}})).is_err());
}

#[test]
fn stale_connection_and_disconnected_control_do_not_write() {
    let mut session = session();
    dispatch(
        &mut session,
        json!({"type":"control_capabilities","generation":1,"identity":identity()}),
    );
    let command = json!({"operation_id":"offline","command":{"type":"start_workout","kind":"run"}});
    let offline = dispatch(
        &mut session,
        json!({"type":"control_request","generation":1,"request":command}),
    );
    assert_eq!(
        serde_json::to_value(offline).unwrap()[0]["reason"],
        "protocol_unverified"
    );
    let stale = dispatch(
        &mut session,
        json!({"type":"control_request","generation":2,"request":command}),
    );
    assert!(matches!(
        &stale[..],
        [Effect::Diagnostic {
            code: "stale_generation",
            ..
        }]
    ));
}
