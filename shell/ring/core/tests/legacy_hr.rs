//! Synthetic frames only: no private ring samples or peer addresses.
use qring_r20::history::{HistoryContext, HistoryError, Metric, ObservationDetails};
use qring_r20::legacy_hr::{LegacyHeartRateAssembler, request_time};

fn context() -> HistoryContext {
    HistoryContext {
        local_date: "2000-01-02".into(),
        timezone: "Europe/Moscow".into(),
    }
}
fn frames(day: u8, samples: &[u8; 295]) -> Vec<[u8; 14]> {
    let mut result = vec![[0; 14]; 24];
    result[0][1] = 24;
    result[0][2] = 5;
    result[1][0] = 1;
    result[1][1..5].copy_from_slice(&request_time(&context(), day).unwrap().to_le_bytes());
    result[1][5..].copy_from_slice(&samples[..9]);
    for (index, frame) in result.iter_mut().enumerate().skip(2) {
        frame[0] = index as u8;
        let start = 9 + (index - 2) * 13;
        frame[1..].copy_from_slice(&samples[start..start + 13]);
    }
    result
}

#[test]
fn request_is_wall_calendar_midnight_and_not_resolved_timezone() {
    assert_eq!(request_time(&context(), 1).unwrap(), 946_684_800);
    let mut other = context();
    other.timezone = "America/New_York".into();
    assert_eq!(
        request_time(&context(), 0).unwrap(),
        request_time(&other, 0).unwrap()
    );
    assert!(request_time(&context(), 30).is_err());
    assert_eq!(
        request_time(
            &HistoryContext {
                local_date: "2000-03-01".into(),
                timezone: "Europe/Moscow".into()
            },
            1
        )
        .unwrap(),
        951_782_400
    );
}

#[test]
fn complete_past_day_needs_no_ff_and_emits_physical_samples_only() {
    let mut raw = [0; 295];
    raw[0] = 73;
    raw[10] = 74;
    raw[287] = 75;
    let stream = frames(1, &raw);
    let mut parser = LegacyHeartRateAssembler::new(context(), 1).unwrap();
    for frame in &stream[..23] {
        assert!(parser.push(frame).unwrap().is_none());
    }
    let page = parser.push(&stream[23]).unwrap().unwrap();
    assert_eq!(page.observations.len(), 288);
    assert_eq!(page.day, 1);
    assert!(!page.empty);
    assert_eq!(page.validation_status, "source_only");
    assert_eq!(page.observations[0].metric, Metric::HeartRate);
    assert_eq!(page.observations[0].local_date, "2000-01-01");
    assert_eq!(page.observations[0].source, "oudmon_legacy_hr");
    assert_eq!(page.observations[10].sample_index, 10);
    assert_eq!(page.observations[10].minute_of_day, Some(50));
    assert_eq!(page.observations[287].minute_of_day, Some(1435));
    assert_eq!(page.observations[287].value, Some(75.0));
    assert_eq!(page.observations[1].value, None);
    assert_eq!(page.observations[1].raw_value, Some(0));
    assert_eq!(page.observations[1].quality, "missing");
}

#[test]
fn today_uses_same_physical_layout_without_padding_missing_tail() {
    let mut raw = [0; 295];
    raw[287] = 77;
    let mut parser = LegacyHeartRateAssembler::new(context(), 0).unwrap();
    let mut page = None;
    for frame in frames(0, &raw) {
        page = parser.push(&frame).unwrap().or(page);
    }
    assert_eq!(page.unwrap().observations[287].value, Some(77.0));
    // Future-position filtering is the Android timezone/now gate, not zeroing.
}

#[test]
fn reordered_frames_after_header_and_identical_duplicates_are_bounded() {
    assert!(std::mem::size_of::<LegacyHeartRateAssembler>() < 1024);
    let stream = frames(1, &[0; 295]);
    let mut parser = LegacyHeartRateAssembler::new(context(), 1).unwrap();
    assert!(parser.push(&stream[0]).unwrap().is_none());
    assert!(parser.push(&stream[0]).unwrap().is_none());
    for index in (2..24).rev() {
        assert!(parser.push(&stream[index]).unwrap().is_none());
        assert!(parser.push(&stream[index]).unwrap().is_none());
    }
    assert_eq!(
        parser.push(&stream[1]).unwrap().unwrap().observations.len(),
        288
    );
}

#[test]
fn terminal_index_alone_never_completes_a_missing_middle_frame() {
    let stream = frames(1, &[0; 295]);
    let mut parser = LegacyHeartRateAssembler::new(context(), 1).unwrap();
    for (index, frame) in stream.iter().enumerate() {
        if index != 10 {
            assert!(parser.push(frame).unwrap().is_none());
        }
    }
    assert!(parser.push(&stream[10]).unwrap().is_some());
}

#[test]
fn conflicting_duplicate_and_stale_calendar_poison_the_stream() {
    let stream = frames(1, &[0; 295]);
    let mut parser = LegacyHeartRateAssembler::new(context(), 1).unwrap();
    parser.push(&stream[0]).unwrap();
    parser.push(&stream[2]).unwrap();
    let mut changed = stream[2];
    changed[1] = 71;
    assert_eq!(parser.push(&changed), Err(HistoryError::Sequence));
    assert_eq!(parser.push(&stream[1]), Err(HistoryError::Sequence));
    let mut stale = LegacyHeartRateAssembler::new(context(), 0).unwrap();
    stale.push(&stream[0]).unwrap();
    assert_eq!(stale.push(&stream[1]), Err(HistoryError::InvalidDate));
}

#[test]
fn unknown_count_dirty_ff_short_frame_and_headerless_data_are_not_empty_history() {
    let stream = frames(1, &[0; 295]);
    for count in [0, 2, 23, 25, 255] {
        let mut parser = LegacyHeartRateAssembler::new(context(), 1).unwrap();
        let mut header = stream[0];
        header[1] = count;
        assert_eq!(parser.push(&header), Err(HistoryError::InvalidPage));
    }
    let mut parser = LegacyHeartRateAssembler::new(context(), 1).unwrap();
    assert_eq!(parser.push(&[0xff; 14]), Err(HistoryError::InvalidPage));
    let mut parser = LegacyHeartRateAssembler::new(context(), 1).unwrap();
    assert_eq!(parser.push(&[0; 13]), Err(HistoryError::Length));
    let mut parser = LegacyHeartRateAssembler::new(context(), 1).unwrap();
    assert_eq!(parser.push(&stream[1]), Err(HistoryError::Sequence));
}

#[test]
fn captured_initial_ff_and_zero_padding_is_explicit_empty_not_a_zero_sample() {
    let frame = qring_r20::codec::unhex("15ff0000000000000000000000000014", 16).unwrap();
    let decoded = qring_r20::codec::decode_core(&frame).unwrap();
    let mut parser = LegacyHeartRateAssembler::new(context(), 2).unwrap();
    let page = parser.push(&decoded.payload).unwrap().unwrap();
    assert!(page.empty);
    assert_eq!(page.day, 2);
    assert!(page.observations.is_empty());
    assert_eq!(page.validation_status, "empty_capture_only");
    assert_eq!(
        parser.push(&decoded.payload),
        Err(HistoryError::AlreadyComplete)
    );
}

#[test]
fn ff_does_not_discard_a_partial_stream_or_accept_unknown_padding() {
    let mut empty = [0u8; 14];
    empty[0] = 0xff;
    let mut partial = LegacyHeartRateAssembler::new(context(), 1).unwrap();
    partial.push(&frames(1, &[0; 295])[0]).unwrap();
    assert_eq!(partial.push(&empty), Err(HistoryError::InvalidPage));
    let mut dirty = LegacyHeartRateAssembler::new(context(), 1).unwrap();
    empty[13] = 1;
    assert_eq!(dirty.push(&empty), Err(HistoryError::InvalidPage));
}

#[test]
fn unknown_header_range_is_metadata_and_nonzero_tail_is_not_an_extra_position() {
    let mut raw = [0; 295];
    raw[294] = 80;
    let mut stream = frames(1, &raw);
    stream[0][2] = 7;
    let mut parser = LegacyHeartRateAssembler::new(context(), 1).unwrap();
    let mut page = None;
    for frame in stream {
        page = parser.push(&frame).unwrap().or(page);
    }
    let page = page.unwrap();
    assert_eq!(page.observations.len(), 288);
    assert!(parser.ignored_tail_nonzero());
    assert!(matches!(
        page.observations[0].details,
        ObservationDetails::LegacyHeartRate {
            interval_minutes: 5,
            header_range_raw: 7,
            ignored_tail_samples: 7,
            ignored_tail_nonzero: true,
            ..
        }
    ));
    assert!(
        page.observations
            .iter()
            .all(|observation| observation.value.is_none())
    );
}
