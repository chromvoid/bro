//! Synthetic core streams, never captured personal measurements.
use qring_r20::history::{
    CoreScalarHistoryAssembler, HistoryContext, HistoryError, Metric, ObservationDetails,
    parse_bulk,
};

fn context() -> HistoryContext {
    HistoryContext {
        local_date: "2026-03-01".into(),
        timezone: "Europe/Moscow".into(),
    }
}

fn header(count: u8, interval: u8) -> [u8; 14] {
    let mut result = [0; 14];
    result[1] = count;
    result[2] = interval;
    result
}

fn first(day: u8) -> [u8; 14] {
    let mut result = [0; 14];
    result[..5].copy_from_slice(&[1, day, 80, 0, 255]);
    result
}

fn later(sequence: u8) -> [u8; 14] {
    let mut result = [0; 14];
    result[0] = sequence;
    result[1] = 42;
    result[13] = 17;
    result
}

#[test]
fn source_core_hrv_preserves_slots_units_and_day_without_a_named_statistic() {
    let mut stream = CoreScalarHistoryAssembler::new(0x39, context(), 1).unwrap();
    assert_eq!(stream.push(&header(3, 60)).unwrap(), None);
    assert_eq!(stream.push(&first(1)).unwrap(), None);
    let page = stream.push(&later(2)).unwrap().unwrap();
    assert!(!page.empty);
    assert_eq!(page.validation_status, "source_only");
    assert_eq!(page.observations.len(), 24);
    let record = &page.observations[0];
    assert_eq!(record.metric, Metric::Hrv);
    assert_eq!(record.opcode, 0x39);
    assert_eq!(record.source, "oudmon_sdk_core");
    assert_eq!(record.unit.as_deref(), Some("ms"));
    assert_eq!(record.local_date, "2026-02-28");
    assert_eq!(record.value, Some(80.0));
    assert_eq!(record.raw_value, Some(80));
    assert_eq!(page.observations[1].quality, "missing");
    assert_eq!(page.observations[1].minute_of_day, Some(60));
    assert_eq!(page.observations[1].value, None);
    assert_eq!(page.observations[2].value, Some(255.0));
    assert_eq!(page.observations[23].minute_of_day, Some(1380));
    assert!(matches!(
        &record.details,
        ObservationDetails::CoreScalarHistory {
            interval_minutes: 60,
            declared_frames: 3,
            wire_day_offset: 1,
            received_samples: 25,
            ignored_tail_samples: 1,
            ignored_tail_nonzero: true,
            ..
        }
    ));
    assert_eq!(stream.push(&later(2)), Err(HistoryError::AlreadyComplete));
}

#[test]
fn stress_is_an_unsigned_device_index_not_a_percentage() {
    let mut stream = CoreScalarHistoryAssembler::new(0x37, context(), 0).unwrap();
    stream.push(&header(3, 30)).unwrap();
    stream.push(&first(0)).unwrap();
    let page = stream.push(&later(2)).unwrap().unwrap();
    // Receive 25 actual positions, never pad the SDK allocation to a full day.
    assert_eq!(page.observations.len(), 25);
    assert_eq!(page.observations[2].metric, Metric::Stress);
    assert_eq!(page.observations[2].unit.as_deref(), Some("index"));
    assert_eq!(page.observations[2].value, Some(255.0));
    assert_eq!(page.observations[24].minute_of_day, Some(720));
    let encoded = serde_json::to_value(&page.observations[2]).unwrap();
    assert_eq!(encoded["details"]["type"], "core_scalar_history");
    assert_eq!(
        encoded["details"]["decode_version"],
        "oudmon-core-scalar-v1"
    );
    assert_eq!(encoded["validation_status"], "source_only");
}

#[test]
fn explicit_empty_and_missing_samples_remain_different() {
    let mut stream = CoreScalarHistoryAssembler::new(0x39, context(), 0).unwrap();
    let mut empty = [0; 14];
    empty[0] = 0xff;
    let page = stream.push(&empty).unwrap().unwrap();
    assert!(page.empty);
    assert!(page.observations.is_empty());
    assert_eq!(page.validation_status, "source_only");

    let mut stream = CoreScalarHistoryAssembler::new(0x37, context(), 0).unwrap();
    stream.push(&header(2, 30)).unwrap();
    let mut missing = [0; 14];
    missing[0] = 1;
    assert_eq!(stream.push(&missing).unwrap(), None);
    let page = stream.push(&empty).unwrap().unwrap();
    assert!(!page.empty);
    assert_eq!(page.observations.len(), 12);
    assert!(
        page.observations
            .iter()
            .all(|record| record.quality == "missing")
    );
}

#[test]
fn five_frame_thirty_minute_stream_keeps_the_last_day_slot_without_sdk_padding() {
    // Structural layout also occurs in the Soul ATT capture; values are synthetic.
    let mut stream = CoreScalarHistoryAssembler::new(0x37, context(), 0).unwrap();
    stream.push(&header(5, 30)).unwrap();
    for sequence in 1..4 {
        let mut frame = [0; 14];
        frame[0] = sequence;
        assert_eq!(stream.push(&frame).unwrap(), None);
    }
    let mut last = [0; 14];
    last[0] = 4;
    last[10] = 80;
    let page = stream.push(&last).unwrap().unwrap();
    assert_eq!(page.observations.len(), 48);
    assert_eq!(page.observations[47].minute_of_day, Some(1410));
    assert_eq!(page.observations[47].value, Some(80.0));
    assert!(matches!(
        &page.observations[47].details,
        ObservationDetails::CoreScalarHistory {
            declared_frames: 5,
            received_samples: 51,
            ignored_tail_samples: 3,
            ignored_tail_nonzero: false,
            ..
        }
    ));
}

#[test]
fn incomplete_mismatched_reordered_and_conflicting_streams_are_poisoned() {
    let mut end = [0; 14];
    end[0] = 0xff;
    for invalid in [first(1), later(2), end] {
        let mut stream = CoreScalarHistoryAssembler::new(0x39, context(), 0).unwrap();
        stream.push(&header(3, 30)).unwrap();
        assert!(stream.push(&invalid).is_err());
        assert_eq!(stream.push(&first(0)), Err(HistoryError::Sequence));
    }
    let mut stream = CoreScalarHistoryAssembler::new(0x39, context(), 0).unwrap();
    stream.push(&header(3, 30)).unwrap();
    assert_eq!(stream.push(&header(3, 30)).unwrap(), None);
    stream.push(&first(0)).unwrap();
    assert_eq!(stream.push(&first(0)).unwrap(), None);
    let mut conflict = first(0);
    conflict[2] += 1;
    assert_eq!(stream.push(&conflict), Err(HistoryError::Sequence));
    assert_eq!(stream.push(&later(2)), Err(HistoryError::Sequence));
}

#[test]
fn unknown_headers_and_bulk_namespace_are_rejected() {
    for invalid in [
        header(0, 30),
        header(128, 30),
        header(3, 0),
        header(3, 7),
        header(3, 144),
    ] {
        let mut stream = CoreScalarHistoryAssembler::new(0x37, context(), 0).unwrap();
        assert!(stream.push(&invalid).is_err());
    }
    for opcode in [0x39, 0x37] {
        assert_eq!(
            parse_bulk(opcode, &[0, 30, 1, 0, 80], &context()),
            Err(HistoryError::UnsupportedOpcode)
        );
    }
    assert!(CoreScalarHistoryAssembler::new(0x75, context(), 0).is_err());
    assert!(CoreScalarHistoryAssembler::new(0x39, context(), 30).is_err());
}
