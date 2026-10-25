//! Entirely synthetic records. No captured family health data is used here.
use qring_r20::history::{
    CoreHistoryAssembler, HistoryContext, HistoryError, Metric, ObservationDetails, parse_bulk,
    place_interval_page, validate_context,
};

fn context(date: &str) -> HistoryContext {
    HistoryContext {
        local_date: date.into(),
        timezone: "Europe/Moscow".into(),
    }
}

fn steps_packet(date: [u8; 3], time_index: u8, packet: u8, total: u8, steps: u16) -> [u8; 14] {
    let mut bytes = [0; 14];
    bytes[..3].copy_from_slice(&date);
    bytes[3..6].copy_from_slice(&[time_index, packet, total]);
    bytes[6..8].copy_from_slice(&0x1234u16.to_le_bytes());
    bytes[8..10].copy_from_slice(&steps.to_le_bytes());
    bytes[10..12].copy_from_slice(&0x5678u16.to_le_bytes());
    bytes
}

fn sleep(day: u8, start: u16, end: u16, stages: &[(u8, u8)]) -> Vec<u8> {
    let mut bytes = vec![1, day, (4 + stages.len() * 2) as u8];
    bytes.extend_from_slice(&start.to_le_bytes());
    bytes.extend_from_slice(&end.to_le_bytes());
    for (stage, duration) in stages {
        bytes.extend_from_slice(&[*stage, *duration]);
    }
    bytes
}

#[test]
fn heart_rate_preserves_missing_position_and_provenance() {
    let page = parse_bulk(0x75, &[1, 5, 1, 0, 60, 0, 255], &context("2026-10-05")).unwrap();
    assert!(!page.empty);
    assert_eq!(page.validation_status, "source_only");
    assert_eq!(page.observations.len(), 3);
    let valid = &page.observations[0];
    assert_eq!(valid.metric, Metric::HeartRate);
    assert_eq!(valid.local_date, "2026-10-04");
    assert_eq!(valid.minute_of_day, Some(0));
    assert_eq!(valid.value, Some(60.0));
    assert_eq!(valid.raw_value, Some(60));
    assert_eq!(valid.unit.as_deref(), Some("bpm"));
    assert_eq!(valid.validation_status, "source_only");
    assert_eq!(page.observations[1].sample_index, 1);
    assert_eq!(page.observations[1].minute_of_day, Some(5));
    assert_eq!(page.observations[1].raw_value, Some(0));
    assert_eq!(page.observations[1].value, None);
    assert_eq!(page.observations[1].quality, "missing");
    // Preserve the full unsigned wire range; do not reinterpret as signed -1.
    assert_eq!(page.observations[2].value, Some(255.0));
}

#[test]
fn later_interval_page_does_not_invent_absolute_time() {
    let page = parse_bulk(0x5f, &[0, 30, 3, 1, 98, 100, 101], &context("2026-10-05")).unwrap();
    assert_eq!(page.page, 1);
    assert_eq!(page.next_page, Some(2));
    assert_eq!(page.observations[0].sample_index, 1 << 16);
    assert_eq!(page.observations[0].minute_of_day, None);
    assert_eq!(page.observations[1].value, Some(100.0));
    assert_eq!(page.observations[2].quality, "out_of_range");
    assert_eq!(page.observations[2].value, None);
    assert_eq!(page.observations[2].raw_value, Some(101));
}

#[test]
fn interval_page_placement_uses_actual_prior_count_including_missing_samples() {
    let ctx = context("2026-10-05");
    let mut first = parse_bulk(0x75, &[0, 120, 2, 0, 80, 0], &ctx).unwrap();
    let count = place_interval_page(&mut first, 0).unwrap();
    assert_eq!(count, 2);
    assert_eq!(first.observations[1].minute_of_day, Some(120));
    assert_eq!(first.observations[1].quality, "missing");
    let mut second = parse_bulk(0x75, &[0, 120, 2, 1, 90], &ctx).unwrap();
    assert_eq!(second.observations[0].minute_of_day, None);
    assert_eq!(place_interval_page(&mut second, count).unwrap(), 3);
    assert_eq!(second.observations[0].sample_index, 2);
    assert_eq!(second.observations[0].minute_of_day, Some(240));
    assert_eq!(second.observations[0].value, Some(90.0));
}

#[test]
fn invalid_interval_placement_is_atomic_and_rejects_overflow() {
    let ctx = context("2026-10-05");
    let mut page = parse_bulk(0x75, &[0, 120, 2, 1, 90, 0], &ctx).unwrap();
    let original = page.clone();
    assert_eq!(
        place_interval_page(&mut page, 11),
        Err(HistoryError::TooLarge)
    );
    assert_eq!(page, original);
    if let ObservationDetails::Interval { index_in_page, .. } = &mut page.observations[1].details {
        *index_in_page = 3;
    }
    let original = page.clone();
    assert_eq!(
        place_interval_page(&mut page, 0),
        Err(HistoryError::Sequence)
    );
    assert_eq!(page, original);
}

#[test]
fn centidegree_temperature_is_little_endian_and_not_tenths() {
    let mut data = vec![0, 30, 1, 0];
    for value in [3654u16, 0, 6001] {
        data.extend_from_slice(&value.to_le_bytes());
    }
    let page = parse_bulk(0x74, &data, &context("2026-10-05")).unwrap();
    assert_eq!(page.observations[0].value, Some(36.54));
    assert_eq!(page.observations[0].raw_value, Some(3654));
    assert_eq!(page.observations[0].unit.as_deref(), Some("celsius"));
    assert_eq!(page.observations[1].quality, "missing");
    assert_eq!(page.observations[1].value, None);
    assert_eq!(page.observations[2].quality, "out_of_range");
}

#[test]
fn empty_interval_response_is_distinct_from_missing_samples() {
    let page = parse_bulk(0x74, &[0, 0, 0, 0], &context("2026-10-05")).unwrap();
    assert!(page.empty);
    assert!(page.observations.is_empty());
    assert_eq!(page.validation_status, "empty_capture_only");
    let missing = parse_bulk(0x74, &[0, 30, 1, 0, 0, 0], &context("2026-10-05")).unwrap();
    assert!(!missing.empty);
    assert_eq!(missing.validation_status, "source_only");
}

#[test]
fn captured_compact_empty_is_accepted_only_for_oxygen_and_temperature() {
    let ctx = context("2026-10-05");
    for action in [0x5f, 0x74] {
        let page = parse_bulk(action, &[0], &ctx).unwrap();
        assert!(page.empty);
        assert!(page.observations.is_empty());
        assert_eq!(page.validation_status, "empty_capture_only");
        assert_eq!(parse_bulk(action, &[], &ctx), Err(HistoryError::Length));
        assert_eq!(parse_bulk(action, &[1], &ctx), Err(HistoryError::Length));
        assert_eq!(parse_bulk(action, &[0, 0], &ctx), Err(HistoryError::Length));
    }
    assert_eq!(parse_bulk(0x75, &[0], &ctx), Err(HistoryError::Length));
}

#[test]
fn malformed_and_oversized_intervals_fail_without_partial_results() {
    let ctx = context("2026-10-05");
    assert_eq!(
        parse_bulk(0x74, &[0, 30, 1, 0, 1], &ctx),
        Err(HistoryError::Length)
    );
    assert_eq!(
        parse_bulk(0x75, &[0, 7, 1, 0, 80], &ctx),
        Err(HistoryError::InvalidInterval)
    );
    assert_eq!(
        parse_bulk(0x75, &[0, 0, 1, 0, 80], &ctx),
        Err(HistoryError::InvalidInterval)
    );
    assert_eq!(
        parse_bulk(0x75, &[0, 5, 1, 1, 80], &ctx),
        Err(HistoryError::InvalidPage)
    );
    assert_eq!(
        parse_bulk(0x75, &[0, 5, 0, 0, 80], &ctx),
        Err(HistoryError::InvalidPage)
    );
    assert_eq!(
        parse_bulk(0x75, &[0, 5, 1, 0], &ctx),
        Err(HistoryError::InvalidRecord)
    );
    let mut too_many = vec![0, 60, 1, 0];
    too_many.extend_from_slice(&[80; 25]);
    assert_eq!(
        parse_bulk(0x75, &too_many, &ctx),
        Err(HistoryError::TooLarge)
    );
    assert_eq!(
        parse_bulk(0x75, &vec![0; 16385], &ctx),
        Err(HistoryError::TooLarge)
    );
}

#[test]
fn oxygen_hourly_minimum_and_maximum_remain_a_range() {
    let mut data = vec![1];
    data.extend_from_slice(&[99, 95]);
    data.extend_from_slice(&[0, 0]);
    data.extend_from_slice(&[94, 98]); // inverted minimum/maximum
    data.resize(49, 0);
    let page = parse_bulk(0x2a, &data, &context("2026-10-05")).unwrap();
    assert_eq!(page.observations.len(), 24);
    assert_eq!(page.observations[0].local_date, "2026-10-04");
    assert_eq!(page.observations[0].value, None);
    assert!(matches!(
        &page.observations[0].details,
        ObservationDetails::Range {
            minimum: Some(95.0),
            maximum: Some(99.0),
            minimum_raw: 95,
            maximum_raw: 99,
            ..
        }
    ));
    assert_eq!(page.observations[1].quality, "missing");
    assert_eq!(page.observations[2].quality, "out_of_range");
    assert_eq!(page.observations[23].minute_of_day, Some(1380));
    data.push(0);
    assert_eq!(
        parse_bulk(0x2a, &data, &context("2026-10-05")),
        Err(HistoryError::Length)
    );
}

#[test]
fn ambiguous_temperature_encoding_does_not_emit_an_importable_value() {
    let page = parse_bulk(0x25, &[0, 30, 100, 129], &context("2026-10-05")).unwrap();
    assert_eq!(page.observations.len(), 3);
    for record in &page.observations {
        assert_eq!(record.value, None);
        assert_eq!(record.minute_of_day, None);
        assert_eq!(record.quality, "temperature_layout_unconfirmed");
        assert_eq!(record.validation_status, "source_only");
    }
    assert_eq!(page.observations[2].raw_value, Some(129));
    assert!(matches!(
        &page.observations[0].details,
        ObservationDetails::RawTemperature {
            day_offset: 0,
            page_index: 0,
            interval_minutes: 30,
            ..
        }
    ));
    let raw = serde_json::to_value(&page.observations[0]).unwrap();
    assert!(raw["unit"].is_null());
    assert_eq!(raw["details"]["payload_hex"], "bc2504008a82001e6481");
    assert_eq!(raw["details"]["decode_version"], "oudmon-bulk-25-raw-v1");
    assert!(matches!(
        &page.observations[1].details,
        ObservationDetails::TemperatureEncoding {
            file_value_celsius: 30.0,
            legacy_value_celsius: Some(42.0),
            ..
        }
    ));
    assert!(matches!(
        &page.observations[2].details,
        ObservationDetails::TemperatureEncoding {
            legacy_value_celsius: None,
            ..
        }
    ));
}

#[test]
fn sleep_crosses_midnight_and_year_boundary_with_original_minutes() {
    let data = sleep(0, 1380, 420, &[(2, 240), (3, 240)]);
    let page = parse_bulk(0x27, &data, &context("2026-01-01")).unwrap();
    let record = &page.observations[0];
    assert_eq!(record.local_date, "2026-01-01");
    assert_eq!(record.minute_of_day, Some(1380));
    assert_eq!(record.value, Some(480.0));
    assert_eq!(record.quality, "valid");
    assert_eq!(record.validation_status, "source_only");
    assert!(matches!(&record.details, ObservationDetails::Sleep {
        start_minute: 1380, end_minute: 420, start_date, end_date, time_interpretation, stages
    } if start_date == "2025-12-31" && end_date == "2026-01-01"
        && time_interpretation == "sdk_start_after_18h_previous_day" && stages.len() == 2));
}

#[test]
fn synthetic_sleep_candidate_for_native_import_contract() {
    let data = sleep(0, 1380, 420, &[(2, 240), (3, 120), (4, 60), (5, 60)]);
    let page = parse_bulk(0x27, &data, &context("2026-10-05")).unwrap();
    let record = &page.observations[0];
    assert_eq!(record.value, Some(480.0));
    assert_eq!(record.quality, "valid");
    let json = serde_json::to_value(record).unwrap();
    assert_eq!(json["details"]["stages"][0]["duration_minutes"], 240);
    assert_eq!(json["details"]["stages"][3]["raw_type"], 5);
    // Only synthetic data, printed with --nocapture for the Kotlin/Python
    // contract test owners to consume the actual Rust serde representation.
    println!("{}", serde_json::to_string(record).unwrap());
}

#[test]
fn sleep_does_not_silently_choose_previous_day_for_ambiguous_18h_boundary() {
    let data = sleep(0, 1080, 420, &[(3, 200)]);
    let page = parse_bulk(0x27, &data, &context("2026-10-05")).unwrap();
    assert_eq!(page.observations[0].value, None);
    assert_eq!(page.observations[0].quality, "ambiguous_time");
    assert!(
        matches!(&page.observations[0].details, ObservationDetails::Sleep {
        start_minute: 1080, end_minute: 420, time_interpretation, ..
    } if time_interpretation == "ambiguous_start_day")
    );
}

#[test]
fn nap_does_not_use_overnight_sdk_rule_and_stages_are_not_reclassified() {
    let data = sleep(0, 1140, 1200, &[(7, 60)]);
    let page = parse_bulk(0x3e, &data, &context("2026-10-05")).unwrap();
    assert_eq!(page.observations[0].value, Some(60.0));
    assert_eq!(page.observations[0].quality, "unknown_sleep_stage");
    assert!(
        matches!(&page.observations[0].details, ObservationDetails::Sleep {
        start_date, end_date, stages, ..
    } if start_date == end_date && stages[0].raw_type == 7)
    );
}

#[test]
fn sleep_length_count_minutes_and_duration_are_checked() {
    let ctx = context("2026-10-05");
    assert_eq!(
        parse_bulk(0x27, &[0], &ctx).unwrap().validation_status,
        "empty_capture_only"
    );
    assert_eq!(parse_bulk(0x27, &[0, 99], &ctx), Err(HistoryError::Length));
    let mut truncated = sleep(0, 60, 120, &[(3, 60)]);
    truncated.pop();
    assert_eq!(
        parse_bulk(0x27, &truncated, &ctx),
        Err(HistoryError::Length)
    );
    let mut count = sleep(0, 60, 120, &[(3, 60)]);
    count[0] = 2;
    assert_eq!(
        parse_bulk(0x27, &count, &ctx),
        Err(HistoryError::InvalidRecord)
    );
    assert_eq!(
        parse_bulk(0x27, &sleep(0, 1440, 120, &[]), &ctx),
        Err(HistoryError::InvalidMinute)
    );
    let mismatch = parse_bulk(0x27, &sleep(0, 60, 120, &[(3, 30)]), &ctx).unwrap();
    assert_eq!(mismatch.observations[0].quality, "duration_mismatch");
}

#[test]
fn steps_assemble_complete_ordered_records_in_little_endian() {
    let mut assembler = CoreHistoryAssembler::new(0x43, context("2026-10-05"));
    let mut start = [0; 14];
    start[0] = 0xf0;
    assert_eq!(assembler.push(&start).unwrap(), None);
    assert_eq!(
        assembler
            .push(&steps_packet([0x26, 0x10, 0x04], 0, 0, 2, 0x1234))
            .unwrap(),
        None
    );
    let page = assembler
        .push(&steps_packet([0x26, 0x10, 0x04], 95, 1, 2, u16::MAX))
        .unwrap()
        .unwrap();
    assert_eq!(page.observations.len(), 2);
    assert_eq!(page.observations[0].value, Some(4660.0));
    assert_eq!(page.observations[0].local_date, "2026-10-04");
    assert_eq!(page.observations[0].day_offset, 1);
    assert_eq!(page.observations[0].source, "oudmon_sdk_core");
    assert_eq!(page.observations[1].minute_of_day, Some(1380));
    assert_eq!(page.observations[1].sample_index, 92);
    assert_eq!(page.observations[1].value, Some(65535.0));
    assert!(matches!(
        page.observations[0].details,
        ObservationDetails::Steps {
            calories_raw: 0x1234,
            distance_raw: 0x5678,
            duration_minutes: Some(60),
            interval_minutes: Some(60),
            ..
        }
    ));
    assert_eq!(assembler.push(&start), Err(HistoryError::AlreadyComplete));
}

#[test]
fn steps_fold_quarter_coordinates_into_nonoverlapping_hourly_sums() {
    let mut assembler = CoreHistoryAssembler::new(0x43, context("2026-10-05"));
    // Packet order remains contiguous even when wire coordinates arrive out of order.
    let samples = [
        (5, [10u16, 2, 3]),
        (4, [20, 5, 7]),
        (7, [30, 11, 13]),
        (6, [40, 17, 19]),
        (8, [50, 23, 29]),
    ];
    let mut page = None;
    for (packet, (slot, [steps, calories, distance])) in samples.into_iter().enumerate() {
        let mut bytes = steps_packet([0x26, 0x10, 0x05], slot, packet as u8, 5, steps);
        bytes[6..8].copy_from_slice(&calories.to_le_bytes());
        bytes[10..12].copy_from_slice(&distance.to_le_bytes());
        page = assembler.push(&bytes).unwrap();
        if packet < 4 {
            assert_eq!(page, None);
        }
    }
    let page = page.unwrap();
    assert_eq!(page.validation_status, "source_only");
    assert_eq!(page.observations.len(), 2);
    let first = &page.observations[0];
    assert_eq!(first.minute_of_day, Some(60));
    assert_eq!(first.sample_index, 4);
    assert_eq!(first.value, Some(100.0));
    assert_eq!(first.raw_value, Some(100));
    assert_eq!(first.source, "oudmon_sdk_core");
    assert_eq!(first.validation_status, "source_only");
    assert_eq!(first.unit.as_deref(), Some("count"));
    assert!(matches!(
        first.details,
        ObservationDetails::Steps {
            calories_raw: 35,
            distance_raw: 42,
            duration_minutes: Some(60),
            interval_minutes: Some(60),
            ..
        }
    ));
    let second = &page.observations[1];
    assert_eq!(second.minute_of_day, Some(120));
    assert_eq!(second.sample_index, 8);
    assert_eq!(second.value, Some(50.0));
    assert_eq!(second.raw_value, Some(50));
    assert!(matches!(
        second.details,
        ObservationDetails::Steps {
            calories_raw: 23,
            distance_raw: 29,
            duration_minutes: Some(60),
            interval_minutes: Some(60),
            ..
        }
    ));
}

#[test]
fn hourly_steps_preserve_unsigned_sums_zero_and_last_hour_boundary() {
    let mut assembler = CoreHistoryAssembler::new(0x43, context("2026-10-05"));
    for slot in 0..4 {
        let mut bytes = steps_packet([0x26, 0x10, 0x05], slot, slot, 5, u16::MAX);
        bytes[6..8].copy_from_slice(&u16::MAX.to_le_bytes());
        bytes[10..12].copy_from_slice(&u16::MAX.to_le_bytes());
        assert_eq!(assembler.push(&bytes).unwrap(), None);
    }
    let mut last = steps_packet([0x26, 0x10, 0x05], 95, 4, 5, 0);
    last[6..8].copy_from_slice(&0u16.to_le_bytes());
    last[10..12].copy_from_slice(&0u16.to_le_bytes());
    let page = assembler.push(&last).unwrap().unwrap();
    assert!(!page.empty);
    assert_eq!(page.observations.len(), 2);
    let sum = &page.observations[0];
    assert_eq!(sum.value, Some(262140.0));
    assert_eq!(sum.raw_value, Some(262140));
    assert_eq!(sum.minute_of_day, Some(0));
    assert_eq!(sum.sample_index, 0);
    assert!(matches!(
        sum.details,
        ObservationDetails::Steps {
            calories_raw: 262140,
            distance_raw: 262140,
            duration_minutes: Some(60),
            interval_minutes: Some(60),
            ..
        }
    ));
    let zero = &page.observations[1];
    assert_eq!(zero.value, Some(0.0));
    assert_eq!(zero.raw_value, Some(0));
    assert_eq!(zero.quality, "valid");
    assert_eq!(zero.minute_of_day, Some(1380));
    assert_eq!(zero.sample_index, 92);
    assert!(matches!(
        zero.details,
        ObservationDetails::Steps {
            calories_raw: 0,
            distance_raw: 0,
            duration_minutes: Some(60),
            interval_minutes: Some(60),
            ..
        }
    ));
}

#[test]
fn steps_empty_is_explicit_and_numeric_zero_is_a_valid_record() {
    let mut empty = CoreHistoryAssembler::new(0x43, context("2026-10-05"));
    let mut marker = [0; 14];
    marker[0] = 0xff;
    assert!(empty.push(&marker).unwrap().unwrap().empty);
    let mut zero = CoreHistoryAssembler::new(0x43, context("2026-10-05"));
    let page = zero
        .push(&steps_packet([0x26, 0x10, 0x05], 0, 0, 1, 0))
        .unwrap()
        .unwrap();
    assert!(!page.empty);
    assert_eq!(page.observations[0].value, Some(0.0));
}

#[test]
fn hourly_steps_still_validate_every_wire_coordinate_before_folding() {
    for invalid in [
        steps_packet([0x26, 0x10, 0x05], 4, 1, 2, 20), // Duplicate coordinate.
        steps_packet([0x26, 0x10, 0x04], 5, 1, 2, 20), // Changed date.
        steps_packet([0x26, 0x10, 0x05], 5, 0, 2, 20), // Repeated packet index.
        steps_packet([0x26, 0x10, 0x05], 5, 1, 3, 20), // Changed packet count.
    ] {
        let mut assembler = CoreHistoryAssembler::new(0x43, context("2026-10-05"));
        let first = steps_packet([0x26, 0x10, 0x05], 4, 0, 2, 10);
        assert_eq!(assembler.push(&first).unwrap(), None);
        assert_eq!(assembler.push(&invalid), Err(HistoryError::Sequence));
        assert_eq!(
            assembler.push(&steps_packet([0x26, 0x10, 0x05], 5, 1, 2, 20)),
            Err(HistoryError::AlreadyComplete)
        );
    }
}

#[test]
fn malformed_step_sequences_poison_partial_data() {
    let mut assembler = CoreHistoryAssembler::new(0x43, context("2026-10-05"));
    let first = steps_packet([0x26, 0x10, 0x05], 0, 0, 2, 10);
    assert_eq!(assembler.push(&first).unwrap(), None);
    assert_eq!(assembler.push(&first), Err(HistoryError::Sequence));
    assert_eq!(
        assembler.push(&steps_packet([0x26, 0x10, 0x05], 1, 1, 2, 10)),
        Err(HistoryError::AlreadyComplete)
    );
    for (data, error) in [
        (
            steps_packet([0x26, 0x1a, 0x05], 0, 0, 1, 10),
            HistoryError::InvalidDate,
        ),
        (
            steps_packet([0x26, 0x02, 0x30], 0, 0, 1, 10),
            HistoryError::InvalidDate,
        ),
        (
            steps_packet([0x26, 0x10, 0x06], 0, 0, 1, 10),
            HistoryError::InvalidDay,
        ),
        (
            steps_packet([0x26, 0x10, 0x05], 96, 0, 1, 10),
            HistoryError::InvalidMinute,
        ),
        (
            steps_packet([0x26, 0x10, 0x05], 0, 1, 2, 10),
            HistoryError::Sequence,
        ),
        (
            steps_packet([0x26, 0x10, 0x05], 0, 0, 0, 10),
            HistoryError::InvalidPage,
        ),
    ] {
        assert_eq!(
            CoreHistoryAssembler::new(0x43, context("2026-10-05")).push(&data),
            Err(error)
        );
    }
}

#[test]
fn today_aggregate_is_separate_running_snapshot_without_fake_sample_time() {
    let data = [1, 2, 3, 0, 4, 5, 0, 6, 7, 0, 8, 9, 1, 2];
    let page = CoreHistoryAssembler::new(0x48, context("2026-10-05"))
        .push(&data)
        .unwrap()
        .unwrap();
    let record = &page.observations[0];
    assert_eq!(record.metric, Metric::RunningSteps);
    assert_eq!(record.value, Some(0x0405 as f64));
    assert_eq!(record.raw_value, Some(0x0405));
    assert_eq!(record.validation_status, "source_only");
    assert_eq!(record.minute_of_day, None);
    assert!(matches!(
        record.details,
        ObservationDetails::RunningSnapshot {
            total_steps: 0x010203,
            calories_raw: 0x0607,
            distance_raw: 0x0809,
            duration_minutes: 0x0102,
        }
    ));
}

#[test]
fn running_snapshot_zero_is_a_measurement_and_bad_lengths_are_rejected() {
    let record = CoreHistoryAssembler::new(0x48, context("2026-10-05"))
        .push(&[0; 14])
        .unwrap()
        .unwrap()
        .observations
        .remove(0);
    assert_eq!(record.metric, Metric::RunningSteps);
    assert_eq!(record.value, Some(0.0));
    assert_eq!(record.quality, "valid");
    for length in [0, 1, 13, 15, 100] {
        assert_eq!(
            CoreHistoryAssembler::new(0x48, context("2026-10-05")).push(&vec![0; length]),
            Err(HistoryError::Length)
        );
    }
}

#[test]
fn context_calendar_and_day_bounds_are_validated_including_leap_years() {
    assert!(validate_context(&context("2024-02-29")).is_ok());
    for date in [
        "2026-02-29",
        "2026-13-01",
        "2026-01-00",
        "2026-1-01",
        "0000-01-01",
        "2026-01-01é",
    ] {
        assert_eq!(
            validate_context(&context(date)),
            Err(HistoryError::InvalidContext)
        );
    }
    let leap = parse_bulk(0x75, &[1, 60, 1, 0, 80], &context("2024-03-01")).unwrap();
    assert_eq!(leap.observations[0].local_date, "2024-02-29");
    assert_eq!(
        parse_bulk(0x75, &[30, 60, 1, 0, 80], &context("2026-10-05")),
        Err(HistoryError::InvalidDay)
    );
    let mut bad_zone = context("2026-10-05");
    bad_zone.timezone = "Europe/Moscow\n".into();
    assert_eq!(
        validate_context(&bad_zone),
        Err(HistoryError::InvalidContext)
    );
}

#[test]
fn automatic_history_scope_excludes_manual_measurement_and_stress_actions() {
    for op in [0x28, 0x49, 0x57, 0x59, 0xff] {
        assert_eq!(
            parse_bulk(op, &[0], &context("2026-10-05")),
            Err(HistoryError::UnsupportedOpcode)
        );
    }
}

#[test]
fn bounded_arbitrary_payloads_never_panic() {
    let ctx = context("2026-10-05");
    let mut state = 0x51ab_437du32;
    for len in 0..260 {
        let bytes: Vec<u8> = (0..len)
            .map(|_| {
                state = state.wrapping_mul(1664525).wrapping_add(1013904223);
                (state >> 24) as u8
            })
            .collect();
        for action in [0x25, 0x27, 0x3e, 0x2a, 0x75, 0x5f, 0x74] {
            let _ = parse_bulk(action, &bytes, &ctx);
        }
        let _ = CoreHistoryAssembler::new(0x43, ctx.clone()).push(&bytes);
    }
}

#[test]
fn serialized_candidates_use_stable_metric_and_details_names() {
    let page = parse_bulk(0x75, &[0, 60, 1, 0, 80], &context("2026-10-05")).unwrap();
    let json = serde_json::to_value(&page.observations[0]).unwrap();
    assert_eq!(json["metric"], "heart_rate");
    assert_eq!(json["details"]["type"], "interval");
    assert_eq!(json["timezone"], "Europe/Moscow");
    assert_eq!(json["validation_status"], "source_only");
}
