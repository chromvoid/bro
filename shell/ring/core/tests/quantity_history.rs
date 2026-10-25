//! Unit provenance comes from the exact Soul path; fixtures are synthetic.
use qring_r20::history::{CoreHistoryAssembler, HistoryContext, Metric, ObservationDetails};

fn context() -> HistoryContext {
    HistoryContext {
        local_date: "2026-10-05".into(),
        timezone: "Europe/Moscow".into(),
    }
}

#[test]
fn core_48_preserves_full_unsigned_counters_and_fractional_kcal() {
    let payload = [1, 2, 3, 4, 5, 6, 1, 0xbc, 0x37, 1, 2, 3, 0, 42];
    let page = CoreHistoryAssembler::new(0x48, context())
        .push(&payload)
        .unwrap()
        .unwrap();
    assert_eq!(page.observations.len(), 3);
    let energy = &page.observations[1];
    assert_eq!(energy.metric, Metric::ActiveEnergy);
    assert_eq!(energy.raw_value, Some(113719));
    assert_eq!(energy.value, Some(113.719));
    assert_eq!(energy.unit.as_deref(), Some("kcal"));
    assert_eq!(energy.minute_of_day, None);
    assert_eq!(energy.validation_status, "source_only");
    assert!(matches!(
        &energy.details,
        ObservationDetails::ActivityQuantitySnapshot {
            total_steps: 0x010203,
            calories_raw: 113719,
            distance_raw: 0x010203,
            duration_minutes: 42,
            soul_display_value: 113.0,
            ..
        }
    ));
    let distance = &page.observations[2];
    assert_eq!(distance.metric, Metric::Distance);
    assert_eq!(distance.raw_value, Some(0x010203));
    assert_eq!(distance.value, Some(66051.0));
    assert_eq!(distance.unit.as_deref(), Some("m"));
    let json = serde_json::to_value(distance).unwrap();
    assert_eq!(json["details"]["unit_evidence"]["namespace"], "core");
    assert_eq!(json["details"]["unit_evidence"]["opcode"], 0x48);
    assert_eq!(json["details"]["unit_evidence"]["divisor"], 1);
    assert_eq!(json["details"]["soul_display_unit"], "km");
}

#[test]
fn zero_is_a_snapshot_value_and_core_43_does_not_borrow_core_48_scales() {
    let page = CoreHistoryAssembler::new(0x48, context())
        .push(&[0; 14])
        .unwrap()
        .unwrap();
    assert!(
        page.observations
            .iter()
            .all(|record| record.value == Some(0.0))
    );
    let mut wire = [0; 14];
    wire[..6].copy_from_slice(&[0x26, 0x10, 0x05, 4, 0, 1]);
    wire[6..8].copy_from_slice(&17u16.to_le_bytes());
    wire[10..12].copy_from_slice(&23u16.to_le_bytes());
    let page = CoreHistoryAssembler::new(0x43, context())
        .push(&wire)
        .unwrap()
        .unwrap();
    assert_eq!(page.observations.len(), 1);
    assert_eq!(page.observations[0].metric, Metric::Steps);
    assert!(matches!(
        &page.observations[0].details,
        ObservationDetails::Steps {
            calories_raw: 17,
            distance_raw: 23,
            ..
        }
    ));
}
