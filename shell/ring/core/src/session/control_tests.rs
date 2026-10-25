use super::*;
use crate::control::verified_test_manager;
use serde_json::json;

fn verified_session() -> Session {
    let config: Config = serde_json::from_value(json!({
        "generation": 1, "timezone": "Europe/Moscow", "local_date": "2026-10-09",
        "clock": {"year":2026,"month":10,"day":9,"hour":8,"minute":0,"second":0},
        "android": {"sdk":36,"model":"Synthetic"}, "history_days":1,
    }))
    .unwrap();
    let mut session = Session::new(config).unwrap();
    session.control = Some(verified_test_manager());
    session
}
fn request(id: &str, command: crate::control::ControlCommand) -> Input {
    Input::ControlRequest {
        generation: 1,
        request: ControlRequest {
            operation_id: id.into(),
            command,
        },
    }
}
fn reason(effects: &[Effect]) -> &str {
    effects
        .iter()
        .find_map(|effect| match effect {
            Effect::ControlResult { result } => Some(result.reason),
            _ => None,
        })
        .unwrap()
}

#[test]
fn cached_capabilities_never_grant_current_connection_control_authority() {
    let mut session = verified_session();
    let identity = session.control.as_ref().unwrap().identity().clone();
    session.dispatch(Input::Connected { generation: 1 }, 0);
    session.dispatch(
        Input::ControlCapabilities {
            generation: 1,
            identity: identity.clone(),
        },
        1,
    );
    let before = session.dispatch(
        request(
            "before",
            crate::control::ControlCommand::StartWorkout {
                kind: crate::control::WorkoutKind::Run,
            },
        ),
        2,
    );
    assert_eq!(reason(&before), "identity_not_verified");
    assert!(
        !before
            .iter()
            .any(|effect| matches!(effect, Effect::Write { .. }))
    );
    session.dispatch(
        Input::ControlIdentity {
            generation: 1,
            identity: identity.clone(),
        },
        3,
    );
    let start = session.dispatch(
        request(
            "run",
            crate::control::ControlCommand::StartWorkout {
                kind: crate::control::WorkoutKind::Run,
            },
        ),
        4,
    );
    assert!(matches!(start[0], Effect::ControlCheckpoint { .. }));
    assert!(
        start
            .iter()
            .any(|effect| matches!(effect, Effect::Write { .. }))
    );
    let stopped_link = session.dispatch(Input::Disconnected { generation: 1 }, 5);
    assert_eq!(reason(&stopped_link), "control_disconnected");
    let original = session.dispatch(
        request(
            "run",
            crate::control::ControlCommand::StartWorkout {
                kind: crate::control::WorkoutKind::Run,
            },
        ),
        5,
    );
    assert_eq!(reason(&original), "control_disconnected");
    assert!(
        matches!(&original[..], [Effect::ControlResult { result }] if result.status == ControlStatus::Unknown)
    );
    session.dispatch(Input::Connected { generation: 1 }, 6);
    session.dispatch(
        Input::ControlCapabilities {
            generation: 1,
            identity: identity.clone(),
        },
        7,
    );
    let cached = session.dispatch(
        request("stop", crate::control::ControlCommand::StopWorkout),
        8,
    );
    assert_eq!(reason(&cached), "identity_not_verified");
    assert!(
        !cached
            .iter()
            .any(|effect| matches!(effect, Effect::Write { .. }))
    );
    session.dispatch(
        Input::ControlIdentity {
            generation: 1,
            identity,
        },
        9,
    );
    let fresh = session.dispatch(
        request("stop", crate::control::ControlCommand::StopWorkout),
        10,
    );
    assert!(
        fresh
            .iter()
            .any(|effect| matches!(effect, Effect::Write { .. }))
    );
}

#[test]
fn history_and_controls_share_the_connection_without_interleaving_queries() {
    let mut session = verified_session();
    let identity = session.control.as_ref().unwrap().identity().clone();
    session.dispatch(Input::Connected { generation: 1 }, 0);
    session.dispatch(
        Input::ControlIdentity {
            generation: 1,
            identity,
        },
        1,
    );
    session.dispatch(Input::Sync { generation: 1 }, 2);
    let control = session.dispatch(
        request(
            "run",
            crate::control::ControlCommand::StartWorkout {
                kind: crate::control::WorkoutKind::Run,
            },
        ),
        3,
    );
    assert_eq!(reason(&control), "history_busy");
    assert!(
        !control
            .iter()
            .any(|effect| matches!(effect, Effect::Write { .. }))
    );
}

#[test]
fn identity_proof_from_another_generation_or_while_disconnected_is_rejected() {
    let mut session = verified_session();
    let identity = session.control.as_ref().unwrap().identity().clone();
    let disconnected = session.dispatch(
        Input::ControlIdentity {
            generation: 1,
            identity: identity.clone(),
        },
        0,
    );
    assert!(matches!(
        &disconnected[..],
        [Effect::Error {
            code: "disconnected",
            ..
        }]
    ));
    session.dispatch(Input::Connected { generation: 1 }, 1);
    let stale = session.dispatch(
        Input::ControlIdentity {
            generation: 2,
            identity,
        },
        2,
    );
    assert!(matches!(
        &stale[..],
        [Effect::Diagnostic {
            code: "stale_generation",
            ..
        }]
    ));
    assert!(!session.control_identity_verified);
}

#[test]
fn manual_cancel_json_is_owned_and_never_uses_stale_connection_authority() {
    let mut session = verified_session();
    let identity = session.control.as_ref().unwrap().identity().clone();
    session.dispatch(Input::Connected { generation: 1 }, 0);
    let cancel = |generation, id: &str| Input::ControlCancel {
        generation,
        operation_id: id.into(),
    };
    let before_identity = session.dispatch(cancel(1, "manual"), 1);
    assert!(matches!(
        &before_identity[..],
        [Effect::Diagnostic {
            code: "measurement_not_active",
            ..
        }]
    ));
    session.dispatch(
        Input::ControlIdentity {
            generation: 1,
            identity,
        },
        2,
    );
    session.dispatch(
        request(
            "manual",
            crate::control::ControlCommand::MeasureOnce {
                metric: crate::control::Metric::HeartRate,
            },
        ),
        3,
    );
    session.dispatch(
        Input::WriteComplete {
            generation: 1,
            success: true,
        },
        4,
    );
    let stale_generation = session.dispatch(cancel(2, "manual"), 5);
    assert!(matches!(
        &stale_generation[..],
        [Effect::Diagnostic {
            code: "stale_generation",
            ..
        }]
    ));
    let stale_operation = session.dispatch(cancel(1, "previous"), 6);
    assert!(matches!(
        &stale_operation[..],
        [Effect::Diagnostic {
            code: "stale_measurement_operation",
            ..
        }]
    ));
    let stopping = session.dispatch(cancel(1, "manual"), 7);
    assert!(
        stopping
            .iter()
            .any(|effect| matches!(effect, Effect::Write { .. }))
    );
    let progress = serde_json::to_value(&stopping).unwrap();
    assert_eq!(progress[0]["type"], "control_progress");
    assert_eq!(progress[0]["operation_id"], "manual");
    assert_eq!(progress[0]["phase"], "stopping");
    assert_eq!(progress[0]["stop_reason"], "cancelled");
    let disconnected = session.dispatch(Input::Disconnected { generation: 1 }, 8);
    assert_eq!(reason(&disconnected), "control_disconnected");
    let after_disconnect = session.dispatch(cancel(1, "manual"), 9);
    assert!(matches!(&after_disconnect[..], [Effect::Diagnostic { .. }]));
    let invalid: serde_json::Value = json!({
        "type":"control_cancel","generation":1,"operation_id":"manual","metric":"heart_rate"
    });
    assert!(serde_json::from_value::<Input>(invalid).is_err());
}
