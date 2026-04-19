#!/usr/bin/env bash
# llama.cpp を Android (arm64-v8a) 向けに cross-compile する。
# 目的: arm64-v8a の llama-server + 必要な .so を app/src/main/jniLibs/arm64-v8a/
#       に配置する。llama-server 実行ファイルは libllama-server.so という名前で
#       jniLibs に入れる (W^X の都合で nativeLibraryDir 配下しか exec できないため)。
#
# 使い方 (Git Bash / MSYS2):
#   ./scripts/build_llama_android.sh
#
# 環境変数:
#   ANDROID_NDK  (default: Windows SDK path)
#   ANDROID_SDK  (default: Windows SDK path)
#   ABI          (default: arm64-v8a)
#   API          (default: 28)
#   JOBS         (default: nproc)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/llama.cpp"

ABI="${ABI:-arm64-v8a}"
API="${API:-28}"
ANDROID_SDK="${ANDROID_SDK:-/c/Users/user/AppData/Local/Android/Sdk}"
ANDROID_NDK="${ANDROID_NDK:-$ANDROID_SDK/ndk/28.2.13676358}"
CMAKE_BIN="$ANDROID_SDK/cmake/3.31.6/bin/cmake.exe"
NINJA_BIN="$ANDROID_SDK/cmake/3.31.6/bin/ninja.exe"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

BUILD="$SRC/build-android-$ABI"
OUT_LIB_DIR="$ROOT/app/src/main/jniLibs/$ABI"

echo "==> src=$SRC"
echo "==> build=$BUILD"
echo "==> sdk=$ANDROID_SDK"
echo "==> ndk=$ANDROID_NDK"
echo "==> abi=$ABI api=$API jobs=$JOBS"

[[ -x "$CMAKE_BIN" ]] || { echo "cmake not found: $CMAKE_BIN"; exit 1; }
[[ -x "$NINJA_BIN" ]] || { echo "ninja not found: $NINJA_BIN"; exit 1; }
[[ -d "$ANDROID_NDK" ]] || { echo "NDK not found: $ANDROID_NDK"; exit 1; }

mkdir -p "$BUILD" "$OUT_BIN_DIR" "$OUT_LIB_DIR"

# ------------------------------------------------------------------
# Configure
# ------------------------------------------------------------------
"$CMAKE_BIN" -S "$SRC" -B "$BUILD" \
    -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_BACKEND_DL=ON \
    -DGGML_CPU_ALL_VARIANTS=ON \
    -DGGML_CPU_KLEIDIAI=ON \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_BUILD_COMMON=ON \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_TOOLS=ON \
    -DLLAMA_BUILD_SERVER=ON

# ------------------------------------------------------------------
# Build
# ------------------------------------------------------------------
"$CMAKE_BIN" --build "$BUILD" --target llama-server -j "$JOBS"

# ------------------------------------------------------------------
# Collect artifacts
# ------------------------------------------------------------------
echo "==> collecting artifacts"

# llama-server 本体: lib*.so の命名で jniLibs に配置 (APK install 時に抽出され
# nativeLibraryDir/libllama-server.so として exec 可能になる)。
SERVER_BIN="$(find "$BUILD" -maxdepth 4 -type f -name 'llama-server' | head -1)"
[[ -n "$SERVER_BIN" ]] || { echo "llama-server binary not found"; exit 1; }
cp -v "$SERVER_BIN" "$OUT_LIB_DIR/libllama-server.so"

# 共有ライブラリ群
# Android は jniLibs/<abi>/ に置けば System.loadLibrary で解決できる。
# llama-server は dlopen(RTLD_NOW, "libXXX.so") の形で backend をロードするので、
# native lib パスに通しておけばランタイムで拾える。
shopt -s nullglob
for so in "$BUILD"/bin/*.so "$BUILD"/src/*.so "$BUILD"/ggml/src/*.so "$BUILD"/ggml/src/**/*.so; do
    [[ -f "$so" ]] && cp -v "$so" "$OUT_LIB_DIR/"
done

# libc++_shared.so (NDK が必須で要求する場合あり)
LIBCXX="$ANDROID_NDK/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if [[ -f "$LIBCXX" ]]; then
    cp -v "$LIBCXX" "$OUT_LIB_DIR/"
fi

echo "==> done"
echo "jniLibs    : $OUT_LIB_DIR/"
ls -lh "$OUT_LIB_DIR/" | head -20
