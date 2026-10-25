use qring_r20::{
    codec::{decode_core, unhex},
    declared_capabilities::{DECODER_VERSION, EVIDENCE_REF, parse},
};

#[test]
fn captured_set_time_declaration_preserves_all_bytes_and_signed_source_fields() {
    let frame = decode_core(&unhex("01010000020000000001002000003055", 16).unwrap()).unwrap();
    let declaration = parse(frame.opcode, &frame.payload).unwrap();
    assert_eq!(declaration.opcode, 0x01);
    assert_eq!(declaration.payload_hex, "0100000200000000010020000030");
    assert_eq!(declaration.flags.len(), 19);
    for name in ["temperature", "spo2", "new_sleep", "wechat", "hrv"] {
        assert!(declaration.flags[name], "{name}");
    }
    for name in ["plate", "contacts", "manual_heart", "blood_pressure"] {
        assert!(!declaration.flags[name], "{name}");
    }
    assert_eq!(declaration.source_values["max_watch_faces_raw"], 0);
    assert_eq!(declaration.source_values["contacts_raw"], 0);
    assert_eq!(declaration.source_values["contacts_sdk"], 20);
    assert_eq!(declaration.decoder_version, DECODER_VERSION);
    assert_eq!(declaration.evidence_ref, EVIDENCE_REF);
    assert_eq!(declaration.validation_status, "declared_only");
}

#[test]
fn captured_device_support_declaration_retains_unknown_bits_without_qualifying_them() {
    let frame = decode_core(&unhex("3c00ac2730000000100000000000004f", 16).unwrap()).unwrap();
    let declaration = parse(frame.opcode, &frame.payload).unwrap();
    assert_eq!(declaration.payload_hex, "00ac273000000010000000000000");
    assert_eq!(declaration.flags.len(), 17);
    for name in [
        "app_revision",
        "ble_pair",
        "gesture",
        "music",
        "video",
        "ebook",
        "game",
        "notifications",
    ] {
        assert!(declaration.flags[name], "{name}");
    }
    for name in [
        "touch",
        "moslin",
        "camera",
        "phone_call",
        "interval_spo2",
        "interval_temperature",
        "lover",
        "prayer",
        "ecg",
    ] {
        assert!(!declaration.flags[name], "{name}");
    }
    assert!(declaration.source_values.is_empty());
    let json = serde_json::to_value(declaration).unwrap();
    assert_eq!(json["validation_status"], "declared_only");
    assert!(json.get("writable").is_none());
    assert!(json.get("supported").is_none());
}

#[test]
fn sdk_exact_byte_flags_inverse_wechat_and_signed_counts_are_not_normalized() {
    let mut payload = [0xff; 14];
    let declaration = parse(0x01, &payload).unwrap();
    for name in [
        "temperature",
        "plate",
        "menstruation",
        "new_sleep",
        "contacts",
        "wechat",
    ] {
        assert!(!declaration.flags[name], "{name}");
    }
    assert!(declaration.flags["manual_heart"]);
    assert!(declaration.flags["hrv"]);
    assert_eq!(declaration.source_values["max_watch_faces_raw"], -1);
    assert_eq!(declaration.source_values["contacts_raw"], -1);
    assert_eq!(declaration.source_values["contacts_sdk"], -10);
    payload[9] = 128;
    payload[12] = 128;
    let declaration = parse(0x01, &payload).unwrap();
    assert_eq!(declaration.source_values["max_watch_faces_raw"], -128);
    assert_eq!(declaration.source_values["contacts_sdk"], -1280);
    payload[12] = 127;
    assert_eq!(
        parse(0x01, &payload).unwrap().source_values["contacts_sdk"],
        1270
    );
}

#[test]
fn device_support_bits_match_each_sdk_field_and_other_opcodes_have_no_declaration() {
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
        let mut payload = [0; 14];
        payload[index] = mask;
        let declaration = parse(0x3c, &payload).unwrap();
        assert!(declaration.flags[key]);
        assert_eq!(
            declaration
                .flags
                .values()
                .filter(|enabled| **enabled)
                .count(),
            1
        );
    }
    assert!(parse(0x03, &[0; 14]).is_none());
    assert!(parse(0x81, &[0; 14]).is_none());
}
