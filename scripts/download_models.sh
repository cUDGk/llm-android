#!/usr/bin/env bash
# Qwen3 GGUF を app/src/main/assets/models/ にダウンロード。
# APK に同梱してアプリ初回起動時に filesDir/models/ へ展開する想定。
#
# 使い方:
#   ./scripts/download_models.sh
#
# すでにダウンロード済みならスキップ。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/models"
mkdir -p "$DEST"

# Unsloth の GGUF ミラー (公式 Qwen GGUF リポは現在 gated)
# size は HEAD で取った Content-Length (bytes)
declare -a MODELS=(
    "Qwen3-4B-Instruct-2507-Q4_K_M.gguf|https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf|2497281120"
    "Qwen3-0.6B-Q4_K_M.gguf|https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf|396705472"
)

for entry in "${MODELS[@]}"; do
    IFS='|' read -r name url expected_size <<<"$entry"
    out="$DEST/$name"
    if [[ -f "$out" ]]; then
        actual=$(stat -c '%s' "$out" 2>/dev/null || stat -f '%z' "$out" 2>/dev/null || echo 0)
        if [[ "$actual" == "$expected_size" ]]; then
            echo "[skip] $name (already $actual bytes)"
            continue
        fi
        echo "[redo] $name (size mismatch: $actual != $expected_size)"
        rm -f "$out"
    fi
    echo "[get ] $name ($((expected_size / 1024 / 1024)) MB)"
    curl -L --fail --retry 3 --retry-delay 5 -o "$out.part" "$url"
    mv "$out.part" "$out"
done

echo
echo "==> models:"
ls -lh "$DEST"
