use qring_r20::codec::*;

#[test]
fn battery_is_exact_and_high_opcode_is_preserved() {
    let request = core_command(3, &[]).unwrap();
    assert_eq!(hex(&request), "03000000000000000000000000000003");
    let response = core_command(0x83, &[77, 1]).unwrap();
    let decoded = decode_core(&response).unwrap();
    assert_eq!(decoded.opcode, 0x83);
    assert_eq!(&decoded.payload[..2], &[77, 1]);
}

#[test]
fn rejects_length_checksum_and_bad_hex() {
    assert_eq!(decode_core(&[0; 15]), Err(DecodeError::Length));
    let mut frame = core_command(3, &[]).unwrap();
    frame[2] ^= 1;
    assert_eq!(decode_core(&frame), Err(DecodeError::Checksum));
    assert!(unhex("0g", 16).is_err());
    assert!(unhex("abc", 16).is_err());
    assert!(unhex("0000", 1).is_err());
}

#[test]
fn crc_reference_and_r20_sleep_request() {
    assert_eq!(crc16_modbus(b"123456789"), 0x4b37);
    assert_eq!(
        hex(&bulk_command(0x27, &[0xff, 1]).unwrap()),
        "bc2702008180ff01"
    );
}

#[test]
fn every_fragment_boundary_and_leftovers() {
    let first = bulk_command(0x75, &[0, 5, 1, 0, 72, 71]).unwrap();
    let second = bulk_command(0x27, &[0]).unwrap();
    for split in 0..=first.len() {
        let mut parser = BulkAssembler::default();
        let mut decoded = parser.push(&first[..split]).unwrap();
        let mut tail = first[split..].to_vec();
        tail.extend_from_slice(&second[..3]);
        decoded.extend(parser.push(&tail).unwrap());
        assert_eq!(
            decoded,
            vec![BulkFrame {
                action: 0x75,
                payload: vec![0, 5, 1, 0, 72, 71]
            }]
        );
        assert_eq!(parser.buffered_len(), 3);
        assert_eq!(
            parser.push(&second[3..]).unwrap(),
            vec![BulkFrame {
                action: 0x27,
                payload: vec![0]
            }]
        );
        assert_eq!(parser.buffered_len(), 0);
    }
}

#[test]
fn corrupt_or_oversized_stream_resets() {
    let mut parser = BulkAssembler::default();
    let mut packet = bulk_command(0x75, &[0, 5, 1, 0, 72]).unwrap();
    packet[6] ^= 1;
    assert_eq!(parser.push(&packet), Err(DecodeError::Crc));
    assert_eq!(parser.buffered_len(), 0);
    assert_eq!(
        parser.push(&[0xbc, 0x75, 0xff, 0xff, 0, 0]),
        Err(DecodeError::TooLarge)
    );
    assert_eq!(parser.buffered_len(), 0);
    assert_eq!(parser.push(&[0]), Err(DecodeError::Header));
}

#[test]
fn zero_payload_and_full_bounded_payload() {
    let frame = bulk_command(1, &[]).unwrap();
    assert_eq!(
        BulkAssembler::default().push(&frame).unwrap()[0].payload,
        Vec::<u8>::new()
    );
    let payload = vec![5; MAX_BULK_PAYLOAD];
    let frame = bulk_command(2, &payload).unwrap();
    assert_eq!(
        BulkAssembler::default().push(&frame).unwrap()[0].payload,
        payload
    );
    assert_eq!(
        bulk_command(1, &vec![0; MAX_BULK_PAYLOAD + 1]),
        Err(DecodeError::TooLarge)
    );
}
