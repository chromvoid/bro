//! ABI 1 uses integer registry handles rather than exposing Rust pointers.
//! Byte buffers returned to C are owned by the library until their token is freed.
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass},
    sys::{jbyteArray, jint, jlong},
};
use qring_r20::session::{Config, Input, Session};
use std::{
    collections::HashMap,
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
};

const MAX_INPUT: usize = 512 * 1024;
const MAX_OUTPUT: usize = 8 * 1024 * 1024;
const MAX_OUTPUT_TOTAL: usize = 16 * 1024 * 1024;
const MAX_SESSIONS: usize = 64;
const MAX_BUFFERS: usize = 64;
const OK: i32 = 0;
const INVALID: i32 = 1;
const FAILED: i32 = 2;
const PANIC: i32 = 3;
static NEXT_ID: AtomicU64 = AtomicU64::new(1);
static SESSIONS: OnceLock<Mutex<HashMap<u64, Session>>> = OnceLock::new();
static BUFFERS: OnceLock<Mutex<HashMap<u64, Box<[u8]>>>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<u64, Session>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn buffers() -> &'static Mutex<HashMap<u64, Box<[u8]>>> {
    BUFFERS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn next_id() -> Result<u64, String> {
    NEXT_ID
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| {
            id.checked_add(1).filter(|n| *n <= i64::MAX as u64)
        })
        .map_err(|_| "Handle registry exhausted".into())
}
fn error_json(code: &str, message: &str) -> Vec<u8> {
    serde_json::to_vec(&serde_json::json!([{ "type": "error", "code": code, "message": message }]))
        .unwrap_or_else(|_| b"[]".to_vec())
}
fn create_json(bytes: &[u8]) -> Result<u64, String> {
    if bytes.len() > MAX_INPUT {
        return Err("Configuration exceeds input limit".into());
    }
    let config: Config = serde_json::from_slice(bytes).map_err(|e| e.to_string())?;
    let session = Session::new(config)?;
    let mut registry = sessions().lock().unwrap_or_else(|e| e.into_inner());
    if registry.len() >= MAX_SESSIONS {
        return Err("Session registry is full".into());
    }
    let id = next_id()?;
    registry.insert(id, session);
    Ok(id)
}
fn destroy_handle(handle: u64) {
    sessions()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove(&handle);
}
fn dispatch_json(handle: u64, bytes: &[u8], now_ms: u64) -> Vec<u8> {
    contain_dispatch(handle, || {
        if bytes.len() > MAX_INPUT {
            return error_json("input_too_large", "Input exceeds byte limit");
        }
        let input: Input = match serde_json::from_slice(bytes) {
            Ok(input) => input,
            Err(e) => return error_json("invalid_input", &e.to_string()),
        };
        let mut registry = sessions().lock().unwrap_or_else(|e| e.into_inner());
        let Some(session) = registry.get_mut(&handle) else {
            return error_json("invalid_handle", "Session handle is closed or invalid");
        };
        match serde_json::to_vec(&session.dispatch(input, now_ms)) {
            Ok(output) if output.len() <= MAX_OUTPUT => output,
            Ok(_) => {
                registry.remove(&handle);
                error_json(
                    "output_too_large",
                    "Session invalidated because effects exceed output limit",
                )
            }
            Err(e) => {
                registry.remove(&handle);
                error_json("serialization_failed", &e.to_string())
            }
        }
    })
}
fn contain_dispatch(handle: u64, action: impl FnOnce() -> Vec<u8>) -> Vec<u8> {
    match catch_unwind(AssertUnwindSafe(action)) {
        Ok(output) => output,
        Err(_) => {
            destroy_handle(handle);
            error_json(
                "native_panic",
                "Session invalidated after an internal panic",
            )
        }
    }
}

#[repr(C)]
pub struct RingBuffer {
    pub data: *const u8,
    pub len: usize,
    pub token: u64,
}
impl Default for RingBuffer {
    fn default() -> Self {
        Self {
            data: std::ptr::null(),
            len: 0,
            token: 0,
        }
    }
}
fn owned_buffer(bytes: Vec<u8>) -> Result<RingBuffer, ()> {
    if bytes.len() > MAX_OUTPUT {
        return Err(());
    }
    let mut registry = buffers().lock().unwrap_or_else(|e| e.into_inner());
    let total: usize = registry.values().map(|buffer| buffer.len()).sum();
    if registry.len() >= MAX_BUFFERS || total.saturating_add(bytes.len()) > MAX_OUTPUT_TOTAL {
        return Err(());
    }
    let token = next_id().map_err(|_| ())?;
    let owned = bytes.into_boxed_slice();
    let result = RingBuffer {
        data: owned.as_ptr(),
        len: owned.len(),
        token,
    };
    registry.insert(token, owned);
    Ok(result)
}

/// # Safety
/// `ptr` must refer to `len` readable bytes for the duration of this call.
/// NULL is allowed only for zero length. Arbitrary memory addresses cannot be
/// validated by a C ABI; integer session and buffer handles are validated.
unsafe fn input_slice<'a>(ptr: *const u8, len: usize) -> Result<&'a [u8], ()> {
    if len > MAX_INPUT || (ptr.is_null() && len != 0) {
        return Err(());
    }
    if len == 0 {
        return Ok(&[]);
    }
    Ok(unsafe { std::slice::from_raw_parts(ptr, len) })
}

#[unsafe(no_mangle)]
pub extern "C" fn herzring_api_version() -> u32 {
    qring_r20::API_VERSION
}

/// # Safety
/// `input` obeys the readable-byte contract and `out_handle` is a writable,
/// correctly aligned u64. No Rust object pointer is accepted or returned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn herzring_create(
    input: *const u8,
    len: usize,
    out_handle: *mut u64,
) -> i32 {
    if out_handle.is_null() || !out_handle.is_aligned() {
        return INVALID;
    }
    unsafe {
        out_handle.write(0);
    }
    let bytes = match unsafe { input_slice(input, len) } {
        Ok(bytes) => bytes,
        Err(_) => return INVALID,
    };
    match catch_unwind(AssertUnwindSafe(|| create_json(bytes))) {
        Ok(Ok(handle)) => {
            unsafe {
                out_handle.write(handle);
            }
            OK
        }
        Ok(Err(_)) => FAILED,
        Err(_) => PANIC,
    }
}

/// # Safety
/// `input` obeys the readable-byte contract; `out` points to one writable,
/// correctly aligned RingBuffer. Free its token exactly once after copying.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn herzring_dispatch(
    handle: u64,
    input: *const u8,
    len: usize,
    now_ms: u64,
    out: *mut RingBuffer,
) -> i32 {
    if out.is_null() || !out.is_aligned() {
        return INVALID;
    }
    unsafe {
        out.write(RingBuffer::default());
    }
    let bytes = match unsafe { input_slice(input, len) } {
        Ok(bytes) => bytes,
        Err(_) => return INVALID,
    };
    match catch_unwind(AssertUnwindSafe(|| {
        owned_buffer(dispatch_json(handle, bytes, now_ms))
    })) {
        Ok(Ok(buffer)) => {
            unsafe {
                out.write(buffer);
            }
            OK
        }
        Ok(Err(_)) => FAILED,
        Err(_) => {
            destroy_handle(handle);
            PANIC
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn herzring_destroy(handle: u64) -> i32 {
    match catch_unwind(AssertUnwindSafe(|| {
        if sessions()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&handle)
            .is_some()
        {
            OK
        } else {
            INVALID
        }
    })) {
        Ok(status) => status,
        Err(_) => PANIC,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn herzring_buffer_free(token: u64) -> i32 {
    match catch_unwind(AssertUnwindSafe(|| {
        if buffers()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&token)
            .is_some()
        {
            OK
        } else {
            INVALID
        }
    })) {
        Ok(status) => status,
        Err(_) => PANIC,
    }
}

fn read_java_bytes(env: &mut JNIEnv<'_>, input: &JByteArray<'_>) -> Result<Vec<u8>, String> {
    let len = env.get_array_length(input).map_err(|e| e.to_string())?;
    if len < 0 || len as usize > MAX_INPUT {
        return Err("ByteArray exceeds input limit".into());
    }
    env.convert_byte_array(input).map_err(|e| e.to_string())
}

/// Stateless typed JNI codec. It cannot create a qualification or a BLE effect.
#[unsafe(no_mangle)]
pub extern "system" fn Java_assistant_mobile_platform_ring_NativeRingOta_encode(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input: JByteArray<'_>,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let output = match read_java_bytes(&mut env, &input) {
            Ok(bytes) => match serde_json::from_slice::<qring_r20::ota::OtaFrameRequest>(&bytes) {
                Ok(request) => match request.encode() {
                    Ok(bytes_hex) => {
                        serde_json::to_vec(&serde_json::json!({"bytes_hex": bytes_hex}))
                            .unwrap_or_else(|_| {
                                error_json("serialization_failed", "OTA frame serialization failed")
                            })
                    }
                    Err(reason) => error_json(reason, "Invalid bounded OTA frame"),
                },
                Err(error) => error_json("invalid_input", &error.to_string()),
            },
            Err(message) => error_json("invalid_input", &message),
        };
        env.byte_array_from_slice(&output)
            .map(|array| array.into_raw())
    }));
    match result {
        Ok(Ok(array)) => array,
        Ok(Err(_)) => std::ptr::null_mut(),
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "Native OTA codec panicked",
            );
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_assistant_mobile_platform_ring_NativeRing_apiVersion(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    qring_r20::API_VERSION as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_assistant_mobile_platform_ring_NativeRing_create(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input: JByteArray<'_>,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        read_java_bytes(&mut env, &input).and_then(|bytes| create_json(&bytes))
    }));
    match result {
        Ok(Ok(handle)) => handle as jlong,
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            0
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "Native session creation panicked",
            );
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_assistant_mobile_platform_ring_NativeRing_dispatch(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    input: JByteArray<'_>,
    now_ms: jlong,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let output = if handle <= 0 || now_ms < 0 {
            error_json(
                "invalid_argument",
                "Handle and monotonic time must be nonnegative",
            )
        } else {
            match read_java_bytes(&mut env, &input) {
                Ok(bytes) => dispatch_json(handle as u64, &bytes, now_ms as u64),
                Err(message) => error_json("invalid_input", &message),
            }
        };
        env.byte_array_from_slice(&output)
            .map(|array| array.into_raw())
    }));
    match result {
        Ok(Ok(array)) => array,
        Ok(Err(_)) => std::ptr::null_mut(), // JNI already has a pending allocation exception.
        Err(_) => {
            if handle > 0 {
                destroy_handle(handle as u64);
            }
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "Native dispatch panicked",
            );
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_assistant_mobile_platform_ring_NativeRing_destroy(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle > 0 {
        let _ = catch_unwind(AssertUnwindSafe(|| destroy_handle(handle as u64)));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn config() -> Vec<u8> {
        serde_json::to_vec(&serde_json::json!({"generation":1,"timezone":"Europe/Moscow","local_date":"2026-10-05","clock":{"year":2026,"month":10,"day":5,"hour":12,"minute":30,"second":0},"android":{"sdk":36,"model":"Synthetic"},"history_days":1})).unwrap()
    }
    #[test]
    fn opaque_handle_is_invalid_after_close() {
        let handle = create_json(&config()).unwrap();
        assert_eq!(
            dispatch_json(handle, br#"{"type":"connected","generation":1}"#, 0),
            b"[]"
        );
        assert_eq!(herzring_destroy(handle), OK);
        assert_eq!(herzring_destroy(handle), INVALID);
        assert!(
            String::from_utf8(dispatch_json(
                handle,
                br#"{"type":"connected","generation":1}"#,
                0
            ))
            .unwrap()
            .contains("invalid_handle")
        );
    }
    #[test]
    fn c_buffer_ownership_and_validation() {
        let config = config();
        let mut handle = 0;
        assert_eq!(
            unsafe { herzring_create(config.as_ptr(), config.len(), &mut handle) },
            OK
        );
        let input = br#"{"type":"sync","generation":1}"#;
        let mut buffer = RingBuffer::default();
        assert_eq!(
            unsafe { herzring_dispatch(handle, input.as_ptr(), input.len(), 0, &mut buffer) },
            OK
        );
        let copied = unsafe { std::slice::from_raw_parts(buffer.data, buffer.len) }.to_vec();
        assert!(String::from_utf8(copied).unwrap().contains("disconnected"));
        assert_eq!(herzring_buffer_free(buffer.token), OK);
        assert_eq!(herzring_buffer_free(buffer.token), INVALID);
        assert_eq!(
            unsafe { herzring_dispatch(handle, std::ptr::null(), 1, 0, &mut buffer) },
            INVALID
        );
        assert_eq!(herzring_destroy(handle), OK);
    }
    #[test]
    fn malformed_json_and_oversized_inputs_are_errors() {
        assert!(create_json(b"{}").is_err());
        let handle = create_json(&config()).unwrap();
        assert!(
            String::from_utf8(dispatch_json(handle, b"[", 0))
                .unwrap()
                .contains("invalid_input")
        );
        assert!(
            String::from_utf8(dispatch_json(handle, &vec![0; MAX_INPUT + 1], 0))
                .unwrap()
                .contains("input_too_large")
        );
        destroy_handle(handle);
    }
    #[test]
    fn contained_panic_invalidates_its_session() {
        let handle = create_json(&config()).unwrap();
        let output = contain_dispatch(handle, || panic!("synthetic internal fault"));
        assert!(String::from_utf8(output).unwrap().contains("native_panic"));
        assert_eq!(herzring_destroy(handle), INVALID);
    }
}
