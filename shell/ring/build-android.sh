#!/bin/sh
set -eu
ring_ndk=""
ring_output=""
ring_targets=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --ndk) ring_ndk="$2"; shift 2 ;;
        --output) ring_output="$2"; shift 2 ;;
        --target)
            case "$2" in
                aarch64-linux-android|x86_64-linux-android) ring_targets="$ring_targets $2" ;;
                *) echo "Unsupported Android target: $2" >&2; exit 2 ;;
            esac
            shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
if [ -z "$ring_ndk" ] || [ -z "$ring_output" ]; then
    echo "Usage: build-android.sh --ndk NDK_PATH --output OUTPUT_PATH [--target RUST_TARGET]..." >&2
    exit 2
fi
# Build both supported Android ABIs unless the caller selects a target explicitly.
ring_targets=${ring_targets:-"aarch64-linux-android x86_64-linux-android"}
ring_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case "$(uname -s)" in
    Darwin) ring_host=darwin-x86_64 ;;
    Linux) ring_host=linux-x86_64 ;;
    *) echo "Unsupported host" >&2; exit 2 ;;
esac
ring_tools="$ring_ndk/toolchains/llvm/prebuilt/$ring_host/bin"
if [ ! -x "$ring_tools/aarch64-linux-android26-clang" ]; then
    echo "NDK toolchain is missing: $ring_tools" >&2; exit 2
fi
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ring_tools/aarch64-linux-android26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$ring_tools/x86_64-linux-android26-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
cd "$ring_root"
# Target values come only from the checked enum above; split the selected list.
rustup target add --toolchain 1.97.1 $ring_targets
for ring_target in $ring_targets; do
    cargo +1.97.1 build --locked --release -p herzring-ffi --target "$ring_target"
    case "$ring_target" in
        aarch64-linux-android) ring_abi=arm64-v8a ;;
        x86_64-linux-android) ring_abi=x86_64 ;;
    esac
    mkdir -p "$ring_output/$ring_abi"
    cp "${CARGO_TARGET_DIR:-target}/$ring_target/release/libherzring.so" "$ring_output/$ring_abi/libherzring.so"
done
