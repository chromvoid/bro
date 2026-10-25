//! Synthetic workouts, never a captured device record.
use qring_r20::history::HistoryError;
use qring_r20::sport_history::{MAX_WORKOUT_RECORDS, SportHistoryAssembler};

fn record(start: u32, heart_rate: &[u8]) -> Vec<[u8; 14]> {
    let mut body = Vec::new();
    for field in [start, 600, 17, 321, 2345, 999, heart_rate.len() as u32] {
        body.extend_from_slice(&field.to_le_bytes());
    }
    body.extend_from_slice(heart_rate);
    body.resize(body.len().div_ceil(13) * 13, 0);
    let mut header = [0; 14];
    header[1] = (body.len() / 13 + 1) as u8;
    let mut frames = vec![header];
    for (index, bytes) in body.chunks(13).enumerate() {
        let mut frame = [0; 14];
        frame[0] = (index + 1) as u8;
        frame[1..].copy_from_slice(bytes);
        frames.push(frame);
    }
    frames
}

fn end() -> [u8; 14] {
    let mut frame = [0; 14];
    frame[0] = 0xff;
    frame
}

#[test]
fn complete_records_need_stream_end_and_keep_raw_fields_and_untimed_hr() {
    let mut stream = SportHistoryAssembler::new(100);
    for start in [200, 150] {
        for frame in record(start, &[80, 0, 250]) {
            assert_eq!(stream.push(&frame).unwrap(), None);
        }
    }
    let page = stream.push(&end()).unwrap().unwrap();
    assert!(!page.empty);
    assert_eq!(page.cursor, 200);
    assert_eq!(page.records.len(), 2);
    let raw = &page.records[0];
    assert_eq!(raw.raw_start_time, 200);
    assert_eq!(raw.duration_raw, 600);
    assert_eq!(raw.sport_type_raw, 17);
    assert_eq!(raw.steps_raw, 321);
    assert_eq!(raw.distance_raw, 2345);
    assert_eq!(raw.calories_raw, 999);
    assert_eq!(raw.heart_rate_bytes, [80, 0, 250]);
    assert_eq!(raw.payload_bytes, 39);
    assert_eq!(raw.ignored_tail_bytes, 8);
    assert!(!raw.ignored_tail_nonzero);
    assert_eq!(raw.time_basis, "first_phone_capture_day");
    assert_eq!(raw.date_basis, "phone_capture_local_date");
    let encoded = serde_json::to_value(raw).unwrap();
    for forbidden in [
        "start_at",
        "end_at",
        "unit",
        "duration_seconds",
        "sport_type",
        "hr_interval_seconds",
    ] {
        assert!(encoded.get(forbidden).is_none());
    }
}

#[test]
fn unsigned_bits_zero_records_and_nonzero_tail_are_preserved_without_unit_assumptions() {
    let mut stream = SportHistoryAssembler::new(0);
    let mut frames = record(u32::MAX, &[]);
    frames.last_mut().unwrap()[13] = 0x7e;
    for frame in frames {
        stream.push(&frame).unwrap();
    }
    let page = stream.push(&end()).unwrap().unwrap();
    assert_eq!(page.cursor, u32::MAX);
    assert_eq!(page.records[0].raw_start_time, u32::MAX);
    assert_eq!(page.records[0].heart_rate_count, 0);
    assert_eq!(page.records[0].ignored_tail_bytes, 11);
    assert!(page.records[0].ignored_tail_nonzero);
    assert!(page.records[0].payload_hex.ends_with("7e"));
    let mut empty = SportHistoryAssembler::new(123);
    let page = empty.push(&end()).unwrap().unwrap();
    assert!(page.empty);
    assert_eq!(page.cursor, 123);
    assert!(page.records.is_empty());
}

#[test]
fn incomplete_reordered_conflicting_and_invalid_counts_poison_the_stream() {
    let frames = record(200, &[80]);
    for bad in [frames[2], end()] {
        let mut stream = SportHistoryAssembler::new(0);
        stream.push(&frames[0]).unwrap();
        assert!(stream.push(&bad).is_err());
        assert_eq!(stream.push(&frames[1]), Err(HistoryError::Sequence));
    }
    let mut stream = SportHistoryAssembler::new(0);
    stream.push(&frames[0]).unwrap();
    stream.push(&frames[1]).unwrap();
    assert_eq!(stream.push(&frames[1]).unwrap(), None);
    let mut changed = frames[1];
    changed[2] ^= 1;
    assert_eq!(stream.push(&changed), Err(HistoryError::Sequence));
    for count in [0, 3, 128, 255] {
        let mut stream = SportHistoryAssembler::new(0);
        let mut header = [0; 14];
        header[1] = count;
        assert_eq!(stream.push(&header), Err(HistoryError::InvalidPage));
    }
}

#[test]
fn hr_length_is_bounded_and_an_identical_terminal_repeat_is_not_a_new_record() {
    let mut frames = record(100, &[80]);
    // HR count begins at body byte 24, in packet 2's bytes 12 and 13.
    frames[2][12] = 0xff;
    frames[2][13] = 0xff;
    let mut stream = SportHistoryAssembler::new(0);
    for frame in &frames[..3] {
        stream.push(frame).unwrap();
    }
    assert_eq!(stream.push(&frames[3]), Err(HistoryError::Length));
    let frames = record(100, &[80]);
    let mut stream = SportHistoryAssembler::new(0);
    for frame in &frames {
        stream.push(frame).unwrap();
    }
    assert_eq!(stream.push(frames.last().unwrap()).unwrap(), None);
    assert_eq!(stream.push(&end()).unwrap().unwrap().records.len(), 1);
}

#[test]
fn a_bounded_stream_can_finish_but_cannot_allocate_a_257th_record() {
    let mut stream = SportHistoryAssembler::new(0);
    for start in 0..MAX_WORKOUT_RECORDS {
        for frame in record(start as u32, &[]) {
            stream.push(&frame).unwrap();
        }
    }
    assert_eq!(
        stream.push(&record(999, &[])[0]),
        Err(HistoryError::TooLarge)
    );
}
