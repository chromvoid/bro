//! Narrow R20 legacy HR 15 profile, independently confirmed by SDK and capture.
//! No observation is emitted until every declared frame and its calendar stamp
//! has been validated. A captured initial FF/zero sentinel is explicit no-data.
//! Actual sample meaning remains source_only.
use crate::history::{
    HistoryContext, HistoryError, HistoryPage, Metric, ObservationDetails, candidate,
    wall_calendar_seconds,
};

const FRAMES: usize = 24;
const SAMPLES_PER_DAY: usize = 288;
const FIRST_SAMPLES: usize = 9;
const LATER_SAMPLES: usize = 13;

pub fn request_time(context: &HistoryContext, day: u8) -> Result<u32, HistoryError> {
    wall_calendar_seconds(context, day)
}

/// Fixed 336-byte frame storage; no length from the device allocates a buffer.
/// Header 0 must arrive first. Later frames may be reordered after the header;
/// identical duplicates are ignored and conflicting duplicates poison the query.
pub struct LegacyHeartRateAssembler {
    context: HistoryContext,
    day: u8,
    expected_calendar: u32,
    frames: [Option<[u8; 14]>; FRAMES],
    complete: bool,
    poisoned: bool,
    tail_nonzero: bool,
}
impl LegacyHeartRateAssembler {
    pub fn new(context: HistoryContext, day: u8) -> Result<Self, HistoryError> {
        let expected_calendar = request_time(&context, day)?;
        Ok(Self {
            context,
            day,
            expected_calendar,
            frames: [None; FRAMES],
            complete: false,
            poisoned: false,
            tail_nonzero: false,
        })
    }
    pub fn ignored_tail_nonzero(&self) -> bool {
        self.tail_nonzero
    }
    /// Payload is exactly frame[1..15], after the caller checks the 16-byte sum.
    pub fn push(&mut self, payload: &[u8]) -> Result<Option<HistoryPage>, HistoryError> {
        if self.poisoned {
            return Err(HistoryError::Sequence);
        }
        let result = self.push_inner(payload);
        if result.is_err() {
            self.poisoned = true;
        }
        result
    }
    fn push_inner(&mut self, payload: &[u8]) -> Result<Option<HistoryPage>, HistoryError> {
        if self.complete {
            return Err(HistoryError::AlreadyComplete);
        }
        if payload.len() != 14 {
            return Err(HistoryError::Length);
        }
        // Confirmed R20 no-data shape: first response is FF plus thirteen zero
        // bytes. It has no calendar echo, so the session's single typed pending
        // query supplies the day. Never discard an already started stream.
        if payload[0] == 0xff
            && payload[1..].iter().all(|byte| *byte == 0)
            && self.frames.iter().all(Option::is_none)
        {
            self.complete = true;
            return Ok(Some(HistoryPage {
                observations: Vec::new(),
                empty: true,
                next_page: None,
                day: self.day,
                page: 0,
                validation_status: "empty_capture_only".into(),
            }));
        }
        let sequence = usize::from(payload[0]);
        if sequence >= FRAMES {
            return Err(HistoryError::InvalidPage);
        }
        if sequence == 0 {
            // Other counts have not been validated for this R20 profile.
            // Do not turn an unknown stream into no-data.
            if usize::from(payload[1]) != FRAMES {
                return Err(HistoryError::InvalidPage);
            }
        } else if self.frames[0].is_none() {
            return Err(HistoryError::Sequence);
        }
        if sequence == 1 {
            let stamp = u32::from_le_bytes([payload[1], payload[2], payload[3], payload[4]]);
            if stamp != self.expected_calendar {
                return Err(HistoryError::InvalidDate);
            }
        }
        let mut saved = [0u8; 14];
        saved.copy_from_slice(payload);
        if let Some(previous) = self.frames[sequence] {
            return if previous == saved {
                Ok(None)
            } else {
                Err(HistoryError::Sequence)
            };
        }
        self.frames[sequence] = Some(saved);
        if self.frames.iter().any(Option::is_none) {
            return Ok(None);
        }

        let header = self.frames[0].ok_or(HistoryError::Sequence)?;
        let first = self.frames[1].ok_or(HistoryError::Sequence)?;
        // 9 + 22*13 = 295 physically received bytes; the SDK uses the first288.
        // Never add synthetic zero samples to an incomplete stream.
        let mut samples = [0u8; FIRST_SAMPLES + (FRAMES - 2) * LATER_SAMPLES];
        samples[..FIRST_SAMPLES].copy_from_slice(&first[5..]);
        for sequence in 2..FRAMES {
            let frame = self.frames[sequence].ok_or(HistoryError::Sequence)?;
            let start = FIRST_SAMPLES + (sequence - 2) * LATER_SAMPLES;
            samples[start..start + LATER_SAMPLES].copy_from_slice(&frame[1..]);
        }
        self.tail_nonzero = samples[SAMPLES_PER_DAY..].iter().any(|value| *value != 0);
        let mut observations = Vec::with_capacity(SAMPLES_PER_DAY);
        for (index, raw) in samples[..SAMPLES_PER_DAY].iter().copied().enumerate() {
            let mut observation = candidate(
                Metric::HeartRate,
                0x15,
                self.day,
                index as u32,
                &self.context,
                ObservationDetails::LegacyHeartRate {
                    interval_minutes: 5,
                    declared_frames: FRAMES as u8,
                    header_range_raw: header[2],
                    wire_calendar_seconds: self.expected_calendar,
                    ignored_tail_samples: (samples.len() - SAMPLES_PER_DAY) as u8,
                    ignored_tail_nonzero: self.tail_nonzero,
                },
            )?;
            observation.source = "oudmon_legacy_hr".into();
            observation.minute_of_day = Some((index * 5) as u16);
            observation.raw_value = Some(u32::from(raw));
            observation.unit = Some("bpm".into());
            if raw == 0 {
                observation.quality = "missing".into();
            } else {
                observation.value = Some(f64::from(raw));
            }
            observations.push(observation);
        }
        self.complete = true;
        Ok(Some(HistoryPage {
            observations,
            empty: false,
            next_page: None,
            day: self.day,
            page: 0,
            validation_status: "source_only".into(),
        }))
    }
}
