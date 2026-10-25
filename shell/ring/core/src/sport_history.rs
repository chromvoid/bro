//! Bounded core 0x13 workout records described by the actual Soul read path.
//! Fields stay raw until the device's epoch, units and sport IDs are qualified.
use crate::codec::hex;
use crate::history::HistoryError;
use serde::{Deserialize, Serialize};

pub const MAX_WORKOUT_RECORDS: usize = 256;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RawDeviceWorkout {
    pub layout: String,
    pub decode_version: String,
    pub date_basis: String,
    pub time_basis: String,
    pub declared_frames: u8,
    pub payload_bytes: u16,
    pub header_hex: String,
    pub payload_hex: String,
    pub raw_start_time: u32,
    pub duration_raw: u32,
    pub sport_type_raw: u32,
    pub steps_raw: u32,
    pub distance_raw: u32,
    pub calories_raw: u32,
    pub heart_rate_count: u32,
    pub heart_rate_bytes: Vec<u8>,
    pub ignored_tail_bytes: u16,
    pub ignored_tail_nonzero: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SportHistoryPage {
    pub records: Vec<RawDeviceWorkout>,
    pub cursor: u32,
    pub empty: bool,
}

#[derive(Debug)]
pub struct SportHistoryAssembler {
    cursor: u32,
    frames: Vec<[u8; 14]>,
    records: Vec<RawDeviceWorkout>,
    last_terminal: Option<[u8; 14]>,
    complete: bool,
    poisoned: bool,
}

impl SportHistoryAssembler {
    pub fn new(cursor: u32) -> Self {
        Self {
            cursor,
            frames: Vec::new(),
            records: Vec::new(),
            last_terminal: None,
            complete: false,
            poisoned: false,
        }
    }

    /// Only the full, explicitly terminated stream can advance the read cursor.
    /// The caller verifies each core frame checksum before passing its payload.
    pub fn push(&mut self, payload: &[u8]) -> Result<Option<SportHistoryPage>, HistoryError> {
        if self.poisoned {
            return Err(HistoryError::Sequence);
        }
        let result = self.push_inner(payload);
        if result.is_err() {
            self.frames.clear();
            self.records.clear();
            self.poisoned = true;
        }
        result
    }

    fn push_inner(&mut self, payload: &[u8]) -> Result<Option<SportHistoryPage>, HistoryError> {
        if self.complete {
            return Err(HistoryError::AlreadyComplete);
        }
        if payload.len() != 14 {
            return Err(HistoryError::Length);
        }
        if payload[0] == 0xff {
            if !self.frames.is_empty() || payload[1..].iter().any(|byte| *byte != 0) {
                return Err(HistoryError::Sequence);
            }
            self.complete = true;
            let records = std::mem::take(&mut self.records);
            return Ok(Some(SportHistoryPage {
                empty: records.is_empty(),
                records,
                cursor: self.cursor,
            }));
        }
        let mut frame = [0; 14];
        frame.copy_from_slice(payload);
        let sequence = usize::from(payload[0]);
        if self.frames.is_empty() && sequence != 0 {
            return if self.last_terminal.as_ref() == Some(&frame) {
                Ok(None)
            } else {
                Err(HistoryError::Sequence)
            };
        }
        if sequence == 0 && !(4..=127).contains(&payload[1]) {
            return Err(HistoryError::InvalidPage);
        }
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
        if self.records.len() >= MAX_WORKOUT_RECORDS {
            return Err(HistoryError::TooLarge);
        }
        self.frames.push(frame);
        if sequence > 0 && self.frames.len() == usize::from(self.frames[0][1]) {
            let record = self.decode_record()?;
            self.cursor = self.cursor.max(record.raw_start_time);
            self.records.push(record);
            self.last_terminal = Some(frame);
            self.frames.clear();
        }
        Ok(None)
    }

    fn decode_record(&self) -> Result<RawDeviceWorkout, HistoryError> {
        let header = self.frames.first().ok_or(HistoryError::Sequence)?;
        let body: Vec<u8> = self
            .frames
            .iter()
            .skip(1)
            .flat_map(|frame| frame[1..].iter().copied())
            .collect();
        if body.len() < 28 {
            return Err(HistoryError::Length);
        }
        let field = |offset| {
            u32::from_le_bytes([
                body[offset],
                body[offset + 1],
                body[offset + 2],
                body[offset + 3],
            ])
        };
        let heart_rate_count = field(24);
        let count = usize::try_from(heart_rate_count).map_err(|_| HistoryError::TooLarge)?;
        if count > body.len() - 28 {
            return Err(HistoryError::Length);
        }
        let tail = &body[28 + count..];
        Ok(RawDeviceWorkout {
            layout: "oudmon-core-13-device-workout-v1".into(),
            decode_version: "oudmon-core-13-device-workout-v1".into(),
            date_basis: "phone_capture_local_date".into(),
            time_basis: "first_phone_capture_day".into(),
            declared_frames: header[1],
            payload_bytes: body.len() as u16,
            header_hex: hex(header),
            payload_hex: hex(&body),
            raw_start_time: field(0),
            duration_raw: field(4),
            sport_type_raw: field(8),
            steps_raw: field(12),
            distance_raw: field(16),
            calories_raw: field(20),
            heart_rate_count,
            heart_rate_bytes: body[28..28 + count].to_vec(),
            ignored_tail_bytes: tail.len() as u16,
            ignored_tail_nonzero: tail.iter().any(|byte| *byte != 0),
        })
    }
}
