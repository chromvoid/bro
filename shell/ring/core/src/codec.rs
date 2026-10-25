use std::fmt;

pub const MAX_BULK_PAYLOAD: usize = 16 * 1024;
pub const MAX_NOTIFICATION_BYTES: usize = 32 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodeError {
    Length,
    Checksum,
    Crc,
    Header,
    TooLarge,
    InvalidHex,
}
impl fmt::Display for DecodeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{self:?}")
    }
}
impl std::error::Error for DecodeError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CoreFrame {
    pub opcode: u8,
    pub payload: [u8; 14],
}

pub fn core_command(opcode: u8, payload: &[u8]) -> Result<[u8; 16], DecodeError> {
    if payload.len() > 14 {
        return Err(DecodeError::Length);
    }
    let mut frame = [0; 16];
    frame[0] = opcode;
    frame[1..1 + payload.len()].copy_from_slice(payload);
    frame[15] = frame[..15].iter().fold(0u8, |s, n| s.wrapping_add(*n));
    Ok(frame)
}

pub fn decode_core(bytes: &[u8]) -> Result<CoreFrame, DecodeError> {
    if bytes.len() != 16 {
        return Err(DecodeError::Length);
    }
    let checksum = bytes[..15].iter().fold(0u8, |s, n| s.wrapping_add(*n));
    if checksum != bytes[15] {
        return Err(DecodeError::Checksum);
    }
    let mut payload = [0; 14];
    payload.copy_from_slice(&bytes[1..15]);
    // Preserve the high bit: it is not a universally established error marker.
    Ok(CoreFrame {
        opcode: bytes[0],
        payload,
    })
}

pub fn crc16_modbus(bytes: &[u8]) -> u16 {
    let mut crc = 0xffffu16;
    for byte in bytes {
        crc ^= u16::from(*byte);
        for _ in 0..8 {
            crc = if crc & 1 != 0 {
                (crc >> 1) ^ 0xa001
            } else {
                crc >> 1
            };
        }
    }
    crc
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BulkFrame {
    pub action: u8,
    pub payload: Vec<u8>,
}

pub fn bulk_command(action: u8, payload: &[u8]) -> Result<Vec<u8>, DecodeError> {
    if payload.len() > MAX_BULK_PAYLOAD {
        return Err(DecodeError::TooLarge);
    }
    let mut frame = Vec::with_capacity(6 + payload.len());
    frame.extend_from_slice(&[0xbc, action]);
    frame.extend_from_slice(&(payload.len() as u16).to_le_bytes());
    frame.extend_from_slice(&crc16_modbus(payload).to_le_bytes());
    frame.extend_from_slice(payload);
    Ok(frame)
}

/// Bounded streaming decoder, handling split headers, split payloads and coalesced frames.
/// A malformed frame invalidates the remaining stream; caller resets/retries its query.
#[derive(Debug, Default)]
pub struct BulkAssembler {
    buffer: Vec<u8>,
}
impl BulkAssembler {
    pub fn reset(&mut self) {
        self.buffer.clear();
    }
    pub fn buffered_len(&self) -> usize {
        self.buffer.len()
    }
    pub fn push(&mut self, bytes: &[u8]) -> Result<Vec<BulkFrame>, DecodeError> {
        let result = self.push_inner(bytes);
        if result.is_err() {
            self.reset();
        }
        result
    }
    fn push_inner(&mut self, bytes: &[u8]) -> Result<Vec<BulkFrame>, DecodeError> {
        if bytes.len() > MAX_NOTIFICATION_BYTES {
            return Err(DecodeError::TooLarge);
        }
        // Process one byte stream at a time so multiple valid frames do not require
        // a combined allocation proportional to the entire notification.
        let mut frames = Vec::new();
        let mut remaining = bytes;
        loop {
            if self.buffer.len() < 6 {
                let amount = (6 - self.buffer.len()).min(remaining.len());
                self.buffer.extend_from_slice(&remaining[..amount]);
                remaining = &remaining[amount..];
                if self.buffer.first().is_some_and(|byte| *byte != 0xbc) {
                    return Err(DecodeError::Header);
                }
                if self.buffer.len() < 6 {
                    break;
                }
            }
            let len = usize::from(u16::from_le_bytes([self.buffer[2], self.buffer[3]]));
            if len > MAX_BULK_PAYLOAD {
                return Err(DecodeError::TooLarge);
            }
            let required = 6 + len;
            let amount = (required - self.buffer.len()).min(remaining.len());
            self.buffer.extend_from_slice(&remaining[..amount]);
            remaining = &remaining[amount..];
            if self.buffer.len() < required {
                break;
            }
            let expected = u16::from_le_bytes([self.buffer[4], self.buffer[5]]);
            if crc16_modbus(&self.buffer[6..]) != expected {
                return Err(DecodeError::Crc);
            }
            frames.push(BulkFrame {
                action: self.buffer[1],
                payload: self.buffer[6..].to_vec(),
            });
            self.buffer.clear();
            if remaining.is_empty() {
                break;
            }
        }
        Ok(frames)
    }
}

pub fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push(DIGITS[(byte >> 4) as usize] as char);
        output.push(DIGITS[(byte & 15) as usize] as char);
    }
    output
}

pub fn unhex(input: &str, max_bytes: usize) -> Result<Vec<u8>, DecodeError> {
    if !input.len().is_multiple_of(2) {
        return Err(DecodeError::InvalidHex);
    }
    if input.len() / 2 > max_bytes {
        return Err(DecodeError::TooLarge);
    }
    fn nibble(byte: u8) -> Option<u8> {
        match byte {
            b'0'..=b'9' => Some(byte - b'0'),
            b'a'..=b'f' => Some(byte - b'a' + 10),
            b'A'..=b'F' => Some(byte - b'A' + 10),
            _ => None,
        }
    }
    input
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            Ok(nibble(pair[0]).ok_or(DecodeError::InvalidHex)? * 16
                + nibble(pair[1]).ok_or(DecodeError::InvalidHex)?)
        })
        .collect()
}
