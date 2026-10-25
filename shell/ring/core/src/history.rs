//! History wire-format candidates for the R20/Oudmon profile.
//!
//! Fresh implementation from observed framing and APK SDK field descriptions;
//! no vendor implementation is included. Nonempty record decoding remains
//! `source_only`: an empty live response validates framing, not sample meaning.
//! Local calendar values deliberately do not imply a UTC instant or a DST fold.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fmt;

const MAX_PAYLOAD: usize = 16 * 1024;
const MAX_DAY: u8 = 29;
const MINUTES_PER_DAY: usize = 1440;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HistoryContext {
    pub local_date: String,
    pub timezone: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Metric {
    Steps,
    RunningSteps,
    ActiveEnergy,
    Distance,
    Workouts,
    Sleep,
    HeartRate,
    Hrv,
    Stress,
    Spo2,
    Temperature,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ObservationCandidate {
    pub metric: Metric,
    pub source: String,
    pub validation_status: String,
    pub opcode: u8,
    pub local_date: String,
    pub timezone: String,
    pub minute_of_day: Option<u16>,
    pub day_offset: u8,
    pub sample_index: u32,
    pub value: Option<f64>,
    pub unit: Option<String>,
    pub raw_value: Option<u32>,
    pub quality: String,
    pub details: ObservationDetails,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ObservationDetails {
    RawDeviceWorkout {
        #[serde(flatten)]
        record: crate::sport_history::RawDeviceWorkout,
    },
    RunningSnapshot {
        total_steps: u32,
        calories_raw: u32,
        distance_raw: u32,
        duration_minutes: u16,
    },
    ActivityQuantitySnapshot {
        total_steps: u32,
        calories_raw: u32,
        distance_raw: u32,
        duration_minutes: u16,
        decode_version: String,
        unit_evidence: QuantityUnitEvidence,
        soul_display_value: f64,
        soul_display_unit: String,
    },
    Steps {
        calories_raw: u32,
        distance_raw: u32,
        running_steps: Option<u32>,
        duration_minutes: Option<u16>,
        interval_minutes: Option<u16>,
    },
    Interval {
        interval_minutes: u16,
        page: u8,
        index_in_page: u16,
    },
    LegacyHeartRate {
        interval_minutes: u16,
        declared_frames: u8,
        header_range_raw: u8,
        wire_calendar_seconds: u32,
        ignored_tail_samples: u8,
        ignored_tail_nonzero: bool,
    },
    CoreScalarHistory {
        interval_minutes: u16,
        declared_frames: u8,
        wire_day_offset: u8,
        received_samples: u16,
        ignored_tail_samples: u16,
        ignored_tail_nonzero: bool,
        decode_version: String,
    },
    Range {
        minimum: Option<f64>,
        maximum: Option<f64>,
        minimum_raw: u8,
        maximum_raw: u8,
        interval_minutes: u16,
    },
    Sleep {
        start_minute: u16,
        end_minute: u16,
        start_date: String,
        end_date: String,
        stages: Vec<SleepStage>,
        time_interpretation: String,
    },
    TemperatureEncoding {
        interval_minutes: u16,
        index_in_page: u16,
        layout_candidates: Vec<String>,
        file_value_celsius: f64,
        legacy_value_celsius: Option<f64>,
    },
    RawTemperature {
        layout: String,
        payload_hex: String,
        day_offset: u8,
        page_index: u8,
        interval_minutes: u16,
        decode_version: String,
        candidates: Vec<String>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct QuantityUnitEvidence {
    pub id: String,
    pub namespace: String,
    pub opcode: u8,
    pub quantity: Metric,
    pub unit: String,
    pub divisor: u32,
    pub source_reference: String,
}

/// Source-described core 0x39/0x37 streams. They are not bulk interval pages.
/// Header 0 declares the frame count and minute spacing; frame 1 echoes the day
/// and carries twelve samples, subsequent frames carry thirteen. Do not emit
/// anything until the complete ordered stream has arrived. The scalar is the
/// device's report: neither an HRV statistic nor a clinical stress scale is known.
#[derive(Debug)]
pub struct CoreScalarHistoryAssembler {
    opcode: u8,
    context: HistoryContext,
    day: u8,
    frames: Vec<[u8; 14]>,
    complete: bool,
    poisoned: bool,
}

impl CoreScalarHistoryAssembler {
    pub fn new(opcode: u8, context: HistoryContext, day: u8) -> Result<Self, HistoryError> {
        if !matches!(opcode, 0x39 | 0x37) {
            return Err(HistoryError::UnsupportedOpcode);
        }
        validate_context(&context)?;
        date_for_day(&context, day)?;
        Ok(Self {
            opcode,
            context,
            day,
            frames: Vec::new(),
            complete: false,
            poisoned: false,
        })
    }

    pub fn push(&mut self, payload: &[u8]) -> Result<Option<HistoryPage>, HistoryError> {
        if self.poisoned {
            return Err(HistoryError::Sequence);
        }
        let result = self.push_inner(payload);
        if result.is_err() {
            self.frames.clear();
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
        if payload[0] == 0xff {
            if payload[1..].iter().any(|byte| *byte != 0) {
                return Err(HistoryError::InvalidRecord);
            }
            if self.frames.is_empty() {
                self.complete = true;
                // SDK sentinel, not a live-capture qualification for this firmware.
                return Ok(Some(page(self.day, 0, true)));
            }
            if self.frames.len() != usize::from(self.frames[0][1]) {
                return Err(HistoryError::Sequence);
            }
            return self.finish().map(Some);
        }
        let sequence = usize::from(payload[0]);
        if sequence == 0 {
            // Vendor fields are signed bytes. Larger declarations are an unknown
            // format, and must not cause an unbounded allocation or a wrapped day.
            if !(2..=127).contains(&payload[1]) {
                return Err(HistoryError::InvalidPage);
            }
            if payload[2] > 127 {
                return Err(HistoryError::InvalidInterval);
            }
            valid_interval(payload[2])?;
        } else {
            let header = self.frames.first().ok_or(HistoryError::Sequence)?;
            if sequence >= usize::from(header[1]) {
                return Err(HistoryError::InvalidPage);
            }
            if sequence == 1 && payload[1] != self.day {
                return Err(HistoryError::InvalidDay);
            }
        }
        let mut frame = [0; 14];
        frame.copy_from_slice(payload);
        if let Some(previous) = self.frames.get(sequence) {
            return if previous == &frame {
                Ok(None)
            } else {
                Err(HistoryError::Sequence)
            };
        }
        if sequence != self.frames.len() {
            return Err(HistoryError::Sequence);
        }
        self.frames.push(frame);
        // A two-frame SDK stream needs its explicit FF terminator: frame 1 is
        // the day header branch, not the vendor's terminal sequence branch.
        if sequence > 1 && self.frames.len() == usize::from(self.frames[0][1]) {
            self.finish().map(Some)
        } else {
            Ok(None)
        }
    }

    fn finish(&mut self) -> Result<HistoryPage, HistoryError> {
        let header = self.frames.first().ok_or(HistoryError::Sequence)?;
        let declared_frames = header[1];
        let interval = valid_interval(header[2])?;
        let mut samples = Vec::with_capacity(usize::from(declared_frames) * 13);
        for (sequence, frame) in self.frames.iter().enumerate().skip(1) {
            samples.extend_from_slice(if sequence == 1 {
                &frame[2..]
            } else {
                &frame[1..]
            });
        }
        let usable = samples.len().min(MINUTES_PER_DAY / usize::from(interval));
        let ignored_tail_samples = (samples.len() - usable) as u16;
        let ignored_tail_nonzero = samples[usable..].iter().any(|value| *value != 0);
        let metric = if self.opcode == 0x39 {
            Metric::Hrv
        } else {
            Metric::Stress
        };
        let unit = if metric == Metric::Hrv { "ms" } else { "index" };
        let mut result = page(self.day, 0, false);
        for (index, raw) in samples[..usable].iter().copied().enumerate() {
            let mut record = candidate(
                metric,
                self.opcode,
                self.day,
                index as u32,
                &self.context,
                ObservationDetails::CoreScalarHistory {
                    interval_minutes: interval,
                    declared_frames,
                    wire_day_offset: self.day,
                    received_samples: samples.len() as u16,
                    ignored_tail_samples,
                    ignored_tail_nonzero,
                    decode_version: "oudmon-core-scalar-v1".into(),
                },
            )?;
            record.source = "oudmon_sdk_core".into();
            record.minute_of_day = Some(index as u16 * interval);
            record.raw_value = Some(u32::from(raw));
            record.unit = Some(unit.into());
            if raw == 0 {
                record.quality = "missing".into();
            } else {
                // Preserve the unsigned byte, including values above 100. The
                // stress channel has no proven percentage or clinical threshold.
                record.value = Some(f64::from(raw));
            }
            result.observations.push(record);
        }
        self.complete = true;
        Ok(result)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SleepStage {
    pub raw_type: u8,
    pub duration_minutes: u16,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HistoryPage {
    pub observations: Vec<ObservationCandidate>,
    /// True only for an explicit no-data response, not a vector of missing samples.
    pub empty: bool,
    pub next_page: Option<u8>,
    pub day: u8,
    pub page: u8,
    pub validation_status: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HistoryError {
    UnsupportedOpcode,
    InvalidContext,
    Length,
    TooLarge,
    InvalidDate,
    InvalidDay,
    InvalidMinute,
    InvalidInterval,
    InvalidPage,
    InvalidRecord,
    Sequence,
    AlreadyComplete,
}

impl fmt::Display for HistoryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{self:?}")
    }
}
impl std::error::Error for HistoryError {}

pub fn validate_context(context: &HistoryContext) -> Result<(), HistoryError> {
    Date::parse(&context.local_date).map_err(|_| HistoryError::InvalidContext)?;
    // The shell supplies a platform-validated IANA zone. Validate a bounded name,
    // but do not pretend this dependency-free core has an IANA zone database.
    let zone = &context.timezone;
    if zone.is_empty()
        || zone.len() > 100
        || !zone.is_ascii()
        || !zone
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"/_+-".contains(&c))
    {
        return Err(HistoryError::InvalidContext);
    }
    Ok(())
}

/// Parse a CRC-checked bulk frame's payload (the six-byte BC header is excluded).
pub fn parse_bulk(
    action: u8,
    payload: &[u8],
    context: &HistoryContext,
) -> Result<HistoryPage, HistoryError> {
    validate_context(context)?;
    if payload.len() > MAX_PAYLOAD {
        return Err(HistoryError::TooLarge);
    }
    match action {
        0x75 => parse_interval(action, Metric::HeartRate, payload, context, 1),
        0x5f => parse_interval(action, Metric::Spo2, payload, context, 1),
        0x74 => parse_interval(action, Metric::Temperature, payload, context, 2),
        0x27 | 0x3e => parse_sleep(action, payload, context),
        0x2a => parse_oxygen_ranges(payload, context),
        0x25 => parse_unconfirmed_temperature(payload, context),
        _ => Err(HistoryError::UnsupportedOpcode),
    }
}

/// Place a page after the actual number of samples received on preceding pages.
/// Counts missing/invalid values as positions too. The caller must have a verified
/// contiguous sequence from page zero; the wire does not specify a page capacity.
/// Returns the cumulative sample count, and leaves the page untouched on error.
pub fn place_interval_page(page: &mut HistoryPage, base_index: u16) -> Result<u16, HistoryError> {
    let total = usize::from(base_index)
        .checked_add(page.observations.len())
        .filter(|total| *total <= MINUTES_PER_DAY)
        .ok_or(HistoryError::TooLarge)?;
    let mut placements = Vec::with_capacity(page.observations.len());
    let mut known_interval = None;
    let mut known_metric = None;
    for (index, record) in page.observations.iter().enumerate() {
        let ObservationDetails::Interval {
            interval_minutes,
            page: record_page,
            index_in_page,
        } = &record.details
        else {
            return Err(HistoryError::InvalidRecord);
        };
        if *interval_minutes == 0
            || !MINUTES_PER_DAY.is_multiple_of(usize::from(*interval_minutes))
            || known_interval.is_some_and(|known| known != *interval_minutes)
            || known_metric.is_some_and(|known| known != record.metric)
            || *record_page != page.page
            || record.day_offset != page.day
            || usize::from(*index_in_page) != index
        {
            return Err(HistoryError::Sequence);
        }
        known_interval = Some(*interval_minutes);
        known_metric = Some(record.metric);
        let global_index = usize::from(base_index) + index;
        let minute = global_index
            .checked_mul(usize::from(*interval_minutes))
            .filter(|minute| *minute < MINUTES_PER_DAY)
            .ok_or(HistoryError::TooLarge)?;
        placements.push((global_index as u32, minute as u16));
    }
    for (record, (index, minute)) in page.observations.iter_mut().zip(placements) {
        record.sample_index = index;
        record.minute_of_day = Some(minute);
        if record.quality == "page_start_unknown" {
            record.quality = match (record.raw_value, record.value) {
                (Some(0), _) => "missing",
                (_, Some(_)) => "valid",
                _ => "out_of_range",
            }
            .into();
        }
    }
    Ok(total as u16)
}

fn page(day: u8, page_index: u8, empty: bool) -> HistoryPage {
    HistoryPage {
        observations: Vec::new(),
        empty,
        next_page: None,
        day,
        page: page_index,
        validation_status: "source_only".into(),
    }
}

fn captured_empty_page(day: u8) -> HistoryPage {
    let mut result = page(day, 0, true);
    result.validation_status = "empty_capture_only".into();
    result
}

pub(crate) fn candidate(
    metric: Metric,
    opcode: u8,
    day: u8,
    index: u32,
    context: &HistoryContext,
    details: ObservationDetails,
) -> Result<ObservationCandidate, HistoryError> {
    let local_date = date_for_day(context, day)?;
    Ok(ObservationCandidate {
        metric,
        source: "oudmon_sdk_bulk".into(),
        validation_status: "source_only".into(),
        opcode,
        local_date,
        timezone: context.timezone.clone(),
        minute_of_day: None,
        day_offset: day,
        sample_index: index,
        value: None,
        unit: None,
        raw_value: None,
        quality: "valid".into(),
        details,
    })
}

fn parse_interval(
    action: u8,
    metric: Metric,
    payload: &[u8],
    context: &HistoryContext,
    width: usize,
) -> Result<HistoryPage, HistoryError> {
    // R20 capture also contains a compact no-data sentinel for these two
    // actions. It carries no day/page identity; the session binds it to the
    // outstanding query, never to an unsolicited notification.
    if payload == [0] && matches!(action, 0x5f | 0x74) {
        return Ok(captured_empty_page(0));
    }
    if payload.len() < 4 {
        return Err(HistoryError::Length);
    }
    let (day, interval, total, current) = (payload[0], payload[1], payload[2], payload[3]);
    date_for_day(context, day)?;
    if total == 0 {
        if current != 0 || payload.len() != 4 {
            return Err(HistoryError::InvalidPage);
        }
        return Ok(captured_empty_page(day));
    }
    if current >= total {
        return Err(HistoryError::InvalidPage);
    }
    let interval = valid_interval(interval)?;
    if !(payload.len() - 4).is_multiple_of(width) {
        return Err(HistoryError::Length);
    }
    let samples = &payload[4..];
    let count = samples.len() / width;
    if count == 0 {
        return Err(HistoryError::InvalidRecord);
    }
    if count > MINUTES_PER_DAY / usize::from(interval) {
        return Err(HistoryError::TooLarge);
    }
    let mut result = page(day, current, false);
    if current < total - 1 {
        result.next_page = Some(current + 1);
    }
    for (index, chunk) in samples.chunks_exact(width).enumerate() {
        let raw = if width == 2 {
            u32::from(le16(chunk))
        } else {
            u32::from(chunk[0])
        };
        let mut record = candidate(
            metric,
            action,
            day,
            (u32::from(current) << 16) | index as u32,
            context,
            ObservationDetails::Interval {
                interval_minutes: interval,
                page: current,
                index_in_page: index as u16,
            },
        )?;
        record.raw_value = Some(raw);
        let (value, unit, valid) = match metric {
            Metric::HeartRate => (f64::from(raw), "bpm", (1..=255).contains(&raw)),
            Metric::Spo2 => (f64::from(raw), "percent", (1..=100).contains(&raw)),
            Metric::Temperature => (f64::from(raw) / 100.0, "celsius", (1..=6000).contains(&raw)),
            _ => return Err(HistoryError::UnsupportedOpcode),
        };
        record.unit = Some(unit.into());
        if raw == 0 {
            record.quality = "missing".into();
        } else if valid {
            record.value = Some(value);
        } else {
            record.quality = "out_of_range".into();
        }
        // Subsequent page capacity is not specified by the wire header. Preserve
        // its page/index rather than inventing an absolute offset from this size.
        if current == 0 {
            record.minute_of_day = Some((index * usize::from(interval)) as u16);
        }
        result.observations.push(record);
    }
    Ok(result)
}

fn parse_oxygen_ranges(
    payload: &[u8],
    context: &HistoryContext,
) -> Result<HistoryPage, HistoryError> {
    if payload.is_empty() {
        return Ok(page(0, 0, true));
    }
    if !payload.len().is_multiple_of(49) {
        return Err(HistoryError::Length);
    }
    if payload.len() / 49 > 30 {
        return Err(HistoryError::TooLarge);
    }
    let mut result = page(payload[0], 0, false);
    let mut days = Vec::new();
    for day_data in payload.chunks_exact(49) {
        let day = day_data[0];
        date_for_day(context, day)?;
        if days.contains(&day) {
            return Err(HistoryError::Sequence);
        }
        days.push(day);
        for (hour, range) in day_data[1..].chunks_exact(2).enumerate() {
            let (maximum_raw, minimum_raw) = (range[0], range[1]);
            let is_missing = maximum_raw == 0 && minimum_raw == 0;
            let valid = minimum_raw > 0 && maximum_raw <= 100 && minimum_raw <= maximum_raw;
            let mut record = candidate(
                Metric::Spo2,
                0x2a,
                day,
                hour as u32,
                context,
                ObservationDetails::Range {
                    minimum: valid.then_some(f64::from(minimum_raw)),
                    maximum: valid.then_some(f64::from(maximum_raw)),
                    minimum_raw,
                    maximum_raw,
                    interval_minutes: 60,
                },
            )?;
            record.minute_of_day = Some((hour * 60) as u16);
            record.unit = Some("percent".into());
            record.quality = if is_missing {
                "missing"
            } else if valid {
                "valid"
            } else {
                "out_of_range"
            }
            .into();
            // Min/max are a range, not a scalar average or a single measurement.
            result.observations.push(record);
        }
    }
    Ok(result)
}

fn parse_unconfirmed_temperature(
    payload: &[u8],
    context: &HistoryContext,
) -> Result<HistoryPage, HistoryError> {
    if payload.len() < 2 {
        return Err(HistoryError::Length);
    }
    let day = payload[0];
    date_for_day(context, day)?;
    let interval = valid_interval(payload[1])?;
    if payload.len() - 2 > MINUTES_PER_DAY / usize::from(interval) {
        return Err(HistoryError::TooLarge);
    }
    let mut result = page(day, 0, false);
    let checked_frame =
        crate::codec::bulk_command(0x25, payload).map_err(|_| HistoryError::InvalidRecord)?;
    let mut raw_report = candidate(
        Metric::Temperature,
        0x25,
        day,
        0,
        context,
        ObservationDetails::RawTemperature {
            layout: "oudmon-temperature-25-ambiguous".into(),
            payload_hex: crate::codec::hex(&checked_frame),
            day_offset: day,
            page_index: 0,
            interval_minutes: interval,
            decode_version: "oudmon-bulk-25-raw-v1".into(),
            candidates: vec![
                "file_u8_tenths_plus20".into(),
                "legacy_u8_tenths_plus32_rle".into(),
            ],
        },
    )?;
    raw_report.quality = "temperature_layout_unconfirmed".into();
    result.observations.push(raw_report);
    for (index, raw) in payload[2..].iter().copied().enumerate() {
        let mut record = candidate(
            Metric::Temperature,
            0x25,
            day,
            index as u32,
            context,
            ObservationDetails::TemperatureEncoding {
                interval_minutes: interval,
                index_in_page: index as u16,
                layout_candidates: vec![
                    "file_u8_tenths_plus20".into(),
                    "legacy_u8_tenths_plus32_rle".into(),
                ],
                file_value_celsius: f64::from(raw) / 10.0 + 20.0,
                legacy_value_celsius: (raw < 128).then_some(f64::from(raw) / 10.0 + 32.0),
            },
        )?;
        record.raw_value = Some(u32::from(raw));
        record.unit = Some("celsius".into());
        record.quality = "temperature_layout_unconfirmed".into();
        // The other SDK layout has RLE: this byte's index is not proven to be a
        // sample index. Leave its time unresolved, and never import a guessed value.
        result.observations.push(record);
    }
    Ok(result)
}

fn parse_sleep(
    action: u8,
    payload: &[u8],
    context: &HistoryContext,
) -> Result<HistoryPage, HistoryError> {
    let Some(&total_days) = payload.first() else {
        return Err(HistoryError::Length);
    };
    if total_days == 0 {
        if payload.len() != 1 {
            return Err(HistoryError::Length);
        }
        return Ok(captured_empty_page(0));
    }
    if total_days > 30 {
        return Err(HistoryError::TooLarge);
    }
    let mut result = page(0, 0, false);
    let mut cursor = 1;
    let mut days = Vec::new();
    while cursor < payload.len() {
        if payload.len() - cursor < 2 {
            return Err(HistoryError::Length);
        }
        let day = payload[cursor];
        let len = usize::from(payload[cursor + 1]) + 2;
        if len < 6 || !len.is_multiple_of(2) || len > payload.len() - cursor {
            return Err(HistoryError::Length);
        }
        if days.contains(&day) {
            return Err(HistoryError::Sequence);
        }
        days.push(day);
        let record = &payload[cursor..cursor + len];
        let (start_minute, end_minute) = (le16(&record[2..4]), le16(&record[4..6]));
        if start_minute >= 1440 || end_minute >= 1440 {
            return Err(HistoryError::InvalidMinute);
        }
        let end_date = date_for_day(context, day)?;
        let previous_day = action == 0x27 && start_minute > 1080;
        let start_date = if previous_day {
            Date::parse(&end_date)?.subtract(1)?.format()
        } else {
            end_date.clone()
        };
        let elapsed = if previous_day {
            1440 - start_minute + end_minute
        } else {
            end_minute.saturating_sub(start_minute)
        };
        let ambiguous = !previous_day && start_minute > end_minute;
        let stages: Vec<SleepStage> = record[6..]
            .chunks_exact(2)
            .map(|pair| SleepStage {
                raw_type: pair[0],
                duration_minutes: u16::from(pair[1]),
            })
            .collect();
        let stage_minutes: u32 = stages
            .iter()
            .map(|stage| u32::from(stage.duration_minutes))
            .sum();
        if stage_minutes > 1440 {
            return Err(HistoryError::InvalidRecord);
        }
        let unknown_stages = stages.iter().any(|stage| !matches!(stage.raw_type, 2..=5));
        let interpretation = if ambiguous {
            "ambiguous_start_day"
        } else if previous_day {
            "sdk_start_after_18h_previous_day"
        } else {
            "sdk_same_local_day"
        };
        let mut observation = candidate(
            Metric::Sleep,
            action,
            day,
            result.observations.len() as u32,
            context,
            ObservationDetails::Sleep {
                start_minute,
                end_minute,
                start_date,
                end_date,
                stages,
                time_interpretation: interpretation.into(),
            },
        )?;
        observation.minute_of_day = Some(start_minute);
        observation.unit = Some("minutes".into());
        observation.quality = if ambiguous {
            "ambiguous_time"
        } else if unknown_stages {
            "unknown_sleep_stage"
        } else if stage_minutes != u32::from(elapsed) {
            "duration_mismatch"
        } else {
            "valid"
        }
        .into();
        // The scalar denotes the observed time window, including awake stages;
        // stage types remain raw so consumers can derive their own supported totals.
        if !ambiguous {
            observation.value = Some(f64::from(elapsed));
        }
        if result.observations.is_empty() {
            result.day = day;
        }
        result.observations.push(observation);
        cursor += len;
    }
    if result.observations.len() != usize::from(total_days) {
        return Err(HistoryError::InvalidRecord);
    }
    Ok(result)
}

#[derive(Debug)]
struct StepsWireRecord {
    local_date: String,
    day_offset: u8,
    slot: u8,
    steps: u32,
    calories_raw: u32,
    distance_raw: u32,
}

/// Reassembles 0x43 detail records and reports hourly sums like the Soul client.
/// The quarter-hour wire coordinate locates a record; it does not declare its duration.
/// 0x48 is a one-frame today aggregate.
/// The caller supplies only the fourteen core payload bytes after checksum checking.
#[derive(Debug)]
pub struct CoreHistoryAssembler {
    opcode: u8,
    context: HistoryContext,
    steps: Vec<StepsWireRecord>,
    total: Option<u8>,
    started: bool,
    complete: bool,
}

impl CoreHistoryAssembler {
    pub fn new(op: u8, context: HistoryContext) -> Self {
        Self {
            opcode: op,
            context,
            steps: Vec::new(),
            total: None,
            started: false,
            complete: false,
        }
    }

    pub fn push(&mut self, payload: &[u8]) -> Result<Option<HistoryPage>, HistoryError> {
        let result = self.push_inner(payload);
        // Invalid streams cannot later produce a receipt from retained partial data.
        if result.is_err() {
            self.steps.clear();
            self.complete = true;
        }
        result
    }

    fn push_inner(&mut self, payload: &[u8]) -> Result<Option<HistoryPage>, HistoryError> {
        if self.complete {
            return Err(HistoryError::AlreadyComplete);
        }
        validate_context(&self.context)?;
        if payload.len() != 14 {
            return Err(HistoryError::Length);
        }
        if self.opcode == 0x48 {
            let running_steps = be24(&payload[3..6]);
            let total_steps = be24(&payload[..3]);
            let calories_raw = be24(&payload[6..9]);
            let distance_raw = be24(&payload[9..12]);
            let duration_minutes = u16::from_be_bytes([payload[12], payload[13]]);
            let mut record = candidate(
                Metric::RunningSteps,
                self.opcode,
                0,
                0,
                &self.context,
                ObservationDetails::RunningSnapshot {
                    total_steps,
                    calories_raw,
                    distance_raw,
                    duration_minutes,
                },
            )?;
            record.source = "oudmon_sdk_core".into();
            record.raw_value = Some(running_steps);
            record.value = Some(f64::from(running_steps));
            record.unit = Some("count".into());
            let mut result = page(0, 0, false);
            result.observations.push(record);
            for (metric, raw, divisor, unit, display_unit, display, reference) in [
                (
                    Metric::ActiveEnergy,
                    calories_raw,
                    1000,
                    "kcal",
                    "kcal",
                    f64::from(calories_raw / 1000),
                    "soul-1.0.1214:sib.C:dex:0x0397,0x039b",
                ),
                (
                    Metric::Distance,
                    distance_raw,
                    1,
                    "m",
                    "km",
                    f64::from(distance_raw) / 1000.0,
                    "soul-1.0.1214:sib.C:dex:0x070c,0x0712,float@0x0027",
                ),
            ] {
                let mut quantity = candidate(
                    metric,
                    self.opcode,
                    0,
                    0,
                    &self.context,
                    ObservationDetails::ActivityQuantitySnapshot {
                        total_steps,
                        calories_raw,
                        distance_raw,
                        duration_minutes,
                        decode_version: "oudmon-core-48-quantity-v1".into(),
                        unit_evidence: QuantityUnitEvidence {
                            id: "soul-1.0.1214-core-48-quantity-v1".into(),
                            namespace: "core".into(),
                            opcode: self.opcode,
                            quantity: metric,
                            unit: unit.into(),
                            divisor,
                            source_reference: reference.into(),
                        },
                        soul_display_value: display,
                        soul_display_unit: display_unit.into(),
                    },
                )?;
                quantity.source = "oudmon_sdk_core".into();
                quantity.raw_value = Some(raw);
                quantity.value = Some(f64::from(raw) / f64::from(divisor));
                quantity.unit = Some(unit.into());
                result.observations.push(quantity);
            }
            self.complete = true;
            return Ok(Some(result));
        }
        if self.opcode != 0x43 {
            return Err(HistoryError::UnsupportedOpcode);
        }
        if payload[0] == 0xff {
            if self.started || !self.steps.is_empty() {
                return Err(HistoryError::Sequence);
            }
            self.complete = true;
            return Ok(Some(page(0, 0, true)));
        }
        if payload[0] == 0xf0 {
            if self.started {
                return Err(HistoryError::Sequence);
            }
            self.started = true;
            return Ok(None);
        }
        // Some firmware omits F0. Its data still carries an explicit packet index.
        let date = Date::new(
            2000 + u16::from(bcd(payload[0])?),
            bcd(payload[1])?,
            bcd(payload[2])?,
        )?;
        let day = Date::parse(&self.context.local_date)?.offset_to(date)?;
        if payload[3] > 95 {
            return Err(HistoryError::InvalidMinute);
        }
        let (packet, total) = (payload[4], payload[5]);
        if total == 0 || total > 96 || packet >= total {
            return Err(HistoryError::InvalidPage);
        }
        if packet as usize != self.steps.len() || self.total.is_some_and(|known| known != total) {
            return Err(HistoryError::Sequence);
        }
        self.total = Some(total);
        self.started = true;
        if self
            .steps
            .iter()
            .any(|record| record.local_date != date.format() || record.slot == payload[3])
        {
            return Err(HistoryError::Sequence);
        }
        self.steps.push(StepsWireRecord {
            local_date: date.format(),
            day_offset: day,
            slot: payload[3],
            steps: u32::from(le16(&payload[8..10])),
            calories_raw: u32::from(le16(&payload[6..8])),
            distance_raw: u32::from(le16(&payload[10..12])),
        });
        if packet == total - 1 {
            let mut result = page(day, 0, false);
            result.observations = self.hourly_steps()?;
            self.complete = true;
            Ok(Some(result))
        } else {
            Ok(None)
        }
    }

    fn hourly_steps(&mut self) -> Result<Vec<ObservationCandidate>, HistoryError> {
        let mut hours: BTreeMap<u8, StepsWireRecord> = BTreeMap::new();
        for record in std::mem::take(&mut self.steps) {
            let hour = record.slot / 4;
            if let Some(sum) = hours.get_mut(&hour) {
                // Unique wire slots cap each hourly sum at four unsigned 16-bit values.
                sum.steps += record.steps;
                sum.calories_raw += record.calories_raw;
                sum.distance_raw += record.distance_raw;
            } else {
                hours.insert(hour, record);
            }
        }
        hours
            .into_iter()
            .map(|(hour, sum)| {
                let mut record = candidate(
                    Metric::Steps,
                    self.opcode,
                    sum.day_offset,
                    u32::from(hour) * 4,
                    &self.context,
                    ObservationDetails::Steps {
                        calories_raw: sum.calories_raw,
                        distance_raw: sum.distance_raw,
                        running_steps: None,
                        duration_minutes: Some(60),
                        interval_minutes: Some(60),
                    },
                )?;
                record.source = "oudmon_sdk_core".into();
                record.local_date = sum.local_date;
                record.minute_of_day = Some(u16::from(hour) * 60);
                record.raw_value = Some(sum.steps);
                record.value = Some(f64::from(sum.steps));
                record.unit = Some("count".into());
                Ok(record)
            })
            .collect()
    }
}

fn valid_interval(raw: u8) -> Result<u16, HistoryError> {
    if raw == 0 || !MINUTES_PER_DAY.is_multiple_of(usize::from(raw)) {
        return Err(HistoryError::InvalidInterval);
    }
    Ok(u16::from(raw))
}
fn le16(bytes: &[u8]) -> u16 {
    u16::from_le_bytes([bytes[0], bytes[1]])
}
fn be24(bytes: &[u8]) -> u32 {
    (u32::from(bytes[0]) << 16) | (u32::from(bytes[1]) << 8) | u32::from(bytes[2])
}
fn bcd(raw: u8) -> Result<u8, HistoryError> {
    if raw >> 4 > 9 || raw & 15 > 9 {
        return Err(HistoryError::InvalidDate);
    }
    Ok((raw >> 4) * 10 + (raw & 15))
}
pub(crate) fn date_for_day(context: &HistoryContext, day: u8) -> Result<String, HistoryError> {
    if day > MAX_DAY {
        return Err(HistoryError::InvalidDay);
    }
    Ok(Date::parse(&context.local_date)?.subtract(day)?.format())
}

/// Calendar encoding used by legacy 15: the ring's local date is encoded as
/// midnight on a UTC-shaped calendar. It is not a timezone-resolved instant.
pub(crate) fn wall_calendar_seconds(
    context: &HistoryContext,
    day: u8,
) -> Result<u32, HistoryError> {
    validate_context(context)?;
    let date = Date::parse(&date_for_day(context, day)?)?;
    if date.year < 1970 {
        return Err(HistoryError::InvalidDate);
    }
    let mut days = 0u64;
    for year in 1970..date.year {
        days += if Date::month_length(year, 2) == 29 {
            366
        } else {
            365
        };
    }
    for month in 1..date.month {
        days += u64::from(Date::month_length(date.year, month));
    }
    days += u64::from(date.day - 1);
    u32::try_from(days * 86400).map_err(|_| HistoryError::InvalidDate)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Date {
    year: u16,
    month: u8,
    day: u8,
}
impl Date {
    fn new(year: u16, month: u8, day: u8) -> Result<Self, HistoryError> {
        if !(1..=9999).contains(&year)
            || !(1..=12).contains(&month)
            || day == 0
            || day > Self::month_length(year, month)
        {
            return Err(HistoryError::InvalidDate);
        }
        Ok(Self { year, month, day })
    }
    fn parse(text: &str) -> Result<Self, HistoryError> {
        let bytes = text.as_bytes();
        if bytes.len() != 10 || bytes[4] != b'-' || bytes[7] != b'-' {
            return Err(HistoryError::InvalidDate);
        }
        let number = |range: std::ops::Range<usize>| -> Result<u16, HistoryError> {
            let mut result = 0;
            for byte in &bytes[range] {
                if !byte.is_ascii_digit() {
                    return Err(HistoryError::InvalidDate);
                }
                result = result * 10 + u16::from(byte - b'0');
            }
            Ok(result)
        };
        Self::new(number(0..4)?, number(5..7)? as u8, number(8..10)? as u8)
    }
    fn month_length(year: u16, month: u8) -> u8 {
        match month {
            4 | 6 | 9 | 11 => 30,
            2 => {
                if year.is_multiple_of(4) && (!year.is_multiple_of(100) || year.is_multiple_of(400))
                {
                    29
                } else {
                    28
                }
            }
            _ => 31,
        }
    }
    fn subtract(mut self, days: u8) -> Result<Self, HistoryError> {
        for _ in 0..days {
            if self.day > 1 {
                self.day -= 1;
            } else {
                if self.month > 1 {
                    self.month -= 1;
                } else {
                    self.year = self
                        .year
                        .checked_sub(1)
                        .filter(|year| *year > 0)
                        .ok_or(HistoryError::InvalidDate)?;
                    self.month = 12;
                }
                self.day = Self::month_length(self.year, self.month);
            }
        }
        Ok(self)
    }
    fn offset_to(self, older: Self) -> Result<u8, HistoryError> {
        for day in 0..=MAX_DAY {
            if self.subtract(day)? == older {
                return Ok(day);
            }
        }
        Err(HistoryError::InvalidDay)
    }
    fn format(self) -> String {
        format!("{:04}-{:02}-{:02}", self.year, self.month, self.day)
    }
}
