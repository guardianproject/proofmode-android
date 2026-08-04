#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="${SCRIPT_DIR}/../rust-cid-lib"
OUT_DIR="${SCRIPT_DIR}/../src/main/jniLibs"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  for candidate in \
    "${ANDROID_HOME:-}/ndk"/* \
    "${HOME}/Android/Sdk/ndk"/*; do
    if [[ -d "${candidate}" && -f "${candidate}/source.properties" ]]; then
      ANDROID_NDK_HOME="${candidate}"
      break
    fi
  done
fi
if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "${ANDROID_NDK_HOME}" ]]; then
  echo "ANDROID_NDK_HOME must be set to an Android NDK install (e.g. ~/Android/Sdk/ndk/<version>)" >&2
  exit 1
fi
export ANDROID_NDK_HOME

JAVA_SRC="${SCRIPT_DIR}/../src/main/java"
UNIFFI_OUT_DIR="${JAVA_SRC}"

cd "${CRATE_DIR}"
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 \
  -o "${OUT_DIR}" build --release

echo "Built native libs:"
find "${OUT_DIR}" -name 'librust_cid_lib.so' -exec sha256sum {} \;

LIB_SO="${OUT_DIR}/arm64-v8a/librust_cid_lib.so"
if [[ ! -f "${LIB_SO}" ]]; then
  echo "Expected ${LIB_SO} after cargo-ndk build" >&2
  exit 1
fi

mkdir -p "${UNIFFI_OUT_DIR}"
cargo run --features=uniffi/cli --bin uniffi-bindgen \
  generate --library "${LIB_SO}" \
  --language kotlin \
  --out-dir "${UNIFFI_OUT_DIR}" \
  --no-format

echo "Generated UniFFI Kotlin glue:"
find "${UNIFFI_OUT_DIR}/org/witness/proofmode/cid/uniffi" -name '*.kt' -exec sha256sum {} \;
