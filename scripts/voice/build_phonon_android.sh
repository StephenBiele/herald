#!/usr/bin/env bash
set -euo pipefail
herald_root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 29.0.14206865}"
checkout="$herald_root/.phonon-runtime"
if [[ ! -e "$checkout/.git" ]]; then
  git clone https://github.com/gradium-ai/xn-ptts.git "$checkout"
fi
git -C "$checkout" checkout --detach 49b9033e44ab0fbb935973eca3213757644d1bbb
rustup target add aarch64-linux-android
(
  cd "$checkout"
  unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C target-cpu=generic -C strip=symbols -C link-arg=-Wl,-z,max-page-size=16384'
  cargo ndk -t arm64-v8a -o android/jniLibs build --release --locked -p ptts-ffi
)
install -d "$herald_root/androidApp/src/main/jniLibs/arm64-v8a"
install -m 644 "$checkout/android/jniLibs/arm64-v8a/libptts_ffi.so" "$herald_root/androidApp/src/main/jniLibs/arm64-v8a/libptts_ffi.so"
python3 "$herald_root/scripts/voice/prepare_phonon_pack.py"
