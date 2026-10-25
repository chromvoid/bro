//! Vendor capability declarations are telemetry, never authority for commands.
//!
//! The field interpretation follows Soul 1.0.1214's SetTimeRsp and
//! DeviceSupportFunctionRsp and the app's retained hj4 extension (Lkm4 in 1.0.1230).
//! Preserve every payload byte, including undocumented bits,
//! so a later decoder can reinterpret an immutable received declaration.
use crate::codec::hex;
use serde::Serialize;
use std::collections::BTreeMap;

pub const DECODER_VERSION: &str = "soul-declared-capabilities-v1";
pub const EVIDENCE_REF: &str = "soul-1.0.1214-declared-capabilities-v1";

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Declaration {
    pub opcode: u8,
    pub payload_hex: String,
    pub flags: BTreeMap<&'static str, bool>,
    pub source_values: BTreeMap<&'static str, i16>,
    pub decoder_version: &'static str,
    pub evidence_ref: &'static str,
    pub validation_status: &'static str,
}

/// Call only with a checksum-validated, exactly 16-byte core response and a
/// matching current query. No declaration modifies a qualification registry.
pub fn parse(opcode: u8, payload: &[u8; 14]) -> Option<Declaration> {
    let mut flags = BTreeMap::new();
    let mut source_values = BTreeMap::new();
    match opcode {
        0x01 => {
            for (key, index) in [
                ("temperature", 0),
                ("plate", 1),
                ("menstruation", 2),
                ("new_sleep", 8),
                ("contacts", 10),
            ] {
                flags.insert(key, payload[index] == 1);
            }
            for (key, index, mask) in [
                ("wallpaper", 3, 1),
                ("spo2", 3, 2),
                ("blood_pressure", 3, 4),
                ("feature", 3, 8),
                ("one_key", 3, 16),
                ("weather", 3, 32),
                ("manual_heart", 11, 1),
                ("e_card", 11, 2),
                ("location", 11, 4),
                ("music", 11, 16),
                ("rtk_mc", 11, 32),
                ("ebook", 11, 64),
                ("hrv", 13, 32),
            ] {
                flags.insert(key, payload[index] & mask != 0);
            }
            // SetTimeRsp intentionally uses the inverse sense for this bit.
            flags.insert("wechat", payload[3] & 64 == 0);
            let contacts_raw = i16::from(payload[12] as i8);
            source_values.insert("max_watch_faces_raw", i16::from(payload[9] as i8));
            source_values.insert("contacts_raw", contacts_raw);
            source_values.insert(
                "contacts_sdk",
                if contacts_raw == 0 {
                    20
                } else {
                    contacts_raw * 10
                },
            );
        }
        0x3c => {
            for (key, index, mask) in [
                ("touch", 1, 1),
                ("moslin", 1, 2),
                ("app_revision", 1, 4),
                ("ble_pair", 1, 8),
                ("gesture", 1, 128),
                ("music", 2, 1),
                ("video", 2, 2),
                ("ebook", 2, 4),
                ("camera", 2, 8),
                ("phone_call", 2, 16),
                ("game", 2, 32),
                ("notifications", 3, 32),
                ("lover", 6, 8),
                ("prayer", 6, 16),
                ("interval_spo2", 7, 4),
                ("interval_temperature", 7, 128),
                ("ecg", 9, 2),
            ] {
                flags.insert(key, payload[index] & mask != 0);
            }
        }
        _ => return None,
    }
    Some(Declaration {
        opcode,
        payload_hex: hex(payload),
        flags,
        source_values,
        decoder_version: DECODER_VERSION,
        evidence_ref: EVIDENCE_REF,
        validation_status: "declared_only",
    })
}
