#![forbid(unsafe_code)]

pub mod codec;
pub mod control;
pub mod declared_capabilities;
pub mod device;
pub mod device_events;
pub mod history;
pub mod legacy_hr;
pub mod ota;
pub mod session;
pub mod sport_history;
pub mod sports;

pub const API_VERSION: u32 = 1;
pub const CORE_SERVICE_UUID: &str = "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e";
pub const CORE_WRITE_UUID: &str = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
pub const CORE_NOTIFY_UUID: &str = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";
pub const BULK_SERVICE_UUID: &str = "de5bf728-d711-4e47-af26-65e3012a5dc7";
pub const BULK_WRITE_UUID: &str = "de5bf72a-d711-4e47-af26-65e3012a5dc7";
pub const BULK_NOTIFY_UUID: &str = "de5bf729-d711-4e47-af26-65e3012a5dc7";
